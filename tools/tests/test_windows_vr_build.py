import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location(
    'windows_vr_build', Path(__file__).resolve().parents[1] / 'vr/build_windows.py')
vr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(vr)


class WindowsVrBuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.out = self.root / 'build/vr'
        self.include = self.root / 'ndk/include'
        for name in vr.SOURCE_FILES:
            self.write(self.root / name, b'build source\n')
        for tree in vr.SOURCE_TREES:
            self.write(self.root / tree / 'input.h', b'header\n')
        for name, source in vr.CONFIGS.items():
            self.write(self.root / source, b'{"name": "current"}\n')
            self.write(self.out / name, b'{"name": "current"}\n')
        for name in vr.LIBRARIES | vr.EXECUTABLES:
            data = bytearray(64)
            data[:6] = b'\x7fELF\x02\x01'
            struct.pack_into('<HH', data, 16, 3, 183)
            self.write(self.out / name, data)
        for name in vr.HAND_MODELS:
            self.write(self.out / name, b'generated hand model\n')
        for name in ('vulkan/vulkan_core.h', 'vulkan/vk_layer.h'):
            self.write(self.include / name, b'vulkan header\n')
        self.commit = mock.patch.object(vr, 'commit', return_value='current-revision')
        self.commit.start()
        self.addCleanup(self.commit.stop)
        self.record()

    @staticmethod
    def write(path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def record(self):
        self.manifest = {'version': 1, 'source_commit': 'current-revision',
                         'sources': vr.hashes(vr.sources(self.root)),
                         'headers': vr.hashes(vr.headers(self.include)),
                         'outputs': {n: vr.sha((self.out / n).read_bytes()) for n in vr.OUTPUTS}}
        self.save_manifest()

    def save_manifest(self):
        (self.out / vr.MANIFEST).write_text(json.dumps(self.manifest))

    def validate(self):
        return vr.validate(self.out, self.include, self.root)

    def test_complete_current_build_accepts_windows_line_endings(self):
        self.write(self.root / 'tools/vr/src/input.h', b'header\r\n')
        files, manifest = self.validate()
        self.assertEqual(set(files), vr.OUTPUTS)
        self.assertEqual(manifest['source_commit'], 'current-revision')

    def test_missing_manifest_cannot_import_old_unattested_binaries(self):
        (self.out / vr.MANIFEST).unlink()
        with self.assertRaisesRegex(ValueError, 'Missing or invalid VR build manifest'):
            self.validate()

    def test_modified_or_new_source_rejects_cached_build(self):
        for name in ('tools/vr/src/input.h', 'tools/vr/src/new.h'):
            with self.subTest(name=name):
                path = self.root / name
                previous = path.read_bytes() if path.exists() else None
                self.write(path, b'changed\n')
                with self.assertRaisesRegex(ValueError, 'VR build is stale'):
                    self.validate()
                if previous is None:
                    path.unlink()
                else:
                    path.write_bytes(previous)

    def test_changed_revision_or_vulkan_headers_reject_cached_build(self):
        with mock.patch.object(vr, 'commit', return_value='new-revision'):
            with self.assertRaisesRegex(ValueError, 'VR build is stale'):
                self.validate()
        self.write(self.include / 'vulkan/vulkan_core.h', b'new header\n')
        with self.assertRaisesRegex(ValueError, 'VR build is stale'):
            self.validate()

    def test_changed_binary_rejects_cached_build(self):
        path = self.out / sorted(vr.LIBRARIES)[0]
        path.write_bytes(path.read_bytes() + b'changed')
        with self.assertRaisesRegex(ValueError, 'output checksum mismatch'):
            self.validate()

    def test_wrong_architecture_rejected_even_with_matching_checksum(self):
        path = self.out / sorted(vr.LIBRARIES)[0]
        data = bytearray(path.read_bytes())
        struct.pack_into('<H', data, 18, 62)
        path.write_bytes(data)
        self.record()
        with self.assertRaisesRegex(ValueError, 'not an ARM64 ELF shared library'):
            self.validate()

    def test_missing_or_unexpected_outputs_reject_build(self):
        path = self.out / sorted(vr.LIBRARIES)[0]
        data = path.read_bytes()
        path.unlink()
        with self.assertRaisesRegex(ValueError, 'missing or unexpected'):
            self.validate()
        path.write_bytes(data)
        self.write(self.out / 'old-library.so', data)
        with self.assertRaisesRegex(ValueError, 'missing or unexpected'):
            self.validate()

    def test_old_configuration_rejected_even_with_matching_checksum(self):
        self.write(self.out / sorted(vr.CONFIGS)[0], b'{"name": "old"}')
        self.record()
        with self.assertRaisesRegex(ValueError, 'configuration differs'):
            self.validate()

    def test_build_image_selection_and_platform_validation(self):
        outputs = vr.read_outputs(self.out, self.root)
        cases = [
            ('cached_amd64', vr.IMAGE, [(0, 'sha256:cached linux/amd64\n')], False, 'sha256:cached', None),
            ('missing_default', vr.IMAGE, [(1, ''), (0, 'sha256:rebuilt linux/amd64\n')], True, 'sha256:rebuilt', None),
            ('default_arm64', vr.IMAGE, [(0, 'sha256:old linux/arm64\n'), (0, 'sha256:rebuilt linux/amd64\n')], True, 'sha256:rebuilt', None),
            ('default_windows', vr.IMAGE, [(0, 'sha256:old windows/amd64\n'), (0, 'sha256:rebuilt linux/amd64\n')], True, 'sha256:rebuilt', None),
            ('custom_arm64', 'custom:vr', [(0, 'sha256:custom linux/arm64\n')], False, None, r'linux/amd64.*custom:vr'),
            ('rebuild_still_arm64', vr.IMAGE, [(0, 'sha256:old linux/arm64\n'), (0, 'sha256:bad linux/arm64\n')], True, None, r'linux/amd64'),
        ]
        for name, image, inspects, expect_build, expected_id, err in cases:
            with self.subTest(name=name):
                calls, queue = [], list(inspects)
                def fake_run(cmd, **kw):
                    calls.append(cmd)
                    if cmd[1:3] == ['image', 'inspect']:
                        rc, out = queue.pop(0)
                        return vr.subprocess.CompletedProcess(cmd, rc, stdout=out, stderr='')
                    return vr.subprocess.CompletedProcess(cmd, 0, stdout='linux\n', stderr='')
                with mock.patch.object(vr.shutil, 'which', return_value='docker'), \
                     mock.patch.object(vr.subprocess, 'run', side_effect=fake_run), \
                     mock.patch.object(vr.subprocess, 'check_output', return_value=b'sha256:legacy\n'), \
                     mock.patch.object(vr, 'read_outputs', return_value=outputs) as m_read, \
                     mock.patch.object(vr, 'validate', return_value=(outputs, self.manifest)) as m_val:
                    if err:
                        with self.assertRaisesRegex(ValueError, err):
                            vr.build(self.out, self.include, self.root, image=image)
                        m_read.assert_not_called()
                        m_val.assert_not_called()
                    else:
                        self.assertEqual(vr.build(self.out, self.include, self.root, image=image), (outputs, self.manifest))
                        m_read.assert_called_once()
                        m_val.assert_called_once_with(self.out, self.include, self.root)
                inspect_cmds = [c for c in calls if c[1:3] == ['image', 'inspect']]
                build_cmds = [c for c in calls if c[1] == 'build']
                run_cmds = [c for c in calls if c[1] == 'run']
                self.assertEqual(len(inspect_cmds), len(inspects))
                for c in inspect_cmds:
                    self.assertEqual(c[:5], ['docker', 'image', 'inspect', '--format', '{{.Id}} {{.Os}}/{{.Architecture}}'])
                if expect_build:
                    self.assertEqual(len(build_cmds), 1)
                    self.assertEqual(build_cmds[0][:6], ['docker', 'build', '--platform', 'linux/amd64', '--tag', image])
                else:
                    self.assertEqual(build_cmds, [])
                if expected_id:
                    self.assertEqual(len(run_cmds), 1)
                    self.assertIn(expected_id, run_cmds[0])
                    self.assertNotIn(image, run_cmds[0])
                    self.assertTrue(all('linux/amd64' not in a or a == 'linux/amd64' for a in run_cmds[0]))
                    manifest = json.loads((self.out / vr.MANIFEST).read_text(encoding='utf-8'))
                    self.assertEqual(manifest['compiler_image'], expected_id)
                else:
                    self.assertEqual(run_cmds, [])


if __name__ == '__main__':
    unittest.main()
