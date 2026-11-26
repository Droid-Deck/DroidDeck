from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/gamescope/patches/0142-finger-touch-keeps-cursor-hidden.patch"

PREAMBLE = r'''
#include <cassert>
struct { bool bCursorHidden = true; bool bCursorHasImage = true; } wlserver;
static void warp( bool bSynthetic, bool bFromTouch )
{
'''

MAIN = r'''
}

int main() {
    warp( false, true );
    assert( wlserver.bCursorHidden );

    warp( true, false );
    assert( wlserver.bCursorHidden );

    warp( false, false );
    assert( !wlserver.bCursorHidden );
    return 0;
}
'''


def added_lines():
    return [line[1:] for line in PATCH.read_text().splitlines()
            if line.startswith("+") and not line.startswith("+++")]


class GamescopeTouchCursorHiddenTest(unittest.TestCase):
    def test_finger_warps_leave_the_cursor_hidden(self):
        compiler = shutil.which("c++") or shutil.which("g++") or shutil.which("clang++")
        if not compiler:
            self.skipTest("no C++ compiler")
        lines = added_lines()
        self.assertEqual(len(lines), 1)
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "touch_cursor_hidden.cpp"
            exe = Path(tmp) / "touch_cursor_hidden"
            src.write_text(PREAMBLE + lines[0] + "\n\t\twlserver.bCursorHidden = !wlserver.bCursorHasImage;\n" + MAIN)
            subprocess.run([compiler, "-std=c++17", "-Wall", "-Werror", "-UNDEBUG", str(src), "-o", str(exe)], check=True)
            subprocess.run([str(exe)], check=True)


if __name__ == "__main__":
    unittest.main()
