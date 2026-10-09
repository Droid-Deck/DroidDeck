/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Client side of feature emulation: reporting what the server emulates (see server_emu.c).
 */
#ifndef VKB_CLIENT_EMU_H
#define VKB_CLIENT_EMU_H

#include "vkb_client.h"

const VkExtensionProperties *vkb_emu_extensions(vkb_physdev *pd, uint32_t *n);
void vkb_emu_patch_limits(vkb_physdev *pd, VkPhysicalDeviceProperties *p);
void vkb_emu_patch_properties_chain(vkb_physdev *pd, VkBaseOutStructure *b);
void vkb_emu_patch_features(vkb_physdev *pd, VkPhysicalDeviceFeatures *f);
void vkb_emu_patch_features_chain(vkb_physdev *pd, VkBaseOutStructure *b);
/* 1 = handled (format is emulated). fp2 may be NULL. */
int vkb_emu_format_properties(vkb_physdev *pd, VkFormat format, VkFormatProperties *fp, VkFormatProperties2 *fp2);
/* The format the server really uses for an emulated one (usage/flags adjusted); else format. */
VkFormat vkb_emu_real_format(vkb_physdev *pd, VkFormat format, VkImageUsageFlags *usage, VkImageCreateFlags *flags);
int vkb_emu_is_emulated_format(vkb_physdev *pd, VkFormat format);
void vkb_emu_image_create_info(vkb_device *dev, VkImageCreateInfo *ci);
void vkb_emu_image_created(vkb_device *dev, const VkImageCreateInfo *ci, VkImage image);
void vkb_emu_image_destroyed(vkb_device *dev, VkImage image);

#endif
