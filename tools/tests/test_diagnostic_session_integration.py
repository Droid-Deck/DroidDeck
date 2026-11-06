import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


SESSION = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-session'


class DiagnosticSessionIntegrationTest(unittest.TestCase):
    def run_inventory(self, enabled, inside=False, installed=True):
        source = SESSION.read_text()
        match = re.search(r'^start_diagnostic_inventory\(\) \{\n.*?^\}\n', source, re.M | re.S)
        self.assertIsNotNone(match)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            marker = root / 'called'
            if installed:
                helper = root / 'droiddeck-diagnostic-inventory'
                helper.write_text('#!/bin/bash\nprintf "%s\\n" "$@" > "$MARKER"\n')
                helper.chmod(0o755)
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                       MARKER=str(marker), DROIDDECK_ENHANCED_DIAGNOSTICS='1' if enabled else '0')
            env['BL_INSIDE'] = '1' if inside else ''
            script = match.group() + '\nstart_diagnostic_inventory\n'
            if installed and enabled and not inside:
                script += 'wait "$_bl_diagnostic_inventory"\n'
            result = subprocess.run(['bash', '-c', script], env=env, capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            return marker.read_text().splitlines() if marker.exists() else [], result.stdout

    def test_starts_for_an_enhanced_outer_session(self):
        arguments, _ = self.run_inventory(True)
        self.assertEqual(arguments[:2], ['--watch', '--root-pid'])
        self.assertTrue(arguments[2].isdigit())

    def test_does_not_start_when_disabled_or_inside_gamescope(self):
        self.assertEqual(self.run_inventory(False)[0], [])
        self.assertEqual(self.run_inventory(True, inside=True)[0], [])

    def test_reports_when_the_provider_is_not_installed(self):
        _, output = self.run_inventory(True, installed=False)
        self.assertIn('DROIDDECK_PROCESS_TRACE', output)
        self.assertIn('"status":"unavailable"', output)


if __name__ == '__main__':
    unittest.main()
