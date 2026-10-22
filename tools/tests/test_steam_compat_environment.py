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


if __name__ == '__main__':
    unittest.main()
