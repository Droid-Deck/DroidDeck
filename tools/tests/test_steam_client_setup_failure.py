import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest


SESSION = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-session'
SOURCE = SESSION.read_text()
ARM64_BRANCH = SOURCE.index('    else\n      step "checking the Steam client (first run')
START = SOURCE.index('    droiddeck-steam-install || steam_setup_failed $?\n', ARM64_BRANCH)
END = SOURCE.index('    step "Steam client ready"\n', START)
HELPER = re.search(r'^steam_setup_failed\(\) \{\n.*?^\}\n', SOURCE, re.M | re.S).group()
INSTALL_BLOCK = HELPER + textwrap.dedent(SOURCE[START:END])
RUN_GAMESCOPE = re.search(r'^run_gamescope\(\) \(\n.*?^\)\n', SOURCE, re.M | re.S).group()
MARKER = '== steam: the Steam client could not be set up'


class SteamClientSetupFailureTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def installer(self, status):
        script = self.root / 'droiddeck-steam-install'
        script.write_text('#!/bin/bash\n'
                          'echo "curl: (35) Recv failure: Connection reset by peer" >&2\n'
                          'exit ' + str(status) + '\n')
        script.chmod(0o755)

    def env(self, **extra):
        base = {k: v for k, v in os.environ.items() if k != 'BL_STEAM_EXIT_FILE'}
        return dict(base, PATH=str(self.root) + os.pathsep + os.environ['PATH'],
                    XDG_RUNTIME_DIR=str(self.root), **extra)

    def run_block(self, status, exit_file=None):
        self.installer(status)
        extra = {'BL_STEAM_EXIT_FILE': str(exit_file)} if exit_file else {}
        return subprocess.run(['bash', '-c', INSTALL_BLOCK + 'echo continued\n'], env=self.env(**extra),
                              capture_output=True, text=True, timeout=10)

    def test_failed_install_reports_its_status_in_the_exit_file(self):
        exit_file = self.root / 'exit'
        result = self.run_block(1, exit_file)
        self.assertEqual(1, result.returncode)
        self.assertIn(MARKER + ' (status 1)', result.stdout)
        self.assertNotIn('continued', result.stdout)
        self.assertEqual('1\n', exit_file.read_text())

    def test_failed_install_outside_gamescope_still_exits_with_its_status(self):
        result = self.run_block(50)
        self.assertEqual(50, result.returncode)
        self.assertIn(MARKER + ' (status 50)', result.stdout)

    def test_ready_client_continues_without_a_report(self):
        exit_file = self.root / 'exit'
        result = self.run_block(0, exit_file)
        self.assertEqual(0, result.returncode)
        self.assertIn('continued', result.stdout)
        self.assertNotIn(MARKER, result.stdout)
        self.assertFalse(exit_file.exists())

    def test_failed_install_survives_successful_compositor_shutdown(self):
        self.installer(1)
        compositor = self.root / 'gamescope'
        compositor.write_text('#!/bin/bash\n"$@"\nexit 0\n')
        compositor.chmod(0o755)
        child = self.root / 'session-child'
        child.write_text('#!/bin/bash\n' + INSTALL_BLOCK + 'exit 0\n')
        child.chmod(0o755)
        result = subprocess.run(['bash', '-c', RUN_GAMESCOPE + '\nrun_gamescope "$@"', 'session', str(child)],
                                env=self.env(), capture_output=True, text=True, timeout=10)
        self.assertEqual(1, result.returncode)
        self.assertIn(MARKER, result.stdout)
        self.assertEqual(list(self.root.glob('droiddeck-steam-exit.??????')), [])


if __name__ == '__main__':
    unittest.main()
