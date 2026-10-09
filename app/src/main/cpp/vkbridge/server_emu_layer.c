/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Emulation wrappers installed into a device's dispatch table, like a Vulkan layer: the generated
 * handlers call c->dt, which for an emulating device points here first. The device is found
 * through the per-call table the connection loop sets (vkb_srv_current_table).
 *
 * VK_KHR_maintenance5 (Zink and DXVK 2.7 refuse a Vulkan 1.3 device without it):
 *   - 64-bit flags structures (pipeline create flags 2, buffer usage 2) folded into the 32-bit
 *     fields, which hold every bit a 1.3 driver knows;
 *   - shader modules given inline (VkShaderModuleCreateInfo chained to a stage) created and
 *     destroyed around the pipeline;
 *   - vkCmdBindIndexBuffer2, vkGetRenderingAreaGranularity, vkGetImageSubresourceLayout2 and
 *     vkGetDeviceImageSubresourceLayout on their 1.0 equivalents;
 *   - VK_REMAINING_ARRAY_LAYERS in copy regions and VK_WHOLE_SIZE in vertex buffer sizes, resolved
 *     from the image/buffer the server keeps a record of.
 */
#define _GNU_SOURCE
#include "server_emu.h"

#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------------------ resource records */

static unsigned rh(uint64_t k)
{
    k ^= k >> 29;
    k *= 0xbf58476d1ce4e5b9ull;
    return (unsigned)(k >> 52) & (VKB_EMU_BUCKETS - 1);
}

void vkb_emu_track_image(vkb_emu_device *e, VkImage img, const VkImageCreateInfo *ci, VkFormat client_format)
{
    vkb_emu_image *r = calloc(1, sizeof(*r));
    r->image = img;
    r->type = ci->imageType;
    r->format = ci->format;
    r->client_format = client_format;
    r->extent = ci->extent;
    r->mips = ci->mipLevels;
    r->layers = ci->arrayLayers;
    pthread_mutex_lock(&e->lock);
    unsigned h = rh((uint64_t)img);
    r->next = e->images[h];
    e->images[h] = r;
    pthread_mutex_unlock(&e->lock);
}

vkb_emu_image *vkb_emu_find_image(vkb_emu_device *e, VkImage img)
{
    pthread_mutex_lock(&e->lock);
    vkb_emu_image *r = e->images[rh((uint64_t)img)];
    while (r && r->image != img) r = r->next;
    pthread_mutex_unlock(&e->lock);
    return r;
}

static void untrack_image(vkb_emu_device *e, VkImage img)
{
    pthread_mutex_lock(&e->lock);
    vkb_emu_image **pp = &e->images[rh((uint64_t)img)];
    while (*pp && (*pp)->image != img) pp = &(*pp)->next;
    vkb_emu_image *r = *pp;
    if (r) *pp = r->next;
    pthread_mutex_unlock(&e->lock);
    if (r) {
        vkb_emu_image_release(e, r);
        free(r);
    }
}

static void track_buffer(vkb_emu_device *e, VkBuffer b, VkDeviceSize size)
{
    vkb_emu_buffer *r = calloc(1, sizeof(*r));
    r->buffer = b;
    r->size = size;
    pthread_mutex_lock(&e->lock);
    unsigned h = rh((uint64_t)b);
    r->next = e->buffers[h];
    e->buffers[h] = r;
    pthread_mutex_unlock(&e->lock);
}

static VkDeviceSize buffer_size(vkb_emu_device *e, VkBuffer b)
{
    pthread_mutex_lock(&e->lock);
    vkb_emu_buffer *r = e->buffers[rh((uint64_t)b)];
    while (r && r->buffer != b) r = r->next;
    VkDeviceSize s = r ? r->size : 0;
    pthread_mutex_unlock(&e->lock);
    return s;
}

static void untrack_buffer(vkb_emu_device *e, VkBuffer b)
{
    pthread_mutex_lock(&e->lock);
    vkb_emu_buffer **pp = &e->buffers[rh((uint64_t)b)];
    while (*pp && (*pp)->buffer != b) pp = &(*pp)->next;
    vkb_emu_buffer *r = *pp;
    if (r) *pp = r->next;
    pthread_mutex_unlock(&e->lock);
    free(r);
}

vkb_emu_device *vkb_emu_cur(void)
{
    vkb_srv_table *t = vkb_srv_current_table();
    return t ? t->emu : NULL;
}

const vkb_dispatch *vkb_emu_real(void)
{
    return &vkb_srv_current_table()->real;
}

/* Removes the first structure of type t from a chain we own (decoded into the call's arena). */
void *vkb_emu_chain_take(const void *head_const, VkStructureType t)
{
    VkBaseOutStructure *prev = (VkBaseOutStructure *)head_const;
    while (prev->pNext) {
        if (prev->pNext->sType == t) {
            VkBaseOutStructure *found = prev->pNext;
            prev->pNext = found->pNext;
            return found;
        }
        prev = prev->pNext;
    }
    return NULL;
}

/* ------------------------------------------------------------------ resources */

static VKAPI_ATTR VkResult VKAPI_CALL emu_CreateBuffer(VkDevice device, const VkBufferCreateInfo *pCreateInfo,
                                                      const VkAllocationCallbacks *pAllocator, VkBuffer *pBuffer)
{
    vkb_emu_device *e = vkb_emu_cur();
    VkBufferCreateInfo *ci = (VkBufferCreateInfo *)pCreateInfo;
    if (e->flags & VKB_EMU_MAINT5) {
        VkBufferUsageFlags2CreateInfo *u = vkb_emu_chain_take(ci, VK_STRUCTURE_TYPE_BUFFER_USAGE_FLAGS_2_CREATE_INFO);
        if (u) ci->usage = (VkBufferUsageFlags)u->usage;
    }
    VkResult r = vkb_emu_real()->vkCreateBuffer(device, ci, pAllocator, pBuffer);
    if (r == VK_SUCCESS) track_buffer(e, *pBuffer, ci->size);
    return r;
}

static VKAPI_ATTR void VKAPI_CALL emu_DestroyBuffer(VkDevice device, VkBuffer buffer, const VkAllocationCallbacks *pAllocator)
{
    if (buffer) untrack_buffer(vkb_emu_cur(), buffer);
    vkb_emu_real()->vkDestroyBuffer(device, buffer, pAllocator);
}

static VKAPI_ATTR VkResult VKAPI_CALL emu_CreateBufferView(VkDevice device, const VkBufferViewCreateInfo *pCreateInfo,
                                                          const VkAllocationCallbacks *pAllocator, VkBufferView *pView)
{
    if (vkb_emu_cur()->flags & VKB_EMU_MAINT5) vkb_emu_chain_take(pCreateInfo, VK_STRUCTURE_TYPE_BUFFER_USAGE_FLAGS_2_CREATE_INFO);
    return vkb_emu_real()->vkCreateBufferView(device, pCreateInfo, pAllocator, pView);
}

static VKAPI_ATTR VkResult VKAPI_CALL emu_CreateImage(VkDevice device, const VkImageCreateInfo *pCreateInfo,
                                                     const VkAllocationCallbacks *pAllocator, VkImage *pImage)
{
    vkb_emu_device *e = vkb_emu_cur();
    VkImageCreateInfo *ci = (VkImageCreateInfo *)pCreateInfo;
    VkFormat client_format = ci->format;
    vkb_emu_image_create_info(e, ci);
    VkResult r = vkb_emu_real()->vkCreateImage(device, ci, pAllocator, pImage);
    if (r == VK_SUCCESS) vkb_emu_track_image(e, *pImage, ci, client_format);
    return r;
}

static VKAPI_ATTR void VKAPI_CALL emu_DestroyImage(VkDevice device, VkImage image, const VkAllocationCallbacks *pAllocator)
{
    if (image) untrack_image(vkb_emu_cur(), image);
    vkb_emu_real()->vkDestroyImage(device, image, pAllocator);
}

/* ------------------------------------------------------------------ pipelines */

/* Fold flags2 and create inline shader modules; temporaries are recorded in mods[]. */
static void prep_stage(VkDevice device, VkPipelineShaderStageCreateInfo *st, VkShaderModule *mods, uint32_t *nmods, uint32_t cap)
{
    if (st->module) return;
    VkShaderModuleCreateInfo *smci = vkb_emu_chain_take(st, VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
    if (!smci) return;
    VkShaderModule m = VK_NULL_HANDLE;
    smci->pNext = NULL;
    if (vkb_emu_real()->vkCreateShaderModule(device, smci, NULL, &m) == VK_SUCCESS) {
        st->module = m;
        if (*nmods < cap) mods[(*nmods)++] = m;
    }
}

static VKAPI_ATTR VkResult VKAPI_CALL emu_CreateGraphicsPipelines(VkDevice device, VkPipelineCache cache, uint32_t n,
                                                                 const VkGraphicsPipelineCreateInfo *infos,
                                                                 const VkAllocationCallbacks *pAllocator, VkPipeline *pPipelines)
{
    vkb_emu_device *e = vkb_emu_cur();
    VkShaderModule mods[64];
    uint32_t nmods = 0;
    for (uint32_t i = 0; i < n; i++) {
        VkGraphicsPipelineCreateInfo *ci = (VkGraphicsPipelineCreateInfo *)&infos[i];
        if (e->flags & VKB_EMU_MAINT5) {
            VkPipelineCreateFlags2CreateInfo *f2 = vkb_emu_chain_take(ci, VK_STRUCTURE_TYPE_PIPELINE_CREATE_FLAGS_2_CREATE_INFO);
            if (f2) ci->flags = (VkPipelineCreateFlags)f2->flags;
            for (uint32_t s = 0; s < ci->stageCount; s++)
                prep_stage(device, (VkPipelineShaderStageCreateInfo *)&ci->pStages[s], mods, &nmods, 64);
        }
        vkb_emu_graphics_pipeline_info(e, ci);
    }
    VkResult r = vkb_emu_real()->vkCreateGraphicsPipelines(device, cache, n, infos, pAllocator, pPipelines);
    for (uint32_t i = 0; i < nmods; i++) vkb_emu_real()->vkDestroyShaderModule(device, mods[i], NULL);
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL emu_CreateComputePipelines(VkDevice device, VkPipelineCache cache, uint32_t n,
                                                                const VkComputePipelineCreateInfo *infos,
                                                                const VkAllocationCallbacks *pAllocator, VkPipeline *pPipelines)
{
    vkb_emu_device *e = vkb_emu_cur();
    VkShaderModule mods[64];
    uint32_t nmods = 0;
    for (uint32_t i = 0; i < n; i++) {
        VkComputePipelineCreateInfo *ci = (VkComputePipelineCreateInfo *)&infos[i];
        if (e->flags & VKB_EMU_MAINT5) {
            VkPipelineCreateFlags2CreateInfo *f2 = vkb_emu_chain_take(ci, VK_STRUCTURE_TYPE_PIPELINE_CREATE_FLAGS_2_CREATE_INFO);
            if (f2) ci->flags = (VkPipelineCreateFlags)f2->flags;
            prep_stage(device, &ci->stage, mods, &nmods, 64);
        }
    }
    VkResult r = vkb_emu_real()->vkCreateComputePipelines(device, cache, n, infos, pAllocator, pPipelines);
    for (uint32_t i = 0; i < nmods; i++) vkb_emu_real()->vkDestroyShaderModule(device, mods[i], NULL);
    return r;
}

/* ------------------------------------------------------------------ maintenance5 entry points */

static VKAPI_ATTR void VKAPI_CALL emu_CmdBindIndexBuffer2(VkCommandBuffer cb, VkBuffer buffer, VkDeviceSize offset, VkDeviceSize size,
                                                         VkIndexType type)
{
    (void)size;
    vkb_emu_real()->vkCmdBindIndexBuffer(cb, buffer, offset, type);
}

static VKAPI_ATTR void VKAPI_CALL emu_GetRenderingAreaGranularity(VkDevice device, const VkRenderingAreaInfo *info, VkExtent2D *g)
{
    (void)device;
    (void)info;
    g->width = 1;
    g->height = 1;
}

static VKAPI_ATTR void VKAPI_CALL emu_GetImageSubresourceLayout2(VkDevice device, VkImage image, const VkImageSubresource2 *sub,
                                                                VkSubresourceLayout2 *layout)
{
    vkb_emu_real()->vkGetImageSubresourceLayout(device, image, &sub->imageSubresource, &layout->subresourceLayout);
}

static VKAPI_ATTR void VKAPI_CALL emu_GetDeviceImageSubresourceLayout(VkDevice device, const VkDeviceImageSubresourceInfo *info,
                                                                     VkSubresourceLayout2 *layout)
{
    VkImage img;
    memset(&layout->subresourceLayout, 0, sizeof(layout->subresourceLayout));
    if (vkb_emu_real()->vkCreateImage(device, info->pCreateInfo, NULL, &img) != VK_SUCCESS) return;
    vkb_emu_real()->vkGetImageSubresourceLayout(device, img, &info->pSubresource->imageSubresource, &layout->subresourceLayout);
    vkb_emu_real()->vkDestroyImage(device, img, NULL);
}

/* VK_REMAINING_ARRAY_LAYERS in a copy region, resolved against the image. */
static void fix_layers(vkb_emu_device *e, VkImage img, VkImageSubresourceLayers *l)
{
    if (l->layerCount != VK_REMAINING_ARRAY_LAYERS) return;
    vkb_emu_image *r = vkb_emu_find_image(e, img);
    l->layerCount = r && r->layers > l->baseArrayLayer ? r->layers - l->baseArrayLayer : 1;
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdCopyImage(VkCommandBuffer cb, VkImage src, VkImageLayout sl, VkImage dst, VkImageLayout dl,
                                                  uint32_t n, const VkImageCopy *regions)
{
    vkb_emu_device *e = vkb_emu_cur();
    for (uint32_t i = 0; i < n; i++) {
        fix_layers(e, src, (VkImageSubresourceLayers *)&regions[i].srcSubresource);
        fix_layers(e, dst, (VkImageSubresourceLayers *)&regions[i].dstSubresource);
    }
    if (vkb_emu_copy_image(e, cb, src, sl, dst, dl, n, regions)) return;
    vkb_emu_real()->vkCmdCopyImage(cb, src, sl, dst, dl, n, regions);
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdCopyImage2(VkCommandBuffer cb, const VkCopyImageInfo2 *info)
{
    vkb_emu_device *e = vkb_emu_cur();
    for (uint32_t i = 0; i < info->regionCount; i++) {
        fix_layers(e, info->srcImage, (VkImageSubresourceLayers *)&info->pRegions[i].srcSubresource);
        fix_layers(e, info->dstImage, (VkImageSubresourceLayers *)&info->pRegions[i].dstSubresource);
    }
    if (vkb_emu_copy_image2(e, cb, info)) return;
    vkb_emu_real()->vkCmdCopyImage2(cb, info);
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdBlitImage(VkCommandBuffer cb, VkImage src, VkImageLayout sl, VkImage dst, VkImageLayout dl,
                                                  uint32_t n, const VkImageBlit *regions, VkFilter filter)
{
    vkb_emu_device *e = vkb_emu_cur();
    for (uint32_t i = 0; i < n; i++) {
        fix_layers(e, src, (VkImageSubresourceLayers *)&regions[i].srcSubresource);
        fix_layers(e, dst, (VkImageSubresourceLayers *)&regions[i].dstSubresource);
    }
    vkb_emu_real()->vkCmdBlitImage(cb, src, sl, dst, dl, n, regions, filter);
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdBlitImage2(VkCommandBuffer cb, const VkBlitImageInfo2 *info)
{
    vkb_emu_device *e = vkb_emu_cur();
    for (uint32_t i = 0; i < info->regionCount; i++) {
        fix_layers(e, info->srcImage, (VkImageSubresourceLayers *)&info->pRegions[i].srcSubresource);
        fix_layers(e, info->dstImage, (VkImageSubresourceLayers *)&info->pRegions[i].dstSubresource);
    }
    vkb_emu_real()->vkCmdBlitImage2(cb, info);
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdResolveImage(VkCommandBuffer cb, VkImage src, VkImageLayout sl, VkImage dst, VkImageLayout dl,
                                                     uint32_t n, const VkImageResolve *regions)
{
    vkb_emu_device *e = vkb_emu_cur();
    for (uint32_t i = 0; i < n; i++) {
        fix_layers(e, src, (VkImageSubresourceLayers *)&regions[i].srcSubresource);
        fix_layers(e, dst, (VkImageSubresourceLayers *)&regions[i].dstSubresource);
    }
    vkb_emu_real()->vkCmdResolveImage(cb, src, sl, dst, dl, n, regions);
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdResolveImage2(VkCommandBuffer cb, const VkResolveImageInfo2 *info)
{
    vkb_emu_device *e = vkb_emu_cur();
    for (uint32_t i = 0; i < info->regionCount; i++) {
        fix_layers(e, info->srcImage, (VkImageSubresourceLayers *)&info->pRegions[i].srcSubresource);
        fix_layers(e, info->dstImage, (VkImageSubresourceLayers *)&info->pRegions[i].dstSubresource);
    }
    vkb_emu_real()->vkCmdResolveImage2(cb, info);
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdCopyBufferToImage(VkCommandBuffer cb, VkBuffer src, VkImage dst, VkImageLayout dl, uint32_t n,
                                                          const VkBufferImageCopy *regions)
{
    vkb_emu_device *e = vkb_emu_cur();
    for (uint32_t i = 0; i < n; i++) fix_layers(e, dst, (VkImageSubresourceLayers *)&regions[i].imageSubresource);
    if (vkb_emu_copy_buffer_to_image(e, cb, src, dst, dl, n, regions)) return;
    vkb_emu_real()->vkCmdCopyBufferToImage(cb, src, dst, dl, n, regions);
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdCopyBufferToImage2(VkCommandBuffer cb, const VkCopyBufferToImageInfo2 *info)
{
    vkb_emu_device *e = vkb_emu_cur();
    for (uint32_t i = 0; i < info->regionCount; i++)
        fix_layers(e, info->dstImage, (VkImageSubresourceLayers *)&info->pRegions[i].imageSubresource);
    if (vkb_emu_copy_buffer_to_image2(e, cb, info)) return;
    vkb_emu_real()->vkCmdCopyBufferToImage2(cb, info);
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdCopyImageToBuffer(VkCommandBuffer cb, VkImage src, VkImageLayout sl, VkBuffer dst, uint32_t n,
                                                          const VkBufferImageCopy *regions)
{
    vkb_emu_device *e = vkb_emu_cur();
    for (uint32_t i = 0; i < n; i++) fix_layers(e, src, (VkImageSubresourceLayers *)&regions[i].imageSubresource);
    vkb_emu_real()->vkCmdCopyImageToBuffer(cb, src, sl, dst, n, regions);
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdCopyImageToBuffer2(VkCommandBuffer cb, const VkCopyImageToBufferInfo2 *info)
{
    vkb_emu_device *e = vkb_emu_cur();
    for (uint32_t i = 0; i < info->regionCount; i++)
        fix_layers(e, info->srcImage, (VkImageSubresourceLayers *)&info->pRegions[i].imageSubresource);
    vkb_emu_real()->vkCmdCopyImageToBuffer2(cb, info);
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdBindVertexBuffers2(VkCommandBuffer cb, uint32_t first, uint32_t n, const VkBuffer *buffers,
                                                           const VkDeviceSize *offsets, const VkDeviceSize *sizes,
                                                           const VkDeviceSize *strides)
{
    vkb_emu_device *e = vkb_emu_cur();
    if (sizes) {
        VkDeviceSize *s = (VkDeviceSize *)sizes;
        for (uint32_t i = 0; i < n; i++) {
            if (s[i] != VK_WHOLE_SIZE || !buffers[i]) continue;
            VkDeviceSize total = buffer_size(e, buffers[i]);
            s[i] = total > offsets[i] ? total - offsets[i] : 0;
        }
    }
    vkb_emu_real()->vkCmdBindVertexBuffers2(cb, first, n, buffers, offsets, sizes, strides);
}

/* ------------------------------------------------------------------ install */

void vkb_emu_install(vkb_srv_table *dev)
{
    vkb_emu_device *e = dev->emu;
    vkb_dispatch *dt = &dev->dt;
    /* Resource records are needed by every emulation. */
    dt->vkCreateBuffer = emu_CreateBuffer;
    dt->vkDestroyBuffer = emu_DestroyBuffer;
    dt->vkCreateImage = emu_CreateImage;
    dt->vkDestroyImage = emu_DestroyImage;
    dt->vkCreateGraphicsPipelines = emu_CreateGraphicsPipelines;
    dt->vkCreateComputePipelines = emu_CreateComputePipelines;
    dt->vkCmdCopyImage = emu_CmdCopyImage;
    dt->vkCmdCopyBufferToImage = emu_CmdCopyBufferToImage;
    if (dev->real.vkCmdCopyImage2) dt->vkCmdCopyImage2 = emu_CmdCopyImage2;
    if (dev->real.vkCmdCopyBufferToImage2) dt->vkCmdCopyBufferToImage2 = emu_CmdCopyBufferToImage2;
    if (e->flags & VKB_EMU_MAINT5) {
        dt->vkCreateBufferView = emu_CreateBufferView;
        dt->vkCmdBindIndexBuffer2 = emu_CmdBindIndexBuffer2;
        dt->vkGetRenderingAreaGranularity = emu_GetRenderingAreaGranularity;
        dt->vkGetImageSubresourceLayout2 = emu_GetImageSubresourceLayout2;
        dt->vkGetDeviceImageSubresourceLayout = emu_GetDeviceImageSubresourceLayout;
        dt->vkCmdBlitImage = emu_CmdBlitImage;
        dt->vkCmdResolveImage = emu_CmdResolveImage;
        dt->vkCmdCopyImageToBuffer = emu_CmdCopyImageToBuffer;
        if (dev->real.vkCmdBlitImage2) dt->vkCmdBlitImage2 = emu_CmdBlitImage2;
        if (dev->real.vkCmdResolveImage2) dt->vkCmdResolveImage2 = emu_CmdResolveImage2;
        if (dev->real.vkCmdCopyImageToBuffer2) dt->vkCmdCopyImageToBuffer2 = emu_CmdCopyImageToBuffer2;
        if (dev->real.vkCmdBindVertexBuffers2) dt->vkCmdBindVertexBuffers2 = emu_CmdBindVertexBuffers2;
    }
    vkb_emu_install_features(dev);
    VKB_INFO("device %u: emulation 0x%x installed", dev->id, e->flags);
}

void vkb_emu_uninstall(vkb_srv_table *dev)
{
    vkb_emu_device *e = dev->emu;
    vkb_emu_uninstall_features(dev);
    for (int i = 0; i < VKB_EMU_BUCKETS; i++) {
        while (e->images[i]) {
            vkb_emu_image *r = e->images[i];
            e->images[i] = r->next;
            vkb_emu_image_release(e, r);
            free(r);
        }
        while (e->buffers[i]) {
            vkb_emu_buffer *r = e->buffers[i];
            e->buffers[i] = r->next;
            free(r);
        }
    }
}
