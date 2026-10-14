#pragma once

#include <vulkan/vulkan.h>

struct VkTable;

struct LsfgVkDispatch {
    PFN_vkAllocateDescriptorSets      AllocateDescriptorSets      = nullptr;
    PFN_vkAllocateMemory              AllocateMemory              = nullptr;
    PFN_vkBindBufferMemory            BindBufferMemory            = nullptr;
    PFN_vkBindImageMemory             BindImageMemory             = nullptr;
    PFN_vkCmdBindDescriptorSets       CmdBindDescriptorSets       = nullptr;
    PFN_vkCmdBindPipeline             CmdBindPipeline             = nullptr;
    PFN_vkCmdCopyImage                CmdCopyImage                = nullptr;
    PFN_vkCmdDispatch                 CmdDispatch                 = nullptr;
    PFN_vkCmdPipelineBarrier          CmdPipelineBarrier          = nullptr;
    PFN_vkCreateBuffer                CreateBuffer                = nullptr;
    PFN_vkCreateComputePipelines      CreateComputePipelines      = nullptr;
    PFN_vkCreateDescriptorPool        CreateDescriptorPool        = nullptr;
    PFN_vkCreateDescriptorSetLayout   CreateDescriptorSetLayout   = nullptr;
    PFN_vkCreateImage                 CreateImage                 = nullptr;
    PFN_vkCreateImageView             CreateImageView             = nullptr;
    PFN_vkCreatePipelineLayout        CreatePipelineLayout        = nullptr;
    PFN_vkCreateSampler               CreateSampler               = nullptr;
    PFN_vkCreateShaderModule          CreateShaderModule          = nullptr;
    PFN_vkDestroyBuffer               DestroyBuffer               = nullptr;
    PFN_vkDestroyDescriptorPool       DestroyDescriptorPool       = nullptr;
    PFN_vkDestroyDescriptorSetLayout  DestroyDescriptorSetLayout  = nullptr;
    PFN_vkDestroyImage                DestroyImage                = nullptr;
    PFN_vkDestroyImageView            DestroyImageView            = nullptr;
    PFN_vkDestroyPipeline             DestroyPipeline             = nullptr;
    PFN_vkDestroyPipelineLayout       DestroyPipelineLayout       = nullptr;
    PFN_vkDestroySampler              DestroySampler              = nullptr;
    PFN_vkDestroyShaderModule         DestroyShaderModule         = nullptr;
    PFN_vkFreeMemory                  FreeMemory                  = nullptr;
    PFN_vkGetBufferMemoryRequirements GetBufferMemoryRequirements = nullptr;
    PFN_vkGetImageMemoryRequirements  GetImageMemoryRequirements  = nullptr;
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties = nullptr;
    PFN_vkMapMemory                   MapMemory                   = nullptr;
    PFN_vkUnmapMemory                 UnmapMemory                 = nullptr;
    PFN_vkUpdateDescriptorSets        UpdateDescriptorSets        = nullptr;
};

extern LsfgVkDispatch vkd;

bool lsfgVkdInit(const VkTable& table);
bool lsfgVkdReady();
