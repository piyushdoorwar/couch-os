#!/usr/bin/env python3
"""Explicit opt-in smoke test of real package-manager commands on an emulator only."""
import importlib.util
import os
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('tvctl', ROOT / 'scripts/tvctl.py')
tv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tv)


def main():
    target = os.environ.get('ANDROID_SERIAL', '')
    tv.require(re.fullmatch(r'emulator-\d+', target), 'Set ANDROID_SERIAL to an explicitly selected TV emulator; physical devices are forbidden here.')
    device = object.__new__(tv.Device)
    device.target = target
    device.config = {}
    tv.require(device.shell('getprop', 'ro.kernel.qemu') == '1', 'Not an emulator. No package changes allowed.')
    tv.require(tv.APP in device.packages(), 'Install the Couch OS debug APK on the emulator first.')
    stock = 'com.google.android.tvlauncher'
    tv.require(stock in device.packages('-s'), 'This smoke test expects the API 30 Android TV stock launcher.')
    device.config = {
        'tv.expected.model': device.shell('getprop', 'ro.product.model'),
        'stock.launcher.package': stock,
        'fallback.package': '',
        'fallback.verified.absent': 'true',
        'verified.disable.packages': stock,
        'protected.packages': 'com.android.tv.settings',
        'verification.complete': 'true',
        'verification.stock.home.priority.checked': 'true',
        'verification.voice.ambient.checked': 'true',
    }
    tv.CAPTURES = ROOT / 'captures/emulator-smoke'
    tv.STATE = tv.CAPTURES / 'apply-state.json'
    previous_home = device.home()
    before = device.enabled_state(stock)
    tv.require(before in (0, 1), 'Start with the emulator stock launcher enabled.')
    device.shell('cmd', 'package', 'set-home-activity', '--user', '0', tv.HOME)
    try:
        tv.require(device.home_role_holder() == tv.APP, 'Failed to select the emulator OS HOME role.')
        tv.apply(device, device.config, False)
        tv.require(device.enabled_state(stock) == before, 'Dry run changed the emulator.')
        tv.apply(device, device.config, True)
        tv.require(device.enabled_state(stock) == 3, 'Expected disabled-user state.')
        tv.require(tv.check(device) == 0, 'Unexpected update-check result.')
        # Simulate an OTA re-enabling the stock launcher; the checker must notice.
        device.set_state(stock, 1)
        tv.require(tv.check(device) == 2, 'Update checker missed a re-enabled package.')
        tv.restore(device, True)
        tv.require(device.enabled_state(stock) == before, 'Restore lost the original enabled state.')
        print('PASS: real ADB dry run, disable-user, update detection and exact-state restore on API 30 emulator.')
    finally:
        if tv.STATE.exists():
            tv.restore(device, True)
        if previous_home and tv.COMPONENT.fullmatch(previous_home):
            device.shell('cmd', 'package', 'set-home-activity', '--user', '0', previous_home)


if __name__ == '__main__':
    os.umask(0o077)
    main()
