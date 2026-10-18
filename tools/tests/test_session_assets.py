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
        self.helper = self.overlay / 'usr/local/bin/droiddeck-login1'
        self.helper.parent.mkdir(parents=True)
        self.helper.write_bytes(b'current helper')
        self.apk = self.root / 'app.apk'
        self.name = 'assets/linuxfs/usr/local/bin/droiddeck-login1'

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

    def test_apk_rejects_embedded_system_vulkan_payloads(self):
        with ZipFile(self.apk, 'w') as package:
            package.writestr(self.name, self.helper.read_bytes())
            package.writestr('assets/hybris/runtime.tzst', b'old bundled runtime')
            package.writestr('assets/hybris/version.txt', b'0' * 64)
        self.assertEqual(
            ['embedded System Vulkan payload must not ship in the APK: '
             'assets/hybris/runtime.tzst, assets/hybris/version.txt'],
            assets.check(self.apk, self.overlay),
        )

    def test_bundled_helper_must_also_be_extracted(self):
        self.assertEqual(['not extracted at session start: usr/local/bin/droiddeck-login1'],
                         assets.check_extraction(self.overlay, ''))
        mapping = '"usr/local/bin/droiddeck-login1" to "usr/local/bin/droiddeck-login1"'
        self.assertEqual([], assets.check_extraction(self.overlay, mapping))

    def test_helper_at_an_unexpected_destination_is_rejected(self):
        mapping = '"usr/local/bin/droiddeck-login1" to "usr/local/bin/unrelated"'
        self.assertEqual(['not extracted at session start: usr/local/bin/droiddeck-login1'],
                         assets.check_extraction(self.overlay, mapping))

    def test_steam_taskset_requires_its_private_destination(self):
        self.helper.rename(self.helper.with_name('droiddeck-steam-taskset'))
        source = 'usr/local/bin/droiddeck-steam-taskset'
        target = 'usr/local/libexec/droiddeck-steam/taskset'
        self.assertEqual([], assets.check_extraction(self.overlay, f'"{source}" to "{target}"'))
        for destination in (source, 'usr/local/libexec/unrelated/taskset'):
            with self.subTest(destination=destination):
                self.assertEqual(['not extracted at session start: ' + source],
                                 assets.check_extraction(self.overlay, f'"{source}" to "{destination}"'))

    def test_current_session_stages_all_bundled_helpers(self):
        self.assertEqual([], assets.check_extraction(assets.OVERLAY, assets.SESSION_FILES.read_text()))
