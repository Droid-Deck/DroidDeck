from pathlib import Path
import tempfile
import unittest

from tools.tests.test_client_zram_focus import function_source, run_bash


class MemoryPressureTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.memory()

    def memory(self, available=1024, swap_free=4096, total=7168, swap_total=6144, psi='0.00'):
        (self.root / 'meminfo').write_text(
            f'MemTotal: {total * 1024} kB\nMemAvailable: {available * 1024} kB\n'
            f'SwapTotal: {swap_total * 1024} kB\nSwapFree: {swap_free * 1024} kB\n')
        (self.root / 'pressure').write_text(f'some avg10={psi} avg60=0.00 avg300=0.00 total=1234\n')

    def run_policy(self, body):
        functions = '\n'.join(function_source(name) for name in (
            'bl_memory_read', 'bl_memory_tight', 'bl_client_can_pageout',
            'bl_client_can_readback', 'bl_client_pressure'))
        functions = functions.replace('/proc/meminfo', str(self.root / 'meminfo'))
        functions = functions.replace('/proc/pressure/memory', str(self.root / 'pressure'))
        harness = '''
set -u
_bl_pids=(123) _bl_game=1 _bl_packed=-1 _bl_pressure_at=-1 _bl_pressure_backoff=30
rss=$((384 * 1024)) client_swap=$((512 * 1024)) reclaimed=$((128 * 1024))
step() { :; }
bl_zram_usage() { _bl_rss=$rss _bl_swap=$client_swap; }
bl_zram_send() { echo "send:$1:$3"; rss=$((rss - reclaimed)); }
''' + functions + '\nbl_memory_read\n' + body
        result = run_bash(harness)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('', result.stderr)
        return result.stdout.splitlines()

    def test_pressure_reclaims_before_the_focus_delay_and_respects_cooldown(self):
        self.assertEqual(['send:1:client', 'send:1:client'], self.run_policy('''
SECONDS=0; bl_client_pressure
SECONDS=5; bl_client_pressure
SECONDS=29; bl_client_pressure
SECONDS=30; bl_client_pressure
'''))

    def test_no_game_or_disabled_client_compression_does_not_reclaim(self):
        self.assertEqual([], self.run_policy('''
_bl_game=0; bl_client_pressure
_bl_game=1 BL_ZRAM_CLIENT=0; bl_client_pressure
'''))

    def test_high_available_memory_does_not_reclaim_even_with_stalls(self):
        self.memory(available=4096, psi='12.34')
        self.assertEqual([], self.run_policy('bl_client_pressure'))

    def test_psi_reclaims_with_moderate_headroom(self):
        self.memory(available=2048, psi='01.23')
        self.assertEqual(['send:1:client'], self.run_policy('bl_client_pressure'))

    def test_missing_psi_still_uses_memory_headroom(self):
        (self.root / 'pressure').unlink()
        self.assertEqual(['send:1:client'], self.run_policy('bl_client_pressure'))

    def test_missing_meminfo_never_reclaims_or_prefetches(self):
        (self.root / 'meminfo').write_text('SwapTotal: 6291456 kB\nSwapFree: 4194304 kB\n')
        self.assertEqual(['no'], self.run_policy('''
bl_client_pressure
if bl_client_can_readback; then echo yes; else echo no; fi
'''))

    def test_missing_available_memory_does_not_trigger_pressure(self):
        p = self.root / 'meminfo'
        p.write_text('\n'.join(line for line in p.read_text().splitlines() if not line.startswith('MemAvailable:')))
        self.assertEqual([], self.run_policy('bl_client_pressure'))

    def test_swap_reserve_includes_the_clients_resident_memory(self):
        self.memory(swap_free=1536)
        self.assertEqual(['no'], self.run_policy('''
bl_client_pressure
if bl_client_can_pageout; then echo yes; else echo no; fi
'''))

    def test_without_swap_automatic_pageout_is_disabled(self):
        self.memory(swap_total=0, swap_free=0)
        self.assertEqual([], self.run_policy('bl_client_pressure'))

    def test_recent_periodic_pageout_prevents_an_immediate_pressure_repeat(self):
        self.assertEqual([], self.run_policy('_bl_packed=$SECONDS; bl_client_pressure'))

    def test_ineffective_reclaim_backs_off_and_success_resets_it(self):
        self.assertEqual(['send:1:client', 'backoff:60', 'send:1:client', 'backoff:30'], self.run_policy('''
reclaimed=0
SECONDS=0; bl_client_pressure
echo "backoff:$_bl_pressure_backoff"
SECONDS=30; bl_client_pressure
reclaimed=$((128 * 1024))
SECONDS=60; bl_client_pressure
echo "backoff:$_bl_pressure_backoff"
'''))

    def test_readback_requires_room_for_the_swapped_client_plus_headroom(self):
        self.memory(available=2048)
        self.assertEqual(['yes', 'no'], self.run_policy('''
if bl_client_can_readback; then echo yes; else echo no; fi
client_swap=$((1024 * 1024))
if bl_client_can_readback; then echo yes; else echo no; fi
'''))

    def test_pressure_prevents_prefetch_even_if_the_client_would_fit(self):
        self.memory(available=2048, psi='2.00')
        self.assertEqual(['no'], self.run_policy('if bl_client_can_readback; then echo yes; else echo no; fi'))


if __name__ == '__main__':
    unittest.main()
