/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Hooks of the feature emulations that are not implemented yet: everything passes through.
 */
#define _GNU_SOURCE
#include "server_emu.h"

int vkb_emu_bcn_supported(const vkb_dispatch *idt, VkPhysicalDevice pd) { (void)idt; (void)pd; return 0; }
void vkb_emu_install_features(vkb_srv_table *dev) { vkb_emu_shader_install(dev); }
void vkb_emu_uninstall_features(vkb_srv_table *dev) { vkb_emu_shader_uninstall(dev); }
void vkb_emu_image_create_info(vkb_emu_device *e, VkImageCreateInfo *ci) { (void)e; (void)ci; }
void vkb_emu_image_release(vkb_emu_device *e, vkb_emu_image *img) { (void)e; (void)img; }
int vkb_emu_copy_image(vkb_emu_device *e, VkCommandBuffer cb, VkImage src, VkImageLayout sl, VkImage dst, VkImageLayout dl,
                       uint32_t n, const VkImageCopy *regions)
{
    (void)e; (void)cb; (void)src; (void)sl; (void)dst; (void)dl; (void)n; (void)regions;
    return 0;
}
int vkb_emu_copy_image2(vkb_emu_device *e, VkCommandBuffer cb, const VkCopyImageInfo2 *info) { (void)e; (void)cb; (void)info; return 0; }
int vkb_emu_copy_buffer_to_image(vkb_emu_device *e, VkCommandBuffer cb, VkBuffer src, VkImage dst, VkImageLayout dl, uint32_t n,
                                 const VkBufferImageCopy *regions)
{
    (void)e; (void)cb; (void)src; (void)dst; (void)dl; (void)n; (void)regions;
    return 0;
}
int vkb_emu_copy_buffer_to_image2(vkb_emu_device *e, VkCommandBuffer cb, const VkCopyBufferToImageInfo2 *info)
{
    (void)e; (void)cb; (void)info;
    return 0;
}
