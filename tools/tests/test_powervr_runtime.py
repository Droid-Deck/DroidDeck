import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile, ZipInfo


spec = importlib.util.spec_from_file_location('powervr_fetch',
        Path(__file__).resolve().parents[1] / 'powervr/fetch.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


def elf():
    content = bytearray(64)
    content[:6], content[18:20] = b'\x7fELF\x02\x01', b'\xb7\x00'
    return bytes(content)


def pe(architecture):
    content = bytearray(128)
    content[:2], content[60:64], content[64:68] = b'MZ', (64).to_bytes(4, 'little'), b'PE\0\0'
    content[68:70] = b'\x4c\x01' if architecture == 'x32' else b'\x64\x86'
    content[88:90] = b'\x0b\x01' if architecture == 'x32' else b'\x0b\x02'
    return bytes(content)


def tar(entries):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode='w') as archive:
        for name, kind, content in entries:
            info = tarfile.TarInfo(name)
            info.type = kind
            if kind in (tarfile.SYMTYPE, tarfile.LNKTYPE):
                info.linkname = content
                archive.addfile(info)
            elif kind == tarfile.REGTYPE:
                info.size = len(content)
                archive.addfile(info, io.BytesIO(content))
            else:
                archive.addfile(info)
    return subprocess.run(['zstd', '-q', '-c'], input=stream.getvalue(),
                          capture_output=True, check=True).stdout


def tar_gz(entries):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode='w:gz') as archive:
        for name, kind, content in entries:
            info = tarfile.TarInfo(name)
            info.type = kind
            if kind == tarfile.SYMTYPE:
                info.linkname = content
                archive.addfile(info)
            elif kind == tarfile.REGTYPE:
                info.size = len(content)
                archive.addfile(info, io.BytesIO(content))
            else:
                archive.addfile(info)
    return stream.getvalue()


def upstream_dxvk(version, architecture_of=lambda arch: arch):
    return {'dxvk-%s/%s/%s' % (version, arch, name): pe(architecture_of(arch))
            for arch in ('x32', 'x64')
            for name in ('d3d8.dll', 'd3d9.dll', 'd3d10core.dll', 'd3d11.dll', 'dxgi.dll')}


class PowerVRRuntimeTest(unittest.TestCase):
    def test_new_hybris_patch_requires_a_rebuilt_component_pin(self):
        patch = self.root / 'tools/hybris/patches/0002-vendor.patch'
        patch.parent.mkdir(parents=True)
        patch.write_text('a new native fix')
        with self.assertRaisesRegex(ValueError, 'patch stack changed'):
            runtime.load_release(self.root)

    def test_removed_hybris_patch_requires_a_new_component_pin(self):
        self.release['hybris']['recipe_inputs']['tools/hybris/patches/removed.patch'] = '0' * 64
        (self.root / 'tools/powervr/release.json').write_text(json.dumps(self.release))
        with self.assertRaisesRegex(ValueError, 'patch stack changed'):
            runtime.load_release(self.root)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.cache = self.root / 'cache'
        self.cache.mkdir()
        for name, content in {
            'tools/powervr/fetch.py': b'fixture assembler',
            'tools/hybris/run-proton.py': b'print("fixture Proton wrapper")\n',
            'tools/hybris/source.env': b'HYBRIS_REVISION=fixture\n',
            'tools/vulkan-wsi/licenses/Vulkan-Headers/LICENSE.md': b'Khronos headers attribution\n',
            'tools/vulkan-wsi/licenses/Vulkan-Headers/MIT.txt': b'MIT license fixture\n',
        }.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        bridge = {name: ('file', 0o755, elf()) for name in runtime.REQUIRED_LIBRARIES if name.startswith('lib/')}
        bridge[runtime.PLACED_MANIFEST] = ('file', 0o644, runtime.json_bytes({'layer': {
            'name': 'VK_LAYER_DROIDDECK_placed_memory', 'type': 'GLOBAL',
            'library_path': './libVkLayer_DROIDDECK_placed_memory.so'}}))
        bridge['lib/libhybris-common.so.1.0.0'] = bridge.pop('lib/libhybris-common.so.1')
        bridge['lib/libhybris-common.so.1'] = ('symlink', 0o777, 'libhybris-common.so.1.0.0')
        data = runtime.pack(bridge)
        self.release = {'format': 1, 'hybris': self.component('bridge.tzst', data), 'archives': []}
        self.release['hybris'].update({
            'tree_sha256': runtime.tree_digest(bridge),
            'recipe_inputs': {'tools/hybris/source.env': runtime.sha256((self.root / 'tools/hybris/source.env').read_bytes())}})
        files = {'dxvk/' + arch + '/' + name: pe(arch)
                 for arch in ('x32', 'x64')
                 for name in ('d3d8.dll', 'd3d9.dll', 'd3d10core.dll', 'd3d11.dll', 'dxgi.dll')}
        files['wsi/libVkLayer_window_system_integration.so'] = elf()
        stream = io.BytesIO()
        with ZipFile(stream, 'w') as archive:
            for name, content in files.items():
                archive.writestr(name, content)
        component = self.component('graphics.zip', stream.getvalue())
        component.update({'ignored': [], 'files': [
            {'name': name, 'path': name, 'size': len(content), 'sha256': runtime.sha256(content), 'mode': 0o644}
            for name, content in files.items()]})
        self.release['archives'].append(component)
        self.pins = self.root / 'tools/powervr/release.json'
        self.pins.write_bytes(runtime.json_bytes(self.release))

    def component(self, name, data):
        component = {'url': 'https://example.invalid/' + name, 'size': len(data),
                     'sha256': runtime.sha256(data), 'recipe_inputs': {}}
        (self.cache / (component['sha256'] + '-' + name)).write_bytes(data)
        return component

    def add_upstream_dxvk(self, files):
        top = next(iter(files)).split('/')[0]
        data = tar_gz([(top, tarfile.DIRTYPE, None)] +
                      [(name, tarfile.REGTYPE, content) for name, content in files.items()])
        component = self.component('dxvk-upstream.tar.gz', data)
        component.update({'format': 'tar.gz', 'ignored': [], 'files': [
            {'name': name, 'path': name, 'size': len(content), 'sha256': runtime.sha256(content), 'mode': 0o755}
            for name, content in files.items()]})
        self.release['archives'].append(component)
        self.pins.write_bytes(runtime.json_bytes(self.release))
        return component

    def assembled(self):
        runtime.assemble(self.root, self.cache)
        return runtime.read_tar((self.root / runtime.ASSETS / 'runtime.tzst').read_bytes())

    def test_complete_payload_preserves_symlinks_and_is_reproducible(self):
        self.assembled()
        first = (self.root / runtime.ASSETS / 'runtime.tzst').read_bytes()
        runtime.verify(self.root)
        self.assembled()
        self.assertEqual(first, (self.root / runtime.ASSETS / 'runtime.tzst').read_bytes())
        self.assertEqual(runtime.read_tar(first)['lib/libhybris-common.so.1'][0], 'symlink')

    def test_upstream_tarball_adds_a_second_dxvk_payload(self):
        files = upstream_dxvk('2.6.2')
        self.add_upstream_dxvk(files)
        entries = self.assembled()
        runtime.verify(self.root)
        for name, content in files.items():
            self.assertEqual(entries[name], ('file', 0o755, content))
        self.assertIn('dxvk/x64/d3d9.dll', entries)

    def add_mali_payload(self, plain_arm64=False, missing=None):
        if __package__:
            from .test_proton_wrapper import pe_fixture, plain_arm64_fixture
        else:
            from test_proton_wrapper import pe_fixture, plain_arm64_fixture
        (self.root / "tools/hybris/run-proton.py").write_bytes((runtime.ROOT / "tools/hybris/run-proton.py").read_bytes())
        files = {}
        for arch, machine in (("i386-windows", 0x14c), ("x86_64-windows", 0x8664), ("aarch64-windows", 0xaa64)):
            for name in ("d3d8.dll", "d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll"):
                path = "dxvk-mali/" + arch + "/" + name
                if path == missing:
                    continue
                files[path] = (plain_arm64_fixture() if plain_arm64 and arch == "aarch64-windows"
                               else pe_fixture(machine, arm64x=arch == "aarch64-windows"))
        self.add_upstream_dxvk(files)
        return files

    def test_mali_bundle_preserves_all_three_architectures(self):
        files = self.add_mali_payload()
        entries = self.assembled()
        runtime.verify(self.root)
        for name, data in files.items():
            self.assertEqual(entries[name], ("file", 0o755, data))

    def test_mali_bundle_rejects_plain_arm64_even_with_matching_pins(self):
        self.add_mali_payload(plain_arm64=True)
        with self.assertRaisesRegex(ValueError, "plain ARM64"):
            self.assembled()

    def test_mali_bundle_rejects_missing_native_architecture_file(self):
        self.add_mali_payload(missing="dxvk-mali/aarch64-windows/dxgi.dll")
        with self.assertRaisesRegex(ValueError, "missing runtime file"):
            self.assembled()

    def test_second_dxvk_payload_with_wrong_pe_architecture_is_rejected(self):
        self.add_upstream_dxvk(upstream_dxvk('2.6.2', lambda arch: 'x64' if arch == 'x32' else 'x32'))
        with self.assertRaisesRegex(ValueError, 'wrong PE architecture: dxvk-2.6.2/'):
            self.assembled()

    def test_tar_gz_rejects_symlinks_unknown_members_and_changed_files(self):
        pin = {'name': 'dxvk-1/x64/d3d9.dll', 'path': 'dxvk-1/x64/d3d9.dll', 'size': 4,
               'sha256': runtime.sha256(b'good'), 'mode': 0o755}
        component = {'files': [pin], 'ignored': []}
        cases = [
            [('dxvk-1/x64/d3d9.dll', tarfile.SYMTYPE, '../../outside')],
            [('dxvk-1/x64/d3d9.dll', tarfile.REGTYPE, b'good'), ('dxvk-1/extra.dll', tarfile.REGTYPE, b'x')],
            [('dxvk-1/x64/d3d9.dll', tarfile.REGTYPE, b'evil')],
            [],
        ]
        for entries in cases:
            with self.subTest(entries=[name for name, _, _ in entries]), self.assertRaises(ValueError):
                runtime.read_tar_gz(tar_gz(entries), component)
        self.assertEqual(runtime.read_tar_gz(tar_gz([('dxvk-1/x64/d3d9.dll', tarfile.REGTYPE, b'good')]), component),
                         {'dxvk-1/x64/d3d9.dll': ('file', 0o755, b'good')})
        with self.assertRaisesRegex(ValueError, 'unsupported component archive format'):
            runtime.read_component(b'', {'format': 'rar'})

    def test_stale_wrapper_cannot_pass_with_an_updated_archive_checksum(self):
        self.assembled()
        (self.root / 'tools/hybris/run-proton.py').write_text('print("new wrapper")\n')
        with self.assertRaisesRegex(ValueError, 'assembly inputs changed'):
            runtime.verify(self.root)

    def test_header_licenses_are_bundled_verbatim(self):
        entries = self.assembled()
        for source, target in runtime.BUNDLED_LICENSES.items():
            self.assertEqual(runtime.read(entries, target), (self.root / source).read_bytes())

    def test_missing_or_modified_header_license_is_rejected(self):
        original = self.assembled()
        target = 'licenses/Vulkan-Headers/MIT.txt'
        for replacement in (None, ('file', 0o644, b'stale license')):
            with self.subTest(replacement=replacement):
                entries = dict(original)
                if replacement is None:
                    del entries[target]
                else:
                    entries[target] = replacement
                with self.assertRaisesRegex(ValueError, 'license missing or stale'):
                    runtime.check_bundle(entries, self.release, self.root)

    def test_changed_tracked_header_license_rejects_old_bundle(self):
        self.assembled()
        source = next(iter(runtime.BUNDLED_LICENSES))
        (self.root / source).write_bytes(b'changed license text')
        with self.assertRaisesRegex(ValueError, 'assembly inputs changed'):
            runtime.verify(self.root)

    def test_changed_component_recipe_requires_new_release_pin(self):
        self.assembled()
        (self.root / 'tools/hybris/source.env').write_text('HYBRIS_REVISION=new-source\n')
        with self.assertRaisesRegex(ValueError, 'recipe changed'):
            runtime.verify(self.root)

    def test_old_dxvk_cannot_pass_by_updating_only_the_bundle_checksum(self):
        entries = self.assembled()
        entries['dxvk/x64/d3d11.dll'] = ('file', 0o644, pe('x64') + b'old DLL')
        archive = runtime.pack(entries)
        with self.assertRaisesRegex(ValueError, 'component mismatch'):
            runtime.verify_archive(archive, runtime.sha256(archive), self.root)

    def test_wrong_pe_architecture_rejected_even_with_matching_member_pin(self):
        entries = self.assembled()
        name = 'dxvk/x32/d3d11.dll'
        entries[name] = ('file', 0o644, pe('x64'))
        pin = next(item for item in self.release['archives'][0]['files'] if item['path'] == name)
        pin['sha256'] = runtime.sha256(pe('x64'))
        with self.assertRaisesRegex(ValueError, 'wrong PE architecture'):
            runtime.check_bundle(entries, self.release, self.root)

    def test_unpinned_bridge_file_is_rejected(self):
        entries = self.assembled()
        entries['lib/extra-loader.so'] = ('file', 0o755, elf())
        with self.assertRaisesRegex(ValueError, 'bridge differs'):
            runtime.check_bundle(entries, self.release, self.root)

    def test_failed_download_leaves_the_previous_payload_ready(self):
        self.assembled()
        before = (self.root / runtime.ASSETS / 'version.txt').read_bytes()
        component = self.release['archives'][0]
        (self.cache / (component['sha256'] + '-graphics.zip')).write_bytes(b'bad cache')
        with patch.object(runtime, 'urlopen', return_value=io.BytesIO(b'corrupt response')):
            with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                runtime.assemble(self.root, self.cache)
        self.assertEqual(before, (self.root / runtime.ASSETS / 'version.txt').read_bytes())
        runtime.verify(self.root)

    def test_tar_rejects_traversal_duplicate_special_files_and_symlink_escape(self):
        cases = [
            [('../escape', tarfile.REGTYPE, b'x')],
            [('file', tarfile.REGTYPE, b'x'), ('./file', tarfile.REGTYPE, b'y')],
            [('device', tarfile.CHRTYPE, b'')],
            [('file', tarfile.LNKTYPE, 'other')],
            [('lib/link', tarfile.SYMTYPE, '../../outside')],
            [('lib/link', tarfile.SYMTYPE, '/absolute')],
            [('lib/a', tarfile.SYMTYPE, 'b'), ('lib/b', tarfile.SYMTYPE, 'a')],
            [('lib', tarfile.SYMTYPE, 'target'), ('target', tarfile.REGTYPE, b'x'),
             ('lib/child', tarfile.REGTYPE, b'x')],
        ]
        for members in cases:
            with self.subTest(members=members):
                with self.assertRaises(ValueError):
                    runtime.read_tar(tar(members))

    def test_relative_symlink_that_stays_inside_runtime_is_preserved(self):
        entries = runtime.read_tar(tar([
            ('lib/a', tarfile.REGTYPE, b'library'), ('lib/child/link', tarfile.SYMTYPE, '../a')]))
        self.assertEqual(runtime.read(entries, 'lib/child/link'), b'library')

    def test_zip_rejects_symlink_even_if_name_is_allowed(self):
        stream = io.BytesIO()
        with ZipFile(stream, 'w') as archive:
            info = ZipInfo('allowed')
            info.create_system = 3
            info.external_attr = 0o120777 << 16
            archive.writestr(info, '../outside')
        with self.assertRaisesRegex(ValueError, 'unexpected component archive entry'):
            runtime.read_zip(stream.getvalue(), {'files': [], 'ignored': ['allowed']})

    def test_zip_rejects_unknown_and_duplicate_members(self):
        for names in (['unexpected'], ['allowed', 'allowed'], ['../escape']):
            with self.subTest(names=names):
                stream = io.BytesIO()
                with ZipFile(stream, 'w') as archive:
                    for name in names:
                        archive.writestr(name, b'file')
                with self.assertRaises(ValueError):
                    runtime.read_zip(stream.getvalue(), {'files': [], 'ignored': ['allowed']})


if __name__ == '__main__':
    unittest.main()
