/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * X11 presentation, software path: the frame is read back and drawn with PutImage. Used only
 * when nothing better exists - under gamescope its WSI layer turns X11 swapchains into Wayland
 * ones before they reach this driver. libxcb (and libX11-xcb for Xlib surfaces) is opened at
 * run time.
 */
#define _GNU_SOURCE
#include "wsi.h"

#include <dlfcn.h>
#include <string.h>
#include <xcb/xcb.h>

static struct {
    void *lib, *x11xcb;
    xcb_get_geometry_cookie_t (*get_geometry)(xcb_connection_t *, xcb_drawable_t);
    xcb_get_geometry_reply_t *(*get_geometry_reply)(xcb_connection_t *, xcb_get_geometry_cookie_t, xcb_generic_error_t **);
    xcb_void_cookie_t (*put_image)(xcb_connection_t *, uint8_t, xcb_drawable_t, xcb_gcontext_t, uint16_t, uint16_t, int16_t, int16_t,
                                   uint8_t, uint8_t, uint32_t, const uint8_t *);
    xcb_void_cookie_t (*create_gc)(xcb_connection_t *, xcb_gcontext_t, xcb_drawable_t, uint32_t, const void *);
    xcb_void_cookie_t (*free_gc)(xcb_connection_t *, xcb_gcontext_t);
    uint32_t (*generate_id)(xcb_connection_t *);
    uint32_t (*get_maximum_request_length)(xcb_connection_t *);
    int (*flush)(xcb_connection_t *);
    int (*connection_has_error)(xcb_connection_t *);
    xcb_connection_t *(*xlib_get_xcb)(void *);
} x;

int vkb_xcb_load(void)
{
    static int state;
    static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
    pthread_mutex_lock(&lock);
    if (!state) {
        state = -1;
        x.lib = dlopen("libxcb.so.1", RTLD_NOW | RTLD_LOCAL);
        if (x.lib) {
#define L(f, n) (*(void **)&x.f = dlsym(x.lib, n))
            int ok = 1;
            ok &= !!L(get_geometry, "xcb_get_geometry");
            ok &= !!L(get_geometry_reply, "xcb_get_geometry_reply");
            ok &= !!L(put_image, "xcb_put_image");
            ok &= !!L(create_gc, "xcb_create_gc");
            ok &= !!L(free_gc, "xcb_free_gc");
            ok &= !!L(generate_id, "xcb_generate_id");
            ok &= !!L(get_maximum_request_length, "xcb_get_maximum_request_length");
            ok &= !!L(flush, "xcb_flush");
            ok &= !!L(connection_has_error, "xcb_connection_has_error");
#undef L
            if (ok) state = 1;
        }
        x.x11xcb = dlopen("libX11-xcb.so.1", RTLD_NOW | RTLD_LOCAL);
        if (x.x11xcb) *(void **)&x.xlib_get_xcb = dlsym(x.x11xcb, "XGetXCBConnection");
        if (state < 0) VKB_WARN("no usable libxcb: X11 presentation unavailable");
    }
    pthread_mutex_unlock(&lock);
    return state > 0;
}

void *vkb_xlib_to_xcb(void *dpy)
{
    if (!vkb_xcb_load() || !x.xlib_get_xcb) return NULL;
    return x.xlib_get_xcb(dpy);
}

static int geometry(vkb_surface *s, uint32_t *w, uint32_t *h, uint8_t *depth)
{
    xcb_get_geometry_reply_t *g = x.get_geometry_reply(s->xcb_conn, x.get_geometry(s->xcb_conn, s->xcb_window), NULL);
    if (!g) return 0;
    *w = g->width;
    *h = g->height;
    if (depth) *depth = g->depth;
    free(g);
    return 1;
}

VkResult vkb_xcb_surface_extent(vkb_surface *s, VkExtent2D *e)
{
    uint32_t w, h;
    if (!geometry(s, &w, &h, NULL)) return VK_ERROR_SURFACE_LOST_KHR;
    e->width = w;
    e->height = h;
    return VK_SUCCESS;
}

typedef struct x11_state {
    xcb_gcontext_t gc;
    uint8_t depth;
    uint8_t *swz;
} x11_state;

static VkResult x11_init(vkb_swapchain *sc)
{
    vkb_surface *s = sc->surface;
    x11_state *st = calloc(1, sizeof(*st));
    sc->bdata = st;
    uint32_t w, h;
    if (!geometry(s, &w, &h, &st->depth)) return VK_ERROR_SURFACE_LOST_KHR;
    if (st->depth != 24 && st->depth != 32) {
        VKB_ERR("X11: window depth %u is not supported by the software present path", st->depth);
        return VK_ERROR_SURFACE_LOST_KHR;
    }
    st->gc = x.generate_id(s->xcb_conn);
    x.create_gc(s->xcb_conn, st->gc, s->xcb_window, 0, NULL);
    VKB_INFO("X11: presenting %ux%u by PutImage (software path)", sc->info.imageExtent.width, sc->info.imageExtent.height);
    return VK_SUCCESS;
}

static VkResult x11_present(vkb_swapchain *sc, uint32_t index, const VkPresentRegionKHR *region)
{
    (void)index;
    (void)region;
    vkb_surface *s = sc->surface;
    x11_state *st = sc->bdata;
    if (x.connection_has_error(s->xcb_conn)) return VK_ERROR_SURFACE_LOST_KHR;
    uint32_t w = sc->info.imageExtent.width, h = sc->info.imageExtent.height;
    const uint8_t *src = sc->staging_ptr;
    int rgba = sc->info.imageFormat == VK_FORMAT_R8G8B8A8_UNORM || sc->info.imageFormat == VK_FORMAT_R8G8B8A8_SRGB;
    if (rgba) {
        if (!st->swz) st->swz = malloc((size_t)w * h * 4);
        for (size_t i = 0; i < (size_t)w * h; i++) {
            st->swz[i * 4 + 0] = src[i * 4 + 2];
            st->swz[i * 4 + 1] = src[i * 4 + 1];
            st->swz[i * 4 + 2] = src[i * 4 + 0];
            st->swz[i * 4 + 3] = src[i * 4 + 3];
        }
        src = st->swz;
    }
    /* Split into requests that fit the server's maximum request length (in 4-byte units). */
    size_t max = (size_t)x.get_maximum_request_length(s->xcb_conn) * 4;
    size_t row = (size_t)w * 4;
    uint32_t rows = max > 64 + row ? (uint32_t)((max - 64) / row) : 1;
    for (uint32_t y = 0; y < h; y += rows) {
        uint32_t n = h - y < rows ? h - y : rows;
        x.put_image(s->xcb_conn, XCB_IMAGE_FORMAT_Z_PIXMAP, s->xcb_window, st->gc, (uint16_t)w, (uint16_t)n, 0, (int16_t)y, 0, st->depth,
                    (uint32_t)(row * n), src + row * y);
    }
    x.flush(s->xcb_conn);
    uint32_t cw, ch;
    if (geometry(s, &cw, &ch, NULL) && (cw != w || ch != h)) sc->status = VK_SUBOPTIMAL_KHR;
    return VK_SUCCESS;
}

static VkResult x11_wait_free(vkb_swapchain *sc, uint64_t timeout_ns)
{
    (void)sc;
    (void)timeout_ns;
    return VK_TIMEOUT; /* images are free as soon as their present returns */
}

static void x11_destroy(vkb_swapchain *sc)
{
    x11_state *st = sc->bdata;
    if (!st) return;
    if (st->gc) x.free_gc(sc->surface->xcb_conn, st->gc);
    free(st->swz);
    free(st);
    sc->bdata = NULL;
}

const vkb_wsi_backend vkb_wsi_x11_backend = {x11_init, x11_present, x11_wait_free, x11_destroy};
