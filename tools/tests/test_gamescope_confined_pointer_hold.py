from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/gamescope/patches/0133-confined-pointer-relative-when-held.patch"


def added_source():
    return "\n".join(line[1:] for line in PATCH.read_text().splitlines() if line.startswith("+") and not line.startswith("+++"))


def block(text, start):
    begin = text.index(start)
    return text[begin:text.index("\n}", begin) + 3]


def rules():
    text = added_source()
    return "\n".join([
        block(text, "struct ConfinedPointerHold"),
        block(text, "static bool confined_pointer_hold_step"),
        block(text, "static bool game_holds_pointer"),
    ])


PREAMBLE = r'''
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
'''

MAIN = r'''
static const uint64_t FRAME = 16'666'667ul;

static bool run(ConfinedPointerHold &state, int frames, double dx, double dy, uint64_t &now, bool confined = true, bool moved = true) {
    bool held = state.bHeld;
    for (int i = 0; i < frames; i++) {
        now += FRAME;
        held = confined_pointer_hold_step(state, confined, moved, dx, dy, now);
    }
    return held;
}

int main() {
    uint64_t now = 1'000'000'000ul;

    ConfinedPointerHold cursorGame;
    assert(!run(cursorGame, 600, 0.0, 0.0, now));
    assert(!run(cursorGame, 600, 12.0, -9.0, now));
    assert(!run(cursorGame, 1, 160.0, 21.0, now));
    assert(!run(cursorGame, 120, 4.0, 2.0, now));

    ConfinedPointerHold camera;
    assert(!run(camera, 2, -400.0, 120.0, now));
    assert(run(camera, 1, -400.0, 120.0, now));
    assert(run(camera, 600, 0.0, 0.0, now, true, false));
    assert(run(camera, 59, 3.0, 1.0, now));
    assert(run(camera, 1, 0.0, 300.0, now));
    assert(run(camera, 59, 3.0, 1.0, now));
    assert(!run(camera, 2, 3.0, 1.0, now));

    ConfinedPointerHold spaced;
    for (int i = 0; i < 10; i++) {
        assert(!run(spaced, 1, 200.0, 0.0, now));
        now += 600'000'000ul;
    }

    ConfinedPointerHold released;
    assert(run(released, 3, 0.0, -250.0, now));
    assert(!run(released, 1, 0.0, -250.0, now, false));
    assert(!run(released, 2, 0.0, -250.0, now));

    assert(game_holds_pointer(true, false, false));
    assert(game_holds_pointer(true, true, true));
    assert(!game_holds_pointer(true, true, false));
    assert(!game_holds_pointer(false, false, true));
    assert(!game_holds_pointer(false, true, true));

    puts("ok");
    return 0;
}
'''


@unittest.skipUnless(shutil.which("c++"), "needs a C++ compiler")
class ConfinedPointerHoldTest(unittest.TestCase):
    def test_only_a_confine_that_holds_the_x_pointer_goes_relative(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "hold.cpp"
            binary = Path(tmp) / "hold"
            source.write_text(PREAMBLE + rules() + MAIN)
            subprocess.run(["c++", "-std=c++17", "-Wall", "-Wextra", "-Werror", str(source), "-o", str(binary)], check=True)
            self.assertEqual(subprocess.run([str(binary)], capture_output=True, text=True, check=True).stdout.strip(), "ok")

    def test_the_relative_rule_uses_the_hold(self):
        added = [line.strip() for line in added_source().splitlines()]
        self.assertIn("const bool bGameHoldsPointer = game_holds_pointer( bHasPointerConstraint, bPointerConfined, s_bConfinedPointerHeld );", added)
        self.assertIn(": ( bForcedRelativeMouse || s_bFollowClientPointer || ( bImageEmpty && bGameHoldsPointer && !bExcludedAppId ) );", added)

    def test_the_lock_and_follow_rules_are_unchanged(self):
        removed = [line[1:].strip() for line in PATCH.read_text().splitlines() if line.startswith("-") and not line.startswith("---")]
        self.assertEqual(removed, [": ( bForcedRelativeMouse || s_bFollowClientPointer || ( bImageEmpty && bHasPointerConstraint && !bExcludedAppId ) );"])


if __name__ == "__main__":
    unittest.main()
