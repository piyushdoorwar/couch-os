import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('tvctl', ROOT / 'scripts/tvctl.py')
tv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tv)

STOCK = 'com.google.android.apps.tv.launcherx'
FALLBACK = 'com.google.android.tungsten.setupwraith'
REC = 'com.google.android.tvrecommendations'


def config():
    return {'verification.complete': 'true', 'verification.voice.ambient.checked': 'true',
            'protected.packages': 'com.google.android.backdrop',
            'verified.disable.packages': f'{STOCK},{FALLBACK},{REC}',
            'stock.launcher.package': STOCK, 'fallback.package': FALLBACK}


class FakeDevice:
    def __init__(self):
        self.config = config()
        self.states = {STOCK: 0, FALLBACK: 1, REC: 0, tv.APP: 1, 'com.google.android.backdrop': 0}
        self.mutations = []
        self.our_home = True
        self.identity = 'test-device-identity'
        self.fail_on = ''
        self.personal = set()

    def identify(self, enforce_model=True):
        return {'identity': self.identity, 'model': 'TEST_MODEL', 'api': 30}

    def home(self):
        return tv.HOME if self.our_home else STOCK + '/.Home'

    def is_our_home(self):
        return self.our_home

    def packages(self, *flags):
        if '-d' in flags:
            return {p for p, s in self.states.items() if s in (2, 3, 4)}
        if '-s' in flags:
            return set(self.states) - {tv.APP} - self.personal
        return set(self.states)

    def enabled_state(self, package):
        return self.states[package]

    def set_state(self, package, state):
        if package == self.fail_on:
            raise tv.SafetyError('Simulated power failure')
        self.mutations.append((package, state))
        self.states[package] = state


class TvSafetyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name) / 'captures'
        self.state = self.directory / 'apply-state.json'
        self.patch = patch.multiple(tv, CAPTURES=self.directory, STATE=self.state)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        self.dev = FakeDevice()
        self.output = contextlib.redirect_stdout(io.StringIO())
        self.output.__enter__()
        self.addCleanup(self.output.__exit__, None, None, None)

    def test_dry_run_never_mutates_or_creates_journal(self):
        tv.apply(self.dev, self.dev.config, False)
        self.assertEqual([], self.dev.mutations)
        self.assertFalse(self.state.exists())

    def test_launcher_must_be_resolved_home_and_installed(self):
        self.dev.our_home = False
        with self.assertRaises(tv.SafetyError):
            tv.apply(self.dev, self.dev.config, True)
        self.assertEqual([], self.dev.mutations)
        self.dev.our_home = True
        del self.dev.states[tv.APP]
        with self.assertRaises(tv.SafetyError):
            tv.plan(self.dev, self.dev.config)

    def test_fallback_precedes_stock_and_restore_uses_prior_states(self):
        original = self.dev.states.copy()
        tv.apply(self.dev, self.dev.config, True)
        self.assertEqual((FALLBACK, 3), self.dev.mutations[0])
        self.assertEqual((STOCK, 3), self.dev.mutations[-1])
        tv.restore(self.dev, True)
        self.assertEqual(original, self.dev.states)
        self.assertTrue(all(r['status'] == 'restored' for r in json.loads(self.state.read_text())['packages'].values()))
        self.assertEqual(0o600, self.state.stat().st_mode & 0o777)

    def test_preserves_already_disabled_packages_and_idempotence(self):
        self.dev.states[REC] = 3
        tv.apply(self.dev, self.dev.config, True)
        once = self.dev.mutations.copy()
        tv.apply(self.dev, self.dev.config, True)
        self.assertEqual(once, self.dev.mutations)
        self.assertNotIn(REC, json.loads(self.state.read_text())['packages'])
        tv.restore(self.dev, True)
        self.assertEqual(3, self.dev.states[REC])

    def test_pending_journal_recovers_interrupted_apply(self):
        original = self.dev.states.copy()
        self.dev.fail_on = REC
        with self.assertRaises(tv.SafetyError):
            tv.apply(self.dev, self.dev.config, True)
        state = json.loads(self.state.read_text())
        self.assertEqual('disabled', state['packages'][FALLBACK]['status'])
        self.assertEqual('pending', state['packages'][REC]['status'])
        self.dev.fail_on = ''
        tv.restore(self.dev, True)
        self.assertEqual(original, self.dev.states)

    def test_restore_refuses_another_device(self):
        tv.apply(self.dev, self.dev.config, True)
        self.dev.identity = 'different-test-device'
        with self.assertRaises(tv.SafetyError):
            tv.restore(self.dev, True)

    def test_restore_does_not_depend_on_current_policy_or_disable_config(self):
        tv.apply(self.dev, self.dev.config, True)
        self.dev.config['verified.disable.packages'] = ''
        with patch.object(tv, 'policy', side_effect=AssertionError('must use journal')):
            tv.restore(self.dev, True)
        self.assertEqual(0, self.dev.states[STOCK])

    def test_refuses_unreviewed_personal_and_protected_packages(self):
        for value in ['com.example.unreviewed', 'com.google.android.backdrop', tv.APP]:
            self.dev.config['verified.disable.packages'] = value
            with self.assertRaises(tv.SafetyError):
                tv.plan(self.dev, self.dev.config)
        self.dev.config = config()
        self.dev.personal.add(REC)
        with self.assertRaises(tv.SafetyError):
            tv.plan(self.dev, self.dev.config)

    def test_critical_family_denied_even_if_added_to_candidate_policy(self):
        for package in ['com.vendor.ambient.photos', 'com.vendor.voice.helper', 'com.vendor.hdmi.input', 'com.vendor.system.updater', 'com.android.bluetooth']:
            self.dev.states[package] = 0
            self.dev.config['verified.disable.packages'] = package
            with patch.object(tv, 'policy', return_value={package: {'action': 'candidate', 'role': 'ads', 'reason': 'bad entry'}}):
                with self.assertRaises(tv.SafetyError):
                    tv.plan(self.dev, self.dev.config)

    def test_stock_requires_verified_fallback(self):
        for key, value in [('fallback.package', ''), ('verified.disable.packages', STOCK), ('stock.launcher.package', 'com.example.wrong')]:
            self.dev.config = config()
            self.dev.config[key] = value
            with self.assertRaises(tv.SafetyError):
                tv.plan(self.dev, self.dev.config)

    def test_checks_report_reenabled_missing_and_changed_home(self):
        tv.apply(self.dev, self.dev.config, True)
        self.assertEqual(0, tv.check(self.dev))
        self.dev.states[REC] = 0
        self.assertEqual(2, tv.check(self.dev))
        del self.dev.states[FALLBACK]
        self.assertEqual(2, tv.check(self.dev))
        self.dev.our_home = False
        self.assertEqual(2, tv.check(self.dev))

    def test_dry_restore_never_mutates(self):
        tv.apply(self.dev, self.dev.config, True)
        self.dev.mutations.clear()
        tv.restore(self.dev, False)
        self.assertEqual([], self.dev.mutations)

    def test_concurrent_mutation_refused(self):
        with tv.mutation_lock():
            with self.assertRaises(tv.SafetyError):
                tv.apply(self.dev, self.dev.config, True)

    def test_strict_config_never_executes_shell_syntax(self):
        path = Path(self.temp.name) / 'local.properties'
        path.write_text('launcher.favourites=$(touch bad)\n')
        with self.assertRaises(tv.SafetyError):
            tv.package_list(tv.read_config(path)['launcher.favourites'])
        path.write_text('key=one\nkey=two\n')
        with self.assertRaises(tv.SafetyError):
            tv.read_config(path)


class AdbProtocolTests(unittest.TestCase):
    def setUp(self):
        # Construct without an endpoint; no real device or network is used in tests.
        self.dev = object.__new__(tv.Device)
        self.dev.config = {'tv.expected.model': 'TEST_MODEL'}

    def test_parses_home_from_android_command(self):
        with patch.object(self.dev, 'shell', return_value='priority=0\n' + tv.HOME):
            self.assertTrue(self.dev.is_our_home())
        with patch.object(self.dev, 'shell', return_value='No activity found'):
            self.assertFalse(self.dev.is_our_home())

    def test_parses_exact_enabled_state_and_rejects_missing_user(self):
        with patch.object(self.dev, 'shell', return_value='Package info\n  User 0: installed=true hidden=false enabled=0 stopped=false\n  User 10: enabled=3'):
            self.assertEqual(0, self.dev.enabled_state(STOCK))
        with patch.object(self.dev, 'shell', return_value='User 10: enabled=1'):
            with self.assertRaises(tv.SafetyError):
                self.dev.enabled_state(STOCK)

    def test_set_state_checks_device_not_just_adb_exit_status(self):
        with patch.object(self.dev, 'shell', return_value=''), patch.object(self.dev, 'enabled_state', return_value=0):
            with self.assertRaises(tv.SafetyError):
                self.dev.set_state(STOCK, 3)

    def test_pair_code_is_stdin_and_config_json_is_not_argv(self):
        dev = FakeDevice()
        dev.target = 'TEST_ENDPOINT'
        calls = []
        def run(*args, **kwargs):
            calls.append((args, kwargs))
            return 'Successfully paired' if args[0] == 'pair' else 'connected to' if args[0] == 'connect' else 'device'
        dev.run = run
        tv.connect(dev, {'tv.host': 'TEST_HOST', 'adb.pair.port': '12345', 'adb.pair.code': '123456'})
        self.assertNotIn('123456', str(calls[0][0]))
        self.assertEqual('123456\n', calls[0][1]['input'])
        dev.shell = lambda *args, **kwargs: ''
        tv.configure(dev, {'launcher.favourites': 'example.private'})
        self.assertNotIn('example.private', str(calls[-1][0]))
        self.assertIn('example.private', calls[-1][1]['input'])


if __name__ == '__main__':
    unittest.main()
