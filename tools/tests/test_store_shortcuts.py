"""The shortcuts writer and a store install: the app registers a game GOG, Epic or Amazon
installed through the same listing as an added folder, pointing the shortcut at the game's
launcher .bat when it has one. These pin the parts of the writer that contract relies on."""
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import zlib

BIN = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin'


def load(name):
    loader = importlib.machinery.SourceFileLoader(name, str(BIN / name))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


shortcuts = load('droiddeck-steam-shortcuts')

FOLDER = '/root/Games/Stores/Epic/Celeste'
LAUNCHER = FOLDER + '/.droiddeck-launch.bat'


def app_id(exe, name):
    """The appid the app computes for a shortcut (AddedGames.scanGame): CRC32 of the quoted exe
    and the name, high bit set - what Steam itself derives for a shortcut it is given."""
    return (zlib.crc32(('"%s"' % exe + name).encode()) | 0x80000000) & 0xFFFFFFFF


class StoreShortcutsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.steam = self.root / 'Steam'
        self.config = self.steam / 'userdata/123/config'
        self.config.mkdir(parents=True)
        self.listing = self.root / 'added-games.json'

    def write(self, *games):
        self.listing.write_text(json.dumps(list(games)))
        subprocess.run(['python3', str(BIN / 'droiddeck-steam-shortcuts'), str(self.steam), str(self.listing)],
                       check=True, capture_output=True)
        return shortcuts.parse((self.config / 'shortcuts.vdf').read_bytes())['shortcuts']

    def test_a_launcher_bat_is_the_shortcuts_exe_and_the_folder_its_start_dir(self):
        game = dict(name='Celeste', exe=LAUNCHER, folder=FOLDER, dir=FOLDER, appid=app_id(LAUNCHER, 'Celeste'), seen=0)
        entries = self.write(game)
        entry = next(iter(entries.values()))
        self.assertEqual('"%s"' % LAUNCHER, entry['Exe'])
        self.assertEqual('"%s"' % FOLDER, entry['StartDir'])
        self.assertEqual('Celeste', entry['AppName'])
        self.assertIn('droiddeck-app', entry['tags'].values())

    def test_the_appid_the_app_computes_is_the_one_written(self):
        appid = app_id(LAUNCHER, 'Celeste')
        entries = self.write(dict(name='Celeste', exe=LAUNCHER, folder=FOLDER, dir=FOLDER, appid=appid, seen=0))
        entry = next(iter(entries.values()))
        self.assertEqual(appid, entry['appid'] & 0xFFFFFFFF)

    def test_an_uninstalled_game_leaves_the_listing_and_the_shortcut_goes_with_it(self):
        game = dict(name='Celeste', exe=LAUNCHER, folder=FOLDER, dir=FOLDER, appid=app_id(LAUNCHER, 'Celeste'), seen=0)
        self.assertEqual(1, len(self.write(game)))
        self.assertEqual(0, len(self.write()))


if __name__ == '__main__':
    unittest.main()
