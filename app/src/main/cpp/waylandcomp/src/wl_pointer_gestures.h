#pragma once
#include <wayland-server.h>

int droiddeck_pointer_has_focus(struct wl_resource *pointer, struct wl_resource *surface);
void droiddeck_pointer_gestures_init(struct wl_display *display);
void droiddeck_pointer_gestures_cancel(struct wl_resource *surface, uint32_t time);
void droiddeck_pointer_pinch(struct wl_resource *surface, uint32_t time, int type,
                            float a, float b, float c, float d);
void droiddeck_pointer_swipe(struct wl_resource *surface, uint32_t time, int type, float a, float b);
void droiddeck_pointer_hold(struct wl_resource *surface, uint32_t time, int fingers);
