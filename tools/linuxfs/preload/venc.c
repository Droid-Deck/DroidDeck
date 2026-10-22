/* /dev/video-enc0 for Steam's Remote Play V4L2 encoder stage, backed by the app's MediaCodec encoder. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/videodev2.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include "../../../app/src/main/cpp/venc/venc_proto.h"

#ifndef V4L2_CID_MPEG_VIDEO_PREPEND_SPSPPS_TO_IDR
#define V4L2_CID_MPEG_VIDEO_PREPEND_SPSPPS_TO_IDR (V4L2_CID_MPEG_BASE + 644)
#endif

#define VENC_DEVICE "/dev/video-enc0"
#define VENC_INSTANCES 4
#define VENC_OFFSET_STEP 0x1000000L
#define VENC_CAPTURE_OFFSET (VENC_OFFSET_STEP * 64)

struct vq_buf {
    int memfd;
    uint32_t size;
};

struct vq_item {
    uint32_t index, bytes, flags, sequence;
    struct timeval timestamp;
};

struct vq {
    struct vq_buf bufs[VENC_MAX_BUFFERS];
    uint32_t count;
    struct vq_item queued[VENC_MAX_BUFFERS], done[VENC_MAX_BUFFERS];
    uint32_t nqueued, ndone;
    int streaming;
};

struct venc {
    int used, fd;
    pid_t owner;
    uint32_t width, height, stride, crop_w, crop_h, raw_size;
    uint32_t coded_fourcc, coded_size;
    uint32_t fps_num, fps_den;
    uint32_t bitrate, peak, vbr, gop;
    int32_t h264_profile, h264_level, hevc_profile;
    int force_key, configured, sent_bitrate;
    uint32_t sequence;
    struct vq raw, coded;
};

static pthread_mutex_t vn_lock = PTHREAD_MUTEX_INITIALIZER;
static struct venc vn_all[VENC_INSTANCES];
static int vn_open_count;

static void vn_marker(int streaming) {
    const char *dir = getenv("XDG_RUNTIME_DIR");
    char path[512];
    if (dir == NULL || snprintf(path, sizeof path, "%s/droiddeck-remote-play", dir) >= (int)sizeof path) return;
    if (!streaming) {
        unlink(path);
        return;
    }
    FILE *f = fopen(path, "w");
    if (f == NULL) return;
    fprintf(f, "%d\n", (int)getpid());
    fclose(f);
}

static void vn_log(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void vn_log(const char *fmt, ...) {
    va_list ap;
    char line[256];
    va_start(ap, fmt);
    int n = vsnprintf(line, sizeof line - 1, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    if (n > (int)sizeof line - 2) n = sizeof line - 2;
    line[n++] = '\n';
    if (write(2, "droiddeck-venc: ", 16) < 0 || write(2, line, n) < 0) return;
}

static struct venc *vn_find(int fd) {
    if (fd < 0 || __atomic_load_n(&vn_open_count, __ATOMIC_ACQUIRE) == 0) return NULL;
    for (int i = 0; i < VENC_INSTANCES; i++) if (vn_all[i].used && vn_all[i].fd == fd) return &vn_all[i];
    return NULL;
}

static int is_raw(uint32_t type) { return type == V4L2_BUF_TYPE_VIDEO_OUTPUT || type == V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE; }
static int is_coded(uint32_t type) { return type == V4L2_BUF_TYPE_VIDEO_CAPTURE || type == V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE; }
static int is_mplane(uint32_t type) { return type == V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE || type == V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE; }
static struct vq *vn_queue(struct venc *v, uint32_t type) { return is_raw(type) ? &v->raw : is_coded(type) ? &v->coded : NULL; }

static void vn_sizes(struct venc *v) {
    if (v->stride < v->width) v->stride = v->width;
    v->raw_size = v->stride * v->height * 3 / 2;
    uint32_t coded = v->width * v->height * 3 / 2;
    if (coded < 512 * 1024) coded = 512 * 1024;
    if (v->coded_size < coded) v->coded_size = coded;
}

static void vn_free_queue(struct vq *q) {
    for (uint32_t i = 0; i < q->count; i++) if (q->bufs[i].memfd >= 0) close(q->bufs[i].memfd);
    memset(q, 0, sizeof *q);
}

__attribute__((visibility("hidden"))) int bl_venc_streaming(void) {
    return __atomic_load_n(&vn_open_count, __ATOMIC_ACQUIRE) > 0;
}

__attribute__((visibility("hidden"))) int bl_venc_connect(void);

int bl_venc_connect(void) {
    const char *path = getenv("BL_VENC_SOCKET");
    if (path == NULL || *path == '\0') return -1;
    struct sockaddr_un addr = { .sun_family = AF_UNIX };
    if (strlen(path) >= sizeof addr.sun_path) return -1;
    strcpy(addr.sun_path, path);
    int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    if (connect(fd, (struct sockaddr *)&addr, sizeof addr) != 0) {
        close(fd);
        return -1;
    }
    struct timeval tv = { .tv_sec = 2 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    return fd;
}

__attribute__((visibility("hidden"))) int bl_venc_open(const char *path, int flags) {
    (void)flags;
    if (path == NULL || strcmp(path, VENC_DEVICE) != 0) return -2;
    int fd = bl_venc_connect();
    if (fd < 0) return -2;
    pthread_mutex_lock(&vn_lock);
    struct venc *v = NULL;
    for (int i = 0; i < VENC_INSTANCES && v == NULL; i++) if (!vn_all[i].used) v = &vn_all[i];
    if (v == NULL) {
        pthread_mutex_unlock(&vn_lock);
        close(fd);
        errno = EBUSY;
        return -1;
    }
    memset(v, 0, sizeof *v);
    v->used = 1;
    v->fd = fd;
    v->owner = getpid();
    v->width = v->crop_w = 1280;
    v->height = v->crop_h = 720;
    v->coded_fourcc = V4L2_PIX_FMT_H264;
    v->fps_num = 1;
    v->fps_den = 60;
    v->bitrate = 20000000;
    v->gop = 0xffff;
    vn_sizes(v);
    if (__atomic_add_fetch(&vn_open_count, 1, __ATOMIC_RELEASE) == 1) vn_marker(1);
    pthread_mutex_unlock(&vn_lock);
    vn_log("opened %s as fd %d (MediaCodec through the app)", path, fd);
    return fd;
}

static int vn_send(struct venc *v, const void *msg, size_t len, const int *fds, int nfds) {
    struct iovec iov = { .iov_base = (void *)msg, .iov_len = len };
    char control[CMSG_SPACE(sizeof(int) * VENC_MAX_BUFFERS * 2)];
    struct msghdr m = { .msg_iov = &iov, .msg_iovlen = 1 };
    if (nfds > 0) {
        memset(control, 0, sizeof control);
        m.msg_control = control;
        m.msg_controllen = CMSG_SPACE(sizeof(int) * nfds);
        struct cmsghdr *c = CMSG_FIRSTHDR(&m);
        c->cmsg_level = SOL_SOCKET;
        c->cmsg_type = SCM_RIGHTS;
        c->cmsg_len = CMSG_LEN(sizeof(int) * nfds);
        memcpy(CMSG_DATA(c), fds, sizeof(int) * nfds);
    }
    return sendmsg(v->fd, &m, MSG_NOSIGNAL) == (ssize_t)len ? 0 : -1;
}

static int vn_configure(struct venc *v) {
    struct venc_config c = {
        .type = VENC_CONFIG,
        .codec = v->coded_fourcc == V4L2_PIX_FMT_HEVC ? VENC_HEVC : VENC_H264,
        .width = v->crop_w, .height = v->crop_h, .stride = v->stride, .rows = v->height,
        .fps_num = v->fps_num, .fps_den = v->fps_den,
        .bitrate = v->bitrate, .peak_bitrate = v->peak, .vbr = v->vbr, .gop = v->gop,
        .profile = v->coded_fourcc == V4L2_PIX_FMT_HEVC ? v->hevc_profile : v->h264_profile,
        .level = v->h264_level,
        .inputs = v->raw.count, .outputs = v->coded.count,
        .input_size = v->raw_size, .output_size = v->coded_size,
    };
    int fds[VENC_MAX_BUFFERS * 2], n = 0;
    for (uint32_t i = 0; i < v->raw.count; i++) fds[n++] = v->raw.bufs[i].memfd;
    for (uint32_t i = 0; i < v->coded.count; i++) fds[n++] = v->coded.bufs[i].memfd;
    if (vn_send(v, &c, sizeof c, fds, n) != 0) return -1;
    struct venc_done d;
    if (recv(v->fd, &d, sizeof d, 0) != (ssize_t)sizeof d || d.type != VENC_DONE || d.status != 0) {
        vn_log("the app's encoder refused %ux%u %s", c.width, c.height, c.codec == VENC_HEVC ? "HEVC" : "H.264");
        return -1;
    }
    vn_log("encoding %ux%u at %u/%u fps, %u kbit/s %s, %s", c.width, c.height, c.fps_den, c.fps_num,
           c.bitrate / 1000, c.vbr ? "VBR" : "CBR", c.codec == VENC_HEVC ? "HEVC" : "H.264");
    v->configured = 1;
    v->sent_bitrate = (int)v->bitrate;
    return 0;
}

static void vn_pump(struct venc *v) {
    while (v->raw.streaming && v->coded.streaming && v->raw.nqueued > 0 && v->coded.nqueued > 0) {
        struct vq_item in = v->raw.queued[0], out = v->coded.queued[0];
        memmove(v->raw.queued, v->raw.queued + 1, --v->raw.nqueued * sizeof in);
        memmove(v->coded.queued, v->coded.queued + 1, --v->coded.nqueued * sizeof out);
        int ok = v->configured || vn_configure(v) == 0;
        if (ok && v->sent_bitrate != (int)v->bitrate) {
            struct venc_bitrate b = { .type = VENC_BITRATE, .bitrate = v->bitrate };
            if (vn_send(v, &b, sizeof b, NULL, 0) == 0) v->sent_bitrate = (int)v->bitrate;
        }
        struct venc_frame f = {
            .type = VENC_FRAME, .input = in.index, .output = out.index, .bytes = in.bytes,
            .keyframe = (uint32_t)v->force_key,
            .pts_us = (int64_t)in.timestamp.tv_sec * 1000000 + in.timestamp.tv_usec,
        };
        struct venc_done d = { 0 };
        ok = ok && vn_send(v, &f, sizeof f, NULL, 0) == 0 &&
             recv(v->fd, &d, sizeof d, 0) == (ssize_t)sizeof d && d.type == VENC_DONE && d.status == 0;
        if (!ok) vn_log("frame %u was not encoded (%s)", v->sequence, strerror(errno));
        v->force_key = 0;
        in.flags = V4L2_BUF_FLAG_DONE;
        out.bytes = ok ? d.bytes : 0;
        out.flags = V4L2_BUF_FLAG_DONE | (ok ? (d.keyframe ? V4L2_BUF_FLAG_KEYFRAME : V4L2_BUF_FLAG_PFRAME) : V4L2_BUF_FLAG_ERROR);
        out.timestamp = in.timestamp;
        in.sequence = out.sequence = v->sequence++;
        v->raw.done[v->raw.ndone++] = in;
        v->coded.done[v->coded.ndone++] = out;
    }
}

static void vn_fill_format(struct venc *v, struct v4l2_format *f) {
    if (is_mplane(f->type)) {
        struct v4l2_pix_format_mplane *p = &f->fmt.pix_mp;
        memset(p, 0, sizeof *p);
        p->width = v->width;
        p->height = v->height;
        p->field = V4L2_FIELD_NONE;
        p->num_planes = 1;
        p->colorspace = V4L2_COLORSPACE_REC709;
        if (is_raw(f->type)) {
            p->pixelformat = V4L2_PIX_FMT_NV12;
            p->plane_fmt[0].bytesperline = v->stride;
            p->plane_fmt[0].sizeimage = v->raw_size;
        } else {
            p->pixelformat = v->coded_fourcc;
            p->plane_fmt[0].sizeimage = v->coded_size;
        }
    } else {
        struct v4l2_pix_format *p = &f->fmt.pix;
        memset(p, 0, sizeof *p);
        p->width = v->width;
        p->height = v->height;
        p->field = V4L2_FIELD_NONE;
        p->colorspace = V4L2_COLORSPACE_REC709;
        p->pixelformat = is_raw(f->type) ? V4L2_PIX_FMT_NV12 : v->coded_fourcc;
        p->bytesperline = is_raw(f->type) ? v->stride : 0;
        p->sizeimage = is_raw(f->type) ? v->raw_size : v->coded_size;
    }
}

static int vn_set_format(struct venc *v, struct v4l2_format *f, int apply) {
    uint32_t w, h, fourcc, stride, size;
    if (is_mplane(f->type)) {
        w = f->fmt.pix_mp.width; h = f->fmt.pix_mp.height; fourcc = f->fmt.pix_mp.pixelformat;
        stride = f->fmt.pix_mp.plane_fmt[0].bytesperline; size = f->fmt.pix_mp.plane_fmt[0].sizeimage;
    } else if (is_raw(f->type) || is_coded(f->type)) {
        w = f->fmt.pix.width; h = f->fmt.pix.height; fourcc = f->fmt.pix.pixelformat;
        stride = f->fmt.pix.bytesperline; size = f->fmt.pix.sizeimage;
    } else {
        return -EINVAL;
    }
    struct venc t = *v;
    if (w >= 64 && h >= 64 && w <= 4096 && h <= 4096) {
        t.width = (w + 1) & ~1u;
        t.height = (h + 1) & ~1u;
        t.crop_w = t.width;
        t.crop_h = t.height;
    }
    if (is_raw(f->type)) {
        t.stride = stride;
    } else {
        t.coded_fourcc = fourcc == V4L2_PIX_FMT_HEVC ? V4L2_PIX_FMT_HEVC : V4L2_PIX_FMT_H264;
        t.coded_size = size;
    }
    vn_sizes(&t);
    if (apply) {
        if (t.width != v->width || t.height != v->height || t.coded_fourcc != v->coded_fourcc) v->configured = 0;
        *v = t;
    }
    vn_fill_format(&t, f);
    return 0;
}

static int vn_reqbufs(struct venc *v, struct v4l2_requestbuffers *r) {
    struct vq *q = vn_queue(v, r->type);
    if (q == NULL) return -EINVAL;
    if (r->memory != V4L2_MEMORY_MMAP) return -EINVAL;
    vn_free_queue(q);
    v->configured = 0;
    uint32_t n = r->count > VENC_MAX_BUFFERS ? VENC_MAX_BUFFERS : r->count;
    uint32_t size = is_raw(r->type) ? v->raw_size : v->coded_size;
    for (uint32_t i = 0; i < n; i++) {
        int fd = memfd_create("droiddeck-venc", MFD_CLOEXEC);
        if (fd < 0 || ftruncate(fd, size) != 0) {
            if (fd >= 0) close(fd);
            vn_free_queue(q);
            return -ENOMEM;
        }
        q->bufs[i].memfd = fd;
        q->bufs[i].size = size;
        q->count = i + 1;
    }
    r->count = n;
#ifdef V4L2_BUF_CAP_SUPPORTS_MMAP
    r->capabilities = V4L2_BUF_CAP_SUPPORTS_MMAP;
#endif
    return 0;
}

static long vn_offset(struct v4l2_buffer *b) {
    return (is_raw(b->type) ? 0 : VENC_CAPTURE_OFFSET) + (long)(b->index + 1) * VENC_OFFSET_STEP;
}

static void vn_describe(struct venc *v, struct v4l2_buffer *b, const struct vq_item *item) {
    struct vq *q = vn_queue(v, b->type);
    uint32_t size = q->bufs[b->index].size;
    b->memory = V4L2_MEMORY_MMAP;
    b->field = V4L2_FIELD_NONE;
    b->flags = V4L2_BUF_FLAG_MAPPED | (item ? item->flags : 0);
    if (item) {
        b->timestamp = item->timestamp;
        b->sequence = item->sequence;
    }
    if (is_mplane(b->type)) {
        if (b->m.planes == NULL || b->length < 1) return;
        b->length = 1;
        b->m.planes[0].length = size;
        b->m.planes[0].m.mem_offset = (uint32_t)vn_offset(b);
        b->m.planes[0].data_offset = 0;
        if (item) b->m.planes[0].bytesused = item->bytes;
    } else {
        b->length = size;
        b->m.offset = (uint32_t)vn_offset(b);
        if (item) b->bytesused = item->bytes;
    }
}

static int vn_qbuf(struct venc *v, struct v4l2_buffer *b) {
    struct vq *q = vn_queue(v, b->type);
    if (q == NULL || b->index >= q->count || q->nqueued >= VENC_MAX_BUFFERS) return -EINVAL;
    struct vq_item item = { .index = b->index, .timestamp = b->timestamp };
    if (is_raw(b->type))
        item.bytes = is_mplane(b->type) ? (b->m.planes && b->length ? b->m.planes[0].bytesused : 0) : b->bytesused;
    if (item.bytes == 0 || item.bytes > q->bufs[b->index].size) item.bytes = q->bufs[b->index].size;
    q->queued[q->nqueued++] = item;
    vn_describe(v, b, NULL);
    b->flags |= V4L2_BUF_FLAG_QUEUED;
    vn_pump(v);
    return 0;
}

static int vn_dqbuf(struct venc *v, struct v4l2_buffer *b) {
    struct vq *q = vn_queue(v, b->type);
    if (q == NULL) return -EINVAL;
    if (q->ndone == 0) return -EAGAIN;
    struct vq_item item = q->done[0];
    memmove(q->done, q->done + 1, --q->ndone * sizeof item);
    b->index = item.index;
    vn_describe(v, b, &item);
    return 0;
}

static void vn_set_control(struct venc *v, uint32_t id, int32_t value) {
    switch (id) {
    case V4L2_CID_MPEG_VIDEO_BITRATE: if (value > 0) v->bitrate = (uint32_t)value; break;
    case V4L2_CID_MPEG_VIDEO_BITRATE_PEAK: if (value > 0) v->peak = (uint32_t)value; break;
    case V4L2_CID_MPEG_VIDEO_BITRATE_MODE: v->vbr = value == V4L2_MPEG_VIDEO_BITRATE_MODE_VBR; v->configured = 0; break;
    case V4L2_CID_MPEG_VIDEO_GOP_SIZE: v->gop = (uint32_t)value; v->configured = 0; break;
    case V4L2_CID_MPEG_VIDEO_H264_PROFILE: v->h264_profile = value; v->configured = 0; break;
    case V4L2_CID_MPEG_VIDEO_H264_LEVEL: v->h264_level = value; v->configured = 0; break;
    case V4L2_CID_MPEG_VIDEO_HEVC_PROFILE: v->hevc_profile = value; v->configured = 0; break;
    case V4L2_CID_MPEG_VIDEO_FORCE_KEY_FRAME: v->force_key = 1; break;
    default: break;
    }
}

static int vn_get_control(struct venc *v, uint32_t id, int32_t *value) {
    switch (id) {
    case V4L2_CID_MPEG_VIDEO_BITRATE: *value = (int32_t)v->bitrate; return 0;
    case V4L2_CID_MPEG_VIDEO_BITRATE_PEAK: *value = (int32_t)v->peak; return 0;
    case V4L2_CID_MPEG_VIDEO_GOP_SIZE: *value = (int32_t)v->gop; return 0;
    default: *value = 0; return 0;
    }
}

static int vn_ioctl(struct venc *v, unsigned long req, void *arg) {
    switch (req) {
    case VIDIOC_QUERYCAP: {
        struct v4l2_capability *c = arg;
        memset(c, 0, sizeof *c);
        strcpy((char *)c->driver, "droiddeck_venc");
        strcpy((char *)c->card, "Android MediaCodec");
        strcpy((char *)c->bus_info, "platform:droiddeck-venc");
        c->version = 0x00060000;
        c->device_caps = V4L2_CAP_VIDEO_M2M_MPLANE | V4L2_CAP_VIDEO_M2M | V4L2_CAP_STREAMING;
        c->capabilities = c->device_caps | V4L2_CAP_DEVICE_CAPS;
        return 0;
    }
    case VIDIOC_ENUM_FMT: {
        struct v4l2_fmtdesc *d = arg;
        static const uint32_t coded[] = { V4L2_PIX_FMT_H264, V4L2_PIX_FMT_HEVC };
        if (is_raw(d->type) && d->index == 0) {
            d->pixelformat = V4L2_PIX_FMT_NV12;
            strcpy((char *)d->description, "Y/CbCr 4:2:0");
            d->flags = 0;
        } else if (is_coded(d->type) && d->index < 2) {
            d->pixelformat = coded[d->index];
            strcpy((char *)d->description, d->index ? "HEVC" : "H.264");
            d->flags = V4L2_FMT_FLAG_COMPRESSED;
        } else {
            return -EINVAL;
        }
        return 0;
    }
    case VIDIOC_ENUM_FRAMESIZES: {
        struct v4l2_frmsizeenum *s = arg;
        if (s->index != 0) return -EINVAL;
        s->type = V4L2_FRMSIZE_TYPE_STEPWISE;
        s->stepwise = (struct v4l2_frmsize_stepwise){ 64, 4096, 2, 64, 4096, 2 };
        return 0;
    }
    case VIDIOC_G_FMT: vn_fill_format(v, arg); return 0;
    case VIDIOC_S_FMT: return vn_set_format(v, arg, 1);
    case VIDIOC_TRY_FMT: return vn_set_format(v, arg, 0);
    case VIDIOC_REQBUFS: return vn_reqbufs(v, arg);
    case VIDIOC_QUERYBUF: {
        struct v4l2_buffer *b = arg;
        struct vq *q = vn_queue(v, b->type);
        if (q == NULL || b->index >= q->count) return -EINVAL;
        vn_describe(v, b, NULL);
        return 0;
    }
    case VIDIOC_QBUF: return vn_qbuf(v, arg);
    case VIDIOC_DQBUF: return vn_dqbuf(v, arg);
    case VIDIOC_STREAMON:
    case VIDIOC_STREAMOFF: {
        struct vq *q = vn_queue(v, *(uint32_t *)arg);
        if (q == NULL) return -EINVAL;
        q->streaming = req == VIDIOC_STREAMON;
        if (!q->streaming) q->nqueued = q->ndone = 0;
        vn_pump(v);
        return 0;
    }
    case VIDIOC_S_PARM:
    case VIDIOC_G_PARM: {
        struct v4l2_streamparm *p = arg;
        if (!is_raw(p->type)) return req == VIDIOC_G_PARM ? 0 : -EINVAL;
        struct v4l2_fract *t = &p->parm.output.timeperframe;
        if (req == VIDIOC_S_PARM && t->numerator > 0 && t->denominator > 0) {
            v->fps_num = t->numerator;
            v->fps_den = t->denominator;
            v->configured = 0;
        }
        p->parm.output.capability = V4L2_CAP_TIMEPERFRAME;
        t->numerator = v->fps_num;
        t->denominator = v->fps_den;
        return 0;
    }
    case VIDIOC_S_CTRL: {
        struct v4l2_control *c = arg;
        vn_set_control(v, c->id, c->value);
        return 0;
    }
    case VIDIOC_G_CTRL: {
        struct v4l2_control *c = arg;
        return vn_get_control(v, c->id, &c->value);
    }
    case VIDIOC_S_EXT_CTRLS:
    case VIDIOC_TRY_EXT_CTRLS:
    case VIDIOC_G_EXT_CTRLS: {
        struct v4l2_ext_controls *e = arg;
        for (uint32_t i = 0; i < e->count && e->controls; i++) {
            if (req == VIDIOC_S_EXT_CTRLS) vn_set_control(v, e->controls[i].id, e->controls[i].value);
            else if (req == VIDIOC_G_EXT_CTRLS) {
                int32_t value;
                vn_get_control(v, e->controls[i].id, &value);
                e->controls[i].value = value;
            }
        }
        return 0;
    }
    case VIDIOC_QUERYCTRL: {
        struct v4l2_queryctrl *q = arg;
        if (q->id & V4L2_CTRL_FLAG_NEXT_CTRL) return -EINVAL;
        q->type = V4L2_CTRL_TYPE_INTEGER;
        q->minimum = 0;
        q->maximum = 0x7fffffff;
        q->step = 1;
        q->flags = 0;
        return 0;
    }
    case VIDIOC_S_SELECTION:
    case VIDIOC_G_SELECTION: {
        struct v4l2_selection *s = arg;
        if (req == VIDIOC_S_SELECTION && s->r.width >= 64 && s->r.height >= 64 &&
            s->r.width <= v->width && s->r.height <= v->height) {
            v->crop_w = s->r.width & ~1u;
            v->crop_h = s->r.height & ~1u;
            v->configured = 0;
        }
        s->r = (struct v4l2_rect){ 0, 0, v->crop_w, v->crop_h };
        return 0;
    }
    case VIDIOC_SUBSCRIBE_EVENT:
    case VIDIOC_UNSUBSCRIBE_EVENT:
    case VIDIOC_ENCODER_CMD:
    case VIDIOC_TRY_ENCODER_CMD:
        return 0;
    case VIDIOC_DQEVENT:
        return -ENOENT;
    default: {
        static unsigned long logged[32];
        int seen = 0;
        for (int i = 0; i < 32 && logged[i]; i++) seen |= logged[i] == req;
        for (int i = 0; i < 32 && !seen; i++) if (!logged[i]) { logged[i] = req; break; }
        if (!seen) vn_log("unhandled ioctl 0x%lx", req);
        return -ENOTTY;
    }
    }
}

__attribute__((visibility("hidden"))) int bl_venc_ioctl(int fd, unsigned long req, void *arg, int *rc) {
    struct venc *v = vn_find(fd);
    if (v == NULL) return 0;
    pthread_mutex_lock(&vn_lock);
    int r = arg ? vn_ioctl(v, req, arg) : -EFAULT;
    pthread_mutex_unlock(&vn_lock);
    if (r < 0) {
        errno = -r;
        *rc = -1;
    } else {
        *rc = 0;
    }
    return 1;
}

__attribute__((visibility("hidden"))) int bl_venc_mmap(void *addr, size_t len, int prot, int flags, int fd, off_t off,
                                                       void *(*real)(void *, size_t, int, int, int, off_t), void **out) {
    struct venc *v = vn_find(fd);
    if (v == NULL) return 0;
    pthread_mutex_lock(&vn_lock);
    struct vq *q = off >= VENC_CAPTURE_OFFSET ? &v->coded : &v->raw;
    long index = (off - (off >= VENC_CAPTURE_OFFSET ? VENC_CAPTURE_OFFSET : 0)) / VENC_OFFSET_STEP - 1;
    int memfd = index >= 0 && index < (long)q->count ? q->bufs[index].memfd : -1;
    pthread_mutex_unlock(&vn_lock);
    if (memfd < 0) {
        errno = EINVAL;
        *out = MAP_FAILED;
    } else {
        *out = real(addr, len, prot, (flags & ~MAP_PRIVATE) | MAP_SHARED, memfd, 0);
    }
    return 1;
}

__attribute__((visibility("hidden"))) void bl_venc_closed(int fd) {
    struct venc *v = vn_find(fd);
    if (v == NULL) return;
    pthread_mutex_lock(&vn_lock);
    vn_free_queue(&v->raw);
    vn_free_queue(&v->coded);
    v->used = 0;
    /* A forked child closing its inherited copy must not end the stream for the parent. */
    if (__atomic_sub_fetch(&vn_open_count, 1, __ATOMIC_RELEASE) == 0 && v->owner == getpid()) vn_marker(0);
    pthread_mutex_unlock(&vn_lock);
}

static short vn_ready(struct venc *v) {
    pthread_mutex_lock(&vn_lock);
    short r = (v->coded.ndone ? POLLIN | POLLRDNORM : 0) | (v->raw.ndone ? POLLOUT | POLLWRNORM : 0);
    pthread_mutex_unlock(&vn_lock);
    return r;
}

static int vn_poll(struct pollfd *fds, nfds_t n, int timeout_ms, int (*real)(struct pollfd *, nfds_t, int)) {
    int ours = 0, ready = 0;
    int saved[64];
    if (n > 64) return real(fds, n, timeout_ms);
    for (nfds_t i = 0; i < n; i++) {
        saved[i] = fds[i].fd;
        struct venc *v = vn_find(fds[i].fd);
        if (v == NULL) continue;
        ours++;
        fds[i].revents = vn_ready(v) & (fds[i].events | POLLERR);
        if (fds[i].revents) ready++;
        fds[i].fd = -1;
    }
    if (ours == 0) return real(fds, n, timeout_ms);
    if (ready || timeout_ms < 0 || timeout_ms > 100) timeout_ms = ready ? 0 : 100;
    short keep[64];
    for (nfds_t i = 0; i < n; i++) keep[i] = fds[i].fd < 0 && saved[i] >= 0 ? fds[i].revents : 0;
    int r = n - ours > 0 ? real(fds, n, timeout_ms) : (timeout_ms && !ready ? real(NULL, 0, timeout_ms) : 0);
    if (r < 0) r = 0;
    for (nfds_t i = 0; i < n; i++) {
        if (fds[i].fd < 0 && saved[i] >= 0) fds[i].revents = keep[i];
        fds[i].fd = saved[i];
    }
    return r + ready;
}

int poll(struct pollfd *fds, nfds_t n, int timeout_ms) {
    static int (*real)(struct pollfd *, nfds_t, int);
    if (!real) real = (int (*)(struct pollfd *, nfds_t, int))dlsym(RTLD_NEXT, "poll");
    if (__atomic_load_n(&vn_open_count, __ATOMIC_ACQUIRE) == 0) return real(fds, n, timeout_ms);
    return vn_poll(fds, n, timeout_ms, real);
}
