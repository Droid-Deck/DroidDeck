from pathlib import Path
import contextlib
import io
import json
import os
import runpy
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-steam-share'
SHARE = runpy.run_path(str(SCRIPT))

CONFIG = ('"InstallConfigStore"\n{\n\t"Software"\n\t{\n\t\t"Valve"\n\t\t{\n\t\t\t"Steam"\n\t\t\t{\n'
          '\t\t\t\t"CompatToolMapping"\n\t\t\t\t{\n%s\t\t\t\t}\n\t\t\t}\n\t\t}\n\t}\n}\n')


def mapping(**tools):
    return ''.join('\t\t\t\t\t"%s"\n\t\t\t\t\t{\n\t\t\t\t\t\t"name"\t\t"%s"\n\t\t\t\t\t\t"config"\t\t""\n'
                   '\t\t\t\t\t\t"priority"\t\t"250"\n\t\t\t\t\t}\n' % (app.lstrip('_'), name)
                   for app, name in tools.items())


def manifest(app, folder, updated=100, flags=4):
    return ('"AppState"\n{\n\t"appid"\t\t"%s"\n\t"name"\t\t"%s"\n\t"StateFlags"\t\t"%d"\n'
            '\t"installdir"\t\t"%s"\n\t"LastUpdated"\t\t"%d"\n}\n' % (app, folder, flags, folder, updated))


class SteamShareTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        home = Path(self.temp.name)
        self.arm = home / 'arm/Steam'
        self.x64 = home / 'x64/Steam'
        self.card = home / 'card'
        self.shared = home / 'x64/shared'
        for root, tool in ((self.arm, 'droiddeck-proton-arm64'), (self.x64, 'droiddeck-proton-a64')):
            (root / 'steamapps/common').mkdir(parents=True)
            (root / 'config').mkdir()
            (root / 'compatibilitytools.d/proton').mkdir(parents=True)
            (root / 'compatibilitytools.d/proton/compatibilitytool.vdf').write_text(
                '"compatibilitytools"\n{\n\t"compat_tools"\n\t{\n\t\t"%s"\n\t\t{\n\t\t}\n\t}\n}\n' % tool)
        (self.arm / 'config/config.vdf').write_text(CONFIG % mapping(_0='droiddeck-proton-arm64', _413150='droiddeck-fex'))
        (self.x64 / 'config/config.vdf').write_text(CONFIG % '')
        self.install(self.arm, '413150', 'Stardew Valley')
        self.install(self.arm, '13210', 'Unreal Tournament 3')
        self.install(self.arm, '1493710', 'Proton - Experimental')

    def install(self, root, app, folder, updated=100):
        (root / 'steamapps/common' / folder).mkdir(parents=True, exist_ok=True)
        (root / 'steamapps/common' / folder / 'game.bin').write_text('x')
        (root / ('steamapps/appmanifest_%s.acf' % app)).write_text(manifest(app, folder, updated))

    def run_share(self, here):
        with contextlib.redirect_stdout(io.StringIO()):
            return SHARE['main']([here, str(self.arm), str(self.x64), str(self.card), str(self.shared)])

    def view(self, *parts):
        return self.shared.joinpath('arm64', 'steamapps', *parts)

    def test_x64_client_sees_arm64_games_in_place(self):
        self.assertEqual(self.run_share('x86-64'), 0)
        self.assertEqual(os.readlink(self.view('common/Stardew Valley')), str(self.arm / 'steamapps/common/Stardew Valley'))
        self.assertTrue(self.view('appmanifest_413150.acf').is_file())
        self.assertTrue(self.view('appmanifest_13210.acf').is_file())
        self.assertFalse(self.view('appmanifest_1493710.acf').exists())
        folders = (self.x64 / 'steamapps/libraryfolders.vdf').read_text()
        self.assertIn(str(self.shared / 'arm64'), folders)
        self.assertIn('ARM64 client games', folders)

    def test_tools_follow_the_other_client(self):
        self.run_share('x86-64')
        tools = SHARE['entries']((self.x64 / 'config/config.vdf').read_text())
        self.assertEqual(tools['413150'], 'droiddeck-direct')
        self.assertEqual(tools['13210'], 'droiddeck-proton-a64')

    def test_a_proton_pick_stays_when_the_other_client_uses_another_proton(self):
        (self.x64 / 'config/config.vdf').write_text(CONFIG % mapping(_13210='droiddeck-proton-11-a64'))
        self.run_share('x86-64')
        self.assertEqual(SHARE['entries']((self.x64 / 'config/config.vdf').read_text())['13210'], 'droiddeck-proton-11-a64')

    def test_an_automatic_proton_gives_way_to_the_installed_linux_build(self):
        self.install(self.x64, '550', 'Left 4 Dead 2')
        (self.x64 / 'config/config.vdf').write_text(CONFIG % mapping(_550='droiddeck-direct'))
        (self.arm / 'config/config.vdf').write_text(CONFIG % mapping(_0='droiddeck-proton-arm64', _550='droiddeck-proton-arm64'))
        self.run_share('arm64')
        self.assertEqual(SHARE['entries']((self.arm / 'config/config.vdf').read_text())['550'], 'droiddeck-fex')

    def test_an_x64_proton_pick_reaches_the_arm64_client(self):
        self.install(self.x64, '550', 'Left 4 Dead 2')
        (self.x64 / 'config/config.vdf').write_text(CONFIG % mapping(_550='droiddeck-proton-a64'))
        self.run_share('arm64')
        self.assertEqual(SHARE['entries']((self.arm / 'config/config.vdf').read_text())['550'], 'droiddeck-proton-arm64')

    def test_a_tool_the_user_chose_is_kept(self):
        (self.x64 / 'config/config.vdf').write_text(CONFIG % mapping(_13210='my-proton'))
        self.run_share('x86-64')
        self.assertEqual(SHARE['entries']((self.x64 / 'config/config.vdf').read_text())['13210'], 'my-proton')

    def test_own_install_wins(self):
        self.install(self.x64, '413150', 'Stardew Valley')
        self.run_share('x86-64')
        self.assertFalse(self.view('appmanifest_413150.acf').exists())
        self.assertFalse(os.path.lexists(self.view('common/Stardew Valley')))

    def test_newer_update_goes_back_to_the_source(self):
        self.run_share('x86-64')
        self.view('appmanifest_413150.acf').write_text(manifest('413150', 'Stardew Valley', 200))
        self.run_share('x86-64')
        self.assertIn('"LastUpdated"\t\t"200"', (self.arm / 'steamapps/appmanifest_413150.acf').read_text())

    def test_uninstall_from_the_view_hides_until_reinstalled(self):
        self.run_share('x86-64')
        self.view('appmanifest_13210.acf').unlink()
        self.run_share('x86-64')
        self.assertFalse(os.path.lexists(self.view('common/Unreal Tournament 3')))
        self.assertTrue((self.arm / 'steamapps/common/Unreal Tournament 3/game.bin').is_file())
        self.run_share('x86-64')
        self.assertFalse(self.view('appmanifest_13210.acf').exists())
        self.install(self.arm, '13210', 'Unreal Tournament 3', 300)
        self.run_share('x86-64')
        self.assertTrue(self.view('appmanifest_13210.acf').is_file())

    def test_files_deleted_through_the_view_drop_the_source_manifest(self):
        self.run_share('x86-64')
        (self.arm / 'steamapps/common/Unreal Tournament 3/game.bin').unlink()
        self.view('appmanifest_13210.acf').unlink()
        self.run_share('x86-64')
        self.assertFalse((self.arm / 'steamapps/appmanifest_13210.acf').exists())

    def test_removed_source_leaves_the_view(self):
        self.run_share('x86-64')
        (self.arm / 'steamapps/appmanifest_413150.acf').unlink()
        self.run_share('x86-64')
        self.assertFalse(self.view('appmanifest_413150.acf').exists())
        self.assertFalse(os.path.lexists(self.view('common/Stardew Valley')))
        state = json.loads((self.shared / 'arm64/.droiddeck-shared.json').read_text())
        self.assertNotIn('413150', state['shared'])

    def test_arm64_client_sees_x64_games(self):
        self.install(self.x64, '550', 'Left 4 Dead 2')
        (self.x64 / 'config/config.vdf').write_text(CONFIG % mapping(_550='droiddeck-direct'))
        self.run_share('arm64')
        self.assertTrue(self.shared.joinpath('x86-64/steamapps/appmanifest_550.acf').is_file())
        self.assertEqual(SHARE['entries']((self.arm / 'config/config.vdf').read_text())['550'], 'droiddeck-fex')
        self.assertIn('x86-64 client games', (self.arm / 'steamapps/libraryfolders.vdf').read_text())

    def test_an_empty_view_is_not_offered(self):
        self.run_share('x86-64')
        for app in ('413150', '13210', '1493710'):
            (self.arm / ('steamapps/appmanifest_%s.acf' % app)).unlink()
        self.run_share('x86-64')
        self.assertNotIn(str(self.shared / 'arm64'), (self.x64 / 'steamapps/libraryfolders.vdf').read_text())

    def test_unfinished_installs_are_not_shared(self):
        (self.arm / 'steamapps/appmanifest_413150.acf').write_text(manifest('413150', 'Stardew Valley', flags=1026))
        self.run_share('x86-64')
        self.assertFalse(self.view('appmanifest_413150.acf').exists())

    def test_bad_arguments(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(SHARE['main'](['mips']), 2)


if __name__ == '__main__':
    unittest.main()
