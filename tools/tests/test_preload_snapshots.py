import copy
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

SPEC = importlib.util.spec_from_file_location('preload_snapshots', Path(__file__).resolve().parents[1] / 'components/preloads.py')
snapshots = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(snapshots)


def archive(files):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode='w:gz') as tar:
        for name, content in files.items():
            entry = tarfile.TarInfo(name)
            entry.size = len(content)
            tar.addfile(entry, io.BytesIO(content))
    return stream.getvalue()


class PreloadSnapshotTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        # Real repository inputs exercise the transitive include walker and build recipe.
        for group in snapshots.INPUTS:
            for name in snapshots.source_files(group):
                if name.startswith('@'):
                    continue
                target = self.root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((snapshots.ROOT / name).read_bytes())
        recipe = 'tools/components/build-arm64-preloads.sh'
        (self.root / recipe).parent.mkdir(parents=True, exist_ok=True)
        (self.root / recipe).write_text((snapshots.ROOT / recipe).read_text())
        x86 = 'tools/components/recipes/x86-preloads.sh'
        (self.root / x86).parent.mkdir(parents=True, exist_ok=True)
        (self.root / x86).write_text((snapshots.ROOT / x86).read_text())
        (self.root / '.github/workflows').mkdir(parents=True)
        (self.root / '.github/workflows/build.yml').write_text((snapshots.ROOT / '.github/workflows/build.yml').read_text())
        self.git('init', '-q')
        self.git('config', 'core.autocrlf', 'false')
        self.git('config', 'user.name', 'Test Developer')
        self.git('config', 'user.email', 'test@example.test')
        self.git('add', '.')
        self.binaries = {name: ('verified-' + name).encode() for name in snapshots.FILES}
        self.content = archive(self.binaries)
        self.data = dict(version=1, origin=dict(run=123, commit='abc', artifact=456, artifact_sha256='0' * 64),
                         groups={g: snapshots.source_files(g, self.root) for g in snapshots.INPUTS},
                         arm64_recipe=snapshots.recipe(self.root), x86_recipe=snapshots.x86_recipe(self.root),
                         archive_sha256=snapshots.sha(self.content),
                         outputs={name: snapshots.sha(value) for name, value in self.binaries.items()})
        (self.root / snapshots.LOCK).write_text(json.dumps(self.data))
        self.git('add', '.')
        self.git('commit', '-qm', 'Approved baseline')
        self.base = self.git('rev-parse', 'HEAD').strip()

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, text=True)

    def restore(self, base=None):
        with patch.dict(os.environ, COMPONENT_TRUSTED_BASE=base or self.base, FORCE_PRELOAD_REBUILD='false'), \
                patch.object(snapshots.urllib.request, 'urlopen', return_value=io.BytesIO(self.content)) as fetch, \
                patch.object(snapshots, 'prove_origin') as prove:
            result = snapshots.restore(self.root)
            return result, fetch, prove

    def test_reviewed_unchanged_binaries_reused_and_written(self):
        result, fetch, prove = self.restore()
        self.assertEqual(result, dict(arm64=True, x86=True))
        prove.assert_not_called()
        fetch.assert_called_once()
        for name, value in self.binaries.items():
            self.assertEqual((self.root / 'app/src/main/assets/linuxfs' / name).read_bytes(), value)

    def test_changed_common_source_requires_both_rebuilds(self):
        path = self.root / snapshots.COMMON[0]
        path.write_text(path.read_text() + '\n// Changed source\n')
        result, fetch, prove = self.restore()
        self.assertEqual(result, dict(arm64=False, x86=False))
        fetch.assert_not_called()
        prove.assert_not_called()

    def test_new_transitive_header_is_an_input(self):
        source = self.root / snapshots.COMMON[0]
        source.write_text(source.read_text() + '\n#include "new-header.h"\n')
        header = source.with_name('new-header.h')
        header.write_text('#define VALUE 1\n')
        before = snapshots.source_files('arm64', self.root)
        header.write_text('#define VALUE 2\n')
        self.assertNotEqual(before, snapshots.source_files('arm64', self.root))

    def test_changed_recipe_rebuilds_arm64_only(self):
        script = self.root / 'tools/components/build-arm64-preloads.sh'
        script.write_text(script.read_text().replace('-O2', '-O3'))
        result, _, _ = self.restore()
        self.assertEqual(result, dict(arm64=False, x86=True))

    def test_shell_newline_changes_invalidate_the_recipe(self):
        script = self.root / 'tools/components/build-arm64-preloads.sh'
        original = snapshots.recipe(self.root)
        text = script.read_text()
        script.write_text(text.replace('-ldl\n', '-ldl ', 1))
        self.assertNotEqual(original, snapshots.recipe(self.root))

    def test_x86_container_recipe_changes_rebuild_x86_only(self):
        script = self.root / 'tools/components/recipes/x86-preloads.sh'
        script.write_text(script.read_text().replace('debian:bullseye', 'debian:bookworm'))
        result, _, _ = self.restore()
        self.assertEqual(result, dict(arm64=True, x86=False))

    def test_new_manifest_requires_main_build_proof(self):
        result, _, prove = self.restore('HEAD~1')
        self.assertTrue(all(result.values()))
        prove.assert_called_once()

    def test_forged_binary_hash_cannot_prove_main_origin(self):
        data = copy.deepcopy(self.data)
        data['outputs']['libblsession.so'] = 'f' * 64
        with patch.object(snapshots, 'artifact_bytes', return_value=self.binaries):
            with self.assertRaisesRegex(ValueError, 'original main APK'):
                snapshots.prove_origin(data, self.root / 'cache', self.root)

    def test_modified_input_policy_cannot_omit_producer_dependencies(self):
        data = copy.deepcopy(self.data)
        data['origin']['commit'] = self.base
        omitted = snapshots.COMMON[0]
        del data['groups']['arm64'][omitted]
        with patch.object(snapshots, 'artifact_bytes', return_value=self.binaries), \
                patch.object(snapshots, 'run', return_value=b''), \
                patch.object(snapshots, 'source_files', side_effect=lambda group, *args: data['groups'][group]), \
                patch.object(snapshots, 'producer_sources', return_value={omitted: self.data['groups']['arm64'][omitted]}):
            with self.assertRaisesRegex(ValueError, 'producing build dependency'):
                snapshots.prove_origin(data, self.root / 'cache', self.root)

    def test_non_main_or_unsuccessful_run_cannot_prove_origin(self):
        build = dict(event='pull_request', head_branch='main', conclusion='success', head_sha='abc',
                     path='.github/workflows/build.yml', repository=dict(full_name=snapshots.REPO))
        for field, value in [('event', 'pull_request'), ('head_branch', 'feature'), ('conclusion', 'failure')]:
            candidate = dict(build, event='push')
            candidate[field] = value
            with self.subTest(field=field), patch.object(snapshots, 'api', return_value=candidate):
                with self.assertRaisesRegex(ValueError, 'successful main'):
                    snapshots.artifact_bytes(self.data['origin'], self.root / 'cache')

    def test_corrupt_archive_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'checksum'):
            snapshots.unpack(self.content + b'tampered', self.data)

    def test_missing_duplicate_or_traversal_entries_are_rejected(self):
        for files in ({}, {'../escaped': b'bad'}, {'libblsession.so': b'wrong'}):
            content = archive(files)
            data = dict(self.data, archive_sha256=snapshots.sha(content))
            with self.subTest(names=list(files)), self.assertRaises(ValueError):
                snapshots.unpack(content, data)

    def test_missing_release_rebuilds_without_accepting_unverified_bytes(self):
        error = snapshots.urllib.error.HTTPError('https://example.test', 404, 'missing', {}, None)
        with patch.dict(os.environ, COMPONENT_TRUSTED_BASE=self.base), \
                patch.object(snapshots.urllib.request, 'urlopen', side_effect=error):
            self.assertEqual(snapshots.restore(self.root), dict(arm64=False, x86=False))

    def test_transient_download_failure_rebuilds_but_integrity_errors_fail(self):
        errors = [TimeoutError('network'), snapshots.http.client.IncompleteRead(b'partial'),
                  snapshots.urllib.error.HTTPError('https://example.test', 503, 'unavailable', {}, None)]
        for error in errors:
            with self.subTest(error=type(error).__name__), patch.dict(os.environ, COMPONENT_TRUSTED_BASE=self.base), \
                    patch.object(snapshots.urllib.request, 'urlopen', side_effect=error):
                self.assertEqual(snapshots.restore(self.root), dict(arm64=False, x86=False))
        with patch.dict(os.environ, COMPONENT_TRUSTED_BASE=self.base), \
                patch.object(snapshots.urllib.request, 'urlopen', return_value=io.BytesIO(b'corrupt')):
            with self.assertRaisesRegex(ValueError, 'checksum'):
                snapshots.restore(self.root)

    def test_manual_rebuild_does_not_download_snapshot(self):
        with patch.dict(os.environ, FORCE_PRELOAD_REBUILD='true'), \
                patch.object(snapshots.urllib.request, 'urlopen') as fetch:
            self.assertEqual(snapshots.restore(self.root), dict(arm64=False, x86=False))
            fetch.assert_not_called()
