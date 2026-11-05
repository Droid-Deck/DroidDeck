#define _GNU_SOURCE
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>

#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "vramcap_budget.h"

#define EXPORT __attribute__((visibility("default")))

static uint64_t g_cap;

__attribute__((constructor)) static void init(void) {
    g_cap = vramcap_parse_mb(getenv("DROIDDECK_VRAM_CAP_MB"));
}

struct inst {
    struct inst *next_in_list;
    VkInstance handle;
    PFN_vkGetInstanceProcAddr gipa;
    PFN_vkDestroyInstance destroy;
    PFN_vkGetPhysicalDeviceMemoryProperties mem;
    PFN_vkGetPhysicalDeviceMemoryProperties2 mem2;
    PFN_vkGetPhysicalDeviceMemoryProperties2 mem2_khr;
};

struct dev {
    struct dev *next_in_list;
    VkDevice handle;
    PFN_vkGetDeviceProcAddr gdpa;
    PFN_vkDestroyDevice destroy;
};

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static struct inst *g_insts;
static struct dev *g_devs;
static int g_announced;

static void *key(const void *obj) { return *(void *const *)obj; }

static struct inst *inst_for(const void *obj) {
    struct inst *r;
    pthread_mutex_lock(&g_lock);
    for (r = g_insts; r && key(r->handle) != key(obj); r = r->next_in_list) {}
    pthread_mutex_unlock(&g_lock);
    return r;
}

static struct dev *dev_for(const void *obj) {
    struct dev *r;
    pthread_mutex_lock(&g_lock);
    for (r = g_devs; r && key(r->handle) != key(obj); r = r->next_in_list) {}
    pthread_mutex_unlock(&g_lock);
    return r;
}

static void say(const char *what) {
    fprintf(stderr, "[droiddeck-vram-cap] pid %d: %s\n", (int)getpid(), what);
}

static void announce(void) {
    if (__atomic_exchange_n(&g_announced, 1, __ATOMIC_RELAXED)) return;
    char line[96];
    snprintf(line, sizeof(line), "reported device memory budget %llu MB", (unsigned long long)(g_cap >> 20));
    say(line);
}

static void clamp_properties(VkPhysicalDeviceMemoryProperties *p) {
    vramcap_properties(p, g_cap);
    if (g_cap) announce();
}

static void clamp_properties2(PFN_vkGetPhysicalDeviceMemoryProperties2 next, VkPhysicalDevice pd,
                              VkPhysicalDeviceMemoryProperties2 *out) {
    next(pd, out);
    clamp_properties(&out->memoryProperties);
    for (VkBaseOutStructure *s = out->pNext; s; s = s->pNext)
        if (s->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_BUDGET_PROPERTIES_EXT)
            vramcap_budget(&out->memoryProperties, (VkPhysicalDeviceMemoryBudgetPropertiesEXT *)s, g_cap);
}

static void VKAPI_CALL GetMemoryProperties(VkPhysicalDevice pd, VkPhysicalDeviceMemoryProperties *out) {
    struct inst *in = inst_for(pd);
    if (!in) return;
    in->mem(pd, out);
    clamp_properties(out);
}

static void VKAPI_CALL GetMemoryProperties2(VkPhysicalDevice pd, VkPhysicalDeviceMemoryProperties2 *out) {
    struct inst *in = inst_for(pd);
    if (in) clamp_properties2(in->mem2, pd, out);
}

static void VKAPI_CALL GetMemoryProperties2KHR(VkPhysicalDevice pd, VkPhysicalDeviceMemoryProperties2 *out) {
    struct inst *in = inst_for(pd);
    if (in) clamp_properties2(in->mem2_khr, pd, out);
}

static VkResult VKAPI_CALL CreateInstance(const VkInstanceCreateInfo *ci, const VkAllocationCallbacks *alloc, VkInstance *out) {
    VkLayerInstanceCreateInfo *link = (VkLayerInstanceCreateInfo *)ci->pNext;
    while (link && !(link->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO && link->function == VK_LAYER_LINK_INFO))
        link = (VkLayerInstanceCreateInfo *)link->pNext;
    if (!link) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr next_gipa = link->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    link->u.pLayerInfo = link->u.pLayerInfo->pNext;
    PFN_vkCreateInstance create = (PFN_vkCreateInstance)next_gipa(VK_NULL_HANDLE, "vkCreateInstance");
    if (!create) return VK_ERROR_INITIALIZATION_FAILED;
    struct inst *in = calloc(1, sizeof(*in));
    if (!in) return VK_ERROR_OUT_OF_HOST_MEMORY;
    VkResult r = create(ci, alloc, out);
    if (r != VK_SUCCESS) { free(in); return r; }
    in->handle = *out;
    in->gipa = next_gipa;
    in->destroy = (PFN_vkDestroyInstance)next_gipa(*out, "vkDestroyInstance");
    in->mem = (PFN_vkGetPhysicalDeviceMemoryProperties)next_gipa(*out, "vkGetPhysicalDeviceMemoryProperties");
    in->mem2 = (PFN_vkGetPhysicalDeviceMemoryProperties2)next_gipa(*out, "vkGetPhysicalDeviceMemoryProperties2");
    in->mem2_khr = (PFN_vkGetPhysicalDeviceMemoryProperties2)next_gipa(*out, "vkGetPhysicalDeviceMemoryProperties2KHR");
    pthread_mutex_lock(&g_lock);
    in->next_in_list = g_insts;
    g_insts = in;
    pthread_mutex_unlock(&g_lock);
    return VK_SUCCESS;
}

static void VKAPI_CALL DestroyInstance(VkInstance instance, const VkAllocationCallbacks *alloc) {
    struct inst *in = inst_for(instance);
    if (!in) return;
    PFN_vkDestroyInstance destroy = in->destroy;
    pthread_mutex_lock(&g_lock);
    for (struct inst **p = &g_insts; *p; p = &(*p)->next_in_list)
        if (*p == in) { *p = in->next_in_list; break; }
    pthread_mutex_unlock(&g_lock);
    free(in);
    if (destroy) destroy(instance, alloc);
}

static VkLayerDeviceCreateInfo *device_chain(const VkDeviceCreateInfo *ci, VkLayerFunction function) {
    VkLayerDeviceCreateInfo *c = (VkLayerDeviceCreateInfo *)ci->pNext;
    while (c && !(c->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO && c->function == function))
        c = (VkLayerDeviceCreateInfo *)c->pNext;
    return c;
}

static VkResult VKAPI_CALL CreateDevice(VkPhysicalDevice pd, const VkDeviceCreateInfo *ci, const VkAllocationCallbacks *alloc,
                                        VkDevice *out) {
    VkLayerDeviceCreateInfo *link = device_chain(ci, VK_LAYER_LINK_INFO);
    if (!link) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr next_gipa = link->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr next_gdpa = link->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    link->u.pLayerInfo = link->u.pLayerInfo->pNext;
    struct inst *in = inst_for(pd);
    PFN_vkCreateDevice create = (PFN_vkCreateDevice)next_gipa(in ? in->handle : VK_NULL_HANDLE, "vkCreateDevice");
    if (!create) return VK_ERROR_INITIALIZATION_FAILED;
    struct dev *d = calloc(1, sizeof(*d));
    if (!d) return VK_ERROR_OUT_OF_HOST_MEMORY;
    VkResult r = create(pd, ci, alloc, out);
    if (r != VK_SUCCESS) { free(d); return r; }
    d->handle = *out;
    d->gdpa = next_gdpa;
    d->destroy = (PFN_vkDestroyDevice)next_gdpa(*out, "vkDestroyDevice");
    pthread_mutex_lock(&g_lock);
    d->next_in_list = g_devs;
    g_devs = d;
    pthread_mutex_unlock(&g_lock);
    return VK_SUCCESS;
}

static void VKAPI_CALL DestroyDevice(VkDevice device, const VkAllocationCallbacks *alloc) {
    struct dev *d = dev_for(device);
    if (!d) return;
    pthread_mutex_lock(&g_lock);
    for (struct dev **p = &g_devs; *p; p = &(*p)->next_in_list)
        if (*p == d) { *p = d->next_in_list; break; }
    pthread_mutex_unlock(&g_lock);
    PFN_vkDestroyDevice destroy = d->destroy;
    free(d);
    if (destroy) destroy(device, alloc);
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL DroidDeckVramCap_GetDeviceProcAddr(VkDevice device, const char *name);

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL DroidDeckVramCap_GetInstanceProcAddr(VkInstance instance, const char *name) {
    if (!strcmp(name, "vkCreateInstance")) return (PFN_vkVoidFunction)CreateInstance;
    if (!strcmp(name, "vkDestroyInstance")) return (PFN_vkVoidFunction)DestroyInstance;
    if (!strcmp(name, "vkCreateDevice")) return (PFN_vkVoidFunction)CreateDevice;
    if (!strcmp(name, "vkDestroyDevice")) return (PFN_vkVoidFunction)DestroyDevice;
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (PFN_vkVoidFunction)DroidDeckVramCap_GetInstanceProcAddr;
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)DroidDeckVramCap_GetDeviceProcAddr;
    if (!instance) return NULL;
    struct inst *in = inst_for(instance);
    if (!in) return NULL;
    if (g_cap) {
        if (!strcmp(name, "vkGetPhysicalDeviceMemoryProperties") && in->mem) return (PFN_vkVoidFunction)GetMemoryProperties;
        if (!strcmp(name, "vkGetPhysicalDeviceMemoryProperties2") && in->mem2) return (PFN_vkVoidFunction)GetMemoryProperties2;
        if (!strcmp(name, "vkGetPhysicalDeviceMemoryProperties2KHR") && in->mem2_khr)
            return (PFN_vkVoidFunction)GetMemoryProperties2KHR;
    }
    return in->gipa(instance, name);
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL DroidDeckVramCap_GetDeviceProcAddr(VkDevice device, const char *name) {
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)DroidDeckVramCap_GetDeviceProcAddr;
    if (!strcmp(name, "vkDestroyDevice")) return (PFN_vkVoidFunction)DestroyDevice;
    struct dev *d = dev_for(device);
    if (!d) return NULL;
    return d->gdpa(device, name);
}

EXPORT VKAPI_ATTR VkResult VKAPI_CALL DroidDeckVramCap_NegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface *v) {
    if (v->loaderLayerInterfaceVersion > 2) v->loaderLayerInterfaceVersion = 2;
    v->pfnGetInstanceProcAddr = DroidDeckVramCap_GetInstanceProcAddr;
    v->pfnGetDeviceProcAddr = DroidDeckVramCap_GetDeviceProcAddr;
    v->pfnGetPhysicalDeviceProcAddr = NULL;
    return VK_SUCCESS;
}
