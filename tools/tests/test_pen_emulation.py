import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / 'app/src/main/cpp/waylandcomp/src/compositor.c'


def function(source, signature):
    start = source.index(signature)
    return source[start:source.index('\n}\n', start) + 3]


class PenEmulationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if compiler is None:
            raise unittest.SkipTest('A host C compiler is required')
        source = SOURCE.read_text()
        cls.temp = tempfile.TemporaryDirectory(prefix='droiddeck-pen-emulation-')
        cls.addClassCleanup(cls.temp.cleanup)
        folder = Path(cls.temp.name)
        start = source.index('struct input_msg {')
        (folder / 'input_msg.inc').write_text(source[start:source.index('\n', start) + 1])
        start = source.index('#define PEN_IN ')
        end = source.index('static struct pen_state g_pen;\n', start)
        (folder / 'pen_state.inc').write_text(source[start:source.index('\n', end) + 1])
        (folder / 'pen.inc').write_text(''.join(function(source, signature) for signature in (
            'static int pointer_locked_for_cursor(void) {',
            'static void input_output_to_scene(double px, double py, double *x, double *y) {',
            'static int pen_buttons(int flags) {',
            'static void pen_release(int buttons) {',
            'static void deliver_pen(const struct input_msg *m) {')))
        start = source.index('#define for_each_pointer_of(client, sp)')
        (folder / 'pointer.inc').write_text(
            function(source, 'static struct seat_pointer *pointer_for(struct wl_client *client) {')
            + source[start:source.index('#define for_each_keyboard_of', start)]
            + ''.join(function(source, signature) for signature in (
                'static void pointer_focus(struct wl_resource *target, wl_fixed_t fx, wl_fixed_t fy) {',
                'static void pointer_input(double x, double y, int relative, uint32_t button, int pressed) {',
                'static void pointer_event(double x, double y, uint32_t button, int pressed) {',
                'static void pointer_button(uint32_t button, int pressed) {')))
        cls.binary = folder / 'pen-test'
        cls.pointer_binary = folder / 'pen-pointer-test'
        for harness, binary in (('pen_emulation_harness.c', cls.binary),
                                ('pen_pointer_harness.c', cls.pointer_binary)):
            subprocess.run([compiler, '-std=c11', '-Wall', '-Wextra', '-Werror', '-I', str(folder),
                            str(REPO / 'tools/tests' / harness),
                            '-o', str(binary)], check=True, capture_output=True, text=True)

    def check_case(self, case, binary=None):
        result = subprocess.run([str(binary or self.binary), case], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_hover_moves_the_pointer_in_scene_coordinates(self):
        self.check_case('hover')

    def test_tip_presses_and_releases_the_left_button_once(self):
        self.check_case('tip')

    def test_buttons_press_after_motion_and_before_releases(self):
        self.check_case('buttons')

    def test_leaving_proximity_releases_every_button_without_motion(self):
        self.check_case('out')

    def test_pointer_lock_blocks_motion_but_passes_buttons(self):
        self.check_case('lock')

    def test_lost_records_still_release_held_buttons(self):
        self.check_case('lost')

    def test_relative_origin_resets_when_proximity_starts_and_ends(self):
        self.check_case('raw')

    def test_side_button_release_keeps_the_grab(self):
        self.check_case('side', self.pointer_binary)

    def test_swapping_buttons_in_one_record_keeps_the_grab(self):
        self.check_case('swap', self.pointer_binary)

    def test_a_button_release_from_another_pointer_keeps_the_grab(self):
        self.check_case('mouse', self.pointer_binary)

    def test_a_window_closed_by_a_button_release_is_not_grabbed_again(self):
        self.check_case('gone', self.pointer_binary)


if __name__ == '__main__':
    unittest.main()
