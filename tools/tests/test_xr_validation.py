from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from test_xr import validate_samples


class XrValidationTest(unittest.TestCase):
    def samples(self):
        return [dict(backend='openxr', generation=1, originEpoch=1, sessionState=5, headFlags=15,
                     rendered=n, eyeSeparationMeters=0.064, source='frames', uploadedFrames=n, sourceFrameId=n+1, steamvrState=4,
                     inputMode='hands', handsActive=[True, False],
                     handProviders=['fb-hand-aim', ''], handPinchPresses=[n//100, 0], handPinchReleases=[n//100, 0])
                for n in (0, 144)]

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
