import importlib.machinery
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

BIN = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin'


def load(name):
    loader = importlib.machinery.SourceFileLoader(name, str(BIN / name))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


shortcuts = load('droiddeck-steam-shortcuts')

APPID = 0x87654321
GUEST = '/root/Games/PC/Example'


class ShortcutsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.steam = self.root / 'Steam'
        self.config = self.steam / 'userdata/123/config'
        self.config.mkdir(parents=True)
        self.listing = self.root / 'added-games.json'

    def game(self, exe='Example.exe', seen=0, appid=APPID, name='Example'):
        path = GUEST + '/' + exe
        return dict(name=name, exe=path, folder=GUEST, dir=path.rsplit('/', 1)[0], appid=appid, seen=seen)

    def run_writer(self, *games):
        self.listing.write_text(json.dumps(list(games)))
        subprocess.run(['python3', str(BIN / 'droiddeck-steam-shortcuts'), str(self.steam), str(self.listing)],
                       check=True, capture_output=True)
        return self.entries()

    def entries(self):
        data = shortcuts.parse((self.config / 'shortcuts.vdf').read_bytes())['shortcuts']
        return {value['appid'] & 0xFFFFFFFF: value for value in data.values()}

    def steam_edits(self, appid=APPID, **fields):
        path = self.config / 'shortcuts.vdf'
        data = shortcuts.parse(path.read_bytes())['shortcuts']
        for value in data.values():
            if value['appid'] & 0xFFFFFFFF == appid:
                value.update(fields)
        path.write_bytes(shortcuts.write({'shortcuts': data}))

    def record(self):
        return json.loads((self.config / shortcuts.RECORD).read_text())

    def test_settings_changed_in_steam_survive_its_restart(self):
        self.run_writer(self.game())
        edits = dict(Exe='"%s/bin/Other.exe"' % GUEST, StartDir='"%s"' % GUEST, LaunchOptions='DXVK_HUD=1 %command%',
                     AppName='Example GOTY', icon='/root/custom.png', IsHidden=1, AllowOverlay=0, LastPlayTime=1791372037,
                     sortas='example', tags={'0': shortcuts.TAG, '1': 'Favorites'})
        self.steam_edits(**edits)
        entry = self.run_writer(self.game())[APPID]
        for field, value in edits.items():
            self.assertEqual(value, entry[field], field)
        entry = self.run_writer(self.game())[APPID]
        for field, value in edits.items():
            self.assertEqual(value, entry[field], field)

    def test_steam_edit_is_recorded_for_the_app(self):
        self.run_writer(self.game())
        self.steam_edits(Exe='"%s/bin/Other.exe"' % GUEST)
        self.run_writer(self.game())
        state = self.record()['games'][str(APPID)]
        self.assertEqual('"%s/Example.exe"' % GUEST, state['app']['Exe'])
        self.assertEqual('"%s/bin/Other.exe"' % GUEST, state['steam']['Exe'])
        self.assertEqual(1, state['rev'])
        self.run_writer(self.game())
        self.assertEqual(1, self.record()['games'][str(APPID)]['rev'])

    def test_the_app_taking_steams_target_keeps_steams_start_in(self):
        self.run_writer(self.game())
        self.steam_edits(Exe='"%s/bin/Other.exe"' % GUEST, StartDir='"%s/data"' % GUEST)
        self.run_writer(self.game())
        entry = self.run_writer(self.game('bin/Other.exe', seen=1))[APPID]
        self.assertEqual('"%s/bin/Other.exe"' % GUEST, entry['Exe'])
        self.assertEqual('"%s/data"' % GUEST, entry['StartDir'])

    def test_an_exe_chosen_in_the_app_replaces_steams_target(self):
        self.run_writer(self.game())
        self.steam_edits(Exe='"%s/bin/Other.exe"' % GUEST, LaunchOptions='-windowed')
        self.run_writer(self.game())
        entry = self.run_writer(self.game('tools/Launcher.exe'))[APPID]
        self.assertEqual('"%s/tools/Launcher.exe"' % GUEST, entry['Exe'])
        self.assertEqual('"%s/tools"' % GUEST, entry['StartDir'])
        self.assertEqual('-windowed', entry['LaunchOptions'])

    def test_going_back_to_the_apps_exe_after_taking_steams_wins(self):
        self.run_writer(self.game())
        self.steam_edits(Exe='"%s/bin/Other.exe"' % GUEST)
        self.run_writer(self.game())
        entry = self.run_writer(self.game(seen=1))[APPID]
        self.assertEqual('"%s/Example.exe"' % GUEST, entry['Exe'])
        self.assertEqual('"%s"' % GUEST, entry['StartDir'])

    def test_a_later_steam_edit_is_kept_until_the_app_takes_it(self):
        self.run_writer(self.game())
        self.steam_edits(Exe='"%s/bin/Other.exe"' % GUEST)
        self.run_writer(self.game())
        self.run_writer(self.game(seen=1))
        self.steam_edits(Exe='"%s/bin/Other.exe"' % GUEST)
        entry = self.run_writer(self.game(seen=1))[APPID]
        self.assertEqual('"%s/bin/Other.exe"' % GUEST, entry['Exe'])
        self.assertEqual(2, self.record()['games'][str(APPID)]['rev'])

    def test_a_game_that_goes_away_comes_back_with_its_settings(self):
        self.run_writer(self.game())
        self.steam_edits(Exe='"%s/bin/Other.exe"' % GUEST, LaunchOptions='-dx11', LastPlayTime=1791372037)
        self.assertEqual({}, self.run_writer())
        self.assertIn(str(APPID), self.record()['away'])
        entry = self.run_writer(self.game())[APPID]
        self.assertEqual('"%s/bin/Other.exe"' % GUEST, entry['Exe'])
        self.assertEqual('-dx11', entry['LaunchOptions'])
        self.assertEqual(1791372037, entry['LastPlayTime'])
        self.assertEqual({}, self.record()['away'])

    def test_first_run_keeps_steams_own_fields_and_writes_the_apps_paths(self):
        old = dict(shortcuts.shortcut(self.game('Old.exe')), LaunchOptions='-novid', LastPlayTime=5, sortas='x')
        mine = dict(shortcuts.shortcut(dict(self.game(appid=0x81111111), name='Mine')), tags={'0': 'Personal'})
        (self.config / 'shortcuts.vdf').write_bytes(shortcuts.write({'shortcuts': {'0': mine, '1': old}}))
        entries = self.run_writer(self.game())
        entry = entries[APPID]
        self.assertEqual('"%s/Example.exe"' % GUEST, entry['Exe'])
        self.assertEqual('-novid', entry['LaunchOptions'])
        self.assertEqual(5, entry['LastPlayTime'])
        self.assertEqual('x', entry['sortas'])
        self.assertEqual(mine, entries[0x81111111])

    def test_a_missing_tag_is_put_back_without_losing_steams(self):
        self.run_writer(self.game())
        self.steam_edits(tags={'1': 'Favorites', '0': shortcuts.TAG})
        entry = self.run_writer(self.game())[APPID]
        self.assertEqual({'1': 'Favorites', '0': shortcuts.TAG}, entry['tags'])
        self.assertEqual({'0': shortcuts.TAG}, shortcuts.merge(shortcuts.shortcut(self.game()), dict(shortcuts.shortcut(self.game()), tags={}), None, 0)[0]['tags'])

    def test_a_damaged_record_is_started_over(self):
        self.run_writer(self.game())
        (self.config / shortcuts.RECORD).write_text('{not json')
        self.steam_edits(LaunchOptions='-safe')
        entry = self.run_writer(self.game())[APPID]
        self.assertEqual('-safe', entry['LaunchOptions'])
        self.assertIn(str(APPID), self.record()['games'])

    def test_games_set_aside_are_bounded(self):
        many = [self.game(appid=0x80000000 + n, name='Game %d' % n) for n in range(shortcuts.AWAY_LIMIT + 4)]
        self.run_writer(*many)
        self.run_writer()
        away = self.record()['away']
        self.assertEqual(shortcuts.AWAY_LIMIT, len(away))
        self.assertNotIn(str(0x80000000), away)
        self.assertIn(str(0x80000000 + shortcuts.AWAY_LIMIT + 3), away)


if __name__ == '__main__':
    unittest.main()
