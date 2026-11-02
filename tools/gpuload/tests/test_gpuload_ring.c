#include <assert.h>
#include <stdint.h>
#include <stdio.h>

#include "gpuload_ring.h"

// A fake device: query results come from this table, [start, start available, end, end available] per slot.
static uint64_t g_result[GPULOAD_RING][4];
static uintptr_t g_next_handle = 0x1000;

static VkResult VKAPI_CALL create_pool(VkDevice d, const VkCommandPoolCreateInfo *ci, const VkAllocationCallbacks *a, VkCommandPool *out) {
    (void)d; (void)ci; (void)a; *out = (VkCommandPool)(g_next_handle++); return VK_SUCCESS;
}
static void VKAPI_CALL destroy_pool(VkDevice d, VkCommandPool p, const VkAllocationCallbacks *a) { (void)d; (void)p; (void)a; }
static VkResult VKAPI_CALL alloc_cmds(VkDevice d, const VkCommandBufferAllocateInfo *ai, VkCommandBuffer *out) {
    (void)d;
    for (uint32_t i = 0; i < ai->commandBufferCount; i++) out[i] = (VkCommandBuffer)(g_next_handle++);
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL begin(VkCommandBuffer c, const VkCommandBufferBeginInfo *bi) { (void)c; (void)bi; return VK_SUCCESS; }
static VkResult VKAPI_CALL end(VkCommandBuffer c) { (void)c; return VK_SUCCESS; }
static void VKAPI_CALL cmd_reset(VkCommandBuffer c, VkQueryPool q, uint32_t first, uint32_t n) { (void)c; (void)q; (void)first; (void)n; }
static void VKAPI_CALL cmd_timestamp(VkCommandBuffer c, VkPipelineStageFlagBits s, VkQueryPool q, uint32_t i) { (void)c; (void)s; (void)q; (void)i; }
static VkResult VKAPI_CALL create_queries(VkDevice d, const VkQueryPoolCreateInfo *ci, const VkAllocationCallbacks *a, VkQueryPool *out) {
    (void)d; (void)a; assert(ci->queryCount == 2 * GPULOAD_RING); *out = (VkQueryPool)(g_next_handle++); return VK_SUCCESS;
}
static void VKAPI_CALL destroy_queries(VkDevice d, VkQueryPool q, const VkAllocationCallbacks *a) { (void)d; (void)q; (void)a; }
static VkResult VKAPI_CALL results(VkDevice d, VkQueryPool q, uint32_t first, uint32_t n, size_t size, void *data,
                                   VkDeviceSize stride, VkQueryResultFlags flags) {
    (void)d; (void)q; (void)size;
    assert(n == 2 && stride == 2 * sizeof(uint64_t) && (flags & VK_QUERY_RESULT_WITH_AVAILABILITY_BIT));
    uint64_t *out = data;
    for (int k = 0; k < 4; k++) out[k] = g_result[first / 2][k];
    return out[1] && out[3] ? VK_SUCCESS : VK_NOT_READY;
}

static void finish(uint32_t slot, uint64_t start, uint64_t end) {
    g_result[slot][0] = start; g_result[slot][1] = 1; g_result[slot][2] = end; g_result[slot][3] = 1;
}

int main(void) {
    struct gpuload_vk v = {
        .device = (VkDevice)(uintptr_t)1, .create_pool = create_pool, .destroy_pool = destroy_pool, .alloc_cmds = alloc_cmds,
        .begin = begin, .end = end, .cmd_reset = cmd_reset, .cmd_timestamp = cmd_timestamp,
        .create_queries = create_queries, .destroy_queries = destroy_queries, .results = results,
    };
    struct gpuload_ring r = {0};
    assert(!gpuload_ring_setup(&r, &v, 0, 64) && r.state == -1);  // no timestamp period yet
    gpuload_vk_period(&v, 52.083f);
    assert(!gpuload_ring_setup(&r, &v, 0, 0) && r.state == -1);   // family without timestamps
    assert(gpuload_ring_setup(&r, &v, 0, 64) && r.state == 1);

    VkCommandBuffer app[2] = {(VkCommandBuffer)(uintptr_t)0xa1, (VkCommandBuffer)(uintptr_t)0xa2};
    VkProtectedSubmitInfo prot = {.sType = VK_STRUCTURE_TYPE_PROTECTED_SUBMIT_INFO, .protectedSubmit = VK_TRUE};
    VkSubmitInfo batches[3] = {
        {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 2, .pCommandBuffers = app},
        {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO},                                                   // semaphores only
        {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .pNext = &prot, .commandBufferCount = 1, .pCommandBuffers = app},
    };
    uint32_t used = 0;
    VkSubmitInfo *mine = gpuload_ring_wrap(&r, 3, batches, &used);
    assert(mine && used == 1 && r.inflight == 1);
    assert(mine[0].commandBufferCount == 4 && mine[0].pCommandBuffers[0] == r.slot[0].start &&
           mine[0].pCommandBuffers[1] == app[0] && mine[0].pCommandBuffers[2] == app[1] &&
           mine[0].pCommandBuffers[3] == r.slot[0].end);
    assert(mine[1].commandBufferCount == 0 && mine[2].commandBufferCount == 1 && mine[2].pCommandBuffers == app);
    free(mine);

    assert(gpuload_ring_harvest(&r, &v) == 0 && r.inflight == 1);  // not finished yet
    finish(0, 1000, 1192);                                           // 192 ticks = 10 us
    assert(gpuload_ring_harvest(&r, &v) == 9999 && r.inflight == 0);

    // A failed submit gives its slot back.
    mine = gpuload_ring_wrap(&r, 1, batches, &used);
    assert(used == 1 && r.inflight == 1);
    gpuload_ring_unwind(&r, used);
    assert(r.inflight == 0 && r.next == 1);
    free(mine);

    // Fill the ring: the 65th batch goes out untimed.
    for (int i = 0; i < GPULOAD_RING; i++) { mine = gpuload_ring_wrap(&r, 1, batches, &used); assert(used == 1); free(mine); }
    mine = gpuload_ring_wrap(&r, 1, batches, &used);
    assert(used == 0 && mine[0].commandBufferCount == 2);
    free(mine);

    // Slot 0 comes round again: until the GPU resets it, it still shows the old pair, which must not count twice.
    // Slots 1..63 finish after a counter restart (smaller ticks than slot 0's old pair); each still counts.
    for (uint32_t s = 1; s < GPULOAD_RING; s++) finish(s, 10 * s, 10 * s + 4);
    assert(gpuload_ring_harvest(&r, &v) == (uint64_t)(63 * 4 * 52.083f) && r.inflight == 1 && r.oldest == 0);
    finish(0, 5, 9);
    assert(gpuload_ring_harvest(&r, &v) == (uint64_t)(4 * 52.083f) && r.inflight == 0);

    gpuload_ring_release(&r, &v);
    puts("test_gpuload_ring: ok");
    return 0;
}
