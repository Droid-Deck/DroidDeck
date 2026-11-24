import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
DESKTOP = (ROOT / 'linuxfs/desktop/droiddeck-desktop').read_text()
OVERLAY_LAYOUT = (ROOT / 'ubuntu/overlay-desktop/usr/share/plasma/look-and-feel/com.valve.vapor.desktop'
                  '/contents/layouts/org.kde.plasma.desktop-layout.js').read_text()
LAYOUT_DIR = '/usr/share/plasma/look-and-feel/com.valve.vapor.desktop/contents/layouts'
RC = 'plasma-org.kde.plasma.desktop-appletsrc'
STAMP = 'droiddeck-panel-layout'


def block(first, last):
    start = DESKTOP.index(first)
    return DESKTOP[start:DESKTOP.index(last, start) + len(last)]


def run(script, home):
    environment = {key: value for key, value in os.environ.items() if key != 'XDG_CONFIG_HOME'}
    environment['HOME'] = str(home)
    result = subprocess.run(['bash', '-c', script], env=environment, capture_output=True, text=True, timeout=5)
    if result.returncode != 0:
        raise AssertionError(result.stderr)


class DesktopSteamosPanelTest(unittest.TestCase):
    def reset(self, home, panel=None, stamp=None):
        config = home / '.config'
        config.mkdir()
        if panel is not None:
            (config / RC).write_text(panel)
        if stamp is not None:
            (config / STAMP).write_text(stamp)
        run(block('dd_panel_rc=', '\nfi\n'), home)
        return config

    def test_an_older_panel_is_set_aside_once_so_plasma_lays_out_the_current_one(self):
        for panel in ('icon=start-here-kubuntu\n', 'icon=distributor-logo-steamdeck\n'):
            with tempfile.TemporaryDirectory() as home:
                config = self.reset(Path(home), panel)
                self.assertFalse((config / RC).exists())
                self.assertEqual(panel, (config / (RC + '.before-steamos-2')).read_text())
                self.assertEqual('steamos-2', (config / STAMP).read_text().strip())

    def test_a_panel_laid_out_by_this_version_is_left_alone(self):
        with tempfile.TemporaryDirectory() as home:
            config = self.reset(Path(home), 'icon=my-logo\n', 'steamos-2\n')
            self.assertEqual('icon=my-logo\n', (config / RC).read_text())
            self.assertFalse((config / (RC + '.before-steamos-2')).exists())

    def test_a_profile_without_a_panel_only_gets_the_stamp(self):
        with tempfile.TemporaryDirectory() as home:
            config = self.reset(Path(home))
            self.assertEqual([STAMP], [entry.name for entry in config.iterdir()])

    def test_the_layout_written_at_each_start_is_the_overlays(self):
        with tempfile.TemporaryDirectory() as home:
            target = Path(home) / 'layouts'
            target.mkdir()
            run(block('dd_layout=', '\nfi\n').replace(LAYOUT_DIR, target.as_posix()), home)
            written = (target / 'org.kde.plasma.desktop-layout.js').read_text()
            self.assertEqual(OVERLAY_LAYOUT.replace('\r\n', '\n').rstrip('\n'), written.rstrip('\n'))

    def test_the_layout_follows_plasmas_stock_panel_with_the_steam_deck_launcher(self):
        for widget in ('kickoff', 'pager', 'icontasks', 'marginsseparator', 'systemtray', 'digitalclock', 'showdesktop'):
            self.assertIn('org.kde.plasma.' + widget, OVERLAY_LAYOUT)
        self.assertIn('distributor-logo-steamdeck', OVERLAY_LAYOUT)
        for pin in ('steamdeck-steam', 'systemsettings', 'org.kde.discover', 'preferred://filemanager', 'preferred://browser'):
            self.assertIn(pin, OVERLAY_LAYOUT)

    def test_the_panel_is_prepared_before_plasma_starts(self):
        for marker in ('dd_layout=', 'dd_panel_rc='):
            self.assertLess(DESKTOP.index(marker), DESKTOP.index('dbus-run-session -- startplasma-wayland'))


if __name__ == '__main__':
    unittest.main()
