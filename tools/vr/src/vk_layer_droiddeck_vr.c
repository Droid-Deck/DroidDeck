#define _GNU_SOURCE
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>

#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <errno.h>
#define DROIDDECK_PRODUCER_SERVER
#include "producer_lease.h"
#ifdef DROIDDECK_WSI_CAPTURE
#include "wsi_capture.h"
#endif

#define LAYER_NAME "VK_LAYER_DROIDDECK_vr"
#define EXPORT __attribute__((visibility("default")))

static void say(const char *fmt, const char *arg) {
    if (getenv("DROIDDECK_VR_LAYER_QUIET")) return;
    fprintf(stderr, "[droiddeck-vr-layer] ");
    fprintf(stderr, fmt, arg);
    fputc('\n', stderr);
}


#include <dlfcn.h>
#include <execinfo.h>
#include <signal.h>
#include <ucontext.h>

static void crash_handler(int sig, siginfo_t *si, void *uc_) {
    ucontext_t *uc = uc_;
#if defined(__aarch64__)
    void *program_counter = (void *)uc->uc_mcontext.pc;
#elif defined(__x86_64__)
    void *program_counter = (void *)uc->uc_mcontext.gregs[REG_RIP];
#else
    void *program_counter = NULL;
#endif
    char buf[512];
    int n = snprintf(buf, sizeof(buf), "\n[crashtrace] %s: signal %d addr %p pc %p\n", program_invocation_short_name, sig,
                     si->si_addr, program_counter);
    if (write(2, buf, n)) {}
    void *frames[48];
    int count = backtrace(frames, 48);
    if (count > 1) frames[1] = program_counter;
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
    if (done++) return;
    if (!getenv("DROIDDECK_VR_CRASHTRACE")) {
        const char *rt = getenv("XDG_RUNTIME_DIR");
        char path[512];
        snprintf(path, sizeof(path), "%s/droiddeck-steamvr/crashtrace", rt && *rt ? rt : "/run/droiddeck");
        if (access(path, F_OK) != 0) return;
    }
    static char stack[65536];
    stack_t old_ss, ss = {.ss_sp = stack, .ss_size = sizeof(stack)};
    if (sigaltstack(NULL, &old_ss) == 0 && (old_ss.ss_flags & SS_DISABLE)) sigaltstack(&ss, NULL);
    struct sigaction sa = {0};
    sa.sa_sigaction = crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    int sigs[] = {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE};
    for (unsigned i = 0; i < sizeof(sigs) / sizeof(*sigs); i++) {
        struct sigaction old;
        if (sigaction(sigs[i], NULL, &old) == 0 && !(old.sa_flags & SA_SIGINFO) && old.sa_handler == SIG_DFL)
            sigaction(sigs[i], &sa, NULL);
    }
}


struct inst {
    VkInstance handle;
    PFN_vkGetInstanceProcAddr gipa;
    PFN_vkDestroyInstance destroy;
    int stripped;
    int active;
    int producer;
};
struct dev {
    VkDevice handle;
    PFN_vkGetDeviceProcAddr gdpa;
    PFN_vkDestroyDevice destroy;
    int active;
    int display;
    int producer;
    int quest_images;
};

static int steamvr_running(void) {
    const char *layers = getenv("VK_INSTANCE_LAYERS");
    if (layers && strstr(layers, "VK_LAYER_DROIDDECK_vr")) return 1;
    if (getenv("DROIDDECK_VR_LAYER_FORCE")) return 1;
    const char *rt = getenv("XDG_RUNTIME_DIR");
    char path[512];
    snprintf(path, sizeof(path), "%s/droiddeck-steamvr/active", rt && *rt ? rt : "/run/droiddeck");
    return access(path, F_OK) == 0;
}

static int bridge_wanted(void) {
    if (steamvr_running()) return 1;
    if (strcmp(program_invocation_short_name, "steamwebhelper")) return 0;
    const char *steamvr = getenv("STEAMVR");
    char path[512];
    snprintf(path, sizeof(path), "%s/bin/linuxarm64/vrstartup", steamvr && *steamvr ? steamvr : "/opt/steamvr");
    return access(path, F_OK) == 0;
}

#define MAX_OBJS 64
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static struct inst g_inst[MAX_OBJS];
static struct dev g_dev[MAX_OBJS];

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

static const char *const DISPLAY_EXTS[] = {
    "VK_KHR_display", "VK_EXT_direct_mode_display", "VK_EXT_display_surface_counter",
    "VK_KHR_get_display_properties2", "VK_EXT_acquire_xlib_display", "VK_EXT_acquire_drm_display",
};
static int is_display_ext(const char *name) {
    for (size_t i = 0; i < sizeof(DISPLAY_EXTS) / sizeof(*DISPLAY_EXTS); i++)
        if (!strcmp(name, DISPLAY_EXTS[i])) return 1;
    return 0;
}


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

    const int vr = steamvr_running();
    VkInstanceCreateInfo mine = *ci;
    const char **names = calloc(ci->enabledExtensionCount + 1, sizeof(*names));
    uint32_t n = 0;
    int stripped = 0;
    for (uint32_t i = 0; i < ci->enabledExtensionCount; i++) {
        if (vr && is_display_ext(ci->ppEnabledExtensionNames[i])) { say("taking %s off the instance", ci->ppEnabledExtensionNames[i]); stripped = 1; continue; }
        names[n++] = ci->ppEnabledExtensionNames[i];
    }
    if (stripped) {
        int have = 0;
        for (uint32_t i = 0; i < n; i++) have |= !strcmp(names[i], "VK_EXT_headless_surface");
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

    const int active = vr || bridge_wanted();
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_OBJS; i++) {
        if (!g_inst[i].handle) {
            const char *lease_mode = getenv("DROIDDECK_VR_PRODUCER_LEASE");
            int producer = lease_mode && !strcmp(lease_mode, "1") && !strcmp(program_invocation_short_name, "vrcompositor") &&
                           (!ci->pApplicationInfo || !ci->pApplicationInfo->pApplicationName ||
                            strcmp(ci->pApplicationInfo->pApplicationName, "droiddeck-vr-presenter"));
            g_inst[i] = (struct inst){*out, next_gipa,
                                      (PFN_vkDestroyInstance)next_gipa(*out, "vkDestroyInstance"), stripped, active, producer};
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
    const int active = in && in->active;
    const int display = in && in->stripped;

    VkDeviceCreateInfo mine = *ci;
    const char **names = calloc(ci->enabledExtensionCount + 4, sizeof(*names));
    if (!names) return VK_ERROR_OUT_OF_HOST_MEMORY;
    uint32_t n = 0;
    for (uint32_t i = 0; i < ci->enabledExtensionCount; i++) {
        if (display && !strcmp(ci->ppEnabledExtensionNames[i], "VK_EXT_display_control")) { say("taking %s off the device", ci->ppEnabledExtensionNames[i]); continue; }
        names[n++] = ci->ppEnabledExtensionNames[i];
    }
#ifdef DROIDDECK_WSI_CAPTURE
    const char *present_mode = getenv("DROIDDECK_VR_PRESENT");
    if (present_mode && !strcmp(present_mode, "questwsi") && !strcmp(program_invocation_short_name, "vrcompositor")) {
        PFN_vkEnumerateDeviceExtensionProperties enumerate = (PFN_vkEnumerateDeviceExtensionProperties)
            next_gipa(in ? in->handle : VK_NULL_HANDLE, "vkEnumerateDeviceExtensionProperties");
        uint32_t count = 0;
        if (enumerate && enumerate(pd, NULL, &count, NULL) == VK_SUCCESS && count) {
            VkExtensionProperties *supported = calloc(count, sizeof(*supported));
            if (supported && enumerate(pd, NULL, &count, supported) == VK_SUCCESS) {
                const char *required[] = {"VK_KHR_external_memory_fd", "VK_EXT_external_memory_dma_buf", "VK_EXT_queue_family_foreign"};
                for (unsigned j = 0; j < 3; j++) {
                    int enabled = 0, available = 0;
                    for (uint32_t k = 0; k < n; k++) if (!strcmp(names[k], required[j])) enabled = 1;
                    for (uint32_t k = 0; k < count; k++) if (!strcmp(supported[k].extensionName, required[j])) available = 1;
                    if (!enabled && available) names[n++] = required[j];
                }
            }
            free(supported);
        }
    }
#endif
    mine.enabledExtensionCount = n;
    mine.ppEnabledExtensionNames = names;
    VkResult r = next_create(pd, &mine, alloc, out);
    if (r != VK_SUCCESS) { free(names); return r; }
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_OBJS; i++) {
        if (!g_dev[i].handle) {
            g_dev[i] = (struct dev){*out, next_gdpa, (PFN_vkDestroyDevice)next_gdpa(*out, "vkDestroyDevice"), active, display,
                                   in && in->producer, active && access("/run/droiddeck/droiddeck-xr-frames", F_OK) == 0};
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);
#ifdef DROIDDECK_WSI_CAPTURE
    if (in) droiddeck_wsi_device(*out, pd, in->handle, in->gipa, next_gdpa, &mine);
#endif
    free(names);
    return VK_SUCCESS;
}

static void forget_device_semaphores(VkDevice dev);
static void track_forget_device(VkDevice dev);
static void producer_destroy_device(struct dev *device, const VkAllocationCallbacks *allocator);

static void VKAPI_CALL DestroyDevice(VkDevice device, const VkAllocationCallbacks *alloc) {
    struct dev *d = dev_for(device);
    if (!d) return;
#ifdef DROIDDECK_WSI_CAPTURE
    droiddeck_wsi_destroy(device);
#endif
    if (d->producer) producer_destroy_device(d, alloc);
    forget_device_semaphores(device);
    track_forget_device(device);
    PFN_vkDestroyDevice f = d->destroy;
    pthread_mutex_lock(&g_lock);
    memset(d, 0, sizeof(*d));
    pthread_mutex_unlock(&g_lock);
    if (f) f(device, alloc);
}


#include <fcntl.h>
#include <stdatomic.h>
#include <time.h>
#include "../../../app/src/main/cpp/waylandcomp/src/headpose_shm.h"

#define FAKE_DISPLAY ((VkDisplayKHR)(uintptr_t)0xdd0001)
#define FAKE_MODE ((VkDisplayModeKHR)(uintptr_t)0xdd0002)
static VkSurfaceKHR g_hmd_surface;
static uint32_t g_mode_w = 1920, g_mode_h = 1080, g_mode_mhz = 60000;
static int g_mode_fixed;

static void mode_init(void) {
    static int done;
    if (done++) return;
    const char *m = getenv("DROIDDECK_VR_DISPLAY_MODE");
    unsigned w, h, hz;
    if (m && sscanf(m, "%ux%u@%u", &w, &h, &hz) == 3 && w && h && hz) { g_mode_w = w; g_mode_h = h; g_mode_mhz = hz * 1000; g_mode_fixed = 1; }
}

static uint64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}
static _Atomic(const struct droiddeck_headpose *) g_pose;
static _Atomic uint64_t g_pose_tried;

static const struct droiddeck_headpose *pose_map(void) {
    const struct droiddeck_headpose *p = atomic_load(&g_pose);
    if (p) return p;
    uint64_t t = now_ns(), last = atomic_load(&g_pose_tried);
    if (last && t - last < 1000000000ull) return NULL;
    atomic_store(&g_pose_tried, t);
    int fd = open("/run/droiddeck/" DROIDDECK_HEADPOSE_FILE, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return NULL;
    void *m = mmap(NULL, sizeof(struct droiddeck_headpose), PROT_READ, MAP_SHARED, fd, 0);
    close(fd);
    if (m == MAP_FAILED) return NULL;
    const struct droiddeck_headpose *expected = NULL;
    if (!atomic_compare_exchange_strong(&g_pose, &expected, (const struct droiddeck_headpose *)m)) {
        munmap(m, sizeof(struct droiddeck_headpose));
        return expected;
    }
    return m;
}

static uint32_t mode_mhz(void) {
    mode_init();
    if (g_mode_fixed) return g_mode_mhz;
    const struct droiddeck_headpose *p = pose_map();
    if (!p || __atomic_load_n(&p->magic, __ATOMIC_RELAXED) != DROIDDECK_HEADPOSE_MAGIC) return g_mode_mhz;
    uint32_t bits = __atomic_load_n((const uint32_t *)&p->display_hz, __ATOMIC_RELAXED);
    float hz;
    memcpy(&hz, &bits, sizeof(hz));
    return hz >= 24.0f && hz <= 240.0f ? (uint32_t)(hz * 1000.0f + 0.5f) : g_mode_mhz;
}

static uint64_t frame_ns(void) { return 1000000000000ull / mode_mhz(); }

static pthread_mutex_t g_vblank_lock = PTHREAD_MUTEX_INITIALIZER;
static uint64_t g_vblank_base, g_vblank_at, g_vblank_frame;

static uint64_t vblank_count(void) {
    uint64_t f = frame_ns();
    pthread_mutex_lock(&g_vblank_lock);
    uint64_t t = now_ns();
    if (f != g_vblank_frame) {
        g_vblank_base = g_vblank_frame ? g_vblank_base + (t - g_vblank_at) / g_vblank_frame : t / f;
        g_vblank_at = t;
        g_vblank_frame = f;
    }
    uint64_t n = g_vblank_base + (t - g_vblank_at) / f;
    pthread_mutex_unlock(&g_vblank_lock);
    return n;
}

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
    VkDisplayModePropertiesKHR m = {FAKE_MODE, {{g_mode_w, g_mode_h}, mode_mhz()}};
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
    if (r == VK_SUCCESS) g_hmd_surface = *s;
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
    *value = vblank_count();
    return VK_SUCCESS;
}

static VkResult VKAPI_CALL CreateSwapchainKHR(VkDevice d, const VkSwapchainCreateInfoKHR *ci, const VkAllocationCallbacks *a,
                                              VkSwapchainKHR *out) {
    PFN_vkCreateSwapchainKHR next = NEXT(d, CreateSwapchainKHR);
    VkSwapchainCreateInfoKHR sized = *ci;
#ifdef DROIDDECK_WSI_CAPTURE
    const char *capture_mode = getenv("DROIDDECK_VR_PRESENT");
    const int capture = capture_mode && !strcmp(capture_mode, "questwsi")
        && !strcmp(program_invocation_short_name, "vrcompositor") && ci->surface == g_hmd_surface;
    if (capture)
        sized.imageUsage |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
#endif
    mode_init();
    if (sized.imageExtent.width == UINT32_MAX || sized.imageExtent.height == UINT32_MAX)
        sized.imageExtent = (VkExtent2D){g_mode_w, g_mode_h};
    ci = &sized;
    VkResult r = next(d, ci, a, out);
    if (r == VK_SUCCESS) {
#ifdef DROIDDECK_WSI_CAPTURE
        if (capture) droiddeck_wsi_swapchain(d, *out, ci);
#endif
        return r;
    }
    char line[256];
    int n = snprintf(line, sizeof(line), "format %d space %d mode %d images %u %ux%u usage 0x%x chain:", ci->imageFormat,
                     ci->imageColorSpace, ci->presentMode, ci->minImageCount, ci->imageExtent.width, ci->imageExtent.height,
                     ci->imageUsage);
    for (const VkBaseInStructure *p = ci->pNext; p && n < (int)sizeof(line) - 16; p = p->pNext) n += snprintf(line + n, sizeof(line) - n, " %d", p->sType);
    say("vkCreateSwapchainKHR refused (%s); retrying for the headless surface", line);
    VkSwapchainCreateInfoKHR mine = *ci;
    mine.pNext = NULL;
    mine.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    mine.imageColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    if (mine.minImageCount < 2) mine.minImageCount = 2;
    r = next(d, &mine, a, out);
    if (r != VK_SUCCESS) {
        mine.imageFormat = VK_FORMAT_B8G8R8A8_UNORM;
        mine.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
#ifdef DROIDDECK_WSI_CAPTURE
        if (capture) mine.imageUsage |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
#endif
        mine.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        mine.preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
        r = next(d, &mine, a, out);
    }
    say("headless swapchain: %s", r == VK_SUCCESS ? "ok" : "still refused");
#ifdef DROIDDECK_WSI_CAPTURE
    if (r == VK_SUCCESS && capture) droiddeck_wsi_swapchain(d, *out, &mine);
#endif
    return r;
}

static void VKAPI_CALL DestroySwapchainKHR(VkDevice device, VkSwapchainKHR swapchain, const VkAllocationCallbacks *allocator) {
#ifdef DROIDDECK_WSI_CAPTURE
    droiddeck_wsi_forget(device, swapchain);
#endif
    NEXT(device, DestroySwapchainKHR)(device, swapchain, allocator);
}

static PFN_vkVoidFunction display_device_hook(const char *name) {
    HOOK_AS("vkCreateSwapchainKHR", CreateSwapchainKHR)
    HOOK_AS("vkDestroySwapchainKHR", DestroySwapchainKHR)
    HOOK_AS("vkDisplayPowerControlEXT", DisplayPowerControlEXT)
    HOOK_AS("vkRegisterDeviceEventEXT", RegisterDeviceEventEXT)
    HOOK_AS("vkRegisterDisplayEventEXT", RegisterDisplayEventEXT)
    HOOK_AS("vkGetSwapchainCounterEXT", GetSwapchainCounterEXT)
    return NULL;
}


#include <sched.h>
#include <stdatomic.h>

#define MAX_SEMS 256
#define MAX_TYPED 2048
#define SHARED_NAME "droiddeck-shared-sem"
#define POLL_US 300

static pthread_mutex_t g_submission_lock = PTHREAD_MUTEX_INITIALIZER;
static unsigned g_producer_idle_calls;
struct producer_queue {
    VkQueue queue;
    VkDevice device;
    VkFence fence;
};
static struct producer_queue g_producer_queues[MAX_OBJS];
static int g_producer_overflow;
static int g_producer_started;
static int64_t g_producer_retry_at;
static struct droiddeck_producer_server g_producer_server;

__attribute__((destructor)) static void producer_process_exit(void) {
    if (g_producer_started && g_producer_server.path[0]) unlink(g_producer_server.path);
}

static int producer_acquire(int64_t deadline) {
    while (pthread_mutex_trylock(&g_submission_lock) != 0) {
        if (droiddeck_producer_now() >= deadline) return 0;
        usleep(100);
    }
    int queues = 0;
    if (g_producer_overflow || g_producer_idle_calls) goto fail;
    for (unsigned index = 0; index < MAX_OBJS; index++) {
        struct producer_queue *entry = &g_producer_queues[index];
        if (!entry->queue) continue;
        queues++;
        struct dev *device = dev_for(entry->device);
        if (!device) goto fail;
        PFN_vkGetFenceStatus status = (PFN_vkGetFenceStatus)device->gdpa(device->handle, "vkGetFenceStatus");
        PFN_vkDestroyFence destroy = (PFN_vkDestroyFence)device->gdpa(device->handle, "vkDestroyFence");
        if (entry->fence) {
            if (status(device->handle, entry->fence) != VK_SUCCESS) goto fail;
            destroy(device->handle, entry->fence, NULL);
            entry->fence = VK_NULL_HANDLE;
        }
        VkFenceCreateInfo create = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, NULL, 0};
        PFN_vkCreateFence create_fence = (PFN_vkCreateFence)device->gdpa(device->handle, "vkCreateFence");
        if (create_fence(device->handle, &create, NULL, &entry->fence) != VK_SUCCESS) goto fail;
        PFN_vkQueueSubmit submit = (PFN_vkQueueSubmit)device->gdpa(device->handle, "vkQueueSubmit");
        if (submit(entry->queue, 0, NULL, entry->fence) != VK_SUCCESS) {
            destroy(device->handle, entry->fence, NULL);
            entry->fence = VK_NULL_HANDLE;
            goto fail;
        }
    }
    if (!queues) goto fail;
    for (unsigned index = 0; index < MAX_OBJS; index++) {
        struct producer_queue *entry = &g_producer_queues[index];
        if (!entry->queue) continue;
        int64_t remaining = deadline - droiddeck_producer_now();
        if (remaining <= 0) goto fail;
        struct dev *device = dev_for(entry->device);
        PFN_vkWaitForFences wait = (PFN_vkWaitForFences)device->gdpa(device->handle, "vkWaitForFences");
        if (wait(device->handle, 1, &entry->fence, VK_TRUE, (uint64_t)remaining) != VK_SUCCESS) goto fail;
    }
    return 1;
fail:
    pthread_mutex_unlock(&g_submission_lock);
    return 0;
}

static void producer_release(void) {
    pthread_mutex_unlock(&g_submission_lock);
}

static void producer_quarantine(void) {
    say("%s", "producer lease lost before capture completion: submissions quarantined; restart SteamVR");
}

static void producer_record(struct dev *device, VkQueue queue) {
    if (!g_producer_started && droiddeck_producer_now() >= g_producer_retry_at) {
        g_producer_retry_at = droiddeck_producer_now() + 1000000000LL;
        g_producer_server.acquire = producer_acquire;
        g_producer_server.release = producer_release;
        g_producer_server.quarantine = producer_quarantine;
        if (droiddeck_producer_start(&g_producer_server)) {
            pthread_detach(g_producer_server.thread);
            g_producer_started = 1;
        }
    }
    for (unsigned index = 0; index < MAX_OBJS; index++) {
        struct producer_queue *entry = &g_producer_queues[index];
        if (entry->queue == queue) return;
        if (!entry->queue) {
            *entry = (struct producer_queue){queue, device->handle, VK_NULL_HANDLE};
            return;
        }
    }
    g_producer_overflow = 1;
}

static void producer_destroy_device(struct dev *device, const VkAllocationCallbacks *allocator) {
    (void)allocator;
    pthread_mutex_lock(&g_submission_lock);
    PFN_vkDestroyFence destroy = (PFN_vkDestroyFence)device->gdpa(device->handle, "vkDestroyFence");
    for (unsigned index = 0; index < MAX_OBJS; index++) {
        struct producer_queue *entry = &g_producer_queues[index];
        if (entry->device != device->handle) continue;
        if (entry->fence) destroy(device->handle, entry->fence, NULL);
        memset(entry, 0, sizeof(*entry));
    }
    pthread_mutex_unlock(&g_submission_lock);
}

struct bridged {
    VkSemaphore sem;
    VkDevice dev;
    _Atomic uint64_t *counter;
    int fd;
    int timeline;
    unsigned long ino;
    uint64_t submitted;
    uint64_t seen, seen_us;
    int imported;
};
static pthread_mutex_t g_br_lock = PTHREAD_MUTEX_INITIALIZER;
static struct bridged g_br[MAX_SEMS];
static VkSemaphore g_timeline_sems[MAX_TYPED];
static pthread_t g_poller;
static int g_poller_started;

static int timeline_known(VkSemaphore s) {
    int r = 0;
    pthread_mutex_lock(&g_br_lock);
    for (int i = 0; i < MAX_TYPED && !r; i++) r = g_timeline_sems[i] == s;
    pthread_mutex_unlock(&g_br_lock);
    return r;
}
static void timeline_note(VkSemaphore s, int add) {
    pthread_mutex_lock(&g_br_lock);
    for (int i = 0; i < MAX_TYPED; i++) {
        if (add ? !g_timeline_sems[i] : g_timeline_sems[i] == s) { g_timeline_sems[i] = add ? s : VK_NULL_HANDLE; break; }
    }
    pthread_mutex_unlock(&g_br_lock);
}

static struct bridged *bridged_find(VkSemaphore s) {
    for (int i = 0; i < MAX_SEMS; i++) if (g_br[i].sem == s) return &g_br[i];
    return NULL;
}
static void bridged_drop(struct bridged *b) {
    if (b->counter) munmap((void *)b->counter, 4096);
    if (b->fd >= 0) close(b->fd);
    memset(b, 0, sizeof(*b));
    b->fd = -1;
}

static int sem_trace_on(void) {
    static int checked, on;
    if (!checked++) {
        const char *rt = getenv("XDG_RUNTIME_DIR");
        char path[512];
        snprintf(path, sizeof(path), "%s/droiddeck-steamvr/semtrace", rt && *rt ? rt : "/run/droiddeck");
        on = access(path, F_OK) == 0;
    }
    return on;
}

static uint64_t now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000u + (uint64_t)ts.tv_nsec / 1000u;
}

static void *poller(void *arg) {
    unsigned ticks = 0;
    for (;;) {
        const int trace = sem_trace_on() && (++ticks % (2000000 / POLL_US) == 0);
        pthread_mutex_lock(&g_br_lock);
        for (int i = 0; i < MAX_SEMS; i++) {
            struct bridged *b = &g_br[i];
            if (!b->sem || !b->timeline) continue;
            struct dev *d = dev_for(b->dev);
            if (!d) continue;
            PFN_vkGetSemaphoreCounterValue get = (PFN_vkGetSemaphoreCounterValue)d->gdpa(b->dev, "vkGetSemaphoreCounterValue");
            if (!get) get = (PFN_vkGetSemaphoreCounterValue)d->gdpa(b->dev, "vkGetSemaphoreCounterValueKHR");
            PFN_vkSignalSemaphore sig = (PFN_vkSignalSemaphore)d->gdpa(b->dev, "vkSignalSemaphore");
            if (!sig) sig = (PFN_vkSignalSemaphore)d->gdpa(b->dev, "vkSignalSemaphoreKHR");
            uint64_t local = 0;
            if (!get || get(b->dev, b->sem, &local) != VK_SUCCESS) continue;
            uint64_t shared = atomic_load(b->counter);
            if (shared != b->seen) { b->seen = shared; b->seen_us = now_us(); }
            if (trace) {
                char line[192];
                snprintf(line, sizeof(line), "%s sem %p %s counter %lu local %llu shared %llu", program_invocation_short_name,
                         (void *)b->sem, b->imported ? "imported" : "made here", b->ino, (unsigned long long)local, (unsigned long long)shared);
                say("%s", line);
            }
            if (local > shared) {
                while (local > shared && !atomic_compare_exchange_weak(b->counter, &shared, local)) {}
            } else if (shared > local && sig) {
                VkSemaphoreSignalInfo si = {VK_STRUCTURE_TYPE_SEMAPHORE_SIGNAL_INFO, NULL, b->sem, shared};
                sig(b->dev, &si);
            }
        }
        pthread_mutex_unlock(&g_br_lock);
        usleep(POLL_US);
    }
    return arg;
}

static void poller_start(void) {
    if (__atomic_exchange_n(&g_poller_started, 1, __ATOMIC_ACQ_REL)) return;
    if (pthread_create(&g_poller, NULL, poller, NULL) == 0) pthread_detach(g_poller);
    say("%s", "shared semaphores are bridged between processes");
}

static _Atomic uint64_t *map_counter(int fd) {
    void *p = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    return p == MAP_FAILED ? NULL : (_Atomic uint64_t *)p;
}

static unsigned long fd_ino(int fd) {
    struct stat st;
    return fstat(fd, &st) == 0 ? (unsigned long)st.st_ino : 0;
}

static int is_shared_counter(int fd) {
    char path[64], target[128];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    ssize_t n = readlink(path, target, sizeof(target) - 1);
    if (n <= 0) return 0;
    target[n] = 0;
    return strstr(target, SHARED_NAME) != NULL;
}

static VkResult VKAPI_CALL CreateSemaphore(VkDevice d, const VkSemaphoreCreateInfo *ci, const VkAllocationCallbacks *a, VkSemaphore *out) {
    PFN_vkCreateSemaphore next = NEXT(d, CreateSemaphore);
    const VkBaseInStructure *export_info = NULL;
    const VkSemaphoreTypeCreateInfo *type_info = NULL;
    for (const VkBaseInStructure *p = ci->pNext; p; p = p->pNext) {
        if (p->sType == VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO) export_info = p;
        if (p->sType == VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO) type_info = (const void *)p;
    }
    const int timeline = type_info && type_info->semaphoreType == VK_SEMAPHORE_TYPE_TIMELINE;
    VkResult r = next(d, ci, a, out);
    if (r == VK_SUCCESS) {
        if (timeline) timeline_note(*out, 1);
        return r;
    }
    if (!export_info || !timeline ||
        ((const VkExportSemaphoreCreateInfo *)export_info)->handleTypes != VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT) return r;
    for (const VkBaseInStructure *item = ci->pNext; item; item = item->pNext)
        if (item != export_info && item != (const void *)type_info) return r;
    VkSemaphoreTypeCreateInfo type = {0};
    VkSemaphoreCreateInfo mine = *ci;
    mine.pNext = NULL;
    if (type_info) {
        type = *type_info;
        type.pNext = NULL;
        mine.pNext = &type;
    }
    r = next(d, &mine, a, out);
    if (r != VK_SUCCESS) return r;
    if (timeline) timeline_note(*out, 1);
    int fd = memfd_create(SHARED_NAME, MFD_CLOEXEC);
    _Atomic uint64_t *counter = NULL;
    if (fd >= 0 && ftruncate(fd, 4096) == 0) counter = map_counter(fd);
    if (!counter) {
        if (fd >= 0) close(fd);
        NEXT(d, DestroySemaphore)(d, *out, a);
        timeline_note(*out, 0);
        *out = VK_NULL_HANDLE;
        return VK_ERROR_OUT_OF_HOST_MEMORY;
    }
    atomic_store(counter, timeline ? type_info->initialValue : 0);
    pthread_mutex_lock(&g_br_lock);
    struct bridged *slot = bridged_find(VK_NULL_HANDLE);
    if (slot) *slot = (struct bridged){*out, d, counter, fd, timeline, fd_ino(fd), 0, atomic_load(counter), now_us(), 0};
    pthread_mutex_unlock(&g_br_lock);
    if (!slot) {
        munmap((void *)counter, 4096);
        close(fd);
        NEXT(d, DestroySemaphore)(d, *out, a);
        timeline_note(*out, 0);
        *out = VK_NULL_HANDLE;
        return VK_ERROR_TOO_MANY_OBJECTS;
    }
    if (timeline) poller_start();
    return r;
}

static void forget_semaphore(VkSemaphore s) {
    pthread_mutex_lock(&g_br_lock);
    struct bridged *b = bridged_find(s);
    if (b) bridged_drop(b);
    pthread_mutex_unlock(&g_br_lock);
    timeline_note(s, 0);
}

static void forget_device_semaphores(VkDevice dev) {
    pthread_mutex_lock(&g_br_lock);
    for (int i = 0; i < MAX_SEMS; i++) if (g_br[i].sem && g_br[i].dev == dev) bridged_drop(&g_br[i]);
    pthread_mutex_unlock(&g_br_lock);
}

static void VKAPI_CALL DestroySemaphore(VkDevice d, VkSemaphore s, const VkAllocationCallbacks *a) {
    forget_semaphore(s);
    NEXT(d, DestroySemaphore)(d, s, a);
}

static VkResult VKAPI_CALL GetSemaphoreFdKHR(VkDevice d, const VkSemaphoreGetFdInfoKHR *info, int *fd) {
    pthread_mutex_lock(&g_br_lock);
    struct bridged *b = bridged_find(info->semaphore);
    int mine = b && b->fd >= 0 ? dup(b->fd) : -2;
    if (b && info->handleType != VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT) {
        if (mine >= 0) close(mine);
        pthread_mutex_unlock(&g_br_lock);
        return VK_ERROR_INVALID_EXTERNAL_HANDLE;
    }
    pthread_mutex_unlock(&g_br_lock);
    if (mine == -2) return NEXT(d, GetSemaphoreFdKHR)(d, info, fd);
    *fd = mine;
    return mine >= 0 ? VK_SUCCESS : VK_ERROR_TOO_MANY_OBJECTS;
}

static VkResult VKAPI_CALL ImportSemaphoreFdKHR(VkDevice d, const VkImportSemaphoreFdInfoKHR *info) {
    if (info->fd < 0 || !is_shared_counter(info->fd)) return NEXT(d, ImportSemaphoreFdKHR)(d, info);
    const int timeline = timeline_known(info->semaphore);
    struct stat metadata;
    if (!timeline || info->flags || info->handleType != VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT ||
        fstat(info->fd, &metadata) != 0 || metadata.st_size != 4096) return VK_ERROR_INVALID_EXTERNAL_HANDLE;
    _Atomic uint64_t *counter = map_counter(info->fd);
    if (!counter) return VK_ERROR_OUT_OF_HOST_MEMORY;
    const unsigned long ino = fd_ino(info->fd);
    pthread_mutex_lock(&g_br_lock);
    struct bridged *b = bridged_find(info->semaphore);
    if (b) bridged_drop(b);
    b = bridged_find(VK_NULL_HANDLE);
    if (b) *b = (struct bridged){info->semaphore, d, counter, info->fd, timeline, ino, 0, atomic_load(counter), now_us(), 1};
    pthread_mutex_unlock(&g_br_lock);
    if (!b) {
        munmap((void *)counter, 4096);
        return VK_ERROR_TOO_MANY_OBJECTS;
    }
    if (timeline) poller_start();
    static int said;
    if (said++ < 4) say("imported a shared timeline semaphore (%s): bridged", program_invocation_short_name);
    return VK_SUCCESS;
}

#define SUBMIT_GRACE_US 3000
#define SHARED_IDLE_US 100000

static int quest_frames(void) {
    static _Atomic uint64_t checked_us;
    static _Atomic int present;
    const uint64_t t = now_us();
    const uint64_t last = atomic_load(&checked_us);
    if (!last || t - last > 1000000) {
        atomic_store(&present, access("/run/droiddeck/droiddeck-xr-frames", F_OK) == 0);
        atomic_store(&checked_us, t);
    }
    return atomic_load(&present);
}

static int submit_drop_wait(VkSemaphore s, uint64_t value) {
    pthread_mutex_lock(&g_br_lock);
    struct bridged *b = bridged_find(s);
    if (!b || !b->timeline || !b->imported) { pthread_mutex_unlock(&g_br_lock); return 0; }
    const uint64_t queued = b->submitted;
    const uint64_t seen_us = b->seen_us;
    VkDevice dev = b->dev;
    pthread_mutex_unlock(&g_br_lock);
    if (!value || value <= queued) return 0;
    struct dev *d = dev_for(dev);
    if (!d || quest_frames()) return 0;
    PFN_vkGetSemaphoreCounterValue get = (PFN_vkGetSemaphoreCounterValue)d->gdpa(dev, "vkGetSemaphoreCounterValue");
    if (!get) get = (PFN_vkGetSemaphoreCounterValue)d->gdpa(dev, "vkGetSemaphoreCounterValueKHR");
    if (!get) return 0;
    const uint64_t start = now_us();
    const uint64_t until = start + (start - seen_us > SHARED_IDLE_US ? 0 : SUBMIT_GRACE_US);
    for (;;) {
        uint64_t local = 0;
        if (get(dev, s, &local) == VK_SUCCESS && local >= value) return 0;
        /* Only the poller mirrors shared values. A second host signal here can
         * race its signal and submit the same timeline value twice. */
        if (now_us() >= until) break;
        usleep(100);
    }
    static _Atomic int dropped;
    if (atomic_fetch_add(&dropped, 1) == 0)
        say("%s: dropping a submit's wait on a shared value nobody has signalled yet (Turnip would block the submit)",
            program_invocation_short_name);
    return 1;
}

static void note_signal(VkSemaphore s, uint64_t value) {
    pthread_mutex_lock(&g_br_lock);
    struct bridged *b = bridged_find(s);
    if (b && b->timeline && value > b->submitted) b->submitted = value;
    pthread_mutex_unlock(&g_br_lock);
}

static void note_submit_signals(uint32_t count, const VkSubmitInfo *submits) {
    for (uint32_t i = 0; i < count; i++) {
        const VkTimelineSemaphoreSubmitInfo *t = NULL;
        for (const VkBaseInStructure *p = submits[i].pNext; p; p = p->pNext)
            if (p->sType == VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO) t = (const void *)p;
        for (uint32_t j = 0; t && j < submits[i].signalSemaphoreCount && j < t->signalSemaphoreValueCount; j++)
            note_signal(submits[i].pSignalSemaphores[j], t->pSignalSemaphoreValues[j]);
    }
}

static void note_submit2_signals(uint32_t count, const VkSubmitInfo2 *submits) {
    for (uint32_t i = 0; i < count; i++)
        for (uint32_t j = 0; j < submits[i].signalSemaphoreInfoCount; j++)
            note_signal(submits[i].pSignalSemaphoreInfos[j].semaphore, submits[i].pSignalSemaphoreInfos[j].value);
}

static uint32_t keep_waits(const VkSemaphore *in, const VkPipelineStageFlags *stages, const uint64_t *values, uint32_t n,
                           VkSemaphore *out, VkPipelineStageFlags *out_stages, uint64_t *out_values) {
    uint32_t k = 0;
    for (uint32_t i = 0; i < n; i++) {
        if (submit_drop_wait(in[i], values ? values[i] : 0)) continue;
        out[k] = in[i];
        if (stages) out_stages[k] = stages[i];
        if (values) out_values[k] = values[i];
        k++;
    }
    return k;
}

static void trace_sem(const char *what, VkSemaphore sem, uint64_t value) {
    if (!sem_trace_on() || value > 3) return;
    pthread_mutex_lock(&g_br_lock);
    struct bridged *b = bridged_find(sem);
    unsigned long ino = b ? b->ino : 0;
    pthread_mutex_unlock(&g_br_lock);
    if (!ino) return;
    char line[160];
    snprintf(line, sizeof(line), "%s %s %p value %llu counter %lu", program_invocation_short_name, what, (void *)sem,
             (unsigned long long)value, ino);
    say("%s", line);
}

#include <limits.h>
#include <linux/futex.h>
#include <sys/syscall.h>
#include "present_sync.h"
#include "fence_tracker.h"
#include <time.h>

static pthread_mutex_t g_trk_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_trk_cond = PTHREAD_COND_INITIALIZER;
static struct tracked *g_trk;
static struct droiddeck_present_sync *g_sync;
static pthread_once_t g_sync_once = PTHREAD_ONCE_INIT;

static void sync_wake(struct droiddeck_present_sync *s) {
    syscall(SYS_futex, &s->done, FUTEX_WAKE, INT_MAX, NULL, NULL, 0);
}

static void *completer(void *arg) {
    struct droiddeck_present_sync *s = arg;
    pthread_mutex_lock(&g_trk_lock);
    for (;;) {
        uint32_t done;
        int pending = track_pool_reap(g_trk, __atomic_load_n(&s->issued, __ATOMIC_ACQUIRE), &done);
        if (done != __atomic_load_n(&s->done, __ATOMIC_RELAXED)) {
            __atomic_store_n(&s->done, done, __ATOMIC_RELEASE);
            sync_wake(s);
        }
        pthread_cond_broadcast(&g_trk_cond);
        if (pending) {
            struct timespec until;
            clock_gettime(CLOCK_REALTIME, &until);
            until.tv_nsec += 1000000; /* Poll pending GPU work at most every 1 ms. */
            if (until.tv_nsec >= 1000000000) { until.tv_sec++; until.tv_nsec -= 1000000000; }
            pthread_cond_timedwait(&g_trk_cond, &g_trk_lock, &until);
        } else {
            pthread_cond_wait(&g_trk_cond, &g_trk_lock);
        }
    }
    return NULL;
}

static void sync_init(void) {
    if (strcmp(program_invocation_short_name, "vrcompositor")) return;
    const char *rt = getenv("XDG_RUNTIME_DIR");
    char path[512];
    snprintf(path, sizeof(path), "%s/droiddeck-steamvr/%s", rt && *rt ? rt : "/run/droiddeck", DROIDDECK_PRESENT_SYNC_NAME);
    int fd = open(path, O_RDWR | O_CREAT | O_CLOEXEC, 0600);
    if (fd < 0) return;
    void *m = ftruncate(fd, DROIDDECK_PRESENT_SYNC_SIZE) == 0
        ? mmap(NULL, DROIDDECK_PRESENT_SYNC_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0) : MAP_FAILED;
    close(fd);
    if (m == MAP_FAILED) return;
    struct droiddeck_present_sync *s = m;
    __atomic_store_n(&s->done, __atomic_load_n(&s->issued, __ATOMIC_ACQUIRE), __ATOMIC_RELEASE);
    sync_wake(s);
    pthread_t t;
    if (pthread_create(&t, NULL, completer, s) != 0) { munmap(m, DROIDDECK_PRESENT_SYNC_SIZE); return; }
    pthread_detach(t);
    g_sync = s;
    say("%s", "frames go to the presenter once their GPU work is done");
}

static struct tracked *track_take(struct dev *d) {
    pthread_mutex_lock(&g_trk_lock);
    struct tracked *t = track_pool_take(&g_trk, d->handle, d->gdpa);
    pthread_mutex_unlock(&g_trk_lock);
    return t;
}

static void track_submitted(struct tracked *t, VkResult r) {
    pthread_mutex_lock(&g_trk_lock);
    uint32_t issued = track_pool_submitted(t, r, __atomic_load_n(&g_sync->issued, __ATOMIC_RELAXED));
    __atomic_store_n(&g_sync->issued, issued, __ATOMIC_RELEASE);
    pthread_cond_broadcast(&g_trk_cond);
    pthread_mutex_unlock(&g_trk_lock);
}

static void track_forget_device(VkDevice dev) {
    pthread_mutex_lock(&g_trk_lock);
    while (track_pool_device_busy(g_trk, dev))
        pthread_cond_wait(&g_trk_cond, &g_trk_lock);
    track_pool_forget(&g_trk, dev);
    pthread_mutex_unlock(&g_trk_lock);
}

static VkResult submit_tracked(PFN_vkQueueSubmit next, VkQueue q, uint32_t count, const VkSubmitInfo *submits, VkFence fence) {
    if (strcmp(program_invocation_short_name, "vrcompositor")) return next(q, count, submits, fence);
    pthread_once(&g_sync_once, sync_init);
    struct dev *d = dev_for(q);
    if (!g_sync || !d) return next(q, count, submits, fence);
    struct tracked *slot = track_take(d);
    if (!slot) return VK_ERROR_OUT_OF_HOST_MEMORY;
    const VkFence mine = slot->fence;
    VkResult r = next(q, count, submits, fence ? fence : mine);
    if (r == VK_SUCCESS && fence) r = next(q, 0, NULL, mine);
    track_submitted(slot, r);
    return r;
}

static VkResult submit2_tracked(PFN_vkQueueSubmit2 next, VkQueue q, uint32_t count, const VkSubmitInfo2 *submits, VkFence fence) {
    if (strcmp(program_invocation_short_name, "vrcompositor")) return next(q, count, submits, fence);
    pthread_once(&g_sync_once, sync_init);
    struct dev *d = dev_for(q);
    if (!g_sync || !d) return next(q, count, submits, fence);
    struct tracked *slot = track_take(d);
    if (!slot) return VK_ERROR_OUT_OF_HOST_MEMORY;
    const VkFence mine = slot->fence;
    VkResult r = next(q, count, submits, fence ? fence : mine);
    if (r == VK_SUCCESS && fence) r = next(q, 0, NULL, mine);
    track_submitted(slot, r);
    return r;
}

static VkResult VKAPI_CALL QueueSubmit(VkQueue q, uint32_t count, const VkSubmitInfo *submits, VkFence fence) {
    PFN_vkQueueSubmit next = (PFN_vkQueueSubmit)dev_for(q)->gdpa(dev_for(q)->handle, "vkQueueSubmit");
    if (sem_trace_on())
        for (uint32_t i = 0; i < count; i++) {
            const VkTimelineSemaphoreSubmitInfo *t = NULL;
            for (const VkBaseInStructure *p = submits[i].pNext; p; p = p->pNext)
                if (p->sType == VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO) t = (const void *)p;
            for (uint32_t j = 0; j < submits[i].waitSemaphoreCount; j++)
                trace_sem("waits", submits[i].pWaitSemaphores[j], t && j < t->waitSemaphoreValueCount ? t->pWaitSemaphoreValues[j] : 0);
            for (uint32_t j = 0; j < submits[i].signalSemaphoreCount; j++)
                trace_sem("signals", submits[i].pSignalSemaphores[j], t && j < t->signalSemaphoreValueCount ? t->pSignalSemaphoreValues[j] : 0);
        }
    int any = 0;
    for (uint32_t i = 0; i < count && !any; i++) {
        const VkTimelineSemaphoreSubmitInfo *t = NULL;
        for (const VkBaseInStructure *p = submits[i].pNext; p; p = p->pNext)
            if (p->sType == VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO) t = (const void *)p;
        for (uint32_t j = 0; submits[i].pWaitSemaphores && j < submits[i].waitSemaphoreCount && !any; j++)
            any = submit_drop_wait(submits[i].pWaitSemaphores[j], t && j < t->waitSemaphoreValueCount ? t->pWaitSemaphoreValues[j] : 0);
    }
    VkSubmitInfo *copy = NULL;
    VkTimelineSemaphoreSubmitInfo *tl = NULL;
    void **bufs = NULL;
    if (any) {
        copy = calloc(count, sizeof(*copy));
        tl = calloc(count, sizeof(*tl));
        bufs = calloc(count * 3, sizeof(void *));
        if (!copy || !tl || !bufs) { free(copy); free(tl); free(bufs); return VK_ERROR_OUT_OF_HOST_MEMORY; }
        for (uint32_t i = 0; i < count; i++) {
            copy[i] = submits[i];
            uint32_t n = submits[i].waitSemaphoreCount;
            if (!n) continue;
            VkSemaphore *sems = calloc(n, sizeof(*sems));
            VkPipelineStageFlags *stages = calloc(n, sizeof(*stages));
            uint64_t *vals = calloc(n, sizeof(*vals));
            bufs[i * 3] = sems; bufs[i * 3 + 1] = stages; bufs[i * 3 + 2] = vals;
            if (!sems || !stages || !vals) {
                for (uint32_t k = 0; k < count * 3; k++) free(bufs[k]);
                free(bufs); free(tl); free(copy);
                return VK_ERROR_OUT_OF_HOST_MEMORY;
            }
            const VkTimelineSemaphoreSubmitInfo *t = NULL;
            for (const VkBaseInStructure *p = submits[i].pNext; p; p = p->pNext)
                if (p->sType == VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO) t = (const void *)p;
            uint32_t k = keep_waits(submits[i].pWaitSemaphores, submits[i].pWaitDstStageMask,
                                    t && t->waitSemaphoreValueCount ? t->pWaitSemaphoreValues : NULL, n, sems, stages, vals);
            copy[i].waitSemaphoreCount = k;
            copy[i].pWaitSemaphores = sems;
            copy[i].pWaitDstStageMask = stages;
            if (t) {
                tl[i] = *t;
                tl[i].waitSemaphoreValueCount = t->waitSemaphoreValueCount ? k : 0;
                tl[i].pWaitSemaphoreValues = vals;
                tl[i].pNext = t->pNext;
                copy[i].pNext = &tl[i];
            }
        }
    }
    struct dev *device = dev_for(q);
    if (device->producer) { pthread_mutex_lock(&g_submission_lock); producer_record(device, q); }
    VkResult result = submit_tracked(next, q, count, any ? copy : submits, fence);
    if (device->producer) pthread_mutex_unlock(&g_submission_lock);
    note_submit_signals(count, submits);
    if (any) {
        for (uint32_t i = 0; i < count * 3; i++) free(bufs[i]);
        free(bufs); free(tl); free(copy);
    }
    return result;
}

static VkResult VKAPI_CALL QueueSubmit2(VkQueue q, uint32_t count, const VkSubmitInfo2 *submits, VkFence fence) {
    PFN_vkQueueSubmit2 next = (PFN_vkQueueSubmit2)dev_for(q)->gdpa(dev_for(q)->handle, "vkQueueSubmit2");
    if (!next) next = (PFN_vkQueueSubmit2)dev_for(q)->gdpa(dev_for(q)->handle, "vkQueueSubmit2KHR");
    if (sem_trace_on())
        for (uint32_t i = 0; i < count; i++) {
            for (uint32_t j = 0; j < submits[i].waitSemaphoreInfoCount; j++)
                trace_sem("waits", submits[i].pWaitSemaphoreInfos[j].semaphore, submits[i].pWaitSemaphoreInfos[j].value);
            for (uint32_t j = 0; j < submits[i].signalSemaphoreInfoCount; j++)
                trace_sem("signals", submits[i].pSignalSemaphoreInfos[j].semaphore, submits[i].pSignalSemaphoreInfos[j].value);
        }
    int any = 0;
    for (uint32_t i = 0; i < count && !any; i++)
        for (uint32_t j = 0; submits[i].pWaitSemaphoreInfos && j < submits[i].waitSemaphoreInfoCount && !any; j++)
            any = submit_drop_wait(submits[i].pWaitSemaphoreInfos[j].semaphore, submits[i].pWaitSemaphoreInfos[j].value);
    VkSubmitInfo2 *copy = NULL;
    VkSemaphoreSubmitInfo **waits = NULL;
    if (any) {
        copy = calloc(count, sizeof(*copy));
        waits = calloc(count, sizeof(*waits));
        if (!copy || !waits) { free(copy); free(waits); return VK_ERROR_OUT_OF_HOST_MEMORY; }
        for (uint32_t i = 0; i < count; i++) {
            copy[i] = submits[i];
            uint32_t n = submits[i].waitSemaphoreInfoCount, k = 0;
            waits[i] = calloc(n + 1, sizeof(VkSemaphoreSubmitInfo));
            if (!waits[i]) {
                for (uint32_t j = 0; j < i; j++) free(waits[j]);
                free(waits); free(copy);
                return VK_ERROR_OUT_OF_HOST_MEMORY;
            }
            for (uint32_t j = 0; j < n; j++)
                if (!submit_drop_wait(submits[i].pWaitSemaphoreInfos[j].semaphore, submits[i].pWaitSemaphoreInfos[j].value))
                    waits[i][k++] = submits[i].pWaitSemaphoreInfos[j];
            copy[i].waitSemaphoreInfoCount = k;
            copy[i].pWaitSemaphoreInfos = waits[i];
        }
    }
    struct dev *device = dev_for(q);
    if (device->producer) { pthread_mutex_lock(&g_submission_lock); producer_record(device, q); }
    VkResult result = submit2_tracked(next, q, count, any ? copy : submits, fence);
    if (device->producer) pthread_mutex_unlock(&g_submission_lock);
    note_submit2_signals(count, submits);
    if (any) {
        for (uint32_t i = 0; i < count; i++) free(waits[i]);
        free(waits); free(copy);
    }
    return result;
}

static VkResult VKAPI_CALL QueuePresentKHR(VkQueue queue, const VkPresentInfoKHR *info) {
    struct dev *device = dev_for(queue);
    PFN_vkQueuePresentKHR next = (PFN_vkQueuePresentKHR)device->gdpa(device->handle, "vkQueuePresentKHR");
    if (device->producer) { pthread_mutex_lock(&g_submission_lock); producer_record(device, queue); }
#ifdef DROIDDECK_WSI_CAPTURE
    VkResult result = droiddeck_wsi_present(device->handle, queue, info, next);
#else
    VkResult result = next(queue, info);
#endif
    if (device->producer) pthread_mutex_unlock(&g_submission_lock);
    return result;
}

static VkResult VKAPI_CALL QueueBindSparse(VkQueue queue, uint32_t count, const VkBindSparseInfo *binds, VkFence fence) {
    struct dev *device = dev_for(queue);
    PFN_vkQueueBindSparse next = (PFN_vkQueueBindSparse)device->gdpa(device->handle, "vkQueueBindSparse");
    if (device->producer) { pthread_mutex_lock(&g_submission_lock); producer_record(device, queue); }
    VkResult result = next(queue, count, binds, fence);
    if (device->producer) pthread_mutex_unlock(&g_submission_lock);
    return result;
}

static VkResult VKAPI_CALL QueueWaitIdle(VkQueue queue) {
    struct dev *device = dev_for(queue);
    PFN_vkQueueWaitIdle next = (PFN_vkQueueWaitIdle)device->gdpa(device->handle, "vkQueueWaitIdle");
    if (device->producer) {
        pthread_mutex_lock(&g_submission_lock);
        g_producer_idle_calls++;
        pthread_mutex_unlock(&g_submission_lock);
    }
    VkResult result = next(queue);
    if (device->producer) {
        pthread_mutex_lock(&g_submission_lock);
        g_producer_idle_calls--;
        pthread_mutex_unlock(&g_submission_lock);
    }
    return result;
}

static VkResult VKAPI_CALL DeviceWaitIdle(VkDevice handle) {
    struct dev *device = dev_for(handle);
    PFN_vkDeviceWaitIdle next = (PFN_vkDeviceWaitIdle)device->gdpa(handle, "vkDeviceWaitIdle");
    if (device->producer) {
        pthread_mutex_lock(&g_submission_lock);
        g_producer_idle_calls++;
        pthread_mutex_unlock(&g_submission_lock);
    }
    VkResult result = next(handle);
    if (device->producer) {
        pthread_mutex_lock(&g_submission_lock);
        g_producer_idle_calls--;
        pthread_mutex_unlock(&g_submission_lock);
    }
    return result;
}

static VkResult VKAPI_CALL WaitSemaphores(VkDevice d, const VkSemaphoreWaitInfo *info, uint64_t timeout) {
    PFN_vkWaitSemaphores next = (PFN_vkWaitSemaphores)dev_for(d)->gdpa(d, "vkWaitSemaphores");
    if (!next) next = (PFN_vkWaitSemaphores)dev_for(d)->gdpa(d, "vkWaitSemaphoresKHR");
    return next(d, info, timeout);
}

static VkResult VKAPI_CALL GetSemaphoreCounterValue(VkDevice d, VkSemaphore s, uint64_t *value) {
    PFN_vkGetSemaphoreCounterValue next = (PFN_vkGetSemaphoreCounterValue)dev_for(d)->gdpa(d, "vkGetSemaphoreCounterValue");
    if (!next) next = (PFN_vkGetSemaphoreCounterValue)dev_for(d)->gdpa(d, "vkGetSemaphoreCounterValueKHR");
    return next(d, s, value);
}

static VkResult VKAPI_CALL CreateImage(VkDevice d, const VkImageCreateInfo *ci, const VkAllocationCallbacks *a, VkImage *out) {
    VkImageCreateInfo compatible = *ci;
    VkExternalMemoryImageCreateInfo shared;
    const VkBaseInStructure *first = ci->pNext;
    // SteamVR's OpenXR stereo arrays omit a format list, while DXVK adds one on import.
    // Leave single-layer OpenVR images alone: the application owns their export layout.
    if (dev_for(d)->quest_images && ci->arrayLayers > 1 && (ci->flags & VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT) && ci->tiling == VK_IMAGE_TILING_OPTIMAL
        && first && first->sType == VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO) {
        const VkBaseInStructure *second = first->pNext;
        if (second && second->sType == VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO
            && ((const VkExternalMemoryImageCreateInfo *)second)->handleTypes == VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT) {
            compatible.pNext = second; ci = &compatible;
            say("%s", "matching opaque shared image layout to SteamVR's unrestricted format list");
        }
    }
    if (dev_for(d)->quest_images && ci->arrayLayers > 1 && (ci->flags & VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT) && ci->tiling == VK_IMAGE_TILING_OPTIMAL
        && first && first->sType == VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO) {
        const VkExternalMemoryImageCreateInfo *external = (const void *)first;
        const VkBaseInStructure *second = external->pNext;
        if (external->handleTypes == VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT && second
            && second->sType == VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO) {
            shared = *external; shared.pNext = second->pNext; compatible.pNext = &shared; ci = &compatible;
            say("%s", "matching opaque shared image layout to SteamVR's unrestricted format list");
        }
    }
    VkResult r = NEXT(d, CreateImage)(d, ci, a, out);
    if (!sem_trace_on()) return r;
    const VkExternalMemoryImageCreateInfo *ext = NULL;
    const VkImageFormatListCreateInfo *list = NULL;
    for (const VkBaseInStructure *p = ci->pNext; p; p = p->pNext) {
        if (p->sType == VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO) ext = (const void *)p;
        if (p->sType == VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO) list = (const void *)p;
    }
    if (!ext) return r;
    char line[256];
    int n = snprintf(line, sizeof(line), "%s image %ux%u format %d flags 0x%x usage 0x%x tiling %d layers %u mips %u samples %u handles 0x%x list",
                     program_invocation_short_name, ci->extent.width, ci->extent.height, ci->format, ci->flags, ci->usage,
                     ci->tiling, ci->arrayLayers, ci->mipLevels, ci->samples, ext->handleTypes);
    for (uint32_t i = 0; list && i < list->viewFormatCount && n < (int)sizeof(line) - 8; i++)
        n += snprintf(line + n, sizeof(line) - n, " %d", list->pViewFormats[i]);
    for (const VkBaseInStructure *p = ci->pNext; p && n < (int)sizeof(line) - 16; p = p->pNext)
        n += snprintf(line + n, sizeof(line) - n, " chain:%d", p->sType);
    say("%s", line);
    return r;
}

static PFN_vkVoidFunction semaphore_hook(const char *name) {
    HOOK_AS("vkCreateImage", CreateImage)
    HOOK_AS("vkCreateSemaphore", CreateSemaphore)
    HOOK_AS("vkDestroySemaphore", DestroySemaphore)
    HOOK_AS("vkGetSemaphoreFdKHR", GetSemaphoreFdKHR)
    HOOK_AS("vkImportSemaphoreFdKHR", ImportSemaphoreFdKHR)
    HOOK_AS("vkQueueSubmit", QueueSubmit)
    HOOK_AS("vkQueueSubmit2", QueueSubmit2)
    HOOK_AS("vkQueueSubmit2KHR", QueueSubmit2)
    HOOK_AS("vkQueuePresentKHR", QueuePresentKHR)
    HOOK_AS("vkQueueBindSparse", QueueBindSparse)
    HOOK_AS("vkQueueWaitIdle", QueueWaitIdle)
    HOOK_AS("vkDeviceWaitIdle", DeviceWaitIdle)
    HOOK_AS("vkWaitSemaphores", WaitSemaphores)
    HOOK_AS("vkWaitSemaphoresKHR", WaitSemaphores)
    HOOK_AS("vkGetSemaphoreCounterValue", GetSemaphoreCounterValue)
    HOOK_AS("vkGetSemaphoreCounterValueKHR", GetSemaphoreCounterValue)
    return NULL;
}


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
    if (in && !in->active) return in->gipa(instance, name);
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
    if (d && !d->active) return d->gdpa(device, name);
    if (d && d->display) {
        PFN_vkVoidFunction f = display_device_hook(name);
        if (f) return f;
    }
    if (d) {
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
