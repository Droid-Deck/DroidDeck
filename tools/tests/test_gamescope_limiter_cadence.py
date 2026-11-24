from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/gamescope/patches/0138-limiter-even-cadence.patch"


def added_source():
    return "\n".join(line[1:] for line in PATCH.read_text().splitlines() if line.startswith("+") and not line.startswith("+++"))


def block(text, start):
    begin = text.index(start)
    return text[begin:text.index("\n}", begin) + 3]


PREAMBLE = r'''
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <vector>
'''

MAIN = r'''
struct Run {
    std::vector<int> gaps;
    int frames = 0;
};

static Run run(int hz, int fps, int vblanks, uint64_t latency) {
    const uint64_t cycle = 1000000000ull / hz;
    const uint64_t limited = cycle * hz / fps;
    uint64_t target = 0;
    int last = -1;
    Run r;
    for (int i = 0; i < vblanks; i++) {
        const uint64_t now = (1000ull + i) * cycle;
        if (limiter_holds_vblank(target, now, limited, cycle)) continue;
        target = limiter_latch_target(target, now + latency, limited, cycle);
        if (last >= 0) r.gaps.push_back(i - last);
        last = i;
        r.frames++;
    }
    return r;
}

static void check(int hz, int fps, uint64_t latency) {
    const int vblanks = hz * 20;
    const Run r = run(hz, fps, vblanks, latency);
    const int low = hz / fps;
    const int high = (hz + fps - 1) / fps;
    for (int gap : r.gaps) {
        if (gap != low && gap != high) {
            std::printf("%d Hz at %d fps: a frame was held for %d refreshes\n", hz, fps, gap);
            assert(false);
        }
    }
    const int expected = fps * 20;
    if (r.frames < expected - 1 || r.frames > expected + 1) {
        std::printf("%d Hz at %d fps: %d frames, expected %d\n", hz, fps, r.frames, expected);
        assert(false);
    }
}

int main() {
    const int rates[] = {60, 90, 120, 144, 165};
    const int limits[] = {24, 30, 36, 40, 45, 48, 50, 60, 72, 80, 90, 100, 120, 144};
    for (int hz : rates) {
        for (int fps : limits) {
            if (fps > hz) continue;
            check(hz, fps, 0);
            check(hz, fps, 1000000000ull / hz / 4);
        }
    }

    const Run even = run(60, 30, 600, 0);
    for (int gap : even.gaps) assert(gap == 2);
    const Run full = run(120, 120, 600, 0);
    for (int gap : full.gaps) assert(gap == 1);
    const Run two_of_three = run(60, 40, 600, 0);
    for (size_t i = 1; i < two_of_three.gaps.size(); i++) assert(two_of_three.gaps[i] != two_of_three.gaps[i - 1]);

    const uint64_t cycle = 16666666, limited = cycle * 60 / 45;
    assert(limiter_latch_target(1000 * cycle, 1500 * cycle, limited, cycle) == 1500 * cycle);
    assert(limiter_latch_target(1500 * cycle, 1000 * cycle, limited, cycle) == 1000 * cycle);
    assert(limiter_latch_target(1000 * cycle, 1001 * cycle, limited, cycle) == 1000 * cycle + limited);
    return 0;
}
'''


class GamescopeLimiterCadenceTest(unittest.TestCase):
    def test_a_limit_the_refresh_rate_does_not_divide_is_spread_evenly(self):
        compiler = shutil.which("c++") or shutil.which("g++") or shutil.which("clang++")
        if not compiler:
            self.skipTest("no C++ compiler")
        added = added_source()
        source = PREAMBLE + block(added, "static uint64_t limiter_latch_target") + block(added, "static bool limiter_holds_vblank") + MAIN
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "limiter_cadence.cpp"
            exe = Path(tmp) / "limiter_cadence"
            src.write_text(source)
            subprocess.run([compiler, "-std=c++17", "-Wall", "-Werror", "-UNDEBUG", str(src), "-o", str(exe)], check=True)
            subprocess.run([str(exe)], check=True)

    def test_the_limited_cycle_is_no_longer_a_whole_number_of_refreshes(self):
        added = added_source()
        self.assertIn("g_SteamCompMgrAppRefreshCycle * nRealRefreshHz / nTargetFPS", added)
        removed = "\n".join(line[1:] for line in PATCH.read_text().splitlines() if line.startswith("-") and not line.startswith("---"))
        self.assertIn("nVblankDivisor", removed)


if __name__ == "__main__":
    unittest.main()
