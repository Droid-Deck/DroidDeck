import importlib.util
import gzip
import io
import json
from pathlib import Path, PureWindowsPath
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
from unittest import mock
from zipfile import ZipFile


spec = importlib.util.spec_from_file_location('runtime_inputs', Path(__file__).resolve().parents[1] / 'release/runtime_inputs.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RuntimeInputsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        # Component archive validation has its own binary/archive fixtures.
        component_check = patch.object(runtime, 'verify_powervr')
        component_check.start()
        self.addCleanup(component_check.stop)
        paths = runtime.outputs(self.root)
        for name in runtime.required():
            path = self.root / paths.get(name, 'app/src/main/' + name)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(gzip.compress(b'inventory') if path.suffix == '.gzip' else b'prepared runtime')
        self.source = self.root / 'tools/linuxfs/preload/test.c'
        self.source.parent.mkdir(parents=True)
        self.source.write_text('current source')
        service = self.root / 'app/src/main/assets/linuxfs/usr/local/lib/droiddeck-ubisoft/droiddeck-upc.exe'
        service.parent.mkdir(parents=True)
        service.write_bytes(b'MZ service')
        rockstar = self.root / 'app/src/main/assets/linuxfs/usr/local/lib/droiddeck-rockstar'
        rockstar.mkdir(parents=True)
        (rockstar / 'Launcher32.exe').write_bytes(b'MZ launcher 32')
        (rockstar / 'Launcher64.exe').write_bytes(b'MZ launcher 64')
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
        for name, rel in runtime.PROOT_LOADER.items():
            proot[rel] = (self.root / runtime.outputs(self.root)[name]).read_bytes()
        self.pack('proot', proot)
        self.pack('butler', {'butler': b'butler', '7z.so': b'7z', 'libc7zip.so': b'c7zip', 'version': b'15.32.0'})
        for name in ('pico-neo3-controllers', 'pico-4-controllers', 'pico-4u-controllers'):
            self.pack(name, {'LICENSE.txt': b'MIT'})
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

    def test_a_stale_proot_loader_in_the_pack_is_rejected(self):
        apk = self.current_apk(self.root / 'current.apk')
        loader = (self.root / 'app/src/main/jniLibs/arm64-v8a/libproot-loader.so').read_bytes()
        self.pack('proot', {'libproot.so': (self.root / 'app/src/main/jniLibs/arm64-v8a/libproot.so').read_bytes(),
                            'libproot-loader.so': loader + b'older'})
        with self.assertRaisesRegex(ValueError, 'proot is missing or has a stale libproot-loader.so'):
            runtime.check_apk(apk, self.root)

    def test_only_the_proot_loader_ships_in_the_apk(self):
        self.assertFalse(self.in_apk('lib/arm64-v8a/libproot.so'))
        self.assertTrue(self.in_apk('lib/arm64-v8a/libproot-loader.so'))
        runtime.check_apk(self.current_apk(self.root / 'current.apk'), self.root)

    def test_missing_proot_cannot_be_attested(self):
        (self.root / 'app/src/main/jniLibs/arm64-v8a/libproot.so').unlink()
        with self.assertRaisesRegex(ValueError, 'libproot.so'):
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

    def helper(self, content):
        source = self.root / 'tools/linuxfs/overlay/usr/local/bin/droiddeck-test-helper'
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text(content)
        staged = self.root / 'app/src/main/assets/linuxfs/usr/local/bin/droiddeck-test-helper'
        staged.parent.mkdir(parents=True, exist_ok=True)
        staged.write_text(content)
        return source, staged

    def test_an_edited_helper_script_needs_no_new_preparation(self):
        self.helper('old helper')
        runtime.record(self.root)
        self.helper('new helper')
        runtime.verify(self.root)

    def test_a_staged_helper_that_lags_its_source_is_rejected(self):
        source, _ = self.helper('old helper')
        runtime.record(self.root)
        source.write_text('new helper')
        with self.assertRaisesRegex(ValueError, 'not a current copy'):
            runtime.verify(self.root, stale_sources_ok=True)

    def test_a_manifest_that_hashed_helper_scripts_still_verifies(self):
        source, staged = self.helper('old helper')
        runtime.record(self.root)
        manifest = self.root / runtime.MANIFEST
        data = json.loads(manifest.read_text())
        data['sources']['tools/linuxfs/overlay/usr/local/bin/droiddeck-test-helper'] = 'old'
        data['outputs']['assets/linuxfs/usr/local/bin/droiddeck-test-helper'] = {
            'path': 'app/src/main/assets/linuxfs/usr/local/bin/droiddeck-test-helper', 'sha256': 'old'}
        manifest.write_text(json.dumps(data))
        self.helper('new helper')
        runtime.verify(self.root)

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

    def test_changed_independent_kwin_source_rejects_old_outputs(self):
        for name in ['tools/ubuntu/kwin-independent.cpp', 'tools/ubuntu/kwin-independent.py',
                     'tools/ubuntu/kwin-output-layout.h', 'tools/ubuntu/kwin-passthrough.cpp',
                     'tools/ubuntu/kwin-ahb-output.h', 'tools/ubuntu/kwin-output-opacity.h',
                     'tools/ubuntu/kwin-independent-filter.h',
                     'tools/ubuntu/patches/kwin/0001-respect-nested-desktop-scale.patch',
                     'app/src/main/cpp/waylandcomp/src/ahb_broker_protocol.h',
                     'tools/ubuntu/build-kwin-runtime.sh', 'tools/ubuntu/build_kwin_windows.py']:
            with self.subTest(source=name):
                source = self.root / name
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text('current source')
                runtime.record(self.root)
                source.write_text('changed source')
                with self.assertRaisesRegex(ValueError, 'sources changed'):
                    runtime.verify(self.root)

    def test_changed_vr_wayland_header_rejects_old_outputs(self):
        source = self.root / 'tools/vr/third_party/wayland/wayland-client-core.h'
        source.parent.mkdir(parents=True)
        source.write_text('current header')
        runtime.record(self.root)
        source.write_text('changed header')
        with self.assertRaisesRegex(ValueError, 'sources changed'):
            runtime.verify(self.root)

    def test_changed_store_launcher_or_cipher_rejects_old_outputs(self):
        for name in ['tools/ubisoft-launcher/src/main.rs', 'tools/rockstar-launcher/src/main.rs',
                     'app/src/main/resources/rockstar/legacy-cipher.bin']:
            with self.subTest(source=name):
                source = self.root / name
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_bytes(b'current source')
                runtime.record(self.root)
                source.write_bytes(b'changed source')
                with self.assertRaisesRegex(ValueError, 'sources changed'):
                    runtime.verify(self.root)

    def test_missing_independent_kwin_input_cannot_be_attested(self):
        for name in ['libkwin.so.6', 'kwin.sha256', 'kwin_wayland', 'LICENSE', 'build.json']:
            with self.subTest(input=name):
                asset = 'assets/linuxfs/usr/local/lib/droiddeck-kwin/' + name
                path = self.root / 'app/src/main' / asset
                content = path.read_bytes()
                path.unlink()
                try:
                    with self.assertRaisesRegex(ValueError, 'Missing runtime inputs') as error:
                        runtime.record(self.root)
                    self.assertIn(asset, str(error.exception))
                finally:
                    path.write_bytes(content)

    def test_missing_xr_or_native_hook_input_cannot_be_attested(self):
        for name in ['libddnatv-hook.so',
                     'usr/local/lib/droiddeck-vr/droiddeck/bin/linuxarm64/xr-display-mode',
                     'usr/local/lib/droiddeck-vr/layer/libdroiddeck_vr_scheduling.so']:
            with self.subTest(input=name):
                asset = 'assets/linuxfs/' + name
                path = self.root / 'app/src/main' / asset
                content = path.read_bytes()
                path.unlink()
                try:
                    with self.assertRaisesRegex(ValueError, 'Missing runtime inputs') as error:
                        runtime.record(self.root)
                    self.assertIn(asset, str(error.exception))
                finally:
                    path.write_bytes(content)

    def test_cargo_output_beside_a_windows_helper_is_not_a_source(self):
        crate = self.root / 'tools/epic-launcher'
        (crate / 'src').mkdir(parents=True)
        (crate / 'Cargo.toml').write_text('[package]\n')
        (crate / 'src/lib.rs').write_text('old')
        runtime.record(self.root)
        (crate / 'target/debug').mkdir(parents=True)
        (crate / 'target/debug/build.log').write_text('local cargo test')
        runtime.verify(self.root)
        (crate / 'src/lib.rs').write_text('new')
        with self.assertRaisesRegex(ValueError, 'sources changed'):
            runtime.verify(self.root)

    def test_missing_quest_driver_cannot_be_attested(self):
        (self.root / 'app/build/prepared-assets/graphics_driver/adrenotools-turnip25.1.0-quest1.tzst').unlink()
        with self.assertRaisesRegex(ValueError, 'quest1.tzst'):
            runtime.record(self.root)

    def test_changed_powervr_wrapper_rejects_old_outputs(self):
        wrapper = self.root / 'tools/hybris/run-proton.py'
        wrapper.parent.mkdir(parents=True)
        wrapper.write_text('current wrapper')
        runtime.record(self.root)
        wrapper.write_text('changed wrapper')
        with self.assertRaisesRegex(ValueError, 'sources changed'):
            runtime.verify(self.root)

    def test_missing_powervr_bundle_cannot_be_attested(self):
        (self.root / 'app/src/main/assets/hybris/runtime.tzst').unlink()
        with self.assertRaisesRegex(ValueError, 'assets/hybris/runtime.tzst'):
            runtime.record(self.root)

    def test_modified_prebuilt_binary_is_rejected(self):
        runtime.record(self.root)
        (self.root / 'app/src/main/jniLibs/arm64-v8a/libproot.so').write_bytes(b'old APK binary')
        with self.assertRaisesRegex(ValueError, 'changed runtime input'):
            runtime.verify(self.root)

    def test_imported_store_library_is_attested_and_checked_in_the_apk(self):
        stores = self.root / 'app/src/main/jniLibs/arm64-v8a/libdroiddeckstores.so'
        stores.write_bytes(b'verified store engine')
        apk = self.current_apk(self.root / 'current.apk')
        runtime.check_apk(apk, self.root)
        stores.write_bytes(b'changed store engine')
        with self.assertRaisesRegex(ValueError, 'changed runtime input'):
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

    def test_complete_current_payload_passes(self):
        runtime.record(self.root)
        data = runtime.verify(self.root)
        apk = self.root / 'current.apk'
        with ZipFile(apk, 'w') as package:
            for name, output in data['outputs'].items():
                if self.in_apk(name):
                    package.writestr(name, (self.root / output['path']).read_bytes())
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

    def test_an_apk_still_carrying_the_ubisoft_service_is_rejected(self):
        name = 'assets/linuxfs/usr/local/lib/droiddeck-ubisoft/droiddeck-upc.exe'
        self.assertIn(name, runtime.outputs(self.root))
        apk = self.current_apk(self.root / 'bundled.apk', {name: b'MZ service'})
        with self.assertRaisesRegex(ValueError, 'ubisoft pack provides'):
            runtime.check_apk(apk, self.root)

    def test_an_apk_still_carrying_withdrawn_mesa_is_rejected(self):
        apk = self.current_apk(self.root / 'bundled.apk',
                               {'assets/linuxfs/usr/local/lib/droiddeck-mesa/libvulkan.so.1': b'withdrawn Mesa'})
        with self.assertRaisesRegex(ValueError, 'withdrawn desktop Mesa replacement'):
            runtime.check_apk(apk, self.root)

    def test_an_apk_still_carrying_the_rockstar_service_is_rejected(self):
        name = 'assets/linuxfs/usr/local/lib/droiddeck-rockstar/Launcher32.exe'
        self.assertIn(name, runtime.outputs(self.root))
        apk = self.current_apk(self.root / 'bundled.apk', {name: b'MZ launcher 32'})
        with self.assertRaisesRegex(ValueError, 'rockstar pack provides'):
            runtime.check_apk(apk, self.root)

    def test_a_packed_file_the_manifest_does_not_list_is_rejected(self):
        name = 'assets/linuxfs/usr/local/lib/droiddeck-rockstar/Unlisted.bin'
        self.assertNotIn(name, runtime.outputs(self.root))
        apk = self.current_apk(self.root / 'bundled.apk', {name: b'helper data'})
        with self.assertRaisesRegex(ValueError, 'Unlisted.bin, which the rockstar pack provides'):
            runtime.check_apk(apk, self.root)

    def test_a_stale_rockstar_pack_is_rejected(self):
        apk = self.current_apk(self.root / 'current.apk')
        self.pack('rockstar', {'Launcher32.exe': b'MZ older launcher', 'Launcher64.exe': b'MZ launcher 64'})
        with self.assertRaisesRegex(ValueError, 'rockstar is missing or has a stale'):
            runtime.check_apk(apk, self.root)

    def test_an_apk_carrying_any_windows_executable_is_rejected(self):
        for name in ('assets/linuxfs/usr/local/lib/new-helper/helper.exe', 'assets/anything/renamed.bin', 'res/raw/x.dll'):
            with self.subTest(name=name):
                apk = self.current_apk(self.root / 'windows.apk', {name: b'MZ helper'})
                with self.assertRaisesRegex(ValueError, 'Windows executables.*' + name):
                    runtime.check_apk(apk, self.root)

    def test_an_apk_still_carrying_the_dirt_stand_in_is_rejected(self):
        dirt = self.root / 'app/src/main/assets/linuxfs/usr/local/lib/droiddeck-dirt'
        dirt.mkdir(parents=True)
        (dirt / 'ws2_32.dll').write_bytes(b'MZ droiddeck-dirt-ws2_32')
        self.packs()
        name = 'assets/linuxfs/usr/local/lib/droiddeck-dirt/ws2_32.dll'
        self.assertIn(name, runtime.outputs(self.root))
        self.assertEqual('dirt', runtime.PACKED['assets/linuxfs/usr/local/lib/droiddeck-dirt/'])
        apk = self.current_apk(self.root / 'bundled.apk', {name: b'MZ droiddeck-dirt-ws2_32'})
        with self.assertRaisesRegex(ValueError, 'dirt pack provides'):
            runtime.check_apk(apk, self.root)
        runtime.check_apk(self.current_apk(self.root / 'current.apk'), self.root)

    def test_pico_geometry_and_local_model_packs_are_excluded_from_the_apk(self):
        for name in ('assets/linuxfs/usr/local/lib/droiddeck-vr/droiddeck/resources/rendermodels/droiddeck_pico_4_left/body.obj',
                     'assets/packs/pico-4-controllers.tar.zst'):
            with self.subTest(name=name):
                apk = self.current_apk(self.root / 'bundled.apk', {name: b'controller'})
                with self.assertRaisesRegex(ValueError, 'PICO controller model assets'):
                    runtime.check_apk(apk, self.root)

    def test_old_staged_pico_geometry_is_not_a_runtime_input(self):
        source = self.root / 'app/src/main/assets/linuxfs/usr/local/lib/droiddeck-vr/droiddeck/resources/rendermodels/droiddeck_pico_4_left/body.obj'
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_bytes(b'old controller')
        self.assertFalse(any('droiddeck_pico_' in name for name in runtime.outputs(self.root)))
    def test_an_apk_still_carrying_the_epic_parent_stand_in_is_rejected(self):
        epic = self.root / 'app/src/main/assets/linuxfs/usr/local/lib/droiddeck-epic'
        epic.mkdir(parents=True)
        (epic / 'EpicGamesLauncher.exe').write_bytes(b'MZ parent')
        self.packs()
        name = 'assets/linuxfs/usr/local/lib/droiddeck-epic/EpicGamesLauncher.exe'
        self.assertIn(name, runtime.outputs(self.root))
        apk = self.current_apk(self.root / 'bundled.apk', {name: b'MZ parent'})
        with self.assertRaisesRegex(ValueError, 'epic pack provides'):
            runtime.check_apk(apk, self.root)
        runtime.check_apk(self.current_apk(self.root / 'current.apk'), self.root)
        self.pack('epic', {'EpicGamesLauncher.exe': b'MZ older parent'})
        with self.assertRaisesRegex(ValueError, 'epic is missing or has a stale'):
            runtime.check_apk(self.current_apk(self.root / 'current.apk'), self.root)
    def test_an_apk_still_carrying_the_powervr_runtime_is_rejected(self):
        apk = self.current_apk(self.root / 'bundled.apk', {'assets/hybris/runtime.tzst': b'runtime'})
        with self.assertRaisesRegex(ValueError, 'powervr pack provides'):
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
