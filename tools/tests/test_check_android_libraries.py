import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile

from tools.release.check_android_libraries import check


class AndroidLibrariesTest(unittest.TestCase):
    def test_sdk_free_package_is_allowed(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'app.apk'
            with ZipFile(apk, 'w') as package:
                package.writestr('assets/readme.txt', 'Runtime SDK import is available.')
            self.assertEqual([], check(apk, 'unused-readelf', stores=False))

    def test_vendor_sdk_is_rejected_in_native_libraries_or_assets(self):
        for name in ('lib/arm64-v8a/libglasses.so', 'lib/armeabi-v7a/libcarina_vio.so',
                     'assets/sdk/libcloud_protocol.so'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                apk = Path(directory) / 'app.apk'
                with ZipFile(apk, 'w') as package:
                    package.writestr(name, b'fixture')
                with patch('tools.release.check_android_libraries.subprocess.check_output', return_value=''):
                    errors = check(apk, 'unused-readelf', stores=False)
                self.assertEqual(['APK must not bundle the user-imported VITURE SDK: ' + name], errors)


if __name__ == '__main__':
    unittest.main()
