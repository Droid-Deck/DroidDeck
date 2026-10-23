import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from test_quest_xr import collect_device_metrics, summarize_samples, validate_samples
import subprocess


class QuestXrValidationTest(unittest.TestCase):
    def samples(self, source='frames'):
        return [{'rendered': count, 'sessionState': 5, 'headFlags': 15, 'source': source,
                 'controllersActive': [False, False], 'guestFrames': count,
                 'uploadedFrames': count, 'sourceFrameId': count + 1, 'steamvrState': 4}
                for count in (0, 144)]

    def test_requires_owned_frame_projection(self):
        self.assertIsNone(validate_samples(self.samples()))
        self.assertEqual('Wrong projection source', validate_samples(self.samples('surface')))

    def test_position_must_be_tracked_not_merely_valid(self):
        samples = self.samples()
        for sample in samples:
            sample['headFlags'] = 7
        self.assertIn('tracked 6DoF', validate_samples(samples))

    def test_optional_controllers_cannot_be_claimed_as_tested(self):
        self.assertIn('controllers', validate_samples(self.samples(), require_controllers=True))

    def test_one_early_tracked_sample_cannot_hide_final_tracking_loss(self):
        samples = self.samples()
        samples[-1]['headFlags'] = 7
        self.assertIn('final sample', validate_samples(samples))
        samples[-1]['headFlags'] = 15
        samples[-1]['sessionState'] = 4
        self.assertIn('final sample', validate_samples(samples))

    def test_measured_rates_are_separate_from_runtime_refresh(self):
        samples = self.samples('frames')
        samples[0]['_hostMonotonicSeconds'] = 10
        samples[-1]['_hostMonotonicSeconds'] = 12
        samples[-1]['periodNs'] = 8333333
        summary = summarize_samples(samples)
        self.assertEqual(summary['observedSeconds'], 2)
        self.assertEqual(summary['ratesHz']['uploadedFrames'], 72)
        self.assertAlmostEqual(summary['runtimeRefreshHz'], 120, places=3)
        self.assertEqual(summary['focusedTrackedFraction'], 1)
        samples[-1]['uploadedFrames'] = -1
        self.assertIsNone(summarize_samples(samples)['ratesHz']['uploadedFrames'])
        self.assertIsNone(summarize_samples(self.samples())['observedSeconds'])
        self.assertIsNone(summarize_samples([])['focusedTrackedFraction'])

    def test_metrics_failures_stay_unavailable_without_privileged_fallback(self):
        commands = []
        def adb(*command):
            commands.append(command)
            if command[-1] == 'battery':
                return 'temperature: 310'
            raise subprocess.CalledProcessError(1, command, stderr='Permission denied')
        metrics = collect_device_metrics(adb, 'com.droiddeck.launcher')
        self.assertEqual(len(commands), 3)
        self.assertTrue(metrics['battery']['available'])
        self.assertFalse(metrics['thermal']['available'])
        self.assertFalse(metrics['memory']['available'])

    def test_steamvr_requires_owned_images_and_source_frame_ids(self):
        samples = self.samples('frames')
        self.assertIsNone(validate_samples(samples))
        samples[-1]['uploadedFrames'] = 0
        self.assertIn('owned SteamVR images', validate_samples(samples))
        samples[-1]['uploadedFrames'] = 144
        samples[-1]['sourceFrameId'] = samples[0]['sourceFrameId']
        self.assertIn('frame IDs', validate_samples(samples))

    def test_running_process_alone_is_not_success(self):
        samples = self.samples('frames')
        samples[-1]['rendered'] = 0
        self.assertIn('stereo frames', validate_samples(samples))
        samples[-1]['rendered'] = 144
        samples[-1]['steamvrState'] = 3
        self.assertIn('RUNNING', validate_samples(samples))


if __name__ == '__main__':
    unittest.main()
