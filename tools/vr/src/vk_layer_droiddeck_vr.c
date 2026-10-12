#define _GNU_SOURCE
/*
 * VK_LAYER_DROIDDECK_vr: lets SteamVR's vrcompositor create its Vulkan instance on Turnip/KGSL.
 *
 * vrcompositor always asks for VK_KHR_display and the direct-mode extensions around it (it opens a
 * direct mode window even when a driver-direct-mode driver - tools/vr/src/driver_droiddeck.cpp -
 * will receive the frames). Turnip's KGSL backend has no displays and refuses to enumerate any GPU
 * on an instance with VK_KHR_display enabled. This layer takes those extensions out of the instance
 * it creates below itself and answers the display queries on its own: there are no displays.
 *
 * Shared semaphores: SteamVR shares a timeline semaphore with every image it shares between its
 * processes, exported as an opaque fd. Turnip/KGSL cannot export one. Such a semaphore is created
 * unshared here instead, its "fd" is a placeholder (a memfd named droiddeck-detached-sem), and a
 * process importing a placeholder keeps a detached semaphore whose GPU waits are dropped and whose
 * host waits return at once. Each process still orders its own work; what is lost is only the GPU
 * ordering between processes, which SteamVR's own IPC (one frame at a time) mostly covers.
 *
 * Only vrcompositor needs it: tools/vr/steamvr-cardboard.sh enables it for SteamVR alone
 * (VK_INSTANCE_LAYERS + VK_LAYER_PATH), and it changes nothing for an instance that does not ask
 * for a display extension.
 */
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>

#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#define LAYER_NAME "VK_LAYER_DROIDDECK_vr"
#define EXPORT __attribute__((visibility("default")))

static void say(const char *fmt, const char *arg) {
    if (getenv("DROIDDECK_VR_LAYER_QUIET")) return;
    fprintf(stderr, "[droiddeck-vr-layer] ");
    fprintf(stderr, fmt, arg);
    fputc('\n', stderr);
}

/* ------------------------------------------------------------------ dispatch */

struct inst {
    VkInstance handle;
    PFN_vkGetInstanceProcAddr gipa;
    PFN_vkDestroyInstance destroy;
    int stripped; /* the app asked for display extensions we took out */
};
struct dev {
    VkDevice handle;
    PFN_vkGetDeviceProcAddr gdpa;
    PFN_vkDestroyDevice destroy;
};

#define MAX_OBJS 16
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static struct inst g_inst[MAX_OBJS];
static struct dev g_dev[MAX_OBJS];

/* The dispatch key: the loader's dispatch table pointer at the start of every dispatchable object. */
static void *key(const void *obj) { return *(void **)obj; }

static struct inst *inst_for(const void *obj) {
    struct inst *r = NULL;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_OBJS; i++)
        if (g_inst[i].handle && key(g_inst[i].handle) == key(obj)) { r = &g_inst[i]; break; }
    pthread_mutex_unlock(&g_lock);
    return r;
}
static struct dev *dev_for(const void *obj) {
    struct dev *r = NULL;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_OBJS; i++)
        if (g_dev[i].handle && key(g_dev[i].handle) == key(obj)) { r = &g_dev[i]; break; }
    pthread_mutex_unlock(&g_lock);
    return r;
}

#define HOOK_AS(str, fn) if (!strcmp(name, str)) return (PFN_vkVoidFunction)fn;

/* Extensions this layer takes off the instance and stands in for. */
static const char *const DISPLAY_EXTS[] = {
    "VK_KHR_display", "VK_EXT_direct_mode_display", "VK_EXT_display_surface_counter",
    "VK_KHR_get_display_properties2", "VK_EXT_acquire_xlib_display", "VK_EXT_acquire_drm_display",
};
static int is_display_ext(const char *name) {
    for (size_t i = 0; i < sizeof(DISPLAY_EXTS) / sizeof(*DISPLAY_EXTS); i++)
        if (!strcmp(name, DISPLAY_EXTS[i])) return 1;
    return 0;
}

/* ------------------------------------------------------------------ instance */

static VkResult VKAPI_CALL CreateInstance(const VkInstanceCreateInfo *ci, const VkAllocationCallbacks *alloc,
                                          VkInstance *out) {
    VkLayerInstanceCreateInfo *chain = (VkLayerInstanceCreateInfo *)ci->pNext;
    while (chain && !(chain->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO && chain->function == VK_LAYER_LINK_INFO))
        chain = (VkLayerInstanceCreateInfo *)chain->pNext;
    if (!chain) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr next_gipa = chain->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    chain->u.pLayerInfo = chain->u.pLayerInfo->pNext;
    PFN_vkCreateInstance next_create = (PFN_vkCreateInstance)next_gipa(VK_NULL_HANDLE, "vkCreateInstance");
    if (!next_create) return VK_ERROR_INITIALIZATION_FAILED;

    VkInstanceCreateInfo mine = *ci;
    const char **names = calloc(ci->enabledExtensionCount + 1, sizeof(*names));
    uint32_t n = 0;
    int stripped = 0;
    for (uint32_t i = 0; i < ci->enabledExtensionCount; i++) {
        if (is_display_ext(ci->ppEnabledExtensionNames[i])) { say("taking %s off the instance", ci->ppEnabledExtensionNames[i]); stripped = 1; continue; }
        names[n++] = ci->ppEnabledExtensionNames[i];
    }
    mine.enabledExtensionCount = n;
    mine.ppEnabledExtensionNames = names;
    VkResult r = next_create(&mine, alloc, out);
    free(names);
    if (r != VK_SUCCESS) return r;

    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_OBJS; i++) {
        if (!g_inst[i].handle) {
            g_inst[i] = (struct inst){*out, next_gipa,
                                      (PFN_vkDestroyInstance)next_gipa(*out, "vkDestroyInstance"), stripped};
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);
    return VK_SUCCESS;
}

static void VKAPI_CALL DestroyInstance(VkInstance instance, const VkAllocationCallbacks *alloc) {
    struct inst *in = inst_for(instance);
    if (!in) return;
    PFN_vkDestroyInstance d = in->destroy;
    pthread_mutex_lock(&g_lock);
    memset(in, 0, sizeof(*in));
    pthread_mutex_unlock(&g_lock);
    if (d) d(instance, alloc);
}

/* ------------------------------------------------------------------ device */

static VkResult VKAPI_CALL CreateDevice(VkPhysicalDevice pd, const VkDeviceCreateInfo *ci,
                                        const VkAllocationCallbacks *alloc, VkDevice *out) {
    VkLayerDeviceCreateInfo *chain = (VkLayerDeviceCreateInfo *)ci->pNext;
    while (chain && !(chain->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO && chain->function == VK_LAYER_LINK_INFO))
        chain = (VkLayerDeviceCreateInfo *)chain->pNext;
    if (!chain) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr next_gipa = chain->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr next_gdpa = chain->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    chain->u.pLayerInfo = chain->u.pLayerInfo->pNext;
    struct inst *in = inst_for(pd);
    PFN_vkCreateDevice next_create = (PFN_vkCreateDevice)next_gipa(in ? in->handle : VK_NULL_HANDLE, "vkCreateDevice");
    if (!next_create) return VK_ERROR_INITIALIZATION_FAILED;

    /* VK_EXT_display_control (vblank events on a display) goes too: there is no display. */
    VkDeviceCreateInfo mine = *ci;
    const char **names = calloc(ci->enabledExtensionCount + 1, sizeof(*names));
    uint32_t n = 0;
    for (uint32_t i = 0; i < ci->enabledExtensionCount; i++) {
        if (!strcmp(ci->ppEnabledExtensionNames[i], "VK_EXT_display_control")) { say("taking %s off the device", ci->ppEnabledExtensionNames[i]); continue; }
        names[n++] = ci->ppEnabledExtensionNames[i];
    }
    mine.enabledExtensionCount = n;
    mine.ppEnabledExtensionNames = names;
    VkResult r = next_create(pd, &mine, alloc, out);
    free(names);
    if (r != VK_SUCCESS) return r;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_OBJS; i++) {
        if (!g_dev[i].handle) {
            g_dev[i] = (struct dev){*out, next_gdpa, (PFN_vkDestroyDevice)next_gdpa(*out, "vkDestroyDevice")};
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);
    return VK_SUCCESS;
}

static void VKAPI_CALL DestroyDevice(VkDevice device, const VkAllocationCallbacks *alloc) {
    struct dev *d = dev_for(device);
    if (!d) return;
    PFN_vkDestroyDevice f = d->destroy;
    pthread_mutex_lock(&g_lock);
    memset(d, 0, sizeof(*d));
    pthread_mutex_unlock(&g_lock);
    if (f) f(device, alloc);
}

/* ------------------------------------------------------------------ the display stand-ins: no displays */

static VkResult VKAPI_CALL GetPhysicalDeviceDisplayPropertiesKHR(VkPhysicalDevice pd, uint32_t *count, VkDisplayPropertiesKHR *p) {
    *count = 0; return VK_SUCCESS;
}
static VkResult VKAPI_CALL GetPhysicalDeviceDisplayProperties2KHR(VkPhysicalDevice pd, uint32_t *count, VkDisplayProperties2KHR *p) {
    *count = 0; return VK_SUCCESS;
}
static VkResult VKAPI_CALL GetPhysicalDeviceDisplayPlanePropertiesKHR(VkPhysicalDevice pd, uint32_t *count, VkDisplayPlanePropertiesKHR *p) {
    *count = 0; return VK_SUCCESS;
}
static VkResult VKAPI_CALL GetPhysicalDeviceDisplayPlaneProperties2KHR(VkPhysicalDevice pd, uint32_t *count, VkDisplayPlaneProperties2KHR *p) {
    *count = 0; return VK_SUCCESS;
}
static VkResult VKAPI_CALL GetDisplayPlaneSupportedDisplaysKHR(VkPhysicalDevice pd, uint32_t plane, uint32_t *count, VkDisplayKHR *d) {
    *count = 0; return VK_SUCCESS;
}
static VkResult VKAPI_CALL GetDisplayModePropertiesKHR(VkPhysicalDevice pd, VkDisplayKHR d, uint32_t *count, VkDisplayModePropertiesKHR *p) {
    *count = 0; return VK_SUCCESS;
}
static VkResult VKAPI_CALL GetDisplayModeProperties2KHR(VkPhysicalDevice pd, VkDisplayKHR d, uint32_t *count, VkDisplayModeProperties2KHR *p) {
    *count = 0; return VK_SUCCESS;
}
static VkResult VKAPI_CALL CreateDisplayModeKHR(VkPhysicalDevice pd, VkDisplayKHR d, const VkDisplayModeCreateInfoKHR *ci,
                                                const VkAllocationCallbacks *a, VkDisplayModeKHR *m) {
    return VK_ERROR_INITIALIZATION_FAILED;
}
static VkResult VKAPI_CALL GetDisplayPlaneCapabilitiesKHR(VkPhysicalDevice pd, VkDisplayModeKHR m, uint32_t plane, VkDisplayPlaneCapabilitiesKHR *c) {
    return VK_ERROR_INITIALIZATION_FAILED;
}
static VkResult VKAPI_CALL CreateDisplayPlaneSurfaceKHR(VkInstance i, const VkDisplaySurfaceCreateInfoKHR *ci,
                                                        const VkAllocationCallbacks *a, VkSurfaceKHR *s) {
    say("%s: no display to put a surface on", "vkCreateDisplayPlaneSurfaceKHR");
    return VK_ERROR_INITIALIZATION_FAILED;
}
static VkResult VKAPI_CALL ReleaseDisplayEXT(VkPhysicalDevice pd, VkDisplayKHR d) { return VK_SUCCESS; }
static VkResult VKAPI_CALL AcquireDrmDisplayEXT(VkPhysicalDevice pd, int32_t fd, VkDisplayKHR d) { return VK_ERROR_INITIALIZATION_FAILED; }
static VkResult VKAPI_CALL GetDrmDisplayEXT(VkPhysicalDevice pd, int32_t fd, uint32_t conn, VkDisplayKHR *d) {
    *d = VK_NULL_HANDLE; return VK_ERROR_INITIALIZATION_FAILED;
}
static VkResult VKAPI_CALL AcquireXlibDisplayEXT(VkPhysicalDevice pd, void *dpy, VkDisplayKHR d) { return VK_ERROR_INITIALIZATION_FAILED; }
static VkResult VKAPI_CALL GetRandROutputDisplayEXT(VkPhysicalDevice pd, void *dpy, unsigned long out, VkDisplayKHR *d) {
    *d = VK_NULL_HANDLE; return VK_SUCCESS;
}

/* ------------------------------------------------------------------ detached shared semaphores */

#define MAX_SEMS 256
static VkSemaphore g_unshared[MAX_SEMS]; /* created here without export: their fds are placeholders */
static VkSemaphore g_detached[MAX_SEMS]; /* imported from a placeholder: nothing will ever signal them */

static int set_has(VkSemaphore *set, VkSemaphore s) {
    int r = 0;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_SEMS && !r; i++) r = set[i] == s;
    pthread_mutex_unlock(&g_lock);
    return r;
}
static void set_add(VkSemaphore *set, VkSemaphore s) {
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_SEMS; i++) if (!set[i]) { set[i] = s; break; }
    pthread_mutex_unlock(&g_lock);
}
static void set_del(VkSemaphore *set, VkSemaphore s) {
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_SEMS; i++) if (set[i] == s) set[i] = VK_NULL_HANDLE;
    pthread_mutex_unlock(&g_lock);
}

#define PLACEHOLDER "droiddeck-detached-sem"
static int is_placeholder(int fd) {
    char path[64], target[128];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    ssize_t n = readlink(path, target, sizeof(target) - 1);
    if (n <= 0) return 0;
    target[n] = 0;
    return strstr(target, PLACEHOLDER) != NULL;
}

#define NEXT(dev, fn) ((PFN_vk##fn)dev_for(dev)->gdpa(dev, "vk" #fn))

static VkResult VKAPI_CALL CreateSemaphore(VkDevice d, const VkSemaphoreCreateInfo *ci, const VkAllocationCallbacks *a, VkSemaphore *out) {
    PFN_vkCreateSemaphore next = NEXT(d, CreateSemaphore);
    VkResult r = next(d, ci, a, out);
    const VkBaseInStructure *export_info = NULL;
    for (const VkBaseInStructure *p = ci->pNext; p; p = p->pNext)
        if (p->sType == VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO) export_info = p;
    if (r == VK_SUCCESS || !export_info) return r;
    /* Rebuild the chain without the export request. */
    VkSemaphoreTypeCreateInfo type = {0};
    VkSemaphoreCreateInfo mine = *ci;
    mine.pNext = NULL;
    for (const VkBaseInStructure *p = ci->pNext; p; p = p->pNext) {
        if (p->sType == VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO) {
            type = *(const VkSemaphoreTypeCreateInfo *)p;
            type.pNext = NULL;
            mine.pNext = &type;
        }
    }
    r = next(d, &mine, a, out);
    if (r == VK_SUCCESS) {
        static int said;
        if (!said++) say("%s: created unshared (the driver cannot export it)", "exportable semaphore");
        set_add(g_unshared, *out);
    }
    return r;
}

static void VKAPI_CALL DestroySemaphore(VkDevice d, VkSemaphore s, const VkAllocationCallbacks *a) {
    set_del(g_unshared, s);
    set_del(g_detached, s);
    NEXT(d, DestroySemaphore)(d, s, a);
}

static VkResult VKAPI_CALL GetSemaphoreFdKHR(VkDevice d, const VkSemaphoreGetFdInfoKHR *info, int *fd) {
    if (set_has(g_unshared, info->semaphore)) {
        *fd = memfd_create(PLACEHOLDER, MFD_CLOEXEC);
        return *fd >= 0 ? VK_SUCCESS : VK_ERROR_TOO_MANY_OBJECTS;
    }
    return NEXT(d, GetSemaphoreFdKHR)(d, info, fd);
}

static VkResult VKAPI_CALL ImportSemaphoreFdKHR(VkDevice d, const VkImportSemaphoreFdInfoKHR *info) {
    if (info->fd >= 0 && is_placeholder(info->fd)) {
        close(info->fd); /* a successful import owns the fd */
        set_add(g_detached, info->semaphore);
        return VK_SUCCESS;
    }
    return NEXT(d, ImportSemaphoreFdKHR)(d, info);
}

/* Drops waits on detached semaphores from a list; returns the new count. */
static uint32_t keep_waits(const VkSemaphore *in, const VkPipelineStageFlags *stages, const uint64_t *values, uint32_t n,
                           VkSemaphore *out, VkPipelineStageFlags *out_stages, uint64_t *out_values) {
    uint32_t k = 0;
    for (uint32_t i = 0; i < n; i++) {
        if (set_has(g_detached, in[i])) continue;
        out[k] = in[i];
        if (stages) out_stages[k] = stages[i];
        if (values) out_values[k] = values[i];
        k++;
    }
    return k;
}

static VkResult VKAPI_CALL QueueSubmit(VkQueue q, uint32_t count, const VkSubmitInfo *submits, VkFence fence) {
    PFN_vkQueueSubmit next = (PFN_vkQueueSubmit)dev_for(q)->gdpa(dev_for(q)->handle, "vkQueueSubmit");
    int any = 0;
    for (uint32_t i = 0; i < count && !any; i++)
        for (uint32_t j = 0; j < submits[i].waitSemaphoreCount && !any; j++)
            any = set_has(g_detached, submits[i].pWaitSemaphores[j]);
    if (!any) return next(q, count, submits, fence);
    VkSubmitInfo *copy = calloc(count, sizeof(*copy));
    VkTimelineSemaphoreSubmitInfo *tl = calloc(count, sizeof(*tl));
    void **bufs = calloc(count * 3, sizeof(void *));
    for (uint32_t i = 0; i < count; i++) {
        copy[i] = submits[i];
        uint32_t n = submits[i].waitSemaphoreCount;
        if (!n) continue;
        VkSemaphore *sems = calloc(n, sizeof(*sems));
        VkPipelineStageFlags *stages = calloc(n, sizeof(*stages));
        uint64_t *vals = calloc(n, sizeof(*vals));
        bufs[i * 3] = sems; bufs[i * 3 + 1] = stages; bufs[i * 3 + 2] = vals;
        const VkTimelineSemaphoreSubmitInfo *t = NULL;
        for (const VkBaseInStructure *p = submits[i].pNext; p; p = p->pNext)
            if (p->sType == VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO) t = (const void *)p;
        uint32_t k = keep_waits(submits[i].pWaitSemaphores, submits[i].pWaitDstStageMask,
                                t && t->waitSemaphoreValueCount ? t->pWaitSemaphoreValues : NULL, n, sems, stages, vals);
        copy[i].waitSemaphoreCount = k;
        copy[i].pWaitSemaphores = sems;
        copy[i].pWaitDstStageMask = stages;
        if (t) {
            /* Only this struct is replaced; the rest of the chain is kept behind it. */
            tl[i] = *t;
            tl[i].waitSemaphoreValueCount = t->waitSemaphoreValueCount ? k : 0;
            tl[i].pWaitSemaphoreValues = vals;
            const void *rest = NULL;
            const VkBaseInStructure *prev = NULL;
            for (const VkBaseInStructure *p = submits[i].pNext; p; prev = p, p = p->pNext) if (p == (const void *)t) { rest = p->pNext; break; }
            (void)prev;
            tl[i].pNext = rest;
            copy[i].pNext = &tl[i]; /* other structs before it are dropped: SteamVR chains only this one */
        }
    }
    VkResult r = next(q, count, copy, fence);
    for (uint32_t i = 0; i < count * 3; i++) free(bufs[i]);
    free(bufs); free(tl); free(copy);
    return r;
}

static VkResult VKAPI_CALL QueueSubmit2(VkQueue q, uint32_t count, const VkSubmitInfo2 *submits, VkFence fence) {
    PFN_vkQueueSubmit2 next = (PFN_vkQueueSubmit2)dev_for(q)->gdpa(dev_for(q)->handle, "vkQueueSubmit2");
    if (!next) next = (PFN_vkQueueSubmit2)dev_for(q)->gdpa(dev_for(q)->handle, "vkQueueSubmit2KHR");
    int any = 0;
    for (uint32_t i = 0; i < count && !any; i++)
        for (uint32_t j = 0; j < submits[i].waitSemaphoreInfoCount && !any; j++)
            any = set_has(g_detached, submits[i].pWaitSemaphoreInfos[j].semaphore);
    if (!any) return next(q, count, submits, fence);
    VkSubmitInfo2 *copy = calloc(count, sizeof(*copy));
    VkSemaphoreSubmitInfo **waits = calloc(count, sizeof(*waits));
    for (uint32_t i = 0; i < count; i++) {
        copy[i] = submits[i];
        uint32_t n = submits[i].waitSemaphoreInfoCount, k = 0;
        waits[i] = calloc(n + 1, sizeof(VkSemaphoreSubmitInfo));
        for (uint32_t j = 0; j < n; j++)
            if (!set_has(g_detached, submits[i].pWaitSemaphoreInfos[j].semaphore)) waits[i][k++] = submits[i].pWaitSemaphoreInfos[j];
        copy[i].waitSemaphoreInfoCount = k;
        copy[i].pWaitSemaphoreInfos = waits[i];
    }
    VkResult r = next(q, count, copy, fence);
    for (uint32_t i = 0; i < count; i++) free(waits[i]);
    free(waits); free(copy);
    return r;
}

static VkResult VKAPI_CALL WaitSemaphores(VkDevice d, const VkSemaphoreWaitInfo *info, uint64_t timeout) {
    PFN_vkWaitSemaphores next = (PFN_vkWaitSemaphores)dev_for(d)->gdpa(d, "vkWaitSemaphores");
    if (!next) next = (PFN_vkWaitSemaphores)dev_for(d)->gdpa(d, "vkWaitSemaphoresKHR");
    VkSemaphore sems[64]; uint64_t vals[64]; uint32_t k = 0;
    for (uint32_t i = 0; i < info->semaphoreCount && k < 64; i++)
        if (!set_has(g_detached, info->pSemaphores[i])) { sems[k] = info->pSemaphores[i]; vals[k] = info->pValues[i]; k++; }
    if (k == info->semaphoreCount) return next(d, info, timeout);
    if (!k) return VK_SUCCESS;
    VkSemaphoreWaitInfo mine = *info;
    mine.semaphoreCount = k; mine.pSemaphores = sems; mine.pValues = vals;
    return next(d, &mine, timeout);
}

static VkResult VKAPI_CALL GetSemaphoreCounterValue(VkDevice d, VkSemaphore s, uint64_t *value) {
    if (set_has(g_detached, s)) { *value = UINT64_MAX / 2; return VK_SUCCESS; }
    PFN_vkGetSemaphoreCounterValue next = (PFN_vkGetSemaphoreCounterValue)dev_for(d)->gdpa(d, "vkGetSemaphoreCounterValue");
    if (!next) next = (PFN_vkGetSemaphoreCounterValue)dev_for(d)->gdpa(d, "vkGetSemaphoreCounterValueKHR");
    return next(d, s, value);
}

static PFN_vkVoidFunction semaphore_hook(const char *name) {
    HOOK_AS("vkCreateSemaphore", CreateSemaphore)
    HOOK_AS("vkDestroySemaphore", DestroySemaphore)
    HOOK_AS("vkGetSemaphoreFdKHR", GetSemaphoreFdKHR)
    HOOK_AS("vkImportSemaphoreFdKHR", ImportSemaphoreFdKHR)
    HOOK_AS("vkQueueSubmit", QueueSubmit)
    HOOK_AS("vkQueueSubmit2", QueueSubmit2)
    HOOK_AS("vkQueueSubmit2KHR", QueueSubmit2)
    HOOK_AS("vkWaitSemaphores", WaitSemaphores)
    HOOK_AS("vkWaitSemaphoresKHR", WaitSemaphores)
    HOOK_AS("vkGetSemaphoreCounterValue", GetSemaphoreCounterValue)
    HOOK_AS("vkGetSemaphoreCounterValueKHR", GetSemaphoreCounterValue)
    return NULL;
}

/* ------------------------------------------------------------------ proc addr */

#define HOOK(fn) if (!strcmp(name, "vk" #fn)) return (PFN_vkVoidFunction)fn;

static PFN_vkVoidFunction display_hook(const char *name) {
    HOOK(GetPhysicalDeviceDisplayPropertiesKHR)
    HOOK(GetPhysicalDeviceDisplayProperties2KHR)
    HOOK(GetPhysicalDeviceDisplayPlanePropertiesKHR)
    HOOK(GetPhysicalDeviceDisplayPlaneProperties2KHR)
    HOOK(GetDisplayPlaneSupportedDisplaysKHR)
    HOOK(GetDisplayModePropertiesKHR)
    HOOK(GetDisplayModeProperties2KHR)
    HOOK(CreateDisplayModeKHR)
    HOOK(GetDisplayPlaneCapabilitiesKHR)
    HOOK(CreateDisplayPlaneSurfaceKHR)
    HOOK(ReleaseDisplayEXT)
    HOOK(AcquireDrmDisplayEXT)
    HOOK(GetDrmDisplayEXT)
    HOOK(AcquireXlibDisplayEXT)
    HOOK(GetRandROutputDisplayEXT)
    return NULL;
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL DroidDeckVr_GetDeviceProcAddr(VkDevice device, const char *name);

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL DroidDeckVr_GetInstanceProcAddr(VkInstance instance, const char *name) {
    HOOK(CreateInstance)
    HOOK(DestroyInstance)
    HOOK(CreateDevice)
    HOOK(DestroyDevice)
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (PFN_vkVoidFunction)DroidDeckVr_GetInstanceProcAddr;
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)DroidDeckVr_GetDeviceProcAddr;
    if (!instance) return NULL;
    struct inst *in = inst_for(instance);
    if (in && in->stripped) {
        PFN_vkVoidFunction f = display_hook(name);
        if (f) return f;
    }
    if (in) {
        PFN_vkVoidFunction f = semaphore_hook(name);
        if (f && in->gipa(instance, name)) return f;
    }
    return in ? in->gipa(instance, name) : NULL;
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL DroidDeckVr_GetDeviceProcAddr(VkDevice device, const char *name) {
    HOOK(DestroyDevice)
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)DroidDeckVr_GetDeviceProcAddr;
    struct dev *d = dev_for(device);
    if (d) {
        /* Only where the driver has the function: the hooks call through to it. */
        PFN_vkVoidFunction mine = semaphore_hook(name);
        if (mine && d->gdpa(device, name)) return mine;
    }
    return d ? d->gdpa(device, name) : NULL;
}

EXPORT VKAPI_ATTR VkResult VKAPI_CALL DroidDeckVr_NegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface *v) {
    if (v->loaderLayerInterfaceVersion > 2) v->loaderLayerInterfaceVersion = 2;
    v->pfnGetInstanceProcAddr = DroidDeckVr_GetInstanceProcAddr;
    v->pfnGetDeviceProcAddr = DroidDeckVr_GetDeviceProcAddr;
    v->pfnGetPhysicalDeviceProcAddr = NULL;
    return VK_SUCCESS;
}
