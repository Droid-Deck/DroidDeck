import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-steam-x64'
SOURCE = SCRIPT.read_text()
LIBRARY_LOOP = re.search(r'^for d in "\$STEAM_X64"/steamrt64/.*?^done\nFEX_ROOTFS=', SOURCE, re.M | re.S).group()
LIBRARY_LOOP = LIBRARY_LOOP[:LIBRARY_LOOP.rfind('\nFEX_ROOTFS=')]


class SteamX64RuntimeLibsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.steam = self.root / 'Steam'
        self.runtime = self.steam / 'steamrt64/pv-runtime/steam-runtime-steamrt'
        self.lib = self.runtime / 'steamrt3c_platform_a/files/lib/x86_64-linux-gnu'
        self.usr_lib = self.runtime / 'steamrt3c_platform_a/files/usr/lib/x86_64-linux-gnu'
        self.lib.mkdir(parents=True)
        self.usr_lib.mkdir(parents=True)
        self.dest = self.root / 'steamrt-libs'
        self.dest.mkdir()

    def run_loop(self):
        shell = 'srt_libs="$DEST"\n' + LIBRARY_LOOP
        env = dict(os.environ, STEAM_X64=str(self.steam), DEST=str(self.dest))
        return subprocess.run(['bash', '-eu', '-c', shell], env=env, capture_output=True, text=True, timeout=10)

    def test_empty_library_directory_is_skipped(self):
        (self.usr_lib / 'libexample.so.1').touch()
        result = self.run_loop()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.dest / 'libexample.so.1').is_symlink())

    def test_two_platform_versions_with_empty_lib_dirs_use_usr_lib(self):
        platform_b = self.runtime / 'steamrt3c_platform_b'
        (platform_b / 'files/lib/x86_64-linux-gnu').mkdir(parents=True)
        usr_lib_b = platform_b / 'files/usr/lib/x86_64-linux-gnu'
        usr_lib_b.mkdir(parents=True)
        (self.usr_lib / 'libexample.so.1').touch()
        (usr_lib_b / 'libother.so.1').touch()
        result = self.run_loop()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.dest / 'libexample.so.1').is_symlink())
        self.assertTrue((self.dest / 'libother.so.1').is_symlink())

    def test_stale_literal_glob_link_does_not_abort(self):
        (self.dest / '*.so*').symlink_to('/missing/*.so*')
        result = self.run_loop()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.dest / '*.so*').is_symlink())

    def test_existing_dangling_library_link_is_not_recreated(self):
        (self.lib / 'libexample.so.1').touch()
        (self.dest / 'libexample.so.1').symlink_to('/missing/libexample.so.1')
        result = self.run_loop()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('/missing/libexample.so.1', os.readlink(self.dest / 'libexample.so.1'))


if __name__ == '__main__':
    unittest.main()
