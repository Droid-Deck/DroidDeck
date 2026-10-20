import json
import os
from pathlib import Path
import runpy
import socket
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
                                        {'BL_LAUNCH_DIR': '/launch', 'DROIDDECK_XBOX_CREDENTIAL_SOCKET': 'stale'}), 7)
                wine = launch.__globals__['wine']
                self.assertEqual(wine.call_args.args[0], ['/private/Game.exe'])
                env = wine.call_args.args[1]
                self.assertNotIn('DROIDDECK_XBOX_CREDENTIAL_SOCKET', env)
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
                   'DROIDDECK_XBOX_CREDENTIAL_SOCKET': 'private-account-channel', 'XODUS_BRIDGE_KEY': 'runtime-key'}
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
                    self.assertNotIn('DROIDDECK_XBOX_CREDENTIAL_SOCKET', child_env)
                    self.assertNotIn('DROIDDECK_XBOX_COMMAND_FILE', child_env)
                    self.assertEqual(child_env['XODUS_BRIDGE_KEY'], 'runtime-key')
