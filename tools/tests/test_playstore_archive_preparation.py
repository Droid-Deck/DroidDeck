import gzip
import hashlib
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

from tools.playstore.prepare_archive import prepare


@unittest.skipUnless(shutil.which('zstd'), 'zstd is unavailable')
class PlayArchivePreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.content = b'archive bytes including headers and file metadata\x00' * 1024
        self.source = self.root / 'original.tar.gz'
        self.source.write_bytes(gzip.compress(self.content))
        version = re.search(r'v([0-9]+\.[0-9]+\.[0-9]+)', subprocess.check_output(['zstd', '--version'], text=True)).group(1)
        self.definition = {'method': 'gzip-to-zstd-19', 'zstd_version': version,
                           'tar_sha256': hashlib.sha256(self.content).hexdigest(), 'tar_size': len(self.content)}

    def test_recompression_preserves_every_original_archive_byte(self):
        output = self.root / 'prepared.tar.zst'
        prepare(self.source, output, self.definition)
        self.assertEqual(self.content, subprocess.check_output(['zstd', '-dc', str(output)]))
        self.assertEqual(self.content, gzip.decompress(self.source.read_bytes()))

    def test_changed_archive_content_cannot_pass_the_pinned_identity(self):
        self.definition['tar_sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'tar content'):
            prepare(self.source, self.root / 'bad.tar.zst', self.definition)

    def test_decompression_cannot_exceed_its_expected_size(self):
        self.definition['tar_size'] -= 1
        with self.assertRaisesRegex(ValueError, 'tar size'):
            prepare(self.source, self.root / 'oversize.tar.zst', self.definition)

    def test_different_compressor_version_is_rejected_before_writing(self):
        self.definition['zstd_version'] = '0.0.0'
        output = self.root / 'unexpected.tar.zst'
        with self.assertRaisesRegex(ValueError, 'pinned zstd version'):
            prepare(self.source, output, self.definition)
        self.assertFalse(output.exists())

    def test_zstd_recompression_preserves_the_exact_original_tar_bytes(self):
        original = self.root / 'original.tar.zst'
        original.write_bytes(subprocess.check_output(['zstd', '-1', '-c'], input=self.content))
        definition = dict(self.definition, method='zstd-to-zstd-19')
        output = self.root / 'recompressed.tar.zst'
        prepare(original, output, definition)
        self.assertEqual(self.content, subprocess.check_output(['zstd', '-dc', str(output)]))


if __name__ == '__main__':
    unittest.main()
