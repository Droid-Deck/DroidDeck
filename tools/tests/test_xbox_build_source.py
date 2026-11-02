import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class XboxBuildSourceTest(unittest.TestCase):
    def test_main_advancing_between_jobs_does_not_change_packaged_source(self):
        try:
            import yaml
        except ImportError:
            self.fail('PyYAML is required to execute workflow steps')
        workflow = yaml.safe_load((Path(__file__).resolve().parents[2] / '.github/workflows/build.yml').read_text())
        jobs = workflow['jobs']
        step = lambda job, name: next(s['run'] for s in jobs[job]['steps'] if s.get('name') == name)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env = dict(os.environ, GIT_AUTHOR_NAME='test', GIT_COMMITTER_NAME='test',
                       GIT_AUTHOR_EMAIL='test@example.test', GIT_COMMITTER_EMAIL='test@example.test',
                       GITHUB_STEP_SUMMARY=str(root / 'summary'))
            def git(*args, cwd=root):
                return subprocess.check_output(['git', *args], cwd=cwd, env=env, stderr=subprocess.DEVNULL, text=True).strip()
            git('init', '-q', '-b', 'main')
            (root / 'backend').write_text('base')
            git('add', '.'); git('commit', '-qm', 'base')
            git('switch', '-qc', 'feature')
            (root / 'app').write_text('feature')
            git('add', '.'); git('commit', '-qm', 'feature')
            head = git('rev-parse', 'HEAD')
            git('switch', '-q', 'main')
            (root / 'backend').write_text('merged backend')
            git('commit', '-qam', 'backend changed after branch')
            for name in ('source', 'xbox', 'components', 'apk', 'tests', 'dispatch'):
                git('clone', '-q', str(root), str(root / name))
                git('checkout', '-q', head, cwd=root / name)
            output = root / 'output'
            env['GITHUB_OUTPUT'] = str(output)
            subprocess.run(['bash', '-c', step('source', 'Pin and merge the APK source')], cwd=root / 'source', env=env, check=True, capture_output=True)
            base = output.read_text().strip().split('=', 1)[1]
            tree = git('rev-parse', 'HEAD^{tree}', cwd=root / 'source')
            (root / 'backend').write_text('newer main, after backend build')
            git('commit', '-qam', 'main advanced again')
            env.update(BUILD_BASE_SHA=base, XBOX_SOURCE_TREE=tree)
            subprocess.run(['bash', '-c', step('build', 'Merge onto main, or say what conflicts')], cwd=root / 'apk', env=env, check=True, capture_output=True)
            subprocess.run(['bash', '-c', step('build', 'Verify backend and APK source match')], cwd=root / 'apk', env=env, check=True, capture_output=True)
            self.assertEqual((root / 'apk/backend').read_text(), 'merged backend')
            self.assertEqual(git('rev-parse', 'HEAD^{tree}', cwd=root / 'apk'), tree)

            subprocess.run(['bash', '-c', step('python-tests', 'Merge the pinned APK base')], cwd=root / 'tests', env=env, check=True, capture_output=True)
            subprocess.run(['bash', '-c', step('python-tests', 'Verify backend and test source match')], cwd=root / 'tests', env=env, check=True, capture_output=True)
            self.assertEqual(git('rev-parse', 'HEAD^{tree}', cwd=root / 'tests'), tree)

            env['SOURCE_TREE'] = tree
            for job in ('xbox', 'components'):
                subprocess.run(['bash', '-c', step(job, 'Merge the pinned APK base')], cwd=root / job, env=env, check=True, capture_output=True)
                subprocess.run(['bash', '-c', step(job, 'Verify pinned source tree')], cwd=root / job, env=env, check=True, capture_output=True)
                self.assertEqual(git('rev-parse', 'HEAD^{tree}', cwd=root / job), tree)

            trusted = root / 'trusted-output'
            env['GITHUB_OUTPUT'] = str(trusted)
            subprocess.run(['bash', '-c', step('source', 'Resolve trusted manifest base')], cwd=root / 'dispatch', env=env, check=True, capture_output=True)
            self.assertEqual(trusted.read_text().strip(), 'base-sha=' + git('rev-parse', 'main'))
            self.assertNotEqual(git('rev-parse', 'HEAD', cwd=root / 'dispatch'), git('rev-parse', 'main'))
