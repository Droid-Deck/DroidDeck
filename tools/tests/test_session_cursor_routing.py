"""Gamescope composites the cursor only when a Steam session asks for it."""
import os
from pathlib import Path
import re
import subprocess
import unittest

SESSION = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-session'
SOURCE = SESSION.read_text()
MATCH = re.search(
    r'^  if \[ "\$mode" = steam \] && \[ "\$\{BL_CURSOR_COMPOSITE:-0\}" = 1 \]; then\n.*?^  fi\n',
    SOURCE, re.M | re.S)
assert MATCH is not None
CURSOR_SETUP = MATCH.group(0)


class SessionCursorRoutingTest(unittest.TestCase):
    def run_setup(self, mode, initial=None, composite=None):
        env = dict(os.environ, mode=mode)
        if initial is not None:
            env['gamescope_cursor_composite'] = initial
        else:
            env.pop('gamescope_cursor_composite', None)
        if composite is not None:
            env['BL_CURSOR_COMPOSITE'] = composite
        else:
            env.pop('BL_CURSOR_COMPOSITE', None)
        result = subprocess.run(
            ['bash', '-c', CURSOR_SETUP + 'printf "%s\n" "${gamescope_cursor_composite-}"'],
            env=env, text=True, capture_output=True, check=True)
        return result.stdout.strip()

    def test_steam_leaves_the_cursor_with_the_host_unless_asked(self):
        self.assertEqual('', self.run_setup('steam'))

    def test_steam_composites_the_cursor_when_the_setting_is_on(self):
        self.assertEqual('2', self.run_setup('steam', composite='1'))

    def test_other_sessions_keep_their_existing_cursor_composition(self):
        self.assertEqual('', self.run_setup('desktop', composite='1'))
        self.assertEqual('1', self.run_setup('run', '1', composite='1'))


if __name__ == '__main__':
    unittest.main()
