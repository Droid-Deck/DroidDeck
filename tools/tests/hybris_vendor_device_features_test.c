#include "device_features.h"
#include <assert.h>
#include <stdlib.h>
#include <string.h>

static int clip = 1;
static int full_bc;
int hybris_clip_active(VkPhysicalDevice physical) { return clip; }
int hybris_vertex_stores_active(VkPhysicalDevice physical) { return 0; }
int hybris_vendor_full_bc_feature(VkPhysicalDevice physical) { return full_bc; }
void *hybris_scaled_alloc(const VkAllocationCallbacks *allocator, size_t size,
    VkSystemAllocationScope scope) { return calloc(1, size); }
void hybris_scaled_free(const VkAllocationCallbacks *allocator, void *memory) { free(memory); }

int main(void)
{
    VkPhysicalDevice physical = (VkPhysicalDevice)1;
    struct hybris_device_features filtered;

    VkPhysicalDeviceFeatures legacy = {
        .shaderClipDistance = VK_TRUE, .textureCompressionBC = VK_TRUE};
    VkDeviceCreateInfo legacy_info = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .pEnabledFeatures = &legacy};
    assert(hybris_device_features_prepare(physical, &legacy_info, NULL, &filtered) == VK_SUCCESS);
    assert(filtered.info.pEnabledFeatures != &legacy);
    assert(!filtered.info.pEnabledFeatures->shaderClipDistance);
    assert(filtered.info.pEnabledFeatures->textureCompressionBC &&
        legacy.shaderClipDistance && legacy.textureCompressionBC);
    hybris_device_features_release(&filtered, NULL);

    VkPhysicalDeviceFeatures2 features = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
        .features = {.shaderClipDistance = VK_TRUE, .textureCompressionBC = VK_TRUE}};
    VkPhysicalDeviceTimelineSemaphoreFeatures timeline = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES,
        .pNext = &features, .timelineSemaphore = VK_TRUE};
    VkPhysicalDeviceFeatures2 features_before = features;
    VkPhysicalDeviceTimelineSemaphoreFeatures timeline_before = timeline;
    VkDeviceCreateInfo chain_info = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .pNext = &timeline};
    assert(hybris_device_features_prepare(physical, &chain_info, NULL, &filtered) == VK_SUCCESS);
    const VkBaseInStructure *first = filtered.info.pNext;
    assert(first != (const VkBaseInStructure *)&timeline);
    const VkPhysicalDeviceFeatures2 *copied =
        (const VkPhysicalDeviceFeatures2 *)first->pNext;
    assert(copied->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2);
    assert(!copied->features.shaderClipDistance && copied->features.textureCompressionBC);
    assert(!memcmp(&timeline, &timeline_before, sizeof(timeline)));
    assert(!memcmp(&features, &features_before, sizeof(features)));
    hybris_device_features_release(&filtered, NULL);

    struct future_node { VkStructureType sType; const void *pNext; uint32_t value; } future = {
        (VkStructureType)0x7ffffff0, &features, 7};
    chain_info.pNext = &future;
    assert(hybris_device_features_prepare(physical, &chain_info, NULL, &filtered) ==
        VK_ERROR_EXTENSION_NOT_PRESENT);
    assert(future.pNext == &features && future.value == 7);
    assert(!memcmp(&features, &features_before, sizeof(features)));

    clip = 0;
    assert(hybris_device_features_prepare(physical, &legacy_info, NULL, &filtered) == VK_SUCCESS);
    assert(filtered.info.pEnabledFeatures == &legacy);
    hybris_device_features_release(&filtered, NULL);
    return 0;
}
