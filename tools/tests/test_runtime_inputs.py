import importlib.util
import gzip
import io
import json
from pathlib import Path, PureWindowsPath
import subprocess
import tarfile
import tempfile
import unittest
from unittest import mock
from zipfile import ZipFile


spec = importlib.util.spec_from_file_location('runtime_inputs', Path(__file__).resolve().parents[1] / 'release/runtime_inputs.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class SyncthingSourceTest(unittest.TestCase):
    relative = 'tools/syncthing/syncthing-android'
    pinned = 'f8819078e7dec1e8a954eec839febd9f7385294e'

    @staticmethod
    def result(stdout=b'', returncode=0):
        return subprocess.CompletedProcess([], returncode, stdout, '')

    def clean_calls(self, root, diff=b'', untracked=b''):
        checkout = root / self.relative
        nested = checkout / 'syncthing/src/github.com/syncthing/syncthing'
        recursive = (f' {self.pinned} {self.relative} (heads/main)\n'
                     f' {"a" * 40} {self.relative}/syncthing (heads/main)\n').encode()
        return [
            self.result(f'160000 {self.pinned} 0\t{self.relative}\n'.encode()),
            self.result((str(checkout.resolve()) + '\n').encode()),
            self.result((self.pinned + '\n').encode()),
            self.result(recursive),
            self.result((str(nested.resolve()) + '\n').encode()),
            self.result(("a" * 40 + '\n').encode()),
            self.result(diff),
            self.result(untracked),
        ]

    def test_pinned_initialized_syncthing_recipe_is_attested_without_tree_scan(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with mock.patch.object(runtime.subprocess, 'run',
                                   side_effect=self.clean_calls(root)) as run:
                sources = runtime.syncthing_sources(root)
        self.assertEqual(set(sources), {self.relative + suffix for suffix in
            ['/.gitlink', '/.checkout', '/.submodules', '/.source-head',
             '/.source-diff', '/.source-untracked']})
        self.assertEqual(run.call_count, 8)
        self.assertIn('tools/syncthing/build.sh', runtime.SOURCE_PATHS)
        self.assertIn(self.relative + '/gradle/libs.versions.toml', runtime.SOURCE_PATHS)
        self.assertNotIn('tools/syncthing', runtime.SOURCE_PATHS)

    def test_syncthing_checkout_and_dirty_source_are_attested(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            nested = root / self.relative / 'syncthing/src/github.com/syncthing/syncthing'
            nested.mkdir(parents=True)
            (nested / 'local.go').write_text('local change')
            calls = self.clean_calls(root, diff=b'diff --git a/a b/a\n', untracked=b'local.go\0')
            calls[2] = self.result(("0" * 40 + '\n').encode())
            with mock.patch.object(runtime.subprocess, 'run', side_effect=calls):
                sources = runtime.syncthing_sources(root)
        self.assertNotEqual(sources[self.relative + '/.gitlink'],
                            sources[self.relative + '/.checkout'])
        self.assertNotEqual(sources[self.relative + '/.source-diff'],
                            runtime.hashlib.sha256().hexdigest())
        self.assertNotEqual(sources[self.relative + '/.source-untracked'],
                            runtime.hashlib.sha256().hexdigest())

    def test_submodule_descriptions_do_not_change_source_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first_calls = self.clean_calls(root)
            second_calls = self.clean_calls(root)
            second_calls[3] = self.result(
                second_calls[3].stdout.replace(b'(heads/main)', b'(v2.0.0-3-g1234567)'))
            with mock.patch.object(runtime.subprocess, 'run', side_effect=first_calls):
                first = runtime.syncthing_sources(root)
            with mock.patch.object(runtime.subprocess, 'run', side_effect=second_calls):
                second = runtime.syncthing_sources(root)
        self.assertEqual(first[self.relative + '/.submodules'],
                         second[self.relative + '/.submodules'])

    def test_submodule_status_preserves_paths_with_spaces(self):
        object_id = b'a' * 40
        first = runtime.normalized_submodule_status(
            b' ' + object_id + b' repo nested/path with spaces (heads/main)\n')
        second = runtime.normalized_submodule_status(
            b' ' + object_id + b' repo nested/path with spaces (v2.0.0-3-g1234567)\n')
        other = runtime.normalized_submodule_status(
            b' ' + object_id + b' repo nested/path with changes (heads/main)\n')
        self.assertEqual(first, second)
        self.assertIn(b'repo nested/path with spaces', first)
        self.assertNotEqual(first, other)

    def test_syncthing_nested_submodules_must_be_initialized(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            calls = self.clean_calls(root)
            calls[3] = self.result(b'-' + calls[3].stdout[1:])
            with mock.patch.object(runtime.subprocess, 'run', side_effect=calls):
                with self.assertRaisesRegex(ValueError, 'uninitialized submodule'):
                    runtime.syncthing_sources(root)


class RuntimeInputsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        syncthing = mock.patch.object(runtime, 'syncthing_sources', return_value={
            'tools/syncthing/syncthing-android/.gitlink': 'a' * 64,
            'tools/syncthing/syncthing-android/.submodules': 'b' * 64,
        })
        syncthing.start()
        self.addCleanup(syncthing.stop)
        paths = runtime.outputs(self.root)
        for name in runtime.required():
            path = self.root / paths.get(name, 'app/src/main/' + name)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(gzip.compress(b'inventory') if path.suffix == '.gzip' else b'prepared runtime')
        self.source = self.root / 'tools/linuxfs/preload/test.c'
        self.source.parent.mkdir(parents=True)
        self.source.write_text('current source')
        self.audio(['pactl', 'modules/arm64/module-aaudio-sink.so', 'modules/arm64/module-directaudio-native-sink.so'])
        self.packs()

    def pack(self, name, files):
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode='w') as archive:
            for rel, content in files.items():
                entry = tarfile.TarInfo(rel)
                entry.size = len(content)
                archive.addfile(entry, io.BytesIO(content))
        path = self.root / runtime.PACKS / (name + '.tar.zst')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(subprocess.run(['zstd', '-q', '-c'], input=stream.getvalue(), capture_output=True, check=True).stdout)

    def packs(self):
        self.pack('tracking', {library.rsplit('/', 1)[1]: b'library' for library in runtime.PACKED_LIBRARIES})
        proot = {}
        for name, (pack, rel) in runtime.PACKED_FILES.items():
            proot[rel] = (self.root / runtime.outputs(self.root)[name]).read_bytes()
        self.pack('proot', proot)
        self.pack('butler', {'butler': b'butler', '7z.so': b'7z', 'libc7zip.so': b'c7zip', 'version': b'15.32.0'})
        for prefix, name in runtime.PACKED.items():
            base = self.root / 'app/src/main' / prefix
            self.pack(name, {path.relative_to(base).as_posix(): path.read_bytes() for path in base.rglob('*') if path.is_file()})

    @staticmethod
    def in_apk(name):
        return name not in runtime.PACKED_FILES and not any(name.startswith(prefix) for prefix in runtime.PACKED)

    def audio(self, names):
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode='w') as archive:
            for name in names:
                entry = tarfile.TarInfo('./' + name)
                entry.size = 1
                archive.addfile(entry, io.BytesIO(b'x'))
        path = self.root / 'app/build/prepared-assets/pulseaudio.tzst'
        path.write_bytes(subprocess.run(['zstd', '-q', '-c'], input=stream.getvalue(), capture_output=True, check=True).stdout)

    def test_an_apk_still_carrying_proot_is_rejected(self):
        apk = self.current_apk(self.root / 'bundled.apk', {'lib/arm64-v8a/libproot.so': b'prepared runtime'})
        with self.assertRaisesRegex(ValueError, 'proot pack provides'):
            runtime.check_apk(apk, self.root)

    def test_missing_proot_cannot_be_attested(self):
        (self.root / 'app/src/main/jniLibs/arm64-v8a/libproot.so').unlink()
        with self.assertRaisesRegex(ValueError, 'libproot.so'):
            runtime.record(self.root)

    def test_missing_syncthing_cannot_be_attested(self):
        (self.root / 'app/src/main/jniLibs/arm64-v8a/libsyncthingnative.so').unlink()
        with self.assertRaisesRegex(ValueError, 'libsyncthingnative.so'):
            runtime.record(self.root)

    def test_base_audio_without_compiled_sinks_is_rejected(self):
        self.audio(['pactl'])
        with self.assertRaisesRegex(ValueError, 'module-aaudio-sink'):
            runtime.record(self.root)

    def test_changed_native_source_rejects_old_outputs(self):
        runtime.record(self.root)
        self.source.write_text('new implementation')
        with self.assertRaisesRegex(ValueError, 'sources changed'):
            runtime.verify(self.root)

    def test_dev_loop_can_reuse_assets_from_other_sources(self):
        runtime.record(self.root)
        self.source.write_text('new implementation')
        with mock.patch('sys.stderr', new_callable=io.StringIO) as stderr:
            runtime.verify(self.root, stale_sources_ok=True)
        self.assertIn('tools/linuxfs/preload/test.c', stderr.getvalue())

    def test_dev_loop_still_rejects_changed_outputs(self):
        runtime.record(self.root)
        (self.root / 'app/src/main/jniLibs/arm64-v8a/libproot.so').write_bytes(b'old APK binary')
        with self.assertRaisesRegex(ValueError, 'changed runtime input'):
            runtime.verify(self.root, stale_sources_ok=True)

    def test_changed_quest_source_rejects_old_outputs(self):
        source = self.root / 'tools/quest/kgsl_compat.c'
        source.parent.mkdir(parents=True)
        source.write_text('old compatibility code')
        runtime.record(self.root)
        source.write_text('new compatibility code')
        with self.assertRaisesRegex(ValueError, 'sources changed'):
            runtime.verify(self.root)

    def test_missing_quest_driver_cannot_be_attested(self):
        (self.root / 'app/build/prepared-assets/graphics_driver/adrenotools-turnip25.1.0-quest1.tzst').unlink()
        with self.assertRaisesRegex(ValueError, 'quest1.tzst'):
            runtime.record(self.root)

    def test_modified_prebuilt_binary_is_rejected(self):
        runtime.record(self.root)
        (self.root / 'app/src/main/jniLibs/arm64-v8a/libproot.so').write_bytes(b'old APK binary')
        with self.assertRaisesRegex(ValueError, 'changed runtime input'):
            runtime.verify(self.root)

    def test_modified_syncthing_binary_is_rejected(self):
        runtime.record(self.root)
        (self.root / 'app/src/main/jniLibs/arm64-v8a/libsyncthingnative.so').write_bytes(
            b'changed syncthing')
        with self.assertRaisesRegex(ValueError, 'libsyncthingnative.so'):
            runtime.verify(self.root)

    def test_an_old_apk_is_rejected_even_when_staging_is_current(self):
        runtime.record(self.root)
        data = runtime.verify(self.root)
        apk = self.root / 'old.apk'
        with ZipFile(apk, 'w') as package:
            for name, output in data['outputs'].items():
                if not self.in_apk(name):
                    continue
                content = b'stale' if name == 'assets/linuxfs/libssbs.so' else (self.root / output['path']).read_bytes()
                package.writestr(name, content)
        with self.assertRaisesRegex(ValueError, 'stale runtime input'):
            runtime.check_apk(apk, self.root)

    def test_apk_with_stale_syncthing_is_rejected(self):
        runtime.record(self.root)
        data = runtime.verify(self.root)
        apk = self.root / 'stale-syncthing.apk'
        with ZipFile(apk, 'w') as package:
            for name, output in data['outputs'].items():
                if not self.in_apk(name):
                    continue
                content = b'stale syncthing' if name.endswith('/libsyncthingnative.so') else \
                    (self.root / output['path']).read_bytes()
                package.writestr(name, content)
        with self.assertRaisesRegex(ValueError, 'stale runtime input: lib/arm64-v8a/libsyncthingnative.so'):
            runtime.check_apk(apk, self.root)

    def test_complete_current_payload_passes(self):
        runtime.record(self.root)
        data = runtime.verify(self.root)
        apk = self.root / 'current.apk'
        with ZipFile(apk, 'w') as package:
            for name, output in data['outputs'].items():
                if self.in_apk(name):
                    package.writestr(name, (self.root / output['path']).read_bytes())
        runtime.check_apk(apk, self.root)

    def require_system_vulkan(self):
        config = self.root / runtime.SYSTEM_VULKAN_COMPONENT_CONFIG
        config.parent.mkdir(parents=True, exist_ok=True)
        config.write_text('{}\n')
        release = self.root / 'tools/gamescope/release.env'
        release.parent.mkdir(parents=True, exist_ok=True)
        release.write_text('GAMESCOPE_TAG=gamescope-3.16.29-p13\n')

    @staticmethod
    def compatible_gamescope():
        return b'\x7fELF' + b'\0'.join(runtime.SYSTEM_VULKAN_GAMESCOPE_MARKERS)

    def gamescope_path(self):
        return self.root / runtime.outputs(self.root)[runtime.GAMESCOPE]

    def test_system_vulkan_rejects_stock_gamescope_while_recording(self):
        self.require_system_vulkan()
        self.gamescope_path().write_bytes(b'\x7fELFstock gamescope p13')
        with self.assertRaisesRegex(ValueError, 'nested-swapchain fallbacks'):
            runtime.record(self.root)

    def test_system_vulkan_accepts_gamescope_with_nested_swapchain_fallbacks(self):
        self.require_system_vulkan()
        self.gamescope_path().write_bytes(self.compatible_gamescope())
        runtime.record(self.root)
        runtime.check_apk(self.current_apk(self.root / 'compatible.apk'), self.root)

    def test_apk_rejects_hash_valid_gamescope_without_system_vulkan_fallbacks(self):
        self.require_system_vulkan()
        self.gamescope_path().write_bytes(self.compatible_gamescope())
        runtime.record(self.root)
        stock = b'\x7fELFstock gamescope p13'
        self.gamescope_path().write_bytes(stock)
        manifest = self.root / runtime.MANIFEST
        data = json.loads(manifest.read_text())
        data['outputs'][runtime.GAMESCOPE]['sha256'] = runtime.digest(self.gamescope_path())
        manifest.write_text(json.dumps(data))
        data = runtime.verify(self.root)
        apk = self.root / 'stock.apk'
        with ZipFile(apk, 'w') as package:
            for name, output in data['outputs'].items():
                if self.in_apk(name):
                    package.writestr(name, (self.root / output['path']).read_bytes())
        with self.assertRaisesRegex(ValueError, 'nested-swapchain fallbacks'):
            runtime.check_apk(apk, self.root)

    def current_apk(self, path, extra=None):
        runtime.record(self.root)
        data = runtime.verify(self.root)
        with ZipFile(path, 'w') as package:
            for name, output in data['outputs'].items():
                if self.in_apk(name):
                    package.writestr(name, (self.root / output['path']).read_bytes())
            for name, content in (extra or {}).items():
                package.writestr(name, content)
        return path

    def test_an_apk_still_carrying_a_packed_tree_is_rejected(self):
        apk = self.current_apk(self.root / 'bundled.apk', {'assets/linuxfs/usr/local/lib/droiddeck-xbox/xodus-cli': b'prepared runtime'})
        with self.assertRaisesRegex(ValueError, 'xbox pack provides'):
            runtime.check_apk(apk, self.root)

    def test_an_apk_still_carrying_tracking_libraries_is_rejected(self):
        apk = self.current_apk(self.root / 'bundled.apk', {runtime.PACKED_LIBRARIES[0]: b'library'})
        with self.assertRaisesRegex(ValueError, 'tracking pack provides'):
            runtime.check_apk(apk, self.root)

    def test_a_stale_pack_is_rejected(self):
        apk = self.current_apk(self.root / 'current.apk')
        self.pack('comet', {'comet': b'old build', 'version': b'old'})
        with self.assertRaisesRegex(ValueError, 'comet is missing or has a stale'):
            runtime.check_apk(apk, self.root)

    def test_a_missing_pack_is_rejected(self):
        apk = self.current_apk(self.root / 'current.apk')
        (self.root / runtime.PACKS / 'xbox.tar.zst').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing add-on pack xbox'):
            runtime.check_apk(apk, self.root)

    def test_windows_manifest_matches_portable_apk_paths(self):
        path_type = type(self.root)
        original_relative_to = path_type.relative_to

        def windows_relative_to(path, *args, **kwargs):
            return PureWindowsPath(original_relative_to(path, *args, **kwargs))

        with mock.patch.object(path_type, 'relative_to', windows_relative_to):
            runtime.record(self.root)
        data = runtime.verify(self.root)
        self.assertIn('tools/linuxfs/preload/test.c', data['sources'])
        nested_asset = 'assets/linuxfs/usr/local/bin/gamescope'
        self.assertEqual(data['outputs'][nested_asset]['path'], 'app/src/main/' + nested_asset)
        for name in data['sources']:
            self.assertNotIn('\\', name)
        apk = self.root / 'portable.apk'
        with ZipFile(apk, 'w') as package:
            for name, output in data['outputs'].items():
                if not self.in_apk(name):
                    continue
                self.assertNotIn('\\', name)
                self.assertNotIn('\\', output['path'])
                package.writestr(name, (self.root / output['path']).read_bytes())
        runtime.check_apk(apk, self.root)

    def test_android_expanding_or_renaming_a_compressed_inventory_is_rejected(self):
        runtime.record(self.root)
        data = runtime.verify(self.root)
        apk = self.root / 'expanded.apk'
        with ZipFile(apk, 'w') as package:
            for name, output in data['outputs'].items():
                if not self.in_apk(name):
                    continue
                content = (self.root / output['path']).read_bytes()
                if name.endswith('.gzip'):
                    name = name.removesuffix('.gzip')
                    content = gzip.decompress(content)
                package.writestr(name, content)
        with self.assertRaisesRegex(ValueError, 'APK missing assets/desktop-removal/'):
            runtime.check_apk(apk, self.root)

    def test_malformed_removal_inventory_cannot_be_attested(self):
        path = self.root / 'app/src/main/assets/desktop-removal/kde-remove.tsv.gzip'
        path.write_bytes(b'not a gzip stream')
        with self.assertRaisesRegex(ValueError, 'Invalid desktop removal inventory'):
            runtime.record(self.root)
