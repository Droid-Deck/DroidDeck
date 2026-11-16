import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'app/src/main/cpp/waylandcomp/src/present_cadence.c'
HARNESS = ROOT / 'tools/tests/present_cadence_harness.c'


@unittest.skipUnless(shutil.which('cc'), 'needs a C compiler')
class PresentCadenceTest(unittest.TestCase):
    def test_game_layer_frames_keep_their_cadence_through_copy_jitter(self):
        with tempfile.TemporaryDirectory(prefix='droiddeck-cadence-') as folder:
            binary = Path(folder) / 'cadence-test'
            build = subprocess.run(['cc', '-std=c11', '-Wall', '-Wextra', '-Werror', '-O2', '-I', str(SOURCE.parent),
                                    str(HARNESS), str(SOURCE), '-o', str(binary)], capture_output=True, text=True)
            self.assertEqual(0, build.returncode, build.stderr)
            result = subprocess.run([str(binary)], capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('PASS', result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
