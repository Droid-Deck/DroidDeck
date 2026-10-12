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
#include <errno.h>

#define LAYER_NAME "VK_LAYER_DROIDDECK_vr"
#define EXPORT __attribute__((visibility("default")))

static void say(const char *fmt, const char *arg) {
    if (getenv("DROIDDECK_VR_LAYER_QUIET")) return;
    fprintf(stderr, "[droiddeck-vr-layer] ");
    fprintf(stderr, fmt, arg);
    fputc('\n', stderr);
}

/* ------------------------------------------------------------------ crash trace (debug) */

/* DROIDDECK_VR_CRASHTRACE=1: print a backtrace on a fatal signal. vrcompositor's launcher drops
 * LD_PRELOAD, but this layer is loaded into it, so this is the way to see where it dies. */
#include <dlfcn.h>
#include <execinfo.h>
#include <signal.h>
#include <ucontext.h>

static void crash_handler(int sig, siginfo_t *si, void *uc_) {
    ucontext_t *uc = uc_;
    char buf[512];
    int n = snprintf(buf, sizeof(buf), "\n[crashtrace] %s: signal %d addr %p pc %p\n", program_invocation_short_name, sig,
                     si->si_addr, (void *)uc->uc_mcontext.pc);
    if (write(2, buf, n)) {}
    void *frames[48];
    int count = backtrace(frames, 48);
    if (count > 1) frames[1] = (void *)uc->uc_mcontext.pc;
    for (int i = 1; i < count; i++) {
        Dl_info info;
        if (dladdr(frames[i], &info) && info.dli_fname)
            n = snprintf(buf, sizeof(buf), "[crashtrace]  #%d %s+0x%lx (%s)\n", i, info.dli_fname,
                         (unsigned long)((char *)frames[i] - (char *)info.dli_fbase), info.dli_sname ? info.dli_sname : "?");
        else
            n = snprintf(buf, sizeof(buf), "[crashtrace]  #%d %p\n", i, frames[i]);
        if (write(2, buf, n)) {}
    }
    signal(sig, SIG_DFL);
    raise(sig);
}

static void crash_trace_install(void) {
    static int done;
    if (done++ || !getenv("DROIDDECK_VR_CRASHTRACE")) return;
    static char stack[65536];
    stack_t ss = {.ss_sp = stack, .ss_size = sizeof(stack)};
    sigaltstack(&ss, NULL);
    struct sigaction sa = {0};
    sa.sa_sigaction = crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    int sigs[] = {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE};
    for (unsigned i = 0; i < sizeof(sigs) / sizeof(*sigs); i++) sigaction(sigs[i], &sa, NULL);
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

#define NEXT(dev, fn) ((PFN_vk##fn)dev_for(dev)->gdpa(dev, "vk" #fn))
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
    crash_trace_install();
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
    if (stripped) {
        int have = 0;
        for (uint32_t i = 0; i < n; i++) have |= !strcmp(names[i], "VK_EXT_headless_surface");
        /* The pretend display's surface is a headless one; it needs VK_KHR_surface too. */
        const char *more[] = {"VK_EXT_headless_surface", "VK_KHR_surface"};
        names = realloc(names, (n + 3) * sizeof(*names));
        if (!have) names[n++] = more[0];
        int surf = 0;
        for (uint32_t i = 0; i < n; i++) surf |= !strcmp(names[i], "VK_KHR_surface");
        if (!surf) names[n++] = more[1];
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

/* ------------------------------------------------------------------ the display stand-in
 *
 * One pretend display with one mode and one plane. vrcompositor keeps a direct mode window on it even
 * when a driver-direct driver takes the frames, and waits on that window's presents, so the display
 * needs a working swapchain: its surface is a headless one (VK_EXT_headless_surface, Mesa's WSI).
 * The vblank side of VK_EXT_display_control is a 60 Hz clock. Size and rate:
 * DROIDDECK_VR_DISPLAY_MODE=WIDTHxHEIGHT@HZ (default 1920x1080@60). */

#include <time.h>

#define FAKE_DISPLAY ((VkDisplayKHR)(uintptr_t)0xdd0001)
#define FAKE_MODE ((VkDisplayModeKHR)(uintptr_t)0xdd0002)
static uint32_t g_mode_w = 1920, g_mode_h = 1080, g_mode_mhz = 60000;

static void mode_init(void) {
    static int done;
    if (done++) return;
    const char *m = getenv("DROIDDECK_VR_DISPLAY_MODE");
    unsigned w, h, hz;
    if (m && sscanf(m, "%ux%u@%u", &w, &h, &hz) == 3 && w && h && hz) { g_mode_w = w; g_mode_h = h; g_mode_mhz = hz * 1000; }
}

static uint64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}
static uint64_t frame_ns(void) { return 1000000000000ull / g_mode_mhz; }

static VkDisplayPropertiesKHR display_props(void) {
    mode_init();
    VkDisplayPropertiesKHR p = {0};
    p.display = FAKE_DISPLAY;
    p.displayName = "DroidDeck Cardboard";
    p.physicalDimensions = (VkExtent2D){120, 68};
    p.physicalResolution = (VkExtent2D){g_mode_w, g_mode_h};
    p.supportedTransforms = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    p.planeReorderPossible = VK_FALSE;
    p.persistentContent = VK_FALSE;
    return p;
}

#define ONE(count, out, value) do { \
    if (!(out)) { *(count) = 1; return VK_SUCCESS; } \
    if (*(count) < 1) return VK_INCOMPLETE; \
    (out)[0] = (value); *(count) = 1; return VK_SUCCESS; } while (0)

static VkResult VKAPI_CALL GetPhysicalDeviceDisplayPropertiesKHR(VkPhysicalDevice pd, uint32_t *count, VkDisplayPropertiesKHR *p) {
    ONE(count, p, display_props());
}
static VkResult VKAPI_CALL GetPhysicalDeviceDisplayProperties2KHR(VkPhysicalDevice pd, uint32_t *count, VkDisplayProperties2KHR *p) {
    if (!p) { *count = 1; return VK_SUCCESS; }
    if (*count < 1) return VK_INCOMPLETE;
    p[0].displayProperties = display_props();
    *count = 1;
    return VK_SUCCESS;
}
static VkDisplayPlanePropertiesKHR plane_props(void) {
    VkDisplayPlanePropertiesKHR p = {FAKE_DISPLAY, 0};
    return p;
}
static VkResult VKAPI_CALL GetPhysicalDeviceDisplayPlanePropertiesKHR(VkPhysicalDevice pd, uint32_t *count, VkDisplayPlanePropertiesKHR *p) {
    ONE(count, p, plane_props());
}
static VkResult VKAPI_CALL GetPhysicalDeviceDisplayPlaneProperties2KHR(VkPhysicalDevice pd, uint32_t *count, VkDisplayPlaneProperties2KHR *p) {
    if (!p) { *count = 1; return VK_SUCCESS; }
    if (*count < 1) return VK_INCOMPLETE;
    p[0].displayPlaneProperties = plane_props();
    *count = 1;
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL GetDisplayPlaneSupportedDisplaysKHR(VkPhysicalDevice pd, uint32_t plane, uint32_t *count, VkDisplayKHR *d) {
    if (plane != 0) { *count = 0; return VK_SUCCESS; }
    ONE(count, d, FAKE_DISPLAY);
}
static VkDisplayModePropertiesKHR mode_props(void) {
    mode_init();
    VkDisplayModePropertiesKHR m = {FAKE_MODE, {{g_mode_w, g_mode_h}, g_mode_mhz}};
    return m;
}
static VkResult VKAPI_CALL GetDisplayModePropertiesKHR(VkPhysicalDevice pd, VkDisplayKHR d, uint32_t *count, VkDisplayModePropertiesKHR *p) {
    ONE(count, p, mode_props());
}
static VkResult VKAPI_CALL GetDisplayModeProperties2KHR(VkPhysicalDevice pd, VkDisplayKHR d, uint32_t *count, VkDisplayModeProperties2KHR *p) {
    if (!p) { *count = 1; return VK_SUCCESS; }
    if (*count < 1) return VK_INCOMPLETE;
    p[0].displayModeProperties = mode_props();
    *count = 1;
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL CreateDisplayModeKHR(VkPhysicalDevice pd, VkDisplayKHR d, const VkDisplayModeCreateInfoKHR *ci,
                                                const VkAllocationCallbacks *a, VkDisplayModeKHR *m) {
    *m = FAKE_MODE;
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL GetDisplayPlaneCapabilitiesKHR(VkPhysicalDevice pd, VkDisplayModeKHR m, uint32_t plane, VkDisplayPlaneCapabilitiesKHR *c) {
    mode_init();
    memset(c, 0, sizeof(*c));
    c->supportedAlpha = VK_DISPLAY_PLANE_ALPHA_OPAQUE_BIT_KHR;
    c->maxSrcExtent = c->maxDstExtent = (VkExtent2D){g_mode_w, g_mode_h};
    c->minSrcExtent = c->minDstExtent = (VkExtent2D){1, 1};
    c->maxSrcPosition = c->maxDstPosition = (VkOffset2D){0, 0};
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL GetDisplayPlaneCapabilities2KHR(VkPhysicalDevice pd, const VkDisplayPlaneInfo2KHR *info, VkDisplayPlaneCapabilities2KHR *c) {
    return GetDisplayPlaneCapabilitiesKHR(pd, info->mode, info->planeIndex, &c->capabilities);
}
static VkResult VKAPI_CALL CreateDisplayPlaneSurfaceKHR(VkInstance i, const VkDisplaySurfaceCreateInfoKHR *ci,
                                                        const VkAllocationCallbacks *a, VkSurfaceKHR *s) {
    struct inst *in = inst_for(i);
    PFN_vkCreateHeadlessSurfaceEXT headless = in ? (PFN_vkCreateHeadlessSurfaceEXT)in->gipa(i, "vkCreateHeadlessSurfaceEXT") : NULL;
    if (!headless) { say("%s: no headless surface to stand in", "vkCreateDisplayPlaneSurfaceKHR"); return VK_ERROR_INITIALIZATION_FAILED; }
    VkHeadlessSurfaceCreateInfoEXT hci = {VK_STRUCTURE_TYPE_HEADLESS_SURFACE_CREATE_INFO_EXT, NULL, 0};
    VkResult r = headless(i, &hci, a, s);
    say("the display's surface is a headless one (%s)", r == VK_SUCCESS ? "ok" : "failed");
    return r;
}
static VkResult VKAPI_CALL GetPhysicalDeviceSurfaceCapabilities2EXT(VkPhysicalDevice pd, VkSurfaceKHR surface, VkSurfaceCapabilities2EXT *c) {
    struct inst *in = inst_for(pd);
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR caps =
        in ? (PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR)in->gipa(in->handle, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR") : NULL;
    if (!caps) return VK_ERROR_INITIALIZATION_FAILED;
    VkSurfaceCapabilitiesKHR k;
    VkResult r = caps(pd, surface, &k);
    if (r != VK_SUCCESS) return r;
    mode_init();
    if (k.currentExtent.width == UINT32_MAX) k.currentExtent = (VkExtent2D){g_mode_w, g_mode_h};
    c->minImageCount = k.minImageCount; c->maxImageCount = k.maxImageCount;
    c->currentExtent = k.currentExtent; c->minImageExtent = k.minImageExtent; c->maxImageExtent = k.maxImageExtent;
    c->maxImageArrayLayers = k.maxImageArrayLayers; c->supportedTransforms = k.supportedTransforms;
    c->currentTransform = k.currentTransform; c->supportedCompositeAlpha = k.supportedCompositeAlpha;
    c->supportedUsageFlags = k.supportedUsageFlags;
    c->supportedSurfaceCounters = VK_SURFACE_COUNTER_VBLANK_BIT_EXT;
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL ReleaseDisplayEXT(VkPhysicalDevice pd, VkDisplayKHR d) { return VK_SUCCESS; }
static VkResult VKAPI_CALL AcquireDrmDisplayEXT(VkPhysicalDevice pd, int32_t fd, VkDisplayKHR d) { return VK_SUCCESS; }
static VkResult VKAPI_CALL GetDrmDisplayEXT(VkPhysicalDevice pd, int32_t fd, uint32_t conn, VkDisplayKHR *d) {
    *d = FAKE_DISPLAY; return VK_SUCCESS;
}
static VkResult VKAPI_CALL AcquireXlibDisplayEXT(VkPhysicalDevice pd, void *dpy, VkDisplayKHR d) { return VK_SUCCESS; }
static VkResult VKAPI_CALL GetRandROutputDisplayEXT(VkPhysicalDevice pd, void *dpy, unsigned long out, VkDisplayKHR *d) {
    *d = FAKE_DISPLAY; return VK_SUCCESS;
}

/* VK_EXT_display_control on the device: a 60 Hz clock. A vblank event's fence comes back signalled
 * once the next tick has passed (the caller's vblank thread waits right after registering). */
static VkResult VKAPI_CALL DisplayPowerControlEXT(VkDevice d, VkDisplayKHR disp, const VkDisplayPowerInfoEXT *info) { return VK_SUCCESS; }
static VkResult signalled_fence(VkDevice d, const VkAllocationCallbacks *a, VkFence *fence) {
    VkFenceCreateInfo fci = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, NULL, VK_FENCE_CREATE_SIGNALED_BIT};
    return NEXT(d, CreateFence)(d, &fci, a, fence);
}
static VkResult VKAPI_CALL RegisterDeviceEventEXT(VkDevice d, const VkDeviceEventInfoEXT *info, const VkAllocationCallbacks *a, VkFence *fence) {
    return signalled_fence(d, a, fence);
}
static VkResult VKAPI_CALL RegisterDisplayEventEXT(VkDevice d, VkDisplayKHR disp, const VkDisplayEventInfoEXT *info,
                                                   const VkAllocationCallbacks *a, VkFence *fence) {
    mode_init();
    uint64_t f = frame_ns(), t = now_ns();
    uint64_t next = (t / f + 1) * f;
    struct timespec ts = {(time_t)(next / 1000000000ull), (long)(next % 1000000000ull)};
    clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, NULL);
    return signalled_fence(d, a, fence);
}
static VkResult VKAPI_CALL GetSwapchainCounterEXT(VkDevice d, VkSwapchainKHR sc, VkSurfaceCounterFlagBitsEXT counter, uint64_t *value) {
    mode_init();
    *value = now_ns() / frame_ns();
    return VK_SUCCESS;
}

/* The pretend display's swapchain, on the headless surface: no swapchain counter (that belongs to
 * VK_EXT_display_control, which the device does not have), and a request the headless WSI accepts. */
static VkResult VKAPI_CALL CreateSwapchainKHR(VkDevice d, const VkSwapchainCreateInfoKHR *ci, const VkAllocationCallbacks *a,
                                              VkSwapchainKHR *out) {
    PFN_vkCreateSwapchainKHR next = NEXT(d, CreateSwapchainKHR);
    VkSwapchainCreateInfoKHR sized = *ci;
    /* A headless surface has no size of its own (UINT32_MAX), and SteamVR passes that through: the
     * pretend display's mode is the size. */
    mode_init();
    if (sized.imageExtent.width == UINT32_MAX || sized.imageExtent.height == UINT32_MAX)
        sized.imageExtent = (VkExtent2D){g_mode_w, g_mode_h};
    ci = &sized;
    VkResult r = next(d, ci, a, out);
    if (r == VK_SUCCESS) return r;
    char line[256];
    int n = snprintf(line, sizeof(line), "format %d space %d mode %d images %u %ux%u usage 0x%x chain:", ci->imageFormat,
                     ci->imageColorSpace, ci->presentMode, ci->minImageCount, ci->imageExtent.width, ci->imageExtent.height,
                     ci->imageUsage);
    for (const VkBaseInStructure *p = ci->pNext; p && n < (int)sizeof(line) - 16; p = p->pNext) n += snprintf(line + n, sizeof(line) - n, " %d", p->sType);
    say("vkCreateSwapchainKHR refused (%s); retrying for the headless surface", line);
    VkSwapchainCreateInfoKHR mine = *ci;
    mine.pNext = NULL; /* drops VkSwapchainCounterCreateInfoEXT and friends */
    mine.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    mine.imageColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    if (mine.minImageCount < 2) mine.minImageCount = 2;
    r = next(d, &mine, a, out);
    if (r != VK_SUCCESS) {
        /* Last resort: the plainest swapchain the surface can take. */
        mine.imageFormat = VK_FORMAT_B8G8R8A8_UNORM;
        mine.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        mine.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        mine.preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
        r = next(d, &mine, a, out);
    }
    say("headless swapchain: %s", r == VK_SUCCESS ? "ok" : "still refused");
    return r;
}

static PFN_vkVoidFunction display_device_hook(const char *name) {
    HOOK_AS("vkCreateSwapchainKHR", CreateSwapchainKHR)
    HOOK_AS("vkDisplayPowerControlEXT", DisplayPowerControlEXT)
    HOOK_AS("vkRegisterDeviceEventEXT", RegisterDeviceEventEXT)
    HOOK_AS("vkRegisterDisplayEventEXT", RegisterDisplayEventEXT)
    HOOK_AS("vkGetSwapchainCounterEXT", GetSwapchainCounterEXT)
    return NULL;
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
        static int said;
        if (said++ < 4) say("importing a placeholder semaphore (%s)", program_invocation_short_name);
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
    if (k == info->semaphoreCount) {
        int unshared = 0;
        for (uint32_t i = 0; i < info->semaphoreCount; i++) unshared |= set_has(g_unshared, info->pSemaphores[i]);
        VkResult r = next(d, info, unshared && timeout > 500000000ull ? 500000000ull : timeout);
        if (r == VK_TIMEOUT && unshared) {
            static int said;
            if (said++ < 4) say("host wait on an unshared semaphore timed out (%s): another process was to signal it", program_invocation_short_name);
            if (timeout > 500000000ull) r = next(d, info, timeout - 500000000ull);
        }
        return r;
    }
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
    HOOK(GetDisplayPlaneCapabilities2KHR)
    HOOK(GetPhysicalDeviceSurfaceCapabilities2EXT)
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
        if (!f) f = display_device_hook(name);
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
    {
        PFN_vkVoidFunction f = display_device_hook(name);
        if (f) return f;
    }
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
