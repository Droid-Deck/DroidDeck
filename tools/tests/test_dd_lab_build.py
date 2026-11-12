import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'agent/dd-lab-build'


class LabBuildPackageTest(unittest.TestCase):
    def test_package_override_survives_app_handoff_without_its_environment(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            inbox = root / 'agent'
            inbox.mkdir()
            cache = root / '.cache'
            env = {**os.environ, 'HOME': tmp, 'DD_AGENT': '1',
                   'DD_AGENT_INBOX': str(inbox), 'DROIDDECK_PACKAGE': 'com.example.target'}
            env.pop('DD_LAB_DETACHED', None)
            child = subprocess.Popen(['bash', str(SCRIPT), '--prepare'], env=env,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                request = inbox / 'lab-request'
                deadline = time.monotonic() + 10
                while not request.exists() and child.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.01)
                self.assertTrue(request.exists())
                payload = json.loads(request.read_text())
                self.assertEqual(['--prepare', '--package=com.example.target'], payload['args'])
                progress = cache / 'dd-lab-build.json'
                progress.write_text('{"stage":"prepared"}')
                stdout, stderr = child.communicate(timeout=10)
                self.assertEqual(0, child.returncode, stdout + stderr)
            finally:
                if child.poll() is None:
                    child.kill()
                    child.communicate()

            bin_dir = root / 'bin'
            bin_dir.mkdir()
            (bin_dir / 'adb').write_text('#!/bin/sh\nexit 0\n')
            (bin_dir / 'python3').write_text(
                '#!/bin/sh\nprintf "%s" "$DROIDDECK_PACKAGE" > "$HOME/selected-package"\nexit 1\n')
            for tool in bin_dir.iterdir():
                tool.chmod(0o755)
            env.pop('DROIDDECK_PACKAGE')
            env['DD_LAB_DETACHED'] = '1'
            env['PATH'] = str(bin_dir) + os.pathsep + env['PATH']
            result = subprocess.run(['bash', str(SCRIPT), *payload['args']], env=env,
                                    capture_output=True, text=True, timeout=10, cwd=SCRIPT.parents[2])
            self.assertNotEqual(0, result.returncode)
            self.assertIn('could not read DroidDeck', result.stderr)
            self.assertEqual('com.example.target', (root / 'selected-package').read_text())

    def test_invalid_packages_are_rejected_before_handoff(self):
        with tempfile.TemporaryDirectory() as tmp:
            inbox = Path(tmp) / 'agent'
            inbox.mkdir()
            for package in ('bad;touch injected', '../other', '', 'com.example/other'):
                with self.subTest(package=package):
                    env = {**os.environ, 'HOME': tmp, 'DD_AGENT': '1', 'DD_AGENT_INBOX': str(inbox)}
                    result = subprocess.run(['bash', str(SCRIPT), '--package=' + package], env=env,
                                            capture_output=True, text=True, timeout=10)
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn('invalid Android package', result.stderr)
                    self.assertFalse((inbox / 'lab-request').exists())
