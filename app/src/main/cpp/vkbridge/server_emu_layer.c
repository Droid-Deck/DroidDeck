/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Emulation wrappers installed into a device's dispatch table.
 */
#define _GNU_SOURCE
#include "server_emu.h"

int vkb_emu_bcn_supported(const vkb_dispatch *idt, VkPhysicalDevice pd)
{
    (void)idt;
    (void)pd;
    return 0;
}

void vkb_emu_install(vkb_srv_table *dev)
{
    (void)dev;
}

void vkb_emu_uninstall(vkb_srv_table *dev)
{
    (void)dev;
}
