/* The Xclipse layer between a simulated loader and a mock driver. The driver refuses what the compat
 * layer and Samsung's driver refuse: textureCompressionBC, and a compute layout holding an immutable
 * YCbCr sampler. Built by test_xclipse_layer.py with the layer source included directly. */
#include "xclipse_layer.c"
#include <assert.h>

struct object { void *dispatch; };
static int instance_table, device_table[4];
static struct object mock_instance = {&instance_table}, mock_physical = {&instance_table};
static struct object mock_devices[4] = {{&device_table[0]}, {&device_table[1]}, {&device_table[2]}, {&device_table[3]}};
static int devices_created;

/* What the driver last saw. */
static int driver_saw_bc, driver_saw_layout_sampler_count;
static VkSampler driver_layout_samplers[16];
static uint64_t next_sampler = 0x100;
static VkSampler driver_ycbcr[16];
static int driver_ycbcr_count;

static VkResult VKAPI_CALL mock_create_instance(const VkInstanceCreateInfo *info, const VkAllocationCallbacks *allocator,
                                                VkInstance *instance) {
    *instance = (VkInstance)&mock_instance;
    return VK_SUCCESS;
}

static void VKAPI_CALL mock_features(VkPhysicalDevice physical, VkPhysicalDeviceFeatures *features) {
    memset(features, 0, sizeof(*features));
}

static void VKAPI_CALL mock_features2(VkPhysicalDevice physical, VkPhysicalDeviceFeatures2 *features) {
    mock_features(physical, &features->features);
}

/* The compat layer's emulation: BC formats sample, the feature bit stays off. */
static void VKAPI_CALL mock_format_properties(VkPhysicalDevice physical, VkFormat format, VkFormatProperties *out) {
    memset(out, 0, sizeof(*out));
    if (format >= VK_FORMAT_BC1_RGB_UNORM_BLOCK && format <= VK_FORMAT_BC7_SRGB_BLOCK)
        out->optimalTilingFeatures = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
}

static void VKAPI_CALL mock_format_properties2(VkPhysicalDevice physical, VkFormat format, VkFormatProperties2 *out) {
    mock_format_properties(physical, format, &out->formatProperties);
}

static VkResult VKAPI_CALL mock_time_domains(VkPhysicalDevice physical, uint32_t *count, VkTimeDomainEXT *domains) {
    if (domains) { domains[0] = VK_TIME_DOMAIN_DEVICE_EXT; domains[1] = VK_TIME_DOMAIN_CLOCK_MONOTONIC_EXT; }
    *count = 2;
    return VK_SUCCESS;
}

static VkResult VKAPI_CALL mock_create_device(VkPhysicalDevice physical, const VkDeviceCreateInfo *info,
                                              const VkAllocationCallbacks *allocator, VkDevice *device) {
    driver_saw_bc = info->pEnabledFeatures && info->pEnabledFeatures->textureCompressionBC;
    for (const VkBaseInStructure *p = info->pNext; p; p = p->pNext)
        if (p->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2 &&
            ((const VkPhysicalDeviceFeatures2 *)p)->features.textureCompressionBC) driver_saw_bc = 1;
    if (driver_saw_bc) return VK_ERROR_FEATURE_NOT_PRESENT;
    assert(devices_created < 4);
    *device = (VkDevice)&mock_devices[devices_created++];
    return VK_SUCCESS;
}

static VkResult VKAPI_CALL mock_create_sampler(VkDevice device, const VkSamplerCreateInfo *info,
                                               const VkAllocationCallbacks *allocator, VkSampler *sampler) {
    *sampler = (VkSampler)next_sampler++;
    for (const VkBaseInStructure *p = info->pNext; p; p = p->pNext)
        if (p->sType == VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO) driver_ycbcr[driver_ycbcr_count++] = *sampler;
    return VK_SUCCESS;
}

static void VKAPI_CALL mock_destroy_sampler(VkDevice device, VkSampler sampler, const VkAllocationCallbacks *allocator) {
    for (int i = 0; i < driver_ycbcr_count; i++)
        if (driver_ycbcr[i] == sampler) driver_ycbcr[i] = driver_ycbcr[--driver_ycbcr_count];
}

static VkResult VKAPI_CALL mock_create_set_layout(VkDevice device, const VkDescriptorSetLayoutCreateInfo *info,
                                                  const VkAllocationCallbacks *allocator, VkDescriptorSetLayout *layout) {
    driver_saw_layout_sampler_count = 0;
    for (uint32_t b = 0; b < info->bindingCount; b++) {
        const VkDescriptorSetLayoutBinding *binding = &info->pBindings[b];
        for (uint32_t s = 0; binding->pImmutableSamplers && s < binding->descriptorCount; s++) {
            VkSampler sampler = binding->pImmutableSamplers[s];
            driver_layout_samplers[driver_saw_layout_sampler_count++] = sampler;
            for (int i = 0; i < driver_ycbcr_count; i++)
                if ((binding->stageFlags & VK_SHADER_STAGE_COMPUTE_BIT) && driver_ycbcr[i] == sampler)
                    return VK_ERROR_UNKNOWN;
        }
    }
    *layout = (VkDescriptorSetLayout)0x5000;
    return VK_SUCCESS;
}

static void VKAPI_CALL mock_destroy_device(VkDevice device, const VkAllocationCallbacks *allocator) {}

static VkResult VKAPI_CALL mock_timestamps(VkDevice device, uint32_t count, const VkCalibratedTimestampInfoEXT *infos,
                                           uint64_t *timestamps, uint64_t *deviation) {
    for (uint32_t i = 0; i < count; i++) timestamps[i] = 42;
    *deviation = 1;
    return VK_SUCCESS;
}

#define MOCK(name, function) if (!strcmp(requested, name)) return (PFN_vkVoidFunction)function
static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL mock_gipa(VkInstance instance, const char *requested) {
    MOCK("vkCreateInstance", mock_create_instance);
    MOCK("vkCreateDevice", mock_create_device);
    MOCK("vkGetPhysicalDeviceFeatures", mock_features);
    MOCK("vkGetPhysicalDeviceFeatures2", mock_features2);
    MOCK("vkGetPhysicalDeviceFormatProperties", mock_format_properties);
    MOCK("vkGetPhysicalDeviceFormatProperties2", mock_format_properties2);
    MOCK("vkGetPhysicalDeviceCalibrateableTimeDomainsEXT", mock_time_domains);
    return NULL;
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL mock_gdpa(VkDevice device, const char *requested) {
    MOCK("vkCreateSampler", mock_create_sampler);
    MOCK("vkDestroySampler", mock_destroy_sampler);
    MOCK("vkCreateDescriptorSetLayout", mock_create_set_layout);
    MOCK("vkDestroyDevice", mock_destroy_device);
    MOCK("vkGetCalibratedTimestampsEXT", mock_timestamps);
    return NULL;
}

/* The loader's link for one vkCreateDevice call, then the caller's own chain. */
static VkResult create(const void *chain, const VkPhysicalDeviceFeatures *enabled, VkDevice *device) {
    VkLayerDeviceLink link = {NULL, mock_gipa, mock_gdpa};
    VkLayerDeviceCreateInfo loader = {VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO, chain, VK_LAYER_LINK_INFO, {&link}};
    VkDeviceCreateInfo info = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, &loader, 0, 0, NULL, 0, NULL, 0, NULL, enabled};
    PFN_vkCreateDevice create_device = (PFN_vkCreateDevice)vkGetInstanceProcAddr((VkInstance)&mock_instance, "vkCreateDevice");
    return create_device((VkPhysicalDevice)&mock_physical, &info, NULL, device);
}

int main(void) {
    VkNegotiateLayerInterface negotiate = {LAYER_NEGOTIATE_INTERFACE_STRUCT, NULL, 2, NULL, NULL, NULL};
    assert(vkNegotiateLoaderLayerInterfaceVersion(&negotiate) == VK_SUCCESS);

    VkLayerInstanceLink instance_link = {NULL, mock_gipa, NULL};
    VkLayerInstanceCreateInfo instance_loader = {VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO, NULL, VK_LAYER_LINK_INFO,
                                                 {&instance_link}};
    VkInstanceCreateInfo instance_info = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pNext = &instance_loader};
    VkInstance instance;
    PFN_vkCreateInstance create_instance_fn = (PFN_vkCreateInstance)vkGetInstanceProcAddr(NULL, "vkCreateInstance");
    assert(create_instance_fn(&instance_info, NULL, &instance) == VK_SUCCESS);

    /* The emulated bit is reported... */
    VkPhysicalDeviceFeatures features;
    ((PFN_vkGetPhysicalDeviceFeatures)vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceFeatures"))(
        (VkPhysicalDevice)&mock_physical, &features);
    assert(features.textureCompressionBC);

    /* ...and cleared before the driver, through pEnabledFeatures... */
    VkDevice device;
    VkPhysicalDeviceFeatures legacy = {.textureCompressionBC = VK_TRUE};
    assert(create(NULL, &legacy, &device) == VK_SUCCESS && !driver_saw_bc && legacy.textureCompressionBC);

    /* ...and through a VkPhysicalDeviceFeatures2 behind another structure, leaving the caller's chain intact. */
    VkPhysicalDeviceVulkan12Features vk12 = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    VkPhysicalDeviceFeatures2 features2 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, &vk12,
                                           {.textureCompressionBC = VK_TRUE}};
    VkPhysicalDeviceTimelineSemaphoreFeatures timeline = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES,
                                                          &features2, VK_TRUE};
    VkPhysicalDeviceFeatures2 features2_before = features2;
    VkPhysicalDeviceTimelineSemaphoreFeatures timeline_before = timeline;
    VkDevice chained;
    assert(create(&timeline, NULL, &chained) == VK_SUCCESS && !driver_saw_bc);
    assert(!memcmp(&features2, &features2_before, sizeof(features2)));
    assert(!memcmp(&timeline, &timeline_before, sizeof(timeline)));

    /* A structure newer than the headers this was built with cannot be copied: the chain passes as it
     * is and the driver refuses the bit, without the caller's structures being written. */
    struct { VkStructureType sType; const void *pNext; uint32_t payload; } future = {(VkStructureType)0x7ffffff0, &features2, 7};
    VkDevice unused;
    assert(create(&future, NULL, &unused) == VK_ERROR_FEATURE_NOT_PRESENT);
    assert(!memcmp(&features2, &features2_before, sizeof(features2)) && future.pNext == &features2);

    /* Compute layouts get a plain sampler in place of a YCbCr one. */
    PFN_vkCreateSampler create_sampler_fn = (PFN_vkCreateSampler)vkGetDeviceProcAddr(device, "vkCreateSampler");
    PFN_vkDestroySampler destroy_sampler_fn = (PFN_vkDestroySampler)vkGetDeviceProcAddr(device, "vkDestroySampler");
    PFN_vkCreateDescriptorSetLayout create_layout = (PFN_vkCreateDescriptorSetLayout)vkGetDeviceProcAddr(device, "vkCreateDescriptorSetLayout");
    VkSamplerYcbcrConversionInfo conversion = {VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO, NULL, (VkSamplerYcbcrConversion)1};
    VkSamplerCreateInfo ycbcr_info = {.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO, .pNext = &conversion};
    VkSamplerCreateInfo plain_info = {.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    VkSampler ycbcr;
    assert(create_sampler_fn(device, &ycbcr_info, NULL, &ycbcr) == VK_SUCCESS);
    VkSampler immutable[2] = {ycbcr, ycbcr};
    VkDescriptorSetLayoutBinding binding = {4, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 2, VK_SHADER_STAGE_COMPUTE_BIT, immutable};
    VkDescriptorSetLayoutCreateInfo layout_info = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, NULL, 0, 1, &binding};
    VkDescriptorSetLayout layout;
    assert(create_layout(device, &layout_info, NULL, &layout) == VK_SUCCESS);
    assert(driver_layout_samplers[0] != ycbcr && immutable[0] == ycbcr);

    /* A destroyed YCbCr sampler's handle, reused for a plain sampler, is left alone. */
    destroy_sampler_fn(device, ycbcr, NULL);
    next_sampler = (uint64_t)ycbcr;
    VkSampler reused;
    assert(create_sampler_fn(device, &plain_info, NULL, &reused) == VK_SUCCESS && reused == ycbcr);
    immutable[0] = immutable[1] = reused;
    assert(create_layout(device, &layout_info, NULL, &layout) == VK_SUCCESS && driver_layout_samplers[0] == reused);

    /* Registrations belong to their device and go with it. */
    VkSampler other;
    assert(create_sampler_fn(chained, &ycbcr_info, NULL, &other) == VK_SUCCESS && ycbcr_count == 1);
    ((PFN_vkDestroyDevice)vkGetDeviceProcAddr(chained, "vkDestroyDevice"))(chained, NULL);
    assert(ycbcr_count == 0);

    /* The KHR calibrated-timestamp entry points reach the EXT ones. */
    uint32_t domains = 0;
    PFN_vkGetPhysicalDeviceCalibrateableTimeDomainsEXT time_domains = (PFN_vkGetPhysicalDeviceCalibrateableTimeDomainsEXT)
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceCalibrateableTimeDomainsKHR");
    assert(time_domains((VkPhysicalDevice)&mock_physical, &domains, NULL) == VK_SUCCESS && domains == 2);
    VkCalibratedTimestampInfoEXT stamp = {VK_STRUCTURE_TYPE_CALIBRATED_TIMESTAMP_INFO_EXT, NULL, VK_TIME_DOMAIN_DEVICE_EXT};
    uint64_t value = 0, deviation = 0;
    PFN_vkGetCalibratedTimestampsEXT timestamps = (PFN_vkGetCalibratedTimestampsEXT)vkGetDeviceProcAddr(device, "vkGetCalibratedTimestampsKHR");
    assert(timestamps(device, 1, &stamp, &value, &deviation) == VK_SUCCESS && value == 42);

    puts("xclipse layer ok");
    return 0;
}
