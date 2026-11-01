#!/usr/bin/env python3
"""Fail closed for private paths; report paths/reasons without echoing secrets."""
import argparse
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APPROVED_BINARY = {
    'gradle/wrapper/gradle-wrapper.jar',
    'app/src/main/res/font/poppins_semibold.ttf',
    'app/src/main/res/font/poppins_extrabold.ttf',
    'app/src/main/res/font/poppins_black.ttf',
}
PRIVATE = re.compile(r'(^|/)(captures|build|\.gradle|\.idea)(/|$)|(^|/)local\.properties$|^config/local\.(?!example\.properties$)|\.(keystore|jks|pem|p12|apk|aab|log)$|(^|/)adb-.*\.log$|(^|/)\.env')
IP = re.compile(r'(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])')
MAC = re.compile(r'(?i)\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\b')
ASSIGNMENT = re.compile(r'(?im)^\s*(?:tv\.host|adb\.(?:pair\.(?:code|port)|connect\.port)|weather\.(?:latitude|longitude)|launcher\.favourites|(?:wifi|wi-fi)[._ ]?(?:ssid|name)|(?:api[._-]?key|password|serial|mac[._-]?address))[ \t]*[=:][ \t]*([^\s#\n][^\n]*)')
SECRET = re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{50,}|AKIA[A-Z0-9]{16})\b')


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def problems(path, data, private_values=()):
    reasons = []
    if PRIVATE.search(path):
        reasons.append('private/generated path')
    if path in APPROVED_BINARY:
        return reasons
    try:
        content = data.decode('utf-8')
    except UnicodeDecodeError:
        return reasons + ['unapproved binary (artwork/captures are not public assets)']
    # Dotted dependency versions are not IP addresses; only valid IPv4 octets qualify.
    if any(all(int(n) <= 255 for n in m.group().split('.')) for m in IP.finditer(content)):
        reasons.append('possible IP address')
    if MAC.search(content) or SECRET.search(content):
        reasons.append('possible device identifier or credential')
    if ASSIGNMENT.search(content):
        reasons.append('populated personal setting')
    if any(value in content for value in private_values):
        reasons.append('value copied from private config')
    return reasons


def local_values():
    p = ROOT / 'config/local.properties'
    if not p.exists():
        return []
    # Match identifying inputs, not booleans, package policy or generic component names.
    keys = {'tv.host', 'adb.pair.code', 'weather.latitude', 'weather.longitude', 'launcher.favourites'}
    return [v.strip() for line in p.read_text().splitlines() if '=' in line
            for k, v in [line.split('=', 1)] if k.strip() in keys and len(v.strip()) >= 4]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--staged', action='store_true')
    parser.add_argument('--tracked', action='store_true')
    parser.add_argument('--history', action='store_true')
    args = parser.parse_args()
    values, failures = local_values(), []
    if args.history:
        revisions = git('rev-list', '--all').decode().splitlines()
        trees = [(rev, git('ls-tree', '-rz', '--full-tree', rev).split(b'\0')) for rev in revisions]
        seen = set()
        for rev, entries in trees:
            for entry in filter(None, entries):
                meta, path_b = entry.split(b'\t', 1)
                mode, kind, oid = meta.split()
                path = path_b.decode()
                if (path, oid) in seen:
                    continue
                seen.add((path, oid))
                reasons = ['symlink/submodule is not allowed'] if mode in (b'120000', b'160000') else problems(path, git('cat-file', 'blob', oid.decode()), values)
                if reasons:
                    failures.append((rev[:8] + ':' + path, reasons))
    else:
        paths = git('diff', '--cached', '--name-only', '--diff-filter=ACMR', '-z') if args.staged else git('ls-files', '-z')
        for path_b in filter(None, paths.split(b'\0')):
            path = path_b.decode()
            data = git('show', ':' + path)
            mode = git('ls-files', '-s', '--', path).split()[0]
            reasons = ['symlink/submodule is not allowed'] if mode in (b'120000', b'160000') else problems(path, data, values)
            if reasons:
                failures.append((path, reasons))
    if failures:
        print('STOP: possible private material. Do not commit/push; review locally first.')
        print('If real personal data must be committed, make the repository private first.')
        for path, reasons in failures:
            print(f'  {path}: {", ".join(reasons)}')
        return 1
    print('Public guard passed. Review the diff: pattern checks cannot recognise account screenshots or every secret.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
