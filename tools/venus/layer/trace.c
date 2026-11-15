/*
 * DROIDDECK_MALI_COMPAT_TRACE=1: the physical-device queries an application makes before
 * vkCreateDevice, logged as they pass through. Zink on Venus over an older Mali driver gave up
 * after vkGetPhysicalDeviceProperties2 without a word (its release builds print nothing there),
 * so the last query in the log is where it stopped.
 */
#include "mali_compat.h"

#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static uint32_t format_queries;

static bool format_worth_logging(uint32_t n, VkFormatFeatureFlags linear, VkFormatFeatureFlags optimal, VkFormatFeatureFlags buffer)
{
    return n == 1 || n % 25 == 0 || (!linear && !optimal && !buffer);
}

static VKAPI_ATTR VkResult VKAPI_CALL TraceEnumeratePhysicalDevices(VkInstance instance, uint32_t *count, VkPhysicalDevice *devices)
{
    struct instance *inst = instance_from_handle(instance);
    VkResult r = inst->EnumeratePhysicalDevices(instance, count, devices);
    layer_log("trace: vkEnumeratePhysicalDevices (%s): %d, %u device(s)", devices ? "list" : "count", (int)r, count ? *count : 0);
    return r;
}

static VKAPI_ATTR void VKAPI_CALL TraceGetPhysicalDeviceMemoryProperties(VkPhysicalDevice pd, VkPhysicalDeviceMemoryProperties *p)
{
    struct instance *inst = instance_from_handle(pd);
    inst->GetPhysicalDeviceMemoryProperties(pd, p);
    char types[384] = "";
    size_t o = 0;
    for (uint32_t i = 0; i < p->memoryTypeCount && o + 16 < sizeof(types); i++)
        o += (size_t)snprintf(types + o, sizeof(types) - o, " %u:h%u/0x%x", i, p->memoryTypes[i].heapIndex,
                              (unsigned)p->memoryTypes[i].propertyFlags);
    layer_log("trace: vkGetPhysicalDeviceMemoryProperties: %u heaps, %u types:%s", p->memoryHeapCount, p->memoryTypeCount, types);
}

static VKAPI_ATTR void VKAPI_CALL TraceGetPhysicalDeviceMemoryProperties2(VkPhysicalDevice pd, VkPhysicalDeviceMemoryProperties2 *p)
{
    struct instance *inst = instance_from_handle(pd);
    inst->GetPhysicalDeviceMemoryProperties2(pd, p);
    char chain[160] = "";
    size_t o = 0;
    for (VkBaseOutStructure *s = p->pNext; s && o + 16 < sizeof(chain); s = s->pNext)
        o += (size_t)snprintf(chain + o, sizeof(chain) - o, " %d", (int)s->sType);
    layer_log("trace: vkGetPhysicalDeviceMemoryProperties2: %u heaps, %u types, chain:%s", p->memoryProperties.memoryHeapCount,
              p->memoryProperties.memoryTypeCount, chain);
}

static void log_queue_families(const char *what, uint32_t n, const VkQueueFamilyProperties *(*at)(const void *, uint32_t), const void *arr)
{
    char fams[256] = "";
    size_t o = 0;
    for (uint32_t i = 0; arr && i < n && o + 24 < sizeof(fams); i++) {
        const VkQueueFamilyProperties *q = at(arr, i);
        o += (size_t)snprintf(fams + o, sizeof(fams) - o, " %u:0x%x*%u", i, (unsigned)q->queueFlags, q->queueCount);
    }
    layer_log("trace: %s: %u famil%s%s", what, n, n == 1 ? "y" : "ies", fams);
}

static const VkQueueFamilyProperties *qf1(const void *a, uint32_t i) { return &((const VkQueueFamilyProperties *)a)[i]; }
static const VkQueueFamilyProperties *qf2(const void *a, uint32_t i) { return &((const VkQueueFamilyProperties2 *)a)[i].queueFamilyProperties; }

static VKAPI_ATTR void VKAPI_CALL TraceGetPhysicalDeviceQueueFamilyProperties(VkPhysicalDevice pd, uint32_t *n, VkQueueFamilyProperties *p)
{
    struct instance *inst = instance_from_handle(pd);
    inst->GetPhysicalDeviceQueueFamilyProperties(pd, n, p);
    log_queue_families("vkGetPhysicalDeviceQueueFamilyProperties", n ? *n : 0, qf1, p);
}

static VKAPI_ATTR void VKAPI_CALL TraceGetPhysicalDeviceQueueFamilyProperties2(VkPhysicalDevice pd, uint32_t *n, VkQueueFamilyProperties2 *p)
{
    struct instance *inst = instance_from_handle(pd);
    inst->GetPhysicalDeviceQueueFamilyProperties2(pd, n, p);
    log_queue_families("vkGetPhysicalDeviceQueueFamilyProperties2", n ? *n : 0, qf2, p);
}

static VKAPI_ATTR void VKAPI_CALL TraceGetPhysicalDeviceFormatProperties(VkPhysicalDevice pd, VkFormat format, VkFormatProperties *p)
{
    struct instance *inst = instance_from_handle(pd);
    if (inst->active) GetPhysicalDeviceFormatProperties(pd, format, p);
    else inst->GetPhysicalDeviceFormatProperties(pd, format, p);
    uint32_t n = __atomic_add_fetch(&format_queries, 1, __ATOMIC_RELAXED);
    if (format_worth_logging(n, p->linearTilingFeatures, p->optimalTilingFeatures, p->bufferFeatures))
        layer_log("trace: vkGetPhysicalDeviceFormatProperties #%u: format %d linear 0x%x optimal 0x%x buffer 0x%x", n, (int)format,
                  (unsigned)p->linearTilingFeatures, (unsigned)p->optimalTilingFeatures, (unsigned)p->bufferFeatures);
}

static VKAPI_ATTR void VKAPI_CALL TraceGetPhysicalDeviceFormatProperties2(VkPhysicalDevice pd, VkFormat format, VkFormatProperties2 *p)
{
    struct instance *inst = instance_from_handle(pd);
    if (inst->active) GetPhysicalDeviceFormatProperties2(pd, format, p);
    else inst->GetPhysicalDeviceFormatProperties2(pd, format, p);
    uint32_t n = __atomic_add_fetch(&format_queries, 1, __ATOMIC_RELAXED);
    const VkFormatProperties *f = &p->formatProperties;
    if (format_worth_logging(n, f->linearTilingFeatures, f->optimalTilingFeatures, f->bufferFeatures))
        layer_log("trace: vkGetPhysicalDeviceFormatProperties2 #%u: format %d linear 0x%x optimal 0x%x buffer 0x%x", n, (int)format,
                  (unsigned)f->linearTilingFeatures, (unsigned)f->optimalTilingFeatures, (unsigned)f->bufferFeatures);
}

static VKAPI_ATTR VkResult VKAPI_CALL TraceGetPhysicalDeviceImageFormatProperties(VkPhysicalDevice pd, VkFormat format, VkImageType type,
                                                                                VkImageTiling tiling, VkImageUsageFlags usage,
                                                                                VkImageCreateFlags flags, VkImageFormatProperties *p)
{
    struct instance *inst = instance_from_handle(pd);
    VkResult r = inst->active ? GetPhysicalDeviceImageFormatProperties(pd, format, type, tiling, usage, flags, p)
                              : inst->GetPhysicalDeviceImageFormatProperties(pd, format, type, tiling, usage, flags, p);
    if (r != VK_SUCCESS)
        layer_log("trace: vkGetPhysicalDeviceImageFormatProperties: format %d type %d tiling %d usage 0x%x flags 0x%x -> %d", (int)format,
                  (int)type, (int)tiling, (unsigned)usage, (unsigned)flags, (int)r);
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL TraceGetPhysicalDeviceImageFormatProperties2(VkPhysicalDevice pd, const VkPhysicalDeviceImageFormatInfo2 *info,
                                                                                 VkImageFormatProperties2 *p)
{
    struct instance *inst = instance_from_handle(pd);
    VkResult r = inst->active ? GetPhysicalDeviceImageFormatProperties2(pd, info, p) : inst->GetPhysicalDeviceImageFormatProperties2(pd, info, p);
    if (r != VK_SUCCESS)
        layer_log("trace: vkGetPhysicalDeviceImageFormatProperties2: format %d type %d tiling %d usage 0x%x flags 0x%x -> %d", (int)info->format,
                  (int)info->type, (int)info->tiling, (unsigned)info->usage, (unsigned)info->flags, (int)r);
    return r;
}

static VKAPI_ATTR void VKAPI_CALL TraceGetPhysicalDeviceExternalBufferProperties(VkPhysicalDevice pd, const VkPhysicalDeviceExternalBufferInfo *info,
                                                                               VkExternalBufferProperties *p)
{
    struct instance *inst = instance_from_handle(pd);
    inst->GetPhysicalDeviceExternalBufferProperties(pd, info, p);
    layer_log("trace: vkGetPhysicalDeviceExternalBufferProperties: handle 0x%x usage 0x%x -> features 0x%x", (unsigned)info->handleType,
              (unsigned)info->usage, (unsigned)p->externalMemoryProperties.externalMemoryFeatures);
}

static VKAPI_ATTR void VKAPI_CALL TraceGetPhysicalDeviceExternalSemaphoreProperties(VkPhysicalDevice pd,
                                                                                  const VkPhysicalDeviceExternalSemaphoreInfo *info,
                                                                                  VkExternalSemaphoreProperties *p)
{
    struct instance *inst = instance_from_handle(pd);
    inst->GetPhysicalDeviceExternalSemaphoreProperties(pd, info, p);
    layer_log("trace: vkGetPhysicalDeviceExternalSemaphoreProperties: handle 0x%x -> features 0x%x", (unsigned)info->handleType,
              (unsigned)p->externalSemaphoreFeatures);
}

static VKAPI_ATTR void VKAPI_CALL TraceGetPhysicalDeviceExternalFenceProperties(VkPhysicalDevice pd, const VkPhysicalDeviceExternalFenceInfo *info,
                                                                              VkExternalFenceProperties *p)
{
    struct instance *inst = instance_from_handle(pd);
    inst->GetPhysicalDeviceExternalFenceProperties(pd, info, p);
    layer_log("trace: vkGetPhysicalDeviceExternalFenceProperties: handle 0x%x -> features 0x%x", (unsigned)info->handleType,
              (unsigned)p->externalFenceFeatures);
}

void trace_extension_names(const VkExtensionProperties *ext, uint32_t n)
{
    char line[400];
    size_t o = 0;
    line[0] = 0;
    for (uint32_t i = 0; i < n; i++) {
        size_t len = strlen(ext[i].extensionName);
        if (o && o + len + 2 > sizeof(line)) {
            layer_log("trace:   extensions:%s", line);
            o = 0;
            line[0] = 0;
        }
        o += (size_t)snprintf(line + o, sizeof(line) - o, " %s", ext[i].extensionName);
    }
    if (o) layer_log("trace:   extensions:%s", line);
}

void trace_load(struct instance *inst)
{
#define T_LOAD(name) inst->name = (PFN_vk##name)inst->gipa(inst->handle, "vk" #name);
#define T_LOAD_KHR(name) \
    if (!inst->name) \
        inst->name = (PFN_vk##name)inst->gipa(inst->handle, "vk" #name "KHR");
    T_LOAD(EnumeratePhysicalDevices)
    T_LOAD(GetPhysicalDeviceMemoryProperties2)
    T_LOAD_KHR(GetPhysicalDeviceMemoryProperties2)
    T_LOAD(GetPhysicalDeviceQueueFamilyProperties)
    T_LOAD(GetPhysicalDeviceQueueFamilyProperties2)
    T_LOAD_KHR(GetPhysicalDeviceQueueFamilyProperties2)
    T_LOAD(GetPhysicalDeviceExternalBufferProperties)
    T_LOAD_KHR(GetPhysicalDeviceExternalBufferProperties)
    T_LOAD(GetPhysicalDeviceExternalSemaphoreProperties)
    T_LOAD_KHR(GetPhysicalDeviceExternalSemaphoreProperties)
    T_LOAD(GetPhysicalDeviceExternalFenceProperties)
    T_LOAD_KHR(GetPhysicalDeviceExternalFenceProperties)
#undef T_LOAD
#undef T_LOAD_KHR
}

struct trace_hook {
    const char *name;
    PFN_vkVoidFunction function;
    size_t next_offset;
};

#define TH(fn, field) {"vk" #fn, (PFN_vkVoidFunction)Trace##field, offsetof(struct instance, field)}

static const struct trace_hook trace_hooks[] = {
    TH(EnumeratePhysicalDevices, EnumeratePhysicalDevices),
    TH(GetPhysicalDeviceMemoryProperties, GetPhysicalDeviceMemoryProperties),
    TH(GetPhysicalDeviceMemoryProperties2, GetPhysicalDeviceMemoryProperties2),
    TH(GetPhysicalDeviceMemoryProperties2KHR, GetPhysicalDeviceMemoryProperties2),
    TH(GetPhysicalDeviceQueueFamilyProperties, GetPhysicalDeviceQueueFamilyProperties),
    TH(GetPhysicalDeviceQueueFamilyProperties2, GetPhysicalDeviceQueueFamilyProperties2),
    TH(GetPhysicalDeviceQueueFamilyProperties2KHR, GetPhysicalDeviceQueueFamilyProperties2),
    TH(GetPhysicalDeviceFormatProperties, GetPhysicalDeviceFormatProperties),
    TH(GetPhysicalDeviceFormatProperties2, GetPhysicalDeviceFormatProperties2),
    TH(GetPhysicalDeviceFormatProperties2KHR, GetPhysicalDeviceFormatProperties2),
    TH(GetPhysicalDeviceImageFormatProperties, GetPhysicalDeviceImageFormatProperties),
    TH(GetPhysicalDeviceImageFormatProperties2, GetPhysicalDeviceImageFormatProperties2),
    TH(GetPhysicalDeviceImageFormatProperties2KHR, GetPhysicalDeviceImageFormatProperties2),
    TH(GetPhysicalDeviceExternalBufferProperties, GetPhysicalDeviceExternalBufferProperties),
    TH(GetPhysicalDeviceExternalBufferPropertiesKHR, GetPhysicalDeviceExternalBufferProperties),
    TH(GetPhysicalDeviceExternalSemaphoreProperties, GetPhysicalDeviceExternalSemaphoreProperties),
    TH(GetPhysicalDeviceExternalSemaphorePropertiesKHR, GetPhysicalDeviceExternalSemaphoreProperties),
    TH(GetPhysicalDeviceExternalFenceProperties, GetPhysicalDeviceExternalFenceProperties),
    TH(GetPhysicalDeviceExternalFencePropertiesKHR, GetPhysicalDeviceExternalFenceProperties),
};

PFN_vkVoidFunction trace_hook(struct instance *inst, const char *name)
{
    for (size_t i = 0; i < sizeof(trace_hooks) / sizeof(trace_hooks[0]); i++) {
        if (strcmp(name, trace_hooks[i].name)) continue;
        void *next = *(void **)((char *)inst + trace_hooks[i].next_offset);
        return next ? trace_hooks[i].function : NULL;
    }
    return NULL;
}
