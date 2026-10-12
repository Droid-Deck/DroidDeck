#ifndef CARDBOARD_H
#define CARDBOARD_H
/*
 * Cardboard mode: the last stage of the compositor pass. The finished frame (after the effects and
 * frame generation) is drawn twice, once per eye, side by side on the panel, barrel-distorted for a
 * phone-in-a-viewer lens (cardboard.frag). The frame either hangs as a flat virtual screen in front
 * of the viewer, world-locked by the phone's rotation sensor (3DoF), or is already a side-by-side
 * stereo pair (SteamVR, SBS 3D content) that only gets the lens treatment.
 *
 * Present-pass order with Cardboard on (vk_present.c, render_impl):
 *     scene -> effects -> frame generation -> vkp_cardboard_run (whole panel) -> 1:1 blit -> present
 * The Fullscreen Mode mapping does not apply: the pass fills the output itself.
 *
 * Settings and the head pose come from any thread (JNI, the sensor thread); the compositor thread
 * snapshots them per frame. A new pose redraws even a static scene (vkp_cardboard_pose_changed), so
 * the virtual screen stays put while the head turns.
 */
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

enum { CARDBOARD_SOURCE_SCREEN = 0, CARDBOARD_SOURCE_SBS = 1 };

struct cardboard_params {
    int enabled;
    int source;          /* CARDBOARD_SOURCE_* */
    float ipd_m;         /* distance between the eyes, metres */
    float screen_dist_m; /* virtual screen distance, metres */
    float screen_width_m;/* virtual screen width, metres */
    float k1, k2;        /* lens distortion coefficients */
    float fov_deg;       /* vertical field of view of one eye before distortion */
    float lens_inset;    /* lens centre inset towards the nose, fraction of an eye viewport */
    float chroma;        /* chromatic aberration pre-correction (0 = off) */
};

/* Any thread. Values are clamped to sane ranges. */
void vkp_cardboard_set(const struct cardboard_params *p);
/* Any thread: the head -> world rotation, row-major 3x3. Identity = looking straight at the screen.
 * live = a sensor produced it. Also published to the guest (headpose_shm.h) once a file is set. */
void vkp_cardboard_set_pose(const float m[9], int live);
/* Any thread, once: where the guest's copy of the pose lives (<files>/.wayland-rt/droiddeck-headpose). */
void vkp_cardboard_set_pose_file(const char *path);

/* Compositor thread. */
void vkp_cardboard_bind_device(VkDevice dev, const VkPhysicalDeviceMemoryProperties *memprops);
/* 1 while Cardboard mode is on (the frame must take the compositor pass). */
int vkp_cardboard_active(void);
/* 1 when the settings or the pose changed since the last call: the caller redraws. */
int vkp_cardboard_pose_changed(void);
/* Called once per frame before any vkp_cardboard_run: releases the previous frame's source views
 * (that frame is fenced by now). */
void vkp_cardboard_begin_frame(void);
/* Records the pass into cmd: src (src_w x src_h, R8G8B8A8, in GENERAL) -> a panel-sized image
 * (out_w x out_h), returned in GENERAL. VK_NULL_HANDLE when the pass is unavailable (the caller
 * presents the frame the usual way). */
VkImage vkp_cardboard_run(VkCommandBuffer cmd, VkImage src, int src_w, int src_h, int out_w, int out_h);

/* Pure helpers, shared with the host test (tools/tests). */
void cardboard_clamp(struct cardboard_params *p);
/* The 24 floats after the quad's ndc in the shader's push constants. */
void cardboard_push_constants(const struct cardboard_params *p, const float pose[9],
                              int src_w, int src_h, int out_w, int out_h, float out[24]);

#endif
