#!/usr/bin/env python3
"""Local TV operations. Public policy is conservative; private state never leaves captures/."""
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / 'config/local.properties'
CAPTURES = ROOT / 'captures'
STATE = CAPTURES / 'apply-state.json'
ANIMATION_STATE = CAPTURES / 'animation-state.json'
ANIMATION_KEYS = ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale')
ANIMATION_VALUE = re.compile(r'(null|\d+(\.\d+)?)\Z')
APP = 'dev.couch.launcher'
HOME = APP + '/.MainActivity'
CONFIG_URI = 'content://dev.couch.launcher.config/local-config.json'
PACKAGE = re.compile(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+\Z')
COMPONENT = re.compile(r'[A-Za-z][A-Za-z0-9_.]*/[A-Za-z._][A-Za-z0-9_.$]*\Z')
# Deny entire critical families, including OEM variations unknown to the public policy.
PROTECTED = re.compile(r'(?i)(^android$|^com\.android\.|^dev\.couch\.|ambient|backdrop|dream|photos|assistant|katniss|quicksearch|voice|speech|input|hdmi|cec|tuner|live.?tv|livechannels|bluetooth|remote|chipset|mediatek|amlogic|qualcomm|realtek|mtk|picture|audio|sound|settings|update|updater|ota(?:\.|$)|vending|gms(?:\.|$)|gsf(?:\.|$)|permission|systemui|provider|tvservice|factory|security)')


class SafetyError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise SafetyError(message)


def read_config(path=CONFIG):
    require(path.exists(), 'Run scripts/setup-dev.sh, then fill config/local.properties locally.')
    result = {}
    for number, raw in enumerate(path.read_text().splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        require('=' in line, f'Config line {number}: expected key=value.')
        key, value = line.split('=', 1)
        require(key.strip() not in result, f'Duplicate config key on line {number}.')
        result[key.strip()] = value.strip()
    return result


def package_list(value):
    packages = [p.strip() for p in value.split(',') if p.strip()]
    require(all(PACKAGE.fullmatch(p) for p in packages), 'Invalid package name in config or state.')
    return list(dict.fromkeys(packages))


def policy():
    rows = {}
    for line in (ROOT / 'tv/55c350mp/policy.tsv').read_text().splitlines():
        if not line or line.startswith('#'):
            continue
        parts = line.split('\t')
        require(len(parts) == 4 and PACKAGE.fullmatch(parts[0]), 'Malformed public package policy.')
        package, action, role, reason = parts
        require(action in ('candidate', 'keep') and package not in rows, 'Invalid/duplicate public package policy.')
        rows[package] = {'action': action, 'role': role, 'reason': reason}
    return rows


def private_dir():
    CAPTURES.mkdir(mode=0o700, exist_ok=True)
    CAPTURES.chmod(0o700)


def save_json(path, data):
    private_dir()
    fd, temporary = tempfile.mkstemp(dir=CAPTURES, prefix='.journal-')
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(data, stream, indent=2)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        # Persist rename as well as contents before changing any package on the TV.
        directory = os.open(CAPTURES, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


@contextlib.contextmanager
def mutation_lock():
    private_dir()
    lock = CAPTURES / '.operation-lock'
    try:
        lock.mkdir()
    except FileExistsError:
        raise SafetyError('Another operation holds captures/.operation-lock. If interrupted, verify no operation is running before removing that empty directory.')
    try:
        yield
    finally:
        lock.rmdir()


class Device:
    def __init__(self, config):
        host = config.get('tv.host', '')
        port = config.get('adb.connect.port', '')
        require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.-]*', host) is not None, 'Set tv.host in local config (IPv4 or hostname).')
        require(port.isdigit() and 1 <= int(port) <= 65535, 'Set a valid adb.connect.port in local config.')
        self.target = host + ':' + port
        self.config = config

    def run(self, *args, input=None, scoped=True, timeout=30):
        command = ['adb'] + (['-s', self.target] if scoped else []) + list(args)
        try:
            result = subprocess.run(command, input=input, text=True, capture_output=True, timeout=timeout)
        except FileNotFoundError:
            raise SafetyError('adb not found. Install Android SDK platform-tools.')
        except subprocess.TimeoutExpired:
            raise SafetyError('ADB timed out; check the TV locally. No device output is printed.')
        # adb sometimes returns zero for a failed pm command. Check stdout too.
        require(result.returncode == 0 and not re.search(r'(?im)^\s*(error:|failure|exception|securityexception|java\.)', result.stdout),
                'ADB operation failed. Inspect the TV locally; raw output is withheld for privacy.')
        return result.stdout.strip().replace('\r', '')

    def shell(self, *args, **kwargs):
        # No user strings are evaluated by a shell. Values allowed remotely are strictly validated.
        return self.run('shell', *args, **kwargs)

    def identify(self, enforce_model=True):
        model = self.shell('getprop', 'ro.product.model')
        if enforce_model:
            require(bool(self.config.get('tv.expected.model')), 'Record tv.expected.model from inventory before making changes.')
            require(model == self.config['tv.expected.model'], 'TV model does not match local config. Stop and inspect the connection.')
        api = self.shell('getprop', 'ro.build.version.sdk')
        require(api.isdigit() and int(api) >= 30, 'Couch OS requires Android TV 11 / API 30 or newer.')
        device_identifier = self.shell('getprop', 'ro.serialno') or self.shell('getprop', 'ro.boot.serialno')
        require(device_identifier and device_identifier.lower() not in ('unknown', 'null'), 'Cannot establish a stable device identity for safe journal restore.')
        return {'identity': hashlib.sha256((model + '\n' + device_identifier).encode()).hexdigest(), 'model': model, 'api': int(api)}

    def packages(self, *flags):
        raw = self.shell('pm', 'list', 'packages', '--user', '0', *flags)
        return {line.removeprefix('package:') for line in raw.splitlines() if line.startswith('package:')}

    def home(self):
        output = self.shell('cmd', 'package', 'resolve-activity', '--brief', '--user', '0', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
        for line in reversed(output.splitlines()):
            if COMPONENT.fullmatch(line):
                return line
        return ''

    def is_our_home(self):
        return self.home() in (HOME, APP + '/' + APP + '.MainActivity')

    def home_role_holder(self):
        try:
            output = self.shell('cmd', 'role', 'get-role-holders', '--user', '0', 'android.app.role.HOME')
            # Some Android 11 firmware (the 55C350MP) answers "Unknown command" with exit status 0.
            if 'unknown command' not in output.lower():
                holders = [line for line in output.splitlines() if PACKAGE.fullmatch(line)]
                return holders[0] if len(holders) == 1 else ''
        except SafetyError:
            pass
        # Android 11 has no role getter shell command; inspect the OS role dump.
        return home_role_from_dump(self.shell('dumpsys', 'role'))

    def enabled_state(self, package):
        require(PACKAGE.fullmatch(package), 'Invalid package name.')
        dump = self.shell('dumpsys', 'package', package)
        match = re.search(r'User 0:.*?\benabled=(\d+)\b', dump)
        require(match is not None, 'Cannot read exact user-0 package state; refusing to guess.')
        state = int(match[1])
        require(state in (0, 1, 2, 3, 4), 'Unknown Android enabled-state value.')
        return state

    def set_state(self, package, state):
        require(PACKAGE.fullmatch(package), 'Invalid package name in journal.')
        verb = {0: 'default-state', 1: 'enable', 2: 'disable', 3: 'disable-user', 4: 'disable-until-used'}[state]
        self.shell('pm', verb, '--user', '0', package)
        require(self.enabled_state(package) == state, 'Package state did not change as requested. Journal retained for restore.')


def load_state(device, allow_missing=False):
    if not STATE.exists():
        require(allow_missing, 'No apply journal exists. Nothing to restore/check.')
        return {'version': 1, 'device': device.identify(), 'packages': {}}
    state = json.loads(STATE.read_text())
    require(state.get('version') == 1 and isinstance(state.get('packages'), dict), 'Unrecognised journal format.')
    require(state['device']['identity'] == device.identify()['identity'], 'Journal belongs to another TV. Refusing to modify this device.')
    for package, record in state['packages'].items():
        require(PACKAGE.fullmatch(package) and record.get('before') in (0, 1), 'Unsafe/corrupt journal entry.')
    return state


def home_role_from_dump(dump):
    """Read only user 0's HOME role in Android 11's brace-delimited role dump.

    Unknown formats/multiple holders fail closed; never infer selection from a
    package simply appearing somewhere else in the diagnostic output.
    """
    def block_at(start):
        depth = 0
        for index in range(start, len(dump)):
            if dump[index] == '{':
                depth += 1
            elif dump[index] == '}':
                depth -= 1
                if depth == 0:
                    return dump[start:index + 1]
        return ''
    # Firmware prints either `user_states={ user_id=0 ... }` or `user_states=[ { user_id=0 ... } ]`
    # (the 55C350MP). Either way, user 0's block is the brace that opens just before its user_id line.
    users = []
    for match in re.finditer(r'(?m)^\s*user_id\s*=\s*0\s*$', dump):
        start = dump.rfind('{', 0, match.start())
        block = block_at(start) if start >= 0 else ''
        if block and re.search(r'\buser_states\s*=', dump[:start]):
            users.append(block)
    if len(users) != 1:
        return ''
    roles = re.findall(r'\{([^{}]*)\}', users[0])
    homes = [role for role in roles if re.search(r'(?m)^\s*name\s*=\s*android\.app\.role\.HOME\s*$', role)]
    if len(homes) != 1:
        return ''
    holders = re.findall(r'(?m)^\s*holders\s*=\s*([A-Za-z][A-Za-z0-9_.]*)\s*$', homes[0])
    return holders[0] if len(holders) == 1 and PACKAGE.fullmatch(holders[0]) else ''


def home_ready(device, config, packages):
    if device.is_our_home():
        return True
    # Android TV can select our HOME role yet resolve the privileged stock app first.
    # An explicit local inspection gate, exact stock match and OS role are all required.
    stock = config.get('stock.launcher.package', '')
    return (config.get('verification.stock.home.priority.checked') == 'true'
            and stock in packages
            and device.home().split('/')[0] == stock
            and device.home_role_holder() == APP)


def plan(device, config):
    device.identify()
    require(config.get('verification.complete') == 'true', 'Finish the inventory review, then set verification.complete=true locally.')
    require(config.get('verification.voice.ambient.checked') == 'true', 'Record baseline voice and ambient checks before applying.')
    protected = set(package_list(config.get('protected.packages', '')))
    require(protected, 'Record verified OEM/ambient/voice/input protected.packages first.')
    packages = package_list(config.get('verified.disable.packages', ''))
    require(packages, 'No verified disable packages selected. Nothing will be disabled.')
    inventory, system, disabled = device.packages(), device.packages('-s'), device.packages('-d')
    require(protected <= inventory, 'A protected package is missing from inventory; check local config and firmware before applying.')
    rules = policy()
    stock = config.get('stock.launcher.package', '')
    fallback = config.get('fallback.package', '')
    for package in packages:
        rule = rules.get(package, {})
        require(rule.get('action') == 'candidate', 'Selected package is not a reviewed candidate in public policy: ' + package)
        require(not PROTECTED.search(package) and package not in protected, 'Critical package may never be disabled: ' + package)
        require(package in inventory and package in system, 'Only verified preinstalled user-0 packages may be selected: ' + package)
        if rule.get('role') == 'stock-launcher':
            require(package == stock, 'Stock launcher candidate does not match the verified local stock launcher.')
            if not fallback:
                require(config.get('fallback.verified.absent') == 'true', 'Verify the setup fallback package, or explicitly verify its absence before disabling stock HOME.')
                require(not any(p in inventory for p, r in rules.items() if r['role'] == 'fallback'), 'A known setup fallback is installed; it cannot be marked absent.')
            else:
                require(rules.get(fallback, {}).get('role') == 'fallback', 'Configured fallback is not a reviewed setup fallback candidate.')
                require(fallback in inventory and fallback in system, 'Configured setup fallback was not found as a system package.')
                require(fallback in packages or fallback in disabled, 'Select the verified setup fallback too, so it cannot resurrect stock HOME.')
        if rule.get('role') == 'fallback':
            require(package == fallback, 'Setup fallback does not match verified config.')
    # Require this for ALL disables: losing a dependency must not strand HOME.
    require(APP in inventory and APP not in disabled and home_ready(device, config, packages), 'Install Couch OS and select it as HOME before disabling any package. See the runbook if verified stock priority still wins.')
    # Fallback first, stock launcher last.
    return sorted(packages, key=lambda p: {'fallback': 0, 'stock-launcher': 2}.get(rules[p]['role'], 1))


def apply(device, config, yes):
    packages = plan(device, config)
    rules = policy()
    if not device.is_our_home():
        print('HOME role is Couch OS; the verified stock launcher still wins priority. This reviewed plan activates the selected HOME by disabling stock last.')
    for package in packages:
        print(f'Disable user 0: {package} — {rules[package]["reason"]}')
    if not yes:
        print('Dry run only. Re-run apply.sh --yes after reviewing this plan.')
        return
    with mutation_lock():
        state = load_state(device, allow_missing=True)
        for package in packages:
            require(home_ready(device, config, packages), 'HOME selection changed during apply. Stop and restore.')
            before = device.enabled_state(package)
            record = state['packages'].get(package)
            if before in (2, 3, 4):
                print('Already disabled; preserve prior state: ' + package)
                continue
            # Save before a device mutation, including pending operations interrupted by power loss.
            if not record or record.get('status') == 'restored':
                record = {'before': before, 'status': 'pending'}
                state['packages'][package] = record
            else:
                record['status'] = 'pending'
            save_json(STATE, state)
            device.set_state(package, 3)
            record['status'] = 'disabled'
            save_json(STATE, state)
        require(device.is_our_home(), 'HOME no longer resolves to Couch OS. Run restore.sh --yes.')
    print('Applied. Test the physical remote, voice, HDMI, Live TV and Ambient mode now. Turn debugging off afterwards.')


def restore(device, yes):
    state = load_state(device)
    records = [(p, r) for p, r in state['packages'].items() if r.get('status') != 'restored']
    # Restore the HOME provider before setup/recommendations regardless of policy/config changes.
    stock = device.config.get('stock.launcher.package', '')
    records.sort(key=lambda pair: pair[0] != stock)
    for package, record in records:
        print(f'Restore {package} to prior enabled state {record["before"]}')
    if not yes:
        print('Dry run only. Re-run restore.sh --yes to restore journaled states.')
        return
    with mutation_lock():
        for package, record in records:
            device.set_state(package, record['before'])
            record['status'] = 'restored'
            save_json(STATE, state)
    print('Restored every recorded package. Couch OS remains installed and HOME selection is unchanged. Turn debugging off.')


def inventory(device):
    info = device.identify(enforce_model=False)
    all_packages, system, disabled = device.packages(), device.packages('-s'), device.packages('-d')
    rules = policy()
    rows = []
    for package in sorted(all_packages):
        rule = rules.get(package, {})
        category = 'preinstalled' if package in system else 'personal-installed'
        decision = rule.get('action', 'keep-unreviewed') if package in system else 'keep-personal'
        if PROTECTED.search(package):
            decision = 'keep-protected'
        rows.append({'package': package, 'category': category, 'decision': decision, 'disabled': package in disabled})
    private_dir()
    save_json(CAPTURES / 'inventory.json', {'device': info, 'home': device.home(), 'packages': rows})
    # Model/version are written locally, not echoed into a potentially shared session log.
    print('Saved classified inventory, exact model/API and HOME to captures/inventory.json (private).')
    print(f'{len(all_packages)} packages; {len(system)} preinstalled. Unknown packages default to keep. No changes made.')


def configure(device, config):
    favourites = package_list(config.get('launcher.favourites', ''))
    lat, lon = config.get('weather.latitude', ''), config.get('weather.longitude', '')
    require(bool(lat) == bool(lon), 'Set both weather coordinates or neither.')
    data = {'favourites': favourites, 'voiceHelper': config.get('voice.helper.enabled') == 'true'}
    if lat:
        try:
            latitude, longitude = float(lat), float(lon)
        except ValueError:
            raise SafetyError('Weather coordinates must be decimal numbers.')
        require(-90 <= latitude <= 90 and -180 <= longitude <= 180, 'Weather coordinates are out of range.')
        data.update(latitude=latitude, longitude=longitude)
    component, action = config.get('inputs.component', ''), config.get('inputs.action', '')
    require(not component or COMPONENT.fullmatch(component), 'Invalid Inputs component.')
    require(not action or re.fullmatch(r'[A-Za-z][A-Za-z0-9_.]+', action), 'Invalid Inputs intent action.')
    data.update(inputsComponent=component, inputsAction=action)
    initial = config.get('profile.initial', '')[:1]
    require(not initial or initial.isalnum(), 'profile.initial must be one letter or digit.')
    if initial:
        data['profileInitial'] = initial.upper()
    device.identify()
    device.shell('am', 'force-stop', APP)
    # JSON is stdin, never shell text, argv, a public asset or a temp file. The app's provider accepts
    # writes only from holders of android.permission.DUMP (adb shell), and works in release builds.
    device.run('shell', 'content', 'write', '--uri', CONFIG_URI, input=json.dumps(data))
    # Android reveals the Wi-Fi name only to apps with location permission; opt-in, revoked otherwise.
    location = 'android.permission.ACCESS_FINE_LOCATION'
    device.shell('pm', 'grant' if config.get('wifi.show_name') == 'true' else 'revoke', APP, location)
    # "Jump back in" may also use Android's usage history; app-op, opt-in, reset to default otherwise.
    device.shell('appops', 'set', APP, 'GET_USAGE_STATS', 'allow' if config.get('recent.usage_access') == 'true' else 'default')
    device.shell('am', 'start', '-n', HOME)
    print('Private launcher config provisioned. No local values were printed or built into the APK.')


def install(device, config, yes):
    # The optimized release build is the one to live with; debug is only a fallback for development.
    release, debug = ROOT / 'app/build/outputs/apk/release/app-release.apk', ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    apk = release if release.exists() else debug
    require(apk.exists(), 'Build the APK first: ./gradlew assembleRelease')
    device.identify()
    kind = 'optimized release' if apk == release else 'debug (slower; build assembleRelease for daily use)'
    print(f'Install generic Couch OS {kind} APK, provision private config, then set it as HOME. No packages will be disabled.')
    if not yes:
        print('Dry run only. Re-run install.sh --yes.')
        return
    device.run('install', '-r', str(apk), timeout=120)
    # Compile with the bundled baseline profile now instead of waiting for overnight idle maintenance.
    # Measured on the 55C350MP: cold start 1.7 s -> 0.8 s, janky frames 18% -> 2%.
    device.shell('cmd', 'package', 'compile', '-m', 'speed-profile', '-f', APP, timeout=180)
    configure(device, config)
    device.shell('cmd', 'package', 'set-home-activity', '--user', '0', HOME)
    if device.is_our_home():
        device.shell('am', 'start', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
        print('Couch OS is resolved HOME. Test the physical Home key before apply.')
    else:
        require(device.home_role_holder() == APP, 'Firmware did not select Couch OS as HOME. Use Settings > default apps; do not disable stock HOME.')
        device.shell('am', 'start', '-n', HOME)
        print('Couch OS is selected for the OS HOME role, but stock HOME still wins resolution. No package was disabled. Follow the priority investigation in the runbook before apply.')


def check(device):
    state = load_state(device)
    disabled = device.packages('-d')
    installed = device.packages()
    changed = []
    for package, record in state['packages'].items():
        if record.get('status') == 'restored':
            continue
        if package not in installed:
            print('Missing after update (inspect locally): ' + package)
            changed.append(package)
        elif package not in disabled:
            print('Re-enabled after update: ' + package)
            changed.append(package)
    if not device.is_our_home():
        print('HOME changed after update. Re-select Couch OS and retest before any apply.')
        changed.append('HOME')
    if not changed:
        print('No journaled disabled packages returned; HOME still resolves to Couch OS.')
    print('No changes made. Recheck voice, inputs and Ambient/Photos; disable debugging when finished.')
    return 2 if changed else 0


def animations(device, yes, restore=False):
    """Halve Android's window/transition/animator durations for a snappier TV, or put back the saved values."""
    device.identify()
    current = {key: device.shell('settings', 'get', 'global', key) or 'null' for key in ANIMATION_KEYS}
    require(all(ANIMATION_VALUE.fullmatch(v) for v in current.values()), 'Unexpected animation setting on the TV; inspect locally.')
    if restore:
        require(ANIMATION_STATE.exists(), 'No saved animation values, so nothing to restore.')
        target = json.loads(ANIMATION_STATE.read_text())
        require(set(target) == set(ANIMATION_KEYS) and all(ANIMATION_VALUE.fullmatch(v) for v in target.values()),
                'Saved animation values are malformed; inspect captures/animation-state.json.')
    else:
        target = {key: '0.5' for key in ANIMATION_KEYS}
    for key in ANIMATION_KEYS:
        print(f'{key}: {current[key]} -> {target[key]}')
    if not yes:
        print('Dry run only. Re-run with --yes.' + ('' if restore else ' animations.sh --restore --yes puts the saved values back.'))
        return
    if not restore and not ANIMATION_STATE.exists():
        # Keep the very first original values, even if this is run twice.
        save_json(ANIMATION_STATE, current)
    for key in ANIMATION_KEYS:
        if target[key] == 'null':
            device.shell('settings', 'delete', 'global', key)
        else:
            device.shell('settings', 'put', 'global', key, target[key])
    if restore:
        ANIMATION_STATE.unlink()
    print('Done. Animation speed changes apply immediately; nothing else was touched.')


def connect(device, config):
    pair_port, code = config.get('adb.pair.port', ''), config.get('adb.pair.code', '')
    if pair_port or code:
        require(pair_port.isdigit() and 1 <= int(pair_port) <= 65535 and re.fullmatch(r'\d{6}', code), 'Set both the current pairing port and six-digit code, or leave both blank.')
        # Code is piped into adb, never placed in process arguments or echoed by this script.
        output = device.run('pair', config['tv.host'] + ':' + pair_port, input=code + '\n', scoped=False)
        require('Successfully paired' in output, 'Pairing did not succeed. Refresh the local code and port.')
    output = device.run('connect', device.target, scoped=False)
    require('connected to' in output, 'ADB connect failed; check the local endpoint and TV prompt.')
    require(device.run('get-state') == 'device', 'TV is not authorised. Accept the TV debugging prompt locally.')
    print('Connected to the configured TV. Endpoint and pairing details were not printed. Clear the pairing code from config after use.')


def diagnose(device):
    device.identify()
    private_dir()
    directory = CAPTURES / 'diagnostics'
    directory.mkdir(mode=0o700, exist_ok=True)
    commands = {
        'voiceinteraction': ('dumpsys', 'voiceinteraction'),
        'audio': ('dumpsys', 'audio'),
        'assistant': ('settings', 'get', 'secure', 'assistant'),
        'voice-service': ('settings', 'get', 'secure', 'voice_interaction_service'),
        'tv-input': ('dumpsys', 'tv_input'),
        'input-picker-activities': ('cmd', 'package', 'query-activities', '--brief', '--user', '0', '-a', 'com.android.tv.action.SELECT_INPUT'),
        'home-role': ('dumpsys', 'role'),
        'resolved-home': ('cmd', 'package', 'resolve-activity', '--brief', '--user', '0', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'),
        'home-activities': ('cmd', 'package', 'query-activities', '--brief', '--user', '0', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'),
        'logcat': ('logcat', '-d', '-t', '1500'),
    }
    system_packages = device.packages('-s')
    commands.update({'package-' + package: ('dumpsys', 'package', package)
                     for package, rule in policy().items()
                     if package in system_packages and rule['action'] == 'candidate'})
    for name, command in commands.items():
        path = directory / (name + '.txt')
        try:
            data = device.shell(*command)
        except SafetyError:
            data = 'Unavailable on this firmware; inspect locally.'
        with path.open('w') as stream:
            os.chmod(path, 0o600)
            stream.write(data + '\n')
    print('Saved private diagnostics under captures/diagnostics/. Collect before/after copies locally; never commit or paste raw dumps.')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['connect', 'inventory', 'apply', 'restore', 'check', 'configure', 'install', 'diagnose', 'disconnect', 'animations'])
    parser.add_argument('--yes', action='store_true')
    parser.add_argument('--restore', action='store_true', help='animations: put back the saved original values')
    args = parser.parse_args()
    os.umask(0o077)
    try:
        config = read_config()
        device = Device(config)
        if args.command == 'connect':
            connect(device, config)
        elif args.command == 'disconnect':
            device.run('disconnect', device.target, scoped=False)
            print('ADB disconnected. Turn debugging off and revoke authorisations on the TV too.')
        elif args.command == 'diagnose':
            diagnose(device)
        elif args.command == 'inventory':
            inventory(device)
        elif args.command == 'apply':
            apply(device, config, args.yes)
        elif args.command == 'restore':
            restore(device, args.yes)
        elif args.command == 'configure':
            if not args.yes:
                print('Dry run. configure.sh --yes copies local favourites/location into private TV app storage.')
            else:
                configure(device, config)
        elif args.command == 'animations':
            animations(device, args.yes, args.restore)
        elif args.command == 'install':
            install(device, config, args.yes)
        elif args.command == 'check':
            return check(device)
        return 0
    except (SafetyError, json.JSONDecodeError, KeyError, OSError) as error:
        # Never render raw exception strings: filesystem/subprocess errors may include endpoints.
        print('STOP: ' + (str(error) if isinstance(error, SafetyError) else 'Invalid local state/config or local I/O failure. Inspect files privately.'), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
