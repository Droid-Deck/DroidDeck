import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SESSION = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-session'
OUTPUT = '2184x1968'
GAME = '3498x1968'


class StretchGameScreenTest(unittest.TestCase):
    def call(self, value):
        source = SESSION.read_text()
        start = source.index('bl_stretch_game_screen() {')
        end = source.index('\n}\n', start) + 3
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / 'xprop.log'
            fake = Path(directory) / 'xprop'
            fake.write_text('#!/bin/bash\nprintf "%s\\n" "$*" >> "$XPROP_LOG"\n')
            fake.chmod(0o755)
            env = dict(os.environ, XPROP_LOG=str(log), PATH=directory + os.pathsep + os.environ['PATH'])
            script = source[start:end] + f'bl_stretch_game_screen "{value}" {OUTPUT} {GAME}\n'
            result = subprocess.run(['bash', '-c', script], env=env, capture_output=True, text=True, timeout=5)
            written = log.read_text().splitlines() if log.exists() else []
            return result.returncode, written

    def test_the_game_screen_the_client_sizes_to_the_output_is_given_the_stretched_size(self):
        status, written = self.call('1, 2184, 1968, 0')
        self.assertEqual(0, status)
        self.assertEqual(['-root -f GAMESCOPE_XWAYLAND_MODE_CONTROL 32c -set GAMESCOPE_XWAYLAND_MODE_CONTROL 1, 3498, 1968, 1'], written)

    def test_a_game_resolution_chosen_in_steam_is_kept(self):
        status, written = self.call('1, 1280, 720, 0')
        self.assertNotEqual(0, status)
        self.assertEqual([], written)

    def test_the_clients_own_screen_is_left_alone(self):
        status, written = self.call('0, 2184, 1968, 0')
        self.assertNotEqual(0, status)
        self.assertEqual([], written)

    def test_the_stretched_size_is_not_rewritten_again(self):
        status, written = self.call('1, 3498, 1968, 1')
        self.assertNotEqual(0, status)
        self.assertEqual([], written)

    def test_a_missing_property_is_ignored(self):
        status, written = self.call('')
        self.assertNotEqual(0, status)
        self.assertEqual([], written)


if __name__ == '__main__':
    unittest.main()
