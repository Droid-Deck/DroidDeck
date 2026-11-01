#ifndef GPU_BUSY_H
#define GPU_BUSY_H
#include "vk_loader.h"

// Times the compositor's own GPU work for the HUD (tools/gpuload/src/gpuload_ring.h); call after the device and its
// functions are loaded. Off until WaylandCompositor.nativeSetGpuTiming(true).
void vkp_gpu_busy_device(VkPhysicalDevice pd, VkDevice dev, uint32_t family);
void vkp_gpu_busy_device_lost(void);
#endif
