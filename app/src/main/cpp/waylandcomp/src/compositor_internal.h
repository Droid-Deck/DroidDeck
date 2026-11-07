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
    struct vkp_image *img;
    int import_failed;
    int refs;
    void *ahb_state;
};

struct client_info {
    struct wl_client *client;
    struct wl_listener destroy;
    pid_t pid;
    char name[64];
    unsigned asked_feedback : 1, shm_gl_said : 1;
    unsigned declares_opaque : 1;
    unsigned dmabuf_buffers, shm_frames;
    struct client_info *next;
};

COMPOSITOR_INTERNAL struct client_info *client_info_of(struct wl_client *client);
COMPOSITOR_INTERNAL void dmabuf_buffer_unref(struct dmabuf_buffer *b);
COMPOSITOR_INTERNAL extern const struct wl_buffer_interface dbuf_buffer_impl;
extern volatile int g_ubwc;
extern volatile int g_no_render_node;


COMPOSITOR_INTERNAL extern dev_t g_main_device;
COMPOSITOR_INTERNAL void bind_dmabuf(struct wl_client *c, void *data, uint32_t ver, uint32_t id);

/* ---- wl_android_buffer.c */
COMPOSITOR_INTERNAL void droiddeck_android_wlegl_init(struct wl_display *display);
