from pathlib import Path
import re
import os
import subprocess
import tempfile
import unittest
import yaml


ROOT = Path(__file__).resolve().parents[2]


class PlaystoreWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.play = yaml.load((ROOT / '.github/workflows/playstore.yml').read_text(), Loader=yaml.BaseLoader)
        self.shared = yaml.load((ROOT / '.github/workflows/build.yml').read_text(), Loader=yaml.BaseLoader)
        self.release = yaml.load((ROOT / '.github/workflows/release.yml').read_text(), Loader=yaml.BaseLoader)

    def test_play_source_defaults_to_playstore_and_is_pinned_for_build_and_signing(self):
        self.assertEqual(['playstore'], self.play['on']['push']['branches'])
        inputs = self.play['on']['workflow_dispatch']['inputs']
        self.assertEqual('playstore', inputs['source_branch']['default'])
        self.assertEqual(['playstore'], inputs['source_branch']['options'])
        self.assertEqual('Version for the file names, e.g. 0.1.7', inputs['version']['description'])
        self.assertEqual('true', inputs['version']['required'])
        call = self.play['jobs']['bundle']
        self.assertEqual('./.github/workflows/build.yml', call['uses'])
        self.assertEqual('true', call['with']['play_bundle'])
        self.assertEqual('${{ needs.source.outputs.sha }}', call['with']['source_sha'])
        for name in ['sign', 'publish']:
            self.assertEqual('${{ needs.source.outputs.sha }}', self.play['jobs'][name]['steps'][0]['with']['ref'])
        self.assertEqual('signing-playstore', self.play['jobs']['sign']['environment'])
        signing = next(step for step in self.play['jobs']['sign']['steps'] if step.get('name') == 'Sign the verified Play bundle')
        self.assertEqual('${{ secrets.RELEASE_KEY_ALIAS }}', signing['env']['RELEASE_KEY_ALIAS'])
        self.assertNotIn('--alias', signing['run'])
        self.assertEqual('${{ needs.source.outputs.version_code }}', call['with']['play_version_code'])

    def test_shared_release_and_catalog_jobs_cannot_run_for_a_play_bundle(self):
        for name in ['sign', 'packs', 'announce', 'catalog']:
            with self.subTest(job=name):
                condition = self.shared['jobs'][name]['if']
                self.assertIn('!inputs.play_bundle', condition)
                self.assertIn("github.ref == 'refs/heads/main'", condition)

    def test_only_manual_play_releases_can_publish_and_never_replace_latest(self):
        self.assertEqual('read', self.play['permissions']['contents'])
        self.assertEqual('Build PlayStore Bundle', self.play['name'])
        publish = self.play['jobs']['publish']
        self.assertEqual("github.event_name == 'workflow_dispatch'", publish['if'])
        self.assertEqual('write', publish['permissions']['contents'])
        command = publish['steps'][-1]['run']
        self.assertIn('--latest=false', command)
        self.assertIn('tag="playstore-$VERSION"', command)
        self.assertNotRegex(command, r'--latest(?:\s|$)')
        allowed = {'actions/checkout@v4', 'actions/setup-java@v4', 'actions/download-artifact@v4',
                   'actions/upload-artifact@v4', 'actions/setup-python@v5'}
        artifacts = []
        for name, job in self.play['jobs'].items():
            if name != 'publish':
                self.assertNotEqual('write', job.get('permissions', {}).get('contents'))
            for step in job.get('steps', []):
                if 'uses' in step:
                    self.assertIn(step['uses'], allowed)
                command = step.get('run', '')
                if name != 'publish':
                    self.assertIsNone(re.search(r'gh\s+(?:release|api\b[^\n]*releases)|publish-ci|announce-preview|update-catalog', command))
                if step.get('uses') == 'actions/upload-artifact@v4':
                    artifacts.append(step['with']['name'])
        self.assertIn('droiddeck-play-bundle-signed', artifacts)

    def test_play_source_hash_reaches_every_shared_compile_and_test_checkout(self):
        for name in ['source', 'xbox', 'components', 'build', 'store-tests', 'python-tests']:
            checkout = self.shared['jobs'][name]['steps'][0]
            self.assertIn('inputs.source_sha', checkout['with']['ref'])

    def test_existing_manual_release_entry_point_can_build_play_without_changing_main(self):
        entry = self.release['jobs']['playstore']
        self.assertEqual("github.ref == 'refs/heads/playstore'", entry['if'])
        self.assertEqual('./.github/workflows/playstore.yml', entry['uses'])
        self.assertEqual('playstore', entry['with']['source_branch'])
        self.assertEqual('${{ inputs.version }}', entry['with']['version'])
        self.assertEqual('true', self.play['on']['workflow_call']['inputs']['version']['required'])
        for name in ['find', 'sign', 'packs', 'publish']:
            self.assertEqual("github.ref == 'refs/heads/main'", self.release['jobs'][name]['if'])
        self.assertIn("'release-playstore' || 'release'", self.release['concurrency']['group'])

    def test_play_version_metadata_comes_from_the_repository_without_generated_offsets(self):
        self.assertEqual('playstore-bundle', self.play['concurrency']['group'])
        self.assertEqual('false', self.play['concurrency']['cancel-in-progress'])
        source = self.play['jobs']['source']['steps'][-1]['run']
        self.assertNotIn('date -u +%s', source)
        self.assertNotIn('GITHUB_RUN_NUMBER', source)
        self.assertNotIn('PLAY_VERSION_CODE_BASE', source)
        self.assertIn('echo "version_code=$source_code"', source)
        self.assertIn('VERSION="$name"', source)
        gradle = (ROOT / 'app/build.gradle').read_text()
        self.assertIn('requestedCode != android.defaultConfig.versionCode.toString()', gradle)
        self.assertNotIn('android.defaultConfig.versionCode = requestedCode', gradle)

    def test_distribution_checks_run_without_the_production_manifest_or_signing_secrets(self):
        checks = self.play['jobs']['checks']
        self.assertNotIn('needs', checks)
        self.assertNotIn('environment', checks)
        self.assertEqual('read', checks['permissions']['contents'])
        self.assertIn('checks', self.play['jobs']['sign']['needs'])
        for step in checks['steps']:
            self.assertNotIn('secrets.', str(step))
        command = checks['steps'][-1]['run']
        self.assertIn('tools.tests.test_playstore_signing', command)
        self.assertIn('tools.tests.test_playstore_payloads', command)

    def test_source_job_emits_repository_versions_even_with_old_counter_variables(self):
        source = self.play['jobs']['source']['steps'][-1]['run']
        gradle = (ROOT / 'app/build.gradle').read_text()
        code = re.search(r'^\s*versionCode (\d+)$', gradle, re.MULTILINE).group(1)
        name = re.search(r'^\s*versionName "([^"]+)"$', gradle, re.MULTILINE).group(1)
        for event in ['push', 'workflow_dispatch']:
            with self.subTest(event=event), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'outputs'
                environment = dict(os.environ, GITHUB_EVENT_NAME=event, GITHUB_REF='refs/heads/playstore',
                                   SOURCE_BRANCH='playstore', VERSION=name, GITHUB_OUTPUT=str(output),
                                   GITHUB_RUN_NUMBER='4000000000', PLAY_VERSION_CODE_BASE='999999999')
                subprocess.run(['bash', '-c', source], cwd=ROOT, env=environment, check=True, capture_output=True)
                values = dict(line.split('=', 1) for line in output.read_text().splitlines())
                self.assertEqual(code, values['version_code'])
                self.assertEqual(name, values['version'])


if __name__ == '__main__':
    unittest.main()
