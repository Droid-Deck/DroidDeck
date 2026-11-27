#pragma once
#include <wayland-server.h>
struct AHardwareBuffer;
struct dmabuf_buffer;
void ahb_broker_init(struct wl_display *display);
void ahb_broker_import(struct dmabuf_buffer *buffer, struct wl_resource *resource);
void ahb_broker_presented(struct AHardwareBuffer *buffer);
void ahb_broker_released(struct AHardwareBuffer *buffer, int completed);
