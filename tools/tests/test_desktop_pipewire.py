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
    def run_lifecycle_block(self, start, end, bottom):
        block = DESKTOP[DESKTOP.index(start):DESKTOP.index(end, DESKTOP.index(start))]
        block = block.replace(HELPER, 'pipewire')
        script = '''
pipewire() {
    if [ "$1" = env ]; then
        printf ':\\n'
    else
        printf 'call:%s\\n' "$1"
    fi
}
''' + block
        environment = dict(os.environ, BL_BOTTOM_DESKTOP='1' if bottom else '0')
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


if __name__ == '__main__':
    unittest.main()
