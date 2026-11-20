import hashlib
import io
import os
import json
import shutil
import socket
import subprocess
import sys
import tarfile
import tempfile
import time
import unittest
from pathlib import Path


SOURCE = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-steamvr'


class SteamVrHelperTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compiler_output = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.compiler_output.cleanup)
        cls.scheduling = Path(cls.compiler_output.name) / 'libdroiddeck_vr_scheduling.so'
        source = Path(__file__).resolve().parents[1] / 'vr/src/scheduling.c'
        subprocess.run(['cc', '-shared', '-fPIC', '-o', str(cls.scheduling), str(source), '-ldl'], check=True)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.home = self.root / 'home'
        self.runtime = self.root / 'runtime'
        self.commands = self.root / 'commands'
        self.driver = self.root / 'driver/droiddeck/bin/linuxarm64'
        self.steamvr = self.root / 'steamvr'
        for directory in (self.commands, self.driver, self.steamvr / 'bin/linuxarm64'):
            directory.mkdir(parents=True)
        self.helper = self.root / 'droiddeck-steamvr'
        self.assertIn('/run/droiddeck/droiddeck-xr-frames', SOURCE.read_text())
        self.helper.write_text(SOURCE.read_text().replace('/run/droiddeck/droiddeck-xr-frames',
                                                        str(self.runtime / 'droiddeck-xr-frames')), encoding='utf-8')
        self.executable(self.commands / 'pgrep', 'exit 1')
        self.executable(self.commands / 'sleep', 'exit 0')
        self.executable(self.commands / 'xset', 'exit 0')
        self.executable(self.commands / 'setsid', 'exec "$@"')
        self.executable(self.driver / 'xr-display-mode', 'echo 3360x1760@120')
        self.executable(self.steamvr / 'bin/linuxarm64/vrstartup',
                        'echo "display=${DROIDDECK_VR_DISPLAY_MODE:-unset}"')
        (self.driver / 'driver_droiddeck.so').touch()
        layer = self.root / 'driver/layer'
        layer.mkdir()
        shutil.copyfile(self.scheduling, layer / self.scheduling.name)
        self.environment = dict(os.environ)
        self.environment.pop('DROIDDECK_VR_DISPLAY_MODE', None)
        self.environment.pop('DROIDDECK_VR_PRESENT', None)
        self.environment.pop('DROIDDECK_VR_PRODUCER_LEASE', None)
        self.environment.update(HOME=str(self.home), XDG_RUNTIME_DIR=str(self.runtime),
                                STEAMVR=str(self.steamvr), DROIDDECK_VR_DIR=str(self.root / 'driver'),
                                DROIDDECK_VR_SETTINGS_HELPER=str(SOURCE.with_name('droiddeck-steamvr-settings')),
                                PATH=str(self.commands) + os.pathsep + os.environ['PATH'])

    def executable(self, destination, body):
        destination.write_text('#!/bin/sh\n' + body + '\n', encoding='utf-8')
        destination.chmod(0o755)

    def start(self):
        result = subprocess.run(['sh', str(self.helper), 'start'], env=self.environment,
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        log = self.home / '.local/share/Steam/logs/droiddeck-vrstartup.log'
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            if log.is_file() and 'display=' in log.read_text():
                return log
            time.sleep(0.01)
        self.fail('Startup log did not arrive')

    def test_fresh_runtime_mode_is_selected_and_log_survives_runtime_cleanup(self):
        log = self.start()
        self.assertEqual((self.runtime / 'droiddeck-steamvr').stat().st_mode & 0o777, 0o700)
        self.assertEqual(log.read_text().strip(), 'display=3360x1760@120')
        self.assertEqual((self.runtime / 'droiddeck-steamvr/vrstartup.log').resolve(), log)
        shutil.rmtree(self.runtime)
        self.assertTrue(log.is_file())

    def test_explicit_display_override_is_preserved(self):
        self.environment['DROIDDECK_VR_DISPLAY_MODE'] = '1920x1080@90'
        self.assertEqual(self.start().read_text().strip(), 'display=1920x1080@90')

    def test_stale_or_missing_tracking_keeps_the_default(self):
        self.executable(self.driver / 'xr-display-mode', 'exit 1')
        self.assertEqual(self.start().read_text().strip(), 'display=unset')

    def test_older_runtime_without_the_reader_still_starts(self):
        (self.driver / 'xr-display-mode').unlink()
        self.assertEqual(self.start().read_text().strip(), 'display=unset')

    def lease_log(self):
        self.executable(self.steamvr / 'bin/linuxarm64/vrstartup',
                        'printf "display=%s present=%s lease=%s\\n" "${DROIDDECK_VR_DISPLAY_MODE:-unset}" '
                        '"${DROIDDECK_VR_PRESENT:-unset}" "${DROIDDECK_VR_PRODUCER_LEASE:-unset}"')
        return self.start().read_text().strip()

    def test_quest_receiver_uses_normal_compositor_and_excludes_unsupported_frame_backend(self):
        self.runtime.mkdir()
        cvp = self.steamvr / 'drivers/cv/bin/linuxarm64/libArcturusPerception.so'
        cvp.parent.mkdir(parents=True)
        cvp.write_bytes(b'frame-only backend')
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as receiver:
            receiver.bind(str(self.runtime / 'droiddeck-xr-frames'))
            self.assertIn('present=questwsi lease=unset', self.lease_log())
        settings = json.loads((self.home / '.local/share/Steam/config/steamvr.vrsettings').read_text())
        self.assertEqual(settings['steamvr'], {'enableLinuxVulkanAsync': True, 'motionSmoothing': False})
        self.assertFalse(cvp.exists())
        self.assertEqual(Path(str(cvp) + '.droiddeck-disabled').read_bytes(), b'frame-only backend')

    def test_quest_settings_preserve_other_preferences_and_start_is_idempotent(self):
        self.environment['DROIDDECK_VR_PRESENT'] = 'questwsi'
        path = self.home / '.local/share/Steam/config/steamvr.vrsettings'
        path.parent.mkdir(parents=True)
        path.write_text(json.dumps({'steamvr': {'somePreference': 42}, 'dashboard': {'somePreference': True}}))
        self.start()
        self.start()
        settings = json.loads(path.read_text())
        self.assertEqual(settings['steamvr']['somePreference'], 42)
        self.assertEqual(settings['dashboard'], {'somePreference': True})

    def test_quest_refuses_to_overwrite_malformed_settings(self):
        self.environment['DROIDDECK_VR_PRESENT'] = 'questwsi'
        path = self.home / '.local/share/Steam/config/steamvr.vrsettings'
        path.parent.mkdir(parents=True)
        path.write_text('invalid json')
        result = subprocess.run(['sh', str(self.helper), 'start'], env=self.environment,
                                capture_output=True, text=True, timeout=5)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(path.read_text(), 'invalid json')

    def test_quest_stop_restores_only_overridden_preferences(self):
        self.environment['DROIDDECK_VR_PRESENT'] = 'questwsi'
        path = self.home / '.local/share/Steam/config/steamvr.vrsettings'
        path.parent.mkdir(parents=True)
        path.write_text(json.dumps({'steamvr': {'motionSmoothing': True, 'somePreference': 42}}))
        self.start()
        settings = json.loads(path.read_text())
        settings['dashboard'] = {'changedDuringSession': True}
        path.write_text(json.dumps(settings))
        subprocess.run(['sh', str(self.helper), 'stop'], env=self.environment, check=True)
        self.assertEqual(json.loads(path.read_text()), {'steamvr': {'motionSmoothing': True, 'somePreference': 42},
                                                      'dashboard': {'changedDuringSession': True}})

    def test_invalid_backup_preserves_preferences_until_repaired(self):
        settings = self.root / 'steamvr.vrsettings'
        backup = self.root / 'quest-backup.json'
        helper = SOURCE.with_name('droiddeck-steamvr-settings')
        original = {'steamvr': {'motionSmoothing': True}, 'dashboard': {'unchanged': 42}}
        settings.write_text(json.dumps(original))
        subprocess.run([sys.executable, str(helper), 'apply', str(settings), str(backup)], check=True)
        valid_backup = backup.read_bytes()
        applied = settings.read_bytes()
        invalid = ['truncated {', '{}', '{"section": true, "values": []}',
                   '{"section": false, "values": {"motionSmoothing": [false]}}']
        for content in invalid:
            for operation in ('apply', 'restore'):
                with self.subTest(content=content, operation=operation):
                    backup.write_text(content)
                    result = subprocess.run([sys.executable, str(helper), operation, str(settings), str(backup)],
                                            capture_output=True, text=True)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn('repair the saved section/values and retry restore', result.stderr)
                    self.assertEqual(backup.read_text(), content)
                    self.assertEqual(settings.read_bytes(), applied)
        backup.write_bytes(valid_backup)
        subprocess.run([sys.executable, str(helper), 'restore', str(settings), str(backup)], check=True)
        self.assertEqual(json.loads(settings.read_text()), original)
        self.assertFalse(backup.exists())

    def test_quest_update_preserves_previous_cvp_backup(self):
        self.environment['DROIDDECK_VR_PRESENT'] = 'questwsi'
        cvp = self.steamvr / 'drivers/cv/bin/linuxarm64/libArcturusPerception.so'
        cvp.parent.mkdir(parents=True)
        cvp.write_bytes(b'updated library')
        backup = Path(str(cvp) + '.droiddeck-disabled')
        backup.write_bytes(b'previous library')
        self.start()
        self.assertFalse(cvp.exists())
        self.assertEqual(backup.read_bytes(), b'previous library')
        self.assertEqual([p.read_bytes() for p in cvp.parent.glob('*.droiddeck-disabled.*')], [b'updated library'])

    def test_start_failure_after_overrides_restores_preferences(self):
        self.environment['DROIDDECK_VR_PRESENT'] = 'questwsi'
        path = self.home / '.local/share/Steam/config/steamvr.vrsettings'
        path.parent.mkdir(parents=True)
        original = {'steamvr': {'enableLinuxVulkanAsync': False, 'motionSmoothing': True}}
        path.write_text(json.dumps(original))
        cvp = self.steamvr / 'drivers/cv/bin/linuxarm64/libArcturusPerception.so'
        cvp.parent.mkdir(parents=True)
        cvp.touch()
        self.executable(self.commands / 'mv', 'exit 1')
        result = subprocess.run(['sh', str(self.helper), 'start'], env=self.environment,
                                capture_output=True, text=True, timeout=5)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(json.loads(path.read_text()), original)

    def test_explicit_quest_presenter_enables_the_lease(self):
        self.environment['DROIDDECK_VR_PRESENT'] = 'questframes'
        self.assertIn('present=questframes lease=1', self.lease_log())

    def test_explicit_diagnostic_override_is_preserved(self):
        self.environment.update(DROIDDECK_VR_PRESENT='questframes', DROIDDECK_VR_PRODUCER_LEASE='0')
        self.assertIn('present=questframes lease=0', self.lease_log())

    def test_flat_presenter_does_not_enable_the_producer_lease(self):
        self.assertIn('present=unset lease=unset', self.lease_log())

    def registration(self):
        return (self.home / '.config/openvr/openvrpaths.vrpath',
                self.home / '.config/openxr/1/active_runtime.json')

    def helper_action(self, action):
        subprocess.run(['sh', str(self.helper), action], env=self.environment, check=True,
                       capture_output=True, timeout=5)

    def test_runtime_is_registered_only_while_a_session_is_active(self):
        vrpath, xr = self.registration()
        self.start()
        self.assertTrue(vrpath.is_file())
        self.assertTrue(xr.is_symlink())
        self.helper_action('status')
        self.assertTrue(vrpath.is_file())
        self.helper_action('stop')
        self.assertFalse(vrpath.exists())
        self.assertFalse(xr.is_symlink())

    def test_idle_status_and_session_start_clear_a_leftover_registration(self):
        vrpath, xr = self.registration()
        for action in ('status', 'unregister'):
            self.start()
            (self.runtime / 'droiddeck-steamvr/active').unlink()
            self.helper_action(action)
            self.assertFalse(vrpath.exists(), action)
            self.assertFalse(xr.is_symlink(), action)

    def test_unregister_leaves_another_runtime_alone(self):
        vrpath, xr = self.registration()
        vrpath.parent.mkdir(parents=True)
        xr.parent.mkdir(parents=True)
        vrpath.write_text('{"runtime": ["/opt/other-runtime"]}')
        xr.symlink_to('/opt/monado/openxr_monado.json')
        self.helper_action('unregister')
        self.assertTrue(vrpath.is_file())
        self.assertTrue(xr.is_symlink())


    def package(self):
        payload = io.BytesIO()
        with tarfile.open(fileobj=payload, mode='w') as archive:
            body = b'#!/bin/sh\n' + os.urandom(6000).hex().encode() + b'\n'
            entry = tarfile.TarInfo('opt/steamvr/bin/linuxarm64/vrstartup')
            entry.size = len(body)
            entry.mode = 0o755
            archive.addfile(entry, io.BytesIO(body))
        data = payload.getvalue()
        source = self.root / 'package'
        source.write_bytes(data)
        text = self.helper.read_text()
        for old, new in (('PKG_SIZE=571309057', 'PKG_SIZE=%d' % len(data)),
                         ('PKG_SHA256=90a600775eb3e8856dc475f83166a93a290a0b1f3b4e82a69c8e2a8f06b9ddc9',
                          'PKG_SHA256=' + hashlib.sha256(data).hexdigest())):
            self.assertIn(old, text)
            text = text.replace(old, new)
        self.helper.write_text(text, encoding='utf-8')
        self.executable(self.commands / 'zstd', 'exec cat "$2"')
        self.environment['STEAMVR'] = str(self.root / 'fresh/steamvr')
        return source, len(data)

    def fake_curl(self, source, chunk, exit_code):
        self.executable(self.commands / 'curl', '\n'.join((
            'out=; previous=',
            'for argument in "$@"; do [ "$previous" = -o ] && out=$argument; previous=$argument; done',
            'echo "$*" >> %s' % (self.root / 'curl-calls'),
            'have=$(stat -c %s "$out" 2>/dev/null || echo 0)',
            'tail -c +$((have + 1)) %s | head -c %d >> "$out"' % (source, chunk),
            '[ "$(stat -c %%s "$out")" -lt "$(stat -c %%s %s)" ] || exit 0' % source,
            'echo "curl: (28) Operation too slow" >&2',
            'exit %d' % exit_code,
        )))

    def install(self):
        return subprocess.run(['sh', str(self.helper), 'install'], env=self.environment,
                              capture_output=True, text=True, timeout=30)

    def install_log(self):
        return (self.home / '.local/share/Steam/logs/droiddeck-steamvr-install.log').read_text()

    def test_install_resumes_a_stalled_download_and_logs_each_step(self):
        source, size = self.package()
        self.fake_curl(source, size // 8 + 1, 28)
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue((self.root / 'fresh/steamvr/bin/linuxarm64/vrstartup').is_file())
        calls = (self.root / 'curl-calls').read_text().splitlines()
        self.assertEqual(len(calls), 8)
        for call in calls:
            self.assertIn('--speed-limit 1024 --speed-time 60', call)
            self.assertIn('--connect-timeout 30', call)
            self.assertIn('-C -', call)
        log = self.install_log()
        self.assertIn('curl exited 28', log)
        self.assertIn('curl: (28) Operation too slow', log)
        self.assertIn('curl exited 0 with %d of %d bytes' % (size, size), log)
        self.assertIn('checking sha256', log)
        self.assertIn('unpacking SteamVR', log)
        self.assertIn('SteamVR installed in', log)
        self.assertFalse((self.runtime / 'droiddeck-steamvr/installing').exists())
        self.assertFalse((self.home / '.cache/droiddeck-vr/steamvr.pkg.tar.zst').exists())

    def test_install_gives_up_when_the_download_makes_no_progress(self):
        source, size = self.package()
        self.fake_curl(source, 0, 28)
        result = self.install()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len((self.root / 'curl-calls').read_text().splitlines()), 5)
        self.assertIn('download failed', self.install_log())
        self.assertFalse((self.runtime / 'droiddeck-steamvr/installing').exists())
        self.assertFalse((self.root / 'fresh/steamvr').exists())

    def test_install_removes_a_corrupt_download(self):
        source, size = self.package()
        self.fake_curl(source, size, 0)
        corrupt = self.home / '.cache/droiddeck-vr/steamvr.pkg.tar.zst'
        corrupt.parent.mkdir(parents=True)
        corrupt.write_bytes(b'x' * size)
        result = self.install()
        self.assertEqual(result.returncode, 1)
        self.assertFalse((self.root / 'curl-calls').exists())
        self.assertIn('checksum mismatch', self.install_log())
        self.assertFalse(corrupt.exists())

    def test_status_reports_download_progress(self):
        partial = self.home / '.cache/droiddeck-vr/steamvr.pkg.tar.zst'
        partial.parent.mkdir(parents=True)
        partial.write_bytes(b'x' * 1234)
        result = subprocess.run(['sh', str(self.helper), 'status'], env=self.environment,
                                capture_output=True, text=True, timeout=5, check=True)
        self.assertIn('downloaded=1234 size=571309057', result.stdout)
        partial.unlink()
        result = subprocess.run(['sh', str(self.helper), 'status'], env=self.environment,
                                capture_output=True, text=True, timeout=5, check=True)
        self.assertIn('downloaded=0 size=571309057', result.stdout)

if __name__ == '__main__':
    unittest.main()
