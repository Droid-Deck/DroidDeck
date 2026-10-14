#include <stdint.h>
#include <stddef.h>
#include "wayland-util.h"

struct wl_display;
struct wl_proxy;
struct wl_event_queue;

struct wl_display *wl_display_connect(const char *name) { return NULL; }
void wl_display_disconnect(struct wl_display *d) {}
int wl_display_roundtrip(struct wl_display *d) { return -1; }
int wl_display_dispatch(struct wl_display *d) { return -1; }
int wl_display_dispatch_pending(struct wl_display *d) { return -1; }
int wl_display_flush(struct wl_display *d) { return -1; }
int wl_display_get_error(struct wl_display *d) { return 0; }
struct wl_proxy *wl_proxy_marshal_flags(struct wl_proxy *p, uint32_t opcode, const struct wl_interface *i, uint32_t v,
                                        uint32_t flags, ...) { return NULL; }
int wl_proxy_add_listener(struct wl_proxy *p, void (**impl)(void), void *data) { return -1; }
uint32_t wl_proxy_get_version(struct wl_proxy *p) { return 0; }
void wl_proxy_destroy(struct wl_proxy *p) {}
void wl_proxy_set_user_data(struct wl_proxy *p, void *d) {}
void *wl_proxy_get_user_data(struct wl_proxy *p) { return NULL; }

const struct wl_interface wl_registry_interface;
const struct wl_interface wl_compositor_interface;
const struct wl_interface wl_surface_interface;
const struct wl_interface wl_output_interface;
const struct wl_interface wl_seat_interface;
const struct wl_interface wl_callback_interface;
const struct wl_interface wl_region_interface;
const struct wl_interface wl_buffer_interface;
