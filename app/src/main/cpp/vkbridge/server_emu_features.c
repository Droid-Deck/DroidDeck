/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Installs the feature emulations into a device.
 */
#define _GNU_SOURCE
#include "server_emu.h"

void vkb_emu_install_features(vkb_srv_table *dev)
{
    vkb_emu_cmdstate_install(dev);
    vkb_emu_shader_install(dev);
    vkb_emu_bcn_install(dev);
    vkb_emu_divisor_install(dev);
}

void vkb_emu_uninstall_features(vkb_srv_table *dev)
{
    vkb_emu_divisor_uninstall(dev);
    vkb_emu_bcn_uninstall(dev);
    vkb_emu_shader_uninstall(dev);
    vkb_emu_cmdstate_uninstall(dev);
}
