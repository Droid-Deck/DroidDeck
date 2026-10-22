import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import stat
import tempfile
import unittest
from unittest import mock
import zipfile

spec = importlib.util.spec_from_file_location(
    'prepare_a850_driver_bundle', Path(__file__).resolve().parents[1] / 'prepare_a850_driver_bundle.py')
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class A850BundleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.android = self.root / 'android.zip'
        self.linux = self.root / 'linux.zip'
        self.output = self.root / 'bundle.zip'
        self.android_files = self.files('android')
        self.linux_files = self.files('linux')
        self.write(self.android, self.android_files)
        self.write(self.linux, self.linux_files)

    def files(self, platform):
        elf = bytearray(64)
        elf[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<H', elf, 18, 183)
        elf += b'libc.so.6\x00' if platform == 'linux' else b'libc.so\x00'
        meta = ({'libc': 'glibc', 'kind': 'linux-vulkan-icd'} if platform == 'linux'
                else {'libraryName': prepare.LIBRARY})
        files = {'meta.json': json.dumps(meta).encode(), prepare.LIBRARY: bytes(elf)}
        if platform == 'linux':
            files['freedreno_icd.aarch64.json'] = json.dumps({'ICD': {'library_path': './' + prepare.LIBRARY}}).encode()
        return files

    def write(self, path, files):
        with zipfile.ZipFile(path, 'w') as archive:
            for name, data in files.items():
                archive.writestr(name, data)

    def pinned_fixture(self):
        return mock.patch.multiple(prepare,
            ANDROID_SHA256=hashlib.sha256(self.android.read_bytes()).hexdigest(),
            LINUX_SHA256=hashlib.sha256(self.linux.read_bytes()).hexdigest())

    def test_manifest_roundtrip_preserves_both_original_halves_and_is_deterministic(self):
        with self.pinned_fixture():
            prepare.prepare_bundle(self.android, self.linux, self.output)
            first = self.output.read_bytes()
            prepare.prepare_bundle(self.android, self.linux, self.output)
        self.assertEqual(first, self.output.read_bytes())
        with zipfile.ZipFile(self.output) as archive:
            manifest = json.loads(archive.read('manifest.json'))
            self.assertEqual('Adreno 850 community experimental', manifest['package'])
            self.assertEqual('20261012-a850exp1', manifest['version'])
            self.assertEqual(manifest['version'], manifest['tag'])
            for platform, originals in (('android', self.android_files), ('linux', self.linux_files)):
                directory = manifest['drivers'][platform]['path']
                self.assertEqual(platform, directory)
                for name, data in originals.items():
                    self.assertEqual(data, archive.read(directory + '/' + name))
            self.assertIn(b'not native Adreno 850 support', archive.read('README.md'))
            self.assertIn(b'issues/342', archive.read('NOTICE'))

    def test_hash_mismatch_refuses_and_preserves_old_output(self):
        self.output.write_bytes(b'previous bundle')
        with self.assertRaisesRegex(ValueError, 'SHA256 mismatch'):
            prepare.prepare_bundle(self.android, self.linux, self.output)
        self.assertEqual(b'previous bundle', self.output.read_bytes())

    def test_unsafe_extra_and_missing_entries_refused_before_output_write(self):
        for entry in ('../escape', '/absolute', 'sub/../meta.json', 'sub\\meta.json', 'extra.so', None):
            with self.subTest(entry=entry):
                files = self.android_files.copy()
                if entry is None:
                    files.pop(prepare.LIBRARY)
                else:
                    files[entry] = b'unexpected'
                self.write(self.android, files)
                self.output.write_bytes(b'old')
                with self.pinned_fixture(), self.assertRaisesRegex(ValueError, 'unsafe, extra or missing'):
                    prepare.prepare_bundle(self.android, self.linux, self.output)
                self.assertEqual(b'old', self.output.read_bytes())

    def test_bad_linux_half_does_not_replace_output(self):
        self.linux_files['meta.json'] = b'{"libc":"bionic","kind":"linux-vulkan-icd"}'
        self.write(self.linux, self.linux_files)
        self.output.write_bytes(b'old')
        with self.pinned_fixture(), self.assertRaisesRegex(ValueError, 'libc/kind'):
            prepare.prepare_bundle(self.android, self.linux, self.output)
        self.assertEqual(b'old', self.output.read_bytes())

    def test_bad_android_library_name_refused(self):
        self.android_files['meta.json'] = b'{"libraryName":"../evil.so"}'
        self.write(self.android, self.android_files)
        with self.pinned_fixture(), self.assertRaisesRegex(ValueError, 'libraryName'):
            prepare.prepare_bundle(self.android, self.linux, self.output)

    def test_wrong_elf_class_endian_architecture_and_libc_refused(self):
        original = self.linux_files[prepare.LIBRARY]
        for offset, replacement in ((4, 1), (5, 2), (18, 62)):
            elf = bytearray(original)
            elf[offset] = replacement
            with self.subTest(offset=offset), self.assertRaisesRegex(ValueError, 'ELF64'):
                prepare.validate_elf(elf, 'linux')
        with self.assertRaisesRegex(ValueError, 'glibc'):
            prepare.validate_elf(self.android_files[prepare.LIBRARY], 'linux')
        with self.assertRaisesRegex(ValueError, 'AdrenoTools'):
            prepare.validate_elf(original, 'android')

    def test_output_cannot_overwrite_either_input_or_alias(self):
        for source in (self.android, self.linux):
            with self.subTest(source=source), self.assertRaisesRegex(ValueError, 'differ'):
                prepare.prepare_bundle(self.android, self.linux, source)
        alias = self.root / 'alias.zip'
        alias.symlink_to(self.android)
        with self.assertRaisesRegex(ValueError, 'differ'):
            prepare.prepare_bundle(self.android, self.linux, alias)

    def test_duplicate_and_symlink_entries_refused(self):
        for duplicate in (True, False):
            with self.subTest(duplicate=duplicate):
                with zipfile.ZipFile(self.android, 'w') as archive:
                    for name, data in self.android_files.items():
                        info = zipfile.ZipInfo(name)
                        if not duplicate and name == prepare.LIBRARY:
                            info.create_system = 3
                            info.external_attr = (stat.S_IFLNK | 0o777) << 16
                        archive.writestr(info, data)
                    if duplicate:
                        import warnings
                        with warnings.catch_warnings():
                            warnings.simplefilter('ignore', UserWarning)
                            archive.writestr('meta.json', b'{}')
                with self.pinned_fixture(), self.assertRaises(ValueError):
                    prepare.prepare_bundle(self.android, self.linux, self.output)
                self.assertFalse(self.output.exists())

    def test_malformed_icd_refused(self):
        for icd in ({'ICD': []}, {'ICD': {'library_path': '../evil.so'}}):
            with self.subTest(icd=icd):
                self.linux_files['freedreno_icd.aarch64.json'] = json.dumps(icd).encode()
                self.write(self.linux, self.linux_files)
                with self.pinned_fixture(), self.assertRaisesRegex(ValueError, 'ICD library_path'):
                    prepare.prepare_bundle(self.android, self.linux, self.output)

    def test_failed_replace_cleans_temporary_and_preserves_previous_output(self):
        self.output.write_bytes(b'old')
        with self.pinned_fixture(), mock.patch.object(prepare.os, 'replace', side_effect=OSError('fixture failure')):
            with self.assertRaisesRegex(OSError, 'fixture failure'):
                prepare.prepare_bundle(self.android, self.linux, self.output)
        self.assertEqual(b'old', self.output.read_bytes())
        self.assertEqual([], list(self.root.glob('.bundle.zip.*')))


if __name__ == '__main__':
    unittest.main()
