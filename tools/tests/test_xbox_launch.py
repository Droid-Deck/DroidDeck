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
