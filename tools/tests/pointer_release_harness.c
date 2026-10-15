#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define WL_POINTER_BUTTON_STATE_RELEASED 0
#define WL_POINTER_FRAME_SINCE_VERSION 5
struct wl_resource { int version, releases, frames; uint32_t held; };
struct seat_pointer { struct wl_resource *ptr, *focus; };
static struct seat_pointer g_ptrs[3];
static int g_nptrs = 3;
static void *g_grab, *g_display;
static unsigned motion_calls, flushes;
static uint32_t now_ms(void) { return 42; }
static uint32_t wl_display_next_serial(void *d) { (void)d; return 1; }
static int wl_resource_get_version(struct wl_resource *r) { return r->version; }
static void wl_pointer_send_frame(struct wl_resource *r) { r->frames++; }
static void wl_display_flush_clients(void *d) { (void)d; flushes++; }
static void wl_pointer_send_button(struct wl_resource *r, uint32_t serial,
                                  uint32_t t, uint32_t button, uint32_t state) {
    (void)serial; (void)t;
    assert(state == WL_POINTER_BUTTON_STATE_RELEASED);
    r->held &= ~(1u << (button - 0x110));
    r->releases++;
}
/* The old dispatcher calls this even for releases, emitting stale coordinates.
 * Recording this boundary detects that unwanted motion/focus routing path. */
static void pointer_input(double x, double y, int relative, uint32_t button, int pressed) {
    assert(x == 0 && y == 0 && relative == 1 && button >= 0x110 && button <= 0x112);
    assert(pressed == 1);
    motion_calls++;
}
#include "pointer_button.inc"
#define BTN_LEFT 0x110
#define MAX_TOUCH_POINTS 2
struct touch_point { int pointer_fallback; };
static struct touch_point g_touch_points[MAX_TOUCH_POINTS];
struct seat_touch { struct wl_resource *touch, *focus; };
static struct seat_touch g_touches[1];
static int g_ntouches = 1, cancels;
static void wl_touch_send_cancel(struct wl_resource *r) { (void)r; cancels++; }
#include "touch_cancel_all.inc"

int main(int argc, char **argv) {
    assert(argc == 2);
    struct wl_resource surface = {0};
    struct wl_resource a = {.version = 5, .held = 7};
    struct wl_resource b = {.version = 4, .held = 7};
    struct wl_resource other = {.version = 5, .held = 7};
    g_ptrs[0] = (struct seat_pointer){&a, &surface};
    g_ptrs[1] = (struct seat_pointer){&b, &surface};
    g_ptrs[2] = (struct seat_pointer){&other, NULL};
    g_grab = &surface;
    if (!strcmp(argv[1], "fallback") || !strcmp(argv[1], "native")) {
        int fallback = !strcmp(argv[1], "fallback");
        g_touches[0] = (struct seat_touch){&a, &surface};
        g_touch_points[0].pointer_fallback = fallback;
        touch_cancel_all();
        assert(!motion_calls && cancels == 1 && !g_touches[0].focus);
        assert(!g_touch_points[0].pointer_fallback);
        assert(a.releases == fallback && a.held == (fallback ? 6u : 7u));
        for (uint32_t button = 0x110; button <= 0x112; button++) pointer_button(button, 0);
        assert(!motion_calls && !a.held && !b.held && !g_grab);
    } else if (!strcmp(argv[1], "press")) {
        pointer_button(0x110, 1);
        assert(motion_calls == 1 && !a.releases && g_grab == &surface);
    } else if (!strcmp(argv[1], "unfocused")) {
        g_ptrs[0].focus = g_ptrs[1].focus = NULL;
        pointer_button(0x110, 0);
        assert(!motion_calls && !a.releases && !b.releases && !other.releases);
        assert(!g_grab);
    } else if (!strcmp(argv[1], "cleanup")) {
        for (uint32_t button = 0x110; button <= 0x112; button++) pointer_button(button, 0);
        assert(!motion_calls && !a.held && !b.held && other.held == 7);
        assert(a.releases == 3 && b.releases == 3 && !other.releases);
        assert(!g_grab && flushes == 3);
    } else {
        assert(!strcmp(argv[1], "focused"));
        a.held = b.held = 1;
        pointer_button(0x110, 0);
        assert(!motion_calls && !a.held && !b.held);
        assert(a.releases == 1 && b.releases == 1 && !other.releases);
        assert(a.frames == 1 && !b.frames && !other.frames);
        assert(g_ptrs[0].focus == &surface && g_ptrs[1].focus == &surface);
        assert(!g_grab && flushes == 1);
    }
    puts("PASS");
}
