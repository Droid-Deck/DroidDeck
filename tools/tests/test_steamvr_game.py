import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-steamvr-game'


class SteamVrGameTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.helper = self.root / 'helper'
        self.assertIn('/run/droiddeck/droiddeck-xr-frames', SOURCE.read_text())
        self.helper.write_text(SOURCE.read_text().replace('/run/droiddeck/droiddeck-xr-frames',
                                                        str(self.root / 'droiddeck-xr-frames')), encoding='utf-8')
        self.command('pgrep', '#!/bin/sh\nexit 0\n')
        self.command('gamescope', '#!/usr/bin/env python3\nimport json, os, sys\n'
                     'print(json.dumps(dict(argv=sys.argv[1:], modifiers=os.getenv("gamescope_vr_use_modifiers"), '
                     'layers=os.getenv("VK_INSTANCE_LAYERS"))))\n')
        self.env = {key: value for key, value in os.environ.items()
                    if not key.startswith(('gamescope_vr_', 'VK_'))}
        self.env.update(PATH=str(self.root) + os.pathsep + os.environ['PATH'],
                        XDG_RUNTIME_DIR=str(self.root))

    def command(self, name, text):
        path = self.root / name
        path.write_text(text, encoding='utf-8')
        path.chmod(0o755)

    def run_game(self, *args):
        return subprocess.run(['sh', str(self.helper), *args], env=self.env,
                              capture_output=True, text=True, timeout=5)

    def test_native_backend_preserves_command_arguments_and_nonquest_defaults(self):
        result = self.run_game('game with spaces', 'a b', '$literal')
        self.assertEqual(result.returncode, 0, result.stderr)
        data = json.loads(result.stdout)
        self.assertEqual(data['argv'][:2], ['--backend', 'openvr'])
        self.assertEqual(data['argv'][-4:], ['--', 'game with spaces', 'a b', '$literal'])
        self.assertIsNone(data['modifiers'])
        self.assertEqual(data['layers'], 'VK_LAYER_DROIDDECK_vr')

    def test_quest_selects_vulkan_sharing_and_preserves_explicit_override(self):
        with socket.socket(socket.AF_UNIX) as receiver:
            receiver.bind(str(self.root / 'droiddeck-xr-frames'))
            self.assertEqual(json.loads(self.run_game('game').stdout)['modifiers'], 'false')
            self.env['gamescope_vr_use_modifiers'] = 'true'
            self.assertEqual(json.loads(self.run_game('game').stdout)['modifiers'], 'true')

    def test_missing_runtime_and_missing_command_fail_clearly(self):
        self.assertEqual(self.run_game().returncode, 2)
        self.command('pgrep', '#!/bin/sh\nexit 1\n')
        result = self.run_game('game')
        self.assertEqual(result.returncode, 1)
        self.assertIn('Start SteamVR', result.stderr)


if __name__ == '__main__':
    unittest.main()
