"""Single-core Steam affinity is rechecked even if its thread count stayed the same."""
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-session'
SOURCE = SCRIPT.read_text()
FUNCTION = SOURCE[SOURCE.index('client_single_core_matches() {'):].split('\n}\n', 1)[0] + '\n}\n'


class ClientCoreMaskTest(unittest.TestCase):
    def matches(self, wanted, tasks):
        return subprocess.run(['bash', '-c', FUNCTION + '\nclient_single_core_matches "$@"',
                               'test', wanted, *map(str, tasks)], capture_output=True).returncode

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


if __name__ == '__main__':
    unittest.main()
