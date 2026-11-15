"""droiddeck-store-launch: a launch from anywhere - the Steam client's own Play button included -
asks the app for an Epic game's sign-in code before Proton starts. These pin how it recognises a
store game, how it asks, and that both Proton launchers call it."""
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

BIN = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin'


def load(name):
    loader = importlib.machinery.SourceFileLoader(name.replace('-', '_'), str(BIN / name))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


store = load('droiddeck-store-launch')


def load_path(path, name):
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module
compat = load('steam-compatibility')


def game(root, store_id='epic', ident='8b6a0e14'):
    folder = Path(root) / 'Games/Stores/Epic/Metalstorm'
    folder.mkdir(parents=True)
    (folder / '.droiddeck-store.json').write_text(json.dumps({'store': store_id, 'id': ident, 'title': 'Metalstorm', 'exe': 'Metalstorm.exe'}))
    (folder / '.droiddeck-launch.bat').write_text('@echo off\r\n')
    return folder


class Detection(unittest.TestCase):
    def test_the_launcher_among_steams_arguments_names_the_game(self):
        with tempfile.TemporaryDirectory() as root:
            folder = game(root)
            found = store.find_store_game(['/usr/bin/steam-runtime', '"%s/.droiddeck-launch.bat"' % folder, '-flag'])
            self.assertIsNotNone(found)
            self.assertEqual(folder, found[0])
            self.assertEqual('epic', found[1]['store'])
            self.assertEqual('8b6a0e14', found[1]['id'])

    def test_anything_else_is_left_alone(self):
        with tempfile.TemporaryDirectory() as root:
            folder = game(root)
            self.assertEqual(folder, store.find_store_game([str(folder / 'Metalstorm.exe')])[0])
            self.assertIsNone(store.find_store_game([str(Path(root) / 'elsewhere/readme.txt')]))
            (folder / '.droiddeck-store.json').unlink()
            self.assertIsNone(store.find_store_game([str(folder / '.droiddeck-launch.bat')]))

    def test_only_epic_asks_for_a_code(self):
        with tempfile.TemporaryDirectory() as root:
            folder = game(root, store_id='gog')
            sc = json.loads((folder / '.droiddeck-store.json').read_text()); sc['cloud'] = False
            (folder / '.droiddeck-store.json').write_text(json.dumps(sc))
            os.environ['BL_LAUNCH_DIR'] = str(Path(root) / 'session')
            try:
                with patch.object(store, 'ask', return_value={'ready': False, 'reason': 'off'}) as ask:
                    self.assertEqual(0, store.main([str(folder / '.droiddeck-launch.bat')]))
                    self.assertEqual('gog-achievements', ask.call_args.args[1]['op'])
            finally:
                del os.environ['BL_LAUNCH_DIR']



class Asking(unittest.TestCase):
    def test_a_request_is_answered_through_the_resp_folder(self):
        with tempfile.TemporaryDirectory() as root:
            channel = Path(root) / 'stores'

            def app():
                req = channel / 'req'
                for _ in range(100):
                    names = [p for p in req.glob('*.json')] if req.exists() else []
                    if names:
                        request = json.loads(names[0].read_text())
                        self.assertEqual({'op': 'epic-code', 'store': 'epic', 'id': 'x'}, request)
                        names[0].unlink()
                        tmp = channel / 'resp' / (names[0].name + '.tmp')
                        tmp.write_text(json.dumps({'ok': True, 'code': True, 'reason': 'ok'}))
                        os.rename(tmp, channel / 'resp' / names[0].name)
                        return
                    time.sleep(0.02)

            t = threading.Thread(target=app)
            t.start()
            answer = store.ask(channel, {'op': 'epic-code', 'store': 'epic', 'id': 'x'}, timeout=3)
            t.join()
            self.assertEqual({'ok': True, 'code': True, 'reason': 'ok'}, answer)

    def test_no_answer_withdraws_the_request(self):
        with tempfile.TemporaryDirectory() as root:
            channel = Path(root) / 'stores'
            self.assertIsNone(store.ask(channel, {'op': 'epic-code'}, timeout=0.2))
            self.assertEqual([], list((channel / 'req').iterdir()))

    def test_a_stale_code_is_dropped_before_asking(self):
        with tempfile.TemporaryDirectory() as root:
            folder = game(root)
            code = folder / '.droiddeck-epic-code'
            code.write_text('old')
            self.assertFalse(store.drop_stale(folder, now=code.stat().st_mtime + 60))
            self.assertTrue(store.drop_stale(folder, now=code.stat().st_mtime + store.STALE_SECONDS + 1))
            self.assertFalse(code.exists())


class Prefix(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.compat = root / 'compatdata/3785150007'
        (self.compat / 'pfx').mkdir(parents=True)
        self.reg = self.compat / 'pfx/user.reg'
        self.reg.write_text('WINE REGISTRY Version 2\n\n[Software\\\\Wine] 1\n"Version"="win10"\n')

    def tearDown(self):
        self.tmp.cleanup()

    def test_wines_browser_is_the_hand_off_by_its_path(self):
        self.assertEqual('browser-set', store.provision_prefix(str(self.compat)))
        text = self.reg.read_text()
        self.assertIn('[Software\\\\Wine\\\\WineBrowser] ', text)
        self.assertIn('"Browsers"="/usr/local/bin/droiddeck-open-url,xdg-open"', text)
        store.provision_prefix(str(self.compat))
        self.assertEqual(text, self.reg.read_text())

    def test_an_earlier_builds_overlay_pointer_is_removed(self):
        self.reg.write_text(self.reg.read_text() + '\n[Software\\\\Epic Games\\\\EOS] 2\n"OverlayPath"="Z:\\\\x"\n"Other"="kept"\n')
        self.assertEqual('browser-set old-overlay-removed', store.provision_prefix(str(self.compat)))
        text = self.reg.read_text()
        self.assertNotIn('OverlayPath', text)
        self.assertIn('"Other"="kept"', text)

    def test_no_prefix_yet(self):
        self.assertEqual('prefix-not-created', store.provision_prefix(self.tmp.name + '/compatdata/1'))
        self.assertEqual('no-prefix', store.provision_prefix(''))


class Choices(unittest.TestCase):
    def test_the_sidecars_epic_choices_are_honoured(self):
        with tempfile.TemporaryDirectory() as root:
            folder = game(root)
            sidecar = json.loads((folder / '.droiddeck-store.json').read_text())
            self.assertEqual({'eos': True, 'offline': False}, store.options(sidecar))
            self.assertEqual({'eos': True, 'offline': False}, store.options({'epic': {'v': 2, 'overlay': True}}))
            sidecar['epic'] = {'eos': True, 'offline': True}
            sidecar['cloud'] = False
            (folder / '.droiddeck-store.json').write_text(json.dumps(sidecar))
            (folder / '.droiddeck-epic-code').write_text('left over')
            compat = Path(root) / 'compatdata/1'
            (compat / 'pfx').mkdir(parents=True)
            reg = compat / 'pfx/user.reg'
            reg.write_text('WINE REGISTRY Version 2\n\n[Software\\\\Epic Games\\\\EOS] 1\n"OverlayPath"="Z:\\\\x"\n"Other"="kept"\n')
            os.environ['BL_LAUNCH_DIR'] = str(Path(root) / 'session')
            os.environ['STEAM_COMPAT_DATA_PATH'] = str(compat)
            try:
                self.assertEqual(0, store.main([str(folder / '.droiddeck-launch.bat')]))
            finally:
                del os.environ['BL_LAUNCH_DIR']
                del os.environ['STEAM_COMPAT_DATA_PATH']
            self.assertFalse((Path(root) / 'session/stores/req').exists())
            self.assertFalse((folder / '.droiddeck-epic-code').exists())
            text = reg.read_text()
            self.assertNotIn('OverlayPath', text)
            self.assertIn('"Other"="kept"', text)


class Browser(unittest.TestCase):
    def test_a_web_address_is_handed_to_the_app(self):
        xdg = load_path(BIN / 'droiddeck-open-url', 'open_url')
        with tempfile.TemporaryDirectory() as root:
            os.environ['BL_LAUNCH_DIR'] = root
            try:
                self.assertEqual(0, xdg.main(['https://www.epicgames.com/activate']))
            finally:
                del os.environ['BL_LAUNCH_DIR']
            reqs = list((Path(root) / 'stores/req').glob('*.json'))
            self.assertEqual(1, len(reqs))
            self.assertEqual({'op': 'open-url', 'url': 'https://www.epicgames.com/activate'}, json.loads(reqs[0].read_text()))
            self.assertEqual('https://www.epicgames.com', xdg.where('https://www.epicgames.com/activate?code=SECRET'))


class ExeTarget(unittest.TestCase):
    def test_a_game_started_by_its_exe_is_found_from_a_subfolder(self):
        with tempfile.TemporaryDirectory() as root:
            folder = game(root, store_id='gog', ident='1207664643')
            (folder / 'bin/x64').mkdir(parents=True)
            exe = folder / 'bin/x64/ELDERBORN.exe'
            exe.write_text('MZ')
            found = store.find_store_game(['"%s"' % exe])
            self.assertEqual(folder, found[0])
            self.assertEqual('gog', found[1]['store'])
            self.assertIsNone(store.find_store_game(['/root/Games/Other/x.exe']))


class CloudHooks(unittest.TestCase):
    def test_a_store_game_with_cloud_saves_asks_before_and_after(self):
        with tempfile.TemporaryDirectory() as root:
            folder = game(root, store_id='gog', ident='1207664643')
            bat = str(folder / '.droiddeck-launch.bat')
            os.environ['BL_LAUNCH_DIR'] = str(Path(root) / 'session')
            try:
                original = store.ask
                asked = []
                store.ask = lambda channel, request, timeout=5.0: asked.append((request, timeout)) or {'ok': True, 'files': 2, 'reason': 'ok'}
                self.assertEqual(store.STORE_GAME, store.main([bat]))
                self.assertEqual(0, store.main(['--exited', bat]))
            finally:
                store.ask = original
                del os.environ['BL_LAUNCH_DIR']
            self.assertEqual('gog-achievements', asked[0][0]['op'])
            self.assertEqual({'op': 'cloud-down', 'store': 'gog', 'id': '1207664643'}, asked[1][0])
            self.assertEqual(store.CLOUD_TIMEOUT, asked[1][1])
            self.assertEqual('cloud-up', asked[2][0]['op'])

    def test_a_first_launch_creates_the_prefix_before_the_cloud_download(self):
        with tempfile.TemporaryDirectory() as root:
            folder = game(root, store_id='epic', ident='fa4240e5')
            sidecar = json.loads((folder / store.SIDECAR).read_text())
            sidecar['epic'] = {'eos': False, 'offline': True}
            (folder / store.SIDECAR).write_text(json.dumps(sidecar))
            proton = Path(root) / 'proton'
            proton.write_text('selected Proton')
            compat = Path(root) / 'compatdata/1'
            order = []

            def run(argv, **kwargs):
                order.append(argv)
                (compat / 'pfx').mkdir(parents=True)
                (compat / 'pfx/user.reg').write_text('WINE REGISTRY Version 2\n')
                return __import__('subprocess').CompletedProcess(argv, 0)

            env = {'BL_LAUNCH_DIR': root, 'STEAM_COMPAT_DATA_PATH': str(compat), 'BL_STORE_PROTON': str(proton)}
            with patch.dict(os.environ, env), patch.object(store.subprocess, 'run', side_effect=run), \
                    patch.object(store, 'ask', side_effect=lambda channel, request, timeout=5.0: order.append(request) or {'result': 'ok'}):
                self.assertEqual(store.STORE_GAME, store.main([str(folder / store.LAUNCHER)]))
                self.assertEqual([str(proton), 'run', 'cmd.exe', '/d', '/c', 'exit'], order[0])
                self.assertEqual({'op': 'cloud-down', 'store': 'epic', 'id': 'fa4240e5'}, order[-1])
                self.assertEqual('present', store.create_prefix(str(compat), str(proton)))
                self.assertEqual(2, len(order))

    def test_a_prefix_that_is_not_made_leaves_the_deferred_download(self):
        with tempfile.TemporaryDirectory() as root:
            proton = Path(root) / 'proton'
            proton.write_text('selected Proton')
            with patch.object(store.subprocess, 'run', return_value=__import__('subprocess').CompletedProcess([], 1)):
                self.assertEqual('failed', store.create_prefix(root + '/compatdata/1', str(proton)))
            with patch.object(store.subprocess, 'run', side_effect=__import__('subprocess').TimeoutExpired('proton', 1)):
                self.assertEqual('timeout', store.create_prefix(root + '/compatdata/1', str(proton)))
            self.assertEqual('no-proton', store.create_prefix(root + '/compatdata/1', root + '/missing'))
            self.assertEqual('no-proton', store.create_prefix('', str(proton)))

    def test_cloud_off_or_amazon_runs_proton_as_before(self):
        with tempfile.TemporaryDirectory() as root:
            folder = game(root, store_id='gog', ident='1')
            sidecar = json.loads((folder / '.droiddeck-store.json').read_text())
            sidecar['cloud'] = False
            (folder / '.droiddeck-store.json').write_text(json.dumps(sidecar))
            self.assertEqual(0, store.main([str(folder / '.droiddeck-launch.bat')]))
            self.assertFalse(store.cloud_on({'store': 'amazon', 'id': 'x'}))
            self.assertTrue(store.cloud_on({'store': 'epic', 'id': 'x'}))

    def test_both_launchers_wait_for_proton_only_for_a_store_game(self):
        for sh in (compat.LAUNCHER_SH, compat.EXTRA_WRAPPER_SH):
            self.assertIn('bl_run ${BL_TASKSET:-} /usr/local/bin/droiddeck-game-env', sh)
            self.assertNotIn('exec ${BL_TASKSET:-} /usr/local/bin/droiddeck-game-env', sh)
        self.assertIn('[ $rc -eq 10 ] && BL_STORE_EXIT=1', compat.BL_STORE_SETUP)
        self.assertIn('droiddeck-store-launch --exited --code "$rc" "${BL_STORE_ARGS[@]}"', compat.BL_STORE_SETUP)
        self.assertIn('exec "$@"', compat.BL_STORE_SETUP)


BNET_EXE = '/root/.local/share/Steam/steamapps/compatdata/2596113170/pfx/drive_c/Program Files (x86)/Battle.net/Battle.net.exe'


class BattleNet(unittest.TestCase):
    def test_a_games_shortcut_is_handed_to_droiddeck_battlenet(self):
        self.assertEqual([store.BATTLENET, 'launch', 'Fen'], store.battlenet(['waitforexitandrun', BNET_EXE, '--exec=launch Fen'], env={}))
        self.assertEqual([store.BATTLENET, 'launch', 'OSI'], store.battlenet(['"%s"' % BNET_EXE, '--exec="launch OSI"'], env={}))
        self.assertEqual([store.BATTLENET, 'open'], store.battlenet([BNET_EXE], env={}))

    def test_anything_else_and_its_own_starts_are_left_alone(self):
        other = BNET_EXE.replace('2596113170', '123')
        self.assertIsNone(store.battlenet([other, '--exec=launch Fen'], env={}))
        self.assertIsNone(store.battlenet(['/x/Diablo IV/Diablo IV.exe'], env={}))
        self.assertIsNone(store.battlenet([BNET_EXE, '--exec=launch Fen'], env={'BL_BATTLENET': '1'}))
        self.assertEqual([store.BATTLENET, 'open'], store.battlenet([BNET_EXE, '--exec=launch Fen;rm'], env={}))

    def test_the_launch_runs_there_and_the_wrapper_ends_with_it(self):
        with patch.dict(os.environ, {}, clear=False), patch.object(store.subprocess, 'call', return_value=0) as call:
            os.environ.pop('BL_BATTLENET', None)
            self.assertEqual(store.RAN, store.main([BNET_EXE, '--exec=launch Pro']))
        call.assert_called_once_with([store.BATTLENET, 'launch', 'Pro'])
        with patch.dict(os.environ, {}, clear=False), patch.object(store.subprocess, 'call', return_value=1):
            os.environ.pop('BL_BATTLENET', None)
            self.assertEqual(store.RAN_FAILED, store.main([BNET_EXE, '--exec=launch Pro']))
        self.assertIn('[ $rc -eq 11 ] && exit 0', compat.BL_STORE_SETUP)
        self.assertIn('[ $rc -eq 12 ] && exit 1', compat.BL_STORE_SETUP)
        self.assertLess(compat.BL_STORE_SETUP.index('droiddeck-store-launch "$@"'), compat.BL_STORE_SETUP.index('[ $rc -eq 11 ] && exit 0'))


class Launchers(unittest.TestCase):
    def test_both_proton_launchers_ask_before_proton_starts(self):
        sh = compat.LAUNCHER_SH
        self.assertIn('bl_store_launch() {', sh)
        self.assertLess(sh.index('bl_store_launch "$@"'), sh.index('droiddeck-game-env "$depot/proton"'))
        self.assertIn('bl_store_launch "$@"', compat.EXTRA_WRAPPER_SH)
        self.assertIn('bl_store_launch() {', compat.BL_STORE_SETUP)
        self.assertIn('waitforexitandrun', compat.BL_STORE_SETUP)


if __name__ == '__main__':
    unittest.main()


class GogAchievements(unittest.TestCase):
    def test_cloud_off_still_prepares_the_gog_companion(self):
        with tempfile.TemporaryDirectory() as root:
            folder = game(root, store_id='gog')
            sidecar = json.loads((folder / store.SIDECAR).read_text())
            sidecar['cloud'] = False
            (folder / store.SIDECAR).write_text(json.dumps(sidecar))
            with patch.dict(os.environ, {'BL_LAUNCH_DIR': root, 'STEAM_COMPAT_DATA_PATH': root + '/compat', 'BL_STORE_PROTON': '/chosen/proton'}), patch.object(store, 'ask', return_value={'ready': True}) as ask, patch.object(store, 'provision_galaxy', return_value='ready') as provision:
                self.assertEqual(0, store.main([str(folder / store.LAUNCHER)]))
                ask.assert_called_once()
                provision.assert_called_once_with(root + '/compat', '/chosen/proton')

    def test_unavailable_companion_does_not_provision_or_block_the_game(self):
        with patch.object(store, 'ask', return_value=None), patch.object(store, 'provision_galaxy') as provision:
            self.assertFalse(store.prepare_gog({'id': '1', 'achievements': False}, '/pfx', '/session'))
            provision.assert_not_called()

    def test_a_fresh_prefix_is_bootstrapped_before_marking_success(self):
        with tempfile.TemporaryDirectory() as root:
            base = Path(root)
            tools = base / 'tools'; tools.mkdir()
            for name in ('GalaxyCommunication.exe', 'update-permissions.exe', 'version'):
                (tools / name).write_text(name)
            proton = base / 'proton'; proton.write_text('selected Proton')
            data = base / 'compat'
            seen = []
            def run(argv, **kwargs):
                self.assertEqual([str(proton), 'run', 'cmd.exe', '/d', '/c'], argv[:5])
                bat = Path(argv[5][2:].replace('\\', '/'))
                text = bat.read_text()
                seen.append(text)
                self.assertIn('goto existing', text)
                self.assertIn('update-permissions.exe', text)
                result = text.split('echo ready>"')[1].split('"')[0]
                Path(result[2:].replace('\\', '/')).write_text('ready')
                (data / 'pfx').mkdir(parents=True)
                (data / 'pfx/system.reg').write_text('Wine registry')
                return __import__('subprocess').CompletedProcess(argv, 0)
            with patch.object(store, 'COMET_DIR', tools), patch.object(store.subprocess, 'run', side_effect=run):
                self.assertEqual('ready', store.provision_galaxy(str(data), str(proton)))
                self.assertTrue((data / 'pfx/.droiddeck-comet-service').is_file())
                self.assertEqual('ready', store.provision_galaxy(str(data), str(proton)))
                self.assertEqual(1, len(seen))
                __import__('shutil').rmtree(data / 'pfx')
                self.assertEqual('ready', store.provision_galaxy(str(data), str(proton)))
                self.assertEqual(2, len(seen))

    def test_failed_service_install_is_not_marked_done(self):
        with tempfile.TemporaryDirectory() as root:
            base = Path(root)
            for name in ('GalaxyCommunication.exe', 'update-permissions.exe', 'version', 'proton'):
                (base / name).write_text(name)
            with patch.object(store, 'COMET_DIR', base), patch.object(store.subprocess, 'run', return_value=__import__('subprocess').CompletedProcess([], 1)):
                self.assertEqual('service-unavailable', store.provision_galaxy(str(base / 'compat'), str(base / 'proton')))
                self.assertFalse((base / 'compat/pfx/.droiddeck-comet-service').exists())


def gog_script(folder, product, actions):
    (folder / ('goggame-%s.script' % product)).write_text(json.dumps({'actions': actions, 'baseProductId': product, 'version': 1}))


def set_registry(**arguments):
    return {'install': {'action': 'setRegistry', 'arguments': arguments}, 'name': 'setRegistry'}


class GogRegistry(unittest.TestCase):
    def test_patch_registration_uses_authentic_build_and_the_prefix_registry_executable(self):
        with tempfile.TemporaryDirectory() as root:
            folder = game(root, store_id='gog', ident='7')
            (folder / 'goggame-7.id').write_text(json.dumps({'buildId': 'opaque-0001'}))
            proton = Path(root) / 'proton'; proton.write_text('selected Proton')
            data = Path(root) / 'compat'
            executable = data / 'pfx/drive_c/windows/system32/reg.exe'
            executable.parent.mkdir(parents=True); executable.write_bytes(b'MZreg')
            (data / 'pfx/system.reg').write_text('registry')
            imported = []
            def run(argv, **kwargs):
                self.assertEqual([str(proton), 'run', str(executable), 'import'], argv[:4])
                text = Path(argv[4][2:].replace('\\', '/')).read_bytes()[2:].decode('utf-16-le')
                imported.append(text)
                return __import__('subprocess').CompletedProcess(argv, 0)
            record = {'id': '7', 'build': 'opaque-0001', 'version': 'RC-final'}
            with patch.object(store.subprocess, 'run', side_effect=run):
                self.assertTrue(store.apply_gog_registry(folder, str(data), str(proton), installation=record).startswith('written='))
            self.assertIn('[HKEY_LOCAL_MACHINE\\Software\\GOG.com\\Games\\7]', imported[0])
            self.assertIn('"BUILDID"="opaque-0001"', imported[0])
            self.assertIn('"VER"="RC-final"', imported[0])
            with self.assertRaises(ValueError):
                store.apply_gog_registry(folder, str(data), str(proton), installation=dict(record, build='invented'))

    def test_the_scripts_registry_values_are_read_with_the_games_paths(self):
        with tempfile.TemporaryDirectory() as root:
            folder = game(root, store_id='gog', ident='1454587428')
            gog_script(folder, '1454587428', [
                set_registry(root='HKEY_LOCAL_MACHINE', subkey='Software\\Bethesda Softworks\\FalloutNV',
                             valueName='Installed Path', valueData='{app}\\', valueType='string'),
                set_registry(root='HKEY_LOCAL_MACHINE', subkey='Software\\GOG.com\\Games\\{productId}',
                             valueName='buildId', valueData='42', valueType='dword'),
                set_registry(root='HKEY_CURRENT_USER', subkey='Software\\Game', valueName='', valueData='AAH/', valueType='binary'),
                set_registry(root='HKEY_LOCAL_MACHINE', subkey='Software\\Empty'),
                set_registry(root='HKEY_LOCAL_MACHINE', subkey='Software\\Game', valueName='x', valueData='1', valueType='qword'),
                set_registry(root='HKEY_LOCAL_MACHINE', subkey='Software\\Game', valueName='x', valueData='{nowhere}', valueType='string'),
                set_registry(root='HKEY_USERS', subkey='S-1-5', valueName='x', valueData='1', valueType='string'),
                {'install': {'action': 'Execute', 'arguments': {'executable': 'setup.exe'}}},
                'nonsense',
            ])
            entries, skipped = store.gog_registry(folder)
            app = store.windows_path(str(folder)).replace('\\', '\\\\')
            self.assertEqual([
                '[HKEY_LOCAL_MACHINE\\Software\\Bethesda Softworks\\FalloutNV]\r\n"Installed Path"="%s\\\\"' % app,
                '[HKEY_LOCAL_MACHINE\\Software\\GOG.com\\Games\\1454587428]\r\n"buildId"=dword:0000002a',
                '[HKEY_CURRENT_USER\\Software\\Game]\r\n@=hex:00,01,ff',
                '[HKEY_LOCAL_MACHINE\\Software\\Empty]',
            ], entries)
            self.assertEqual(3, skipped)

    def test_a_game_without_a_script_starts_without_proton(self):
        with tempfile.TemporaryDirectory() as root:
            folder = game(root, store_id='gog')
            with patch.object(store.subprocess, 'run') as run:
                self.assertEqual('none', store.apply_gog_registry(folder, root + '/compat', root + '/proton'))
                run.assert_not_called()

    def test_values_are_imported_into_the_32_bit_view_once_until_the_script_changes(self):
        with tempfile.TemporaryDirectory() as root:
            folder = game(root, store_id='gog', ident='1454587428')
            proton = Path(root) / 'proton'
            proton.write_text('selected Proton')
            data = Path(root) / 'compat'
            action = set_registry(root='HKLM', subkey='Software\\Bethesda Softworks\\FalloutNV',
                                  valueName='Installed Path', valueData='{app}', valueType='string')
            gog_script(folder, '1454587428', [action])
            imported = []
            def run(argv, **kwargs):
                self.assertEqual([str(proton), 'run', 'reg.exe', 'import'], argv[:4])
                self.assertEqual('/reg:32', argv[5])
                raw = Path(argv[4][2:].replace('\\', '/')).read_bytes()
                self.assertTrue(raw.startswith(b'\xff\xfe'))
                imported.append(raw[2:].decode('utf-16-le'))
                (data / 'pfx').mkdir(parents=True, exist_ok=True)
                (data / 'pfx/system.reg').write_text('Wine registry')
                return __import__('subprocess').CompletedProcess(argv, 0)
            with patch.object(store.subprocess, 'run', side_effect=run):
                self.assertEqual('written=1', store.apply_gog_registry(folder, str(data), str(proton)))
                self.assertTrue(imported[0].startswith('Windows Registry Editor Version 5.00\r\n'))
                self.assertIn('[HKEY_LOCAL_MACHINE\\Software\\Bethesda Softworks\\FalloutNV]', imported[0])
                self.assertEqual('ready', store.apply_gog_registry(folder, str(data), str(proton)))
                self.assertEqual(1, len(imported))
                action['install']['arguments']['valueData'] = '{app}\\'
                gog_script(folder, '1454587428', [action])
                self.assertEqual('written=1', store.apply_gog_registry(folder, str(data), str(proton)))
                self.assertEqual(2, len(imported))

    def test_a_failed_import_is_tried_again_next_launch(self):
        with tempfile.TemporaryDirectory() as root:
            folder = game(root, store_id='gog', ident='7')
            proton = Path(root) / 'proton'
            proton.write_text('selected Proton')
            gog_script(folder, '7', [set_registry(root='HKLM', subkey='Software\\Game', valueName='a', valueData='b', valueType='string')])
            with patch.object(store.subprocess, 'run', return_value=__import__('subprocess').CompletedProcess([], 1)):
                self.assertEqual('import-failed', store.apply_gog_registry(folder, root + '/compat', str(proton)))
            self.assertFalse((Path(root) / 'compat/pfx' / store.GOG_REGISTRY_MARK).exists())
            self.assertEqual('no-proton', store.apply_gog_registry(folder, root + '/compat', root + '/missing'))

    def test_every_gog_launch_brings_the_registry_in(self):
        with tempfile.TemporaryDirectory() as root:
            folder = game(root, store_id='gog')
            with patch.dict(os.environ, {'BL_LAUNCH_DIR': root, 'STEAM_COMPAT_DATA_PATH': root + '/compat/', 'BL_STORE_PROTON': '/chosen/proton'}), \
                    patch.object(store, 'prepare_gog', return_value=False), patch.object(store, 'ask', return_value=None), \
                    patch.object(store, 'apply_gog_registry', return_value='ready') as apply:
                store.main([str(folder / store.LAUNCHER)])
                apply.assert_called_once_with(folder, root + '/compat', '/chosen/proton')
