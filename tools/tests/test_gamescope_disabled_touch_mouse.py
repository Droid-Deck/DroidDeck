from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/gamescope/patches/0141-nested-mouse-moves-when-touch-is-disabled.patch"


def patched_source():
    return "\n".join(line[1:] for line in PATCH.read_text().splitlines()
                     if line[:1] in (" ", "+") and not line.startswith("+++"))


def branch(text, start):
    begin = text.index(start)
    depth = 0
    for i in range(text.index("{", begin), len(text)):
        depth += {"{": 1, "}": -1}.get(text[i], 0)
        if depth == 0:
            return text[begin:i + 1]
    raise ValueError(start)


PREAMBLE = r'''
#include <cassert>
#include <cstdint>
namespace gamescope { enum class TouchClickModes { Hover, Left, Right, Middle, Passthrough, Disabled, Trackpad }; }
static int warps, bumps, warp_from_touch = -1;
static double warp_x, warp_y;
static void wlserver_mousewarp( double x, double y, uint32_t, bool, bool bFromTouch = false ) { warps++; warp_x = x; warp_y = y; warp_from_touch = bFromTouch; }
static void bump_input_counter() { bumps++; }

static void touchmotion( double tx, double ty, uint32_t time, bool bAlwaysWarpCursor )
{
	gamescope::TouchClickModes eMode = gamescope::TouchClickModes::Disabled;
	if ( eMode == gamescope::TouchClickModes::Passthrough )
	{
	}
'''

MAIN = r'''
	bump_input_counter();
}

int main() {
    touchmotion( 0.0, 0.0, 1, false );
    assert( warps == 0 && bumps == 0 );

    touchmotion( 640.5, 360.25, 2, true );
    assert( warps == 1 && warp_x == 640.5 && warp_y == 360.25 );
    assert( warp_from_touch == 0 );
    assert( bumps == 1 );
    return 0;
}
'''


class GamescopeDisabledTouchMouseTest(unittest.TestCase):
    def test_the_nested_mouse_moves_the_pointer_while_fingers_stay_ignored(self):
        compiler = shutil.which("c++") or shutil.which("g++") or shutil.which("clang++")
        if not compiler:
            self.skipTest("no C++ compiler")
        disabled = branch(patched_source(), "else if ( eMode == gamescope::TouchClickModes::Disabled )")
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "disabled_touch_mouse.cpp"
            exe = Path(tmp) / "disabled_touch_mouse"
            src.write_text(PREAMBLE + "\t" + disabled + "\n" + MAIN)
            subprocess.run([compiler, "-std=c++17", "-Wall", "-Werror", "-UNDEBUG", str(src), "-o", str(exe)], check=True)
            subprocess.run([str(exe)], check=True)


if __name__ == "__main__":
    unittest.main()
