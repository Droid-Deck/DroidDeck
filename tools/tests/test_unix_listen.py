import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / 'app/src/main/cpp/waylandcomp/src'
SUN_PATH = 108


@unittest.skipUnless(sys.platform.startswith('linux') and shutil.which('cc'), 'requires a Linux C compiler')
class UnixListenTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.folder = tempfile.TemporaryDirectory()
        cls.binary = pathlib.Path(cls.folder.name) / 'unix-listen-test'
        subprocess.run([
            'cc', '-std=c11', '-D_GNU_SOURCE', '-Wall', '-Wextra', '-Werror',
            str(ROOT / 'tools/tests/test_unix_listen.c'), str(SRC / 'unix_listen.c'),
            '-o', str(cls.binary),
        ], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.folder.cleanup()

    def run_in(self, minimum):
        with tempfile.TemporaryDirectory() as base:
            directory = pathlib.Path(base)
            while len(str(directory)) + len('/wayland-0') < minimum:
                directory = directory / ('e' * 30)
            directory.mkdir(parents=True, exist_ok=True)
            fits = len(str(directory)) + len('/wayland-0') < SUN_PATH
            result = subprocess.run([str(self.binary), str(directory), '1' if fits else '0'],
                                    capture_output=True, text=True, timeout=10)
            return directory, result

    def test_a_path_that_fits_binds_directly(self):
        directory, result = self.run_in(0)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('PASS: unix listen', result.stdout)

    def test_a_path_longer_than_sun_path_still_listens_and_accepts(self):
        directory, result = self.run_in(140)
        self.assertGreaterEqual(len(str(directory)) + len('/wayland-0'), SUN_PATH)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('PASS: unix listen', result.stdout)


if __name__ == '__main__':
    unittest.main()
