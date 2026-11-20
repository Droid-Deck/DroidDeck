#include <assert.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define BTN_LEFT 0x110
#define BTN_RIGHT 0x111
#define BTN_MIDDLE 0x112
#define INPUT_SPACE_W 1920
#define INPUT_SPACE_H 1080
#define WL_POINTER_FRAME_SINCE_VERSION 5
#define WL_POINTER_BUTTON_STATE_RELEASED 0
#define WL_POINTER_BUTTON_STATE_PRESSED 1
#include "input_msg.inc"
#include "pen_state.inc"

typedef int32_t wl_fixed_t;
struct wl_client { int id; };
struct wl_resource { struct wl_client *client; };
struct wl_display { int unused; };
struct surface { struct wl_resource *resource; int placed, x, y; };
struct window_target { int width, height; };
static const char *independent_key(struct surface *s) { (void)s; return NULL; }
static struct window_target *window_target_of(const char *key) { (void)key; return NULL; }
struct seat_pointer { struct wl_resource *ptr, *focus; };
struct constraint { int is_lock; struct surface *surface; };

static struct wl_client client_a, client_b;
static struct wl_resource surface_a = {&client_a}, surface_b = {&client_b};
static struct wl_resource pointer_a = {&client_a}, pointer_b = {&client_b};
static struct surface a = {&surface_a, 1, 0, 0}, b = {&surface_b, 1, 1000, 0};
static struct seat_pointer g_ptrs[2] = {{&pointer_a, NULL}, {&pointer_b, NULL}};
static int g_nptrs = 2;
static struct wl_display *g_display;
static struct constraint *g_active_constraint;
static struct surface *g_desktop, *g_grab, *g_key_target, *g_ime_click;
static double g_ptr_x, g_ptr_y, g_raw_x, g_raw_y;
static int g_ptr_known, g_raw_valid;
static atomic_int g_cursor_composite;

enum { ENTER, LEAVE, PRESS, RELEASE };
struct event { int kind; struct wl_resource *pointer, *surface; uint32_t button; };
static struct event events[32];
static int nevents;

static void record(int kind, struct wl_resource *pointer, struct wl_resource *surface, uint32_t button) {
    assert(nevents < 32);
    events[nevents++] = (struct event){kind, pointer, surface, button};
}

static void scene_size(int *w, int *h) { *w = 2000; *h = 1000; }
static void vkp_output_size(int *w, int *h) { *w = 0; *h = 0; }
static int vkp_output_to_scene(double ox, double oy, double *x, double *y) {
    (void)ox; (void)oy; (void)x; (void)y;
    return 0;
}
static struct surface *toplevel_at(double x, double y) {
    (void)y;
    return x < 1000 ? &a : &b;
}
static void confine_clamp(struct constraint *k, double *x, double *y) { (void)k; (void)x; (void)y; }
static void schedule_render(void) {}
static void publish_query_state(void) {}
static void constraints_focus_entered(struct wl_resource *target, struct wl_client *client) {
    (void)target; (void)client;
}
static void droiddeck_text_input_refocus(void) {}
static void pen_log_target(void) {}
static struct surface *dying;
static void wl_display_flush_clients(struct wl_display *display) {
    (void)display;
    if (!dying || !nevents || events[nevents - 1].kind != RELEASE) return;
    if (g_grab == dying) g_grab = NULL;
    if (g_pen.grab == dying) g_pen.grab = NULL;
    dying = NULL;
}
static uint32_t now_ms(void) { return 0; }
static uint32_t wl_display_next_serial(struct wl_display *display) { (void)display; return 0; }
static int wl_resource_get_version(struct wl_resource *r) { (void)r; return 7; }
static struct wl_client *wl_resource_get_client(struct wl_resource *r) { return r->client; }
static wl_fixed_t wl_fixed_from_double(double d) { return (wl_fixed_t)(d * 256); }
static int send_relative_motion(struct wl_client *client, double dx, double dy) {
    (void)client; (void)dx; (void)dy;
    return 0;
}
static void wl_pointer_send_enter(struct wl_resource *p, uint32_t serial, struct wl_resource *s,
                                  wl_fixed_t x, wl_fixed_t y) {
    (void)serial; (void)x; (void)y;
    record(ENTER, p, s, 0);
}
static void wl_pointer_send_leave(struct wl_resource *p, uint32_t serial, struct wl_resource *s) {
    (void)serial;
    record(LEAVE, p, s, 0);
}
static void wl_pointer_send_motion(struct wl_resource *p, uint32_t t, wl_fixed_t x, wl_fixed_t y) {
    (void)p; (void)t; (void)x; (void)y;
}
static void wl_pointer_send_frame(struct wl_resource *p) { (void)p; }
static void wl_pointer_send_button(struct wl_resource *p, uint32_t serial, uint32_t t, uint32_t button,
                                   uint32_t state) {
    (void)serial; (void)t;
    record(state == WL_POINTER_BUTTON_STATE_PRESSED ? PRESS : RELEASE, p, NULL, button);
}
#include "pointer.inc"
#include "pen.inc"

static void pen(int flags, double x) {
    struct input_msg m = {13, flags, (int)(x * INPUT_SPACE_W / 2000 * 256), 100 * 256, 0};
    nevents = 0;
    deliver_pen(&m);
}

static void focus(int i, struct wl_resource *pointer, struct wl_resource *surface) {
    assert(i < nevents && events[i].kind == ENTER && events[i].pointer == pointer && events[i].surface == surface);
}

static void left(int i, struct wl_resource *pointer, struct wl_resource *surface) {
    assert(i < nevents && events[i].kind == LEAVE && events[i].pointer == pointer && events[i].surface == surface);
}

static void button(int i, int kind, uint32_t code) {
    assert(i < nevents && events[i].kind == kind && events[i].pointer == &pointer_a && events[i].button == code);
}

static void hover_away(void) {
    assert(!g_grab);
    pen(PEN_IN, 1500);
    assert(nevents == 2);
    left(0, &pointer_a, &surface_a); focus(1, &pointer_b, &surface_b);
}

int main(int argc, char **argv) {
    assert(argc == 2);
    const char *c = argv[1];
    pen(PEN_IN, 500);
    assert(nevents == 1);
    focus(0, &pointer_a, &surface_a);
    pen(PEN_IN | PEN_TIP, 500);
    assert(nevents == 1 && g_grab == &a);
    button(0, PRESS, BTN_LEFT);
    if (!strcmp(c, "side")) {
        pen(PEN_IN | PEN_TIP | PEN_RIGHT, 600);
        assert(nevents == 1 && g_grab == &a);
        button(0, PRESS, BTN_RIGHT);
        pen(PEN_IN | PEN_TIP, 700);
        assert(nevents == 1 && g_grab == &a);
        button(0, RELEASE, BTN_RIGHT);
        pen(PEN_IN | PEN_TIP, 1500);
        assert(nevents == 0 && g_grab == &a);
        pen(PEN_IN, 1500);
        assert(nevents == 1);
        button(0, RELEASE, BTN_LEFT);
    } else if (!strcmp(c, "swap")) {
        pen(PEN_IN | PEN_RIGHT, 600);
        assert(nevents == 2 && g_grab == &a);
        button(0, PRESS, BTN_RIGHT); button(1, RELEASE, BTN_LEFT);
        pen(PEN_IN | PEN_RIGHT, 1500);
        assert(nevents == 0 && g_grab == &a);
        pen(PEN_IN, 1500);
        assert(nevents == 1);
        button(0, RELEASE, BTN_RIGHT);
    } else if (!strcmp(c, "mouse")) {
        pointer_button(BTN_RIGHT, 1);
        pointer_button(BTN_RIGHT, 0);
        pen(PEN_IN | PEN_TIP, 1500);
        assert(nevents == 0 && g_grab == &a);
        pen(PEN_IN, 1500);
        assert(nevents == 1);
        button(0, RELEASE, BTN_LEFT);
    } else {
        assert(!strcmp(c, "gone"));
        pen(PEN_IN | PEN_TIP | PEN_RIGHT, 600);
        assert(nevents == 1 && g_grab == &a);
        dying = &a;
        pen(PEN_IN | PEN_TIP, 700);
        assert(nevents == 1 && !dying && !g_grab && !g_pen.grab);
        button(0, RELEASE, BTN_RIGHT);
        pen(PEN_IN, 700);
        assert(nevents == 1);
        button(0, RELEASE, BTN_LEFT);
    }
    hover_away();
    puts("PASS");
}
