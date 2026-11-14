/*
 * VK_LAYER_DROIDDECK_xclipse: workarounds for Samsung's Xclipse driver loaded through libhybris.
 *
 * 1. bionic environ. A glibc process never initializes bionic's `environ`; the driver's
 *    AMD-derived shader compiler walks it for AMD_SC_* settings and crashed on the NULL.
 *    Once instance creation has loaded the driver, point it at a snapshot of ours.
 * 2. YCbCr immutable samplers in compute. Compute pipelines whose layout binds an array of
 *    immutable YCbCr-conversion samplers fail to compile (VK_ERROR_UNKNOWN); gamescope's
 *    composite shaders all bind one. Such bindings get a plain linear sampler instead, so only
 *    multi-planar (NV12) layers sample incorrectly.
 * 3. Calibrated timestamps. The driver has only VK_EXT_calibrated_timestamps, but the bridge
 *    lists the KHR names too, and Wine calls those; the loader then aborts (it took down Wine's
 *    explorer.exe and with it every game window). The KHR entry points forward to the EXT ones.
 * 4. BC4/BC5 filtering. The libhybris compat layer emulates BC textures; BC4/BC5 decode into
 *    R16/R16G16 images, for which this driver advertises no linear filtering, so DXVK would
 *    treat them as unfilterable. The layer reports linear filtering for BC4/BC5 where they are
 *    sampled at all (RDNA filters 16-bit normalized formats; at worst they sample unfiltered).
 * 5. textureCompressionBC. The compat layer emulates BC formats but keeps the feature bit off;
 *    DXVK before 3.0 (the last that fits this driver's 128 bytes of push constants) requires
 *    it. The layer reports it while BC formats are sampleable and clears it at device creation.
 * Everything else passes straight through.
 */
#define _GNU_SOURCE
#include <vulkan/vk_layer.h>
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define EXPORT __attribute__((visibility("default")))
#define MAX_OBJECTS 64

extern char **environ;

/* The loader's dispatch table pointer heads every dispatchable object: an instance shares it
 * with its physical devices, a device with its queues and command buffers. */
typedef void *dispatch_key;
static dispatch_key key_of(const void *object) { return *(dispatch_key *)object; }

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static int debug;

struct instance_data {
    dispatch_key key;
    VkInstance handle;
    PFN_vkGetInstanceProcAddr next;
    PFN_vkGetPhysicalDeviceCalibrateableTimeDomainsEXT time_domains;
    PFN_vkGetPhysicalDeviceFeatures features;
    PFN_vkGetPhysicalDeviceFeatures2 features2;
    PFN_vkGetPhysicalDeviceFormatProperties format_properties;
    PFN_vkGetPhysicalDeviceFormatProperties2 format_properties2;
};
struct device_data {
    dispatch_key key;
    VkDevice handle;
    PFN_vkGetDeviceProcAddr next;
    PFN_vkCreateSampler create_sampler;
    PFN_vkDestroySampler destroy_sampler;
    PFN_vkCreateDescriptorSetLayout create_set_layout;
    PFN_vkDestroyDevice destroy_device;
    PFN_vkGetCalibratedTimestampsEXT timestamps;
    VkSampler fallback;
};
static struct instance_data instances[MAX_OBJECTS];
static struct device_data devices[MAX_OBJECTS];
/* The live YCbCr samplers each device created, so set layouts can recognise them. Guarded by lock. */
static struct ycbcr_sampler { VkDevice device; VkSampler sampler; } *ycbcr_samplers;
static size_t ycbcr_count, ycbcr_capacity;

static struct instance_data *instance_by_key(dispatch_key key) {
    for (int i = 0; i < MAX_OBJECTS; i++)
        if (instances[i].handle && instances[i].key == key) return &instances[i];
    return NULL;
}

static struct device_data *device_by_key(dispatch_key key) {
    for (int i = 0; i < MAX_OBJECTS; i++)
        if (devices[i].handle && devices[i].key == key) return &devices[i];
    return NULL;
}

/* Entries are never freed while their object lives, so a looked-up copy of the pointers is safe. */
static struct instance_data instance_of(const void *object) {
    struct instance_data copy = {0};
    pthread_mutex_lock(&lock);
    struct instance_data *data = instance_by_key(key_of(object));
    if (data) copy = *data;
    pthread_mutex_unlock(&lock);
    return copy;
}

static struct device_data device_of(const void *object) {
    struct device_data copy = {0};
    pthread_mutex_lock(&lock);
    struct device_data *data = device_by_key(key_of(object));
    if (data) copy = *data;
    pthread_mutex_unlock(&lock);
    return copy;
}

static void fix_bionic_environ(void) {
    void *common = dlopen("libhybris-common.so.1", RTLD_NOW | RTLD_NOLOAD);
    if (!common) return; /* not a libhybris driver */
    void *(*h_dlopen)(const char *, int) = (void *(*)(const char *, int))dlsym(common, "hybris_dlopen");
    void *(*h_dlsym)(void *, const char *) = (void *(*)(void *, const char *))dlsym(common, "hybris_dlsym");
    if (!h_dlopen || !h_dlsym) return;
    void *libc = h_dlopen("libc.so", RTLD_NOW);
    char ***bionic_environ = libc ? (char ***)h_dlsym(libc, "environ") : NULL;
    if (!bionic_environ || *bionic_environ) return;
    size_t count = 0;
    while (environ && environ[count]) count++;
    char **snapshot = calloc(count + 1, sizeof(*snapshot));
    if (!snapshot) return;
    for (size_t i = 0; i < count; i++) snapshot[i] = strdup(environ[i]);
    *bionic_environ = snapshot;
    if (debug) fprintf(stderr, "xclipse layer: bionic environ set (%zu entries)\n", count);
}

/* ---- instance ---- */

static VkResult VKAPI_CALL create_instance(const VkInstanceCreateInfo *info, const VkAllocationCallbacks *allocator,
                                           VkInstance *instance) {
    VkLayerInstanceCreateInfo *chain = (VkLayerInstanceCreateInfo *)info->pNext;
    while (chain && !(chain->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO && chain->function == VK_LAYER_LINK_INFO))
        chain = (VkLayerInstanceCreateInfo *)chain->pNext;
    if (!chain || !chain->u.pLayerInfo) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr next = chain->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    chain->u.pLayerInfo = chain->u.pLayerInfo->pNext;
    PFN_vkCreateInstance next_create = (PFN_vkCreateInstance)next(NULL, "vkCreateInstance");
    if (!next_create) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult result = next_create(info, allocator, instance);
    if (result != VK_SUCCESS) return result;
    fix_bionic_environ();
    struct instance_data data = {
        .key = key_of(*instance),
        .handle = *instance,
        .next = next,
        .time_domains = (PFN_vkGetPhysicalDeviceCalibrateableTimeDomainsEXT)
            next(*instance, "vkGetPhysicalDeviceCalibrateableTimeDomainsEXT"),
        .features = (PFN_vkGetPhysicalDeviceFeatures)next(*instance, "vkGetPhysicalDeviceFeatures"),
        .features2 = (PFN_vkGetPhysicalDeviceFeatures2)next(*instance, "vkGetPhysicalDeviceFeatures2"),
        .format_properties = (PFN_vkGetPhysicalDeviceFormatProperties)next(*instance, "vkGetPhysicalDeviceFormatProperties"),
        .format_properties2 = (PFN_vkGetPhysicalDeviceFormatProperties2)next(*instance, "vkGetPhysicalDeviceFormatProperties2"),
    };
    pthread_mutex_lock(&lock);
    for (int i = 0; i < MAX_OBJECTS; i++)
        if (!instances[i].handle) { instances[i] = data; break; }
    pthread_mutex_unlock(&lock);
    return result;
}

static void VKAPI_CALL destroy_instance(VkInstance instance, const VkAllocationCallbacks *allocator) {
    PFN_vkGetInstanceProcAddr next = instance_of(instance).next;
    pthread_mutex_lock(&lock);
    struct instance_data *data = instance_by_key(key_of(instance));
    if (data) memset(data, 0, sizeof(*data));
    pthread_mutex_unlock(&lock);
    PFN_vkDestroyInstance next_destroy = next ? (PFN_vkDestroyInstance)next(instance, "vkDestroyInstance") : NULL;
    if (next_destroy) next_destroy(instance, allocator);
}

static VkResult VKAPI_CALL time_domains_khr(VkPhysicalDevice physical, uint32_t *count, VkTimeDomainEXT *domains) {
    PFN_vkGetPhysicalDeviceCalibrateableTimeDomainsEXT ext = instance_of(physical).time_domains;
    if (!ext) { *count = 0; return VK_SUCCESS; }
    return ext(physical, count, domains);
}

/* BC formats sampleable below us although the driver's feature bit is off: the compat layer emulates them. */
static int bc_emulated(VkPhysicalDevice physical) {
    struct instance_data data = instance_of(physical);
    if (!data.features || !data.format_properties) return 0;
    VkPhysicalDeviceFeatures native;
    data.features(physical, &native);
    if (native.textureCompressionBC) return 0;
    VkFormatProperties bc1, bc7;
    data.format_properties(physical, VK_FORMAT_BC1_RGBA_UNORM_BLOCK, &bc1);
    data.format_properties(physical, VK_FORMAT_BC7_UNORM_BLOCK, &bc7);
    return (bc1.optimalTilingFeatures & bc7.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) != 0;
}

static void VKAPI_CALL get_features(VkPhysicalDevice physical, VkPhysicalDeviceFeatures *features) {
    PFN_vkGetPhysicalDeviceFeatures next = instance_of(physical).features;
    if (next) next(physical, features);
    if (bc_emulated(physical)) features->textureCompressionBC = VK_TRUE;
}

static void VKAPI_CALL get_features2(VkPhysicalDevice physical, VkPhysicalDeviceFeatures2 *features) {
    PFN_vkGetPhysicalDeviceFeatures2 next = instance_of(physical).features2;
    if (next) next(physical, features);
    if (bc_emulated(physical)) features->features.textureCompressionBC = VK_TRUE;
}

static int bc45(VkFormat format) {
    return format >= VK_FORMAT_BC4_UNORM_BLOCK && format <= VK_FORMAT_BC5_SNORM_BLOCK;
}

static void VKAPI_CALL get_format_properties(VkPhysicalDevice physical, VkFormat format, VkFormatProperties *out) {
    PFN_vkGetPhysicalDeviceFormatProperties next = instance_of(physical).format_properties;
    if (next) next(physical, format, out);
    if (bc45(format) && (out->optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT))
        out->optimalTilingFeatures |= VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
}

static void VKAPI_CALL get_format_properties2(VkPhysicalDevice physical, VkFormat format, VkFormatProperties2 *out) {
    PFN_vkGetPhysicalDeviceFormatProperties2 next = instance_of(physical).format_properties2;
    if (next) next(physical, format, out);
    if (!bc45(format)) return;
    if (out->formatProperties.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT)
        out->formatProperties.optimalTilingFeatures |= VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
    for (VkBaseOutStructure *p = (VkBaseOutStructure *)out->pNext; p; p = p->pNext) {
        if (p->sType != VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_3) continue;
        VkFormatProperties3 *f3 = (VkFormatProperties3 *)p;
        if (f3->optimalTilingFeatures & VK_FORMAT_FEATURE_2_SAMPLED_IMAGE_BIT)
            f3->optimalTilingFeatures |= VK_FORMAT_FEATURE_2_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
    }
}

/* ---- device ---- */

/* The size of each structure a VkDeviceCreateInfo chain may hold (device_chain.inc, generated from the
 * Vulkan registry), plus the loader's own; 0 for anything else. */
static size_t chain_size(VkStructureType type) {
    switch (type) {
#define DEVICE_CHAIN(stype, name) case stype: return sizeof(name);
#include "device_chain.inc"
#undef DEVICE_CHAIN
    case VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO: return sizeof(VkLayerDeviceCreateInfo);
    default: return 0;
    }
}

struct chain_copy { void **nodes; size_t count; };

static void free_chain_copy(struct chain_copy *copy) {
    for (size_t i = 0; i < copy->count; i++) free(copy->nodes[i]);
    free(copy->nodes);
    copy->nodes = NULL;
    copy->count = 0;
}

/* The chain with textureCompressionBC cleared in a copy of the VkPhysicalDeviceFeatures2 that enables
 * it: every structure up to it is copied into [copy], the rest is shared. The caller's structures are
 * never written. *head_out stays [head] when nothing needs clearing, or when a structure ahead of it
 * is unknown to this build (newer than its Vulkan headers); the compat layer then refuses the bit. */
static VkResult chain_without_bc(const void *head, struct chain_copy *copy, const void **head_out) {
    *head_out = head;
    const VkBaseInStructure *target = NULL;
    size_t count = 0;
    for (const VkBaseInStructure *p = head; p && !target; p = p->pNext) {
        if (!chain_size(p->sType)) return VK_SUCCESS;
        count++;
        if (p->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2 &&
            ((const VkPhysicalDeviceFeatures2 *)p)->features.textureCompressionBC) target = p;
    }
    if (!target) return VK_SUCCESS;
    if (!(copy->nodes = calloc(count, sizeof(*copy->nodes)))) return VK_ERROR_OUT_OF_HOST_MEMORY;
    VkBaseOutStructure *first = NULL, *last = NULL;
    for (const VkBaseInStructure *p = head; ; p = p->pNext) {
        size_t size = chain_size(p->sType);
        VkBaseOutStructure *node = malloc(size);
        if (!node) { free_chain_copy(copy); return VK_ERROR_OUT_OF_HOST_MEMORY; }
        copy->nodes[copy->count++] = node;
        memcpy(node, p, size);
        if (last) last->pNext = node; else first = node;
        last = node;
        if (p == target) {
            ((VkPhysicalDeviceFeatures2 *)node)->features.textureCompressionBC = VK_FALSE;
            *head_out = first; /* its pNext still leads to the caller's remaining structures */
            return VK_SUCCESS;
        }
    }
}

static VkResult VKAPI_CALL create_device(VkPhysicalDevice physical, const VkDeviceCreateInfo *info,
                                         const VkAllocationCallbacks *allocator, VkDevice *device) {
    VkLayerDeviceCreateInfo *chain = (VkLayerDeviceCreateInfo *)info->pNext;
    while (chain && !(chain->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO && chain->function == VK_LAYER_LINK_INFO))
        chain = (VkLayerDeviceCreateInfo *)chain->pNext;
    if (!chain || !chain->u.pLayerInfo) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr next_gipa = chain->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr next_gdpa = chain->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    chain->u.pLayerInfo = chain->u.pLayerInfo->pNext;
    PFN_vkCreateDevice next_create = (PFN_vkCreateDevice)next_gipa(NULL, "vkCreateDevice");
    if (!next_create) return VK_ERROR_INITIALIZATION_FAILED;

    VkDeviceCreateInfo patched = *info;
    /* An emulated textureCompressionBC never reaches the compat layer, which refuses the bit. */
    int strip_bc = bc_emulated(physical);
    VkPhysicalDeviceFeatures plain_features;
    if (strip_bc && info->pEnabledFeatures && info->pEnabledFeatures->textureCompressionBC) {
        plain_features = *info->pEnabledFeatures;
        plain_features.textureCompressionBC = VK_FALSE;
        patched.pEnabledFeatures = &plain_features;
    }
    struct chain_copy copy = {0};
    if (strip_bc) {
        VkResult copied = chain_without_bc(info->pNext, &copy, &patched.pNext);
        if (copied != VK_SUCCESS) return copied;
    }
    VkResult result = next_create(physical, &patched, allocator, device);
    free_chain_copy(&copy);
    if (result != VK_SUCCESS) return result;

    PFN_vkCreateSampler create_sampler = (PFN_vkCreateSampler)next_gdpa(*device, "vkCreateSampler");
    VkSamplerCreateInfo plain = {
        .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
        .magFilter = VK_FILTER_LINEAR, .minFilter = VK_FILTER_LINEAR,
        .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .borderColor = VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK,
    };
    VkSampler fallback = VK_NULL_HANDLE;
    if (create_sampler) create_sampler(*device, &plain, NULL, &fallback);
    struct device_data data = {
        .key = key_of(*device),
        .handle = *device,
        .next = next_gdpa,
        .create_sampler = create_sampler,
        .destroy_sampler = (PFN_vkDestroySampler)next_gdpa(*device, "vkDestroySampler"),
        .create_set_layout = (PFN_vkCreateDescriptorSetLayout)next_gdpa(*device, "vkCreateDescriptorSetLayout"),
        .destroy_device = (PFN_vkDestroyDevice)next_gdpa(*device, "vkDestroyDevice"),
        .timestamps = (PFN_vkGetCalibratedTimestampsEXT)next_gdpa(*device, "vkGetCalibratedTimestampsEXT"),
        .fallback = fallback,
    };
    pthread_mutex_lock(&lock);
    for (int i = 0; i < MAX_OBJECTS; i++)
        if (!devices[i].handle) { devices[i] = data; break; }
    pthread_mutex_unlock(&lock);
    return result;
}

static void VKAPI_CALL destroy_device(VkDevice device, const VkAllocationCallbacks *allocator) {
    struct device_data data = device_of(device);
    pthread_mutex_lock(&lock);
    struct device_data *entry = device_by_key(key_of(device));
    if (entry) memset(entry, 0, sizeof(*entry));
    for (size_t k = 0; k < ycbcr_count;)
        if (ycbcr_samplers[k].device == device) ycbcr_samplers[k] = ycbcr_samplers[--ycbcr_count];
        else k++;
    pthread_mutex_unlock(&lock);
    if (data.fallback && data.destroy_sampler) data.destroy_sampler(device, data.fallback, NULL);
    if (data.destroy_device) data.destroy_device(device, allocator);
}

static VkResult VKAPI_CALL timestamps_khr(VkDevice device, uint32_t count, const VkCalibratedTimestampInfoEXT *infos,
                                          uint64_t *timestamps, uint64_t *deviation) {
    PFN_vkGetCalibratedTimestampsEXT ext = device_of(device).timestamps;
    return ext ? ext(device, count, infos, timestamps, deviation) : VK_ERROR_FEATURE_NOT_PRESENT;
}

static VkResult VKAPI_CALL create_sampler(VkDevice device, const VkSamplerCreateInfo *info,
                                          const VkAllocationCallbacks *allocator, VkSampler *sampler) {
    PFN_vkCreateSampler next = device_of(device).create_sampler;
    if (!next) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult result = next(device, info, allocator, sampler);
    if (result != VK_SUCCESS) return result;
    int ycbcr = 0;
    for (const VkBaseInStructure *p = info->pNext; p && !ycbcr; p = p->pNext)
        ycbcr = p->sType == VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO;
    if (!ycbcr) return result;
    pthread_mutex_lock(&lock);
    if (ycbcr_count == ycbcr_capacity) {
        size_t capacity = ycbcr_capacity ? ycbcr_capacity * 2 : 16;
        struct ycbcr_sampler *grown = realloc(ycbcr_samplers, capacity * sizeof(*grown));
        if (!grown) {
            pthread_mutex_unlock(&lock);
            /* An untracked YCbCr sampler would reach a compute layout unreplaced: fail instead. */
            PFN_vkDestroySampler destroy = device_of(device).destroy_sampler;
            if (destroy) destroy(device, *sampler, allocator);
            *sampler = VK_NULL_HANDLE;
            return VK_ERROR_OUT_OF_HOST_MEMORY;
        }
        ycbcr_samplers = grown;
        ycbcr_capacity = capacity;
    }
    ycbcr_samplers[ycbcr_count++] = (struct ycbcr_sampler){device, *sampler};
    pthread_mutex_unlock(&lock);
    return result;
}

static void VKAPI_CALL destroy_sampler(VkDevice device, VkSampler sampler, const VkAllocationCallbacks *allocator) {
    pthread_mutex_lock(&lock);
    for (size_t k = 0; k < ycbcr_count; k++)
        if (ycbcr_samplers[k].device == device && ycbcr_samplers[k].sampler == sampler) {
            ycbcr_samplers[k] = ycbcr_samplers[--ycbcr_count];
            break;
        }
    pthread_mutex_unlock(&lock);
    PFN_vkDestroySampler next = device_of(device).destroy_sampler;
    if (next) next(device, sampler, allocator);
}

/* Called with lock held. */
static int is_ycbcr(VkDevice device, VkSampler sampler) {
    for (size_t k = 0; sampler && k < ycbcr_count; k++)
        if (ycbcr_samplers[k].device == device && ycbcr_samplers[k].sampler == sampler) return 1;
    return 0;
}

static VkResult VKAPI_CALL create_set_layout(VkDevice device, const VkDescriptorSetLayoutCreateInfo *info,
                                             const VkAllocationCallbacks *allocator, VkDescriptorSetLayout *layout) {
    struct device_data data = device_of(device);
    if (!data.create_set_layout) return VK_ERROR_INITIALIZATION_FAILED;
    int replace = 0;
    pthread_mutex_lock(&lock);
    for (uint32_t b = 0; data.fallback && b < info->bindingCount && !replace; b++) {
        const VkDescriptorSetLayoutBinding *binding = &info->pBindings[b];
        if (!(binding->stageFlags & VK_SHADER_STAGE_COMPUTE_BIT) || !binding->pImmutableSamplers) continue;
        for (uint32_t s = 0; s < binding->descriptorCount; s++)
            if (is_ycbcr(device, binding->pImmutableSamplers[s])) { replace = 1; break; }
    }
    pthread_mutex_unlock(&lock);
    if (!replace) return data.create_set_layout(device, info, allocator, layout);

    VkResult result = VK_ERROR_OUT_OF_HOST_MEMORY;
    VkDescriptorSetLayoutBinding *bindings = calloc(info->bindingCount, sizeof(*bindings));
    VkSampler **arrays = calloc(info->bindingCount, sizeof(*arrays));
    if (!bindings || !arrays) goto done;
    for (uint32_t b = 0; b < info->bindingCount; b++) {
        bindings[b] = info->pBindings[b];
        if (!(bindings[b].stageFlags & VK_SHADER_STAGE_COMPUTE_BIT) || !bindings[b].pImmutableSamplers) continue;
        /* Without the replacement the driver would see the samplers it cannot compile: fail instead. */
        if (!(arrays[b] = malloc(bindings[b].descriptorCount * sizeof(VkSampler)))) goto done;
        pthread_mutex_lock(&lock);
        for (uint32_t s = 0; s < bindings[b].descriptorCount; s++) {
            VkSampler original = bindings[b].pImmutableSamplers[s];
            arrays[b][s] = is_ycbcr(device, original) ? data.fallback : original;
        }
        pthread_mutex_unlock(&lock);
        bindings[b].pImmutableSamplers = arrays[b];
    }
    VkDescriptorSetLayoutCreateInfo patched = *info;
    patched.pBindings = bindings;
    result = data.create_set_layout(device, &patched, allocator, layout);
    if (debug) fprintf(stderr, "xclipse layer: compute YCbCr immutable samplers replaced (%d)\n", result);
done:
    for (uint32_t b = 0; arrays && b < info->bindingCount; b++) free(arrays[b]);
    free(arrays);
    free(bindings);
    return result;
}

/* ---- dispatch ---- */

static PFN_vkVoidFunction device_hook(const char *name) {
    if (!strcmp(name, "vkGetDeviceProcAddr")) return NULL; /* handled by the caller */
    if (!strcmp(name, "vkDestroyDevice")) return (PFN_vkVoidFunction)destroy_device;
    if (!strcmp(name, "vkCreateSampler")) return (PFN_vkVoidFunction)create_sampler;
    if (!strcmp(name, "vkDestroySampler")) return (PFN_vkVoidFunction)destroy_sampler;
    if (!strcmp(name, "vkCreateDescriptorSetLayout")) return (PFN_vkVoidFunction)create_set_layout;
    if (!strcmp(name, "vkGetCalibratedTimestampsKHR")) return (PFN_vkVoidFunction)timestamps_khr;
    return NULL;
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char *name) {
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)vkGetDeviceProcAddr;
    PFN_vkVoidFunction hook = device_hook(name);
    if (hook) return hook;
    struct device_data data = device ? device_of(device) : (struct device_data){0};
    return data.next ? data.next(device, name) : NULL;
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char *name) {
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (PFN_vkVoidFunction)vkGetInstanceProcAddr;
    if (!strcmp(name, "vkCreateInstance")) return (PFN_vkVoidFunction)create_instance;
    if (!strcmp(name, "vkDestroyInstance")) return (PFN_vkVoidFunction)destroy_instance;
    if (!strcmp(name, "vkCreateDevice")) return (PFN_vkVoidFunction)create_device;
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)vkGetDeviceProcAddr;
    if (!strcmp(name, "vkGetPhysicalDeviceFeatures")) return (PFN_vkVoidFunction)get_features;
    if (!strcmp(name, "vkGetPhysicalDeviceFeatures2") || !strcmp(name, "vkGetPhysicalDeviceFeatures2KHR"))
        return (PFN_vkVoidFunction)get_features2;
    if (!strcmp(name, "vkGetPhysicalDeviceCalibrateableTimeDomainsKHR")) return (PFN_vkVoidFunction)time_domains_khr;
    if (!strcmp(name, "vkGetPhysicalDeviceFormatProperties")) return (PFN_vkVoidFunction)get_format_properties;
    if (!strcmp(name, "vkGetPhysicalDeviceFormatProperties2") || !strcmp(name, "vkGetPhysicalDeviceFormatProperties2KHR"))
        return (PFN_vkVoidFunction)get_format_properties2;
    PFN_vkVoidFunction hook = device_hook(name);
    if (hook) return hook;
    PFN_vkGetInstanceProcAddr next = instance ? instance_of(instance).next : NULL;
    return next ? next(instance, name) : NULL;
}

EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface *negotiate) {
    if (!negotiate || negotiate->loaderLayerInterfaceVersion < 2) return VK_ERROR_INITIALIZATION_FAILED;
    debug = getenv("DROIDDECK_XCLIPSE_LAYER_DEBUG") != NULL;
    negotiate->loaderLayerInterfaceVersion = 2;
    negotiate->pfnGetInstanceProcAddr = vkGetInstanceProcAddr;
    negotiate->pfnGetDeviceProcAddr = vkGetDeviceProcAddr;
    negotiate->pfnGetPhysicalDeviceProcAddr = NULL;
    return VK_SUCCESS;
}
