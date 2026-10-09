/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Client side of feature emulation (Phase 4 fills this in).
 */
#define _GNU_SOURCE
#include "emu.h"

const VkExtensionProperties *vkb_emu_extensions(vkb_physdev *pd, uint32_t *n)
{
    (void)pd;
    *n = 0;
    return NULL;
}

void vkb_emu_patch_limits(vkb_physdev *pd, VkPhysicalDeviceProperties *p) { (void)pd; (void)p; }
void vkb_emu_patch_properties_chain(vkb_physdev *pd, VkBaseOutStructure *b) { (void)pd; (void)b; }
void vkb_emu_patch_features(vkb_physdev *pd, VkPhysicalDeviceFeatures *f) { (void)pd; (void)f; }
void vkb_emu_patch_features_chain(vkb_physdev *pd, VkBaseOutStructure *b)
{
    (void)pd;
    /* Host image copy hands the driver host pointers: never offered across the bridge. */
    if (b->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_4_FEATURES) ((VkPhysicalDeviceVulkan14Features *)b)->hostImageCopy = VK_FALSE;
    if (b->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_HOST_IMAGE_COPY_FEATURES) ((VkPhysicalDeviceHostImageCopyFeatures *)b)->hostImageCopy = VK_FALSE;
}

int vkb_emu_format_properties(vkb_physdev *pd, VkFormat format, VkFormatProperties *fp, VkFormatProperties2 *fp2)
{
    (void)pd; (void)format; (void)fp; (void)fp2;
    return 0;
}

VkFormat vkb_emu_real_format(vkb_physdev *pd, VkFormat format, VkImageUsageFlags *usage, VkImageCreateFlags *flags)
{
    (void)pd; (void)usage; (void)flags;
    return format;
}

int vkb_emu_is_emulated_format(vkb_physdev *pd, VkFormat format) { (void)pd; (void)format; return 0; }
void vkb_emu_image_create_info(vkb_device *dev, VkImageCreateInfo *ci) { (void)dev; (void)ci; }
void vkb_emu_image_created(vkb_device *dev, const VkImageCreateInfo *ci, VkImage image) { (void)dev; (void)ci; (void)image; }
void vkb_emu_image_destroyed(vkb_device *dev, VkImage image) { (void)dev; (void)image; }
