// Timestamp ring shared by VK_LAYER_DROIDDECK_gpu_load (guest) and DroidDeck's compositor (app): each submit batch
// that has command buffers gets one of the ring's command buffers in front (resets a query pair, writes a TOP_OF_PIPE
// timestamp) and one behind (BOTTOM_OF_PIPE timestamp). Finished pairs are read back without waiting.
// The start timestamp sits after the batch's semaphore waits only because Turnip and Qualcomm's driver on kgsl hold a
// whole batch until its waits signal; a driver that starts TOP_OF_PIPE work earlier would count the wait as busy.
// Callers serialize access to one ring (a queue's submits are externally synchronized anyway).
#ifndef DROIDDECK_GPULOAD_RING_H
#define DROIDDECK_GPULOAD_RING_H

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>

#include "gpuload_busy.h"

#define GPULOAD_RING 64

typedef VkResult(VKAPI_PTR *gpuload_set_data_fn)(VkDevice device, void *object);

struct gpuload_vk {
    VkDevice device;
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
    gpuload_set_data_fn set_data;  // a layer must give the loader every command buffer it allocates; NULL elsewhere
    double period_ns;
    uint64_t max_ticks;
};

struct gpuload_slot {
    VkCommandBuffer start, end;
    uint64_t last_end;
};

struct gpuload_ring {
    int state;  // 0 not set up, 1 timing, -1 cannot time
    VkCommandPool pool;
    VkQueryPool queries;
    uint64_t mask;
    uint32_t next, oldest, inflight;
    struct gpuload_slot slot[GPULOAD_RING];
};

#define GPULOAD_LONGEST_BATCH_NS 2000000000.0

static inline void gpuload_vk_period(struct gpuload_vk *v, float timestamp_period) {
    v->period_ns = timestamp_period;
    v->max_ticks = timestamp_period > 0 ? (uint64_t)(GPULOAD_LONGEST_BATCH_NS / timestamp_period) : 0;
}

static inline int gpuload_vk_complete(const struct gpuload_vk *v) {
    return v->device && v->create_pool && v->destroy_pool && v->alloc_cmds && v->begin && v->end && v->cmd_reset &&
           v->cmd_timestamp && v->create_queries && v->destroy_queries && v->results && v->period_ns > 0;
}

static inline void gpuload_ring_release(struct gpuload_ring *r, const struct gpuload_vk *v) {
    if (r->queries) v->destroy_queries(v->device, r->queries, NULL);
    if (r->pool) v->destroy_pool(v->device, r->pool, NULL);
    r->queries = VK_NULL_HANDLE;
    r->pool = VK_NULL_HANDLE;
    r->next = r->oldest = r->inflight = 0;
}

static inline int gpuload_ring_record(struct gpuload_ring *r, const struct gpuload_vk *v, uint32_t i) {
    const VkCommandBufferBeginInfo begin = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    struct gpuload_slot *s = &r->slot[i];
    if (v->begin(s->start, &begin) != VK_SUCCESS) return 0;
    v->cmd_reset(s->start, r->queries, 2 * i, 2);
    v->cmd_timestamp(s->start, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, r->queries, 2 * i);
    if (v->end(s->start) != VK_SUCCESS) return 0;
    if (v->begin(s->end, &begin) != VK_SUCCESS) return 0;
    v->cmd_timestamp(s->end, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, r->queries, 2 * i + 1);
    return v->end(s->end) == VK_SUCCESS;
}

// Sets the ring up for a queue of this family; returns 1 when it can time it (r->state says the same afterwards).
static inline int gpuload_ring_setup(struct gpuload_ring *r, const struct gpuload_vk *v, uint32_t family,
                                     uint32_t timestamp_bits) {
    r->state = -1;
    if (!timestamp_bits || !gpuload_vk_complete(v)) return 0;
    r->mask = timestamp_bits >= 64 ? ~0ull : (1ull << timestamp_bits) - 1;
    const VkCommandPoolCreateInfo pool = {.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, .queueFamilyIndex = family};
    const VkQueryPoolCreateInfo queries = {.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO,
                                           .queryType = VK_QUERY_TYPE_TIMESTAMP, .queryCount = 2 * GPULOAD_RING};
    VkCommandBuffer cmds[2 * GPULOAD_RING];
    if (v->create_pool(v->device, &pool, NULL, &r->pool) != VK_SUCCESS) { r->pool = VK_NULL_HANDLE; goto fail; }
    if (v->create_queries(v->device, &queries, NULL, &r->queries) != VK_SUCCESS) { r->queries = VK_NULL_HANDLE; goto fail; }
    const VkCommandBufferAllocateInfo alloc = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, .commandPool = r->pool,
                                               .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 2 * GPULOAD_RING};
    if (v->alloc_cmds(v->device, &alloc, cmds) != VK_SUCCESS) goto fail;
    for (uint32_t i = 0; i < 2 * GPULOAD_RING; i++)
        if (v->set_data && v->set_data(v->device, cmds[i]) != VK_SUCCESS) goto fail;
    for (uint32_t i = 0; i < GPULOAD_RING; i++) {
        r->slot[i].start = cmds[2 * i];
        r->slot[i].end = cmds[2 * i + 1];
        r->slot[i].last_end = 0;
        if (!gpuload_ring_record(r, v, i)) goto fail;
    }
    r->next = r->oldest = r->inflight = 0;
    r->state = 1;
    return 1;
fail:
    gpuload_ring_release(r, v);
    r->state = -1;
    return 0;
}

// Nanoseconds of GPU time in the batches that finished since the last call. Slots are taken in ring order and a
// queue runs its batches in order, so the oldest slot finishes first.
static inline uint64_t gpuload_ring_harvest(struct gpuload_ring *r, const struct gpuload_vk *v) {
    uint64_t ticks = 0;
    while (r->inflight) {
        struct gpuload_slot *s = &r->slot[r->oldest];
        uint64_t q[4] = {0, 0, 0, 0};
        VkResult res = v->results(v->device, r->queries, 2 * r->oldest, 2, sizeof(q), q, 2 * sizeof(uint64_t),
                                  VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
        if (res != VK_SUCCESS && res != VK_NOT_READY) break;
        uint64_t start = q[0] & r->mask, end = q[2] & r->mask;
        if (!gpuload_result_is_new(q[1], q[3], end, s->last_end)) break;
        s->last_end = end;
        r->oldest = (r->oldest + 1) % GPULOAD_RING;
        r->inflight--;
        ticks += gpuload_interval(start, end, v->max_ticks);
    }
    return (uint64_t)((double)ticks * v->period_ns);
}

static inline int gpuload_plain_batch(const void *next) {
    for (const VkBaseInStructure *s = (const VkBaseInStructure *)next; s; s = s->pNext) {
        if (s->sType == VK_STRUCTURE_TYPE_DEVICE_GROUP_SUBMIT_INFO) return 0;
        if (s->sType == VK_STRUCTURE_TYPE_PROTECTED_SUBMIT_INFO && ((const VkProtectedSubmitInfo *)s)->protectedSubmit)
            return 0;
    }
    return 1;
}

static inline struct gpuload_slot *gpuload_ring_take(struct gpuload_ring *r) {
    if (r->inflight >= GPULOAD_RING) return NULL;
    struct gpuload_slot *s = &r->slot[r->next];
    r->next = (r->next + 1) % GPULOAD_RING;
    r->inflight++;
    return s;
}

// Gives back the slots of a submit that failed.
static inline void gpuload_ring_unwind(struct gpuload_ring *r, uint32_t used) {
    r->next = (r->next + GPULOAD_RING - used) % GPULOAD_RING;
    r->inflight -= used;
}

// A copy of the batches with the ring's command buffers around each one that has command buffers (free() it after
// the submit), or NULL to submit the originals. *used counts the slots taken, for gpuload_ring_unwind.
static inline VkSubmitInfo *gpuload_ring_wrap(struct gpuload_ring *r, uint32_t count, const VkSubmitInfo *infos,
                                              uint32_t *used) {
    *used = 0;
    size_t cmds = 0;
    for (uint32_t i = 0; i < count; i++) cmds += infos[i].commandBufferCount + 2;
    VkSubmitInfo *mine = (VkSubmitInfo *)malloc(count * sizeof(*mine) + cmds * sizeof(VkCommandBuffer));
    if (!mine) return NULL;
    VkCommandBuffer *list = (VkCommandBuffer *)(mine + count);
    for (uint32_t i = 0; i < count; i++) {
        uint32_t n = infos[i].commandBufferCount;
        mine[i] = infos[i];
        if (!n || !gpuload_plain_batch(infos[i].pNext)) continue;
        struct gpuload_slot *s = gpuload_ring_take(r);
        if (!s) continue;
        list[0] = s->start;
        memcpy(list + 1, infos[i].pCommandBuffers, n * sizeof(VkCommandBuffer));
        list[n + 1] = s->end;
        mine[i].pCommandBuffers = list;
        mine[i].commandBufferCount = n + 2;
        list += n + 2;
        (*used)++;
    }
    return mine;
}

static inline VkSubmitInfo2 *gpuload_ring_wrap2(struct gpuload_ring *r, uint32_t count, const VkSubmitInfo2 *infos,
                                                uint32_t *used) {
    *used = 0;
    size_t cmds = 0;
    for (uint32_t i = 0; i < count; i++) cmds += infos[i].commandBufferInfoCount + 2;
    VkSubmitInfo2 *mine = (VkSubmitInfo2 *)malloc(count * sizeof(*mine) + cmds * sizeof(VkCommandBufferSubmitInfo));
    if (!mine) return NULL;
    VkCommandBufferSubmitInfo *list = (VkCommandBufferSubmitInfo *)(mine + count);
    for (uint32_t i = 0; i < count; i++) {
        uint32_t n = infos[i].commandBufferInfoCount;
        mine[i] = infos[i];
        if (!n || (infos[i].flags & VK_SUBMIT_PROTECTED_BIT) || !gpuload_plain_batch(infos[i].pNext)) continue;
        struct gpuload_slot *s = gpuload_ring_take(r);
        if (!s) continue;
        memset(&list[0], 0, sizeof(list[0]));
        list[0].sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO;
        list[0].commandBuffer = s->start;
        memcpy(list + 1, infos[i].pCommandBufferInfos, n * sizeof(VkCommandBufferSubmitInfo));
        memset(&list[n + 1], 0, sizeof(list[n + 1]));
        list[n + 1].sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO;
        list[n + 1].commandBuffer = s->end;
        mine[i].pCommandBufferInfos = list;
        mine[i].commandBufferInfoCount = n + 2;
        list += n + 2;
        (*used)++;
    }
    return mine;
}

#endif
