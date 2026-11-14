from pathlib import Path
import runpy
import struct
import tempfile
import unittest


BIN = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin'
MODULE = runpy.run_path(str(BIN / 'droiddeck-pace-cores'))
Frames = MODULE['Frames']
Parker = MODULE['Parker']
Governor = MODULE['Governor']
efficient_cores = MODULE['efficient_cores']
paced_interval = MODULE['paced_interval']

ALL = set(range(8))
EFFICIENT = set(range(6))
VBLANK = 1e9 / 60
CAPACITY = 1024


class FakeCores:

    def __init__(self, tids):
        self.state = {tid: set(ALL) for tid in tids}

    def get(self, tid):
        if tid not in self.state:
            raise ProcessLookupError(tid)
        return set(self.state[tid])

    def set(self, tid, cpus):
        if tid not in self.state:
            raise ProcessLookupError(tid)
        self.state[tid] = set(cpus)


class FeedWriter:

    def __init__(self, path):
        self.path = path
        self.count = 0
        self.data = bytearray(128 + CAPACITY * 16)
        struct.pack_into('<IIII', self.data, 0, 0x46484444, 1, CAPACITY, 0)

    def focus(self, pid, steam=False):
        struct.pack_into('<I', self.data, 12, pid)
        struct.pack_into('<IIII', self.data, 28, 1920, 1080, 60000, 1 if steam else 0)
        self.flush()

    def frames(self, *intervals):
        for value in intervals:
            struct.pack_into('<QQ', self.data, 128 + (self.count % CAPACITY) * 16, int(value), 0)
            self.count += 1
        struct.pack_into('<Q', self.data, 16, self.count)
        self.flush()

    def flush(self):
        self.path.write_bytes(bytes(self.data))


class PacedIntervalTest(unittest.TestCase):
    def test_a_steady_cap_at_30_fps_or_more_is_paced(self):
        self.assertAlmostEqual(paced_interval([VBLANK] * 60), VBLANK)
        self.assertAlmostEqual(paced_interval([1e9 / 30] * 30), 1e9 / 30)
        jitter = [1e9 / 60 * (1 + 0.03 * (-1) ** i) for i in range(60)]
        self.assertIsNotNone(paced_interval(jitter))

    def test_under_30_fps_is_not(self):
        self.assertIsNone(paced_interval([1e9 / 24] * 24))
        self.assertIsNone(paced_interval([1e9 / 15] * 15))

    def test_a_spread_or_missed_frames_are_not(self):
        self.assertIsNone(paced_interval([VBLANK] * 50 + [2 * VBLANK] * 10))
        self.assertIsNone(paced_interval([VBLANK * (0.8 + 0.4 * (i % 5) / 4) for i in range(60)]))
        self.assertIsNone(paced_interval([]))


class EfficientCoresTest(unittest.TestCase):
    def test_everything_below_the_top_capacity(self):
        with tempfile.TemporaryDirectory() as temp:
            cpu = Path(temp)
            (cpu / 'online').write_text('0-7\n')
            for core in ALL:
                (cpu / f'cpu{core}').mkdir()
                (cpu / f'cpu{core}/cpu_capacity').write_text('1024\n' if core >= 6 else '792\n')
            self.assertEqual(efficient_cores(cpu, set(ALL)), EFFICIENT)
            self.assertEqual(efficient_cores(cpu, {6, 7}), set())

    def test_one_tier_has_none(self):
        with tempfile.TemporaryDirectory() as temp:
            cpu = Path(temp)
            (cpu / 'online').write_text('0-3\n')
            for core in range(4):
                (cpu / f'cpu{core}').mkdir()
                (cpu / f'cpu{core}/cpu_capacity').write_text('1024\n')
            self.assertEqual(efficient_cores(cpu, set(range(4))), set())


class GovernorTest(unittest.TestCase):
    PID = 400
    TIDS = (400, 401, 402)

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        for tid in self.TIDS:
            (root / f'proc/{self.PID}/task/{tid}').mkdir(parents=True)
        self.cores = FakeCores(self.TIDS)
        self.feed = FeedWriter(root / 'frames')
        self.feed.focus(self.PID)
        self.now = 0.0
        self.governor = Governor(Frames(root / 'frames'), Parker(EFFICIENT, root / 'proc', self.cores),
                                 clock=lambda: self.now)
        self.governor.tick()

    def run_for(self, seconds, interval):
        for _ in range(int(seconds / 0.5)):
            self.now += 0.5
            self.feed.frames(*([interval] * int(0.5e9 / interval)))
            self.governor.tick()

    def test_parks_a_game_that_holds_its_pace(self):
        self.run_for(4, VBLANK)
        self.assertEqual(self.governor.parker.pid, self.PID)
        self.assertTrue(all(cpus == EFFICIENT for cpus in self.cores.state.values()))

    def test_releases_when_frames_fall_behind_and_backs_off(self):
        self.run_for(4, VBLANK)
        self.run_for(2, 2 * VBLANK)
        self.assertIsNone(self.governor.parker.pid)
        self.assertTrue(all(cpus == ALL for cpus in self.cores.state.values()))
        self.run_for(10, VBLANK)
        self.assertIsNone(self.governor.parker.pid)
        self.run_for(30, VBLANK)
        self.assertEqual(self.governor.parker.pid, self.PID)

    def test_struggling_game_stays_on_every_core(self):
        for step in range(20):
            self.now += 0.5
            self.feed.frames(*([VBLANK, 2 * VBLANK] * 10))
            self.governor.tick()
        self.assertIsNone(self.governor.parker.pid)
        self.assertTrue(all(cpus == ALL for cpus in self.cores.state.values()))

    def test_steam_in_front_releases_without_backoff(self):
        self.run_for(4, VBLANK)
        self.feed.focus(self.PID, steam=True)
        self.now += 0.5
        self.governor.tick()
        self.assertTrue(all(cpus == ALL for cpus in self.cores.state.values()))
        self.feed.focus(self.PID)
        self.run_for(4, VBLANK)
        self.assertEqual(self.governor.parker.pid, self.PID)

    def test_a_mask_the_game_set_itself_is_kept(self):
        self.run_for(4, VBLANK)
        self.cores.state[401] = {7}
        self.run_for(2, 2 * VBLANK)
        self.assertEqual(self.cores.state[401], {7})
        self.assertEqual(self.cores.state[400], ALL)

    def idle_for(self, seconds):
        for _ in range(int(seconds / 0.5)):
            self.now += 0.5
            self.governor.tick()

    def test_thread_born_parked_is_restored(self):
        self.run_for(4, VBLANK)
        (self.governor.parker.proc / f'{self.PID}/task/403').mkdir()
        self.cores.state[403] = set(EFFICIENT)
        self.run_for(1, VBLANK)
        self.run_for(2, 2 * VBLANK)
        self.assertEqual(self.cores.state[403], ALL)

    def test_a_thread_already_on_efficient_cores_keeps_its_mask(self):
        self.cores.state[402] = set(EFFICIENT)
        self.run_for(4, VBLANK)
        self.run_for(1, VBLANK)
        self.run_for(2, 2 * VBLANK)
        self.assertEqual(self.cores.state[402], EFFICIENT)
        self.assertEqual(self.cores.state[400], ALL)

    def test_a_child_of_a_game_pinned_thread_keeps_its_mask(self):
        self.cores.state[402] = set(EFFICIENT)
        self.run_for(4, VBLANK)
        (self.governor.parker.proc / f'{self.PID}/task/404').mkdir()
        self.cores.state[404] = set(EFFICIENT)
        self.run_for(1, VBLANK)
        self.run_for(2, 2 * VBLANK)
        self.assertEqual(self.cores.state[404], EFFICIENT)
        self.assertEqual(self.cores.state[400], ALL)

    def test_a_child_of_a_game_pinned_thread_that_exited_keeps_its_mask(self):
        self.cores.state[402] = set(EFFICIENT)
        self.run_for(4, VBLANK)
        (self.governor.parker.proc / f'{self.PID}/task/402').rmdir()
        del self.cores.state[402]
        (self.governor.parker.proc / f'{self.PID}/task/404').mkdir()
        self.cores.state[404] = set(EFFICIENT)
        self.run_for(1, VBLANK)
        self.run_for(2, 2 * VBLANK)
        self.assertEqual(self.cores.state[404], EFFICIENT)
        self.assertEqual(self.cores.state[400], ALL)

    def test_born_parked_thread_gets_every_original_it_could_have_had(self):
        for tid in self.TIDS:
            self.cores.state[tid] = EFFICIENT | {6}
        self.cores.state[402] = EFFICIENT | {7}
        self.run_for(4, VBLANK)
        (self.governor.parker.proc / f'{self.PID}/task/404').mkdir()
        self.cores.state[404] = set(EFFICIENT)
        self.run_for(1, VBLANK)
        self.run_for(2, 2 * VBLANK)
        self.assertEqual(self.cores.state[404], ALL)
        self.assertEqual(self.cores.state[402], EFFICIENT | {7})

    def test_a_stalled_game_is_not_parked_on_old_frames(self):
        self.run_for(0.5, VBLANK)
        self.idle_for(3)
        self.assertIsNone(self.governor.parker.pid)
        self.run_for(2, VBLANK)
        self.assertIsNone(self.governor.parker.pid)

    def test_a_parked_game_that_stops_presenting_is_released(self):
        self.run_for(4, VBLANK)
        self.run_for(1, 1e9)
        self.idle_for(1)
        self.assertIsNone(self.governor.parker.pid)
        self.assertTrue(all(cpus == ALL for cpus in self.cores.state.values()))

    def test_threads_pinned_to_fast_cores_are_left_alone(self):
        self.cores.state[402] = {6, 7}
        self.run_for(4, VBLANK)
        self.assertEqual(self.cores.state[402], {6, 7})
        self.assertEqual(self.cores.state[400], EFFICIENT)


if __name__ == '__main__':
    unittest.main()
