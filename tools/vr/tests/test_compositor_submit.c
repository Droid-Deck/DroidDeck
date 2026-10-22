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
    assert(timeout == 0);
    waits_seen++;
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

static VkQueue forwarded_queue;
static const void *forwarded_info;
static VkFence forwarded_fence;
static VkResult VKAPI_CALL forward1(VkQueue q, uint32_t n, const VkSubmitInfo *info, VkFence fence) {
    assert(q == forwarded_queue && n == 1 && info == forwarded_info && fence == forwarded_fence);
    submits_seen++;
    return submit_result;
}
static VkResult VKAPI_CALL forward2(VkQueue q, uint32_t n, const VkSubmitInfo2 *info, VkFence fence) {
    assert(q == forwarded_queue && n == 1 && info == forwarded_info && fence == forwarded_fence);
    submits_seen++;
    return submit_result;
}
static void unavailable_tracking(int modern, int missing_sync, VkFence fence, VkResult result) {
    struct droiddeck_present_sync *saved_sync = g_sync;
    const uint32_t issued = saved_sync->issued, done = saved_sync->done;
    const unsigned freed_before = freed;
    uintptr_t unknown_dispatch = 2;
    forwarded_queue = missing_sync ? (VkQueue)device : (VkQueue)&unknown_dispatch;
    if (missing_sync) g_sync = NULL;
    VkSubmitInfo info = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO };
    VkSubmitInfo2 info2 = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO_2 };
    forwarded_info = modern ? (const void *)&info2 : (const void *)&info;
    forwarded_fence = fence;
    submits_seen = waits_seen = markers_seen = 0;
    submit_result = result;
    VkResult r = modern ? submit2_tracked(forward2, forwarded_queue, 1, &info2, fence)
                        : submit_tracked(forward1, forwarded_queue, 1, &info, fence);
    assert(r == result && submits_seen == 1 && !waits_seen && !markers_seen && !g_trk);
    assert(freed == freed_before && saved_sync->issued == issued && saved_sync->done == done);
    g_sync = saved_sync;
}

static void scenario(int modern, VkFence fence, VkResult submitStatus, VkResult markerStatus, VkResult completeStatus) {
    submit_result = submitStatus; marker_result = markerStatus; completion = completeStatus;
    submits_seen = waits_seen = markers_seen = 0;
    VkSubmitInfo info = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO };
    VkSubmitInfo2 info2 = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO_2 };
    VkResult r = modern ? submit2_tracked(submit2, (VkQueue)device, 1, &info2, fence)
                        : submit_tracked(submit1, (VkQueue)device, 1, &info, fence);
    VkResult tracking = submitStatus == VK_SUCCESS && fence ? markerStatus : submitStatus;
    assert(r == tracking);
    assert(waits_seen == 0);
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

static VkFence dependency_fence;
static VkQueue dependency_queue;
static VkResult dependency_submit(VkQueue q, VkFence fence, uint64_t wait, uint64_t signal) {
    assert(g_trk && g_trk->state == TRACK_RESERVED);
    if (q == dependency_queue) {
        assert(wait == 1 && signal == 0 && dependency_fence == VK_NULL_HANDLE);
        dependency_fence = fence;
        fake(fence)->result = VK_TIMEOUT;
    } else {
        assert(wait == 0 && signal == 1 && dependency_fence != VK_NULL_HANDLE);
        fake(dependency_fence)->result = fake(fence)->result = VK_SUCCESS;
    }
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL dependent_submit1(VkQueue q, uint32_t n, const VkSubmitInfo *info, VkFence fence) {
    assert(n == 1);
    const VkTimelineSemaphoreSubmitInfo *timeline = info->pNext;
    assert(timeline->sType == VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO);
    return dependency_submit(q, fence, info->waitSemaphoreCount ? timeline->pWaitSemaphoreValues[0] : 0,
        info->signalSemaphoreCount ? timeline->pSignalSemaphoreValues[0] : 0);
}
static VkResult VKAPI_CALL dependent_submit2(VkQueue q, uint32_t n, const VkSubmitInfo2 *info, VkFence fence) {
    assert(n == 1);
    return dependency_submit(q, fence, info->waitSemaphoreInfoCount ? info->pWaitSemaphoreInfos[0].value : 0,
        info->signalSemaphoreInfoCount ? info->pSignalSemaphoreInfos[0].value : 0);
}
static void wait_before_signal(int modern) {
    uintptr_t dispatch = *(uintptr_t *)device;
    VkQueue signalling = (VkQueue)&dispatch;
    dependency_queue = (VkQueue)device;
    dependency_fence = VK_NULL_HANDLE;
    VkSemaphore semaphore = (VkSemaphore)(uintptr_t)123;
    uint64_t value = 1;
    VkPipelineStageFlags stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    VkTimelineSemaphoreSubmitInfo timeline_wait = { .sType = VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO,
        .waitSemaphoreValueCount = 1, .pWaitSemaphoreValues = &value };
    VkTimelineSemaphoreSubmitInfo timeline_signal = { .sType = VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO,
        .signalSemaphoreValueCount = 1, .pSignalSemaphoreValues = &value };
    VkSubmitInfo wait = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .pNext = &timeline_wait,
        .waitSemaphoreCount = 1, .pWaitSemaphores = &semaphore, .pWaitDstStageMask = &stage };
    VkSubmitInfo signal = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .pNext = &timeline_signal,
        .signalSemaphoreCount = 1, .pSignalSemaphores = &semaphore };
    VkSemaphoreSubmitInfo dependency = { .sType = VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO,
        .semaphore = semaphore, .value = 1, .stageMask = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT };
    VkSubmitInfo2 wait2 = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO_2,
        .waitSemaphoreInfoCount = 1, .pWaitSemaphoreInfos = &dependency };
    VkSubmitInfo2 signal2 = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO_2,
        .signalSemaphoreInfoCount = 1, .pSignalSemaphoreInfos = &dependency };
    assert((modern ? submit2_tracked(dependent_submit2, dependency_queue, 1, &wait2, VK_NULL_HANDLE)
                   : submit_tracked(dependent_submit1, dependency_queue, 1, &wait, VK_NULL_HANDLE)) == VK_SUCCESS);
    uint32_t done;
    assert(track_pool_reap(g_trk, g_sync->issued, &done));
    assert(done == g_sync->issued - 1);
    assert((modern ? submit2_tracked(dependent_submit2, signalling, 1, &signal2, VK_NULL_HANDLE)
                   : submit_tracked(dependent_submit1, signalling, 1, &signal, VK_NULL_HANDLE)) == VK_SUCCESS);
    assert(!track_pool_reap(g_trk, g_sync->issued, &done));
    assert(done == g_sync->issued);
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
    wait_before_signal(0);
    wait_before_signal(1);
    for (int modern = 0; modern < 2; modern++) {
        for (int missing_sync = 0; missing_sync < 2; missing_sync++) {
            unavailable_tracking(modern, missing_sync, VK_NULL_HANDLE, VK_SUCCESS);
            unavailable_tracking(modern, missing_sync, caller_fence, VK_SUCCESS);
            unavailable_tracking(modern, missing_sync, caller_fence, VK_ERROR_DEVICE_LOST);
        }
    }
    create_fails = 1;
    submits_seen = 0;
    VkSubmitInfo info = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO };
    VkSubmitInfo2 info2 = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO_2 };
    assert(submit_tracked(submit1, (VkQueue)device, 1, &info, VK_NULL_HANDLE) == VK_ERROR_OUT_OF_HOST_MEMORY);
    assert(submit2_tracked(submit2, (VkQueue)device, 1, &info2, caller_fence) == VK_ERROR_OUT_OF_HOST_MEMORY);
    assert(!submits_seen && !g_trk);
    program_invocation_short_name = "vrserver";
    submit_result = VK_SUCCESS; waits_seen = 0;
    assert(submit_tracked(submit1, (VkQueue)device, 1, &info, caller_fence) == VK_SUCCESS);
    assert(submits_seen == 1 && waits_seen == 0 && !g_trk);
    puts("PASS: compositor submissions stay asynchronous, preserve caller fences, and allow later cross-queue signals");
}
