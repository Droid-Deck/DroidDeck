#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define BTN_LEFT 0x110
#define BTN_RIGHT 0x111
#define BTN_MIDDLE 0x112
#define INPUT_SPACE_W 1920
#define INPUT_SPACE_H 1080
#include "input_msg.inc"
#include "pen_state.inc"

enum { MOTION, PRESS, RELEASE };
struct event { int kind; uint32_t button; double x, y; int raw_valid; };
struct constraint { int is_lock; };
static struct constraint *g_active_constraint;
static struct surface *g_grab;
static int g_raw_valid;
static struct event events[16];
static int nevents, logs;
static uint32_t held;

static void scene_size(int *w, int *h) { *w = 3840; *h = 2160; }
static void vkp_output_size(int *w, int *h) { *w = 0; *h = 0; }
static int vkp_output_to_scene(double ox, double oy, double *x, double *y) {
    (void)ox; (void)oy; (void)x; (void)y;
    return 0;
}
static void pointer_event(double x, double y, uint32_t button, int pressed) {
    assert(!button && !pressed && nevents < 16);
    events[nevents++] = (struct event){MOTION, 0, x, y, g_raw_valid};
    g_raw_valid = 1;
}
static void pointer_button(uint32_t button, int pressed) {
    assert(button >= BTN_LEFT && button <= BTN_MIDDLE && nevents < 16);
    uint32_t bit = 1u << (button - BTN_LEFT);
    if (pressed) assert(!(held & bit));
    else assert(held & bit);
    held = pressed ? held | bit : held & ~bit;
    events[nevents++] = (struct event){pressed ? PRESS : RELEASE, button, 0, 0, g_raw_valid};
}
static void pen_log_target(void) { logs++; }
#include "pen.inc"

static void pen(int flags, double x, double y) {
    struct input_msg m = {13, flags, (int)(x * 256), (int)(y * 256), 0};
    nevents = 0;
    deliver_pen(&m);
}

static void motion(int i, double x, double y) {
    assert(events[i].kind == MOTION && events[i].x == x && events[i].y == y);
}

static void button(int i, int kind, uint32_t code) {
    assert(events[i].kind == kind && events[i].button == code);
}

static void expect(int count, uint32_t buttons) {
    assert(nevents == count && held == buttons);
    assert(g_pen.buttons == (int)buttons);
}

#define L (1u << 0)
#define R (1u << 1)
#define M (1u << 2)

int main(int argc, char **argv) {
    assert(argc == 2);
    const char *c = argv[1];
    if (!strcmp(c, "hover")) {
        pen(PEN_IN, 100.5, 50.25);
        expect(1, 0); motion(0, 201, 100.5);
        pen(PEN_IN, 2000, -4);
        expect(1, 0); motion(0, 3839, 0);
        assert(g_pen.in && logs == 2);
        pen(0, 2000, -4);
        expect(0, 0);
        assert(!g_pen.in && logs == 2);
    } else if (!strcmp(c, "tip")) {
        pen(PEN_IN, 10, 10);
        expect(1, 0);
        pen(PEN_IN | PEN_TIP, 10, 10);
        expect(2, L); motion(0, 20, 20); button(1, PRESS, BTN_LEFT);
        pen(PEN_IN | PEN_TIP, 11, 10);
        expect(1, L); motion(0, 22, 20);
        pen(PEN_IN, 12, 10);
        expect(2, 0); motion(0, 24, 20); button(1, RELEASE, BTN_LEFT);
        pen(PEN_IN | PEN_TIP, 12, 10);
        pen(PEN_IN, 12, 10);
        pen(0, 12, 10);
        expect(0, 0);
    } else if (!strcmp(c, "buttons")) {
        pen(PEN_IN | PEN_RIGHT, 10, 10);
        expect(2, R); motion(0, 20, 20); button(1, PRESS, BTN_RIGHT);
        pen(PEN_IN | PEN_RIGHT | PEN_MIDDLE, 10, 10);
        expect(2, R | M); button(1, PRESS, BTN_MIDDLE);
        pen(PEN_IN | PEN_MIDDLE | PEN_TIP, 11, 10);
        expect(3, L | M); motion(0, 22, 20); button(1, PRESS, BTN_LEFT); button(2, RELEASE, BTN_RIGHT);
        pen(PEN_IN | PEN_RIGHT, 12, 10);
        expect(4, R); motion(0, 24, 20); button(1, PRESS, BTN_RIGHT);
        button(2, RELEASE, BTN_LEFT); button(3, RELEASE, BTN_MIDDLE);
        pen(PEN_IN, 12, 10);
        expect(2, 0); button(1, RELEASE, BTN_RIGHT);
    } else if (!strcmp(c, "out")) {
        pen(PEN_IN | PEN_TIP | PEN_RIGHT | PEN_MIDDLE, 10, 10);
        expect(4, L | R | M); motion(0, 20, 20);
        button(1, PRESS, BTN_LEFT); button(2, PRESS, BTN_RIGHT); button(3, PRESS, BTN_MIDDLE);
        pen(PEN_TIP | PEN_RIGHT | PEN_MIDDLE, 30, 30);
        expect(3, 0);
        button(0, RELEASE, BTN_LEFT); button(1, RELEASE, BTN_RIGHT); button(2, RELEASE, BTN_MIDDLE);
        assert(!g_pen.in && !g_pen.buttons);
        pen(0, 30, 30);
        expect(0, 0);
    } else if (!strcmp(c, "lock")) {
        struct constraint lock = {1}, confine = {0};
        g_active_constraint = &lock;
        pen(PEN_IN, 10, 10);
        expect(0, 0);
        assert(g_pen.in && logs == 1);
        pen(PEN_IN | PEN_TIP, 11, 10);
        expect(1, L); button(0, PRESS, BTN_LEFT);
        pen(PEN_IN | PEN_TIP, 40, 10);
        expect(0, L);
        pen(PEN_IN | PEN_RIGHT, 40, 10);
        expect(2, R); button(0, PRESS, BTN_RIGHT); button(1, RELEASE, BTN_LEFT);
        pen(0, 40, 10);
        expect(1, 0); button(0, RELEASE, BTN_RIGHT);
        g_active_constraint = &confine;
        pen(PEN_IN, 10, 10);
        expect(1, 0); motion(0, 20, 20);
    } else if (!strcmp(c, "lost")) {
        pen(PEN_IN | PEN_TIP, 10, 10);
        expect(2, L); motion(0, 20, 20); button(1, PRESS, BTN_LEFT);
        pen(0, 10, 10);
        expect(1, 0); button(0, RELEASE, BTN_LEFT);
        pen(PEN_IN | PEN_TIP | PEN_MIDDLE, 10, 10);
        expect(3, L | M);
        pen(PEN_IN, 50, 10);
        expect(3, 0); motion(0, 100, 20); button(1, RELEASE, BTN_LEFT); button(2, RELEASE, BTN_MIDDLE);
    } else {
        assert(!strcmp(c, "raw"));
        g_raw_valid = 1;
        pen(PEN_IN, 10, 10);
        assert(!events[0].raw_valid && g_raw_valid);
        pen(PEN_IN, 11, 10);
        assert(events[0].raw_valid);
        pen(PEN_IN | PEN_TIP, 12, 10);
        assert(events[0].raw_valid && events[1].raw_valid);
        pen(0, 12, 10);
        assert(!g_raw_valid);
        g_raw_valid = 1;
        pen(0, 12, 10);
        assert(g_raw_valid);
    }
    puts("PASS");
}
