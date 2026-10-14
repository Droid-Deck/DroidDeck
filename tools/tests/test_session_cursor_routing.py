"""The XTest cursor path is enabled only for Steam sessions."""
import os
from pathlib import Path
import re
import subprocess
import unittest

SESSION = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-session'
SOURCE = SESSION.read_text()
MATCH = re.search(
    r'^  if \[ "\$mode" = steam \]; then\n.*?^  fi\n', SOURCE, re.M | re.S)
assert MATCH is not None
CURSOR_SETUP = MATCH.group(0)


class SessionCursorRoutingTest(unittest.TestCase):
    def run_setup(self, mode, initial=None):
        env = dict(os.environ, mode=mode)
        if initial is not None:
            env['gamescope_cursor_composite'] = initial
        else:
            env.pop('gamescope_cursor_composite', None)
        result = subprocess.run(
            ['bash', '-c', CURSOR_SETUP + 'printf "%s\n" "${gamescope_cursor_composite-}"'],
            env=env, text=True, capture_output=True, check=True)
        return result.stdout.strip()

    def test_steam_enables_manual_composition_for_the_nested_cursor(self):
        self.assertEqual('2', self.run_setup('steam'))

    def test_other_sessions_keep_their_existing_cursor_composition(self):
        self.assertEqual('', self.run_setup('desktop'))
        self.assertEqual('1', self.run_setup('run', '1'))


if __name__ == '__main__':
    unittest.main()
