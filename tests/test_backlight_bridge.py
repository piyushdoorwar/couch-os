import importlib.util
import json
import ssl
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("backlight_bridge", SCRIPTS / "backlight-bridge.py")
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


class BacklightBridgeTest(unittest.TestCase):
    def test_certificate_token_and_explicit_power_state(self):
        import ipaddress

        with tempfile.TemporaryDirectory() as directory:
            key = Path(directory) / "key.pem"
            cert = Path(directory) / "cert.pem"
            subprocess.run(["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
                            "-noenc", "-days", "1", "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost",
                            "-keyout", key, "-out", cert], capture_output=True, check=True)
            server_tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            server_tls.load_cert_chain(cert, key)
            client_tls = ssl.create_default_context(cafile=cert)
            state, writes = False, []

            def backlight(desired):
                nonlocal state
                if desired is not None:
                    state = desired
                    writes.append(desired)
                return state

            with bridge.BacklightServer((str(ipaddress.IPv4Address(0x7F000001)), 0), server_tls, "test-token", backlight) as server:
                worker = threading.Thread(target=server.serve_forever)
                worker.start()
                try:
                    url = f"https://localhost:{server.server_port}/backlight"
                    def request(token, method="GET", data=None):
                        with urlopen(Request(url, headers={"Authorization": f"Bearer {token}"}, method=method, data=data),
                                     context=client_tls, timeout=3) as response:
                            return json.load(response)

                    with self.assertRaises(HTTPError) as unauthorized:
                        request("wrong-token", "PUT", b'{"on":true}')
                    self.assertEqual(401, unauthorized.exception.code)
                    self.assertEqual([], writes)
                    self.assertEqual({"on": False}, request("test-token"))
                    with self.assertRaises(HTTPError) as malformed:
                        request("test-token", "PUT", b'{"on":"yes"}')
                    self.assertEqual(400, malformed.exception.code)
                    self.assertEqual({"on": True}, request("test-token", "PUT", b'{"on":true}'))
                    self.assertEqual({"on": True}, request("test-token", "PUT", b'{"on":true}'))
                    self.assertEqual({"on": True}, request("test-token"))
                    self.assertEqual([True, True], writes)
                finally:
                    server.shutdown()
                    worker.join()
