/**
 * The HarmonyOS implementations of upstream's Android-only compositor
 * modules.
 *
 * Upstream renders the guest's frames through three Android-specific
 * paths: an AHardwareBuffer swap chain, a SurfaceControl display layer and
 * a set of Vulkan effects. None of those exist on HarmonyOS, so this file
 * provides them as "unavailable" implementations that keep the ported
 * compositor's control flow intact.
 *
 * The frames themselves reach the screen through the GLES path in
 * `render/vk_present.h`, which the compositor uses whenever the
 * zero-copy path is off.
 */

#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "ahb_swapchain.h"
#include "cardboard.h"
#include "compositor_internal.h"
#include "ddk_log.h"
#include "effects_chain.h"
#include "framegen_bridge.h"
#include "sc_layer.h"
#include "vk_present.h"

// --- The wlroots and AOSP extensions the ported compositor expects -------

/**
 * wlroots' patched Wayland adds this to cap the size of the buffers a
 * client may hand over. It only matters for the Android hardware buffer
 * path, which HarmonyOS does not have.
 */
void wl_display_set_default_max_buffer_size(struct wl_display *display, size_t max_buffer_size) {
  (void)display;
  (void)max_buffer_size;
}

/**
 * Upstream's wlegl extension, from wl_android_buffer.c. The extension is
 * about the Android EGL buffer, so there is nothing to set up here.
 */
COMPOSITOR_INTERNAL void droiddeck_android_wlegl_init(struct wl_display *display) {
  (void)display;
}

// --- sc_layer: the SurfaceControl display layer ---------------------------

int sc_layer_available(void) {
  // SurfaceControl does not exist on HarmonyOS, so the compositor keeps
  // using the GLES presentation path.
  return 0;
}

void sc_layer_probe_dmabuf_fd(int fd) {
  (void)fd;
}

int sc_layer_can_tag_hdr(void) {
  return 0;
}

void sc_layer_hdr_symbols(char *out, size_t size) {
  if (out == NULL || size == 0) {
    return;
  }
  const char *none = "none";
  size_t length = strlen(none);
  if (length >= size) {
    length = size - 1;
  }
  memcpy(out, none, length);
  out[length] = '\0';
}

int sc_layer_present(struct vkp_image *src, int scene_w, int scene_h,
                     const struct droiddeck_color *color) {
  (void)src;
  (void)scene_w;
  (void)scene_h;
  (void)color;
  return -1;
}

int sc_layer_present_pass(const struct vkp_draw *draws, int n, int scene_w, int scene_h) {
  (void)draws;
  (void)n;
  (void)scene_w;
  (void)scene_h;
  return -1;
}

int sc_layer_present_hdr_scene(const struct vkp_draw *draws, int n, const struct vkp_hdr_frame *hf,
                                int scene_w, int scene_h, const struct droiddeck_color *color) {
  (void)draws;
  (void)n;
  (void)hf;
  (void)scene_w;
  (void)scene_h;
  (void)color;
  return -1;
}

int sc_layer_present_ahb(struct AHardwareBuffer *ahb, int w, int h, int acquire_fd, void *token,
                         int scene_w, int scene_h, const struct droiddeck_color *color,
                         uint32_t ahb_format) {
  (void)ahb;
  (void)w;
  (void)h;
  (void)acquire_fd;
  (void)token;
  (void)scene_w;
  (void)scene_h;
  (void)color;
  (void)ahb_format;
  return -1;
}

void sc_layer_set_frame_rate(float fps) {
  (void)fps;
}

int sc_layer_overlay_affordable(void) {
  return 0;
}

int sc_layer_present_overlay(struct vkp_image *src, const int geo[8], int translucent) {
  (void)src;
  (void)geo;
  (void)translucent;
  return -1;
}

void sc_layer_hide(void) {
}

int sc_layer_base_black(int width, int height) {
  (void)width;
  (void)height;
  return -1;
}

void sc_layer_hide_base(void) {
}

void sc_layer_hide_overlay(void) {
}

void sc_layer_window_gone(void) {
}

unsigned sc_layer_frames_take(void) {
  return 0;
}

unsigned sc_layer_drops_take(void) {
  return 0;
}

// --- ahb_swapchain: the AHardwareBuffer swap chain ------------------------

static int g_advertised = 0;
static int g_mode = 0;
static unsigned g_frames = 0;

void ahb_swapchain_init(struct wl_display *display) {
  // The AHB protocol is not advertised: AHardwareBuffer does not exist on
  // HarmonyOS, so clients must use shared memory buffers.
  (void)display;
  g_advertised = 0;
}

void ahb_swapchain_set_mode(int on, int live) {
  (void)live;
  g_mode = on;
}

int ahb_swapchain_last_frame_age_ms(void) {
  return 0;
}

int ahb_swapchain_has_ahb(const struct dmabuf_buffer *b) {
  (void)b;
  return 0;
}

int ahb_swapchain_present(struct dmabuf_buffer *b, struct surface *s, int scene_w, int scene_h) {
  (void)b;
  (void)s;
  (void)scene_w;
  (void)scene_h;
  return -1;
}

int ahb_swapchain_defer_release(struct dmabuf_buffer *b, struct wl_resource *buffer,
                                struct surface *s, int paced) {
  (void)b;
  (void)buffer;
  (void)s;
  (void)paced;
  return 0;
}

void ahb_swapchain_surface_gone(struct surface *s) {
  (void)s;
}

unsigned ahb_swapchain_stats_take(void) {
  const unsigned frames = g_frames;
  g_frames = 0;
  return frames;
}

int ahb_swapchain_advertised(void) {
  return g_advertised;
}

uint32_t ahb_swapchain_ahb_format(const struct dmabuf_buffer *b) {
  (void)b;
  return 0;
}

void ahb_swapchain_layer_released(void *token, int release_fd) {
  (void)token;
  (void)release_fd;
}

// --- effects_chain: the Vulkan screen effects -----------------------------

void vkp_effects_bind_device(vkp_effects_device dev, vkp_effects_physical_device pd,
                             const vkp_effects_memory_properties *memprops) {
  (void)dev;
  (void)pd;
  (void)memprops;
}

int vkp_effects_sync(void) {
  return 0;
}

int vkp_effects_active(void) {
  return 0;
}

int vkp_effects_upscaling(void) {
  return 0;
}

int vkp_effects_postprocessing(void) {
  return 0;
}

vkp_effects_filter vkp_effects_blit_filter(void) {
  return 0;
}

vkp_effects_image vkp_effects_run(vkp_effects_command_buffer cmd, vkp_effects_image scene,
                                   int scene_w, int scene_h, int out_w, int out_h,
                                   int *res_w, int *res_h) {
  (void)cmd;
  (void)scene;
  (void)scene_w;
  (void)scene_h;
  (void)out_w;
  (void)out_h;
  if (res_w != NULL) {
    *res_w = 0;
  }
  if (res_h != NULL) {
    *res_h = 0;
  }
  return NULL;
}

void vkp_effects_destroy(void) {
}

void vkp_effects_forget_image(vkp_effects_image img) {
  (void)img;
}

void vkp_effects_set_formats(vkp_effects_format scene_fmt, vkp_effects_format target_fmt) {
  (void)scene_fmt;
  (void)target_fmt;
}

void vkp_effects_set_scaling(int mode) {
  (void)mode;
}

void vkp_effects_set_upscale_sharpness(int pct) {
  (void)pct;
}

void vkp_effects_set_cas(int enabled, int pct) {
  (void)enabled;
  (void)pct;
}

void vkp_effects_set_hdr(int enabled) {
  (void)enabled;
}

void vkp_effects_set_deband(int enabled, int pct) {
  (void)enabled;
  (void)pct;
}

void vkp_effects_set_screen(float brightness, float contrast, float gamma, float saturation,
                            int fxaa, int toon, int crt, int ntsc) {
  (void)brightness;
  (void)contrast;
  (void)gamma;
  (void)saturation;
  (void)fxaa;
  (void)toon;
  (void)crt;
  (void)ntsc;
}

void vkp_effects_set_look(const char *name) {
  (void)name;
}

// --- framegen_bridge: the Vulkan frame generators --------------------------

void vkp_framegen_set_engine(int kind) {
  (void)kind;
}

void vkp_framegen_set_armed(int armed, int multiplier, int target_fps) {
  (void)armed;
  (void)multiplier;
  (void)target_fps;
}

void vkp_framegen_set_lsfg_cache_path(const char *path) {
  (void)path;
}

void vkp_framegen_set_tuning(float flow_scale, float refresh_hz) {
  (void)flow_scale;
  (void)refresh_hz;
}

void vkp_framegen_set_ddfg_tuning(int model, int perf_preset) {
  (void)model;
  (void)perf_preset;
}

int vkp_framegen_problem(void) {
  // The generators need Vulkan, which HarmonyOS does not guarantee.
  return 1;
}

const char *vkp_framegen_caps_reason(void) {
  return "frame generation needs Vulkan, which this device does not provide";
}

void vkp_framegen_stats(float out[6]) {
  if (out == NULL) {
    return;
  }
  for (int index = 0; index < 6; ++index) {
    out[index] = 0.0f;
  }
}

unsigned vkp_framegen_stats_take(void) {
  return 0;
}

const void *vkp_framegen_device_features(void *inst, void *gipa, void *pd) {
  (void)inst;
  (void)gipa;
  (void)pd;
  return NULL;
}

void vkp_framegen_device_ready(void *dev, void *queue, uint32_t qfam, int features_enabled) {
  (void)dev;
  (void)queue;
  (void)qfam;
  (void)features_enabled;
}

void vkp_framegen_device_lost(void) {
}

int vkp_framegen_active(void) {
  return 0;
}

int vkp_framegen_extra_images(void) {
  return 0;
}

int vkp_framegen_run(void *cmd, void *scene, void *scene_view, int w, int h, int fmt,
                     void *gens[VKP_FG_MAX_GENERATIONS]) {
  (void)cmd;
  (void)scene;
  (void)scene_view;
  (void)w;
  (void)h;
  (void)fmt;
  (void)gens;
  return 0;
}

void vkp_framegen_presented(int generated) {
  (void)generated;
}

int vkp_framegen_format_ok(int fmt) {
  (void)fmt;
  return 0;
}

// --- cardboard: the VR presentation mode ---------------------------------

void vkp_cardboard_set(const struct cardboard_params *p) {
  (void)p;
}

void vkp_cardboard_set_pose(const float m[9], int live) {
  (void)m;
  (void)live;
}

void vkp_cardboard_set_spatial_pose(const float m[9], const float position[3], int live) {
  (void)m;
  (void)position;
  (void)live;
}

void vkp_cardboard_set_pose_file(const char *path) {
  (void)path;
}

void vkp_cardboard_set_render_height(int height) {
  (void)height;
}

void vkp_cardboard_bind_device(void *dev, const void *memprops) {
  (void)dev;
  (void)memprops;
}

int vkp_cardboard_active(void) {
  return 0;
}

int vkp_cardboard_source(void) {
  return CARDBOARD_SOURCE_SCREEN;
}

int vkp_cardboard_pose_changed(void) {
  return 0;
}

void vkp_cardboard_begin_frame(void) {
}

void *vkp_cardboard_run(void *cmd, void *src, int src_w, int src_h, int out_w, int out_h) {
  (void)cmd;
  (void)src;
  (void)src_w;
  (void)src_h;
  (void)out_w;
  (void)out_h;
  return NULL;
}

void cardboard_clamp(struct cardboard_params *p) {
  if (p == NULL) {
    return;
  }
  if (p->ipd_m < 0.0f) {
    p->ipd_m = 0.0f;
  }
  if (p->screen_dist_m < 0.0f) {
    p->screen_dist_m = 0.0f;
  }
  if (p->screen_width_m < 0.0f) {
    p->screen_width_m = 0.0f;
  }
  if (p->fov_deg < 0.0f) {
    p->fov_deg = 0.0f;
  }
  if (p->fov_deg > 180.0f) {
    p->fov_deg = 180.0f;
  }
}
