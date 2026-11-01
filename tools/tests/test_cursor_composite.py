"""Exercise the compositor's real cursor placement rule for glasses output."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]


class CursorCompositeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if compiler is None:
            raise unittest.SkipTest('A host C compiler is required')
        header = (REPO / 'app/src/main/cpp/waylandcomp/src/cardboard.h').read_text()
        sources = dict(re.findall(r'CARDBOARD_SOURCE_(SCREEN|SBS|FLAT)\s*=\s*(\d+)', header))
        for name in ('SCREEN', 'SBS', 'FLAT'):
            if name not in sources:
                raise AssertionError(f'cardboard.h no longer defines CARDBOARD_SOURCE_{name}')
        if sources['SBS'] != '1':
            raise AssertionError(f'CARDBOARD_SOURCE_SBS is {sources["SBS"]}, expected 1')

        source = (REPO / 'app/src/main/cpp/waylandcomp/src/compositor.c').read_text()
        start = source.index('static int cursor_scene_positions(int scene_w, int source, int ptr_x, int hx, int out[2]) {')
        end = source.index('\n}\n', start) + 3

        cls.temp = tempfile.TemporaryDirectory(prefix='droiddeck-cursor-composite-')
        cls.addClassCleanup(cls.temp.cleanup)
        folder = Path(cls.temp.name)
        (folder / 'cursor_scene_positions.inc').write_text(source[start:end])
        start = source.index('static int cursor_clip_to_eye(int scene_w, int eye, int x0, int w, int *dx, int *sx, int *dw) {')
        end = source.index('\n}\n', start) + 3
        (folder / 'cursor_clip_to_eye.inc').write_text(source[start:end])
        (folder / 'cursor_constants.inc').write_text(
            f'#define CARDBOARD_SOURCE_SCREEN {sources["SCREEN"]}\n'
            f'#define CARDBOARD_SOURCE_SBS {sources["SBS"]}\n'
            f'#define CARDBOARD_SOURCE_FLAT {sources["FLAT"]}\n')
        cls.binary = folder / 'cursor-composite-test'
        subprocess.run([compiler, '-std=c11', '-Wall', '-Wextra', '-Werror', '-I', str(folder),
                        str(REPO / 'tools/tests/cursor_composite_harness.c'),
                        '-o', str(cls.binary)], check=True, capture_output=True, text=True)

    def test_cursor_is_drawn_once_for_one_eye_and_mirrored_for_sbs(self):
        result = subprocess.run([str(self.binary)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('PASS: cursor stereo placement', result.stdout)


if __name__ == '__main__':
    unittest.main()
