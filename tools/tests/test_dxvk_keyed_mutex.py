import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / 'tools/dxvk/patches/0005-keyed-mutex-without-fence.patch'
CHECK = ROOT / 'tools/dxvk/check_keyed_mutex_fence.py'
TARGET = 'src/dxvk/dxvk_image.cpp'


def pre_image():
    lines = []
    inside = False
    for line in PATCH.read_text().splitlines():
        if line.startswith('@@'):
            inside = True
            lines.append('')
        elif inside and line[:1] in (' ', '-') and not line.startswith('---'):
            lines.append(line[1:])
    return '\n'.join(lines) + '\n'


@unittest.skipUnless(shutil.which(os.environ.get('HOST_CXX', 'c++')), 'needs a native C++ compiler')
class KeyedMutexFenceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.tree = Path(temporary.name)
        (self.tree / 'src/dxvk').mkdir(parents=True)

    def run_check(self, path):
        return subprocess.run(['python3', '-I', str(CHECK), str(path)],
                              capture_output=True, text=True, timeout=120)

    def test_patched_source_passes(self):
        target = self.tree / TARGET
        target.write_text(pre_image())
        subprocess.run(['git', 'apply', '--unsafe-paths', '--directory', str(self.tree), str(PATCH)],
                       check=True, cwd=self.tree)
        result = self.run_check(target)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('PASS', result.stdout)

    def test_unpatched_source_is_rejected(self):
        target = self.tree / TARGET
        target.write_text(pre_image())
        result = self.run_check(target)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('FAIL', result.stderr)


if __name__ == '__main__':
    unittest.main()
