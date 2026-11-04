#!/usr/bin/env python3
"""Serve the power state of one Tapo light over authenticated LAN HTTPS."""
import argparse
import asyncio
import base64
import getpass
import hmac
import ipaddress
import json
import os
import secrets
import socket
import ssl
import subprocess
import sys
import tempfile
from http.server import BaseHTTPRequestHandler, HTTPServer

from tvctl import CONFIG, SafetyError, read_config

CERT = CONFIG.with_name("local.backlight-cert.pem")
KEY = CONFIG.with_name("local.backlight-key.pem")
PORT = 8765


def lan_address(value):
    address = ipaddress.IPv4Address(value)
    first, second, *_ = address.packed
    if first == 10 or first == 172 and 16 <= second <= 31 or first == 192 and second == 168:
        return str(address)
    raise ValueError("Expected a private LAN IPv4 address")


def setup(device):
    device = lan_address(device)
    config = read_config()
    if config.get("backlight.token"):
        raise SafetyError("A backlight bridge is already configured locally.")
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        probe.connect((device, PORT))
        host = lan_address(probe.getsockname()[0])

    with tempfile.TemporaryDirectory(dir=CONFIG.parent, prefix="local.backlight-") as directory:
        cert = os.path.join(directory, "cert.pem")
        key = os.path.join(directory, "key.pem")
        subprocess.run([
            "openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
            "-noenc", "-sha256", "-days", "3650", "-subj", "/CN=CouchOS Backlight",
            "-addext", f"subjectAltName=IP:{host}", "-keyout", key, "-out", cert,
        ], check=True, capture_output=True)
        with open(cert) as stream:
            der = ssl.PEM_cert_to_DER_cert(stream.read())
        os.replace(key, KEY)
        os.replace(cert, CERT)

    updates = {
        "backlight.device": device,
        "backlight.host": host,
        "backlight.certificate": base64.b64encode(der).decode("ascii"),
        "backlight.token": secrets.token_urlsafe(32),
    }
    lines = [line for line in CONFIG.read_text().splitlines() if line.split("=", 1)[0].strip() not in updates]
    lines += ["", "# Private CouchOS backlight bridge"] + [f"{key}={value}" for key, value in updates.items()]
    fd, path = tempfile.mkstemp(dir=CONFIG.parent, prefix="local.backlight-config-")
    try:
        with os.fdopen(fd, "w") as stream:
            stream.write("\n".join(lines) + "\n")
        os.replace(path, CONFIG)
    finally:
        if os.path.exists(path):
            os.unlink(path)
    print("Prepared the bridge and private CouchOS settings.")


class BacklightHandler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        self.handle_backlight(False)

    def do_PUT(self):
        self.handle_backlight(True)

    def handle_backlight(self, change):
        if self.path != "/backlight":
            self.send_error(404)
            return
        authorization = self.headers.get("Authorization", "").encode("utf-8")
        if not hmac.compare_digest(authorization, self.server.authorization):
            self.send_error(401)
            return
        desired = None
        if change:
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 64:
                    raise ValueError
                desired = json.loads(self.rfile.read(length))["on"]
                if type(desired) is not bool:
                    raise ValueError
            except (ValueError, TypeError, KeyError):
                self.send_error(400)
                return

        try:
            state = self.server.backlight(desired)
        except Exception:
            self.send_error(503)
            return
        data = json.dumps({"on": state}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)


class BacklightServer(HTTPServer):
    def __init__(self, address, context, token, backlight):
        self.context = context
        self.authorization = f"Bearer {token}".encode("ascii")
        self.backlight = backlight
        super().__init__(address, BacklightHandler)

    def get_request(self):
        connection, address = super().get_request()
        try:
            connection.settimeout(5)
            return self.context.wrap_socket(connection, server_side=True), address
        except OSError:
            connection.close()
            raise

    def handle_error(self, *_):
        pass


def serve():
    from tapo import ApiClient

    config = read_config()
    host = lan_address(config["backlight.host"])
    device = lan_address(config["backlight.device"])
    token = config["backlight.token"]
    if len(token) < 32:
        raise ValueError("Missing bridge token")
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(CERT, KEY)
    user = input("Tapo account email (local only): ").strip()
    tapo_password = getpass.getpass("Tapo account password (hidden): ")
    if not user or not tapo_password:
        raise ValueError("Missing Tapo credentials")

    with asyncio.Runner() as loop:
        async def connect():
            return await asyncio.wait_for(ApiClient(user, tapo_password).l900(device), timeout=10)

        light = loop.run(connect())

        async def power(desired):
            if desired is True:
                await light.on()
            if desired is False:
                await light.off()
            return (await light.get_device_info()).device_on

        def backlight(desired):
            return loop.run(asyncio.wait_for(power(desired), timeout=4))

        info = loop.run(asyncio.wait_for(light.get_device_info(), timeout=4))
        if not info.model.startswith("L900"):
            raise ValueError("The configured device is not a Tapo L900")
        state = info.device_on
        with BacklightServer((host, PORT), context, token, backlight) as server:
            print(f'Backlight bridge ready. The light is {"on" if state else "off"}.')
            server.serve_forever()


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("setup").add_argument("--device", required=True)
    commands.add_parser("serve")
    args = parser.parse_args()
    try:
        if args.command == "setup":
            setup(args.device)
        else:
            serve()
    except KeyboardInterrupt:
        pass
    except ImportError:
        print("Install the pinned Tapo client in the local bridge environment first.", file=sys.stderr)
        return 1
    except Exception:
        print("Backlight bridge could not start. Check the local settings, Tapo login and device connection.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
