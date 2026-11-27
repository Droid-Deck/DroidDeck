import importlib.util
from pathlib import Path
import unittest

PATH = Path(__file__).resolve().parents[1] / 'steam_lite_bench.py'
SPEC = importlib.util.spec_from_file_location('steam_lite_bench', PATH)
bench = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bench)


def sample(mono, mark, rows, available=1_000_000):
    return {'mono': mono, 'clk_tck': 100, 'mark': mark, 'processes': rows,
            'memory': {'MemAvailable': available, 'psi_some_avg10': 0.5}, 'gpu': {}}


def row(pid, role, pss, ticks, swap=0):
    return {'pid': pid, 'role': role, 'Pss': pss, 'SwapPss': swap, 'ticks': ticks}


class AccountingTest(unittest.TestCase):
    def test_tree_sums_pss_and_leaves_out_the_measuring_tools(self):
        s = sample(0, 'idle', [row(1, 'client', 100, 0), row(2, 'renderer', 300, 0, swap=20),
                               row(3, 'steam-lite', 9000, 0), row(4, 'sampler', 7000, 0)])
        self.assertEqual(bench.tree(s)['pss'], 400)
        self.assertEqual(bench.tree(s)['swap'], 20)
        self.assertEqual(bench.tree(s, bench.WEB)['pss'], 300)

    def test_unreadable_pss_is_not_zero(self):
        s = sample(0, 'idle', [row(1, 'client', None, 0)])
        self.assertIsNone(bench.tree(s)['pss'])
        self.assertEqual(bench.tree(s)['unreadable'], 1)

    def test_cpu_is_percent_of_one_core_and_ignores_new_processes(self):
        a = sample(10, 'game', [row(1, 'renderer', 1, 100)])
        b = sample(20, 'game', [row(1, 'renderer', 1, 150), row(2, 'renderer', 1, 999)])
        self.assertAlmostEqual(bench.cpu(a, b), 5.0)

    def test_summary_per_phase(self):
        samples = [sample(t, 'idle', [row(1, 'renderer', 1024 * (300 + t), 10 * t)]) for t in range(4)]
        samples += [sample(10 + t, 'game', [row(1, 'renderer', 1024 * 250, 100)], available=500_000)
                    for t in range(4)]
        events = {'profile': '1', 'launch_s': 12.5, 'pacing': {'fpsOnScreen': 60, 'presentToPresentMs':
                  {'16': 90, '33': 9, '50': 1}}, 'overlay_open_s': [0.5, None, 0.7]}
        out = bench.summarize(samples, events)
        self.assertEqual(out['idle_pss_peak_mib'], 303.0)
        self.assertEqual(out['game_pss_mib'], 250.0)
        self.assertEqual(out['game_avail_min_mib'], 488.3)
        self.assertEqual((out['frame_p95_ms'], out['frame_p99_ms']), (33, 33))
        self.assertEqual((out['overlay_open_median_s'], out['overlay_failures']), (0.6, 1))
        self.assertEqual(out['idle_web_cpu_pct'], 10.0)


class FakeDevice:
    def __init__(self, replies):
        self.replies = list(replies)

    def ctl(self, *args, timeout=0):
        return self.replies.pop(0)


class HarnessTest(unittest.TestCase):
    def setUp(self):
        self.sleep = bench.time.sleep
        bench.time.sleep = lambda s: None
        self.addCleanup(setattr, bench.time, 'sleep', self.sleep)

    def test_unknown_arms_are_refused_before_touching_the_device(self):
        with self.assertRaises(SystemExit):
            bench.main(['steady', '--app', '1', '--arms', 'stock,turbo', '--out', 'unused'])

    def test_an_arm_sets_every_variable_it_names(self):
        class Recording(FakeDevice):
            def __init__(self):
                super().__init__([{'ok': True}] * 2)
                self.calls = []

            def ctl(self, *args, timeout=0):
                self.calls.append(args)
                return super().ctl(*args, timeout=timeout)
        dev = Recording()
        bench.set_arm(dev, 'softcomp')
        self.assertEqual([c[-1] for c in dev.calls], ['DD_STEAM_SOFT_COMPOSITING=1', 'DD_STEAM_CEF_GPU=1'])

    def test_an_unanswered_state_query_is_not_a_stopped_session(self):
        self.assertIsNone(bench.running(FakeDevice([{'ok': False}] * 3)))
        self.assertIs(bench.running(FakeDevice([{'ok': False}, {'ok': True, 'session': {'running': False}}])), False)
        self.assertIs(bench.running(FakeDevice([{'ok': True, 'session': {'running': True}}])), True)

    def test_a_refused_environment_write_stops_the_run(self):
        with self.assertRaises(RuntimeError):
            bench.set_env(FakeDevice([{'ok': False, 'error': 'AGENT_COMMANDS_DISABLED'}]), 'DD_STEAM_SOFT_COMPOSITING', '1')
        bench.set_env(FakeDevice([{'ok': True}]), 'DD_STEAM_SOFT_COMPOSITING', '1')

    def test_unreadable_web_pss_keeps_the_phase(self):
        samples = [sample(t, 'idle', [row(1, 'client', 1024, 0), row(2, 'renderer', None, 0)]) for t in range(4)]
        out = bench.summarize(samples, {'profile': 'stock'})
        self.assertEqual(out['idle_pss_mib'], 1.0)
        self.assertNotIn('idle_web_pss_mib', out)

    def test_report_only_summarizes_cycle_runs(self):
        import json
        import tempfile
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name, events in (('flags-default-r1', {'kind': 'flags', 'variant': 'default'}),
                                 ('cycle-r1', {'profile': '1', 'app': '1'})):
                (root / name).mkdir()
                (root / name / 'events.json').write_text(json.dumps(events))
                (root / name / 'samples.jsonl').write_text(
                    json.dumps(sample(0, 'idle', [row(1, 'client', 1024, 0)])) + '\n')
            rows = bench.report(root)
        self.assertEqual([r['profile'] for r in rows], ['1'])


if __name__ == '__main__':
    unittest.main()
