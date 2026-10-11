/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * BCn emulation: what each block-compressed format is decoded into. Shared by the client (format
 * queries) and the server (image creation, decode). The decoded image is created with the UNORM
 * base format and MUTABLE_FORMAT so the decoder can write it through a storage view, and the
 * app's sRGB views still work.
 */
#ifndef VKB_EMU_FORMATS_H
#define VKB_EMU_FORMATS_H

#include <vulkan/vulkan.h>

static inline int vkb_is_bc(VkFormat f)
{
    return f >= VK_FORMAT_BC1_RGB_UNORM_BLOCK && f <= VK_FORMAT_BC7_SRGB_BLOCK;
}

/* The format an app's view of a BC format becomes (keeps sRGB/SNORM meaning). */
static inline VkFormat vkb_bc_view_format(VkFormat f)
{
    switch (f) {
    case VK_FORMAT_BC1_RGB_UNORM_BLOCK:
    case VK_FORMAT_BC1_RGBA_UNORM_BLOCK:
    case VK_FORMAT_BC2_UNORM_BLOCK:
    case VK_FORMAT_BC3_UNORM_BLOCK:
    case VK_FORMAT_BC7_UNORM_BLOCK: return VK_FORMAT_R8G8B8A8_UNORM;
    case VK_FORMAT_BC1_RGB_SRGB_BLOCK:
    case VK_FORMAT_BC1_RGBA_SRGB_BLOCK:
    case VK_FORMAT_BC2_SRGB_BLOCK:
    case VK_FORMAT_BC3_SRGB_BLOCK:
    case VK_FORMAT_BC7_SRGB_BLOCK: return VK_FORMAT_R8G8B8A8_SRGB;
    case VK_FORMAT_BC4_UNORM_BLOCK: return VK_FORMAT_R8_UNORM;
    case VK_FORMAT_BC4_SNORM_BLOCK: return VK_FORMAT_R8_SNORM;
    case VK_FORMAT_BC5_UNORM_BLOCK: return VK_FORMAT_R8G8_UNORM;
    case VK_FORMAT_BC5_SNORM_BLOCK: return VK_FORMAT_R8G8_SNORM;
    case VK_FORMAT_BC6H_UFLOAT_BLOCK:
    case VK_FORMAT_BC6H_SFLOAT_BLOCK: return VK_FORMAT_R16G16B16A16_SFLOAT;
    default: return f;
    }
}

/* The format the decoded image is created with and written through (storage-capable). */
static inline VkFormat vkb_bc_storage_format(VkFormat f)
{
    switch (vkb_bc_view_format(f)) {
    case VK_FORMAT_R8G8B8A8_SRGB:
    case VK_FORMAT_R8G8B8A8_UNORM: return VK_FORMAT_R8G8B8A8_UNORM;
    case VK_FORMAT_R8_SNORM:
    case VK_FORMAT_R8_UNORM: return VK_FORMAT_R8_UNORM;
    case VK_FORMAT_R8G8_SNORM:
    case VK_FORMAT_R8G8_UNORM: return VK_FORMAT_R8G8_UNORM;
    case VK_FORMAT_R16G16B16A16_SFLOAT: return VK_FORMAT_R16G16B16A16_SFLOAT;
    default: return f;
    }
}

/* Bytes per 4x4 block. */
static inline uint32_t vkb_bc_block_bytes(VkFormat f)
{
    switch (f) {
    case VK_FORMAT_BC1_RGB_UNORM_BLOCK:
    case VK_FORMAT_BC1_RGB_SRGB_BLOCK:
    case VK_FORMAT_BC1_RGBA_UNORM_BLOCK:
    case VK_FORMAT_BC1_RGBA_SRGB_BLOCK:
    case VK_FORMAT_BC4_UNORM_BLOCK:
    case VK_FORMAT_BC4_SNORM_BLOCK: return 8;
    default: return 16;
    }
}

#endif
