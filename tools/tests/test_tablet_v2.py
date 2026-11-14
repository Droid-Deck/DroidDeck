import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
WAYLANDCOMP = REPO / 'app/src/main/cpp/waylandcomp'


def pkg_config(flag):
    tool = shutil.which('pkg-config')
    if tool is None:
        return None
    result = subprocess.run([tool, flag, 'wayland-server', 'wayland-client'], capture_output=True, text=True)
    return result.stdout.split() if result.returncode == 0 else None


class TabletV2Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        scanner = shutil.which('wayland-scanner')
        cflags, libs = pkg_config('--cflags'), pkg_config('--libs')
        if compiler is None or scanner is None or cflags is None or libs is None:
            raise unittest.SkipTest('A host C compiler, pkg-config, wayland-scanner and libwayland-dev are required')
        cls.temp = tempfile.TemporaryDirectory(prefix='droiddeck-tablet-v2-')
        cls.addClassCleanup(cls.temp.cleanup)
        folder = Path(cls.temp.name)
        subprocess.run([scanner, 'client-header', str(WAYLANDCOMP / 'protocols/tablet-v2.xml'),
                        str(folder / 'tablet-v2-client-protocol.h')], check=True, capture_output=True)
        cls.binary = folder / 'tablet-v2-test'
        build = subprocess.run([compiler, '-std=gnu11', '-Wall', '-Wextra', '-Werror', '-Wno-unused-parameter',
                                '-Wno-missing-field-initializers', '-I', str(folder), '-I', str(WAYLANDCOMP / 'generated'),
                                '-I', str(WAYLANDCOMP / 'src'), *cflags, str(WAYLANDCOMP / 'src/wl_tablet.c'),
                                str(WAYLANDCOMP / 'generated/tablet-v2-protocol.c'),
                                str(REPO / 'tools/tests/tablet_v2_harness.c'), *libs, '-o', str(cls.binary)],
                               capture_output=True, text=True)
        if build.returncode != 0:
            raise AssertionError(build.stdout + build.stderr)

    def check_case(self, case):
        result = subprocess.run([str(self.binary), case], capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual('PASS', result.stdout.strip().splitlines()[-1])

    def test_seats_get_the_tablet_once_capabilities_arrive(self):
        self.check_case('bind')

    def test_tools_are_announced_on_first_use_with_their_capabilities(self):
        self.check_case('tools')

    def test_frames_carry_only_changed_axes_and_proximity_in_the_full_state(self):
        self.check_case('hover')

    def test_contact_carries_pressure_and_tilt_and_is_not_clipped(self):
        self.check_case('down')

    def test_right_is_btn_stylus2_and_middle_is_btn_stylus(self):
        self.check_case('buttons')

    def test_a_new_surface_or_client_gets_the_tool_after_the_old_one_lost_it(self):
        self.check_case('focus')

    def test_a_tool_switch_releases_the_old_tool_first(self):
        self.check_case('switch')

    def test_set_cursor_needs_the_proximity_serial(self):
        self.check_case('cursor')

    def test_a_destroyed_surface_gets_up_releases_and_proximity_out(self):
        self.check_case('surface')

    def test_a_client_disconnecting_mid_stroke_leaves_nothing_behind(self):
        self.check_case('disconnect')

    def test_a_seat_bound_late_gets_the_tablet_then_the_tools(self):
        self.check_case('late')

    def test_every_binding_gets_its_own_tablet(self):
        self.check_case('bindings')

    def test_a_tool_destroyed_in_proximity_enters_again_after_a_rebind(self):
        self.check_case('toolgone')

    def test_a_seat_bound_in_proximity_waits_for_the_next_proximity(self):
        self.check_case('rebind')


if __name__ == '__main__':
    unittest.main()
