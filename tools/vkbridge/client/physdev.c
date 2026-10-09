/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Physical device queries: what the app sees is the server's GPU, with the bridge's memory
 * model, its extension list and the features the server emulates.
 */
#define _GNU_SOURCE
#include "vkb_client.h"
#include "gen/vkb_client_gen.h"
#include "emu.h"

#include <stdio.h>
#include <string.h>

const char *const *vkb_client_device_exts(uint32_t *n);

/* ------------------------------------------------------------------ memory model */

/*
 * Images and GPU-only buffers live in memory the client never maps; host-visible memory must be
 * shared pages (server_memory.c), which only buffers (and the rare linear image) can use. So the
 * app gets a discrete-GPU-like view: device-local types without host access, and host-visible
 * types (shared) that only resources able to use them list in their memoryTypeBits.
 */
static void build_memory_model(vkb_physdev *pd)
{
    const VkPhysicalDeviceMemoryProperties *s = &pd->server_mem;
    VkPhysicalDeviceMemoryProperties *c = &pd->client_mem;
    memset(c, 0, sizeof(*c));
    uint32_t share = pd->info.strategy != VKB_MEM_NONE ? pd->info.shareable_types : 0;
    int pure_dl = -1;
    for (uint32_t t = 0; t < s->memoryTypeCount; t++) {
        VkMemoryPropertyFlags f = s->memoryTypes[t].propertyFlags;
        if ((f & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) && !(f & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) &&
            !(f & VK_MEMORY_PROPERTY_PROTECTED_BIT)) {
            pure_dl = (int)t;
            break;
        }
    }
    int uma = pure_dl < 0;
    /* Heaps: the server's, plus (UMA) a host heap for shared types so they are not "device local". */
    for (uint32_t h = 0; h < s->memoryHeapCount; h++) {
        c->memoryHeaps[h] = s->memoryHeaps[h];
        pd->heap_map[h] = h;
    }
    c->memoryHeapCount = s->memoryHeapCount;
    uint32_t host_heap = UINT32_MAX;

    struct cand {
        VkMemoryPropertyFlags flags;
        uint32_t heap, server;
        uint8_t shared;
    } cands[VK_MAX_MEMORY_TYPES * 2];
    uint32_t nc = 0;
    if (uma) {
        /* A device-local type with no host access, backed by the first device-local type. */
        for (uint32_t t = 0; t < s->memoryTypeCount; t++) {
            VkMemoryPropertyFlags f = s->memoryTypes[t].propertyFlags;
            if ((f & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) && !(f & VK_MEMORY_PROPERTY_PROTECTED_BIT)) {
                cands[nc++] = (struct cand){VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, s->memoryTypes[t].heapIndex, t, 0};
                break;
            }
        }
    }
    for (uint32_t t = 0; t < s->memoryTypeCount && nc < VK_MAX_MEMORY_TYPES; t++) {
        VkMemoryPropertyFlags f = s->memoryTypes[t].propertyFlags;
        uint32_t heap = s->memoryTypes[t].heapIndex;
        if (!(f & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) {
            cands[nc++] = (struct cand){f, heap, t, 0};
            continue;
        }
        if (!(share & (1u << t))) continue; /* host-visible but not shareable: not offered */
        VkMemoryPropertyFlags cf = f;
        if (!(pd->info.coherent_types & (1u << t))) cf &= ~VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        if (uma) {
            cf &= ~VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
            if (host_heap == UINT32_MAX && c->memoryHeapCount < VK_MAX_MEMORY_HEAPS) {
                host_heap = c->memoryHeapCount++;
                c->memoryHeaps[host_heap] = s->memoryHeaps[heap];
                c->memoryHeaps[host_heap].flags &= ~VK_MEMORY_HEAP_DEVICE_LOCAL_BIT;
                pd->heap_map[host_heap] = heap;
            }
            if (host_heap != UINT32_MAX) heap = host_heap;
        }
        /* Two client types with identical flags on one heap are pointless: keep the first. */
        int dup = 0;
        for (uint32_t i = 0; i < nc; i++) dup |= cands[i].flags == cf && cands[i].heap == heap;
        if (!dup) cands[nc++] = (struct cand){cf, heap, t, 1};
    }
    /* Order: fewer property bits first, which satisfies the spec's subset ordering. */
    for (uint32_t i = 1; i < nc; i++) {
        struct cand x = cands[i];
        uint32_t j = i;
        while (j > 0 && __builtin_popcount(cands[j - 1].flags) > __builtin_popcount(x.flags)) {
            cands[j] = cands[j - 1];
            j--;
        }
        cands[j] = x;
    }
    if (nc > VK_MAX_MEMORY_TYPES) nc = VK_MAX_MEMORY_TYPES;
    c->memoryTypeCount = nc;
    for (uint32_t i = 0; i < nc; i++) {
        c->memoryTypes[i].propertyFlags = cands[i].flags;
        c->memoryTypes[i].heapIndex = cands[i].heap;
        pd->type_map[i].server_type = cands[i].server;
        pd->type_map[i].shared = cands[i].shared;
    }
    for (uint32_t i = 0; i < nc; i++)
        VKB_DBG("memory type %u: flags 0x%x heap %u -> server type %u%s", i, c->memoryTypes[i].propertyFlags,
                c->memoryTypes[i].heapIndex, pd->type_map[i].server_type, pd->type_map[i].shared ? " (shared)" : "");
}

uint32_t vkb_mem_bits_to_client(vkb_physdev *pd, uint32_t server_bits, int allow_shared)
{
    uint32_t bits = 0;
    for (uint32_t i = 0; i < pd->client_mem.memoryTypeCount; i++) {
        if (!(server_bits & (1u << pd->type_map[i].server_type))) continue;
        if (pd->type_map[i].shared && !allow_shared) continue;
        bits |= 1u << i;
    }
    return bits;
}

/* ------------------------------------------------------------------ setup */

static int bridged_device_ext(const char *name)
{
    for (int i = 0; i < VKB_EXT_COUNT; i++)
        if (vkb_exts[i].device && !vkb_exts[i].client && !strcmp(vkb_exts[i].name, name)) return 1;
    return 0;
}

int vkb_physdev_ready(vkb_physdev *pd)
{
    if (pd->ready) return pd->ready > 0;
    pthread_mutex_lock(&pd->lock);
    if (pd->ready) {
        pthread_mutex_unlock(&pd->lock);
        return pd->ready > 0;
    }
    vkb_call c;
    vkb_call_begin(&c, VKB_CMD_vkbQueryServer, pd->obj.table);
    vkb_enc_u64(&c.e, pd->obj.remote);
    if (!vkb_call_exec(&c)) {
        pd->ready = -1;
        pthread_mutex_unlock(&pd->lock);
        return 0;
    }
    vkb_dec_raw_into(&c.d, &pd->info, sizeof(pd->info));
    vkb_call_end(&c);
    vkb_wire_vkGetPhysicalDeviceProperties((VkPhysicalDevice)pd, &pd->props);
    vkb_wire_vkGetPhysicalDeviceMemoryProperties((VkPhysicalDevice)pd, &pd->server_mem);
    build_memory_model(pd);

    uint32_t n = 0;
    vkb_wire_vkEnumerateDeviceExtensionProperties((VkPhysicalDevice)pd, NULL, &n, NULL);
    VkExtensionProperties *srv = calloc(n + 1, sizeof(*srv));
    vkb_wire_vkEnumerateDeviceExtensionProperties((VkPhysicalDevice)pd, NULL, &n, srv);
    uint32_t ncl = 0;
    const char *const *cl = vkb_client_device_exts(&ncl);
    uint32_t nemu = 0;
    const VkExtensionProperties *emu = vkb_emu_extensions(pd, &nemu);
    pd->exts = calloc(n + ncl + nemu + 1, sizeof(VkExtensionProperties));
    pd->next = 0;
    for (uint32_t i = 0; i < n; i++)
        if (bridged_device_ext(srv[i].extensionName)) pd->exts[pd->next++] = srv[i];
    for (uint32_t i = 0; i < nemu; i++) {
        int dup = 0;
        for (uint32_t j = 0; j < pd->next; j++) dup |= !strcmp(pd->exts[j].extensionName, emu[i].extensionName);
        if (!dup) pd->exts[pd->next++] = emu[i];
    }
    for (uint32_t i = 0; i < ncl; i++) {
        VkExtensionProperties *e = &pd->exts[pd->next++];
        snprintf(e->extensionName, sizeof(e->extensionName), "%s", cl[i]);
        e->specVersion = !strcmp(cl[i], "VK_KHR_swapchain") ? 70 : !strcmp(cl[i], "VK_KHR_incremental_present") ? 2 : 1;
    }
    free(srv);
    VKB_INFO("%s: %u extensions offered, memory sharing %s, emulation 0x%x", pd->props.deviceName, pd->next,
             vkb_mem_strategy_name(pd->info.strategy), pd->info.emu);
    pd->ready = 1;
    pthread_mutex_unlock(&pd->lock);
    return 1;
}

/* ------------------------------------------------------------------ properties */

static void patch_props(vkb_physdev *pd, VkPhysicalDeviceProperties *p)
{
    if (p->apiVersion > VKB_API_VERSION) p->apiVersion = VKB_API_VERSION;
    (void)pd;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetPhysicalDeviceProperties(VkPhysicalDevice physicalDevice, VkPhysicalDeviceProperties *pProperties)
{
    vkb_physdev *pd = vkb_pd(physicalDevice);
    if (vkb_physdev_ready(pd)) *pProperties = pd->props;
    else vkb_wire_vkGetPhysicalDeviceProperties(physicalDevice, pProperties);
    patch_props(pd, pProperties);
    vkb_emu_patch_limits(pd, pProperties);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetPhysicalDeviceProperties2(VkPhysicalDevice physicalDevice, VkPhysicalDeviceProperties2 *pProperties)
{
    vkb_physdev *pd = vkb_pd(physicalDevice);
    vkb_wire_vkGetPhysicalDeviceProperties2(physicalDevice, pProperties);
    patch_props(pd, &pProperties->properties);
    vkb_emu_patch_limits(pd, &pProperties->properties);
    for (VkBaseOutStructure *b = (VkBaseOutStructure *)pProperties->pNext; b; b = b->pNext) {
        if (b->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES) {
            VkPhysicalDeviceDriverProperties *d = (VkPhysicalDeviceDriverProperties *)b;
            size_t l = strlen(d->driverInfo);
            if (l + 12 < sizeof(d->driverInfo)) strcat(d->driverInfo, " (vkbridge)");
        }
        vkb_emu_patch_properties_chain(pd, b);
    }
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetPhysicalDeviceFeatures(VkPhysicalDevice physicalDevice, VkPhysicalDeviceFeatures *pFeatures)
{
    vkb_wire_vkGetPhysicalDeviceFeatures(physicalDevice, pFeatures);
    vkb_emu_patch_features(vkb_pd(physicalDevice), pFeatures);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetPhysicalDeviceFeatures2(VkPhysicalDevice physicalDevice, VkPhysicalDeviceFeatures2 *pFeatures)
{
    vkb_wire_vkGetPhysicalDeviceFeatures2(physicalDevice, pFeatures);
    vkb_emu_patch_features(vkb_pd(physicalDevice), &pFeatures->features);
    for (VkBaseOutStructure *b = (VkBaseOutStructure *)pFeatures->pNext; b; b = b->pNext)
        vkb_emu_patch_features_chain(vkb_pd(physicalDevice), b);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetPhysicalDeviceMemoryProperties(VkPhysicalDevice physicalDevice,
                                                                      VkPhysicalDeviceMemoryProperties *pMemoryProperties)
{
    vkb_physdev *pd = vkb_pd(physicalDevice);
    if (!vkb_physdev_ready(pd)) {
        memset(pMemoryProperties, 0, sizeof(*pMemoryProperties));
        return;
    }
    *pMemoryProperties = pd->client_mem;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetPhysicalDeviceMemoryProperties2(VkPhysicalDevice physicalDevice,
                                                                       VkPhysicalDeviceMemoryProperties2 *pMemoryProperties)
{
    vkb_physdev *pd = vkb_pd(physicalDevice);
    VkPhysicalDeviceMemoryBudgetPropertiesEXT *budget = NULL;
    for (VkBaseOutStructure *b = (VkBaseOutStructure *)pMemoryProperties->pNext; b; b = b->pNext)
        if (b->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_BUDGET_PROPERTIES_EXT) budget = (void *)b;
    vkb_wire_vkGetPhysicalDeviceMemoryProperties2(physicalDevice, pMemoryProperties);
    if (!vkb_physdev_ready(pd)) return;
    pMemoryProperties->memoryProperties = pd->client_mem;
    if (budget) {
        VkDeviceSize b[VK_MAX_MEMORY_HEAPS], u[VK_MAX_MEMORY_HEAPS];
        memcpy(b, budget->heapBudget, sizeof(b));
        memcpy(u, budget->heapUsage, sizeof(u));
        memset(budget->heapBudget, 0, sizeof(budget->heapBudget));
        memset(budget->heapUsage, 0, sizeof(budget->heapUsage));
        for (uint32_t h = 0; h < pd->client_mem.memoryHeapCount; h++) {
            budget->heapBudget[h] = b[pd->heap_map[h]];
            budget->heapUsage[h] = u[pd->heap_map[h]];
        }
    }
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetPhysicalDeviceQueueFamilyProperties(VkPhysicalDevice physicalDevice, uint32_t *pCount,
                                                                           VkQueueFamilyProperties *pProps)
{
    vkb_wire_vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pCount, pProps);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetPhysicalDeviceQueueFamilyProperties2(VkPhysicalDevice physicalDevice, uint32_t *pCount,
                                                                            VkQueueFamilyProperties2 *pProps)
{
    vkb_wire_vkGetPhysicalDeviceQueueFamilyProperties2(physicalDevice, pCount, pProps);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetPhysicalDeviceFormatProperties(VkPhysicalDevice physicalDevice, VkFormat format,
                                                                      VkFormatProperties *pFormatProperties)
{
    if (vkb_emu_format_properties(vkb_pd(physicalDevice), format, pFormatProperties, NULL)) return;
    vkb_wire_vkGetPhysicalDeviceFormatProperties(physicalDevice, format, pFormatProperties);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetPhysicalDeviceFormatProperties2(VkPhysicalDevice physicalDevice, VkFormat format,
                                                                       VkFormatProperties2 *pFormatProperties)
{
    if (vkb_emu_format_properties(vkb_pd(physicalDevice), format, &pFormatProperties->formatProperties, pFormatProperties)) return;
    vkb_wire_vkGetPhysicalDeviceFormatProperties2(physicalDevice, format, pFormatProperties);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkGetPhysicalDeviceImageFormatProperties(VkPhysicalDevice physicalDevice, VkFormat format,
                                                                               VkImageType type, VkImageTiling tiling,
                                                                               VkImageUsageFlags usage, VkImageCreateFlags flags,
                                                                               VkImageFormatProperties *pProps)
{
    VkFormat real = vkb_emu_real_format(vkb_pd(physicalDevice), format, &usage, &flags);
    return vkb_wire_vkGetPhysicalDeviceImageFormatProperties(physicalDevice, real, type, tiling, usage, flags, pProps);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkGetPhysicalDeviceImageFormatProperties2(VkPhysicalDevice physicalDevice,
                                                                                const VkPhysicalDeviceImageFormatInfo2 *pInfo,
                                                                                VkImageFormatProperties2 *pProps)
{
    VkPhysicalDeviceImageFormatInfo2 info = *pInfo;
    info.format = vkb_emu_real_format(vkb_pd(physicalDevice), pInfo->format, &info.usage, &info.flags);
    return vkb_wire_vkGetPhysicalDeviceImageFormatProperties2(physicalDevice, &info, pProps);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetPhysicalDeviceSparseImageFormatProperties(VkPhysicalDevice physicalDevice, VkFormat format,
                                                                                 VkImageType type, VkSampleCountFlagBits samples,
                                                                                 VkImageUsageFlags usage, VkImageTiling tiling,
                                                                                 uint32_t *pCount, VkSparseImageFormatProperties *pProps)
{
    if (vkb_emu_is_emulated_format(vkb_pd(physicalDevice), format)) {
        *pCount = 0;
        return;
    }
    vkb_wire_vkGetPhysicalDeviceSparseImageFormatProperties(physicalDevice, format, type, samples, usage, tiling, pCount, pProps);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetPhysicalDeviceSparseImageFormatProperties2(VkPhysicalDevice physicalDevice,
                                                                                  const VkPhysicalDeviceSparseImageFormatInfo2 *pInfo,
                                                                                  uint32_t *pCount, VkSparseImageFormatProperties2 *pProps)
{
    if (vkb_emu_is_emulated_format(vkb_pd(physicalDevice), pInfo->format)) {
        *pCount = 0;
        return;
    }
    vkb_wire_vkGetPhysicalDeviceSparseImageFormatProperties2(physicalDevice, pInfo, pCount, pProps);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkGetPhysicalDeviceToolProperties(VkPhysicalDevice physicalDevice, uint32_t *pToolCount,
                                                                        VkPhysicalDeviceToolProperties *pToolProperties)
{
    (void)physicalDevice;
    (void)pToolProperties;
    *pToolCount = 0;
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetPhysicalDeviceExternalBufferProperties(VkPhysicalDevice physicalDevice,
                                                                              const VkPhysicalDeviceExternalBufferInfo *pInfo,
                                                                              VkExternalBufferProperties *pProps)
{
    vkb_wire_vkGetPhysicalDeviceExternalBufferProperties(physicalDevice, pInfo, pProps);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetPhysicalDeviceExternalSemaphoreProperties(VkPhysicalDevice physicalDevice,
                                                                                 const VkPhysicalDeviceExternalSemaphoreInfo *pInfo,
                                                                                 VkExternalSemaphoreProperties *pProps)
{
    vkb_wire_vkGetPhysicalDeviceExternalSemaphoreProperties(physicalDevice, pInfo, pProps);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetPhysicalDeviceExternalFenceProperties(VkPhysicalDevice physicalDevice,
                                                                             const VkPhysicalDeviceExternalFenceInfo *pInfo,
                                                                             VkExternalFenceProperties *pProps)
{
    vkb_wire_vkGetPhysicalDeviceExternalFenceProperties(physicalDevice, pInfo, pProps);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetPhysicalDeviceMultisamplePropertiesEXT(VkPhysicalDevice physicalDevice,
                                                                              VkSampleCountFlagBits samples,
                                                                              VkMultisamplePropertiesEXT *pProps)
{
    vkb_wire_vkGetPhysicalDeviceMultisamplePropertiesEXT(physicalDevice, samples, pProps);
}
