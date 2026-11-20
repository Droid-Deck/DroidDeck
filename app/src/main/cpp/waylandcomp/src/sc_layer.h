#ifndef SC_LAYER_H
#define SC_LAYER_H
#include <stddef.h>
#include <stdint.h>
#include <android/hardware_buffer.h>

struct vkp_image;
struct vkp_draw;
struct droiddeck_color;

enum sc_layer_id { SC_LAYER_GAME = 0, SC_LAYER_OVERLAY = 1, SC_LAYER_COUNT = 2 };

int sc_layer_available(void);
#define SC_LAYER_AHB_USAGE_VENDOR_UBWC (1ULL << 28)
int sc_layer_ahb_fd(AHardwareBuffer *ahb, uint64_t need);

void sc_layer_probe_dmabuf_fd(int fd);

int sc_layer_can_tag_hdr(void);
void sc_layer_hdr_symbols(char *out, size_t size);


int sc_layer_present(struct vkp_image *src, int scene_w, int scene_h, const struct vkp_draw *draw,
                     const struct droiddeck_color *color);

int sc_layer_present_pass(const struct vkp_draw *draws, int n, int scene_w, int scene_h);

struct vkp_hdr_frame;
int sc_layer_present_hdr_scene(const struct vkp_draw *draws, int n, const struct vkp_hdr_frame *hf,
                               int scene_w, int scene_h, const struct droiddeck_color *color);

int sc_layer_present_ahb(AHardwareBuffer *ahb, int w, int h, int acquire_fd, void *token,
                         int scene_w, int scene_h, const struct vkp_draw *draw, const struct droiddeck_color *color, uint32_t ahb_format);

void sc_layer_set_frame_rate(float fps);

int sc_layer_overlay_affordable(void);

int sc_layer_queues(void);

int sc_layer_present_overlay(struct vkp_image *src, const int geo[8], int translucent);

void sc_layer_hide(void);
int sc_layer_base_black(int width, int height);
void sc_layer_hide_base(void);
void sc_layer_hide_overlay(void);

void sc_layer_window_gone(void);

void sc_layer_frame_timeline(int64_t present_ns, int64_t deadline_ns, int64_t period_ns);
void sc_layer_aim_take(unsigned *next, unsigned *later, unsigned *even);
unsigned sc_layer_frames_take(void);
unsigned sc_layer_drops_take(void);

#endif
