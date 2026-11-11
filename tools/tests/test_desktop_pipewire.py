from pathlib import Path
import unittest


LINUXFS = Path(__file__).resolve().parents[1] / 'linuxfs'
DESKTOP = (LINUXFS / 'desktop/droiddeck-desktop').read_text()
SESSION = (LINUXFS / 'overlay/usr/local/bin/droiddeck-session').read_text()
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

    def test_steam_mode_keeps_its_own_pipewire_and_other_modes_have_none(self):
        self.assertEqual(1, SESSION.count(HELPER + ' start'))
        self.assertIn('if [ "$mode" = steam ]; then\n    if ' + HELPER + ' start', SESSION)


if __name__ == '__main__':
    unittest.main()
