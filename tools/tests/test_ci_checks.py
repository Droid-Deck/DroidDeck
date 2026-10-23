import itertools
import os
from pathlib import Path
import subprocess
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]


class CiChecksTest(unittest.TestCase):
    def test_required_status_rejects_failed_cancelled_or_skipped_test_jobs(self):
        jobs = yaml.safe_load((ROOT / '.github/workflows/build.yml').read_text())['jobs']
        gate = jobs['checks']
        self.assertEqual(gate['name'], 'Compile and test APK')
        self.assertEqual(set(gate['needs']), {'build', 'python-tests'})
        self.assertIn('always()', gate['if'])
        for build, python in itertools.product(('success', 'failure', 'cancelled', 'skipped'), repeat=2):
            with self.subTest(build=build, python=python):
                result = subprocess.run(
                    ['bash', '-c', gate['steps'][0]['run']],
                    env=dict(os.environ, BUILD_RESULT=build, PYTHON_RESULT=python),
                    capture_output=True)
                self.assertEqual(result.returncode == 0, build == python == 'success')
        for publisher in ('sign', 'packs'):
            self.assertIn('checks', jobs[publisher]['needs'])

