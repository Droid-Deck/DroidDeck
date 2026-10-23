#pragma once
#include <vulkan/vulkan.h>

#ifdef __cplusplus
extern "C" {
#endif
void droiddeck_wsi_device(VkDevice device, VkPhysicalDevice physical, VkInstance instance,
                          PFN_vkGetInstanceProcAddr gipa, PFN_vkGetDeviceProcAddr gdpa,
                          const VkDeviceCreateInfo *create);
void droiddeck_wsi_destroy(VkDevice device);
void droiddeck_wsi_swapchain(VkDevice device, VkSwapchainKHR swapchain, const VkSwapchainCreateInfoKHR *create);
void droiddeck_wsi_forget(VkDevice device, VkSwapchainKHR swapchain);
VkResult droiddeck_wsi_present(VkDevice device, VkQueue queue, const VkPresentInfoKHR *present,
                               PFN_vkQueuePresentKHR next);
#ifdef __cplusplus
}
#endif
