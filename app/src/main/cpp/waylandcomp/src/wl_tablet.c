#include <stdlib.h>
#include "droiddeck_ext.h"
#include "tablet-v2-server-protocol.h"

#ifndef BTN_TOOL_PEN
#define BTN_TOOL_PEN 0x140
#endif
#ifndef BTN_TOOL_RUBBER
#define BTN_TOOL_RUBBER 0x141
#endif
#ifndef BTN_STYLUS
#define BTN_STYLUS 0x14b
#endif
#ifndef BTN_STYLUS2
#define BTN_STYLUS2 0x14c
#endif

#define TABLET_NAME "DroidDeck Pen"
#define TOOLS 2
#define BUTTONS 2
#define SENT_TABLET 1u
#define SENT_TOOL(i) (2u << (i))

static const uint32_t button_codes[BUTTONS] = { BTN_STYLUS2, BTN_STYLUS };

struct tablet_seat {
    struct wl_resource *seat, *tablet, *tools[TOOLS];
    unsigned sent, entered;
};

struct tool {
    uint32_t type;
    int known, caps;
    struct wl_list resources;
    struct wl_resource *focus;
    struct wl_client *client;
    uint32_t serial;
    int down, fresh;
    uint32_t buttons;
    wl_fixed_t x, y, tilt_x, tilt_y;
    uint32_t pressure, distance;
};

static struct wl_display *g_disp;
static struct wl_list g_seats;
static struct tool g_tools[TOOLS];
static struct tool *g_active;
static int g_caps, g_announced;

static struct tool *tool_for(int tool) {
    if (tool == BTN_TOOL_PEN) return &g_tools[0];
    if (tool == BTN_TOOL_RUBBER) return &g_tools[1];
    return NULL;
}

static const char *tool_name(const struct tool *t) {
    return t->type == ZWP_TABLET_TOOL_V2_TYPE_ERASER ? "eraser" : "pen";
}
static unsigned tool_bit(const struct tool *t) { return 1u << (t - g_tools); }

static struct tablet_seat *live(struct wl_resource *tool_resource) {
    struct tablet_seat *s = wl_resource_get_user_data(tool_resource);
    return s && s->tablet ? s : NULL;
}

static int has_live_resource(struct tool *t, struct wl_client *client) {
    struct wl_resource *r;
    wl_resource_for_each(r, &t->resources)
        if (wl_resource_get_client(r) == client && live(r)) return 1;
    return 0;
}

static int entered(struct tool *t, struct wl_resource *tool_resource) {
    struct tablet_seat *s = live(tool_resource);
    return s && (s->entered & tool_bit(t));
}

static int has_entered_resource(struct tool *t) {
    struct wl_resource *r;
    wl_resource_for_each(r, &t->resources)
        if (entered(t, r)) return 1;
    return 0;
}

static void tool_drop_focus(struct tool *t) {
    struct wl_resource *r;
    wl_resource_for_each(r, &t->resources) {
        struct tablet_seat *s = wl_resource_get_user_data(r);
        if (s) s->entered &= ~tool_bit(t);
    }
    t->focus = NULL;
    t->client = NULL;
    t->down = 0;
    t->buttons = 0;
    if (g_active == t) g_active = NULL;
}

static void forget_dead_focus(struct wl_client *client) {
    for (int i = 0; i < TOOLS; i++)
        if (g_tools[i].client == client && !has_entered_resource(&g_tools[i])) tool_drop_focus(&g_tools[i]);
}

static void seat_release(struct tablet_seat *s) {
    if (s->seat || s->tablet) return;
    for (int i = 0; i < TOOLS; i++)
        if (s->tools[i]) return;
    free(s);
}

static void seat_res_destroy(struct wl_resource *r) {
    struct tablet_seat *s = wl_resource_get_user_data(r);
    wl_list_remove(wl_resource_get_link(r));
    s->seat = NULL;
    seat_release(s);
}

static void tablet_res_destroy(struct wl_resource *r) {
    struct tablet_seat *s = wl_resource_get_user_data(r);
    s->tablet = NULL;
    forget_dead_focus(wl_resource_get_client(r));
    seat_release(s);
}

static void tool_res_destroy(struct wl_resource *r) {
    struct tablet_seat *s = wl_resource_get_user_data(r);
    wl_list_remove(wl_resource_get_link(r));
    for (int i = 0; i < TOOLS; i++)
        if (s->tools[i] == r) s->tools[i] = NULL;
    forget_dead_focus(wl_resource_get_client(r));
    seat_release(s);
}

static void tablet_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct zwp_tablet_v2_interface tablet_impl = { .destroy = tablet_destroy };

static void tool_set_cursor(struct wl_client *c, struct wl_resource *r, uint32_t serial,
                            struct wl_resource *surface, int32_t hx, int32_t hy) {
    struct tablet_seat *s = wl_resource_get_user_data(r);
    for (int i = 0; i < TOOLS; i++) {
        struct tool *t = &g_tools[i];
        if (s->tools[i] == r && t->focus && t->client == c && serial == t->serial && entered(t, r))
            droiddeck_cursor_from_tool(surface);
    }
}
static void tool_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct zwp_tablet_tool_v2_interface tool_impl = {
    .set_cursor = tool_set_cursor, .destroy = tool_destroy,
};

static void seat_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct zwp_tablet_seat_v2_interface seat_impl = { .destroy = seat_destroy };

static void send_tablet(struct tablet_seat *s) {
    struct wl_client *c = wl_resource_get_client(s->seat);
    int version = wl_resource_get_version(s->seat);
    struct wl_resource *r = wl_resource_create(c, &zwp_tablet_v2_interface, version, 0);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &tablet_impl, s, tablet_res_destroy);
    s->tablet = r;
    zwp_tablet_seat_v2_send_tablet_added(s->seat, r);
    zwp_tablet_v2_send_name(r, TABLET_NAME);
    if (version >= ZWP_TABLET_V2_BUSTYPE_SINCE_VERSION) zwp_tablet_v2_send_bustype(r, ZWP_TABLET_V2_BUSTYPE_VIRTUAL);
    zwp_tablet_v2_send_done(r);
}

static void send_tool(struct tablet_seat *s, int i) {
    struct tool *t = &g_tools[i];
    struct wl_client *c = wl_resource_get_client(s->seat);
    struct wl_resource *r = wl_resource_create(c, &zwp_tablet_tool_v2_interface, wl_resource_get_version(s->seat), 0);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &tool_impl, s, tool_res_destroy);
    wl_list_insert(t->resources.prev, wl_resource_get_link(r));
    s->tools[i] = r;
    zwp_tablet_seat_v2_send_tool_added(s->seat, r);
    zwp_tablet_tool_v2_send_type(r, t->type);
    if (t->caps & DROIDDECK_PEN_CAP_TILT) zwp_tablet_tool_v2_send_capability(r, ZWP_TABLET_TOOL_V2_CAPABILITY_TILT);
    zwp_tablet_tool_v2_send_capability(r, ZWP_TABLET_TOOL_V2_CAPABILITY_PRESSURE);
    if (t->caps & DROIDDECK_PEN_CAP_DISTANCE)
        zwp_tablet_tool_v2_send_capability(r, ZWP_TABLET_TOOL_V2_CAPABILITY_DISTANCE);
    zwp_tablet_tool_v2_send_done(r);
}

static void announce(struct tablet_seat *s) {
    if (!g_announced || !s->seat) return;
    if (!(s->sent & SENT_TABLET)) {
        s->sent |= SENT_TABLET;
        send_tablet(s);
    }
    if (!s->tablet) return;
    for (int i = 0; i < TOOLS; i++) {
        if (!g_tools[i].known || (s->sent & SENT_TOOL(i))) continue;
        s->sent |= SENT_TOOL(i);
        send_tool(s, i);
    }
}

static void announce_all(void) {
    struct wl_resource *r;
    wl_resource_for_each(r, &g_seats) announce(wl_resource_get_user_data(r));
}

static void manager_get_tablet_seat(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *seat) {
    struct tablet_seat *s = calloc(1, sizeof(*s));
    if (!s) { wl_client_post_no_memory(c); return; }
    s->seat = wl_resource_create(c, &zwp_tablet_seat_v2_interface, wl_resource_get_version(r), id);
    if (!s->seat) { free(s); wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(s->seat, &seat_impl, s, seat_res_destroy);
    wl_list_insert(g_seats.prev, wl_resource_get_link(s->seat));
    droiddeck_log("pen", "%s takes the pen as a tablet", droiddeck_client_name(c));
    announce(s);
}
static void manager_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct zwp_tablet_manager_v2_interface manager_impl = {
    .get_tablet_seat = manager_get_tablet_seat, .destroy = manager_destroy,
};
static void bind_tablet_manager(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &zwp_tablet_manager_v2_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &manager_impl, NULL, NULL);
}

void droiddeck_tablet_set_capabilities(int caps) {
    g_caps |= caps & (DROIDDECK_PEN_CAP_PRESSURE | DROIDDECK_PEN_CAP_TILT | DROIDDECK_PEN_CAP_DISTANCE);
    if (g_announced || !g_caps) return;
    g_announced = 1;
    droiddeck_log("pen", "the pen tablet \"%s\" is available to programs", TABLET_NAME);
    announce_all();
}

void droiddeck_tablet_ensure_tool(int tool) {
    struct tool *t = tool_for(tool);
    if (!t || t->known || !g_announced) return;
    t->known = 1;
    t->caps = g_caps;
    droiddeck_log("pen", "the pen tablet has a %s now (pressure%s%s)", tool_name(t),
                  t->caps & DROIDDECK_PEN_CAP_TILT ? ", tilt" : "",
                  t->caps & DROIDDECK_PEN_CAP_DISTANCE ? ", distance" : "");
    announce_all();
}

int droiddeck_tablet_client_has_tool(struct wl_client *client, int tool) {
    struct tool *t = tool_for(tool);
    return t && client && has_live_resource(t, client);
}

int droiddeck_tablet_client_bound(struct wl_client *client) {
    return client && wl_resource_find_for_client(&g_seats, client) != NULL;
}

static void tool_leave(struct tool *t, uint32_t time_ms) {
    struct wl_resource *r;
    if (t->focus) {
        wl_resource_for_each(r, &t->resources) {
            if (!entered(t, r)) continue;
            if (t->down) zwp_tablet_tool_v2_send_up(r);
            for (int i = 0; i < BUTTONS; i++)
                if (t->buttons & (1u << i))
                    zwp_tablet_tool_v2_send_button(r, wl_display_next_serial(g_disp), button_codes[i],
                                                   ZWP_TABLET_TOOL_V2_BUTTON_STATE_RELEASED);
            zwp_tablet_tool_v2_send_proximity_out(r);
            zwp_tablet_tool_v2_send_frame(r, time_ms);
        }
    }
    tool_drop_focus(t);
}

static void tool_enter(struct tool *t, struct wl_resource *surface) {
    struct wl_client *c = wl_resource_get_client(surface);
    struct wl_resource *r;
    if (!has_live_resource(t, c)) return;
    t->focus = surface;
    t->client = c;
    t->serial = wl_display_next_serial(g_disp);
    t->fresh = 1;
    g_active = t;
    wl_resource_for_each(r, &t->resources) {
        struct tablet_seat *s = live(r);
        if (wl_resource_get_client(r) != c || !s) continue;
        s->entered |= tool_bit(t);
        zwp_tablet_tool_v2_send_proximity_in(r, t->serial, s->tablet, surface);
    }
}

static void tool_frame(struct tool *t, const struct droiddeck_pen *pen) {
    struct wl_resource *r;
    const wl_fixed_t x = wl_fixed_from_double(pen->x), y = wl_fixed_from_double(pen->y);
    const wl_fixed_t tx = pen->has_tilt ? wl_fixed_from_double(pen->tilt_x) : t->tilt_x;
    const wl_fixed_t ty = pen->has_tilt ? wl_fixed_from_double(pen->tilt_y) : t->tilt_y;
    const int motion = t->fresh || x != t->x || y != t->y;
    const int pressure = t->fresh || pen->pressure != t->pressure;
    const int distance = (t->caps & DROIDDECK_PEN_CAP_DISTANCE) && (t->fresh || pen->distance != t->distance);
    const int tilt = (t->caps & DROIDDECK_PEN_CAP_TILT) && (t->fresh || tx != t->tilt_x || ty != t->tilt_y);
    const int down = pen->down ? 1 : 0;
    const uint32_t buttons = pen->buttons & ((1u << BUTTONS) - 1);
    const int changed = motion || pressure || distance || tilt || down != t->down || buttons != t->buttons;
    wl_resource_for_each(r, &t->resources) {
        if (!changed || !entered(t, r)) continue;
        if (motion) zwp_tablet_tool_v2_send_motion(r, x, y);
        if (pressure) zwp_tablet_tool_v2_send_pressure(r, pen->pressure);
        if (distance) zwp_tablet_tool_v2_send_distance(r, pen->distance);
        if (tilt) zwp_tablet_tool_v2_send_tilt(r, tx, ty);
        if (down && !t->down) zwp_tablet_tool_v2_send_down(r, wl_display_next_serial(g_disp));
        else if (!down && t->down) zwp_tablet_tool_v2_send_up(r);
        for (int i = 0; i < BUTTONS; i++)
            if ((buttons ^ t->buttons) & (1u << i))
                zwp_tablet_tool_v2_send_button(r, wl_display_next_serial(g_disp), button_codes[i],
                                               buttons & (1u << i) ? ZWP_TABLET_TOOL_V2_BUTTON_STATE_PRESSED
                                                                   : ZWP_TABLET_TOOL_V2_BUTTON_STATE_RELEASED);
        zwp_tablet_tool_v2_send_frame(r, pen->time_ms);
    }
    t->x = x; t->y = y;
    t->tilt_x = tx; t->tilt_y = ty;
    t->pressure = pen->pressure;
    t->distance = pen->distance;
    t->down = down;
    t->buttons = buttons;
    t->fresh = 0;
}

void droiddeck_tablet_frame(struct wl_resource *surface, const struct droiddeck_pen *pen) {
    struct tool *t = tool_for(pen->tool);
    if (!t) return;
    if (g_active && g_active != t) tool_leave(g_active, pen->time_ms);
    if (t->focus != surface) {
        tool_leave(t, pen->time_ms);
        tool_enter(t, surface);
    }
    if (t->focus) tool_frame(t, pen);
    wl_display_flush_clients(g_disp);
}

void droiddeck_tablet_out(uint32_t time_ms) {
    if (!g_active) return;
    tool_leave(g_active, time_ms);
    wl_display_flush_clients(g_disp);
}

void droiddeck_tablet_surface_gone(struct wl_resource *surface, uint32_t time_ms) {
    for (int i = 0; i < TOOLS; i++)
        if (g_tools[i].focus == surface) tool_leave(&g_tools[i], time_ms);
}

void tablet_init(struct wl_display *display) {
    g_disp = display;
    wl_list_init(&g_seats);
    for (int i = 0; i < TOOLS; i++) wl_list_init(&g_tools[i].resources);
    g_tools[0].type = ZWP_TABLET_TOOL_V2_TYPE_PEN;
    g_tools[1].type = ZWP_TABLET_TOOL_V2_TYPE_ERASER;
    wl_global_create(display, &zwp_tablet_manager_v2_interface, 2, NULL, bind_tablet_manager);
}
