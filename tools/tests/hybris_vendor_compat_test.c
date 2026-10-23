#include "vendor_compat.h"
#include "../common/vendor_compat.h"
#include "../layer/layer.h"
#include <assert.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct physical_config {
    const char *name;
    uint32_t vendor_id, device_id, driver_version, api_version;
    int native_bc;
    unsigned bc_mask;
    int ycbcr_feature, khr_advertised, ext_advertised;
};
struct device_config {
    VkDevice handle;
    VkPhysicalDevice physical;
    int native_khr_proc, ext_supported, ext_enabled;
};

static VkInstance instance = (VkInstance)0x10;
static VkPhysicalDevice physical[] = {(VkPhysicalDevice)0x20, (VkPhysicalDevice)0x21};
static VkDevice device[] = {(VkDevice)0x30, (VkDevice)0x31};
static struct physical_config physicals[2];
static struct device_config devices[2];
static uint32_t physical_count = 1;
static uint32_t instance_api_version = VK_API_VERSION_1_3;
static int instance_native_domains_proc, instance_native_timestamps_proc;
static int instance_ext_proc = 1;
static int features2_core_proc = 1, features2_khr_proc = 1;
static uint64_t next_sampler = 0x100;
static VkSampler driver_ycbcr, driver_layout_sampler;
static int driver_layout_calls;
static int ext_domain_calls, ext_timestamp_calls;
static int native_domain_calls, native_timestamp_calls;
static int allocations, frees, fail_allocation = -1;
static int bionic_dlopen_calls;
static int bionic_dlsym_failures;
static char **bionic_environ;

static int physical_index(VkPhysicalDevice handle)
{
    for (int i = 0; i < 2; ++i) if (physical[i] == handle) return i;
    return -1;
}
static int device_index(VkDevice handle)
{
    for (int i = 0; i < 2; ++i) if (device[i] == handle) return i;
    return -1;
}

void *hybris_dlopen(const char *name, int flags)
{
    assert(!strcmp(name, "libc.so"));
    ++bionic_dlopen_calls;
    return (void *)0x50;
}
void *hybris_dlsym(void *handle, const char *name)
{
    assert(handle == (void *)0x50 && !strcmp(name, "environ"));
    if (bionic_dlsym_failures) { --bionic_dlsym_failures; return NULL; }
    return &bionic_environ;
}

static VkResult VKAPI_CALL enumerate_physical(VkInstance handle, uint32_t *count,
    VkPhysicalDevice *out)
{
    if (!out) { *count = physical_count; return VK_SUCCESS; }
    uint32_t written = *count < physical_count ? *count : physical_count;
    for (uint32_t i = 0; i < written; ++i) out[i] = physical[i];
    *count = written;
    return written < physical_count ? VK_INCOMPLETE : VK_SUCCESS;
}
static VkResult VKAPI_CALL enumerate_extensions(VkPhysicalDevice handle, const char *layer,
    uint32_t *count, VkExtensionProperties *out)
{
    int index = physical_index(handle);
    assert(index >= 0 && !layer);
    const char *names[2];
    uint32_t available = 0;
    if (physicals[index].khr_advertised)
        names[available++] = VK_KHR_CALIBRATED_TIMESTAMPS_EXTENSION_NAME;
    if (physicals[index].ext_advertised)
        names[available++] = VK_EXT_CALIBRATED_TIMESTAMPS_EXTENSION_NAME;
    if (!out) { *count = available; return VK_SUCCESS; }
    uint32_t written = *count < available ? *count : available;
    for (uint32_t i = 0; i < written; ++i)
        snprintf(out[i].extensionName, sizeof(out[i].extensionName), "%s", names[i]);
    *count = written;
    return written < available ? VK_INCOMPLETE : VK_SUCCESS;
}
static void VKAPI_CALL properties(VkPhysicalDevice handle, VkPhysicalDeviceProperties *out)
{
    int index = physical_index(handle);
    assert(index >= 0);
    memset(out, 0, sizeof(*out));
    out->vendorID = physicals[index].vendor_id;
    out->deviceID = physicals[index].device_id;
    out->driverVersion = physicals[index].driver_version;
    out->apiVersion = physicals[index].api_version;
    snprintf(out->deviceName, sizeof(out->deviceName), "%s", physicals[index].name);
}
static void VKAPI_CALL features(VkPhysicalDevice handle, VkPhysicalDeviceFeatures *out)
{
    int index = physical_index(handle);
    assert(index >= 0);
    memset(out, 0, sizeof(*out));
    out->textureCompressionBC = physicals[index].native_bc;
}
static void VKAPI_CALL features2(VkPhysicalDevice handle, VkPhysicalDeviceFeatures2 *out)
{
    int index = physical_index(handle);
    assert(index >= 0);
    features(handle, &out->features);
    for (VkBaseOutStructure *node = out->pNext; node; node = node->pNext)
        if (node->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES)
            ((VkPhysicalDeviceSamplerYcbcrConversionFeatures *)node)->samplerYcbcrConversion =
                physicals[index].ycbcr_feature;
}
static VkResult VKAPI_CALL domains_ext(VkPhysicalDevice handle, uint32_t *count,
    VkTimeDomainEXT *domains)
{
    ++ext_domain_calls;
    if (domains) domains[0] = VK_TIME_DOMAIN_DEVICE_EXT;
    *count = 1;
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL domains_khr(VkPhysicalDevice handle, uint32_t *count,
    VkTimeDomainKHR *domains)
{
    ++native_domain_calls;
    if (domains) domains[0] = VK_TIME_DOMAIN_DEVICE_KHR;
    *count = 1;
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL timestamps_ext(VkDevice handle, uint32_t count,
    const VkCalibratedTimestampInfoEXT *infos, uint64_t *timestamps, uint64_t *deviation)
{
    ++ext_timestamp_calls;
    for (uint32_t i = 0; i < count; ++i) timestamps[i] = 42;
    *deviation = 1;
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL timestamps_khr_native(VkDevice handle, uint32_t count,
    const VkCalibratedTimestampInfoKHR *infos, uint64_t *timestamps, uint64_t *deviation)
{
    ++native_timestamp_calls;
    for (uint32_t i = 0; i < count; ++i) timestamps[i] = 84;
    *deviation = 2;
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL create_sampler(VkDevice handle, const VkSamplerCreateInfo *info,
    const VkAllocationCallbacks *allocator, VkSampler *out)
{
    *out = (VkSampler)(uintptr_t)next_sampler++;
    for (const VkBaseInStructure *node = info->pNext; node; node = node->pNext)
        if (node->sType == VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO)
            driver_ycbcr = *out;
    return VK_SUCCESS;
}
static void VKAPI_CALL destroy_sampler(VkDevice handle, VkSampler sampler,
    const VkAllocationCallbacks *allocator)
{
    if (driver_ycbcr == sampler) driver_ycbcr = VK_NULL_HANDLE;
}
static VkResult VKAPI_CALL create_layout(VkDevice handle,
    const VkDescriptorSetLayoutCreateInfo *info, const VkAllocationCallbacks *allocator,
    VkDescriptorSetLayout *out)
{
    ++driver_layout_calls;
    assert(info->bindingCount == 2);
    assert(info->pBindings[0].descriptorType == VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE);
    assert(info->pBindings[0].pImmutableSamplers == (const VkSampler *)(uintptr_t)1);
    driver_layout_sampler = info->pBindings[1].pImmutableSamplers[0];
    if ((info->pBindings[1].stageFlags & VK_SHADER_STAGE_COMPUTE_BIT) &&
        driver_layout_sampler == driver_ycbcr) return VK_ERROR_UNKNOWN;
    *out = (VkDescriptorSetLayout)0x40;
    return VK_SUCCESS;
}

#define PROC(value, symbol) if (!strcmp(name, value)) return (PFN_vkVoidFunction)symbol
static PFN_vkVoidFunction VKAPI_CALL instance_resolver(VkInstance handle, const char *name)
{
    PROC("vkEnumeratePhysicalDevices", enumerate_physical);
    PROC("vkEnumerateDeviceExtensionProperties", enumerate_extensions);
    PROC("vkGetPhysicalDeviceProperties", properties);
    PROC("vkGetPhysicalDeviceFeatures", features);
    if (!strcmp(name, "vkGetPhysicalDeviceFeatures2"))
        return features2_core_proc ? (PFN_vkVoidFunction)features2 : NULL;
    if (!strcmp(name, "vkGetPhysicalDeviceFeatures2KHR"))
        return features2_khr_proc ? (PFN_vkVoidFunction)features2 : NULL;
    if (!strcmp(name, "vkGetPhysicalDeviceCalibrateableTimeDomainsKHR"))
        return instance_native_domains_proc ? (PFN_vkVoidFunction)domains_khr : NULL;
    if (!strcmp(name, "vkGetPhysicalDeviceCalibrateableTimeDomainsEXT"))
        return instance_ext_proc ? (PFN_vkVoidFunction)domains_ext : NULL;
    if (!strcmp(name, "vkGetCalibratedTimestampsKHR"))
        return instance_native_timestamps_proc ? (PFN_vkVoidFunction)timestamps_khr_native : NULL;
    if (!strcmp(name, "vkGetCalibratedTimestampsEXT"))
        return instance_ext_proc ? (PFN_vkVoidFunction)timestamps_ext : NULL;
    return NULL;
}
static PFN_vkVoidFunction VKAPI_CALL device_resolver(VkDevice handle, const char *name)
{
    int index = device_index(handle);
    assert(index >= 0);
    PROC("vkCreateSampler", create_sampler);
    PROC("vkDestroySampler", destroy_sampler);
    PROC("vkCreateDescriptorSetLayout", create_layout);
    if (!strcmp(name, "vkGetCalibratedTimestampsKHR"))
        return devices[index].native_khr_proc ? (PFN_vkVoidFunction)timestamps_khr_native : NULL;
    if (!strcmp(name, "vkGetCalibratedTimestampsEXT"))
        return devices[index].ext_supported && devices[index].ext_enabled ?
            (PFN_vkVoidFunction)timestamps_ext : NULL;
    return NULL;
}

int hybris_layer_lookup_physical(VkPhysicalDevice handle, struct hybris_layer_physical *out)
{
    if (physical_index(handle) < 0) return 0;
    *out = (struct hybris_layer_physical){.instance = instance, .resolver = instance_resolver,
        .api_version = instance_api_version};
    return 1;
}
int hybris_layer_lookup_device(VkDevice handle, struct hybris_layer_device *out)
{
    int index = device_index(handle);
    if (index < 0) return 0;
    *out = (struct hybris_layer_device){.handle = handle, .physical = devices[index].physical,
        .resolver = device_resolver};
    return 1;
}
unsigned hybris_bc_physical_mask(VkPhysicalDevice handle)
{
    int index = physical_index(handle);
    return index < 0 ? 0 : physicals[index].bc_mask;
}
void *hybris_scaled_alloc(const VkAllocationCallbacks *allocator, size_t size,
    VkSystemAllocationScope scope)
{
    return allocator ? allocator->pfnAllocation(allocator->pUserData, size,
        _Alignof(max_align_t), scope) : calloc(1, size);
}
void hybris_scaled_free(const VkAllocationCallbacks *allocator, void *memory)
{
    if (!memory) return;
    if (allocator) allocator->pfnFree(allocator->pUserData, memory);
    else free(memory);
}
static void *VKAPI_PTR test_allocate(void *data, size_t size, size_t alignment,
    VkSystemAllocationScope scope)
{
    if (fail_allocation == allocations) {
        fail_allocation = -1;
        return NULL;
    }
    void *memory = malloc(size);
    assert(memory);
    memset(memory, 0xa5, size);
    ++allocations;
    return memory;
}
static void VKAPI_PTR test_free(void *data, void *memory)
{
    if (memory) { ++frees; free(memory); }
}

static void reset_configs(void)
{
    physicals[0] = (struct physical_config){.name = "Samsung Xclipse 540",
        .vendor_id = 0x144d, .device_id = 0x0540, .driver_version = 1,
        .api_version = VK_API_VERSION_1_3, .bc_mask = 0xffff, .ycbcr_feature = 1};
    physicals[1] = (struct physical_config){.name = "Mali-G715", .vendor_id = 0x13b5,
        .device_id = 0x0715, .driver_version = 2, .api_version = VK_API_VERSION_1_3,
        .bc_mask = 0xffff};
    devices[0] = (struct device_config){.handle = device[0], .physical = physical[0],
        .ext_supported = 1};
    devices[1] = (struct device_config){.handle = device[1], .physical = physical[1],
        .ext_supported = 1};
    physical_count = 1;
    instance_api_version = VK_API_VERSION_1_3;
    instance_native_domains_proc = instance_native_timestamps_proc = 0;
    instance_ext_proc = 1;
    features2_core_proc = features2_khr_proc = 1;
    next_sampler = 0x100;
    driver_ycbcr = driver_layout_sampler = VK_NULL_HANDLE;
    driver_layout_calls = 0;
    ext_domain_calls = ext_timestamp_calls = 0;
    native_domain_calls = native_timestamp_calls = 0;
}

static void *prepare_hal(void *path)
{
    return (void *)(uintptr_t)hybris_vendor_compat_prepare_vulkan_hal(path);
}

static int has_extension(const VkDeviceCreateInfo *info, const char *name)
{
    for (uint32_t i = 0; i < info->enabledExtensionCount; ++i)
        if (!strcmp(info->ppEnabledExtensionNames[i], name)) return 1;
    return 0;
}

static void native_create(int index, const VkDeviceCreateInfo *info)
{
    devices[index].ext_enabled = has_extension(info,
        VK_EXT_CALIBRATED_TIMESTAMPS_EXTENSION_NAME);
}

static void test_bionic_environment(int preserve_native)
{
    if (preserve_native) {
        static char *native[] = {"BIONIC_NATIVE=1", NULL};
        bionic_environ = native;
        assert(hybris_vendor_compat_prepare_vulkan_hal("/vendor/lib64/hw/vulkan.mali.so"));
        assert(hybris_vendor_compat_prepare_vulkan_hal("/vendor/lib64/hw/vulkan.samsung.so"));
        assert(bionic_environ == native && bionic_dlopen_calls == 1);
        return;
    }
    setenv("HYBRIS_VENDOR_TEST", "copied", 1);
    assert(hybris_vendor_compat_prepare_vulkan_hal(NULL));
    assert(!bionic_dlopen_calls && !bionic_environ);
    char *paths[] = {"/vendor/lib64/hw/vulkan.mali.so",
        "/vendor/lib64/hw/vulkan.powervr.so", "/vendor/lib64/hw/vulkan.samsung.so"};
    pthread_t threads[3];
    for (int i = 0; i < 3; ++i) assert(!pthread_create(&threads[i], NULL, prepare_hal, paths[i]));
    for (int i = 0; i < 3; ++i) {
        void *result = NULL;
        assert(!pthread_join(threads[i], &result) && (uintptr_t)result == 1);
    }
    char **snapshot = bionic_environ;
    assert(snapshot && bionic_dlopen_calls == 1);
    assert(hybris_vendor_compat_prepare_vulkan_hal("/vendor/lib64/hw/vulkan.powervr.so"));
    assert(hybris_vendor_compat_prepare_vulkan_hal("/vendor/lib64/hw/vulkan.samsung.so"));
    assert(bionic_environ == snapshot && bionic_dlopen_calls == 1);
    int copied = 0;
    for (char **entry = bionic_environ; *entry; ++entry)
        if (!strcmp(*entry, "HYBRIS_VENDOR_TEST=copied")) copied = 1;
    assert(copied);
}

static void test_xclipse_workarounds(void)
{
    unsetenv("HYBRIS_VULKAN_HAL");
    reset_configs();
    assert(!hybris_vendor_xclipse_physical(physical[0]));
    setenv("HYBRIS_VULKAN_HAL", "/vendor/lib64/hw/vulkan.samsung.so.bak", 1);
    assert(!hybris_vendor_xclipse_physical(physical[0]));
    setenv("HYBRIS_VULKAN_HAL", "/vendor/lib64/hw/vulkan.samsung.so", 1);
    assert(hybris_vendor_xclipse_physical(physical[0]));
    assert(hybris_vendor_xclipse_bc(physical[0]));
    physicals[0].bc_mask = 1u << 4;
    assert(hybris_vendor_xclipse_bc(physical[0]));
    assert(!hybris_vendor_full_bc_feature(physical[0]));
    physicals[0].bc_mask = 0xffff;
    assert(hybris_vendor_compat_reason(physical[0], HYBRIS_VENDOR_VIRTUAL_BC));
    assert(hybris_vendor_compat_reason(physical[0], HYBRIS_VENDOR_IMMUTABLE_YCBCR));
    physicals[0].native_bc = 1;
    assert(!hybris_vendor_xclipse_bc(physical[0]));
    physicals[0].native_bc = 0;
    physicals[0].bc_mask = 0;
    assert(!hybris_vendor_xclipse_bc(physical[0]));
    physicals[0].bc_mask = 0xffff;

    VkFormatProperties format = {.optimalTilingFeatures = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT};
    hybris_vendor_adjust_bc_format(physical[0], VK_FORMAT_BC4_UNORM_BLOCK, &format);
    assert(format.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT);
    format.optimalTilingFeatures = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
    hybris_vendor_adjust_bc_format(physical[0], VK_FORMAT_BC1_RGBA_UNORM_BLOCK, &format);
    assert(!(format.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT));

    instance_api_version = VK_API_VERSION_1_0;
    features2_core_proc = 0;
    assert(hybris_vendor_compat_reason(physical[0], HYBRIS_VENDOR_IMMUTABLE_YCBCR));
    VkAllocationCallbacks allocator = {.pfnAllocation = test_allocate, .pfnFree = test_free};
    VkDeviceCreateInfo device_info = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    assert(hybris_vendor_attach_device(device[0], physical[0], device_resolver,
        &device_info, NULL) == VK_SUCCESS);
    PFN_vkCreateSampler create = (PFN_vkCreateSampler)hybris_vendor_device_proc("vkCreateSampler");
    PFN_vkCreateDescriptorSetLayout layout = (PFN_vkCreateDescriptorSetLayout)
        hybris_vendor_device_proc("vkCreateDescriptorSetLayout");
    VkSamplerYcbcrConversionInfo conversion = {
        .sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO,
        .conversion = (VkSamplerYcbcrConversion)1};
    VkSamplerCreateInfo sampler_info = {
        .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO, .pNext = &conversion};
    VkSampler ycbcr;
    assert(create(device[0], &sampler_info, &allocator, &ycbcr) == VK_SUCCESS);
    VkSampler immutable[] = {ycbcr};
    VkDescriptorSetLayoutBinding bindings[] = {
        {.binding = 3, .descriptorType = VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
         .descriptorCount = 1, .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
         .pImmutableSamplers = (const VkSampler *)(uintptr_t)1},
        {.binding = 4, .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
         .descriptorCount = 1, .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
         .pImmutableSamplers = immutable},
    };
    VkDescriptorSetLayoutCreateInfo layout_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = 2, .pBindings = bindings};
    VkDescriptorSetLayout set_layout;
    assert(layout(device[0], &layout_info, &allocator, &set_layout) == VK_SUCCESS);
    assert(driver_layout_sampler != ycbcr && immutable[0] == ycbcr);
    int live = allocations - frees;
    int layout_calls = driver_layout_calls;
    for (int failure = 0; failure < 3; ++failure) {
        fail_allocation = allocations + failure;
        assert(layout(device[0], &layout_info, &allocator, &set_layout) ==
            VK_ERROR_OUT_OF_HOST_MEMORY);
        assert(fail_allocation == -1 && allocations - frees == live);
        assert(driver_layout_calls == layout_calls && immutable[0] == ycbcr);
    }
    assert(layout(device[0], &layout_info, &allocator, &set_layout) == VK_SUCCESS);
    assert(driver_layout_calls == layout_calls + 1 && allocations - frees == live);
    PFN_vkDestroySampler destroy = (PFN_vkDestroySampler)
        hybris_vendor_device_proc("vkDestroySampler");
    destroy(device[0], ycbcr, &allocator);
    hybris_vendor_release_device(device[0]);
    assert(allocations == frees);
}

static void test_calibrated_timestamp_policy(void)
{
    setenv("HYBRIS_VULKAN_HAL", "/vendor/lib64/hw/vulkan.mali.so", 1);
    reset_configs();
    physicals[0].name = "Mali-G715";
    physicals[0].vendor_id = 0x13b5;
    physicals[0].khr_advertised = physicals[0].ext_advertised = 1;
    devices[0].physical = physical[0];
    const char *enabled[] = {VK_KHR_CALIBRATED_TIMESTAMPS_EXTENSION_NAME};
    VkDeviceCreateInfo enabled_info = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .enabledExtensionCount = 1, .ppEnabledExtensionNames = enabled};
    VkDeviceCreateInfo disabled_info = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    struct hybris_vendor_device_create prepared;

    instance_native_domains_proc = instance_native_timestamps_proc = 1;
    devices[0].native_khr_proc = 1;
    assert(!hybris_vendor_physical_alias_proc(instance, instance_resolver,
        "vkGetPhysicalDeviceCalibrateableTimeDomainsKHR"));
    assert(hybris_vendor_prepare_device(physical[0], &enabled_info, NULL, &prepared) ==
        VK_SUCCESS);
    assert(prepared.extensions && prepared.info.enabledExtensionCount == 2);
    native_create(0, &prepared.info);
    assert(hybris_vendor_attach_device(device[0], physical[0], device_resolver,
        &prepared.info, NULL) == VK_SUCCESS);
    assert(!hybris_vendor_device_alias_proc(device[0], "vkGetCalibratedTimestampsKHR"));
    hybris_vendor_release_device(device[0]);
    hybris_vendor_release_device_create(&prepared, NULL);

    devices[0].native_khr_proc = 0;
    assert(!hybris_vendor_physical_alias_proc(instance, instance_resolver,
        "vkGetPhysicalDeviceCalibrateableTimeDomainsKHR"));
    assert(!hybris_vendor_physical_alias_proc(instance, instance_resolver,
        "vkGetCalibratedTimestampsKHR"));
    assert(hybris_vendor_prepare_device(physical[0], &enabled_info, NULL, &prepared) ==
        VK_SUCCESS);
    assert(prepared.extensions && prepared.info.enabledExtensionCount == 2);
    native_create(0, &prepared.info);
    assert(hybris_vendor_attach_device(device[0], physical[0], device_resolver,
        &prepared.info, NULL) == VK_SUCCESS);
    assert(hybris_vendor_device_alias_proc(device[0], "vkGetCalibratedTimestampsKHR"));
    hybris_vendor_release_device(device[0]);
    hybris_vendor_release_device_create(&prepared, NULL);

    instance_native_timestamps_proc = 0;
    instance_native_domains_proc = 0;
    physicals[0].khr_advertised = 0;
    assert(!hybris_vendor_physical_alias_proc(instance, instance_resolver,
        "vkGetPhysicalDeviceCalibrateableTimeDomainsKHR"));
    physicals[0].khr_advertised = 1;
    physicals[0].ext_advertised = 0;
    assert(!hybris_vendor_physical_alias_proc(instance, instance_resolver,
        "vkGetPhysicalDeviceCalibrateableTimeDomainsKHR"));
    physicals[0].ext_advertised = 1;
    assert(hybris_vendor_compat_reason(physical[0],
        HYBRIS_VENDOR_CALIBRATED_TIMESTAMPS_ALIAS));
    PFN_vkGetPhysicalDeviceCalibrateableTimeDomainsKHR query_domains =
        (PFN_vkGetPhysicalDeviceCalibrateableTimeDomainsKHR)
        hybris_vendor_physical_alias_proc(instance, instance_resolver,
            "vkGetPhysicalDeviceCalibrateableTimeDomainsKHR");
    assert(query_domains);
    assert(hybris_vendor_physical_alias_proc(instance, instance_resolver,
        "vkGetCalibratedTimestampsKHR"));
    uint32_t count = 0;
    assert(query_domains(physical[0], &count, NULL) == VK_SUCCESS && count == 1);
    assert(ext_domain_calls == 1 && !native_domain_calls);

    assert(hybris_vendor_prepare_device(physical[0], &disabled_info, NULL, &prepared) ==
        VK_SUCCESS);
    native_create(0, &prepared.info);
    assert(hybris_vendor_attach_device(device[0], physical[0], device_resolver,
        &prepared.info, NULL) == VK_SUCCESS);
    assert(!hybris_vendor_device_alias_proc(device[0], "vkGetCalibratedTimestampsKHR"));
    hybris_vendor_release_device(device[0]);
    hybris_vendor_release_device_create(&prepared, NULL);

    physical_count = 2;
    physicals[1].khr_advertised = physicals[1].ext_advertised = 1;
    struct hybris_vendor_device_create prepared0, prepared1;
    assert(hybris_vendor_prepare_device(physical[0], &enabled_info, NULL, &prepared0) ==
        VK_SUCCESS);
    assert(enabled_info.enabledExtensionCount == 1 && enabled_info.ppEnabledExtensionNames == enabled);
    assert(prepared0.extensions && prepared0.info.enabledExtensionCount == 2);
    assert(has_extension(&prepared0.info, VK_KHR_CALIBRATED_TIMESTAMPS_EXTENSION_NAME));
    assert(has_extension(&prepared0.info, VK_EXT_CALIBRATED_TIMESTAMPS_EXTENSION_NAME));
    native_create(0, &prepared0.info);
    assert(devices[0].ext_enabled);
    assert(hybris_vendor_attach_device(device[0], physical[0], device_resolver,
        &prepared0.info, NULL) == VK_SUCCESS);
    assert(hybris_vendor_prepare_device(physical[1], &disabled_info, NULL, &prepared1) ==
        VK_SUCCESS);
    native_create(1, &prepared1.info);
    assert(!devices[1].ext_enabled);
    assert(hybris_vendor_attach_device(device[1], physical[1], device_resolver,
        &prepared1.info, NULL) == VK_SUCCESS);
    PFN_vkGetCalibratedTimestampsKHR query_timestamps = (PFN_vkGetCalibratedTimestampsKHR)
        hybris_vendor_device_alias_proc(device[0], "vkGetCalibratedTimestampsKHR");
    assert(query_timestamps);
    assert(!hybris_vendor_device_alias_proc(device[1], "vkGetCalibratedTimestampsKHR"));
    VkCalibratedTimestampInfoKHR stamp = {
        .sType = VK_STRUCTURE_TYPE_CALIBRATED_TIMESTAMP_INFO_KHR,
        .timeDomain = VK_TIME_DOMAIN_DEVICE_KHR};
    uint64_t timestamp = 0, deviation = 0;
    assert(query_timestamps(device[0], 1, &stamp, &timestamp, &deviation) == VK_SUCCESS);
    assert(timestamp == 42 && deviation == 1 && ext_timestamp_calls == 1 && !native_timestamp_calls);
    hybris_vendor_release_device(device[0]);
    assert(!hybris_vendor_device_alias_proc(device[0], "vkGetCalibratedTimestampsKHR"));
    assert(!hybris_vendor_device_alias_proc(device[1], "vkGetCalibratedTimestampsKHR"));
    hybris_vendor_release_device(device[1]);
    hybris_vendor_release_device_create(&prepared0, NULL);
    hybris_vendor_release_device_create(&prepared1, NULL);
}

int main(int argc, char **argv)
{
    if (argc > 1 && !strcmp(argv[1], "preinitialized")) {
        test_bionic_environment(1);
        puts("hybris native environment preserved");
        return 0;
    }
    if (argc > 1 && !strcmp(argv[1], "retry")) {
        bionic_dlsym_failures = 1;
        assert(!hybris_vendor_compat_prepare_vulkan_hal("/vendor/lib64/hw/vulkan.mali.so"));
        assert(!bionic_environ && bionic_dlopen_calls == 1);
        assert(hybris_vendor_compat_prepare_vulkan_hal("/vendor/lib64/hw/vulkan.mali.so"));
        assert(bionic_environ && bionic_dlopen_calls == 2);
        assert(hybris_vendor_compat_prepare_vulkan_hal("/vendor/lib64/hw/vulkan.mali.so"));
        assert(bionic_dlopen_calls == 2);
        puts("hybris environment preparation retried");
        return 0;
    }
    test_bionic_environment(0);
    test_xclipse_workarounds();
    test_calibrated_timestamp_policy();
    puts("hybris vendor compatibility ok");
    return 0;
}
