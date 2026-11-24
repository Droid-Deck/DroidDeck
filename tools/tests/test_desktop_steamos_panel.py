import os
from pathlib import Path
import subprocess
import tempfile
import unittest


DESKTOP = (Path(__file__).resolve().parents[1] / 'linuxfs/desktop/droiddeck-desktop').read_text()
START = 'dd_panel_rc="${XDG_CONFIG_HOME:-$HOME/.config}/plasma-org.kde.plasma.desktop-appletsrc"'
RC = 'plasma-org.kde.plasma.desktop-appletsrc'


class DesktopSteamosPanelTest(unittest.TestCase):
    def run_block(self, home, content):
        config = home / '.config'
        config.mkdir()
        if content is not None:
            (config / RC).write_text(content)
        block = DESKTOP[DESKTOP.index(START):]
        block = block[:block.index('\nfi\n') + 4]
        environment = {key: value for key, value in os.environ.items() if key != 'XDG_CONFIG_HOME'}
        environment['HOME'] = str(home)
        result = subprocess.run(['bash', '-c', block], env=environment, capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        return config

    def test_a_kubuntu_panel_is_set_aside_so_plasma_lays_out_the_steamos_one(self):
        with tempfile.TemporaryDirectory() as home:
            config = self.run_block(Path(home), '[Containments][2][Applets][3][Configuration][General]\nicon=start-here-kubuntu\n')
            self.assertFalse((config / RC).exists())
            self.assertTrue((config / (RC + '.kubuntu-layout')).is_file())

    def test_a_steamos_or_customised_panel_is_left_alone(self):
        for content in ('icon=distributor-logo-steamdeck\n', 'icon=start-here-kubuntu-custom\n', 'icon=my-logo\n'):
            with tempfile.TemporaryDirectory() as home:
                config = self.run_block(Path(home), content)
                self.assertEqual(content, (config / RC).read_text())
                self.assertFalse((config / (RC + '.kubuntu-layout')).exists())

    def test_a_profile_without_a_panel_is_untouched(self):
        with tempfile.TemporaryDirectory() as home:
            config = self.run_block(Path(home), None)
            self.assertEqual([], list(config.iterdir()))

    def test_the_panel_is_checked_before_plasma_starts(self):
        self.assertLess(DESKTOP.index(START), DESKTOP.index('dbus-run-session -- startplasma-wayland'))


if __name__ == '__main__':
    unittest.main()
