/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Client side of feature emulation: the app sees a feature when the GPU has it or the server
 * emulates it (vkb_server_pd_info.emu), and does not see one the GPU lacks (.missing).
 */
#define _GNU_SOURCE
#include "emu.h"
#include "gen/vkb_client_gen.h"
#include "vkb_emu_formats.h"

#include <string.h>

#define EMU(pd, f) (((pd)->info.emu & (f)) != 0)
#define MISSING(pd, f) (((pd)->info.missing & (f)) != 0 && !EMU(pd, f))

const VkExtensionProperties *vkb_emu_extensions(vkb_physdev *pd, uint32_t *n)
{
    static __thread VkExtensionProperties list[8];
    uint32_t c = 0;
    if (EMU(pd, VKB_EMU_MAINT5)) list[c++] = (VkExtensionProperties){"VK_KHR_maintenance5", 1};
    if (EMU(pd, VKB_EMU_DIVISOR)) {
        list[c++] = (VkExtensionProperties){"VK_EXT_vertex_attribute_divisor", 3};
        list[c++] = (VkExtensionProperties){"VK_KHR_vertex_attribute_divisor", 1};
    }
    if (EMU(pd, VKB_EMU_DEPTH_CLIP)) list[c++] = (VkExtensionProperties){"VK_EXT_depth_clip_enable", 1};
    *n = c;
    return list;
}

void vkb_emu_patch_limits(vkb_physdev *pd, VkPhysicalDeviceProperties *p)
{
    (void)pd;
    (void)p;
}

void vkb_emu_patch_properties_chain(vkb_physdev *pd, VkBaseOutStructure *b)
{
    if (EMU(pd, VKB_EMU_DIVISOR)) {
        if (b->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VERTEX_ATTRIBUTE_DIVISOR_PROPERTIES_EXT)
            ((VkPhysicalDeviceVertexAttributeDivisorPropertiesEXT *)b)->maxVertexAttribDivisor = UINT32_MAX;
        if (b->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VERTEX_ATTRIBUTE_DIVISOR_PROPERTIES) {
            VkPhysicalDeviceVertexAttributeDivisorProperties *p = (void *)b;
            p->maxVertexAttribDivisor = UINT32_MAX;
            p->supportsNonZeroFirstInstance = VK_TRUE;
        }
    }
}

static void set_feature(vkb_physdev *pd, uint32_t flag, VkBool32 *f)
{
    if (EMU(pd, flag)) *f = VK_TRUE;
    else if (MISSING(pd, flag)) *f = VK_FALSE;
}

void vkb_emu_patch_features(vkb_physdev *pd, VkPhysicalDeviceFeatures *f)
{
    set_feature(pd, VKB_EMU_BCN, &f->textureCompressionBC);
    set_feature(pd, VKB_EMU_CLIP_DISTANCE, &f->shaderClipDistance);
    set_feature(pd, VKB_EMU_CULL_DISTANCE, &f->shaderCullDistance);
}

void vkb_emu_patch_features_chain(vkb_physdev *pd, VkBaseOutStructure *b)
{
    switch ((int)b->sType) {
    case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_5_FEATURES:
        if (EMU(pd, VKB_EMU_MAINT5)) ((VkPhysicalDeviceMaintenance5Features *)b)->maintenance5 = VK_TRUE;
        break;
    case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VERTEX_ATTRIBUTE_DIVISOR_FEATURES: {
        VkPhysicalDeviceVertexAttributeDivisorFeatures *d = (void *)b;
        set_feature(pd, VKB_EMU_DIVISOR, &d->vertexAttributeInstanceRateDivisor);
        set_feature(pd, VKB_EMU_DIVISOR, &d->vertexAttributeInstanceRateZeroDivisor);
        break;
    }
    case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTENDED_DYNAMIC_STATE_3_FEATURES_EXT:
        /* Emulated depth clip is pipeline state (clamp = not clip): neither may be dynamic. */
        if (MISSING(pd, VKB_EMU_DEPTH_CLIP) || EMU(pd, VKB_EMU_DEPTH_CLIP)) {
            VkPhysicalDeviceExtendedDynamicState3FeaturesEXT *d3 = (void *)b;
            d3->extendedDynamicState3DepthClipEnable = VK_FALSE;
            if (EMU(pd, VKB_EMU_DEPTH_CLIP)) d3->extendedDynamicState3DepthClampEnable = VK_FALSE;
        }
        break;
    case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DEPTH_CLIP_ENABLE_FEATURES_EXT:
        set_feature(pd, VKB_EMU_DEPTH_CLIP, &((VkPhysicalDeviceDepthClipEnableFeaturesEXT *)b)->depthClipEnable);
        break;
    case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_4_FEATURES: {
        VkPhysicalDeviceVulkan14Features *v = (void *)b;
        v->hostImageCopy = VK_FALSE;
        set_feature(pd, VKB_EMU_DIVISOR, &v->vertexAttributeInstanceRateDivisor);
        set_feature(pd, VKB_EMU_DIVISOR, &v->vertexAttributeInstanceRateZeroDivisor);
        break;
    }
    case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_HOST_IMAGE_COPY_FEATURES:
        /* Host image copy hands the driver host pointers: never offered across the bridge. */
        ((VkPhysicalDeviceHostImageCopyFeatures *)b)->hostImageCopy = VK_FALSE;
        break;
    default:
        break;
    }
}

int vkb_emu_is_emulated_format(vkb_physdev *pd, VkFormat format)
{
    return vkb_is_bc(format) && EMU(pd, VKB_EMU_BCN);
}

int vkb_emu_format_properties(vkb_physdev *pd, VkFormat format, VkFormatProperties *fp, VkFormatProperties2 *fp2)
{
    if (!vkb_is_bc(format) || !(pd->info.missing & VKB_EMU_BCN)) return 0;
    memset(fp, 0, sizeof(*fp));
    if (EMU(pd, VKB_EMU_BCN)) {
        /* What the decoded image can do, limited to what a compressed texture is used for. */
        VkFormatProperties real;
        vkb_wire_vkGetPhysicalDeviceFormatProperties((VkPhysicalDevice)pd, vkb_bc_view_format(format), &real);
        fp->optimalTilingFeatures = real.optimalTilingFeatures &
                                    (VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT |
                                     VK_FORMAT_FEATURE_TRANSFER_SRC_BIT | VK_FORMAT_FEATURE_TRANSFER_DST_BIT |
                                     VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_MINMAX_BIT);
    }
    if (fp2) {
        for (VkBaseOutStructure *b = (VkBaseOutStructure *)fp2->pNext; b; b = b->pNext) {
            if (b->sType == VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_3) {
                VkFormatProperties3 *p3 = (void *)b;
                p3->linearTilingFeatures = 0;
                p3->optimalTilingFeatures = fp->optimalTilingFeatures;
                p3->bufferFeatures = 0;
            } else if (b->sType == VK_STRUCTURE_TYPE_DRM_FORMAT_MODIFIER_PROPERTIES_LIST_EXT) {
                ((VkDrmFormatModifierPropertiesListEXT *)b)->drmFormatModifierCount = 0;
            } else if (b->sType == VK_STRUCTURE_TYPE_DRM_FORMAT_MODIFIER_PROPERTIES_LIST_2_EXT) {
                ((VkDrmFormatModifierPropertiesList2EXT *)b)->drmFormatModifierCount = 0;
            }
        }
    }
    return 1;
}

VkFormat vkb_emu_real_format(vkb_physdev *pd, VkFormat format, VkImageUsageFlags *usage, VkImageCreateFlags *flags)
{
    if (!vkb_emu_is_emulated_format(pd, format)) return format;
    /* As the server creates it (server_emu_bcn.c): decoded format, mutable, extended usage. */
    *flags &= ~(VkImageCreateFlags)VK_IMAGE_CREATE_BLOCK_TEXEL_VIEW_COMPATIBLE_BIT;
    *flags |= VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT | VK_IMAGE_CREATE_EXTENDED_USAGE_BIT;
    *usage |= VK_IMAGE_USAGE_STORAGE_BIT;
    return vkb_bc_view_format(format);
}

/* Image creation is rewritten on the server (it knows the decode); nothing to do here. */
void vkb_emu_image_create_info(vkb_device *dev, VkImageCreateInfo *ci) { (void)dev; (void)ci; }
void vkb_emu_image_created(vkb_device *dev, const VkImageCreateInfo *ci, VkImage image) { (void)dev; (void)ci; (void)image; }
void vkb_emu_image_destroyed(vkb_device *dev, VkImage image) { (void)dev; (void)image; }
