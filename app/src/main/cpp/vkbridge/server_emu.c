/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Feature emulation: entry point. What the GPU lacks and the bridge can make up for is decided
 * here per physical device; the client reports those features as present, the server strips
 * them from vkCreateDevice and installs wrappers in the device's dispatch table.
 */
#define _GNU_SOURCE
#include "vkb_server.h"
#include "server_emu.h"

#include <stdlib.h>
#include <string.h>

static int token(const char *list, const char *t)
{
    size_t n = strlen(t);
    for (const char *p = list; p && (p = strstr(p, t)); p += n)
        if ((p == list || p[-1] == ',') && (p[n] == ',' || p[n] == 0)) return 1;
    return 0;
}

static int env_off(const char *name)
{
    const char *v = getenv(name);
    return v && !strcmp(v, "0");
}

uint32_t vkb_emu_detect(const vkb_dispatch *idt, VkPhysicalDevice pd, uint32_t *missing)
{
    VkPhysicalDeviceFeatures f;
    idt->vkGetPhysicalDeviceFeatures(pd, &f);
    uint32_t n = 0;
    idt->vkEnumerateDeviceExtensionProperties(pd, NULL, &n, NULL);
    VkExtensionProperties *e = calloc(n ? n : 1, sizeof(*e));
    idt->vkEnumerateDeviceExtensionProperties(pd, NULL, &n, e);
    int divisor = 0, depth_clip = 0, maint5 = 0;
    for (uint32_t i = 0; i < n; i++) {
        if (!strcmp(e[i].extensionName, "VK_EXT_vertex_attribute_divisor") ||
            !strcmp(e[i].extensionName, "VK_KHR_vertex_attribute_divisor"))
            divisor = 1;
        if (!strcmp(e[i].extensionName, "VK_EXT_depth_clip_enable")) depth_clip = 1;
        if (!strcmp(e[i].extensionName, "VK_KHR_maintenance5")) maint5 = 1;
    }
    free(e);
    /* VKBRIDGE_FAKE_MISSING=bc,divisor,clip,cull,depthclip pretends the GPU lacks them (host tests). */
    const char *fake = getenv("VKBRIDGE_FAKE_MISSING");
    if (fake) {
        if (token(fake, "bc")) f.textureCompressionBC = VK_FALSE;
        if (token(fake, "divisor")) divisor = 0;
        if (token(fake, "clip")) f.shaderClipDistance = VK_FALSE;
        if (token(fake, "cull")) f.shaderCullDistance = VK_FALSE;
        if (token(fake, "depthclip")) depth_clip = 0;
        if (token(fake, "maint5")) maint5 = 0;
    }
    uint32_t emu = 0;
    VkPhysicalDeviceProperties props;
    idt->vkGetPhysicalDeviceProperties(pd, &props);
    /* Vulkan 1.4 has maintenance5 in core. */
    if (!maint5 && (props.apiVersion < VK_API_VERSION_1_4 || (fake && token(fake, "maint5"))) && !env_off("VKBRIDGE_EMU_MAINT5"))
        emu |= VKB_EMU_MAINT5;
    if (!f.textureCompressionBC && vkb_emu_bcn_supported(idt, pd) && !env_off("VKBRIDGE_EMU_BCN")) emu |= VKB_EMU_BCN;
    if (!divisor && !env_off("VKBRIDGE_EMU_DIVISOR")) emu |= VKB_EMU_DIVISOR;
    if (!f.shaderClipDistance && !env_off("VKBRIDGE_EMU_CLIP")) emu |= VKB_EMU_CLIP_DISTANCE;
    if (!f.shaderCullDistance && !env_off("VKBRIDGE_EMU_CULL")) emu |= VKB_EMU_CULL_DISTANCE;
    if (!depth_clip && f.depthClamp && !env_off("VKBRIDGE_EMU_DEPTHCLIP")) emu |= VKB_EMU_DEPTH_CLIP;
    /* PointSize in geometry/tessellation stages: stripped when the feature is missing. */
    if (!f.shaderTessellationAndGeometryPointSize && (f.geometryShader || f.tessellationShader)) emu |= VKB_EMU_POINT_SIZE;
    uint32_t miss = 0;
    if (!f.textureCompressionBC) miss |= VKB_EMU_BCN;
    if (!divisor) miss |= VKB_EMU_DIVISOR;
    if (!f.shaderClipDistance) miss |= VKB_EMU_CLIP_DISTANCE;
    if (!f.shaderCullDistance) miss |= VKB_EMU_CULL_DISTANCE;
    if (!depth_clip) miss |= VKB_EMU_DEPTH_CLIP;
    if (emu & VKB_EMU_MAINT5) miss |= VKB_EMU_MAINT5;
    *missing = miss;
    return emu & VKB_EMU_IMPLEMENTED;
}

/* Remove a structure from a pNext chain (the chain lives in the request's arena). */
static void chain_remove(VkDeviceCreateInfo *ci, VkStructureType t)
{
    VkBaseOutStructure *prev = (VkBaseOutStructure *)ci;
    while (prev->pNext) {
        if (prev->pNext->sType == t) prev->pNext = prev->pNext->pNext;
        else prev = prev->pNext;
    }
}

static VkPhysicalDeviceFeatures *find_features(VkDeviceCreateInfo *ci)
{
    if (ci->pEnabledFeatures) return (VkPhysicalDeviceFeatures *)ci->pEnabledFeatures;
    for (VkBaseOutStructure *b = (VkBaseOutStructure *)ci->pNext; b; b = b->pNext)
        if (b->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2) return &((VkPhysicalDeviceFeatures2 *)b)->features;
    return NULL;
}

void vkb_emu_device_create_info(vkb_srv_table *inst, VkPhysicalDevice pd, uint32_t emu, VkDeviceCreateInfo *ci, vkb_arena *a)
{
    VkPhysicalDeviceFeatures real;
    inst->real.vkGetPhysicalDeviceFeatures(pd, &real);
    VkPhysicalDeviceFeatures *f = find_features(ci);
    if (f) {
        /* pEnabledFeatures came from the request: it is ours to change. Anything the client asked
         * for that the GPU does not have is either emulated or would fail the call. */
        VkBool32 *want = (VkBool32 *)f;
        const VkBool32 *have = (const VkBool32 *)&real;
        for (size_t i = 0; i < sizeof(*f) / sizeof(VkBool32); i++) {
            if (want[i] && !have[i]) want[i] = VK_FALSE;
        }
    }
    if (emu & VKB_EMU_DIVISOR) chain_remove(ci, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VERTEX_ATTRIBUTE_DIVISOR_FEATURES_EXT);
    if (emu & VKB_EMU_DEPTH_CLIP) chain_remove(ci, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DEPTH_CLIP_ENABLE_FEATURES_EXT);
    if (emu & VKB_EMU_MAINT5) chain_remove(ci, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_5_FEATURES);
    (void)a;
}

void vkb_emu_device_init(vkb_srv_table *dev, uint32_t emu)
{
    if (!emu) return;
    vkb_emu_device *e = calloc(1, sizeof(*e));
    e->flags = emu;
    e->dev = dev;
    pthread_mutex_init(&e->lock, NULL);
    dev->emu = e;
    vkb_emu_install(dev);
}

void vkb_emu_device_destroy(vkb_srv_table *dev)
{
    if (!dev->emu) return;
    vkb_emu_uninstall(dev);
    pthread_mutex_destroy(&dev->emu->lock);
    free(dev->emu);
    dev->emu = NULL;
}
