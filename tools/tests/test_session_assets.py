import importlib.util
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile


spec = importlib.util.spec_from_file_location(
    'check_session_assets', Path(__file__).resolve().parents[1] / 'release/check_session_assets.py')
assets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assets)


class SessionAssetsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.overlay = self.root / 'overlay'
        self.helper = self.overlay / 'usr/local/bin/bannerlator-login1'
        self.helper.parent.mkdir(parents=True)
        self.helper.write_bytes(b'current helper')
        self.apk = self.root / 'app.apk'
        self.name = 'assets/linuxfs/usr/local/bin/bannerlator-login1'

    def test_missing_required_helper_rejects_cached_bundle(self):
        with ZipFile(self.apk, 'w') as package:
            package.writestr('classes.dex', b'classes')
        self.assertEqual(['missing ' + self.name], assets.check(self.apk, self.overlay))

    def test_stale_helper_rejects_cached_bundle(self):
        with ZipFile(self.apk, 'w') as package:
            package.writestr(self.name, b'old helper')
        self.assertEqual(['stale ' + self.name], assets.check(self.apk, self.overlay))

    def test_current_helper_passes(self):
        with ZipFile(self.apk, 'w') as package:
            package.writestr(self.name, self.helper.read_bytes())
        self.assertEqual([], assets.check(self.apk, self.overlay))
