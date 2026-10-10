/*
 * wp_single_pixel_buffer_manager_v1 (version 1): a wl_buffer of one pixel of one colour, which a
 * client stretches over a surface with wp_viewporter. KWin's Wayland backend - the desktop's KWin
 * nested in this compositor - refuses to start without it. The surface takes the pixel as a 1x1
 * image, the way it takes a wl_shm buffer (compositor.c, take_pixel).
 */
#include <stdlib.h>
#include "droiddeck_ext.h"
#include "single-pixel-buffer-v1-server-protocol.h"

struct single_pixel {
    uint8_t bgra[4]; /* premultiplied, as wl_shm's ARGB8888 lies in memory */
};

static void buffer_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct wl_buffer_interface buffer_impl = { .destroy = buffer_destroy };

static void buffer_free(struct wl_resource *r) { free(wl_resource_get_user_data(r)); }

int single_pixel_buffer_get(struct wl_resource *buffer, uint8_t bgra[4]) {
    if (!buffer || !wl_resource_instance_of(buffer, &wl_buffer_interface, &buffer_impl)) return 0;
    struct single_pixel *p = wl_resource_get_user_data(buffer);
    for (int i = 0; i < 4; i++) bgra[i] = p->bgra[i];
    return 1;
}

static void mgr_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void mgr_create_buffer(struct wl_client *c, struct wl_resource *r, uint32_t id,
                              uint32_t red, uint32_t green, uint32_t blue, uint32_t alpha) {
    struct single_pixel *p = calloc(1, sizeof(*p));
    struct wl_resource *buffer = p ? wl_resource_create(c, &wl_buffer_interface, 1, id) : NULL;
    if (!buffer) { free(p); wl_client_post_no_memory(c); return; }
    /* The protocol's values span the whole u32 range; the top byte is the 8-bit channel. */
    p->bgra[0] = blue >> 24;
    p->bgra[1] = green >> 24;
    p->bgra[2] = red >> 24;
    p->bgra[3] = alpha >> 24;
    wl_resource_set_implementation(buffer, &buffer_impl, p, buffer_free);
}
static const struct wp_single_pixel_buffer_manager_v1_interface mgr_impl = {
    .destroy = mgr_destroy, .create_u32_rgba_buffer = mgr_create_buffer,
};
static void bind_manager(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wp_single_pixel_buffer_manager_v1_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &mgr_impl, NULL, NULL);
}

void single_pixel_init(struct wl_display *display) {
    wl_global_create(display, &wp_single_pixel_buffer_manager_v1_interface, 1, NULL, bind_manager);
}
