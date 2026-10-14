#ifndef EFFECTS_CHAIN_H
#define EFFECTS_CHAIN_H
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

void vkp_effects_bind_device(VkDevice dev, VkPhysicalDevice pd,
                             const VkPhysicalDeviceMemoryProperties *memprops);

int vkp_effects_sync(void);

int vkp_effects_active(void);

VkFilter vkp_effects_blit_filter(void);

VkImage vkp_effects_run(VkCommandBuffer cmd, VkImage scene, int scene_w, int scene_h,
                        int out_w, int out_h, int *res_w, int *res_h);

void vkp_effects_destroy(void);

void vkp_effects_forget_image(VkImage img);

void vkp_effects_set_formats(VkFormat scene_fmt, VkFormat target_fmt);

void vkp_effects_set_scaling(int mode);
void vkp_effects_set_upscale_sharpness(int pct);
void vkp_effects_set_cas(int enabled, int pct);
void vkp_effects_set_hdr(int enabled);
void vkp_effects_set_deband(int enabled, int pct);
void vkp_effects_set_screen(float brightness, float contrast, float gamma, float saturation,
                            int fxaa, int toon, int crt, int ntsc);
void vkp_effects_set_look(const char *name);

#endif
