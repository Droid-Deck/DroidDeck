/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Installs the feature emulations into a device, and the graphics-state hooks of the ones that
 * are not implemented yet (vertex attribute divisor).
 */
#define _GNU_SOURCE
#include "server_emu.h"

void vkb_emu_install_features(vkb_srv_table *dev)
{
    vkb_emu_cmdstate_install(dev);
    vkb_emu_shader_install(dev);
    vkb_emu_bcn_install(dev);
}

void vkb_emu_uninstall_features(vkb_srv_table *dev)
{
    vkb_emu_bcn_uninstall(dev);
    vkb_emu_shader_uninstall(dev);
    vkb_emu_cmdstate_uninstall(dev);
}

void vkb_emu_gfx_bind_pipeline(vkb_emu_device *e, VkCommandBuffer cb, VkPipeline p) { (void)e; (void)cb; (void)p; }
void vkb_emu_gfx_reset(void *gfx) { (void)gfx; }
void vkb_emu_gfx_free(void *gfx) { (void)gfx; }
