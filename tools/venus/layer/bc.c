/*
 * BC1-BC7 textures on a GPU without them: each BC image is created in an uncompressed format, and
 * every upload into one is decoded on the CPU while the copy is recorded. The decoded texels go
 * through a staging buffer of the layer's own, which lives until the command buffer is reset.
 * Uploads must come from memory the application has mapped, as DXVK's staging memory always is.
 */
#include "mali_compat.h"

#include <stdlib.h>
#include <string.h>

#define BCDEC_IMPLEMENTATION
#define BCDEC_STATIC
#define BCDEC_BC4BC5_PRECISE
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-function"
#include "bcdec.h"
#pragma GCC diagnostic pop

#define HANDLE(h) ((uint64_t)(uintptr_t)(h))
#define STAGING_BLOCK_SIZE (16u << 20)
#define STAGING_KEPT (64u << 20)

enum bc_kind { BC1, BC2, BC3, BC4, BC5, BC6H, BC7 };

struct bc_format {
    VkFormat format;
    VkFormat substitute;
    enum bc_kind kind;
    uint32_t block_bytes;
    uint32_t texel_bytes;
    bool is_signed;
    /* BC1's RGB formats have no alpha; its 1-bit transparency must read as opaque. */
    bool opaque;
};

static const struct bc_format bc_formats[] = {
    {VK_FORMAT_BC1_RGB_UNORM_BLOCK, VK_FORMAT_R8G8B8A8_UNORM, BC1, 8, 4, false, true},
    {VK_FORMAT_BC1_RGB_SRGB_BLOCK, VK_FORMAT_R8G8B8A8_SRGB, BC1, 8, 4, false, true},
    {VK_FORMAT_BC1_RGBA_UNORM_BLOCK, VK_FORMAT_R8G8B8A8_UNORM, BC1, 8, 4, false, false},
    {VK_FORMAT_BC1_RGBA_SRGB_BLOCK, VK_FORMAT_R8G8B8A8_SRGB, BC1, 8, 4, false, false},
    {VK_FORMAT_BC2_UNORM_BLOCK, VK_FORMAT_R8G8B8A8_UNORM, BC2, 16, 4, false, false},
    {VK_FORMAT_BC2_SRGB_BLOCK, VK_FORMAT_R8G8B8A8_SRGB, BC2, 16, 4, false, false},
    {VK_FORMAT_BC3_UNORM_BLOCK, VK_FORMAT_R8G8B8A8_UNORM, BC3, 16, 4, false, false},
    {VK_FORMAT_BC3_SRGB_BLOCK, VK_FORMAT_R8G8B8A8_SRGB, BC3, 16, 4, false, false},
    {VK_FORMAT_BC4_UNORM_BLOCK, VK_FORMAT_R8_UNORM, BC4, 8, 1, false, false},
    {VK_FORMAT_BC4_SNORM_BLOCK, VK_FORMAT_R8_SNORM, BC4, 8, 1, true, false},
    {VK_FORMAT_BC5_UNORM_BLOCK, VK_FORMAT_R8G8_UNORM, BC5, 16, 2, false, false},
    {VK_FORMAT_BC5_SNORM_BLOCK, VK_FORMAT_R8G8_SNORM, BC5, 16, 2, true, false},
    {VK_FORMAT_BC6H_UFLOAT_BLOCK, VK_FORMAT_R16G16B16A16_SFLOAT, BC6H, 16, 8, false, false},
    {VK_FORMAT_BC6H_SFLOAT_BLOCK, VK_FORMAT_R16G16B16A16_SFLOAT, BC6H, 16, 8, true, false},
    {VK_FORMAT_BC7_UNORM_BLOCK, VK_FORMAT_R8G8B8A8_UNORM, BC7, 16, 4, false, false},
    {VK_FORMAT_BC7_SRGB_BLOCK, VK_FORMAT_R8G8B8A8_SRGB, BC7, 16, 4, false, false},
};

static const struct bc_format *find_bc(VkFormat format)
{
    for (size_t i = 0; i < sizeof(bc_formats) / sizeof(bc_formats[0]); i++) {
        if (bc_formats[i].format == format)
            return &bc_formats[i];
    }
    return NULL;
}

VkFormat bc_substitute(VkFormat format)
{
    const struct bc_format *bc = find_bc(format);
    return bc ? bc->substitute : VK_FORMAT_UNDEFINED;
}

/* What a real BC format allows: sampling and copies. */
#define BC_FEATURES \
    (VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT | \
     VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_MINMAX_BIT | VK_FORMAT_FEATURE_TRANSFER_SRC_BIT | \
     VK_FORMAT_FEATURE_TRANSFER_DST_BIT | VK_FORMAT_FEATURE_BLIT_SRC_BIT)
#define BC_USAGE (VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)

static const struct bc_format *physical_bc(VkPhysicalDevice physical_device, VkFormat format, struct instance **inst)
{
    *inst = instance_from_handle(physical_device);
    if (!(physical_device_gaps(*inst, physical_device) & GAP_BC))
        return NULL;
    return find_bc(format);
}

VKAPI_ATTR void VKAPI_CALL GetPhysicalDeviceFormatProperties(VkPhysicalDevice physical_device, VkFormat format,
                                                             VkFormatProperties *properties)
{
    struct instance *inst;
    const struct bc_format *bc = physical_bc(physical_device, format, &inst);
    inst->GetPhysicalDeviceFormatProperties(physical_device, bc ? bc->substitute : format, properties);
    if (bc) {
        properties->optimalTilingFeatures &= BC_FEATURES;
        properties->linearTilingFeatures = 0;
        properties->bufferFeatures = 0;
    }
}

VKAPI_ATTR void VKAPI_CALL GetPhysicalDeviceFormatProperties2(VkPhysicalDevice physical_device, VkFormat format,
                                                              VkFormatProperties2 *properties)
{
    struct instance *inst;
    const struct bc_format *bc = physical_bc(physical_device, format, &inst);
    inst->GetPhysicalDeviceFormatProperties2(physical_device, bc ? bc->substitute : format, properties);
    if (!bc)
        return;
    properties->formatProperties.optimalTilingFeatures &= BC_FEATURES;
    properties->formatProperties.linearTilingFeatures = 0;
    properties->formatProperties.bufferFeatures = 0;
    for (VkBaseOutStructure *s = properties->pNext; s; s = s->pNext) {
        if (s->sType == VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_3) {
            VkFormatProperties3 *p3 = (VkFormatProperties3 *)s;
            p3->optimalTilingFeatures &= (VkFormatFeatureFlags2)BC_FEATURES;
            p3->linearTilingFeatures = 0;
            p3->bufferFeatures = 0;
        } else if (s->sType == VK_STRUCTURE_TYPE_DRM_FORMAT_MODIFIER_PROPERTIES_LIST_EXT) {
            ((VkDrmFormatModifierPropertiesListEXT *)s)->drmFormatModifierCount = 0;
        } else if (s->sType == VK_STRUCTURE_TYPE_DRM_FORMAT_MODIFIER_PROPERTIES_LIST_2_EXT) {
            ((VkDrmFormatModifierPropertiesList2EXT *)s)->drmFormatModifierCount = 0;
        }
    }
}

VKAPI_ATTR VkResult VKAPI_CALL GetPhysicalDeviceImageFormatProperties(
    VkPhysicalDevice physical_device, VkFormat format, VkImageType type, VkImageTiling tiling,
    VkImageUsageFlags usage, VkImageCreateFlags flags, VkImageFormatProperties *properties)
{
    struct instance *inst;
    const struct bc_format *bc = physical_bc(physical_device, format, &inst);
    if (!bc)
        return inst->GetPhysicalDeviceImageFormatProperties(physical_device, format, type, tiling, usage, flags,
                                                           properties);
    if (tiling != VK_IMAGE_TILING_OPTIMAL || (usage & ~BC_USAGE))
        return VK_ERROR_FORMAT_NOT_SUPPORTED;
    return inst->GetPhysicalDeviceImageFormatProperties(physical_device, bc->substitute, type, tiling, usage,
                                                       flags & ~VK_IMAGE_CREATE_BLOCK_TEXEL_VIEW_COMPATIBLE_BIT,
                                                       properties);
}

/* A view format list naming BC formats, as one naming their substitutes. */
struct format_list {
    VkImageFormatListCreateInfo *list;
    const VkFormat *original;
    VkFormat formats[16];
};

static void substitute_format_list(const void *chain, VkFormat substitute, struct format_list *saved)
{
    saved->list = NULL;
    for (const VkBaseInStructure *s = chain; s; s = s->pNext) {
        if (s->sType != VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO)
            continue;
        VkImageFormatListCreateInfo *list = (VkImageFormatListCreateInfo *)s;
        uint32_t count = 0;
        saved->formats[count++] = substitute;
        for (uint32_t i = 0; i < list->viewFormatCount && count < 16; i++) {
            VkFormat format = bc_substitute(list->pViewFormats[i]);
            /* Uncompressed views of the compressed blocks have nothing to alias any more. */
            if (format == VK_FORMAT_UNDEFINED)
                continue;
            bool seen = false;
            for (uint32_t k = 0; k < count; k++)
                seen |= saved->formats[k] == format;
            if (!seen)
                saved->formats[count++] = format;
        }
        saved->list = list;
        saved->original = list->pViewFormats;
        list->pViewFormats = saved->formats;
        list->viewFormatCount = count;
        return;
    }
}

static void restore_format_list(struct format_list *saved, uint32_t original_count)
{
    if (saved->list) {
        saved->list->pViewFormats = saved->original;
        saved->list->viewFormatCount = original_count;
    }
}

static uint32_t format_list_count(const void *chain)
{
    for (const VkBaseInStructure *s = chain; s; s = s->pNext) {
        if (s->sType == VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO)
            return ((const VkImageFormatListCreateInfo *)s)->viewFormatCount;
    }
    return 0;
}

VKAPI_ATTR VkResult VKAPI_CALL GetPhysicalDeviceImageFormatProperties2(
    VkPhysicalDevice physical_device, const VkPhysicalDeviceImageFormatInfo2 *info,
    VkImageFormatProperties2 *properties)
{
    struct instance *inst;
    const struct bc_format *bc = physical_bc(physical_device, info->format, &inst);
    if (!bc)
        return inst->GetPhysicalDeviceImageFormatProperties2(physical_device, info, properties);
    if (info->tiling != VK_IMAGE_TILING_OPTIMAL || (info->usage & ~BC_USAGE))
        return VK_ERROR_FORMAT_NOT_SUPPORTED;
    VkPhysicalDeviceImageFormatInfo2 copy = *info;
    copy.format = bc->substitute;
    copy.flags &= ~VK_IMAGE_CREATE_BLOCK_TEXEL_VIEW_COMPATIBLE_BIT;
    uint32_t list_count = format_list_count(info->pNext);
    struct format_list saved;
    substitute_format_list(info->pNext, bc->substitute, &saved);
    VkResult result = inst->GetPhysicalDeviceImageFormatProperties2(physical_device, &copy, properties);
    restore_format_list(&saved, list_count);
    return result;
}

struct bc_image {
    const struct bc_format *format;
    uint32_t array_layers;
};

static void substitute_image_info(const struct bc_format *bc, VkImageCreateInfo *info)
{
    info->format = bc->substitute;
    info->flags &= ~VK_IMAGE_CREATE_BLOCK_TEXEL_VIEW_COMPATIBLE_BIT;
    info->usage &= BC_USAGE;
}

VKAPI_ATTR VkResult VKAPI_CALL CreateImage(VkDevice device, const VkImageCreateInfo *info,
                                           const VkAllocationCallbacks *allocator, VkImage *image)
{
    struct device *dev = device_from_handle(device);
    const struct bc_format *bc = find_bc(info->format);
    if (!bc)
        return dev->CreateImage(device, info, allocator, image);

    VkImageCreateInfo copy = *info;
    substitute_image_info(bc, &copy);
    uint32_t list_count = format_list_count(info->pNext);
    struct format_list saved;
    substitute_format_list(info->pNext, bc->substitute, &saved);
    VkResult result = dev->CreateImage(device, &copy, allocator, image);
    restore_format_list(&saved, list_count);
    if (result != VK_SUCCESS)
        return result;

    struct bc_image *tracked = malloc(sizeof(*tracked));
    tracked->format = bc;
    tracked->array_layers = info->arrayLayers;
    pthread_mutex_lock(&dev->lock);
    map_put(&dev->images, HANDLE(*image), tracked);
    pthread_mutex_unlock(&dev->lock);
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL DestroyImage(VkDevice device, VkImage image, const VkAllocationCallbacks *allocator)
{
    struct device *dev = device_from_handle(device);
    pthread_mutex_lock(&dev->lock);
    free(map_remove(&dev->images, HANDLE(image)));
    pthread_mutex_unlock(&dev->lock);
    dev->DestroyImage(device, image, allocator);
}

static const struct bc_image *tracked_image(struct device *dev, VkImage image)
{
    pthread_mutex_lock(&dev->lock);
    const struct bc_image *tracked = map_get(&dev->images, HANDLE(image));
    pthread_mutex_unlock(&dev->lock);
    return tracked;
}

VKAPI_ATTR VkResult VKAPI_CALL CreateImageView(VkDevice device, const VkImageViewCreateInfo *info,
                                               const VkAllocationCallbacks *allocator, VkImageView *view)
{
    struct device *dev = device_from_handle(device);
    const struct bc_image *tracked = tracked_image(dev, info->image);
    if (!tracked)
        return dev->CreateImageView(device, info, allocator, view);
    VkImageViewCreateInfo copy = *info;
    VkFormat format = bc_substitute(info->format);
    copy.format = format != VK_FORMAT_UNDEFINED ? format : tracked->format->substitute;
    return dev->CreateImageView(device, &copy, allocator, view);
}

VKAPI_ATTR void VKAPI_CALL GetDeviceImageMemoryRequirements(VkDevice device,
                                                            const VkDeviceImageMemoryRequirements *info,
                                                            VkMemoryRequirements2 *requirements)
{
    struct device *dev = device_from_handle(device);
    const struct bc_format *bc = find_bc(info->pCreateInfo->format);
    if (!bc) {
        dev->GetDeviceImageMemoryRequirements(device, info, requirements);
        return;
    }
    VkImageCreateInfo image = *info->pCreateInfo;
    substitute_image_info(bc, &image);
    VkDeviceImageMemoryRequirements copy = *info;
    copy.pCreateInfo = &image;
    dev->GetDeviceImageMemoryRequirements(device, &copy, requirements);
}

VKAPI_ATTR void VKAPI_CALL GetDeviceImageSparseMemoryRequirements(
    VkDevice device, const VkDeviceImageMemoryRequirements *info, uint32_t *count,
    VkSparseImageMemoryRequirements2 *requirements)
{
    struct device *dev = device_from_handle(device);
    const struct bc_format *bc = find_bc(info->pCreateInfo->format);
    if (!bc) {
        dev->GetDeviceImageSparseMemoryRequirements(device, info, count, requirements);
        return;
    }
    VkImageCreateInfo image = *info->pCreateInfo;
    substitute_image_info(bc, &image);
    VkDeviceImageMemoryRequirements copy = *info;
    copy.pCreateInfo = &image;
    dev->GetDeviceImageSparseMemoryRequirements(device, &copy, count, requirements);
}

VKAPI_ATTR void VKAPI_CALL GetDeviceImageSubresourceLayout(VkDevice device,
                                                           const VkDeviceImageSubresourceInfo *info,
                                                           VkSubresourceLayout2 *layout)
{
    struct device *dev = device_from_handle(device);
    const struct bc_format *bc = find_bc(info->pCreateInfo->format);
    if (!bc) {
        dev->GetDeviceImageSubresourceLayout(device, info, layout);
        return;
    }
    VkImageCreateInfo image = *info->pCreateInfo;
    substitute_image_info(bc, &image);
    VkDeviceImageSubresourceInfo copy = *info;
    copy.pCreateInfo = &image;
    dev->GetDeviceImageSubresourceLayout(device, &copy, layout);
}

struct bound_buffer {
    VkDeviceMemory memory;
    VkDeviceSize offset;
};

static void track_binding(struct device *dev, VkBuffer buffer, VkDeviceMemory memory, VkDeviceSize offset)
{
    struct bound_buffer *bound = malloc(sizeof(*bound));
    bound->memory = memory;
    bound->offset = offset;
    pthread_mutex_lock(&dev->lock);
    free(map_remove(&dev->buffers, HANDLE(buffer)));
    map_put(&dev->buffers, HANDLE(buffer), bound);
    pthread_mutex_unlock(&dev->lock);
}

VKAPI_ATTR VkResult VKAPI_CALL BindBufferMemory(VkDevice device, VkBuffer buffer, VkDeviceMemory memory,
                                                VkDeviceSize offset)
{
    struct device *dev = device_from_handle(device);
    VkResult result = dev->BindBufferMemory(device, buffer, memory, offset);
    if (result == VK_SUCCESS)
        track_binding(dev, buffer, memory, offset);
    return result;
}

VKAPI_ATTR VkResult VKAPI_CALL BindBufferMemory2(VkDevice device, uint32_t count, const VkBindBufferMemoryInfo *infos)
{
    struct device *dev = device_from_handle(device);
    VkResult result = dev->BindBufferMemory2(device, count, infos);
    if (result == VK_SUCCESS) {
        for (uint32_t i = 0; i < count; i++)
            track_binding(dev, infos[i].buffer, infos[i].memory, infos[i].memoryOffset);
    }
    return result;
}

VKAPI_ATTR void VKAPI_CALL DestroyBuffer(VkDevice device, VkBuffer buffer, const VkAllocationCallbacks *allocator)
{
    struct device *dev = device_from_handle(device);
    pthread_mutex_lock(&dev->lock);
    free(map_remove(&dev->buffers, HANDLE(buffer)));
    pthread_mutex_unlock(&dev->lock);
    dev->DestroyBuffer(device, buffer, allocator);
}

/* The memory's start as the application sees it mapped. */
static void track_mapping(struct device *dev, VkDeviceMemory memory, VkDeviceSize offset, void *data)
{
    pthread_mutex_lock(&dev->lock);
    map_put(&dev->memories, HANDLE(memory), (uint8_t *)data - offset);
    pthread_mutex_unlock(&dev->lock);
}

static void forget_mapping(struct device *dev, VkDeviceMemory memory)
{
    pthread_mutex_lock(&dev->lock);
    map_remove(&dev->memories, HANDLE(memory));
    pthread_mutex_unlock(&dev->lock);
}

VKAPI_ATTR VkResult VKAPI_CALL MapMemory(VkDevice device, VkDeviceMemory memory, VkDeviceSize offset,
                                         VkDeviceSize size, VkMemoryMapFlags flags, void **data)
{
    struct device *dev = device_from_handle(device);
    VkResult result = dev->MapMemory(device, memory, offset, size, flags, data);
    if (result == VK_SUCCESS)
        track_mapping(dev, memory, offset, *data);
    return result;
}

VKAPI_ATTR VkResult VKAPI_CALL MapMemory2(VkDevice device, const VkMemoryMapInfo *info, void **data)
{
    struct device *dev = device_from_handle(device);
    VkResult result = dev->MapMemory2(device, info, data);
    if (result == VK_SUCCESS)
        track_mapping(dev, info->memory, info->offset, *data);
    return result;
}

VKAPI_ATTR void VKAPI_CALL UnmapMemory(VkDevice device, VkDeviceMemory memory)
{
    struct device *dev = device_from_handle(device);
    forget_mapping(dev, memory);
    dev->UnmapMemory(device, memory);
}

VKAPI_ATTR VkResult VKAPI_CALL UnmapMemory2(VkDevice device, const VkMemoryUnmapInfo *info)
{
    struct device *dev = device_from_handle(device);
    forget_mapping(dev, info->memory);
    return dev->UnmapMemory2(device, info);
}

VKAPI_ATTR void VKAPI_CALL FreeMemory(VkDevice device, VkDeviceMemory memory, const VkAllocationCallbacks *allocator)
{
    struct device *dev = device_from_handle(device);
    forget_mapping(dev, memory);
    dev->FreeMemory(device, memory, allocator);
}

struct staging_block {
    VkBuffer buffer;
    VkDeviceMemory memory;
    uint8_t *data;
    VkDeviceSize size;
    VkDeviceSize used;
    struct staging_block *next;
};

struct command_buffer {
    VkCommandPool pool;
    struct staging_block *staging;
};

static void destroy_block(struct device *dev, struct staging_block *block)
{
    dev->DestroyBuffer(dev->handle, block->buffer, NULL);
    dev->FreeMemory(dev->handle, block->memory, NULL);
    free(block);
}

static struct staging_block *create_block(struct device *dev, VkDeviceSize size)
{
    struct staging_block *block = calloc(1, sizeof(*block));
    block->size = size;
    VkBufferCreateInfo buffer_info = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size = size,
        .usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
    };
    if (dev->CreateBuffer(dev->handle, &buffer_info, NULL, &block->buffer) != VK_SUCCESS) {
        free(block);
        return NULL;
    }
    VkMemoryRequirements requirements;
    dev->GetBufferMemoryRequirements(dev->handle, block->buffer, &requirements);
    const VkMemoryPropertyFlags wanted = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    uint32_t type = UINT32_MAX;
    for (uint32_t i = 0; i < dev->memory_properties.memoryTypeCount && type == UINT32_MAX; i++) {
        if ((requirements.memoryTypeBits & (1u << i)) &&
            (dev->memory_properties.memoryTypes[i].propertyFlags & wanted) == wanted)
            type = i;
    }
    VkMemoryAllocateInfo allocate_info = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = requirements.size,
        .memoryTypeIndex = type,
    };
    void *data = NULL;
    if (type == UINT32_MAX || dev->AllocateMemory(dev->handle, &allocate_info, NULL, &block->memory) != VK_SUCCESS) {
        dev->DestroyBuffer(dev->handle, block->buffer, NULL);
        free(block);
        return NULL;
    }
    if (dev->BindBufferMemory(dev->handle, block->buffer, block->memory, 0) != VK_SUCCESS ||
        dev->MapMemory(dev->handle, block->memory, 0, VK_WHOLE_SIZE, 0, &data) != VK_SUCCESS) {
        destroy_block(dev, block);
        return NULL;
    }
    block->data = data;
    return block;
}

/* Called with the device lock held: the command buffer is done with its staging. */
static void recycle_staging(struct device *dev, struct command_buffer *state)
{
    VkDeviceSize kept = 0;
    for (struct staging_block *b = dev->free_staging; b; b = b->next)
        kept += b->size;
    while (state->staging) {
        struct staging_block *block = state->staging;
        state->staging = block->next;
        if (kept + block->size > STAGING_KEPT) {
            destroy_block(dev, block);
            continue;
        }
        block->used = 0;
        block->next = dev->free_staging;
        dev->free_staging = block;
        kept += block->size;
    }
}

/* Room for `size` bytes in a staging buffer that lives as long as the command buffer's recording. */
static struct staging_block *staging_for(struct device *dev, VkCommandBuffer cmd, VkDeviceSize size,
                                         VkDeviceSize *offset)
{
    pthread_mutex_lock(&dev->lock);
    struct command_buffer *state = map_get(&dev->command_buffers, HANDLE(cmd));
    if (!state) {
        state = calloc(1, sizeof(*state));
        map_put(&dev->command_buffers, HANDLE(cmd), state);
    }
    struct staging_block *block = state->staging;
    VkDeviceSize start = block ? (block->used + 63) & ~(VkDeviceSize)63 : 0;
    if (!block || start + size > block->size) {
        struct staging_block **link = &dev->free_staging;
        while (*link && (*link)->size < size)
            link = &(*link)->next;
        block = *link;
        if (block)
            *link = block->next;
        else
            block = create_block(dev, size > STAGING_BLOCK_SIZE ? size : STAGING_BLOCK_SIZE);
        if (block) {
            block->next = state->staging;
            state->staging = block;
        }
        start = 0;
    }
    if (block)
        block->used = start + size;
    pthread_mutex_unlock(&dev->lock);
    *offset = start;
    return block;
}

/* Where the application has the buffer's contents mapped, or NULL. */
static const uint8_t *mapped_buffer(struct device *dev, VkBuffer buffer)
{
    pthread_mutex_lock(&dev->lock);
    const struct bound_buffer *bound = map_get(&dev->buffers, HANDLE(buffer));
    const uint8_t *base = bound ? map_get(&dev->memories, HANDLE(bound->memory)) : NULL;
    const uint8_t *data = base ? base + bound->offset : NULL;
    pthread_mutex_unlock(&dev->lock);
    return data;
}

static void decode_block(const struct bc_format *bc, const uint8_t *block, uint8_t *texels)
{
    switch (bc->kind) {
    case BC1:
        bcdec_bc1(block, texels, 16);
        if (bc->opaque) {
            for (int i = 0; i < 16; i++)
                texels[i * 4 + 3] = 0xff;
        }
        break;
    case BC2:
        bcdec_bc2(block, texels, 16);
        break;
    case BC3:
        bcdec_bc3(block, texels, 16);
        break;
    case BC4:
        bcdec_bc4(block, texels, 4, bc->is_signed);
        break;
    case BC5:
        bcdec_bc5(block, texels, 8, bc->is_signed);
        break;
    case BC6H: {
        uint16_t rgb[16 * 3];
        bcdec_bc6h_half(block, rgb, 12, bc->is_signed);
        uint16_t *rgba = (uint16_t *)texels;
        for (int i = 0; i < 16; i++) {
            rgba[i * 4 + 0] = rgb[i * 3 + 0];
            rgba[i * 4 + 1] = rgb[i * 3 + 1];
            rgba[i * 4 + 2] = rgb[i * 3 + 2];
            rgba[i * 4 + 3] = 0x3c00;
        }
        break;
    }
    case BC7:
        bcdec_bc7(block, texels, 16);
        break;
    }
}

struct upload {
    VkDeviceSize offset;
    uint32_t row_length;
    uint32_t image_height;
    VkImageSubresourceLayers subresource;
    VkOffset3D image_offset;
    VkExtent3D extent;
};

static uint32_t warned;

static void warn_once(uint32_t bit, const char *message)
{
    if (!(__atomic_fetch_or(&warned, bit, __ATOMIC_RELAXED) & bit))
        layer_log("%s", message);
}

static void upload_region(struct device *dev, VkCommandBuffer cmd, const struct bc_image *image,
                          const uint8_t *source, VkImage dst, VkImageLayout layout, const struct upload *region)
{
    const struct bc_format *bc = image->format;
    uint32_t width = region->extent.width, height = region->extent.height;
    uint32_t layers = region->subresource.layerCount;
    if (layers == VK_REMAINING_ARRAY_LAYERS)
        layers = image->array_layers - region->subresource.baseArrayLayer;
    uint32_t slices = region->extent.depth * layers;
    uint32_t row_texels = region->row_length ? region->row_length : width;
    uint32_t height_texels = region->image_height ? region->image_height : height;
    size_t source_row = (size_t)((row_texels + 3) / 4) * bc->block_bytes;
    size_t source_slice = (size_t)((height_texels + 3) / 4) * source_row;
    size_t row_bytes = (size_t)width * bc->texel_bytes;
    VkDeviceSize size = (VkDeviceSize)row_bytes * height * slices;
    if (!size)
        return;

    VkDeviceSize offset;
    struct staging_block *block = staging_for(dev, cmd, size, &offset);
    if (!block) {
        warn_once(1u << 0, "out of staging memory for BC texture uploads; textures left empty");
        return;
    }
    uint8_t *out = block->data + offset;
    const uint8_t *in = source + region->offset;
    uint8_t texels[16 * 8];
    for (uint32_t s = 0; s < slices; s++) {
        for (uint32_t by = 0; by < (height + 3) / 4; by++) {
            for (uint32_t bx = 0; bx < (width + 3) / 4; bx++) {
                decode_block(bc, in + s * source_slice + by * source_row + (size_t)bx * bc->block_bytes, texels);
                for (uint32_t y = 0; y < 4 && by * 4 + y < height; y++) {
                    uint32_t columns = width - bx * 4 < 4 ? width - bx * 4 : 4;
                    memcpy(out + ((size_t)s * height + by * 4 + y) * row_bytes + (size_t)bx * 4 * bc->texel_bytes,
                           texels + (size_t)y * 4 * bc->texel_bytes, (size_t)columns * bc->texel_bytes);
                }
            }
        }
    }

    VkBufferImageCopy copy = {
        .bufferOffset = offset,
        .imageSubresource = region->subresource,
        .imageOffset = region->image_offset,
        .imageExtent = region->extent,
    };
    copy.imageSubresource.layerCount = layers;
    dev->CmdCopyBufferToImage(cmd, block->buffer, dst, layout, 1, &copy);
}

VKAPI_ATTR void VKAPI_CALL CmdCopyBufferToImage(VkCommandBuffer cmd, VkBuffer buffer, VkImage image,
                                                VkImageLayout layout, uint32_t count,
                                                const VkBufferImageCopy *regions)
{
    struct device *dev = device_from_handle(cmd);
    const struct bc_image *tracked = tracked_image(dev, image);
    if (!tracked) {
        dev->CmdCopyBufferToImage(cmd, buffer, image, layout, count, regions);
        return;
    }
    const uint8_t *source = mapped_buffer(dev, buffer);
    if (!source) {
        warn_once(1u << 1, "BC texture upload from memory that is not mapped; texture left empty");
        return;
    }
    for (uint32_t i = 0; i < count; i++) {
        struct upload region = {regions[i].bufferOffset, regions[i].bufferRowLength, regions[i].bufferImageHeight,
                                regions[i].imageSubresource, regions[i].imageOffset, regions[i].imageExtent};
        upload_region(dev, cmd, tracked, source, image, layout, &region);
    }
}

VKAPI_ATTR void VKAPI_CALL CmdCopyBufferToImage2(VkCommandBuffer cmd, const VkCopyBufferToImageInfo2 *info)
{
    struct device *dev = device_from_handle(cmd);
    const struct bc_image *tracked = tracked_image(dev, info->dstImage);
    if (!tracked) {
        dev->CmdCopyBufferToImage2(cmd, info);
        return;
    }
    const uint8_t *source = mapped_buffer(dev, info->srcBuffer);
    if (!source) {
        warn_once(1u << 1, "BC texture upload from memory that is not mapped; texture left empty");
        return;
    }
    for (uint32_t i = 0; i < info->regionCount; i++) {
        const VkBufferImageCopy2 *r = &info->pRegions[i];
        struct upload region = {r->bufferOffset, r->bufferRowLength, r->bufferImageHeight,
                                r->imageSubresource, r->imageOffset, r->imageExtent};
        upload_region(dev, cmd, tracked, source, info->dstImage, info->dstImageLayout, &region);
    }
}

/* Reading compressed blocks back would hand out decoded texels the buffer has no room for. */
VKAPI_ATTR void VKAPI_CALL CmdCopyImageToBuffer(VkCommandBuffer cmd, VkImage image, VkImageLayout layout,
                                                VkBuffer buffer, uint32_t count, const VkBufferImageCopy *regions)
{
    struct device *dev = device_from_handle(cmd);
    if (tracked_image(dev, image)) {
        warn_once(1u << 2, "BC texture read back into a buffer; skipped");
        return;
    }
    dev->CmdCopyImageToBuffer(cmd, image, layout, buffer, count, regions);
}

VKAPI_ATTR void VKAPI_CALL CmdCopyImageToBuffer2(VkCommandBuffer cmd, const VkCopyImageToBufferInfo2 *info)
{
    struct device *dev = device_from_handle(cmd);
    if (tracked_image(dev, info->srcImage)) {
        warn_once(1u << 2, "BC texture read back into a buffer; skipped");
        return;
    }
    dev->CmdCopyImageToBuffer2(cmd, info);
}

/* Between two BC images the texels line up; between BC and anything else they cannot. */
VKAPI_ATTR void VKAPI_CALL CmdCopyImage(VkCommandBuffer cmd, VkImage src, VkImageLayout src_layout, VkImage dst,
                                        VkImageLayout dst_layout, uint32_t count, const VkImageCopy *regions)
{
    struct device *dev = device_from_handle(cmd);
    if (!tracked_image(dev, src) != !tracked_image(dev, dst)) {
        warn_once(1u << 3, "copy between a BC texture and an uncompressed one; skipped");
        return;
    }
    dev->CmdCopyImage(cmd, src, src_layout, dst, dst_layout, count, regions);
}

VKAPI_ATTR void VKAPI_CALL CmdCopyImage2(VkCommandBuffer cmd, const VkCopyImageInfo2 *info)
{
    struct device *dev = device_from_handle(cmd);
    if (!tracked_image(dev, info->srcImage) != !tracked_image(dev, info->dstImage)) {
        warn_once(1u << 3, "copy between a BC texture and an uncompressed one; skipped");
        return;
    }
    dev->CmdCopyImage2(cmd, info);
}

VKAPI_ATTR VkResult VKAPI_CALL AllocateCommandBuffers(VkDevice device, const VkCommandBufferAllocateInfo *info,
                                                      VkCommandBuffer *buffers)
{
    struct device *dev = device_from_handle(device);
    VkResult result = dev->AllocateCommandBuffers(device, info, buffers);
    if (result != VK_SUCCESS)
        return result;
    pthread_mutex_lock(&dev->lock);
    for (uint32_t i = 0; i < info->commandBufferCount; i++) {
        struct command_buffer *state = calloc(1, sizeof(*state));
        state->pool = info->commandPool;
        map_put(&dev->command_buffers, HANDLE(buffers[i]), state);
    }
    pthread_mutex_unlock(&dev->lock);
    return result;
}

VKAPI_ATTR void VKAPI_CALL FreeCommandBuffers(VkDevice device, VkCommandPool pool, uint32_t count,
                                              const VkCommandBuffer *buffers)
{
    struct device *dev = device_from_handle(device);
    pthread_mutex_lock(&dev->lock);
    for (uint32_t i = 0; i < count; i++) {
        struct command_buffer *state = buffers[i] ? map_remove(&dev->command_buffers, HANDLE(buffers[i])) : NULL;
        if (state) {
            recycle_staging(dev, state);
            free(state);
        }
    }
    pthread_mutex_unlock(&dev->lock);
    dev->FreeCommandBuffers(device, pool, count, buffers);
}

static void recycle_command_buffer(struct device *dev, VkCommandBuffer cmd)
{
    pthread_mutex_lock(&dev->lock);
    struct command_buffer *state = map_get(&dev->command_buffers, HANDLE(cmd));
    if (state)
        recycle_staging(dev, state);
    pthread_mutex_unlock(&dev->lock);
}

VKAPI_ATTR VkResult VKAPI_CALL BeginCommandBuffer(VkCommandBuffer cmd, const VkCommandBufferBeginInfo *info)
{
    struct device *dev = device_from_handle(cmd);
    recycle_command_buffer(dev, cmd);
    return dev->BeginCommandBuffer(cmd, info);
}

VKAPI_ATTR VkResult VKAPI_CALL ResetCommandBuffer(VkCommandBuffer cmd, VkCommandBufferResetFlags flags)
{
    struct device *dev = device_from_handle(cmd);
    recycle_command_buffer(dev, cmd);
    return dev->ResetCommandBuffer(cmd, flags);
}

/* Every command buffer of the pool is done with its staging; `forget` drops them as well. */
static void recycle_pool(struct device *dev, VkCommandPool pool, bool forget)
{
    pthread_mutex_lock(&dev->lock);
    struct handle_map *map = &dev->command_buffers;
    for (uint32_t i = 0; i < map->capacity;) {
        struct command_buffer *state = map->values[i];
        if (!map->keys[i] || state->pool != pool) {
            i++;
            continue;
        }
        recycle_staging(dev, state);
        if (forget) {
            /* Removal shifts a later entry into this slot, so look at it again. */
            free(map_remove(map, map->keys[i]));
            continue;
        }
        i++;
    }
    pthread_mutex_unlock(&dev->lock);
}

VKAPI_ATTR VkResult VKAPI_CALL ResetCommandPool(VkDevice device, VkCommandPool pool, VkCommandPoolResetFlags flags)
{
    struct device *dev = device_from_handle(device);
    recycle_pool(dev, pool, false);
    return dev->ResetCommandPool(device, pool, flags);
}

VKAPI_ATTR void VKAPI_CALL DestroyCommandPool(VkDevice device, VkCommandPool pool,
                                              const VkAllocationCallbacks *allocator)
{
    struct device *dev = device_from_handle(device);
    recycle_pool(dev, pool, true);
    dev->DestroyCommandPool(device, pool, allocator);
}

void bc_destroy_device(struct device *dev)
{
    struct handle_map *maps[] = {&dev->images, &dev->buffers, &dev->command_buffers};
    for (size_t m = 0; m < sizeof(maps) / sizeof(maps[0]); m++) {
        for (uint32_t i = 0; i < maps[m]->capacity; i++) {
            if (!maps[m]->keys[i])
                continue;
            if (maps[m] == &dev->command_buffers)
                recycle_staging(dev, maps[m]->values[i]);
            free(maps[m]->values[i]);
        }
        map_free(maps[m]);
    }
    map_free(&dev->memories);
    while (dev->free_staging) {
        struct staging_block *block = dev->free_staging;
        dev->free_staging = block->next;
        destroy_block(dev, block);
    }
}
