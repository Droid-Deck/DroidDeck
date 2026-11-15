from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/gamescope/patches/0134-touch-click-press-once.patch"


def added_source():
    return "\n".join(line[1:] for line in PATCH.read_text().splitlines() if line.startswith("+") and not line.startswith("+++"))


def block(text, start):
    begin = text.index(start)
    return text[begin:text.index("\n}", begin) + 3]


PREAMBLE = r'''
#include <cassert>
#include <cstdint>
#define WLSERVER_BUTTON_COUNT 7
enum { HOVER = 0, LEFT = 1, RIGHT = 2, PASSTHROUGH = 4 };
static const uint32_t BTN_L = 0x110, BTN_R = 0x111;
'''

MAIN = r'''
struct Seat {
    int pressed[2] = {0, 0};
    int sent[2] = {0, 0};
    int slot(uint32_t b) { return b == BTN_L ? 0 : 1; }
    void press(uint32_t b) { if (pressed[slot(b)]++ == 0) sent[slot(b)]++; }
    void release(uint32_t b) { if (pressed[slot(b)] > 0 && --pressed[slot(b)] == 0) sent[slot(b)]--; }
};

static uint32_t button_of(int mode) { return mode == LEFT ? BTN_L : mode == RIGHT ? BTN_R : 0; }

struct Server {
    bool held[WLSERVER_BUTTON_COUNT] = {};
    Seat seat;
    void down(int mode) {
        uint32_t b = button_of(mode);
        if (touch_click_press(held, mode, b)) seat.press(b);
    }
    void up() {
        for (int i = 0; i < WLSERVER_BUTTON_COUNT; i++) {
            if (held[i] && button_of(i)) seat.release(button_of(i));
            held[i] = false;
        }
    }
};

int main() {
    Server tap;
    tap.down(LEFT);
    assert(tap.seat.sent[0] == 1);
    tap.up();
    assert(tap.seat.sent[0] == 0 && tap.seat.pressed[0] == 0);

    Server overlap;
    overlap.down(LEFT);
    overlap.down(LEFT);
    overlap.up();
    overlap.up();
    assert(overlap.seat.pressed[0] == 0 && overlap.seat.sent[0] == 0);
    overlap.down(LEFT);
    assert(overlap.seat.sent[0] == 1);
    overlap.up();
    assert(overlap.seat.sent[0] == 0);

    Server three;
    for (int i = 0; i < 3; i++) three.down(LEFT);
    for (int i = 0; i < 3; i++) three.up();
    assert(three.seat.pressed[0] == 0);

    Server modes;
    modes.down(LEFT);
    modes.down(RIGHT);
    modes.up();
    modes.up();
    assert(modes.seat.pressed[0] == 0 && modes.seat.pressed[1] == 0);

    Server hover;
    hover.down(HOVER);
    hover.down(PASSTHROUGH);
    assert(hover.seat.pressed[0] == 0 && hover.seat.pressed[1] == 0);
    bool held[WLSERVER_BUTTON_COUNT] = {};
    assert(!touch_click_press(held, -1, BTN_L));
    assert(!touch_click_press(held, WLSERVER_BUTTON_COUNT, BTN_L));
    return 0;
}
'''


class GamescopeTouchClickTest(unittest.TestCase):
    def test_overlapping_touches_release_the_emulated_button(self):
        compiler = shutil.which("c++") or shutil.which("g++") or shutil.which("clang++")
        if not compiler:
            self.skipTest("no C++ compiler")
        source = PREAMBLE + block(added_source(), "static bool touch_click_press") + MAIN
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "touch_click.cpp"
            exe = Path(tmp) / "touch_click"
            src.write_text(source)
            subprocess.run([compiler, "-std=c++17", "-Wall", "-Werror", "-UNDEBUG", str(src), "-o", str(exe)], check=True)
            subprocess.run([str(exe)], check=True)


if __name__ == "__main__":
    unittest.main()
