/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Loader interface, instances, physical devices, devices and queues.
 */
#define _GNU_SOURCE
#include "vkb_client.h"
#include "gen/vkb_client_gen.h"
#include "wsi.h"

#include <stdio.h>
#include <string.h>

/* ------------------------------------------------------------------ proc lookup */

static int proc_cmp(const void *a, const void *b)
{
    return strcmp(((const vkb_proc_desc *)a)->name, ((const vkb_proc_desc *)b)->name);
}

static vkb_proc_desc *sorted_procs;
static pthread_once_t procs_once = PTHREAD_ONCE_INIT;

static void procs_init(void)
{
    sorted_procs = malloc(vkb_client_proc_count * sizeof(vkb_proc_desc));
    memcpy(sorted_procs, vkb_client_procs, vkb_client_proc_count * sizeof(vkb_proc_desc));
    qsort(sorted_procs, vkb_client_proc_count, sizeof(vkb_proc_desc), proc_cmp);
}

static const vkb_proc_desc *find_proc(const char *name)
{
    pthread_once(&procs_once, procs_init);
    vkb_proc_desc key = {name, NULL, 0, NULL};
    return bsearch(&key, sorted_procs, vkb_client_proc_count, sizeof(vkb_proc_desc), proc_cmp);
}

int vkb_device_ext_enabled(const vkb_device *d, const char *name)
{
    for (uint32_t i = 0; i < d->enabled_ext_count; i++)
        if (!strcmp(d->enabled_exts[i], name)) return 1;
    return 0;
}

PFN_vkVoidFunction vkb_lookup_proc(const char *name, int level_max, vkb_device *dev, vkb_instance *inst)
{
    (void)inst;
    if (!name) return NULL;
    const vkb_proc_desc *p = find_proc(name);
    if (!p) return NULL;
    if (level_max == VKB_LEVEL_DEVICE) {
        if (p->level != VKB_LEVEL_DEVICE) return NULL;
        if (dev && p->ext && !vkb_device_ext_enabled(dev, p->ext)) return NULL;
    }
    return p->fn;
}

/* ------------------------------------------------------------------ loader entry points */

VKB_EXPORT VKAPI_ATTR VkResult VKAPI_CALL vk_icdNegotiateLoaderICDInterfaceVersion(uint32_t *pVersion)
{
    if (*pVersion > 5) *pVersion = 5;
    return VK_SUCCESS;
}

VKB_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vk_icdGetInstanceProcAddr(VkInstance instance, const char *pName)
{
    if (!pName) return NULL;
    if (!instance) {
        const vkb_proc_desc *p = find_proc(pName);
        if (!p) return NULL;
        if (p->level == VKB_LEVEL_GLOBAL || !strcmp(pName, "vkGetInstanceProcAddr")) return p->fn;
        return NULL;
    }
    return vkb_lookup_proc(pName, VKB_LEVEL_DEVICE + 1, NULL, vkb_inst(instance));
}

VKB_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vk_icdGetPhysicalDeviceProcAddr(VkInstance instance, const char *pName)
{
    const vkb_proc_desc *p = find_proc(pName);
    if (!p || p->level != VKB_LEVEL_PHYSDEV) return NULL;
    (void)instance;
    return p->fn;
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkb_ep_vkGetInstanceProcAddr(VkInstance instance, const char *pName)
{
    return vk_icdGetInstanceProcAddr(instance, pName);
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkb_ep_vkGetDeviceProcAddr(VkDevice device, const char *pName)
{
    return vkb_lookup_proc(pName, VKB_LEVEL_DEVICE, vkb_dev(device), NULL);
}

/* ------------------------------------------------------------------ wrapping server objects */

void *vkb_wrap(VkObjectType type, const void *parent, uint64_t remote)
{
    if (!remote) return NULL;
    switch (type) {
    case VK_OBJECT_TYPE_PHYSICAL_DEVICE: {
        vkb_instance *inst = (vkb_instance *)parent;
        pthread_mutex_lock(&inst->lock);
        vkb_physdev *pd = inst->pds;
        while (pd && pd->obj.remote != remote) pd = pd->list_next;
        if (!pd) {
            pd = calloc(1, sizeof(*pd));
            pd->obj.loader.loaderMagic = ICD_LOADER_MAGIC;
            pd->obj.type = type;
            pd->obj.remote = remote;
            pd->obj.table = inst->obj.table;
            pd->instance = inst;
            pthread_mutex_init(&pd->lock, NULL);
            pd->list_next = inst->pds;
            inst->pds = pd;
        }
        pthread_mutex_unlock(&inst->lock);
        return pd;
    }
    case VK_OBJECT_TYPE_QUEUE: {
        vkb_device *dev = (vkb_device *)parent;
        pthread_mutex_lock(&dev->lock);
        vkb_queue *q = dev->queues;
        while (q && q->obj.remote != remote) q = q->next;
        if (!q) {
            q = calloc(1, sizeof(*q));
            q->obj.loader.loaderMagic = ICD_LOADER_MAGIC;
            q->obj.type = type;
            q->obj.remote = remote;
            q->obj.table = dev->obj.table;
            q->device = dev;
            q->next = dev->queues;
            dev->queues = q;
        }
        pthread_mutex_unlock(&dev->lock);
        return q;
    }
    case VK_OBJECT_TYPE_COMMAND_BUFFER: {
        vkb_device *dev = (vkb_device *)parent;
        vkb_cmdbuf *cb = calloc(1, sizeof(*cb));
        cb->obj.loader.loaderMagic = ICD_LOADER_MAGIC;
        cb->obj.type = type;
        cb->obj.remote = remote;
        cb->obj.table = dev->obj.table;
        cb->device = dev;
        vkb_enc_init(&cb->stream);
        return cb;
    }
    default:
        VKB_ERR("internal: cannot wrap object type %d", type);
        return NULL;
    }
}

/* ------------------------------------------------------------------ instance */

static uint32_t server_instance_version(void)
{
    static uint32_t v;
    if (!v) {
        uint32_t sv = VK_API_VERSION_1_0;
        if (vkb_wire_vkEnumerateInstanceVersion(&sv) != VK_SUCCESS) return VK_API_VERSION_1_0;
        extern uint32_t vkb_max_api(void);
        v = sv < vkb_max_api() ? sv : vkb_max_api();
    }
    return v;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkEnumerateInstanceVersion(uint32_t *pApiVersion)
{
    if (!vkb_connected()) {
        *pApiVersion = VK_API_VERSION_1_0;
        return VK_SUCCESS;
    }
    *pApiVersion = server_instance_version();
    return VK_SUCCESS;
}

static const char *const client_instance_exts[] = {
    "VK_KHR_surface", "VK_KHR_wayland_surface", "VK_KHR_xcb_surface", "VK_KHR_xlib_surface",
    "VK_EXT_headless_surface", "VK_KHR_get_surface_capabilities2", "VK_EXT_swapchain_colorspace",
    "VK_EXT_debug_utils",
};

static int bridged_ext(const char *name, int device)
{
    for (int i = 0; i < VKB_EXT_COUNT; i++)
        if (vkb_exts[i].device == device && !vkb_exts[i].client && !strcmp(vkb_exts[i].name, name)) return 1;
    return 0;
}

static VkResult fill_props(const VkExtensionProperties *list, uint32_t n, uint32_t *pCount, VkExtensionProperties *out)
{
    if (!out) {
        *pCount = n;
        return VK_SUCCESS;
    }
    uint32_t c = *pCount < n ? *pCount : n;
    memcpy(out, list, c * sizeof(*out));
    *pCount = c;
    return c < n ? VK_INCOMPLETE : VK_SUCCESS;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkEnumerateInstanceExtensionProperties(const char *pLayerName, uint32_t *pPropertyCount,
                                                                              VkExtensionProperties *pProperties)
{
    if (pLayerName) return VK_ERROR_LAYER_NOT_PRESENT;
    static VkExtensionProperties *list;
    static uint32_t count;
    static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
    pthread_mutex_lock(&lock);
    if (!list) {
        if (!vkb_connected()) {
            /* No server: offer nothing, and vkCreateInstance will say the driver is incompatible. */
            pthread_mutex_unlock(&lock);
            *pPropertyCount = 0;
            return VK_SUCCESS;
        }
        uint32_t n = 0;
        vkb_wire_vkEnumerateInstanceExtensionProperties(NULL, &n, NULL);
        VkExtensionProperties *srv = calloc(n + 1, sizeof(*srv));
        vkb_wire_vkEnumerateInstanceExtensionProperties(NULL, &n, srv);
        size_t nc = sizeof(client_instance_exts) / sizeof(client_instance_exts[0]);
        list = calloc(n + nc, sizeof(*list));
        for (uint32_t i = 0; i < n; i++)
            if (bridged_ext(srv[i].extensionName, 0)) list[count++] = srv[i];
        for (size_t i = 0; i < nc; i++) {
            snprintf(list[count].extensionName, sizeof(list[count].extensionName), "%s", client_instance_exts[i]);
            list[count].specVersion = 1;
            if (!strcmp(client_instance_exts[i], "VK_KHR_surface")) list[count].specVersion = 25;
            if (!strcmp(client_instance_exts[i], "VK_KHR_wayland_surface")) list[count].specVersion = 6;
            if (!strcmp(client_instance_exts[i], "VK_KHR_xcb_surface")) list[count].specVersion = 6;
            if (!strcmp(client_instance_exts[i], "VK_KHR_xlib_surface")) list[count].specVersion = 6;
            if (!strcmp(client_instance_exts[i], "VK_EXT_swapchain_colorspace")) list[count].specVersion = 4;
            if (!strcmp(client_instance_exts[i], "VK_EXT_debug_utils")) list[count].specVersion = 2;
            count++;
        }
        free(srv);
    }
    pthread_mutex_unlock(&lock);
    return fill_props(list, count, pPropertyCount, pProperties);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkEnumerateInstanceLayerProperties(uint32_t *pPropertyCount, VkLayerProperties *pProperties)
{
    (void)pProperties;
    *pPropertyCount = 0;
    return VK_SUCCESS;
}

static int is_client_instance_ext(const char *n)
{
    for (size_t i = 0; i < sizeof(client_instance_exts) / sizeof(client_instance_exts[0]); i++)
        if (!strcmp(client_instance_exts[i], n)) return 1;
    return 0;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkCreateInstance(const VkInstanceCreateInfo *pCreateInfo, const VkAllocationCallbacks *pAllocator,
                                                       VkInstance *pInstance)
{
    (void)pAllocator;
    if (!vkb_connected()) return VK_ERROR_INCOMPATIBLE_DRIVER;
    vkb_instance *inst = calloc(1, sizeof(*inst));
    if (!inst) return VK_ERROR_OUT_OF_HOST_MEMORY;
    inst->obj.loader.loaderMagic = ICD_LOADER_MAGIC;
    inst->obj.type = VK_OBJECT_TYPE_INSTANCE;
    pthread_mutex_init(&inst->lock, NULL);

    VkInstanceCreateInfo ci = *pCreateInfo;
    const char **exts = calloc(ci.enabledExtensionCount + 1, sizeof(char *));
    uint32_t n = 0;
    for (uint32_t i = 0; i < ci.enabledExtensionCount; i++) {
        const char *e = ci.ppEnabledExtensionNames[i];
        VKB_DBG("instance extension requested: %s", e);
        if (is_client_instance_ext(e)) {
            if (!strcmp(e, "VK_KHR_surface")) inst->ext_surface = 1;
            else if (!strcmp(e, "VK_KHR_wayland_surface")) inst->ext_wayland = 1;
            else if (!strcmp(e, "VK_KHR_xcb_surface")) inst->ext_xcb = 1;
            else if (!strcmp(e, "VK_KHR_xlib_surface")) inst->ext_xlib = 1;
            else if (!strcmp(e, "VK_EXT_headless_surface")) inst->ext_headless = 1;
            else if (!strcmp(e, "VK_KHR_get_surface_capabilities2")) inst->ext_surface_caps2 = 1;
            else if (!strcmp(e, "VK_EXT_debug_utils")) inst->ext_debug_utils = 1;
            continue;
        }
        exts[n++] = e;
    }
    ci.enabledExtensionCount = n;
    ci.ppEnabledExtensionNames = exts;
    ci.enabledLayerCount = 0;
    ci.ppEnabledLayerNames = NULL;
    VkApplicationInfo app;
    uint32_t api = server_instance_version();
    if (ci.pApplicationInfo) {
        app = *ci.pApplicationInfo;
        if (app.apiVersion > api) app.apiVersion = api;
        ci.pApplicationInfo = &app;
        inst->api_version = app.apiVersion ? app.apiVersion : VK_API_VERSION_1_0;
    } else {
        inst->api_version = VK_API_VERSION_1_0;
    }

    vkb_call c;
    vkb_call_begin(&c, VKB_CMD_vkCreateInstance, 0);
    vkb_enc_u8(&c.e, 1);
    vkb_enc_VkInstanceCreateInfo(&c.e, &ci);
    vkb_enc_u8(&c.e, 1);
    free(exts);
    if (!vkb_call_exec(&c)) {
        free(inst);
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    VkResult r = (VkResult)vkb_dec_u32(&c.d);
    inst->obj.remote = vkb_dec_u64(&c.d);
    inst->obj.table = vkb_dec_u32(&c.d);
    vkb_call_end(&c);
    if (r != VK_SUCCESS) {
        free(inst);
        return r;
    }
    VKB_INFO("instance created (Vulkan %u.%u)", VK_API_VERSION_MAJOR(inst->api_version), VK_API_VERSION_MINOR(inst->api_version));
    *pInstance = (VkInstance)inst;
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkDestroyInstance(VkInstance instance, const VkAllocationCallbacks *pAllocator)
{
    (void)pAllocator;
    if (!instance) return;
    vkb_instance *inst = vkb_inst(instance);
    vkb_call c;
    vkb_call_begin(&c, VKB_CMD_vkDestroyInstance, inst->obj.table);
    vkb_enc_u64(&c.e, inst->obj.remote);
    if (vkb_call_exec(&c)) vkb_call_end(&c);
    vkb_physdev *pd = inst->pds;
    while (pd) {
        vkb_physdev *n = pd->list_next;
        free(pd->exts);
        pthread_mutex_destroy(&pd->lock);
        free(pd);
        pd = n;
    }
    pthread_mutex_destroy(&inst->lock);
    free(inst);
}

/* ------------------------------------------------------------------ physical devices */

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkEnumeratePhysicalDevices(VkInstance instance, uint32_t *pCount, VkPhysicalDevice *pDevices)
{
    VkResult r = vkb_wire_vkEnumeratePhysicalDevices(instance, pCount, pDevices);
    if (pDevices && (r == VK_SUCCESS || r == VK_INCOMPLETE)) {
        for (uint32_t i = 0; i < *pCount; i++) vkb_physdev_ready(vkb_pd(pDevices[i]));
    }
    return r;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkEnumeratePhysicalDeviceGroups(VkInstance instance, uint32_t *pCount,
                                                                       VkPhysicalDeviceGroupProperties *pGroups)
{
    /* One group per device: what a single-GPU driver reports. */
    uint32_t n = 0;
    VkResult r = vkb_ep_vkEnumeratePhysicalDevices(instance, &n, NULL);
    if (r != VK_SUCCESS) return r;
    if (!pGroups) {
        *pCount = n;
        return VK_SUCCESS;
    }
    VkPhysicalDevice *pds = calloc(n ? n : 1, sizeof(*pds));
    r = vkb_ep_vkEnumeratePhysicalDevices(instance, &n, pds);
    uint32_t c = *pCount < n ? *pCount : n;
    for (uint32_t i = 0; i < c; i++) {
        pGroups[i].physicalDeviceCount = 1;
        memset(pGroups[i].physicalDevices, 0, sizeof(pGroups[i].physicalDevices));
        pGroups[i].physicalDevices[0] = pds[i];
        pGroups[i].subsetAllocation = VK_FALSE;
    }
    free(pds);
    *pCount = c;
    return c < n ? VK_INCOMPLETE : r;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkEnumerateDeviceExtensionProperties(VkPhysicalDevice physicalDevice, const char *pLayerName,
                                                                            uint32_t *pPropertyCount, VkExtensionProperties *pProperties)
{
    if (pLayerName) return VK_ERROR_LAYER_NOT_PRESENT;
    vkb_physdev *pd = vkb_pd(physicalDevice);
    if (!vkb_physdev_ready(pd)) {
        *pPropertyCount = 0;
        return VK_SUCCESS;
    }
    return fill_props(pd->exts, pd->next, pPropertyCount, pProperties);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkEnumerateDeviceLayerProperties(VkPhysicalDevice physicalDevice, uint32_t *pPropertyCount,
                                                                        VkLayerProperties *pProperties)
{
    (void)physicalDevice;
    (void)pProperties;
    *pPropertyCount = 0;
    return VK_SUCCESS;
}

/* ------------------------------------------------------------------ device */

static const char *const client_device_exts[] = {
    "VK_KHR_swapchain", "VK_KHR_incremental_present", "VK_KHR_swapchain_mutable_format",
};

int vkb_is_client_device_ext(const char *n)
{
    for (size_t i = 0; i < sizeof(client_device_exts) / sizeof(client_device_exts[0]); i++)
        if (!strcmp(client_device_exts[i], n)) return 1;
    return 0;
}

const char *const *vkb_client_device_exts(uint32_t *n)
{
    *n = sizeof(client_device_exts) / sizeof(client_device_exts[0]);
    return client_device_exts;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkCreateDevice(VkPhysicalDevice physicalDevice, const VkDeviceCreateInfo *pCreateInfo,
                                                     const VkAllocationCallbacks *pAllocator, VkDevice *pDevice)
{
    (void)pAllocator;
    vkb_physdev *pd = vkb_pd(physicalDevice);
    if (!vkb_physdev_ready(pd)) return VK_ERROR_INITIALIZATION_FAILED;
    vkb_device *dev = calloc(1, sizeof(*dev));
    if (!dev) return VK_ERROR_OUT_OF_HOST_MEMORY;
    dev->obj.loader.loaderMagic = ICD_LOADER_MAGIC;
    dev->obj.type = VK_OBJECT_TYPE_DEVICE;
    dev->pd = pd;
    dev->instance = pd->instance;
    pthread_mutex_init(&dev->lock, NULL);

    VkDeviceCreateInfo ci = *pCreateInfo;
    const char **server_exts = calloc(ci.enabledExtensionCount + 1, sizeof(char *));
    dev->enabled_exts = calloc(ci.enabledExtensionCount + 1, sizeof(char *));
    uint32_t n = 0;
    for (uint32_t i = 0; i < ci.enabledExtensionCount; i++) {
        const char *e = ci.ppEnabledExtensionNames[i];
        VKB_DBG("device extension requested: %s", e);
        dev->enabled_exts[dev->enabled_ext_count++] = strdup(e);
        if (!strcmp(e, "VK_KHR_swapchain")) dev->ext_swapchain = 1;
        if (vkb_is_client_device_ext(e)) continue;
        server_exts[n++] = e;
    }
    ci.enabledExtensionCount = n;
    ci.ppEnabledExtensionNames = server_exts;
    ci.enabledLayerCount = 0;
    ci.ppEnabledLayerNames = NULL;

    vkb_call c;
    vkb_call_begin(&c, VKB_CMD_vkCreateDevice, pd->obj.table);
    vkb_enc_u64(&c.e, pd->obj.remote);
    vkb_enc_u8(&c.e, 1);
    vkb_enc_VkDeviceCreateInfo(&c.e, &ci);
    vkb_enc_u8(&c.e, 1);
    free(server_exts);
    VkResult r;
    if (!vkb_call_exec(&c)) {
        r = VK_ERROR_INITIALIZATION_FAILED;
    } else {
        r = (VkResult)vkb_dec_u32(&c.d);
        dev->obj.remote = vkb_dec_u64(&c.d);
        dev->obj.table = vkb_dec_u32(&c.d);
        vkb_call_end(&c);
    }
    if (r != VK_SUCCESS) {
        for (uint32_t i = 0; i < dev->enabled_ext_count; i++) free((void *)dev->enabled_exts[i]);
        free(dev->enabled_exts);
        free(dev);
        return r;
    }
    vkb_map_init(&dev->memories);
    vkb_map_init(&dev->templates);
    vkb_map_init(&dev->pools);
    vkb_map_init(&dev->unshared);
    vkb_map_init(&dev->shared_images);
    vkb_memory_init_device(dev);
    VKB_INFO("device created on %s", pd->props.deviceName);
    *pDevice = (VkDevice)dev;
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkDestroyDevice(VkDevice device, const VkAllocationCallbacks *pAllocator)
{
    (void)pAllocator;
    if (!device) return;
    vkb_device *dev = vkb_dev(device);
    vkb_call c;
    vkb_call_begin(&c, VKB_CMD_vkDestroyDevice, dev->obj.table);
    vkb_enc_u64(&c.e, dev->obj.remote);
    if (vkb_call_exec(&c)) vkb_call_end(&c);
    vkb_queue *q = dev->queues;
    while (q) {
        vkb_queue *n = q->next;
        free(q);
        q = n;
    }
    /* Mapped memory the app never freed. */
    for (uint32_t i = 0; i < dev->memories.cap; i++) {
        if (dev->memories.keys[i] && dev->memories.keys[i] != ~(uint64_t)0) {
            vkb_memory *m = dev->memories.vals[i];
            extern void vkb_memory_unmap_free(vkb_memory * m);
            vkb_memory_unmap_free(m);
        }
    }
    for (uint32_t i = 0; i < dev->pools.cap; i++) {
        if (dev->pools.keys[i] && dev->pools.keys[i] != ~(uint64_t)0) {
            extern void vkb_cmdpool_free(vkb_cmdpool * p);
            vkb_cmdpool_free(dev->pools.vals[i]);
        }
    }
    for (uint32_t i = 0; i < dev->templates.cap; i++)
        if (dev->templates.keys[i] && dev->templates.keys[i] != ~(uint64_t)0) free(dev->templates.vals[i]);
    vkb_map_destroy(&dev->memories);
    vkb_map_destroy(&dev->templates);
    vkb_map_destroy(&dev->pools);
    vkb_map_destroy(&dev->unshared);
    vkb_map_destroy(&dev->shared_images);
    for (uint32_t i = 0; i < dev->enabled_ext_count; i++) free((void *)dev->enabled_exts[i]);
    free(dev->enabled_exts);
    pthread_mutex_destroy(&dev->lock);
    free(dev);
}

static void queue_set_info(VkQueue q, uint32_t family, uint32_t index)
{
    if (!q) return;
    vkb_queue *vq = (vkb_queue *)q;
    vq->family = family;
    vq->index = index;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetDeviceQueue(VkDevice device, uint32_t queueFamilyIndex, uint32_t queueIndex, VkQueue *pQueue)
{
    vkb_wire_vkGetDeviceQueue(device, queueFamilyIndex, queueIndex, pQueue);
    queue_set_info(*pQueue, queueFamilyIndex, queueIndex);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetDeviceQueue2(VkDevice device, const VkDeviceQueueInfo2 *pQueueInfo, VkQueue *pQueue)
{
    vkb_wire_vkGetDeviceQueue2(device, pQueueInfo, pQueue);
    queue_set_info(*pQueue, pQueueInfo->queueFamilyIndex, pQueueInfo->queueIndex);
}

/* ------------------------------------------------------------------ queues and sync */

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkQueueSubmit(VkQueue queue, uint32_t submitCount, const VkSubmitInfo *pSubmits, VkFence fence)
{
    return vkb_wire_vkQueueSubmit(queue, submitCount, pSubmits, fence);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkQueueSubmit2(VkQueue queue, uint32_t submitCount, const VkSubmitInfo2 *pSubmits, VkFence fence)
{
    return vkb_wire_vkQueueSubmit2(queue, submitCount, pSubmits, fence);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkQueueWaitIdle(VkQueue queue)
{
    return vkb_wire_vkQueueWaitIdle(queue);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkDeviceWaitIdle(VkDevice device)
{
    return vkb_wire_vkDeviceWaitIdle(device);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkWaitForFences(VkDevice device, uint32_t fenceCount, const VkFence *pFences, VkBool32 waitAll,
                                                      uint64_t timeout)
{
    return vkb_wire_vkWaitForFences(device, fenceCount, pFences, waitAll, timeout);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkGetFenceStatus(VkDevice device, VkFence fence)
{
    return vkb_wire_vkGetFenceStatus(device, fence);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkWaitSemaphores(VkDevice device, const VkSemaphoreWaitInfo *pWaitInfo, uint64_t timeout)
{
    return vkb_wire_vkWaitSemaphores(device, pWaitInfo, timeout);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkGetSemaphoreCounterValue(VkDevice device, VkSemaphore semaphore, uint64_t *pValue)
{
    return vkb_wire_vkGetSemaphoreCounterValue(device, semaphore, pValue);
}
