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
                                                            'exe': '/private/Game.exe', 'playfab': 'game-token',
                                                            'mountRoot': '/launch/xodus-mounts-1'})}
            close = unittest.mock.Mock()
            with patch('runpy.run_path', return_value=store), patch.dict(xbox['launch'].__globals__, RUNTIME=runtime.parent), \
                 patch.dict(xbox['launch'].__globals__, bridge=lambda path: (1234, 'key', close)), \
                 patch.dict(xbox['launch'].__globals__, wine=unittest.mock.Mock(return_value=7)):
                launch = xbox['launch']
                self.assertEqual(launch(['/proton', 'waitforexitandrun', '/encrypted/Game.exe'],
                                        {'DD_LAUNCH_DIR': '/launch', 'DROIDDECK_XBOX_CREDENTIAL_SOCKET': 'stale',
                                         'DROIDDECK_XBOX_CREDENTIAL_PORT': '4100', 'DROIDDECK_XBOX_CREDENTIAL_KEY': 'stale-key'}), 7)
                wine = launch.__globals__['wine']
                self.assertEqual(wine.call_args.args[0], ['/private/Game.exe'])
                env = wine.call_args.args[1]
                for name in ('DROIDDECK_XBOX_CREDENTIAL_SOCKET', 'DROIDDECK_XBOX_CREDENTIAL_PORT', 'DROIDDECK_XBOX_CREDENTIAL_KEY'):
                    self.assertNotIn(name, env)
                self.assertEqual(env['XODUS_XSTS_PLAYFAB'], 'game-token')
                self.assertEqual(env['XODUS_MOUNT_ROOT'], 'Z:\\launch\\xodus-mounts-1')
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
                    xbox['launch'](['/proton', 'waitforexitandrun', '/Game.exe'], {'DD_LAUNCH_DIR': '/launch'})
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

    def test_package_install_actions_run_once_and_stay_inside_the_game(self):
        with tempfile.TemporaryDirectory() as root:
            game, prefix = Path(root) / 'game', Path(root) / 'pfx'
            (game / 'Installers').mkdir(parents=True)
            (game / 'Installers' / 'GameInputRedist.msi').write_bytes(b'msi')
            (game / 'Emu.exe').write_bytes(b'exe')
            (Path(root) / 'outside.msi').write_bytes(b'msi')
            (game / 'MicrosoftGame.config').write_text(
                '<Game><DesktopRegistration><CustomInstallActions><Folder>Installers</Folder><InstallActionList>'
                '<InstallAction File="GameInputRedist.msi" Name="InstallGameInput" Arguments="/quiet"/>'
                '<InstallAction File="..\\..\\outside.msi" Name="Escape"/>'
                '</InstallActionList></CustomInstallActions></DesktopRegistration></Game>')
            with patch('subprocess.run', return_value=subprocess.CompletedProcess([], 0)) as child:
                xbox['run_install_actions']('/selected/proton', prefix, str(game / 'Emu.exe'), {'A': '1'})
                xbox['run_install_actions']('/selected/proton', prefix, str(game / 'Emu.exe'), {'A': '1'})
            self.assertEqual(child.call_count, 1)
            command = child.call_args.args[0]
            self.assertEqual(command[:4], ['/selected/proton', 'run', 'msiexec', '/i'])
            self.assertTrue(command[4].startswith('Z:') and command[4].endswith('GameInputRedist.msi'))
            self.assertEqual(command[5:], ['/quiet'])

    def test_a_failed_install_action_is_retried_on_the_next_launch(self):
        with tempfile.TemporaryDirectory() as root:
            game, prefix = Path(root) / 'game', Path(root) / 'pfx'
            game.mkdir()
            (game / 'setup.exe').write_bytes(b'exe')
            (game / 'MicrosoftGame.config').write_text(
                '<Game><CustomInstallActions><InstallActionList><InstallAction File="setup.exe" Name="Setup"/>'
                '</InstallActionList></CustomInstallActions></Game>')
            with patch('subprocess.run', return_value=subprocess.CompletedProcess([], 1)) as child:
                xbox['run_install_actions']('/p', prefix, str(game / 'setup.exe'), {})
                xbox['run_install_actions']('/p', prefix, str(game / 'setup.exe'), {})
            self.assertEqual(child.call_count, 2)
            self.assertEqual(child.call_args.args[0][:2], ['/p', 'run'])

    def test_install_action_arguments_follow_windows_command_line_rules(self):
        parse = xbox['windows_arguments']
        self.assertEqual(parse('/quiet /log "C:\\ProgramData\\Game Setup\\install.log"'),
                         ['/quiet', '/log', 'C:\\ProgramData\\Game Setup\\install.log'])
        self.assertEqual(parse('a\\\\b "x\\"y" "\\\\" ""  c""d "e""f"'), ['a\\\\b', 'x"y', '\\', '', 'cd', 'e"f'])
        self.assertEqual(parse('  '), [])
        with tempfile.TemporaryDirectory() as root:
            game, prefix = Path(root) / 'game', Path(root) / 'pfx'
            game.mkdir()
            (game / 'setup.msi').write_bytes(b'msi')
            (game / 'MicrosoftGame.config').write_text(
                '<Game><CustomInstallActions><InstallActionList><InstallAction File="setup.msi" Name="Setup" '
                'Arguments="/quiet /log &quot;C:\\ProgramData\\Game Setup\\install.log&quot;"/>'
                '</InstallActionList></CustomInstallActions></Game>')
            with patch('subprocess.run', return_value=subprocess.CompletedProcess([], 0)) as child:
                xbox['run_install_actions']('/p', prefix, str(game / 'setup.msi'), {})
            self.assertEqual(child.call_args.args[0][5:], ['/quiet', '/log', 'C:\\ProgramData\\Game Setup\\install.log'])

    def test_actions_whose_names_differ_only_in_punctuation_each_run(self):
        with tempfile.TemporaryDirectory() as root:
            game, prefix = Path(root) / 'game', Path(root) / 'pfx'
            game.mkdir()
            (game / 'setup.exe').write_bytes(b'exe')
            (game / 'MicrosoftGame.config').write_text(
                '<Game><CustomInstallActions><InstallActionList>'
                '<InstallAction File="setup.exe" Name="Setup A" Arguments="/a"/>'
                '<InstallAction File="setup.exe" Name="SetupA" Arguments="/b"/>'
                '</InstallActionList></CustomInstallActions></Game>')
            with patch('subprocess.run', return_value=subprocess.CompletedProcess([], 0)) as child:
                xbox['run_install_actions']('/p', prefix, str(game / 'setup.exe'), {})
                xbox['run_install_actions']('/p', prefix, str(game / 'setup.exe'), {})
            self.assertEqual([call.args[0][-1] for call in child.call_args_list], ['/a', '/b'])

    def test_an_install_action_error_never_stops_the_launch(self):
        with tempfile.TemporaryDirectory() as root:
            game, prefix = Path(root) / 'game', Path(root) / 'pfx'
            game.mkdir()
            (game / 'first.exe').write_bytes(b'exe')
            (game / 'second.exe').write_bytes(b'exe')
            (game / 'MicrosoftGame.config').write_text(
                '<Game><CustomInstallActions><InstallActionList>'
                '<InstallAction File="first.exe" Name="First"/><InstallAction File="second.exe" Name="Second"/>'
                '</InstallActionList></CustomInstallActions></Game>')
            real_open = Path.open
            def unreadable(path, *args, **kwargs):
                if path.name == 'first.exe':
                    raise PermissionError('denied')
                return real_open(path, *args, **kwargs)
            with patch.object(Path, 'open', unreadable), \
                    patch('subprocess.run', return_value=subprocess.CompletedProcess([], 0)) as child:
                xbox['run_install_actions']('/p', prefix, str(game / 'first.exe'), {})
            self.assertEqual(child.call_count, 1)
            self.assertTrue(child.call_args.args[0][2].endswith('second.exe'))
            with patch('subprocess.run', side_effect=OSError('no proton')):
                xbox['run_install_actions']('/p', prefix, str(game / 'first.exe'), {})
            with patch.object(Path, 'touch', side_effect=OSError('read-only')), \
                    patch('subprocess.run', return_value=subprocess.CompletedProcess([], 0)) as child:
                xbox['run_install_actions']('/p', prefix / 'other', str(game / 'first.exe'), {})
            self.assertEqual(child.call_count, 2)

    def test_missing_runtime_classes_are_registered_once(self):
        with tempfile.TemporaryDirectory() as root:
            prefix = Path(root)
            key = xbox['CLASS_KEY'].replace('\\', '\\\\')
            section = '[%s%s] 1\n"DllPath"="C:\\\\windows\\\\system32\\\\%s"\n\n'
            present = [(name, dll) for dll, names in xbox['CLASSES'].items() for name in names
                       if name != 'Windows.ApplicationModel.DataTransfer.DataTransferManager']
            (prefix / 'system.reg').write_text(''.join(section % (key, name, dll) for name, dll in present))
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
                file.write(section % (key, 'Windows.ApplicationModel.DataTransfer.DataTransferManager', 'twinapi.appcore.dll'))
            with patch('subprocess.run') as child:
                xbox['register_classes']('/selected/proton', prefix, {})
                (prefix / 'system.reg').unlink()
                xbox['register_classes']('/selected/proton', prefix, {})
            child.assert_not_called()

    def test_package_classes_come_from_the_manifest(self):
        with tempfile.TemporaryDirectory() as root:
            package = Path(root) / 'Game'
            (package / 'bin').mkdir(parents=True)
            (package / 'Wheel.dll').write_bytes(b'MZ')
            (package / 'bin' / 'Live.dll').write_bytes(b'MZ')
            server = ('<Extension Category="windows.activatableClass.inProcessServer"><InProcessServer>'
                      '<Path>%s</Path>%s</InProcessServer></Extension>')
            (package / 'AppxManifest.xml').write_text(''.join((
                server % ('Wheel.dll', '<ActivatableClass ActivatableClassId="Vendor.Input.Wheel" ThreadingModel="both" />'),
                server % ('bin\\Live.dll', '<ActivatableClass ActivatableClassId="Vendor.Live.User" />'
                                           '<ActivatableClass ActivatableClassId="Vendor.Live.Context" />'),
                server % ('..\\Outside.dll', '<ActivatableClass ActivatableClassId="Vendor.Outside" />'),
                server % ('C:\\windows\\system32\\kernel32.dll', '<ActivatableClass ActivatableClassId="Vendor.Absolute" />'),
                server % ('Missing.dll', '<ActivatableClass ActivatableClassId="Vendor.Missing" />'),
                server % ('Wheel.dll', '<ActivatableClass ActivatableClassId="Vendor.Bad]Name" />'))))
            windows = 'Z:' + str(package).replace('/', '\\')
            self.assertEqual(xbox['package_classes'](package),
                             {windows + '\\Wheel.dll': ['Vendor.Input.Wheel'],
                              windows + '\\bin\\Live.dll': ['Vendor.Live.User', 'Vendor.Live.Context']})
            self.assertEqual(xbox['package_classes'](Path('Game')), {})

            prefix = Path(root)
            key = xbox['CLASS_KEY'].replace('\\', '\\\\')
            (prefix / 'system.reg').write_text(''.join(
                '[%s%s] 1\n"DllPath"="C:\\\\windows\\\\system32\\\\%s"\n\n' % (key, name, dll)
                for dll, names in xbox['CLASSES'].items() for name in names))
            imported = []
            def run(argv, **kwargs):
                imported.append(Path(argv[4][2:].replace('\\', '/')).read_bytes()[2:].decode('utf-16-le'))
                return subprocess.CompletedProcess(argv, 0)
            with patch('subprocess.run', side_effect=run):
                xbox['register_classes']('/selected/proton', prefix, {}, {'Z:\\games\\Game\\Wheel.dll': ['Vendor.Input.Wheel']})
            self.assertEqual(imported, ['Windows Registry Editor Version 5.00\r\n\r\n[HKEY_LOCAL_MACHINE\\Software\\Microsoft\\WindowsRuntime'
                                        '\\ActivatableClassId\\Vendor.Input.Wheel]\r\n'
                                        '"DllPath"="Z:\\\\games\\\\Game\\\\Wheel.dll"\r\n\r\n'])

    def test_a_class_named_twice_resolves_to_the_later_dll(self):
        with tempfile.TemporaryDirectory() as root:
            prefix = Path(root)
            key = xbox['CLASS_KEY'].replace('\\', '\\\\')
            section = '[%s%s] 1\n"DllPath"="C:\\\\windows\\\\system32\\\\%s"\n\n'
            name = 'Windows.Devices.Enumeration.DeviceInformation'
            (prefix / 'system.reg').write_text(''.join(
                section % (key, other, 'uwp-host.dll' if other == name else dll)
                for dll, names in xbox['CLASSES'].items() for other in names))
            with patch('subprocess.run') as child:
                xbox['register_classes']('/selected/proton', prefix, {}, {'uwp-host.dll': (name,)})
            child.assert_not_called()

    def test_uwp_classes_override_windows_data_json_to_uwp_host(self):
        json_classes = (
            'Windows.Data.Json.JsonObject',
            'Windows.Data.Json.JsonArray',
            'Windows.Data.Json.JsonValue',
        )
        for cls in json_classes:
            self.assertIn(cls, xbox['UWP_CLASSES'])
        with tempfile.TemporaryDirectory() as root:
            prefix = Path(root)
            key = xbox['CLASS_KEY'].replace('\\', '\\\\')
            section = '[%s%s] 1\n"DllPath"="C:\\\\windows\\\\system32\\\\%s"\n\n'
            (prefix / 'system.reg').write_text(''.join(
                section % (key, other, dll)
                for dll, names in xbox['CLASSES'].items() for other in names))
            imported = []
            def run(argv, **kwargs):
                imported.append(Path(argv[4][2:].replace('\\', '/')).read_bytes()[2:].decode('utf-16-le'))
                return subprocess.CompletedProcess(argv, 0)
            with patch('subprocess.run', side_effect=run):
                xbox['register_classes']('/selected/proton', prefix, {}, {'uwp-host.dll': xbox['UWP_CLASSES']})
            self.assertEqual(len(imported), 1)
            for cls in json_classes:
                self.assertIn('[HKEY_LOCAL_MACHINE\\Software\\Microsoft\\WindowsRuntime\\ActivatableClassId\\%s]\r\n'
                              '"DllPath"="C:\\\\windows\\\\system32\\\\uwp-host.dll"' % cls, imported[0])

    def test_failed_class_registration_is_reported_and_launch_continues(self):
        with tempfile.TemporaryDirectory() as root:
            prefix = Path(root)
            (prefix / 'system.reg').write_text('')
            for outcome in ({'return_value': subprocess.CompletedProcess([], 1)},
                            {'side_effect': subprocess.TimeoutExpired('reg.exe', 90)}):
                with patch('subprocess.run', **outcome), patch('sys.stderr', new_callable=io.StringIO) as err:
                    self.assertIsNone(xbox['register_classes']('/selected/proton', prefix, {}))
                self.assertIn('registration failed', err.getvalue())


def pe_with_exports(names, forward=None, machine=0x8664, unnamed=0):
    """A minimal x64 PE image whose export table names `names`; with `forward`, every export is forwarded there.
    `unnamed` adds that many ordinal-only exports after the named ones."""
    rva, raw = 0x1000, 0x200
    functions = rva + 40
    name_pointers = functions + 4 * (len(names) + unnamed)
    ordinals = name_pointers + 4 * len(names)
    strings = ordinals + 2 * len(names)
    blob = bytearray()
    name_rvas, forward_rvas = [], []
    for name in names:
        name_rvas.append(strings + len(blob))
        blob += name.encode() + b'\0'
    for name in names:
        forward_rvas.append(strings + len(blob))
        if forward:
            blob += ('%s.%s' % (forward, name)).encode() + b'\0'
    size = strings + len(blob) - rva
    directory = bytearray(40)
    directory[16:20] = (1).to_bytes(4, 'little')
    directory[20:24] = (len(names) + unnamed).to_bytes(4, 'little')
    directory[24:28] = len(names).to_bytes(4, 'little')
    directory[28:32] = functions.to_bytes(4, 'little')
    directory[32:36] = name_pointers.to_bytes(4, 'little')
    directory[36:40] = ordinals.to_bytes(4, 'little')
    section = bytearray(directory)
    for index in range(len(names)):
        section += (forward_rvas[index] if forward else 0x3000 + index).to_bytes(4, 'little')
    for index in range(unnamed):
        section += (0x4000 + index).to_bytes(4, 'little')
    for value in name_rvas:
        section += value.to_bytes(4, 'little')
    for index in range(len(names)):
        section += index.to_bytes(2, 'little')
    section += blob
    image = bytearray(raw)
    image[:2] = b'MZ'
    image[60:64] = (0x40).to_bytes(4, 'little')
    image[0x40:0x44] = b'PE\0\0'
    image[0x44:0x46] = machine.to_bytes(2, 'little')
    image[0x46:0x48] = (1).to_bytes(2, 'little')
    image[0x54:0x56] = (240).to_bytes(2, 'little')
    optional = 0x58
    image[optional:optional + 2] = (0x20B).to_bytes(2, 'little')
    image[optional + 108:optional + 112] = (16).to_bytes(4, 'little')
    image[optional + 112:optional + 116] = rva.to_bytes(4, 'little')
    image[optional + 116:optional + 120] = size.to_bytes(4, 'little')
    header = optional + 240
    image[header + 8:header + 12] = len(section).to_bytes(4, 'little')
    image[header + 12:header + 16] = rva.to_bytes(4, 'little')
    image[header + 16:header + 20] = len(section).to_bytes(4, 'little')
    image[header + 20:header + 24] = raw.to_bytes(4, 'little')
    return bytes(image + section)


class PlayFabProxyTest(unittest.TestCase):
    SDK = pe_with_exports(xbox['PGS_EXPORTS'])
    NEWER_SDK = SDK + b'\0update'
    PROXY = pe_with_exports(xbox['PGS_EXPORTS'], forward='nexus_pgs_native')

    def stage(self, root, proxy=PROXY):
        runtime = Path(root) / 'runtime'
        if proxy is not None:
            (runtime / 'compat').mkdir(parents=True, exist_ok=True)
            (runtime / 'compat/PlayFabGameSave.dll').write_bytes(proxy)
        with patch.dict(xbox['stage_playfab_proxy'].__globals__, PGS_PROXY=runtime / 'compat/PlayFabGameSave.dll'), \
             patch('sys.stderr', new_callable=io.StringIO):
            return xbox['stage_playfab_proxy'](Path(root) / 'game')

    def game(self, root, data, sub='Bin'):
        folder = Path(root) / 'game' / sub
        folder.mkdir(parents=True)
        (folder / 'PlayFabGameSave.dll').write_bytes(data)
        return folder

    def test_sdk_and_proxy_are_told_apart_by_their_exports(self):
        self.assertTrue(xbox['playfab_sdk'](self.SDK))
        self.assertFalse(xbox['playfab_proxy'](self.SDK))
        self.assertTrue(xbox['playfab_proxy'](self.PROXY))
        self.assertFalse(xbox['playfab_sdk'](self.PROXY))
        other = pe_with_exports(list(xbox['PGS_EXPORTS'][:-1]) + ['PFGameSaveFilesSomethingNew'])
        self.assertFalse(xbox['playfab_sdk'](other))
        self.assertFalse(xbox['playfab_sdk'](pe_with_exports(xbox['PGS_EXPORTS'], machine=0xAA64)))
        self.assertIsNone(xbox['pe_exports'](b'not a pe image'))
        self.assertEqual(xbox['pe_exports'](pe_with_exports(['A', 'B'], unnamed=1))[0], 3)
        self.assertFalse(xbox['playfab_sdk'](pe_with_exports(xbox['PGS_EXPORTS'], unnamed=1)))
        self.assertFalse(xbox['playfab_proxy'](pe_with_exports(xbox['PGS_EXPORTS'], forward='nexus_pgs_native', unnamed=1)))

    def test_first_launch_keeps_the_sdk_and_puts_the_proxy_in_front(self):
        with tempfile.TemporaryDirectory() as root:
            folder = self.game(root, self.SDK)
            self.assertEqual(self.stage(root), 1)
            self.assertEqual((folder / 'nexus_pgs_native.dll').read_bytes(), self.SDK)
            self.assertEqual((folder / 'PlayFabGameSave.dll').read_bytes(), self.PROXY)
            self.assertEqual(self.stage(root), 1)
            self.assertEqual((folder / 'nexus_pgs_native.dll').read_bytes(), self.SDK)
            self.assertEqual(sorted(path.name for path in folder.iterdir()), ['PlayFabGameSave.dll', 'nexus_pgs_native.dll'])

    def test_a_game_update_replaces_the_kept_sdk(self):
        with tempfile.TemporaryDirectory() as root:
            folder = self.game(root, self.SDK)
            self.stage(root)
            (folder / 'PlayFabGameSave.dll').write_bytes(self.NEWER_SDK)
            self.assertEqual(self.stage(root), 1)
            self.assertEqual((folder / 'nexus_pgs_native.dll').read_bytes(), self.NEWER_SDK)
            self.assertEqual((folder / 'PlayFabGameSave.dll').read_bytes(), self.PROXY)

    def test_an_older_proxy_is_refreshed_only_while_its_sdk_is_there(self):
        older = self.PROXY + b'\0older'
        with tempfile.TemporaryDirectory() as root:
            folder = self.game(root, older)
            (folder / 'nexus_pgs_native.dll').write_bytes(self.SDK)
            self.assertEqual(self.stage(root), 1)
            self.assertEqual((folder / 'PlayFabGameSave.dll').read_bytes(), self.PROXY)
        with tempfile.TemporaryDirectory() as root:
            folder = self.game(root, older)
            self.assertEqual(self.stage(root), 0)
            self.assertEqual((folder / 'PlayFabGameSave.dll').read_bytes(), older)

    def test_an_unknown_file_or_link_in_place_of_the_kept_sdk_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as root:
            folder = self.game(root, self.SDK)
            (folder / 'nexus_pgs_native.dll').write_bytes(b'MZ someone else')
            self.assertEqual(self.stage(root), 0)
            self.assertEqual((folder / 'nexus_pgs_native.dll').read_bytes(), b'MZ someone else')
            self.assertEqual((folder / 'PlayFabGameSave.dll').read_bytes(), self.SDK)
        with tempfile.TemporaryDirectory() as root:
            folder = self.game(root, self.SDK)
            target = Path(root) / 'elsewhere.dll'
            target.write_bytes(self.SDK)
            (folder / 'nexus_pgs_native.dll').symlink_to(target)
            self.assertEqual(self.stage(root), 0)
            self.assertTrue((folder / 'nexus_pgs_native.dll').is_symlink())
            self.assertEqual(target.read_bytes(), self.SDK)
            self.assertEqual((folder / 'PlayFabGameSave.dll').read_bytes(), self.SDK)

    def test_unknown_sdk_symlinks_and_a_runtime_without_the_proxy_are_left_alone(self):
        with tempfile.TemporaryDirectory() as root:
            unknown = pe_with_exports(['PFGameSaveFilesInitialize'])
            folder = self.game(root, unknown)
            self.assertEqual(self.stage(root), 0)
            self.assertEqual((folder / 'PlayFabGameSave.dll').read_bytes(), unknown)
            self.assertFalse((folder / 'nexus_pgs_native.dll').exists())
        with tempfile.TemporaryDirectory() as root:
            folder = self.game(root, self.SDK)
            self.assertEqual(self.stage(root, proxy=None), 0)
            self.assertEqual((folder / 'PlayFabGameSave.dll').read_bytes(), self.SDK)
        with tempfile.TemporaryDirectory() as root:
            target = Path(root) / 'elsewhere.dll'
            target.write_bytes(self.SDK)
            folder = Path(root) / 'game'
            folder.mkdir()
            (folder / 'PlayFabGameSave.dll').symlink_to(target)
            self.assertEqual(self.stage(root), 0)
            self.assertEqual(target.read_bytes(), self.SDK)

    def test_launch_stages_the_proxy_without_dll_overrides_and_survives_a_failure(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            runtime = root / 'windows'
            runtime.mkdir()
            for name in xbox['DLLS']:
                (runtime / name).touch()
            store = {'find_store_game': lambda argv: (root, {'store': 'xbox', 'id': 'GAME'}),
                     'ask': unittest.mock.Mock(return_value={'ok': True, 'socket': '/service', 'lease': 'lease-1',
                                                            'exe': '/Game.exe'})}
            wine = unittest.mock.Mock(return_value=0)
            staged = []
            def failing(folder):
                raise RuntimeError('unreadable game folder')
            for stage in (lambda folder: staged.append(folder) or 1, failing):
                wine.reset_mock()
                with patch('runpy.run_path', return_value=store), patch('sys.stderr', new_callable=io.StringIO), \
                     patch.dict(xbox['launch'].__globals__, RUNTIME=root, bridge=lambda path: (1, 'key', lambda: None),
                                wine=wine, stage_playfab_proxy=stage):
                    self.assertEqual(xbox['launch'](['/proton', 'waitforexitandrun', '/Game.exe'],
                                                    {'DD_LAUNCH_DIR': '/launch', 'WINEDLLOVERRIDES': 'd3d11=n'}), 0)
                self.assertEqual(wine.call_args.args[1]['WINEDLLOVERRIDES'], 'd3d11=n')
            self.assertEqual(staged, [root])


class ComClassRegistrationTest(unittest.TestCase):
    XHR_CLSID = '{88d96a0a-f192-11d4-a65f-0040963251e5}'
    OTHER_CLSID = '{11111111-2222-3333-4444-555555555555}'

    def test_com_class_paths_parses_inprocserver32_entries(self):
        registry = (
            '[Software\\\\Classes\\\\CLSID\\\\{88D96A0A-F192-11D4-A65F-0040963251E5}\\\\InprocServer32] 1700000000\n'
            '@="C:\\\\windows\\\\system32\\\\uwp-host.dll"\n'
            '"ThreadingModel"="Both"\n'
            '[Software\\\\Classes\\\\CLSID\\\\{11111111-2222-3333-4444-555555555555}\\\\ProgID] 1700000000\n'
            '@="Ignored.ProgID"\n'
        )
        self.assertEqual(
            xbox['com_class_paths'](registry),
            {self.XHR_CLSID: 'C:\\windows\\system32\\uwp-host.dll'},
        )

    def test_register_com_classes_imports_only_missing_or_mismatched_clsids(self):
        with tempfile.TemporaryDirectory() as root:
            prefix = Path(root)
            (prefix / 'system.reg').write_text(
                '[Software\\\\Classes\\\\CLSID\\\\{88d96a0a-f192-11d4-a65f-0040963251e5}\\\\InprocServer32]\n'
                '@="C:\\\\windows\\\\system32\\\\msxml6.dll"\n',
                encoding='utf-8',
            )
            imported = []
            def fake_run(cmd, **kwargs):
                reg_path = Path(cmd[-1][2:].replace('\\', '/'))
                imported.append(reg_path.read_bytes().decode('utf-16'))
                return unittest.mock.Mock(returncode=0)

            with patch('subprocess.run', side_effect=fake_run) as run:
                xbox['register_com_classes']('/proton', prefix, {}, 'uwp-host.dll', [self.XHR_CLSID])
            self.assertEqual(run.call_count, 1)
            self.assertIn(self.XHR_CLSID, imported[0])
            self.assertIn('C:\\\\windows\\\\system32\\\\uwp-host.dll', imported[0])
            self.assertIn('"ThreadingModel"="Both"', imported[0])

    def test_register_com_classes_skips_when_already_pointing_to_target_dll(self):
        with tempfile.TemporaryDirectory() as root:
            prefix = Path(root)
            (prefix / 'system.reg').write_text(
                '[Software\\\\Classes\\\\CLSID\\\\{88d96a0a-f192-11d4-a65f-0040963251e5}\\\\InprocServer32]\n'
                '@="C:\\\\windows\\\\system32\\\\uwp-host.dll"\n',
                encoding='utf-8',
            )
            with patch('subprocess.run') as run:
                previous = xbox['register_com_classes']('/proton', prefix, {}, 'uwp-host.dll', [self.XHR_CLSID])
            self.assertEqual(previous, {})
            run.assert_not_called()

    def test_restores_previous_com_entry_and_removes_new_ones(self):
        with tempfile.TemporaryDirectory() as folder:
            prefix = Path(folder)
            (prefix / 'system.reg').write_text(
                '[Software\\\\Classes\\\\CLSID\\\\' + self.XHR_CLSID + '\\\\InprocServer32]\n'
                '@="C:\\\\windows\\\\system32\\\\msxml6.dll"\n'
                '"ThreadingModel"="Apartment"\n',
                encoding='utf-8',
            )
            imported = []

            def fake_run(cmd, **kwargs):
                reg_file = Path(cmd[-1][2:].replace('\\', '/'))
                imported.append(reg_file.read_bytes().decode('utf-16'))
                return type('Result', (), {'returncode': 0})()

            other_clsid = '{11111111-2222-3333-4444-555555555555}'
            with patch('subprocess.run', side_effect=fake_run):
                previous = xbox['register_com_classes'](
                    '/proton', prefix, {}, 'uwp-host.dll', [self.XHR_CLSID, other_clsid]
                )
                self.assertEqual(
                    previous,
                    {
                        self.XHR_CLSID: ('C:\\windows\\system32\\msxml6.dll', 'Apartment'),
                        other_clsid: None,
                    },
                )
                xbox['restore_com_classes']('/proton', prefix, {}, previous)
            self.assertEqual(len(imported), 2)
            self.assertIn('msxml6.dll', imported[1])
            self.assertIn('"ThreadingModel"="Apartment"', imported[1])
            self.assertIn('[-HKEY_LOCAL_MACHINE\\Software\\Classes\\CLSID\\' + other_clsid + '\\InprocServer32]', imported[1])

    def test_restore_failure_is_nonfatal(self):
        with tempfile.TemporaryDirectory() as folder:
            prefix = Path(folder)
            with patch('subprocess.run', return_value=type('Result', (), {'returncode': 1})()):
                xbox['restore_com_classes']('/proton', prefix, {}, {self.XHR_CLSID: None})

    def test_failed_com_import_keeps_snapshot_for_restore(self):
        with tempfile.TemporaryDirectory() as folder:
            prefix = Path(folder)
            (prefix / 'system.reg').write_text(
                '[Software\\\\Classes\\\\CLSID\\\\' + self.XHR_CLSID + '\\\\InprocServer32]\n'
                '@="C:\\\\windows\\\\system32\\\\msxml6.dll"\n',
                encoding='utf-8',
            )
            with patch('subprocess.run', return_value=type('Result', (), {'returncode': 1})()):
                previous = xbox['register_com_classes'](
                    '/proton', prefix, {}, 'uwp-host.dll', [self.XHR_CLSID]
                )
            self.assertEqual(previous, {self.XHR_CLSID: ('C:\\windows\\system32\\msxml6.dll', None)})

    def test_uwp_view_app_matches_launched_executable(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'AppxManifest.xml').write_text(
                '<Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10">'
                '<Applications>'
                '<Application Id="Desktop" Executable="Bin\\Desktop.exe" EntryPoint="Windows.FullTrustApplication"/>'
                '<Application Id="Game" Executable="Game.exe" EntryPoint="Game.App"/>'
                '</Applications>'
                '</Package>',
                encoding='utf-8',
            )
            self.assertFalse(xbox['uwp_view_app'](root, str(root / 'Bin' / 'Desktop.exe')))
            self.assertTrue(xbox['uwp_view_app'](root, str(root / 'Game.exe')))

