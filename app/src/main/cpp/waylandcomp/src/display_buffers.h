#ifndef DISPLAY_BUFFERS_H
#define DISPLAY_BUFFERS_H
#include <wayland-server.h>

struct dmabuf_buffer;

void display_buffers_init(struct wl_display *display);
int display_buffers_offer_ubwc(uint32_t drm);
void display_buffers_withdraw_ubwc(void);
void display_buffers_match(struct wl_client *c, struct wl_resource *buffer, struct dmabuf_buffer *b);

#endif
