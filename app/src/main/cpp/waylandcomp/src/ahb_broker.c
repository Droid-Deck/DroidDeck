#define _GNU_SOURCE
#include "ahb_broker.h"
#include "ahb_layout.h"
#include "ahb_broker_protocol.h"
#include "ahb_swapchain.h"
#include "compositor_internal.h"
#include <android/hardware_buffer.h>
#include <android/rect.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/un.h>
#include <unistd.h>

struct broker_handle { int version, num_fds, num_ints, data[]; };
struct allocation {
    int socket;
    AHardwareBuffer *buffer;
    struct stat identity;
    struct droiddeck_ahb_reply layout;
    uint64_t release_serial;
    unsigned display_users;
    int unconfirmed_release;
    int release_events;
    struct wl_event_source *event;
    struct wl_list link;
};
static struct wl_list g_allocations;
static struct wl_event_loop *g_broker_loop;
static const struct broker_handle *(*g_native_handle)(const AHardwareBuffer *);
static int (*g_lock_planes)(AHardwareBuffer *, uint64_t, int32_t, const ARect *, AHardwareBuffer_Planes *);
static unsigned g_allocation_count;
static int g_broker_fd = -1;
static struct wl_listener g_display_destroy;
static int g_layout_reported;

static uint32_t ahb_format(uint32_t drm) {
    switch (drm) {
    case DRM_ABGR8888: case DRM_XBGR8888:
        return AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    case DRM_ARGB8888: case DRM_XRGB8888:
        return 5; /* HAL_PIXEL_FORMAT_BGRA_8888 is not in the public NDK AHB enum. */
    case FOURCC('A', 'B', '3', '0'): case FOURCC('X', 'B', '3', '0'):
        return AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM;
    default:
        return 0;
    }
}

static int plane_fd(const struct broker_handle *handle, const AHardwareBuffer_Planes *planes,
                    uint64_t minimum_size, struct stat *identity, uint32_t *plane_offset) {
    if (!handle || handle->num_fds < 1 || handle->num_fds > 16 || planes->planeCount != 1) return -1;
    FILE *maps = fopen("/proc/self/maps", "r");
    if (!maps) return -1;
    const uintptr_t pointer = (uintptr_t)planes->planes[0].data;
    char line[1024], permissions[5];
    unsigned long start, end;
    unsigned long long offset, inode;
    unsigned device_major, device_minor;
    int result = -1;
    while (fgets(line, sizeof(line), maps)) {
        if (sscanf(line, "%lx-%lx %4s %llx %x:%x %llu", &start, &end, permissions,
                   &offset, &device_major, &device_minor, &inode) != 7 ||
            permissions[0] != 'r' || permissions[3] != 's' ||
            !ahb_plane_offset(pointer, start, end, offset, minimum_size, plane_offset)) continue;
        for (int i = 0; i < handle->num_fds; ++i) {
            struct stat st;
            if (fstat(handle->data[i], &st) == 0 && st.st_size > 0 &&
                ahb_plane_fits(st.st_size, *plane_offset, minimum_size) &&
                st.st_ino == inode && major(st.st_dev) == device_major && minor(st.st_dev) == device_minor) {
                *identity = st;
                result = handle->data[i];
                break;
            }
        }
        break;
    }
    fclose(maps);
    return result;
}

static void allocation_destroy(struct allocation *allocation) {
    wl_list_remove(&allocation->link);
    wl_event_source_remove(allocation->event);
    close(allocation->socket);
    if (allocation->buffer) AHardwareBuffer_release(allocation->buffer);
    free(allocation);
    --g_allocation_count;
}

static int allocation_read(int fd, uint32_t mask, void *data) {
    struct allocation *allocation = data;
    if (mask & (WL_EVENT_HANGUP | WL_EVENT_ERROR)) { allocation_destroy(allocation); return 0; }
    struct droiddeck_ahb_request request;
    ssize_t length = recv(fd, &request, sizeof(request), MSG_TRUNC);
    if (length < 0 && errno == EAGAIN) return 0;
    if (length != sizeof(request) || allocation->buffer ||
        (request.magic != DROIDDECK_AHB_MAGIC && request.magic != DROIDDECK_AHB_OUTPUT_MAGIC) ||
        request.width < 1 || request.height < 1 || request.width > 8192 || request.height > 8192 ||
        !ahb_format(request.format)) {
        allocation_destroy(allocation);
        return 0;
    }
    allocation->release_events = request.magic == DROIDDECK_AHB_MAGIC;
    AHardwareBuffer_Desc desc = {
        .width = request.width, .height = request.height, .layers = 1,
        .format = ahb_format(request.format),
        .usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                 AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY | AHARDWAREBUFFER_USAGE_CPU_READ_RARELY,
    };
    struct droiddeck_ahb_reply reply = {.status = -1};
    int buffer_fd = -1;
    if (AHardwareBuffer_allocate(&desc, &allocation->buffer) == 0 && allocation->buffer) {
        AHardwareBuffer_Desc got;
        AHardwareBuffer_describe(allocation->buffer, &got);
        AHardwareBuffer_Planes planes = {0};
        uint32_t plane_offset = 0;
        const struct broker_handle *handle = g_native_handle(allocation->buffer);
        if (handle &&
            g_lock_planes(allocation->buffer, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY,
                                        -1, NULL, &planes) == 0) {
            int valid = got.width == desc.width && got.height == desc.height && got.format == desc.format &&
                got.layers == 1 && got.stride >= got.width && got.stride <= UINT32_MAX / 4 &&
                planes.planeCount == 1 && planes.planes[0].pixelStride == 4 &&
                planes.planes[0].rowStride == got.stride * 4;
            if (valid) buffer_fd = plane_fd(handle, &planes, (uint64_t)got.stride * 4 * got.height,
                                            &allocation->identity, &plane_offset);
            AHardwareBuffer_unlock(allocation->buffer, NULL);
            if (buffer_fd >= 0) {
                reply = (struct droiddeck_ahb_reply){.status = 0, .stride = got.stride * 4,
                    .offset = plane_offset,
                    .reserved = allocation->release_events ? DROIDDECK_AHB_RELEASE_EVENTS : 0,
                    .modifier = MOD_LINEAR, .size = allocation->identity.st_size};
            }
            if (!g_layout_reported) {
                g_layout_reported = 1;
                droiddeck_log("window", "AHB broker plane verification: native fds=%d, planes=%u, stride=%u, result=%d",
                    handle->num_fds, planes.planeCount, got.stride * 4, reply.status);
            }
        }
    }
    allocation->layout = reply;
    struct iovec iov = {.iov_base = &reply, .iov_len = sizeof(reply)};
    char control[CMSG_SPACE(sizeof(int))] = {0};
    struct msghdr message = {.msg_iov = &iov, .msg_iovlen = 1};
    if (buffer_fd >= 0) {
        message.msg_control = control;
        message.msg_controllen = sizeof(control);
        struct cmsghdr *header = CMSG_FIRSTHDR(&message);
        header->cmsg_level = SOL_SOCKET; header->cmsg_type = SCM_RIGHTS;
        header->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(header), &buffer_fd, sizeof(buffer_fd));
    }
    if (sendmsg(fd, &message, MSG_NOSIGNAL) != sizeof(reply) || reply.status != 0) {
        allocation_destroy(allocation);
    }
    return 0;
}

static int broker_accept(int fd, uint32_t mask, void *data) {
    for (;;) {
        int socket = accept4(fd, NULL, NULL, SOCK_CLOEXEC | SOCK_NONBLOCK);
        if (socket < 0) break;
        struct ucred credential;
        socklen_t size = sizeof(credential);
        if (getsockopt(socket, SOL_SOCKET, SO_PEERCRED, &credential, &size) != 0 ||
            credential.uid != getuid() || g_allocation_count >= 4096) { close(socket); continue; }
        struct allocation *allocation = calloc(1, sizeof(*allocation));
        if (!allocation) { close(socket); continue; }
        allocation->socket = socket;
        allocation->event = wl_event_loop_add_fd(g_broker_loop, socket, WL_EVENT_READABLE, allocation_read, allocation);
        if (!allocation->event) { close(socket); free(allocation); continue; }
        wl_list_insert(g_allocations.prev, &allocation->link);
        ++g_allocation_count;
    }
    return 0;
}

static void broker_destroy(struct wl_listener *listener, void *data) {
    while (!wl_list_empty(&g_allocations)) {
        struct allocation *allocation = wl_container_of(g_allocations.next, allocation, link);
        allocation_destroy(allocation);
    }
    close(g_broker_fd);
    g_broker_fd = -1;
    g_broker_loop = NULL;
    wl_list_remove(&g_display_destroy.link);
}

void ahb_broker_import(struct dmabuf_buffer *buffer, struct wl_resource *resource) {
    if (!g_broker_loop || !buffer || buffer->n_planes != 1 || buffer->modifier != MOD_LINEAR ||
        !ahb_format(buffer->format)) return;
    struct stat identity;
    if (fstat(buffer->fd[0], &identity) != 0) return;
    struct allocation *allocation;
    wl_list_for_each(allocation, &g_allocations, link) {
        if (!allocation->buffer || allocation->layout.status || identity.st_dev != allocation->identity.st_dev ||
            identity.st_ino != allocation->identity.st_ino) continue;
        AHardwareBuffer_Desc desc;
        AHardwareBuffer_describe(allocation->buffer, &desc);
        if (desc.format == ahb_format(buffer->format) &&
            buffer->width == (int)desc.width && buffer->height == (int)desc.height &&
            buffer->stride[0] == allocation->layout.stride && buffer->offset[0] == allocation->layout.offset) {
            ahb_swapchain_register(buffer, resource, allocation->buffer);
        }
        return;
    }
}

void ahb_broker_presented(AHardwareBuffer *buffer) {
    if (!g_broker_loop) return;
    struct allocation *allocation;
    wl_list_for_each(allocation, &g_allocations, link) {
        if (allocation->buffer != buffer) continue;
        ++allocation->display_users;
        return;
    }
}

void ahb_broker_released(AHardwareBuffer *buffer, int completed) {
    if (!g_broker_loop) return;
    struct allocation *allocation;
    wl_list_for_each(allocation, &g_allocations, link) {
        if (allocation->buffer != buffer) continue;
        if (!allocation->display_users) return;
        if (!completed) allocation->unconfirmed_release = 1;
        if (--allocation->display_users || allocation->unconfirmed_release) return;
        struct droiddeck_ahb_release release = {
            .magic = DROIDDECK_AHB_RELEASE_MAGIC,
            .serial = ++allocation->release_serial,
        };
        if (allocation->release_events)
            send(allocation->socket, &release, sizeof(release), MSG_DONTWAIT | MSG_NOSIGNAL);
        return;
    }
}

void ahb_broker_init(struct wl_display *display) {
    if (!ahb_swapchain_advertised()) return;
    g_native_handle = (const struct broker_handle *(*)(const AHardwareBuffer *))
        dlsym(RTLD_DEFAULT, "AHardwareBuffer_getNativeHandle");
    g_lock_planes = (int (*)(AHardwareBuffer *, uint64_t, int32_t, const ARect *, AHardwareBuffer_Planes *))
        dlsym(RTLD_DEFAULT, "AHardwareBuffer_lockPlanes");
    if (!g_native_handle || !g_lock_planes) return;
    int broker_fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (broker_fd < 0) return;
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    int length = snprintf(address.sun_path + 1, sizeof(address.sun_path) - 1, "%s-%u",
                          DROIDDECK_AHB_SOCKET, (unsigned)getuid());
    if (bind(broker_fd, (struct sockaddr *)&address, offsetof(struct sockaddr_un, sun_path) + 1 + length) != 0 ||
        listen(broker_fd, 64) != 0) { close(broker_fd); return; }
    wl_list_init(&g_allocations);
    g_broker_loop = wl_display_get_event_loop(display);
    if (!wl_event_loop_add_fd(g_broker_loop, broker_fd, WL_EVENT_READABLE, broker_accept, NULL)) {
        close(broker_fd);
        g_broker_loop = NULL;
        return;
    }
    g_broker_fd = broker_fd;
    g_display_destroy.notify = broker_destroy;
    wl_display_add_destroy_listener(display, &g_display_destroy);
    droiddeck_log("window", "AHB allocation broker ready: %s-%u, verified linear RGBA/BGRA/10-bit plane layout",
                   DROIDDECK_AHB_SOCKET, (unsigned)getuid());
}
