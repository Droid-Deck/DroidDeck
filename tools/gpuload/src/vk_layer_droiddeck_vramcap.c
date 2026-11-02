// VK_LAYER_DROIDDECK_vram_cap: holds a game to $DROIDDECK_VRAM_CAP_MB of device-local memory. The heap sizes and
// VK_EXT_memory_budget budgets the game (DXVK, vkd3d-proton) reads are clamped, and vkAllocateMemory past the cap fails
// with VK_ERROR_OUT_OF_DEVICE_MEMORY, so a game that ignores the budget still stays under it.
#define _GNU_SOURCE
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>

#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "vramcap_acct.h"

#define EXPORT __attribute__((visibility("default")))
#define BUCKETS 1024

static uint64_t g_cap;

__attribute__((constructor)) static void init(void) {
    const char *mb = getenv("DROIDDECK_VRAM_CAP_MB");
    char *end = NULL;
    unsigned long long value = mb && *mb ? strtoull(mb, &end, 10) : 0;
    if (value && end && !*end && value < (UINT64_MAX >> 20)) g_cap = (uint64_t)value << 20;
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

struct block {
    struct block *next;
    VkDeviceMemory memory;
    uint64_t size;
    uint32_t heap;
};

struct dev {
    struct dev *next_in_list;
    VkDevice handle;
    PFN_vkGetDeviceProcAddr gdpa;
    PFN_vkDestroyDevice destroy;
    PFN_vkAllocateMemory allocate;
    PFN_vkFreeMemory release;
    VkPhysicalDeviceMemoryProperties memory;
    uint64_t used[VK_MAX_MEMORY_HEAPS];
    struct block *blocks[BUCKETS];
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

static int capped(const VkMemoryHeap *heap) { return g_cap && (heap->flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT); }

static void say(const char *what) {
    fprintf(stderr, "[droiddeck-vram-cap] pid %d: %s\n", (int)getpid(), what);
}

static void announce(void) {
    if (__atomic_exchange_n(&g_announced, 1, __ATOMIC_RELAXED)) return;
    char line[96];
    snprintf(line, sizeof(line), "device memory held to %llu MB", (unsigned long long)(g_cap >> 20));
    say(line);
}

static void clamp_properties(VkPhysicalDeviceMemoryProperties *p) {
    for (uint32_t i = 0; i < p->memoryHeapCount && i < VK_MAX_MEMORY_HEAPS; i++)
        if (capped(&p->memoryHeaps[i])) {
            p->memoryHeaps[i].size = vramcap_clamp(p->memoryHeaps[i].size, g_cap);
            announce();
        }
}

static void clamp_budget(const VkPhysicalDeviceMemoryProperties *p, VkPhysicalDeviceMemoryBudgetPropertiesEXT *b) {
    for (uint32_t i = 0; i < p->memoryHeapCount && i < VK_MAX_MEMORY_HEAPS; i++)
        if (capped(&p->memoryHeaps[i])) b->heapBudget[i] = vramcap_clamp(b->heapBudget[i], g_cap);
}

static void clamp_properties2(PFN_vkGetPhysicalDeviceMemoryProperties2 next, VkPhysicalDevice pd,
                              VkPhysicalDeviceMemoryProperties2 *out) {
    next(pd, out);
    clamp_properties(&out->memoryProperties);
    for (VkBaseOutStructure *s = out->pNext; s; s = s->pNext)
        if (s->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_BUDGET_PROPERTIES_EXT)
            clamp_budget(&out->memoryProperties, (VkPhysicalDeviceMemoryBudgetPropertiesEXT *)s);
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

// ---- allocations -----------------------------------------------------------------------------------------------

static size_t bucket(VkDeviceMemory memory) { return (size_t)(((uintptr_t)memory >> 4) % BUCKETS); }

static VkResult VKAPI_CALL AllocateMemory(VkDevice device, const VkMemoryAllocateInfo *info, const VkAllocationCallbacks *alloc,
                                          VkDeviceMemory *out) {
    struct dev *d = dev_for(device);
    if (!d) return VK_ERROR_DEVICE_LOST;
    uint32_t heap = info->memoryTypeIndex < d->memory.memoryTypeCount ? d->memory.memoryTypes[info->memoryTypeIndex].heapIndex : 0;
    int held = heap < d->memory.memoryHeapCount && capped(&d->memory.memoryHeaps[heap]);
    struct block *b = held ? calloc(1, sizeof(*b)) : NULL;
    if (held) {
        pthread_mutex_lock(&g_lock);
        int fits = b && vramcap_reserve(&d->used[heap], g_cap, info->allocationSize);
        pthread_mutex_unlock(&g_lock);
        if (!fits) {
            free(b);
            return b ? VK_ERROR_OUT_OF_DEVICE_MEMORY : VK_ERROR_OUT_OF_HOST_MEMORY;
        }
    }
    VkResult r = d->allocate(device, info, alloc, out);
    if (!held) return r;
    pthread_mutex_lock(&g_lock);
    if (r == VK_SUCCESS) {
        b->memory = *out;
        b->size = info->allocationSize;
        b->heap = heap;
        b->next = d->blocks[bucket(*out)];
        d->blocks[bucket(*out)] = b;
        b = NULL;
    } else {
        vramcap_release(&d->used[heap], info->allocationSize);
    }
    pthread_mutex_unlock(&g_lock);
    free(b);
    return r;
}

static void VKAPI_CALL FreeMemory(VkDevice device, VkDeviceMemory memory, const VkAllocationCallbacks *alloc) {
    struct dev *d = dev_for(device);
    if (!d) return;
    struct block *found = NULL;
    if (memory) {
        pthread_mutex_lock(&g_lock);
        for (struct block **p = &d->blocks[bucket(memory)]; *p; p = &(*p)->next)
            if ((*p)->memory == memory) {
                found = *p;
                *p = found->next;
                vramcap_release(&d->used[found->heap], found->size);
                break;
            }
        pthread_mutex_unlock(&g_lock);
    }
    free(found);
    d->release(device, memory, alloc);
}

// ---- instance and device ---------------------------------------------------------------------------------------

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
    d->allocate = (PFN_vkAllocateMemory)next_gdpa(*out, "vkAllocateMemory");
    d->release = (PFN_vkFreeMemory)next_gdpa(*out, "vkFreeMemory");
    if (in && in->mem) in->mem(pd, &d->memory);
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
    for (size_t i = 0; i < BUCKETS; i++)
        while (d->blocks[i]) {
            struct block *b = d->blocks[i];
            d->blocks[i] = b->next;
            free(b);
        }
    PFN_vkDestroyDevice destroy = d->destroy;
    free(d);
    if (destroy) destroy(device, alloc);
}

// ---- dispatch --------------------------------------------------------------------------------------------------

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL DroidDeckVramCap_GetDeviceProcAddr(VkDevice device, const char *name);

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL DroidDeckVramCap_GetInstanceProcAddr(VkInstance instance, const char *name) {
    if (!strcmp(name, "vkCreateInstance")) return (PFN_vkVoidFunction)CreateInstance;
    if (!strcmp(name, "vkDestroyInstance")) return (PFN_vkVoidFunction)DestroyInstance;
    if (!strcmp(name, "vkCreateDevice")) return (PFN_vkVoidFunction)CreateDevice;
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
    if (g_cap) {
        if (!strcmp(name, "vkAllocateMemory") && d->allocate) return (PFN_vkVoidFunction)AllocateMemory;
        if (!strcmp(name, "vkFreeMemory") && d->release) return (PFN_vkVoidFunction)FreeMemory;
    }
    return d->gdpa(device, name);
}

EXPORT VKAPI_ATTR VkResult VKAPI_CALL DroidDeckVramCap_NegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface *v) {
    if (v->loaderLayerInterfaceVersion > 2) v->loaderLayerInterfaceVersion = 2;
    v->pfnGetInstanceProcAddr = DroidDeckVramCap_GetInstanceProcAddr;
    v->pfnGetDeviceProcAddr = DroidDeckVramCap_GetDeviceProcAddr;
    v->pfnGetPhysicalDeviceProcAddr = NULL;
    return VK_SUCCESS;
}
