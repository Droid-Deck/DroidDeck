#ifndef FRAMEGEN_BRIDGE_H
#define FRAMEGEN_BRIDGE_H

/**
 * The frame generation bridge (LSFG and DDFG).
 *
 * Both engines are Vulkan frame generators. The port keeps the API but
 * reports the bridge as inactive, so the compositor presents one frame per
 * guest frame.
 */

#include <stdint.h>

enum { VKP_FG_ENGINE_LSFG = 0, VKP_FG_ENGINE_DDFG = 1 };
#define VKP_FG_MAX_GENERATIONS 3

void vkp_framegen_set_engine(int kind);
void vkp_framegen_set_armed(int armed, int multiplier, int target_fps);
void vkp_framegen_set_lsfg_cache_path(const char *path);
void vkp_framegen_set_tuning(float flow_scale, float refresh_hz);
void vkp_framegen_set_ddfg_tuning(int model, int perf_preset);

/** The problem that stopped frame generation, or 0 when there is none. */
int vkp_framegen_problem(void);

/** Why frame generation is unavailable. */
const char *vkp_framegen_caps_reason(void);

/** Reads the frame generation counters. */
void vkp_framegen_stats(float out[6]);

/** Takes and resets the frame generation counters. */
unsigned vkp_framegen_stats_take(void);

const void *vkp_framegen_device_features(void *inst, void *gipa, void *pd);
void vkp_framegen_device_ready(void *dev, void *queue, uint32_t qfam, int features_enabled);
void vkp_framegen_device_lost(void);

/** True when frame generation is running. */
int vkp_framegen_active(void);

/** Extra images the generator wants. */
int vkp_framegen_extra_images(void);

int vkp_framegen_run(void *cmd, void *scene, void *scene_view, int w, int h, int fmt,
                     void *gens[VKP_FG_MAX_GENERATIONS]);

void vkp_framegen_presented(int generated);
int vkp_framegen_format_ok(int fmt);

#endif  // FRAMEGEN_BRIDGE_H
