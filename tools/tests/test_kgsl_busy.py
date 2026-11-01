"""Exercise the HUD's kgsl memstore busy sampling and counter layout (app/src/main/cpp/gpuclock)."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]


class KgslBusyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if compiler is None:
            raise unittest.SkipTest('A host C compiler is required')
        cls.temp = tempfile.TemporaryDirectory(prefix='droiddeck-kgsl-busy-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.binary = Path(cls.temp.name) / 'kgsl-busy-test'
        subprocess.run([compiler, '-std=c11', '-Wall', '-Wextra', '-Werror',
                        '-I', str(REPO / 'app/src/main/cpp/gpuclock'),
                        str(REPO / 'tools/tests/kgsl_busy_harness.c'), '-o', str(cls.binary)],
                       check=True, capture_output=True, text=True)

    def test_ringbuffer_slots_and_busy_rule(self):
        result = subprocess.run([str(self.binary)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('PASS: kgsl memstore busy', result.stdout)
        self.assertIn('PASS: kgsl memstore contexts', result.stdout)
        self.assertIn('PASS: kgsl counter layout', result.stdout)


if __name__ == '__main__':
    unittest.main()
