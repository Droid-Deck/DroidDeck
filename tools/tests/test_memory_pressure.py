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
bl_zram_send() { echo "send:$1:$3"; rss=$((rss - reclaimed)); _bl_pageout_complete=${pageout_complete:-1}; }
''' + functions + '\nbl_memory_read\n' + body
        result = run_bash('NOW=0\n' + harness.replace('SECONDS', 'NOW'))
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

    def test_unfinished_pageout_does_not_increase_the_ineffective_reclaim_backoff(self):
        self.assertEqual(['send:1:client', 'backoff:30'], self.run_policy('''
reclaimed=0 pageout_complete=0
SECONDS=0; bl_client_pressure
echo "backoff:$_bl_pressure_backoff"
'''))

    def test_pageout_completion_requires_a_finished_ack_from_every_live_target(self):
        proc = self.root / 'proc'
        proc.mkdir()
        for pid in (123, 456):
            (proc / str(pid)).mkdir()
        source = function_source('bl_zram_wait').replace('/proc/', str(proc) + '/')
        source = source.replace('/tmp/.bl-zram.ack.', str(self.root / 'ack.'))
        for pending in ('done', 'running', 'not_started'):
            with self.subTest(pending=pending):
                (self.root / 'ack.123').write_text('1')
                if pending != 'not_started':
                    (self.root / 'ack.456').write_text('1' if pending == 'done' else '')
                result = run_bash('nap() { SECONDS=$((SECONDS + 1)); }\n' + source +
                                  '\nSECONDS=0; bl_zram_wait 123 456; echo "$_bl_pageout_complete"')
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual('1' if pending == 'done' else '0', result.stdout.strip())

    def test_pressure_prevents_prefetch_even_if_the_client_would_fit(self):
        self.memory(available=2048, psi='2.00')
        self.assertEqual(['no'], self.run_policy('if bl_client_can_readback; then echo yes; else echo no; fi'))


class SessionPressureTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.memory()

    def memory(self, available=1024, swap_free=6144, total=7168, swap_total=8192, psi='0.00'):
        (self.root / 'meminfo').write_text(
            f'MemTotal: {total * 1024} kB\nMemAvailable: {available * 1024} kB\n'
            f'SwapTotal: {swap_total * 1024} kB\nSwapFree: {swap_free * 1024} kB\n')
        (self.root / 'pressure').write_text(f'some avg10={psi} avg60=0.00 avg300=0.00 total=1234\n')

    def run_policy(self, body):
        functions = '\n'.join(function_source(name) for name in (
            'bl_memory_read', 'bl_memory_tight', 'bl_memory_critical', 'bl_low_ram',
            'bl_session_pressure', 'bl_watch_nap', 'bl_zram_pick', 'bl_tier_note', 'bl_tier_readback'))
        functions = functions.replace('/proc/meminfo', str(self.root / 'meminfo'))
        functions = functions.replace('/proc/pressure/memory', str(self.root / 'pressure'))
        harness = '''
set -u
_bl_game=1 _bl_tier_at=-1 _bl_tier_backoff=30 _bl_tier_pushed=0 _bl_tier_back=0 _bl_tier_targets=() _bl_tier_swaps=() _bl_tier_starts=() _bl_pids=(999)
_bl_pressure_at=-1 _bl_packed=-1
rss=$((1536 * 1024)) swap=0 pushed=$((1024 * 1024)) alive=999 started=100 complete=1
step() { :; }
bl_proc_start() { _bl_start=; case " $alive " in *" $1 "*) _bl_start=$started; return 0 ;; esac; return 1; }
nap() { echo "nap:$1"; }
bl_session_others() { _bl_session=(321 654); }
bl_zram_usage() { _bl_rss=$rss _bl_swap=$swap; }
bl_zram_send() { echo "send:$1:$3"; rss=$((rss - pushed)); swap=$((swap + pushed)); _bl_pageout_complete=$complete; }
''' + functions + '\nbl_memory_read\n' + body
        result = run_bash('NOW=0\n' + harness.replace('SECONDS', 'NOW'))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('', result.stderr)
        return result.stdout.splitlines()

    def test_low_headroom_compresses_the_game_session_at_most_every_30_seconds(self):
        self.assertEqual(['send:1:game', 'send:1:game'], self.run_policy('''
SECONDS=0; bl_session_pressure
SECONDS=10; bl_session_pressure
SECONDS=29; bl_session_pressure
SECONDS=30; bl_session_pressure
'''))

    def test_twelve_gigabyte_devices_are_included_and_larger_ones_are_not(self):
        for total, expected in ((11264, ['send:1:game']), (12288, ['send:1:game']), (15360, [])):
            with self.subTest(total=total):
                self.memory(total=total)
                self.assertEqual(expected, self.run_policy('bl_session_pressure'))

    def test_no_game_does_not_compress_the_session(self):
        self.assertEqual([], self.run_policy('_bl_game=0; bl_session_pressure'))

    def test_enough_available_memory_does_not_compress_even_with_stalls(self):
        self.memory(available=3072, psi='5.00')
        self.assertEqual([], self.run_policy('bl_session_pressure'))

    def test_stalls_with_moderate_headroom_compress_before_the_hard_limit(self):
        self.memory(available=2048, psi='1.50')
        self.assertEqual(['send:1:game'], self.run_policy('bl_session_pressure'))

    def test_moderate_headroom_without_stalls_waits(self):
        self.memory(available=2048)
        self.assertEqual([], self.run_policy('bl_session_pressure'))

    def test_game_reading_back_most_of_a_pageout_skips_and_doubles_the_wait(self):
        self.assertEqual(['send:1:game', 'backoff:60', 'send:1:game', 'backoff:60',
                          'send:1:game', 'backoff:30'], self.run_policy('''
pushed=$((512 * 1024))
SECONDS=0; bl_session_pressure
swap=$((swap / 4))
SECONDS=30; bl_session_pressure
echo "backoff:$_bl_tier_backoff"
SECONDS=60; bl_session_pressure
SECONDS=90; bl_session_pressure
echo "backoff:$_bl_tier_backoff"
SECONDS=150; bl_session_pressure
echo "backoff:$_bl_tier_backoff"
'''))

    def test_repeated_read_back_keeps_doubling_up_to_five_minutes(self):
        self.assertEqual(['backoff:300'], self.run_policy('''
_bl_tier_backoff=240 _bl_tier_at=0 _bl_tier_targets=(999) _bl_tier_swaps=($((1024 * 1024))) _bl_tier_starts=(100) _bl_tier_pushed=$((1024 * 1024)) swap=0
SECONDS=240; bl_session_pressure
echo "backoff:$_bl_tier_backoff"
'''))

    def test_nearly_out_of_memory_ignores_the_backoff_but_keeps_30_seconds(self):
        self.memory(available=512)
        self.assertEqual(['send:1:game', 'send:1:game'], self.run_policy('''
_bl_tier_backoff=300
SECONDS=0; bl_session_pressure
SECONDS=20; bl_session_pressure
SECONDS=30; bl_session_pressure
'''))

    def test_nearly_out_of_memory_still_backs_off_when_the_game_reads_its_pageout_back(self):
        self.memory(available=512)
        self.assertEqual(['send:1:game', 'backoff:60', 'send:1:game'], self.run_policy('''
SECONDS=0; bl_session_pressure
swap=0
SECONDS=30; bl_session_pressure
echo "backoff:$_bl_tier_backoff"
SECONDS=59; bl_session_pressure
SECONDS=60; bl_session_pressure
SECONDS=90; bl_session_pressure
'''))

    def test_nearly_out_of_memory_retries_a_skipped_pageout_after_30_seconds(self):
        self.memory(available=512)
        self.assertEqual(['send:1:game', 'send:1:game'], self.run_policy('''
SECONDS=0; bl_session_pressure
swap=0
SECONDS=30; bl_session_pressure
_bl_tier_backoff=300
saved=$rss; rss=$((128 * 1024))
SECONDS=330; bl_session_pressure
rss=$saved
SECONDS=359; bl_session_pressure
SECONDS=360; bl_session_pressure
'''))

    def test_small_swap_headroom_skips_the_pageout(self):
        self.memory(swap_free=1536, swap_total=2048)
        self.assertEqual([], self.run_policy('bl_session_pressure'))

    def test_small_session_is_left_alone(self):
        self.assertEqual([], self.run_policy('rss=$((128 * 1024)); bl_session_pressure'))

    def test_watch_polls_every_second_near_the_limit_and_stops_when_critical(self):
        self.memory(available=2560)
        self.assertEqual(['nap:1', 'nap:1', 'nap:1', 'nap:1', 'nap:1'], self.run_policy('bl_watch_nap'))
        self.memory(available=1024)
        self.assertEqual(['nap:1'], self.run_policy('_bl_available=$((2560 * 1024)); bl_watch_nap'))
        self.memory(available=4096)
        self.assertEqual(['nap:5'], self.run_policy('bl_watch_nap'))
        self.assertEqual(['nap:5'], self.run_policy('_bl_game=0; bl_watch_nap'))

    def test_one_push_compresses_the_game_and_the_launcher_and_restarts_the_launcher_wait(self):
        self.assertEqual(['send:1:game', 'pids:321 654 999', 'client:0:0'], self.run_policy('''
SECONDS=0; bl_session_pressure
bl_zram_pick game; echo "pids:${_pids[*]}"
echo "client:$_bl_pressure_at:$_bl_packed"
'''))

    def test_launcher_compression_turned_off_keeps_the_launcher_out(self):
        self.assertEqual(['pids:321 654', 'client:-1:-1'], self.run_policy('''
BL_ZRAM_CLIENT=0
SECONDS=0; bl_session_pressure
bl_zram_pick game; echo "pids:${_pids[*]}"
echo "client:$_bl_pressure_at:$_bl_packed"
''')[1:])

    def test_low_ram_devices_compress_with_a_quarter_of_memory_left(self):
        self.memory(available=1700)
        self.assertEqual(['send:1:game'], self.run_policy('bl_session_pressure'))
        self.memory(available=1900)
        self.assertEqual([], self.run_policy('bl_session_pressure'))


    def test_a_compressed_process_that_exits_is_not_counted_as_read_back(self):
        self.assertEqual(['send:1:game', 'send:1:game', 'backoff:30'], self.run_policy('''
SECONDS=0; bl_session_pressure
alive=""; swap=0
SECONDS=30; bl_session_pressure
echo "backoff:$_bl_tier_backoff"
'''))

    def test_a_process_that_starts_after_a_pageout_is_not_in_its_read_back(self):
        self.assertEqual(['send:1:game', 'targets:999', 'send:1:game', 'backoff:30'], self.run_policy('''
SECONDS=0; bl_session_pressure
echo "targets:${_bl_tier_targets[*]}"
alive="999 1234"
SECONDS=30; bl_session_pressure
echo "backoff:$_bl_tier_backoff"
'''))

    def test_an_incomplete_pageout_does_not_restart_the_launcher_wait(self):
        self.assertEqual(['send:1:game', 'client:-1:-1'], self.run_policy('''
complete=0
SECONDS=0; bl_session_pressure
echo "client:$_bl_pressure_at:$_bl_packed"
'''))


    def test_a_reused_process_id_is_not_counted_as_read_back(self):
        self.assertEqual(['send:1:game', 'send:1:game', 'backoff:30'], self.run_policy('''
SECONDS=0; bl_session_pressure
started=200; swap=0
SECONDS=30; bl_session_pressure
echo "backoff:$_bl_tier_backoff"
'''))

    def test_process_start_comes_from_stat_and_zombies_have_none(self):
        stat = self.root / 'stat'
        source = function_source('bl_proc_start').replace('/proc/$1/stat', str(stat))
        fields = ' '.join(['0'] * 17)
        for state, expected in (('S', ['ok:4242']), ('Z', ['gone:'])):
            with self.subTest(state=state):
                stat.write_text(f'77 (Wow (arm64).exe) {state} 1 {fields} 4242 99 0\n')
                result = run_bash(source + '\nif bl_proc_start 77; then echo "ok:$_bl_start"; else echo "gone:$_bl_start"; fi')
                self.assertEqual(expected, result.stdout.split())
        stat.unlink()
        result = run_bash(source + '\nbl_proc_start 77 || echo gone')
        self.assertEqual(['gone'], result.stdout.split())


if __name__ == '__main__':
    unittest.main()
