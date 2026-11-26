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
    def test_tree_sums_pss_and_leaves_out_the_supervisor(self):
        s = sample(0, 'idle', [row(1, 'client', 100, 0), row(2, 'renderer', 300, 0, swap=20),
                               row(3, 'steam-lite', 9000, 0)])
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


if __name__ == '__main__':
    unittest.main()
