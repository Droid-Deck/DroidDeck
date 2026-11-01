import io
import json
import os
from pathlib import Path
import runpy
import socket
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch

BIN = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin'
xbox = runpy.run_path(str(BIN / 'droiddeck-xbox'))


class XboxLaunchTest(unittest.TestCase):
    def test_other_stores_keep_their_existing_launch(self):
        with tempfile.TemporaryDirectory() as root:
            folder = Path(root)
            (folder / '.droiddeck-store.json').write_text(json.dumps({'store': 'gog', 'id': '1'}))
            self.assertIsNone(xbox['launch'](['/proton', 'waitforexitandrun', str(folder / 'Game.exe')], {}))

    def test_renewal_retries_a_timeout_and_stops_after_rejection(self):
        stopped = unittest.mock.Mock()
        stopped.wait.side_effect = [False, False, False, AssertionError('renewed a rejected lease')]
        ask = unittest.mock.Mock(side_effect=[None, {'ok': True}, {'ok': False}])
        xbox['renew_lease'](ask, Path('/channel'), 'GAME', 'lease-1', stopped)
        self.assertEqual(ask.call_count, 3)

    def test_stopping_a_launch_stops_renewal_without_another_request(self):
        stopped = threading.Event()
        stopped.set()
        ask = unittest.mock.Mock()
        xbox['renew_lease'](ask, Path('/channel'), 'GAME', 'lease-1', stopped)
        ask.assert_not_called()

    def test_missing_store_detector_keeps_stock_launch(self):
        for error in (FileNotFoundError('missing'), RuntimeError('module initialization failed')):
            with self.subTest(error=error), patch('runpy.run_path', side_effect=error):
                self.assertIsNone(xbox['launch'](['/proton', 'waitforexitandrun', '/Game.exe'], {}))

    def test_launch_uses_prepared_image_and_never_receives_account_credentials(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            runtime = root / 'runtime/windows'
            runtime.mkdir(parents=True)
            for name in xbox['DLLS']:
                (runtime / name).write_bytes(b'MZ')
            store = {'find_store_game': lambda argv: (root, {'store': 'xbox', 'id': 'GAME'}),
                     'ask': unittest.mock.Mock(return_value={'ok': True, 'socket': '/service', 'lease': 'lease-1',
                                                            'exe': '/private/Game.exe', 'playfab': 'game-token'})}
            close = unittest.mock.Mock()
            with patch('runpy.run_path', return_value=store), patch.dict(xbox['launch'].__globals__, RUNTIME=runtime.parent), \
                 patch.dict(xbox['launch'].__globals__, bridge=lambda path: (1234, 'key', close)), \
                 patch.dict(xbox['launch'].__globals__, wine=unittest.mock.Mock(return_value=7)):
                launch = xbox['launch']
                self.assertEqual(launch(['/proton', 'waitforexitandrun', '/encrypted/Game.exe'],
                                        {'BL_LAUNCH_DIR': '/launch', 'DROIDDECK_XBOX_CREDENTIAL_SOCKET': 'stale',
                                         'DROIDDECK_XBOX_CREDENTIAL_PORT': '4100', 'DROIDDECK_XBOX_CREDENTIAL_KEY': 'stale-key'}), 7)
                wine = launch.__globals__['wine']
                self.assertEqual(wine.call_args.args[0], ['/private/Game.exe'])
                env = wine.call_args.args[1]
                for name in ('DROIDDECK_XBOX_CREDENTIAL_SOCKET', 'DROIDDECK_XBOX_CREDENTIAL_PORT', 'DROIDDECK_XBOX_CREDENTIAL_KEY'):
                    self.assertNotIn(name, env)
                self.assertEqual(env['XODUS_XSTS_PLAYFAB'], 'game-token')
            close.assert_called_once()
            self.assertEqual(store['ask'].call_args.args[1], {'op': 'xbox-released', 'lease': 'lease-1'})

    def test_bridge_start_failure_still_releases_the_lease(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            runtime = root / 'windows'
            runtime.mkdir()
            for name in xbox['DLLS']:
                (runtime / name).touch()
            ask = unittest.mock.Mock(return_value={'ok': True, 'socket': '/service', 'lease': 'lease-1'})
            with patch('runpy.run_path', return_value={'find_store_game': lambda argv: (root, {'store': 'xbox', 'id': 'GAME'}), 'ask': ask}), \
                 patch.dict(xbox['launch'].__globals__, RUNTIME=root, bridge=unittest.mock.Mock(side_effect=OSError('bind failed'))):
                with self.assertRaises(OSError):
                    xbox['launch'](['/proton', 'waitforexitandrun', '/Game.exe'], {'BL_LAUNCH_DIR': '/launch'})
            self.assertEqual(ask.call_args.args[1], {'op': 'xbox-released', 'lease': 'lease-1'})

    def test_bridge_authenticates_before_forwarding(self):
        with tempfile.TemporaryDirectory() as root:
            path = str(Path(root) / 'service.sock')
            upstream = socket.socket(socket.AF_UNIX)
            upstream.bind(path); upstream.listen(1); upstream.settimeout(3)
            received = []
            def serve():
                connection, _ = upstream.accept()
                with connection:
                    received.append(connection.recv(8))
                    connection.sendall(b'reply')
            worker = threading.Thread(target=serve); worker.start()
            port, key, close = xbox['bridge'](path)
            try:
                with socket.create_connection(('127.0.0.1', port), timeout=2) as denied:
                    denied.sendall(b'x' * 64)
                    self.assertEqual(denied.recv(1), b'')
                with socket.create_connection(('127.0.0.1', port), timeout=2) as client:
                    client.sendall(key[:20].encode())
                    client.sendall(key[20:].encode() + b'request')
                    self.assertEqual(client.recv(5), b'reply')
                worker.join(3)
                self.assertFalse(worker.is_alive())
                self.assertEqual(received, [b'request'])
            finally:
                close(); upstream.close()

    def test_wine_wrapper_preserves_selected_proton_args_and_prefix_isolation(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            prefix = root / 'compat/pfx'
            prefix.mkdir(parents=True)
            runtime = root / 'runtime/windows'; runtime.mkdir(parents=True)
            for name in xbox['DLLS']:
                (runtime / name).write_bytes(b'MZ-synthetic')
            command = root / 'command.json'
            command.write_text(json.dumps(['/selected/proton', 'waitforexitandrun', '/encrypted/Game.exe', '--custom', 'two words']))
            env = {'STEAM_COMPAT_DATA_PATH': str(prefix.parent), 'DROIDDECK_XBOX_COMMAND_FILE': str(command),
                   'DROIDDECK_XBOX_CREDENTIAL_SOCKET': 'private-account-channel', 'DROIDDECK_XBOX_CREDENTIAL_PORT': '4100',
                   'DROIDDECK_XBOX_CREDENTIAL_KEY': 'private-key', 'XODUS_BRIDGE_KEY': 'runtime-key'}
            with patch.dict(os.environ, env), patch.dict(xbox['wine'].__globals__, RUNTIME=runtime.parent), patch('subprocess.run') as initialize, patch('subprocess.call', return_value=7) as call:
                initialize.return_value.returncode = 0
                self.assertEqual(xbox['wine'](['/private-view/Game.exe']), 7)
                argv = call.call_args.args[0]
                self.assertEqual(argv, ['/selected/proton', 'waitforexitandrun', '/private-view/Game.exe', '--custom', 'two words'])
                self.assertIn('xgameruntime=n', call.call_args.kwargs['env']['WINEDLLOVERRIDES'])
                self.assertEqual((prefix / 'drive_c/windows/system32/xgameruntime.dll').read_bytes(), b'MZ-synthetic')
                initialize.assert_called_once()
                for child in (initialize, call):
                    child_env = child.call_args.kwargs['env']
                    for name in ('DROIDDECK_XBOX_CREDENTIAL_SOCKET', 'DROIDDECK_XBOX_CREDENTIAL_PORT', 'DROIDDECK_XBOX_CREDENTIAL_KEY'):
                        self.assertNotIn(name, child_env)
                    self.assertNotIn('DROIDDECK_XBOX_COMMAND_FILE', child_env)
                    self.assertEqual(child_env['XODUS_BRIDGE_KEY'], 'runtime-key')

    def test_launch_view_runs_from_its_own_folder_on_a_short_drive_letter(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            prefix = root / 'compat/pfx'
            prefix.mkdir(parents=True)
            runtime = root / 'runtime/windows'; runtime.mkdir(parents=True)
            for name in xbox['DLLS']:
                (runtime / name).write_bytes(b'MZ-synthetic')
            view = root / 'files/xbox-runtime/views/launch-4093691424/droiddeck-xbox-1ZpM3D'
            (view / 'Bin').mkdir(parents=True)
            command = root / 'command.json'
            command.write_text(json.dumps(['/selected/proton', 'waitforexitandrun', '/encrypted/Game.exe']))
            env = {'STEAM_COMPAT_DATA_PATH': str(prefix.parent), 'DROIDDECK_XBOX_COMMAND_FILE': str(command)}
            seen = []
            def call(argv, **kwargs):
                seen.append((argv, os.readlink(prefix / 'dosdevices/x:')))
                return 0
            with patch.dict(os.environ, env), patch.dict(xbox['wine'].__globals__, RUNTIME=runtime.parent), patch('subprocess.run') as initialize, patch('subprocess.call', side_effect=call):
                initialize.return_value.returncode = 0
                self.assertEqual(xbox['wine']([str(view / 'Bin/Game.exe')]), 0)
            self.assertEqual(seen, [(['/selected/proton', 'waitforexitandrun', 'X:\\droiddeck-xbox-1ZpM3D\\Bin\\Game.exe'], str(view.parent))])
            self.assertFalse(os.path.lexists(prefix / 'dosdevices/x:'))

    def test_view_drive_keeps_drives_in_use_and_reuses_a_stale_view(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            devices = root / 'pfx/dosdevices'; devices.mkdir(parents=True)
            views = root / 'views'
            live = views / 'launch-1/droiddeck-xbox-live'; live.mkdir(parents=True)
            view = views / 'launch-2/droiddeck-xbox-new'; view.mkdir(parents=True)
            (devices / 'x:').symlink_to(live)
            (devices / 'w:').symlink_to(root / 'games')
            (devices / 'v:').symlink_to(views / 'launch-0/droiddeck-xbox-gone')
            exe, link = xbox['view_drive'](root / 'pfx', str(view / 'Game.exe'))
            self.assertEqual((exe, link), ('V:\\droiddeck-xbox-new\\Game.exe', devices / 'v:'))
            self.assertEqual(os.readlink(devices / 'v:'), str(view.parent))
            self.assertEqual(os.readlink(devices / 'x:'), str(live))
            self.assertEqual(sorted(p.name for p in devices.iterdir()), ['v:', 'w:', 'x:'])
            self.assertEqual(xbox['view_drive'](root / 'pfx', '/games/Game.exe'), ('/games/Game.exe', None))

    def test_missing_runtime_classes_are_registered_once(self):
        with tempfile.TemporaryDirectory() as root:
            prefix = Path(root)
            key = xbox['CLASS_KEY'].replace('\\', '\\\\')
            present = [name for names in xbox['CLASSES'].values() for name in names
                       if name != 'Windows.ApplicationModel.DataTransfer.DataTransferManager']
            (prefix / 'system.reg').write_text(''.join('[%s%s] 1\n"DllPath"="x"\n\n' % (key, name) for name in present))
            imported = []
            def run(argv, **kwargs):
                imported.append(Path(argv[4][2:].replace('\\', '/')).read_bytes()[2:].decode('utf-16-le'))
                return subprocess.CompletedProcess(argv, 0)
            with patch('subprocess.run', side_effect=run) as child:
                xbox['register_classes']('/selected/proton', prefix, {'A': '1'})
            self.assertEqual(child.call_args.args[0][:4], ['/selected/proton', 'run', 'reg.exe', 'import'])
            self.assertEqual(child.call_args.kwargs['env'], {'A': '1'})
            self.assertEqual(imported, ['Windows Registry Editor Version 5.00\r\n\r\n[HKEY_LOCAL_MACHINE\\Software\\Microsoft\\WindowsRuntime'
                                        '\\ActivatableClassId\\Windows.ApplicationModel.DataTransfer.DataTransferManager]\r\n'
                                        '"DllPath"="C:\\\\windows\\\\system32\\\\twinapi.appcore.dll"\r\n\r\n'])
            with (prefix / 'system.reg').open('a') as file:
                file.write('[%sWindows.ApplicationModel.DataTransfer.DataTransferManager] 1\n' % key)
            with patch('subprocess.run') as child:
                xbox['register_classes']('/selected/proton', prefix, {})
                (prefix / 'system.reg').unlink()
                xbox['register_classes']('/selected/proton', prefix, {})
            child.assert_not_called()

    def test_failed_class_registration_is_reported_and_launch_continues(self):
        with tempfile.TemporaryDirectory() as root:
            prefix = Path(root)
            (prefix / 'system.reg').write_text('')
            for outcome in ({'return_value': subprocess.CompletedProcess([], 1)},
                            {'side_effect': subprocess.TimeoutExpired('reg.exe', 90)}):
                with patch('subprocess.run', **outcome), patch('sys.stderr', new_callable=io.StringIO) as err:
                    self.assertIsNone(xbox['register_classes']('/selected/proton', prefix, {}))
                self.assertIn('registration failed', err.getvalue())
