import json
import os
from pathlib import Path
import runpy
import tempfile
import unittest


BIN = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin'
MODULE = runpy.run_path(str(BIN / 'droiddeck-launcher-cores'))
Holder = MODULE['Holder']
Governor = MODULE['Governor']
NICE = MODULE['NICE']

HELPER = 'C:\\Program Files\\Rockstar Games\\Social Club\\SocialClubHelper.exe'
LAUNCHER = 'C:\\Program Files\\Rockstar Games\\Launcher\\Launcher.exe'
GAME = 'S:\\steamapps\\common\\Red Dead Redemption 2\\RDR2.exe'
ALL = set(range(8))


class FakeCores:
    """Per-thread masks and priorities, with every change recorded."""

    def __init__(self):
        self.state = {}
        self.calls = []

    def own(self):
        return set(ALL)

    def get(self, tid):
        if tid not in self.state:
            raise ProcessLookupError(tid)
        return self.state[tid]

    def set(self, tid, cpus, nice):
        if tid not in self.state:
            raise ProcessLookupError(tid)
        self.calls.append((tid, set(cpus), nice))
        self.state[tid] = (set(cpus), nice)


class LauncherCoresTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.proc = self.root / 'proc'
        self.proc.mkdir()
        cpufreq = self.root / 'cpu'
        for cpu in ALL:
            (cpufreq / f'cpu{cpu}/cpufreq').mkdir(parents=True)
            (cpufreq / f'cpu{cpu}/cpufreq/cpuinfo_max_freq').write_text('3532800\n' if cpu < 6 else '4320000\n')
        self.cores = FakeCores()
        self.holder = Holder(self.proc, cpufreq, self.cores)
        self.focus = self.root / 'focus.json'
        self.now = 0.0
        self.governor = Governor(self.focus, self.holder, clock=lambda: self.now)

    def process(self, pid, argv0, threads=(), cpus=ALL, nice=0):
        directory = self.proc / str(pid)
        (directory / 'task').mkdir(parents=True)
        (directory / 'cmdline').write_bytes(argv0.encode() + b'\0--type=renderer\0')
        for tid in (pid, *threads):
            self.thread(pid, tid, cpus, nice)

    def thread(self, pid, tid, cpus=ALL, nice=0):
        (self.proc / str(pid) / 'task' / str(tid)).mkdir()
        self.cores.state[tid] = (set(cpus), nice)

    def end(self, pid):
        for tid in os.listdir(self.proc / str(pid) / 'task'):
            self.cores.state.pop(int(tid), None)
            (self.proc / str(pid) / 'task' / tid).rmdir()
        (self.proc / str(pid) / 'task').rmdir()
        (self.proc / str(pid) / 'cmdline').unlink()
        (self.proc / str(pid)).rmdir()

    def focus_on(self, pid, app=1174180):
        stamp = getattr(self, 'stamp', 1_000_000_000) + 1_000_000_000
        self.stamp = stamp
        self.focus.write_text(json.dumps({
            'focusedApp': app, 'focusedWindow': 0x200001,
            'focusableWindows': [{'window': 0x100001, 'appId': 769, 'pid': 9},
                                 {'window': 0x200001, 'appId': app, 'pid': pid}]}))
        os.utime(self.focus, ns=(stamp, stamp))

    def held(self, tid):
        return self.cores.state[tid] == ({5}, NICE)

    def test_helpers_held_on_the_slow_clusters_last_core_while_the_game_has_focus(self):
        self.process(100, LAUNCHER, (101,))
        self.process(200, HELPER, (201, 202), nice=-2)
        self.process(300, HELPER, (301,))
        self.process(400, GAME, (401,))
        self.focus_on(400)
        self.governor.tick()
        for tid in (200, 201, 202, 300, 301):
            self.assertTrue(self.held(tid), tid)
        for tid in (100, 101, 400, 401):
            self.assertEqual((ALL, 0), self.cores.state[tid])

    def test_launcher_focus_gives_each_thread_its_own_mask_and_priority_back(self):
        self.process(100, LAUNCHER)
        self.process(200, HELPER, (201,), cpus={0, 1, 2, 3}, nice=-2)
        self.cores.state[201] = ({6, 7}, 4)
        self.process(400, GAME)
        self.focus_on(400)
        self.governor.tick()
        self.assertTrue(self.held(201))
        self.focus_on(100)
        self.governor.tick()
        self.assertEqual(({0, 1, 2, 3}, -2), self.cores.state[200])
        self.assertEqual(({6, 7}, 4), self.cores.state[201])
        self.assertEqual({}, self.holder.held)

    def test_nothing_is_touched_before_a_game_has_focus(self):
        self.process(100, LAUNCHER)
        self.process(200, HELPER)
        self.process(9, '/root/.local/share/Steam/steamrtarm64/steam')
        self.governor.tick()
        self.focus_on(9, app=769)
        self.governor.tick()
        self.focus_on(100)
        self.governor.tick()
        self.assertEqual([], self.cores.calls)

    def test_a_launcher_left_after_its_game_stays_held_behind_the_client(self):
        self.process(200, HELPER)
        self.process(400, GAME)
        self.process(9, '/root/.local/share/Steam/steamrtarm64/steam')
        self.focus_on(400)
        self.governor.tick()
        self.end(400)
        self.focus_on(9, app=769)
        self.governor.tick()
        self.assertTrue(self.held(200))

    def test_a_thread_inheriting_the_hold_is_released_to_what_its_process_had(self):
        self.process(200, HELPER, cpus={0, 1, 2, 3, 4, 5}, nice=1)
        self.process(400, GAME)
        self.focus_on(400)
        self.governor.tick()
        self.thread(200, 203, {5}, NICE)
        self.process(500, HELPER, (501,), cpus={5}, nice=NICE)
        self.now += 10
        self.governor.tick()
        self.focus_on(200)
        self.governor.tick()
        for tid in (200, 203, 500, 501):
            self.assertEqual(({0, 1, 2, 3, 4, 5}, 1), self.cores.state[tid], tid)

    def test_a_held_thread_the_program_moves_back_is_held_again_on_the_next_look(self):
        self.process(200, HELPER, (201,))
        self.process(400, GAME)
        self.focus_on(400)
        self.governor.tick()
        self.cores.state[201] = (ALL, -5)
        self.now += 5
        self.governor.tick()
        self.assertFalse(self.held(201))
        self.now += 5
        self.governor.tick()
        self.assertTrue(self.held(201))
        self.focus_on(200)
        self.governor.tick()
        self.assertEqual((ALL, 0), self.cores.state[201])

    def test_a_steady_hold_is_not_rewritten(self):
        self.process(200, HELPER, (201,))
        self.process(400, GAME)
        self.focus_on(400)
        self.governor.tick()
        calls = len(self.cores.calls)
        for _ in range(5):
            self.now += 10
            self.governor.tick()
        self.assertEqual(calls, len(self.cores.calls))

    def test_threads_that_end_are_skipped_and_ended_helpers_forgotten(self):
        self.process(200, HELPER, (201,))
        self.process(300, HELPER)
        self.process(400, GAME)
        del self.cores.state[201]
        self.focus_on(400)
        self.governor.tick()
        self.assertNotIn(201, self.holder.held[200])
        self.end(300)
        self.now += 10
        self.governor.tick()
        self.assertEqual([200], list(self.holder.held))

    def test_stop_releases(self):
        self.process(200, HELPER)
        self.process(400, GAME)
        self.focus_on(400)
        self.governor.tick()
        with self.assertRaises(SystemExit):
            self.governor.stop()
        self.assertEqual((ALL, 0), self.cores.state[200])

    def test_unreadable_focus_is_ignored(self):
        self.process(200, HELPER)
        self.focus.write_text('{"focusedApp": 1174')
        self.governor.tick()
        self.focus.write_text('[]')
        os.utime(self.focus, ns=(5, 5))
        self.governor.tick()
        self.assertEqual([], self.cores.calls)

    def test_window_without_a_pid_is_left_alone(self):
        self.process(200, HELPER)
        self.focus.write_text(json.dumps({'focusedApp': 1174180, 'focusedWindow': 7, 'focusableWindows': []}))
        self.governor.tick()
        self.assertEqual([], self.cores.calls)


if __name__ == '__main__':
    unittest.main()
