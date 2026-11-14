from pathlib import Path
import re
import shutil
import subprocess
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-steam-x64'


class SteamUpdateProgressTest(unittest.TestCase):
    def forward(self, lines):
        matches = re.findall(
            r'(?m)^\s*(grep --line-buffered .*\| sed -u .* >&2) \) &$',
            SCRIPT.read_text(encoding='utf-8'),
        )
        self.assertEqual(1, len(matches), 'Find the production bootstrap forwarding pipeline')
        result = subprocess.run(
            [shutil.which('bash') or 'bash', '-c', matches[0]],
            input='\n'.join(lines) + '\n', text=True, capture_output=True, check=True, timeout=10,
        )
        self.assertEqual('', result.stdout)
        return result.stderr.splitlines()

    def test_downloads_keep_their_normalized_progress(self):
        self.assertEqual(['Downloading update (1250 of 5000 KB)'], self.forward([
            '[2026-10-25 12:00:01] Downloading update (1,250 of 5,000 KB)...',
        ]))

    def test_forwards_extracting_and_installing_from_bootstrap_logs(self):
        lines = [
            '[2026-10-25 12:00:02] Extracting package...',
            '[2026-10-25 12:00:03] Installing update...',
        ]
        self.assertEqual(lines, self.forward(lines))

    def test_accepts_plain_and_console_prefixed_messages(self):
        lines = [
            'Extracting package...', '[----] Installing update...',
            '[2026-10-25 12:00:02] [----] Extracting package...',
        ]
        self.assertEqual(lines, self.forward(lines))

    def test_ignores_unrelated_and_similarly_named_messages(self):
        self.assertEqual([], self.forward([
            'Loading cached metrics', 'worker: Installing update...',
            'Installing update for Proton...', '[12:00:02] error: Extracting package...',
        ]))

    def test_preserves_stage_order_when_a_download_restarts(self):
        self.assertEqual([
            'Downloading update (100 of 100 KB)', 'Installing update...',
            'Downloading update (20 of 100 KB)',
        ], self.forward([
            'Downloading update (100 of 100 KB)', 'Installing update...',
            'Downloading update (20 of 100 KB)',
        ]))
