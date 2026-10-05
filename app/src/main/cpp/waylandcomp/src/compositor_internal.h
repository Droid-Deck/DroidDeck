/*
 * What compositor.c shares with the protocol modules split out of it (wl_dmabuf.c): the dma-buf
 * buffer records, the per-client bookkeeping and the few functions and globals both sides use.
 * Everything declared here is hidden: it is internal to libdroiddeckwayland, not part of its ABI.
 */
#pragma once
#include <stdint.h>
#include <sys/types.h>
#include <android/log.h>
#include <wayland-server.h>
#include "vk_present.h"

#define COMPOSITOR_INTERNAL __attribute__((visibility("hidden")))

#ifndef WLOGE
#define WLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DroidDeckWayland", __VA_ARGS__)
#endif

/* ------------------------------------------------------------------ dmabuf buffers */

#define FOURCC(a, b, c, d) \
    ((uint32_t)(a) | ((uint32_t)(b) << 8) | ((uint32_t)(c) << 16) | ((uint32_t)(d) << 24))
#define DRM_ARGB8888 FOURCC('A', 'R', '2', '4')
#define DRM_XRGB8888 FOURCC('X', 'R', '2', '4')
#define DRM_ABGR8888 FOURCC('A', 'B', '2', '4')
#define DRM_XBGR8888 FOURCC('X', 'B', '2', '4')
#define MOD_LINEAR VKP_MOD_LINEAR
#define MOD_INVALID VKP_MOD_INVALID
#define MAX_PLANES 4

struct dmabuf_params {
    int fd[MAX_PLANES];
    uint32_t offset[MAX_PLANES], stride[MAX_PLANES];
    uint64_t modifier[MAX_PLANES];
    int n_planes;
};
struct dmabuf_buffer {
    int fd[MAX_PLANES];
    uint32_t offset[MAX_PLANES], stride[MAX_PLANES];
    int n_planes;
    int32_t width, height;
    uint32_t format;
    uint64_t modifier;
    struct vkp_image *img;                  /* imported once, reused for every frame */
    int import_failed;
    /* One reference for the wl_buffer resource, one per surface showing the buffer. Mesa destroys
     * a swapchain's wl_buffers the moment the game rebuilds its swapchain, i.e. while the last
     * committed one is still what is on screen: the import (and the dma-buf memory it pins) stays
     * until the surface commits something newer, so the picture never blinks to black. */
    int refs;
    void *ahb_state;                        /* zero-copy: ahb_swapchain.c's record (the game's AHardwareBuffer) */
};

/* Which program each Wayland client is (from /proc/<pid>/cmdline), for the session log. */
struct client_info {
    struct wl_client *client;
    struct wl_listener destroy;
    pid_t pid;
    char name[64];
    /* An OpenGL program whose EGL gave up on the GPU: it asked for dma-buf feedback (EGL's
     * Wayland GPU path always does), never made a dma-buf buffer, and draws wl_shm frames. */
    unsigned asked_feedback : 1, shm_gl_said : 1;
    /* Clients that describe opaque regions can use alpha outside those regions. Wine windows
     * never describe them, and their unused alpha channel must not make them translucent. */
    unsigned declares_opaque : 1;
    unsigned dmabuf_buffers, shm_frames;
    struct client_info *next;
};

/* ---- compositor.c */
COMPOSITOR_INTERNAL struct client_info *client_info_of(struct wl_client *client);
COMPOSITOR_INTERNAL void dmabuf_buffer_unref(struct dmabuf_buffer *b);
COMPOSITOR_INTERNAL extern const struct wl_buffer_interface dbuf_buffer_impl;
extern volatile int g_ubwc;             /* DROIDDECK_WAYLAND_UBWC (waylandcomp_jni.c sets it) */
extern volatile int g_no_render_node;   /* DROIDDECK_WAYLAND_NO_RENDER_NODE */

/* ---- wl_dmabuf.c */
COMPOSITOR_INTERNAL extern dev_t g_main_device;  /* the render node clients are told to allocate on */
COMPOSITOR_INTERNAL void bind_dmabuf(struct wl_client *c, void *data, uint32_t ver, uint32_t id);
