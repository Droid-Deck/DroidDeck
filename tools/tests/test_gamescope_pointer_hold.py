from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/gamescope/patches/0133-constraint-relative-when-held.patch"


def added_source():
    return "\n".join(line[1:] for line in PATCH.read_text().splitlines() if line.startswith("+") and not line.startswith("+++"))


def block(text, start):
    begin = text.index(start)
    return text[begin:text.index("\n}", begin) + 3]


def rules():
    text = added_source()
    return "\n".join([
        block(text, "struct PointerHold"),
        block(text, "static bool pointer_hold_step"),
    ])


PREAMBLE = r'''
#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
'''

MAIN = r'''
static const uint64_t FRAME = 16'666'667ul;

static bool run(PointerHold &state, int frames, double seatDX, double seatDY, double pointerDX, double pointerDY, uint64_t &now, bool constrained = true) {
    bool held = state.bHeld;
    for (int i = 0; i < frames; i++) {
        now += FRAME;
        held = pointer_hold_step(state, constrained, seatDX, seatDY, pointerDX, pointerDY, now);
    }
    return held;
}

int main() {
    uint64_t now = 1'000'000'000ul;

    PointerHold cursorGame;
    assert(!run(cursorGame, 600, 12.0, -9.0, 12.0, -9.0, now));
    assert(!run(cursorGame, 600, 40.0, 3.0, 39.0, 3.0, now));
    assert(!run(cursorGame, 1, 30.0, 0.0, 0.0, 0.0, now));
    assert(!run(cursorGame, 1, 30.0, 0.0, 30.0, 0.0, now));
    assert(!run(cursorGame, 1, 30.0, 0.0, 0.0, 0.0, now));
    assert(!run(cursorGame, 120, 2.0, 1.0, 0.0, 0.0, now));

    PointerHold offset;
    assert(!run(offset, 600, 25.0, 25.0, 25.0, 25.0, now));

    PointerHold recentring;
    assert(!run(recentring, 2, -40.0, 12.0, 0.0, 0.0, now));
    assert(run(recentring, 1, -40.0, 12.0, 1.0, 0.0, now));
    assert(run(recentring, 600, 0.0, 0.0, 0.0, 0.0, now));
    assert(run(recentring, 59, 10.0, 4.0, 10.0, 4.0, now));
    assert(run(recentring, 1, 20.0, 0.0, 0.0, 0.0, now));
    assert(run(recentring, 59, 10.0, 4.0, 10.0, 4.0, now));
    assert(!run(recentring, 1, 10.0, 4.0, 10.0, 4.0, now));

    PointerHold clipped;
    assert(run(clipped, 3, 0.0, 30.0, 0.0, 2.0, now));

    PointerHold spaced;
    for (int i = 0; i < 10; i++) {
        assert(!run(spaced, 1, 50.0, 0.0, 0.0, 0.0, now));
        now += 600'000'000ul;
    }

    PointerHold released;
    assert(run(released, 3, 0.0, -25.0, 0.0, 0.0, now));
    assert(!run(released, 1, 0.0, -25.0, 0.0, 0.0, now, false));
    assert(!run(released, 2, 0.0, -25.0, 0.0, 0.0, now));

    puts("ok");
    return 0;
}
'''


@unittest.skipUnless(shutil.which("c++"), "needs a C++ compiler")
class PointerHoldTest(unittest.TestCase):
    def test_only_a_game_that_holds_the_x_pointer_goes_relative(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "hold.cpp"
            binary = Path(tmp) / "hold"
            source.write_text(PREAMBLE + rules() + MAIN)
            subprocess.run(["c++", "-std=c++17", "-Wall", "-Wextra", "-Werror", str(source), "-o", str(binary)], check=True)
            self.assertEqual(subprocess.run([str(binary)], capture_output=True, text=True, check=True).stdout.strip(), "ok")

    def test_every_constraint_waits_for_the_hold(self):
        added = [line.strip() for line in added_source().splitlines()]
        self.assertIn("update_pointer_hold( pPaintFocus, bHasPointerConstraint && bImageEmpty && nRelativeMouse != 2 && !bExcludedAppId );", added)
        self.assertIn("const bool bGameHoldsPointer = bHasPointerConstraint && s_bPointerHeld;", added)
        self.assertIn(": ( bForcedRelativeMouse || s_bFollowClientPointer || ( bImageEmpty && bGameHoldsPointer && !bExcludedAppId ) );", added)
        self.assertFalse(any("IsConstrained()" in line for line in added))

    def test_the_forced_and_follow_rules_are_unchanged(self):
        removed = [line[1:].strip() for line in PATCH.read_text().splitlines() if line.startswith("-") and not line.startswith("---")]
        self.assertEqual(removed, [": ( bForcedRelativeMouse || s_bFollowClientPointer || ( bImageEmpty && bHasPointerConstraint && !bExcludedAppId ) );"])


if __name__ == "__main__":
    unittest.main()
