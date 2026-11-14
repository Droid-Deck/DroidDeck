#include <assert.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define BTN_LEFT 0x110
#define BTN_RIGHT 0x111
#define BTN_MIDDLE 0x112
#define BTN_TOOL_PEN 0x140
#define BTN_TOOL_RUBBER 0x141
#define INPUT_SPACE_W 1920
#define INPUT_SPACE_H 1080
#define WL_POINTER_FRAME_SINCE_VERSION 5
#define WL_POINTER_BUTTON_STATE_RELEASED 0
#define WL_POINTER_BUTTON_STATE_PRESSED 1
#include "input_msg.inc"
#include "pen_state.inc"
#include "pen_api.inc"

typedef int32_t wl_fixed_t;
struct wl_client { int id; };
struct wl_resource { struct wl_client *client; };
struct wl_display { int unused; };
struct surface { struct wl_resource *resource; int placed, x, y; };
struct seat_pointer { struct wl_resource *ptr, *focus; };
struct seat_touch { struct wl_resource *touch, *focus; };
struct constraint { int is_lock; struct surface *surface; };

static struct wl_client client_a, client_b, client_d;
static struct wl_resource surface_a = {&client_a}, surface_b = {&client_b}, surface_d = {&client_d};
static struct wl_resource pointer_a = {&client_a}, pointer_b = {&client_b};
static struct surface a = {&surface_a, 1, 0, 0}, b = {&surface_b, 1, 1000, 0}, d = {&surface_d, 1, 300, 0};
static struct seat_pointer g_ptrs[2] = {{&pointer_a, NULL}, {&pointer_b, NULL}};
static int g_nptrs = 2;
static struct seat_touch g_touches[1];
static int g_ntouches;
static struct wl_display *g_display;
static struct constraint *g_active_constraint;
static struct surface *g_desktop, *g_grab, *g_key_target, *g_ime_click;
static double g_ptr_x, g_ptr_y, g_raw_x, g_raw_y;
static int g_ptr_known, g_raw_valid;
static atomic_int g_cursor_composite;

#define NOW 4242u
enum { ENTER, LEAVE, PRESS, RELEASE, FRAME, OUT };
struct event { int kind; struct wl_resource *pointer, *surface; uint32_t button; };
static struct event events[32];
static int nevents, motions, ensures, tablet_caps, tools_known;
static struct droiddeck_pen last_pen;
static struct wl_client *tablet_taker, *gamescope;

static void record(int kind, struct wl_resource *pointer, struct wl_resource *surface, uint32_t button) {
    assert(nevents < 32);
    events[nevents++] = (struct event){kind, pointer, surface, button};
}

static int tool_bit(int tool) { return tool == BTN_TOOL_PEN ? 1 : tool == BTN_TOOL_RUBBER ? 2 : 0; }
static void droiddeck_tablet_set_capabilities(int caps) { tablet_caps |= caps; }
static void droiddeck_tablet_ensure_tool(int tool) {
    ensures++;
    if (tablet_caps) tools_known |= tool_bit(tool);
}
static int droiddeck_tablet_client_has_tool(struct wl_client *client, int tool) {
    return client == tablet_taker && (tools_known & tool_bit(tool));
}
static int droiddeck_tablet_client_bound(struct wl_client *client) { return client == tablet_taker; }
static void droiddeck_tablet_frame(struct wl_resource *surface, const struct droiddeck_pen *pen) {
    assert(pen->time_ms == NOW);
    record(FRAME, NULL, surface, 0);
    last_pen = *pen;
}
static void droiddeck_tablet_out(uint32_t time_ms) {
    assert(time_ms == NOW);
    record(OUT, NULL, NULL, 0);
}
static const char *client_name(struct wl_client *client) { return client == gamescope ? "gamescope" : "a program"; }

static void scene_size(int *w, int *h) { *w = 2000; *h = 1000; }
static void vkp_output_size(int *w, int *h) { *w = 0; *h = 0; }
static int vkp_output_to_scene(double ox, double oy, double *x, double *y) {
    (void)ox; (void)oy; (void)x; (void)y;
    return 0;
}
static int vkp_scene_to_output(double x, double y, double *ox, double *oy) {
    (void)x; (void)y; (void)ox; (void)oy;
    return 0;
}
static struct surface *toplevel_at(double x, double y) {
    (void)y;
    return x < 1000 ? &a : &b;
}
static void confine_clamp(struct constraint *k, double *x, double *y) { (void)k; (void)x; (void)y; }
static void schedule_render(void) {}
static void constraints_focus_entered(struct wl_resource *target, struct wl_client *client) {
    (void)target; (void)client;
}
static void droiddeck_text_input_refocus(void) {}
static void pen_log_target(void) {}
static void publish_query_state(void);
static struct surface *dying;
static void wl_display_flush_clients(struct wl_display *display) {
    (void)display;
    if (!dying || !nevents || events[nevents - 1].kind != RELEASE) return;
    if (g_grab == dying) g_grab = NULL;
    if (g_pen.grab == dying) g_pen.grab = NULL;
    dying = NULL;
}
static uint32_t now_ms(void) { return NOW; }
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
    motions++;
}
static void wl_pointer_send_frame(struct wl_resource *p) { (void)p; }
static void wl_pointer_send_button(struct wl_resource *p, uint32_t serial, uint32_t t, uint32_t button,
                                   uint32_t state) {
    (void)serial; (void)t;
    record(state == WL_POINTER_BUTTON_STATE_PRESSED ? PRESS : RELEASE, p, NULL, button);
}
#include "pointer.inc"
#include "touch.inc"
#include "query.inc"
#include "pen.inc"

static void send_pen(int flags, double x, double y, int pressure, int distance, int tilt_x, int tilt_y) {
    struct input_msg m = {13, flags, (int)(x * INPUT_SPACE_W / 2000 * 256), (int)(y * INPUT_SPACE_H / 1000 * 256),
                          pressure, distance, tilt_x, tilt_y};
    nevents = 0;
    deliver_pen(&m);
}

static void pen(int flags, double x) { send_pen(flags, x, 100, 0, 0, 0, 0); }

static double scene_x(double x) { return (int)(x * INPUT_SPACE_W / 2000 * 256) / 256.0 * 2000 / INPUT_SPACE_W; }

static void focus(int i, struct wl_resource *pointer, struct wl_resource *surface) {
    assert(i < nevents && events[i].kind == ENTER && events[i].pointer == pointer && events[i].surface == surface);
}

static void left(int i, struct wl_resource *pointer, struct wl_resource *surface) {
    assert(i < nevents && events[i].kind == LEAVE && events[i].pointer == pointer && events[i].surface == surface);
}

static void button(int i, int kind, struct wl_resource *pointer, uint32_t code) {
    assert(i < nevents && events[i].kind == kind && events[i].pointer == pointer && events[i].button == code);
}

static void frame(int i, struct wl_resource *surface) {
    assert(i < nevents && events[i].kind == FRAME && events[i].surface == surface);
}

static void out(int i) { assert(i < nevents && events[i].kind == OUT); }

static void tablet_for(struct wl_client *client, int caps) {
    tablet_taker = client;
    atomic_store(&g_pen_caps, caps);
}

#define T (PEN_IN | PEN_TABLET)

static void press_on_a(void) {
    pen(PEN_IN, 500);
    assert(nevents == 1);
    focus(0, &pointer_a, &surface_a);
    pen(PEN_IN | PEN_TIP, 500);
    assert(nevents == 1 && g_grab == &a);
    button(0, PRESS, &pointer_a, BTN_LEFT);
}

static void hover_away(void) {
    assert(!g_grab);
    pen(PEN_IN, 1500);
    assert(nevents == 2);
    left(0, &pointer_a, &surface_a); focus(1, &pointer_b, &surface_b);
}

static void side(void) {
    press_on_a();
    pen(PEN_IN | PEN_TIP | PEN_RIGHT, 600);
    assert(nevents == 1 && g_grab == &a);
    button(0, PRESS, &pointer_a, BTN_RIGHT);
    pen(PEN_IN | PEN_TIP, 700);
    assert(nevents == 1 && g_grab == &a);
    button(0, RELEASE, &pointer_a, BTN_RIGHT);
    pen(PEN_IN | PEN_TIP, 1500);
    assert(nevents == 0 && g_grab == &a);
    pen(PEN_IN, 1500);
    assert(nevents == 1);
    button(0, RELEASE, &pointer_a, BTN_LEFT);
    hover_away();
}

static void swap(void) {
    press_on_a();
    pen(PEN_IN | PEN_RIGHT, 600);
    assert(nevents == 2 && g_grab == &a);
    button(0, PRESS, &pointer_a, BTN_RIGHT); button(1, RELEASE, &pointer_a, BTN_LEFT);
    pen(PEN_IN | PEN_RIGHT, 1500);
    assert(nevents == 0 && g_grab == &a);
    pen(PEN_IN, 1500);
    assert(nevents == 1);
    button(0, RELEASE, &pointer_a, BTN_RIGHT);
    hover_away();
}

static void mouse(void) {
    press_on_a();
    pointer_button(BTN_RIGHT, 1);
    pointer_button(BTN_RIGHT, 0);
    pen(PEN_IN | PEN_TIP, 1500);
    assert(nevents == 0 && g_grab == &a);
    pen(PEN_IN, 1500);
    assert(nevents == 1);
    button(0, RELEASE, &pointer_a, BTN_LEFT);
    hover_away();
}

static void gone(void) {
    press_on_a();
    pen(PEN_IN | PEN_TIP | PEN_RIGHT, 600);
    assert(nevents == 1 && g_grab == &a);
    dying = &a;
    pen(PEN_IN | PEN_TIP, 700);
    assert(nevents == 1 && !dying && !g_grab && !g_pen.grab);
    button(0, RELEASE, &pointer_a, BTN_RIGHT);
    pen(PEN_IN, 700);
    assert(nevents == 1);
    button(0, RELEASE, &pointer_a, BTN_LEFT);
    hover_away();
}

static void frozen(void) {
    tablet_for(&client_b, DROIDDECK_PEN_CAP_PRESSURE);
    press_on_a();
    pen(T | PEN_TIP, 1500);
    assert(nevents == 0 && g_pen.route == PEN_ROUTE_POINTER && g_grab == &a);
    pen(T, 1500);
    assert(nevents == 1 && !g_grab);
    button(0, RELEASE, &pointer_a, BTN_LEFT);
    pen(T, 1500);
    assert(nevents == 1 && g_pen.route == PEN_ROUTE_TABLET && g_pen.target == &b);
    frame(0, &surface_b);
    assert(last_pen.x == 500 && last_pen.y == 100 && !last_pen.down);
    pen(T | PEN_TIP, 1500);
    pen(T | PEN_TIP, 500);
    assert(nevents == 1 && g_pen.target == &b && last_pen.down && last_pen.x == -500);
    frame(0, &surface_b);
    pen(T, 500);
    assert(nevents == 1 && !last_pen.down);
    frame(0, &surface_b);
    pen(T, 500);
    assert(nevents == 1 && g_pen.route == PEN_ROUTE_POINTER);
    out(0);
}

static void no_tablet(void) {
    atomic_store(&g_pen_caps, DROIDDECK_PEN_CAP_PRESSURE | DROIDDECK_PEN_CAP_TILT);
    pen(PEN_IN, 1500);
    assert(nevents == 1 && !ensures && !tablet_caps);
    focus(0, &pointer_b, &surface_b);
    pen(T, 1500);
    assert(nevents == 0 && ensures == 1 && tablet_caps == 3 && tools_known == 1);
    pen(T | PEN_ERASER | PEN_TIP, 1500);
    assert(nevents == 1 && ensures == 2 && tools_known == 3);
    button(0, PRESS, &pointer_b, BTN_LEFT);
    pen(T | PEN_ERASER, 1500);
    pen(0, 1500);
    assert(nevents == 0 && g_pen.route == PEN_ROUTE_NONE);
    tablet_taker = &client_b;
    tablet_caps = tools_known = 0;
    atomic_store(&g_pen_caps, 0);
    pen(T, 1500);
    assert(nevents == 0 && g_pen.route == PEN_ROUTE_POINTER && !tools_known);
    pen(0, 1500);
    atomic_store(&g_pen_caps, DROIDDECK_PEN_CAP_PRESSURE);
    pen(T, 1500);
    assert(nevents == 1 && g_pen.route == PEN_ROUTE_TABLET);
    frame(0, &surface_b);
}

static void route_switch(void) {
    tablet_for(&client_b, DROIDDECK_PEN_CAP_PRESSURE);
    pen(T, 1500);
    assert(nevents == 1);
    frame(0, &surface_b);
    pen(T, 500);
    assert(nevents == 2 && g_pen.route == PEN_ROUTE_POINTER);
    out(0); focus(1, &pointer_a, &surface_a);
    pen(T, 1500);
    assert(nevents == 1 && g_pen.route == PEN_ROUTE_TABLET && !g_raw_valid);
    frame(0, &surface_b);
    pen(PEN_IN, 1500);
    assert(nevents == 3 && g_pen.route == PEN_ROUTE_POINTER);
    out(0); left(1, &pointer_a, &surface_a); focus(2, &pointer_b, &surface_b);
    pen(T, 1500);
    pen(0, 1500);
    assert(nevents == 1 && g_pen.route == PEN_ROUTE_NONE);
    out(0);
}

static void axes(void) {
    tablet_for(&client_b, DROIDDECK_PEN_CAP_PRESSURE | DROIDDECK_PEN_CAP_TILT);
    send_pen(T | PEN_TILT | PEN_TIP | PEN_RIGHT | PEN_MIDDLE, 1500, 200, 40000, 7, -4500, 9000);
    assert(nevents == 1);
    frame(0, &surface_b);
    assert(last_pen.tool == BTN_TOOL_PEN && last_pen.x == 500 && last_pen.y == 200 && last_pen.down);
    assert(last_pen.pressure == 40000 && last_pen.distance == 7);
    assert(last_pen.has_tilt && last_pen.tilt_x == -45 && last_pen.tilt_y == 90);
    assert(last_pen.buttons == (DROIDDECK_PEN_RIGHT | DROIDDECK_PEN_MIDDLE));
    send_pen(T | PEN_ERASER | PEN_RIGHT, 1600.5, 125, 0, 0, 100, 100);
    assert(nevents == 1);
    frame(0, &surface_b);
    assert(last_pen.tool == BTN_TOOL_RUBBER && last_pen.x == scene_x(1600.5) - 1000 && last_pen.x != (int)last_pen.x);
    assert(!last_pen.has_tilt && !last_pen.down && last_pen.buttons == DROIDDECK_PEN_RIGHT);
    pen(0, 1600);
    assert(nevents == 1);
    out(0);
}

static void target_order(void) {
    struct constraint lock = {1, &a};
    atomic_store(&g_pen_caps, DROIDDECK_PEN_CAP_PRESSURE);
    g_active_constraint = &lock;
    g_desktop = &d;
    g_grab = &b;
    tablet_taker = &client_a;
    pen(T, 1500);
    assert(nevents == 1 && g_pen.target == &a && last_pen.x == 1500);
    frame(0, &surface_a);
    pen(0, 1500);
    g_active_constraint = NULL;
    tablet_taker = &client_d;
    pen(T, 500);
    assert(nevents == 1 && g_pen.target == &d && last_pen.x == 500);
    frame(0, &surface_d);
    pen(0, 500);
    g_desktop = NULL;
    tablet_taker = &client_b;
    pen(T, 500);
    assert(nevents == 1 && g_pen.target == &b && last_pen.x == -500);
    frame(0, &surface_b);
    pen(0, 500);
    g_grab = NULL;
    pen(T, 1500);
    assert(nevents == 1 && g_pen.target == &b && last_pen.x == 500);
    frame(0, &surface_b);
}

static void orphan(void) {
    tablet_for(&client_b, DROIDDECK_PEN_CAP_PRESSURE);
    pen(T | PEN_TIP, 1500);
    assert(nevents == 1 && g_pen.target == &b);
    frame(0, &surface_b);
    g_pen.target = NULL;
    pen(T | PEN_TIP, 1600);
    pen(T, 1600);
    assert(nevents == 0 && g_pen.route == PEN_ROUTE_TABLET);
    pen(T, 1600);
    assert(nevents == 1 && g_pen.target == &b && !last_pen.down);
    frame(0, &surface_b);
}

static void query(void) {
    tablet_for(&client_b, DROIDDECK_PEN_CAP_PRESSURE);
    publish_query_state();
    assert(!droiddeck_focus_takes_tablet());
    pen(T, 1500);
    assert(droiddeck_focus_takes_tablet());
    pen(0, 1500);
    assert(droiddeck_focus_takes_tablet());
    pen(PEN_IN, 500);
    pen(0, 500);
    assert(!droiddeck_focus_takes_tablet());
    g_desktop = &b;
    publish_query_state();
    assert(droiddeck_focus_takes_tablet());
    gamescope = &client_b;
    publish_query_state();
    assert(!droiddeck_focus_takes_tablet());
}

static void refused(void) {
    struct constraint lock = {1, &a};
    tablet_for(&client_a, DROIDDECK_PEN_CAP_PRESSURE);
    gamescope = &client_a;
    g_active_constraint = &lock;
    pen(T, 1500);
    assert(nevents == 0 && !motions && g_pen.route == PEN_ROUTE_POINTER && tools_known == 1);
    pen(T | PEN_TIP, 1200);
    assert(nevents == 2 && !motions);
    focus(0, &pointer_a, &surface_a); button(1, PRESS, &pointer_a, BTN_LEFT);
    pen(T, 1200);
    assert(nevents == 1 && !motions);
    button(0, RELEASE, &pointer_a, BTN_LEFT);
    pen(0, 1200);
    g_active_constraint = NULL;
    pen(T, 500);
    assert(nevents == 0 && motions == 1 && g_pen.route == PEN_ROUTE_POINTER);
    gamescope = NULL;
    pen(T, 500);
    assert(nevents == 1 && g_pen.route == PEN_ROUTE_TABLET);
    frame(0, &surface_a);
}

static void tool_switch(void) {
    tablet_for(&client_b, DROIDDECK_PEN_CAP_PRESSURE);
    pen(T | PEN_RIGHT, 1500);
    assert(nevents == 1 && ensures == 1 && tools_known == 1);
    frame(0, &surface_b);
    assert(last_pen.tool == BTN_TOOL_PEN && last_pen.buttons == DROIDDECK_PEN_RIGHT);
    pen(T | PEN_ERASER | PEN_RIGHT, 1500);
    assert(nevents == 1 && ensures == 2 && tools_known == 3 && g_pen.target == &b);
    frame(0, &surface_b);
    assert(last_pen.tool == BTN_TOOL_RUBBER && last_pen.buttons == DROIDDECK_PEN_RIGHT);
    pen(T | PEN_ERASER | PEN_RIGHT, 1600);
    assert(nevents == 1 && ensures == 2);
    pen(T, 1600);
    assert(nevents == 1 && g_pen.route == PEN_ROUTE_TABLET && last_pen.tool == BTN_TOOL_PEN && !last_pen.buttons);
    frame(0, &surface_b);
    pen(0, 1600);
    assert(nevents == 1);
    out(0);
}

int main(int argc, char **argv) {
    static const struct { const char *name; void (*run)(void); } cases[] = {
        {"side", side}, {"swap", swap}, {"mouse", mouse}, {"gone", gone}, {"frozen", frozen}, {"notablet", no_tablet},
        {"switch", route_switch}, {"axes", axes}, {"target", target_order}, {"orphan", orphan},
        {"query", query}, {"gamescope", refused}, {"tool", tool_switch},
    };
    assert(argc == 2);
    for (size_t i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) {
        if (strcmp(argv[1], cases[i].name)) continue;
        cases[i].run();
        puts("PASS");
        return 0;
    }
    return 2;
}
