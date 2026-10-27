import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


BIN = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin'


class SteamCompatEnvironmentTest(unittest.TestCase):
    def test_library_launch_preserves_guest_environment_without_steam_client_libraries(self):
        source = (BIN / 'droiddeck-session').read_text()
        setup = source[source.index('    _bl_compat_env='):source.index('    export SDL_VIDEO_X11_DGAMOUSE=')]
        launch = next(line for line in source.splitlines()
                      if 'droiddeck-steam-compat "$steam_root"' in line)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = root / 'droiddeck-steam-compat'
            helper.write_text('#!/usr/bin/env python3\nimport json, os, sys\n'
                              'json.dump({"env": dict(os.environ), "args": sys.argv[1:]}, '
                              'open(os.environ["BRIDGE_CAPTURE"], "w"))\n')
            helper.chmod(0o755)
            for inherited in ({}, {'LD_LIBRARY_PATH': '/guest/driver:/guest/libs'},
                              {'LD_LIBRARY_PATH': '', 'STEAM_RUNTIME': ''},
                              {'LD_LIBRARY_PATH': '/custom path/lib', 'STEAM_RUNTIME': '0'}):
                with self.subTest(inherited=inherited):
                    env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                               BRIDGE_CAPTURE=str(root / 'captured.json'),
                               VK_ICD_FILENAMES='/guest/driver/turnip.json', steam_root='/steam')
                    for key in ('LD_LIBRARY_PATH', 'STEAM_RUNTIME'):
                        env.pop(key, None)
                    env.update(inherited)
                    result = subprocess.run(['bash', '-c', setup + '\n' + launch + '\nwait $!\n'
                                             'printf "%s\\n" "$STEAM_RUNTIME" "$LD_LIBRARY_PATH"'],
                                            env=env, capture_output=True, text=True, timeout=10)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    bridge = json.loads((root / 'captured.json').read_text())
                    self.assertEqual(bridge['args'], ['/steam'])
                    for key in ('LD_LIBRARY_PATH', 'STEAM_RUNTIME', 'VK_ICD_FILENAMES'):
                        self.assertEqual(bridge['env'].get(key), env.get(key), key)
                    client_path = '/steam/steamrtarm64:/steam/steamrtarm64/panorama'
                    if inherited.get('LD_LIBRARY_PATH'):
                        client_path += ':' + inherited['LD_LIBRARY_PATH']
                    self.assertEqual(result.stdout.splitlines(), ['1', client_path])

    def test_session_exit_stops_the_library_helper(self):
        source = (BIN / 'droiddeck-session').read_text()
        launch = next(line for line in source.splitlines()
                      if 'droiddeck-steam-compat "$steam_root"' in line)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pid = root / 'pid'
            helper = root / 'droiddeck-steam-compat'
            helper.write_text('#!/bin/sh\necho $$ > "$PID_FILE"\nexec sleep 30\n')
            helper.chmod(0o755)
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                       PID_FILE=str(pid), steam_root='/steam', steam_x64='0')
            subprocess.run(['bash', '-c', '_bl_compat_env=(env)\n' + launch + '\n'
                            'until [ -s "$PID_FILE" ]; do sleep 0.05; done\n'
                            'kill $(jobs -p)\nwait'],
                           env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
            helper_pid = int(pid.read_text())
            try:
                os.kill(helper_pid, 0)
            except ProcessLookupError:
                return
            os.kill(helper_pid, 9)
            self.fail('droiddeck-steam-compat outlived the session')

    def test_the_x86_64_client_gets_only_the_file_dialog_bridge(self):
        source = (BIN / 'droiddeck-session').read_text()
        start = source.index('    if [ "$steam_x64" = 1 ]; then\n      { droiddeck-steam-compat --files-only')
        block = source[start:source.index('    fi\n', start) + len('    fi\n')]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = root / 'droiddeck-steam-compat'
            helper.write_text('#!/bin/sh\necho "$@" >> "$ARGS_FILE"\n')
            helper.chmod(0o755)
            for x64, expected in (('1', '--files-only /steam'), ('0', '/steam')):
                with self.subTest(x64=x64):
                    args = root / ('args-' + x64)
                    env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                               ARGS_FILE=str(args), steam_root='/steam', steam_x64=x64)
                    subprocess.run(['bash', '-c', '_bl_compat_env=(env)\n' + block + 'wait\n'], env=env, timeout=10, check=True)
                    self.assertEqual(args.read_text().split('\n')[0], expected)

    def test_files_only_mode_installs_only_the_file_dialog_hook(self):
        import runpy
        compat = runpy.run_path(str(BIN / 'droiddeck-steam-compat'))
        calls = []

        class Page:
            def __init__(self, port, path):
                self.ws = None
            def evaluate(self, expression):
                calls.append(('evaluate', expression[:40]))
                return True
            def call(self, method, params):
                calls.append((method, params['name']))

        helper = compat['Helper']('/steam', 1234, files_only=True)
        helper.attach.__globals__.update(shared_context=lambda port: '/devtools/page/1', Page=Page)
        self.assertTrue(helper.attach())
        bindings = [name for method, name in calls if method == 'Runtime.addBinding']
        self.assertEqual(bindings, [compat['FILE_BINDING']])
        self.assertIn(('evaluate', compat['FILE_JS'][:40]), calls)


if __name__ == '__main__':
    unittest.main()
