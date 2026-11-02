import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('component_prepare', Path(__file__).resolve().parents[1] / 'components/prepare.py')
components = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(components)


class ComponentPrepareTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.fixture(self.root)

    def fixture(self, root):
        for name in components.TREES:
            (root / name).mkdir(parents=True)
        (root / 'tools/components/recipes').mkdir(parents=True)
        (root / 'input.h').write_text('#define VALUE 1\n')
        (root / 'tools/components/recipes/fake.sh').write_text('printf binary > app/src/main/assets/linuxfs/demo.so\n')
        spec = dict(script='tools/components/recipes/fake.sh', inputs=['input.h', 'tools/components/recipes/fake.sh'],
                    outputs=['app/src/main/assets/linuxfs/demo.so'])
        (root / components.REGISTRY).write_text(json.dumps(dict(fake=spec)))
        for name in components.LIBRARIES:
            (root / name).parent.mkdir(parents=True, exist_ok=True)
            (root / name).write_bytes(b'library')
        subprocess.run(['git', 'init', '-q', str(root)], check=True)
        subprocess.run(['git', '-C', str(root), 'config', 'core.autocrlf', 'false'], check=True)
        subprocess.run(['git', '-C', str(root), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(root), '-c', 'user.name=Test Developer', '-c', 'user.email=test@example.test',
                        'commit', '-qm', 'baseline'], check=True)

    def test_recipe_failure_or_missing_output_does_not_succeed(self):
        script = self.root / 'tools/components/recipes/fake.sh'
        script.write_text('exit 7\n')
        with self.assertRaises(subprocess.CalledProcessError):
            components.prepare('fake', self.root)
        script.write_text('exit 0\n')
        with self.assertRaisesRegex(ValueError, 'required outputs'):
            components.prepare('fake', self.root)

    def test_identity_covers_source_and_recipe_changes(self):
        original = components.identity('fake', self.root)
        (self.root / 'input.h').write_text('#define VALUE 2\n')
        changed = components.identity('fake', self.root)
        self.assertNotEqual(original, changed)
        script = self.root / 'tools/components/recipes/fake.sh'
        script.write_text(script.read_text() + '\nprintf changed\n')
        self.assertNotEqual(changed, components.identity('fake', self.root))

    def test_tool_requirements_are_installed_in_one_union(self):
        definitions = dict(one=dict(packages=['gcc', 'cmake']), two=dict(packages=['gcc', 'qemu-user']))
        with patch.object(components, 'registry', return_value=definitions), patch.object(components.subprocess, 'run') as run:
            components.prepare_tools(['one', 'two'], self.root)
            run.assert_called_once_with(['bash', 'tools/ci/install-ubuntu-packages.sh', 'cmake', 'gcc', 'qemu-user'],
                                        cwd=self.root, check=True)

    def test_prepared_handoff_verifies_every_file_and_source_tree(self):
        components.prepare('fake', self.root)
        archive = self.root / 'prepared.tar.gz'
        components.pack(archive, self.root)
        tree = subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], cwd=self.root, text=True).strip()
        target = self.root / 'target'
        self.fixture(target)
        components.unpack(archive, tree, target)
        self.assertEqual((target / 'app/src/main/assets/linuxfs/demo.so').read_bytes(), b'binary')
        with self.assertRaisesRegex(ValueError, 'pinned source'):
            components.unpack(archive, 'wrong-tree', target)

    def test_tampered_component_bytes_fail_handoff_verification(self):
        components.prepare('fake', self.root)
        archive = self.root / 'prepared.tar.gz'
        components.pack(archive, self.root)
        corrupt = self.root / 'corrupt.tar.gz'
        with tarfile.open(archive) as original, tarfile.open(corrupt, 'w:gz') as output:
            for entry in original:
                content = original.extractfile(entry).read()
                if entry.name.endswith('demo.so'):
                    content = b'tampered'
                    entry.size = len(content)
                output.addfile(entry, io.BytesIO(content))
        target = self.root / 'target'
        self.fixture(target)
        tree = subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], cwd=self.root, text=True).strip()
        with self.assertRaisesRegex(ValueError, 'file hashes'):
            components.unpack(corrupt, tree, target)

    def test_archive_cannot_overwrite_source_through_traversal(self):
        archive = self.root / 'bad.tar.gz'
        with tarfile.open(archive, mode='w:gz') as tar:
            name = 'app/src/main/assets/linuxfs/../../../../../input.h'
            entry = tarfile.TarInfo(name)
            entry.size = 3
            tar.addfile(entry, io.BytesIO(b'bad'))
        with self.assertRaisesRegex(ValueError, 'Unsafe'):
            components.unpack(archive, 'tree', self.root)
        self.assertEqual((self.root / 'input.h').read_text(), '#define VALUE 1\n')
