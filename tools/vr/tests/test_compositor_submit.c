#define _GNU_SOURCE
#include <ucontext.h>
#include <assert.h>
#include <stdint.h>
#include "../src/vk_layer_droiddeck_vr.c"

struct fake_fence { VkResult result; unsigned resets; };
static VkDevice device;
static VkResult submit_result = VK_SUCCESS, completion = VK_SUCCESS, marker_result = VK_SUCCESS;
static unsigned submits_seen, waits_seen, markers_seen, freed;
static int create_fails;
static VkFence caller_fence = (VkFence)(uintptr_t)99;

static struct fake_fence *fake(VkFence f) { return (struct fake_fence *)(uintptr_t)f; }
static VkResult VKAPI_CALL create(VkDevice d, const VkFenceCreateInfo *ci, const VkAllocationCallbacks *a, VkFence *out) {
    assert(d == device && ci->flags == 0);
    if (create_fails) return VK_ERROR_OUT_OF_HOST_MEMORY;
    struct fake_fence *f = calloc(1, sizeof(*f));
    f->result = VK_TIMEOUT;
    *out = (VkFence)(uintptr_t)f;
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL wait_fence(VkDevice d, uint32_t n, const VkFence *f, VkBool32 all, uint64_t timeout) {
    assert(d == device && n == 1 && all);
    if (timeout) {
        assert(timeout == 1000000000ull);
        assert(pthread_mutex_trylock(&g_trk_lock) == 0);
        assert(g_trk->state == TRACK_RESERVED);
        uint32_t done;
        assert(!track_pool_reap(g_trk, g_sync->issued, &done));
        assert(g_trk->state == TRACK_RESERVED && fake(*f)->resets == 0);
        pthread_mutex_unlock(&g_trk_lock);
        waits_seen++;
    }
    return fake(*f)->result;
}
static VkResult VKAPI_CALL reset(VkDevice d, uint32_t n, const VkFence *f) {
    assert(d == device && n == 1);
    fake(*f)->resets++;
    fake(*f)->result = VK_TIMEOUT;
    return VK_SUCCESS;
}
static void VKAPI_CALL destroy(VkDevice d, VkFence f, const VkAllocationCallbacks *a) {
    assert(d == device); free(fake(f)); freed++;
}
static PFN_vkVoidFunction VKAPI_CALL gdpa(VkDevice d, const char *name) {
    assert(d == device);
    if (!strcmp(name, "vkCreateFence")) return (PFN_vkVoidFunction)create;
    if (!strcmp(name, "vkWaitForFences")) return (PFN_vkVoidFunction)wait_fence;
    if (!strcmp(name, "vkResetFences")) return (PFN_vkVoidFunction)reset;
    if (!strcmp(name, "vkDestroyFence")) return (PFN_vkVoidFunction)destroy;
    assert(0); return NULL;
}
static VkResult submitted(uint32_t n, VkFence f) {
    submits_seen++;
    if (!strcmp(program_invocation_short_name, "vrserver")) return submit_result;
    assert(g_trk && g_trk->state == TRACK_RESERVED);
    if (!n) markers_seen++;
    VkResult r = n ? submit_result : marker_result;
    if (r == VK_SUCCESS && f != caller_fence) fake(f)->result = completion;
    return r;
}
static VkResult VKAPI_CALL submit1(VkQueue q, uint32_t n, const VkSubmitInfo *info, VkFence f) {
    assert((n == 0) == (info == NULL)); return submitted(n, f);
}
static VkResult VKAPI_CALL submit2(VkQueue q, uint32_t n, const VkSubmitInfo2 *info, VkFence f) {
    assert((n == 0) == (info == NULL)); return submitted(n, f);
}
static void no_init(void) {}

static void scenario(int modern, VkFence fence, VkResult submitStatus, VkResult markerStatus, VkResult completeStatus) {
    submit_result = submitStatus; marker_result = markerStatus; completion = completeStatus;
    submits_seen = waits_seen = markers_seen = 0;
    VkSubmitInfo info = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO };
    VkSubmitInfo2 info2 = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO_2 };
    VkResult r = modern ? submit2_tracked(submit2, (VkQueue)device, 1, &info2, fence)
                        : submit_tracked(submit1, (VkQueue)device, 1, &info, fence);
    VkResult tracking = submitStatus == VK_SUCCESS && fence ? markerStatus : submitStatus;
    VkResult expected = tracking != VK_SUCCESS ? tracking : completeStatus == VK_TIMEOUT ? VK_ERROR_DEVICE_LOST : completeStatus;
    assert(r == expected);
    assert(waits_seen == (tracking == VK_SUCCESS ? 1u : 0u));
    assert(markers_seen == (submitStatus == VK_SUCCESS && fence ? 1u : 0u));
    assert(submits_seen == 1 + markers_seen);
    if (tracking == VK_SUCCESS) {
        assert(g_trk->state == TRACK_SUBMITTED && fake(g_trk->fence)->resets == 0);
        if (completeStatus == VK_TIMEOUT) {
            uint32_t done;
            assert(track_pool_reap(g_trk, g_sync->issued, &done));
            assert(g_trk->state == TRACK_SUBMITTED && fake(g_trk->fence)->resets == 0);
        }
        fake(g_trk->fence)->result = VK_SUCCESS;
        uint32_t done;
        assert(!track_pool_reap(g_trk, g_sync->issued, &done));
    }
    assert(!track_pool_device_busy(g_trk, device));
    track_pool_forget(&g_trk, device);
}

int main(void) {
    uintptr_t dispatch = 1;
    device = (VkDevice)&dispatch;
    g_dev[0] = (struct dev){ .handle = device, .gdpa = gdpa };
    struct droiddeck_present_sync sync = {0};
    g_sync = &sync;
    pthread_once(&g_sync_once, no_init);
    program_invocation_short_name = "vrcompositor";
    for (int modern = 0; modern < 2; modern++) {
        scenario(modern, VK_NULL_HANDLE, VK_SUCCESS, VK_SUCCESS, VK_SUCCESS);
        scenario(modern, caller_fence, VK_SUCCESS, VK_SUCCESS, VK_SUCCESS);
        scenario(modern, VK_NULL_HANDLE, VK_SUCCESS, VK_SUCCESS, VK_TIMEOUT);
        scenario(modern, caller_fence, VK_SUCCESS, VK_SUCCESS, VK_ERROR_DEVICE_LOST);
        scenario(modern, caller_fence, VK_ERROR_DEVICE_LOST, VK_SUCCESS, VK_SUCCESS);
        scenario(modern, caller_fence, VK_SUCCESS, VK_ERROR_DEVICE_LOST, VK_SUCCESS);
    }
    assert(freed == 12);
    create_fails = 1;
    submits_seen = 0;
    VkSubmitInfo info = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO };
    assert(submit_tracked(submit1, (VkQueue)device, 1, &info, VK_NULL_HANDLE) == VK_ERROR_OUT_OF_HOST_MEMORY);
    assert(!submits_seen && !g_trk);
    program_invocation_short_name = "vrserver";
    submit_result = VK_SUCCESS; waits_seen = 0;
    assert(submit_tracked(submit1, (VkQueue)device, 1, &info, caller_fence) == VK_SUCCESS);
    assert(submits_seen == 1 && waits_seen == 0 && !g_trk);
    puts("PASS: compositor submissions await their own fences, preserve caller fences, bound waits, and retain pending fences on timeout");
}
