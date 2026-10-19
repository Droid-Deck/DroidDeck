"""Single-core Steam affinity is rechecked even if its thread count stayed the same."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-session'
SOURCE = SCRIPT.read_text()
FUNCTION = SOURCE[SOURCE.index('client_single_core_matches() {'):].split('\n}\n', 1)[0] + '\n}\n'
PROGRAM_CORES = SOURCE[SOURCE.index('program_cores() {'):].split('\n}\n', 1)[0] + '\n}\n'


class ClientCoreMaskTest(unittest.TestCase):
    def matches(self, wanted, tasks):
        return subprocess.run(['bash', '-c', FUNCTION + '\nclient_single_core_matches "$@"',
                               'test', wanted, *map(str, tasks)], capture_output=True).returncode

    def program_cores(self, freqs):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for i, freq in enumerate(freqs):
                cpufreq = root / f'cpu{i}' / 'cpufreq'
                cpufreq.mkdir(parents=True)
                (cpufreq / 'cpuinfo_max_freq').write_text(f'{freq}\n')
            script = (
                'command() { return 0; }\n'
                'unset BL_PIN_CORES BL_GAME_CPUS\n'
                + PROGRAM_CORES
                + '\nprogram_cores\n'
            )
            res = subprocess.run(
                ['bash', '-c', script],
                env={**os.environ, 'BL_CPU_SYSFS': str(root)},
                capture_output=True,
                text=True,
                check=True,
            )
            return res.stdout.strip()

    def test_a_mask_change_without_a_new_thread_needs_repinning(self):
        with tempfile.TemporaryDirectory() as tmp:
            tasks = [Path(tmp) / str(i) for i in (1, 2)]
            for task in tasks:
                task.mkdir()
                (task / 'status').write_text('Name:\tsteam\nCpus_allowed_list:\t7\n')
            self.assertEqual(self.matches('7', tasks), 0)
            (tasks[1] / 'status').write_text('Name:\tsteam\nCpus_allowed_list:\t0-7\n')
            self.assertEqual(self.matches('7', tasks), 1)

    def test_an_exited_thread_does_not_prevent_checking_live_threads(self):
        with tempfile.TemporaryDirectory() as tmp:
            live = Path(tmp) / '1'
            live.mkdir()
            (live / 'status').write_text('Cpus_allowed_list:\t3-6\n')
            self.assertEqual(self.matches('7', [Path(tmp) / 'gone', live]), 1)

    def test_multi_core_masks_keep_the_existing_thread_count_policy(self):
        self.assertEqual(self.matches('0,1,2,3', []), 0)

    def test_six_plus_two_oryon_clusters_keep_all_eight_cores(self):
        fold8_sm8850_ac = [3628800] * 6 + [4742400] * 2
        fold7_sm8750_ac = [3532800] * 6 + [4470000] * 2
        self.assertEqual(self.program_cores(fold8_sm8850_ac), '0,1,2,3,4,5,6,7')
        self.assertEqual(self.program_cores(fold7_sm8750_ac), '0,1,2,3,4,5,6,7')

    def test_little_efficiency_clusters_are_still_dropped(self):
        fold6_sm8650_ac = [2265600] * 2 + [3148800] * 5 + [3398400]
        sd865_4_3_1 = [1804800] * 4 + [2419200] * 3 + [2841600]
        self.assertEqual(self.program_cores(fold6_sm8650_ac), '2,3,4,5,6,7')
        self.assertEqual(self.program_cores(sd865_4_3_1), '4,5,6,7')


if __name__ == '__main__':
    unittest.main()
