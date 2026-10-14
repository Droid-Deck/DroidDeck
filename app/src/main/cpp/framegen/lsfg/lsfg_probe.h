#pragma once

#include <vulkan/vulkan.h>
#include <cstdint>
#include <vector>

#include "lsfg_dll.h"

struct VkTable;

namespace lsfg {

struct FeatureSupport {
    bool queried                     = false;
    bool apiAtLeast13                = false;
    bool vulkanMemoryModel           = false;
    bool vulkanMemoryModelDeviceScope= false;
    bool storageImageWriteWithoutFormat = false;
    bool storageImageExtendedFormats = false;
    uint32_t deviceApiVersion        = 0;

    bool     extensionPath   = false;
    bool     hasSpirv14        = false;
    bool     hasFloatControls  = false;
    bool     hasMemoryModelExt = false;
    uint32_t spirvTarget     = kSpirv16;

    bool deviceGatesPass() const {
        return queried && (apiAtLeast13 || extensionPath) && vulkanMemoryModel
            && storageImageWriteWithoutFormat && storageImageExtendedFormats;
    }
};

struct Caps {
    FeatureSupport features;
    bool featuresEnabled   = false;
    bool storageOnSwapchainFormat = false;
    bool linearBlitOnSwapchainFormat = false;
    VkFormat probedFormat  = VK_FORMAT_UNDEFINED;
    char reason[160]       = "not probed";

    bool supported() const {
        return featuresEnabled && features.deviceGatesPass() && storageOnSwapchainFormat;
    }
};

FeatureSupport queryFeatures(const VkTable& vk, VkPhysicalDevice pd,
                             const std::vector<VkExtensionProperties>& deviceExtensions,
                             bool allowVk11);

std::vector<const char*> extensionNames(const FeatureSupport& f);

bool probeStorageFormat(const VkTable& vk, VkPhysicalDevice pd, VkFormat fmt);

bool probeLinearBlit(const VkTable& vk, VkPhysicalDevice pd, VkFormat fmt);

void explain(Caps& caps);

}
