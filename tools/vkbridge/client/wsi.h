/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Window-system integration implemented by the bridge client: the server's GPU has no Linux
 * window systems, so swapchains are built here from ordinary images and handed to the
 * compositor as dma-bufs (Wayland, zwp_linux_dmabuf_v1) or copied out through shared memory
 * (Wayland wl_shm, X11 PutImage), or not shown at all (VK_EXT_headless_surface).
 */
#ifndef VKB_WSI_H
#define VKB_WSI_H

#include "vkb_client.h"

struct wl_display;
struct wl_surface;

enum vkb_surface_kind { VKB_SURF_WAYLAND, VKB_SURF_XCB, VKB_SURF_HEADLESS };

typedef struct vkb_surface {
    enum vkb_surface_kind kind;
    struct wl_display *wl_display;
    struct wl_surface *wl_surface;
    void *xcb_conn;      /* xcb_connection_t* */
    uint32_t xcb_window;
    struct vkb_wl_display_info *wl_info;   /* cached formats/modifiers per wl_display */
} vkb_surface;

typedef struct vkb_swapchain vkb_swapchain;

typedef struct vkb_swap_image {
    VkImage image;
    VkDeviceMemory memory;
    int busy;                 /* held by the compositor (or acquired by the app) */
    int acquired;
    void *backend;            /* backend per-image data (wl_buffer...) */
    uint32_t stride, offset;
    uint64_t modifier;
    int dmabuf_fd;
    vkb_swapchain *chain;
} vkb_swap_image;

typedef struct vkb_wsi_backend {
    /* Called after images exist; may fail (e.g. no dma-buf support) - the generic code then
     * retries with copy_present = 1. */
    VkResult (*init)(vkb_swapchain *sc);
    /* The image's rendering is complete (and, with copy_present, its pixels are in sc->staging). */
    VkResult (*present)(vkb_swapchain *sc, uint32_t index, const VkPresentRegionKHR *region);
    /* Wait until an image may be acquired (dispatching events); 0 timeout = poll. */
    VkResult (*wait_free)(vkb_swapchain *sc, uint64_t timeout_ns);
    void (*destroy)(vkb_swapchain *sc);
} vkb_wsi_backend;

struct vkb_swapchain {
    vkb_device *dev;
    vkb_surface *surface;
    VkSwapchainCreateInfoKHR info;
    VkFormat *view_formats;
    uint32_t view_format_count;
    uint32_t count;
    vkb_swap_image *images;
    const vkb_wsi_backend *backend;
    void *bdata;
    int dmabuf;               /* images are exported dma-bufs */
    int copy_present;         /* pixels are read back into `staging` at present */
    /* Read-back resources (copy_present). */
    VkBuffer staging;
    VkDeviceMemory staging_mem;
    uint8_t *staging_ptr;
    VkDeviceSize staging_size;
    VkCommandPool pool;
    uint32_t pool_family;
    VkCommandBuffer *copy_cbs;
    VkFence fence;
    VkResult status;          /* VK_ERROR_OUT_OF_DATE_KHR / SURFACE_LOST once broken */
    pthread_mutex_t lock;
    uint64_t presents;
    int retired;
};

/* Formats every surface offers. */
extern const VkSurfaceFormatKHR vkb_wsi_formats[];
extern const uint32_t vkb_wsi_format_count;

/* Wayland */
extern const vkb_wsi_backend vkb_wsi_wayland_backend;
int vkb_wl_load(void);
VkResult vkb_wl_surface_extent(vkb_surface *s, VkExtent2D *e);
/* X11 */
extern const vkb_wsi_backend vkb_wsi_x11_backend;
int vkb_xcb_load(void);
VkResult vkb_xcb_surface_extent(vkb_surface *s, VkExtent2D *e);
void *vkb_xlib_to_xcb(void *dpy);
/* Headless */
extern const vkb_wsi_backend vkb_wsi_headless_backend;

/* Helpers in wsi.c */
uint32_t vkb_drm_format(VkFormat f);
VkResult vkb_wsi_wait_rendering(vkb_swapchain *sc, VkQueue queue, uint32_t index, uint32_t nwait, const VkSemaphore *wait);

#endif
