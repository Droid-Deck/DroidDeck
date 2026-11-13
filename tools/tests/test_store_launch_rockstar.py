import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

from test_store_launch import store


class RockstarLaunch(unittest.TestCase):
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
