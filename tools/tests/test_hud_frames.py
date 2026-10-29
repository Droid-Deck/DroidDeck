from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/gamescope/patches/0125-droiddeck-hud-frame-times.patch"
READER = ROOT / "app/src/main/cpp/waylandcomp/src/hudframes.c"


def patch_writer():
    added = [line[1:] for line in PATCH.read_text().splitlines() if line.startswith("+") and not line.startswith("+++")]
    text = "\n".join(added)
    start = text.index("// DroidDeck's own HUD reads")
    end = text.index("\n}\n", text.index("static void publish_droiddeck_hud_info()", start)) + 3
    return text[start:end]


PREAMBLE = r'''
#include <assert.h>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <string>
#include <sys/mman.h>
#include <unistd.h>
#include <unordered_map>

extern "C" {
#include "hudframes.h"
}

struct steamcompmgr_win_t { int appID; };
struct global_focus_t {
    uint64_t ulBasePlaneCommitID = 0;
    pid_t nFocusWindowPid = 0;
    std::shared_ptr<std::string> pFocusWindowEngine;
    steamcompmgr_win_t *inputFocusWindow = nullptr;
};
static std::unordered_map<uint64_t, global_focus_t> g_VirtualConnectorFocuses;
static global_focus_t *GetCurrentFocus() { return nullptr; }
static bool window_is_steam(steamcompmgr_win_t *w) { return w && w->appID == 769; }
static uint64_t g_now = 1000000000ull;
static uint64_t get_time_in_nanos() { return g_now; }
static uint32_t g_nOutputWidth = 1280, g_nOutputHeight = 720;
static int g_nOutputRefresh = 144000;
struct Log {
    void infof(const char *, ...) {}
    void errorf_errno(const char *fmt, ...) { va_list a; va_start(a, fmt); vfprintf(stderr, fmt, a); va_end(a); }
} xwm_log;
'''

MAIN = r'''
static void frame(uint64_t step) { g_now += step; droiddeck_hud_app_frame(g_now); }

int main(int argc, char **argv) {
    setenv("DROIDDECK_HUD_FRAMES", argv[1], 1);
    assert(droiddeck_hud_frames_wanted());
    global_focus_t &focus = g_VirtualConnectorFocuses[0];
    focus.nFocusWindowPid = 4242;
    focus.pFocusWindowEngine = std::make_shared<std::string>("DXVK");
    steamcompmgr_win_t game{ 12345 };
    focus.inputFocusWindow = &game;
    publish_droiddeck_hud_info();

    droiddeck_hud_app_frame(g_now);
    struct hud_frames *h = hud_frames_open(argv[1]);
    assert(h);
    int64_t ft[2048], tm[2048];
    assert(hud_frames_poll(h, ft, tm, 2048) == 0);

    const uint64_t steps[] = { 2700000, 2800000, 41600000, 6944000 };
    for (uint64_t step : steps) frame(step);
    int n = hud_frames_poll(h, ft, tm, 2048);
    assert(n == 4);
    for (int i = 0; i < 4; i++) assert((uint64_t)ft[i] == steps[i]);
    assert((uint64_t)tm[3] == g_now);
    assert(hud_frames_poll(h, ft, tm, 2048) == 0);

    int32_t info[5];
    char engine[41];
    assert(hud_frames_info(h, info, engine, sizeof(engine)));
    assert(info[0] == 4242 && info[1] == 1280 && info[2] == 720 && info[3] == 144000 && info[4] == 0);
    assert(strcmp(engine, "DXVK") == 0);
    game.appID = 769;
    publish_droiddeck_hud_info();
    assert(hud_frames_info(h, info, engine, sizeof(engine)) && info[4] == 1);

    for (int i = 0; i < 3000; i++) frame(1000000 + i);
    n = hud_frames_poll(h, ft, tm, 2048);
    assert(n == 1024);
    assert(ft[1023] == 1000000 + 2999);
    assert(ft[0] == 1000000 + 2999 - 1023);

    for (int i = 0; i < 10; i++) frame(2000000);
    assert(hud_frames_poll(h, ft, tm, 4) == 4 && ft[3] == 2000000);
    hud_frames_close(h);
    puts("ok");
    return 0;
}
'''


@unittest.skipUnless(shutil.which("cc") and shutil.which("c++"), "needs a C and C++ compiler")
class HudFramesTest(unittest.TestCase):
    def test_gamescope_writer_and_app_reader_agree(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            source = tmp / "writer.cpp"
            source.write_text(PREAMBLE + patch_writer() + MAIN)
            include = READER.parent
            subprocess.run(["cc", "-std=gnu11", "-Wall", "-Wextra", "-Werror", "-c", str(READER), "-o", str(tmp / "reader.o")], check=True)
            subprocess.run(["c++", "-std=c++20", "-Wall", "-Werror", "-Wno-unused-function", f"-I{include}", str(source), str(tmp / "reader.o"),
                            "-o", str(tmp / "hudframes")], check=True)
            result = subprocess.run([str(tmp / "hudframes"), str(tmp / "frames")], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), "ok")
            self.assertEqual(os.path.getsize(tmp / "frames"), 128 + 1024 * 16)

    def test_session_points_gamescope_at_the_file_the_app_reads(self):
        session = (ROOT / "tools/linuxfs/overlay/usr/local/bin/droiddeck-session").read_text()
        self.assertIn("export DROIDDECK_HUD_FRAMES=/run/droiddeck-hud/frames", session)
        app = (ROOT / "app/src/main/java/com/droiddeck/launcher/hud/HudFrames.kt").read_text()
        self.assertIn('"run/droiddeck-hud/frames"', app)


if __name__ == "__main__":
    unittest.main()
