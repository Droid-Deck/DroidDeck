/*
 * zwp_linux_dmabuf_v1: the buffer params a client builds a dma-buf wl_buffer from, the format and
 * modifier table the renderer can import, and dma-buf feedback (version 4). Split out of
 * compositor.c; the buffer records themselves are in compositor_internal.h.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <wayland-server.h>

#include "linux-dmabuf-v1-server-protocol.h"
#include "vk_present.h"
#include "droiddeck_ext.h"
#include "droiddeck_color.h"
#include "compositor_internal.h"

/* ------------------------------------------------------------ zwp_linux_dmabuf_v1 */

static void dbuf_buffer_resource_destroy(struct wl_resource *r) {
    dmabuf_buffer_unref(wl_resource_get_user_data(r));
}

static void params_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void params_add(struct wl_client *c, struct wl_resource *r, int32_t fd, uint32_t plane,
                       uint32_t offset, uint32_t stride, uint32_t mod_hi, uint32_t mod_lo) {
    struct dmabuf_params *p = wl_resource_get_user_data(r);
    if (plane >= MAX_PLANES) { close(fd); return; }
    if (p->fd[plane] >= 0) close(p->fd[plane]);
    p->fd[plane] = fd;
    p->offset[plane] = offset;
    p->stride[plane] = stride;
    p->modifier[plane] = ((uint64_t)mod_hi << 32) | mod_lo;
    if ((int)plane + 1 > p->n_planes) p->n_planes = plane + 1;
}
static struct wl_resource *params_do_create(struct wl_client *c, struct wl_resource *r, uint32_t id,
                                            int32_t w, int32_t h, uint32_t format, uint32_t flags) {
    struct dmabuf_params *p = wl_resource_get_user_data(r);
    struct dmabuf_buffer *b = calloc(1, sizeof(*b));
    if (!b) return NULL;
    b->n_planes = p->n_planes;
    b->refs = 1; /* the wl_buffer resource's */
    b->width = w; b->height = h; b->format = format;
    b->modifier = p->modifier[0];
    for (int i = 0; i < MAX_PLANES; i++) b->fd[i] = -1;
    for (int i = 0; i < p->n_planes; i++) {
        b->fd[i] = p->fd[i];
        b->offset[i] = p->offset[i];
        b->stride[i] = p->stride[i];
        p->fd[i] = -1; /* ownership moves to the buffer */
    }
    struct wl_resource *buf = wl_resource_create(c, &wl_buffer_interface, 1, id);
    if (!buf) {
        for (int i = 0; i < b->n_planes; i++) if (b->fd[i] >= 0) close(b->fd[i]);
        free(b);
        wl_client_post_no_memory(c);
        return NULL;
    }
    wl_resource_set_implementation(buf, &dbuf_buffer_impl, b, dbuf_buffer_resource_destroy);
    struct client_info *ci = client_info_of(c);
    if (ci) ci->dmabuf_buffers++;
    return buf;
}
static void params_create(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h,
                          uint32_t format, uint32_t flags) {
    struct wl_resource *buf = params_do_create(c, r, 0, w, h, format, flags);
    if (buf) zwp_linux_buffer_params_v1_send_created(r, buf);
    else zwp_linux_buffer_params_v1_send_failed(r);
}
static void params_create_immed(struct wl_client *c, struct wl_resource *r, uint32_t buffer_id,
                                int32_t w, int32_t h, uint32_t format, uint32_t flags) {
    params_do_create(c, r, buffer_id, w, h, format, flags);
}
static const struct zwp_linux_buffer_params_v1_interface params_impl = {
    .destroy = params_destroy,
    .add = params_add,
    .create = params_create,
    .create_immed = params_create_immed,
};
static void params_resource_destroy(struct wl_resource *r) {
    struct dmabuf_params *p = wl_resource_get_user_data(r);
    if (!p) return;
    for (int i = 0; i < MAX_PLANES; i++)
        if (p->fd[i] >= 0) close(p->fd[i]);
    free(p);
}

static void dmabuf_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void dmabuf_create_params(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct dmabuf_params *p = calloc(1, sizeof(*p));
    if (!p) { wl_client_post_no_memory(c); return; }
    for (int i = 0; i < MAX_PLANES; i++) p->fd[i] = -1;
    struct wl_resource *pr = wl_resource_create(c, &zwp_linux_buffer_params_v1_interface,
                                                wl_resource_get_version(r), id);
    if (!pr) { free(p); wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(pr, &params_impl, p, params_resource_destroy);
}
static void dmabuf_get_default_feedback(struct wl_client *c, struct wl_resource *r, uint32_t id);
static void dmabuf_get_surface_feedback(struct wl_client *c, struct wl_resource *r, uint32_t id,
                                        struct wl_resource *surface);
static const struct zwp_linux_dmabuf_v1_interface dmabuf_impl = {
    .destroy = dmabuf_destroy,
    .create_params = dmabuf_create_params,
    .get_default_feedback = dmabuf_get_default_feedback,
    .get_surface_feedback = dmabuf_get_surface_feedback,
};
/* The advertised format/modifier table, built at the first bind from what the renderer's driver
 * can import (vkp_dmabuf_modifiers). INVALID is always offered too (Mesa drops it; other clients
 * may use it for the implicit path). Without a renderer the list is LINEAR + INVALID, as before. */
/* The last two rows are HDR10's (droiddeck_color.h): A2B10G10R10 as AB30 (alpha) + XB30 (opaque) - Mesa
 * lists a VkFormat only when both are advertised, and it is the one 10-bit layout our zero-copy WSI
 * can put in a gralloc buffer (AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM). They are advertised ONLY
 * while the HDR gate is open, and after the 8-bit rows, so every other session - and every client that
 * takes the first format it sees - gets exactly the table it always had. */
#define DMABUF_NFMT_MAX 6
#define DMABUF_NFMT_SDR 4
#define DMABUF_NMOD 4
#define DRM_ABGR2101010 FOURCC('A', 'B', '3', '0')
#define DRM_XBGR2101010 FOURCC('X', 'B', '3', '0')
static struct { uint32_t fmt; uint64_t mods[DMABUF_NMOD]; int n; } g_dmabuf_fmts[DMABUF_NFMT_MAX] = {
    {DRM_ARGB8888}, {DRM_XRGB8888}, {DRM_ABGR8888}, {DRM_XBGR8888}, {DRM_ABGR2101010}, {DRM_XBGR2101010}};
static int g_dmabuf_nfmt = DMABUF_NFMT_SDR;   /* rows advertised: the SDR four, + 2 with the HDR gate open */
static int g_dmabuf_fmts_ready;
static void dmabuf_build_feedback(void);

static void dmabuf_build_formats(void) {
    char line[320];
    int pos = 0, compressed = 0;
    g_dmabuf_fmts_ready = 1;
    g_dmabuf_nfmt = droiddeck_color_hdr_open() ? DMABUF_NFMT_MAX : DMABUF_NFMT_SDR;
    for (int f = 0; f < g_dmabuf_nfmt; f++) {
        uint64_t got[DMABUF_NMOD];
        int n = vkp_dmabuf_modifiers(g_dmabuf_fmts[f].fmt, got, DMABUF_NMOD), k = 0;
        /* LINEAR first: it is the layout every client and the shm fallback agree on, and the one
         * we advertised before this table existed. */
        g_dmabuf_fmts[f].mods[k++] = MOD_LINEAR;
        for (int i = 0; i < n && k < DMABUF_NMOD - 1; i++) {
            if (got[i] == MOD_LINEAR) continue;
            if (got[i] == VKP_MOD_QCOM_COMPRESSED && !g_ubwc) continue;
            g_dmabuf_fmts[f].mods[k++] = got[i];
            if (got[i] == VKP_MOD_QCOM_COMPRESSED) compressed++;
        }
        g_dmabuf_fmts[f].mods[k++] = MOD_INVALID;
        g_dmabuf_fmts[f].n = k;
        uint32_t fmt = g_dmabuf_fmts[f].fmt;
        pos += snprintf(line + pos, sizeof(line) - (size_t)pos, "%s%c%c%c%c", f ? ", " : "",
                        fmt & 0xff, (fmt >> 8) & 0xff, (fmt >> 16) & 0xff, (fmt >> 24) & 0xff);
        for (int i = 0; i < k - 1 && pos < (int)sizeof(line); i++)
            pos += snprintf(line + pos, sizeof(line) - (size_t)pos, "%s%s", i ? "+" : " ",
                            vkp_modifier_name(g_dmabuf_fmts[f].mods[i]));
        if (pos >= (int)sizeof(line)) pos = (int)sizeof(line) - 1;
    }
    droiddeck_log("dmabuf", "formats: %s%s", line,
               !g_ubwc ? " (DROIDDECK_WAYLAND_UBWC=0: qcom_compressed not advertised)" : "");
    if (g_ubwc && !compressed)
        droiddeck_log("dmabuf", "the compositor's driver (%s) reports no importable qcom_compressed layout: "
                   "game swapchains stay linear", vkp_gpu_name());
    if (g_dmabuf_nfmt > DMABUF_NFMT_SDR) {
        /* The rows above always carry LINEAR; say what the compositor's own driver can really import
         * for 10-bit, since a gralloc buffer it cannot import is shown on the display layer only. */
        uint64_t got[DMABUF_NMOD];
        int n = vkp_dmabuf_modifiers(DRM_XBGR2101010, got, DMABUF_NMOD), ubwc10 = 0;
        for (int i = 0; i < n; i++) if (got[i] == VKP_MOD_QCOM_COMPRESSED) ubwc10 = 1;
        droiddeck_log("color", "10-bit dma-buf formats AB30/XB30 advertised for HDR10; the compositor's driver (%s) "
                   "imports XB30 %s", vkp_gpu_name(),
                   n == 0 ? "with no layout it reports (copy path unlikely; display layer only)"
                          : ubwc10 ? "linear and UBWC" : "linear only (UBWC 10-bit frames: display layer only)");
    }
    dmabuf_build_feedback();
}

/* ---- dmabuf feedback (zwp_linux_dmabuf_v1 version 4)
 *
 * Turnip's Vulkan WSI is happy with the version 3 format/modifier events, so every Vulkan game
 * worked while we only advertised 3. Mesa's EGL is not: its Wayland platform only takes the GPU
 * (kopper/Zink) path when it can bind this interface with FEEDBACK, and with 3 it silently drops
 * to its wl_shm software path. On this Proton layer that path has no software rasteriser to fall
 * back to (the gallium build is zink+kopper+swrast, no llvmpipe), so the shm buffer it commits is
 * never written: a native OpenGL window came out solid black, ~30 shm commits/s and no GPU frame
 * at all (Wizardry: The Labyrinth of Lost Souls). Feedback is what makes that path work.
 *
 * What a client needs from us is one tranche describing "everything the compositor can import":
 * a format table it mmaps read-only, the device to allocate on, and the indices it may use.
 * Clients binding versions 1-3 keep getting the old format/modifier events instead. */

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif
#ifndef MFD_ALLOW_SEALING
#define MFD_ALLOW_SEALING 0x0002U
#endif
#ifndef F_ADD_SEALS
#define F_ADD_SEALS 1033
#define F_SEAL_SEAL 0x0001
#define F_SEAL_SHRINK 0x0002
#define F_SEAL_GROW 0x0004
#define F_SEAL_WRITE 0x0008
#endif

struct dmabuf_fmt_entry { uint32_t format; uint32_t pad; uint64_t modifier; }; /* the wire layout */

static int g_fmt_table_fd = -1;         /* sealed read-only memfd of dmabuf_fmt_entry[] */
static size_t g_fmt_table_size;
static uint16_t g_fmt_table_n;          /* entries, == the indices a tranche may name */
dev_t g_main_device;             /* the render node clients should allocate on */

/* The GPU we import through is reached with KGSL, not DRM, so there is no render node of our own
 * to name. Clients only use main_device to match "the same device as the compositor" and to pick
 * a driver; the one DRM render node this platform has is the right answer, and Zink ignores it
 * anyway (it renders on the Vulkan device it already has). 0 if the platform has none. */
static dev_t dmabuf_render_node(void) {
    static const char *nodes[] = {"/dev/dri/renderD128", "/dev/dri/renderD129", "/dev/dri/card0"};
    struct stat st;
    if (g_no_render_node) return 0;
    for (size_t i = 0; i < sizeof(nodes) / sizeof(nodes[0]); i++)
        if (!stat(nodes[i], &st) && S_ISCHR(st.st_mode)) return st.st_rdev;
    return 0;
}

static void dmabuf_build_feedback(void) {
    struct dmabuf_fmt_entry entries[DMABUF_NFMT_MAX * DMABUF_NMOD];
    int n = 0;

    g_main_device = dmabuf_render_node();
    for (int f = 0; f < g_dmabuf_nfmt; f++)
        for (int m = 0; m < g_dmabuf_fmts[f].n; m++) {
            if (g_dmabuf_fmts[f].mods[m] == MOD_INVALID) continue; /* never offer INVALID here */
            entries[n].format = g_dmabuf_fmts[f].fmt;
            entries[n].pad = 0;
            entries[n].modifier = g_dmabuf_fmts[f].mods[m];
            n++;
        }
    if (!n) return;

    int fd = (int)syscall(__NR_memfd_create, "droiddeck-dmabuf-formats",
                          MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (fd < 0) { WLOGE("dmabuf feedback: memfd_create failed (%s)", strerror(errno)); return; }
    size_t size = (size_t)n * sizeof(entries[0]);
    if (write(fd, entries, size) != (ssize_t)size) {
        WLOGE("dmabuf feedback: could not write the format table (%s)", strerror(errno));
        close(fd);
        return;
    }
    /* The client mmaps this read-only and trusts it not to change under it. */
    fcntl(fd, F_ADD_SEALS, F_SEAL_SEAL | F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_WRITE);
    g_fmt_table_fd = fd;
    g_fmt_table_size = size;
    g_fmt_table_n = (uint16_t)n;
    droiddeck_log("dmabuf", "feedback ready: %d format/modifier pairs, main device %u:%u",
               n, (unsigned)major(g_main_device), (unsigned)minor(g_main_device));
    /* Vulkan games never need the node. Mesa's EGL did until Wayland layer versionCode 9: without
     * one it fell back to a software path that draws nothing here (black window, sound plays). */
    if (!g_main_device)
        droiddeck_log("dmabuf", "%s: OpenGL games need Wayland layer versionCode 9 or newer, "
                   "which runs OpenGL on the GPU without a DRM node; older layers show a black window",
                   g_no_render_node ? "no DRM device named (forced by DROIDDECK_WAYLAND_NO_RENDER_NODE=1)"
                                    : "this device gives apps no display (DRM) device (/dev/dri)");
}

/* One tranche: our device, every pair in the table, no scanout flag. */
static void dmabuf_feedback_send(struct wl_resource *fb) {
    struct wl_array dev, idx;
    uint16_t *ind;

    if (g_fmt_table_fd < 0) { zwp_linux_dmabuf_feedback_v1_send_done(fb); return; }

    zwp_linux_dmabuf_feedback_v1_send_format_table(fb, g_fmt_table_fd, (uint32_t)g_fmt_table_size);

    wl_array_init(&dev);
    memcpy(wl_array_add(&dev, sizeof(dev_t)), &g_main_device, sizeof(dev_t));
    zwp_linux_dmabuf_feedback_v1_send_main_device(fb, &dev);
    zwp_linux_dmabuf_feedback_v1_send_tranche_target_device(fb, &dev);
    wl_array_release(&dev);

    wl_array_init(&idx);
    ind = wl_array_add(&idx, (size_t)g_fmt_table_n * sizeof(uint16_t));
    if (ind) for (uint16_t i = 0; i < g_fmt_table_n; i++) ind[i] = i;
    zwp_linux_dmabuf_feedback_v1_send_tranche_formats(fb, &idx);
    wl_array_release(&idx);

    zwp_linux_dmabuf_feedback_v1_send_tranche_flags(fb, 0);
    zwp_linux_dmabuf_feedback_v1_send_tranche_done(fb);
    zwp_linux_dmabuf_feedback_v1_send_done(fb);
}

static void dmabuf_feedback_destroy(struct wl_client *c, struct wl_resource *r) {
    wl_resource_destroy(r);
}
static const struct zwp_linux_dmabuf_feedback_v1_interface dmabuf_feedback_impl = {
    .destroy = dmabuf_feedback_destroy,
};

static void dmabuf_new_feedback(struct wl_client *c, struct wl_resource *parent, uint32_t id) {
    struct wl_resource *fb = wl_resource_create(c, &zwp_linux_dmabuf_feedback_v1_interface,
                                                wl_resource_get_version(parent), id);
    if (!fb) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(fb, &dmabuf_feedback_impl, NULL, NULL);
    if (!g_dmabuf_fmts_ready) dmabuf_build_formats();
    dmabuf_feedback_send(fb);
}

static void dmabuf_get_default_feedback(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct client_info *ci = client_info_of(c);
    if (ci) ci->asked_feedback = 1;
    dmabuf_new_feedback(c, r, id);
}
/* Per-surface feedback would let us hint a different tranche for a window on its own display
 * layer; we have nothing better to say per surface, so it is the default one. */
static void dmabuf_get_surface_feedback(struct wl_client *c, struct wl_resource *r, uint32_t id,
                                        struct wl_resource *surface) {
    dmabuf_new_feedback(c, r, id);
}

void bind_dmabuf(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &zwp_linux_dmabuf_v1_interface, ver, id);
    wl_resource_set_implementation(r, &dmabuf_impl, NULL, NULL);
    if (!g_dmabuf_fmts_ready) dmabuf_build_formats();
    /* From version 4 the format and modifier events are gone: the client asks for feedback
     * instead, and sending both would only confuse it about which list is authoritative. */
    if (ver >= 4) return;
    for (int f = 0; f < g_dmabuf_nfmt; f++) {
        zwp_linux_dmabuf_v1_send_format(r, g_dmabuf_fmts[f].fmt);
        if (ver >= 3)
            for (int m = 0; m < g_dmabuf_fmts[f].n; m++)
                zwp_linux_dmabuf_v1_send_modifier(r, g_dmabuf_fmts[f].fmt,
                                                  (uint32_t)(g_dmabuf_fmts[f].mods[m] >> 32),
                                                  (uint32_t)(g_dmabuf_fmts[f].mods[m] & 0xffffffff));
    }
}
