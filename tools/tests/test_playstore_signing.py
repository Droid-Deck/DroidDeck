import importlib.util
import contextlib
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('playstore_signing', ROOT / 'tools/playstore/sign_bundle.py')
signing = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(signing)


class PlaystoreSigningTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source = self.root / 'input.aab'
        self.destination = self.root / 'signed.aab'
        self.keystore = ROOT / 'keystore/testkey.p12'
        self.signer = 'a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc'
        with zipfile.ZipFile(self.source, 'w', zipfile.ZIP_DEFLATED) as package:
            package.writestr('base/assets/runtime', b'component payload')
            package.writestr('base/manifest/AndroidManifest.xml', b'fixture manifest')
            package.writestr('META-INF/OLD.SF', b'old signature')
            package.writestr('META-INF/OLD.RSA', b'old certificate')
            package.writestr('META-INF/services/runtime', b'keep service metadata')

    def sign(self, signer=None):
        with patch.dict(os.environ, RELEASE_STORE_PASSWORD='android'):
            signing.sign(self.source, self.destination, self.keystore, 'testkey', signer or self.signer)

    def test_signs_with_pinned_certificate_preserving_every_payload_and_source(self):
        original = self.source.read_bytes()
        self.sign()
        self.assertEqual(original, self.source.read_bytes())
        with zipfile.ZipFile(self.source) as source, zipfile.ZipFile(self.destination) as output:
            self.assertNotIn('META-INF/OLD.SF', output.namelist())
            self.assertNotIn('META-INF/OLD.RSA', output.namelist())
            for name in ['base/assets/runtime', 'base/manifest/AndroidManifest.xml', 'META-INF/services/runtime']:
                self.assertEqual(source.read(name), output.read(name))

    def test_unexpected_signing_identity_creates_no_output(self):
        with self.assertRaisesRegex(ValueError, 'release identity'):
            self.sign('0' * 64)
        self.assertFalse(self.destination.exists())

    def test_existing_destination_cannot_be_overwritten(self):
        self.destination.write_bytes(b'keep existing')
        with self.assertRaisesRegex(ValueError, 'new output'):
            self.sign()
        self.assertEqual(self.destination.read_bytes(), b'keep existing')

    def test_missing_password_cannot_sign_a_bundle(self):
        with patch.dict(os.environ, RELEASE_STORE_PASSWORD=''):
            with self.assertRaisesRegex(ValueError, 'password'):
                signing.sign(self.source, self.destination, self.keystore, 'testkey', self.signer)
        self.assertFalse(self.destination.exists())

    def test_alias_is_not_used_as_signature_metadata_or_printed(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.sign()
        self.assertNotIn('testkey', output.getvalue())
        with zipfile.ZipFile(self.destination) as package:
            self.assertIn('META-INF/RELEASE.SF', package.namelist())
            self.assertFalse(any('TESTKEY' in name.upper() for name in package.namelist()))

    def test_wrong_alias_fails_without_printing_it_or_creating_an_output(self):
        with patch.dict(os.environ, RELEASE_STORE_PASSWORD='android'):
            with self.assertRaisesRegex(ValueError, '^Bundle signing or signature verification failed$'):
                signing.sign(self.source, self.destination, self.keystore, 'private-alias-value', self.signer)
        self.assertFalse(self.destination.exists())

    def test_unsigned_bundle_is_signed_without_a_second_full_bundle_copy(self):
        with zipfile.ZipFile(self.source, 'w') as package:
            package.writestr('base/assets/runtime', b'component payload')
        with patch.object(signing, 'sanitize', side_effect=AssertionError('Unexpected full bundle copy')):
            self.sign()
        with zipfile.ZipFile(self.destination) as package:
            self.assertEqual(b'component payload', package.read('base/assets/runtime'))


if __name__ == '__main__':
    unittest.main()
