import base64
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

BIN = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin'
loader = importlib.machinery.SourceFileLoader('droiddeck_store_launch_ea', str(BIN / 'droiddeck-store-launch'))
spec = importlib.util.spec_from_loader(loader.name, loader)
store = importlib.util.module_from_spec(spec)
loader.exec_module(store)


class EaHook(unittest.TestCase):
    def test_wmi_output_reads_the_value_under_the_header(self):
        text = 'Manufacturer  \r\r\nThe Wine Project  \r\r\n\r\r\n'
        self.assertEqual(store.wmi_value(text), 'The Wine Project')
        self.assertEqual(store.wmi_value(store.decode_output('Name\r\nFEX CPU\r\n'.encode('utf-16'))), 'FEX CPU')
        self.assertEqual(store.wmi_value(''), '')

    def test_registry_points_the_game_keys_at_the_install_folder(self):
        sidecar = {'id': 'Origin.OFR.50.0001', 'extra': {'registry': json.dumps([
            [r'HKEY_LOCAL_MACHINE\SOFTWARE\EA Games\Game', 'Install Dir'],
            [r'HKLM\SOFTWARE\Respawn\Titanfall2', 'Install Dir'],
            [r'HKEY_CLASSES_ROOT\x', 'y'],
        ])}}
        entries = store.ea_registry_entries(Path('/root/Games/Stores/EA/Game'), sidecar)
        self.assertIn((r'HKEY_LOCAL_MACHINE\SOFTWARE\Origin', 'ClientPath', store.EA_CLIENT), entries)
        game = [e for e in entries if e[1] == 'Install Dir']
        self.assertEqual([e[0] for e in game], [r'HKEY_LOCAL_MACHINE\SOFTWARE\EA Games\Game', r'HKEY_LOCAL_MACHINE\SOFTWARE\Respawn\Titanfall2'])
        self.assertEqual(game[0][2], 'Z:' + '\\'.join(['', 'root', 'Games', 'Stores', 'EA', 'Game', '']))

    def test_touchup_arguments_fill_locale_and_folder(self):
        install = r'Z:\root\My Game'
        args = store.touchup_args('-locale {locale} -installPath "{installLocation}" /silent', 'fr_FR', install)
        self.assertEqual(args, ['-locale', 'fr_FR', '-installPath', install, '/silent'])

    def test_licences_stay_inside_the_prefix(self):
        with tempfile.TemporaryDirectory() as tmp:
            data = base64.b64encode(b'licence').decode()
            written = store.write_licenses(tmp, [
                {'path': 'ProgramData/Electronic Arts/EA Services/License/X.dlf', 'data': data},
                {'path': '../escape.dlf', 'data': data},
                {'path': '/abs.dlf', 'data': data},
            ])
            self.assertEqual(written, 1)
            self.assertEqual((Path(tmp) / 'pfx/drive_c/ProgramData/Electronic Arts/EA Services/License/X.dlf').read_bytes(), b'licence')

    def test_prepare_asks_the_app_and_writes_the_licence(self):
        with tempfile.TemporaryDirectory() as tmp:
            compat = Path(tmp) / 'compat'
            (compat / 'pfx').mkdir(parents=True)
            (compat / 'pfx/user.reg').write_text('')
            (compat / 'pfx' / store.EA_HARDWARE).write_text(json.dumps({'processorId': 'ABC'}))
            folder = Path(tmp) / 'game'
            folder.mkdir()
            asked = {}

            def fake_ask(channel, request, timeout=5.0):
                asked.update(request)
                return {'ok': True, 'lsx': True, 'license': 'ok v4', 'licenses': [{'path': 'ProgramData/L/x.dlf', 'data': base64.b64encode(b'z').decode()}]}

            with patch.object(store, 'ask', fake_ask), patch.object(store, 'ea_registry', lambda *a, **k: 'ready'), \
                    patch.object(store, 'ea_touchup', lambda *a, **k: 'none'), patch.dict('os.environ', {'BL_STORE_PROTON': ''}):
                store.prepare_ea(folder, {'id': 'Origin.OFR.1'}, str(compat), str(Path(tmp) / 'launch'))
            self.assertEqual(asked['op'], 'ea-prepare')
            self.assertEqual(asked['hardware'], {'processorId': 'ABC'})
            self.assertEqual((compat / 'pfx/drive_c/ProgramData/L/x.dlf').read_bytes(), b'z')


if __name__ == '__main__':
    unittest.main()
