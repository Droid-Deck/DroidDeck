import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock
from zipfile import ZipFile


spec = importlib.util.spec_from_file_location(
    'prepare_windows_runtime', Path(__file__).resolve().parents[1] / 'prepare_windows_runtime.py')
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class WindowsRuntimePreparationTest(unittest.TestCase):
    def test_archive_paths_cannot_escape_destination(self):
        for name in ('../outside', '/absolute', 'dir/../../outside', 'C:/absolute', 'dir\\outside', '.'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                prepare.validate_relative(name)

    def test_tracked_outputs_cannot_be_overwritten(self):
        with mock.patch.object(prepare, 'git', return_value=b'tracked/file\n'):
            with self.assertRaisesRegex(ValueError, 'overwrite tracked'):
                prepare.checked_output('tracked/file')

    def test_rebuilt_libraries_must_match_each_architecture(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'libblsession.so'
            elf = bytearray(64)
            elf[:4] = b'\x7fELF'
            elf[5] = 1
            elf[18:20] = struct.pack('<H', 183)
            path.write_bytes(elf)
            self.assertEqual(prepare.validate_rebuilt(path, 183), elf)
            with self.assertRaisesRegex(ValueError, 'wrong ELF architecture'):
                prepare.validate_rebuilt(path, 62)
            path.write_bytes(b'not an ELF')
            with self.assertRaisesRegex(ValueError, 'wrong ELF architecture'):
                prepare.validate_rebuilt(path, 183)

    def test_new_committed_native_changes_reject_donor_reuse(self):
        with mock.patch.object(prepare, 'git', return_value=prepare.DONOR_TREE.encode()), \
                mock.patch.object(prepare, 'changed', return_value={'tools/linuxfs/preload/net.c'}):
            with self.assertRaisesRegex(ValueError, 'Sources changed since'):
                prepare.check_sources()

    def test_vr_sources_are_classified_as_rebuilt_including_local_and_new_files(self):
        committed = {'tools/vr/src/vk_layer_droiddeck_vr.c', 'tools/vr/build.sh'}
        local = {'tools/vr/src/presenter.cpp'}
        new = {'tools/vr/src/fence_tracker.h'}
        with mock.patch.object(prepare, 'changed', side_effect=[committed, local, committed]), \
                mock.patch.object(prepare, 'git', side_effect=[prepare.DONOR_TREE.encode(),
                    b'tools/vr/src/fence_tracker.h\0']):
            classifications = prepare.check_sources()
            self.assertEqual(set(classifications), committed | local | new)
            self.assertTrue(all('never imported from donor' in value for value in classifications.values()))

    def test_preparation_excludes_all_donor_vr_files_and_records_replacement_origin(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            apk = root / 'donor.apk'
            vr_name = 'layer/libVkLayer_droiddeck_vr.so'
            with ZipFile(apk, 'w') as package:
                package.writestr(prepare.VR_PREFIX + vr_name, b'old library')
                package.writestr(prepare.VR_PREFIX + 'obsolete.so', b'obsolete donor file')
                package.writestr('assets/linuxfs/unchanged.so', b'unchanged component')
            (root / 'app/build').mkdir(parents=True)
            (root / 'app/build/runtime-inputs.json').write_text('{}')
            vr_manifest = {'version': 1, 'source_commit': 'current', 'outputs': {vr_name: 'verified'}}
            with mock.patch.object(prepare, 'ROOT', root), \
                    mock.patch.object(prepare, 'check_sources', return_value={}), \
                    mock.patch.object(prepare, 'DONOR_SHA256', prepare.runtime.digest(apk)), \
                    mock.patch.object(prepare, 'REBUILT', {}), \
                    mock.patch.object(prepare, 'JNI', ()), mock.patch.object(prepare, 'PREPARED', ()), \
                    mock.patch.object(prepare, 'LOCAL_BUILD_PLUMBING', set()), \
                    mock.patch.object(prepare, 'source_assets', return_value={}), \
                    mock.patch.object(prepare, 'read_gamescope', return_value=({}, {
                        'GAMESCOPE_REPO': 'repo', 'GAMESCOPE_TAG': 'tag', 'GAMESCOPE_SHA256': 'hash'})), \
                    mock.patch.object(prepare, 'validate_tracked_assets', return_value={}), \
                    mock.patch.object(prepare, 'prepare_ime', return_value=({}, {})), \
                    mock.patch.object(prepare, 'git', return_value=b''), \
                    mock.patch.object(prepare.runtime, 'record'), mock.patch.object(prepare.runtime, 'verify'), \
                    mock.patch.object(prepare.vr_build, 'build', return_value=({vr_name: b'new library'}, vr_manifest)) as build:
                prepare.prepare(apk, root, root, llvm_strip=root / 'bin/llvm-strip')
            build.assert_called_once()
            vr_folder = root / 'app/src/main' / prepare.VR_PREFIX
            self.assertEqual((vr_folder / vr_name).read_bytes(), b'new library')
            self.assertFalse((vr_folder / 'obsolete.so').exists())
            import json
            provenance = json.loads((root / 'app/build/windows-runtime-provenance.json').read_text())
            self.assertEqual(provenance['vr_build'], vr_manifest)
            self.assertIn('VR rebuilt', provenance['outputs']['app/src/main/' + prepare.VR_PREFIX + vr_name]['origin'])

    def test_invalid_cached_vr_stops_before_reading_or_staging_the_donor(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            apk = root / 'donor.apk'
            apk.write_bytes(b'donor')
            with mock.patch.object(prepare, 'check_sources', return_value={}), \
                    mock.patch.object(prepare, 'DONOR_SHA256', prepare.runtime.digest(apk)), \
                    mock.patch.object(prepare, 'REBUILT', {}), \
                    mock.patch.object(prepare, 'read_gamescope', return_value=({}, {})), \
                    mock.patch.object(prepare.vr_build, 'validate', side_effect=ValueError('VR build is stale')), \
                    mock.patch.object(prepare.vr_build, 'build') as build, \
                    mock.patch.object(prepare, 'ZipFile') as archive:
                with self.assertRaisesRegex(ValueError, 'VR build is stale'):
                    prepare.prepare(apk, root, root, rebuilt_vr=root, vk_include=root)
                build.assert_not_called()
                archive.assert_not_called()

    def test_current_helpers_are_allowed_only_when_restaged_from_source(self):
        helpers = {
            'tools/linuxfs/overlay/usr/local/bin/droiddeck-session',
            'tools/linuxfs/overlay/usr/local/bin/droiddeck-steam-compat',
            'tools/linuxfs/overlay/usr/local/bin/droiddeck-steam-ime',
            'tools/linuxfs/overlay/usr/local/bin/droiddeck-steamvr',
            'tools/linuxfs/overlay/usr/local/bin/droiddeck-text-input',
        }
        self.assertEqual(prepare.LOCAL_SESSION_HELPERS, helpers)
        sources = {prepare.ROOT / path: 'assets/linuxfs/' + path.split('/overlay/')[1]
                   for path in helpers}
        for local_changes in (set(), helpers):
            with self.subTest(local_changes=local_changes), \
                    mock.patch.object(prepare, 'source_assets', return_value=sources), \
                    mock.patch.object(prepare, 'changed', side_effect=[helpers, local_changes, helpers]), \
                    mock.patch.object(prepare, 'git', side_effect=[prepare.DONOR_TREE.encode(), b'']):
                classifications = prepare.check_sources()
                self.assertEqual(set(classifications), helpers)
                self.assertTrue(all('restaged from current source' in action
                                    for action in classifications.values()))
        with mock.patch.object(prepare, 'source_assets', return_value={}), \
                mock.patch.object(prepare, 'changed', return_value=helpers), \
                mock.patch.object(prepare, 'git', return_value=prepare.DONOR_TREE.encode()):
            with self.assertRaisesRegex(ValueError, 'Sources changed since'):
                prepare.check_sources()

    def test_cardboard_changes_are_compiled_by_gradle_not_reused_from_donor(self):
        changed = {'app/src/main/cpp/waylandcomp/src/cardboard.c'}
        with mock.patch.object(prepare, 'changed', side_effect=[changed, changed, changed]), \
                mock.patch.object(prepare, 'git', side_effect=[prepare.DONOR_TREE.encode(), b'']):
            classifications = prepare.check_sources()
            self.assertEqual(set(classifications), changed)
            self.assertIn('compiled by Gradle; not imported', next(iter(classifications.values())))

    def test_other_helper_changes_still_need_an_audit(self):
        with mock.patch.object(prepare, 'git', return_value=prepare.DONOR_TREE.encode()), \
                mock.patch.object(prepare, 'changed', return_value={
                    'tools/linuxfs/overlay/usr/local/bin/droiddeck-unreviewed'}):
            with self.assertRaisesRegex(ValueError, 'Sources changed since'):
                prepare.check_sources()

    def test_uncommitted_native_changes_reject_donor_reuse(self):
        with mock.patch.object(prepare, 'git', side_effect=[prepare.DONOR_TREE.encode(), b'']), \
                mock.patch.object(prepare, 'changed', side_effect=[set(), {'tools/linuxfs/preload/net.c'}]):
            with self.assertRaisesRegex(ValueError, 'Unreviewed runtime'):
                prepare.check_sources()

    def test_wrong_apk_hash_fails_before_replacement_or_output_access(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / 'incorrect.apk'
            apk.write_bytes(b'incorrect donor')
            with mock.patch.object(prepare, 'check_sources', return_value={}), \
                    mock.patch.object(prepare, 'read_gamescope') as read_gamescope:
                with self.assertRaisesRegex(ValueError, 'Donor APK checksum mismatch'):
                    prepare.prepare(apk, Path(folder) / 'missing', Path(folder) / 'missing.tzst')
                read_gamescope.assert_not_called()

    def test_sdk_bridge_is_compiled_locally_while_other_untracked_native_files_fail(self):
        with mock.patch.object(prepare, 'source_assets', return_value={}), \
                mock.patch.object(prepare, 'changed', side_effect=[set(), {'app/src/main/cpp/CMakeLists.txt'}, set()]), \
                mock.patch.object(prepare, 'git', side_effect=[prepare.DONOR_TREE.encode(),
                    b'app/src/main/cpp/viture/sdk_bridge.cpp\0app/src/main/cpp/viture/stereo_frame_gate.h\0']):
            classifications = prepare.check_sources()
            self.assertEqual(set(classifications), {'app/src/main/cpp/CMakeLists.txt',
                                                    'app/src/main/cpp/viture/sdk_bridge.cpp',
                                                    'app/src/main/cpp/viture/stereo_frame_gate.h'})
            self.assertTrue(all('compiled by Gradle' in action for action in classifications.values()))
        with mock.patch.object(prepare, 'changed', side_effect=[set(), set()]), \
                mock.patch.object(prepare, 'git', side_effect=[prepare.DONOR_TREE.encode(),
                    b'app/src/main/cpp/unreviewed.cpp\0']):
            with self.assertRaisesRegex(ValueError, 'Unreviewed runtime'):
                prepare.check_sources()


    def test_ime_sources_are_regenerated_from_pinned_rpms_in_every_stage(self):
        ime = {'tools/ime/prepare.py', 'tools/ime/packages.json', 'tools/ime/smoke.py'}
        with mock.patch.object(prepare, 'changed', side_effect=[ime, ime, ime]), \
                mock.patch.object(prepare, 'git', side_effect=[prepare.DONOR_TREE.encode(), b'']):
            classifications = prepare.check_sources()
        self.assertEqual(set(classifications), ime)
        for action in classifications.values():
            self.assertIn('regenerated from checksum-pinned RPMs', action)
            self.assertIn('never reused from donor', action)

    def test_unknown_ime_and_native_sources_still_fail_the_audit(self):
        for path in ('tools/ime/unreviewed.py', 'app/src/main/cpp/unreviewed.cpp'):
            with self.subTest(path=path), \
                    mock.patch.object(prepare, 'git', return_value=prepare.DONOR_TREE.encode()), \
                    mock.patch.object(prepare, 'changed', return_value={path}):
                with self.assertRaisesRegex(ValueError, 'Sources changed since'):
                    prepare.check_sources()
        with mock.patch.object(prepare, 'changed', side_effect=[set(), set(), set()]), \
                mock.patch.object(prepare, 'git', side_effect=[prepare.DONOR_TREE.encode(),
                    b'tools/ime/unreviewed.py\0']):
            with self.assertRaisesRegex(ValueError, 'Unreviewed runtime'):
                prepare.check_sources()

    def test_ime_payload_is_staged_before_record_and_overrides_donor_files(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            apk = root / 'donor.apk'
            files = {prepare.IME_MANIFEST: b'generated manifest',
                     'usr/local/share/droiddeck/pinyin.so': b'generated ime'}
            with ZipFile(apk, 'w') as package:
                package.writestr('assets/linuxfs/' + prepare.IME_MANIFEST, b'donor manifest')
                package.writestr('assets/linuxfs/usr/local/share/droiddeck/pinyin.so', b'donor ime')
            (root / 'app/build').mkdir(parents=True)
            (root / 'app/build/runtime-inputs.json').write_text('{}')
            staged = {}

            def record():
                for name in files:
                    staged[name] = (root / 'app/src/main/assets/linuxfs' / name).read_bytes()

            with mock.patch.object(prepare, 'ROOT', root), \
                    mock.patch.object(prepare, 'check_sources', return_value={}), \
                    mock.patch.object(prepare, 'DONOR_SHA256', prepare.runtime.digest(apk)), \
                    mock.patch.object(prepare, 'REBUILT', {}), \
                    mock.patch.object(prepare, 'JNI', ()), mock.patch.object(prepare, 'PREPARED', ()), \
                    mock.patch.object(prepare, 'LOCAL_BUILD_PLUMBING', set()), \
                    mock.patch.object(prepare, 'source_assets', return_value={}), \
                    mock.patch.object(prepare, 'read_gamescope', return_value=({}, {
                        'GAMESCOPE_REPO': 'repo', 'GAMESCOPE_TAG': 'tag', 'GAMESCOPE_SHA256': 'hash'})), \
                    mock.patch.object(prepare, 'validate_tracked_assets', return_value={}), \
                    mock.patch.object(prepare, 'prepare_ime',
                                       return_value=(files, {'version': 1, 'files': {}})), \
                    mock.patch.object(prepare, 'git', return_value=b''), \
                    mock.patch.object(prepare.runtime, 'record', side_effect=record), \
                    mock.patch.object(prepare.runtime, 'verify'), \
                    mock.patch.object(prepare.vr_build, 'build', return_value=({}, {})):
                prepare.prepare(apk, root, root, llvm_strip=root / 'bin/llvm-strip')
            self.assertEqual(staged, files)
            output = json.loads((root / 'app/build/windows-runtime-provenance.json').read_text())
            self.assertEqual(output['ime_build'], {'version': 1, 'files': {}})
            self.assertIn('regenerated from checksum-pinned RPMs',
                          output['outputs']['app/src/main/assets/linuxfs/' + prepare.IME_MANIFEST]['origin'])

    def test_second_preparation_accepts_existing_generated_ime_files(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            apk = root / 'donor.apk'
            with ZipFile(apk, 'w') as package:
                package.writestr('assets/linuxfs/unchanged.so', b'unchanged component')
            (root / 'app/build').mkdir(parents=True)
            (root / 'app/build/runtime-inputs.json').write_text('{}')
            files = {prepare.IME_MANIFEST: b'generated manifest'}
            with mock.patch.object(prepare, 'ROOT', root), \
                    mock.patch.object(prepare, 'check_sources', return_value={}), \
                    mock.patch.object(prepare, 'DONOR_SHA256', prepare.runtime.digest(apk)), \
                    mock.patch.object(prepare, 'REBUILT', {}), \
                    mock.patch.object(prepare, 'JNI', ()), mock.patch.object(prepare, 'PREPARED', ()), \
                    mock.patch.object(prepare, 'LOCAL_BUILD_PLUMBING', set()), \
                    mock.patch.object(prepare, 'source_assets', return_value={}), \
                    mock.patch.object(prepare, 'read_gamescope', return_value=({}, {
                        'GAMESCOPE_REPO': 'repo', 'GAMESCOPE_TAG': 'tag', 'GAMESCOPE_SHA256': 'hash'})), \
                    mock.patch.object(prepare, 'validate_tracked_assets', return_value={}), \
                    mock.patch.object(prepare, 'prepare_ime',
                                      return_value=(files, {'version': 1, 'files': {}})) as prepare_ime, \
                    mock.patch.object(prepare, 'git', return_value=b''), \
                    mock.patch.object(prepare.runtime, 'record'), \
                    mock.patch.object(prepare.runtime, 'verify'), \
                    mock.patch.object(prepare.vr_build, 'build', return_value=({}, {})) as build_vr, \
                    mock.patch.object(prepare.vr_build, 'validate', return_value=({}, {})) as validate_vr:
                prepare.prepare(apk, root, root, llvm_strip=root / 'bin/llvm-strip')
                prepare.prepare(apk, root, root, llvm_strip=root / 'bin/llvm-strip', rebuilt_vr=root / 'cached-vr')
            build_vr.assert_called_once()
            validate_vr.assert_called_once()
            self.assertEqual(prepare_ime.call_args_list, [
                mock.call(prepare.vr_build.IMAGE),
                mock.call(prepare.vr_build.IMAGE),
            ])
            self.assertEqual((root / 'app/src/main/assets/linuxfs' / prepare.IME_MANIFEST).read_bytes(),
                             b'generated manifest')

    def test_windows_ime_preparation_uses_docker_without_local_extractors(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'tools/ime').mkdir(parents=True)
            (root / 'tools/ime/prepare.py').write_text('# prepare', encoding='utf-8')
            (root / 'tools/ime/packages.json').write_text('{}', encoding='utf-8')
            manifest = {'version': 1, 'files': {
                'usr/local/share/droiddeck/pinyin.so': {
                    'sha256': hashlib.sha256(b'ime').hexdigest(), 'mode': 420}}}
            commands = []

            def run(command, check=True, capture_output=True, text=True):
                commands.append(command)
                if command[1:3] == ['image', 'inspect']:
                    return mock.Mock(returncode=0, stdout='sha256:image-id\n', stderr='')
                mounted = next(part for part in command if part.endswith(':/out'))
                output = Path(mounted.removesuffix(':/out'))
                target = output / 'usr/local/share/droiddeck/pinyin.so'
                target.parent.mkdir(parents=True)
                target.write_bytes(b'ime')
                (output / prepare.IME_MANIFEST).write_text(json.dumps(manifest), encoding='utf-8')
                return mock.Mock(returncode=0, stdout='', stderr='')

            with mock.patch.object(prepare, 'ROOT', root), \
                    mock.patch.object(prepare.sys, 'platform', 'win32'), \
                    mock.patch.object(prepare.shutil, 'which', return_value='docker') as locate, \
                    mock.patch.object(prepare.subprocess, 'run', side_effect=run):
                files, provenance = prepare.prepare_ime('compiler-image')
            inspect_command, command = commands
            self.assertEqual(inspect_command, ['docker', 'image', 'inspect', '--format', '{{.Id}}',
                                               'compiler-image'])
            self.assertEqual(command[0], 'docker')
            self.assertIn('--rm', command)
            self.assertIn('--platform', command)
            self.assertIn('linux/amd64', command)
            self.assertIn('sha256:image-id', command)
            self.assertNotIn('compiler-image', command)
            shell = command[-1]
            self.assertNotIn('apt-get', shell)
            self.assertIn('command -v "$tool"', shell)
            self.assertIn('rpm2cpio cpio', shell)
            self.assertIn('rebuild compiler image using tools/local-cross.Dockerfile', shell)
            self.assertIn('exec python3 /src/tools/ime/prepare.py', shell)
            self.assertIn('--packages /cache', shell)
            self.assertIn('--download', shell)
            self.assertIn('--output /out', shell)
            locate.assert_called_once_with('docker')
            self.assertEqual(files['usr/local/share/droiddeck/pinyin.so'], b'ime')
            self.assertEqual(json.loads(files[prepare.IME_MANIFEST]), manifest)
            self.assertEqual(provenance, manifest)

    def test_windows_ime_preparation_reuses_existing_default_image_without_building(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'tools/ime').mkdir(parents=True)
            (root / 'tools/ime/prepare.py').write_text('# prepare', encoding='utf-8')
            (root / 'tools/ime/packages.json').write_text('{}', encoding='utf-8')
            manifest = {'version': 1, 'files': {
                'usr/local/share/droiddeck/pinyin.so': {
                    'sha256': hashlib.sha256(b'ime').hexdigest(), 'mode': 420}}}
            commands = []

            def run(command, check=True, capture_output=True, text=True, input=None):
                commands.append(command)
                if command[1:3] == ['image', 'inspect']:
                    return mock.Mock(returncode=0, stdout='sha256:existing-v5\n', stderr='')
                mounted = next(part for part in command if part.endswith(':/out'))
                output = Path(mounted.removesuffix(':/out'))
                target = output / 'usr/local/share/droiddeck/pinyin.so'
                target.parent.mkdir(parents=True)
                target.write_bytes(b'ime')
                (output / prepare.IME_MANIFEST).write_text(json.dumps(manifest), encoding='utf-8')
                return mock.Mock(returncode=0, stdout='', stderr='')

            with mock.patch.object(prepare, 'ROOT', root), \
                    mock.patch.object(prepare.sys, 'platform', 'win32'), \
                    mock.patch.object(prepare.shutil, 'which', return_value='docker'), \
                    mock.patch.object(prepare.subprocess, 'run', side_effect=run):
                files, provenance = prepare.prepare_ime(prepare.vr_build.IMAGE)
            self.assertEqual(prepare.vr_build.IMAGE, 'droiddeck-local-cross:24.04-v5')
            self.assertEqual([cmd[1] for cmd in commands], ['image', 'run'])
            self.assertIn('sha256:existing-v5', commands[1])
            self.assertEqual(files['usr/local/share/droiddeck/pinyin.so'], b'ime')
            self.assertEqual(provenance, manifest)

    def test_windows_ime_preparation_builds_missing_default_image_and_runs_by_id(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'tools/ime').mkdir(parents=True)
            (root / 'tools/ime/prepare.py').write_text('# prepare', encoding='utf-8')
            (root / 'tools/ime/packages.json').write_text('{}', encoding='utf-8')
            (root / 'tools/local-cross.Dockerfile').write_bytes(b'FROM ubuntu:24.04\r\nRUN echo ok\r\n')
            manifest = {'version': 1, 'files': {
                'usr/local/share/droiddeck/pinyin.so': {
                    'sha256': hashlib.sha256(b'ime').hexdigest(), 'mode': 420}}}
            calls = []
            inspected = 0

            def run(command, check=True, capture_output=False, text=False, input=None):
                nonlocal inspected
                calls.append((command, input))
                if command[1:3] == ['image', 'inspect']:
                    inspected += 1
                    if inspected == 1:
                        return mock.Mock(returncode=1, stdout='', stderr='No such image')
                    return mock.Mock(returncode=0, stdout='sha256:built-v5-id\n', stderr='')
                if command[1] == 'build':
                    return mock.Mock(returncode=0)
                mounted = next(part for part in command if part.endswith(':/out'))
                output = Path(mounted.removesuffix(':/out'))
                target = output / 'usr/local/share/droiddeck/pinyin.so'
                target.parent.mkdir(parents=True)
                target.write_bytes(b'ime')
                (output / prepare.IME_MANIFEST).write_text(json.dumps(manifest), encoding='utf-8')
                return mock.Mock(returncode=0)

            with mock.patch.object(prepare, 'ROOT', root), \
                    mock.patch.object(prepare.sys, 'platform', 'win32'), \
                    mock.patch.object(prepare.shutil, 'which', return_value='docker'), \
                    mock.patch.object(prepare.subprocess, 'run', side_effect=run):
                files, provenance = prepare.prepare_ime(prepare.vr_build.IMAGE)
            self.assertEqual([cmd[1] for cmd, _ in calls], ['image', 'build', 'image', 'run'])
            self.assertEqual(calls[0][0], ['docker', 'image', 'inspect', '--format', '{{.Id}}',
                                           'droiddeck-local-cross:24.04-v5'])
            build_cmd, build_input = calls[1]
            self.assertEqual(build_cmd[:8], ['docker', 'build', '--platform', 'linux/amd64',
                                             '--tag', 'droiddeck-local-cross:24.04-v5', '--file', '-'])
            self.assertEqual(build_input, b'FROM ubuntu:24.04\nRUN echo ok\n')
            run_cmd = calls[3][0]
            self.assertIn('sha256:built-v5-id', run_cmd)
            self.assertNotIn('droiddeck-local-cross:24.04-v5', run_cmd)
            self.assertNotIn('apt-get', run_cmd[-1])
            self.assertEqual(files['usr/local/share/droiddeck/pinyin.so'], b'ime')
            self.assertEqual(provenance, manifest)

    def test_windows_ime_preparation_reports_default_image_build_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'tools').mkdir(parents=True)
            (root / 'tools/local-cross.Dockerfile').write_bytes(b'FROM ubuntu:24.04\n')
            commands = []
            build_error = prepare.subprocess.CalledProcessError(17, ['docker', 'build'])

            def run(command, check=True, capture_output=False, text=False, input=None):
                commands.append(command)
                if command[1:3] == ['image', 'inspect']:
                    return mock.Mock(returncode=1, stdout='', stderr='No such image')
                if command[1] == 'build':
                    raise build_error
                self.fail('unexpected command: ' + repr(command))

            with mock.patch.object(prepare, 'ROOT', root), \
                    mock.patch.object(prepare.sys, 'platform', 'win32'), \
                    mock.patch.object(prepare.shutil, 'which', return_value='docker'), \
                    mock.patch.object(prepare.subprocess, 'run', side_effect=run):
                with self.assertRaises(ValueError) as context:
                    prepare.prepare_ime(prepare.vr_build.IMAGE)
            message = str(context.exception)
            self.assertIn(prepare.vr_build.IMAGE, message)
            self.assertRegex(message, r'(?i)build.*default compiler')
            self.assertRegex(message, r'(?i)output.*rerun')
            self.assertIs(context.exception.__cause__, build_error)
            self.assertEqual(context.exception.__cause__.returncode, 17)
            self.assertEqual([cmd[1] for cmd in commands], ['image', 'build'])

    def test_windows_ime_preparation_stops_if_default_image_still_has_no_id_after_build(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'tools').mkdir(parents=True)
            (root / 'tools/local-cross.Dockerfile').write_bytes(b'FROM ubuntu:24.04\n')
            commands = []

            def run(command, check=True, capture_output=False, text=False, input=None):
                commands.append(command)
                if command[1:3] == ['image', 'inspect']:
                    return mock.Mock(returncode=1, stdout='', stderr='still missing')
                return mock.Mock(returncode=0)

            with mock.patch.object(prepare, 'ROOT', root), \
                    mock.patch.object(prepare.sys, 'platform', 'win32'), \
                    mock.patch.object(prepare.shutil, 'which', return_value='docker'), \
                    mock.patch.object(prepare.subprocess, 'run', side_effect=run):
                with self.assertRaisesRegex(ValueError, 'requires the existing Docker image'):
                    prepare.prepare_ime(prepare.vr_build.IMAGE)
            self.assertEqual([cmd[1] for cmd in commands], ['image', 'build', 'image'])

    def test_windows_ime_preparation_rejects_a_missing_image_before_running(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with mock.patch.object(prepare, 'ROOT', root), \
                    mock.patch.object(prepare.sys, 'platform', 'win32'), \
                    mock.patch.object(prepare.shutil, 'which', return_value='docker'), \
                    mock.patch.object(prepare.subprocess, 'run', return_value=mock.Mock(
                        returncode=1, stdout='', stderr='No such image')) as run:
                with self.assertRaisesRegex(ValueError, 'requires the existing Docker image'):
                    prepare.prepare_ime('missing-image')
            run.assert_called_once()
            self.assertEqual(run.call_args.args[0][:3], ['docker', 'image', 'inspect'])


if __name__ == '__main__':
    unittest.main()
