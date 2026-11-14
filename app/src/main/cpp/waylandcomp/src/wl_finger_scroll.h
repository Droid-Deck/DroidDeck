#pragma once
#include <math.h>
#include <wayland-server.h>

struct droiddeck_finger_scroll {
    double rest_x, rest_y;
    unsigned axes;
};

static inline int droiddeck_finger_delta(struct droiddeck_finger_scroll *state,
                                        double dx, double dy, wl_fixed_t *x, wl_fixed_t *y) {
    if (!isfinite(dx) || !isfinite(dy) || fabs(dx) >= 8388608 || fabs(dy) >= 8388608) return 0;
    state->rest_x += dx;
    state->rest_y += dy;
    *x = (wl_fixed_t)(state->rest_x * 256);
    *y = (wl_fixed_t)(state->rest_y * 256);
    state->rest_x -= *x / 256.0;
    state->rest_y -= *y / 256.0;
    state->axes |= (*x ? 1 : 0) | (*y ? 2 : 0);
    return *x || *y;
}

static inline void droiddeck_finger_send(struct wl_resource *pointer, uint32_t time, wl_fixed_t x, wl_fixed_t y) {
    int version = wl_resource_get_version(pointer);
    if (version >= WL_POINTER_AXIS_SOURCE_SINCE_VERSION)
        wl_pointer_send_axis_source(pointer, WL_POINTER_AXIS_SOURCE_FINGER);
    if (x) wl_pointer_send_axis(pointer, time, WL_POINTER_AXIS_HORIZONTAL_SCROLL, x);
    if (y) wl_pointer_send_axis(pointer, time, WL_POINTER_AXIS_VERTICAL_SCROLL, y);
    if (version >= WL_POINTER_FRAME_SINCE_VERSION) wl_pointer_send_frame(pointer);
}

static inline void droiddeck_finger_stop(struct wl_resource *pointer, uint32_t time, unsigned axes) {
    if (!axes) return;
    int version = wl_resource_get_version(pointer);
    if (version >= WL_POINTER_AXIS_SOURCE_SINCE_VERSION)
        wl_pointer_send_axis_source(pointer, WL_POINTER_AXIS_SOURCE_FINGER);
    if (version >= WL_POINTER_AXIS_STOP_SINCE_VERSION) {
        if (axes & 1) wl_pointer_send_axis_stop(pointer, time, WL_POINTER_AXIS_HORIZONTAL_SCROLL);
        if (axes & 2) wl_pointer_send_axis_stop(pointer, time, WL_POINTER_AXIS_VERTICAL_SCROLL);
    }
    if (version >= WL_POINTER_FRAME_SINCE_VERSION) wl_pointer_send_frame(pointer);
}
