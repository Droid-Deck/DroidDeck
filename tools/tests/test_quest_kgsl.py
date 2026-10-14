from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

QUEST = Path(__file__).resolve().parents[1] / 'quest'


@unittest.skipUnless(sys.platform == 'linux' and shutil.which('gcc'), 'needs Linux and gcc')
class QuestKgslTest(unittest.TestCase):
    def test_allocation_passthrough_translation_and_cleanup(self):
        with tempfile.TemporaryDirectory() as folder:
            binary = str(Path(folder) / 'test-kgsl')
            subprocess.run(['gcc', '-Wall', '-Wextra', '-Werror', '-O2', '-o', binary,
                            str(QUEST / 'test_kgsl_compat.c'), str(QUEST / 'kgsl_compat.c')], check=True)
            result = subprocess.run([binary], capture_output=True, text=True, check=True, timeout=10)
            self.assertIn('PASS: 12 GPUOBJ', result.stdout)


if __name__ == '__main__':
    unittest.main()
