#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
git config core.hooksPath .githooks
if [[ ! -f config/local.properties ]]; then
    (umask 077; cp config/local.example.properties config/local.properties)
fi
chmod 600 config/local.properties
python3 scripts/public_guard.py --tracked
printf '%s\n' 'Hooks installed. Keep all device values in config/local.properties.'
