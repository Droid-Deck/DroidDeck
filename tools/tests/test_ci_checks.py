import itertools
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]


class CiChecksTest(unittest.TestCase):
    def test_pr_cache_writes_and_source_timestamp_policy(self):
        jobs = yaml.safe_load((ROOT / '.github/workflows/build.yml').read_text())['jobs']
        cache = next(step for step in jobs['build']['steps']
                     if step.get('uses') == 'gradle/actions/setup-gradle@v4')['with']
        self.assertEqual(cache['cache-read-only'],
                         "${{ github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name != github.repository }}")
        gradle = (ROOT / 'app/build.gradle').read_text()
        self.assertIn("gitValue(['show', '-s', '--format=%ct', '--end-of-options', sourceCommit])", gradle)

    def test_report_comment_lookup_ignores_human_marker(self):
        if not shutil.which('jq'):
            self.skipTest('jq required to execute comment selection')
        script = (ROOT / 'tools/release/report-build.sh').read_text()
        line = next(line for line in script.splitlines() if line.startswith('ID='))
        arguments = shlex.split(line[len('ID=$('):].split(' | head', 1)[0])
        query = arguments[arguments.index('--jq') + 1].replace('$MARK', '<!-- build -->')
        comments = [dict(id=1, user=dict(login='someone'), body='<!-- build --> forged'),
                    dict(id=2, user=dict(login='github-actions[bot]'), body='<!-- build --> result')]
        result = subprocess.check_output(['jq', '-r', query], input=json.dumps(comments), text=True)
        self.assertEqual(result.strip(), '2')

    def test_build_and_tests_use_identical_generated_metadata(self):
        jobs = yaml.safe_load((ROOT / '.github/workflows/build.yml').read_text())['jobs']
        steps = {step.get('id'): step for step in jobs['build']['steps']}
        self.assertEqual(steps['build']['env'], steps['unit-tests']['env'])
        self.assertIn('DROIDDECK_BUILD_LABEL', steps['unit-tests']['env'])
        self.assertIn('SGDB_API_KEY', steps['unit-tests']['env'])
        gradle = (ROOT / 'app/build.gradle').read_text()
        self.assertIn("outputs.doNotCacheIf('CI executes the test suite on every run') { true }", gradle)
        self.assertIn('outputs.upToDateWhen { false }', gradle)

    def test_independent_jobs_wait_only_for_the_pinned_source(self):
        jobs = yaml.safe_load((ROOT / '.github/workflows/build.yml').read_text())['jobs']
        for name in ('xbox', 'components', 'python-tests'):
            self.assertEqual(jobs[name]['needs'], 'source')
        self.assertEqual(set(jobs['build']['needs']), {'source', 'components', 'xbox'})

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

