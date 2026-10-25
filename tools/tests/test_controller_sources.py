import difflib
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest

from tools.prepare_controller_sources import prepare


class ControllerSourcesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for name in ('sdlbridge', 'sdl3'):
            vendor = self.root / 'third_party' / name
            (vendor / 'upstream/java').mkdir(parents=True)
            (vendor / 'patches').mkdir()
            (vendor / 'upstream/java/Input.java').write_bytes(b'upstream\n')
            patch = ''.join(difflib.unified_diff(['upstream\n'], ['embedded\n'],
                fromfile='a/java/Input.java', tofile='b/java/Input.java'))
            (vendor / 'patches/embed.patch').write_text(patch, encoding='utf-8', newline='\n')
            manifest = {'version': 1, 'source': {'commit': 'a' * 40},
                'files': {'java/Input.java': {'sha256': hashlib.sha256(b'upstream\n').hexdigest()}},
                'patches': ['patches/embed.patch']}
            (vendor / 'upstream.json').write_text(json.dumps(manifest), encoding='utf-8')

    def test_prepares_patched_sources_without_changing_snapshots(self):
        prepare(self.root)
        for name in ('sdlbridge', 'sdl3'):
            self.assertEqual(b'embedded\n', (self.root / 'app/build/controller-sources' / name / 'java/Input.java').read_bytes())
            self.assertEqual(b'upstream\n', (self.root / 'third_party' / name / 'upstream/java/Input.java').read_bytes())
            self.assertTrue((self.root / 'app/build/controller-sources' / name / 'upstream.json').is_file())

    def test_rejects_changed_snapshot_before_replacing_previous_output(self):
        output = self.root / 'app/build/controller-sources'
        output.mkdir(parents=True)
        (output / 'previous').write_text('keep')
        (self.root / 'third_party/sdlbridge/upstream/java/Input.java').write_bytes(b'drift\n')
        with self.assertRaisesRegex(ValueError, 'hash changed'):
            prepare(self.root)
        self.assertEqual('keep', (output / 'previous').read_text())

    def test_accepts_a_relative_output_path(self):
        previous = Path.cwd()
        try:
            os.chdir(self.root)
            prepare(self.root, Path('app/build/relative'))
        finally:
            os.chdir(previous)
        self.assertEqual(b'embedded\n', (self.root / 'app/build/relative/sdlbridge/java/Input.java').read_bytes())

    def test_fails_when_an_upstream_update_breaks_a_patch(self):
        vendor = self.root / 'third_party/sdlbridge'
        (vendor / 'upstream/java/Input.java').write_bytes(b'new upstream\n')
        manifest = json.loads((vendor / 'upstream.json').read_text())
        manifest['files']['java/Input.java']['sha256'] = hashlib.sha256(b'new upstream\n').hexdigest()
        (vendor / 'upstream.json').write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, 'patch does not apply'):
            prepare(self.root)
        self.assertFalse((self.root / 'app/build/controller-sources').exists())

    def test_refuses_output_outside_the_build_directory(self):
        with self.assertRaisesRegex(ValueError, 'below app/build'):
            prepare(self.root, self.root / 'third_party')


if __name__ == '__main__':
    unittest.main()
