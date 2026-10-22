#define _GNU_SOURCE
#include <assert.h>
#include <stdatomic.h>
#include "../src/vk_layer_droiddeck_vr.c"

static VkDevice test_device;
static VkQueue test_queue;
static VkSemaphore gate;
static VkCommandBuffer commands[2];
static atomic_int second_returned;
static atomic_int second_entered;

static void *signal_gate(void *argument) {
    (void)argument;
    usleep(30000);
    VkSemaphoreSignalInfo signal_info = {VK_STRUCTURE_TYPE_SEMAPHORE_SIGNAL_INFO, NULL, gate, 1};
    assert(vkSignalSemaphore(test_device, &signal_info) == VK_SUCCESS);
    return NULL;
}

static void *second_submit(void *argument) {
    (void)argument;
    VkSubmitInfo submit = {VK_STRUCTURE_TYPE_SUBMIT_INFO, NULL, 0, NULL, NULL, 1, &commands[1], 0, NULL};
    atomic_store(&second_entered, 1);
    assert(QueueSubmit(test_queue, 1, &submit, VK_NULL_HANDLE) == VK_SUCCESS);
    atomic_store(&second_returned, 1);
    return NULL;
}

static void check_pixels(const uint32_t *pixels, uint32_t expected) {
    for (unsigned index = 0; index < 1024; index++) assert(pixels[index] == expected);
}

int main(void) {
    setenv("DROIDDECK_VR_LAYER_QUIET", "1", 1);
    VkApplicationInfo application = {VK_STRUCTURE_TYPE_APPLICATION_INFO, NULL, "DroidDeck producer GPU test", 1, NULL, 0, VK_API_VERSION_1_2};
    VkInstanceCreateInfo create_instance = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, NULL, 0, &application, 0, NULL, 0, NULL};
    VkInstance instance;
    assert(vkCreateInstance(&create_instance, NULL, &instance) == VK_SUCCESS);
    uint32_t count = 1;
    VkPhysicalDevice physical;
    VkResult enumeration = vkEnumeratePhysicalDevices(instance, &count, &physical);
    assert((enumeration == VK_SUCCESS || enumeration == VK_INCOMPLETE) && count);
    VkQueueFamilyProperties families[32];
    count = 32;
    vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, families);
    uint32_t family = UINT32_MAX;
    for (uint32_t index = 0; index < count; index++)
        if (families[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) { family = index; break; }
    assert(family != UINT32_MAX);
    float priority = 1;
    VkDeviceQueueCreateInfo queue_info = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, NULL, 0, family, 1, &priority};
    VkPhysicalDeviceTimelineSemaphoreFeatures timeline_feature = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES, NULL, VK_TRUE};
    VkDeviceCreateInfo create_device = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, &timeline_feature, 0, 1, &queue_info, 0, NULL, 0, NULL, NULL};
    assert(vkCreateDevice(physical, &create_device, NULL, &test_device) == VK_SUCCESS);
    vkGetDeviceQueue(test_device, family, 0, &test_queue);
    g_dev[0] = (struct dev){test_device, vkGetDeviceProcAddr, NULL, 1, 0, 1, 0};
    g_producer_started = 1;
    VkBufferCreateInfo create_buffer = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, NULL, 0, 4096, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                                         VK_SHARING_MODE_EXCLUSIVE, 0, NULL};
    VkBuffer buffer;
    assert(vkCreateBuffer(test_device, &create_buffer, NULL, &buffer) == VK_SUCCESS);
    VkMemoryRequirements requirements;
    vkGetBufferMemoryRequirements(test_device, buffer, &requirements);
    VkPhysicalDeviceMemoryProperties properties;
    vkGetPhysicalDeviceMemoryProperties(physical, &properties);
    uint32_t memory_type = UINT32_MAX;
    VkMemoryPropertyFlags required = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    for (uint32_t index = 0; index < properties.memoryTypeCount; index++)
        if ((requirements.memoryTypeBits & (1u << index)) && (properties.memoryTypes[index].propertyFlags & required) == required) {
            memory_type = index;
            break;
        }
    assert(memory_type != UINT32_MAX);
    VkMemoryAllocateInfo allocate = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, NULL, requirements.size, memory_type};
    VkDeviceMemory memory;
    assert(vkAllocateMemory(test_device, &allocate, NULL, &memory) == VK_SUCCESS);
    assert(vkBindBufferMemory(test_device, buffer, memory, 0) == VK_SUCCESS);
    uint32_t *pixels;
    assert(vkMapMemory(test_device, memory, 0, VK_WHOLE_SIZE, 0, (void **)&pixels) == VK_SUCCESS);
    VkCommandPoolCreateInfo create_pool = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, NULL, 0, family};
    VkCommandPool pool;
    assert(vkCreateCommandPool(test_device, &create_pool, NULL, &pool) == VK_SUCCESS);
    VkCommandBufferAllocateInfo allocate_commands = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, NULL, pool, VK_COMMAND_BUFFER_LEVEL_PRIMARY, 2};
    assert(vkAllocateCommandBuffers(test_device, &allocate_commands, commands) == VK_SUCCESS);
    const uint32_t patterns[2] = {0x13579bdf, 0x2468ace0};
    for (unsigned index = 0; index < 2; index++) {
        VkCommandBufferBeginInfo begin = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, NULL, 0, NULL};
        assert(vkBeginCommandBuffer(commands[index], &begin) == VK_SUCCESS);
        vkCmdFillBuffer(commands[index], buffer, 0, 4096, patterns[index]);
        VkMemoryBarrier barrier = {VK_STRUCTURE_TYPE_MEMORY_BARRIER, NULL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT};
        vkCmdPipelineBarrier(commands[index], VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &barrier, 0, NULL, 0, NULL);
        assert(vkEndCommandBuffer(commands[index]) == VK_SUCCESS);
    }
    VkSemaphoreTypeCreateInfo type = {VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO, NULL, VK_SEMAPHORE_TYPE_TIMELINE, 0};
    VkSemaphoreCreateInfo create_gate = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, &type, 0};
    assert(vkCreateSemaphore(test_device, &create_gate, NULL, &gate) == VK_SUCCESS);
    uint64_t value = 1;
    VkTimelineSemaphoreSubmitInfo timeline = {VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO, NULL, 1, &value, 0, NULL};
    VkPipelineStageFlags stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkSubmitInfo first = {VK_STRUCTURE_TYPE_SUBMIT_INFO, &timeline, 1, &gate, &stage, 1, &commands[0], 0, NULL};
    assert(QueueSubmit(test_queue, 1, &first, VK_NULL_HANDLE) == VK_SUCCESS);
    pthread_t signal_thread;
    int64_t began = droiddeck_producer_now();
    assert(pthread_create(&signal_thread, NULL, signal_gate, NULL) == 0);
    assert(producer_acquire(began + 1000000000LL));
    assert(droiddeck_producer_now() - began >= 20000000LL);
    assert(pthread_join(signal_thread, NULL) == 0);
    check_pixels(pixels, patterns[0]);
    pthread_t writer;
    assert(pthread_create(&writer, NULL, second_submit, NULL) == 0);
    while (!atomic_load(&second_entered)) usleep(100);
    usleep(50000);
    assert(!atomic_load(&second_returned));
    check_pixels(pixels, patterns[0]);
    producer_release();
    assert(pthread_join(writer, NULL) == 0);
    assert(vkQueueWaitIdle(test_queue) == VK_SUCCESS);
    check_pixels(pixels, patterns[1]);
    value = 2;
    assert(QueueSubmit(test_queue, 1, &first, VK_NULL_HANDLE) == VK_SUCCESS);
    assert(!producer_acquire(droiddeck_producer_now() + 20000000LL));
    VkFence pending = g_producer_queues[0].fence;
    assert(pending && vkGetFenceStatus(test_device, pending) == VK_NOT_READY);
    assert(!producer_acquire(droiddeck_producer_now() + 20000000LL));
    assert(g_producer_queues[0].fence == pending);
    VkSemaphoreSignalInfo release_gate = {VK_STRUCTURE_TYPE_SEMAPHORE_SIGNAL_INFO, NULL, gate, 2};
    assert(vkSignalSemaphore(test_device, &release_gate) == VK_SUCCESS);
    assert(vkQueueWaitIdle(test_queue) == VK_SUCCESS);
    assert(producer_acquire(droiddeck_producer_now() + 1000000000LL));
    producer_release();
    check_pixels(pixels, patterns[0]);
    producer_destroy_device(&g_dev[0], NULL);
    vkDestroySemaphore(test_device, gate, NULL);
    vkDestroyCommandPool(test_device, pool, NULL);
    vkUnmapMemory(test_device, memory);
    vkDestroyBuffer(test_device, buffer, NULL);
    vkFreeMemory(test_device, memory, NULL);
    vkDestroyDevice(test_device, NULL);
    vkDestroyInstance(instance, NULL);
    puts("producer GPU: actual completion, frozen overwrite, timeout retention and 1024 buffer values passed");
    return 0;
}
