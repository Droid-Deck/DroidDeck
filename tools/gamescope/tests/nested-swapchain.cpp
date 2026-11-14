#include "nested_swapchain.hpp"
#include "nested_surface_format.hpp"
#include "dmabuf_import.hpp"
#include "sdl_refresh.hpp"
#include <cassert>
#include <cstdio>

int main()
{
    using gamescope::ChooseSDLRefresh;
    constexpr int configured60 = 60'000;
    constexpr int desktop120 = 120'000;
    auto capped = ChooseSDLRefresh(configured60, desktop120);
    assert(capped.configured && capped.refresh == configured60);

    auto discovered = ChooseSDLRefresh(0, desktop120);
    assert(!discovered.configured && discovered.refresh == desktop120);

    auto configured120 = ChooseSDLRefresh(120'000, 60'000);
    assert(configured120.configured && configured120.refresh == 120'000);

    VkSurfaceCapabilitiesKHR caps = {};
    caps.minImageCount = 2;
    caps.maxImageCount = 3;
    caps.currentExtent = {UINT32_MAX, UINT32_MAX};
    caps.minImageExtent = {16, 16};
    caps.maxImageExtent = {4096, 4096};
    caps.supportedCompositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    caps.supportedUsageFlags = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT |
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    gamescope::NestedSwapchainConfig config;

    // Existing desktop drivers retain direct composition into mutable swapchains.
    assert(gamescope::ChooseNestedSwapchainConfig(caps, true, 1280, 720, config));
    assert(!config.copy && config.imageCount == 3 && config.extent.width == 1280);

    // No mutable formats: compose offscreen and request only a copy destination.
    assert(gamescope::ChooseNestedSwapchainConfig(caps, false, 1280, 720, config));
    assert(config.copy && config.usage == VK_IMAGE_USAGE_TRANSFER_DST_BIT);

    // The Android bridge exposes transfer usage and inherited alpha, but no storage.
    caps.supportedUsageFlags &= ~VK_IMAGE_USAGE_STORAGE_BIT;
    caps.supportedCompositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    caps.maxImageCount = 2;
    caps.currentExtent = {1602, 720};
    assert(gamescope::ChooseNestedSwapchainConfig(caps, true, 1920, 1080, config));
    assert(config.copy && config.alpha == VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR);
    assert(config.imageCount == 2 && config.extent.width == 1602);

    // Fail rather than submitting unsupported image usage or alpha.
    caps.supportedUsageFlags &= ~VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    assert(!gamescope::ChooseNestedSwapchainConfig(caps, true, 1280, 720, config));
    caps.supportedUsageFlags |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    caps.supportedCompositeAlpha = 0;
    assert(!gamescope::ChooseNestedSwapchainConfig(caps, false, 1280, 720, config));
    caps.supportedCompositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;

    caps.currentExtent = {0, 0};
    assert(!gamescope::ChooseNestedSwapchainConfig(caps, false, 1280, 720, config));
    caps.currentExtent = {UINT32_MAX, UINT32_MAX};
    assert(gamescope::ChooseNestedSwapchainConfig(caps, false, 1, 9000, config));
    assert(config.extent.width == 16 && config.extent.height == 4096);
    caps.maxImageCount = 1;
    assert(!gamescope::ChooseNestedSwapchainConfig(caps, false, 1280, 720, config));

    using gamescope::ChooseNestedSDRCopySurfaceFormat;
    const VkSurfaceFormatKHR rgba = {
        VK_FORMAT_R8G8B8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR};
    const VkSurfaceFormatKHR bgra = {
        VK_FORMAT_B8G8R8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR};
    uint32_t surfaceFormat = UINT32_MAX;

    // PowerVR SDR copy output needs the RGBA view matching the rgba8 shader.
    const VkSurfaceFormatKHR bgraFirst[] = {bgra, rgba, rgba};
    assert(ChooseNestedSDRCopySurfaceFormat(0x1010, true, false,
                                           bgraFirst, 3, surfaceFormat));
    assert(surfaceFormat == 1);
    const VkSurfaceFormatKHR rgbaFirst[] = {rgba, bgra};
    assert(ChooseNestedSDRCopySurfaceFormat(0x1010, true, false,
                                           rgbaFirst, 2, surfaceFormat));
    assert(surfaceFormat == 0);
    assert(ChooseNestedSDRCopySurfaceFormat(0x1010, true, false,
                                           &rgba, 1, surfaceFormat));
    assert(surfaceFormat == 0);

    // Reusing a successful index must not hide a missing compatible format.
    assert(!ChooseNestedSDRCopySurfaceFormat(0x1010, true, false,
                                            &bgra, 1, surfaceFormat));
    assert(surfaceFormat == 1);
    const VkSurfaceFormatKHR wrongColorSpace = {
        VK_FORMAT_R8G8B8A8_UNORM, VK_COLOR_SPACE_DISPLAY_P3_NONLINEAR_EXT};
    assert(!ChooseNestedSDRCopySurfaceFormat(0x1010, true, false,
                                            &wrongColorSpace, 1, surfaceFormat));
    assert(surfaceFormat == 1);
    const VkSurfaceFormatKHR srgbFormat = {
        VK_FORMAT_R8G8B8A8_SRGB, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR};
    assert(!ChooseNestedSDRCopySurfaceFormat(0x1010, true, false,
                                            &srgbFormat, 1, surfaceFormat));
    assert(surfaceFormat == 1);
    const VkSurfaceFormatKHR compatibleLast[] = {wrongColorSpace, srgbFormat, rgba};
    assert(ChooseNestedSDRCopySurfaceFormat(0x1010, true, false,
                                           compatibleLast, 3, surfaceFormat));
    assert(surfaceFormat == 2);
    assert(!ChooseNestedSDRCopySurfaceFormat(0x1010, true, false,
                                            nullptr, 0, surfaceFormat));
    assert(surfaceFormat == 0);

    // The existing selector still owns HDR, direct output, and other vendors.
    // Each call clears a stale index even when BGRA is the only advertised format.
    surfaceFormat = 0;
    assert(ChooseNestedSDRCopySurfaceFormat(0x1010, true, true,
                                           &bgra, 1, surfaceFormat));
    assert(surfaceFormat == 1);
    surfaceFormat = 0;
    assert(ChooseNestedSDRCopySurfaceFormat(0x1010, false, false,
                                           &bgra, 1, surfaceFormat));
    assert(surfaceFormat == 1);
    surfaceFormat = 0;
    assert(ChooseNestedSDRCopySurfaceFormat(0x5143, true, false,
                                           &bgra, 1, surfaceFormat));
    assert(surfaceFormat == 1);

    // Retry only the device/format/layout/flag combination reproduced on PowerVR.
    using gamescope::RetryPowerVRLinearQuery;
    assert(RetryPowerVRLinearQuery(0x1010, VK_FORMAT_B8G8R8A8_UNORM, 0, 1,
                                  VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT, VK_ERROR_FORMAT_NOT_SUPPORTED));
    assert(RetryPowerVRLinearQuery(0x1010, VK_FORMAT_R8G8B8A8_UNORM, 0, 1,
                                  VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT, VK_ERROR_FORMAT_NOT_SUPPORTED));
    assert(!RetryPowerVRLinearQuery(0x5143, VK_FORMAT_B8G8R8A8_UNORM, 0, 1,
                                   VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT, VK_ERROR_FORMAT_NOT_SUPPORTED));
    assert(!RetryPowerVRLinearQuery(0x1010, VK_FORMAT_B8G8R8A8_UNORM, 1, 1,
                                   VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT, VK_ERROR_FORMAT_NOT_SUPPORTED));
    assert(!RetryPowerVRLinearQuery(0x1010, VK_FORMAT_B8G8R8A8_UNORM, 0, 2,
                                   VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT, VK_ERROR_FORMAT_NOT_SUPPORTED));
    assert(!RetryPowerVRLinearQuery(0x1010, VK_FORMAT_R16G16B16A16_SFLOAT, 0, 1,
                                   VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT, VK_ERROR_FORMAT_NOT_SUPPORTED));
    assert(!RetryPowerVRLinearQuery(0x1010, VK_FORMAT_B8G8R8A8_UNORM, 0, 1,
                                   0, VK_ERROR_FORMAT_NOT_SUPPORTED));
    assert(!RetryPowerVRLinearQuery(0x1010, VK_FORMAT_B8G8R8A8_UNORM, 0, 1,
                                   VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT, VK_ERROR_DEVICE_LOST));

    VkPhysicalDeviceMemoryProperties memory = {};
    memory.memoryTypeCount = 3;
    memory.memoryTypes[0].propertyFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
    memory.memoryTypes[1].propertyFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT |
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    memory.memoryTypes[2].propertyFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
    using gamescope::DmabufMemoryType;
    assert(DmabufMemoryType(memory, 7, 6, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) == 1);
    assert(DmabufMemoryType(memory, 7, 4, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) == 2);
    assert(DmabufMemoryType(memory, 1, 6, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) == UINT32_MAX);
    assert(DmabufMemoryType(memory, 7, 0, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) == UINT32_MAX);
    assert(DmabufMemoryType(memory, 7, 4, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                           VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) == UINT32_MAX);
    std::puts("Gamescope presentation and DMA-BUF import tests passed");
}
