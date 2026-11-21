#include <assert.h>
#include <poll.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <wayland-client.h>
#include <wayland-server.h>
#include "droiddeck_ext.h"
#include "tablet-v2-client-protocol.h"

#define PEN 0x140
#define ERASER 0x141
#define CAPS_PT (DROIDDECK_PEN_CAP_PRESSURE | DROIDDECK_PEN_CAP_TILT)

void droiddeck_log(const char *tag, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    printf("[%s] ", tag);
    vprintf(fmt, ap);
    putchar('\n');
    va_end(ap);
}
const char *droiddeck_client_name(struct wl_client *client) { return "harness"; }

static int cursor_calls;
static struct wl_resource *cursor_surface;
void droiddeck_cursor_from_tool(struct wl_resource *surface) {
    cursor_calls++;
    cursor_surface = surface;
}

static struct wl_display *server;
static struct wl_resource *last_surface;

static void surface_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct wl_surface_interface surface_impl = { .destroy = surface_destroy };
#define GONE_TIME 99
static void surface_gone(struct wl_resource *r) { droiddeck_tablet_surface_gone(r, GONE_TIME); }
static void compositor_create_surface(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *s = wl_resource_create(c, &wl_surface_interface, wl_resource_get_version(r), id);
    assert(s);
    wl_resource_set_implementation(s, &surface_impl, NULL, surface_gone);
    last_surface = s;
}
static const struct wl_compositor_interface compositor_impl = { .create_surface = compositor_create_surface };
static void bind_compositor(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    wl_resource_set_implementation(wl_resource_create(c, &wl_compositor_interface, ver, id), &compositor_impl, NULL, NULL);
}
static void seat_release(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct wl_seat_interface seat_impl = { .release = seat_release };
static void bind_seat(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_seat_interface, ver, id);
    wl_resource_set_implementation(r, &seat_impl, NULL, NULL);
    wl_seat_send_capabilities(r, 0);
}

#define MAX_TOOLS 4
struct client;
struct tool_proxy {
    struct client *c;
    struct zwp_tablet_tool_v2 *tool;
    struct zwp_tablet_v2 *tablet;
    uint32_t type, serial;
    struct zwp_tablet_v2 *in_tablet;
    struct wl_surface *in_surface;
    int leaving, events;
};
struct binding {
    struct client *c;
    struct zwp_tablet_seat_v2 *seat;
    struct zwp_tablet_v2 *tablet;
};
struct client {
    struct wl_display *display;
    struct wl_client *server_side;
    struct wl_compositor *compositor;
    struct wl_seat *seat;
    struct zwp_tablet_manager_v2 *manager;
    uint32_t version;
    struct binding bindings[2];
    int nbindings;
    struct zwp_tablet_v2 *tablets[2];
    int ntablets;
    struct tool_proxy tools[MAX_TOOLS];
    int ntools;
    uint32_t last_serial;
    char log[8192];
};

static struct client *clients[4];
static int nclients;

static void note(struct client *c, const char *fmt, ...) {
    char e[128];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(e, sizeof(e), fmt, ap);
    va_end(ap);
    size_t n = strlen(c->log);
    snprintf(c->log + n, sizeof(c->log) - n, "%s%s", n ? " " : "", e);
}

static const char *tool_label(struct tool_proxy *t) { return t->type == ERASER ? "eraser" : "pen"; }

static struct client *in_proximity(void *d) {
    struct tool_proxy *p = d;
    assert(p->in_surface);
    p->events++;
    return p->c;
}

static void tablet_name(void *d, struct zwp_tablet_v2 *t, const char *name) { note(d, "name=%s", name); }
static void tablet_id(void *d, struct zwp_tablet_v2 *t, uint32_t vid, uint32_t pid) { note(d, "id"); }
static void tablet_path(void *d, struct zwp_tablet_v2 *t, const char *path) { note(d, "path"); }
static void tablet_done(void *d, struct zwp_tablet_v2 *t) { note(d, "tablet_done"); }
static void tablet_removed(void *d, struct zwp_tablet_v2 *t) { note(d, "tablet_removed"); }
static void tablet_bustype(void *d, struct zwp_tablet_v2 *t, uint32_t bustype) { note(d, "bustype=%u", bustype); }
static const struct zwp_tablet_v2_listener tablet_listener = {
    .name = tablet_name, .id = tablet_id, .path = tablet_path, .done = tablet_done,
    .removed = tablet_removed, .bustype = tablet_bustype,
};

static void tool_type(void *d, struct zwp_tablet_tool_v2 *t, uint32_t type) {
    struct tool_proxy *p = d;
    p->type = type;
    note(p->c, "type=%#x", type);
}
static void tool_serial(void *d, struct zwp_tablet_tool_v2 *t, uint32_t hi, uint32_t lo) { note(((struct tool_proxy *)d)->c, "hardware_serial"); }
static void tool_hwid(void *d, struct zwp_tablet_tool_v2 *t, uint32_t hi, uint32_t lo) { note(((struct tool_proxy *)d)->c, "hardware_id"); }
static void tool_capability(void *d, struct zwp_tablet_tool_v2 *t, uint32_t cap) { note(((struct tool_proxy *)d)->c, "cap=%u", cap); }
static void tool_done(void *d, struct zwp_tablet_tool_v2 *t) { note(((struct tool_proxy *)d)->c, "tool_done"); }
static void tool_removed(void *d, struct zwp_tablet_tool_v2 *t) { note(((struct tool_proxy *)d)->c, "tool_removed"); }
static void tool_in(void *d, struct zwp_tablet_tool_v2 *t, uint32_t serial, struct zwp_tablet_v2 *tablet,
                    struct wl_surface *surface) {
    struct tool_proxy *p = d;
    assert(!p->in_surface && surface);
    p->serial = serial;
    p->in_tablet = tablet;
    p->in_surface = surface;
    p->events++;
    assert(serial >= p->c->last_serial);
    p->c->last_serial = serial;
    note(p->c, "in:%s", tool_label(p));
}
static void tool_out(void *d, struct zwp_tablet_tool_v2 *t) {
    struct tool_proxy *p = d;
    assert(p->in_surface);
    p->in_surface = NULL;
    p->leaving = 1;
    p->events++;
    note(p->c, "out:%s", tool_label(p));
}
static void tool_down(void *d, struct zwp_tablet_tool_v2 *t, uint32_t serial) {
    struct tool_proxy *p = d;
    assert(p->in_surface && serial > p->c->last_serial);
    p->c->last_serial = serial;
    p->events++;
    note(p->c, "down");
}
static void tool_up(void *d, struct zwp_tablet_tool_v2 *t) { note(in_proximity(d), "up"); }
static void tool_motion(void *d, struct zwp_tablet_tool_v2 *t, wl_fixed_t x, wl_fixed_t y) {
    note(in_proximity(d), "motion=%.2f,%.2f", wl_fixed_to_double(x), wl_fixed_to_double(y));
}
static void tool_pressure(void *d, struct zwp_tablet_tool_v2 *t, uint32_t v) { note(in_proximity(d), "pressure=%u", v); }
static void tool_distance(void *d, struct zwp_tablet_tool_v2 *t, uint32_t v) { note(in_proximity(d), "distance=%u", v); }
static void tool_tilt(void *d, struct zwp_tablet_tool_v2 *t, wl_fixed_t x, wl_fixed_t y) {
    note(in_proximity(d), "tilt=%.2f,%.2f", wl_fixed_to_double(x), wl_fixed_to_double(y));
}
static void tool_rotation(void *d, struct zwp_tablet_tool_v2 *t, wl_fixed_t v) { note(((struct tool_proxy *)d)->c, "rotation"); }
static void tool_slider(void *d, struct zwp_tablet_tool_v2 *t, int32_t v) { note(((struct tool_proxy *)d)->c, "slider"); }
static void tool_wheel(void *d, struct zwp_tablet_tool_v2 *t, wl_fixed_t v, int32_t clicks) { note(((struct tool_proxy *)d)->c, "wheel"); }
static void tool_button(void *d, struct zwp_tablet_tool_v2 *t, uint32_t serial, uint32_t button, uint32_t state) {
    struct tool_proxy *p = d;
    assert(p->in_surface && serial > p->c->last_serial);
    p->c->last_serial = serial;
    p->events++;
    note(p->c, "button=%#x,%u", button, state);
}
static void tool_frame(void *d, struct zwp_tablet_tool_v2 *t, uint32_t time) {
    struct tool_proxy *p = d;
    assert((p->in_surface || p->leaving) && p->events > 0);
    p->leaving = 0;
    p->events = 0;
    note(p->c, "frame=%u", time);
}
static const struct zwp_tablet_tool_v2_listener tool_listener = {
    .type = tool_type, .hardware_serial = tool_serial, .hardware_id_wacom = tool_hwid,
    .capability = tool_capability, .done = tool_done, .removed = tool_removed,
    .proximity_in = tool_in, .proximity_out = tool_out, .down = tool_down, .up = tool_up,
    .motion = tool_motion, .pressure = tool_pressure, .distance = tool_distance, .tilt = tool_tilt,
    .rotation = tool_rotation, .slider = tool_slider, .wheel = tool_wheel, .button = tool_button,
    .frame = tool_frame,
};

static void seat_tablet_added(void *d, struct zwp_tablet_seat_v2 *s, struct zwp_tablet_v2 *tablet) {
    struct binding *b = d;
    struct client *c = b->c;
    assert(c->ntablets < 2 && !b->tablet);
    b->tablet = tablet;
    c->tablets[c->ntablets++] = tablet;
    zwp_tablet_v2_add_listener(tablet, &tablet_listener, c);
    note(c, "tablet_added");
}
static void seat_tool_added(void *d, struct zwp_tablet_seat_v2 *s, struct zwp_tablet_tool_v2 *tool) {
    struct binding *b = d;
    struct client *c = b->c;
    assert(c->ntools < MAX_TOOLS && b->tablet);
    struct tool_proxy *p = &c->tools[c->ntools++];
    p->c = c;
    p->tool = tool;
    p->tablet = b->tablet;
    zwp_tablet_tool_v2_add_listener(tool, &tool_listener, p);
    note(c, "tool_added");
}
static void seat_pad_added(void *d, struct zwp_tablet_seat_v2 *s, struct zwp_tablet_pad_v2 *pad) {
    note(((struct binding *)d)->c, "pad_added");
}
static const struct zwp_tablet_seat_v2_listener seat_listener = {
    .tablet_added = seat_tablet_added, .tool_added = seat_tool_added, .pad_added = seat_pad_added,
};

static void registry_global(void *d, struct wl_registry *r, uint32_t name, const char *iface, uint32_t ver) {
    struct client *c = d;
    if (!strcmp(iface, wl_compositor_interface.name))
        c->compositor = wl_registry_bind(r, name, &wl_compositor_interface, 4);
    else if (!strcmp(iface, wl_seat_interface.name))
        c->seat = wl_registry_bind(r, name, &wl_seat_interface, 1);
    else if (!strcmp(iface, zwp_tablet_manager_v2_interface.name)) {
        assert(ver == 2);
        c->manager = wl_registry_bind(r, name, &zwp_tablet_manager_v2_interface, c->version);
    }
}
static void registry_remove(void *d, struct wl_registry *r, uint32_t name) {}
static const struct wl_registry_listener registry_listener = { registry_global, registry_remove };

static void pump(void) {
    for (int round = 0; round < 6; round++) {
        for (int i = 0; i < nclients; i++) if (clients[i]) wl_display_flush(clients[i]->display);
        wl_event_loop_dispatch(wl_display_get_event_loop(server), 0);
        wl_display_flush_clients(server);
        for (int i = 0; i < nclients; i++) {
            struct wl_display *d;
            if (!clients[i]) continue;
            d = clients[i]->display;
            while (wl_display_prepare_read(d) != 0) wl_display_dispatch_pending(d);
            struct pollfd p = { .fd = wl_display_get_fd(d), .events = POLLIN };
            if (poll(&p, 1, 0) > 0) wl_display_read_events(d); else wl_display_cancel_read(d);
            wl_display_dispatch_pending(d);
        }
    }
}

static struct client *connect_client(uint32_t version) {
    struct client *c = calloc(1, sizeof(*c));
    int sv[2];
    assert(c && socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, sv) == 0);
    c->version = version;
    c->server_side = wl_client_create(server, sv[0]);
    c->display = wl_display_connect_to_fd(sv[1]);
    assert(c->server_side && c->display);
    clients[nclients++] = c;
    wl_registry_add_listener(wl_display_get_registry(c->display), &registry_listener, c);
    pump();
    assert(c->compositor && c->seat && c->manager);
    return c;
}

static struct binding *bind_tablet_seat(struct client *c) {
    assert(c->nbindings < 2);
    struct binding *b = &c->bindings[c->nbindings++];
    b->c = c;
    b->seat = zwp_tablet_manager_v2_get_tablet_seat(c->manager, c->seat);
    zwp_tablet_seat_v2_add_listener(b->seat, &seat_listener, b);
    pump();
    return b;
}

static struct client *tablet_client(uint32_t version) {
    struct client *c = connect_client(version);
    bind_tablet_seat(c);
    return c;
}

static struct wl_resource *make_surface(struct client *c, struct wl_surface **out) {
    last_surface = NULL;
    struct wl_surface *s = wl_compositor_create_surface(c->compositor);
    pump();
    assert(last_surface && wl_resource_get_client(last_surface) == c->server_side);
    if (out) *out = s;
    return last_surface;
}

static void disconnect(struct client *c) {
    for (int i = 0; i < nclients; i++) if (clients[i] == c) clients[i] = NULL;
    wl_display_disconnect(c->display);
    free(c);
    pump();
}

static void expect(struct client *c, const char *events) {
    if (strcmp(c->log, events)) {
        fprintf(stderr, "expected: %s\n     got: %s\n", events, c->log);
        abort();
    }
    c->log[0] = 0;
}

static struct tool_proxy *tool_of(struct client *c, uint32_t type, int nth) {
    for (int i = 0; i < c->ntools; i++)
        if (c->tools[i].type == type && nth-- == 0) return &c->tools[i];
    return NULL;
}

static struct droiddeck_pen hover(int tool, double x, double y, uint32_t time) {
    return (struct droiddeck_pen){ .tool = tool, .x = x, .y = y, .time_ms = time };
}

static void frame(struct wl_resource *surface, struct droiddeck_pen pen) {
    droiddeck_tablet_frame(surface, &pen);
    pump();
}

#define TABLET_V2 "tablet_added name=DroidDeck Pen bustype=6 tablet_done"
#define PEN_PT "tool_added type=0x140 cap=1 cap=2 tool_done"
#define ERASER_PT "tool_added type=0x141 cap=1 cap=2 tool_done"

static void bind_before_and_after(void) {
    struct client *a = tablet_client(2);
    expect(a, "");
    droiddeck_tablet_ensure_tool(PEN);
    droiddeck_tablet_set_capabilities(0);
    pump();
    expect(a, "");
    assert(!droiddeck_tablet_client_has_tool(a->server_side, PEN));
    assert(droiddeck_tablet_client_bound(a->server_side));
    droiddeck_tablet_set_capabilities(DROIDDECK_PEN_CAP_PRESSURE);
    pump();
    expect(a, TABLET_V2);
    droiddeck_tablet_set_capabilities(DROIDDECK_PEN_CAP_TILT);
    pump();
    expect(a, "");
    struct client *b = connect_client(2), *v1 = connect_client(1);
    assert(!droiddeck_tablet_client_bound(b->server_side));
    bind_tablet_seat(b);
    bind_tablet_seat(v1);
    expect(b, TABLET_V2);
    expect(v1, "tablet_added name=DroidDeck Pen tablet_done");
    assert(droiddeck_tablet_client_bound(b->server_side) && a->ntablets == 1 && b->ntablets == 1);
}

static void lazy_tools(void) {
    droiddeck_tablet_set_capabilities(CAPS_PT);
    struct client *a = tablet_client(2);
    expect(a, TABLET_V2);
    assert(!droiddeck_tablet_client_has_tool(a->server_side, PEN));
    droiddeck_tablet_ensure_tool(0x142);
    pump();
    expect(a, "");
    droiddeck_tablet_ensure_tool(PEN);
    assert(droiddeck_tablet_client_has_tool(a->server_side, PEN));
    assert(!droiddeck_tablet_client_has_tool(a->server_side, ERASER));
    pump();
    expect(a, PEN_PT);
    droiddeck_tablet_ensure_tool(PEN);
    droiddeck_tablet_set_capabilities(DROIDDECK_PEN_CAP_DISTANCE);
    pump();
    expect(a, "");
    droiddeck_tablet_ensure_tool(ERASER);
    pump();
    expect(a, "tool_added type=0x141 cap=1 cap=2 cap=3 tool_done");
    assert(droiddeck_tablet_client_has_tool(a->server_side, ERASER));
    struct wl_resource *s = make_surface(a, NULL);
    struct droiddeck_pen p = hover(ERASER, 1, 2, 10);
    p.distance = 700;
    frame(s, p);
    expect(a, "in:eraser motion=1.00,2.00 pressure=0 distance=700 tilt=0.00,0.00 frame=10");
    p.distance = 0;
    p.time_ms = 11;
    frame(s, p);
    expect(a, "distance=0 frame=11");
    p = hover(PEN, 1, 2, 12);
    p.distance = 900;
    frame(s, p);
    expect(a, "out:eraser frame=12 in:pen motion=1.00,2.00 pressure=0 tilt=0.00,0.00 frame=12");
}

static void hover_in_motion_frame(void) {
    droiddeck_tablet_set_capabilities(CAPS_PT);
    droiddeck_tablet_ensure_tool(PEN);
    struct client *a = tablet_client(2), *b = tablet_client(2);
    expect(a, TABLET_V2 " " PEN_PT);
    expect(b, TABLET_V2 " " PEN_PT);
    struct wl_surface *surface;
    struct wl_resource *s = make_surface(a, &surface), *sb = make_surface(b, NULL);
    struct droiddeck_pen p = hover(PEN, 10.5, 20.25, 100);
    frame(s, p);
    expect(a, "in:pen motion=10.50,20.25 pressure=0 tilt=0.00,0.00 frame=100");
    struct tool_proxy *t = tool_of(a, PEN, 0);
    assert(t->in_surface == surface && t->in_tablet == a->tablets[0]);
    p.has_tilt = 1; p.tilt_x = 10.5; p.tilt_y = -20; p.time_ms = 101;
    frame(s, p);
    expect(a, "tilt=10.50,-20.00 frame=101");
    p.has_tilt = 0; p.x = 11; p.time_ms = 102;
    frame(s, p);
    expect(a, "motion=11.00,20.25 frame=102");
    p.time_ms = 103;
    frame(s, p);
    p.distance = 5000; p.time_ms = 104;
    frame(s, p);
    expect(a, "");
    p.down = 1; p.pressure = 100; p.time_ms = 105;
    frame(s, p);
    frame(s, p);
    p.down = 0; p.pressure = 0; p.time_ms = 106;
    frame(s, p);
    droiddeck_tablet_out(107);
    pump();
    droiddeck_tablet_out(108);
    pump();
    expect(a, "pressure=100 down frame=105 pressure=0 up frame=106 out:pen frame=107");
    p.time_ms = 109;
    frame(sb, p);
    expect(b, "in:pen motion=11.00,20.25 pressure=0 tilt=10.50,-20.00 frame=109");
}

static void down_with_pressure_and_tilt(void) {
    droiddeck_tablet_set_capabilities(CAPS_PT);
    droiddeck_tablet_ensure_tool(PEN);
    struct client *a = tablet_client(2);
    struct wl_resource *s = make_surface(a, NULL);
    expect(a, TABLET_V2 " " PEN_PT);
    struct droiddeck_pen p = hover(PEN, 5, 5, 1);
    frame(s, p);
    expect(a, "in:pen motion=5.00,5.00 pressure=0 tilt=0.00,0.00 frame=1");
    p.down = 1; p.pressure = 32768; p.has_tilt = 1; p.tilt_x = -45; p.tilt_y = 30.25; p.time_ms = 2;
    frame(s, p);
    expect(a, "pressure=32768 tilt=-45.00,30.25 down frame=2");
    p.x = -30; p.y = 5000.5; p.pressure = 65535; p.time_ms = 3;
    frame(s, p);
    expect(a, "motion=-30.00,5000.50 pressure=65535 frame=3");
    droiddeck_tablet_out(4);
    pump();
    expect(a, "up out:pen frame=4");
}

static void buttons(void) {
    droiddeck_tablet_set_capabilities(CAPS_PT);
    droiddeck_tablet_ensure_tool(PEN);
    struct client *a = tablet_client(2);
    struct wl_resource *s = make_surface(a, NULL);
    expect(a, TABLET_V2 " " PEN_PT);
    struct droiddeck_pen p = hover(PEN, 5, 5, 1);
    p.buttons = DROIDDECK_PEN_RIGHT;
    frame(s, p);
    expect(a, "in:pen motion=5.00,5.00 pressure=0 tilt=0.00,0.00 button=0x14c,1 frame=1");
    p.buttons = DROIDDECK_PEN_RIGHT | DROIDDECK_PEN_MIDDLE; p.time_ms = 2;
    frame(s, p);
    expect(a, "button=0x14b,1 frame=2");
    p.buttons = DROIDDECK_PEN_MIDDLE; p.time_ms = 3;
    frame(s, p);
    expect(a, "button=0x14c,0 frame=3");
    p.buttons = DROIDDECK_PEN_RIGHT | DROIDDECK_PEN_MIDDLE; p.down = 1; p.time_ms = 4;
    frame(s, p);
    expect(a, "down button=0x14c,1 frame=4");
    droiddeck_tablet_out(5);
    pump();
    expect(a, "up button=0x14c,0 button=0x14b,0 out:pen frame=5");
}

static void focus_change(void) {
    droiddeck_tablet_set_capabilities(CAPS_PT);
    droiddeck_tablet_ensure_tool(PEN);
    struct client *a = tablet_client(2), *b = tablet_client(2);
    struct wl_resource *sa = make_surface(a, NULL), *sa2 = make_surface(a, NULL), *sb = make_surface(b, NULL);
    expect(a, TABLET_V2 " " PEN_PT);
    expect(b, TABLET_V2 " " PEN_PT);
    struct droiddeck_pen p = hover(PEN, 1, 1, 1);
    frame(sa, p);
    expect(a, "in:pen motion=1.00,1.00 pressure=0 tilt=0.00,0.00 frame=1");
    p.time_ms = 2;
    frame(sb, p);
    expect(a, "out:pen frame=2");
    expect(b, "in:pen motion=1.00,1.00 pressure=0 tilt=0.00,0.00 frame=2");
    p.time_ms = 3;
    frame(sa, p);
    expect(b, "out:pen frame=3");
    expect(a, "in:pen motion=1.00,1.00 pressure=0 tilt=0.00,0.00 frame=3");
    p.time_ms = 4; p.down = 1; p.buttons = DROIDDECK_PEN_MIDDLE; p.pressure = 9;
    frame(sa2, p);
    expect(a, "out:pen frame=4 in:pen motion=1.00,1.00 pressure=9 tilt=0.00,0.00 down button=0x14b,1 frame=4");
    p.time_ms = 5;
    frame(sb, p);
    expect(a, "up button=0x14b,0 out:pen frame=5");
    expect(b, "in:pen motion=1.00,1.00 pressure=9 tilt=0.00,0.00 down button=0x14b,1 frame=5");
    droiddeck_tablet_out(6);
    pump();
    expect(b, "up button=0x14b,0 out:pen frame=6");
    expect(a, "");
}

static void tool_switch_in_proximity(void) {
    droiddeck_tablet_set_capabilities(CAPS_PT);
    droiddeck_tablet_ensure_tool(PEN);
    struct client *a = tablet_client(2);
    struct wl_surface *cursor;
    struct wl_resource *s = make_surface(a, NULL);
    make_surface(a, &cursor);
    expect(a, TABLET_V2 " " PEN_PT);
    struct droiddeck_pen p = hover(PEN, 1, 2, 1);
    p.buttons = DROIDDECK_PEN_RIGHT;
    frame(s, p);
    expect(a, "in:pen motion=1.00,2.00 pressure=0 tilt=0.00,0.00 button=0x14c,1 frame=1");
    droiddeck_tablet_ensure_tool(ERASER);
    p.tool = ERASER; p.time_ms = 2;
    frame(s, p);
    expect(a, ERASER_PT " button=0x14c,0 out:pen frame=2 in:eraser motion=1.00,2.00 pressure=0 tilt=0.00,0.00 button=0x14c,1 frame=2");
    struct tool_proxy *pen = tool_of(a, PEN, 0), *eraser = tool_of(a, ERASER, 0);
    assert(!pen->in_surface && eraser->in_surface && eraser->in_tablet == a->tablets[0] && eraser->serial > pen->serial);
    zwp_tablet_tool_v2_set_cursor(eraser->tool, pen->serial, cursor, 1, 1);
    zwp_tablet_tool_v2_set_cursor(pen->tool, eraser->serial, cursor, 1, 1);
    pump();
    assert(cursor_calls == 0);
    zwp_tablet_tool_v2_set_cursor(eraser->tool, eraser->serial, cursor, 1, 1);
    pump();
    assert(cursor_calls == 1);
    p.tool = PEN; p.buttons = 0; p.x = 3; p.time_ms = 3;
    frame(s, p);
    expect(a, "button=0x14c,0 out:eraser frame=3 in:pen motion=3.00,2.00 pressure=0 tilt=0.00,0.00 frame=3");
    droiddeck_tablet_out(4);
    pump();
    expect(a, "out:pen frame=4");
}

static void set_cursor(void) {
    droiddeck_tablet_set_capabilities(CAPS_PT);
    droiddeck_tablet_ensure_tool(PEN);
    droiddeck_tablet_ensure_tool(ERASER);
    struct client *a = tablet_client(2), *b = tablet_client(2);
    struct wl_surface *cursor;
    struct wl_resource *s = make_surface(a, NULL), *cs = make_surface(a, &cursor);
    struct wl_surface *other_cursor;
    make_surface(b, &other_cursor);
    struct tool_proxy *pen = tool_of(a, PEN, 0), *eraser = tool_of(a, ERASER, 0), *other = tool_of(b, PEN, 0);
    zwp_tablet_tool_v2_set_cursor(pen->tool, 0, NULL, 0, 0);
    pump();
    assert(cursor_calls == 0);
    frame(s, hover(PEN, 1, 1, 1));
    zwp_tablet_tool_v2_set_cursor(pen->tool, pen->serial + 1, cursor, 1, 2);
    zwp_tablet_tool_v2_set_cursor(pen->tool, pen->serial - 1, cursor, 1, 2);
    zwp_tablet_tool_v2_set_cursor(eraser->tool, pen->serial, cursor, 1, 2);
    zwp_tablet_tool_v2_set_cursor(other->tool, pen->serial, other_cursor, 1, 2);
    pump();
    assert(cursor_calls == 0);
    zwp_tablet_tool_v2_set_cursor(pen->tool, pen->serial, NULL, 0, 0);
    pump();
    assert(cursor_calls == 1 && cursor_surface == NULL);
    zwp_tablet_tool_v2_set_cursor(pen->tool, pen->serial, cursor, 3, 4);
    pump();
    assert(cursor_calls == 2 && cursor_surface == cs);
    uint32_t old = pen->serial;
    droiddeck_tablet_out(2);
    pump();
    zwp_tablet_tool_v2_set_cursor(pen->tool, old, cursor, 3, 4);
    pump();
    assert(cursor_calls == 2);
    frame(s, hover(PEN, 1, 1, 3));
    assert(pen->serial != old);
    zwp_tablet_tool_v2_set_cursor(pen->tool, old, cursor, 3, 4);
    pump();
    assert(cursor_calls == 2);
    assert(wl_display_get_error(a->display) == 0 && wl_display_get_error(b->display) == 0);
}

static void surface_destroyed_while_down(void) {
    droiddeck_tablet_set_capabilities(CAPS_PT);
    droiddeck_tablet_ensure_tool(PEN);
    struct client *a = tablet_client(2);
    struct wl_surface *surface;
    struct wl_resource *s = make_surface(a, &surface), *s2 = make_surface(a, NULL);
    expect(a, TABLET_V2 " " PEN_PT);
    struct droiddeck_pen p = hover(PEN, 1, 1, 1);
    p.down = 1; p.buttons = DROIDDECK_PEN_RIGHT;
    frame(s, p);
    expect(a, "in:pen motion=1.00,1.00 pressure=0 tilt=0.00,0.00 down button=0x14c,1 frame=1");
    wl_surface_destroy(surface);
    pump();
    expect(a, "up button=0x14c,0 out:pen frame=99");
    droiddeck_tablet_out(5);
    pump();
    expect(a, "");
    p.down = 0; p.buttons = 0; p.time_ms = 6;
    frame(s2, p);
    expect(a, "in:pen motion=1.00,1.00 pressure=0 tilt=0.00,0.00 frame=6");
}

static void disconnect_mid_stroke(void) {
    droiddeck_tablet_set_capabilities(CAPS_PT);
    droiddeck_tablet_ensure_tool(PEN);
    struct client *a = tablet_client(2), *b = tablet_client(2);
    struct wl_resource *sa = make_surface(a, NULL), *sb = make_surface(b, NULL);
    struct droiddeck_pen p = hover(PEN, 1, 1, 1);
    p.down = 1; p.buttons = DROIDDECK_PEN_MIDDLE;
    frame(sa, p);
    assert(strstr(a->log, "in:pen") && strstr(a->log, "down"));
    disconnect(a);
    assert(!droiddeck_tablet_client_has_tool(b->server_side, ERASER));
    droiddeck_tablet_out(2);
    droiddeck_tablet_ensure_tool(ERASER);
    pump();
    p.time_ms = 3;
    frame(sb, p);
    expect(b, TABLET_V2 " " PEN_PT " " ERASER_PT " in:pen motion=1.00,1.00 pressure=0 tilt=0.00,0.00 down button=0x14b,1 frame=3");
    disconnect(b);
    droiddeck_tablet_out(4);
    struct client *c = tablet_client(2);
    expect(c, TABLET_V2 " " PEN_PT " " ERASER_PT);
}

static void late_bind(void) {
    droiddeck_tablet_set_capabilities(DROIDDECK_PEN_CAP_PRESSURE);
    droiddeck_tablet_ensure_tool(ERASER);
    droiddeck_tablet_ensure_tool(PEN);
    struct client *a = connect_client(2);
    struct wl_resource *s = make_surface(a, NULL);
    expect(a, "");
    assert(!droiddeck_tablet_client_has_tool(a->server_side, PEN));
    bind_tablet_seat(a);
    expect(a, TABLET_V2 " tool_added type=0x140 cap=2 tool_done tool_added type=0x141 cap=2 tool_done");
    assert(droiddeck_tablet_client_has_tool(a->server_side, PEN) && droiddeck_tablet_client_has_tool(a->server_side, ERASER));
    struct droiddeck_pen p = hover(PEN, 3, 4, 9);
    p.has_tilt = 1; p.tilt_x = 10;
    frame(s, p);
    expect(a, "in:pen motion=3.00,4.00 pressure=0 frame=9");
}

static void two_bindings(void) {
    droiddeck_tablet_set_capabilities(CAPS_PT);
    droiddeck_tablet_ensure_tool(PEN);
    struct client *a = connect_client(2);
    struct binding *first = bind_tablet_seat(a);
    struct wl_resource *s = make_surface(a, NULL);
    expect(a, TABLET_V2 " " PEN_PT);
    struct binding *second = bind_tablet_seat(a);
    expect(a, TABLET_V2 " " PEN_PT);
    assert(a->ntablets == 2 && a->ntools == 2 && first->tablet != second->tablet);
    frame(s, hover(PEN, 1, 1, 1));
    for (int i = 0; i < 2; i++) assert(a->tools[i].in_surface && a->tools[i].in_tablet == a->tools[i].tablet);
    expect(a, "in:pen in:pen motion=1.00,1.00 pressure=0 tilt=0.00,0.00 frame=1 motion=1.00,1.00 pressure=0 tilt=0.00,0.00 frame=1");
    zwp_tablet_seat_v2_destroy(first->seat);
    zwp_tablet_v2_destroy(second->tablet);
    pump();
    droiddeck_tablet_ensure_tool(ERASER);
    pump();
    assert(a->ntools == 2 && droiddeck_tablet_client_bound(a->server_side));
    assert(droiddeck_tablet_client_has_tool(a->server_side, PEN));
    assert(!droiddeck_tablet_client_has_tool(a->server_side, ERASER));
    struct droiddeck_pen p = hover(PEN, 2, 1, 2);
    frame(s, p);
    expect(a, "motion=2.00,1.00 frame=2");
    zwp_tablet_v2_destroy(first->tablet);
    pump();
    assert(!droiddeck_tablet_client_has_tool(a->server_side, PEN));
    p.time_ms = 3;
    frame(s, p);
    droiddeck_tablet_out(4);
    pump();
    expect(a, "");
}

static void tool_destroyed_in_proximity(void) {
    droiddeck_tablet_set_capabilities(CAPS_PT);
    droiddeck_tablet_ensure_tool(PEN);
    struct client *a = tablet_client(2);
    struct wl_resource *s = make_surface(a, NULL);
    expect(a, TABLET_V2 " " PEN_PT);
    struct droiddeck_pen p = hover(PEN, 1, 1, 1);
    p.down = 1;
    frame(s, p);
    expect(a, "in:pen motion=1.00,1.00 pressure=0 tilt=0.00,0.00 down frame=1");
    zwp_tablet_tool_v2_destroy(tool_of(a, PEN, 0)->tool);
    pump();
    assert(!droiddeck_tablet_client_has_tool(a->server_side, PEN));
    p.time_ms = 2;
    frame(s, p);
    expect(a, "");
    bind_tablet_seat(a);
    expect(a, TABLET_V2 " " PEN_PT);
    p.time_ms = 4;
    frame(s, p);
    expect(a, "in:pen motion=1.00,1.00 pressure=0 tilt=0.00,0.00 down frame=4");
}

static void rebind_in_proximity(void) {
    droiddeck_tablet_set_capabilities(CAPS_PT);
    droiddeck_tablet_ensure_tool(PEN);
    struct client *a = tablet_client(2);
    struct wl_surface *surface;
    struct wl_resource *s = make_surface(a, &surface);
    expect(a, TABLET_V2 " " PEN_PT);
    struct droiddeck_pen p = hover(PEN, 1, 1, 1);
    frame(s, p);
    expect(a, "in:pen motion=1.00,1.00 pressure=0 tilt=0.00,0.00 frame=1");
    bind_tablet_seat(a);
    expect(a, TABLET_V2 " " PEN_PT);
    struct tool_proxy *first = tool_of(a, PEN, 0), *second = tool_of(a, PEN, 1);
    p.x = 2; p.time_ms = 2;
    frame(s, p);
    expect(a, "motion=2.00,1.00 frame=2");
    assert(first->in_surface == surface && !second->in_surface);
    zwp_tablet_tool_v2_set_cursor(second->tool, first->serial, NULL, 0, 0);
    pump();
    assert(cursor_calls == 0);
    zwp_tablet_tool_v2_set_cursor(first->tool, first->serial, NULL, 0, 0);
    pump();
    assert(cursor_calls == 1);
    droiddeck_tablet_out(3);
    pump();
    expect(a, "out:pen frame=3");
    p.time_ms = 4;
    frame(s, p);
    expect(a, "in:pen in:pen motion=2.00,1.00 pressure=0 tilt=0.00,0.00 frame=4 motion=2.00,1.00 pressure=0 tilt=0.00,0.00 frame=4");
    assert(second->in_surface == surface && second->serial == first->serial);

    struct client *b = tablet_client(2);
    struct wl_resource *sb = make_surface(b, NULL);
    expect(b, TABLET_V2 " " PEN_PT);
    p.down = 1; p.buttons = DROIDDECK_PEN_RIGHT; p.pressure = 7; p.time_ms = 5;
    frame(sb, p);
    expect(a, "out:pen frame=5 out:pen frame=5");
    expect(b, "in:pen motion=2.00,1.00 pressure=7 tilt=0.00,0.00 down button=0x14c,1 frame=5");
    bind_tablet_seat(b);
    expect(b, TABLET_V2 " " PEN_PT);
    p.time_ms = 6;
    frame(sb, p);
    expect(b, "");
    zwp_tablet_v2_destroy(b->bindings[0].tablet);
    pump();
    assert(droiddeck_tablet_client_has_tool(b->server_side, PEN));
    p.time_ms = 7;
    frame(sb, p);
    expect(b, "in:pen motion=2.00,1.00 pressure=7 tilt=0.00,0.00 down button=0x14c,1 frame=7");
    droiddeck_tablet_out(8);
    pump();
    expect(b, "up button=0x14c,0 out:pen frame=8");
    expect(a, "");
}

int main(int argc, char **argv) {
    static const struct { const char *name; void (*run)(void); } cases[] = {
        {"bind", bind_before_and_after}, {"tools", lazy_tools}, {"hover", hover_in_motion_frame},
        {"down", down_with_pressure_and_tilt}, {"buttons", buttons}, {"focus", focus_change},
        {"switch", tool_switch_in_proximity}, {"cursor", set_cursor}, {"surface", surface_destroyed_while_down},
        {"disconnect", disconnect_mid_stroke}, {"late", late_bind}, {"bindings", two_bindings},
        {"toolgone", tool_destroyed_in_proximity}, {"rebind", rebind_in_proximity},
    };
    assert(argc == 2);
    server = wl_display_create();
    assert(server);
    wl_global_create(server, &wl_compositor_interface, 4, NULL, bind_compositor);
    wl_global_create(server, &wl_seat_interface, 7, NULL, bind_seat);
    tablet_init(server);
    for (size_t i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) {
        if (strcmp(argv[1], cases[i].name)) continue;
        cases[i].run();
        for (int k = 0; k < nclients; k++) if (clients[k]) assert(wl_display_get_error(clients[k]->display) == 0);
        for (int k = 0; k < nclients; k++) if (clients[k]) disconnect(clients[k]);
        wl_display_destroy(server);
        puts("PASS");
        return 0;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
