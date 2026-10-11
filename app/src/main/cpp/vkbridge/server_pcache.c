/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Persistent pipeline cache. Mali compiles every pipeline from scratch in each new process unless
 * it is handed a VkPipelineCache; games through DXVK pass none. With a cache directory set
 * (--cache-dir), each device gets a cache loaded from <dir>/<vendor>-<device>-<cacheUUID>.vkpc:
 *   - pipelines created without a cache use it;
 *   - caches the app creates empty start from it, and are merged back into it when destroyed;
 *   - it is written back every few seconds while new pipelines come in (the app stops the server
 *     with SIGKILL, so there is no reliable last chance) and when the device goes away.
 * The file's header is checked against the GPU before the driver sees it.
 */
#define _GNU_SOURCE
#include "vkb_server.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define PC_MAX_FILE (256u << 20)
#define PC_SAVE_PERIOD_S 10

typedef struct vkb_pcache {
    vkb_srv_table *t;
    VkPipelineCache cache;
    char path[512];
    int dirty;
    size_t saved_size;
    PFN_vkCreateGraphicsPipelines create_gfx;
    PFN_vkCreateComputePipelines create_compute;
    PFN_vkCreatePipelineCache create_cache;
    PFN_vkDestroyPipelineCache destroy_cache;
    struct vkb_pcache *next;
} vkb_pcache;

static const char *pc_dir;
static pthread_mutex_t pc_lock = PTHREAD_MUTEX_INITIALIZER;
static vkb_pcache *pc_list;
static int pc_thread_started;

void vkb_pcache_set_dir(const char *dir)
{
    if (!dir || !*dir) return;
    mkdir(dir, 0700);
    pc_dir = dir;
    VKB_INFO("pipeline cache directory %s", dir);
}

static vkb_pcache *cur(void)
{
    vkb_srv_table *t = vkb_srv_current_table();
    return t ? t->pcache : NULL;
}

/* The header every pipeline cache starts with (VkPipelineCacheHeaderVersionOne). */
static int header_ok(const vkb_srv_table *t, const uint8_t *data, size_t size)
{
    VkPipelineCacheHeaderVersionOne h;
    if (size < sizeof(h)) return 0;
    memcpy(&h, data, sizeof(h));
    VkPhysicalDeviceProperties props;
    t->real.vkGetPhysicalDeviceProperties(t->physical, &props);
    return h.headerSize >= sizeof(h) && h.headerVersion == VK_PIPELINE_CACHE_HEADER_VERSION_ONE &&
           h.vendorID == props.vendorID && h.deviceID == props.deviceID &&
           !memcmp(h.pipelineCacheUUID, props.pipelineCacheUUID, VK_UUID_SIZE);
}

static uint8_t *read_file(const char *path, size_t *size)
{
    *size = 0;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return NULL;
    struct stat st;
    uint8_t *buf = NULL;
    if (fstat(fd, &st) == 0 && st.st_size > 0 && (uint64_t)st.st_size <= PC_MAX_FILE && (buf = malloc((size_t)st.st_size))) {
        size_t got = 0;
        while (got < (size_t)st.st_size) {
            ssize_t r = read(fd, buf + got, (size_t)st.st_size - got);
            if (r <= 0) break;
            got += (size_t)r;
        }
        if (got == (size_t)st.st_size) {
            *size = got;
        } else {
            free(buf);
            buf = NULL;
        }
    }
    close(fd);
    return buf;
}

/* Writes the cache next to its file and renames it over, so a kill mid-write loses nothing. */
static void save(vkb_pcache *p)
{
    vkb_srv_table *t = p->t;
    size_t size = 0;
    if (t->real.vkGetPipelineCacheData(t->device, p->cache, &size, NULL) != VK_SUCCESS || !size || size > PC_MAX_FILE) return;
    if (size == p->saved_size) {
        /* Nothing new was compiled (merges of what it already had leave the size alone). */
        p->dirty = 0;
        return;
    }
    uint8_t *buf = malloc(size);
    if (!buf) return;
    VkResult r = t->real.vkGetPipelineCacheData(t->device, p->cache, &size, buf);
    if (r == VK_SUCCESS) {
        char tmp[600];
        snprintf(tmp, sizeof(tmp), "%s.%d.tmp", p->path, (int)getpid());
        int fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
        int ok = fd >= 0;
        for (size_t done = 0; ok && done < size;) {
            ssize_t w = write(fd, buf + done, size - done);
            if (w < 0 && errno == EINTR) continue;
            if (w <= 0) ok = 0;
            else done += (size_t)w;
        }
        if (fd >= 0 && fsync(fd) != 0) ok = 0;
        if (fd >= 0) close(fd);
        if (ok && rename(tmp, p->path) == 0) {
            VKB_INFO("device %u: pipeline cache saved (%zu KB)", t->id, size >> 10);
            p->saved_size = size;
            p->dirty = 0;
        } else {
            VKB_WARN("device %u: pipeline cache not saved to %s: %s", t->id, p->path, strerror(errno));
            unlink(tmp);
        }
    }
    free(buf);
}

static void *saver(void *arg)
{
    (void)arg;
    for (;;) {
        struct timespec ts = {PC_SAVE_PERIOD_S, 0};
        nanosleep(&ts, NULL);
        pthread_mutex_lock(&pc_lock);
        for (vkb_pcache *p = pc_list; p; p = p->next)
            if (__atomic_load_n(&p->dirty, __ATOMIC_RELAXED)) save(p);
        pthread_mutex_unlock(&pc_lock);
    }
    return NULL;
}

/* ------------------------------------------------------------------ wrappers */

static VkResult VKAPI_CALL pc_CreateGraphicsPipelines(VkDevice device, VkPipelineCache cache, uint32_t n,
                                                      const VkGraphicsPipelineCreateInfo *ci, const VkAllocationCallbacks *a,
                                                      VkPipeline *out)
{
    vkb_pcache *p = cur();
    if (!cache) {
        cache = p->cache;
        __atomic_store_n(&p->dirty, 1, __ATOMIC_RELAXED);
    }
    return p->create_gfx(device, cache, n, ci, a, out);
}

static VkResult VKAPI_CALL pc_CreateComputePipelines(VkDevice device, VkPipelineCache cache, uint32_t n,
                                                     const VkComputePipelineCreateInfo *ci, const VkAllocationCallbacks *a,
                                                     VkPipeline *out)
{
    vkb_pcache *p = cur();
    if (!cache) {
        cache = p->cache;
        __atomic_store_n(&p->dirty, 1, __ATOMIC_RELAXED);
    }
    return p->create_compute(device, cache, n, ci, a, out);
}

static VkResult VKAPI_CALL pc_CreatePipelineCache(VkDevice device, const VkPipelineCacheCreateInfo *ci,
                                                  const VkAllocationCallbacks *a, VkPipelineCache *out)
{
    vkb_pcache *p = cur();
    if (ci->initialDataSize) return p->create_cache(device, ci, a, out);
    size_t size = 0;
    uint8_t *buf = NULL;
    if (p->t->real.vkGetPipelineCacheData(device, p->cache, &size, NULL) == VK_SUCCESS && size && (buf = malloc(size)) &&
        p->t->real.vkGetPipelineCacheData(device, p->cache, &size, buf) != VK_SUCCESS)
        size = 0;
    VkPipelineCacheCreateInfo seeded = *ci;
    seeded.initialDataSize = buf ? size : 0;
    seeded.pInitialData = buf;
    VkResult r = p->create_cache(device, &seeded, a, out);
    free(buf);
    return r;
}

static void VKAPI_CALL pc_DestroyPipelineCache(VkDevice device, VkPipelineCache cache, const VkAllocationCallbacks *a)
{
    vkb_pcache *p = cur();
    if (cache && cache != p->cache && p->t->real.vkMergePipelineCaches(device, p->cache, 1, &cache) == VK_SUCCESS)
        __atomic_store_n(&p->dirty, 1, __ATOMIC_RELAXED);
    p->destroy_cache(device, cache, a);
}

/* ------------------------------------------------------------------ device hooks */

void vkb_pcache_device_init(vkb_srv_table *t)
{
    if (!pc_dir || !t->real.vkCreatePipelineCache || !t->real.vkGetPipelineCacheData || !t->real.vkMergePipelineCaches) return;
    vkb_pcache *p = calloc(1, sizeof(*p));
    if (!p) return;
    p->t = t;
    VkPhysicalDeviceProperties props;
    t->real.vkGetPhysicalDeviceProperties(t->physical, &props);
    char uuid[2 * VK_UUID_SIZE + 1];
    for (int i = 0; i < VK_UUID_SIZE; i++) snprintf(uuid + 2 * i, 3, "%02x", props.pipelineCacheUUID[i]);
    snprintf(p->path, sizeof(p->path), "%s/%04x-%04x-%s.vkpc", pc_dir, props.vendorID, props.deviceID, uuid);

    size_t size;
    uint8_t *data = read_file(p->path, &size);
    if (data && !header_ok(t, data, size)) {
        VKB_WARN("device %u: %s is not this GPU's pipeline cache; starting empty", t->id, p->path);
        free(data);
        data = NULL;
        size = 0;
    }
    VkPipelineCacheCreateInfo ci = {VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO, NULL, 0, data ? size : 0, data};
    VkResult r = t->real.vkCreatePipelineCache(t->device, &ci, NULL, &p->cache);
    if (r != VK_SUCCESS && data) {
        VKB_WARN("device %u: driver refused the saved pipeline cache (%d); starting empty", t->id, r);
        ci.initialDataSize = 0;
        ci.pInitialData = NULL;
        r = t->real.vkCreatePipelineCache(t->device, &ci, NULL, &p->cache);
    }
    free(data);
    if (r != VK_SUCCESS) {
        VKB_WARN("device %u: no pipeline cache (%d)", t->id, r);
        free(p);
        return;
    }
    p->saved_size = size;
    VKB_INFO("device %u: pipeline cache %s (%zu KB loaded)", t->id, p->path, size >> 10);

    vkb_dispatch *dt = &t->dt;
    p->create_gfx = dt->vkCreateGraphicsPipelines;
    p->create_compute = dt->vkCreateComputePipelines;
    p->create_cache = dt->vkCreatePipelineCache;
    p->destroy_cache = dt->vkDestroyPipelineCache;
    dt->vkCreateGraphicsPipelines = pc_CreateGraphicsPipelines;
    dt->vkCreateComputePipelines = pc_CreateComputePipelines;
    dt->vkCreatePipelineCache = pc_CreatePipelineCache;
    dt->vkDestroyPipelineCache = pc_DestroyPipelineCache;
    t->pcache = p;

    pthread_mutex_lock(&pc_lock);
    p->next = pc_list;
    pc_list = p;
    if (!pc_thread_started) {
        pthread_t th;
        pthread_attr_t at;
        pthread_attr_init(&at);
        pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED);
        pc_thread_started = pthread_create(&th, &at, saver, NULL) == 0;
        pthread_attr_destroy(&at);
    }
    pthread_mutex_unlock(&pc_lock);
}

/* Before the device is destroyed: last save, then the cache goes. */
void vkb_pcache_device_destroy(vkb_srv_table *t)
{
    vkb_pcache *p = t->pcache;
    if (!p) return;
    pthread_mutex_lock(&pc_lock);
    for (vkb_pcache **pp = &pc_list; *pp; pp = &(*pp)->next) {
        if (*pp == p) {
            *pp = p->next;
            break;
        }
    }
    if (p->dirty) save(p);
    pthread_mutex_unlock(&pc_lock);
    t->dt.vkCreateGraphicsPipelines = p->create_gfx;
    t->dt.vkCreateComputePipelines = p->create_compute;
    t->dt.vkCreatePipelineCache = p->create_cache;
    t->dt.vkDestroyPipelineCache = p->destroy_cache;
    t->real.vkDestroyPipelineCache(t->device, p->cache, NULL);
    t->pcache = NULL;
    free(p);
}
