from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/gamescope/patches/0126-topmost-focus-outranks-other-process-override.patch"


def patched_rule():
    added = [line[1:] for line in PATCH.read_text().splitlines() if line.startswith("+") and not line.startswith("+++")]
    text = "\n".join(added)
    start = text.index("static bool is_stacked_below_focus")
    return text[start:text.index("\n}\n", start) + 3]


PREAMBLE = r'''
#include <cassert>
#include <cstdint>
#include <cstdio>

static constexpr uint32_t WS_EX_TOPMOST = 0x00000008u;
static constexpr uint32_t WS_EX_TOOLWINDOW = 0x00000080u;
static constexpr uint32_t WS_EX_LAYERED = 0x00080000u;

enum class steamcompmgr_win_type_t { XWAYLAND, XDG };
struct xwayland_t { unsigned long id = 0; unsigned long transientFor = 0; };
struct steamcompmgr_win_t {
    int pid = 0;
    bool hasHwndStyleEx = false;
    uint32_t hwndStyleEx = 0;
    steamcompmgr_win_type_t type = steamcompmgr_win_type_t::XWAYLAND;
    xwayland_t x;
    xwayland_t &xwayland() { return x; }
};

static steamcompmgr_win_t win(int pid, unsigned long id, uint32_t exstyle) {
    steamcompmgr_win_t w;
    w.pid = pid;
    w.x.id = id;
    w.hasHwndStyleEx = true;
    w.hwndStyleEx = exstyle;
    return w;
}
'''

MAIN = r'''
int main() {
    steamcompmgr_win_t signin = win(4407, 0x2c00008, WS_EX_TOPMOST);
    steamcompmgr_win_t splash = win(4055, 0x1c00005, WS_EX_LAYERED | WS_EX_TOOLWINDOW);
    assert(is_stacked_below_focus(&splash, &signin));

    steamcompmgr_win_t dropdown = win(4407, 0x2c00010, WS_EX_TOOLWINDOW);
    assert(!is_stacked_below_focus(&dropdown, &signin));

    steamcompmgr_win_t menu = win(5120, 0x3000004, WS_EX_TOPMOST | WS_EX_TOOLWINDOW);
    assert(!is_stacked_below_focus(&menu, &signin));

    steamcompmgr_win_t game = win(4407, 0x2c00008, 0);
    assert(!is_stacked_below_focus(&splash, &game));

    steamcompmgr_win_t owned = win(4055, 0x1c00007, 0);
    owned.x.transientFor = signin.x.id;
    assert(!is_stacked_below_focus(&owned, &signin));

    steamcompmgr_win_t unknown = win(4055, 0x1c00009, 0);
    unknown.hasHwndStyleEx = false;
    assert(!is_stacked_below_focus(&unknown, &signin));

    steamcompmgr_win_t no_pid = win(-1, 0x1c0000b, WS_EX_LAYERED | WS_EX_TOOLWINDOW);
    assert(!is_stacked_below_focus(&no_pid, &signin));
    steamcompmgr_win_t signin_no_pid = win(-1, 0x2c00008, WS_EX_TOPMOST);
    assert(!is_stacked_below_focus(&splash, &signin_no_pid));

    puts("ok");
    return 0;
}
'''


@unittest.skipUnless(shutil.which("c++"), "needs a C++ compiler")
class OverrideStackingTest(unittest.TestCase):
    def test_a_topmost_focus_keeps_other_process_windows_beneath_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "stacking.cpp"
            binary = Path(tmp) / "stacking"
            source.write_text(PREAMBLE + patched_rule() + MAIN)
            subprocess.run(["c++", "-std=c++17", "-Wall", "-Werror", str(source), "-o", str(binary)], check=True)
            self.assertEqual(subprocess.run([str(binary)], capture_output=True, text=True, check=True).stdout.strip(), "ok")

    def test_map_win_reads_the_wine_extended_style(self):
        hunks = PATCH.read_text().split("\n@@ ")
        map_win = next(hunk for hunk in hunks if "map_win(" in hunk.split("\n", 1)[0])
        added = [line[1:].strip() for line in map_win.splitlines() if line.startswith("+")]
        self.assertIn("w->hwndStyleEx = get_prop(ctx, w->xwayland().id, ctx->atoms.wineHwndStyleEx, 0, &w->hasHwndStyleEx);", added)


if __name__ == "__main__":
    unittest.main()
