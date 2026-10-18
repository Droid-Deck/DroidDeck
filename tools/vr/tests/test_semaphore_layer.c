#define _GNU_SOURCE
#include <assert.h>
#include <fcntl.h>
#include "../src/vk_layer_droiddeck_vr.c"

static const VkSubmitInfo *expected_submit;
static const VkSubmitInfo2 *expected_submit2;
static const VkSemaphoreWaitInfo *expected_wait;
static unsigned create_calls;
static unsigned destroyed;
static VkResult submit_result = VK_SUCCESS;
static int expect_dropped_wait;
static const void *expected_image_chain;
static const void *expected_external_tail;
static int expect_cloned_external;

static VkResult VKAPI_CALL mock_image(VkDevice device, const VkImageCreateInfo *info,
                                      const VkAllocationCallbacks *allocator, VkImage *output) {
    (void)device; (void)allocator; (void)output;
    if (expect_cloned_external) {
        const VkExternalMemoryImageCreateInfo *external = info->pNext;
        assert(external != expected_image_chain);
        assert(external->sType == VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO);
        assert(external->handleTypes == VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);
        assert(external->pNext == expected_external_tail);
    } else assert(info->pNext == expected_image_chain);
    return VK_ERROR_FORMAT_NOT_SUPPORTED;
}

static void check_shared_images(VkDevice device) {
    VkBaseInStructure tail = {VK_STRUCTURE_TYPE_APPLICATION_INFO, NULL};
    VkExternalMemoryImageCreateInfo external = {VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
        &tail, VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT};
    VkFormat formats[] = {VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_R8G8B8A8_UNORM};
    VkImageFormatListCreateInfo list = {VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO, &external, 2, formats};
    VkImageCreateInfo image = {0};
    image.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    image.pNext = &list;
    image.flags = VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
    image.tiling = VK_IMAGE_TILING_OPTIMAL;
    VkImage output;
    /* Other targets, nonmutable, linear and DMA-BUF images retain their caller's chain. */
    expected_image_chain = &list;
    assert(CreateImage(device, &image, NULL, &output) == VK_ERROR_FORMAT_NOT_SUPPORTED);
    g_dev[0].quest_images = 1;
    image.arrayLayers = 1;
    assert(CreateImage(device, &image, NULL, &output) == VK_ERROR_FORMAT_NOT_SUPPORTED);
    image.arrayLayers = 2;
    image.flags = 0;
    assert(CreateImage(device, &image, NULL, &output) == VK_ERROR_FORMAT_NOT_SUPPORTED);
    image.flags = VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
    image.tiling = VK_IMAGE_TILING_LINEAR;
    assert(CreateImage(device, &image, NULL, &output) == VK_ERROR_FORMAT_NOT_SUPPORTED);
    image.tiling = VK_IMAGE_TILING_OPTIMAL;
    external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
    assert(CreateImage(device, &image, NULL, &output) == VK_ERROR_FORMAT_NOT_SUPPORTED);
    external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
    expected_image_chain = &external;
    assert(CreateImage(device, &image, NULL, &output) == VK_ERROR_FORMAT_NOT_SUPPORTED);
    assert(image.pNext == &list && list.pNext == &external && external.pNext == &tail);
    /* The reverse chain order preserves its tail without modifying caller memory. */
    external.pNext = &list; list.pNext = &tail; image.pNext = &external;
    expect_cloned_external = 1; expected_external_tail = &tail;
    assert(CreateImage(device, &image, NULL, &output) == VK_ERROR_FORMAT_NOT_SUPPORTED);
    assert(image.pNext == &external && external.pNext == &list && list.pNext == &tail);
    expect_cloned_external = 0; g_dev[0].quest_images = 0;
}

static VkResult VKAPI_CALL mock_submit(VkQueue queue, uint32_t count, const VkSubmitInfo *submits, VkFence fence) {
    assert(queue && count == 1 && fence == VK_NULL_HANDLE);
    if (expect_dropped_wait) {
        assert(submits != expected_submit && submits->waitSemaphoreCount == 0);
        const VkTimelineSemaphoreSubmitInfo *timeline = submits->pNext;
        assert(timeline->sType == VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO && timeline->waitSemaphoreValueCount == 0);
        assert(expected_submit->waitSemaphoreCount == 1);
        return submit_result;
    }
    assert(submits == expected_submit);
    assert(submits->waitSemaphoreCount == 1);
    return submit_result;
}

static VkResult VKAPI_CALL mock_submit2(VkQueue queue, uint32_t count, const VkSubmitInfo2 *submits, VkFence fence) {
    assert(queue && count == 1 && fence == VK_NULL_HANDLE);
    assert(submits == expected_submit2);
    assert(submits->waitSemaphoreInfoCount == 1);
    return submit_result;
}

static VkResult VKAPI_CALL mock_wait(VkDevice device, const VkSemaphoreWaitInfo *info, uint64_t timeout) {
    assert(device && info == expected_wait && timeout == 7654321);
    assert(info->semaphoreCount == 65);
    return VK_TIMEOUT;
}

static VkResult VKAPI_CALL mock_create(VkDevice device, const VkSemaphoreCreateInfo *info,
                                      const VkAllocationCallbacks *allocator, VkSemaphore *output) {
    (void)device;
    (void)allocator;
    create_calls++;
    for (const VkBaseInStructure *item = info->pNext; item; item = item->pNext)
        if (item->sType == VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO) return VK_ERROR_INVALID_EXTERNAL_HANDLE;
    *output = (VkSemaphore)(uintptr_t)(create_calls + 100);
    return VK_SUCCESS;
}

static void VKAPI_CALL mock_destroy(VkDevice device, VkSemaphore semaphore, const VkAllocationCallbacks *allocator) {
    (void)device;
    (void)semaphore;
    (void)allocator;
    destroyed++;
}

static int expect_queue_lock;
static VkQueue other_queue;
static _Atomic int other_submitted;

static void *signal_from_other_queue(void *unused) {
    (void)unused;
    assert(QueueSubmit(other_queue, 1, expected_submit, VK_NULL_HANDLE) == VK_SUCCESS);
    atomic_store(&other_submitted, 1);
    return NULL;
}

static void check_queue_lock(void) {
    int acquired = pthread_mutex_trylock(&g_submission_lock);
    assert(acquired == 0);
    if (!acquired) pthread_mutex_unlock(&g_submission_lock);
    assert(g_producer_idle_calls == (unsigned)expect_queue_lock);
    if (expect_queue_lock) assert(!producer_acquire(droiddeck_producer_now() + 10000000LL));
}

static VkResult VKAPI_CALL mock_queue_idle(VkQueue queue) {
    assert(queue); check_queue_lock();
    if (expect_queue_lock) {
        VkSubmitInfo signal = {VK_STRUCTURE_TYPE_SUBMIT_INFO, NULL, 1, NULL, NULL, 0, NULL, 0, NULL};
        expected_submit = &signal;
        pthread_t worker; atomic_store(&other_submitted, 0);
        assert(pthread_create(&worker, NULL, signal_from_other_queue, NULL) == 0);
        int64_t deadline = droiddeck_producer_now() + 1000000000LL;
        while (!atomic_load(&other_submitted) && droiddeck_producer_now() < deadline) usleep(1000);
        assert(atomic_load(&other_submitted)); pthread_join(worker, NULL);
    }
    return VK_ERROR_DEVICE_LOST;
}

static VkResult VKAPI_CALL mock_device_idle(VkDevice device) {
    assert(device); check_queue_lock(); return VK_ERROR_DEVICE_LOST;
}

static VkResult VKAPI_CALL mock_counter(VkDevice device, VkSemaphore semaphore, uint64_t *value) {
    (void)device; (void)semaphore;
    *value = 0;
    return VK_SUCCESS;
}

static VkResult VKAPI_CALL mock_signal(VkDevice device, const VkSemaphoreSignalInfo *info) {
    (void)device; (void)info;
    assert(0);
    return VK_SUCCESS;
}

static PFN_vkVoidFunction VKAPI_CALL mock_proc(VkDevice device, const char *name) {
    (void)device;
    if (!strcmp(name, "vkGetSemaphoreCounterValue")) return (PFN_vkVoidFunction)mock_counter;
    if (!strcmp(name, "vkSignalSemaphore")) return (PFN_vkVoidFunction)mock_signal;
    if (!strcmp(name, "vkCreateImage")) return (PFN_vkVoidFunction)mock_image;
    if (!strcmp(name, "vkQueueWaitIdle")) return (PFN_vkVoidFunction)mock_queue_idle;
    if (!strcmp(name, "vkDeviceWaitIdle")) return (PFN_vkVoidFunction)mock_device_idle;
    if (!strcmp(name, "vkQueueSubmit")) return (PFN_vkVoidFunction)mock_submit;
    if (!strcmp(name, "vkQueueSubmit2")) return (PFN_vkVoidFunction)mock_submit2;
    if (!strcmp(name, "vkWaitSemaphores")) return (PFN_vkVoidFunction)mock_wait;
    if (!strcmp(name, "vkCreateSemaphore")) return (PFN_vkVoidFunction)mock_create;
    if (!strcmp(name, "vkDestroySemaphore")) return (PFN_vkVoidFunction)mock_destroy;
    assert(0);
    return NULL;
}

int main(void) {
    setenv("DROIDDECK_VR_LAYER_QUIET", "1", 1);
    void *dispatch = &dispatch;
    VkDevice device = (VkDevice)&dispatch;
    VkQueue queue = (VkQueue)&dispatch;
    void *other_dispatch = dispatch;
    other_queue = (VkQueue)&other_dispatch;
    g_producer_retry_at = INT64_MAX;
    g_dev[0] = (struct dev){device, mock_proc, NULL, 1, 0, 0, 0};
    check_shared_images(device);
    g_poller_started = 1;
    PFN_vkQueueWaitIdle queue_idle = (PFN_vkQueueWaitIdle)semaphore_hook("vkQueueWaitIdle");
    PFN_vkDeviceWaitIdle device_idle = (PFN_vkDeviceWaitIdle)semaphore_hook("vkDeviceWaitIdle");
    assert(queue_idle && device_idle);
    for (int producer = 0; producer <= 1; producer++) {
        g_dev[0].producer = producer;
        expect_queue_lock = producer;
        assert(queue_idle(queue) == VK_ERROR_DEVICE_LOST);
        assert(device_idle(device) == VK_ERROR_DEVICE_LOST);
        assert(g_producer_idle_calls == 0);
    }
    g_dev[0].producer = 0;
    VkExportSemaphoreCreateInfo export_info = {VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO, NULL,
                                               VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT};
    VkSemaphoreCreateInfo create = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, &export_info, 0};
    VkSemaphore semaphore = VK_NULL_HANDLE;
    assert(CreateSemaphore(device, &create, NULL, &semaphore) == VK_ERROR_INVALID_EXTERNAL_HANDLE);
    assert(create_calls == 1 && !semaphore);
    VkSemaphoreTypeCreateInfo type = {VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO, &export_info,
                                      VK_SEMAPHORE_TYPE_TIMELINE, 0};
    create.pNext = &type;
    assert(CreateSemaphore(device, &create, NULL, &semaphore) == VK_SUCCESS);
    struct bridged *bridge = bridged_find(semaphore);
    assert(bridge && atomic_load(bridge->counter) == 0);
    VkPipelineStageFlags stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    uint64_t value = 9;
    VkTimelineSemaphoreSubmitInfo timeline = {VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO, NULL, 1, &value, 0, NULL};
    VkSubmitInfo submit = {VK_STRUCTURE_TYPE_SUBMIT_INFO, &timeline, 1, &semaphore, &stage, 0, NULL, 0, NULL};
    expected_submit = &submit;
    assert(QueueSubmit(queue, 1, &submit, VK_NULL_HANDLE) == VK_SUCCESS);
    assert(atomic_load(bridge->counter) == 0);
    VkSemaphoreSubmitInfo wait2 = {VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO, NULL, semaphore, value,
                                  VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, 0};
    VkSubmitInfo2 submit2 = {VK_STRUCTURE_TYPE_SUBMIT_INFO_2, NULL, 0, 1, &wait2, 0, NULL, 0, NULL};
    expected_submit2 = &submit2;
    assert(QueueSubmit2(queue, 1, &submit2, VK_NULL_HANDLE) == VK_SUCCESS);
    submit_result = VK_ERROR_DEVICE_LOST;
    assert(QueueSubmit(queue, 1, &submit, VK_NULL_HANDLE) == VK_ERROR_DEVICE_LOST);
    assert(QueueSubmit2(queue, 1, &submit2, VK_NULL_HANDLE) == VK_ERROR_DEVICE_LOST);
    VkSemaphore semaphores[65];
    uint64_t values[65];
    for (unsigned index = 0; index < 65; index++) { semaphores[index] = semaphore; values[index] = value; }
    VkSemaphoreWaitInfo wait = {VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO, NULL, VK_SEMAPHORE_WAIT_ANY_BIT, 65, semaphores, values};
    expected_wait = &wait;
    assert(WaitSemaphores(device, &wait, 7654321) == VK_TIMEOUT);
    int descriptor = dup(bridge->fd);
    VkSemaphore binary = (VkSemaphore)(uintptr_t)999;
    VkImportSemaphoreFdInfoKHR import_info = {VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR, NULL, binary, 0,
                                             VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT, descriptor};
    assert(ImportSemaphoreFdKHR(device, &import_info) == VK_ERROR_INVALID_EXTERNAL_HANDLE);
    assert(fcntl(descriptor, F_GETFD) >= 0);
    timeline_note(binary, 1);
    assert(ftruncate(descriptor, 32) == 0);
    assert(ImportSemaphoreFdKHR(device, &import_info) == VK_ERROR_INVALID_EXTERNAL_HANDLE);
    assert(ftruncate(descriptor, 4096) == 0);
    import_info.flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT;
    assert(ImportSemaphoreFdKHR(device, &import_info) == VK_ERROR_INVALID_EXTERNAL_HANDLE);
    import_info.flags = 0;
    assert(ImportSemaphoreFdKHR(device, &import_info) == VK_SUCCESS);
    VkSubmitInfo imported_wait = submit;
    imported_wait.pWaitSemaphores = &binary;
    expected_submit = &imported_wait;
    submit_result = VK_SUCCESS;
    expect_dropped_wait = 1;
    assert(QueueSubmit(queue, 1, &imported_wait, VK_NULL_HANDLE) == VK_SUCCESS);
    expect_dropped_wait = 0;
    VkSemaphoreGetFdInfoKHR get_info = {VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR, NULL, binary,
                                       VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT};
    int reexported = -1;
    assert(GetSemaphoreFdKHR(device, &get_info, &reexported) == VK_SUCCESS && reexported >= 0);
    assert(fd_ino(reexported) == fd_ino(descriptor));
    close(reexported);
    get_info.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    assert(GetSemaphoreFdKHR(device, &get_info, &reexported) == VK_ERROR_INVALID_EXTERNAL_HANDLE);
    DestroySemaphore(device, binary, NULL);
    assert(fcntl(descriptor, F_GETFD) == -1 && errno == EBADF);
    DestroySemaphore(device, semaphore, NULL);
    assert(destroyed == 2 && !bridged_find(semaphore));
    puts("semaphore layer: pending waits retained, unsignalled imported waits dropped, errors propagated, unsupported binary imports refused, fd ownership passed");
    return 0;
}
