import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / 'app/src/main/cpp/waylandcomp/src/compositor.c'
EXTENSION = REPO / 'app/src/main/cpp/waylandcomp/src/droiddeck_ext.h'
PEN_STREAM = REPO / 'app/src/main/java/com/droiddeck/launcher/input/PenStream.kt'


def function(source, signature):
    start = source.index(signature)
    return source[start:source.index('\n}\n', start) + 3]


def lines(source, first, last):
    start = source.index(first)
    return source[start:source.index('\n', source.index(last, start) + len(last) - 1) + 1]


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
        (folder / 'pen_state.inc').write_text(lines(source, '#define PEN_IN ', 'static atomic_int g_pen_caps;'))
        extension = EXTENSION.read_text()
        (folder / 'pen_api.inc').write_text(lines(extension, 'enum { DROIDDECK_PEN_CAP_PRESSURE', '    uint32_t time_ms;\n};'))
        (folder / 'pen.inc').write_text(''.join(function(source, signature) for signature in (
            'static int pointer_locked_for_cursor(void) {',
            'static void input_output_to_scene(double px, double py, double *x, double *y) {',
            'static int pen_buttons(int flags) {',
            'static void pen_release(int buttons) {',
            'static void pen_end_route(void) {',
            'static int pen_warp(void) {',
            'static void pen_pointer(const struct input_msg *m) {',
            'static void pen_tablet(const struct input_msg *m) {',
            'static void deliver_pen(const struct input_msg *m) {')))
        (folder / 'touch.inc').write_text(lines(source, '#define for_each_touch_of(client, st)', '== (client))'))
        start = source.index('static struct surface *pen_target_at(double x, double y) {')
        (folder / 'query.inc').write_text(source[start:source.index('int droiddeck_cursor_input_pos(', start)])
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

    def test_flags_match_the_kotlin_pen_stream(self):
        native = dict(re.findall(r'^#define (PEN_[A-Z]+) \(1 << (\d+)\)$', SOURCE.read_text(), re.MULTILINE))
        kotlin = dict(re.findall(r'^\s*const val (PEN_[A-Z]+) = 1 shl (\d+)$', PEN_STREAM.read_text(), re.MULTILINE))
        self.assertEqual({'PEN_IN', 'PEN_TIP', 'PEN_RIGHT', 'PEN_MIDDLE', 'PEN_ERASER', 'PEN_TABLET', 'PEN_TILT'},
                         set(native))
        self.assertEqual(native, kotlin)

    def test_capabilities_match_the_kotlin_pen_stream(self):
        native = dict(re.findall(r'DROIDDECK_PEN_CAP_([A-Z]+) = 1 << (\d+)', EXTENSION.read_text()))
        kotlin = dict(re.findall(r'^\s*const val CAP_([A-Z]+) = 1 shl (\d+)$', PEN_STREAM.read_text(), re.MULTILINE))
        self.assertEqual({'PRESSURE', 'TILT', 'DISTANCE'}, set(native))
        self.assertEqual(native, kotlin)

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

    def test_route_and_target_stay_until_the_stroke_ends(self):
        self.check_case('frozen', self.pointer_binary)

    def test_without_a_tablet_client_the_tablet_wish_is_the_pointer(self):
        self.check_case('notablet', self.pointer_binary)

    def test_a_route_change_ends_the_old_route_first(self):
        self.check_case('switch', self.pointer_binary)

    def test_tablet_frames_carry_local_axes_and_the_delivery_time(self):
        self.check_case('axes', self.pointer_binary)

    def test_the_tablet_target_follows_the_pointer_order(self):
        self.check_case('target', self.pointer_binary)

    def test_a_stroke_whose_window_closed_sends_nothing_until_it_ends(self):
        self.check_case('orphan', self.pointer_binary)

    def test_the_query_says_whether_the_pen_focus_takes_a_tablet(self):
        self.check_case('query', self.pointer_binary)

    def test_gamescope_gets_the_pointer_even_with_a_tablet(self):
        self.check_case('gamescope', self.pointer_binary)

    def test_a_tool_switch_keeps_the_route_and_announces_the_new_tool(self):
        self.check_case('tool', self.pointer_binary)

    def test_the_pointer_moves_to_the_pen_as_it_comes_and_where_it_leaves(self):
        self.check_case('warp', self.pointer_binary)

    def test_a_pointer_lock_that_ends_while_the_pen_hovers_moves_the_pointer_to_it(self):
        self.check_case('unlock', self.pointer_binary)

    def test_a_pointer_lock_that_ends_during_a_press_moves_the_pointer_once_the_pen_hovered(self):
        self.check_case('unlockpressed', self.pointer_binary)


if __name__ == '__main__':
    unittest.main()
