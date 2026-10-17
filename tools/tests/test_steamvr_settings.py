import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


HELPER = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-steamvr'
SCRIPT = HELPER.read_text().split("<<'PY'\n", 1)[1].split('\nPY\n', 1)[0]


class SteamVrSettingsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'steamvr.vrsettings'

    def run_defaults(self):
        result = subprocess.run([sys.executable, '-', str(self.path)], input=SCRIPT,
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(list(self.path.parent.iterdir()), [self.path])
        return result

    def test_new_install_defaults_to_manual_100_percent(self):
        self.run_defaults()
        self.assertEqual(json.loads(self.path.read_text()), {
            'steamvr': {'supersampleManualOverride': True, 'supersampleScale': 1.0}})

    def test_existing_automatic_gpu_estimate_does_not_override_app_resolution(self):
        settings = {'GpuSpeed': {'gpuSpeedRenderTargetScale': 1.1},
                    'steamvr': {'environmentMode': 0}, 'driver_droiddeck': {'directMode': True}}
        self.path.write_text(json.dumps(settings))
        self.run_defaults()
        settings['steamvr'].update(supersampleManualOverride=True, supersampleScale=1.0)
        self.assertEqual(json.loads(self.path.read_text()), settings)

    def test_explicit_user_and_per_game_settings_are_preserved_without_rewrite(self):
        for manual in (False, True):
            with self.subTest(manual=manual):
                settings = {'steamvr': {'supersampleManualOverride': manual, 'supersampleScale': 0.7},
                            'steam.app.611670': {'resolutionScale': 80}}
                content = json.dumps(settings)
                self.path.write_text(content)
                self.run_defaults()
                self.assertEqual(self.path.read_text(), content)

    def test_defaults_are_idempotent(self):
        self.run_defaults()
        before = self.path.read_bytes()
        self.run_defaults()
        self.assertEqual(self.path.read_bytes(), before)

    def test_partial_user_settings_are_preserved(self):
        self.path.write_text('{"steamvr": {"supersampleScale": 0.5}}')
        self.run_defaults()
        self.assertEqual(json.loads(self.path.read_text())['steamvr'], {
            'supersampleScale': 0.5, 'supersampleManualOverride': True})

    def test_a_crash_block_on_the_droiddeck_driver_is_cleared_and_others_kept(self):
        settings = {'steamvr': {'supersampleManualOverride': True, 'supersampleScale': 1.0},
                    'driver_droiddeck': {'blocked_by_safe_mode': True, 'directMode': True},
                    'driver_lighthouse': {'blocked_by_safe_mode': True}}
        self.path.write_text(json.dumps(settings))
        self.assertIn('unblocked it', self.run_defaults().stdout)
        settings['driver_droiddeck']['blocked_by_safe_mode'] = False
        self.assertEqual(json.loads(self.path.read_text()), settings)

    def test_the_crash_marker_is_removed_so_steamvr_does_not_start_in_safe_mode(self):
        (self.path.parent / 'vrserver_crash_timestamp.txt').write_text('1792116926 1789606310 223')
        self.assertIn('crash marker', self.run_defaults().stdout)

    def test_invalid_settings_are_reported_without_losing_the_original(self):
        for content in ('{broken', '[]', '{"steamvr": null}', '{"steamvr": []}'):
            with self.subTest(content=content):
                self.path.write_text(content)
                self.assertIn('Could not set SteamVR resolution defaults', self.run_defaults().stderr)
                self.assertEqual(self.path.read_text(), content)


if __name__ == '__main__':
    unittest.main()
