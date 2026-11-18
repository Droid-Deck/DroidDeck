from pathlib import Path
import tempfile
import unittest

from tools.tests.test_client_zram_focus import STATE, function_source, run_bash

MB = 1024


class MemoryPressureTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def sample(self, available=3000, total=7168, stall=0, zram=(6144, 0), other=(4096, 0), stored=None, swapped_in=None):
        lines = [f'MemTotal: {total * MB} kB', f'MemAvailable: {available * MB} kB',
                 f'SwapTotal: {(zram[0] + other[0]) * MB} kB',
                 f'SwapFree: {(zram[0] - zram[1] + other[0] - other[1]) * MB} kB']
        swaps = ['Filename Type Size Used Priority']
        if zram[0]:
            swaps.append(f'/dev/block/zram0 partition {zram[0] * MB} {zram[1] * MB} 100')
        if other[0]:
            swaps.append(f'/data/swap/swapfile file {other[0] * MB} {other[1] * MB} 50')
        text = "printf '%s\\n' " + ' '.join(f"'{line}'" for line in lines) + f" > {self.root}/meminfo\n"
        text += "printf '%s\\n' " + ' '.join(f"'{line}'" for line in swaps) + f" > {self.root}/swaps\n"
        text += f"echo 'some avg10=0.00 avg60=0.00 avg300=0.00 total={stall}' > {self.root}/pressure\n"
        if swapped_in is None:
            text += f'rm -f {self.root}/vmstat\n'
        else:
            text += f"printf '%s\\n' 'pgfault 9' 'pswpin {swapped_in * MB // 4}' 'pswpout 7' > {self.root}/vmstat\n"
        if stored is None:
            text += f'rm -f {self.root}/mm_stat\n'
        else:
            text += f'echo "{stored[0] << 20} {stored[1] << 20} {stored[1] << 20} 0 0 0 0 0 0" > {self.root}/mm_stat\n'
        return text

    def run_policy(self, body, *names):
        functions = '\n'.join(function_source(name) for name in ('dd_memory_read', 'dd_memory_need') + names)
        for name in ('meminfo', 'swaps', 'pressure/memory', 'vmstat'):
            functions = functions.replace('/proc/' + name, str(self.root / name.split('/')[0]))
        functions = functions.replace('/sys/block/zram0/mm_stat', str(self.root / 'mm_stat'))
        functions = functions.replace('/tmp/.dd-zram', str(self.root / 'ctl'))
        result = run_bash(('NOW=0\nset -u\n' + STATE + functions + '\n' + body).replace('SECONDS', 'NOW'))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('', result.stderr)
        return result.stdout.splitlines()

    def test_the_floor_follows_device_memory_within_limits(self):
        body = ''.join(self.sample(total=total) + 'dd_memory_read; echo $((_dd_floor / 1024))\n'
                       for total in (3584, 7168, 15360))
        self.assertEqual(['640', '896', '1280'], self.run_policy(body))

    def test_the_target_grows_with_how_fast_memory_is_falling(self):
        body = (self.sample(available=3000) + 'dd_memory_read; echo $_dd_rate $((_dd_target / 1024))\n' +
                self.sample(available=2800) + 'NOW=1; dd_memory_read; echo $((_dd_rate / 1024)) $((_dd_target / 1024))\n' +
                'NOW=2; dd_memory_read; echo $((_dd_rate / 1024)) $((_dd_target / 1024))\n' +
                self.sample(available=3400) + 'NOW=3; dd_memory_read; echo $((_dd_rate / 1024))\n')
        self.assertEqual(['0 896', '200 2896', '150 2396', '112'], self.run_policy(body))

    def test_memory_the_monitor_freed_itself_does_not_hide_a_fall(self):
        body = (self.sample(available=2000) + 'dd_memory_read\n_dd_freed=$((300 * 1024))\n' +
                'NOW=2; dd_memory_read; echo $((_dd_rate / 1024)) $_dd_freed\n')
        self.assertEqual(['150 0'], self.run_policy(body))

    def test_two_reads_in_the_same_second_keep_the_sample(self):
        body = (self.sample(available=2000) + 'dd_memory_read\n_dd_freed=4096\n' +
                self.sample(available=1000) + 'dd_memory_read; echo $_dd_rate $_dd_freed $_dd_sample_available\n')
        self.assertEqual([f'0 4096 {2000 * MB}'], self.run_policy(body))

    def test_stall_time_becomes_a_share_of_the_interval(self):
        body = (self.sample(stall=1000) + 'dd_memory_read; echo $_dd_psi\n' +
                self.sample(stall=41000) + 'NOW=2; dd_memory_read; echo $_dd_psi\n' +
                'NOW=3; dd_memory_read; echo $_dd_psi\n')
        self.assertEqual(['0', '200', '0'], self.run_policy(body))

    def test_room_counts_zram_only_and_keeps_a_reserve(self):
        body = (self.sample(zram=(6144, 1024)) + 'dd_memory_read; echo $((_dd_swap_room / 1024))\n' +
                self.sample(zram=(6144, 5632)) + 'dd_memory_read; echo $((_dd_swap_room / 1024))\n' +
                self.sample(zram=(0, 0), other=(4096, 1024)) + 'dd_memory_read; echo $((_dd_swap_room / 1024))\n' +
                self.sample(zram=(512, 0), other=(0, 0)) + 'dd_memory_read; echo $((_dd_swap_room / 1024))\n' +
                self.sample(zram=(0, 0), other=(4096, 1024)) + f'rm {self.root}/swaps\n' +
                'dd_memory_read; echo $((_dd_swap_room / 1024))\n')
        self.assertEqual(['4352', '-256', '-128', '384', '2560'], self.run_policy(body))

    def test_memory_read_back_from_swap_is_remembered_for_half_a_minute(self):
        body = '_dd_page_kb=4\n' + ''.join(
            f'NOW={now}\n' + self.sample(swapped_in=total) + 'dd_memory_read; echo $((_dd_returned / 1024))\n'
            for now, total in ((0, 500), (1, 800), (2, 800), (16, 800), (17, None), (18, 900), (19, 1000), (60, 1000)))
        self.assertEqual(['0', '300', '290', '154', '149', '144', '239', '0'], self.run_policy(body))

    def test_saving_comes_from_what_zram_really_stores(self):
        body = ''.join(self.sample(stored=stored) + 'dd_memory_read; echo $_dd_saving\n'
                       for stored in (None, (32, 30), (1000, 250), (1000, 950), (1000, 20)))
        self.assertEqual(['600', '600', '750', '167', '833'], self.run_policy(body))

    def need(self, available, rate=0, psi=0):
        return (self.sample(available=available) +
                f'dd_memory_read; _dd_rate=$(({rate} * 1024)) _dd_psi={psi}\n'
                '_dd_target=$((_dd_floor + _dd_rate * 10))\n'
                'dd_memory_need; echo $((_dd_need / 1024)) $((_dd_hard / 1024)) $_dd_force\n')

    def test_steady_memory_above_the_floor_needs_nothing(self):
        self.assertEqual(['0 0 0'], self.run_policy(self.need(2000)))

    def test_memory_under_the_floor_must_be_freed(self):
        self.assertEqual(['96 96 0'], self.run_policy(self.need(800)))

    def test_a_slow_fall_is_met_ten_seconds_ahead(self):
        self.assertEqual(['196 0 0'], self.run_policy(self.need(1000, rate=30)))

    def test_a_fast_fall_is_matched_once_memory_is_under_two_fifths(self):
        self.assertEqual(['0 0 0', '200 0 0', '0 0 0'],
                         self.run_policy(self.need(2500, rate=20) + self.need(2500, rate=100) + self.need(4000, rate=100)))

    def test_stalls_make_a_fall_urgent(self):
        self.assertEqual(['200 0 0', '200 200 0', '0 0 0'],
                         self.run_policy(self.need(2500, rate=100) + self.need(2500, rate=100, psi=100) +
                                         self.need(2000, psi=900)))

    def test_half_the_floor_forces(self):
        self.assertEqual(['446 446 0', '456 456 1'], self.run_policy(self.need(450) + self.need(440)))

    RELIEVE = '''
step() { echo "step:$*"; }
dd_session_others() { echo others; _dd_session=(70 80); }
dd_zram_trim() { echo "trim:$1:$(($2 / 1024)):${*:3}"; _dd_trimmed=${paged:-$2}; }
relieve() { dd_memory_read; _dd_returned=${returned:-0}; _dd_rate=$((${2:-0} * 1024)) _dd_target=$((_dd_floor + ${2:-0} * 10240)); dd_memory_relieve; }
_dd_pids=(10 20) _dd_game=1 _dd_focus=0 _dd_saving=500
'''

    def relieve(self, body):
        return self.run_policy(self.RELIEVE + body, 'dd_memory_relieve', 'dd_memory_note')

    def test_a_fall_trims_the_client_behind_a_game_by_what_zram_gives_back(self):
        self.assertEqual(['trim:2:326:10 20', f'{196 * MB} {334506 * 600 // 1000} 334506'], self.relieve(
            self.sample(available=1000) + 'relieve 1000 30; echo $_dd_need $_dd_freed $_dd_client_out\n'))

    def test_the_client_in_front_is_left_alone_until_memory_is_under_the_floor(self):
        self.assertEqual(['trim:2:160:10 20'], self.relieve(
            self.sample(available=1000) + '_dd_focus=1; relieve 1000 30\n' +
            self.sample(available=800) + 'relieve 800\n'))

    def test_unknown_focus_still_trims_the_client(self):
        self.assertEqual(['trim:2:326:10 20'], self.relieve(self.sample(available=1000) + '_dd_focus=2; relieve 1000 30\n'))

    def test_the_client_is_not_trimmed_right_after_it_was_read_back(self):
        self.assertEqual(['trim:2:326:10 20'], self.relieve(
            self.sample(available=1000) + 'NOW=100 _dd_back_at=80; relieve 1000 30\nNOW=110; relieve 1000 30\n'))

    def test_without_a_game_only_the_floor_trims(self):
        self.assertEqual(['trim:2:160:10 20'], self.relieve(
            self.sample(available=1000) + '_dd_game=0 _dd_focus=1; relieve 1000 30\n' +
            self.sample(available=800) + 'relieve 800\n'))

    def test_the_session_gives_what_the_client_could_not(self):
        self.assertEqual(['trim:2:660:10 20', 'others', 'trim:2:256:70 80', 'trim:2:660:10 20', 'trim:2:256:70 80'],
                         self.relieve(self.sample(available=500) + f'paged={64 * MB}; relieve 500\nNOW=1; relieve 500\n'))

    def test_memory_that_keeps_coming_back_is_taken_off_what_a_pass_may_move(self):
        self.assertEqual(['trim:2:660:10 20', 'others', 'trim:2:156:70 80', 'trim:2:660:10 20', 'trim:2:24:10 20'], self.relieve(
            self.sample(available=500) + f'paged={64 * MB}\nreturned={100 * MB}; relieve 500\n' +
            f'NOW=1 returned={300 * MB}; relieve 500\nNOW=2 returned={1000 * MB}; relieve 500\n'))

    def test_forcing_moves_memory_whatever_came_back(self):
        self.assertEqual(['trim:3:993:10 20', 'others', 'trim:3:993:70 80'], self.relieve(
            self.sample(available=300) + f'paged=0 returned={2048 * MB}; relieve 300\n'))

    def test_the_session_is_scanned_again_after_a_new_process_scan(self):
        self.assertEqual(['others', 'others'], [line for line in self.relieve(
            self.sample(available=500) + 'paged=0; relieve 500\n_dd_scan_at=5; relieve 500\n') if line == 'others'])

    def test_forcing_ignores_heat_once_in_ten_seconds(self):
        lines = self.relieve(self.sample(available=300) + 'paged=0\nrelieve 300\nNOW=5; relieve 300\nNOW=10; relieve 300\n')
        self.assertEqual(['3', '3', '2', '2', '3', '3'], [line.split(':')[1] for line in lines if line.startswith('trim')])
        self.assertIn('trim:3:993:70 80', lines)

    def test_trims_stop_at_the_room_zram_has(self):
        self.assertEqual(['trim:2:100:10 20', 'step:memory: available 800 MB and zRAM has no room left'], self.relieve(
            self.sample(available=800, zram=(6144, 5276)) + 'relieve 800\n' +
            self.sample(available=800, zram=(6144, 5400)) + 'relieve 800\nNOW=30; relieve 800\n'))

    def test_small_trims_are_not_logged(self):
        self.assertEqual(['step:memory: available 1000 MB, falling 30 MB/s, compressed 20 MB of the client and 0 MB of the session'],
                         [line for line in self.relieve(
                             self.sample(available=1000) + f'paged={10 * MB}\nrelieve 1000 30; dd_memory_note\n'
                             'NOW=1; relieve 1000 30; dd_memory_note\nNOW=2; dd_memory_note\n')
                          if line.startswith('step')])

    TRIM = '''
declare -A anon=() reply=()
nap() { :; }
dd_zram_usage() { _dd_anon=${anon[$1]}; }
kill() {
  echo "signal:$2:$(< "$ROOT/ctl")"
  case ${reply[$2]} in
    deaf) ;;
    gone) return 1 ;;
    *) printf %s "${reply[$2]}" > "$ROOT/ctl.ack.$2" ;;
  esac
}
'''

    def trim(self, body):
        return self.run_policy(f'ROOT={self.root}\n' + self.TRIM + body + '\nls "$ROOT"; cat "$ROOT/ctl"', 'dd_zram_trim')

    def test_a_trim_spends_its_budget_across_processes_in_order(self):
        self.assertEqual(['signal:10:2 100000', 'signal:20:2 60000', '100000', 'ctl', '2 0'], self.trim('''
anon=([10]=500000 [20]=500000 [30]=500000) reply=([10]=40000 [20]=60000 [30]=1)
dd_zram_trim 2 100000 10 20 30
echo $_dd_trimmed'''))

    def test_a_trim_skips_small_and_gone_processes_and_stops_when_little_is_left(self):
        self.assertEqual(['signal:20:3 50000', 'signal:30:3 50000', 'signal:40:3 50000', '47000', 'ctl', '2 0'], self.trim('''
anon=([10]=4096 [20]=500000 [30]=500000 [40]=500000 [50]=500000) reply=([20]=gone [30]=0 [40]=47000 [50]=1)
dd_zram_trim 3 50000 10 20 30 40 50
echo $_dd_trimmed'''))

    def test_a_process_that_never_answers_is_left_alone_for_a_minute(self):
        self.assertEqual(['signal:10:2 9000', 'signal:20:2 9000', '500', 'signal:20:2 9000', 'signal:10:2 9000',
                          'signal:20:2 9000', 'ctl', '2 0'], self.trim('''
anon=([10]=500000 [20]=500000) reply=([10]=deaf [20]=500)
dd_zram_trim 2 9000 10 20
echo $_dd_trimmed
NOW=59; dd_zram_trim 2 9000 10 20
NOW=60; dd_zram_trim 2 9000 10 20'''))

    READBACK = '''
step() { echo "step:$*"; }
kill() { echo "signal:$*:$(< "$ROOT/ctl")"; }
dd_zram_usage() { _dd_swap=$((swap * 1024)); }
back() { dd_memory_read; _dd_rate=0 _dd_target=$_dd_floor; dd_client_readback; }
_dd_pids=(10 20) _dd_game=0 _dd_client_out=4096 _dd_trim_at=0 swap=400 NOW=30
'''

    def readback(self, body):
        return self.run_policy(f'ROOT={self.root}\n' + self.READBACK + body, 'dd_client_readback')

    def test_the_client_is_read_back_once_memory_can_hold_it_with_a_floor_to_spare(self):
        self.assertEqual(['step:memory: available 2192 MB, reading 400 MB of the client back', 'signal:-URG 10 20:0',
                          f'0 {-400 * MB * 600 // 1000} 30'], self.readback(
            self.sample(available=2191) + 'back\n' + self.sample(available=2192) + 'back\n' +
            'echo $_dd_client_out $_dd_freed $_dd_back_at\nback\n'))

    def test_nothing_is_read_back_right_after_a_trim(self):
        self.assertEqual([], self.readback(self.sample(available=4000) + 'NOW=29; back\n'))

    def test_a_running_game_keeps_the_client_compressed_until_the_client_is_in_front(self):
        self.assertEqual(['signal:-URG 10 20:0'], [line for line in self.readback(
            self.sample(available=4000) + '_dd_game=1\nback\n_dd_away=27; back\n_dd_away=25; back\n')
            if line.startswith('signal')])

    def test_a_client_the_kernel_already_brought_back_needs_no_request(self):
        self.assertEqual(['0'], self.readback(self.sample(available=4000) + 'swap=8; back; echo $_dd_client_out\n'))


if __name__ == '__main__':
    unittest.main()
