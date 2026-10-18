"""Exercise the compositor's real button dispatcher with a recording Wayland sink."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]


class PointerReleaseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if compiler is None:
            raise unittest.SkipTest('A host C compiler is required')
        source = (REPO / 'app/src/main/cpp/waylandcomp/src/compositor.c').read_text()
        start = source.index('static void pointer_button(uint32_t button, int pressed) {')
        end = source.index('\n}\n', start) + 3
        cls.temp = tempfile.TemporaryDirectory(prefix='droiddeck-pointer-release-')
        cls.addClassCleanup(cls.temp.cleanup)
        folder = Path(cls.temp.name)
        (folder / 'pointer_button.inc').write_text(source[start:end])
        start = source.index('static void touch_cancel_all(void) {')
        end = source.index('\n}\n', start) + 3
        (folder / 'touch_cancel_all.inc').write_text(source[start:end])
        cls.binary = folder / 'pointer-test'
        subprocess.run([compiler, '-std=c11', '-Wall', '-I', str(folder),
                        str(REPO / 'tools/tests/pointer_release_harness.c'),
                        '-o', str(cls.binary)], check=True, capture_output=True, text=True)

    def check_case(self, case):
        result = subprocess.run([str(self.binary), case], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_cleanup_releases_all_buttons_without_motion(self):
        self.check_case('cleanup')

    def test_release_without_focus_does_not_select_a_window(self):
        self.check_case('unfocused')

    def test_release_reaches_each_focused_pointer_and_clears_grab(self):
        self.check_case('focused')

    def test_touch_fallback_cancel_releases_without_motion(self):
        self.check_case('fallback')

    def test_native_touch_cancel_then_cleanup_releases_stale_buttons(self):
        self.check_case('native')

    def test_press_keeps_existing_routing(self):
        self.check_case('press')


if __name__ == '__main__':
    unittest.main()
