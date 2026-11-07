#ifndef CARDBOARD_H
#define CARDBOARD_H

/**
 * The cardboard VR presentation mode.
 *
 * Upstream renders the scene through a Vulkan pass with a lens distortion.
 * The port keeps the API and reports the mode as inactive.
 */

#include <stdint.h>

enum { CARDBOARD_SOURCE_SCREEN = 0, CARDBOARD_SOURCE_SBS = 1, CARDBOARD_SOURCE_FLAT = 2 };

struct cardboard_params {
    int enabled;
    int source;
    float ipd_m;
    float screen_dist_m;
    float screen_width_m;
    float k1, k2;
    float fov_deg;
    float lens_inset;
    float chroma;
    int mono;
    float display_hz;
};

void vkp_cardboard_set(const struct cardboard_params *p);
void vkp_cardboard_set_pose(const float m[9], int live);
void vkp_cardboard_set_spatial_pose(const float m[9], const float position[3], int live);
void vkp_cardboard_set_pose_file(const char *path);
void vkp_cardboard_set_render_height(int height);
void vkp_cardboard_bind_device(void *dev, const void *memprops);

/** True when the cardboard mode is running. */
int vkp_cardboard_active(void);

/** Which source the mode is rendering. */
int vkp_cardboard_source(void);

/** True when the headset pose changed since the last frame. */
int vkp_cardboard_pose_changed(void);

void vkp_cardboard_begin_frame(void);
void *vkp_cardboard_run(void *cmd, void *src, int src_w, int src_h, int out_w, int out_h);
void cardboard_clamp(struct cardboard_params *p);

#endif  // CARDBOARD_H
