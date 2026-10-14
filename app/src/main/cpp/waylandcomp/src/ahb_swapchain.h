#ifndef AHB_SWAPCHAIN_H
#define AHB_SWAPCHAIN_H
#include <stddef.h>
#include <stdint.h>
#include <wayland-server.h>

struct dmabuf_buffer;
struct surface;

void ahb_swapchain_init(struct wl_display *display);
void ahb_swapchain_set_mode(int on, int live);
int ahb_swapchain_last_frame_age_ms(void);
int ahb_swapchain_has_ahb(const struct dmabuf_buffer *b);
int ahb_swapchain_present(struct dmabuf_buffer *b, struct surface *s, int scene_w, int scene_h);
int ahb_swapchain_defer_release(struct dmabuf_buffer *b, struct wl_resource *buffer, struct surface *s, int paced);
void ahb_swapchain_surface_gone(struct surface *s);
unsigned ahb_swapchain_stats_take(void);
int ahb_swapchain_advertised(void);
uint32_t ahb_swapchain_ahb_format(const struct dmabuf_buffer *b);

void ahb_swapchain_layer_released(void *token, int release_fd);

struct dmabuf_buffer *droiddeck_dmabuf_from_resource(struct wl_resource *buffer);
int droiddeck_dmabuf_fd(const struct dmabuf_buffer *b);
void droiddeck_dmabuf_size(const struct dmabuf_buffer *b, int *w, int *h);
void **droiddeck_dmabuf_ahb_slot(struct dmabuf_buffer *b);
void droiddeck_dmabuf_ref(struct dmabuf_buffer *b);
void droiddeck_dmabuf_unref(struct dmabuf_buffer *b);
void droiddeck_release_buffer(struct surface *s, struct wl_resource *buffer, int paced, int64_t since_ns);
void droiddeck_request_redraw(void);
void droiddeck_surface_describe(const struct surface *s, char *out, size_t size);
struct droiddeck_color;
const struct droiddeck_color *droiddeck_surface_color(const struct surface *s);

#endif
