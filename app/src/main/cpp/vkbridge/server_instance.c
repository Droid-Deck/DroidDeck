/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Instances, devices and the startup self-test.
 */
#define _GNU_SOURCE
#include "vkb_server.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

vkb_pd_knowledge vkb_pds[VKB_MAX_PDS];
int vkb_npds;

static int has_ext(const VkExtensionProperties *e, uint32_t n, const char *name)
{
    for (uint32_t i = 0; i < n; i++)
        if (!strcmp(e[i].extensionName, name)) return 1;
    return 0;
}

const vkb_pd_knowledge *vkb_pd_lookup(const vkb_dispatch *dt, VkPhysicalDevice pd)
{
    VkPhysicalDeviceIDProperties id = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES};
    VkPhysicalDeviceProperties2 p2 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, &id};
    if (!dt->vkGetPhysicalDeviceProperties2) return NULL;
    dt->vkGetPhysicalDeviceProperties2(pd, &p2);
    for (int i = 0; i < vkb_npds; i++)
        if (vkb_pds[i].valid && !memcmp(vkb_pds[i].uuid, id.deviceUUID, VK_UUID_SIZE)) return &vkb_pds[i];
    return NULL;
}

/* ------------------------------------------------------------------ startup self-test */

static void log_device(const vkb_dispatch *dt, VkPhysicalDevice pd, const VkPhysicalDeviceProperties *p)
{
    VKB_INFO("GPU: %s (vendor 0x%04x device 0x%04x, Vulkan %u.%u.%u, driver 0x%08x)", p->deviceName,
             p->vendorID, p->deviceID, VK_API_VERSION_MAJOR(p->apiVersion), VK_API_VERSION_MINOR(p->apiVersion),
             VK_API_VERSION_PATCH(p->apiVersion), p->driverVersion);
    VkPhysicalDeviceFeatures f;
    dt->vkGetPhysicalDeviceFeatures(pd, &f);
#define F(x) #x, f.x
    VKB_INFO("features: %s=%u %s=%u %s=%u %s=%u %s=%u %s=%u %s=%u %s=%u", F(textureCompressionBC), F(shaderClipDistance),
             F(shaderCullDistance), F(geometryShader), F(tessellationShader), F(depthClamp), F(shaderFloat64), F(shaderInt64));
    VKB_INFO("features: %s=%u %s=%u %s=%u %s=%u %s=%u %s=%u %s=%u %s=%u", F(multiViewport), F(independentBlend), F(logicOp),
             F(fillModeNonSolid), F(dualSrcBlend), F(sampleRateShading), F(depthBiasClamp), F(wideLines));
    VKB_INFO("features: %s=%u %s=%u %s=%u %s=%u %s=%u %s=%u %s=%u %s=%u", F(drawIndirectFirstInstance), F(multiDrawIndirect),
             F(occlusionQueryPrecise), F(pipelineStatisticsQuery), F(vertexPipelineStoresAndAtomics),
             F(fragmentStoresAndAtomics), F(shaderStorageImageWriteWithoutFormat), F(imageCubeArray));
    VKB_INFO("features: %s=%u %s=%u %s=%u %s=%u %s=%u %s=%u", F(robustBufferAccess), F(fullDrawIndexUint32), F(samplerAnisotropy),
             F(depthBounds), F(largePoints), F(shaderStorageImageExtendedFormats));
#undef F
    uint32_t n = 0;
    dt->vkEnumerateDeviceExtensionProperties(pd, NULL, &n, NULL);
    VkExtensionProperties *e = calloc(n ? n : 1, sizeof(*e));
    dt->vkEnumerateDeviceExtensionProperties(pd, NULL, &n, e);
    static const char *const watch[] = {
        "VK_EXT_depth_clip_enable", "VK_EXT_vertex_attribute_divisor", "VK_KHR_vertex_attribute_divisor",
        "VK_EXT_external_memory_dma_buf", "VK_EXT_image_drm_format_modifier", "VK_KHR_external_memory_fd",
        "VK_EXT_external_memory_host", "VK_ANDROID_external_memory_android_hardware_buffer",
        "VK_KHR_external_semaphore_fd", "VK_KHR_external_fence_fd", "VK_EXT_queue_family_foreign",
        "VK_EXT_robustness2", "VK_EXT_transform_feedback", "VK_EXT_custom_border_color",
        "VK_EXT_mutable_descriptor_type", "VK_VALVE_mutable_descriptor_type", "VK_EXT_extended_dynamic_state3",
        "VK_EXT_graphics_pipeline_library", "VK_EXT_physical_device_drm", "VK_KHR_push_descriptor",
        "VK_EXT_descriptor_indexing", "VK_KHR_timeline_semaphore", "VK_EXT_memory_budget",
        "VK_KHR_maintenance5", "VK_EXT_line_rasterization", "VK_EXT_provoking_vertex",
    };
    char line[1024];
    size_t len = 0;
    line[0] = 0;
    for (size_t i = 0; i < sizeof(watch) / sizeof(watch[0]); i++) {
        int ok = has_ext(e, n, watch[i]);
        len += (size_t)snprintf(line + len, sizeof(line) - len, "%s%s%s", len ? " " : "", ok ? "+" : "-", watch[i] + 3);
        if (len > 700 || i + 1 == sizeof(watch) / sizeof(watch[0])) {
            VKB_INFO("extensions: %s", line);
            len = 0;
            line[0] = 0;
        }
    }
    VKB_INFO("%u device extensions in total", n);
    /* All of them, until the device runs show which ones the server's process sees (it is not an
     * app process, and saw fewer than the capability viewer on the same tablet). */
    len = 0;
    line[0] = 0;
    for (uint32_t i = 0; i < n; i++) {
        len += (size_t)snprintf(line + len, sizeof(line) - len, "%s%s:%u", len ? " " : "", e[i].extensionName + 3, e[i].specVersion);
        if (len > 700 || i + 1 == n) {
            VKB_INFO("  all: %s", line);
            len = 0;
            line[0] = 0;
        }
    }
    free(e);
    VkPhysicalDeviceMemoryProperties mp;
    dt->vkGetPhysicalDeviceMemoryProperties(pd, &mp);
    for (uint32_t i = 0; i < mp.memoryHeapCount; i++)
        VKB_INFO("memory heap %u: %llu MB flags 0x%x", i, (unsigned long long)(mp.memoryHeaps[i].size >> 20), mp.memoryHeaps[i].flags);
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
        VKB_INFO("memory type %u: heap %u flags 0x%x", i, mp.memoryTypes[i].heapIndex, mp.memoryTypes[i].propertyFlags);
}

void vkb_selftest_all(void)
{
    uint32_t api = VK_API_VERSION_1_0;
    if (vkb_global_dt.vkEnumerateInstanceVersion) vkb_global_dt.vkEnumerateInstanceVersion(&api);
    if (api > VK_API_VERSION_1_3) api = VK_API_VERSION_1_3;
    VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO, NULL, "vkbridge-selftest", 1, "vkbridge", 1, api};
    VkInstanceCreateInfo ci = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, NULL, 0, &app, 0, NULL, 0, NULL};
    VkInstance inst;
    VkResult r = vkb_global_dt.vkCreateInstance(&ci, NULL, &inst);
    if (r != VK_SUCCESS) {
        VKB_ERR("self-test: vkCreateInstance failed (%d); the driver is unusable", r);
        return;
    }
    vkb_dispatch dt;
    vkb_dispatch_load_instance(&dt, vkb_gipa, inst);
    uint32_t n = 0;
    dt.vkEnumeratePhysicalDevices(inst, &n, NULL);
    if (n > VKB_MAX_PDS) n = VKB_MAX_PDS;
    VkPhysicalDevice pds[VKB_MAX_PDS];
    dt.vkEnumeratePhysicalDevices(inst, &n, pds);
    VKB_INFO("self-test: loader Vulkan %u.%u, %u physical device(s)", VK_API_VERSION_MAJOR(api), VK_API_VERSION_MINOR(api), n);
    for (uint32_t i = 0; i < n; i++) {
        VkPhysicalDeviceIDProperties id = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES};
        VkPhysicalDeviceProperties2 p2 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, &id};
        dt.vkGetPhysicalDeviceProperties2(pds[i], &p2);
        log_device(&dt, pds[i], &p2.properties);
        vkb_pd_knowledge *k = &vkb_pds[vkb_npds++];
        memcpy(k->uuid, id.deviceUUID, VK_UUID_SIZE);
        snprintf(k->name, sizeof(k->name), "%s", p2.properties.deviceName);
        k->info.real_api_version = p2.properties.apiVersion;
        vkb_mem_selftest(k, inst, &dt, pds[i]);
        k->info.emu_available = vkb_emu_detect(&dt, pds[i], &k->info.missing);
        k->info.emu = k->info.emu_available;
        const char *emu_env = getenv("VKBRIDGE_EMULATE");
        if (emu_env && !strcmp(emu_env, "0")) k->info.emu = 0;
        k->valid = 1;
        VKB_INFO("%s: memory sharing = %s, missing 0x%x, emulating 0x%x", k->name, vkb_mem_strategy_name(k->info.strategy),
                 k->info.missing, k->info.emu);
    }
    dt.vkDestroyInstance(inst, NULL);
}

/* ------------------------------------------------------------------ instance */

void vkb_sv_vkCreateInstance(vkb_srv_call *c)
{
    VkInstanceCreateInfo *ci = NULL;
    if (vkb_dec_u8(&c->d)) {
        ci = vkb_dec_alloc(&c->d, sizeof(*ci));
        if (ci) vkb_dec_VkInstanceCreateInfo(&c->d, ci);
    }
    uint8_t want_out = vkb_dec_u8(&c->d);
    if (c->d.err || !ci || !want_out) {
        vkb_srv_bad_message(c);
        return;
    }
    VkInstance inst = VK_NULL_HANDLE;
    VkResult r = vkb_global_dt.vkCreateInstance(ci, NULL, &inst);
    uint32_t id = 0;
    if (r == VK_SUCCESS) {
        vkb_srv_table *t = calloc(1, sizeof(*t));
        pthread_mutex_init(&t->lock, NULL);
        t->instance = inst;
        t->proc = c->conn->proc;
        t->api_version = ci->pApplicationInfo ? ci->pApplicationInfo->apiVersion : VK_API_VERSION_1_0;
        vkb_dispatch_load_instance(&t->real, vkb_gipa, inst);
        t->dt = t->real;
        id = vkb_table_register(t);
        if (!id) {
            t->real.vkDestroyInstance(inst, NULL);
            free(t);
            r = VK_ERROR_OUT_OF_HOST_MEMORY;
            inst = VK_NULL_HANDLE;
        } else {
            VKB_INFO("instance %u created (app \"%s\", engine \"%s\", Vulkan %u.%u)", id,
                     ci->pApplicationInfo && ci->pApplicationInfo->pApplicationName ? ci->pApplicationInfo->pApplicationName : "",
                     ci->pApplicationInfo && ci->pApplicationInfo->pEngineName ? ci->pApplicationInfo->pEngineName : "",
                     VK_API_VERSION_MAJOR(t->api_version), VK_API_VERSION_MINOR(t->api_version));
        }
    } else {
        VKB_ERR("vkCreateInstance failed: %d", r);
    }
    vkb_enc_u32(&c->r, (uint32_t)r);
    vkb_enc_u64(&c->r, (uint64_t)(uintptr_t)inst);
    vkb_enc_u32(&c->r, id);
}

void vkb_sv_vkDestroyInstance(vkb_srv_call *c)
{
    VkInstance inst = (VkInstance)(uintptr_t)vkb_dec_u64(&c->d);
    vkb_srv_table *t = c->table;
    if (!t || t->is_device || t->instance != inst) {
        vkb_srv_bad_message(c);
        return;
    }
    vkb_table_unregister(t);
    t->real.vkDestroyInstance(inst, NULL);
    pthread_mutex_destroy(&t->lock);
    free(t);
    c->table = NULL;
}

void vkb_sv_vkbQueryServer(vkb_srv_call *c)
{
    VkPhysicalDevice pd = (VkPhysicalDevice)(uintptr_t)vkb_dec_u64(&c->d);
    if (c->d.err || !c->table) {
        vkb_srv_bad_message(c);
        return;
    }
    const vkb_pd_knowledge *k = vkb_pd_lookup(c->dt, pd);
    vkb_server_pd_info info;
    memset(&info, 0, sizeof(info));
    if (k) info = k->info;
    else VKB_WARN("query for a physical device the self-test did not see");
    vkb_enc_raw(&c->r, &info, sizeof(info));
}

/* ------------------------------------------------------------------ device */

static int list_has(const char *const *l, uint32_t n, const char *s)
{
    for (uint32_t i = 0; i < n; i++)
        if (l[i] && !strcmp(l[i], s)) return 1;
    return 0;
}

void vkb_sv_vkCreateDevice(vkb_srv_call *c)
{
    VkPhysicalDevice pd = (VkPhysicalDevice)(uintptr_t)vkb_dec_u64(&c->d);
    VkDeviceCreateInfo *ci = NULL;
    if (vkb_dec_u8(&c->d)) {
        ci = vkb_dec_alloc(&c->d, sizeof(*ci));
        if (ci) vkb_dec_VkDeviceCreateInfo(&c->d, ci);
    }
    uint8_t want_out = vkb_dec_u8(&c->d);
    vkb_srv_table *inst = c->table;
    if (c->d.err || !ci || !want_out || !inst || inst->is_device) {
        vkb_srv_bad_message(c);
        return;
    }
    const vkb_pd_knowledge *k = vkb_pd_lookup(&inst->real, pd);
    uint32_t strategy = k ? k->info.strategy : VKB_MEM_NONE;
    uint32_t emu = k ? k->info.emu : 0;

    /* Extensions the server itself needs (memory sharing), when the driver has them. */
    uint32_t avail_n = 0;
    inst->real.vkEnumerateDeviceExtensionProperties(pd, NULL, &avail_n, NULL);
    VkExtensionProperties *avail = vkb_dec_alloc(&c->d, (avail_n ? avail_n : 1) * sizeof(*avail));
    inst->real.vkEnumerateDeviceExtensionProperties(pd, NULL, &avail_n, avail);
    uint32_t extra_n = 0;
    const char *const *extra = vkb_mem_required_extensions(strategy, &extra_n);
    const char **exts = vkb_dec_alloc(&c->d, (ci->enabledExtensionCount + extra_n + 10) * sizeof(char *));
    uint32_t n = 0;
    for (uint32_t i = 0; i < ci->enabledExtensionCount; i++) {
        const char *e = ci->ppEnabledExtensionNames[i];
        if (!has_ext(avail, avail_n, e)) {
            VKB_DBG("device extension %s is emulated or client-side; not passed to the driver", e);
            continue;
        }
        exts[n++] = e;
    }
    for (uint32_t i = 0; i < extra_n; i++)
        if (has_ext(avail, avail_n, extra[i]) && !list_has(exts, n, extra[i])) exts[n++] = extra[i];
    /* What the client's presentation code uses on every device (dma-buf swapchains, sync fds). */
    static const char *const wsi_exts[] = {
        "VK_KHR_external_memory_fd", "VK_EXT_external_memory_dma_buf", "VK_KHR_image_format_list",
        "VK_EXT_image_drm_format_modifier", "VK_KHR_external_semaphore_fd", "VK_KHR_external_fence_fd",
    };
    for (size_t i = 0; i < sizeof(wsi_exts) / sizeof(wsi_exts[0]); i++)
        if (has_ext(avail, avail_n, wsi_exts[i]) && !list_has(exts, n, wsi_exts[i])) exts[n++] = wsi_exts[i];
    /* The BC decoder binds its buffer and image with push descriptors. */
    if ((emu & VKB_EMU_BCN) && has_ext(avail, avail_n, "VK_KHR_push_descriptor") && !list_has(exts, n, "VK_KHR_push_descriptor"))
        exts[n++] = "VK_KHR_push_descriptor";
    ci->enabledExtensionCount = n;
    ci->ppEnabledExtensionNames = exts;
    ci->enabledLayerCount = 0;
    ci->ppEnabledLayerNames = NULL;

    vkb_emu_device_create_info(inst, pd, emu, ci, c->d.arena);

    VkDevice dev = VK_NULL_HANDLE;
    VkResult r = inst->real.vkCreateDevice(pd, ci, NULL, &dev);
    uint32_t id = 0;
    if (r == VK_SUCCESS) {
        vkb_srv_table *t = calloc(1, sizeof(*t));
        pthread_mutex_init(&t->lock, NULL);
        t->is_device = 1;
        t->proc = c->conn->proc;
        t->instance = inst->instance;
        t->device = dev;
        t->physical = pd;
        t->parent = inst;
        t->pd = k;
        t->api_version = inst->api_version;
        t->real = inst->real;
        PFN_vkGetDeviceProcAddr gdpa = (PFN_vkGetDeviceProcAddr)vkb_gipa(inst->instance, "vkGetDeviceProcAddr");
        vkb_dispatch_load_device(&t->real, gdpa, dev);
        t->dt = t->real;
        id = vkb_table_register(t);
        if (!id) {
            t->real.vkDestroyDevice(dev, NULL);
            free(t);
            dev = VK_NULL_HANDLE;
            r = VK_ERROR_OUT_OF_HOST_MEMORY;
        } else {
            vkb_emu_device_init(t, emu);
            vkb_pcache_device_init(t);
            vkb_dsmap_device_init(t);
            VKB_INFO("device %u created on %s (%u extensions, memory sharing %s)", id, k ? k->name : "?", n,
                     vkb_mem_strategy_name(strategy));
            for (uint32_t i = 0; i < n; i++) VKB_DBG("  device extension %s", exts[i]);
        }
    } else {
        VKB_ERR("vkCreateDevice failed: %d (%u extensions)", r, n);
        for (uint32_t i = 0; i < n; i++) VKB_ERR("  requested %s", exts[i]);
    }
    vkb_enc_u32(&c->r, (uint32_t)r);
    vkb_enc_u64(&c->r, (uint64_t)(uintptr_t)dev);
    vkb_enc_u32(&c->r, id);
}

void vkb_sv_vkDestroyDevice(vkb_srv_call *c)
{
    VkDevice dev = (VkDevice)(uintptr_t)vkb_dec_u64(&c->d);
    vkb_srv_table *t = c->table;
    if (!t || !t->is_device || t->device != dev) {
        vkb_srv_bad_message(c);
        return;
    }
    vkb_table_unregister(t);
    vkb_dsmap_device_destroy(t);
    vkb_pcache_device_destroy(t);
    vkb_emu_device_destroy(t);
    vkb_mem_free_all(t);
    t->real.vkDestroyDevice(dev, NULL);
    VKB_INFO("device %u destroyed", t->id);
    pthread_mutex_destroy(&t->lock);
    free(t);
    c->table = NULL;
}
