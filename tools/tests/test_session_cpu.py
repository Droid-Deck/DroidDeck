import os
from pathlib import Path
import re
import subprocess
import unittest


SESSION = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-session'


class SessionCpuTest(unittest.TestCase):
    def cores(self, mode, cpus='', pin='1', freqs=None):
        function = re.search(r'^program_cores\(\) \{\n.*?^\}\n', SESSION.read_text(), re.M | re.S).group(0)
        if freqs is None:
            freqs = {**{i: '3630000' for i in range(6)}, 6: '4610000', 7: '4610000'}
        cases = '\n'.join(f'    */cpu{i}/cpufreq/*) echo {val} ;;' for i, val in freqs.items())
        script = f'''
set -u
taskset() {{ echo 'unexpected taskset invocation' >&2; return 1; }}
cat() {{
  case "$1" in
{cases}
    *) return 1 ;;
  esac
}}
'''
        paths = ' '.join(f'/sys/devices/system/cpu/cpu{i}/cpufreq/cpuinfo_max_freq' for i in sorted(freqs))
        function = function.replace('/sys/devices/system/cpu/cpu[0-9]*/cpufreq/cpuinfo_max_freq', paths)
        env = dict(os.environ, mode=mode, DD_GAME_CPUS=cpus, DD_PIN_CORES=pin)
        result = subprocess.run(['bash', '-c', script + function + '\nprogram_cores\n'],
                                env=env, capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('', result.stderr)
        return result.stdout.strip()

    def test_run_with_all_cores_leaves_scheduling_unrestricted(self):
        self.assertEqual('', self.cores('run'))

    def test_run_preserves_an_explicit_core_selection(self):
        self.assertEqual('1,2,3,4,5,6,7', self.cores('run', '1,2,3,4,5,6,7'))

    def test_disabled_pinning_takes_precedence_over_explicit_cores(self):
        self.assertEqual('', self.cores('run', '6,7', pin='0'))

    def test_desktop_keeps_its_fast_core_default(self):
        self.assertEqual('6,7', self.cores('lxqt'))

    def test_desktop_preserves_an_explicit_core_selection(self):
        self.assertEqual('0,1,2', self.cores('lxqt', '0,1,2'))

    def test_desktop_handles_s26_ultra_multi_cluster_and_homogeneous_topologies(self):
        s26_ultra = {**{i: '3628800' for i in range(6)}, 6: '4742400', 7: '4742400'}
        one_three_four = {**{i: '1800000' for i in range(4)}, 4: '2800000', 5: '2800000', 6: '2800000', 7: '3200000'}
        one_five_two = {0: '2265600', 1: '2265600', 2: '3148800', 3: '3148800', 4: '3148800', 5: '2956800', 6: '2956800', 7: '3302400'}
        one_four_three = {0: '2000000', 1: '2000000', 2: '2000000', 3: '2800000', 4: '2800000', 5: '2800000', 6: '2800000', 7: '3200000'}
        four_cluster_10 = {**{i: '1960000' for i in range(4)}, 4: '2600000', 5: '2600000', 6: '2600000', 7: '2900000', 8: '2900000', 9: '3210000'}
        homogeneous = {i: '2000000' for i in range(8)}
        self.assertEqual('6,7', self.cores('lxqt', freqs=s26_ultra))
        self.assertEqual('4,5,6,7', self.cores('lxqt', freqs=one_three_four))
        self.assertEqual('2,3,4,5,6,7', self.cores('lxqt', freqs=one_five_two))
        self.assertEqual('3,4,5,6,7', self.cores('lxqt', freqs=one_four_three))
        self.assertEqual('4,5,6,7,8,9', self.cores('lxqt', freqs=four_cluster_10))
        self.assertEqual('', self.cores('lxqt', freqs=homogeneous))
        self.assertEqual('6,7', self.cores('lxqt', freqs={**s26_ultra, 5: '0'}))


if __name__ == '__main__':
    unittest.main()
