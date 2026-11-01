import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('guard', ROOT / 'scripts/public_guard.py')
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class PublicGuardTests(unittest.TestCase):
    def test_private_paths_block_even_when_force_added(self):
        for path in ['config/local.properties', 'config/local.backup', 'captures/shot.png', 'release.jks', 'local.properties', 'adb-session.log', 'app/build/export.json']:
            self.assertTrue(guard.problems(path, b''), path)

    def test_template_with_empty_values_is_allowed(self):
        self.assertFalse(guard.problems('config/local.example.properties', (ROOT / 'config/local.example.properties').read_bytes()))

    def test_detects_personal_values_without_printing_them(self):
        for text in ['tv.host=' + '.'.join(['192', '168', '1', '5']), 'weather.latitude=' + '12.345', 'launcher.favourites=' + 'example.private', 'password=' + 'not-a-real-secret']:
            self.assertTrue(guard.problems('docs/accidental.txt', text.encode()))

    def test_unapproved_binaries_and_private_value_copies_blocked(self):
        self.assertTrue(guard.problems('artwork/logo.png', bytes([137, 80, 78, 71])))
        self.assertTrue(guard.problems('notes.md', b'copied private value', ['private value']))

    def test_binary_allowlist_is_bound_to_exact_content(self):
        path = 'gradle/wrapper/gradle-wrapper.jar'
        self.assertFalse(guard.problems(path, (ROOT / path).read_bytes()))
        self.assertTrue(guard.problems(path, b'accidental replacement'))
        self.assertTrue(guard.problems('docs/account.svg', b'<svg/>'))

    def test_documentation_and_versions_allowed(self):
        self.assertFalse(guard.problems('docs/example.md', b'Compose 1.0.1 and API 30; weather.latitude= is left empty.'))


if __name__ == '__main__':
    unittest.main()
