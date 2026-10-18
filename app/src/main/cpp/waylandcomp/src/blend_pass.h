#ifndef BLEND_PASS_H
#define BLEND_PASS_H
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

void blendp_bind_device(VkDevice dev);
void blendp_forget_image(VkImage img);
void blendp_begin_frame(void);

int blendp_draw(VkCommandBuffer cmd, VkImage src, VkFormat src_fmt, int src_w, int src_h, int src_general,
                VkImage dst, VkFormat dst_fmt, int dst_w, int dst_h, const VkImageBlit *blit);

#endif
