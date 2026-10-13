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
        self.assertIn('[ $? -eq 10 ] && BL_STORE_EXIT=1', compat.BL_STORE_SETUP)
        self.assertIn('droiddeck-store-launch --exited "${BL_STORE_ARGS[@]}"', compat.BL_STORE_SETUP)
        self.assertIn('exec "$@"', compat.BL_STORE_SETUP)


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
