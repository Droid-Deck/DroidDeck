#ifndef AHB_SWAPCHAIN_H
#define AHB_SWAPCHAIN_H

/**
 * The AHardwareBuffer swap chain.
 *
 * AHardwareBuffer is an AOSP gralloc type with no HarmonyOS counterpart,
 * so the port cannot present through it. The swap chain therefore stays
 * unadvertised, and the guest falls back to shared memory buffers.
 *
 * The dma-buf bookkeeping around it is still needed, so the API is kept
 * exactly as upstream has it.
 */

#include <stddef.h>
#include <stdint.h>
#include <wayland-server.h>

struct dmabuf_buffer;
struct surface;
struct droiddeck_color;

/** Sets the swap chain up on the display. */
void ahb_swapchain_init(struct wl_display *display);

/** Turns the swap chain on or off. */
void ahb_swapchain_set_mode(int on, int live);

/** Age of the last frame in milliseconds. */
int ahb_swapchain_last_frame_age_ms(void);

/** True when the buffer carries an AHardwareBuffer. */
int ahb_swapchain_has_ahb(const struct dmabuf_buffer *b);

/** Presents the buffer through the swap chain. Returns 0 on success. */
int ahb_swapchain_present(struct dmabuf_buffer *b, struct surface *s, int scene_w, int scene_h);

/** Defers the buffer release. */
int ahb_swapchain_defer_release(struct dmabuf_buffer *b, struct wl_resource *buffer,
                                struct surface *s, int paced);

/** Called when the surface goes away. */
void ahb_swapchain_surface_gone(struct surface *s);

/** Takes and resets the swap chain counters. */
unsigned ahb_swapchain_stats_take(void);

/** True when the swap chain is advertised to clients. */
int ahb_swapchain_advertised(void);

/** The AHardwareBuffer format of the buffer. */
uint32_t ahb_swapchain_ahb_format(const struct dmabuf_buffer *b);

/** Called when the display layer released the buffer. */
void ahb_swapchain_layer_released(void *token, int release_fd);

struct dmabuf_buffer *droiddeck_dmabuf_from_resource(struct wl_resource *buffer);
int droiddeck_dmabuf_fd(const struct dmabuf_buffer *b);
void droiddeck_dmabuf_size(const struct dmabuf_buffer *b, int *w, int *h);
void **droiddeck_dmabuf_ahb_slot(struct dmabuf_buffer *b);
void droiddeck_dmabuf_ref(struct dmabuf_buffer *b);
void droiddeck_dmabuf_unref(struct dmabuf_buffer *b);
void droiddeck_release_buffer(struct surface *s, struct wl_resource *buffer, int paced,
                              int64_t since_ns);
void droiddeck_request_redraw(void);
void droiddeck_surface_describe(const struct surface *s, char *out, size_t size);
const struct droiddeck_color *droiddeck_surface_color(const struct surface *s);

#endif  // AHB_SWAPCHAIN_H
