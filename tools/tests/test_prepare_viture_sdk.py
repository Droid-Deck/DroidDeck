import contextlib
import hashlib
from http.client import BadStatusLine, InvalidURL
import importlib.util
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock
from urllib.error import URLError
import warnings
from zipfile import ZipFile


spec = importlib.util.spec_from_file_location(
    'prepare_viture_sdk', Path(__file__).resolve().parents[1] / 'viture/prepare_sdk.py')
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class VitureSdkPreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.archive = self.root / 'sdk.zip'
        self.output = self.root / 'prepared'
        self.files = {name: name.encode() for name in prepare.REQUIRED_FILES}
        self.files['include/viture_version.h'] = (
            b'#define VITURE_VERSION_MAJOR 2\n'
            b'#define VITURE_VERSION_MINOR 4\n'
            b'#define VITURE_VERSION_PATCH 0\n')

    def write_archive(self, duplicate=None):
        with ZipFile(self.archive, 'w') as package:
            for name, content in self.files.items():
                package.writestr(name, content)
            if duplicate:
                with warnings.catch_warnings():
                    warnings.simplefilter('ignore', UserWarning)
                    package.writestr(duplicate, b'duplicate')
        release = {'version': '2.4.0', 'sha256': hashlib.sha256(self.archive.read_bytes()).hexdigest()}
        patcher = mock.patch.object(prepare, 'read_release', return_value=release)
        patcher.start()
        self.addCleanup(patcher.stop)

    def assert_clean_failure(self, message):
        with self.assertRaisesRegex(prepare.PreparationError, message):
            prepare.prepare(self.output, self.archive)
        self.assertFalse(self.output.exists())
        self.assertFalse(list(self.root.glob('.viture-sdk-*')))

    def test_only_required_arm64_files_are_extracted(self):
        self.files.update({
            'demo/glasses-demo-android.apk': b'demo',
            'android/armeabi-v7a/libglasses.so': b'other architecture',
            '../outside': b'unsafe',
            '/absolute': b'unsafe',
            'include/../../outside': b'unsafe',
        })
        self.write_archive()
        self.assertEqual(prepare.prepare(self.output, self.archive), '2.4.0')
        extracted = {path.relative_to(self.output).as_posix()
                     for path in self.output.rglob('*') if path.is_file()}
        self.assertEqual(extracted, set(prepare.REQUIRED_FILES))
        for name in prepare.REQUIRED_FILES:
            self.assertEqual((self.output / name).read_bytes(), self.files[name])
        self.assertFalse((self.root / 'outside').exists())
        self.assertFalse(list(self.root.glob('.viture-sdk-*')))

    def test_checksum_failure_precedes_archive_extraction(self):
        self.write_archive()
        self.archive.write_bytes(b'changed archive')
        with mock.patch.object(prepare, 'extract_sdk') as extract:
            self.assert_clean_failure('SHA-256')
        extract.assert_not_called()

    def test_missing_library_leaves_no_partial_output(self):
        del self.files['android/arm64-v8a/libcarina_vio.so']
        self.write_archive()
        self.assert_clean_failure('missing required files.*libcarina_vio')

    def test_empty_library_is_rejected(self):
        self.files['android/arm64-v8a/libglasses.so'] = b''
        self.write_archive()
        self.assert_clean_failure('empty SDK archive entry')

    def test_duplicate_selected_entry_is_rejected(self):
        self.write_archive(duplicate='include/viture_device.h')
        self.assert_clean_failure('Duplicate SDK archive entry')

    def test_mismatched_header_version_is_rejected(self):
        self.files['include/viture_version.h'] = self.files['include/viture_version.h'].replace(
            b'VERSION_MINOR 4', b'VERSION_MINOR 5')
        self.write_archive()
        self.assert_clean_failure('header version')

    def test_existing_output_is_not_modified(self):
        self.output.mkdir()
        marker = self.output / 'existing'
        marker.write_text('keep')
        with mock.patch.object(prepare, 'download_archive') as download:
            with self.assertRaisesRegex(prepare.PreparationError, 'already exists'):
                prepare.prepare(self.output)
        self.assertEqual(marker.read_text(), 'keep')
        download.assert_not_called()

    def test_downloaded_archive_is_verified_and_prepared(self):
        self.write_archive()
        response = io.BytesIO(self.archive.read_bytes())
        response.geturl = lambda: 'https://sdk.example/sdk.zip?token=private'
        with mock.patch.dict(os.environ, {'VITURE_SDK_URL': response.geturl()}), \
                mock.patch.object(prepare, 'build_opener') as opener:
            opener.return_value.open.return_value = response
            self.assertEqual(prepare.prepare(self.output), '2.4.0')
        self.assertTrue((self.output / 'android/NOTICE').is_file())

    def test_download_errors_do_not_expose_signed_url(self):
        url = 'https://sdk.example/sdk.zip?token=private-secret'
        for exception in (URLError(url), InvalidURL(url), BadStatusLine(url)):
            with self.subTest(exception=type(exception).__name__):
                error = io.StringIO()
                with mock.patch.dict(os.environ, {'VITURE_SDK_URL': url}), \
                        mock.patch.object(prepare, 'build_opener') as opener, \
                        contextlib.redirect_stderr(error):
                    opener.return_value.open.side_effect = exception
                    self.assertEqual(prepare.main(['--output', str(self.output)]), 1)
                self.assertIn('download failed', error.getvalue())
                self.assertNotIn(url, error.getvalue())
                self.assertNotIn('private-secret', error.getvalue())
                self.assertFalse(self.output.exists())
                self.assertFalse(list(self.root.glob('.viture-sdk-*')))

    def test_missing_url_is_actionable(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(prepare.PreparationError, 'Set VITURE_SDK_URL'):
                prepare.prepare(self.output)

    def test_plain_http_and_malformed_urls_are_rejected_without_logging_them(self):
        for url in ('http://sdk.example/sdk.zip?token=private', 'https://[bad?private', '/private.zip'):
            with self.subTest(url=url), mock.patch.dict(os.environ, {'VITURE_SDK_URL': url}):
                with self.assertRaisesRegex(prepare.PreparationError, 'HTTPS URL') as error:
                    prepare.prepare(self.output)
                self.assertNotIn(url, str(error.exception))

    def test_redirect_cannot_downgrade_to_http(self):
        with self.assertRaisesRegex(prepare.PreparationError, 'HTTPS URL'):
            prepare.HttpsRedirectHandler().redirect_request(
                None, None, 302, 'Found', {}, 'http://sdk.example/sdk.zip?token=private')


if __name__ == '__main__':
    unittest.main()
