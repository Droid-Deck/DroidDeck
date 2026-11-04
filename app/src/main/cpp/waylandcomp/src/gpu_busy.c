#include "gpu_busy.h"

#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>

#include "gpuload_ring.h"

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static struct gpuload_vk g_v;
static struct gpuload_ring g_ring;
static uint32_t g_family, g_bits;
static PFN_vkQueueSubmit g_submit;
static int g_was_on;
static _Atomic int g_on, g_lost, g_timing;
static _Atomic uint64_t g_busy_ns;

static VkResult VKAPI_CALL timed_submit(VkQueue queue, uint32_t count, const VkSubmitInfo *infos, VkFence fence) {
    if (!count || !atomic_load(&g_on) || atomic_load(&g_lost)) return g_submit(queue, count, infos, fence);
    pthread_mutex_lock(&g_lock);
    if (!atomic_load(&g_on) || atomic_load(&g_lost)) {
        pthread_mutex_unlock(&g_lock);
        return g_submit(queue, count, infos, fence);
    }
    if (g_ring.state == 0 && gpuload_ring_setup(&g_ring, &g_v, g_family, g_bits)) atomic_store(&g_timing, 1);
    VkSubmitInfo *mine = NULL;
    uint32_t used = 0;
    if (g_ring.state == 1) {
        uint64_t ns = gpuload_ring_harvest(&g_ring, &g_v);
        if (g_was_on) atomic_fetch_add(&g_busy_ns, ns);  // the first harvest after a pause holds work timed before it
        g_was_on = 1;
        mine = gpuload_ring_wrap(&g_ring, count, infos, &used);
    }
    VkResult r = g_submit(queue, count, mine ? mine : infos, fence);
    if (mine && r != VK_SUCCESS) gpuload_ring_unwind(&g_ring, used);
    pthread_mutex_unlock(&g_lock);
    free(mine);
    return r;
}

void vkp_gpu_busy_device(VkPhysicalDevice pd, VkDevice dev, uint32_t family) {
    pthread_mutex_lock(&g_lock);
    memset(&g_ring, 0, sizeof(g_ring));
    g_was_on = 0;
    atomic_store(&g_timing, 0);
    atomic_store(&g_lost, 0);
    PFN_vkGetDeviceProcAddr gdpa = g_vk.GetDeviceProcAddr;
    g_v = (struct gpuload_vk){
        .device = dev,
        .create_pool = g_vk.CreateCommandPool,
        .destroy_pool = (PFN_vkDestroyCommandPool)gdpa(dev, "vkDestroyCommandPool"),
        .alloc_cmds = g_vk.AllocateCommandBuffers,
        .begin = g_vk.BeginCommandBuffer,
        .end = g_vk.EndCommandBuffer,
        .cmd_reset = (PFN_vkCmdResetQueryPool)gdpa(dev, "vkCmdResetQueryPool"),
        .cmd_timestamp = (PFN_vkCmdWriteTimestamp)gdpa(dev, "vkCmdWriteTimestamp"),
        .create_queries = (PFN_vkCreateQueryPool)gdpa(dev, "vkCreateQueryPool"),
        .destroy_queries = (PFN_vkDestroyQueryPool)gdpa(dev, "vkDestroyQueryPool"),
        .results = (PFN_vkGetQueryPoolResults)gdpa(dev, "vkGetQueryPoolResults"),
    };
    VkPhysicalDeviceProperties props;
    g_vk.GetPhysicalDeviceProperties(pd, &props);
    gpuload_vk_period(&g_v, props.limits.timestampPeriod);
    VkQueueFamilyProperties families[16];
    uint32_t n = 16;
    g_vk.GetPhysicalDeviceQueueFamilyProperties(pd, &n, families);
    g_family = family;
    g_bits = family < n ? families[family].timestampValidBits : 0;
    // vk_loader_load_device has just put the driver's function back.
    if (g_vk.QueueSubmit != timed_submit) g_submit = g_vk.QueueSubmit;
    if (g_submit) g_vk.QueueSubmit = timed_submit;
    pthread_mutex_unlock(&g_lock);
}

void vkp_gpu_busy_device_lost(void) {
    atomic_store(&g_lost, 1);
    atomic_store(&g_timing, 0);
}

JNIEXPORT jlong JNICALL Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeGpuBusyNs(JNIEnv *env, jclass cls) {
    return atomic_load(&g_timing) ? (jlong)atomic_load(&g_busy_ns) : -1;
}

JNIEXPORT void JNICALL Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetGpuTiming(JNIEnv *env, jclass cls, jboolean on) {
    pthread_mutex_lock(&g_lock);
    if (!on) g_was_on = 0;
    atomic_store(&g_on, on ? 1 : 0);
    pthread_mutex_unlock(&g_lock);
}
