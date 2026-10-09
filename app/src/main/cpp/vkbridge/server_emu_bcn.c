/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * BC1-BC7 textures on a GPU without them (textureCompressionBC missing; DXVK and vkd3d-proton
 * refuse such a device). The image the app creates in a BC format is created decoded instead
 * (RGBA8 / R8 / RG8 / RGBA16F, see vkb_emu_formats.h), mutable so sRGB/SNORM views work, and
 * every upload - vkCmdCopyBufferToImage(2), how all BC data arrives - becomes a compute dispatch
 * that decodes the blocks from the source buffer straight into it (shaders/bc_decode.comp). The
 * app's compute bindings are restored afterwards (server_emu_cmdstate.c).
 *
 * Not emulated (logged once): reading a BC image back to a buffer, and copies between BC and
 * uncompressed images (they would need an encoder).
 */
#define _GNU_SOURCE
#include "server_emu.h"
#include "vkb_emu_formats.h"
#include "bc_spv.h"

#include <stdlib.h>
#include <string.h>

enum { FAM_BC1, FAM_BC2, FAM_BC3, FAM_BC4, FAM_BC5, FAM_BC6H, FAM_BC7, FAM_COUNT };

typedef struct bcn_state {
    VkDescriptorSetLayout dsl;
    VkPipelineLayout layout;
    VkPipeline pipes[FAM_COUNT];
    VkDeviceSize ssbo_align;
    pthread_mutex_t lock;
} bcn_state;

typedef struct bcn_image {
    VkImageView views[16];   /* per mip: 2D array storage view of every layer */
} bcn_image;

typedef struct pc_t {
    uint32_t src_word, row_blocks, layer_blocks, flags;
    int32_t dst_off[2];
    uint32_t extent[2];
} pc_t;

static int family(VkFormat f)
{
    switch (f) {
    case VK_FORMAT_BC1_RGB_UNORM_BLOCK: case VK_FORMAT_BC1_RGB_SRGB_BLOCK:
    case VK_FORMAT_BC1_RGBA_UNORM_BLOCK: case VK_FORMAT_BC1_RGBA_SRGB_BLOCK: return FAM_BC1;
    case VK_FORMAT_BC2_UNORM_BLOCK: case VK_FORMAT_BC2_SRGB_BLOCK: return FAM_BC2;
    case VK_FORMAT_BC3_UNORM_BLOCK: case VK_FORMAT_BC3_SRGB_BLOCK: return FAM_BC3;
    case VK_FORMAT_BC4_UNORM_BLOCK: case VK_FORMAT_BC4_SNORM_BLOCK: return FAM_BC4;
    case VK_FORMAT_BC5_UNORM_BLOCK: case VK_FORMAT_BC5_SNORM_BLOCK: return FAM_BC5;
    case VK_FORMAT_BC6H_UFLOAT_BLOCK: case VK_FORMAT_BC6H_SFLOAT_BLOCK: return FAM_BC6H;
    default: return FAM_BC7;
    }
}

static uint32_t family_flags(VkFormat f)
{
    uint32_t fl = 0;
    if (f == VK_FORMAT_BC1_RGB_UNORM_BLOCK || f == VK_FORMAT_BC1_RGB_SRGB_BLOCK) fl |= 1;
    if (f == VK_FORMAT_BC4_SNORM_BLOCK || f == VK_FORMAT_BC5_SNORM_BLOCK || f == VK_FORMAT_BC6H_SFLOAT_BLOCK) fl |= 2;
    return fl;
}

int vkb_emu_bcn_supported(const vkb_dispatch *idt, VkPhysicalDevice pd)
{
    VkPhysicalDeviceFeatures f;
    idt->vkGetPhysicalDeviceFeatures(pd, &f);
    if (!f.shaderStorageImageExtendedFormats) {
        VKB_WARN("BC emulation: the GPU lacks shaderStorageImageExtendedFormats");
        return 0;
    }
    static const VkFormat need[] = {VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8_UNORM, VK_FORMAT_R8G8_UNORM, VK_FORMAT_R16G16B16A16_SFLOAT};
    for (size_t i = 0; i < sizeof(need) / sizeof(need[0]); i++) {
        VkFormatProperties fp;
        idt->vkGetPhysicalDeviceFormatProperties(pd, need[i], &fp);
        if (!(fp.optimalTilingFeatures & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT)) {
            VKB_WARN("BC emulation: format %d cannot be a storage image", need[i]);
            return 0;
        }
    }
    VkPhysicalDeviceProperties p;
    idt->vkGetPhysicalDeviceProperties(pd, &p);
    if (p.apiVersion >= VK_API_VERSION_1_4) return 1;
    uint32_t n = 0;
    idt->vkEnumerateDeviceExtensionProperties(pd, NULL, &n, NULL);
    VkExtensionProperties *e = calloc(n ? n : 1, sizeof(*e));
    idt->vkEnumerateDeviceExtensionProperties(pd, NULL, &n, e);
    int ok = 0;
    for (uint32_t i = 0; i < n; i++) ok |= !strcmp(e[i].extensionName, "VK_KHR_push_descriptor");
    free(e);
    if (!ok) VKB_WARN("BC emulation: the GPU lacks VK_KHR_push_descriptor");
    return ok;
}

/* ------------------------------------------------------------------ images */

void vkb_emu_image_create_info(vkb_emu_device *e, VkImageCreateInfo *ci)
{
    if (!(e->flags & VKB_EMU_BCN) || !vkb_is_bc(ci->format)) return;
    /* The image keeps the meaning of the app's format (sRGB, SNORM) so blits and copies convert as
     * they would; the decoder writes it through a view in the storage format (EXTENDED_USAGE lets
     * the image carry STORAGE although sRGB/SNORM formats cannot be stored to). */
    static __thread VkFormat list[2];
    VkFormat view = vkb_bc_view_format(ci->format), st = vkb_bc_storage_format(ci->format);
    list[0] = view;
    list[1] = st;
    ci->format = view;
    ci->flags &= ~(VkImageCreateFlags)VK_IMAGE_CREATE_BLOCK_TEXEL_VIEW_COMPATIBLE_BIT;
    ci->flags |= VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT | VK_IMAGE_CREATE_EXTENDED_USAGE_BIT;
    ci->usage |= VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    for (VkBaseOutStructure *b = (VkBaseOutStructure *)ci->pNext; b; b = b->pNext) {
        if (b->sType == VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO) {
            VkImageFormatListCreateInfo *fl = (VkImageFormatListCreateInfo *)b;
            fl->viewFormatCount = view == st ? 1 : 2;
            fl->pViewFormats = list;
        }
    }
}

void vkb_emu_image_release(vkb_emu_device *e, vkb_emu_image *img)
{
    bcn_image *bi = img->emu;
    if (!bi) return;
    for (int i = 0; i < 16; i++)
        if (bi->views[i]) e->dev->real.vkDestroyImageView(e->dev->device, bi->views[i], NULL);
    free(bi);
    img->emu = NULL;
}

VkFormat vkb_emu_bcn_view_format(vkb_emu_device *e, VkImage image, VkFormat requested)
{
    if (!(e->flags & VKB_EMU_BCN)) return requested;
    if (vkb_is_bc(requested)) return vkb_bc_view_format(requested);
    vkb_emu_image *r = vkb_emu_find_image(e, image);
    if (r && vkb_is_bc(r->client_format)) {
        VKB_ONCE(VKB_LOG_WARN, "BC emulation: an uncompressed view (format %d) of a BC image shows decoded texels (logged once)", requested);
        return vkb_bc_view_format(r->client_format);
    }
    return requested;
}

static VKAPI_ATTR VkResult VKAPI_CALL emu_CreateImageView(VkDevice device, const VkImageViewCreateInfo *ci,
                                                         const VkAllocationCallbacks *pAllocator, VkImageView *pView)
{
    VkImageViewCreateInfo *v = (VkImageViewCreateInfo *)ci;
    v->format = vkb_emu_bcn_view_format(vkb_emu_cur(), ci->image, ci->format);
    return vkb_emu_real()->vkCreateImageView(device, v, pAllocator, pView);
}

void vkb_emu_bcn_buffer_usage(vkb_emu_device *e, VkBufferCreateInfo *ci)
{
    /* The decoder reads uploads as a storage buffer. */
    if ((e->flags & VKB_EMU_BCN) && (ci->usage & VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) ci->usage |= VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
}

/* ------------------------------------------------------------------ decoder */

static VkPipeline pipeline(vkb_emu_device *e, int fam)
{
    bcn_state *s = e->bcn;
    if (s->pipes[fam]) return s->pipes[fam];
    pthread_mutex_lock(&s->lock);
    if (!s->pipes[fam]) {
        static const uint32_t *const code[FAM_COUNT] = {spv_bc1, spv_bc2, spv_bc3, spv_bc4, spv_bc5, spv_bc6h, spv_bc7};
        static const size_t size[FAM_COUNT] = {sizeof(spv_bc1), sizeof(spv_bc2), sizeof(spv_bc3), sizeof(spv_bc4),
                                               sizeof(spv_bc5), sizeof(spv_bc6h), sizeof(spv_bc7)};
        const vkb_dispatch *r = &e->dev->real;
        VkDevice d = e->dev->device;
        VkShaderModuleCreateInfo mci = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, NULL, 0, size[fam], code[fam]};
        VkShaderModule m;
        if (r->vkCreateShaderModule(d, &mci, NULL, &m) == VK_SUCCESS) {
            VkComputePipelineCreateInfo pci = {VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
            pci.stage = (VkPipelineShaderStageCreateInfo){VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, NULL, 0,
                                                          VK_SHADER_STAGE_COMPUTE_BIT, m, "main", NULL};
            pci.layout = s->layout;
            if (r->vkCreateComputePipelines(d, VK_NULL_HANDLE, 1, &pci, NULL, &s->pipes[fam]) != VK_SUCCESS) {
                VKB_ERR("BC emulation: decoder pipeline %d failed to build", fam);
                s->pipes[fam] = VK_NULL_HANDLE;
            }
            r->vkDestroyShaderModule(d, m, NULL);
        }
    }
    pthread_mutex_unlock(&s->lock);
    return s->pipes[fam];
}

static VkImageView storage_view(vkb_emu_device *e, vkb_emu_image *img, uint32_t mip)
{
    if (mip >= 16) return VK_NULL_HANDLE;
    pthread_mutex_lock(&e->lock);
    if (!img->emu) img->emu = calloc(1, sizeof(bcn_image));
    bcn_image *bi = img->emu;
    VkImageView v = bi->views[mip];
    pthread_mutex_unlock(&e->lock);
    if (v) return v;
    VkImageViewUsageCreateInfo usage = {VK_STRUCTURE_TYPE_IMAGE_VIEW_USAGE_CREATE_INFO, NULL, VK_IMAGE_USAGE_STORAGE_BIT};
    VkImageViewCreateInfo ci = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, &usage, 0, img->image, VK_IMAGE_VIEW_TYPE_2D_ARRAY,
                                vkb_bc_storage_format(img->client_format),
                                {0}, {VK_IMAGE_ASPECT_COLOR_BIT, mip, 1, 0, img->layers}};
    if (e->dev->real.vkCreateImageView(e->dev->device, &ci, NULL, &v) != VK_SUCCESS) return VK_NULL_HANDLE;
    pthread_mutex_lock(&e->lock);
    if (bi->views[mip]) {
        e->dev->real.vkDestroyImageView(e->dev->device, v, NULL);
        v = bi->views[mip];
    } else {
        bi->views[mip] = v;
    }
    pthread_mutex_unlock(&e->lock);
    return v;
}

typedef struct region {
    VkDeviceSize offset;
    uint32_t row_length, image_height;
    VkImageSubresourceLayers sub;
    VkOffset3D ioff;
    VkExtent3D iext;
} region;

static void decode(vkb_emu_device *e, VkCommandBuffer cb, VkBuffer src, vkb_emu_image *img, VkImageLayout layout,
                   uint32_t n, const region *regs)
{
    bcn_state *s = e->bcn;
    const vkb_dispatch *r = &e->dev->real;
    if (img->type != VK_IMAGE_TYPE_2D) {
        VKB_ONCE(VKB_LOG_WARN, "BC emulation: uploads to 1D/3D BC images are not decoded (logged once)");
        return;
    }
    VkPipeline pipe = pipeline(e, family(img->client_format));
    if (!pipe) return;
    for (uint32_t i = 0; i < n; i++) {
        const region *g = &regs[i];
        VkImageView view = storage_view(e, img, g->sub.mipLevel);
        if (!view) continue;
        VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, g->sub.mipLevel, 1, g->sub.baseArrayLayer, g->sub.layerCount};
        VkMemoryBarrier mb = {VK_STRUCTURE_TYPE_MEMORY_BARRIER, NULL, VK_ACCESS_MEMORY_WRITE_BIT,
                              VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT};
        VkImageMemoryBarrier ib = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, NULL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                                   layout, VK_IMAGE_LAYOUT_GENERAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, img->image, range};
        r->vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                0, 1, &mb, 0, NULL, 1, &ib);
        VkDeviceSize aligned = g->offset & ~(s->ssbo_align - 1);
        VkDescriptorBufferInfo bi = {src, aligned, VK_WHOLE_SIZE};
        VkDescriptorImageInfo ii = {VK_NULL_HANDLE, view, VK_IMAGE_LAYOUT_GENERAL};
        VkWriteDescriptorSet w[2] = {
            {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, NULL, VK_NULL_HANDLE, 0, 0, 1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, NULL, &bi, NULL},
            {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, NULL, VK_NULL_HANDLE, 1, 0, 1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &ii, NULL, NULL}};
        r->vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipe);
        r->vkCmdPushDescriptorSet(cb, VK_PIPELINE_BIND_POINT_COMPUTE, s->layout, 0, 2, w);
        uint32_t w_tex = g->row_length ? g->row_length : g->iext.width;
        uint32_t h_tex = g->image_height ? g->image_height : g->iext.height;
        pc_t pc;
        pc.src_word = (uint32_t)((g->offset - aligned) / 4);
        pc.row_blocks = (w_tex + 3) / 4;
        pc.layer_blocks = pc.row_blocks * ((h_tex + 3) / 4);
        pc.flags = family_flags(img->client_format) | (g->sub.baseArrayLayer << 8);
        pc.dst_off[0] = g->ioff.x;
        pc.dst_off[1] = g->ioff.y;
        pc.extent[0] = g->iext.width;
        pc.extent[1] = g->iext.height;
        r->vkCmdPushConstants(cb, s->layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pc), &pc);
        uint32_t bw = (g->iext.width + 3) / 4, bh = (g->iext.height + 3) / 4;
        r->vkCmdDispatch(cb, (bw + 7) / 8, (bh + 7) / 8, g->sub.layerCount);
        ib.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        ib.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT;
        ib.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        ib.newLayout = layout;
        r->vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1, &ib);
    }
    vkb_emu_restore_compute(e, cb);
}

static vkb_emu_image *bc_image(vkb_emu_device *e, VkImage img)
{
    if (!(e->flags & VKB_EMU_BCN)) return NULL;
    vkb_emu_image *r = vkb_emu_find_image(e, img);
    return r && vkb_is_bc(r->client_format) ? r : NULL;
}

int vkb_emu_copy_buffer_to_image(vkb_emu_device *e, VkCommandBuffer cb, VkBuffer src, VkImage dst, VkImageLayout dl, uint32_t n,
                                 const VkBufferImageCopy *regions)
{
    vkb_emu_image *img = bc_image(e, dst);
    if (!img) return 0;
    region *g = malloc((n ? n : 1) * sizeof(*g));
    for (uint32_t i = 0; i < n; i++)
        g[i] = (region){regions[i].bufferOffset, regions[i].bufferRowLength, regions[i].bufferImageHeight, regions[i].imageSubresource,
                        regions[i].imageOffset, regions[i].imageExtent};
    decode(e, cb, src, img, dl, n, g);
    free(g);
    return 1;
}

int vkb_emu_copy_buffer_to_image2(vkb_emu_device *e, VkCommandBuffer cb, const VkCopyBufferToImageInfo2 *info)
{
    vkb_emu_image *img = bc_image(e, info->dstImage);
    if (!img) return 0;
    region *g = malloc((info->regionCount ? info->regionCount : 1) * sizeof(*g));
    for (uint32_t i = 0; i < info->regionCount; i++) {
        const VkBufferImageCopy2 *r = &info->pRegions[i];
        g[i] = (region){r->bufferOffset, r->bufferRowLength, r->bufferImageHeight, r->imageSubresource, r->imageOffset, r->imageExtent};
    }
    decode(e, cb, info->srcBuffer, img, info->dstImageLayout, info->regionCount, g);
    free(g);
    return 1;
}

/* Copies between BC images copy decoded texels; a region may run past a small mip's edge (whole
 * blocks), which the decoded image does not have. */
static void clamp_region(const vkb_emu_image *s, const vkb_emu_image *d, const VkImageSubresourceLayers *ss,
                         const VkImageSubresourceLayers *ds, const VkOffset3D *so, const VkOffset3D *dof, VkExtent3D *ext)
{
    uint32_t sw = s->extent.width >> ss->mipLevel, sh = s->extent.height >> ss->mipLevel;
    uint32_t dw = d->extent.width >> ds->mipLevel, dh = d->extent.height >> ds->mipLevel;
    if (!sw) sw = 1;
    if (!sh) sh = 1;
    if (!dw) dw = 1;
    if (!dh) dh = 1;
    if ((uint32_t)so->x + ext->width > sw) ext->width = sw > (uint32_t)so->x ? sw - (uint32_t)so->x : 0;
    if ((uint32_t)so->y + ext->height > sh) ext->height = sh > (uint32_t)so->y ? sh - (uint32_t)so->y : 0;
    if ((uint32_t)dof->x + ext->width > dw) ext->width = dw > (uint32_t)dof->x ? dw - (uint32_t)dof->x : 0;
    if ((uint32_t)dof->y + ext->height > dh) ext->height = dh > (uint32_t)dof->y ? dh - (uint32_t)dof->y : 0;
}

int vkb_emu_copy_image(vkb_emu_device *e, VkCommandBuffer cb, VkImage src, VkImageLayout sl, VkImage dst, VkImageLayout dl, uint32_t n,
                       const VkImageCopy *regions)
{
    vkb_emu_image *s = bc_image(e, src), *d = bc_image(e, dst);
    if (!s && !d) return 0;
    if (!s || !d || vkb_bc_storage_format(s->client_format) != vkb_bc_storage_format(d->client_format)) {
        VKB_ONCE(VKB_LOG_WARN, "BC emulation: a copy between a BC image and another format is skipped (logged once)");
        return 1;
    }
    VkImageCopy *c = (VkImageCopy *)regions;
    for (uint32_t i = 0; i < n; i++)
        clamp_region(s, d, &c[i].srcSubresource, &c[i].dstSubresource, &c[i].srcOffset, &c[i].dstOffset, &c[i].extent);
    e->dev->real.vkCmdCopyImage(cb, src, sl, dst, dl, n, regions);
    return 1;
}

int vkb_emu_copy_image2(vkb_emu_device *e, VkCommandBuffer cb, const VkCopyImageInfo2 *info)
{
    vkb_emu_image *s = bc_image(e, info->srcImage), *d = bc_image(e, info->dstImage);
    if (!s && !d) return 0;
    if (!s || !d || vkb_bc_storage_format(s->client_format) != vkb_bc_storage_format(d->client_format)) {
        VKB_ONCE(VKB_LOG_WARN, "BC emulation: a copy between a BC image and another format is skipped (logged once)");
        return 1;
    }
    VkImageCopy2 *c = (VkImageCopy2 *)info->pRegions;
    for (uint32_t i = 0; i < info->regionCount; i++)
        clamp_region(s, d, &c[i].srcSubresource, &c[i].dstSubresource, &c[i].srcOffset, &c[i].dstOffset, &c[i].extent);
    e->dev->real.vkCmdCopyImage2(cb, info);
    return 1;
}

int vkb_emu_bcn_blocks_readback(vkb_emu_device *e, VkImage img)
{
    if (!bc_image(e, img)) return 0;
    VKB_ONCE(VKB_LOG_WARN, "BC emulation: reading a BC image back to a buffer is not supported (logged once)");
    return 1;
}

/* ------------------------------------------------------------------ install */

void vkb_emu_bcn_install(vkb_srv_table *dev)
{
    vkb_emu_device *e = dev->emu;
    if (!(e->flags & VKB_EMU_BCN)) return;
    bcn_state *s = calloc(1, sizeof(*s));
    pthread_mutex_init(&s->lock, NULL);
    VkPhysicalDeviceProperties p;
    dev->real.vkGetPhysicalDeviceProperties(dev->physical, &p);
    s->ssbo_align = p.limits.minStorageBufferOffsetAlignment ? p.limits.minStorageBufferOffsetAlignment : 4;
    VkDescriptorSetLayoutBinding b[2] = {{0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, NULL},
                                         {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, NULL}};
    VkDescriptorSetLayoutCreateInfo dci = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, NULL,
                                           VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT, 2, b};
    VkPushConstantRange pr = {VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pc_t)};
    if (dev->real.vkCreateDescriptorSetLayout(dev->device, &dci, NULL, &s->dsl) != VK_SUCCESS) {
        VKB_ERR("BC emulation: cannot create the decoder's descriptor layout");
        free(s);
        e->flags &= ~VKB_EMU_BCN;
        return;
    }
    VkPipelineLayoutCreateInfo plci = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, NULL, 0, 1, &s->dsl, 1, &pr};
    dev->real.vkCreatePipelineLayout(dev->device, &plci, NULL, &s->layout);
    e->bcn = s;
    dev->dt.vkCreateImageView = emu_CreateImageView;
}

void vkb_emu_bcn_uninstall(vkb_srv_table *dev)
{
    bcn_state *s = dev->emu->bcn;
    if (!s) return;
    for (int i = 0; i < FAM_COUNT; i++)
        if (s->pipes[i]) dev->real.vkDestroyPipeline(dev->device, s->pipes[i], NULL);
    dev->real.vkDestroyPipelineLayout(dev->device, s->layout, NULL);
    dev->real.vkDestroyDescriptorSetLayout(dev->device, s->dsl, NULL);
    pthread_mutex_destroy(&s->lock);
    free(s);
    dev->emu->bcn = NULL;
}
