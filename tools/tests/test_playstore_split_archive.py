import io
import re
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest

from tools.playstore.split_archive import combine, data_file, inventory, prepare, validate_data


class PlaySplitArchiveTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.zstd_version = re.search(r'v([0-9.]+),', subprocess.check_output(['zstd', '--version'], text=True)).group(1)

    def archive(self, entries, name='source'):
        content = io.BytesIO()
        with tarfile.open(fileobj=content, mode='w', format=tarfile.GNU_FORMAT) as archive:
            for path, body, mode in entries:
                member = tarfile.TarInfo(path)
                member.size, member.mode = len(body), mode
                member.uid, member.gid, member.mtime = 1000, 1001, 1234567
                archive.addfile(member, io.BytesIO(body))
        target = self.root / name
        target.write_bytes(subprocess.check_output(['zstd', '-19', '-T4', '-c'], input=content.getvalue()))
        return target

    def test_split_preserves_all_file_content_and_metadata_across_tar_encodings(self):
        font = 'usr/share/fonts/' + 'long-directory/' * 8 + 'example.ttf'
        source = self.archive([('usr/bin/program', b'\x7fELF' + b'code' * 100, 0o755),
                               (font, b'\x00\x01\x00\x00' + b'font' * 100, 0o755),
                               ('usr/share/config.json', b'{"enabled":true}', 0o644)])
        code, data, combined = [self.root / name for name in ['code', 'data', 'combined']]
        result = prepare(source, code, data, self.zstd_version)
        combine([code, data], combined)
        self.assertEqual(result['inventory_sha256'], inventory(combined, 'zstd')[0])
        self.assertEqual(inventory(source, 'zstd')[0], inventory(combined, 'zstd')[0])
        validate_data(data)
        raw = subprocess.check_output(['zstd', '-dc', str(combined)])
        with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
            self.assertEqual(archive.getnames(), ['usr/bin/program', 'usr/share/config.json', font])
            self.assertEqual(archive.getmember(font).mode, 0o755)

    def test_data_validation_rejects_executables_and_scripts_with_resource_names(self):
        for content in [b'\x7fELF' + b'library', b'MZ' + b'program', b'#!/bin/sh\necho bad']:
            member = tarfile.TarInfo('image.png')
            self.assertFalse(data_file(member, content))
            source = self.archive([('image.png', content, 0o644)], name=str(len(content)))
            with self.assertRaisesRegex(ValueError, 'unclassified'):
                validate_data(source)

    def test_missing_assets_do_not_silently_produce_an_empty_pack(self):
        source = self.archive([('program', b'\x7fELFcode', 0o755)])
        with self.assertRaisesRegex(ValueError, 'no classified'):
            prepare(source, self.root / 'code', self.root / 'data', self.zstd_version)

    def test_archive_paths_cannot_escape_the_runtime(self):
        source = self.archive([('../font.ttf', b'\x00\x01\x00\x00font', 0o644)])
        with self.assertRaisesRegex(ValueError, 'unsafe'):
            inventory(source, 'zstd')
