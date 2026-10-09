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

BIN = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin'


def load(name):
    loader = importlib.machinery.SourceFileLoader(name.replace('-', '_'), str(BIN / name))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


store = load('droiddeck-store-launch')
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
            self.assertIsNone(store.find_store_game([str(folder / 'Metalstorm.exe')]))
            (folder / '.droiddeck-store.json').unlink()
            self.assertIsNone(store.find_store_game([str(folder / '.droiddeck-launch.bat')]))

    def test_only_epic_asks(self):
        with tempfile.TemporaryDirectory() as root:
            folder = game(root, store_id='gog')
            os.environ['BL_LAUNCH_DIR'] = str(Path(root) / 'session')
            try:
                self.assertEqual(0, store.main([str(folder / '.droiddeck-launch.bat')]))
            finally:
                del os.environ['BL_LAUNCH_DIR']
            self.assertFalse((Path(root) / 'session/stores').exists())


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
