#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <poll.h>
#include <stddef.h>

#if defined(__aarch64__) || defined(BL_VDEC_ANY_ARCH)

#include <fcntl.h>
#include <linux/videodev2.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include "../../../app/src/main/cpp/venc/venc_proto.h"

#define VDEC_DEVICE "/dev/video-dec0"
#define VDEC_INSTANCES 4
#define VDEC_BUFFERS 16
#define VDEC_BITSTREAM_SIZE (4u << 20)
#define VDEC_PADDING 64
#define VDEC_DUMB 64

struct vd_dma_heap_alloc {
    uint64_t len;
    uint32_t fd;
    uint32_t fd_flags;
    uint64_t heap_flags;
};
#define VD_DMA_HEAP_IOCTL_ALLOC _IOWR('H', 0x0, struct vd_dma_heap_alloc)

struct vd_dma_buf_sync {
    uint64_t flags;
};
#define VD_DMA_BUF_SYNC_WRITE 2u
#define VD_DMA_BUF_SYNC_START 0u
#define VD_DMA_BUF_SYNC_END 4u
#define VD_DMA_BUF_IOCTL_SYNC _IOW('b', 0, struct vd_dma_buf_sync)

struct vd_create_dumb {
    uint32_t height, width, bpp, flags, handle, pitch;
    uint64_t size;
};
struct vd_destroy_dumb {
    uint32_t handle;
};
struct vd_prime_handle {
    uint32_t handle, flags;
    int32_t fd;
};
#define VD_IOCTL_MODE_CREATE_DUMB _IOWR('d', 0xB2, struct vd_create_dumb)
#define VD_IOCTL_MODE_DESTROY_DUMB _IOWR('d', 0xB4, struct vd_destroy_dumb)
#define VD_IOCTL_PRIME_HANDLE_TO_FD _IOWR('d', 0x2d, struct vd_prime_handle)

struct vd_mode_info {
    uint32_t clock;
    uint16_t hdisplay, hsync_start, hsync_end, htotal, hskew;
    uint16_t vdisplay, vsync_start, vsync_end, vtotal, vscan;
    uint32_t vrefresh, flags, type;
    char name[32];
};
struct vd_res {
    int count_fbs;
    uint32_t *fbs;
    int count_crtcs;
    uint32_t *crtcs;
    int count_connectors;
    uint32_t *connectors;
    int count_encoders;
    uint32_t *encoders;
    uint32_t min_width, max_width, min_height, max_height;
};
struct vd_connector {
    uint32_t connector_id, encoder_id, connector_type, connector_type_id;
    int connection;
    uint32_t mm_width, mm_height;
    int subpixel;
    int count_modes;
    struct vd_mode_info *modes;
    int count_props;
    uint32_t *props;
    uint64_t *prop_values;
    int count_encoders;
    uint32_t *encoders;
};
struct vd_encoder {
    uint32_t encoder_id, encoder_type, crtc_id, possible_crtcs, possible_clones;
};
struct vd_crtc {
    uint32_t crtc_id, buffer_id, x, y, width, height;
    int mode_valid;
    struct vd_mode_info mode;
    int gamma_size;
};
struct vd_device {
    char **nodes;
    int available_nodes;
    int bustype;
    void *businfo;
    void *deviceinfo;
};

struct vd_capture {
    int fd;
    ino_t ino;
    uint8_t *map;
    size_t len;
};

struct vd_done {
    uint32_t index;
    struct timeval timestamp;
};

struct vdec {
    int used, fd;
    uint32_t fourcc, width, height;
    uint32_t cap_width, cap_height, bpl, cap_size;
    uint8_t *bitstream;
    uint32_t out_count;
    uint32_t out_done[VDEC_BUFFERS], n_out_done;
    struct vd_capture cap[VDEC_BUFFERS];
    uint32_t cap_count;
    uint32_t cap_queue[VDEC_BUFFERS], n_cap_queue;
    struct vd_done cap_done[VDEC_BUFFERS];
    uint32_t n_cap_done;
    int out_on, cap_on;
    int sock, hw, hw_failed;
    int64_t pts;
    void *ctx, *packet, *frame;
    uint8_t *packet_data;
    size_t packet_capacity;
    int last;
    uint32_t frames, slow;
    double decode_ms;
};

static struct {
    void *(*find_decoder_by_name)(const char *);
    void *(*alloc_context3)(const void *);
    int (*open2)(void *, const void *, void **);
    void (*free_context)(void **);
    int (*send_packet)(void *, const void *);
    int (*receive_frame)(void *, void *);
    void (*flush_buffers)(void *);
    void *(*packet_alloc)(void);
    void (*packet_free)(void **);
    void *(*frame_alloc)(void);
    void (*frame_free)(void **);
    void (*frame_unref)(void *);
    int (*dict_set)(void **, const char *, const char *, int);
    void (*dict_free)(void **);
    const char *(*pix_fmt_name)(int);
} av;

static pthread_mutex_t vd_lock = PTHREAD_MUTEX_INITIALIZER;
static struct vdec vd_all[VDEC_INSTANCES];
static int vd_open_count;
static int vd_state;
static int vd_ffmpeg;
static int vd_heap = -1;
static int vd_dumb_fd[VDEC_DUMB];
static char vd_drm_handle;

static void vd_log(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void vd_log(const char *fmt, ...) {
    va_list ap;
    char line[256];
    va_start(ap, fmt);
    int n = vsnprintf(line, sizeof line - 1, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    if (n > (int)sizeof line - 2) n = sizeof line - 2;
    line[n++] = '\n';
    if (write(2, "droiddeck-vdec: ", 16) < 0 || write(2, line, n) < 0) return;
}

static int vd_load_ffmpeg(void) {
    void *codec = dlopen("libavcodec.so.62", RTLD_LAZY | RTLD_LOCAL);
    void *util = codec ? dlopen("libavutil.so.60", RTLD_LAZY | RTLD_LOCAL) : NULL;
    if (codec == NULL || util == NULL) {
        vd_log("FFmpeg 8 is not available (%s), Remote Play decoding stays off", dlerror());
        return 0;
    }
#define VD_SYM(lib, field, name)                                 \
    if ((*(void **)&av.field = dlsym(lib, name)) == NULL) {      \
        vd_log("FFmpeg has no %s, Remote Play decoding stays off", name); \
        return 0;                                                \
    }
    VD_SYM(codec, find_decoder_by_name, "avcodec_find_decoder_by_name")
    VD_SYM(codec, alloc_context3, "avcodec_alloc_context3")
    VD_SYM(codec, open2, "avcodec_open2")
    VD_SYM(codec, free_context, "avcodec_free_context")
    VD_SYM(codec, send_packet, "avcodec_send_packet")
    VD_SYM(codec, receive_frame, "avcodec_receive_frame")
    VD_SYM(codec, flush_buffers, "avcodec_flush_buffers")
    VD_SYM(codec, packet_alloc, "av_packet_alloc")
    VD_SYM(codec, packet_free, "av_packet_free")
    VD_SYM(util, frame_alloc, "av_frame_alloc")
    VD_SYM(util, frame_free, "av_frame_free")
    VD_SYM(util, frame_unref, "av_frame_unref")
    VD_SYM(util, dict_set, "av_dict_set")
    VD_SYM(util, dict_free, "av_dict_free")
    VD_SYM(util, pix_fmt_name, "av_get_pix_fmt_name")
#undef VD_SYM
    return 1;
}

static int vd_have_ffmpeg(void) {
    if (vd_ffmpeg == 0) vd_ffmpeg = vd_load_ffmpeg() ? 1 : -1;
    return vd_ffmpeg > 0;
}

__attribute__((visibility("hidden"))) int bl_venc_connect(void);

static int vd_enabled(void) {
    pthread_mutex_lock(&vd_lock);
    if (vd_state == 0) {
        const char *flag = getenv("BL_REMOTE_PLAY_DECODE"), *socket = getenv("BL_VENC_SOCKET");
        vd_state = -1;
        if (strcmp(program_invocation_short_name, "streaming_client") == 0 && (flag == NULL || strcmp(flag, "0") != 0)) {
            vd_heap = open("/dev/dma_heap/system", O_RDONLY | O_CLOEXEC);
            if (vd_heap < 0) vd_log("no /dev/dma_heap/system (%s), Remote Play decoding stays off", strerror(errno));
            else if ((socket && *socket) || vd_have_ffmpeg()) vd_state = 1;
            for (int i = 0; i < VDEC_DUMB; i++) vd_dumb_fd[i] = -1;
        }
    }
    int on = vd_state > 0;
    pthread_mutex_unlock(&vd_lock);
    return on;
}

static struct vdec *vd_find(int fd) {
    if (fd < 0 || __atomic_load_n(&vd_open_count, __ATOMIC_ACQUIRE) == 0) return NULL;
    for (int i = 0; i < VDEC_INSTANCES; i++) if (vd_all[i].used && vd_all[i].fd == fd) return &vd_all[i];
    return NULL;
}

__attribute__((visibility("hidden"))) int bl_vdec_open(const char *path, int flags) {
    (void)flags;
    if (path == NULL || strcmp(path, VDEC_DEVICE) != 0 || !vd_enabled()) return -2;
    int fd = memfd_create("droiddeck-vdec", 0);
    if (fd < 0) return -1;
    pthread_mutex_lock(&vd_lock);
    struct vdec *v = NULL;
    for (int i = 0; i < VDEC_INSTANCES && v == NULL; i++) if (!vd_all[i].used) v = &vd_all[i];
    if (v == NULL) {
        pthread_mutex_unlock(&vd_lock);
        close(fd);
        errno = EBUSY;
        return -1;
    }
    memset(v, 0, sizeof *v);
    v->used = 1;
    v->fd = fd;
    v->fourcc = V4L2_PIX_FMT_H264;
    v->last = -1;
    v->sock = -1;
    for (int i = 0; i < VDEC_BUFFERS; i++) v->cap[i].fd = -1;
    __atomic_add_fetch(&vd_open_count, 1, __ATOMIC_RELEASE);
    pthread_mutex_unlock(&vd_lock);
    return fd;
}

static void vd_close_decoder(struct vdec *v) {
    if (v->frames) {
        vd_log("%u frames, %.1f ms each on average, %u over 25 ms", v->frames, v->decode_ms / v->frames, v->slow);
    }
    if (v->sock >= 0) close(v->sock);
    v->sock = -1;
    v->hw = 0;
    if (v->ctx) av.free_context(&v->ctx);
    if (v->packet) av.packet_free(&v->packet);
    if (v->frame) av.frame_free(&v->frame);
    free(v->packet_data);
    v->packet_data = NULL;
    v->packet_capacity = 0;
    v->frames = v->slow = 0;
    v->decode_ms = 0;
    v->last = -1;
}

static int vd_send(int sock, const void *msg, size_t len, int fd) {
    struct iovec iov = { .iov_base = (void *)msg, .iov_len = len };
    char control[CMSG_SPACE(sizeof(int))];
    struct msghdr m = { .msg_iov = &iov, .msg_iovlen = 1 };
    if (fd >= 0) {
        memset(control, 0, sizeof control);
        m.msg_control = control;
        m.msg_controllen = sizeof control;
        struct cmsghdr *c = CMSG_FIRSTHDR(&m);
        c->cmsg_level = SOL_SOCKET;
        c->cmsg_type = SCM_RIGHTS;
        c->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(c), &fd, sizeof fd);
    }
    return sendmsg(sock, &m, MSG_NOSIGNAL) == (ssize_t)len ? 0 : -1;
}

static void vd_send_capture(struct vdec *v, uint32_t index) {
    struct vdec_capture c = { .type = VDEC_CAPTURE, .index = index, .size = (uint32_t)v->cap[index].len };
    if (v->sock >= 0 && v->cap[index].map && vd_send(v->sock, &c, sizeof c, v->cap[index].fd) != 0)
        vd_log("could not hand capture buffer %u to the app: %s", index, strerror(errno));
}

static int vd_open_hardware(struct vdec *v) {
    if (v->hw_failed) return -1;
    int sock = bl_venc_connect();
    if (sock < 0) return -1;
    struct vdec_config c = {
        .type = VDEC_CONFIG, .codec = v->fourcc == V4L2_PIX_FMT_HEVC ? VENC_HEVC : VENC_H264,
        .width = v->width ? v->width : v->cap_width, .height = v->height ? v->height : v->cap_height,
        .stride = v->bpl, .rows = v->cap_height, .bitstreams = v->out_count, .bitstream_size = VDEC_BITSTREAM_SIZE,
    };
    struct vdec_result r;
    if (vd_send(sock, &c, sizeof c, v->fd) != 0 || recv(sock, &r, sizeof r, 0) != (ssize_t)sizeof r ||
        r.type != VDEC_RESULT || r.status != 0) {
        vd_log("the app has no %s decoder for %ux%u", c.codec == VENC_HEVC ? "HEVC" : "H.264", c.width, c.height);
        close(sock);
        v->hw_failed = 1;
        return -1;
    }
    v->sock = sock;
    v->hw = 1;
    for (uint32_t i = 0; i < v->cap_count; i++) vd_send_capture(v, i);
    vd_log("decoding %s %ux%u with the app's MediaCodec into %ux%u NV12 dma-bufs",
           c.codec == VENC_HEVC ? "HEVC" : "H.264", c.width, c.height, v->cap_width, v->cap_height);
    return 0;
}

static int vd_open_decoder(struct vdec *v) {
    if (vd_open_hardware(v) == 0) return 0;
    if (!vd_have_ffmpeg()) return -1;
    const char *name = v->fourcc == V4L2_PIX_FMT_HEVC ? "hevc" : "h264";
    const void *codec = av.find_decoder_by_name(name);
    if (codec == NULL) {
        vd_log("FFmpeg has no %s decoder", name);
        return -1;
    }
    v->ctx = av.alloc_context3(codec);
    v->packet = av.packet_alloc();
    v->frame = av.frame_alloc();
    void *options = NULL;
    long cores = sysconf(_SC_NPROCESSORS_ONLN);
    char threads[8];
    snprintf(threads, sizeof threads, "%ld", cores > 4 ? 4L : cores < 1 ? 1L : cores);
    av.dict_set(&options, "threads", threads, 0);
    av.dict_set(&options, "thread_type", "slice", 0);
    av.dict_set(&options, "flags", "+low_delay", 0);
    av.dict_set(&options, "flags2", "+fast", 0);
    int r = v->ctx && v->packet && v->frame ? av.open2(v->ctx, codec, &options) : -1;
    av.dict_free(&options);
    if (r < 0) {
        vd_log("could not open the %s decoder (%d)", name, r);
        vd_close_decoder(v);
        return -1;
    }
    vd_log("decoding %s %ux%u in software into %ux%u NV12 dma-bufs", v->fourcc == V4L2_PIX_FMT_HEVC ? "HEVC" : "H.264",
           v->width, v->height, v->cap_width, v->cap_height);
    return 0;
}

static void vd_unmap_capture(struct vdec *v) {
    for (uint32_t i = 0; i < VDEC_BUFFERS; i++) {
        if (v->cap[i].map) munmap(v->cap[i].map, v->cap[i].len);
        v->cap[i] = (struct vd_capture){ .fd = -1 };
    }
    v->cap_count = v->n_cap_queue = v->n_cap_done = 0;
    v->last = -1;
}

static void vd_sync(int fd, uint64_t flags) {
    struct vd_dma_buf_sync s = { .flags = flags | VD_DMA_BUF_SYNC_WRITE };
    ioctl(fd, VD_DMA_BUF_IOCTL_SYNC, &s);
}

static void vd_blank(struct vdec *v, struct vd_capture *c) {
    size_t luma = (size_t)v->bpl * v->cap_height;
    if (luma + luma / 2 > c->len) return;
    vd_sync(c->fd, VD_DMA_BUF_SYNC_START);
    memset(c->map, 16, luma);
    memset(c->map + luma, 128, luma / 2);
    vd_sync(c->fd, VD_DMA_BUF_SYNC_END);
}

static int vd_map_capture(struct vdec *v, uint32_t index, int fd) {
    struct stat st;
    if (fd < 0 || fstat(fd, &st) != 0) return -EINVAL;
    struct vd_capture *c = &v->cap[index];
    if (c->map && c->ino == st.st_ino) {
        c->fd = fd;
        return 0;
    }
    if (c->map) {
        munmap(c->map, c->len);
        *c = (struct vd_capture){ .fd = -1 };
        if (v->last == (int)index) v->last = -1;
    }
    off_t len = lseek(fd, 0, SEEK_END);
    if (len < (off_t)v->cap_size) return -EINVAL;
    void *p = mmap(NULL, (size_t)len, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (p == MAP_FAILED) {
        vd_log("could not map capture buffer %u: %s", index, strerror(errno));
        *c = (struct vd_capture){ .fd = -1 };
        return -EINVAL;
    }
    *c = (struct vd_capture){ .fd = fd, .ino = st.st_ino, .map = p, .len = (size_t)len };
    vd_blank(v, c);
    if (v->hw) vd_send_capture(v, index);
    return 0;
}

static int vd_write_frame(struct vdec *v, struct vd_capture *c) {
    uint8_t **data = (uint8_t **)v->frame;
    int *linesize = (int *)((uint8_t *)v->frame + 64);
    int width = *(int *)((uint8_t *)v->frame + 104), height = *(int *)((uint8_t *)v->frame + 108);
    const char *format = av.pix_fmt_name(*(int *)((uint8_t *)v->frame + 116));
    if (format == NULL || width <= 0 || height <= 0) return -1;
    int nv12 = strcmp(format, "nv12") == 0;
    if (!nv12 && strcmp(format, "yuv420p") != 0 && strcmp(format, "yuvj420p") != 0) {
        static int warned;
        if (!warned++) vd_log("cannot show %s frames", format);
        return -1;
    }
    uint32_t w = (uint32_t)width < v->bpl ? (uint32_t)width : v->bpl;
    uint32_t h = (uint32_t)height < v->cap_height ? (uint32_t)height : v->cap_height;
    uint8_t *y = c->map, *uv = c->map + (size_t)v->bpl * v->cap_height;
    vd_sync(c->fd, VD_DMA_BUF_SYNC_START);
    for (uint32_t row = 0; row < h; row++) memcpy(y + (size_t)row * v->bpl, data[0] + (size_t)row * linesize[0], w);
    for (uint32_t row = 0; row < (h + 1) / 2; row++) {
        uint8_t *dst = uv + (size_t)row * v->bpl;
        if (nv12) {
            memcpy(dst, data[1] + (size_t)row * linesize[1], w & ~1u);
            continue;
        }
        const uint8_t *u = data[1] + (size_t)row * linesize[1], *vv = data[2] + (size_t)row * linesize[2];
        for (uint32_t x = 0; x < w / 2; x++) {
            dst[2 * x] = u[x];
            dst[2 * x + 1] = vv[x];
        }
    }
    vd_sync(c->fd, VD_DMA_BUF_SYNC_END);
    return 0;
}

static void vd_copy_last(struct vdec *v, struct vd_capture *c) {
    if (v->last < 0 || v->cap[v->last].map == NULL || &v->cap[v->last] == c) return;
    size_t size = v->cap_size < c->len ? v->cap_size : c->len;
    vd_sync(c->fd, VD_DMA_BUF_SYNC_START);
    memcpy(c->map, v->cap[v->last].map, size);
    vd_sync(c->fd, VD_DMA_BUF_SYNC_END);
}

static double vd_now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

static void vd_deliver(struct vdec *v, int written, double start, struct timeval timestamp) {
    uint32_t target = v->cap_queue[0];
    if (!written) {
        if (v->last < 0) return;
        vd_copy_last(v, &v->cap[target]);
    }
    double ms = vd_now_ms() - start;
    v->frames++;
    v->decode_ms += ms;
    if (ms > 25.0) v->slow++;
    memmove(v->cap_queue, v->cap_queue + 1, --v->n_cap_queue * sizeof v->cap_queue[0]);
    v->cap_done[v->n_cap_done++] = (struct vd_done){ .index = target, .timestamp = timestamp };
    v->last = (int)target;
}

static void vd_decode_hardware(struct vdec *v, uint32_t index, uint32_t bytes, struct timeval timestamp) {
    double start = vd_now_ms();
    v->pts += 16667;
    struct vdec_packet p = { .type = VDEC_PACKET, .input = index, .bytes = bytes, .capture = v->cap_queue[0], .pts_us = v->pts };
    struct vdec_result r;
    if (vd_send(v->sock, &p, sizeof p, -1) != 0 || recv(v->sock, &r, sizeof r, 0) != (ssize_t)sizeof r ||
        r.type != VDEC_RESULT) {
        vd_log("lost the app's decoder (%s), switching to software", strerror(errno));
        vd_close_decoder(v);
        v->hw_failed = 1;
        return;
    }
    vd_deliver(v, r.status == 0 && r.frame, start, timestamp);
}

static void vd_decode(struct vdec *v, uint32_t index, uint32_t bytes, struct timeval timestamp) {
    if (!v->cap_on || v->n_cap_queue == 0) return;
    if (v->ctx == NULL && !v->hw && vd_open_decoder(v) != 0) return;
    if (v->hw) {
        vd_decode_hardware(v, index, bytes, timestamp);
        return;
    }
    if (v->packet_capacity < bytes + VDEC_PADDING) {
        free(v->packet_data);
        v->packet_capacity = bytes + VDEC_PADDING + (64u << 10);
        v->packet_data = malloc(v->packet_capacity);
        if (v->packet_data == NULL) {
            v->packet_capacity = 0;
            return;
        }
    }
    memcpy(v->packet_data, v->bitstream + (size_t)index * VDEC_BITSTREAM_SIZE, bytes);
    memset(v->packet_data + bytes, 0, VDEC_PADDING);
    *(uint8_t **)((uint8_t *)v->packet + 24) = v->packet_data;
    *(int *)((uint8_t *)v->packet + 32) = (int)bytes;
    double start = vd_now_ms();
    uint32_t target = v->cap_queue[0];
    struct vd_capture *c = &v->cap[target];
    int written = 0;
    if (av.send_packet(v->ctx, v->packet) >= 0) {
        while (av.receive_frame(v->ctx, v->frame) >= 0) {
            if (vd_write_frame(v, c) == 0) written = 1;
            av.frame_unref(v->frame);
        }
    }
    *(uint8_t **)((uint8_t *)v->packet + 24) = NULL;
    *(int *)((uint8_t *)v->packet + 32) = 0;
    vd_deliver(v, written, start, timestamp);
}

static int is_output(uint32_t type) { return type == V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE || type == V4L2_BUF_TYPE_VIDEO_OUTPUT; }
static int is_capture(uint32_t type) { return type == V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE || type == V4L2_BUF_TYPE_VIDEO_CAPTURE; }

static void vd_fill_format(struct vdec *v, struct v4l2_format *f) {
    struct v4l2_pix_format_mplane *p = &f->fmt.pix_mp;
    memset(p, 0, sizeof *p);
    p->field = V4L2_FIELD_NONE;
    p->num_planes = 1;
    p->colorspace = V4L2_COLORSPACE_REC709;
    if (is_output(f->type)) {
        p->width = v->width;
        p->height = v->height;
        p->pixelformat = v->fourcc;
        p->plane_fmt[0].sizeimage = VDEC_BITSTREAM_SIZE;
    } else {
        p->width = v->cap_width;
        p->height = v->cap_height;
        p->pixelformat = V4L2_PIX_FMT_NV12;
        p->plane_fmt[0].bytesperline = v->bpl;
        p->plane_fmt[0].sizeimage = v->cap_size;
    }
}

static int vd_set_format(struct vdec *v, struct v4l2_format *f, int apply) {
    struct v4l2_pix_format_mplane *p = &f->fmt.pix_mp;
    if (f->type != V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE && f->type != V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE) return -EINVAL;
    struct vdec t = *v;
    if (is_output(f->type)) {
        if (p->pixelformat != V4L2_PIX_FMT_H264 && p->pixelformat != V4L2_PIX_FMT_HEVC) return -EINVAL;
        t.fourcc = p->pixelformat;
        t.width = p->width;
        t.height = p->height;
    } else {
        uint32_t w = p->width > t.width ? p->width : t.width, h = p->height > t.height ? p->height : t.height;
        if (w < 16 || h < 16 || w > 8192 || h > 8192) return -EINVAL;
        t.cap_width = (w + 15) & ~15u;
        t.cap_height = (h + 15) & ~15u;
        t.bpl = t.cap_width;
        t.cap_size = t.bpl * t.cap_height * 3 / 2;
    }
    if (apply) {
        if (t.fourcc != v->fourcc || (t.hw && (t.bpl != v->bpl || t.cap_height != v->cap_height))) vd_close_decoder(&t);
        *v = t;
    }
    vd_fill_format(&t, f);
    return 0;
}

static int vd_reqbufs(struct vdec *v, struct v4l2_requestbuffers *r) {
    uint32_t n = r->count > VDEC_BUFFERS ? VDEC_BUFFERS : r->count;
    if (r->type == V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE && r->memory == V4L2_MEMORY_MMAP) {
        if (v->bitstream) munmap(v->bitstream, (size_t)v->out_count * VDEC_BITSTREAM_SIZE);
        v->bitstream = NULL;
        v->out_count = v->n_out_done = 0;
        if (n && ftruncate(v->fd, (off_t)n * VDEC_BITSTREAM_SIZE) != 0) return -ENOMEM;
        if (n) {
            void *p = mmap(NULL, (size_t)n * VDEC_BITSTREAM_SIZE, PROT_READ, MAP_SHARED, v->fd, 0);
            if (p == MAP_FAILED) return -ENOMEM;
            v->bitstream = p;
        }
        v->out_count = n;
    } else if (r->type == V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE && r->memory == V4L2_MEMORY_DMABUF) {
        if (v->hw) vd_close_decoder(v);
        vd_unmap_capture(v);
        v->cap_count = n;
    } else {
        return -EINVAL;
    }
    r->count = n;
    r->capabilities = r->memory == V4L2_MEMORY_MMAP ? V4L2_BUF_CAP_SUPPORTS_MMAP : V4L2_BUF_CAP_SUPPORTS_DMABUF;
    return 0;
}

static int vd_querybuf(struct vdec *v, struct v4l2_buffer *b) {
    if (b->type != V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE || b->index >= v->out_count || b->m.planes == NULL || b->length < 1)
        return -EINVAL;
    b->memory = V4L2_MEMORY_MMAP;
    b->flags = V4L2_BUF_FLAG_MAPPED;
    b->length = 1;
    b->m.planes[0].length = VDEC_BITSTREAM_SIZE;
    b->m.planes[0].m.mem_offset = b->index * VDEC_BITSTREAM_SIZE;
    return 0;
}

static int vd_qbuf(struct vdec *v, struct v4l2_buffer *b) {
    if (b->m.planes == NULL || b->length < 1) return -EINVAL;
    if (b->type == V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE) {
        if (b->index >= v->out_count || v->n_out_done >= VDEC_BUFFERS) return -EINVAL;
        uint32_t bytes = b->m.planes[0].bytesused;
        if (bytes > VDEC_BITSTREAM_SIZE) bytes = VDEC_BITSTREAM_SIZE;
        if (v->out_on && bytes) vd_decode(v, b->index, bytes, b->timestamp);
        v->out_done[v->n_out_done++] = b->index;
    } else if (b->type == V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE) {
        if (b->index >= v->cap_count || v->cap_size == 0) return -EINVAL;
        int r = vd_map_capture(v, b->index, b->m.planes[0].m.fd);
        if (r < 0) return r;
        for (uint32_t i = 0; i < v->n_cap_queue; i++) if (v->cap_queue[i] == b->index) return 0;
        if (v->n_cap_queue >= VDEC_BUFFERS) return -EINVAL;
        v->cap_queue[v->n_cap_queue++] = b->index;
    } else {
        return -EINVAL;
    }
    b->flags |= V4L2_BUF_FLAG_QUEUED;
    return 0;
}

static int vd_dqbuf(struct vdec *v, struct v4l2_buffer *b) {
    if (b->m.planes == NULL || b->length < 1) return -EINVAL;
    if (b->type == V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE) {
        if (v->n_out_done == 0) return -EAGAIN;
        b->index = v->out_done[0];
        memmove(v->out_done, v->out_done + 1, --v->n_out_done * sizeof v->out_done[0]);
        b->memory = V4L2_MEMORY_MMAP;
        b->flags = V4L2_BUF_FLAG_DONE | V4L2_BUF_FLAG_MAPPED;
        b->length = 1;
        b->m.planes[0].bytesused = 0;
        b->m.planes[0].length = VDEC_BITSTREAM_SIZE;
        b->m.planes[0].m.mem_offset = b->index * VDEC_BITSTREAM_SIZE;
        return 0;
    }
    if (b->type != V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE) return -EINVAL;
    if (v->n_cap_done == 0) return -EAGAIN;
    struct vd_done d = v->cap_done[0];
    memmove(v->cap_done, v->cap_done + 1, --v->n_cap_done * sizeof d);
    b->index = d.index;
    b->memory = V4L2_MEMORY_DMABUF;
    b->flags = V4L2_BUF_FLAG_DONE | V4L2_BUF_FLAG_TIMESTAMP_COPY;
    b->field = V4L2_FIELD_NONE;
    b->timestamp = d.timestamp;
    b->length = 1;
    b->m.planes[0].bytesused = v->cap_size;
    b->m.planes[0].length = v->cap_size;
    b->m.planes[0].m.fd = v->cap[d.index].fd;
    return 0;
}

static int vd_ioctl(struct vdec *v, unsigned long req, void *arg) {
    switch (req) {
    case VIDIOC_QUERYCAP: {
        struct v4l2_capability *c = arg;
        memset(c, 0, sizeof *c);
        strcpy((char *)c->driver, "droiddeck_vdec");
        strcpy((char *)c->card, "DroidDeck video decoder");
        strcpy((char *)c->bus_info, "platform:droiddeck-vdec");
        c->version = 0x00060000;
        c->device_caps = V4L2_CAP_VIDEO_M2M_MPLANE | V4L2_CAP_STREAMING;
        c->capabilities = c->device_caps | V4L2_CAP_DEVICE_CAPS;
        return 0;
    }
    case VIDIOC_ENUM_FMT: {
        struct v4l2_fmtdesc *d = arg;
        static const uint32_t coded[] = { V4L2_PIX_FMT_H264, V4L2_PIX_FMT_HEVC };
        if (is_capture(d->type) && d->index == 0) {
            d->pixelformat = V4L2_PIX_FMT_NV12;
            strcpy((char *)d->description, "Y/CbCr 4:2:0");
            d->flags = 0;
        } else if (is_output(d->type) && d->index < 2) {
            d->pixelformat = coded[d->index];
            strcpy((char *)d->description, d->index ? "HEVC" : "H.264");
            d->flags = V4L2_FMT_FLAG_COMPRESSED;
        } else {
            return -EINVAL;
        }
        return 0;
    }
    case VIDIOC_G_FMT: {
        struct v4l2_format *f = arg;
        if (!is_output(f->type) && !is_capture(f->type)) return -EINVAL;
        vd_fill_format(v, f);
        return 0;
    }
    case VIDIOC_S_FMT: return vd_set_format(v, arg, 1);
    case VIDIOC_TRY_FMT: return vd_set_format(v, arg, 0);
    case VIDIOC_REQBUFS: return vd_reqbufs(v, arg);
    case VIDIOC_QUERYBUF: return vd_querybuf(v, arg);
    case VIDIOC_QBUF: return vd_qbuf(v, arg);
    case VIDIOC_DQBUF: return vd_dqbuf(v, arg);
    case VIDIOC_STREAMON:
    case VIDIOC_STREAMOFF: {
        uint32_t type = *(uint32_t *)arg;
        int on = req == VIDIOC_STREAMON;
        if (is_output(type)) {
            v->out_on = on;
            if (!on) {
                v->n_out_done = 0;
                if (v->ctx) av.flush_buffers(v->ctx);
            }
        } else if (is_capture(type)) {
            v->cap_on = on;
            if (!on) v->n_cap_queue = v->n_cap_done = 0;
        } else {
            return -EINVAL;
        }
        if (v->out_on && v->cap_on && v->ctx == NULL && !v->hw && v->out_count && v->cap_size) vd_open_decoder(v);
        return 0;
    }
    case VIDIOC_SUBSCRIBE_EVENT:
    case VIDIOC_UNSUBSCRIBE_EVENT:
    case VIDIOC_DECODER_CMD:
    case VIDIOC_TRY_DECODER_CMD:
        return 0;
    case VIDIOC_DQEVENT:
        return -ENOENT;
    default:
        vd_log("unhandled ioctl 0x%lx", req);
        return -ENOTTY;
    }
}

__attribute__((visibility("hidden"))) int bl_vdec_ioctl(int fd, unsigned long req, void *arg, int *rc) {
    struct vdec *v = vd_find(fd);
    if (v == NULL) return 0;
    pthread_mutex_lock(&vd_lock);
    int r = arg ? vd_ioctl(v, req, arg) : -EFAULT;
    pthread_mutex_unlock(&vd_lock);
    if (r < 0) {
        errno = -r;
        *rc = -1;
    } else {
        *rc = 0;
    }
    return 1;
}

__attribute__((visibility("hidden"))) void bl_vdec_closed(int fd) {
    struct vdec *v = vd_find(fd);
    if (v == NULL) return;
    pthread_mutex_lock(&vd_lock);
    vd_close_decoder(v);
    vd_unmap_capture(v);
    if (v->bitstream) munmap(v->bitstream, (size_t)v->out_count * VDEC_BITSTREAM_SIZE);
    v->bitstream = NULL;
    v->used = 0;
    __atomic_sub_fetch(&vd_open_count, 1, __ATOMIC_RELEASE);
    pthread_mutex_unlock(&vd_lock);
}

__attribute__((visibility("hidden"))) int bl_vdec_poll(struct pollfd *fds, nfds_t n, int timeout_ms,
                                                       int (*real)(struct pollfd *, nfds_t, int), int *ret) {
    if (n != 1 || vd_find(fds[0].fd) == NULL) return 0;
    struct vdec *v = vd_find(fds[0].fd);
    pthread_mutex_lock(&vd_lock);
    short ready = (v->n_cap_done ? POLLIN | POLLRDNORM : 0) | (v->n_out_done ? POLLOUT | POLLWRNORM : 0);
    pthread_mutex_unlock(&vd_lock);
    fds[0].revents = ready & fds[0].events;
    if (fds[0].revents == 0 && timeout_ms != 0) real(NULL, 0, timeout_ms < 0 || timeout_ms > 1000 ? 1000 : timeout_ms);
    *ret = fds[0].revents ? 1 : 0;
    return 1;
}

static int drm_get_devices2(uint32_t flags, struct vd_device **devices, int max) {
    (void)flags;
    static char *nodes[3] = { "/dev/null", NULL, NULL };
    static struct vd_device device = { .nodes = nodes, .available_nodes = 1 };
    if (devices == NULL || max < 1) return 1;
    devices[0] = &device;
    return 1;
}

static void drm_free_devices(struct vd_device **devices, int count) { (void)devices; (void)count; }

static struct vd_mode_info drm_mode = {
    .clock = 148500, .hdisplay = 1920, .hsync_start = 2008, .hsync_end = 2052, .htotal = 2200,
    .vdisplay = 1080, .vsync_start = 1084, .vsync_end = 1089, .vtotal = 1125, .vrefresh = 60, .name = "1920x1080",
};
static uint32_t drm_crtc_ids[1] = { 1 }, drm_connector_ids[1] = { 2 }, drm_encoder_ids[1] = { 3 };

static struct vd_res *drm_get_resources(int fd) {
    (void)fd;
    static struct vd_res res = {
        .count_crtcs = 1, .crtcs = drm_crtc_ids, .count_connectors = 1, .connectors = drm_connector_ids,
        .count_encoders = 1, .encoders = drm_encoder_ids, .max_width = 8192, .max_height = 8192,
    };
    return &res;
}

static struct vd_connector *drm_get_connector(int fd, uint32_t id) {
    (void)fd;
    static struct vd_connector connector = {
        .connector_id = 2, .encoder_id = 3, .connector_type = 14, .connection = 1, .count_modes = 1,
        .modes = &drm_mode, .count_encoders = 1, .encoders = drm_encoder_ids,
    };
    return id == 2 ? &connector : NULL;
}

static struct vd_encoder *drm_get_encoder(int fd, uint32_t id) {
    (void)fd;
    static struct vd_encoder encoder = { .encoder_id = 3, .encoder_type = 8, .crtc_id = 1, .possible_crtcs = 1 };
    return id == 3 ? &encoder : NULL;
}

static struct vd_crtc *drm_get_crtc(int fd, uint32_t id) {
    (void)fd;
    static struct vd_crtc crtc;
    if (id != 1) return NULL;
    crtc = (struct vd_crtc){ .crtc_id = 1, .width = 1920, .height = 1080, .mode_valid = 1, .mode = drm_mode };
    return &crtc;
}

static void drm_free(void *p) { (void)p; }
static void *drm_null(void) { return NULL; }
static int drm_fail(void) { return -EINVAL; }

static int drm_dumb_slot(uint32_t handle) { return handle >= 1 && handle <= VDEC_DUMB ? (int)handle - 1 : -1; }

static int drm_ioctl(int fd, unsigned long request, void *arg) {
    (void)fd;
    int r = -EINVAL;
    pthread_mutex_lock(&vd_lock);
    if (request == VD_IOCTL_MODE_CREATE_DUMB && arg) {
        struct vd_create_dumb *c = arg;
        uint64_t pitch = ((uint64_t)c->width * c->bpp + 7) / 8;
        uint64_t size = (pitch * c->height + 4095) & ~4095ull;
        int slot = -1;
        for (int i = 0; i < VDEC_DUMB && slot < 0; i++) if (vd_dumb_fd[i] < 0) slot = i;
        struct vd_dma_heap_alloc a = { .len = size, .fd_flags = O_RDWR | O_CLOEXEC };
        if (slot < 0 || size == 0 || size > (512ull << 20)) {
            r = -ENOMEM;
        } else if (ioctl(vd_heap, VD_DMA_HEAP_IOCTL_ALLOC, &a) != 0) {
            r = -errno;
            vd_log("dma-heap allocation of %llu bytes failed: %s", (unsigned long long)size, strerror(errno));
        } else {
            vd_dumb_fd[slot] = (int)a.fd;
            c->handle = (uint32_t)slot + 1;
            c->pitch = (uint32_t)pitch;
            c->size = size;
            r = 0;
        }
    } else if (request == VD_IOCTL_PRIME_HANDLE_TO_FD && arg) {
        struct vd_prime_handle *h = arg;
        int slot = drm_dumb_slot(h->handle);
        int dup_fd = slot >= 0 && vd_dumb_fd[slot] >= 0 ? fcntl(vd_dumb_fd[slot], F_DUPFD_CLOEXEC, 0) : -1;
        if (dup_fd >= 0) {
            h->fd = dup_fd;
            r = 0;
        } else {
            r = slot >= 0 ? -errno : -ENOENT;
        }
    } else if (request == VD_IOCTL_MODE_DESTROY_DUMB && arg) {
        int slot = drm_dumb_slot(((struct vd_destroy_dumb *)arg)->handle);
        if (slot >= 0 && vd_dumb_fd[slot] >= 0) {
            close(vd_dumb_fd[slot]);
            vd_dumb_fd[slot] = -1;
            r = 0;
        } else {
            r = -ENOENT;
        }
    }
    pthread_mutex_unlock(&vd_lock);
    if (r < 0) {
        errno = -r;
        return -1;
    }
    return 0;
}

static const struct {
    const char *name;
    void *fn;
} drm_table[] = {
    { "drmGetDevices2", (void *)drm_get_devices2 },
    { "drmFreeDevices", (void *)drm_free_devices },
    { "drmSetMaster", (void *)drm_fail },
    { "drmSetClientCap", (void *)drm_fail },
    { "drmModeGetResources", (void *)drm_get_resources },
    { "drmModeFreeResources", (void *)drm_free },
    { "drmModeGetConnector", (void *)drm_get_connector },
    { "drmModeFreeConnector", (void *)drm_free },
    { "drmModeGetEncoder", (void *)drm_get_encoder },
    { "drmModeFreeEncoder", (void *)drm_free },
    { "drmModeGetCrtc", (void *)drm_get_crtc },
    { "drmModeSetCrtc", (void *)drm_fail },
    { "drmModeFreeCrtc", (void *)drm_free },
    { "drmModeGetPlaneResources", (void *)drm_null },
    { "drmModeFreePlaneResources", (void *)drm_free },
    { "drmModeGetPlane", (void *)drm_null },
    { "drmModeFreePlane", (void *)drm_free },
    { "drmModeObjectGetProperties", (void *)drm_null },
    { "drmModeFreeObjectProperties", (void *)drm_free },
    { "drmModeGetProperty", (void *)drm_null },
    { "drmModeFreeProperty", (void *)drm_free },
    { "drmModeAddFB2", (void *)drm_fail },
    { "drmModeAddFB2WithModifiers", (void *)drm_fail },
    { "drmModeRmFB", (void *)drm_fail },
    { "drmModeAtomicAlloc", (void *)drm_null },
    { "drmModeAtomicFree", (void *)drm_free },
    { "drmModeAtomicAddProperty", (void *)drm_fail },
    { "drmModeAtomicCommit", (void *)drm_fail },
    { "drmIoctl", (void *)drm_ioctl },
};

void *SDL_LoadObject(const char *name) {
    static void *(*real)(const char *);
    if (name && strcmp(name, "libdrm.so.2") == 0 && vd_enabled()) return &vd_drm_handle;
    if (!real) real = (void *(*)(const char *))dlsym(RTLD_NEXT, "SDL_LoadObject");
    return real ? real(name) : NULL;
}

void *SDL_LoadFunction(void *handle, const char *name) {
    static void *(*real)(void *, const char *);
    if (handle == &vd_drm_handle) {
        for (size_t i = 0; name && i < sizeof drm_table / sizeof drm_table[0]; i++)
            if (strcmp(drm_table[i].name, name) == 0) return drm_table[i].fn;
        return NULL;
    }
    if (!real) real = (void *(*)(void *, const char *))dlsym(RTLD_NEXT, "SDL_LoadFunction");
    return real ? real(handle, name) : NULL;
}

void SDL_UnloadObject(void *handle) {
    static void (*real)(void *);
    if (handle == &vd_drm_handle) return;
    if (!real) real = (void (*)(void *))dlsym(RTLD_NEXT, "SDL_UnloadObject");
    if (real) real(handle);
}

#else

__attribute__((visibility("hidden"))) int bl_vdec_open(const char *path, int flags) {
    (void)path;
    (void)flags;
    return -2;
}

__attribute__((visibility("hidden"))) int bl_vdec_ioctl(int fd, unsigned long req, void *arg, int *rc) {
    (void)fd;
    (void)req;
    (void)arg;
    (void)rc;
    return 0;
}

__attribute__((visibility("hidden"))) void bl_vdec_closed(int fd) { (void)fd; }

__attribute__((visibility("hidden"))) int bl_vdec_poll(struct pollfd *fds, nfds_t n, int timeout_ms,
                                                       int (*real)(struct pollfd *, nfds_t, int), int *ret) {
    (void)fds;
    (void)n;
    (void)timeout_ms;
    (void)real;
    (void)ret;
    return 0;
}

#endif
