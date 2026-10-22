#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "fence_tracker.h"

struct fake_fence { VkDevice dev; VkResult result; unsigned waits; };
static unsigned created, destroyed;
static int fail_create;

static struct fake_fence *fake(VkFence fence) { return (struct fake_fence *)(uintptr_t)fence; }

static VkResult VKAPI_CALL create_fence(VkDevice dev, const VkFenceCreateInfo *info,
                                       const VkAllocationCallbacks *alloc, VkFence *out) {
    (void)alloc;
    assert(info->flags == 0);
    if (fail_create) return VK_ERROR_OUT_OF_HOST_MEMORY;
    struct fake_fence *f = calloc(1, sizeof(*f));
    assert(f);
    f->dev = dev;
    f->result = VK_TIMEOUT;
    *out = (VkFence)(uintptr_t)f;
    created++;
    return VK_SUCCESS;
}

static VkResult VKAPI_CALL wait_fence(VkDevice dev, uint32_t count, const VkFence *fences,
                                     VkBool32 all, uint64_t timeout) {
    assert(count == 1 && all && timeout == 0);
    struct fake_fence *f = fake(*fences);
    assert(f->dev == dev);
    f->waits++;
    return f->result;
}

static VkResult VKAPI_CALL reset_fence(VkDevice dev, uint32_t count, const VkFence *fences) {
    assert(count == 1 && fake(*fences)->dev == dev);
    fake(*fences)->result = VK_TIMEOUT;
    return VK_SUCCESS;
}

static void VKAPI_CALL destroy_fence(VkDevice dev, VkFence fence, const VkAllocationCallbacks *alloc) {
    (void)alloc;
    assert(fake(fence)->dev == dev);
    free(fake(fence));
    destroyed++;
}

static PFN_vkVoidFunction VKAPI_CALL gdpa(VkDevice dev, const char *name) {
    (void)dev;
    if (!strcmp(name, "vkCreateFence")) return (PFN_vkVoidFunction)create_fence;
    if (!strcmp(name, "vkWaitForFences")) return (PFN_vkVoidFunction)wait_fence;
    if (!strcmp(name, "vkResetFences")) return (PFN_vkVoidFunction)reset_fence;
    if (!strcmp(name, "vkDestroyFence")) return (PFN_vkVoidFunction)destroy_fence;
    assert(0);
    return NULL;
}

static struct tracked *submit(struct tracked **pool, VkDevice dev, uint32_t *issued) {
    struct tracked *t = track_pool_take(pool, dev, gdpa);
    assert(t && t->state == TRACK_RESERVED);
    *issued = track_pool_submitted(t, VK_SUCCESS, *issued);
    return t;
}

static void saturated_pool_and_out_of_order_completion(void) {
    struct tracked *pool = NULL, *pending[130];
    VkDevice dev = (VkDevice)(uintptr_t)1;
    uint32_t issued = 0, done = 0;
    /* These waits can all depend on a signal from a later queue submission.
     * A fixed 64-slot pool blocks that signal before it reaches the driver. */
    for (unsigned i = 0; i < 130; i++) pending[i] = submit(&pool, dev, &issued);
    assert(issued == 130);
    assert(track_pool_reap(pool, issued, &done) && done == 0);
    for (unsigned i = 0; i < 130; i++) assert(fake(pending[i]->fence)->waits == 1);

    fake(pending[129]->fence)->result = VK_SUCCESS;
    assert(track_pool_reap(pool, issued, &done) && done == 0);
    assert(pending[129]->state == TRACK_IDLE);
    unsigned before = created;
    struct tracked *signal = submit(&pool, dev, &issued);
    assert(signal == pending[129] && created == before && issued == 131);
    /* Freeing the latest fence must not mark the older work as drawn. */
    for (unsigned i = 0; i < 129; i++) fake(pending[i]->fence)->result = VK_SUCCESS;
    assert(track_pool_reap(pool, issued, &done) && done == 130);
    fake(signal->fence)->result = VK_SUCCESS;
    assert(!track_pool_reap(pool, issued, &done) && done == 131);
    assert(!track_pool_device_busy(pool, dev));
    track_pool_forget(&pool, dev);
    assert(!pool);
}

static void wrapped_watermark(void) {
    struct tracked *pool = NULL;
    VkDevice dev = (VkDevice)(uintptr_t)1;
    uint32_t issued = UINT32_MAX - 1, done = issued;
    struct tracked *first = submit(&pool, dev, &issued);
    struct tracked *second = submit(&pool, dev, &issued);
    struct tracked *third = submit(&pool, dev, &issued);
    assert(issued == 1);
    fake(second->fence)->result = VK_SUCCESS;
    fake(third->fence)->result = VK_SUCCESS;
    assert(track_pool_reap(pool, issued, &done) && done == UINT32_MAX - 1);
    fake(first->fence)->result = VK_SUCCESS;
    assert(!track_pool_reap(pool, issued, &done) && done == 1);
    track_pool_forget(&pool, dev);
}

static void reservations_failures_and_devices(void) {
    struct tracked *pool = NULL;
    VkDevice a = (VkDevice)(uintptr_t)1, b = (VkDevice)(uintptr_t)2;
    uint32_t issued = 7, done = 7;
    struct tracked *reserved = track_pool_take(&pool, a, gdpa);
    assert(track_pool_device_busy(pool, a));
    assert(!track_pool_reap(pool, issued, &done) && done == 7);
    assert(fake(reserved->fence)->waits == 0);
    issued = track_pool_submitted(reserved, VK_ERROR_DEVICE_LOST, issued);
    assert(issued == 7 && !track_pool_device_busy(pool, a));
    fail_create = 1;
    assert(!track_pool_take(&pool, b, gdpa));
    assert(pool == reserved && pool->dev == a);
    fail_create = 0;
    unsigned before = destroyed;
    struct tracked *other = submit(&pool, b, &issued);
    assert(other == reserved && destroyed == before + 1);
    struct tracked *own = submit(&pool, a, &issued);
    fake(other->fence)->result = VK_SUCCESS;
    assert(track_pool_reap(pool, issued, &done) && done == 8);
    track_pool_forget(&pool, b);
    assert(pool == own && track_pool_device_busy(pool, a));
    fake(own->fence)->result = VK_ERROR_DEVICE_LOST;
    assert(!track_pool_reap(pool, issued, &done) && done == 9);
    track_pool_forget(&pool, a);
    assert(!pool);
    fail_create = 1;
    assert(!track_pool_take(&pool, a, gdpa) && !pool);
    fail_create = 0;
}

int main(void) {
    saturated_pool_and_out_of_order_completion();
    wrapped_watermark();
    reservations_failures_and_devices();
    assert(created == destroyed);
    puts("fence tracker: all regression scenarios passed");
    return 0;
}
