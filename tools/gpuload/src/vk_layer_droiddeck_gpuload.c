// VK_LAYER_DROIDDECK_gpu_load: measures how long this process keeps the GPU busy, for the HUD on devices where
// Android keeps the GPU load from apps (the kgsl sysfs nodes are SELinux-blocked and kgsl refuses perfcounter
// reads while /sys/class/kgsl/kgsl-3d0/perfcounter is 0).
//
// Each submit batch that has command buffers gets one of our command buffers in front (resets a query pair and
// writes a TOP_OF_PIPE timestamp) and one behind (BOTTOM_OF_PIPE timestamp). Finished pairs are read back without
// waiting on later submits, overlapping time is counted once per device, and the process total in nanoseconds is
// written to $DROIDDECK_GPU_LOAD_DIR/<pid>. The app turns the totals of all processes into a percentage.
// Nothing is added while $DROIDDECK_GPU_LOAD_DIR/on is missing (the app removes it while no HUD is shown).
// The start timestamp sits after the batch's semaphore waits only because Turnip on kgsl holds a whole batch until
// its waits signal; a driver that starts TOP_OF_PIPE work before the waits would count the waiting time as busy.
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

#include "gpuload_busy.h"

#define EXPORT __attribute__((visibility("default")))
#define RING 64
#define MAX_FAMILIES 32
#define WANTED_CHECK_NS 500000000ull
#define WRITE_EVERY_NS 100000000ull
#define LONGEST_SUBMIT_NS 2000000000.0

static const char *g_dir;

static void say(const char *what) {
    fprintf(stderr, "[droiddeck-gpu-load] %s: %s\n", program_invocation_short_name, what);
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
    PFN_vkSetDeviceLoaderData set_data;
    PFN_vkDestroyDevice destroy;
    PFN_vkGetDeviceQueue get_queue;
    PFN_vkGetDeviceQueue2 get_queue2;
    PFN_vkQueueSubmit submit;
    PFN_vkQueueSubmit2 submit2;
    PFN_vkQueueSubmit2 submit2_khr;
    PFN_vkCreateCommandPool create_pool;
    PFN_vkDestroyCommandPool destroy_pool;
    PFN_vkAllocateCommandBuffers alloc_cmds;
    PFN_vkBeginCommandBuffer begin;
    PFN_vkEndCommandBuffer end;
    PFN_vkCmdResetQueryPool cmd_reset;
    PFN_vkCmdWriteTimestamp cmd_timestamp;
    PFN_vkCreateQueryPool create_queries;
    PFN_vkDestroyQueryPool destroy_queries;
    PFN_vkGetQueryPoolResults results;
    double period_ns;
    uint64_t max_ticks;
    uint32_t family_count;
    uint32_t timestamp_bits[MAX_FAMILIES];
    pthread_mutex_t lock;
    struct gpuload_busy busy;
};

struct slot {
    VkCommandBuffer start, end;
    uint64_t last_end;
};

// Submits to one queue are externally synchronized by the app, so a queue's ring needs no lock of its own.
struct queue {
    struct queue *next_in_list;
    VkQueue handle;
    struct dev *dev;
    uint32_t family;
    int state;  // 0 not set up, 1 measuring, -1 cannot measure
    VkCommandPool pool;
    VkQueryPool queries;
    uint64_t mask;
    uint32_t next, oldest, inflight;
    struct slot slot[RING];
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
    atomic_store(&g_wanted, on);
    return on;
}

static void publish(uint64_t now) {
    if (pthread_mutex_trylock(&g_write_lock)) return;
    if (now - g_written_at >= WRITE_EVERY_NS) {
        g_written_at = now;
        pid_t pid = getpid();
        struct stat st;
        // A forked child writes its own file; a file the app removed as stale is created again.
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

// ---- per-queue timestamp ring --------------------------------------------------------------------------------

static void queue_release(struct queue *q) {
    struct dev *d = q->dev;
    if (q->queries) d->destroy_queries(d->handle, q->queries, NULL);
    if (q->pool) d->destroy_pool(d->handle, q->pool, NULL);
    q->queries = VK_NULL_HANDLE;
    q->pool = VK_NULL_HANDLE;
}

static int record_slot(struct dev *d, struct queue *q, uint32_t i) {
    const VkCommandBufferBeginInfo begin = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    struct slot *s = &q->slot[i];
    if (d->begin(s->start, &begin) != VK_SUCCESS) return 0;
    d->cmd_reset(s->start, q->queries, 2 * i, 2);
    d->cmd_timestamp(s->start, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, q->queries, 2 * i);
    if (d->end(s->start) != VK_SUCCESS) return 0;
    if (d->begin(s->end, &begin) != VK_SUCCESS) return 0;
    d->cmd_timestamp(s->end, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, q->queries, 2 * i + 1);
    return d->end(s->end) == VK_SUCCESS;
}

static void queue_setup(struct queue *q) {
    struct dev *d = q->dev;
    q->state = -1;
    if (!d->set_data || q->family >= d->family_count || !d->timestamp_bits[q->family]) return;
    uint32_t bits = d->timestamp_bits[q->family];
    q->mask = bits >= 64 ? ~0ull : (1ull << bits) - 1;
    const VkCommandPoolCreateInfo pool = {.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, .queueFamilyIndex = q->family};
    const VkQueryPoolCreateInfo queries = {.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO,
                                           .queryType = VK_QUERY_TYPE_TIMESTAMP, .queryCount = 2 * RING};
    VkCommandBuffer cmds[2 * RING];
    const VkCommandBufferAllocateInfo alloc = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                                               .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 2 * RING};
    if (d->create_pool(d->handle, &pool, NULL, &q->pool) != VK_SUCCESS) { q->pool = VK_NULL_HANDLE; goto fail; }
    if (d->create_queries(d->handle, &queries, NULL, &q->queries) != VK_SUCCESS) { q->queries = VK_NULL_HANDLE; goto fail; }
    VkCommandBufferAllocateInfo mine = alloc;
    mine.commandPool = q->pool;
    if (d->alloc_cmds(d->handle, &mine, cmds) != VK_SUCCESS) goto fail;
    for (uint32_t i = 0; i < 2 * RING; i++)
        if (d->set_data(d->handle, cmds[i]) != VK_SUCCESS) goto fail;
    for (uint32_t i = 0; i < RING; i++) {
        q->slot[i].start = cmds[2 * i];
        q->slot[i].end = cmds[2 * i + 1];
        if (!record_slot(d, q, i)) goto fail;
    }
    q->state = 1;
    if (!__atomic_exchange_n(&g_announced, 1, __ATOMIC_RELAXED)) say("measuring this process's GPU busy time for the HUD");
    return;
fail:
    say("cannot time this queue; leaving it alone");
    queue_release(q);
    q->state = -1;
}

// Slots are used in ring order and a queue runs its batches in order, so the oldest slot finishes first.
static void harvest(struct queue *q) {
    struct dev *d = q->dev;
    while (q->inflight) {
        struct slot *s = &q->slot[q->oldest];
        uint64_t r[4] = {0, 0, 0, 0};
        VkResult res = d->results(d->handle, q->queries, 2 * q->oldest, 2, sizeof(r), r, 2 * sizeof(uint64_t),
                                  VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
        if (res != VK_SUCCESS && res != VK_NOT_READY) return;
        uint64_t start = r[0] & q->mask, end = r[2] & q->mask;
        if (!gpuload_result_is_new(r[1], r[3], end, s->last_end)) return;
        s->last_end = end;
        q->oldest = (q->oldest + 1) % RING;
        q->inflight--;
        pthread_mutex_lock(&d->lock);
        uint64_t before = d->busy.busy_ticks;
        gpuload_add_interval(&d->busy, start, end, d->max_ticks);
        uint64_t added = d->busy.busy_ticks - before;
        pthread_mutex_unlock(&d->lock);
        if (added) atomic_fetch_add(&g_busy_ns, (uint64_t)((double)added * d->period_ns));
    }
}

// Common entry for every submit: returns the queue to wrap batches on, or NULL to pass the submit through.
static struct queue *measuring(VkQueue handle) {
    if (!g_dir) return NULL;
    struct queue *q = queue_for(handle);
    if (!q) return NULL;
    uint64_t now = now_ns();
    if (!wanted(now)) return NULL;
    if (q->state == 0) queue_setup(q);
    if (q->state != 1) return NULL;
    harvest(q);
    publish(now);
    return q;
}

static int plain_batch(const void *next) {
    for (const VkBaseInStructure *s = next; s; s = s->pNext) {
        if (s->sType == VK_STRUCTURE_TYPE_DEVICE_GROUP_SUBMIT_INFO) return 0;
        if (s->sType == VK_STRUCTURE_TYPE_PROTECTED_SUBMIT_INFO && ((const VkProtectedSubmitInfo *)s)->protectedSubmit)
            return 0;
    }
    return 1;
}

static void unwind(struct queue *q, uint32_t used) {
    q->next = (q->next + RING - used) % RING;
    q->inflight -= used;
}

static VkResult VKAPI_CALL QueueSubmit(VkQueue queue, uint32_t count, const VkSubmitInfo *infos, VkFence fence) {
    struct dev *d = dev_for(queue);
    if (!d) return VK_ERROR_DEVICE_LOST;
    struct queue *q = count ? measuring(queue) : NULL;
    if (!q) return d->submit(queue, count, infos, fence);
    size_t cmds = 0;
    for (uint32_t i = 0; i < count; i++) cmds += infos[i].commandBufferCount + 2;
    VkSubmitInfo *mine = malloc(count * sizeof(*mine) + cmds * sizeof(VkCommandBuffer));
    if (!mine) return d->submit(queue, count, infos, fence);
    VkCommandBuffer *list = (VkCommandBuffer *)(mine + count);
    uint32_t used = 0;
    for (uint32_t i = 0; i < count; i++) {
        mine[i] = infos[i];
        if (!infos[i].commandBufferCount || !plain_batch(infos[i].pNext) || q->inflight >= RING) continue;
        struct slot *s = &q->slot[q->next];
        VkCommandBuffer *cb = list;
        cb[0] = s->start;
        memcpy(cb + 1, infos[i].pCommandBuffers, infos[i].commandBufferCount * sizeof(VkCommandBuffer));
        cb[infos[i].commandBufferCount + 1] = s->end;
        mine[i].pCommandBuffers = cb;
        mine[i].commandBufferCount = infos[i].commandBufferCount + 2;
        list += infos[i].commandBufferCount + 2;
        q->next = (q->next + 1) % RING;
        q->inflight++;
        used++;
    }
    VkResult r = d->submit(queue, count, mine, fence);
    if (r != VK_SUCCESS) unwind(q, used);
    free(mine);
    return r;
}

static VkResult submit2(PFN_vkQueueSubmit2 next, VkQueue queue, uint32_t count, const VkSubmitInfo2 *infos, VkFence fence) {
    struct queue *q = count ? measuring(queue) : NULL;
    if (!q) return next(queue, count, infos, fence);
    size_t cmds = 0;
    for (uint32_t i = 0; i < count; i++) cmds += infos[i].commandBufferInfoCount + 2;
    VkSubmitInfo2 *mine = malloc(count * sizeof(*mine) + cmds * sizeof(VkCommandBufferSubmitInfo));
    if (!mine) return next(queue, count, infos, fence);
    VkCommandBufferSubmitInfo *list = (VkCommandBufferSubmitInfo *)(mine + count);
    uint32_t used = 0;
    for (uint32_t i = 0; i < count; i++) {
        mine[i] = infos[i];
        uint32_t n = infos[i].commandBufferInfoCount;
        if (!n || (infos[i].flags & VK_SUBMIT_PROTECTED_BIT) || !plain_batch(infos[i].pNext) || q->inflight >= RING) continue;
        struct slot *s = &q->slot[q->next];
        VkCommandBufferSubmitInfo *cb = list;
        cb[0] = (VkCommandBufferSubmitInfo){.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO, .commandBuffer = s->start};
        memcpy(cb + 1, infos[i].pCommandBufferInfos, n * sizeof(VkCommandBufferSubmitInfo));
        cb[n + 1] = (VkCommandBufferSubmitInfo){.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO, .commandBuffer = s->end};
        mine[i].pCommandBufferInfos = cb;
        mine[i].commandBufferInfoCount = n + 2;
        list += n + 2;
        q->next = (q->next + 1) % RING;
        q->inflight++;
        used++;
    }
    VkResult r = next(queue, count, mine, fence);
    if (r != VK_SUCCESS) unwind(q, used);
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
    d->set_data = callback ? callback->u.pfnSetDeviceLoaderData : NULL;
#define LOAD(field, type, name) d->field = (type)next_gdpa(*out, name)
    LOAD(destroy, PFN_vkDestroyDevice, "vkDestroyDevice");
    LOAD(get_queue, PFN_vkGetDeviceQueue, "vkGetDeviceQueue");
    LOAD(get_queue2, PFN_vkGetDeviceQueue2, "vkGetDeviceQueue2");
    LOAD(submit, PFN_vkQueueSubmit, "vkQueueSubmit");
    LOAD(submit2, PFN_vkQueueSubmit2, "vkQueueSubmit2");
    LOAD(submit2_khr, PFN_vkQueueSubmit2, "vkQueueSubmit2KHR");
    LOAD(create_pool, PFN_vkCreateCommandPool, "vkCreateCommandPool");
    LOAD(destroy_pool, PFN_vkDestroyCommandPool, "vkDestroyCommandPool");
    LOAD(alloc_cmds, PFN_vkAllocateCommandBuffers, "vkAllocateCommandBuffers");
    LOAD(begin, PFN_vkBeginCommandBuffer, "vkBeginCommandBuffer");
    LOAD(end, PFN_vkEndCommandBuffer, "vkEndCommandBuffer");
    LOAD(cmd_reset, PFN_vkCmdResetQueryPool, "vkCmdResetQueryPool");
    LOAD(cmd_timestamp, PFN_vkCmdWriteTimestamp, "vkCmdWriteTimestamp");
    LOAD(create_queries, PFN_vkCreateQueryPool, "vkCreateQueryPool");
    LOAD(destroy_queries, PFN_vkDestroyQueryPool, "vkDestroyQueryPool");
    LOAD(results, PFN_vkGetQueryPoolResults, "vkGetQueryPoolResults");
#undef LOAD
    pthread_mutex_init(&d->lock, NULL);
    if (in && in->props && in->families) {
        VkPhysicalDeviceProperties props;
        in->props(pd, &props);
        d->period_ns = props.limits.timestampPeriod;
        uint32_t n = 0;
        in->families(pd, &n, NULL);
        VkQueueFamilyProperties families[MAX_FAMILIES];
        if (n > MAX_FAMILIES) n = MAX_FAMILIES;
        in->families(pd, &n, families);
        d->family_count = n;
        for (uint32_t i = 0; i < n; i++) d->timestamp_bits[i] = families[i].timestampValidBits;
    }
    // Without these the device is passed through untouched.
    if (d->period_ns <= 0 || !d->create_pool || !d->alloc_cmds || !d->begin || !d->end || !d->cmd_reset ||
        !d->cmd_timestamp || !d->create_queries || !d->results || !d->destroy_pool || !d->destroy_queries)
        d->family_count = 0;
    d->max_ticks = d->period_ns > 0 ? (uint64_t)(LONGEST_SUBMIT_NS / d->period_ns) : 0;
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
    while (mine) { struct queue *q = mine; mine = q->next_in_list; queue_release(q); free(q); }
    PFN_vkDestroyDevice destroy = d->destroy;
    pthread_mutex_destroy(&d->lock);
    free(d);
    if (destroy) destroy(device, alloc);
}

// ---- dispatch --------------------------------------------------------------------------------------------------

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL DroidDeckGpuLoad_GetDeviceProcAddr(VkDevice device, const char *name);

static PFN_vkVoidFunction device_hook(struct dev *d, const char *name) {
    if (!g_dir || !d) return NULL;
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
