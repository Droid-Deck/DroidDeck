/*
 * The Mali compatibility layer's draw and descriptor emulation (added to David Cimo's layer):
 *
 * - multiDrawIndirect: an indirect draw with drawCount > 1 is recorded as drawCount single draws
 *   at offset + i * stride. Mali-G57 (Bifrost/Valhall v9) has none, and DXVK refuses the device.
 * - robustness2's nullDescriptor: VK_NULL_HANDLE buffers, image views and vertex buffers in
 *   descriptor writes (plain, template and push) and vertex-buffer binds are replaced with small
 *   stand-ins the layer owns. robustBufferAccess2 itself maps to robustBufferAccess (layer.c).
 *   A null texel-buffer view gets an RGBA8 (uniform) or R32_UINT (storage) view of the stand-in
 *   buffer: its format is not known here, but Zink leaves them unbound and never reads them.
 */
#include <stdlib.h>
#include <string.h>

#include "mali_compat.h"

/* --- multiDrawIndirect --- */

VKAPI_ATTR void VKAPI_CALL CmdDrawIndirect(VkCommandBuffer cmd, VkBuffer buffer, VkDeviceSize offset,
                                           uint32_t draw_count, uint32_t stride)
{
    struct device *dev = device_from_handle(cmd);
    if (draw_count <= 1) {
        dev->CmdDrawIndirect(cmd, buffer, offset, draw_count, stride);
        return;
    }
    for (uint32_t i = 0; i < draw_count; i++)
        dev->CmdDrawIndirect(cmd, buffer, offset + (VkDeviceSize)i * stride, 1, stride);
}

VKAPI_ATTR void VKAPI_CALL CmdDrawIndexedIndirect(VkCommandBuffer cmd, VkBuffer buffer, VkDeviceSize offset,
                                                  uint32_t draw_count, uint32_t stride)
{
    struct device *dev = device_from_handle(cmd);
    if (draw_count <= 1) {
        dev->CmdDrawIndexedIndirect(cmd, buffer, offset, draw_count, stride);
        return;
    }
    for (uint32_t i = 0; i < draw_count; i++)
        dev->CmdDrawIndexedIndirect(cmd, buffer, offset + (VkDeviceSize)i * stride, 1, stride);
}

/* --- nullDescriptor stand-ins --- */

/* A template's entries, to find the null handles in the data it is given. */
struct template_info {
    uint32_t count;
    VkDescriptorUpdateTemplateEntry *entries;
    size_t size; /* bytes the data spans */
};

#define NULL_BUFFER_SIZE 65536

static uint32_t memory_type(const struct device *dev, uint32_t bits, VkMemoryPropertyFlags want)
{
    for (uint32_t i = 0; i < dev->memory_properties.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (dev->memory_properties.memoryTypes[i].propertyFlags & want) == want)
            return i;
    for (uint32_t i = 0; i < dev->memory_properties.memoryTypeCount; i++)
        if (bits & (1u << i))
            return i;
    return 0;
}

/* Made once per device, under its lock, the first time a null descriptor is seen. */
static bool null_resources(struct device *dev)
{
    if (dev->null_ready)
        return dev->null_ready > 0;
    dev->null_ready = -1;

    VkBufferCreateInfo binfo = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size = NULL_BUFFER_SIZE,
        .usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
                 VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT |
                 VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_TEXEL_BUFFER_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
    };
    if (dev->CreateBuffer(dev->handle, &binfo, NULL, &dev->null_buffer) != VK_SUCCESS)
        return false;
    VkMemoryRequirements breq;
    dev->GetBufferMemoryRequirements(dev->handle, dev->null_buffer, &breq);
    VkMemoryAllocateInfo balloc = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = breq.size,
        .memoryTypeIndex = memory_type(dev, breq.memoryTypeBits,
                                       VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT),
    };
    if (dev->AllocateMemory(dev->handle, &balloc, NULL, &dev->null_buffer_memory) != VK_SUCCESS)
        return false;
    dev->BindBufferMemory(dev->handle, dev->null_buffer, dev->null_buffer_memory, 0);
    /* A null buffer reads as zeros. */
    void *data = NULL;
    if ((dev->memory_properties.memoryTypes[balloc.memoryTypeIndex].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) &&
        dev->MapMemory(dev->handle, dev->null_buffer_memory, 0, VK_WHOLE_SIZE, 0, &data) == VK_SUCCESS) {
        memset(data, 0, (size_t)breq.size);
        dev->UnmapMemory(dev->handle, dev->null_buffer_memory);
    }

    VkImageCreateInfo iinfo = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = VK_FORMAT_R8G8B8A8_UNORM,
        .extent = {1, 1, 1},
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
    if (dev->CreateImage(dev->handle, &iinfo, NULL, &dev->null_image) != VK_SUCCESS)
        return false;
    VkMemoryRequirements ireq;
    dev->GetImageMemoryRequirements(dev->handle, dev->null_image, &ireq);
    VkMemoryAllocateInfo ialloc = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = ireq.size,
        .memoryTypeIndex = memory_type(dev, ireq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT),
    };
    if (dev->AllocateMemory(dev->handle, &ialloc, NULL, &dev->null_image_memory) != VK_SUCCESS)
        return false;
    dev->BindImageMemory(dev->handle, dev->null_image, dev->null_image_memory, 0);
    VkImageViewCreateInfo vinfo = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .image = dev->null_image,
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = VK_FORMAT_R8G8B8A8_UNORM,
        .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
    };
    if (dev->CreateImageView(dev->handle, &vinfo, NULL, &dev->null_view) != VK_SUCCESS)
        return false;
    /* Texel views are optional: a write that needs one and has none keeps its null. */
    VkBufferViewCreateInfo tinfo = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_VIEW_CREATE_INFO,
        .buffer = dev->null_buffer,
        .format = VK_FORMAT_R8G8B8A8_UNORM,
        .range = VK_WHOLE_SIZE,
    };
    if (dev->CreateBufferView(dev->handle, &tinfo, NULL, &dev->null_uniform_texel) != VK_SUCCESS)
        dev->null_uniform_texel = VK_NULL_HANDLE;
    tinfo.format = VK_FORMAT_R32_UINT;
    if (dev->CreateBufferView(dev->handle, &tinfo, NULL, &dev->null_storage_texel) != VK_SUCCESS)
        dev->null_storage_texel = VK_NULL_HANDLE;
    dev->null_ready = 1;
    layer_log("nullDescriptor: stand-in buffer and 1x1 image ready");
    return true;
}

void null_resources_destroy(struct device *dev)
{
    for (uint32_t i = 0; i < dev->templates.capacity; i++) {
        struct template_info *t = dev->templates.values ? dev->templates.values[i] : NULL;
        if (t) {
            free(t->entries);
            free(t);
        }
    }
    map_free(&dev->templates);
    if (dev->null_uniform_texel) dev->DestroyBufferView(dev->handle, dev->null_uniform_texel, NULL);
    if (dev->null_storage_texel) dev->DestroyBufferView(dev->handle, dev->null_storage_texel, NULL);
    if (dev->null_view) dev->DestroyImageView(dev->handle, dev->null_view, NULL);
    if (dev->null_image) dev->DestroyImage(dev->handle, dev->null_image, NULL);
    if (dev->null_image_memory) dev->FreeMemory(dev->handle, dev->null_image_memory, NULL);
    if (dev->null_buffer) dev->DestroyBuffer(dev->handle, dev->null_buffer, NULL);
    if (dev->null_buffer_memory) dev->FreeMemory(dev->handle, dev->null_buffer_memory, NULL);
}

static bool is_buffer_type(VkDescriptorType t)
{
    return t == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER || t == VK_DESCRIPTOR_TYPE_STORAGE_BUFFER ||
           t == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC || t == VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC;
}

static bool is_image_type(VkDescriptorType t)
{
    return t == VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE || t == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE ||
           t == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER || t == VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT;
}

static bool is_texel_type(VkDescriptorType t)
{
    return t == VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER || t == VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER;
}

static void fix_texel(struct device *dev, VkDescriptorType t, VkBufferView *view)
{
    if (*view != VK_NULL_HANDLE || !null_resources(dev))
        return;
    *view = t == VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER ? dev->null_storage_texel : dev->null_uniform_texel;
}

static void fix_buffer(struct device *dev, VkDescriptorBufferInfo *info)
{
    if (info->buffer != VK_NULL_HANDLE || !null_resources(dev))
        return;
    info->buffer = dev->null_buffer;
    info->offset = 0;
    info->range = VK_WHOLE_SIZE;
}

static void fix_image(struct device *dev, VkDescriptorImageInfo *info)
{
    if (info->imageView != VK_NULL_HANDLE || !null_resources(dev))
        return;
    info->imageView = dev->null_view;
}

/* Copies of the writes with stand-ins for null handles; NULL when nothing needed changing. */
static VkWriteDescriptorSet *fix_writes(struct device *dev, uint32_t count, const VkWriteDescriptorSet *writes,
                                        void ***scratch, uint32_t *scratch_count)
{
    VkWriteDescriptorSet *copies = NULL;
    for (uint32_t i = 0; i < count; i++) {
        const VkWriteDescriptorSet *w = &writes[i];
        bool null_found = false;
        for (uint32_t j = 0; j < w->descriptorCount && !null_found; j++) {
            if (is_buffer_type(w->descriptorType) && w->pBufferInfo && w->pBufferInfo[j].buffer == VK_NULL_HANDLE)
                null_found = true;
            if (is_image_type(w->descriptorType) && w->pImageInfo && w->pImageInfo[j].imageView == VK_NULL_HANDLE &&
                w->descriptorType != VK_DESCRIPTOR_TYPE_SAMPLER)
                null_found = true;
            if (is_texel_type(w->descriptorType) && w->pTexelBufferView && w->pTexelBufferView[j] == VK_NULL_HANDLE)
                null_found = true;
        }
        if (!null_found)
            continue;
        if (!copies) {
            copies = malloc(count * sizeof(*copies));
            memcpy(copies, writes, count * sizeof(*copies));
            *scratch = calloc(count, sizeof(void *));
        }
        if (is_buffer_type(w->descriptorType)) {
            VkDescriptorBufferInfo *b = malloc(w->descriptorCount * sizeof(*b));
            memcpy(b, w->pBufferInfo, w->descriptorCount * sizeof(*b));
            for (uint32_t j = 0; j < w->descriptorCount; j++)
                fix_buffer(dev, &b[j]);
            copies[i].pBufferInfo = b;
            (*scratch)[(*scratch_count)++] = b;
        } else if (is_texel_type(w->descriptorType)) {
            VkBufferView *v = malloc(w->descriptorCount * sizeof(*v));
            memcpy(v, w->pTexelBufferView, w->descriptorCount * sizeof(*v));
            for (uint32_t j = 0; j < w->descriptorCount; j++)
                fix_texel(dev, w->descriptorType, &v[j]);
            copies[i].pTexelBufferView = v;
            (*scratch)[(*scratch_count)++] = v;
        } else {
            VkDescriptorImageInfo *im = malloc(w->descriptorCount * sizeof(*im));
            memcpy(im, w->pImageInfo, w->descriptorCount * sizeof(*im));
            for (uint32_t j = 0; j < w->descriptorCount; j++)
                fix_image(dev, &im[j]);
            copies[i].pImageInfo = im;
            (*scratch)[(*scratch_count)++] = im;
        }
    }
    return copies;
}

static void free_scratch(void **scratch, uint32_t count)
{
    for (uint32_t i = 0; i < count; i++)
        free(scratch[i]);
    free(scratch);
}

VKAPI_ATTR void VKAPI_CALL UpdateDescriptorSets(VkDevice device, uint32_t write_count, const VkWriteDescriptorSet *writes,
                                                uint32_t copy_count, const VkCopyDescriptorSet *copies)
{
    struct device *dev = device_from_handle(device);
    pthread_mutex_lock(&dev->lock);
    void **scratch = NULL;
    uint32_t scratch_count = 0;
    VkWriteDescriptorSet *fixed = fix_writes(dev, write_count, writes, &scratch, &scratch_count);
    pthread_mutex_unlock(&dev->lock);
    dev->UpdateDescriptorSets(device, write_count, fixed ? fixed : writes, copy_count, copies);
    free(fixed);
    free_scratch(scratch, scratch_count);
}

VKAPI_ATTR void VKAPI_CALL CmdPushDescriptorSet(VkCommandBuffer cmd, VkPipelineBindPoint bind_point,
                                                VkPipelineLayout layout, uint32_t set, uint32_t write_count,
                                                const VkWriteDescriptorSet *writes)
{
    struct device *dev = device_from_handle(cmd);
    pthread_mutex_lock(&dev->lock);
    void **scratch = NULL;
    uint32_t scratch_count = 0;
    VkWriteDescriptorSet *fixed = fix_writes(dev, write_count, writes, &scratch, &scratch_count);
    pthread_mutex_unlock(&dev->lock);
    dev->CmdPushDescriptorSet(cmd, bind_point, layout, set, write_count, fixed ? fixed : writes);
    free(fixed);
    free_scratch(scratch, scratch_count);
}

static size_t entry_size(VkDescriptorType t)
{
    if (is_buffer_type(t))
        return sizeof(VkDescriptorBufferInfo);
    if (is_image_type(t) || t == VK_DESCRIPTOR_TYPE_SAMPLER)
        return sizeof(VkDescriptorImageInfo);
    if (t == VK_DESCRIPTOR_TYPE_INLINE_UNIFORM_BLOCK)
        return 1;
    return sizeof(uint64_t);
}

VKAPI_ATTR VkResult VKAPI_CALL CreateDescriptorUpdateTemplate(VkDevice device,
                                                              const VkDescriptorUpdateTemplateCreateInfo *info,
                                                              const VkAllocationCallbacks *allocator,
                                                              VkDescriptorUpdateTemplate *tmpl)
{
    struct device *dev = device_from_handle(device);
    VkResult result = dev->CreateDescriptorUpdateTemplate(device, info, allocator, tmpl);
    if (result != VK_SUCCESS)
        return result;
    struct template_info *t = calloc(1, sizeof(*t));
    t->count = info->descriptorUpdateEntryCount;
    t->entries = malloc(t->count * sizeof(*t->entries));
    memcpy(t->entries, info->pDescriptorUpdateEntries, t->count * sizeof(*t->entries));
    for (uint32_t i = 0; i < t->count; i++) {
        const VkDescriptorUpdateTemplateEntry *e = &t->entries[i];
        size_t end = e->descriptorType == VK_DESCRIPTOR_TYPE_INLINE_UNIFORM_BLOCK
            ? e->offset + e->descriptorCount
            : e->offset + (e->descriptorCount ? (e->descriptorCount - 1) * e->stride : 0) + entry_size(e->descriptorType);
        if (end > t->size)
            t->size = end;
    }
    pthread_mutex_lock(&dev->lock);
    map_put(&dev->templates, (uint64_t)*tmpl, t);
    pthread_mutex_unlock(&dev->lock);
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL DestroyDescriptorUpdateTemplate(VkDevice device, VkDescriptorUpdateTemplate tmpl,
                                                           const VkAllocationCallbacks *allocator)
{
    struct device *dev = device_from_handle(device);
    pthread_mutex_lock(&dev->lock);
    struct template_info *t = map_remove(&dev->templates, (uint64_t)tmpl);
    pthread_mutex_unlock(&dev->lock);
    if (t) {
        free(t->entries);
        free(t);
    }
    dev->DestroyDescriptorUpdateTemplate(device, tmpl, allocator);
}

/* A copy of the template's data with stand-ins for null handles; NULL when nothing changed. */
static void *fix_template_data(struct device *dev, VkDescriptorUpdateTemplate tmpl, const void *data)
{
    pthread_mutex_lock(&dev->lock);
    struct template_info *t = map_get(&dev->templates, (uint64_t)tmpl);
    void *copy = NULL;
    if (t && data) {
        for (uint32_t i = 0; i < t->count; i++) {
            const VkDescriptorUpdateTemplateEntry *e = &t->entries[i];
            bool buffer = is_buffer_type(e->descriptorType);
            bool image = is_image_type(e->descriptorType);
            bool texel = is_texel_type(e->descriptorType);
            if (!buffer && !image && !texel)
                continue;
            for (uint32_t j = 0; j < e->descriptorCount; j++) {
                size_t at = e->offset + (size_t)j * e->stride;
                const char *src = (const char *)(copy ? copy : data) + at;
                bool null_handle = buffer  ? ((const VkDescriptorBufferInfo *)src)->buffer == VK_NULL_HANDLE
                                   : texel ? *(const VkBufferView *)src == VK_NULL_HANDLE
                                           : ((const VkDescriptorImageInfo *)src)->imageView == VK_NULL_HANDLE;
                if (!null_handle)
                    continue;
                if (!copy) {
                    copy = malloc(t->size);
                    memcpy(copy, data, t->size);
                }
                if (buffer)
                    fix_buffer(dev, (VkDescriptorBufferInfo *)((char *)copy + at));
                else if (texel)
                    fix_texel(dev, e->descriptorType, (VkBufferView *)((char *)copy + at));
                else
                    fix_image(dev, (VkDescriptorImageInfo *)((char *)copy + at));
            }
        }
    }
    pthread_mutex_unlock(&dev->lock);
    return copy;
}

VKAPI_ATTR void VKAPI_CALL UpdateDescriptorSetWithTemplate(VkDevice device, VkDescriptorSet set,
                                                           VkDescriptorUpdateTemplate tmpl, const void *data)
{
    struct device *dev = device_from_handle(device);
    void *fixed = fix_template_data(dev, tmpl, data);
    dev->UpdateDescriptorSetWithTemplate(device, set, tmpl, fixed ? fixed : data);
    free(fixed);
}

VKAPI_ATTR void VKAPI_CALL CmdPushDescriptorSetWithTemplate(VkCommandBuffer cmd, VkDescriptorUpdateTemplate tmpl,
                                                            VkPipelineLayout layout, uint32_t set, const void *data)
{
    struct device *dev = device_from_handle(cmd);
    void *fixed = fix_template_data(dev, tmpl, data);
    dev->CmdPushDescriptorSetWithTemplate(cmd, tmpl, layout, set, fixed ? fixed : data);
    free(fixed);
}

/* Null vertex buffers read as zeros: bound to the stand-in at offset 0. */
VKAPI_ATTR void VKAPI_CALL CmdBindVertexBuffers(VkCommandBuffer cmd, uint32_t first, uint32_t count,
                                                const VkBuffer *buffers, const VkDeviceSize *offsets)
{
    struct device *dev = device_from_handle(cmd);
    VkBuffer fixed[64];
    VkDeviceSize fixed_offsets[64];
    bool any = false;
    for (uint32_t i = 0; i < count && i < 64; i++) any |= buffers[i] == VK_NULL_HANDLE;
    if (!any || count > 64) {
        dev->CmdBindVertexBuffers(cmd, first, count, buffers, offsets);
        return;
    }
    pthread_mutex_lock(&dev->lock);
    bool ready = null_resources(dev);
    pthread_mutex_unlock(&dev->lock);
    for (uint32_t i = 0; i < count; i++) {
        fixed[i] = buffers[i] == VK_NULL_HANDLE && ready ? dev->null_buffer : buffers[i];
        fixed_offsets[i] = buffers[i] == VK_NULL_HANDLE ? 0 : offsets[i];
    }
    dev->CmdBindVertexBuffers(cmd, first, count, fixed, fixed_offsets);
}

VKAPI_ATTR void VKAPI_CALL CmdBindVertexBuffers2(VkCommandBuffer cmd, uint32_t first, uint32_t count,
                                                 const VkBuffer *buffers, const VkDeviceSize *offsets,
                                                 const VkDeviceSize *sizes, const VkDeviceSize *strides)
{
    struct device *dev = device_from_handle(cmd);
    VkBuffer fixed[64];
    VkDeviceSize fixed_offsets[64], fixed_sizes[64];
    bool any = false;
    for (uint32_t i = 0; i < count && i < 64; i++) any |= buffers[i] == VK_NULL_HANDLE;
    if (!any || count > 64) {
        dev->CmdBindVertexBuffers2(cmd, first, count, buffers, offsets, sizes, strides);
        return;
    }
    pthread_mutex_lock(&dev->lock);
    bool ready = null_resources(dev);
    pthread_mutex_unlock(&dev->lock);
    for (uint32_t i = 0; i < count; i++) {
        bool null_buffer = buffers[i] == VK_NULL_HANDLE;
        fixed[i] = null_buffer && ready ? dev->null_buffer : buffers[i];
        fixed_offsets[i] = null_buffer ? 0 : offsets[i];
        fixed_sizes[i] = sizes ? (null_buffer ? NULL_BUFFER_SIZE : sizes[i]) : 0;
    }
    dev->CmdBindVertexBuffers2(cmd, first, count, fixed, fixed_offsets, sizes ? fixed_sizes : NULL, strides);
}
