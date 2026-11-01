// VK_LAYER_DROIDDECK_gpu_load: this process's GPU busy time for the HUD where Android keeps the GPU load from apps
// (kgsl sysfs SELinux-blocked, perfcounter reads refused while /sys/class/kgsl/kgsl-3d0/perfcounter is 0).
// The process total in nanoseconds goes to $DROIDDECK_GPU_LOAD_DIR/<pid>; nothing is timed while
// $DROIDDECK_GPU_LOAD_DIR/on is missing (the app removes it while no HUD is shown).
#define _GNU_SOURCE
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include "gpuload_ring.h"

#define EXPORT __attribute__((visibility("default")))
#define MAX_FAMILIES 32
#define WANTED_CHECK_NS 500000000ull
#define WRITE_EVERY_NS 100000000ull

static const char *g_dir;

static void say(const char *what) {
    // FEX leaves program_invocation_short_name empty for x86 programs.
    fprintf(stderr, "[droiddeck-gpu-load] %s (pid %d): %s\n", program_invocation_short_name, (int)getpid(), what);
}

__attribute__((constructor)) static void init(void) {
    const char *dir = getenv("DROIDDECK_GPU_LOAD_DIR");
    g_dir = dir && *dir ? dir : NULL;
}

static uint64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

struct inst {
    struct inst *next_in_list;
    VkInstance handle;
    PFN_vkGetInstanceProcAddr gipa;
    PFN_vkDestroyInstance destroy;
    PFN_vkGetPhysicalDeviceProperties props;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties families;
};

struct dev {
    struct dev *next_in_list;
    VkDevice handle;
    PFN_vkGetDeviceProcAddr gdpa;
    PFN_vkDestroyDevice destroy;
    PFN_vkGetDeviceQueue get_queue;
    PFN_vkGetDeviceQueue2 get_queue2;
    PFN_vkQueueSubmit submit;
    PFN_vkQueueSubmit2 submit2;
    PFN_vkQueueSubmit2 submit2_khr;
    struct gpuload_vk vk;
    uint32_t family_count;
    uint32_t timestamp_bits[MAX_FAMILIES];
};

struct queue {
    struct queue *next_in_list;
    VkQueue handle;
    struct dev *dev;
    uint32_t family;
    uint32_t epoch;
    struct gpuload_ring ring;
};

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static struct inst *g_insts;
static struct dev *g_devs;
static struct queue *g_queues;

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

static struct queue *queue_for(VkQueue handle) {
    struct queue *r;
    pthread_mutex_lock(&g_lock);
    for (r = g_queues; r && r->handle != handle; r = r->next_in_list) {}
    pthread_mutex_unlock(&g_lock);
    return r;
}

static void note_queue(struct dev *d, VkQueue handle, uint32_t family) {
    if (!handle || queue_for(handle)) return;
    struct queue *q = calloc(1, sizeof(*q));
    if (!q) return;
    q->handle = handle;
    q->dev = d;
    q->family = family;
    pthread_mutex_lock(&g_lock);
    q->next_in_list = g_queues;
    g_queues = q;
    pthread_mutex_unlock(&g_lock);
}

// ---- process total ---------------------------------------------------------------------------------------------

static _Atomic uint64_t g_busy_ns;
static _Atomic uint64_t g_wanted_at;
static _Atomic int g_wanted;
static _Atomic uint32_t g_epoch = 1;  // bumped each time the HUD comes back
static pthread_mutex_t g_write_lock = PTHREAD_MUTEX_INITIALIZER;
static uint64_t g_written_at;
static int g_fd = -1;
static pid_t g_fd_pid;
static int g_announced;

static int wanted(uint64_t now) {
    uint64_t at = atomic_load(&g_wanted_at);
    if (at && now - at < WANTED_CHECK_NS) return atomic_load(&g_wanted);
    atomic_store(&g_wanted_at, now);
    char path[512];
    snprintf(path, sizeof(path), "%s/on", g_dir);
    int on = access(path, F_OK) == 0;
    if (on && !atomic_exchange(&g_wanted, on)) atomic_fetch_add(&g_epoch, 1);
    if (!on) atomic_store(&g_wanted, 0);
    return on;
}

static void publish(uint64_t now) {
    if (pthread_mutex_trylock(&g_write_lock)) return;
    if (now - g_written_at >= WRITE_EVERY_NS) {
        g_written_at = now;
        pid_t pid = getpid();
        struct stat st;
        // The app deletes files that stopped changing; a forked child needs its own.
        if (g_fd >= 0 && (g_fd_pid != pid || fstat(g_fd, &st) || st.st_nlink == 0)) { close(g_fd); g_fd = -1; }
        if (g_fd < 0) {
            char path[512];
            snprintf(path, sizeof(path), "%s/%d", g_dir, (int)pid);
            g_fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
            g_fd_pid = pid;
        }
        if (g_fd >= 0) {
            char line[80];
            int n = snprintf(line, sizeof(line), "%20llu %-32.32s\n", (unsigned long long)atomic_load(&g_busy_ns),
                             program_invocation_short_name);
            if (pwrite(g_fd, line, (size_t)n, 0) != n) { close(g_fd); g_fd = -1; }
        }
    }
    pthread_mutex_unlock(&g_write_lock);
}

// The queue to time this submit on, or NULL to pass it through.
static struct queue *measuring(VkQueue handle) {
    if (!g_dir) return NULL;
    struct queue *q = queue_for(handle);
    if (!q) return NULL;
    uint64_t now = now_ns();
    if (!wanted(now)) return NULL;
    struct dev *d = q->dev;
    if (q->ring.state == 0) {
        uint32_t bits = q->family < d->family_count ? d->timestamp_bits[q->family] : 0;
        if (gpuload_ring_setup(&q->ring, &d->vk, q->family, bits)) {
            if (!__atomic_exchange_n(&g_announced, 1, __ATOMIC_RELAXED)) say("timing this process's GPU work for the HUD");
        } else {
            say(bits ? "cannot set up GPU timing on this queue" : "this queue family has no timestamps");
        }
        q->epoch = atomic_load(&g_epoch);
    }
    if (q->ring.state != 1) return NULL;
    uint64_t ns = gpuload_ring_harvest(&q->ring, &d->vk);
    uint32_t epoch = atomic_load(&g_epoch);
    if (q->epoch == epoch) atomic_fetch_add(&g_busy_ns, ns);
    else q->epoch = epoch;  // timed before the HUD went away
    publish(now);
    return q;
}

static VkResult VKAPI_CALL QueueSubmit(VkQueue queue, uint32_t count, const VkSubmitInfo *infos, VkFence fence) {
    struct dev *d = dev_for(queue);
    if (!d) return VK_ERROR_DEVICE_LOST;
    struct queue *q = count ? measuring(queue) : NULL;
    uint32_t used = 0;
    VkSubmitInfo *mine = q ? gpuload_ring_wrap(&q->ring, count, infos, &used) : NULL;
    VkResult r = d->submit(queue, count, mine ? mine : infos, fence);
    if (mine && r != VK_SUCCESS) gpuload_ring_unwind(&q->ring, used);
    free(mine);
    return r;
}

static VkResult submit2(PFN_vkQueueSubmit2 next, VkQueue queue, uint32_t count, const VkSubmitInfo2 *infos, VkFence fence) {
    struct queue *q = count ? measuring(queue) : NULL;
    uint32_t used = 0;
    VkSubmitInfo2 *mine = q ? gpuload_ring_wrap2(&q->ring, count, infos, &used) : NULL;
    VkResult r = next(queue, count, mine ? mine : infos, fence);
    if (mine && r != VK_SUCCESS) gpuload_ring_unwind(&q->ring, used);
    free(mine);
    return r;
}

static VkResult VKAPI_CALL QueueSubmit2(VkQueue queue, uint32_t count, const VkSubmitInfo2 *infos, VkFence fence) {
    struct dev *d = dev_for(queue);
    return d ? submit2(d->submit2, queue, count, infos, fence) : VK_ERROR_DEVICE_LOST;
}

static VkResult VKAPI_CALL QueueSubmit2KHR(VkQueue queue, uint32_t count, const VkSubmitInfo2 *infos, VkFence fence) {
    struct dev *d = dev_for(queue);
    return d ? submit2(d->submit2_khr, queue, count, infos, fence) : VK_ERROR_DEVICE_LOST;
}

static void VKAPI_CALL GetDeviceQueue(VkDevice device, uint32_t family, uint32_t index, VkQueue *out) {
    struct dev *d = dev_for(device);
    if (!d) return;
    d->get_queue(device, family, index, out);
    if (out && *out) note_queue(d, *out, family);
}

static void VKAPI_CALL GetDeviceQueue2(VkDevice device, const VkDeviceQueueInfo2 *info, VkQueue *out) {
    struct dev *d = dev_for(device);
    if (!d) return;
    d->get_queue2(device, info, out);
    if (out && *out) note_queue(d, *out, info->queueFamilyIndex);
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
    in->props = (PFN_vkGetPhysicalDeviceProperties)next_gipa(*out, "vkGetPhysicalDeviceProperties");
    in->families = (PFN_vkGetPhysicalDeviceQueueFamilyProperties)next_gipa(*out, "vkGetPhysicalDeviceQueueFamilyProperties");
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
    VkLayerDeviceCreateInfo *callback = device_chain(ci, VK_LOADER_DATA_CALLBACK);
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
#define LOAD(field, type, name) d->field = (type)next_gdpa(*out, name)
    LOAD(destroy, PFN_vkDestroyDevice, "vkDestroyDevice");
    LOAD(get_queue, PFN_vkGetDeviceQueue, "vkGetDeviceQueue");
    LOAD(get_queue2, PFN_vkGetDeviceQueue2, "vkGetDeviceQueue2");
    LOAD(submit, PFN_vkQueueSubmit, "vkQueueSubmit");
    LOAD(submit2, PFN_vkQueueSubmit2, "vkQueueSubmit2");
    LOAD(submit2_khr, PFN_vkQueueSubmit2, "vkQueueSubmit2KHR");
    LOAD(vk.create_pool, PFN_vkCreateCommandPool, "vkCreateCommandPool");
    LOAD(vk.destroy_pool, PFN_vkDestroyCommandPool, "vkDestroyCommandPool");
    LOAD(vk.alloc_cmds, PFN_vkAllocateCommandBuffers, "vkAllocateCommandBuffers");
    LOAD(vk.begin, PFN_vkBeginCommandBuffer, "vkBeginCommandBuffer");
    LOAD(vk.end, PFN_vkEndCommandBuffer, "vkEndCommandBuffer");
    LOAD(vk.cmd_reset, PFN_vkCmdResetQueryPool, "vkCmdResetQueryPool");
    LOAD(vk.cmd_timestamp, PFN_vkCmdWriteTimestamp, "vkCmdWriteTimestamp");
    LOAD(vk.create_queries, PFN_vkCreateQueryPool, "vkCreateQueryPool");
    LOAD(vk.destroy_queries, PFN_vkDestroyQueryPool, "vkDestroyQueryPool");
    LOAD(vk.results, PFN_vkGetQueryPoolResults, "vkGetQueryPoolResults");
#undef LOAD
    d->vk.device = *out;
    d->vk.set_data = callback ? (gpuload_set_data_fn)callback->u.pfnSetDeviceLoaderData : NULL;
    if (in && in->props && in->families) {
        VkPhysicalDeviceProperties props;
        in->props(pd, &props);
        gpuload_vk_period(&d->vk, props.limits.timestampPeriod);
        uint32_t n = 0;
        in->families(pd, &n, NULL);
        VkQueueFamilyProperties families[MAX_FAMILIES];
        if (n > MAX_FAMILIES) n = MAX_FAMILIES;
        in->families(pd, &n, families);
        d->family_count = n;
        for (uint32_t i = 0; i < n; i++) d->timestamp_bits[i] = families[i].timestampValidBits;
    }
    // Without the loader callback our command buffers would lack the loader's dispatch pointer.
    if (!d->vk.set_data) d->family_count = 0;
    pthread_mutex_lock(&g_lock);
    d->next_in_list = g_devs;
    g_devs = d;
    pthread_mutex_unlock(&g_lock);
    return VK_SUCCESS;
}

static void VKAPI_CALL DestroyDevice(VkDevice device, const VkAllocationCallbacks *alloc) {
    struct dev *d = dev_for(device);
    if (!d) return;
    struct queue *mine = NULL;
    pthread_mutex_lock(&g_lock);
    for (struct queue **p = &g_queues; *p;) {
        struct queue *q = *p;
        if (q->dev == d) { *p = q->next_in_list; q->next_in_list = mine; mine = q; } else p = &q->next_in_list;
    }
    for (struct dev **p = &g_devs; *p; p = &(*p)->next_in_list)
        if (*p == d) { *p = d->next_in_list; break; }
    pthread_mutex_unlock(&g_lock);
    while (mine) {
        struct queue *q = mine;
        mine = q->next_in_list;
        gpuload_ring_release(&q->ring, &d->vk);
        free(q);
    }
    PFN_vkDestroyDevice destroy = d->destroy;
    free(d);
    if (destroy) destroy(device, alloc);
}

// ---- dispatch --------------------------------------------------------------------------------------------------

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL DroidDeckGpuLoad_GetDeviceProcAddr(VkDevice device, const char *name);

static PFN_vkVoidFunction device_hook(struct dev *d, const char *name) {
    if (!g_dir) return NULL;
    if (!strcmp(name, "vkGetDeviceQueue") && d->get_queue) return (PFN_vkVoidFunction)GetDeviceQueue;
    if (!strcmp(name, "vkGetDeviceQueue2") && d->get_queue2) return (PFN_vkVoidFunction)GetDeviceQueue2;
    if (!strcmp(name, "vkQueueSubmit") && d->submit) return (PFN_vkVoidFunction)QueueSubmit;
    if (!strcmp(name, "vkQueueSubmit2") && d->submit2) return (PFN_vkVoidFunction)QueueSubmit2;
    if (!strcmp(name, "vkQueueSubmit2KHR") && d->submit2_khr) return (PFN_vkVoidFunction)QueueSubmit2KHR;
    return NULL;
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL DroidDeckGpuLoad_GetInstanceProcAddr(VkInstance instance, const char *name) {
    if (!strcmp(name, "vkCreateInstance")) return (PFN_vkVoidFunction)CreateInstance;
    if (!strcmp(name, "vkDestroyInstance")) return (PFN_vkVoidFunction)DestroyInstance;
    if (!strcmp(name, "vkCreateDevice")) return (PFN_vkVoidFunction)CreateDevice;
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (PFN_vkVoidFunction)DroidDeckGpuLoad_GetInstanceProcAddr;
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)DroidDeckGpuLoad_GetDeviceProcAddr;
    if (!instance) return NULL;
    struct inst *in = inst_for(instance);
    return in ? in->gipa(instance, name) : NULL;
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL DroidDeckGpuLoad_GetDeviceProcAddr(VkDevice device, const char *name) {
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)DroidDeckGpuLoad_GetDeviceProcAddr;
    if (!strcmp(name, "vkDestroyDevice")) return (PFN_vkVoidFunction)DestroyDevice;
    struct dev *d = dev_for(device);
    if (!d) return NULL;
    PFN_vkVoidFunction mine = device_hook(d, name);
    return mine ? mine : d->gdpa(device, name);
}

EXPORT VKAPI_ATTR VkResult VKAPI_CALL DroidDeckGpuLoad_NegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface *v) {
    if (v->loaderLayerInterfaceVersion > 2) v->loaderLayerInterfaceVersion = 2;
    v->pfnGetInstanceProcAddr = DroidDeckGpuLoad_GetInstanceProcAddr;
    v->pfnGetDeviceProcAddr = DroidDeckGpuLoad_GetDeviceProcAddr;
    v->pfnGetPhysicalDeviceProcAddr = NULL;
    return VK_SUCCESS;
}
