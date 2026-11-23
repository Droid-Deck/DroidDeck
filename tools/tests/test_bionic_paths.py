import importlib.machinery
import importlib.util
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
PROTON = TOOLS / 'bionic-runtime/tool/proton'
LAUNCH = TOOLS / 'bionic-runtime/tool/launch.sh'
BIN = TOOLS / 'linuxfs/overlay/usr/local/bin'
ANDROID = '/data/user/0/com.droiddeck.launcher/files/linuxfs'
BINDS = '/storage/emulated/0/Games:/home/games|/storage/1234-5678/Steam:/mnt/sd'


def load(name):
    loader = importlib.machinery.SourceFileLoader(name.replace('-', '_'), str(BIN / name))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


store = load('droiddeck-store-launch')


def zpath(arg):
    text = PROTON.read_text()
    functions = text[text.index('real() {'):text.index('\nhere=')]
    env = dict(os.environ, DDNATV_ROOT=ANDROID, DDNATV_BINDS=BINDS)
    return subprocess.run(['bash', '-c', functions + '\nzpath "$1"', 'zpath', arg], env=env,
                          capture_output=True, text=True, check=True).stdout.rstrip('\n')


class ToolArguments(unittest.TestCase):
    def test_a_guest_path_names_its_android_file(self):
        self.assertEqual('Z:\\data\\user\\0\\com.droiddeck.launcher\\files\\linuxfs\\usr\\local\\lib\\droiddeck-ubisoft\\droiddeck-upc.exe',
                         zpath('Z:\\usr\\local\\lib\\droiddeck-ubisoft\\droiddeck-upc.exe'))

    def test_a_bound_folder_names_its_android_side(self):
        self.assertEqual('Z:\\storage\\emulated\\0\\Games\\Brotato\\setup.exe', zpath('Z:\\home\\games\\Brotato\\setup.exe'))
        self.assertEqual('Z:\\storage\\1234-5678\\Steam\\a.reg', zpath('z:/mnt/sd/a.reg'))

    def test_a_switch_value_is_mapped(self):
        self.assertEqual('/DIR=Z:\\storage\\emulated\\0\\Games\\Game', zpath('/DIR=Z:\\home\\games\\Game'))

    def test_android_and_other_paths_stay(self):
        for arg in ('Z:\\storage\\emulated\\0\\Download\\a.exe', 'Z:\\data\\user\\0\\com.droiddeck.launcher\\files\\linuxfs\\tmp\\a.bat',
                    'C:\\windows\\system32\\reg.exe', 'X:\\Brotato.exe', '-dx12', '-n', '-e', 'cmd.exe', '/d', ''):
            self.assertEqual(arg, zpath(arg))

    def test_wine_keeps_the_guest_root(self):
        self.assertIn('DROIDDECK_GUEST_ROOT) ;;', LAUNCH.read_text())


class StorePaths(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.tool = Path(self.tmp.name) / 'compatibilitytools.d/DroidDeck-Bionic-12.8-r41-Proton'
        (self.tool / 'bionic').mkdir(parents=True)
        shutil.copy(PROTON, self.tool / 'proton')
        (self.tool / 'proton').chmod(0o755)
        (self.tool / 'bionic/launch.sh').write_text('')

    def env(self, **extra):
        values = {'DD_STORE_PROTON': str(self.tool / 'proton'), 'DDNATV_ROOT': ANDROID, 'DDNATV_BINDS': BINDS}
        values.update(extra)
        return patch.dict(os.environ, values)

    def test_a_bionic_game_gets_android_paths(self):
        with self.env():
            self.assertEqual('Z:\\data\\user\\0\\com.droiddeck.launcher\\files\\linuxfs\\root\\.cache\\droiddeck-ubisoft\\bridge-274',
                             store.windows_path('/root/.cache/droiddeck-ubisoft/bridge-274'))
            self.assertEqual('Z:\\storage\\emulated\\0\\Games\\Watch_Dogs\\bin\\Watch_Dogs.exe',
                             store.windows_path('/home/games/Watch_Dogs/bin/Watch_Dogs.exe'))

    def test_the_wrapper_names_the_same_tool(self):
        with self.env(DD_STORE_PROTON=str(self.tool / 'droiddeck-proton-wrap')):
            self.assertEqual('Z:\\storage\\emulated\\0\\Games\\a.reg', store.windows_path('/home/games/a.reg'))

    def test_other_tools_keep_guest_paths(self):
        (self.tool / 'bionic/launch.sh').unlink()
        with self.env():
            self.assertEqual('Z:\\home\\games\\a.reg', store.windows_path('/home/games/a.reg'))
        with patch.dict(os.environ, {'DD_STORE_PROTON': str(self.tool / 'proton')}):
            os.environ.pop('DDNATV_ROOT', None)
            self.assertEqual('Z:\\home\\games\\b.reg', store.windows_path('/home/games/b.reg'))

    def test_a_failing_tool_keeps_the_guest_path(self):
        (self.tool / 'proton').write_text('#!/bin/sh\nexit 1\n')
        with self.env():
            self.assertEqual('Z:\\home\\games\\c.reg', store.windows_path('/home/games/c.reg'))


if __name__ == '__main__':
    unittest.main()
