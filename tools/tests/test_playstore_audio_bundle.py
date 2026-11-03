import gzip
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

from tools.playstore import check_audio_bundle


class PlayAudioBundleTest(unittest.TestCase):
    def test_packaged_sources_allow_lossless_android_gzip_expansion(self):
        self.check_source(b'corresponding source', b'corresponding source', expanded=True)

    def test_packaged_sources_must_match_even_after_android_gzip_expansion(self):
        with self.assertRaisesRegex(ValueError, 'differs'):
            self.check_source(b'corresponding source', b'other source', expanded=True)

    def test_packaged_audio_asset_cannot_be_replaced_with_stale_output(self):
        with self.assertRaisesRegex(ValueError, 'differs'):
            self.check_source(b'rebuilt audio', b'old audio', expanded=False)

    def test_packaged_sources_cannot_be_omitted(self):
        with self.assertRaises(KeyError):
            self.check_source(b'corresponding source', None, expanded=True)

    def check_source(self, expected, actual, expanded):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            name = 'assets/licenses/play-audio/sources/pulse.tar.gz' if expanded else 'assets/pulseaudio.tzst'
            source = gzip.compress(expected) if expanded else expected
            path = root / name
            path.parent.mkdir(parents=True)
            path.write_bytes(source)
            bundle = root / 'test.aab'
            with ZipFile(bundle, 'w') as package:
                if actual is not None:
                    package.writestr('base/' + (name.removesuffix('.gz') if expanded else name), actual)
            manifest = {'outputs': {name: hashlib.sha256(source).hexdigest()}}
            with patch.object(check_audio_bundle, 'verify', return_value=manifest), \
                    patch.object(check_audio_bundle, 'LIBRARIES', []):
                check_audio_bundle.check(bundle, root, root / 'ndk')


if __name__ == '__main__':
    unittest.main()
