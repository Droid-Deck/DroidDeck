import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


SESSION = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-session'


class SteamExitStatusTest(unittest.TestCase):
    def run_session(self, child_status, compositor_status=0, report=True, report_value=None,
                    stale_status=None, diagnostics=False, output=False):
        source = SESSION.read_text()
        function = re.search(r'^run_gamescope\(\) \(\n.*?^\)\n', source, re.M | re.S)
        self.assertIsNotNone(function)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stale_file = root / 'droiddeck-steam-exit.stale'
            if stale_status is not None:
                stale_file.write_text(str(stale_status) + '\n')
            compositor = root / 'gamescope'
            compositor.write_text('#!/bin/bash\n"$@"\nexit ' + str(compositor_status) + '\n')
            compositor.chmod(0o755)
            child = root / 'steam'
            report_arg = '"$1"' if report_value is None else '"$2"'
            report_line = ('printf "%s\\n" ' + report_arg + ' > "$BL_STEAM_EXIT_FILE"\n'
                           if report else '')
            child.write_text('#!/bin/bash\n' + report_line + 'exit "$1"\n')
            child.chmod(0o755)
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                       XDG_RUNTIME_DIR=str(root))
            env['DROIDDECK_ENHANCED_DIAGNOSTICS'] = '1' if diagnostics else '0'
            result = subprocess.run(['bash', '-c', function.group() + '\nrun_gamescope "$@"',
                                     'session', str(child), str(child_status),
                                     str(report_value) if report_value is not None else ''], env=env,
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(list(root.glob('droiddeck-steam-exit.??????')), [])
            if stale_status is not None:
                self.assertEqual(stale_file.read_text(), str(stale_status) + '\n')
            return result if output else result.returncode

    def test_steam_crash_survives_successful_compositor_shutdown(self):
        self.assertEqual(self.run_session(139), 139)

    def test_clean_steam_shutdown_remains_success(self):
        self.assertEqual(self.run_session(0), 0)

    def test_compositor_failure_remains_visible(self):
        self.assertEqual(self.run_session(0, compositor_status=1), 1)

    def test_early_child_failure_preserves_compositor_result(self):
        self.assertEqual(self.run_session(127, compositor_status=127, report=False), 127)

    def test_no_report_does_not_invent_a_failure(self):
        self.assertEqual(self.run_session(0, report=False), 0)

    def test_invalid_report_cannot_become_a_shell_exit_status(self):
        self.assertEqual(self.run_session(999), 0)

    def test_malformed_report_is_ignored(self):
        self.assertEqual(self.run_session(0, report_value='not-an-exit-code'), 0)

    def test_stale_marker_does_not_affect_a_new_launch(self):
        self.assertEqual(self.run_session(0, report=False, stale_status=139), 0)

    def test_diagnostics_preserve_separate_steam_and_compositor_statuses(self):
        result = self.run_session(139, diagnostics=True, output=True)
        self.assertEqual(result.returncode, 139)
        self.assertIn('event=session_exit compositor_status=0 steam_status=139', result.stdout)

    def test_diagnostics_report_an_unavailable_child_status(self):
        result = self.run_session(0, compositor_status=1, report=False, diagnostics=True, output=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn('event=session_exit compositor_status=1 steam_status=unavailable', result.stdout)

    def test_diagnostics_are_disabled_by_default(self):
        result = self.run_session(0, output=True)
        self.assertEqual(result.returncode, 0)
        self.assertNotIn('DROIDDECK_DIAGNOSTICS', result.stdout)


if __name__ == '__main__':
    unittest.main()
