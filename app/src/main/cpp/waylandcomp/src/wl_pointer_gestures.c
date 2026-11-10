#include <stdlib.h>
#include <math.h>
#include <time.h>
#include "wl_pointer_gestures.h"
#include "pointer-gestures-unstable-v1-server-protocol.h"

struct pointer_gesture {
    struct wl_list link;
    struct wl_resource *resource, *pointer, *active;
    struct wl_listener pointer_destroy;
    int kind;
};
static struct wl_list gestures;
static struct wl_display *gesture_display;

static void end(struct pointer_gesture *gesture, uint32_t time, int cancelled) {
    if (!gesture->active) return;
    uint32_t serial = wl_display_next_serial(gesture_display);
    if (gesture->kind == 2) zwp_pointer_gesture_hold_v1_send_end(gesture->resource, serial, time, cancelled);
    else if (gesture->kind == 1) zwp_pointer_gesture_pinch_v1_send_end(gesture->resource, serial, time, cancelled);
    else zwp_pointer_gesture_swipe_v1_send_end(gesture->resource, serial, time, cancelled);
    gesture->active = NULL;
}

void droiddeck_pointer_gestures_cancel(struct wl_resource *surface, uint32_t time) {
    struct pointer_gesture *gesture;
    wl_list_for_each(gesture, &gestures, link)
        if (!surface || gesture->active == surface) end(gesture, time, 1);
}

static void pointer_destroyed(struct wl_listener *listener, void *data) {
    struct pointer_gesture *gesture = wl_container_of(listener, gesture, pointer_destroy);
    struct timespec time;
    clock_gettime(CLOCK_MONOTONIC, &time);
    end(gesture, (uint32_t)(time.tv_sec * 1000ull + time.tv_nsec / 1000000), 1);
    wl_list_remove(&gesture->pointer_destroy.link);
    wl_list_init(&gesture->pointer_destroy.link);
    gesture->pointer = NULL;
}

static void resource_destroyed(struct wl_resource *resource) {
    struct pointer_gesture *gesture = wl_resource_get_user_data(resource);
    wl_list_remove(&gesture->pointer_destroy.link);
    wl_list_remove(&gesture->link);
    free(gesture);
}

static void destroy(struct wl_client *client, struct wl_resource *resource) {
    wl_resource_destroy(resource);
}
static const struct zwp_pointer_gesture_swipe_v1_interface swipe_impl = { .destroy = destroy };
static const struct zwp_pointer_gesture_pinch_v1_interface pinch_impl = { .destroy = destroy };
static const struct zwp_pointer_gesture_hold_v1_interface hold_impl = { .destroy = destroy };

static void create(struct wl_client *client, struct wl_resource *manager, uint32_t id,
                   struct wl_resource *pointer, int kind) {
    struct pointer_gesture *gesture = calloc(1, sizeof(*gesture));
    if (!gesture) { wl_client_post_no_memory(client); return; }
    const struct wl_interface *interface = kind == 2 ? &zwp_pointer_gesture_hold_v1_interface
        : kind == 1 ? &zwp_pointer_gesture_pinch_v1_interface : &zwp_pointer_gesture_swipe_v1_interface;
    int version = wl_resource_get_version(manager);
    if (version > interface->version) version = interface->version;
    gesture->resource = wl_resource_create(client, interface, version, id);
    if (!gesture->resource) { free(gesture); wl_client_post_no_memory(client); return; }
    gesture->pointer = pointer;
    gesture->kind = kind;
    gesture->pointer_destroy.notify = pointer_destroyed;
    wl_resource_add_destroy_listener(pointer, &gesture->pointer_destroy);
    const void *implementation = kind == 2 ? (const void *)&hold_impl
        : kind == 1 ? (const void *)&pinch_impl : (const void *)&swipe_impl;
    wl_resource_set_implementation(gesture->resource, implementation, gesture, resource_destroyed);
    wl_list_insert(gestures.prev, &gesture->link);
}
static void get_swipe(struct wl_client *client, struct wl_resource *manager, uint32_t id,
                      struct wl_resource *pointer) { create(client, manager, id, pointer, 0); }
static void get_pinch(struct wl_client *client, struct wl_resource *manager, uint32_t id,
                      struct wl_resource *pointer) { create(client, manager, id, pointer, 1); }
static void get_hold(struct wl_client *client, struct wl_resource *manager, uint32_t id,
                     struct wl_resource *pointer) { create(client, manager, id, pointer, 2); }
static const struct zwp_pointer_gestures_v1_interface manager_impl = {
    .get_swipe_gesture = get_swipe, .get_pinch_gesture = get_pinch, .release = destroy,
    .get_hold_gesture = get_hold,
};
static void bind_manager(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    struct wl_resource *resource = wl_resource_create(client, &zwp_pointer_gestures_v1_interface, version, id);
    if (!resource) { wl_client_post_no_memory(client); return; }
    wl_resource_set_implementation(resource, &manager_impl, NULL, NULL);
}

void droiddeck_pointer_gestures_init(struct wl_display *display) {
    gesture_display = display;
    wl_list_init(&gestures);
    wl_global_create(display, &zwp_pointer_gestures_v1_interface, 3, NULL, bind_manager);
}

void droiddeck_pointer_pinch(struct wl_resource *surface, uint32_t time, int type,
                            float a, float b, float c, float d) {
    struct pointer_gesture *gesture;
    if (type == 2) droiddeck_pointer_gestures_cancel(NULL, time);
    if (type == 3 && (!isfinite(a) || !isfinite(b) || !isfinite(c) || !isfinite(d) ||
        fabsf(a) >= 8388608 || fabsf(b) >= 8388608 || c <= 0 || c >= 8388608 || fabsf(d) >= 8388608)) return;
    wl_list_for_each(gesture, &gestures, link) {
        if (gesture->kind != 1 || !gesture->pointer) continue;
        if (type == 2 && droiddeck_pointer_has_focus(gesture->pointer, surface)) {
            gesture->active = surface;
            zwp_pointer_gesture_pinch_v1_send_begin(gesture->resource,
                wl_display_next_serial(gesture_display), time, surface, (uint32_t)a);
        } else if (gesture->active == surface) {
            if (type == 3)
                zwp_pointer_gesture_pinch_v1_send_update(gesture->resource, time,
                    wl_fixed_from_double(a), wl_fixed_from_double(b), wl_fixed_from_double(c), wl_fixed_from_double(d));
            else if (type == 4) end(gesture, time, a != 0);
        }
    }
}

void droiddeck_pointer_swipe(struct wl_resource *surface, uint32_t time, int type, float a, float b) {
    if (type == 5) {
        if (a != 3 && a != 4) return;
        droiddeck_pointer_gestures_cancel(NULL, time);
    }
    if (type == 6 && (!isfinite(a) || !isfinite(b) || fabsf(a) >= 8388608 || fabsf(b) >= 8388608)) return;
    struct pointer_gesture *gesture;
    wl_list_for_each(gesture, &gestures, link) {
        if (gesture->kind != 0 || !gesture->pointer) continue;
        if (type == 5 && droiddeck_pointer_has_focus(gesture->pointer, surface)) {
            gesture->active = surface;
            zwp_pointer_gesture_swipe_v1_send_begin(gesture->resource,
                wl_display_next_serial(gesture_display), time, surface, (uint32_t)a);
        } else if (gesture->active == surface) {
            if (type == 6) zwp_pointer_gesture_swipe_v1_send_update(gesture->resource, time,
                wl_fixed_from_double(a), wl_fixed_from_double(b));
            else if (type == 7) end(gesture, time, a != 0);
        }
    }
}

void droiddeck_pointer_hold(struct wl_resource *surface, uint32_t time, int fingers) {
    if (fingers < 1 || fingers > 5) return;
    droiddeck_pointer_gestures_cancel(NULL, time);
    struct pointer_gesture *gesture;
    wl_list_for_each(gesture, &gestures, link) {
        if (gesture->kind != 2 || !gesture->pointer ||
            !droiddeck_pointer_has_focus(gesture->pointer, surface)) continue;
        gesture->active = surface;
        zwp_pointer_gesture_hold_v1_send_begin(gesture->resource,
            wl_display_next_serial(gesture_display), time, surface, fingers);
        end(gesture, time, 1);
    }
}
