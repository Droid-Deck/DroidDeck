#pragma once

#include <stdint.h>
#include <stdlib.h>
#include <vulkan/vulkan.h>

/* All pool operations require the caller's tracker lock. Reserved fences are
 * invisible to the completer until vkQueueSubmit has returned successfully. */
struct tracked {
    struct tracked *next;
    VkDevice dev;
    VkFence fence;
    PFN_vkWaitForFences wait;
    PFN_vkResetFences reset;
    PFN_vkDestroyFence destroy;
    uint32_t mark;
    enum { TRACK_IDLE, TRACK_RESERVED, TRACK_SUBMITTED } state;
};

static struct tracked *track_pool_take(struct tracked **pool, VkDevice dev, PFN_vkGetDeviceProcAddr gdpa) {
    struct tracked *idle = NULL;
    for (struct tracked *t = *pool; t; t = t->next) {
        if (t->state != TRACK_IDLE) continue;
        if (t->dev == dev) {
            t->state = TRACK_RESERVED;
            return t;
        }
        if (!idle) idle = t;
    }
    struct tracked t = {.dev = dev, .state = TRACK_RESERVED};
    t.wait = (PFN_vkWaitForFences)gdpa(dev, "vkWaitForFences");
    t.reset = (PFN_vkResetFences)gdpa(dev, "vkResetFences");
    t.destroy = (PFN_vkDestroyFence)gdpa(dev, "vkDestroyFence");
    PFN_vkCreateFence create = (PFN_vkCreateFence)gdpa(dev, "vkCreateFence");
    if (!t.wait || !t.reset || !t.destroy || !create) return NULL;
    /* Never wait for a slot: the next submission may signal a semaphore that
     * every outstanding fence is waiting on. Grow only at the high-water mark. */
    struct tracked *slot = idle ? idle : calloc(1, sizeof(*slot));
    if (!slot) return NULL;
    VkFenceCreateInfo fci = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, NULL, 0};
    if (create(dev, &fci, NULL, &t.fence) != VK_SUCCESS) {
        if (!idle) free(slot);
        return NULL;
    }
    if (idle) {
        idle->destroy(idle->dev, idle->fence, NULL);
        t.next = idle->next;
    } else {
        t.next = *pool;
        *pool = slot;
    }
    *slot = t;
    return slot;
}

static uint32_t track_pool_submitted(struct tracked *t, VkResult result, uint32_t issued) {
    if (result == VK_SUCCESS) {
        t->mark = ++issued;
        t->state = TRACK_SUBMITTED;
    } else {
        t->reset(t->dev, 1, &t->fence);
        t->state = TRACK_IDLE;
    }
    return issued;
}

static int track_pool_reap(struct tracked *pool, uint32_t issued, uint32_t *done) {
    int pending = 0;
    uint32_t through = issued;
    for (struct tracked *t = pool; t; t = t->next) {
        if (t->state != TRACK_SUBMITTED) continue;
        /* Scan all fences without blocking behind an earlier submission on a
         * different queue. Device loss retires fences as in the previous path. */
        if (t->wait(t->dev, 1, &t->fence, VK_TRUE, 0) == VK_TIMEOUT) {
            pending = 1;
            if ((int32_t)(t->mark - through) <= 0) through = t->mark - 1;
        } else {
            t->reset(t->dev, 1, &t->fence);
            t->state = TRACK_IDLE;
        }
    }
    /* done is a contiguous watermark, not a count of completed fences. Later
     * work may free its slot but cannot release an unfinished presenter frame.
     * Signed differences also handle the uint32_t counter wrapping. */
    *done = through;
    return pending;
}

static int track_pool_device_busy(struct tracked *pool, VkDevice dev) {
    for (struct tracked *t = pool; t; t = t->next)
        if (t->dev == dev && t->state != TRACK_IDLE) return 1;
    return 0;
}

/* The caller must first wait for all this device's reservations and work. */
static void track_pool_forget(struct tracked **pool, VkDevice dev) {
    for (struct tracked **p = pool; *p;) {
        struct tracked *t = *p;
        if (t->dev != dev) { p = &t->next; continue; }
        t->destroy(t->dev, t->fence, NULL);
        *p = t->next;
        free(t);
    }
}
