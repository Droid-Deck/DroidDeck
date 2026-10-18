#pragma once
#include <vulkan/vulkan_core.h>

namespace droiddeck {

inline VkFormat AlternateEyeFormat(VkFormat format) {
    switch (format) {
    case VK_FORMAT_R8G8B8A8_SRGB: return VK_FORMAT_R8G8B8A8_UNORM;
    case VK_FORMAT_R8G8B8A8_UNORM: return VK_FORMAT_R8G8B8A8_SRGB;
    case VK_FORMAT_B8G8R8A8_SRGB: return VK_FORMAT_B8G8R8A8_UNORM;
    case VK_FORMAT_B8G8R8A8_UNORM: return VK_FORMAT_B8G8R8A8_SRGB;
    default: return format;
    }
}

inline bool SrgbEyeFormat(VkFormat format) {
    return format == VK_FORMAT_R8G8B8A8_SRGB || format == VK_FORMAT_B8G8R8A8_SRGB;
}

struct SharedEyeImage {
    VkFormat formats[2];
    VkImageFormatListCreateInfo list;
    VkExternalMemoryImageCreateInfo external;
    VkImageCreateInfo info;

    SharedEyeImage(VkFormat format, uint32_t width, uint32_t height)
        : formats{format, AlternateEyeFormat(format)},
          list{VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO, nullptr, formats[0] == formats[1] ? 1u : 2u, formats},
          external{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO, &list, VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT},
          info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, &external, VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT, VK_IMAGE_TYPE_2D,
               format, {width, height, 1}, 1, 1, VK_SAMPLE_COUNT_1_BIT, VK_IMAGE_TILING_OPTIMAL,
               VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                   VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT,
               VK_SHARING_MODE_EXCLUSIVE, 0, nullptr, VK_IMAGE_LAYOUT_UNDEFINED} {}
    SharedEyeImage(const SharedEyeImage &) = delete;
    SharedEyeImage &operator=(const SharedEyeImage &) = delete;
};

inline bool EyeSurfaceFormat(VkFormat eye, const VkSurfaceFormatKHR *formats, uint32_t count, VkSurfaceFormatKHR *out) {
    const bool srgb = SrgbEyeFormat(eye);
    if (count == 1 && formats[0].format == VK_FORMAT_UNDEFINED) {
        *out = {srgb ? VK_FORMAT_B8G8R8A8_SRGB : VK_FORMAT_B8G8R8A8_UNORM, formats[0].colorSpace};
        return out->colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    }
    for (uint32_t i = 0; i < count; i++) {
        const auto &f = formats[i];
        if (f.colorSpace != VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) continue;
        if (srgb ? SrgbEyeFormat(f.format) :
                   f.format == VK_FORMAT_R8G8B8A8_UNORM || f.format == VK_FORMAT_B8G8R8A8_UNORM) {
            *out = f;
            return true;
        }
    }
    return false;
}

}
