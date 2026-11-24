import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest import mock

COMPONENTS = Path(__file__).resolve().parents[1] / 'components'
sys.path.insert(0, str(COMPONENTS))
spec = importlib.util.spec_from_file_location('component_reuse', COMPONENTS / 'reuse.py')
reuse = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reuse)
prepare = reuse.prepare


class ComponentReuseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'tools/ci').mkdir(parents=True)
        (self.root / 'tools/ci/toolchains.env').write_text('CI_ANDROID_NDK_VERSION=30.0.1\n')
        self.local = {'runtime': 'a', 'proot': 'b'}
        self.unregistered, self.submodules = [], []
        for patcher in [mock.patch.object(prepare, 'registry', lambda root: dict.fromkeys(self.local)),
                        mock.patch.object(prepare, 'identity', lambda name, root: self.local[name]),
                        mock.patch.object(reuse, 'unregistered_changes', lambda tree, root: self.unregistered),
                        mock.patch.object(reuse, 'edited_submodules', lambda root: self.submodules)]:
            patcher.start()
            self.addCleanup(patcher.stop)
        self.archive = self.root / 'prepared-components.tar.gz'
        self.contents = {name + '/file': name.encode() for name in prepare.TREES}
        self.contents.update({name: b'library' for name in prepare.LIBRARIES})

    def write_archive(self, recipes, contents=None):
        contents = contents or self.contents
        manifest = dict(version=1, source_tree='tree', run='42', recipes=recipes,
                        files={name: hashlib.sha256(data).hexdigest() for name, data in self.contents.items()})
        with tarfile.open(self.archive, 'w:gz') as archive:
            for name, data in list(contents.items()) + [(prepare.MANIFEST, json.dumps(manifest).encode())]:
                entry = tarfile.TarInfo(name)
                entry.size = len(data)
                entry.mode = 0o644
                archive.addfile(entry, io.BytesIO(data))

    def test_matching_components_replace_local_ones(self):
        stale = self.root / prepare.TREES[0] / 'left-over'
        stale.parent.mkdir(parents=True)
        stale.write_text('from an older local build')
        self.write_archive(dict(self.local))
        self.assertTrue(reuse.reuse(self.archive, self.root))
        self.assertEqual(b'library', (self.root / prepare.LIBRARIES[0]).read_bytes())
        self.assertFalse(stale.exists())

    def test_a_changed_component_is_not_reused(self):
        self.write_archive(dict(self.local, proot='other'))
        with mock.patch('sys.stdout', new_callable=io.StringIO) as stdout:
            self.assertFalse(reuse.reuse(self.archive, self.root))
        self.assertIn('proot', stdout.getvalue())
        self.assertFalse((self.root / prepare.LIBRARIES[0]).exists())

    def test_a_tampered_file_is_rejected_and_local_outputs_survive(self):
        local = self.root / prepare.LIBRARIES[0]
        local.parent.mkdir(parents=True)
        local.write_bytes(b'local build')
        self.write_archive(dict(self.local), dict(self.contents, **{prepare.LIBRARIES[0]: b'tampered'}))
        with self.assertRaisesRegex(ValueError, 'do not match'):
            reuse.reuse(self.archive, self.root)
        self.assertEqual(b'local build', local.read_bytes())

    def test_changed_sources_ci_builds_outside_recipes_are_not_reused(self):
        self.unregistered = ['tools/linuxfs/xclipse-layer/xclipse_layer.c']
        self.write_archive(dict(self.local))
        with mock.patch('sys.stdout', new_callable=io.StringIO) as stdout:
            self.assertFalse(reuse.reuse(self.archive, self.root))
        self.assertIn('xclipse_layer.c', stdout.getvalue())

    def test_an_edited_submodule_is_not_reused(self):
        self.submodules = ['external/MaliBridge']
        self.write_archive(dict(self.local))
        with mock.patch('sys.stdout', new_callable=io.StringIO):
            self.assertFalse(reuse.reuse(self.archive, self.root))

    def test_identities_use_the_ci_runner_and_ndk(self):
        seen = {}
        with mock.patch.object(prepare, 'identity', lambda name, root: seen.update(
                arch=reuse.os.environ['RUNNER_ARCH'], ndk=reuse.os.environ['NDK_VERSION']) or 'x'):
            reuse.identities(reuse.ci_environment('X64', self.root), self.root)
        self.assertEqual({'arch': 'X64', 'ndk': '30.0.1'}, seen)
