#ifndef SC_LAYER_H
#define SC_LAYER_H
#include <stddef.h>
#include <stdint.h>
#include <android/hardware_buffer.h>

struct vkp_image;
struct vkp_draw;
struct droiddeck_color;
struct ANativeWindow;
struct sc_target;
struct sc_target *sc_target_create(struct ANativeWindow *window, const char *name);
void sc_target_destroy(struct sc_target *target);
void sc_target_hide(struct sc_target *target);
int sc_target_present(struct sc_target *target, struct vkp_image *src, int width, int height,
                      const struct droiddeck_color *color, int translucent);
int sc_target_present_geometry(struct sc_target *target, struct vkp_image *src, const int geometry[8],
                               int translucent, int z, const struct droiddeck_color *color);
int sc_target_present_ahb(struct sc_target *target, AHardwareBuffer *buffer, int acquire_fd,
                          void *token, const int geometry[8], int translucent, int z,
                          const struct droiddeck_color *color);
int sc_target_batch_begin(void);
void sc_target_batch_end(void);
void sc_target_collect(void);
int sc_target_ready(struct sc_target *target);

enum sc_layer_id { SC_LAYER_GAME = 0, SC_LAYER_OVERLAY = 1, SC_LAYER_COUNT = 2 };

int sc_layer_available(void);
int sc_layer_ready(void);

void sc_layer_probe_dmabuf_fd(int fd);

int sc_layer_can_tag_hdr(void);
void sc_layer_hdr_symbols(char *out, size_t size);


int sc_layer_present(struct vkp_image *src, int scene_w, int scene_h, const struct droiddeck_color *color);

int sc_layer_present_pass(const struct vkp_draw *draws, int n, int scene_w, int scene_h);

struct vkp_hdr_frame;
int sc_layer_present_hdr_scene(const struct vkp_draw *draws, int n, const struct vkp_hdr_frame *hf,
                               int scene_w, int scene_h, const struct droiddeck_color *color);

int sc_layer_present_ahb(AHardwareBuffer *ahb, int w, int h, int acquire_fd, void *token,
                         int scene_w, int scene_h, const struct droiddeck_color *color, uint32_t ahb_format);

void sc_layer_set_frame_rate(float fps);

int sc_layer_overlay_affordable(void);

int sc_layer_present_overlay(struct vkp_image *src, const int geo[8], int translucent);

void sc_layer_hide(void);
int sc_layer_base_black(int width, int height);
void sc_layer_hide_base(void);
void sc_layer_hide_overlay(void);

void sc_layer_window_gone(void);

unsigned sc_layer_frames_take(void);
unsigned sc_layer_drops_take(void);

#endif
