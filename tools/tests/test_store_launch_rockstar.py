import json
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

from test_store_launch import game, store


class RockstarLaunch(unittest.TestCase):
    def test_service_directory_cannot_export_sessions_outside_the_prefix(self):
        with tempfile.TemporaryDirectory() as root:
            folder, sidecar, prefix, _, _ = self.setup_game(root)
            outside = Path(root) / 'outside'
            outside.mkdir()
            target = prefix / 'pfx' / store.ROCKSTAR_FOLDER
            target.parent.mkdir(parents=True)
            target.symlink_to(outside, target_is_directory=True)
            sentinel = outside / 'launch.json'
            sentinel.write_text('keep')
            with patch.object(store, 'ask') as ask:
                self.assertEqual('invalid-service-path', store.prepare_rockstar(folder, sidecar, prefix, 'channel'))
            ask.assert_not_called()
            self.assertEqual('keep', sentinel.read_text())

    def test_sdk_registration_does_not_use_an_untrusted_display_name_as_a_key(self):
        with tempfile.TemporaryDirectory() as root:
            folder, _, prefix, _, _ = self.setup_game(root)
            with patch.object(store, 'wait_for_server', return_value=True):
                self.assertTrue(store.rockstar_registry(folder, None, prefix, '', sdk_arch='x64'))
            text = (prefix / 'pfx/system.reg').read_text()
            self.assertIn(r'[Software\\Rockstar Games\\Rockstar Games Social Club]', text)
            self.assertNotIn('Grand Theft Auto', text)

    def test_root_metadata_is_found_after_many_assets(self):
        with tempfile.TemporaryDirectory() as root:
            folder = Path(root)
            for index in range(300):
                (folder / ('asset-%s.dat' % index)).write_bytes(b'asset')
            (folder / 'title.rgl').write_bytes(b'RGLM' + bytes(108))
            self.assertTrue(store.rockstar_sdk(folder, {'id': 'future_title', 'exe': 'Game.exe'}))
            (folder / 'title.rgl').unlink()
            (folder / 'Title.RGL').write_bytes(b'RGLM' + bytes(108))
            self.assertTrue(store.rockstar_sdk(folder, {'id': 'future_title', 'exe': 'Game.exe'}))

    def test_sdk_titles_receive_only_their_short_lived_game_session(self):
        with tempfile.TemporaryDirectory() as root:
            def change(values):
                folder, sidecar, _, _, answer = values
                sidecar['id'] = 'future_title'
                (folder / 'socialclub.dll').write_bytes(b'game-runtime')
                answer['session'] = {'accountId': '123', 'identity': 'player@example.invalid', 'ticket': 'game-session',
                                     'ScAuthToken': 'never-export-refresh-token'}
            result, path, _ = self.prepare(root, change)
            self.assertEqual('ready', result)
            data = json.loads(path.read_text())
            self.assertEqual({'accountId', 'identity', 'ticket'}, set(data['session']))
            self.assertNotIn('pipe', data)
            self.assertNotIn('material', data)
            self.assertNotIn('never-export-refresh-token', path.read_text())
            self.assertEqual(0o600, path.stat().st_mode & 0o777)

    def test_invalid_sdk_sessions_cannot_reuse_an_old_handoff(self):
        for session in (None, {'accountId': '0', 'identity': 'player', 'ticket': 'session'},
                        {'accountId': '123', 'identity': 'player', 'ticket': 'bad\0session'}):
            with self.subTest(session=session), tempfile.TemporaryDirectory() as root:
                def change(values):
                    folder, sidecar, _, _, answer = values
                    sidecar['id'] = 'future_title'
                    (folder / 'socialclub.dll').write_bytes(b'game-runtime')
                    answer['session'] = session
                result, path, _ = self.prepare(root, change)
                self.assertEqual('invalid-sdk-session', result)
                self.assertFalse(path.exists())

    def test_sdk_detection_does_not_follow_external_metadata(self):
        with tempfile.TemporaryDirectory() as root:
            folder = Path(root) / 'game'
            folder.mkdir()
            metadata = Path(root) / 'outside.rgl'
            metadata.write_bytes(b'RGLM' + bytes(108))
            (folder / 'title.rgl').symlink_to(metadata)
            self.assertFalse(store.rockstar_sdk(folder, {'id': 'future_title', 'exe': 'Game.exe'}))

    def test_sdk_installer_cannot_escape_its_architecture_folder(self):
        with tempfile.TemporaryDirectory() as root:
            folder = Path(root) / 'game'
            installer = folder / 'Installers/Social-Club-Setup.exe'
            installer.parent.mkdir(parents=True)
            installer.write_bytes(b'installer')
            listing = type('Listing', (), {'returncode': 0, 'stdout': b'Path = x64/../../outside.dll\nSize = 1\n'})()
            with patch.object(store.shutil, 'which', return_value='/bin/7z'), patch.object(store.subprocess, 'run', return_value=listing):
                with self.assertRaises(ValueError):
                    store.rockstar_runtime(folder, folder / 'Game.exe', str(Path(root) / 'prefix'), 'x64')
            self.assertFalse((Path(root) / 'outside.dll').exists())

    def test_other_titles_can_launch_without_the_classic_pipe_service(self):
        with tempfile.TemporaryDirectory() as root:
            folder = Path(root)
            sidecar = {'store': 'rockstar', 'id': 'rdr2', 'exe': 'RDR2.exe'}
            with patch.object(store, 'find_store_game', return_value=(folder, sidecar)), \
                    patch.object(store, 'prepare_rockstar') as prepare, \
                    patch.object(store, 'ask') as ask, patch.dict(os.environ, {}, clear=True):
                self.assertEqual(store.STORE_GAME, store.main([str(folder / 'RDR2.exe')]))
            prepare.assert_not_called()
            ask.assert_not_called()

    def test_classic_authentication_failure_still_stops_launch(self):
        with tempfile.TemporaryDirectory() as root:
            folder = Path(root)
            sidecar = {'store': 'rockstar', 'id': 'gtavc', 'exe': 'gta-vc.exe'}
            with patch.object(store, 'find_store_game', return_value=(folder, sidecar)), \
                    patch.object(store, 'prepare_rockstar', return_value='session-not-owned'), \
                    patch.dict(os.environ, {}, clear=True):
                self.assertEqual(store.RAN_FAILED, store.main([str(folder / 'gta-vc.exe')]))

    def setup_game(self, root):
        folder = Path(root) / 'game'
        folder.mkdir()
        exe = bytearray(128)
        exe[:2] = b'MZ'
        exe[0x3c:0x40] = (64).to_bytes(4, 'little')
        exe[64:70] = b'PE\0\0\x4c\x01'
        (folder / 'gta-vc.exe').write_bytes(exe)
        (folder / 'MTLX.dll').write_bytes(b'vendor')
        prefix = Path(root) / 'prefix'
        (prefix / 'pfx').mkdir(parents=True)
        (prefix / 'pfx/user.reg').write_text('WINE REGISTRY Version 2\n')
        (prefix / 'pfx/system.reg').write_text('WINE REGISTRY Version 2\n')
        service = Path(root) / 'service'
        service.mkdir()
        (service / 'Launcher32.exe').write_bytes(b'compiled-helper')
        sidecar = {'store': 'rockstar', 'id': 'gtavc', 'exe': 'gta-vc.exe', 'args': ['one argument', '&literal']}
        answer = {'ok': True, 'pipe': 'GTA-VC_Pipe', 'material': 'A' * 640,
                  'expiresAt': int(time.time() * 1000) + 90000, 'ticket': 'must-not-be-exported'}
        return folder, sidecar, prefix, service, answer

    def prepare(self, root, modify=lambda values: None):
        values = self.setup_game(root)
        modify(values)
        folder, sidecar, prefix, service, answer = values
        config = prefix / 'pfx' / store.ROCKSTAR_FOLDER / 'launch.json'
        config.parent.mkdir(parents=True)
        config.write_text('stale')
        requests = []

        def ask(channel, request, timeout):
            requests.append(request)
            return answer

        with patch.object(store, 'ROCKSTAR_SERVICE', service), patch.object(store, 'rockstar_registry', return_value=True):
            result = store.prepare_rockstar(folder, sidecar, str(prefix), str(Path(root) / 'channel'), ask_app=ask)
        return result, config, requests

    def test_handoff_contains_only_launch_fields_and_preserves_argument_boundaries(self):
        with tempfile.TemporaryDirectory() as root:
            result, path, requests = self.prepare(root)
            self.assertEqual('ready', result)
            data = json.loads(path.read_text())
            self.assertEqual({'folder', 'exe', 'workdir', 'args', 'pipe', 'material', 'expiresAt'}, set(data))
            self.assertEqual(['one argument', '&literal'], data['args'])
            self.assertNotIn('must-not-be-exported', path.read_text())
            self.assertEqual('rockstar-launch', requests[0]['op'])
            self.assertEqual(b'compiled-helper', path.with_name('Launcher.exe').read_bytes())

    def test_a_missing_service_is_fetched_from_the_app_before_launch(self):
        with tempfile.TemporaryDirectory() as root:
            folder, sidecar, prefix, service, answer = self.setup_game(root)
            helper = (service / 'Launcher32.exe').read_bytes()
            (service / 'Launcher32.exe').unlink()
            requests = []

            def ask(channel, request, timeout):
                requests.append(request)
                if request['op'] == 'store-service':
                    (service / 'Launcher32.exe').write_bytes(helper)
                    return {'ok': True}
                return answer

            with patch.object(store, 'ROCKSTAR_SERVICE', service), patch.object(store, 'rockstar_registry', return_value=True):
                result = store.prepare_rockstar(folder, sidecar, str(prefix), str(Path(root) / 'channel'), ask_app=ask)
            self.assertEqual('ready', result)
            self.assertEqual([{'op': 'store-service', 'store': 'rockstar'}], requests[:1])
            self.assertEqual('rockstar-launch', requests[1]['op'])

    def test_an_unavailable_service_does_not_request_account_data(self):
        with tempfile.TemporaryDirectory() as root:
            folder, sidecar, prefix, service, _ = self.setup_game(root)
            (service / 'Launcher32.exe').unlink()
            requests = []

            def ask(channel, request, timeout):
                requests.append(request)
                return {'ok': False, 'reason': 'service-unavailable'}

            with patch.object(store, 'ROCKSTAR_SERVICE', service), patch.object(store, 'rockstar_registry', return_value=True):
                result = store.prepare_rockstar(folder, sidecar, str(prefix), str(Path(root) / 'channel'), ask_app=ask)
            self.assertEqual('no-service', result)
            self.assertEqual(['store-service'], [request['op'] for request in requests])

    def test_rejected_or_expired_sessions_cannot_reuse_stale_material(self):
        for update in ({'ok': False, 'reason': 'not-owned'}, {'material': 'A' * 641}, {'expiresAt': 1}):
            with self.subTest(update=update), tempfile.TemporaryDirectory() as root:
                result, config, _ = self.prepare(root, lambda values: values[4].update(update))
                self.assertNotEqual('ready', result)
                self.assertFalse(config.exists())

    def test_path_escape_does_not_request_account_data(self):
        with tempfile.TemporaryDirectory() as root:
            def change(values):
                (Path(root) / 'outside.exe').write_bytes(b'MZ')
                values[1]['exe'] = '../outside.exe'
            result, config, requests = self.prepare(root, change)
            self.assertEqual('invalid-game-path', result)
            self.assertEqual([], requests)
            self.assertFalse(config.exists())

    def test_each_launch_starts_a_fresh_launcher_log(self):
        with tempfile.TemporaryDirectory() as root:
            stale = []

            def ask(channel, request, timeout):
                stale.append((config.parent / 'launcher.log').exists())
                return answer

            folder, sidecar, prefix, service, answer = self.setup_game(root)
            config = prefix / 'pfx' / store.ROCKSTAR_FOLDER / 'launch.json'
            config.parent.mkdir(parents=True)
            (config.parent / 'launcher.log').write_bytes(b'x' * 70000)
            with patch.object(store, 'ROCKSTAR_SERVICE', service), patch.object(store, 'rockstar_registry', return_value=True):
                result = store.prepare_rockstar(folder, sidecar, str(prefix), str(Path(root) / 'channel'), ask_app=ask)
            self.assertEqual('ready', result)
            self.assertEqual([False], stale)

    def exit_game(self, root, store_id, log_text=None, link=None):
        folder = game(root, store_id=store_id, ident='gta5')
        prefix = Path(root) / 'prefix'
        target = prefix / 'pfx' / store.ROCKSTAR_FOLDER
        target.mkdir(parents=True)
        if log_text is not None:
            (target / 'launcher.log').write_text(log_text)
        if link is not None:
            (target / 'launcher.log').symlink_to(link)
        lines = []
        with patch.dict(os.environ, {'DD_LAUNCH_DIR': str(Path(root) / 'session'), 'STEAM_COMPAT_DATA_PATH': str(prefix)}), \
                patch.object(store, 'ask', return_value={'ok': True}), patch.object(store, 'log', lines.append):
            self.assertEqual(0, store.main(['--exited', str(folder / '.droiddeck-launch.bat')]))
        return lines, target / 'launcher.log'

    def test_launcher_log_reaches_the_session_log_when_the_game_exits(self):
        with tempfile.TemporaryDirectory() as root:
            text = 'launch handoff accepted\r\nlauncher pipes created, identity: none\r\n\r\ngame exited with code 0\r\n'
            lines, path = self.exit_game(root, 'rockstar', log_text=text)
            self.assertEqual(['rockstar launcher: launch handoff accepted',
                              'rockstar launcher: launcher pipes created, identity: none',
                              'rockstar launcher: game exited with code 0'], lines)
            self.assertFalse(path.exists())

    def test_launcher_log_relay_does_not_follow_links(self):
        with tempfile.TemporaryDirectory() as root:
            outside = Path(root) / 'outside.txt'
            outside.write_text('private\n')
            lines, path = self.exit_game(root, 'rockstar', link=outside)
            self.assertEqual([], lines)
            self.assertTrue(path.is_symlink())
            self.assertEqual('private\n', outside.read_text())

    def test_other_stores_do_not_relay_the_launcher_log(self):
        with tempfile.TemporaryDirectory() as root:
            lines, path = self.exit_game(root, 'gog', log_text='launch handoff accepted\n')
            self.assertFalse(any(line.startswith('rockstar launcher:') for line in lines))
            self.assertTrue(path.exists())

    def test_relay_is_bounded(self):
        with tempfile.TemporaryDirectory() as root:
            prefix = Path(root) / 'prefix'
            target = prefix / 'pfx' / store.ROCKSTAR_FOLDER
            target.mkdir(parents=True)
            (target / 'launcher.log').write_text('a' * 10 + '\n' + 'b' * 100 + '\n')
            self.assertEqual(['a' * 10, 'b' * 5], store.rockstar_launcher_log(str(prefix), limit=16))

    def test_writes_both_registry_views_and_escapes_values(self):
        with tempfile.TemporaryDirectory() as root:
            folder, _, prefix, _, _ = self.setup_game(root)
            with patch.object(store, 'wait_for_server', return_value=True):
                self.assertTrue(store.rockstar_registry(folder, 'Grand Theft Auto: Vice City', prefix, ''))
            text = (prefix / 'pfx/system.reg').read_text()
            self.assertIn(r'[Software\\Rockstar Games\\Launcher]', text)
            self.assertIn(r'[Software\\Wow6432Node\\Rockstar Games\\Launcher]', text)
            self.assertIn('Grand Theft Auto: Vice City', text)


SDK_FILES = {'socialclub.dll': b'sdk dll', 'socialclub.pak': b'sdk pak', 'SocialClubHelper.exe': b'sdk helper'}


class EpicRockstarLaunch(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.folder = self.root / 'Grand Theft Auto V'
        self.folder.mkdir()
        (self.folder / 'PlayGTAV.exe').write_bytes(b'MZ')
        self.sidecar = {'store': 'epic', 'id': '9d2d0eb64d5c44529cece33fe2a46482', 'title': 'Grand Theft Auto V', 'exe': 'PlayGTAV.exe'}
        (self.folder / '.droiddeck-store.json').write_text(json.dumps(self.sidecar))
        self.prefix = self.root / 'prefix'
        (self.prefix / 'pfx').mkdir(parents=True)
        (self.prefix / 'pfx/user.reg').write_text('WINE REGISTRY Version 2\n')
        (self.prefix / 'pfx/system.reg').write_text('WINE REGISTRY Version 2\n')
        self.sdk = self.prefix / 'pfx/drive_c/Program Files/Rockstar Games/Social Club'
        self.unpacked = []

    def unpacker(self, command, **kwargs):
        if command[1] == 'l':
            listing = ''.join('Path = x64/%s\nSize = %d\n\n' % (name, len(data)) for name, data in SDK_FILES.items())
            return type('Listing', (), {'returncode': 0, 'stdout': listing.encode()})()
        self.unpacked.append(Path(command[-2]))
        out = Path(next(arg[2:] for arg in command if arg.startswith('-o'))) / 'x64'
        out.mkdir(parents=True)
        for name, data in SDK_FILES.items():
            (out / name).write_bytes(data + b' from ' + Path(command[-2]).read_bytes())
        return type('Done', (), {'returncode': 0})()

    def stage(self):
        with patch.object(store.shutil, 'which', return_value='/bin/7z'), patch.object(store.subprocess, 'run', side_effect=self.unpacker), \
                patch.object(store, 'wait_for_server', return_value=True), \
                patch.object(store, 'create_prefix', side_effect=AssertionError('the prefix already exists')):
            return store.epic_rockstar_sdk(self.folder, self.sidecar, str(self.prefix), '')

    def test_the_launchers_own_sdk_installer_is_staged_so_it_never_runs_it(self):
        setup = self.prefix / 'pfx' / store.EPIC_SDK_SETUP
        setup.parent.mkdir(parents=True)
        setup.write_bytes(b'launcher setup')
        (self.folder / 'Installers').mkdir()
        (self.folder / 'Installers/Social-Club-Setup.exe').write_bytes(b'older game setup')
        self.assertEqual('installed', self.stage())
        self.assertEqual([setup], self.unpacked)
        self.assertEqual(b'sdk dll from launcher setup', (self.sdk / 'socialclub.dll').read_bytes())
        text = (self.prefix / 'pfx/system.reg').read_text()
        self.assertIn(r'[Software\\Wow6432Node\\Rockstar Games\\Rockstar Games Social Club]', text)
        self.assertIn(r'"InstallFolder"="C:\\Program Files\\Rockstar Games\\Social Club"', text)
        self.assertNotIn(r'Rockstar Games\\Launcher]', text)
        self.assertEqual('present', self.stage())
        self.assertEqual([setup], self.unpacked)

    def test_an_updated_launcher_installer_is_staged_again(self):
        setup = self.prefix / 'pfx' / store.EPIC_SDK_SETUP
        setup.parent.mkdir(parents=True)
        setup.write_bytes(b'launcher setup')
        self.assertEqual('installed', self.stage())
        setup.write_bytes(b'newer launcher setup')
        self.assertEqual('installed', self.stage())
        self.assertEqual(b'sdk dll from newer launcher setup', (self.sdk / 'socialclub.dll').read_bytes())

    def test_before_the_launcher_is_installed_the_games_installer_is_used(self):
        (self.folder / 'Redistributables').mkdir()
        (self.folder / 'Redistributables/Social-Club-Setup.exe').write_bytes(b'game setup')
        self.assertEqual('installed', self.stage())
        self.assertEqual(b'sdk dll from game setup', (self.sdk / 'socialclub.dll').read_bytes())
        self.assertNotIn(r'Rockstar Games\\Launcher]', (self.prefix / 'pfx/system.reg').read_text())

    def test_other_epic_games_are_left_alone(self):
        self.assertIsNone(self.stage())
        self.assertEqual([], self.unpacked)
        self.assertFalse(self.sdk.exists())
        self.assertEqual('WINE REGISTRY Version 2\n', (self.prefix / 'pfx/system.reg').read_text())

    def test_an_installer_linked_from_outside_the_game_is_ignored(self):
        outside = self.root / 'outside.exe'
        outside.write_bytes(b'not the game')
        (self.folder / 'Installers').mkdir()
        (self.folder / 'Installers/Social-Club-Setup.exe').symlink_to(outside)
        self.assertIsNone(self.stage())
        self.assertEqual([], self.unpacked)

    def game_environment(self, shared=None, games=None):
        config = self.root / 'home/.config/droiddeck/game-environment.json'
        config.parent.mkdir(parents=True, exist_ok=True)
        config.write_text(json.dumps({'version': 1, 'shared': shared or {}, 'games': games or {}}))

    def launch(self, bat, parent, compat_data=''):
        (self.folder / '.droiddeck-launch.bat').write_text(bat)
        calls = []
        env = {'DD_LAUNCH_DIR': str(self.root / 'launch'), 'STEAM_COMPAT_DATA_PATH': compat_data, 'HOME': str(self.root / 'home')}
        with patch.dict(os.environ, env), patch.object(store, 'EPIC_PARENT', parent), \
                patch.object(store, 'client_wanted', return_value=False), \
                patch.object(store, 'ask', side_effect=lambda channel, request, timeout=0, claim=False: calls.append((request['op'], timeout)) or {'ok': False}):
            store.main([str(self.folder / '.droiddeck-launch.bat')])
        return calls

    def test_a_title_that_needs_the_parent_fetches_the_stand_in_before_its_code(self):
        bat = '@echo off\r\nsetlocal\r\nset "DD_P=1"\r\n'
        calls = self.launch(bat, self.root / 'missing/EpicGamesLauncher.exe')
        self.assertEqual([('store-service', store.EPIC_SERVICE_WAIT), 'epic-code', 'cloud-down'],
                         [calls[0]] + [op for op, _ in calls[1:]])
        staged = self.root / 'EpicGamesLauncher.exe'
        staged.write_bytes(b'MZ')
        self.assertEqual(['epic-code', 'cloud-down'], [op for op, _ in self.launch(bat, staged)])

    def test_other_epic_games_do_not_fetch_the_stand_in(self):
        bat = '@echo off\r\nsetlocal\r\nset "DD_P="\r\nif "%DD_EPIC_PARENT%"=="1" set "DD_P=1"\r\n'
        calls = self.launch(bat, self.root / 'missing/EpicGamesLauncher.exe')
        self.assertEqual(['epic-code', 'cloud-down'], [op for op, _ in calls])

    def test_the_games_own_switch_decides_whether_the_stand_in_is_fetched(self):
        compat_data = str(self.root / 'compatdata/4089453117')
        missing = self.root / 'missing/EpicGamesLauncher.exe'
        self.game_environment(games={'4089453117': {'DD_EPIC_PARENT': '1'}})
        self.assertEqual('store-service', self.launch('@echo off\r\nset "DD_P="\r\n', missing, compat_data)[0][0])
        self.game_environment(games={'4089453117': {'DD_EPIC_PARENT': '0'}})
        self.assertEqual(['epic-code', 'cloud-down'], [op for op, _ in self.launch('@echo off\r\nset "DD_P=1"\r\n', missing, compat_data)])
        self.game_environment(games={'4089453117': {'DD_EPIC_PARENT': None}}, shared={'DD_EPIC_PARENT': '0'})
        self.assertEqual('store-service', self.launch('@echo off\r\nset "DD_P=1"\r\n', missing, compat_data)[0][0])

    def test_the_switch_is_read_the_way_the_game_will_see_it(self):
        env = {'HOME': str(self.root / 'home'), 'STEAM_COMPAT_DATA_PATH': str(self.root / 'compatdata/4089453117')}
        self.assertEqual('', store.epic_parent_switch(env))
        self.assertEqual('1', store.epic_parent_switch(dict(env, DD_EPIC_PARENT='1')))
        self.game_environment(shared={'DD_EPIC_PARENT': '1'}, games={'4089453117': {'DD_EPIC_PARENT': '0'}, '7': {'DD_EPIC_PARENT': '1'}})
        self.assertEqual('0', store.epic_parent_switch(env))
        self.assertEqual('1', store.epic_parent_switch(dict(env, STEAM_COMPAT_DATA_PATH=str(self.root / 'compatdata/12'))))
        (self.root / 'home/.config/droiddeck/game-environment.json').write_text('{"version": 9}')
        self.assertEqual('1', store.epic_parent_switch(dict(env, DD_EPIC_PARENT='1')))


if __name__ == '__main__':
    unittest.main()
