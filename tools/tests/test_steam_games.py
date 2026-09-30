import importlib.machinery
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

BIN = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin'


def load(name):
    loader = importlib.machinery.SourceFileLoader(name, str(BIN / name))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


imports = load('bannerlator-steam-games')
shortcuts = load('bannerlator-steam-shortcuts')


class ImportsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.steam = self.root / 'Steam'
        self.acct = self.steam / 'userdata/123'
        self.folder = self.root / 'Games/Example'
        self.folder.mkdir(parents=True)
        self.exe = self.folder / 'Example.exe'
        self.exe.write_bytes(b'game')
        self.game = dict(name='Example', exe=str(self.exe), folder=str(self.folder),
                         dir=str(self.folder), appid=0x87654321)

    def snapshot(self, owned=True, account='123'):
        imports.save_json(self.acct / 'config' / imports.STATE,
                          dict(account=account, owned={'42': owned},
                               candidates={str(self.folder): 42},
                               sources={str(self.folder): imports.source_stamp(self.game)}))

    def test_owned_uses_real_id_and_registers_installed_files(self):
        self.snapshot()
        games, routes = imports.route(self.steam, self.acct, [self.game])
        self.assertEqual([], games)
        self.assertEqual({str(self.game['appid']): 42}, routes)
        manifest = self.steam / 'steamapps/appmanifest_42.acf'
        self.assertIn('"StateFlags" "4"', manifest.read_text())
        self.assertIn('"Universe" "1"', manifest.read_text())
        self.assertEqual(self.folder, (self.steam / 'steamapps/common/DroidDeck-42').resolve())
        self.assertEqual(b'game', self.exe.read_bytes())

    def test_migrates_only_unchanged_legacy_import_manifests(self):
        self.snapshot()
        imports.route(self.steam, self.acct, [self.game])
        path = self.steam / 'steamapps/appmanifest_42.acf'
        current = path.read_text()
        legacy = current.replace('    "Universe" "1"\n', '').replace('"StateFlags" "4"', '"StateFlags" "2"')
        path.write_text(legacy)
        imports.route(self.steam, self.acct, [self.game])
        self.assertEqual(current, path.read_text())
        changed = legacy.replace('"StateFlags" "2"', '"StateFlags" "6"')
        path.write_text(changed)
        imports.route(self.steam, self.acct, [self.game])
        self.assertEqual(changed, path.read_text())

    def test_unresolved_titles_are_retried_after_network_recovers(self):
        listing = self.root / 'games.json'
        listing.write_text(json.dumps([self.game]))
        from unittest.mock import Mock
        result = Mock(returncode=0, stdout='DROIDDECK_OWNERSHIP=' + json.dumps(dict(account='123', owned={'42': True})))
        with patch.object(imports, 'identify', side_effect=[None, 42]) as identify, \
             patch.object(imports.time, 'monotonic', side_effect=[0, 301]), \
             patch.object(imports.time, 'sleep', side_effect=[None, RuntimeError('stop')]), \
             patch.object(imports.subprocess, 'run', return_value=result), \
             patch('builtins.print'):
            with self.assertRaisesRegex(RuntimeError, 'stop'):
                imports.watch(self.steam, listing)
        self.assertEqual(2, identify.call_count)
        snapshot = imports.read_json(self.acct / 'config' / imports.STATE, {})
        self.assertTrue(snapshot['owned']['42'])

    def test_unowned_unknown_and_different_account_stay_shortcuts(self):
        for owned, account in [(False, '123'), (True, '456')]:
            self.snapshot(owned, account)
            self.assertEqual(([self.game], {}), imports.route(self.steam, self.acct, [self.game]))
        (self.acct / 'config' / imports.STATE).unlink()
        self.assertEqual(([self.game], {}), imports.route(self.steam, self.acct, [self.game]))
        self.assertFalse((self.steam / 'steamapps').exists())

    def winnative_manifest(self, flags='4', installdir='Example'):
        path = self.folder.parent / imports.WINNATIVE / 'appmanifest_42.acf'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('"AppState"\n{\n\t"appid"\t\t"42"\n\t"StateFlags"\t\t"%s"\n\t"installdir"\t\t"%s"\n'
                        '\t"buildid"\t\t"7"\n\t"InstalledDepots"\n\t{\n\t\t"43"\n\t\t{\n\t\t\t"manifest"\t\t"99"\n'
                        '\t\t\t"size"\t\t"4"\n\t\t}\n\t}\n}\n' % (flags, installdir))
        return path

    def test_winnative_manifest_carries_installed_build(self):
        source = self.winnative_manifest()
        self.snapshot()
        imports.route(self.steam, self.acct, [self.game])
        text = (self.steam / 'steamapps/appmanifest_42.acf').read_text()
        self.assertEqual(source.read_text().replace('"Example"', '"DroidDeck-42"'), text)

    def test_winnative_manifest_identifies_folder(self):
        self.winnative_manifest()
        with patch.object(imports.urllib.request, 'urlopen', side_effect=AssertionError):
            self.assertEqual(42, imports.identify(self.game))

    def test_incomplete_winnative_manifest_is_ignored(self):
        for flags, installdir in [('1026', 'Example'), ('4', 'Other')]:
            self.winnative_manifest(flags, installdir)
            self.assertIsNone(imports.installed_build(self.folder, 42, 'DroidDeck-42'))

    def test_depot_config_records_installed_depots(self):
        config = self.folder / '.DepotDownloader/depot.config'
        config.parent.mkdir()
        config.write_text(json.dumps({'installedManifestIDs': {'43': 99}}))
        self.assertIsNone(imports.installed_build(self.folder, 42, 'DroidDeck-42'))
        (self.folder / '.download_complete').touch()
        self.snapshot()
        imports.route(self.steam, self.acct, [self.game])
        text = (self.steam / 'steamapps/appmanifest_42.acf').read_text()
        self.assertIn('"InstalledDepots"\n    {\n        "43"\n        {\n            "manifest" "99"', text)
        self.assertTrue(text.endswith('    }\n}\n'))
        config.write_text(json.dumps({'installedManifestIDs': {'43': 0x7FFFFFFFFFFFFFFF}}))
        self.assertIsNone(imports.installed_build(self.folder, 42, 'DroidDeck-42'))

    def test_bare_import_manifest_gains_installed_build(self):
        self.snapshot()
        imports.route(self.steam, self.acct, [self.game])
        self.winnative_manifest()
        imports.route(self.steam, self.acct, [self.game])
        self.assertIn('"buildid"\t\t"7"', (self.steam / 'steamapps/appmanifest_42.acf').read_text())

    def steam_fresh_install(self, downloaded='0'):
        self.snapshot()
        imports.route(self.steam, self.acct, [self.game])
        path = self.steam / 'steamapps/appmanifest_42.acf'
        path.write_text('"AppState"\n{\n\t"appid"\t\t"42"\n\t"StateFlags"\t\t"6"\n\t"installdir"\t\t"DroidDeck-42"\n'
                        '\t"buildid"\t\t"0"\n\t"BytesDownloaded"\t\t"%s"\n\t"BytesStaged"\t\t"0"\n'
                        '\t"InstalledDepots"\n\t{\n\t}\n}\n' % downloaded)
        self.winnative_manifest()
        return path

    def test_queued_fresh_install_takes_installed_build(self):
        path = self.steam_fresh_install()
        imports.route(self.steam, self.acct, [self.game])
        self.assertIn('"buildid"\t\t"7"', path.read_text())

    def test_started_fresh_install_is_left_to_steam(self):
        for downloaded, staging in [('5', False), ('0', True)]:
            path = self.steam_fresh_install(downloaded)
            if staging:
                (self.steam / 'steamapps/downloading/42').mkdir(parents=True)
            before = path.read_text()
            imports.route(self.steam, self.acct, [self.game])
            self.assertEqual(before, path.read_text())

    def test_existing_manifest_is_never_overwritten(self):
        manifest = self.steam / 'steamapps/appmanifest_42.acf'
        manifest.parent.mkdir(parents=True)
        manifest.write_text('Steam owns this file')
        self.snapshot()
        imports.route(self.steam, self.acct, [self.game])
        self.assertEqual('Steam owns this file', manifest.read_text())

    def test_import_collision_falls_back_without_touching_files(self):
        occupied = self.steam / 'steamapps/common/DroidDeck-42'
        occupied.mkdir(parents=True)
        self.snapshot()
        self.assertEqual(([self.game], {}), imports.route(self.steam, self.acct, [self.game]))
        self.assertTrue(occupied.is_dir())
        self.assertFalse(occupied.is_symlink())

    def test_removed_folder_does_not_import(self):
        self.snapshot()
        self.exe.unlink()
        self.folder.rmdir()
        self.assertEqual(([self.game], {}), imports.route(self.steam, self.acct, [self.game]))

    def test_replaced_game_does_not_inherit_cached_ownership(self):
        self.snapshot()
        self.exe.write_bytes(b'a different game')
        self.assertEqual(([self.game], {}), imports.route(self.steam, self.acct, [self.game]))

    def test_duplicate_owned_folders_share_one_steam_entry(self):
        self.snapshot()
        games, routes = imports.route(self.steam, self.acct, [self.game, self.game])
        self.assertEqual([], games)
        self.assertEqual(1, len(routes))
        self.assertEqual(1, len(list((self.steam / 'steamapps').glob('*.acf'))))

    def test_explicit_appid_does_not_need_network(self):
        (self.folder / 'steam_appid.txt').write_text('42\n')
        with patch.object(imports.urllib.request, 'urlopen', side_effect=AssertionError):
            self.assertEqual(42, imports.identify(self.game))

    def test_manifest_identity_uses_install_directory(self):
        folder = self.root / 'second/steamapps/common/Custom Name'
        folder.mkdir(parents=True)
        (folder.parent.parent / 'appmanifest_42.acf').write_text('"AppState" { "installdir" "Custom Name" }')
        game = dict(self.game, folder=str(folder), exe=str(folder / 'game.exe'))
        with patch.object(imports.urllib.request, 'urlopen', side_effect=AssertionError):
            self.assertEqual(42, imports.identify(game))

    def test_store_requires_unique_exact_title(self):
        cases = [([{'id': 42, 'name': 'Example Deluxe'}], None),
                 ([{'id': 42, 'name': 'Example'}, {'id': 43, 'name': 'Example'}], None),
                 ([{'id': 42, 'name': 'EXAMPLE'}], 42)]
        for items, expected in cases:
            with patch.object(imports.urllib.request, 'urlopen') as request:
                request.return_value.__enter__.return_value.read.return_value = json.dumps({'items': items}).encode()
                self.assertEqual(expected, imports.identify(self.game))

    def test_store_country_and_edition_suffixes(self):
        for folder, store in [('FINAL FANTASY VII REMAKE', 'FINAL FANTASY VII REMAKE INTERGRADE'),
                              ('FINAL FANTASY XV', 'FINAL FANTASY XV WINDOWS EDITION')]:
            with patch.object(imports.urllib.request, 'urlopen') as request:
                request.return_value.__enter__.return_value.read.return_value = json.dumps({'items': [{'id': 42, 'name': store}]}).encode()
                self.assertEqual(42, imports.identify(dict(self.game, name=folder)))
                self.assertIn('cc=US&', request.call_args.args[0])

    def test_edition_matching_rejects_ambiguous_titles_and_soundtracks(self):
        with patch.object(imports.urllib.request, 'urlopen') as request:
            request.return_value.__enter__.return_value.read.return_value = json.dumps({'items': [
                {'id': 42, 'name': 'Example WINDOWS EDITION'},
                {'id': 43, 'name': 'Example INTERGRADE'},
                {'id': 44, 'name': 'Example Soundtrack'},
            ]}).encode()
            self.assertIsNone(imports.identify(self.game))

    def test_corrupt_shortcuts_are_preserved(self):
        path = self.acct / 'config/shortcuts.vdf'
        path.parent.mkdir(parents=True)
        original = b'corrupt'
        path.write_bytes(original)
        listing = self.root / 'games.json'
        listing.write_text(json.dumps([self.game]))
        run = subprocess.run(['python3', str(BIN / 'bannerlator-steam-shortcuts'), str(self.steam), str(listing)], capture_output=True)
        self.assertEqual(1, run.returncode)
        self.assertEqual(original, path.read_bytes())

    def test_user_shortcuts_survive_owned_promotion(self):
        self.snapshot()
        path = self.acct / 'config/shortcuts.vdf'
        manual = dict(shortcuts.shortcut(self.game), tags={'0': 'Personal'}, AppName='Manual')
        path.write_bytes(shortcuts.write({'shortcuts': {'0': manual, '1': shortcuts.shortcut(self.game)}}))
        listing = self.root / 'games.json'
        listing.write_text(json.dumps([self.game]))
        subprocess.run(['python3', str(BIN / 'bannerlator-steam-shortcuts'), str(self.steam), str(listing)], check=True, capture_output=True)
        result = shortcuts.parse(path.read_bytes())['shortcuts']
        self.assertEqual([manual], list(result.values()))
        self.assertEqual({str(self.game['appid']): 42}, json.loads((path.parent / '.droiddeck-routes.json').read_text()))


class SteamProbeTest(unittest.TestCase):
    def test_probe_reads_account_and_licenses_and_releases_handles(self):
        import ctypes as C
        from unittest.mock import Mock
        objects, callbacks, calls = [], [], []

        def interface(slots):
            table = (C.c_void_p * 24)()
            for slot, result, args, callback in slots:
                fn = C.CFUNCTYPE(result, C.c_void_p, *args)(callback)
                callbacks.append(fn)
                table[slot] = C.cast(fn, C.c_void_p).value
            obj = C.pointer(C.cast(table, C.POINTER(C.c_void_p)))
            objects.extend([table, obj])
            return C.cast(obj, C.c_void_p).value

        user = interface([
            (1, C.c_bool, [], lambda _: True),
            (2, C.c_uint64, [], lambda _: imports.BASE_ID + 123),
        ])
        apps = interface([(6, C.c_bool, [C.c_uint32], lambda _, appid: appid == 42)])
        client = interface([
            (0, C.c_int, [], lambda _: 7),
            (1, C.c_bool, [C.c_int], lambda _, pipe: calls.append(('pipe', pipe)) or True),
            (2, C.c_int, [C.c_int], lambda _, pipe: 9),
            (4, None, [C.c_int, C.c_int], lambda _, pipe, handle: calls.append(('user', pipe, handle))),
            (5, C.c_void_p, [C.c_int, C.c_int, C.c_char_p], lambda _, handle, pipe, version: user if version == b'SteamUser021' else 0),
            (15, C.c_void_p, [C.c_int, C.c_int, C.c_char_p], lambda _, handle, pipe, version: apps if version == b'STEAMAPPS_INTERFACE_VERSION008' else 0),
        ])
        library = Mock()
        library.CreateInterface.return_value = client
        with patch.object(imports.C, 'CDLL', return_value=library):
            result = imports.probe('/Steam', [42, 43])
        self.assertEqual('123', result['account'])
        self.assertEqual({'42': True, '43': False}, result['owned'])
        self.assertEqual([('user', 7, 9), ('pipe', 7)], calls)
        library.CreateInterface.assert_called_once_with(b'SteamClient020', None)


if __name__ == '__main__':
    unittest.main()
