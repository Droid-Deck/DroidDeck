#pragma once
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>
#include <cstring>

namespace ddfg {

static inline void* dispatch_key(void* handle) { return *reinterpret_cast<void**>(handle); }

struct InstanceDispatch {
    PFN_vkGetInstanceProcAddr          GetInstanceProcAddr = nullptr;
    PFN_vkDestroyInstance              DestroyInstance = nullptr;
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties = nullptr;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties GetPhysicalDeviceQueueFamilyProperties = nullptr;
    PFN_vkGetPhysicalDeviceProperties  GetPhysicalDeviceProperties = nullptr;
    PFN_vkGetPhysicalDeviceProperties2 GetPhysicalDeviceProperties2 = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR GetPhysicalDeviceSurfaceCapabilitiesKHR = nullptr;
};

struct DeviceDispatch {
    PFN_vkGetDeviceProcAddr            GetDeviceProcAddr = nullptr;
    PFN_vkDestroyDevice                DestroyDevice = nullptr;
    PFN_vkGetDeviceQueue               GetDeviceQueue = nullptr;
    PFN_vkQueueSubmit                  QueueSubmit = nullptr;
    PFN_vkQueueSubmit2                 QueueSubmit2 = nullptr;
    PFN_vkQueueSubmit2KHR              QueueSubmit2KHR = nullptr;
    PFN_vkQueueWaitIdle                QueueWaitIdle = nullptr;
    PFN_vkDeviceWaitIdle               DeviceWaitIdle = nullptr;

    PFN_vkCreateSwapchainKHR           CreateSwapchainKHR = nullptr;
    PFN_vkDestroySwapchainKHR          DestroySwapchainKHR = nullptr;
    PFN_vkGetSwapchainImagesKHR        GetSwapchainImagesKHR = nullptr;
    PFN_vkAcquireNextImageKHR          AcquireNextImageKHR = nullptr;
    PFN_vkAcquireNextImage2KHR         AcquireNextImage2KHR = nullptr;
    PFN_vkQueuePresentKHR              QueuePresentKHR = nullptr;

    PFN_vkCreateImage                  CreateImage = nullptr;
    PFN_vkDestroyImage                 DestroyImage = nullptr;
    PFN_vkCreateImageView              CreateImageView = nullptr;
    PFN_vkDestroyImageView             DestroyImageView = nullptr;
    PFN_vkAllocateMemory               AllocateMemory = nullptr;
    PFN_vkFreeMemory                   FreeMemory = nullptr;
    PFN_vkBindImageMemory              BindImageMemory = nullptr;
    PFN_vkGetImageMemoryRequirements   GetImageMemoryRequirements = nullptr;
    PFN_vkCreateBuffer                 CreateBuffer = nullptr;
    PFN_vkDestroyBuffer                DestroyBuffer = nullptr;
    PFN_vkGetBufferMemoryRequirements  GetBufferMemoryRequirements = nullptr;
    PFN_vkBindBufferMemory             BindBufferMemory = nullptr;
    PFN_vkMapMemory                    MapMemory = nullptr;
    PFN_vkUnmapMemory                  UnmapMemory = nullptr;
    PFN_vkCreateSampler                CreateSampler = nullptr;
    PFN_vkDestroySampler               DestroySampler = nullptr;

    PFN_vkCreateShaderModule           CreateShaderModule = nullptr;
    PFN_vkDestroyShaderModule          DestroyShaderModule = nullptr;
    PFN_vkCreateDescriptorSetLayout    CreateDescriptorSetLayout = nullptr;
    PFN_vkDestroyDescriptorSetLayout   DestroyDescriptorSetLayout = nullptr;
    PFN_vkCreatePipelineLayout         CreatePipelineLayout = nullptr;
    PFN_vkDestroyPipelineLayout        DestroyPipelineLayout = nullptr;
    PFN_vkCreateComputePipelines       CreateComputePipelines = nullptr;
    PFN_vkDestroyPipeline              DestroyPipeline = nullptr;
    PFN_vkCreateDescriptorPool         CreateDescriptorPool = nullptr;
    PFN_vkDestroyDescriptorPool        DestroyDescriptorPool = nullptr;
    PFN_vkResetDescriptorPool          ResetDescriptorPool = nullptr;
    PFN_vkAllocateDescriptorSets       AllocateDescriptorSets = nullptr;
    PFN_vkUpdateDescriptorSets         UpdateDescriptorSets = nullptr;

    PFN_vkCreateCommandPool            CreateCommandPool = nullptr;
    PFN_vkDestroyCommandPool           DestroyCommandPool = nullptr;
    PFN_vkAllocateCommandBuffers       AllocateCommandBuffers = nullptr;
    PFN_vkFreeCommandBuffers           FreeCommandBuffers = nullptr;
    PFN_vkBeginCommandBuffer           BeginCommandBuffer = nullptr;
    PFN_vkEndCommandBuffer             EndCommandBuffer = nullptr;
    PFN_vkCmdBindPipeline              CmdBindPipeline = nullptr;
    PFN_vkCmdBindDescriptorSets        CmdBindDescriptorSets = nullptr;
    PFN_vkCmdDispatch                  CmdDispatch = nullptr;
    PFN_vkCmdPipelineBarrier           CmdPipelineBarrier = nullptr;
    PFN_vkCmdCopyImage                 CmdCopyImage = nullptr;
    PFN_vkCmdCopyImageToBuffer         CmdCopyImageToBuffer = nullptr;
    PFN_vkCmdBlitImage                 CmdBlitImage = nullptr;
    PFN_vkCmdClearColorImage           CmdClearColorImage = nullptr;
    PFN_vkCreateFence                  CreateFence = nullptr;
    PFN_vkDestroyFence                 DestroyFence = nullptr;
    PFN_vkWaitForFences                WaitForFences = nullptr;
    PFN_vkResetFences                  ResetFences = nullptr;
    PFN_vkCreateSemaphore              CreateSemaphore = nullptr;
    PFN_vkDestroySemaphore             DestroySemaphore = nullptr;
};

template <typename PFN>
static inline void load(PFN& slot, PFN_vkGetDeviceProcAddr gdpa, VkDevice dev, const char* name) {
    slot = reinterpret_cast<PFN>(gdpa(dev, name));
}

}
