import itertools
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]


class CiChecksTest(unittest.TestCase):
    def test_dependency_update_errors_block_installation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sudo = root / 'sudo'
            sudo.write_text('#!/bin/bash\nprintf "%s\\n" "$*" >> "$APT_LOG"\nexit "${APT_RESULT:-0}"\n')
            sudo.chmod(0o755)
            query = root / 'dpkg-query'
            query.write_text('#!/bin/sh\nexit 1\n')
            query.chmod(0o755)
            log = root / 'apt.log'
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'], APT_LOG=str(log))
            installer = ROOT / 'tools/ci/install-ubuntu-packages.sh'
            result = subprocess.run(['bash', str(installer), 'qemu-user'], env=dict(env, APT_RESULT='17'))
            self.assertEqual(result.returncode, 17)
            self.assertEqual(len(log.read_text().splitlines()), 2)
            log.unlink()
            subprocess.run(['bash', str(installer), 'qemu-user'], env=env, check=True)
            update, install = log.read_text().splitlines()
            for command in (update, install):
                self.assertIn('Dir::Etc::sourceparts=-', command)
                self.assertIn('Acquire::https::Timeout=15', command)
                self.assertIn('Acquire::Retries=1', command)
            self.assertIn('APT::Update::Error-Mode=any', update)
            self.assertIn('install -y -q --no-install-recommends qemu-user', install)

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

