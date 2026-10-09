/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef VKB_SERVER_EMU_H
#define VKB_SERVER_EMU_H

#include "vkb_server.h"

/* Emulations that are implemented (the rest are never offered to the client). */
#define VKB_EMU_IMPLEMENTED (VKB_EMU_MAINT5)

#define VKB_EMU_BUCKETS 1024

typedef struct vkb_emu_image {
    VkImage image;
    VkImageType type;
    VkFormat format;          /* what the driver holds */
    VkFormat client_format;   /* what the app asked for (differs for emulated formats) */
    VkExtent3D extent;
    uint32_t mips, layers;
    void *emu;                /* per-emulation state (BCn: decode resources) */
    struct vkb_emu_image *next;
} vkb_emu_image;

typedef struct vkb_emu_buffer {
    VkBuffer buffer;
    VkDeviceSize size;
    struct vkb_emu_buffer *next;
} vkb_emu_buffer;

typedef struct vkb_emu_device {
    uint32_t flags;
    vkb_srv_table *dev;
    pthread_mutex_t lock;
    vkb_emu_image *images[VKB_EMU_BUCKETS];
    vkb_emu_buffer *buffers[VKB_EMU_BUCKETS];
    void *bcn;       /* server_emu_bcn.c */
    void *shaders;   /* server_emu_shader.c */
} vkb_emu_device;

/* layer (server_emu_layer.c) */
vkb_emu_device *vkb_emu_cur(void);
const vkb_dispatch *vkb_emu_real(void);
void *vkb_emu_chain_take(const void *head, VkStructureType t);
vkb_emu_image *vkb_emu_find_image(vkb_emu_device *e, VkImage img);
void vkb_emu_track_image(vkb_emu_device *e, VkImage img, const VkImageCreateInfo *ci, VkFormat client_format);

/* feature hooks (server_emu_features.c and friends) */
int vkb_emu_bcn_supported(const vkb_dispatch *idt, VkPhysicalDevice pd);
void vkb_emu_install(vkb_srv_table *dev);
void vkb_emu_uninstall(vkb_srv_table *dev);
void vkb_emu_install_features(vkb_srv_table *dev);
void vkb_emu_uninstall_features(vkb_srv_table *dev);
void vkb_emu_image_create_info(vkb_emu_device *e, VkImageCreateInfo *ci);
void vkb_emu_image_release(vkb_emu_device *e, vkb_emu_image *img);
void vkb_emu_graphics_pipeline_info(vkb_emu_device *e, VkGraphicsPipelineCreateInfo *ci);
/* 1 = handled (the emulation recorded its own commands instead). */
int vkb_emu_copy_image(vkb_emu_device *e, VkCommandBuffer cb, VkImage src, VkImageLayout sl, VkImage dst, VkImageLayout dl,
                       uint32_t n, const VkImageCopy *regions);
int vkb_emu_copy_image2(vkb_emu_device *e, VkCommandBuffer cb, const VkCopyImageInfo2 *info);
int vkb_emu_copy_buffer_to_image(vkb_emu_device *e, VkCommandBuffer cb, VkBuffer src, VkImage dst, VkImageLayout dl, uint32_t n,
                                 const VkBufferImageCopy *regions);
int vkb_emu_copy_buffer_to_image2(vkb_emu_device *e, VkCommandBuffer cb, const VkCopyBufferToImageInfo2 *info);

#endif
