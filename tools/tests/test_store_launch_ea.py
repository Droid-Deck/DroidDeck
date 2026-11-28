import base64
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch

BIN = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin'
loader = importlib.machinery.SourceFileLoader('droiddeck_store_launch_ea', str(BIN / 'droiddeck-store-launch'))
spec = importlib.util.spec_from_loader(loader.name, loader)
store = importlib.util.module_from_spec(spec)
loader.exec_module(store)


def called_imports(exe):
    wide = struct.unpack_from('<H', exe, 0x58)[0] == 0x20B
    base = struct.unpack_from('<Q', exe, 0x58 + 24)[0] if wide else struct.unpack_from('<I', exe, 0x58 + 28)[0]
    dirs = 0x58 + struct.unpack_from('<H', exe, 0x54)[0] - 128
    raw = lambda rva: 0x200 + rva - 0x1000
    ilt, _, _, dll, iat = struct.unpack_from('<IIIII', exe, raw(struct.unpack_from('<I', exe, dirs + 8)[0]))
    thunk = 8 if wide else 4
    slots = {}
    for i in range(3):
        hint = struct.unpack_from('<Q' if wide else '<I', exe, raw(iat) + thunk * i)[0]
        if hint != struct.unpack_from('<Q' if wide else '<I', exe, raw(ilt) + thunk * i)[0]:
            return None
        slots[iat + thunk * i] = exe[raw(hint) + 2:].split(b'\0')[0]
    code = exe[0x200:0x300]
    called = []
    for at in range(len(code) - 6):
        if code[at:at + 2] == b'\xff\x15':
            value = struct.unpack_from('<i' if wide else '<I', code, at + 2)[0]
            called.append(slots.get(0x1000 + at + 6 + value if wide else value - base))
    return exe[raw(dll):].split(b'\0')[0], called


PROBE_NARROW = bytes.fromhex('1600000047656e756e74656c696e654961060a00000805000f23d882fffb8b27436f727465782d4135323000000000000000000000000000000000000000000000000000000000000000000000000000')
PROBE_WIDE = bytearray(PROBE_NARROW)
PROBE_WIDE[16 + 8:16 + 12] = bytes.fromhex('0f33d8be')


def fake_probe(wide):
    def run(argv, **kwargs):
        bat = Path(argv[-1][2:].replace('\\', '/'))
        for line in bat.read_text().splitlines():
            found = re.search(r'"?([^"]*)"? > "([^"]*)"$', line)
            if not found:
                continue
            source, out = found.group(1), Path(found.group(2)[2:].replace('\\', '/'))
            if source.endswith('cpuid64.exe'):
                if wide is not None:
                    out.write_bytes(wide)
            elif source.endswith('cpuid.exe'):
                out.write_bytes(PROBE_NARROW)
            elif source.startswith('vol'):
                out.write_text(' Volume Serial Number is 1A2B-3C4D\r\n')
            else:
                out.write_text('Header\r\nvalue\r\n')
    return run


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

    def test_a_symlinked_or_deep_exe_still_finds_its_launcher(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp) / 'Game'
            deep = folder / 'a/b/c/d/e/f'
            deep.mkdir(parents=True)
            (deep / 'Game.exe').write_text('x')
            (folder / store.LAUNCHER).write_text('x')
            sidecar = {'store': 'ea', 'id': 'Origin.OFR.50.1', 'exe': 'a/b/c/d/e/f/Game.exe', 'launcher': store.LAUNCHER}
            (folder / store.SIDECAR).write_text(json.dumps(sidecar))
            link = Path(tmp) / 'x/y/z/Link.exe'
            link.parent.mkdir(parents=True)
            link.symlink_to(deep / 'Game.exe')
            expected = str(folder.resolve() / store.LAUNCHER)
            self.assertEqual(store.store_launcher(str(deep / 'Game.exe')), expected)
            self.assertEqual(store.store_launcher(str(link)), expected)
            self.assertIsNone(store.find_store_game([str(deep / 'Game.exe')]))

    def test_a_symlinked_exe_finds_the_sidecar_beside_the_link(self):
        with tempfile.TemporaryDirectory() as tmp:
            outside = Path(tmp) / 'Real/Game.exe'
            outside.parent.mkdir()
            outside.write_text('x')
            folder = Path(tmp) / 'Game'
            folder.mkdir()
            (folder / store.LAUNCHER).write_text('x')
            (folder / 'Game.exe').symlink_to(outside)
            sidecar = {'store': 'ea', 'id': 'Origin.OFR.50.1', 'exe': 'Game.exe', 'launcher': store.LAUNCHER}
            (folder / store.SIDECAR).write_text(json.dumps(sidecar))
            self.assertEqual(store.store_launcher(str(folder / 'Game.exe')), str(folder / store.LAUNCHER))
            self.assertIsNone(store.store_launcher(str(outside)))

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
        self.assertEqual(called_imports(exe), (b'KERNEL32.dll', [b'GetStdHandle', b'WriteFile', b'ExitProcess']))

    def test_cpuid_probe_has_a_64_bit_form_that_writes_the_same_leaves(self):
        exe = store.cpuid_exe(True)
        self.assertEqual(exe[:2], b'MZ')
        self.assertEqual(struct.unpack_from('<H', exe, 0x44)[0], 0x8664)
        self.assertEqual(struct.unpack_from('<H', exe, 0x58)[0], 0x20B)
        self.assertEqual(struct.unpack_from('<H', exe, 0x58 + 68)[0], 3)
        self.assertEqual(len(exe), 0x400)
        self.assertEqual(called_imports(exe), (b'KERNEL32.dll', [b'GetStdHandle', b'WriteFile', b'ExitProcess']))
        code = exe[0x200:0x300]
        self.assertEqual(code.count(b'\x0f\xa2'), len(store.CPUID_LEAVES))
        loads = {}
        for at in range(len(code) - 7):
            if code[at:at + 3] in (b'\x48\x8d\x3d', b'\x48\x8d\x15', b'\x4c\x8d\x0d'):
                loads[code[at:at + 3]] = at + 7 + struct.unpack_from('<i', code, at + 3)[0]
        self.assertEqual(loads[b'\x48\x8d\x3d'], loads[b'\x48\x8d\x15'])
        self.assertGreaterEqual(loads[b'\x4c\x8d\x0d'], loads[b'\x48\x8d\x15'] + 16 * len(store.CPUID_LEAVES))
        self.assertLessEqual(loads[b'\x4c\x8d\x0d'] + 4, 0x200)
        self.assertFalse(any(exe[0x200 + loads[b'\x48\x8d\x15']:0x200 + loads[b'\x4c\x8d\x0d'] + 4]))
        self.assertEqual(store.cpuid_exe(False), store.cpuid_exe())

    def test_each_game_is_sent_the_cpuid_of_its_own_bitness(self):
        narrow = {'cpuVendor': 'GenuineIntel', 'cpuEax': '000A0661', 'cpuEdx': '278BFBFF', 'cpuEcx': '82d8230f', 'cpuBrand': 'Cortex-A520'}
        probe = dict({key: 'x' for key in store.EA_PROBE_FIELDS}, **narrow)
        values = dict(probe, cpu64=dict(narrow, cpuEcx='bed8330f'), runtime='stamp')
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            (folder / 'Game64.exe').write_bytes(store.cpuid_exe(True))
            (folder / 'bin').mkdir()
            (folder / 'bin/Game32.exe').write_bytes(store.cpuid_exe())
            self.assertEqual(store.ea_game_hardware(values, folder, {'exe': 'game64.exe'}), dict(probe, cpuEcx='bed8330f'))
            self.assertEqual(store.ea_game_hardware(values, folder, {'exe': 'bin/Game32.exe'}), probe)
            self.assertEqual(store.ea_game_hardware(values, folder, {'exe': 'Missing.exe'}), probe)
            self.assertEqual(store.ea_game_hardware(values, folder, {}), probe)
            self.assertEqual(store.ea_game_hardware(probe, folder, {'exe': 'Game64.exe'}), probe)

    def test_a_probe_from_another_proton_or_fex_build_is_taken_again(self):
        with tempfile.TemporaryDirectory() as tmp:
            proton = Path(tmp) / 'tool/proton'
            fex = proton.parent / 'files/lib/wine/aarch64-windows/libwow64fex.dll'
            fex.parent.mkdir(parents=True)
            proton.write_text('')
            (proton.parent / 'version').write_text('1 experimental-11.0')
            fex.write_bytes(b'fex 1')
            runtime = store.runtime_stamp(str(proton))
            self.assertTrue(runtime)
            self.assertEqual(store.runtime_stamp(str(proton)), runtime)
            self.assertEqual(store.runtime_stamp(''), '')
            compat = Path(tmp) / 'compat'
            (compat / 'pfx').mkdir(parents=True)
            cached = dict({key: 'x' for key in store.EA_PROBE_FIELDS}, runtime=runtime)
            (compat / 'pfx' / store.EA_HARDWARE).write_text(json.dumps(cached))
            with patch.object(store.subprocess, 'run', side_effect=AssertionError('probed again')):
                self.assertEqual(store.ea_hardware(str(compat), str(proton)), (cached, 'cached'))
                self.assertEqual(store.ea_hardware(str(compat), ''), (cached, 'cached'))
            fex.write_bytes(b'fex 2')
            self.assertNotEqual(store.runtime_stamp(str(proton)), runtime)
            with patch.object(store.subprocess, 'run', side_effect=OSError) as run:
                self.assertEqual(store.ea_hardware(str(compat), str(proton)), (cached, 'failed'))
            run.assert_called_once()
            (proton.parent / 'version').write_text('2 experimental-11.0')
            self.assertNotEqual(store.runtime_stamp(str(proton)), runtime)

    def test_the_probe_records_both_bitnesses_and_the_runtime(self):
        with tempfile.TemporaryDirectory() as tmp:
            proton = Path(tmp) / 'tool/proton'
            proton.parent.mkdir()
            proton.write_text('')
            compat = Path(tmp) / 'compat'
            (compat / 'pfx').mkdir(parents=True)
            with patch.object(store.subprocess, 'run', side_effect=fake_probe(bytes(PROBE_WIDE))):
                values, how = store.ea_hardware(str(compat), str(proton))
            self.assertEqual(how, 'probed')
            self.assertEqual(values['cpuEcx'], '82d8230f')
            self.assertEqual(values['cpu64']['cpuEcx'], 'bed8330f')
            self.assertEqual(values['volumeSerial'], '1a2b3c4d')
            self.assertEqual(values['runtime'], store.runtime_stamp(str(proton)))
            self.assertEqual(json.loads((compat / 'pfx' / store.EA_HARDWARE).read_text()), values)

    def test_a_failed_64_bit_probe_keeps_the_old_cache_and_is_retried(self):
        with tempfile.TemporaryDirectory() as tmp:
            proton = Path(tmp) / 'tool/proton'
            proton.parent.mkdir()
            proton.write_text('')
            compat = Path(tmp) / 'compat'
            (compat / 'pfx').mkdir(parents=True)
            cache = compat / 'pfx' / store.EA_HARDWARE
            for wide in (None, PROBE_NARROW[:40]):
                with patch.object(store.subprocess, 'run', side_effect=fake_probe(wide)):
                    values, how = store.ea_hardware(str(compat), str(proton))
                self.assertEqual(how, 'incomplete')
                self.assertEqual(values['cpuEcx'], '82d8230f')
                self.assertNotIn('cpu64', values)
                self.assertNotIn('runtime', values)
                self.assertFalse(cache.exists())
            stale = dict({key: 'x' for key in store.EA_PROBE_FIELDS}, cpu64={'cpuEcx': 'bed8330f'}, runtime='old')
            cache.write_text(json.dumps(stale))
            with patch.object(store.subprocess, 'run', side_effect=fake_probe(None)):
                self.assertEqual(store.ea_hardware(str(compat), str(proton)), (stale, 'incomplete'))
            self.assertEqual(json.loads(cache.read_text()), stale)
            with patch.object(store.subprocess, 'run', side_effect=fake_probe(bytes(PROBE_WIDE))):
                values, how = store.ea_hardware(str(compat), str(proton))
            self.assertEqual(how, 'probed')
            self.assertEqual(values['cpu64']['cpuEcx'], 'bed8330f')
            self.assertEqual(json.loads(cache.read_text()), values)

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

            def fake_ask(channel, request, timeout=5.0, claim=False):
                asked.update(request)
                return {'ok': True, 'lsx': True, 'license': 'ok v4', 'licenses': [{'path': 'ProgramData/L/x.dlf', 'data': base64.b64encode(b'z').decode()}]}

            with patch.object(store, 'ask', fake_ask), patch.object(store, 'ea_registry', lambda *a, **k: 'ready'), \
                    patch.object(store, 'ea_touchup', lambda *a, **k: 'none'), patch.dict('os.environ', {'DD_STORE_PROTON': ''}):
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

    def touchup_setup(self, tmp):
        folder = Path(tmp) / 'game'
        (folder / 'Touchup').mkdir(parents=True)
        (folder / 'Touchup/touchup.exe').write_text('')
        compat = Path(tmp) / 'compat'
        (compat / 'pfx').mkdir(parents=True)
        proton = Path(tmp) / 'proton'
        proton.write_text('')
        sidecar = {'id': 'Origin.OFR.1', 'installVersion': '1', 'installedAt': 'now', 'extra': {'touchup': 'Touchup/touchup.exe'}}
        return folder, sidecar, str(compat), str(proton)

    def test_a_failed_touchup_is_retried_and_a_successful_one_is_not(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder, sidecar, compat, proton = self.touchup_setup(tmp)
            codes = iter([3, 124, 0])
            calls = []

            def fake_run(cmd, **kwargs):
                calls.append(cmd)
                kwargs['stdout'].write(b'PhysX failed\n')
                return subprocess.CompletedProcess(cmd, next(codes))

            logged = []
            with patch.object(store.subprocess, 'run', fake_run), patch.object(store, 'log', logged.append):
                self.assertEqual(store.ea_touchup(folder, sidecar, compat, proton), 'exit-3')
                self.assertEqual(store.ea_touchup(folder, sidecar, compat, proton), 'exit-124')
                self.assertEqual(store.ea_touchup(folder, sidecar, compat, proton), 'ok')
                self.assertEqual(store.ea_touchup(folder, sidecar, compat, proton), 'done')
            self.assertEqual(len(calls), 3)
            self.assertIn('touchup: PhysX failed', logged)

    def test_a_timed_out_touchup_is_retried(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder, sidecar, compat, proton = self.touchup_setup(tmp)
            calls = []

            def fake_run(cmd, **kwargs):
                calls.append(cmd)
                kwargs['stdout'].write(b'still running\n')
                if len(calls) == 1:
                    raise subprocess.TimeoutExpired(cmd, 1)
                return subprocess.CompletedProcess(cmd, 0)

            logged = []
            marker = list((Path(compat) / 'pfx').glob('.droiddeck-ea-touchup-*'))
            with patch.object(store.subprocess, 'run', fake_run), patch.object(store, 'log', logged.append):
                self.assertEqual(store.ea_touchup(folder, sidecar, compat, proton), 'timeout')
                self.assertIn('touchup: still running', logged)
                self.assertEqual(list((Path(compat) / 'pfx').glob('.droiddeck-ea-touchup-*')), marker)
                self.assertEqual(store.ea_touchup(folder, sidecar, compat, proton), 'ok')
                self.assertEqual(store.ea_touchup(folder, sidecar, compat, proton), 'done')
            self.assertEqual(len(calls), 2)

    def test_only_the_tail_of_a_long_touchup_output_is_logged(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder, sidecar, compat, proton = self.touchup_setup(tmp)

            def fake_run(cmd, **kwargs):
                kwargs['stdout'].write(b''.join(b'line %d\n' % i for i in range(100000)))
                return subprocess.CompletedProcess(cmd, 0)

            logged = []
            with patch.object(store.subprocess, 'run', fake_run), patch.object(store, 'log', logged.append):
                self.assertEqual(store.ea_touchup(folder, sidecar, compat, proton), 'ok')
            self.assertEqual(len(logged), 200)
            self.assertEqual(logged[-1], 'touchup: line 99999')

    def test_touchup_runs_and_is_marked_when_its_output_cannot_be_captured(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder, sidecar, compat, proton = self.touchup_setup(tmp)
            seen = []

            def fake_run(cmd, **kwargs):
                seen.append(kwargs['stdout'])
                return subprocess.CompletedProcess(cmd, 0)

            def no_space(*a, **k):
                raise OSError('no space')

            with patch.object(store.subprocess, 'run', fake_run), patch.object(store.tempfile, 'TemporaryFile', no_space):
                self.assertEqual(store.ea_touchup(folder, sidecar, compat, proton), 'ok')
                self.assertEqual(store.ea_touchup(folder, sidecar, compat, proton), 'done')
            self.assertEqual(seen, [subprocess.DEVNULL])

    def test_touchup_result_survives_an_unreadable_output_tail(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder, sidecar, compat, proton = self.touchup_setup(tmp)

            class Broken:
                def seek(self, *a):
                    raise OSError('io')

                def close(self):
                    pass

            with patch.object(store.subprocess, 'run', lambda cmd, **k: subprocess.CompletedProcess(cmd, 0)), \
                    patch.object(store.tempfile, 'TemporaryFile', Broken):
                self.assertEqual(store.ea_touchup(folder, sidecar, compat, proton), 'ok')


if __name__ == '__main__':
    unittest.main()
