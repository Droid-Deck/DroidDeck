import importlib.machinery
import importlib.util
import os
import re
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
SHARE = TOOLS.parent / 'app/src/main/java/com/droiddeck/launcher/session/EnhancedSessionLogShare.kt'
ANDROID = '/data/user/0/com.droiddeck.launcher/files/linuxfs'
BINDS = '/storage/emulated/0/Games:/home/games|/storage/1234-5678/Steam:/mnt/sd'


def load(name):
    loader = importlib.machinery.SourceFileLoader(name.replace('-', '_'), str(BIN / name))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


store = load('droiddeck-store-launch')


def proton_function(call, *args, **env):
    text = PROTON.read_text()
    functions = text[text.index('real() {'):text.index('\nhere=')]
    env = dict(os.environ, DDNATV_ROOT=ANDROID, DDNATV_BINDS=BINDS, **env)
    return subprocess.run(['bash', '-c', functions + '\n' + call, 'proton', *args], env=env,
                          capture_output=True, text=True, check=True).stdout.rstrip('\n')


def zpath(arg):
    return proton_function('zpath "$1"', arg)


def session_logs(verb, **env):
    return proton_function('verb=$1; session_logs', verb, **env)


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


class SessionLogs(unittest.TestCase):
    SESSION = '/data/user/0/com.droiddeck.launcher/files/logs/2026-11-22-09-steam'

    def test_a_game_launch_gets_a_folder_of_its_own_in_the_session(self):
        folder = session_logs('waitforexitandrun', DD_DEBUG_DIR=self.SESSION + '/', SteamAppId='341940')
        self.assertRegex(folder, '^' + re.escape(self.SESSION) + r'/games/[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{6}-bionic-341940$')

    def test_the_folder_name_survives_share_logs(self):
        pattern = re.search(r'Regex\("(\[0-9\]\{4\}-[^"]+)"\)', SHARE.read_text()).group(1).replace('\\\\', '\\')
        name = session_logs('waitforexitandrun', DD_DEBUG_DIR=self.SESSION, SteamAppId='341940').rsplit('/', 1)[1]
        self.assertRegex(name, '^(?:' + pattern + ')$')
        self.assertIn('"droiddeck-bionic.log"', SHARE.read_text())

    def test_detailed_graphics_logs_keep_the_game_log_folder(self):
        self.assertEqual(ANDROID + '/tmp/logs/games/20261122-201105-ab12-341940',
                         session_logs('waitforexitandrun', DD_DEBUG_DIR=self.SESSION, PROTON_LOG_DIR='/tmp/logs/games/20261122-201105-ab12-341940'))

    def test_other_verbs_and_sessions_without_a_folder_get_none(self):
        self.assertEqual('', session_logs('run', DD_DEBUG_DIR=self.SESSION))
        self.assertEqual('', session_logs('runinprefix', DD_DEBUG_DIR=self.SESSION))
        env = dict(os.environ)
        env.pop('DD_DEBUG_DIR', None)
        with patch.dict(os.environ, env, clear=True):
            self.assertEqual('', session_logs('waitforexitandrun'))


class LaunchLog(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.compat = self.root / 'compatdata/341940'
        self.logs = self.root / 'session/games/2026-11-22-201105-bionic-341940'

    def run_block(self, verb, logs=''):
        text = LAUNCH.read_text()
        block = text[text.index('mkdir -p "$COMPAT"'):text.index('exec >> "$LOG" 2>&1')]
        script = 'set -e\n' + block + 'echo "line from %s" >> "$LOG"\n' % verb
        subprocess.run(['sh', '-c', script], check=True, env=dict(os.environ, COMPAT=str(self.compat), LOGS=str(logs), verb=verb))
        return self.compat / 'droiddeck-bionic.log'

    def test_a_game_log_lives_in_the_session_folder_with_one_name(self):
        self.compat.mkdir(parents=True)
        (self.compat / 'droiddeck-bionic.log').write_text('previous launch\n')
        log = self.run_block('waitforexitandrun', self.logs)
        shared = self.logs / 'droiddeck-bionic.log'
        self.assertTrue(log.is_symlink())
        self.assertEqual(shared, Path(os.readlink(log)))
        self.assertEqual('line from waitforexitandrun\n', shared.read_text())
        self.assertEqual(1, shared.stat().st_nlink)
        self.assertEqual('previous launch\n', (self.compat / 'droiddeck-bionic.log.1').read_text())

    def test_a_removed_session_folder_leaves_no_dangling_log(self):
        self.run_block('waitforexitandrun', self.logs)
        shutil.rmtree(self.root / 'session')
        log = self.run_block('runinprefix')
        self.assertFalse(log.is_symlink())
        self.assertEqual('line from runinprefix\n', log.read_text())

    def test_without_a_session_folder_the_log_stays_in_the_prefix(self):
        log = self.run_block('run')
        self.assertFalse(log.is_symlink())
        self.assertEqual('line from run\n', log.read_text())

    def test_graphics_logs_follow_the_launch(self):
        text = LAUNCH.read_text()
        self.assertIn('DXVK_LOG_PATH|VKD3D_LOG_FILE|MESA_LOG_FILE) ;;', text)
        self.assertIn('DXVK_LOG_PATH=${DXVK_LOG_PATH:-$COMPAT}', text)


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

    def test_other_tools_name_rootfs_host_paths_by_their_guest_paths(self):
        (self.tool / 'bionic/launch.sh').unlink()
        with self.env():
            self.assertEqual('Z:\\tmp\\droiddeck-ea-1\\probe.bat', store.windows_path(ANDROID + '/tmp/droiddeck-ea-1/probe.bat'))
            self.assertEqual('Z:\\', store.windows_path(ANDROID))
            self.assertEqual('Z:\\data\\user\\0\\com.droiddeck.launcher\\files\\linuxfs2\\a.reg',
                             store.windows_path(ANDROID + '2/a.reg'))
            self.assertEqual('Z:\\storage\\emulated\\0\\Games\\a.reg', store.windows_path('/storage/emulated/0/Games/a.reg'))
        real = '/data/data/com.droiddeck.launcher/files/linuxfs'
        with self.env(), patch.object(store.os.path, 'realpath', lambda path: real if path == ANDROID else path):
            self.assertEqual('Z:\\tmp\\droiddeck-ea-2\\registry.reg', store.windows_path(real + '/tmp/droiddeck-ea-2/registry.reg'))
        with patch.dict(os.environ, {'DD_STORE_PROTON': str(self.tool / 'proton')}):
            os.environ.pop('DDNATV_ROOT', None)
            self.assertEqual('Z:\\data\\user\\0\\com.droiddeck.launcher\\files\\linuxfs\\tmp\\c.reg',
                             store.windows_path(ANDROID + '/tmp/c.reg'))

    def test_a_failing_tool_keeps_the_guest_path(self):
        (self.tool / 'proton').write_text('#!/bin/sh\nexit 1\n')
        with self.env():
            self.assertEqual('Z:\\home\\games\\c.reg', store.windows_path('/home/games/c.reg'))


if __name__ == '__main__':
    unittest.main()
