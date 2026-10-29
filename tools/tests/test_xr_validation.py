from pathlib import Path
import contextlib
import io
import json
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from test_xr import DeviceResolutionError, main, validate_progress, validate_samples


class XrValidationTest(unittest.TestCase):
    def samples(self):
        return [dict(backend='openxr', generation=1, originEpoch=1, sessionState=5, headFlags=15,
                     rendered=n, eyeSeparationMeters=0.064, source='frames', uploadedFrames=n, sourceFrameId=n+1, steamvrState=4,
                     inputMode='hands', handsActive=[True, False],
                     handProviders=['fb-hand-aim', ''], handPinchPresses=[n//100, 0], handPinchReleases=[n//100, 0])
                for n in (0, 144)]

    def run_main(self, samples, extra_args=(), logcat_error=None, log_write_error=False):
        commands, health = [], iter(samples)
        original_write = Path.write_text
        def write(path, *args, **kwargs):
            if log_write_error and path.name == 'logcat.txt':
                raise OSError('Log file is unavailable')
            return original_write(path, *args, **kwargs)
        def run(command, **kwargs):
            remote = command[3:]
            commands.append(remote)
            if remote[:2] == ['shell', 'getprop']:
                output = '0'
            elif remote[:2] == ['shell', 'cat']:
                output = json.dumps(next(health))
            elif remote[0] == 'logcat' and logcat_error is not None:
                raise logcat_error
            else:
                output = ''
            return subprocess.CompletedProcess(command, 0, stdout=output)
        with tempfile.TemporaryDirectory() as output:
            args = ['test_xr.py', '--seconds', '5', '--output', output, *extra_args]
            stderr = io.StringIO()
            with patch.object(sys, 'argv', args), contextlib.redirect_stdout(io.StringIO()), \
                    contextlib.redirect_stderr(stderr), patch('test_xr.resolve_serial', return_value='TEST'), \
                    patch('test_xr.subprocess.run', side_effect=run), patch('test_xr.time.sleep'), \
                    patch('test_xr.time.monotonic', side_effect=[0, 1, 1, 2, 2, 6]), \
                    patch.object(Path, 'write_text', write):
                result = main()
            report = json.loads((Path(output) / 'report.json').read_text(encoding='utf-8'))
        return result, report, commands, stderr.getvalue()

    def test_remote_package_and_activity_are_quoted_in_all_shell_calls(self):
        package = "com.example; echo 'test name' $(id)"
        activity = 'com.example.Test; echo activity'
        result, report, commands, _ = self.run_main(self.samples(),
            ['--package', package, '--activity', activity, '--metrics'])
        self.assertEqual(result, 0)
        self.assertTrue(report['ok'])
        shell_commands = [shlex.split(' '.join(command[1:])) for command in commands if command[0] == 'shell']
        health = f'/sdcard/Android/data/{package}/files/xr-health.json'
        self.assertIn(['rm', '-f', health], shell_commands)
        self.assertIn(['cat', health], shell_commands)
        self.assertIn(['am', 'start', '-W', '-n', f'{package}/{activity}', '--es', 'xr_input', 'hands'], shell_commands)
        self.assertEqual(shell_commands.count(['dumpsys', 'meminfo', package]), 2)

    def test_logcat_errors_do_not_change_passed_or_failed_validation(self):
        errors = [subprocess.CalledProcessError(1, ['adb', 'logcat']),
                  subprocess.TimeoutExpired(['adb', 'logcat'], 15), OSError('ADB unavailable')]
        for passed in (False, True):
            for error, write_error in [(error, False) for error in errors] + [(None, True)]:
                with self.subTest(passed=passed, error=error, write_error=write_error):
                    samples = self.samples()
                    if not passed:
                        samples[-1]['handPinchReleases'] = [0, 0]
                    result, report, _, stderr = self.run_main(samples, logcat_error=error, log_write_error=write_error)
                    self.assertEqual(result, 0 if passed else 1)
                    self.assertEqual(report['ok'], passed)
                    self.assertEqual(report['status'], 'passed' if passed else 'failed')
                    if passed:
                        self.assertIsNone(report['error'])
                    else:
                        self.assertIn('pinch press and release', report['error'])
                    self.assertTrue(report['logcatError'])
                    self.assertIn('logcat capture failed', stderr)

    def test_guest_startup_can_take_more_than_five_seconds(self):
        samples = [dict(self.samples()[-1], _hostMonotonicSeconds=second, rendered=second * 72,
                        uploadedFrames=max(0, second - 8) * 72, sourceFrameId=max(0, second - 8),
                        handPinchPresses=[second, 0], handPinchReleases=[second, 0])
                   for second in (0, 4, 8, 12, 16)]
        self.assertIsNone(validate_samples(samples))
        for key in ('uploadedFrames', 'sourceFrameId'):
            with self.subTest(key=key):
                frozen = dict(samples[-1], _hostMonotonicSeconds=22, rendered=22 * 72,
                              uploadedFrames=22 * 72, sourceFrameId=22)
                frozen[key] = samples[-1][key]
                self.assertIn(key, validate_samples(samples + [frozen]))

    def test_guest_counter_regression_and_disappearance_are_rejected_before_and_after_start(self):
        for initial in (0, 1):
            for key in ('uploadedFrames', 'sourceFrameId'):
                for missing in (False, True):
                    with self.subTest(initial=initial, key=key, missing=missing):
                        first = dict(self.samples()[0], _hostMonotonicSeconds=0)
                        first[key] = initial
                        final = dict(first, _hostMonotonicSeconds=1, rendered=144)
                        if missing:
                            del final[key]
                        else:
                            final[key] = initial - 1
                        self.assertIn('regressed or disappeared: ' + key, validate_progress([first, final]))

    def test_guest_counters_appearing_after_the_first_sample_are_monitored(self):
        samples = [{'rendered': 0, '_hostMonotonicSeconds': 0},
                   {'rendered': 144, 'uploadedFrames': 72, 'sourceFrameId': 1, '_hostMonotonicSeconds': 4},
                   {'rendered': 288, 'uploadedFrames': 72, 'sourceFrameId': 1, '_hostMonotonicSeconds': 10}]
        self.assertIn('uploadedFrames', validate_progress(samples))

    def test_render_counter_stall_is_not_deferred_until_its_first_frame(self):
        samples = [dict(self.samples()[0], _hostMonotonicSeconds=second, sourceFrameId=0)
                   for second in (0, 6)]
        self.assertIn('rendered', validate_progress(samples))

    def test_no_guest_frames_still_fails_at_the_end_of_the_run(self):
        samples = [dict(self.samples()[0], _hostMonotonicSeconds=second, rendered=second * 72, sourceFrameId=0)
                   for second in (0, 4, 8)]
        self.assertIn('owned SteamVR images', validate_samples(samples))

    def test_stale_health_cannot_pass_after_early_submissions(self):
        samples = self.samples()
        samples[0]['_hostMonotonicSeconds'] = 0
        samples[-1]['_hostMonotonicSeconds'] = 1
        samples.append(dict(samples[-1], _hostMonotonicSeconds=8))
        self.assertIn('Stale XR telemetry', validate_samples(samples))

    def test_supported_runtime_hand_providers(self):
        for provider in ('fb-hand-aim', 'ext-hand-interaction'):
            samples = self.samples()
            samples[-1]['handProviders'] = [provider, '']
            self.assertIsNone(validate_samples(samples))

    def test_requires_runtime_and_real_press_release(self):
        for field, value, expected in [('backend', 'mock', 'real OpenXR'),
                                       ('generation', 2, 'generation'),
                                       ('eyeSeparationMeters', 0, 'stereo eye separation'),
                                       ('handPinchReleases', [0, 0], 'press and release'),
                                       ('handsActive', [False, False], 'active runtime hand'),
                                       ('handProviders', ['synthetic', ''], 'provider')]:
            samples = self.samples()
            samples[-1][field] = value
            self.assertIn(expected, validate_samples(samples))

    def test_local_scene_cannot_pass_guest_validation(self):
        samples = self.samples()
        samples[-1]['source'] = 'synthetic'
        self.assertIsNotNone(validate_samples(samples))
        samples = self.samples()
        for sample in samples:
            sample.update(source='frames', uploadedFrames=sample['rendered'], sourceFrameId=sample['rendered']+1,
                          steamvrState=4)
        self.assertIsNone(validate_samples(samples))

    def test_mock_auto_input_still_cannot_pass(self):
        samples = self.samples()
        for sample in samples:
            sample['backend'] = 'mock'
        self.assertIn('real OpenXR', validate_samples(samples, require_hands=False))

    def test_controller_mode_requires_both_controllers_in_the_final_sample(self):
        samples = self.samples()
        for sample in samples:
            sample.update(inputMode='controllers', controllersActive=[True, True], handsActive=[False, False])
        self.assertIsNone(validate_samples(samples, require_hands=False, require_controllers=True))
        for final_input in ({'controllersActive': [True, False]}, {'controllersActive': []},
                            {'controllersActive': [True]}, {'inputMode': 'hands'}):
            with self.subTest(final_input=final_input):
                changed = [samples[0], dict(samples[-1], **final_input)]
                self.assertIn('Both runtime controllers',
                              validate_samples(changed, require_hands=False, require_controllers=True))

    def test_controller_mode_still_requires_owned_steamvr_images(self):
        samples = self.samples()
        for sample in samples:
            sample.update(inputMode='controllers', controllersActive=[True, True], uploadedFrames=0, sourceFrameId=0)
        self.assertIn('owned SteamVR images',
                      validate_samples(samples, require_hands=False, require_controllers=True))
        for sample in samples:
            sample.update(source='frames', uploadedFrames=sample['rendered'], sourceFrameId=sample['rendered'] + 1,
                          steamvrState=4)
        self.assertIsNone(validate_samples(samples, require_hands=False, require_controllers=True))

    def test_androidxr_steamvr_accepts_controller_and_auto_launch_policies(self):
        for policy, evidence in [('controllers', 'runtime-controller-activity'), ('auto', 'shared-openxr-runtime')]:
            with self.subTest(policy=policy), tempfile.TemporaryDirectory() as output:
                args = ['test_xr.py', '--input', policy, '--output', output]
                with patch.object(sys, 'argv', args), contextlib.redirect_stdout(io.StringIO()), \
                        patch('test_xr.resolve_serial', side_effect=DeviceResolutionError('No test headset')) as resolve:
                    self.assertEqual(main(), 1)
                    resolve.assert_called_once()
                report = json.loads((Path(output) / 'report.json').read_text(encoding='utf-8'))
                self.assertEqual(report['status'], 'not_run')
                self.assertEqual(report['input'], policy)
                self.assertEqual(report['evidence'], evidence)
                self.assertFalse(report['steamVrInputDeliveryVerified'])

    def test_select_only_counters_cannot_validate_the_mapped_pinch(self):
        samples = self.samples()
        for sample in samples:
            sample['handSelectPresses'] = sample.pop('handPinchPresses')
            sample['handSelectReleases'] = sample.pop('handPinchReleases')
        self.assertIn('pinch press and release', validate_samples(samples))

    def test_press_release_must_belong_to_an_active_supported_hand(self):
        samples = self.samples()
        samples[-1]['handPinchPresses'] = samples[-1]['handPinchReleases'] = [0, 1]
        self.assertIn('pinch press and release', validate_samples(samples))
