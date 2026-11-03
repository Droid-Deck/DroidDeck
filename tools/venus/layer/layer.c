#define _GNU_SOURCE
#include "mali_compat.h"

#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define LAYER_NAME "VK_LAYER_DROIDDECK_mali_compat"

static pthread_mutex_t global_lock = PTHREAD_MUTEX_INITIALIZER;
static struct instance *instances;
static struct device *devices;

void layer_log(const char *format, ...)
{
    char line[512];
    va_list args;
    va_start(args, format);
    vsnprintf(line, sizeof(line), format, args);
    va_end(args);
    fprintf(stderr, "droiddeck-mali-compat: %s\n", line);
}

/* The loader's dispatch table pointer: shared by an instance and its physical devices, and by a
 * device and its queues and command buffers. */
static void *dispatch_key(const void *dispatchable)
{
    return *(void *const *)dispatchable;
}

struct instance *instance_from_handle(const void *dispatchable)
{
    void *key = dispatch_key(dispatchable);
    pthread_mutex_lock(&global_lock);
    struct instance *inst = instances;
    while (inst && inst->key != key)
        inst = inst->next;
    pthread_mutex_unlock(&global_lock);
    return inst;
}

struct device *device_from_handle(const void *dispatchable)
{
    void *key = dispatch_key(dispatchable);
    pthread_mutex_lock(&global_lock);
    struct device *dev = devices;
    while (dev && dev->key != key)
        dev = dev->next;
    pthread_mutex_unlock(&global_lock);
    return dev;
}

static uint64_t hash(uint64_t key)
{
    key ^= key >> 33;
    key *= 0xff51afd7ed558ccdull;
    key ^= key >> 33;
    return key;
}

void *map_get(const struct handle_map *map, uint64_t key)
{
    if (!map->capacity)
        return NULL;
    for (uint32_t i = hash(key) & (map->capacity - 1);; i = (i + 1) & (map->capacity - 1)) {
        if (map->keys[i] == key)
            return map->values[i];
        if (!map->keys[i])
            return NULL;
    }
}

static void map_grow(struct handle_map *map)
{
    struct handle_map bigger = {
        .capacity = map->capacity ? map->capacity * 2 : 64,
    };
    bigger.keys = calloc(bigger.capacity, sizeof(*bigger.keys));
    bigger.values = calloc(bigger.capacity, sizeof(*bigger.values));
    for (uint32_t i = 0; i < map->capacity; i++) {
        if (map->keys[i])
            map_put(&bigger, map->keys[i], map->values[i]);
    }
    free(map->keys);
    free(map->values);
    *map = bigger;
}

void map_put(struct handle_map *map, uint64_t key, void *value)
{
    if ((map->count + 1) * 4 > map->capacity * 3)
        map_grow(map);
    uint32_t i = hash(key) & (map->capacity - 1);
    while (map->keys[i] && map->keys[i] != key)
        i = (i + 1) & (map->capacity - 1);
    if (!map->keys[i])
        map->count++;
    map->keys[i] = key;
    map->values[i] = value;
}

void *map_remove(struct handle_map *map, uint64_t key)
{
    if (!map->capacity)
        return NULL;
    uint32_t mask = map->capacity - 1;
    uint32_t i = hash(key) & mask;
    while (map->keys[i] != key) {
        if (!map->keys[i])
            return NULL;
        i = (i + 1) & mask;
    }
    void *value = map->values[i];
    map->count--;
    /* Backward-shift deletion keeps every remaining key reachable from its home slot. */
    for (uint32_t j = (i + 1) & mask; map->keys[j]; j = (j + 1) & mask) {
        uint32_t home = hash(map->keys[j]) & mask;
        if (((j - home) & mask) >= ((j - i) & mask)) {
            map->keys[i] = map->keys[j];
            map->values[i] = map->values[j];
            i = j;
        }
    }
    map->keys[i] = 0;
    map->values[i] = NULL;
    return value;
}

void map_free(struct handle_map *map)
{
    free(map->keys);
    free(map->values);
    *map = (struct handle_map){0};
}

/* Wine processes by default. DROIDDECK_MALI_COMPAT=1 (the app's Venus route) turns it on everywhere
 * but gamescope, so Zink in the Steam client and Xwayland gets robustness2's nullDescriptor, which it
 * requires; gamescope runs fine on the driver's own features. =all includes gamescope too. */
static bool wanted(void)
{
    const char *setting = getenv("DROIDDECK_MALI_COMPAT");
    if (setting) {
        if (strcmp(setting, "0") == 0)
            return false;
        if (strcmp(setting, "all") == 0)
            return true;
        return strcmp(program_invocation_short_name, "gamescope") != 0;
    }
    return getenv("WINEPREFIX") != NULL;
}

/* DROIDDECK_MALI_COMPAT_TRACE=1: what an application asks before it creates a device (Zink on a
 * Mali-G615 through Venus gave up before vkCreateDevice without a word). */
static bool tracing(void)
{
    static int on = -1;
    if (on < 0) {
        const char *v = getenv("DROIDDECK_MALI_COMPAT_TRACE");
        on = v && *v && strcmp(v, "0") != 0;
    }
    return on;
}

static bool has_extension(struct instance *inst, VkPhysicalDevice physical_device, const char *name)
{
    uint32_t count = 0;
    inst->EnumerateDeviceExtensionProperties(physical_device, NULL, &count, NULL);
    VkExtensionProperties *extensions = calloc(count, sizeof(*extensions));
    inst->EnumerateDeviceExtensionProperties(physical_device, NULL, &count, extensions);
    bool found = false;
    for (uint32_t i = 0; i < count && !found; i++)
        found = strcmp(extensions[i].extensionName, name) == 0;
    free(extensions);
    return found;
}

uint32_t physical_device_gaps(struct instance *inst, VkPhysicalDevice physical_device)
{
    pthread_mutex_lock(&inst->lock);
    for (uint32_t i = 0; i < inst->physical_device_count; i++) {
        if (inst->physical_devices[i].handle == physical_device) {
            uint32_t gaps = inst->physical_devices[i].gaps;
            pthread_mutex_unlock(&inst->lock);
            return gaps;
        }
    }
    pthread_mutex_unlock(&inst->lock);

    bool robustness2 = has_extension(inst, physical_device, "VK_EXT_robustness2");
    VkPhysicalDeviceRobustness2FeaturesEXT robust = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ROBUSTNESS_2_FEATURES_EXT,
    };
    VkPhysicalDeviceFeatures2 features = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
        .pNext = robustness2 ? &robust : NULL,
    };
    inst->GetPhysicalDeviceFeatures2(physical_device, &features);
    uint32_t gaps = 0;
    if (!features.features.fillModeNonSolid)
        gaps |= GAP_FILL_MODE;
    if (!features.features.multiViewport)
        gaps |= GAP_MULTI_VIEWPORT;
    if (!features.features.shaderClipDistance)
        gaps |= GAP_CLIP_DISTANCE;
    if (!features.features.shaderCullDistance)
        gaps |= GAP_CULL_DISTANCE;
    if (!features.features.textureCompressionBC)
        gaps |= GAP_BC;
    if (robustness2 && !robust.robustBufferAccess2)
        gaps |= GAP_ROBUSTNESS2;
    if (!features.features.multiDrawIndirect)
        gaps |= GAP_MULTI_DRAW;
    if (!has_extension(inst, physical_device, VK_EXT_DEPTH_CLIP_ENABLE_EXTENSION_NAME))
        gaps |= GAP_DEPTH_CLIP;
    /* No robustness2 at all: the extension is offered with robustBufferAccess2 (as robustBufferAccess)
     * and nullDescriptor (stand-ins, draw.c). */
    if (!robustness2)
        gaps |= GAP_ROBUSTNESS2 | GAP_NULL_DESCRIPTOR;

    pthread_mutex_lock(&inst->lock);
    if (inst->physical_device_count < sizeof(inst->physical_devices) / sizeof(inst->physical_devices[0])) {
        inst->physical_devices[inst->physical_device_count++] =
            (struct physical_device){.handle = physical_device, .gaps = gaps};
    }
    pthread_mutex_unlock(&inst->lock);
    return gaps;
}

/* Structures of extensions the driver does not have are taken out of a chain for the call down and
 * put back after it: the driver (and Venus) must not see them. */
struct unlinked {
    VkBaseOutStructure *prev, *node;
};

static uint32_t unlink_types(void *head, const VkStructureType *types, uint32_t type_count,
                             struct unlinked *out, uint32_t max)
{
    uint32_t n = 0;
    VkBaseOutStructure *prev = head;
    while (prev && prev->pNext && n < max) {
        VkBaseOutStructure *s = prev->pNext;
        bool drop = false;
        for (uint32_t i = 0; i < type_count; i++)
            drop |= s->sType == types[i];
        if (drop) {
            out[n++] = (struct unlinked){prev, s};
            prev->pNext = s->pNext;
        } else {
            prev = s;
        }
    }
    return n;
}

static void relink(struct unlinked *list, uint32_t n)
{
    while (n--)
        list[n].prev->pNext = list[n].node;
}

static uint32_t foreign_types(uint32_t gaps, VkStructureType *types)
{
    uint32_t n = 0;
    if (gaps & GAP_DEPTH_CLIP)
        types[n++] = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DEPTH_CLIP_ENABLE_FEATURES_EXT;
    if (gaps & GAP_NULL_DESCRIPTOR)
        types[n++] = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ROBUSTNESS_2_FEATURES_EXT;
    return n;
}

static void report_features(VkPhysicalDeviceFeatures *features, uint32_t gaps)
{
    if (gaps & GAP_FILL_MODE)
        features->fillModeNonSolid = VK_TRUE;
    if (gaps & GAP_MULTI_VIEWPORT)
        features->multiViewport = VK_TRUE;
    if (gaps & GAP_CLIP_DISTANCE)
        features->shaderClipDistance = VK_TRUE;
    if (gaps & GAP_CULL_DISTANCE)
        features->shaderCullDistance = VK_TRUE;
    if (gaps & GAP_BC)
        features->textureCompressionBC = VK_TRUE;
    if (gaps & GAP_MULTI_DRAW)
        features->multiDrawIndirect = VK_TRUE;
}

/* Which made-up features the application asked for, and off with them before the driver sees them. */
static uint32_t take_features(VkPhysicalDeviceFeatures *features, uint32_t gaps)
{
    uint32_t requested = 0;
    if ((gaps & GAP_FILL_MODE) && features->fillModeNonSolid) {
        requested |= GAP_FILL_MODE;
        features->fillModeNonSolid = VK_FALSE;
    }
    if ((gaps & GAP_MULTI_VIEWPORT) && features->multiViewport) {
        requested |= GAP_MULTI_VIEWPORT;
        features->multiViewport = VK_FALSE;
    }
    if ((gaps & GAP_CLIP_DISTANCE) && features->shaderClipDistance) {
        requested |= GAP_CLIP_DISTANCE;
        features->shaderClipDistance = VK_FALSE;
    }
    if ((gaps & GAP_CULL_DISTANCE) && features->shaderCullDistance) {
        requested |= GAP_CULL_DISTANCE;
        features->shaderCullDistance = VK_FALSE;
    }
    if ((gaps & GAP_BC) && features->textureCompressionBC) {
        requested |= GAP_BC;
        features->textureCompressionBC = VK_FALSE;
    }
    if ((gaps & GAP_MULTI_DRAW) && features->multiDrawIndirect) {
        requested |= GAP_MULTI_DRAW;
        features->multiDrawIndirect = VK_FALSE;
    }
    return requested;
}

static void report_limits(VkPhysicalDeviceLimits *limits, uint32_t gaps)
{
    if (gaps & GAP_MULTI_VIEWPORT)
        limits->maxViewports = 16;
    if ((gaps & GAP_MULTI_DRAW) && limits->maxDrawIndirectCount < (1u << 30))
        limits->maxDrawIndirectCount = 1u << 30;
    if (gaps & GAPS_SHADER) {
        limits->maxClipDistances = 8;
        limits->maxCullDistances = 8;
        limits->maxCombinedClipAndCullDistances = 8;
    }
}

static VKAPI_ATTR void VKAPI_CALL GetPhysicalDeviceFeatures(VkPhysicalDevice physical_device,
                                                            VkPhysicalDeviceFeatures *features)
{
    struct instance *inst = instance_from_handle(physical_device);
    inst->GetPhysicalDeviceFeatures(physical_device, features);
    report_features(features, physical_device_gaps(inst, physical_device));
}

static VKAPI_ATTR void VKAPI_CALL GetPhysicalDeviceFeatures2(VkPhysicalDevice physical_device,
                                                             VkPhysicalDeviceFeatures2 *features)
{
    struct instance *inst = instance_from_handle(physical_device);
    uint32_t gaps = physical_device_gaps(inst, physical_device);
    VkStructureType types[4];
    struct unlinked dropped[8];
    uint32_t dropped_count = unlink_types(features, types, foreign_types(gaps, types), dropped, 8);
    inst->GetPhysicalDeviceFeatures2(physical_device, features);
    relink(dropped, dropped_count);
    report_features(&features->features, gaps);
    if (tracing()) {
        char chain[512] = "";
        size_t used = 0;
        for (VkBaseOutStructure *s = features->pNext; s && used + 16 < sizeof(chain); s = s->pNext)
            used += (size_t)snprintf(chain + used, sizeof(chain) - used, " %d", (int)s->sType);
        layer_log("trace: vkGetPhysicalDeviceFeatures2 (gaps 0x%x, %u structures held back), chain:%s", gaps, dropped_count, chain);
    }
    for (VkBaseOutStructure *s = features->pNext; s; s = s->pNext) {
        if (s->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ROBUSTNESS_2_FEATURES_EXT && (gaps & GAP_ROBUSTNESS2)) {
            ((VkPhysicalDeviceRobustness2FeaturesEXT *)s)->robustBufferAccess2 = VK_TRUE;
            if (gaps & GAP_NULL_DESCRIPTOR)
                ((VkPhysicalDeviceRobustness2FeaturesEXT *)s)->nullDescriptor = VK_TRUE;
        }
        if (s->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DEPTH_CLIP_ENABLE_FEATURES_EXT && (gaps & GAP_DEPTH_CLIP))
            ((VkPhysicalDeviceDepthClipEnableFeaturesEXT *)s)->depthClipEnable = VK_TRUE;
    }
}

static VKAPI_ATTR void VKAPI_CALL GetPhysicalDeviceProperties(VkPhysicalDevice physical_device,
                                                              VkPhysicalDeviceProperties *properties)
{
    struct instance *inst = instance_from_handle(physical_device);
    inst->GetPhysicalDeviceProperties(physical_device, properties);
    report_limits(&properties->limits, physical_device_gaps(inst, physical_device));
}

static VKAPI_ATTR void VKAPI_CALL GetPhysicalDeviceProperties2(VkPhysicalDevice physical_device,
                                                               VkPhysicalDeviceProperties2 *properties)
{
    struct instance *inst = instance_from_handle(physical_device);
    inst->GetPhysicalDeviceProperties2(physical_device, properties);
    report_limits(&properties->properties.limits, physical_device_gaps(inst, physical_device));
    if (tracing())
        layer_log("trace: vkGetPhysicalDeviceProperties2: %s, Vulkan %u.%u.%u, type %d", properties->properties.deviceName,
                  VK_API_VERSION_MAJOR(properties->properties.apiVersion), VK_API_VERSION_MINOR(properties->properties.apiVersion),
                  VK_API_VERSION_PATCH(properties->properties.apiVersion), (int)properties->properties.deviceType);
}

/* The extensions the layer stands in for, added to the driver's list. */
static VKAPI_ATTR VkResult VKAPI_CALL EnumerateDeviceExtensionProperties(VkPhysicalDevice physical_device,
                                                                         const char *layer_name, uint32_t *count,
                                                                         VkExtensionProperties *properties)
{
    struct instance *inst = instance_from_handle(physical_device);
    if (layer_name)
        return inst->EnumerateDeviceExtensionProperties(physical_device, layer_name, count, properties);
    uint32_t gaps = physical_device_gaps(inst, physical_device);
    VkExtensionProperties extra[2];
    uint32_t extra_count = 0;
    if (gaps & GAP_DEPTH_CLIP)
        extra[extra_count++] = (VkExtensionProperties){VK_EXT_DEPTH_CLIP_ENABLE_EXTENSION_NAME, 1};
    if (gaps & GAP_NULL_DESCRIPTOR)
        extra[extra_count++] = (VkExtensionProperties){VK_EXT_ROBUSTNESS_2_EXTENSION_NAME, 1};
    uint32_t own = 0;
    VkResult result = inst->EnumerateDeviceExtensionProperties(physical_device, NULL, &own, NULL);
    if (result != VK_SUCCESS)
        return result;
    if (!properties) {
        *count = own + extra_count;
        return VK_SUCCESS;
    }
    VkExtensionProperties *all = calloc(own + extra_count, sizeof(*all));
    inst->EnumerateDeviceExtensionProperties(physical_device, NULL, &own, all);
    memcpy(all + own, extra, extra_count * sizeof(*extra));
    uint32_t total = own + extra_count;
    if (tracing()) {
        layer_log("trace: vkEnumerateDeviceExtensionProperties: %u from the driver, %u added by the layer", own, extra_count);
        trace_extension_names(all, total);
    }
    uint32_t n = *count < total ? *count : total;
    memcpy(properties, all, n * sizeof(*all));
    free(all);
    *count = n;
    return n < total ? VK_INCOMPLETE : VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL CreateInstance(const VkInstanceCreateInfo *info,
                                                     const VkAllocationCallbacks *allocator, VkInstance *instance)
{
    VkLayerInstanceCreateInfo *link = (VkLayerInstanceCreateInfo *)info->pNext;
    while (link && !(link->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO && link->function == VK_LAYER_LINK_INFO))
        link = (VkLayerInstanceCreateInfo *)link->pNext;
    if (!link)
        return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr gipa = link->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    link->u.pLayerInfo = link->u.pLayerInfo->pNext;

    PFN_vkCreateInstance create = (PFN_vkCreateInstance)gipa(VK_NULL_HANDLE, "vkCreateInstance");
    VkResult result = create(info, allocator, instance);
    if (tracing())
        layer_log("trace: vkCreateInstance for %s (API %u.%u): %d, layer %s", info->pApplicationInfo && info->pApplicationInfo->pApplicationName ?
                  info->pApplicationInfo->pApplicationName : "?", info->pApplicationInfo ? VK_API_VERSION_MAJOR(info->pApplicationInfo->apiVersion) : 0,
                  info->pApplicationInfo ? VK_API_VERSION_MINOR(info->pApplicationInfo->apiVersion) : 0, (int)result, wanted() ? "active" : "idle");
    if (result != VK_SUCCESS)
        return result;

    struct instance *inst = calloc(1, sizeof(*inst));
    inst->key = dispatch_key(*instance);
    inst->handle = *instance;
    inst->active = wanted();
    inst->gipa = gipa;
    pthread_mutex_init(&inst->lock, NULL);
#define LOAD(name) inst->name = (PFN_vk##name)gipa(*instance, "vk" #name);
#define LOAD_KHR(name) \
    if (!inst->name) \
        inst->name = (PFN_vk##name)gipa(*instance, "vk" #name "KHR");
    LOAD(DestroyInstance)
    LOAD(EnumerateDeviceExtensionProperties)
    LOAD(GetPhysicalDeviceFeatures)
    LOAD(GetPhysicalDeviceFeatures2)
    LOAD_KHR(GetPhysicalDeviceFeatures2)
    LOAD(GetPhysicalDeviceProperties)
    LOAD(GetPhysicalDeviceProperties2)
    LOAD_KHR(GetPhysicalDeviceProperties2)
    LOAD(GetPhysicalDeviceMemoryProperties)
    LOAD(GetPhysicalDeviceFormatProperties)
    LOAD(GetPhysicalDeviceFormatProperties2)
    LOAD_KHR(GetPhysicalDeviceFormatProperties2)
    LOAD(GetPhysicalDeviceImageFormatProperties)
    LOAD(GetPhysicalDeviceImageFormatProperties2)
    LOAD_KHR(GetPhysicalDeviceImageFormatProperties2)
#undef LOAD
#undef LOAD_KHR
    if (tracing())
        trace_load(inst);
    /* Without the Vulkan 1.1 queries there is no way to answer the chained ones. */
    if (!inst->GetPhysicalDeviceFeatures2 || !inst->GetPhysicalDeviceProperties2 ||
        !inst->GetPhysicalDeviceFormatProperties2 || !inst->GetPhysicalDeviceImageFormatProperties2)
        inst->active = false;

    pthread_mutex_lock(&global_lock);
    inst->next = instances;
    instances = inst;
    pthread_mutex_unlock(&global_lock);
    return VK_SUCCESS;
}

static VKAPI_ATTR void VKAPI_CALL DestroyInstance(VkInstance instance, const VkAllocationCallbacks *allocator)
{
    void *key = dispatch_key(instance);
    pthread_mutex_lock(&global_lock);
    struct instance **link = &instances;
    while (*link && (*link)->key != key)
        link = &(*link)->next;
    struct instance *inst = *link;
    if (inst)
        *link = inst->next;
    pthread_mutex_unlock(&global_lock);
    if (!inst)
        return;
    inst->DestroyInstance(instance, allocator);
    pthread_mutex_destroy(&inst->lock);
    free(inst);
}

static VKAPI_ATTR VkResult VKAPI_CALL CreateDevice(VkPhysicalDevice physical_device, const VkDeviceCreateInfo *info,
                                                   const VkAllocationCallbacks *allocator, VkDevice *device)
{
    VkLayerDeviceCreateInfo *link = (VkLayerDeviceCreateInfo *)info->pNext;
    while (link && !(link->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO && link->function == VK_LAYER_LINK_INFO))
        link = (VkLayerDeviceCreateInfo *)link->pNext;
    if (!link)
        return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr gipa = link->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr gdpa = link->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    link->u.pLayerInfo = link->u.pLayerInfo->pNext;

    struct instance *inst = instance_from_handle(physical_device);
    PFN_vkCreateDevice create = (PFN_vkCreateDevice)gipa(inst->handle, "vkCreateDevice");
    uint32_t gaps = inst->active ? physical_device_gaps(inst, physical_device) : 0;

    /* The application's own structures are put back after the call: DXVK reads its enabled
     * features again later, and must keep seeing what it asked for. */
    VkDeviceCreateInfo copy = *info;
    VkPhysicalDeviceFeatures features;
    VkPhysicalDeviceFeatures2 *features2 = NULL;
    VkPhysicalDeviceRobustness2FeaturesEXT *robust = NULL;
    for (VkBaseOutStructure *s = (VkBaseOutStructure *)info->pNext; s; s = s->pNext) {
        if (s->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
            features2 = (VkPhysicalDeviceFeatures2 *)s;
        else if (s->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ROBUSTNESS_2_FEATURES_EXT)
            robust = (VkPhysicalDeviceRobustness2FeaturesEXT *)s;
    }
    VkPhysicalDeviceFeatures *enabled = NULL;
    VkPhysicalDeviceFeatures saved_features = {0};
    if (info->pEnabledFeatures) {
        features = *info->pEnabledFeatures;
        copy.pEnabledFeatures = &features;
        enabled = &features;
    } else if (features2) {
        saved_features = features2->features;
        enabled = &features2->features;
    }
    uint32_t requested = enabled ? take_features(enabled, gaps) : 0;
    VkBool32 saved_robust = robust ? robust->robustBufferAccess2 : VK_FALSE;
    if (robust && (gaps & GAP_ROBUSTNESS2) && robust->robustBufferAccess2) {
        requested |= GAP_ROBUSTNESS2;
        robust->robustBufferAccess2 = VK_FALSE;
        /* The first version of robust buffer access is the nearest the driver has. */
        if (enabled)
            enabled->robustBufferAccess = VK_TRUE;
    }

    /* The extensions the layer stands in for leave the list, and their structures the chain. */
    const char **names = NULL;
    if (gaps & (GAP_DEPTH_CLIP | GAP_NULL_DESCRIPTOR)) {
        names = malloc((info->enabledExtensionCount ? info->enabledExtensionCount : 1) * sizeof(*names));
        copy.enabledExtensionCount = 0;
        for (uint32_t i = 0; i < info->enabledExtensionCount; i++) {
            const char *name = info->ppEnabledExtensionNames[i];
            if ((gaps & GAP_DEPTH_CLIP) && !strcmp(name, VK_EXT_DEPTH_CLIP_ENABLE_EXTENSION_NAME)) {
                requested |= GAP_DEPTH_CLIP;
                continue;
            }
            if ((gaps & GAP_NULL_DESCRIPTOR) && !strcmp(name, VK_EXT_ROBUSTNESS_2_EXTENSION_NAME)) {
                requested |= GAP_NULL_DESCRIPTOR;
                if (enabled)
                    enabled->robustBufferAccess = VK_TRUE;
                continue;
            }
            names[copy.enabledExtensionCount++] = name;
        }
        copy.ppEnabledExtensionNames = names;
    }
    VkStructureType foreign[4];
    struct unlinked dropped[8];
    uint32_t dropped_count = unlink_types(&copy, foreign, foreign_types(gaps, foreign), dropped, 8);

    VkResult result = create(physical_device, &copy, allocator, device);

    relink(dropped, dropped_count);
    free(names);
    if (features2 && !info->pEnabledFeatures)
        features2->features = saved_features;
    if (robust)
        robust->robustBufferAccess2 = saved_robust;
    if (result != VK_SUCCESS)
        return result;

    struct device *dev = calloc(1, sizeof(*dev));
    dev->key = dispatch_key(*device);
    dev->handle = *device;
    dev->gdpa = gdpa;
    dev->emulated = requested;
    pthread_mutex_init(&dev->lock, NULL);
#define LOAD(name) \
    dev->name = (PFN_vk##name)gdpa(*device, "vk" #name); \
    if (!dev->name) \
        dev->name = (PFN_vk##name)gdpa(*device, "vk" #name "KHR"); \
    if (!dev->name) \
        dev->name = (PFN_vk##name)gdpa(*device, "vk" #name "EXT");
    DEVICE_FUNCTIONS(LOAD)
#undef LOAD
    inst->GetPhysicalDeviceMemoryProperties(physical_device, &dev->memory_properties);
    VkPhysicalDeviceProperties properties;
    inst->GetPhysicalDeviceProperties(physical_device, &properties);
    uint32_t components = properties.limits.maxVertexOutputComponents;
    if (properties.limits.maxFragmentInputComponents < components)
        components = properties.limits.maxFragmentInputComponents;
    dev->max_varying_locations = components / 4;

    pthread_mutex_lock(&global_lock);
    dev->next = devices;
    devices = dev;
    pthread_mutex_unlock(&global_lock);

    if (requested) {
        layer_log("emulating%s%s%s%s%s%s%s%s%s for %s", (requested & GAP_FILL_MODE) ? " fillModeNonSolid" : "",
                  (requested & GAP_MULTI_VIEWPORT) ? " multiViewport" : "",
                  (requested & GAP_CLIP_DISTANCE) ? " shaderClipDistance" : "",
                  (requested & GAP_CULL_DISTANCE) ? " shaderCullDistance" : "",
                  (requested & GAP_ROBUSTNESS2) ? " robustBufferAccess2" : "",
                  (requested & GAP_BC) ? " textureCompressionBC" : "",
                  (requested & GAP_MULTI_DRAW) ? " multiDrawIndirect" : "",
                  (requested & GAP_DEPTH_CLIP) ? " depthClipEnable" : "",
                  (requested & GAP_NULL_DESCRIPTOR) ? " nullDescriptor" : "", properties.deviceName);
    }
    return VK_SUCCESS;
}

static VKAPI_ATTR void VKAPI_CALL DestroyDevice(VkDevice device, const VkAllocationCallbacks *allocator)
{
    void *key = dispatch_key(device);
    pthread_mutex_lock(&global_lock);
    struct device **link = &devices;
    while (*link && (*link)->key != key)
        link = &(*link)->next;
    struct device *dev = *link;
    if (dev)
        *link = dev->next;
    pthread_mutex_unlock(&global_lock);
    if (!dev)
        return;
    bc_destroy_device(dev);
    null_resources_destroy(dev);
    dev->DestroyDevice(device, allocator);
    for (uint32_t i = 0; i < dev->shader_modules.capacity; i++) {
        struct spirv_code *code = dev->shader_modules.values[i];
        if (code) {
            free(code->words);
            free(code);
        }
    }
    map_free(&dev->shader_modules);
    pthread_mutex_destroy(&dev->lock);
    free(dev);
}

struct hook {
    const char *name;
    PFN_vkVoidFunction function;
    uint32_t gaps;
};

#define HOOK(name, gaps) {"vk" #name, (PFN_vkVoidFunction)name, gaps}
#define HOOK_ALIAS(name, alias, gaps) {"vk" #alias, (PFN_vkVoidFunction)name, gaps}

static const struct hook device_hooks[] = {
    HOOK(CreateShaderModule, GAPS_SHADER),
    HOOK(DestroyShaderModule, GAPS_SHADER),
    HOOK(CreateGraphicsPipelines, GAPS_PIPELINE),
    HOOK(CmdSetViewport, GAP_MULTI_VIEWPORT),
    HOOK(CmdSetScissor, GAP_MULTI_VIEWPORT),
    HOOK(CmdSetViewportWithCount, GAP_MULTI_VIEWPORT),
    HOOK_ALIAS(CmdSetViewportWithCount, CmdSetViewportWithCountEXT, GAP_MULTI_VIEWPORT),
    HOOK(CmdSetScissorWithCount, GAP_MULTI_VIEWPORT),
    HOOK_ALIAS(CmdSetScissorWithCount, CmdSetScissorWithCountEXT, GAP_MULTI_VIEWPORT),
    HOOK(CreateImage, GAP_BC),
    HOOK(DestroyImage, GAP_BC),
    HOOK(CreateImageView, GAP_BC),
    HOOK(GetDeviceImageMemoryRequirements, GAP_BC),
    HOOK_ALIAS(GetDeviceImageMemoryRequirements, GetDeviceImageMemoryRequirementsKHR, GAP_BC),
    HOOK(GetDeviceImageSparseMemoryRequirements, GAP_BC),
    HOOK_ALIAS(GetDeviceImageSparseMemoryRequirements, GetDeviceImageSparseMemoryRequirementsKHR, GAP_BC),
    HOOK(GetDeviceImageSubresourceLayout, GAP_BC),
    HOOK_ALIAS(GetDeviceImageSubresourceLayout, GetDeviceImageSubresourceLayoutKHR, GAP_BC),
    HOOK(CmdCopyBufferToImage, GAP_BC),
    HOOK(CmdCopyBufferToImage2, GAP_BC),
    HOOK_ALIAS(CmdCopyBufferToImage2, CmdCopyBufferToImage2KHR, GAP_BC),
    HOOK(CmdCopyImageToBuffer, GAP_BC),
    HOOK(CmdCopyImageToBuffer2, GAP_BC),
    HOOK_ALIAS(CmdCopyImageToBuffer2, CmdCopyImageToBuffer2KHR, GAP_BC),
    HOOK(CmdCopyImage, GAP_BC),
    HOOK(CmdCopyImage2, GAP_BC),
    HOOK_ALIAS(CmdCopyImage2, CmdCopyImage2KHR, GAP_BC),
    HOOK(BindBufferMemory, GAP_BC),
    HOOK(BindBufferMemory2, GAP_BC),
    HOOK_ALIAS(BindBufferMemory2, BindBufferMemory2KHR, GAP_BC),
    HOOK(DestroyBuffer, GAP_BC),
    HOOK(FreeMemory, GAP_BC),
    HOOK(MapMemory, GAP_BC),
    HOOK(MapMemory2, GAP_BC),
    HOOK_ALIAS(MapMemory2, MapMemory2KHR, GAP_BC),
    HOOK(UnmapMemory, GAP_BC),
    HOOK(UnmapMemory2, GAP_BC),
    HOOK_ALIAS(UnmapMemory2, UnmapMemory2KHR, GAP_BC),
    HOOK(AllocateCommandBuffers, GAP_BC),
    HOOK(FreeCommandBuffers, GAP_BC),
    HOOK(BeginCommandBuffer, GAP_BC),
    HOOK(ResetCommandBuffer, GAP_BC),
    HOOK(ResetCommandPool, GAP_BC),
    HOOK(DestroyCommandPool, GAP_BC),
    HOOK(CmdDrawIndirect, GAP_MULTI_DRAW),
    HOOK(CmdDrawIndexedIndirect, GAP_MULTI_DRAW),
    HOOK(UpdateDescriptorSets, GAP_NULL_DESCRIPTOR),
    HOOK(CmdPushDescriptorSet, GAP_NULL_DESCRIPTOR),
    HOOK_ALIAS(CmdPushDescriptorSet, CmdPushDescriptorSetKHR, GAP_NULL_DESCRIPTOR),
    HOOK(CreateDescriptorUpdateTemplate, GAP_NULL_DESCRIPTOR),
    HOOK_ALIAS(CreateDescriptorUpdateTemplate, CreateDescriptorUpdateTemplateKHR, GAP_NULL_DESCRIPTOR),
    HOOK(DestroyDescriptorUpdateTemplate, GAP_NULL_DESCRIPTOR),
    HOOK_ALIAS(DestroyDescriptorUpdateTemplate, DestroyDescriptorUpdateTemplateKHR, GAP_NULL_DESCRIPTOR),
    HOOK(UpdateDescriptorSetWithTemplate, GAP_NULL_DESCRIPTOR),
    HOOK_ALIAS(UpdateDescriptorSetWithTemplate, UpdateDescriptorSetWithTemplateKHR, GAP_NULL_DESCRIPTOR),
    HOOK(CmdPushDescriptorSetWithTemplate, GAP_NULL_DESCRIPTOR),
    HOOK_ALIAS(CmdPushDescriptorSetWithTemplate, CmdPushDescriptorSetWithTemplateKHR, GAP_NULL_DESCRIPTOR),
    HOOK(CmdBindVertexBuffers, GAP_NULL_DESCRIPTOR),
    HOOK(CmdBindVertexBuffers2, GAP_NULL_DESCRIPTOR),
    HOOK_ALIAS(CmdBindVertexBuffers2, CmdBindVertexBuffers2EXT, GAP_NULL_DESCRIPTOR),
};

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL GetDeviceProcAddr(VkDevice device, const char *name);

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL GetDeviceProcAddr(VkDevice device, const char *name)
{
    if (!strcmp(name, "vkGetDeviceProcAddr"))
        return (PFN_vkVoidFunction)GetDeviceProcAddr;
    struct device *dev = device_from_handle(device);
    if (!dev)
        return NULL;
    if (!strcmp(name, "vkDestroyDevice"))
        return (PFN_vkVoidFunction)DestroyDevice;
    PFN_vkVoidFunction next = dev->gdpa(device, name);
    if (!next)
        return NULL;
    for (size_t i = 0; i < sizeof(device_hooks) / sizeof(device_hooks[0]); i++) {
        if ((dev->emulated & device_hooks[i].gaps) && !strcmp(name, device_hooks[i].name))
            return device_hooks[i].function;
    }
    return next;
}

static const struct hook physical_device_hooks[] = {
    HOOK(EnumerateDeviceExtensionProperties, 0),
    HOOK(GetPhysicalDeviceFeatures, 0),
    HOOK(GetPhysicalDeviceFeatures2, 0),
    HOOK_ALIAS(GetPhysicalDeviceFeatures2, GetPhysicalDeviceFeatures2KHR, 0),
    HOOK(GetPhysicalDeviceProperties, 0),
    HOOK(GetPhysicalDeviceProperties2, 0),
    HOOK_ALIAS(GetPhysicalDeviceProperties2, GetPhysicalDeviceProperties2KHR, 0),
    HOOK(GetPhysicalDeviceFormatProperties, 0),
    HOOK(GetPhysicalDeviceFormatProperties2, 0),
    HOOK_ALIAS(GetPhysicalDeviceFormatProperties2, GetPhysicalDeviceFormatProperties2KHR, 0),
    HOOK(GetPhysicalDeviceImageFormatProperties, 0),
    HOOK(GetPhysicalDeviceImageFormatProperties2, 0),
    HOOK_ALIAS(GetPhysicalDeviceImageFormatProperties2, GetPhysicalDeviceImageFormatProperties2KHR, 0),
};

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL GetInstanceProcAddr(VkInstance instance, const char *name)
{
    if (!strcmp(name, "vkGetInstanceProcAddr"))
        return (PFN_vkVoidFunction)GetInstanceProcAddr;
    if (!strcmp(name, "vkCreateInstance"))
        return (PFN_vkVoidFunction)CreateInstance;
    if (!strcmp(name, "vkGetDeviceProcAddr"))
        return (PFN_vkVoidFunction)GetDeviceProcAddr;
    if (!instance)
        return NULL;
    struct instance *inst = instance_from_handle(instance);
    if (!inst)
        return NULL;
    if (!strcmp(name, "vkDestroyInstance"))
        return (PFN_vkVoidFunction)DestroyInstance;
    /* Always ours, even when inactive: each layer moves the device chain one link on. */
    if (!strcmp(name, "vkCreateDevice"))
        return (PFN_vkVoidFunction)CreateDevice;
    PFN_vkVoidFunction next = inst->gipa(instance, name);
    if (next && tracing()) {
        PFN_vkVoidFunction traced = trace_hook(inst, name);
        if (traced)
            return traced;
    }
    if (!next || !inst->active)
        return next;
    for (size_t i = 0; i < sizeof(physical_device_hooks) / sizeof(physical_device_hooks[0]); i++) {
        if (!strcmp(name, physical_device_hooks[i].name))
            return physical_device_hooks[i].function;
    }
    return next;
}

__attribute__((visibility("default"))) VKAPI_ATTR VkResult VKAPI_CALL
vkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface *interface)
{
    if (interface->loaderLayerInterfaceVersion < 2)
        return VK_ERROR_INITIALIZATION_FAILED;
    interface->loaderLayerInterfaceVersion = 2;
    interface->pfnGetInstanceProcAddr = GetInstanceProcAddr;
    interface->pfnGetDeviceProcAddr = GetDeviceProcAddr;
    interface->pfnGetPhysicalDeviceProcAddr = NULL;
    return VK_SUCCESS;
}
