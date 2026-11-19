import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "steam_present", Path(__file__).resolve().parents[1] / "steam-present" / "steam_present.py")
sp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sp)

LAYER = "SurfaceView[com.ludashi.benchmark/com.droiddeck.launcher.SessionActivity]#123(BLAST)#124"
LIST = "\n".join(["Background for SurfaceView[x.SessionActivity]#9", "StatusBar#1", LAYER,
                  "SurfaceView[com.ludashi.benchmark/com.droiddeck.launcher.SessionActivity]#123"])


def cadence(gaps_ms, start=1_000_000_000):
    t, out = start, [start]
    for g in gaps_ms:
        t += int(g * 1e6)
        out.append(t)
    return out


def latency_text(ts, window=127, period=8333333):
    ts = ts[-window:]
    rows = [f"{t} {t} {t + 1000}" for t in ts]
    rows += [f"0 {sp.INT64_MAX} {sp.INT64_MAX}"] * (127 - len(rows))  # pending
    return "\n".join([str(period)] + rows) + "\n"


def timestats_text(name, hist, frames):
    bins = " ".join(f"{k}ms={v}" for k, v in sorted(hist.items()))
    return (f"displayRefreshRate = 120 fps\nlayerName = {name}\ntotalFrames = {frames}\n"
            f"present2present histogram is as below:\n{bins}\n")


class ParseTests(unittest.TestCase):
    def test_uniform_8_33(self):
        _, ts = sp.parse_latency(latency_text(cadence([8.333] * 120)))
        r = sp.summarize(sp.intervals_from_ts(ts), len(ts), 1.0, "latency")
        self.assertEqual(r["verdict"], "120-paced")
        self.assertAlmostEqual(r["meanMs"], 8.33, places=1)
        self.assertEqual(r["histogram"]["8.33ms"], r["intervals"])

    def test_uniform_16_67(self):
        _, ts = sp.parse_latency(latency_text(cadence([16.667] * 120)))
        r = sp.summarize(sp.intervals_from_ts(ts), len(ts), 2.0, "latency")
        self.assertEqual(r["verdict"], "60-capped")
        self.assertAlmostEqual(r["fps"], 60.0, delta=0.2)
        self.assertEqual(r["histogram"]["16.7ms"], r["intervals"])

    def test_uniform_11_1(self):
        _, ts = sp.parse_latency(latency_text(cadence([11.111] * 100)))
        self.assertEqual(sp.summarize(sp.intervals_from_ts(ts), len(ts), 1, "latency")["verdict"], "90-capped")

    def test_mixed_drops(self):
        gaps = ([8.333] * 8 + [33.3]) * 12  # ~11% stalls to 33 ms
        _, ts = sp.parse_latency(latency_text(cadence(gaps)))
        r = sp.summarize(sp.intervals_from_ts(ts), len(ts), 1, "latency")
        self.assertEqual(r["verdict"], "dropped-frames")
        self.assertEqual(r["histogram"]["33ms"], 12)
        self.assertEqual(r["histogram"]["8.33ms"], 96)

    def test_idle_gap_not_a_drop(self):
        gaps = [8.333] * 50 + [900.0] + [8.333] * 50
        _, ts = sp.parse_latency(latency_text(cadence(gaps), window=200))
        r = sp.summarize(sp.intervals_from_ts(ts), len(ts), 2, "latency")
        self.assertEqual(r["idleGaps"], 1)
        self.assertEqual(r["verdict"], "120-paced")

    def test_empty_and_period_only(self):
        self.assertEqual(sp.parse_latency("8333333\n"), (8333333, []))
        self.assertEqual(sp.parse_latency("")[1], [])
        r = sp.summarize([], 0, 5, "none")
        self.assertEqual(r["verdict"], "no-frames")

    def test_pending_rows_dropped(self):
        _, ts = sp.parse_latency("8333333\n0 9223372036854775807 9223372036854775807\n5 100 200\n")
        self.assertEqual(ts, [100])

    def test_timestats_bins(self):
        txt = timestats_text(LAYER, {8: 580, 9: 20, 16: 5}, 606) + timestats_text("other#1", {16: 99}, 99)
        frames, hist = sp.parse_timestats(txt)
        self.assertEqual((frames, hist), (606, {8: 580, 9: 20, 16: 5}))
        r = sp.summarize(sp.expand_bins(hist), frames, 5.0, "timestats")
        self.assertEqual(r["verdict"], "120-paced")
        r16 = sp.summarize(sp.expand_bins({16: 290, 17: 310}), 601, 10.0, "timestats")
        self.assertEqual(r16["verdict"], "60-capped")

    def test_layer_ranking_and_name_change(self):
        self.assertEqual(sp.find_layers(LIST)[0], LAYER)
        self.assertNotIn("Background for SurfaceView[x.SessionActivity]#9", sp.find_layers(LIST))


class MeasureTests(unittest.TestCase):
    def run_fake(self, latency_for, timestats):
        state = {"t": 0.0, "n": 0}

        def adb(args):
            if "--list" in args:
                n = "#999" if state["n"] >= 3 else "#123"  # restart renames layer
                return LIST.replace("#123", n)
            if "--latency" in args:
                return latency_for(args[-1], state)
            if "-dump" in args:
                return timestats
            return ""

        def clock():
            return state["t"]

        def sleep(d):
            state["t"] += d
            state["n"] += 1
        return sp.measure(adb, 3.0, clock=clock, sleep=sleep, poll=0.4)

    def test_latency_path_merges_polls_and_survives_rename(self):
        allts = cadence([8.333] * 400)

        def lat(layer, st):
            if "#123(BLAST)" in layer and st["n"] >= 3:  # old name gone after restart
                return ""
            end = min(len(allts), 130 + 48 * st["n"])
            return latency_text(allts[:end])
        r = self.run_fake(lat, "")
        self.assertEqual(r["source"], "latency")
        self.assertEqual(r["verdict"], "120-paced")
        self.assertTrue(any("#999" in n for n in r["layers"]))

    def test_falls_back_to_timestats_when_latency_empty(self):
        ts = timestats_text(LAYER, {8: 340, 9: 10}, 351)
        r = self.run_fake(lambda l, s: "8333333\n", ts)
        self.assertEqual(r["source"], "timestats")
        self.assertEqual(r["verdict"], "120-paced")

    def test_nothing_anywhere(self):
        r = self.run_fake(lambda l, s: "8333333\n", "")
        self.assertEqual(r["verdict"], "no-frames")


if __name__ == "__main__":
    unittest.main()
