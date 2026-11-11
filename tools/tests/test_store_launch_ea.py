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
        sidecar = {'id': 'Origin.OFR.50.0001', 'title': 'Mass Effect 3', 'extra': {'registry': json.dumps([
            [r'HKEY_LOCAL_MACHINE\SOFTWARE\EA Games\Game', 'Install Dir'],
            [r'HKLM\SOFTWARE\Respawn\Titanfall2', 'Install Dir'],
            [r'HKEY_CLASSES_ROOT\x', 'y'],
        ])}}
        entries = store.ea_registry_entries(Path('/root/Games/Stores/EA/Game'), sidecar)
        self.assertIn((r'HKEY_LOCAL_MACHINE\SOFTWARE\Origin', 'ClientPath', store.EA_CLIENT), entries)
        game = [e for e in entries if e[1] == 'Install Dir']
        self.assertEqual([e[0] for e in game], [r'HKEY_LOCAL_MACHINE\SOFTWARE\EA Games\Game', r'HKEY_LOCAL_MACHINE\SOFTWARE\Respawn\Titanfall2'])
        self.assertEqual(game[0][2], 'Z:' + '\\'.join(['', 'root', 'Games', 'Stores', 'EA', 'Game', '']))
        self.assertIn((r'HKEY_LOCAL_MACHINE\SOFTWARE\EA Games\Game', 'DisplayName', 'Mass Effect 3'), entries)
        self.assertIn((r'HKEY_LOCAL_MACHINE\SOFTWARE\Respawn\Titanfall2', 'DisplayName', 'Mass Effect 3'), entries)

    def test_the_games_exe_maps_to_its_launcher_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp) / 'Game'
            (folder / 'bin').mkdir(parents=True)
            for name in ('bin/Game.exe', 'bin/Other.exe', store.LAUNCHER):
                (folder / name).write_text('x')
            sidecar = {'store': 'ea', 'id': 'Origin.OFR.50.1', 'exe': 'bin/Game.exe', 'launcher': store.LAUNCHER}
            (folder / store.SIDECAR).write_text(json.dumps(sidecar))
            self.assertEqual(store.store_launcher(str(folder / 'bin/Game.exe')), str(folder / store.LAUNCHER))
            self.assertEqual(store.store_launcher(str(folder / 'bin/game.exe')), str(folder / store.LAUNCHER))
            self.assertIsNone(store.store_launcher(str(folder / 'bin/Other.exe')))
            self.assertIsNone(store.store_launcher(str(Path(tmp) / 'Elsewhere.exe')))
            (folder / store.SIDECAR).write_text(json.dumps(dict(sidecar, launcher='')))
            self.assertIsNone(store.store_launcher(str(folder / 'bin/Game.exe')))

    def test_legacy_games_are_registered_by_content_id(self):
        sidecar = {'id': 'Origin.OFR.50.0005237', 'title': 'Mass Effect 3 (2012)',
                   'extra': {'contentIds': '71073,71183, bad id', 'displayName': 'Mass Effect 3', 'locale': 'fr_FR'}}
        entries = store.ea_registry_entries(Path('/root/Games/ME3'), sidecar)
        key = r'HKEY_LOCAL_MACHINE\SOFTWARE\Origin Games' + '\\'
        self.assertIn((key + '71073', 'DisplayName', 'Mass Effect 3'), entries)
        self.assertIn((key + '71183', 'Locale', 'fr_FR'), entries)
        self.assertFalse(any('bad id' in e[0] for e in entries))

    def test_cpuid_probe_is_a_32_bit_console_program(self):
        import struct
        exe = store.cpuid_exe()
        self.assertEqual(exe[:2], b'MZ')
        self.assertEqual(exe[0x40:0x44], b'PE\0\0')
        self.assertEqual(struct.unpack_from('<H', exe, 0x44)[0], 0x14C)
        self.assertEqual(struct.unpack_from('<H', exe, 0x58)[0], 0x10B)
        self.assertEqual(struct.unpack_from('<H', exe, 0x58 + 68)[0], 3)
        self.assertIn(b'KERNEL32.dll', exe)
        self.assertEqual(len(exe), 0x400)

    def test_origin_stub_is_a_windowless_program_installed_once(self):
        import struct
        exe = store.origin_stub_exe()
        self.assertEqual(exe[:2], b'MZ')
        self.assertEqual(struct.unpack_from('<H', exe, 0x58 + 68)[0], 2)
        self.assertIn(b'Sleep', exe)
        self.assertEqual(exe[0x208:0x20A], b'\xeb\xf6')
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(store.install_origin_stub(tmp), 'written')
            self.assertEqual(store.install_origin_stub(tmp), 'ready')
            self.assertTrue((Path(tmp) / 'pfx/drive_c/Program Files (x86)/Origin/Origin.exe').is_file())
        self.assertTrue(store.EA_CLIENT.endswith('Origin.exe'))

    def test_cpuid_output_is_decoded_as_the_game_sees_it(self):
        raw = bytes.fromhex('1600000047656e756e74656c696e654961060a00000805000f23d882fffb8b27436f727465782d4135323000000000000000000000000000000000000000000000000000000000000000000000000000')
        self.assertEqual(store.cpuid_values(raw), {'cpuVendor': 'GenuineIntel', 'cpuEax': '000A0661', 'cpuEdx': '278BFBFF',
                                                   'cpuEcx': '82d8230f', 'cpuBrand': 'Cortex-A520'})
        self.assertEqual(store.cpuid_values(b''), {})

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
            probe = {key: 'x' for key in store.EA_PROBE_FIELDS}
            (compat / 'pfx' / store.EA_HARDWARE).write_text(json.dumps(probe))
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
            self.assertEqual(asked['hardware'], probe)
            self.assertEqual((compat / 'pfx/drive_c/ProgramData/L/x.dlf').read_bytes(), b'z')

    def test_an_incomplete_cached_probe_is_probed_again(self):
        with tempfile.TemporaryDirectory() as tmp:
            pfx = Path(tmp) / 'pfx'
            pfx.mkdir()
            partial = {key: 'x' for key in store.EA_PROBE_FIELDS if key != 'osInstallDate'}
            (pfx / store.EA_HARDWARE).write_text(json.dumps(partial))
            self.assertFalse(store.probe_complete(partial))
            self.assertEqual(store.ea_hardware(tmp, ''), ({}, 'no-proton'))
            whole = dict(partial, osInstallDate='20261108')
            (pfx / store.EA_HARDWARE).write_text(json.dumps(whole))
            self.assertEqual(store.ea_hardware(tmp, ''), (whole, 'cached'))


if __name__ == '__main__':
    unittest.main()
