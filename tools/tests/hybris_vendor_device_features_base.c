/* SPDX-License-Identifier: Apache-2.0 */
#define VK_NO_PROTOTYPES
#include "device_features.h"
#include "../compat/vertex_stores.h"
#include "../compat/clip_distance.h"
#include "../compat/scaled_vertex.h"
#include <vulkan/vk_layer.h>
#include <stdio.h>
#include <string.h>

static size_t node_size(VkStructureType type)
{
    switch (type) {
    case VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO: return sizeof(VkLayerDeviceCreateInfo);
#include "device_create_sizes.inc"
    default: return 0;
    }
}

void hybris_device_features_release(struct hybris_device_features *filtered,
    const VkAllocationCallbacks *allocator)
{
    VkBaseOutStructure *node = filtered->copies;
    while (node && node != filtered->suffix) {
        VkBaseOutStructure *next = node->pNext;
        hybris_scaled_free(allocator, node);
        node = next;
    }
    filtered->copies = NULL;
}

VkResult hybris_device_features_prepare(VkPhysicalDevice physical,
    const VkDeviceCreateInfo *info, const VkAllocationCallbacks *allocator,
    struct hybris_device_features *out)
{
    *out = (struct hybris_device_features){.info = *info};
    int clip = hybris_clip_active(physical);
    int stores = hybris_vertex_stores_active(physical);
    if (!clip && !stores) return VK_SUCCESS;
    if (info->pEnabledFeatures) {
        out->features = *info->pEnabledFeatures;
        if (clip) out->features.shaderClipDistance = VK_FALSE;
        if (stores) out->features.vertexPipelineStoresAndAtomics = VK_FALSE;
        out->info.pEnabledFeatures = &out->features;
    }
    const VkBaseInStructure *target = info->pNext;
    while (target && target->sType != VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
        target = target->pNext;
    if (!target) return VK_SUCCESS;
    const VkPhysicalDeviceFeatures *requested = &((const VkPhysicalDeviceFeatures2 *)target)->features;
    if (!(clip && requested->shaderClipDistance) && !(stores && requested->vertexPipelineStoresAndAtomics))
        return VK_SUCCESS;

    /* Only copy the prefix needed to replace Features2. Preserve every other
     * payload and the untouched suffix; never write through application input
     * memory, which can be read-only or shared by concurrent device creates. */
    out->suffix = target->pNext;
    VkBaseOutStructure **slot = &out->copies;
    for (const VkBaseInStructure *node = info->pNext; ; node = node->pNext) {
        size_t size = node_size(node->sType);
        if (!size) {
            fprintf(stderr, "HYBRIS_DEVICE_FEATURES unknown prefix sType=%u\n", node->sType);
            hybris_device_features_release(out, allocator);
            return VK_ERROR_EXTENSION_NOT_PRESENT;
        }
        VkBaseOutStructure *copy = hybris_scaled_alloc(allocator, size, VK_SYSTEM_ALLOCATION_SCOPE_COMMAND);
        if (!copy) {
            hybris_device_features_release(out, allocator);
            return VK_ERROR_OUT_OF_HOST_MEMORY;
        }
        memcpy(copy, node, size);
        copy->pNext = NULL;
        *slot = copy;
        slot = &copy->pNext;
        if (node == target) {
            if (clip) ((VkPhysicalDeviceFeatures2 *)copy)->features.shaderClipDistance = VK_FALSE;
            if (stores) ((VkPhysicalDeviceFeatures2 *)copy)->features.vertexPipelineStoresAndAtomics = VK_FALSE;
            copy->pNext = (void *)out->suffix;
            break;
        }
    }
    out->info.pNext = out->copies;
    return VK_SUCCESS;
}
