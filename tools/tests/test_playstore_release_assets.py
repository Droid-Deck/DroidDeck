import hashlib
from pathlib import Path
import tempfile
import unittest

from tools.playstore.release_assets import prepare


class PlayReleaseAssetsTest(unittest.TestCase):
    def test_large_signed_bundle_is_reassembled_without_changing_a_byte(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle = root / 'DroidDeck.aab'
            content = bytes(range(256)) * 4
            bundle.write_bytes(content)
            checksum = root / 'SHA256SUMS'
            checksum.write_text(hashlib.sha256(content).hexdigest() + '  ' + bundle.name + '\n')
            output = root / 'release'
            parts = prepare(bundle, checksum, output, limit=300)
            self.assertEqual(content, b''.join((output / name).read_bytes() for name in parts))
            self.assertTrue(all((output / name).stat().st_size <= 300 for name in parts))
            self.assertEqual(checksum.read_text(), (output / 'SHA256SUMS').read_text())
            self.assertEqual(content, bundle.read_bytes())
            self.assertEqual(4, len((output / 'PART-SHA256SUMS').read_text().splitlines()))

    def test_changed_signed_bundle_cannot_be_published(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle = root / 'bundle.aab'
            bundle.write_bytes(b'changed')
            checksum = root / 'SHA256SUMS'
            checksum.write_text('0' * 64 + '  bundle.aab\n')
            with self.assertRaisesRegex(ValueError, 'checksum verification'):
                prepare(bundle, checksum, root / 'release', limit=4)
