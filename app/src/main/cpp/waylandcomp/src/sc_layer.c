#define _GNU_SOURCE
#include "sc_layer.h"
#include "ahb_swapchain.h"
#include "droiddeck_color.h"
#include "vk_present.h"
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <android/rect.h>
#include <dlfcn.h>
#include <errno.h>
#include <math.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#define FOURCC(a, b, c, d) \
    ((uint32_t)(a) | ((uint32_t)(b) << 8) | ((uint32_t)(c) << 16) | ((uint32_t)(d) << 24))
#define DRM_ABGR8888 FOURCC('A', 'B', '2', '4')
#define DRM_ABGR2101010 FOURCC('A', 'B', '3', '0')
#define AHB_RGBA8 AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM
#define AHB_RGB10A2 AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM
#define MOD_LINEAR 0ULL
#define MOD_QCOM_COMPRESSED 0x0500000000000001ULL

struct droiddeck_native_handle { int version; int numFds; int numInts; int data[]; };


typedef struct ASurfaceControl ASurfaceControl;
typedef struct ASurfaceTransaction ASurfaceTransaction;
typedef struct ASurfaceTransactionStats ASurfaceTransactionStats;
typedef void (*sc_complete_fn)(void *context, ASurfaceTransactionStats *stats);

struct droiddeck_color_xy { float x, y; };
struct droiddeck_hdr_smpte2086 {
    struct droiddeck_color_xy displayPrimaryRed, displayPrimaryGreen, displayPrimaryBlue, whitePoint;
    float maxLuminance, minLuminance;
};
struct droiddeck_hdr_cta861_3 { float maxContentLightLevel, maxFrameAverageLightLevel; };

static struct {
    ASurfaceControl *(*createFromWindow)(ANativeWindow *, const char *);
    void (*release)(ASurfaceControl *);
    ASurfaceTransaction *(*txCreate)(void);
    void (*txDelete)(ASurfaceTransaction *);
    void (*txApply)(ASurfaceTransaction *);
    void (*setBuffer)(ASurfaceTransaction *, ASurfaceControl *, AHardwareBuffer *, int);
    void (*setZOrder)(ASurfaceTransaction *, ASurfaceControl *, int32_t);
    void (*setVisibility)(ASurfaceTransaction *, ASurfaceControl *, int8_t);
    void (*setGeometry)(ASurfaceTransaction *, ASurfaceControl *, const ARect *, const ARect *, int32_t);
    void (*setBufferTransparency)(ASurfaceTransaction *, ASurfaceControl *, int8_t);
    void (*reparent)(ASurfaceTransaction *, ASurfaceControl *, ASurfaceControl *);
    void (*setOnComplete)(ASurfaceTransaction *, void *, sc_complete_fn);
    int (*prevReleaseFence)(ASurfaceTransactionStats *, ASurfaceControl *);
    void (*setFrameRate)(ASurfaceTransaction *, ASurfaceControl *, float, int8_t);
    void (*setFrameRateStrategy)(ASurfaceTransaction *, ASurfaceControl *, float, int8_t, int8_t);
    void (*setBufferDataSpace)(ASurfaceTransaction *, ASurfaceControl *, int32_t);
    void (*setHdrMetadata_smpte2086)(ASurfaceTransaction *, ASurfaceControl *, const struct droiddeck_hdr_smpte2086 *);
    void (*setHdrMetadata_cta861_3)(ASurfaceTransaction *, ASurfaceControl *, const struct droiddeck_hdr_cta861_3 *);
    const void *(*getNativeHandle)(const AHardwareBuffer *);
    void (*setDesiredHdrHeadroom)(ASurfaceTransaction *, ASurfaceControl *, float);
    int state;
} api;

#define ASC_VISIBILITY_HIDE 0
#define ASC_VISIBILITY_SHOW 1
#define ASC_TRANSPARENCY_TRANSLUCENT 1
#define ASC_TRANSPARENCY_OPAQUE 2

static int load_api(void) {
    if (api.state) return api.state == 1 ? 0 : -1;
    api.state = -1;
    void *nw = dlopen("libnativewindow.so", RTLD_NOW | RTLD_NOLOAD);
    if (!nw) nw = dlopen("libnativewindow.so", RTLD_NOW);
    api.getNativeHandle = nw ? dlsym(nw, "AHardwareBuffer_getNativeHandle") : NULL;
    if (!api.getNativeHandle) {
        droiddeck_log("layer", "unavailable: libnativewindow.so has no AHardwareBuffer_getNativeHandle");
        return -1;
    }
    void *lib = dlopen("libandroid.so", RTLD_NOW | RTLD_NOLOAD);
    if (!lib) lib = dlopen("libandroid.so", RTLD_NOW);
    if (!lib) { droiddeck_log("layer", "unavailable: dlopen(libandroid.so): %s", dlerror()); return -1; }
#define SYM(field, name) api.field = dlsym(lib, name)
    SYM(createFromWindow, "ASurfaceControl_createFromWindow");
    SYM(release, "ASurfaceControl_release");
    SYM(txCreate, "ASurfaceTransaction_create");
    SYM(txDelete, "ASurfaceTransaction_delete");
    SYM(txApply, "ASurfaceTransaction_apply");
    SYM(setBuffer, "ASurfaceTransaction_setBuffer");
    SYM(setZOrder, "ASurfaceTransaction_setZOrder");
    SYM(setVisibility, "ASurfaceTransaction_setVisibility");
    SYM(setGeometry, "ASurfaceTransaction_setGeometry");
    SYM(setBufferTransparency, "ASurfaceTransaction_setBufferTransparency");
    SYM(reparent, "ASurfaceTransaction_reparent");
    SYM(setOnComplete, "ASurfaceTransaction_setOnComplete");
    SYM(prevReleaseFence, "ASurfaceTransactionStats_getPreviousReleaseFenceFd");
    SYM(setFrameRate, "ASurfaceTransaction_setFrameRate");
    SYM(setFrameRateStrategy, "ASurfaceTransaction_setFrameRateWithChangeStrategy");
    SYM(setBufferDataSpace, "ASurfaceTransaction_setBufferDataSpace");
    SYM(setHdrMetadata_smpte2086, "ASurfaceTransaction_setHdrMetadata_smpte2086");
    SYM(setHdrMetadata_cta861_3, "ASurfaceTransaction_setHdrMetadata_cta861_3");
    SYM(setDesiredHdrHeadroom, "ASurfaceTransaction_setDesiredHdrHeadroom");
#undef SYM
    if (!api.createFromWindow || !api.release || !api.txCreate || !api.txDelete || !api.txApply ||
        !api.setBuffer || !api.setZOrder || !api.setVisibility || !api.setGeometry ||
        !api.setBufferTransparency || !api.reparent || !api.setOnComplete || !api.prevReleaseFence) {
        droiddeck_log("layer", "unavailable: libandroid.so lacks part of the ASurfaceControl API (Android 10+)");
        return -1;
    }
    api.state = 1;
    return 0;
}









#define ASC_FRAME_RATE_COMPAT_DEFAULT 0
#define ASC_CHANGE_FRAME_RATE_ALWAYS  1

static _Atomic float g_fps_want;

void sc_layer_set_frame_rate(float fps) {
    atomic_store(&g_fps_want, fps > 0.0f ? fps : 0.0f);
}


#define POOL_MAX 5

struct slot {
    AHardwareBuffer *ahb;
    struct vkp_image *img;
    int w, h;
    uint32_t fmt;
    int release_fd;
    int busy;
};

struct layer {
    const char *name;
    int32_t z;
    int pool_n;
    struct slot slots[POOL_MAX];
    ASurfaceControl *sc;
    ANativeWindow *win;
    int shown;
    int cur_slot;
    void *cur_token;
    ARect geo_src, geo_dst;
    int geo_valid;
    int alloc_tier;
    uint64_t pool_modifier;
    int first_logged;
    int64_t drop_logged_ns;
    unsigned drops_unlogged;

    int logged_w, logged_h, logged_tier;
    uint32_t logged_fmt;
    uint64_t logged_mod;
    int votes_rate;
    float fps_applied;
    int recreate_pending;

    int32_t ds_applied;

    uint32_t md_applied;
    float hr_applied;
};

static struct layer g_layers[SC_LAYER_COUNT];
static void apply_frame_rate(ASurfaceTransaction *tx, struct layer *l);
static void coverage_text(const struct layer *l, char *out, size_t n);
static int g_layers_ready;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_pending_cb;
static unsigned g_stat_layer_frames;
static int g_two_logged;
static AHardwareBuffer *g_blank;

static void layers_init(void) {
    if (g_layers_ready) return;
    g_layers_ready = 1;
    g_layers[SC_LAYER_GAME] = (struct layer){.name = "droiddeck_wayland_game", .z = 1, .pool_n = POOL_MAX,
                                             .cur_slot = -1, .votes_rate = 1, .fps_applied = -1.0f, .hr_applied = -1.0f,
                                             .ds_applied = -1};
    g_layers[SC_LAYER_OVERLAY] = (struct layer){.name = "droiddeck_wayland_overlay", .z = 2, .pool_n = 3,
                                                .cur_slot = -1, .votes_rate = 0, .fps_applied = -1.0f,
                                                .hr_applied = -1.0f, .ds_applied = -1};
    for (int i = 0; i < SC_LAYER_COUNT; i++)
        for (int j = 0; j < POOL_MAX; j++) g_layers[i].slots[j].release_fd = -1;
}

static struct layer *layer_of(int id) {
    layers_init();
    return (id >= 0 && id < SC_LAYER_COUNT) ? &g_layers[id] : NULL;
}

struct complete_ctx {
    ASurfaceControl *sc;
    int layer;
    int prev_slot;
    void *prev_token;
    int retire;
};

static int64_t now_ns(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static void on_complete(void *context, ASurfaceTransactionStats *stats) {
    struct complete_ctx *ctx = context;
    pthread_mutex_lock(&g_lock);
    if (ctx->layer >= 0 && ctx->layer < SC_LAYER_COUNT && ctx->prev_slot >= 0 && ctx->prev_slot < POOL_MAX) {
        struct slot *s = &g_layers[ctx->layer].slots[ctx->prev_slot];
        int fd = (stats && ctx->sc) ? api.prevReleaseFence(stats, ctx->sc) : -1;
        if (s->release_fd >= 0) close(s->release_fd);
        s->release_fd = fd;
        s->busy = 0;
    }
    if (g_pending_cb > 0) g_pending_cb--;
    pthread_mutex_unlock(&g_lock);
    if (ctx->prev_token) {
        int fd = (stats && ctx->sc) ? api.prevReleaseFence(stats, ctx->sc) : -1;
        ahb_swapchain_layer_released(ctx->prev_token, fd);
    }
    if (ctx->retire && ctx->sc) api.release(ctx->sc);
    free(ctx);
}

static int add_complete_on(ASurfaceTransaction *tx, struct layer *l, ASurfaceControl *sc,
                           int prev_slot, void *prev_token, int retire) {
    struct complete_ctx *ctx = calloc(1, sizeof(*ctx));
    if (!ctx) return -1;
    ctx->sc = sc; ctx->layer = (int)(l - g_layers); ctx->prev_slot = prev_slot;
    ctx->prev_token = prev_token; ctx->retire = retire;
    pthread_mutex_lock(&g_lock);
    g_pending_cb++;
    pthread_mutex_unlock(&g_lock);
    api.setOnComplete(tx, ctx, on_complete);
    return 0;
}

static int add_complete(ASurfaceTransaction *tx, struct layer *l, int prev_slot, void *prev_token, int retire) {
    return add_complete_on(tx, l, l->sc, prev_slot, prev_token, retire);
}

static AHardwareBuffer *blank_buffer(void) {
    if (g_blank) return g_blank;
    AHardwareBuffer_Desc d = {
        .width = 16, .height = 16, .layers = 1, .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
        .usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY |
                 AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY};
    if (AHardwareBuffer_allocate(&d, &g_blank) != 0 || !g_blank) { g_blank = NULL; return NULL; }
    void *p = NULL;
    if (AHardwareBuffer_lock(g_blank, AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY, -1, NULL, &p) == 0 && p) {
        AHardwareBuffer_Desc got; AHardwareBuffer_describe(g_blank, &got);
        memset(p, 0, (size_t)got.stride * 4 * 16);
        AHardwareBuffer_unlock(g_blank, NULL);
    }
    return g_blank;
}

static void replace_with_blank(ASurfaceTransaction *tx, struct layer *l) {
    AHardwareBuffer *b = blank_buffer();
    if (b) api.setBuffer(tx, l->sc, b, -1);
}

static void retire_ops(ASurfaceTransaction *tx, ASurfaceControl *sc) {
    AHardwareBuffer *b = blank_buffer();
    if (b) api.setBuffer(tx, sc, b, -1);
    api.setVisibility(tx, sc, ASC_VISIBILITY_HIDE);
    api.reparent(tx, sc, NULL);
}

static void retire_sc(struct layer *l) {
    if (!l->sc) return;
    ASurfaceTransaction *tx = api.txCreate();
    if (tx) {
        retire_ops(tx, l->sc);
        if (add_complete(tx, l, l->cur_slot, l->cur_token, 1) != 0) api.release(l->sc);
        api.txApply(tx);
        api.txDelete(tx);
    } else {
        if (l->cur_token) ahb_swapchain_layer_released(l->cur_token, -1);
        api.release(l->sc);
    }
    droiddeck_log("layer", "%s: SurfaceControl retired (window %p)", l->name, (void *)l->win);
    l->sc = NULL; l->win = NULL;
    l->shown = 0; l->cur_slot = -1; l->cur_token = NULL; l->geo_valid = 0;
    l->fps_applied = -1.0f;
    l->recreate_pending = 0;
    l->ds_applied = -1; l->md_applied = 0; l->hr_applied = -1.0f;
}

static void drain_and_free_pools(void) {
    int64_t deadline = now_ns() + 300000000LL;
    for (;;) {
        pthread_mutex_lock(&g_lock);
        int pending = g_pending_cb;
        pthread_mutex_unlock(&g_lock);
        if (!pending || now_ns() > deadline) break;
        usleep(2000);
    }
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < SC_LAYER_COUNT; i++) {
        for (int j = 0; j < POOL_MAX; j++) {
            struct slot *s = &g_layers[i].slots[j];
            if (s->release_fd >= 0) { struct pollfd p = {.fd = s->release_fd, .events = POLLIN}; poll(&p, 1, 100); close(s->release_fd); }
            if (s->img) vkp_image_destroy(s->img);
            if (s->ahb) AHardwareBuffer_release(s->ahb);
            memset(s, 0, sizeof(*s));
            s->release_fd = -1;
        }
        g_layers[i].first_logged = 0;
        g_layers[i].logged_w = g_layers[i].logged_h = 0;
    }
    pthread_mutex_unlock(&g_lock);
}

static int sniff_modifier(const struct droiddeck_native_handle *h, uint64_t *mod) {
    const uint32_t gmsm = ('g' << 24) | ('m' << 16) | ('s' << 8) | 'm';
    if (!h || h->numFds < 1 || h->numInts < 2) return 0;
    if ((uint32_t)h->data[h->numFds] != gmsm) return 0;
    *mod = (h->data[h->numFds + 1] & 0x08000000) ? MOD_QCOM_COMPRESSED : MOD_LINEAR;
    return 1;
}

static int g_alloc_failed;

enum { TIER_UBWC, TIER_PLAIN, TIER_LINEAR };
#define DROIDDECK_AHB_USAGE_VENDOR_UBWC (1ULL << 28)

static const char *tier_name(int tier) {
    return tier == TIER_UBWC ? "asked for UBWC" : tier == TIER_PLAIN ? "no UBWC request" : "linear by request (CPU bit)";
}

static int alloc_slot(struct layer *l, struct slot *s, int w, int h, uint32_t fmt) {
    g_alloc_failed = 0;
    while (l->alloc_tier <= TIER_LINEAR) {
        const int tier = l->alloc_tier;
        AHardwareBuffer_Desc d = {
            .width = (uint32_t)w, .height = (uint32_t)h, .layers = 1,
            .format = fmt,
            .usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                     AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY |
                     (tier == TIER_UBWC ? DROIDDECK_AHB_USAGE_VENDOR_UBWC : 0) |
                     (tier == TIER_LINEAR ? AHARDWAREBUFFER_USAGE_CPU_READ_RARELY : 0)};
        AHardwareBuffer *ahb = NULL;
        if (AHardwareBuffer_allocate(&d, &ahb) != 0 || !ahb) {
            if (tier < TIER_LINEAR) {
                droiddeck_log("layer", "%s: gralloc refused a %dx%d pool buffer (%s): trying %s", l->name, w, h,
                           tier_name(tier), tier_name(tier + 1));
                l->alloc_tier = tier + 1;
                continue;
            }
            droiddeck_log("error", "layer: %s: AHardwareBuffer_allocate %dx%d (format %#x) failed", l->name, w, h, fmt);
            g_alloc_failed = 1;
            return -1;
        }
        AHardwareBuffer_Desc got; AHardwareBuffer_describe(ahb, &got);
        const struct droiddeck_native_handle *nh = api.getNativeHandle(ahb);
        uint64_t mod = MOD_LINEAR;
        if (!sniff_modifier(nh, &mod)) {
            if (tier < TIER_LINEAR) {
                AHardwareBuffer_release(ahb);
                l->alloc_tier = TIER_LINEAR;
                droiddeck_log("layer", "%s: gralloc handle layout unknown (%d fds, %d ints): using linear pool buffers",
                           l->name, nh ? nh->numFds : -1, nh ? nh->numInts : -1);
                continue;
            }
            mod = MOD_LINEAR;
        }
        int fd = (nh && nh->numFds > 0) ? nh->data[0] : -1;
        struct vkp_image *img = fd >= 0 ? vkp_image_import_dmabuf(fd, fmt == AHB_RGB10A2 ? DRM_ABGR2101010 : DRM_ABGR8888,
                                                                  mod, w, h, got.stride * 4, 0, 1) : NULL;
        if (!img) {
            droiddeck_log("layer", "%s: import of a %s %dx%d pool buffer (stride %u px) into the compositor's Turnip failed",
                       l->name, mod == MOD_QCOM_COMPRESSED ? "UBWC" : "linear", w, h, got.stride);
            AHardwareBuffer_release(ahb);
            if (tier < TIER_LINEAR) { l->alloc_tier = tier + 1; continue; }
            g_alloc_failed = 1;
            return -1;
        }
        s->ahb = ahb; s->img = img; s->w = w; s->h = h; s->fmt = fmt; s->release_fd = -1; s->busy = 0;
        l->pool_modifier = mod;
        if (l->logged_w != w || l->logged_h != h || l->logged_fmt != fmt || l->logged_mod != mod ||
            l->logged_tier != tier) {
            l->logged_w = w; l->logged_h = h; l->logged_fmt = fmt; l->logged_mod = mod; l->logged_tier = tier;
            droiddeck_log("layer", "%s: pool buffers %dx%d%s: %s (%s), stride %u px, up to %d buffers (gralloc handle %d fds / %d ints)",
                       l->name, w, h, fmt == AHB_RGB10A2 ? " 10-bit RGBA1010102" : "",
                       mod == MOD_QCOM_COMPRESSED ? "UBWC (QCOM_COMPRESSED)" : "linear", tier_name(tier),
                       got.stride, l->pool_n, nh ? nh->numFds : -1, nh ? nh->numInts : -1);
        }
        return 0;
    }
    g_alloc_failed = 1;
    return -1;
}

static int fence_signalled(int fd) {
    struct pollfd p = {.fd = fd, .events = POLLIN};
    int r;
    do { r = poll(&p, 1, 0); } while (r < 0 && errno == EINTR);
    return r > 0;
}

static int take_free_slot(struct layer *l, int w, int h, uint32_t fmt, int *wait_fd) {
    int idx = -1, pending = -1, fd = -1;
    *wait_fd = -1;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < l->pool_n; i++) {
        if (i == l->cur_slot || l->slots[i].busy) continue;
        const int rf = l->slots[i].release_fd;
        if (rf < 0 || fence_signalled(rf)) { idx = i; break; }
        if (pending < 0) pending = i;
    }
    if (idx < 0) idx = pending;
    if (idx >= 0) { fd = l->slots[idx].release_fd; l->slots[idx].release_fd = -1; }
    pthread_mutex_unlock(&g_lock);
    if (idx < 0) return -1;
    if (fd >= 0 && fence_signalled(fd)) { close(fd); fd = -1; }
    struct slot *s = &l->slots[idx];
    if (s->ahb && (s->w != w || s->h != h || s->fmt != fmt)) {
        if (fd >= 0) { close(fd); fd = -1; }
        vkp_image_destroy(s->img); AHardwareBuffer_release(s->ahb);
        memset(s, 0, sizeof(*s)); s->release_fd = -1;
    }
    if (fd >= 0 && !vkp_can_wait_sync_fd()) {
        struct pollfd p = {.fd = fd, .events = POLLIN};
        int r;
        do { r = poll(&p, 1, 100); } while (r < 0 && errno == EINTR);
        if (r == 0) {
            pthread_mutex_lock(&g_lock);
            if (s->release_fd < 0) s->release_fd = fd; else close(fd);
            pthread_mutex_unlock(&g_lock);
            return -1;
        }
        close(fd);
        fd = -1;
    }
    if (!s->ahb && alloc_slot(l, s, w, h, fmt) != 0) {
        if (fd >= 0) close(fd);
        return -1;
    }
    *wait_fd = fd;
    return idx;
}


int sc_layer_available(void) { return load_api() == 0; }

void sc_layer_probe_dmabuf_fd(int fd) {
    static int done;
    if (done || fd < 0) return;
    done = 1;
    struct { uint32_t flags; int32_t fd; } exp = {.flags = 1u  , .fd = -1};

    if (ioctl(fd, _IOWR('b', 2, exp), &exp) == 0) {
        droiddeck_log("layer", "kernel exports sync_file fences from the game's dma-buf: zero-copy acquire fences can come from the buffer itself");
        if (exp.fd >= 0) close(exp.fd);
    } else {
        droiddeck_log("layer", "DMA_BUF_IOCTL_EXPORT_SYNC_FILE on the game's dma-buf failed (%s): acquire fences must be exported by the game's driver (sync_fd) instead",
                   strerror(errno));
    }
}

static int ensure_sc(struct layer *l) {
    if (load_api() != 0) return -1;
    ANativeWindow *win = vkp_window();
    if (!win) return -1;
    if (l->sc && l->win != win) retire_sc(l);
    if (!l->sc) {
        l->sc = api.createFromWindow(win, l->name);
        if (!l->sc) { droiddeck_log("error", "layer: ASurfaceControl_createFromWindow(%s) failed", l->name); return -1; }
        l->win = win;
        l->ds_applied = -1; l->md_applied = 0; l->hr_applied = -1.0f;
        ASurfaceTransaction *tx = api.txCreate();
        if (tx) {
            api.setZOrder(tx, l->sc, l->z);
            api.setVisibility(tx, l->sc, ASC_VISIBILITY_HIDE);
            apply_frame_rate(tx, l);
            api.txApply(tx); api.txDelete(tx);
        }
        droiddeck_log("layer", "SurfaceControl \"%s\" created as a child of the screen surface (z=%d)", l->name, (int)l->z);
    }
    return 0;
}

static int layer_geometry(int w, int h, int scene_w, int scene_h, int r[8]) {
    if (vkp_update_map(scene_w, scene_h) != 0) return -1;
    return vkp_map_rect(w, h, scene_w, scene_h, r) ? 1 : 0;
}

static ARect g_game_src, g_game_dst;
static int g_game_geo_known;

static void apply_geometry(ASurfaceTransaction *tx, struct layer *l, const int r[8]) {
    ARect srcR = {r[0], r[1], r[2], r[3]}, dstR = {r[4], r[5], r[6], r[7]};
    if (l == &g_layers[SC_LAYER_GAME]) { g_game_src = srcR; g_game_dst = dstR; g_game_geo_known = 1; }
    if (!l->geo_valid || memcmp(&srcR, &l->geo_src, sizeof(srcR)) || memcmp(&dstR, &l->geo_dst, sizeof(dstR))) {
        api.setGeometry(tx, l->sc, &srcR, &dstR, 0  );
        l->geo_src = srcR; l->geo_dst = dstR; l->geo_valid = 1;
        char cov[96] = "";
        if (l->ds_applied > 0) coverage_text(l, cov, sizeof(cov));
        droiddeck_log("layer", "%s geometry: buffer %d,%d-%d,%d -> screen %d,%d-%d,%d%s%s%s", l->name,
                   r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], cov[0] ? " (HDR layer " : "", cov, cov[0] ? ")" : "");
    }
}

static void apply_frame_rate(ASurfaceTransaction *tx, struct layer *l) {
    float want = l->votes_rate ? atomic_load(&g_fps_want) : 0.0f;
    if (want == l->fps_applied) return;
    if (!api.setFrameRateStrategy && !api.setFrameRate) return;
    if (api.setFrameRateStrategy) {
        api.setFrameRateStrategy(tx, l->sc, want, ASC_FRAME_RATE_COMPAT_DEFAULT, ASC_CHANGE_FRAME_RATE_ALWAYS);
    } else {
        api.setFrameRate(tx, l->sc, want, ASC_FRAME_RATE_COMPAT_DEFAULT);
    }
    int first = l->fps_applied < 0.0f;
    l->fps_applied = want;
    if (want > 0.0f) droiddeck_log("layer", "display frame-rate vote on %s: %.2f Hz", l->name, want);
    else if (!first) droiddeck_log("layer", "display frame-rate vote on %s cleared (panel runs free)", l->name);
}

static void coverage_text(const struct layer *l, char *out, size_t n) {
    int ow = 0, oh = 0;
    vkp_output_size(&ow, &oh);
    if (!l->geo_valid || ow <= 0 || oh <= 0) { if (n) out[0] = 0; return; }
    int w = l->geo_dst.right - l->geo_dst.left, h = l->geo_dst.bottom - l->geo_dst.top;
    if (w < 0) w = 0;
    if (h < 0) h = 0;
    const double pct = 100.0 * ((double)w * h) / ((double)ow * oh);
    snprintf(out, n, "covers %.0f%% of the screen (%dx%d of %dx%d)", pct > 100.0 ? 100.0 : pct, w, h, ow, oh);
}

static void apply_headroom(ASurfaceTransaction *tx, struct layer *l, const struct droiddeck_color *c) {
    static int said_missing;
    if (!api.setDesiredHdrHeadroom) {
        if (c && c->dataspace && !said_missing) {
            said_missing = 1;
            droiddeck_color_note_headroom_request(-1.0f);
            droiddeck_log("color", "HDR headroom request not available on %s (Android < 15 has no "
                       "ASurfaceTransaction_setDesiredHdrHeadroom): the layer relies on Android's default", l->name);
        }
        return;
    }
    const float want = (c && c->dataspace) ? droiddeck_color_desired_headroom(c, NULL, 0) : 0.0f;
    if (l->hr_applied < 0.0f && want == 0.0f && !(c && c->dataspace)) return;
    if (l->hr_applied >= 0.0f && fabsf(want - l->hr_applied) < 0.005f) return;
    char why[200] = "";
    if (c && c->dataspace) droiddeck_color_desired_headroom(c, why, sizeof(why));
    api.setDesiredHdrHeadroom(tx, l->sc, want);
    const int first = l->hr_applied < 0.0f;
    l->hr_applied = want;
    droiddeck_color_note_headroom_request(want);
    if (want > 0.0f)
        droiddeck_log("color", "requested HDR headroom %.1fx on %s (%s)", want, l->name, why);
    else if (c && c->dataspace)
        droiddeck_log("color", "no HDR headroom requested on %s: %s", l->name, why);
    else if (!first)
        droiddeck_log("color", "HDR headroom request on %s cleared (no preference): the frame on the layer is not HDR", l->name);
}

static void apply_colour(ASurfaceTransaction *tx, struct layer *l, const struct droiddeck_color *c) {
    int32_t want = c ? c->dataspace : DROIDDECK_ADATASPACE_UNKNOWN;
    uint32_t md = (c && c->dataspace) ? c->identity : 0;
    if (!api.setBufferDataSpace) return;
    if (l->ds_applied < 0 && want == DROIDDECK_ADATASPACE_UNKNOWN) return;
    apply_headroom(tx, l, want ? c : NULL);
    if (want == l->ds_applied && md == l->md_applied) return;
    api.setBufferDataSpace(tx, l->sc, want);
    struct droiddeck_hdr_smpte2086 st;
    struct droiddeck_hdr_cta861_3 cta;
    int st_on = c && c->dataspace && c->has_st2086, cta_on = c && c->dataspace && c->has_cta861;
    if (st_on) {
        st = (struct droiddeck_hdr_smpte2086){
            .displayPrimaryRed = {c->red[0], c->red[1]}, .displayPrimaryGreen = {c->green[0], c->green[1]},
            .displayPrimaryBlue = {c->blue[0], c->blue[1]}, .whitePoint = {c->white[0], c->white[1]},
            .maxLuminance = c->max_lum, .minLuminance = c->min_lum};
    }
    if (cta_on) cta = (struct droiddeck_hdr_cta861_3){.maxContentLightLevel = c->max_cll, .maxFrameAverageLightLevel = c->max_fall};
    if (api.setHdrMetadata_smpte2086) api.setHdrMetadata_smpte2086(tx, l->sc, st_on ? &st : NULL);
    if (api.setHdrMetadata_cta861_3) api.setHdrMetadata_cta861_3(tx, l->sc, cta_on ? &cta : NULL);
    if (want) {
        char cov[96];
        coverage_text(l, cov, sizeof(cov));
        droiddeck_log("color", "%s: dataspace %s (%#x) set on the display layer for image description #%u%s%s; SMPTE 2086 "
                   "%s, CTA-861.3 %s [%s]", l->name, want == DROIDDECK_ADATASPACE_BT2020_PQ ? "BT2020_PQ" : "HDR",
                   (unsigned)want, c->identity, cov[0] ? ", " : "", cov,
                   st_on ? (api.setHdrMetadata_smpte2086 ? "sent" : "not supported by this Android") : "none given",
                   cta_on ? (api.setHdrMetadata_cta861_3 ? "sent" : "not supported by this Android") : "none given",
                   c->text);
    }
    else
        droiddeck_log("color", "%s: dataspace back to UNKNOWN (sRGB), HDR metadata cleared - the frame on the layer is "
                   "not an HDR frame", l->name);
    l->ds_applied = want;
    l->md_applied = md;
}

int sc_layer_can_tag_hdr(void) { return load_api() == 0 && api.setBufferDataSpace != NULL; }

void sc_layer_hdr_symbols(char *out, size_t size) {
    if (load_api() != 0) { snprintf(out, size, "no display layers"); return; }
    snprintf(out, size, "setBufferDataSpace %s, setHdrMetadata_smpte2086 %s, setHdrMetadata_cta861_3 %s, "
             "setDesiredHdrHeadroom %s", api.setBufferDataSpace ? "yes" : "NO",
             api.setHdrMetadata_smpte2086 ? "yes" : "no", api.setHdrMetadata_cta861_3 ? "yes" : "no",
             api.setDesiredHdrHeadroom ? "yes (asked for per HDR frame: content peak / SDR white)" : "no (Android < 15)");
}

static void log_layer_count(void) {
    if (g_two_logged) return;
    g_two_logged = 1;
    droiddeck_log("layer", "%d display layers in use: \"%s\" (z=%d) and \"%s\" (z=%d) above it, both children of the "
               "screen surface; the app's own views (drawer, HUD, pointer) stay above both. %d is the cap - more "
               "would push SurfaceFlinger into GPU client composition",
               SC_LAYER_COUNT, g_layers[SC_LAYER_GAME].name, (int)g_layers[SC_LAYER_GAME].z,
               g_layers[SC_LAYER_OVERLAY].name, (int)g_layers[SC_LAYER_OVERLAY].z, SC_LAYER_COUNT);
}

static ASurfaceControl *swap_sc_begin(struct layer *l) {
    if (!l->recreate_pending) return NULL;
    l->recreate_pending = 0;
    if (!l->sc) return NULL;
    ANativeWindow *win = vkp_window();
    if (!win || win != l->win) return NULL;
    ASurfaceControl *fresh = api.createFromWindow(win, l->name);
    if (!fresh) {
        droiddeck_log("error", "layer: %s: composition recovery could not create a new SurfaceControl", l->name);
        return NULL;
    }
    ASurfaceControl *old = l->sc;
    l->sc = fresh;
    l->shown = 0;
    l->geo_valid = 0;
    l->fps_applied = -1.0f;
    l->ds_applied = -1; l->md_applied = 0; l->hr_applied = -1.0f;
    droiddeck_log("layer", "composition recovery: %s got a fresh SurfaceControl now that nothing is above "
               "the game (measured on this panel: hardware composition does NOT return from this alone)", l->name);
    return old;
}

static int present_slot(struct layer *l, int idx, const int r[8], const struct droiddeck_color *color,
                        int translucent) {
    struct slot *s = &l->slots[idx];
    ASurfaceTransaction *tx = api.txCreate();
    if (!tx) return -1;
    ASurfaceControl *old = swap_sc_begin(l);
    if (old) api.setZOrder(tx, l->sc, l->z);
    api.setBuffer(tx, l->sc, s->ahb, -1);
    api.setBufferTransparency(tx, l->sc,
                              translucent ? ASC_TRANSPARENCY_TRANSLUCENT : ASC_TRANSPARENCY_OPAQUE);
    apply_geometry(tx, l, r);
    apply_frame_rate(tx, l);
    apply_colour(tx, l, color);
    if (!l->shown) api.setVisibility(tx, l->sc, ASC_VISIBILITY_SHOW);
    if (old) retire_ops(tx, old);
    if (add_complete_on(tx, l, old ? old : l->sc, l->cur_slot, l->cur_token, old ? 1 : 0) != 0 && old)
        api.release(old);
    pthread_mutex_lock(&g_lock);
    s->busy = 1;
    pthread_mutex_unlock(&g_lock);
    api.txApply(tx);
    api.txDelete(tx);
    l->cur_slot = idx;
    l->cur_token = NULL;
    if (!l->shown) { l->shown = 1; droiddeck_log("layer", "%s: layer shown", l->name); }
    g_stat_layer_frames++;
    return 0;
}

static unsigned g_pool_drops;
static void log_drop(struct layer *l) {
    g_pool_drops++;
    l->drops_unlogged++;
    int64_t t = now_ns();
    if (!l->drop_logged_ns || t - l->drop_logged_ns > 30000000000LL) {
        droiddeck_log("layer", "%s: no free layer buffer (display still holds all %d): %u frame%s dropped%s", l->name,
                   l->pool_n, l->drops_unlogged, l->drops_unlogged == 1 ? "" : "s",
                   l->drop_logged_ns ? " since the last such line" : "");
        l->drop_logged_ns = t;
        l->drops_unlogged = 0;
    }
}

unsigned sc_layer_drops_take(void) {
    unsigned n = g_pool_drops;
    g_pool_drops = 0;
    return n;
}

int sc_layer_present_ahb(AHardwareBuffer *ahb, int w, int h, int acquire_fd, void *token, int scene_w, int scene_h,
                         const struct droiddeck_color *color, uint32_t ahb_format) {
    struct layer *l = layer_of(SC_LAYER_GAME);
    int r[8];
    if (!ahb || !token || ensure_sc(l) != 0) goto unavailable;
    int g = layer_geometry(w, h, scene_w, scene_h, r);
    if (g < 0) goto unavailable;
    if (g == 0) { if (acquire_fd >= 0) close(acquire_fd); sc_layer_hide(); return 1; }
    if (token == l->cur_token && l->shown && !l->recreate_pending) {
        if (acquire_fd >= 0) close(acquire_fd);
        ASurfaceTransaction *tx = api.txCreate();
        if (tx) {
            apply_geometry(tx, l, r); apply_frame_rate(tx, l); apply_colour(tx, l, color);
            api.txApply(tx); api.txDelete(tx);
        }
        vkp_signal_first_frame();
        return 0;
    }
    ASurfaceTransaction *tx = api.txCreate();
    if (!tx) goto unavailable;
    ASurfaceControl *old = swap_sc_begin(l);
    if (old) api.setZOrder(tx, l->sc, l->z);
    api.setBuffer(tx, l->sc, ahb, acquire_fd);
    api.setBufferTransparency(tx, l->sc, ASC_TRANSPARENCY_OPAQUE);
    apply_geometry(tx, l, r);
    apply_frame_rate(tx, l);
    apply_colour(tx, l, color);
    if (!l->shown) api.setVisibility(tx, l->sc, ASC_VISIBILITY_SHOW);
    if (old) retire_ops(tx, old);
    if (add_complete_on(tx, l, old ? old : l->sc, l->cur_slot,
                        l->cur_token == token ? NULL : l->cur_token, old ? 1 : 0) != 0) {
        api.txDelete(tx);
        if (old) api.release(old);
        l->ds_applied = -1; l->md_applied = 0; l->hr_applied = -1.0f;
        return -1;
    }
    api.txApply(tx);
    api.txDelete(tx);
    l->cur_slot = -1;
    l->cur_token = token;
    if (!l->shown) { l->shown = 1; droiddeck_log("layer", "%s: layer shown", l->name); }
    if (!l->first_logged) l->first_logged = 1;
    vkp_signal_first_frame();
    if (color && color->dataspace) droiddeck_color_frame_shown(color, DROIDDECK_HDR_ZERO_COPY, ahb_format);
    return 0;
unavailable:
    if (acquire_fd >= 0) close(acquire_fd);
    return -1;
}

int sc_layer_present(struct vkp_image *src, int scene_w, int scene_h, const struct droiddeck_color *color) {
    struct layer *l = layer_of(SC_LAYER_GAME);
    if (!src || ensure_sc(l) != 0) return -1;
    int sw = vkp_image_width(src), sh = vkp_image_height(src);
    int r[8];
    int g = layer_geometry(sw, sh, scene_w, scene_h, r);
    if (g < 0) return -1;
    if (g == 0) { sc_layer_hide(); return 0; }

    int wait_fd;
    int idx = take_free_slot(l, sw, sh, AHB_RGBA8, &wait_fd);
    if (idx < 0) { log_drop(l); return 0; }
    if (vkp_blit_image(src, l->slots[idx].img, wait_fd) != 0) return -1;
    if (present_slot(l, idx, r, color, 0) != 0) return -1;
    if (color && color->dataspace) droiddeck_color_frame_shown(color, DROIDDECK_HDR_LAYER_COPY, AHB_RGBA8);
    if (!l->first_logged) {
        l->first_logged = 1;
        droiddeck_log("layer", "presenting %dx%d game frames on their own SurfaceControl layer (%s pool, %d buffers); "
                   "HUD and pointer stay Android views above it", sw, sh,
                   l->pool_modifier == MOD_QCOM_COMPRESSED ? "UBWC" : "linear", l->pool_n);
    }
    vkp_signal_first_frame();
    return 0;
}

int sc_layer_present_pass(const struct vkp_draw *draws, int n, int scene_w, int scene_h) {
    struct layer *l = layer_of(SC_LAYER_GAME);
    int rw = 0, rh = 0, r[8];
    if (!draws || n <= 0 || ensure_sc(l) != 0) return -1;
    if (vkp_update_map(scene_w, scene_h) != 0) return -1;
    if (vkp_pass_begin(scene_w, scene_h, draws, n, &rw, &rh) != 0) return -1;
    if (!vkp_map_rect(rw, rh, scene_w, scene_h, r)) { vkp_pass_abort(); sc_layer_hide(); return 0; }
    int wait_fd;
    int idx = take_free_slot(l, rw, rh, AHB_RGBA8, &wait_fd);
    if (idx < 0) { vkp_pass_abort(); log_drop(l); return 0; }
    if (vkp_pass_copy_to(l->slots[idx].img, wait_fd) != 0) return -1;
    if (present_slot(l, idx, r, NULL, 0) != 0) return -1;
    if (!l->first_logged) {
        l->first_logged = 1;
        droiddeck_log("layer", "presenting %dx%d frames on their own SurfaceControl layer (%s pool, %d buffers); "
                   "HUD and pointer stay Android views above it", rw, rh,
                   l->pool_modifier == MOD_QCOM_COMPRESSED ? "UBWC" : "linear", l->pool_n);
    }
    vkp_signal_first_frame();
    return 0;
}

static uint32_t g_hdr_pool_fmt = AHB_RGB10A2;

int sc_layer_present_hdr_scene(const struct vkp_draw *draws, int n, const struct vkp_hdr_frame *hf,
                               int scene_w, int scene_h, const struct droiddeck_color *color) {
    struct layer *l = layer_of(SC_LAYER_GAME);
    int rw = 0, rh = 0, r[8];
    if (!draws || n <= 0 || !hf || ensure_sc(l) != 0) return -1;
    if (vkp_update_map(scene_w, scene_h) != 0) return -1;
    if (vkp_pass_begin_hdr(scene_w, scene_h, draws, n, hf, &rw, &rh) != 0) return -1;
    if (!vkp_map_rect(rw, rh, scene_w, scene_h, r)) { vkp_pass_abort(); sc_layer_hide(); return 0; }
    const int tm = hf->tonemap;
    const uint32_t fmt = tm ? AHB_RGBA8 : g_hdr_pool_fmt;
    g_alloc_failed = 0;
    int wait_fd;
    int idx = take_free_slot(l, rw, rh, fmt, &wait_fd);
    if (idx < 0 && g_alloc_failed && fmt == AHB_RGB10A2) {
        g_hdr_pool_fmt = AHB_RGBA8;
        droiddeck_log("color", "%s: this device will not make a 10-bit layer buffer (RGBA1010102): the HDR picture goes "
                   "on 8-bit buffers instead (still tagged BT2020_PQ; less precision)", l->name);
        idx = take_free_slot(l, rw, rh, g_hdr_pool_fmt, &wait_fd);
    }
    if (idx < 0) { vkp_pass_abort(); log_drop(l); return 0; }
    if (vkp_pass_copy_to(l->slots[idx].img, wait_fd) != 0) return -1;
    if (present_slot(l, idx, r, tm ? NULL : color, 0) != 0) return -1;
    if (color && color->dataspace)
        droiddeck_color_frame_shown(color, tm ? DROIDDECK_HDR_TONEMAPPED : DROIDDECK_HDR_COMPOSED, tm ? AHB_RGBA8 : g_hdr_pool_fmt);
    static int said = -1;
    if (said != tm) {
        said = tm;
        if (tm)
            droiddeck_log("color", "tone-mapped picture on the game's display layer: %dx%d, %s 8-bit buffers, untagged "
                       "(sRGB) - HDR output is switched off", rw, rh,
                       l->pool_modifier == MOD_QCOM_COMPRESSED ? "UBWC" : "linear");
        else {
            char cov[96];
            coverage_text(l, cov, sizeof(cov));
            droiddeck_log("color", "HDR picture on its own display layer: %dx%d, %s %s buffers, tagged %s%s%s", rw, rh,
                       l->pool_modifier == MOD_QCOM_COMPRESSED ? "UBWC" : "linear",
                       g_hdr_pool_fmt == AHB_RGB10A2 ? "10-bit" : "8-bit",
                       color && color->dataspace == DROIDDECK_ADATASPACE_BT2020_PQ ? "BT2020_PQ" : "HDR",
                       cov[0] ? ", " : "", cov);
        }
    }
    if (!l->first_logged) l->first_logged = 1;
    vkp_signal_first_frame();
    return 0;
}

static int overlay_affordable(int *deg, int sw[2], int dw[2]) {
    *deg = vkp_surface_rotation_degrees();
    sw[0] = sw[1] = dw[0] = dw[1] = 0;
    if (!g_game_geo_known) return 1;
    if (*deg <= 0) return 1;
    sw[0] = g_game_src.right - g_game_src.left; sw[1] = g_game_src.bottom - g_game_src.top;
    dw[0] = g_game_dst.right - g_game_dst.left; dw[1] = g_game_dst.bottom - g_game_dst.top;
    if (sw[0] <= 0 || sw[1] <= 0) return 1;
    return !(dw[0] != sw[0] || dw[1] != sw[1]);
}

static int g_overlay_declined = -1;

int sc_layer_overlay_affordable(void) {
    layers_init();
    int deg, sr[2], ds[2];
    int ok = overlay_affordable(&deg, sr, ds);
    int want = ok ? 0 : 1;
    if (want != g_overlay_declined) {
        int first = g_overlay_declined < 0;
        g_overlay_declined = want;
        if (want)
            droiddeck_log("layer", "overlay layer declined: this display rotates every layer %d° and the game "
                       "layer is scaled %dx%d -> %dx%d, and on that combination a second layer drops the "
                       "whole frame to GPU composition for the rest of the session (measured). The window "
                       "above the game goes on the copy path instead - same picture, one blit.",
                       deg, sr[0], sr[1], ds[0], ds[1]);
        else if (!first)
            droiddeck_log("layer", "overlay layer allowed again: the game layer is no longer both rotated and "
                       "scaled, so a second display layer costs nothing here");
    }
    return ok;
}

int sc_layer_present_overlay(struct vkp_image *src, const int geo[8], int translucent) {
    struct layer *l = layer_of(SC_LAYER_OVERLAY);
    if (!src || !geo) return -1;
    if (!sc_layer_overlay_affordable()) return -1;
    if (ensure_sc(l) != 0) return -1;
    int sw = vkp_image_width(src), sh = vkp_image_height(src);
    if (sw <= 0 || sh <= 0) return -1;
    int wait_fd;
    int idx = take_free_slot(l, sw, sh, AHB_RGBA8, &wait_fd);
    if (idx < 0) { log_drop(l); return 0; }
    if (vkp_blit_image(src, l->slots[idx].img, wait_fd) != 0) return -1;
    if (present_slot(l, idx, geo, NULL, translucent) != 0) return -1;
    if (!l->first_logged) {
        l->first_logged = 1;
        log_layer_count();
    }
    return 0;
}

static void hide_layer(struct layer *l) {
    if (!l->sc || !l->shown || api.state != 1) return;
    ASurfaceTransaction *tx = api.txCreate();
    if (!tx) return;
    replace_with_blank(tx, l);
    api.setVisibility(tx, l->sc, ASC_VISIBILITY_HIDE);
    add_complete(tx, l, l->cur_slot, l->cur_token, 0);
    api.txApply(tx); api.txDelete(tx);
    l->shown = 0; l->cur_slot = -1; l->cur_token = NULL;
}

void sc_layer_hide(void) {
    layers_init();
    int said = g_layers[SC_LAYER_GAME].shown || g_layers[SC_LAYER_OVERLAY].sc;
    if (g_layers[SC_LAYER_OVERLAY].sc) sc_layer_hide_overlay();
    if (g_layers[SC_LAYER_GAME].shown) hide_layer(&g_layers[SC_LAYER_GAME]);
    if (said) droiddeck_log("layer", "layers hidden (scene is not a single fullscreen window)");
}

void sc_layer_hide_overlay(void) {
    layers_init();
    struct layer *l = &g_layers[SC_LAYER_OVERLAY];
    if (!l->sc) return;
    if (l->shown) hide_layer(l);
    retire_sc(l);
    droiddeck_log("layer", "%s: gone (nothing is above the game any more)", l->name);
    if (g_layers[SC_LAYER_GAME].sc) g_layers[SC_LAYER_GAME].recreate_pending = 1;
}

void sc_layer_window_gone(void) {
    if (api.state != 1) return;
    layers_init();
    for (int i = SC_LAYER_COUNT - 1; i >= 0; i--) retire_sc(&g_layers[i]);
    drain_and_free_pools();
}

unsigned sc_layer_frames_take(void) {
    unsigned n = g_stat_layer_frames;
    g_stat_layer_frames = 0;
    return n;
}
