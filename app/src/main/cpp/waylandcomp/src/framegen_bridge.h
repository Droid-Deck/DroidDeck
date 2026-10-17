#ifndef FRAMEGEN_BRIDGE_H
#define FRAMEGEN_BRIDGE_H
#include <stdint.h>
#ifndef VK_USE_PLATFORM_ANDROID_KHR
#define VK_USE_PLATFORM_ANDROID_KHR
#endif
#include <vulkan/vulkan.h>

enum { VKP_FG_ENGINE_LSFG = 0, VKP_FG_ENGINE_WINFG = 1 };
#define VKP_FG_MAX_GENERATIONS 3

void vkp_framegen_set_engine(int kind);
void vkp_framegen_set_armed(int armed, int multiplier, int target_fps);
void vkp_framegen_set_lsfg_cache_path(const char *path);
void vkp_framegen_set_tuning(float flow_scale, float refresh_hz);
void vkp_framegen_set_winfg_tuning(int model, int perf_preset);
int vkp_framegen_problem(void);
const char *vkp_framegen_caps_reason(void);
void vkp_framegen_stats(float out[6]);
unsigned vkp_framegen_stats_take(void);

const void *vkp_framegen_device_features(VkInstance inst, PFN_vkGetInstanceProcAddr gipa,
                                         VkPhysicalDevice pd);
void vkp_framegen_device_ready(VkDevice dev, VkQueue queue, uint32_t qfam, int features_enabled);
void vkp_framegen_device_lost(void);

int vkp_framegen_active(void);
int vkp_framegen_extra_images(void);
int vkp_framegen_run(VkCommandBuffer cmd, VkImage scene, VkImageView scene_view, int w, int h,
                     VkFormat fmt, VkImage gens[VKP_FG_MAX_GENERATIONS]);
void vkp_framegen_presented(int generated);
int vkp_framegen_format_ok(VkFormat fmt);

#endif
