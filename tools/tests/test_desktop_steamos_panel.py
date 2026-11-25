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


def run(script, home, layout_installed=True):
    environment = {key: value for key, value in os.environ.items() if key != 'XDG_CONFIG_HOME'}
    environment['HOME'] = str(home)
    prefix = 'dd_layout_installed=1\\n' if layout_installed else ''
    result = subprocess.run(['bash', '-c', prefix + script], env=environment, capture_output=True, text=True, timeout=5)
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

    def test_an_earlier_backup_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as home:
            config = Path(home) / '.config'
            config.mkdir()
            (config / (RC + '.before-steamos-2')).write_text('original backup')
            (config / RC).write_text('icon=start-here-kubuntu\n')
            run(block('dd_panel_rc=', '\nfi\n'), Path(home))
            self.assertEqual('original backup', (config / (RC + '.before-steamos-2')).read_text())
            self.assertFalse((config / RC).exists())
            self.assertEqual(1, len([entry for entry in config.iterdir() if entry.name.startswith(RC + '.before-steamos-2.')]))
            self.assertEqual('steamos-2', (config / STAMP).read_text().strip())

    def test_the_stamp_is_not_written_when_the_panel_could_not_be_moved(self):
        with tempfile.TemporaryDirectory() as home:
            config = Path(home) / '.config'
            config.mkdir()
            (config / RC).write_text('icon=start-here-kubuntu\n')
            script = block('dd_panel_rc=', '\nfi\n').replace('mv "$dd_panel_rc"', 'false "$dd_panel_rc"')
            run(script, Path(home))
            self.assertTrue((config / RC).exists())
            self.assertFalse((config / STAMP).exists())

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

    def test_the_layout_keeps_plasmas_stock_wide_screen_cap_and_input_method_panel(self):
        self.assertIn('const maximumAspectRatio = 21 / 9;', OVERLAY_LAYOUT)
        self.assertIn('panel.maximumLength = maximumWidth;', OVERLAY_LAYOUT)
        self.assertIn('org.kde.plasma.kimpanel', OVERLAY_LAYOUT)
        for language in ('ja', 'ko', 'th', 'zh_CN', 'zh_TW', 'hi'):
            self.assertIn('"' + language + '"', OVERLAY_LAYOUT)

    def test_panel_migration_is_skipped_when_layout_directory_is_missing(self):
        with tempfile.TemporaryDirectory() as home:
            home = Path(home)
            config = home / '.config'
            config.mkdir()
            (config / RC).write_text('existing panel\\n')
            layout_dir = home / 'missing-layout'
            script = block('dd_layout=', '# startplasma applies this file')
            script = script.replace('dd_layout=' + LAYOUT_DIR, 'dd_layout=' + layout_dir.as_posix())
            run(script, home)
            self.assertEqual('existing panel\\n', (config / RC).read_text())
            self.assertFalse((config / (RC + '.before-steamos-2')).exists())
            self.assertFalse((config / STAMP).exists())

    def test_panel_migration_is_skipped_when_layout_write_fails(self):
        with tempfile.TemporaryDirectory() as home:
            home = Path(home)
            config = home / '.config'
            config.mkdir()
            (config / RC).write_text('existing panel\\n')
            layout_dir = home / 'layouts'
            layout_dir.mkdir()
            (layout_dir / 'org.kde.plasma.desktop-layout.js').mkdir()
            script = block('dd_layout=', '# startplasma applies this file')
            script = script.replace('dd_layout=' + LAYOUT_DIR, 'dd_layout=' + layout_dir.as_posix())
            run(script, home)
            self.assertEqual('existing panel\\n', (config / RC).read_text())
            self.assertFalse((config / (RC + '.before-steamos-2')).exists())
            self.assertFalse((config / STAMP).exists())

    def test_panel_migration_runs_after_layout_write_succeeds(self):
        with tempfile.TemporaryDirectory() as home:
            home = Path(home)
            config = home / '.config'
            config.mkdir()
            (config / RC).write_text('old panel\\n')
            layout_dir = home / 'layouts'
            layout_dir.mkdir()
            script = block('dd_layout=', '# startplasma applies this file')
            script = script.replace('dd_layout=' + LAYOUT_DIR, 'dd_layout=' + layout_dir.as_posix())
            run(script, home)
            self.assertTrue((layout_dir / 'org.kde.plasma.desktop-layout.js').is_file())
            self.assertFalse((config / RC).exists())
            self.assertEqual('old panel\\n', (config / (RC + '.before-steamos-2')).read_text())
            self.assertEqual('steamos-2', (config / STAMP).read_text().strip())

    def test_the_panel_is_prepared_before_plasma_starts(self):
        for marker in ('dd_layout=', 'dd_panel_rc='):
            self.assertLess(DESKTOP.index(marker), DESKTOP.index('dbus-run-session -- startplasma-wayland'))


if __name__ == '__main__':
    unittest.main()
