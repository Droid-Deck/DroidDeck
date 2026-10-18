#define _GNU_SOURCE
#include <ucontext.h>
#include <assert.h>
#include <stdint.h>

#include "../src/vk_layer_droiddeck_vr.c"

static _Atomic uint64_t local_value;
static _Atomic unsigned duplicate_signals;
static pthread_t submit_thread;
static int first_read = 1;

static VkResult VKAPI_CALL get_value(VkDevice device, VkSemaphore semaphore, uint64_t *value) {
    (void)device;
    (void)semaphore;
    *value = atomic_load(&local_value);
    if (pthread_equal(pthread_self(), submit_thread) && first_read) {
        first_read = 0;
        assert(pthread_create(&g_poller, NULL, poller, NULL) == 0);
        const uint64_t until = now_us() + 2000000;
        while (!atomic_load(&local_value) && now_us() < until) usleep(100);
        assert(atomic_load(&local_value) == 1);
    }
    return VK_SUCCESS;
}

static VkResult VKAPI_CALL signal_value(VkDevice device, const VkSemaphoreSignalInfo *info) {
    (void)device;
    if (info->value <= atomic_load(&local_value)) {
        atomic_fetch_add(&duplicate_signals, 1);
        return VK_ERROR_DEVICE_LOST;
    }
    atomic_store(&local_value, info->value);
    return VK_SUCCESS;
}

static PFN_vkVoidFunction VKAPI_CALL get_proc(VkDevice device, const char *name) {
    (void)device;
    if (!strcmp(name, "vkGetSemaphoreCounterValue")) return (PFN_vkVoidFunction)get_value;
    if (!strcmp(name, "vkSignalSemaphore")) return (PFN_vkVoidFunction)signal_value;
    assert(0);
    return NULL;
}

int main(void) {
    uintptr_t dispatch = 1;
    VkDevice device = (VkDevice)&dispatch;
    VkSemaphore semaphore = (VkSemaphore)(uintptr_t)2;
    _Atomic uint64_t shared = 1;
    g_dev[0] = (struct dev){.handle = device, .gdpa = get_proc};
    g_br[0] = (struct bridged){.sem = semaphore, .dev = device, .counter = &shared,
        .fd = -1, .timeline = 1, .seen = 1, .seen_us = now_us(), .imported = 1};
    submit_thread = pthread_self();

    submit_drop_wait(semaphore, 1);
    assert(atomic_load(&local_value) == 1);
    assert(atomic_load(&duplicate_signals) == 0);
    assert(submit_drop_wait(semaphore, 1) == 0);
    pthread_cancel(g_poller);
    pthread_join(g_poller, NULL);
    puts("PASS: a concurrent bridge signal cannot be repeated by the submit thread");
    return 0;
}
