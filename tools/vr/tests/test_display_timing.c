#define _GNU_SOURCE
#define DROIDDECK_WSI_CAPTURE
#undef NDEBUG
#include "../src/vk_layer_droiddeck_vr.c"
#include "../src/xr_display_timing.h"
#include <assert.h>

static int64_t test_now = 2000000000LL, test_prediction, test_period;
static int clock_available = 1, interrupted;
static uint64_t slept_until;
static unsigned sleeps, fences;

int clock_gettime(clockid_t clock, struct timespec *time) {
    (void)clock;
    time->tv_sec = test_now / 1000000000LL;
    time->tv_nsec = test_now % 1000000000LL;
    return 0;
}
int clock_nanosleep(clockid_t clock, int flags, const struct timespec *time, struct timespec *remaining) {
    (void)remaining;
    assert(clock == CLOCK_MONOTONIC && flags == TIMER_ABSTIME);
    slept_until = (uint64_t)time->tv_sec * 1000000000ULL + time->tv_nsec;
    sleeps++;
    if (interrupted) { interrupted = 0; return EINTR; }
    test_now = (int64_t)slept_until;
    return 0;
}
int droiddeck_wsi_display_timing(int64_t now, int64_t *last, int64_t *period) {
    if (!clock_available) return 0;
    *last = xr_display_last_tick(now, test_prediction, test_period);
    *period = test_period;
    return *last > 0;
}
void droiddeck_wsi_device(VkDevice d, VkPhysicalDevice p, VkInstance i, PFN_vkGetInstanceProcAddr a,
                         PFN_vkGetDeviceProcAddr b, const VkDeviceCreateInfo *c) {
    (void)d; (void)p; (void)i; (void)a; (void)b; (void)c;
}
void droiddeck_wsi_destroy(VkDevice d) { (void)d; }
void droiddeck_wsi_swapchain(VkDevice d, VkSwapchainKHR s, const VkSwapchainCreateInfoKHR *c) { (void)d; (void)s; (void)c; }
void droiddeck_wsi_forget(VkDevice d, VkSwapchainKHR s) { (void)d; (void)s; }
VkResult droiddeck_wsi_present(VkDevice d, VkQueue q, const VkPresentInfoKHR *p, PFN_vkQueuePresentKHR n) {
    return n(q, p);
}

static VkResult VKAPI_CALL create_fence(VkDevice d, const VkFenceCreateInfo *info,
                                      const VkAllocationCallbacks *allocator, VkFence *fence) {
    (void)d; (void)allocator;
    assert(info->flags == VK_FENCE_CREATE_SIGNALED_BIT && test_now == (int64_t)slept_until);
    fences++; *fence = (VkFence)(uintptr_t)fences;
    return VK_SUCCESS;
}
static PFN_vkVoidFunction VKAPI_CALL get_proc(VkDevice d, const char *name) {
    (void)d; assert(!strcmp(name, "vkCreateFence")); return (PFN_vkVoidFunction)create_fence;
}

int main(void) {
    uintptr_t dispatch = 7;
    VkDevice device = (VkDevice)&dispatch;
    g_dev[0].handle = device; g_dev[0].gdpa = get_proc;
    int rates[] = {72, 90, 120};
    for (unsigned rate_index = 0; rate_index < sizeof(rates) / sizeof(rates[0]); rate_index++) {
        int rate = rates[rate_index];
        test_period = 1000000000LL / rate;
        test_prediction = 2000000000LL + test_period * 2 + 234567;
        for (int frame = 0; frame < 5; frame++) {
            test_now = test_prediction - test_period * 2 + frame * test_period + 1000000;
            int64_t last = xr_display_last_tick(test_now, test_prediction, test_period);
            assert(last <= test_now && test_now < last + test_period);
            assert((test_prediction - last) % test_period == 0);
            uint64_t count = vblank_count();
            assert(vblank_count() == count);
            VkFence fence;
            unsigned before = sleeps;
            interrupted = 1;
            assert(RegisterDisplayEventEXT(device, FAKE_DISPLAY, NULL, NULL, &fence) == VK_SUCCESS);
            assert(slept_until == (uint64_t)(last + test_period) && sleeps == before + 2);
            assert(vblank_count() >= count);
        }
    }
    clock_available = 0;
    test_now += 1234567;
    uint64_t expected = ((uint64_t)test_now / frame_ns() + 1) * frame_ns();
    VkFence fence;
    assert(RegisterDisplayEventEXT(device, FAKE_DISPLAY, NULL, NULL, &fence) == VK_SUCCESS);
    assert(slept_until == expected);
    assert(xr_display_last_tick(test_now, 0, test_period) == 0);
    assert(xr_display_last_tick(test_now, test_prediction, 0) == 0);
    assert(xr_display_last_tick(test_now, test_prediction, 100000000) == 0);
    puts("Display timing passed: XR phase, exact periods, monotonic counters, interrupted waits and non-XR fallback");
}
