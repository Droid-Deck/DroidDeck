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
        with patch.dict(os.environ, {'BL_LAUNCH_DIR': str(Path(root) / 'session'), 'STEAM_COMPAT_DATA_PATH': str(prefix)}), \
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


if __name__ == '__main__':
    unittest.main()
