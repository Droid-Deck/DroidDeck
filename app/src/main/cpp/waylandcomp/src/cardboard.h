#ifndef CARDBOARD_H
#define CARDBOARD_H
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

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

void vkp_cardboard_bind_device(VkDevice dev, const VkPhysicalDeviceMemoryProperties *memprops);
int vkp_cardboard_active(void);
int vkp_cardboard_pose_changed(void);
void vkp_cardboard_begin_frame(void);
VkImage vkp_cardboard_run(VkCommandBuffer cmd, VkImage src, int src_w, int src_h, int out_w, int out_h);

void cardboard_clamp(struct cardboard_params *p);
void cardboard_push_constants(const struct cardboard_params *p, const float pose[9],
                              int src_w, int src_h, int out_w, int out_h, float out[24]);
void cardboard_push_translation(const struct cardboard_params *p, const float position[3], float out[24]);

#endif
