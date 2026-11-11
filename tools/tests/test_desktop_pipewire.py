from pathlib import Path
import unittest


LINUXFS = Path(__file__).resolve().parents[1] / 'linuxfs'
DESKTOP = (LINUXFS / 'desktop/droiddeck-desktop').read_text()
SESSION = (LINUXFS / 'overlay/usr/local/bin/droiddeck-session').read_text()
BOTTOM_DESKTOP = (LINUXFS / 'overlay/usr/local/bin/droiddeck-bottom-desktop').read_text()
HELPER = '/usr/local/bin/droiddeck-pipewire'


class DesktopPipeWireTest(unittest.TestCase):
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

    def test_bottom_desktop_reuses_the_top_sessions_pipewire(self):
        start_guard = 'if [ "${BL_BOTTOM_DESKTOP:-0}" != 1 ]; then\n  if ' + HELPER + ' start'
        stop_guard = 'if [ "${BL_BOTTOM_DESKTOP:-0}" != 1 ]; then\n  ' + HELPER + ' stop'
        self.assertIn(start_guard, DESKTOP)
        self.assertIn(stop_guard, DESKTOP)
        self.assertIn('export BL_BOTTOM_DESKTOP=1', BOTTOM_DESKTOP)
        self.assertLess(BOTTOM_DESKTOP.index('export BL_BOTTOM_DESKTOP=1'),
                        BOTTOM_DESKTOP.index('exec /usr/local/bin/droiddeck-desktop'))


if __name__ == '__main__':
    unittest.main()
