/* android_wlegl buffer import for the existing libhybris Wayland WSI.
 * Protocol: Collabora/libhybris, vendored with its license in protocols/wayland-android.xml.
 * Native handles are cloned through Android; opaque vendor integers are never interpreted. */
#include "compositor_internal.h"
#include "droiddeck_ext.h"
#include "wayland-android-server-protocol.h"
#include <android/hardware_buffer.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Stable native_handle_t ABI, unavailable in the public NDK. */
struct android_native_handle {
    int version, num_fds, num_ints;
    int data[];
};
struct wire_handle {
    struct android_native_handle *handle;
    int received;
};
enum { MAX_HANDLE_FDS = 64, MAX_HANDLE_INTS = 256, MAX_BUFFER_SIDE = 8192 };
/* VNDK ABI (not exposed by the NDK): frameworks/native/libs/nativewindow/include/
 * vndk/hardware_buffer.h, AHARDWAREBUFFER_CREATE_FROM_HANDLE_METHOD_CLONE = 3.
 * Method 1 is not accepted by AHardwareBuffer_createFromHandle. */
enum { CREATE_FROM_HANDLE_CLONE = 3 };
static int (*import_handle)(const AHardwareBuffer_Desc *, const struct android_native_handle *,
                            int32_t, AHardwareBuffer **);
static const struct android_native_handle *(*native_handle)(const AHardwareBuffer *);

static int valid_description(int32_t width, int32_t height, int32_t stride, int32_t format) {
    return width > 0 && height > 0 && width <= MAX_BUFFER_SIDE && height <= MAX_BUFFER_SIDE &&
        stride >= width && stride <= MAX_BUFFER_SIDE * 4 &&
        (format == 1 || format == 2 || format == 5);
}

static void handle_resource_destroy(struct wl_resource *resource) {
    struct wire_handle *wire = wl_resource_get_user_data(resource);
    if (!wire) return;
    for (int i = 0; i < wire->received; i++) close(wire->handle->data[i]);
    free(wire->handle);
    free(wire);
}

static void handle_add_fd(struct wl_client *client, struct wl_resource *resource, int32_t fd) {
    struct wire_handle *wire = wl_resource_get_user_data(resource);
    if (wire->received >= wire->handle->num_fds) {
        close(fd);
        wl_resource_post_error(resource, ANDROID_WLEGL_HANDLE_ERROR_TOO_MANY_FDS,
                               "more native handle descriptors than declared");
        return;
    }
    wire->handle->data[wire->received++] = fd;
}

static void handle_destroy(struct wl_client *client, struct wl_resource *resource) {
    wl_resource_destroy(resource);
}

static const struct android_wlegl_handle_interface handle_impl = {
    .add_fd = handle_add_fd, .destroy = handle_destroy,
};

static void create_handle(struct wl_client *client, struct wl_resource *resource,
                           uint32_t id, int32_t fds, struct wl_array *ints) {
    if (fds < 0 || fds > MAX_HANDLE_FDS || ints->size % sizeof(int32_t) ||
        ints->size > MAX_HANDLE_INTS * sizeof(int32_t)) {
        wl_resource_post_error(resource, ANDROID_WLEGL_ERROR_BAD_VALUE, "invalid native handle size");
        return;
    }
    struct wire_handle *wire = calloc(1, sizeof(*wire));
    if (!wire) { wl_client_post_no_memory(client); return; }
    wire->handle = calloc(1, sizeof(*wire->handle) + (size_t)fds * sizeof(int32_t) + ints->size);
    if (!wire->handle) { free(wire); wl_client_post_no_memory(client); return; }
    wire->handle->version = sizeof(*wire->handle);
    wire->handle->num_fds = fds;
    wire->handle->num_ints = (int)(ints->size / sizeof(int32_t));
    if (ints->size) memcpy(wire->handle->data + fds, ints->data, ints->size);
    struct wl_resource *handle = wl_resource_create(client, &android_wlegl_handle_interface, 1, id);
    if (!handle) {
        free(wire->handle); free(wire); wl_client_post_no_memory(client); return;
    }
    wl_resource_set_implementation(handle, &handle_impl, wire, handle_resource_destroy);
}

static void buffer_resource_destroy(struct wl_resource *resource) {
    dmabuf_buffer_unref(wl_resource_get_user_data(resource));
}

static struct wl_resource *create_android_buffer(struct wl_client *client, uint32_t id,
                                                  AHardwareBuffer *hardware) {
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(hardware, &desc);
    struct vkp_image *image = vkp_image_from_android_buffer(hardware);
    if (!image) return NULL;
    struct dmabuf_buffer *buffer = calloc(1, sizeof(*buffer));
    if (!buffer) { vkp_image_destroy(image); wl_client_post_no_memory(client); return NULL; }
    buffer->refs = 1;
    buffer->width = (int32_t)desc.width; buffer->height = (int32_t)desc.height;
    buffer->format = desc.format == 5 ? DRM_ARGB8888 : desc.format == 2 ? DRM_XBGR8888 : DRM_ABGR8888;
    buffer->modifier = MOD_INVALID; /* No DMA-BUF layout was inferred from the native handle. */
    buffer->img = image;
    for (int i = 0; i < MAX_PLANES; i++) buffer->fd[i] = -1;
    struct wl_resource *result = wl_resource_create(client, &wl_buffer_interface, 1, id);
    if (!result) { dmabuf_buffer_unref(buffer); wl_client_post_no_memory(client); return NULL; }
    wl_resource_set_implementation(result, &dbuf_buffer_impl, buffer, buffer_resource_destroy);
    struct client_info *info = client_info_of(client);
    if (info) info->dmabuf_buffers++;
    return result;
}

static void create_buffer(struct wl_client *client, struct wl_resource *resource, uint32_t id,
                           int32_t width, int32_t height, int32_t stride, int32_t format,
                           int32_t usage, struct wl_resource *handle_resource) {
    /* Some vendor allocators (Samsung Xclipse) record no stride for GPU-only buffers. The import
     * must pass that 0 back unchanged: gralloc validates it against the handle's own value. */
    if (!valid_description(width, height, stride ? stride : width, format) ||
        !wl_resource_instance_of(handle_resource, &android_wlegl_handle_interface, &handle_impl)) {
        droiddeck_log("display", "android_wlegl rejected: %dx%d stride=%d format=%d usage=0x%x",
                     width, height, stride, format, (uint32_t)usage);
        wl_resource_post_error(resource, ANDROID_WLEGL_ERROR_BAD_VALUE, "invalid Android buffer description");
        return;
    }
    struct wire_handle *wire = wl_resource_get_user_data(handle_resource);
    if (wire->received != wire->handle->num_fds || !wire->received) {
        wl_resource_post_error(resource, ANDROID_WLEGL_ERROR_BAD_HANDLE, "incomplete native handle");
        return;
    }
    AHardwareBuffer_Desc desc = {
        .width = (uint32_t)width, .height = (uint32_t)height, .layers = 1,
        .format = (uint32_t)format, .usage = (uint32_t)usage, .stride = (uint32_t)stride};
    AHardwareBuffer *hardware = NULL;
    int status = import_handle(&desc, wire->handle, CREATE_FROM_HANDLE_CLONE, &hardware);
    if (status != 0 || !hardware) {
        droiddeck_log("display", "android_wlegl import failed: status=%d %dx%d stride=%d format=%d usage=0x%x fds=%d ints=%d",
                     status, width, height, stride, format, (uint32_t)usage,
                     wire->handle->num_fds, wire->handle->num_ints);
        wl_resource_post_error(resource, ANDROID_WLEGL_ERROR_BAD_HANDLE,
                               "Android rejected the native handle (status %d)", status);
        return;
    }
    struct wl_resource *buffer = create_android_buffer(client, id, hardware);
    AHardwareBuffer_release(hardware);
    if (!buffer) wl_resource_post_error(resource, ANDROID_WLEGL_ERROR_BAD_HANDLE, "Vulkan cannot import this buffer");
}

static void get_server_buffer(struct wl_client *client, struct wl_resource *resource, uint32_t id,
                               int32_t width, int32_t height, int32_t format, int32_t usage) {
    if (!format) format = 1;
    if (!valid_description(width, height, width, format)) {
        wl_resource_post_error(resource, ANDROID_WLEGL_ERROR_BAD_VALUE, "invalid buffer allocation request");
        return;
    }
    AHardwareBuffer_Desc desc = {
        .width = (uint32_t)width, .height = (uint32_t)height, .layers = 1,
        .format = (uint32_t)format,
        .usage = (uint32_t)usage | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT};
    AHardwareBuffer *hardware = NULL;
    if (AHardwareBuffer_allocate(&desc, &hardware) != 0 || !hardware) {
        wl_resource_post_error(resource, ANDROID_WLEGL_ERROR_BAD_VALUE, "Android buffer allocation failed");
        return;
    }
    AHardwareBuffer_describe(hardware, &desc);
    const struct android_native_handle *handle = native_handle(hardware);
    if (!handle || handle->num_fds < 1 || handle->num_fds > MAX_HANDLE_FDS ||
        handle->num_ints < 0 || handle->num_ints > MAX_HANDLE_INTS) {
        AHardwareBuffer_release(hardware);
        wl_resource_post_error(resource, ANDROID_WLEGL_ERROR_BAD_HANDLE, "invalid allocated native handle");
        return;
    }
    struct wl_resource *buffer = create_android_buffer(client, 0, hardware);
    if (!buffer) {
        AHardwareBuffer_release(hardware);
        wl_resource_post_error(resource, ANDROID_WLEGL_ERROR_BAD_HANDLE, "Vulkan cannot import allocated buffer");
        return;
    }
    struct wl_resource *reply = wl_resource_create(client, &android_wlegl_server_buffer_handle_interface,
                                                   wl_resource_get_version(resource), id);
    if (!reply) {
        wl_resource_destroy(buffer); AHardwareBuffer_release(hardware);
        wl_client_post_no_memory(client); return;
    }
    struct wl_array ints = {.size = (size_t)handle->num_ints * sizeof(int32_t),
                            .alloc = 0, .data = (void *)(handle->data + handle->num_fds)};
    android_wlegl_server_buffer_handle_send_buffer_ints(reply, &ints);
    for (int i = 0; i < handle->num_fds; i++)
        android_wlegl_server_buffer_handle_send_buffer_fd(reply, handle->data[i]);
    /* No linear_layout event: Android's opaque allocation need not be linear. */
    android_wlegl_server_buffer_handle_send_buffer(reply, buffer, (int32_t)desc.format, (int32_t)desc.stride);
    wl_resource_destroy(reply);
    AHardwareBuffer_release(hardware);
}

static const struct android_wlegl_interface wlegl_impl = {
    .create_handle = create_handle, .create_buffer = create_buffer,
    .get_server_buffer_handle = get_server_buffer,
};

static void bind_android_wlegl(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    struct wl_resource *resource = wl_resource_create(client, &android_wlegl_interface,
                                                      version < 3 ? version : 3, id);
    if (!resource) { wl_client_post_no_memory(client); return; }
    wl_resource_set_implementation(resource, &wlegl_impl, NULL, NULL);
}

void droiddeck_android_wlegl_init(struct wl_display *display) {
    if (!vkp_android_buffer_supported()) return;
    void *library = dlopen("libnativewindow.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) return;
    import_handle = dlsym(library, "AHardwareBuffer_createFromHandle");
    native_handle = dlsym(library, "AHardwareBuffer_getNativeHandle");
    if (!import_handle || !native_handle) { dlclose(library); return; }
    /* Keep the platform entry points valid for the compositor's lifetime. */
    if (wl_global_create(display, &android_wlegl_interface, 3, NULL, bind_android_wlegl))
        droiddeck_log("display", "android_wlegl v3: Android buffer import available");
}
