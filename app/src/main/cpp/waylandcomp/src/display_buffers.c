#define _GNU_SOURCE
#include "display_buffers.h"
#include "ahb_swapchain.h"
#include "compositor_internal.h"
#include "droiddeck_color.h"
#include "droiddeck_ext.h"
#include "sc_layer.h"
#include <android/hardware_buffer.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#define DISPLAY_BUFFERS_SOCKET "droiddeck-display-buffers"
#define DISPLAY_BUFFERS_MAGIC 0x42444444u
#define DISPLAY_BUFFERS_VERSION 1u
#define DISPLAY_BUFFERS_ALLOC 1u
#define DISPLAY_BUFFERS_FREE 2u
#define DISPLAY_BUFFERS_MAX_SIDE 16384u
#define DISPLAY_BUFFERS_MAX_PER_CLIENT 256
#define DISPLAY_BUFFERS_PROBE_W 1280u
#define DISPLAY_BUFFERS_PROBE_H 720u
#define ALIGN_UP(v, a) (((v) + (a) - 1) / (a) * (a))

extern volatile int g_zero_copy;
volatile int g_display_buffer_driver;

struct display_buffers_request {
    uint32_t magic, version, op, width, height, format, stride, offset;
    uint64_t size, id, modifier;
};

struct display_buffers_reply {
    uint32_t magic, status;
    uint64_t id, size;
};

struct peer {
    int fd;
    pid_t pid;
    int count;
    int logged_w, logged_h;
    uint32_t logged_format;
    struct wl_event_source *src;
};

struct entry {
    uint64_t id;
    dev_t dev;
    ino_t ino;
    AHardwareBuffer *ahb;
    uint32_t format, stride, width, height;
    uint64_t modifier;
    struct peer *peer;
    struct wl_list link;
};

static struct wl_list g_entries;
static struct wl_event_loop *g_loop;
static uint64_t g_next_id = 1;
static int g_refused_logged;
static int g_ubwc_promised;
static int g_ubwc_probed[2];

static uint32_t ahb_format_of(uint32_t drm, uint64_t modifier) {
    switch (drm) {
    case DRM_ABGR8888: case DRM_XBGR8888: return AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    case DRM_ARGB8888: case DRM_XRGB8888: return modifier == MOD_QCOM_COMPRESSED ? AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM : 0;
    case FOURCC('A', 'B', '3', '0'): case FOURCC('X', 'B', '3', '0'): return AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM;
    default: return 0;
    }
}

static uint64_t ubwc_meta_size(uint32_t w, uint32_t h) {
    return ALIGN_UP((uint64_t)ALIGN_UP((w + 15) / 16, 64) * ALIGN_UP((h + 3) / 4, 16), 4096);
}

static uint64_t ubwc_size(uint32_t w, uint32_t h, uint32_t stride) {
    return ubwc_meta_size(w, h) + ALIGN_UP((uint64_t)stride * ALIGN_UP(h, 16), 4096);
}

static void entry_free(struct entry *e) {
    wl_list_remove(&e->link);
    e->peer->count--;
    AHardwareBuffer_release(e->ahb);
    free(e);
}

static AHardwareBuffer *allocate_ubwc(uint32_t w, uint32_t h, uint32_t format, uint32_t stride, int *fd, uint64_t *size) {
    const uint64_t tiers[2] = {SC_LAYER_AHB_USAGE_VENDOR_UBWC, 0};
    for (int i = 0; i < 2; i++) {
        AHardwareBuffer_Desc d = {
            .width = w, .height = h, .layers = 1, .format = format,
            .usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                     AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY | tiers[i]};
        AHardwareBuffer *ahb = NULL;
        if (AHardwareBuffer_allocate(&d, &ahb) != 0 || !ahb) continue;
        AHardwareBuffer_Desc got;
        AHardwareBuffer_describe(ahb, &got);
        const uint64_t need = ubwc_size(w, h, got.stride * 4);
        *fd = !stride || (uint64_t)got.stride * 4 == stride ? sc_layer_ahb_fd(ahb, need) : -1;
        if (*fd >= 0 && (uint64_t)lseek(*fd, 0, SEEK_END) == need) { *size = need; return ahb; }
        *fd = -1;
        AHardwareBuffer_release(ahb);
    }
    return NULL;
}

static uint64_t gralloc_bytes(uint32_t format, uint64_t usage, uint32_t *stride) {
    AHardwareBuffer_Desc d = {
        .width = DISPLAY_BUFFERS_PROBE_W, .height = DISPLAY_BUFFERS_PROBE_H, .layers = 1, .format = format,
        .usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                 AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY | usage};
    AHardwareBuffer *ahb = NULL;
    if (AHardwareBuffer_allocate(&d, &ahb) != 0 || !ahb) return 0;
    AHardwareBuffer_describe(ahb, &d);
    const int fd = sc_layer_ahb_fd(ahb, 1);
    const off_t bytes = fd >= 0 ? lseek(fd, 0, SEEK_END) : 0;
    if (fd >= 0) lseek(fd, 0, SEEK_SET);
    AHardwareBuffer_release(ahb);
    *stride = d.stride;
    return bytes > 0 ? (uint64_t)bytes : 0;
}

static int ubwc_probe(uint32_t format) {
    int *state = &g_ubwc_probed[format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM ? 0 : 1];
    if (*state) return *state > 0;
    uint32_t stride = 0, plain_stride = 0, linear_stride = 0;
    const uint64_t flagged = gralloc_bytes(format, SC_LAYER_AHB_USAGE_VENDOR_UBWC, &stride);
    const uint64_t plain = gralloc_bytes(format, 0, &plain_stride);
    const uint64_t linear = gralloc_bytes(format, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY, &linear_stride);
    const uint64_t need_flagged = stride ? ubwc_size(DISPLAY_BUFFERS_PROBE_W, DISPLAY_BUFFERS_PROBE_H, stride * 4) : 0;
    const uint64_t need_plain = plain_stride ? ubwc_size(DISPLAY_BUFFERS_PROBE_W, DISPLAY_BUFFERS_PROBE_H, plain_stride * 4) : 0;
    const uint64_t need = need_flagged ? need_flagged : need_plain;
    *state = (need_flagged && linear != need_flagged && flagged == need_flagged) ||
             (need_plain && linear != need_plain && plain == need_plain) ? 1 : -1;
    droiddeck_log("layer", "display buffers: gralloc %s buffers %s (%ux%u: the layout takes %llu bytes; gralloc gives %llu "
               "asked for UBWC, %llu unasked, %llu linear)", droiddeck_ahb_format_name(format),
               *state > 0 ? "use the UBWC layout the drivers render to: compressed frames can go on the display layer"
                          : "do not match the UBWC layout the drivers render to: compressed frames are not offered",
               DISPLAY_BUFFERS_PROBE_W, DISPLAY_BUFFERS_PROBE_H, (unsigned long long)need, (unsigned long long)flagged,
               (unsigned long long)plain, (unsigned long long)linear);
    return *state > 0;
}

int display_buffers_offer_ubwc(uint32_t drm) {
    const uint32_t format = ahb_format_of(drm, MOD_QCOM_COMPRESSED);
    if (!g_loop || !g_zero_copy || !g_ubwc || !g_display_buffer_driver || !format || !ubwc_probe(format)) return 0;
    g_ubwc_promised = 1;
    return 1;
}

void display_buffers_withdraw_ubwc(void) { g_ubwc_promised = 0; }

static AHardwareBuffer *allocate(const struct display_buffers_request *q, uint32_t format, int *fd) {
    const uint32_t rows = (uint32_t)((q->size + q->stride - 1) / q->stride);
    const uint32_t dims[2][2] = {{q->width, q->height}, {q->stride / 4, rows}};
    for (int i = 0; i < 2; i++) {
        AHardwareBuffer_Desc d = {
            .width = dims[i][0], .height = dims[i][1], .layers = 1, .format = format,
            .usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                     AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY | AHARDWAREBUFFER_USAGE_CPU_READ_RARELY};
        AHardwareBuffer *ahb = NULL;
        if (i && dims[1][0] == dims[0][0] && dims[1][1] == dims[0][1]) break;
        if (AHardwareBuffer_allocate(&d, &ahb) != 0 || !ahb) continue;
        AHardwareBuffer_Desc got;
        AHardwareBuffer_describe(ahb, &got);
        *fd = (uint64_t)got.stride * 4 == q->stride ? sc_layer_ahb_fd(ahb, q->size) : -1;
        if (*fd >= 0) return ahb;
        AHardwareBuffer_release(ahb);
    }
    return NULL;
}

static int reply(struct peer *p, const struct display_buffers_reply *r, int fd) {
    char control[CMSG_SPACE(sizeof(int))] = {0};
    struct iovec iov = {.iov_base = (void *)r, .iov_len = sizeof(*r)};
    struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1};
    if (fd >= 0) {
        msg.msg_control = control;
        msg.msg_controllen = sizeof(control);
        struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
        c->cmsg_level = SOL_SOCKET;
        c->cmsg_type = SCM_RIGHTS;
        c->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(c), &fd, sizeof(int));
    }
    ssize_t n;
    do { n = sendmsg(p->fd, &msg, MSG_NOSIGNAL); } while (n < 0 && errno == EINTR);
    return n == (ssize_t)sizeof(*r) ? 0 : -1;
}

static void handle_alloc(struct peer *p, const struct display_buffers_request *q) {
    struct display_buffers_reply r = {.magic = DISPLAY_BUFFERS_MAGIC, .status = 1};
    const int compressed = q->modifier == MOD_QCOM_COMPRESSED;
    const uint32_t format = compressed || q->modifier == MOD_LINEAR ? ahb_format_of(q->format, q->modifier) : 0;
    struct entry *e = NULL;
    AHardwareBuffer *ahb = NULL;
    struct stat st;
    uint64_t need = 0;
    int fd = -1;
    const int sized = format && q->width && q->height && q->width <= DISPLAY_BUFFERS_MAX_SIDE &&
                      q->height <= DISPLAY_BUFFERS_MAX_SIDE && q->stride % 4 == 0 && q->stride / 4 >= q->width &&
                      q->stride / 4 <= DISPLAY_BUFFERS_MAX_SIDE;
    const int sane = !sized ? 0
                     : compressed ? q->offset == ubwc_meta_size(q->width, q->height) &&
                                    q->size == ubwc_size(q->width, q->height, q->stride)
                                  : q->offset == 0 && q->size >= (uint64_t)q->stride * q->height &&
                                    q->size <= (uint64_t)q->stride * DISPLAY_BUFFERS_MAX_SIDE;
    const int wanted = compressed ? g_ubwc_promised : g_zero_copy;
    if (sane && wanted && ahb_swapchain_advertised() && p->count < DISPLAY_BUFFERS_MAX_PER_CLIENT)
        ahb = compressed ? allocate_ubwc(q->width, q->height, format, q->stride, &fd, &need) : allocate(q, format, &fd);
    if (ahb && fstat(fd, &st) == 0) e = calloc(1, sizeof(*e));
    if (e) {
        e->id = r.id = g_next_id++;
        e->dev = st.st_dev; e->ino = st.st_ino;
        e->ahb = ahb;
        e->format = format; e->stride = q->stride;
        e->width = q->width; e->height = q->height;
        e->modifier = q->modifier;
        e->peer = p;
        p->count++;
        wl_list_insert(&g_entries, &e->link);
        r.status = 0;
        r.size = (uint64_t)lseek(fd, 0, SEEK_END);
        lseek(fd, 0, SEEK_SET);
        if (p->logged_w != (int)q->width || p->logged_h != (int)q->height || p->logged_format != q->format) {
            AHardwareBuffer_Desc got;
            AHardwareBuffer_describe(ahb, &got);
            p->logged_w = (int)q->width; p->logged_h = (int)q->height; p->logged_format = q->format;
            droiddeck_log("layer", "display buffers: pid %d renders %ux%u %c%c%c%c %s straight into gralloc buffers "
                       "(%s %ux%u, stride %u px, %llu bytes for the %llu it asked for)", (int)p->pid, q->width, q->height,
                       q->format & 0xff, (q->format >> 8) & 0xff, (q->format >> 16) & 0xff, (q->format >> 24) & 0xff,
                       compressed ? "UBWC" : "linear", droiddeck_ahb_format_name(format), got.width, got.height,
                       got.stride, (unsigned long long)r.size, (unsigned long long)q->size);
        }
    } else {
        if (ahb) AHardwareBuffer_release(ahb);
        fd = -1;
        if (sized && wanted && (compressed || !g_refused_logged)) {
            g_refused_logged = 1;
            droiddeck_log(compressed ? "error" : "layer", "display buffers: no gralloc buffer matches pid %d's %ux%u "
                       "%c%c%c%c %s layout (stride %u bytes, pixels at %u, %llu bytes; gralloc's layout has pixels at "
                       "%llu, %llu bytes): %s", (int)p->pid, q->width, q->height,
                       q->format & 0xff, (q->format >> 8) & 0xff, (q->format >> 16) & 0xff, (q->format >> 24) & 0xff,
                       compressed ? "UBWC" : "linear", q->stride, q->offset, (unsigned long long)q->size,
                       (unsigned long long)(compressed ? ubwc_meta_size(q->width, q->height) : 0),
                       (unsigned long long)(compressed ? ubwc_size(q->width, q->height, q->stride) : q->size),
                       compressed ? "its compressed frames cannot be shown" : "its frames keep the copy path");
        }
    }
    if (reply(p, &r, fd) != 0 && e) entry_free(e);
}

static void peer_gone(struct peer *p) {
    struct entry *e, *tmp;
    wl_list_for_each_safe(e, tmp, &g_entries, link)
        if (e->peer == p) entry_free(e);
    wl_event_source_remove(p->src);
    close(p->fd);
    free(p);
}

static int on_peer(int fd, uint32_t mask, void *data) {
    struct peer *p = data;
    for (;;) {
        struct display_buffers_request q;
        ssize_t n = recv(fd, &q, sizeof(q), MSG_DONTWAIT);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return 0;
        if (n != (ssize_t)sizeof(q) || q.magic != DISPLAY_BUFFERS_MAGIC || q.version != DISPLAY_BUFFERS_VERSION) break;
        if (q.op == DISPLAY_BUFFERS_ALLOC) {
            handle_alloc(p, &q);
        } else if (q.op == DISPLAY_BUFFERS_FREE) {
            struct entry *e, *tmp;
            wl_list_for_each_safe(e, tmp, &g_entries, link)
                if (e->peer == p && e->id == q.id) entry_free(e);
        } else {
            break;
        }
    }
    peer_gone(p);
    return 0;
}

static int on_listen(int fd, uint32_t mask, void *data) {
    int c = accept4(fd, NULL, NULL, SOCK_CLOEXEC | SOCK_NONBLOCK);
    if (c < 0) return 0;
    struct peer *p = calloc(1, sizeof(*p));
    if (!p) { close(c); return 0; }
    struct ucred cred = {0};
    socklen_t len = sizeof(cred);
    if (getsockopt(c, SOL_SOCKET, SO_PEERCRED, &cred, &len) == 0) p->pid = cred.pid;
    p->fd = c;
    p->src = wl_event_loop_add_fd(g_loop, c, WL_EVENT_READABLE, on_peer, p);
    if (!p->src) { close(c); free(p); }
    return 0;
}

void display_buffers_match(struct wl_client *c, struct wl_resource *buffer, struct dmabuf_buffer *b) {
    struct stat st;
    struct entry *e;
    if (!g_loop || wl_list_empty(&g_entries) || b->n_planes != 1 || b->offset[0] != 0 || fstat(b->fd[0], &st) != 0) return;
    wl_list_for_each(e, &g_entries, link) {
        if (e->dev != st.st_dev || e->ino != st.st_ino) continue;
        AHardwareBuffer_Desc d;
        AHardwareBuffer_describe(e->ahb, &d);
        const int compressed = e->modifier == MOD_QCOM_COMPRESSED;
        const int laid_out = compressed ? b->modifier == MOD_QCOM_COMPRESSED && (uint32_t)b->width == e->width &&
                                          (uint32_t)b->height == e->height
                                        : (b->modifier == MOD_LINEAR || b->modifier == MOD_INVALID) &&
                                          e->stride == b->stride[0];
        if (laid_out && e->format == ahb_format_of(b->format, e->modifier) && b->width > 0 && b->height > 0 &&
            (uint32_t)b->width <= d.width && (uint32_t)b->height <= d.height)
            ahb_swapchain_adopt(c, buffer, b, e->ahb, e->modifier);
        return;
    }
}

void display_buffers_init(struct wl_display *display) {
    wl_list_init(&g_entries);
    const char *dir = getenv("XDG_RUNTIME_DIR");
    struct sockaddr_un addr = {.sun_family = AF_UNIX};
    if (!ahb_swapchain_advertised() || !dir) return;
    if (snprintf(addr.sun_path, sizeof(addr.sun_path), "%s/" DISPLAY_BUFFERS_SOCKET, dir) >= (int)sizeof(addr.sun_path)) {
        droiddeck_log("layer", "display buffers: %s is too long for a socket path, not offered", dir);
        return;
    }
    int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) return;
    unlink(addr.sun_path);
    struct wl_event_loop *loop = wl_display_get_event_loop(display);
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0 || listen(fd, 16) != 0 ||
        !wl_event_loop_add_fd(loop, fd, WL_EVENT_READABLE, on_listen, NULL)) {
        droiddeck_log("layer", "display buffers: socket %s failed (%s), not offered", addr.sun_path, strerror(errno));
        close(fd);
        return;
    }
    g_loop = loop;
    droiddeck_log("layer", "display buffers: offered on %s (programs whose driver asks render into gralloc buffers while "
               "zero-copy is on)", addr.sun_path);
}
