import os
from pathlib import Path
import subprocess
import unittest


LINUXFS = Path(__file__).resolve().parents[1] / 'linuxfs'
DESKTOP = (LINUXFS / 'desktop/droiddeck-desktop').read_text()
SESSION = (LINUXFS / 'overlay/usr/local/bin/droiddeck-session').read_text()
BOTTOM_DESKTOP = (LINUXFS / 'overlay/usr/local/bin/droiddeck-bottom-desktop').read_text()
BOTTOM_SESSION = (
    Path(__file__).resolve().parents[2]
    / 'app/src/main/java/com/droiddeck/launcher/session/BottomDesktop.kt'
).read_text()
HELPER = '/usr/local/bin/droiddeck-pipewire'


class DesktopPipeWireTest(unittest.TestCase):
    def run_lifecycle_block(self, start, end, bottom, start_status=0, probe_env=False):
        block = DESKTOP[DESKTOP.index(start):DESKTOP.index(end, DESKTOP.index(start))]
        block = block.replace(HELPER, 'pipewire')
        script = '''
pipewire() {
    if [ "$1" = env ]; then
        printf 'export PW_ENV=set\\n'
    else
        printf 'call:%s\\n' "$1"
        [ "$1" != start ] || return "$PW_START_STATUS"
    fi
}
''' + block + ('echo "env:${PW_ENV:-unset}"\n' if probe_env else '')
        environment = dict(os.environ, BL_BOTTOM_DESKTOP='1' if bottom else '0',
                           PW_START_STATUS=str(start_status))
        result = subprocess.run(['bash', '-c', script], env=environment, capture_output=True,
                                text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.splitlines()

    def test_desktop_serves_pipewire_before_plasma_and_stops_it_after(self):
        start = DESKTOP.index(HELPER + ' start')
        env = DESKTOP.index(HELPER + ' env')
        plasma = DESKTOP.index('dbus-run-session -- startplasma-wayland')
        stop = DESKTOP.index(HELPER + ' stop')
        self.assertLess(start, env)
        self.assertLess(env, plasma)
        self.assertLess(plasma, stop)
        self.assertLess(stop, DESKTOP.index('droiddeck-steam-launch bigpicture'))

    def test_steam_session_keeps_its_pipewire_startup(self):
        self.assertEqual(1, SESSION.count(HELPER + ' start'))
        self.assertIn('if [ "$mode" = steam ]; then\n    if ' + HELPER + ' start', SESSION)

    def test_bottom_desktop_starts_and_stops_its_pipewire_service(self):
        self.assertIn('export BL_BOTTOM_DESKTOP=1', BOTTOM_DESKTOP)
        self.assertLess(BOTTOM_DESKTOP.index('export BL_BOTTOM_DESKTOP=1'),
                        BOTTOM_DESKTOP.index('exec /usr/local/bin/droiddeck-desktop'))
        self.assertIn('File(context.filesDir, ".wayland-rt-bottom")', BOTTOM_SESSION)
        self.assertNotIn('if [ "${BL_BOTTOM_DESKTOP:-0}" != 1 ]; then', DESKTOP)
        for bottom in (False, True):
            self.assertEqual(['call:start'], self.run_lifecycle_block(
                'if ' + HELPER + ' start',
                'echo "== droiddeck-desktop: Plasma on $WAYLAND_DISPLAY"', bottom))
            self.assertEqual(['call:stop'], self.run_lifecycle_block(
                HELPER + ' stop',
                'echo "== droiddeck-desktop: Plasma ended ($rc)"', bottom))

    def test_successful_start_exports_the_client_environment(self):
        for bottom in (False, True):
            self.assertEqual(['call:start', 'env:set'], self.run_lifecycle_block(
                'if ' + HELPER + ' start',
                'echo "== droiddeck-desktop: Plasma on $WAYLAND_DISPLAY"', bottom, probe_env=True))

    def test_failed_start_reports_it_and_leaves_the_environment_alone(self):
        for bottom in (False, True):
            self.assertEqual(
                ['call:start', '== pipewire: not running; screen capture has no picture', 'env:unset'],
                self.run_lifecycle_block(
                    'if ' + HELPER + ' start',
                    'echo "== droiddeck-desktop: Plasma on $WAYLAND_DISPLAY"', bottom,
                    start_status=1, probe_env=True))


if __name__ == '__main__':
    unittest.main()
