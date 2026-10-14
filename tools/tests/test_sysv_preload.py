from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / 'linuxfs' / 'preload'


@unittest.skipUnless(sys.platform == 'linux' and shutil.which('gcc'), 'needs Linux and gcc')
class SysvPreloadTest(unittest.TestCase):
    def test_semop_sets_otime_for_waiters(self):
        with tempfile.TemporaryDirectory() as folder:
            binary = str(Path(folder) / 'test-sysv')
            subprocess.run(['gcc', '-Wall', '-Wextra', '-O2', '-pthread', '-o', binary,
                            str(Path(__file__).resolve().parent / 'sysv_otime_harness.c'), str(PRELOAD / 'sysv.c')], check=True)
            env = dict(os.environ, BL_SYSV_DIR=folder)
            result = subprocess.run([binary], capture_output=True, text=True, timeout=10, env=env)
            self.assertIn('PASS: sysv otime', result.stdout, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
