#include "presenter_image.h"
#include <cassert>
#include <cstdio>
#include <initializer_list>

int main() {
    droiddeck::SharedEyeImage image(VK_FORMAT_R8G8B8A8_SRGB, 1728, 1080);
    const auto &info = image.info;
    // SteamVR's shared-eye creation trace on the Fold7: format 43, flags 8, usage 0x97, list 43/37.
    assert(info.format == 43 && info.flags == 8 && info.usage == 0x97);
    assert(info.extent.width == 1728 && info.extent.height == 1080 && info.extent.depth == 1);
    assert(info.samples == VK_SAMPLE_COUNT_1_BIT && info.mipLevels == 1 && info.arrayLayers == 1);
    assert(info.tiling == VK_IMAGE_TILING_OPTIMAL && info.sharingMode == VK_SHARING_MODE_EXCLUSIVE);
    const auto *external = static_cast<const VkExternalMemoryImageCreateInfo *>(info.pNext);
    assert(external->handleTypes == VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);
    const auto *list = static_cast<const VkImageFormatListCreateInfo *>(external->pNext);
    assert(list->viewFormatCount == 2 && list->pViewFormats[0] == 43 && list->pViewFormats[1] == 37);

    const VkSurfaceFormatKHR choices[] = {
        {VK_FORMAT_B8G8R8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR},
        {VK_FORMAT_R8G8B8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR},
        {VK_FORMAT_B8G8R8A8_SRGB, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR},
        {VK_FORMAT_R8G8B8A8_SRGB, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR},
    };
    VkSurfaceFormatKHR selected{};
    for (VkFormat eye : {VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_B8G8R8A8_SRGB}) {
        assert(droiddeck::EyeSurfaceFormat(eye, choices, 4, &selected));
        assert(droiddeck::SrgbEyeFormat(selected.format));
        assert(!droiddeck::EyeSurfaceFormat(eye, choices, 2, &selected));
    }
    for (VkFormat eye : {VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_UNORM}) {
        assert(droiddeck::EyeSurfaceFormat(eye, choices, 4, &selected));
        assert(!droiddeck::SrgbEyeFormat(selected.format));
        assert(!droiddeck::EyeSurfaceFormat(eye, choices + 2, 2, &selected));
    }
    assert(!droiddeck::EyeSurfaceFormat(VK_FORMAT_R8G8B8A8_SRGB, nullptr, 0, &selected));
    const VkSurfaceFormatKHR any{VK_FORMAT_UNDEFINED, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR};
    assert(droiddeck::EyeSurfaceFormat(VK_FORMAT_R8G8B8A8_SRGB, &any, 1, &selected));
    assert(selected.format == VK_FORMAT_B8G8R8A8_SRGB);
    std::puts("PASS: shared eye imports match SteamVR and preserve color encoding through presentation");
}
