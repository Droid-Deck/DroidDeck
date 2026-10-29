import os
from pathlib import Path
import re
import subprocess
import unittest


SESSION = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-session'


class SessionCpuTest(unittest.TestCase):
    def cores(self, mode, cpus='', pin='1'):
        function = re.search(r'^program_cores\(\) \{\n.*?^\}\n', SESSION.read_text(), re.M | re.S).group(0)
        script = '''
set -u
taskset() { echo 'unexpected taskset invocation' >&2; return 1; }
cat() {
  case "$1" in
    */cpu[0-5]/cpufreq/*) echo 3630000 ;;
    */cpu[67]/cpufreq/*) echo 4610000 ;;
    *) return 1 ;;
  esac
}
'''
        paths = ' '.join(f'/sys/devices/system/cpu/cpu{i}/cpufreq/cpuinfo_max_freq' for i in range(8))
        function = function.replace('/sys/devices/system/cpu/cpu[0-9]*/cpufreq/cpuinfo_max_freq', paths)
        env = dict(os.environ, mode=mode, BL_GAME_CPUS=cpus, BL_PIN_CORES=pin)
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


if __name__ == '__main__':
    unittest.main()
