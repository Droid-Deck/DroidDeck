/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Bridge-specific (non-Vulkan) parts of the protocol: hello, server query, memory sharing.
 */
#ifndef VKB_PROTO_H
#define VKB_PROTO_H

#include <stdint.h>

#define VKB_DEFAULT_SOCKET_ENV "VKBRIDGE_SOCKET"

/* How host-visible memory is shared between the client's address space and the GPU. */
enum vkb_mem_strategy {
    VKB_MEM_NONE = 0,      /* no sharing works: host-visible memory is not offered */
    VKB_MEM_HOSTPTR = 1,   /* client memfd, server imports it (VK_EXT_external_memory_host) */
    VKB_MEM_DMABUF = 2,    /* server exports a dma-buf (VK_EXT_external_memory_dma_buf), client mmaps */
    VKB_MEM_AHB = 3,       /* server allocates an AHardwareBuffer (BLOB), client mmaps its dma-buf */
};

static inline const char *vkb_mem_strategy_name(uint32_t s)
{
    switch (s) {
    case VKB_MEM_HOSTPTR: return "hostptr";
    case VKB_MEM_DMABUF: return "dmabuf";
    case VKB_MEM_AHB: return "ahb";
    default: return "none";
    }
}

/* Emulated features (server rewrites resources/shaders; client reports them as supported). */
#define VKB_EMU_BCN            (1u << 0)   /* BC1-BC7 textures decoded on upload */
#define VKB_EMU_DIVISOR        (1u << 1)   /* VK_EXT_vertex_attribute_divisor */
#define VKB_EMU_CLIP_DISTANCE  (1u << 2)   /* shaderClipDistance */
#define VKB_EMU_CULL_DISTANCE  (1u << 3)   /* shaderCullDistance (accepted, not applied) */
#define VKB_EMU_POINT_SIZE     (1u << 4)   /* strip PointSize from geometry/tessellation stages */
#define VKB_EMU_DEPTH_CLIP     (1u << 5)   /* VK_EXT_depth_clip_enable via depthClamp */

/* vkbQueryServer reply (per physical device). */
typedef struct vkb_server_pd_info {
    uint32_t strategy;             /* enum vkb_mem_strategy */
    uint32_t buffer_handle_types;  /* VkExternalMemoryHandleTypeFlags to put on buffers */
    uint32_t shareable_types;      /* server memory type bits that can be shared */
    uint32_t coherent_types;       /* of those, verified coherent without flushes */
    uint64_t import_alignment;     /* hostptr: minImportedHostPointerAlignment */
    uint32_t emu;                  /* VKB_EMU_* the server will perform */
    uint32_t emu_available;        /* VKB_EMU_* the server could perform */
    uint32_t real_api_version;
    uint32_t reserved[7];
} vkb_server_pd_info;

/* vkAllocateMemory wire extras (after VkMemoryAllocateInfo). */
#define VKB_ALLOC_PLAIN 0u
#define VKB_ALLOC_SHARED 1u

/* Extra structure the client hangs on create infos to tell the server which shared handle
 * types were injected (so the server can remove them if the driver refuses). Not on the wire. */

#endif
