/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef VKB_SERVER_EMU_H
#define VKB_SERVER_EMU_H

#include "vkb_server.h"

/* Emulations that are implemented (the rest are never offered to the client). */
#ifndef VKB_EMU_IMPLEMENTED
#define VKB_EMU_IMPLEMENTED 0u
#endif

typedef struct vkb_emu_device {
    uint32_t flags;
    vkb_srv_table *dev;
    pthread_mutex_t lock;
    void *bcn;       /* server_emu_bcn.c state */
    void *shaders;   /* server_emu_shader.c state */
} vkb_emu_device;

int vkb_emu_bcn_supported(const vkb_dispatch *idt, VkPhysicalDevice pd);
void vkb_emu_install(vkb_srv_table *dev);
void vkb_emu_uninstall(vkb_srv_table *dev);

#endif
