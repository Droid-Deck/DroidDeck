#ifndef HDR_COMPOSE_H
#define HDR_COMPOSE_H
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#define HDRC_MAX_RECTS 6

enum hdrc_mode { HDRC_OUT_PQ = 0, HDRC_OUT_SDR = 1 };

struct hdrc_params {
    enum hdrc_mode mode;
    float sdr_white_nits;
    float peak_nits;
    int count;
    unsigned hdr_mask;
    float rects[HDRC_MAX_RECTS][4];
};

void hdrc_bind_device(VkDevice dev, const VkPhysicalDeviceMemoryProperties *memprops);
void hdrc_destroy(void);
void hdrc_forget_image(VkImage img);

int hdrc_encode(VkCommandBuffer cmd, VkImage mixed, VkImage out, VkFormat out_fmt, int w, int h,
                const struct hdrc_params *p);

#endif
