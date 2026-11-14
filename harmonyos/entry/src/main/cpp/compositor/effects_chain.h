#ifndef EFFECTS_CHAIN_H
#define EFFECTS_CHAIN_H

/**
 * The screen effects chain (upscaling, CAS, debanding, HDR tonemapping).
 *
 * Upstream runs these as Vulkan passes. The port keeps the API but not the
 * Vulkan types, and reports the effects as inactive, so the compositor
 * presents the scene directly.
 */

#include <stdint.h>

/** The Vulkan handles the upstream chain takes, as opaque pointers here. */
typedef void *vkp_effects_device;
typedef void *vkp_effects_physical_device;
typedef void *vkp_effects_memory_properties;
typedef void *vkp_effects_command_buffer;
typedef void *vkp_effects_image;
typedef int vkp_effects_format;
typedef int vkp_effects_filter;

void vkp_effects_bind_device(vkp_effects_device dev, vkp_effects_physical_device pd,
                             const vkp_effects_memory_properties *memprops);

/** Waits for the effect chain to settle. */
int vkp_effects_sync(void);

/** True when any effect is active. */
int vkp_effects_active(void);

/** True when upscaling is active. */
int vkp_effects_upscaling(void);

/** True when post processing is active. */
int vkp_effects_postprocessing(void);

vkp_effects_filter vkp_effects_blit_filter(void);

vkp_effects_image vkp_effects_run(vkp_effects_command_buffer cmd, vkp_effects_image scene,
                                   int scene_w, int scene_h, int out_w, int out_h,
                                   int *res_w, int *res_h);

void vkp_effects_destroy(void);
void vkp_effects_forget_image(vkp_effects_image img);
void vkp_effects_set_formats(vkp_effects_format scene_fmt, vkp_effects_format target_fmt);
void vkp_effects_set_scaling(int mode);
void vkp_effects_set_upscale_sharpness(int pct);
void vkp_effects_set_cas(int enabled, int pct);
void vkp_effects_set_hdr(int enabled);
void vkp_effects_set_deband(int enabled, int pct);
void vkp_effects_set_screen(float brightness, float contrast, float gamma, float saturation,
                            int fxaa, int toon, int crt, int ntsc);
void vkp_effects_set_look(const char *name);

#endif  // EFFECTS_CHAIN_H
