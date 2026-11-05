#include <assert.h>
#include <inttypes.h>
#include "../src/vk_layer_droiddeck_vramcap.c"

static int instance_dispatch, device_dispatch;
static void *instance_object = &instance_dispatch, *physical_object = &instance_dispatch;
static void *device_object = &device_dispatch;
static VkInstance instance;
static VkPhysicalDevice physical;
static VkDevice device;
static unsigned allocations, frees, destroyed_devices, destroyed_instances;

static void VKAPI_CALL memory_properties(VkPhysicalDevice pd, VkPhysicalDeviceMemoryProperties *out) {
    assert(pd == physical);
    *out = (VkPhysicalDeviceMemoryProperties){
        .memoryHeapCount = 3,
        .memoryHeaps = {{8ULL << 30, VK_MEMORY_HEAP_DEVICE_LOCAL_BIT}, {4ULL << 30, 0},
                       {512ULL << 20, VK_MEMORY_HEAP_DEVICE_LOCAL_BIT}},
        .memoryTypeCount = 1,
        .memoryTypes = {{VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, 0}},
    };
}

static void VKAPI_CALL memory_properties2(VkPhysicalDevice pd, VkPhysicalDeviceMemoryProperties2 *out) {
    memory_properties(pd, &out->memoryProperties);
    for (VkBaseOutStructure *p = out->pNext; p; p = p->pNext)
        if (p->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_BUDGET_PROPERTIES_EXT) {
            VkPhysicalDeviceMemoryBudgetPropertiesEXT *b = (void *)p;
            b->heapBudget[0] = 6ULL << 30;
            b->heapBudget[1] = 3ULL << 30;
            b->heapBudget[2] = 256ULL << 20;
            b->heapUsage[0] = 3ULL << 30;
        }
}

static VkResult VKAPI_CALL allocate(VkDevice d, const VkMemoryAllocateInfo *info,
                                    const VkAllocationCallbacks *a, VkDeviceMemory *out) {
    assert(d == device && info->allocationSize == (1536ULL << 20));
    *out = (VkDeviceMemory)(uintptr_t)++allocations;
    return VK_SUCCESS;
}

static void VKAPI_CALL release(VkDevice d, VkDeviceMemory m, const VkAllocationCallbacks *a) {
    assert(d == device && m);
    frees++;
}

static void VKAPI_CALL destroy_device(VkDevice d, const VkAllocationCallbacks *a) {
    assert(d == device);
    destroyed_devices++;
}

static void VKAPI_CALL destroy_instance(VkInstance in, const VkAllocationCallbacks *a) {
    assert(in == instance);
    destroyed_instances++;
}

static PFN_vkVoidFunction VKAPI_CALL gdpa(VkDevice d, const char *name) {
    assert(d == device);
    if (!strcmp(name, "vkAllocateMemory")) return (PFN_vkVoidFunction)allocate;
    if (!strcmp(name, "vkFreeMemory")) return (PFN_vkVoidFunction)release;
    if (!strcmp(name, "vkDestroyDevice")) return (PFN_vkVoidFunction)destroy_device;
    return NULL;
}

static VkResult VKAPI_CALL create_device(VkPhysicalDevice pd, const VkDeviceCreateInfo *ci,
                                         const VkAllocationCallbacks *a, VkDevice *out) {
    assert(pd == physical);
    *out = device;
    return VK_SUCCESS;
}

static VkResult VKAPI_CALL create_instance(const VkInstanceCreateInfo *ci, const VkAllocationCallbacks *a, VkInstance *out) {
    *out = instance;
    return VK_SUCCESS;
}

static PFN_vkVoidFunction VKAPI_CALL gipa(VkInstance in, const char *name) {
    assert(in == instance || in == VK_NULL_HANDLE);
    if (!strcmp(name, "vkCreateInstance")) return (PFN_vkVoidFunction)create_instance;
    if (!strcmp(name, "vkDestroyInstance")) return (PFN_vkVoidFunction)destroy_instance;
    if (!strcmp(name, "vkCreateDevice")) return (PFN_vkVoidFunction)create_device;
    if (!strcmp(name, "vkGetPhysicalDeviceMemoryProperties")) return (PFN_vkVoidFunction)memory_properties;
    if (!strcmp(name, "vkGetPhysicalDeviceMemoryProperties2") || !strcmp(name, "vkGetPhysicalDeviceMemoryProperties2KHR"))
        return (PFN_vkVoidFunction)memory_properties2;
    return NULL;
}

int main(void) {
    assert(vramcap_parse_mb("2048") == (2ULL << 30));
    assert(vramcap_parse_mb(NULL) == 0 && vramcap_parse_mb("") == 0 && vramcap_parse_mb("0") == 0);
    const char *bad[] = {"-1", "+1", " 1024", "1024 ", "1GB", "18446744073709551616", "17592186044416"};
    for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); i++) assert(vramcap_parse_mb(bad[i]) == 0);
    assert(vramcap_parse_mb("17592186044415") == ((UINT64_MAX >> 20) << 20));

    instance = (VkInstance)&instance_object;
    physical = (VkPhysicalDevice)&physical_object;
    device = (VkDevice)&device_object;
    g_cap = 2ULL << 30;
    VkLayerInstanceLink il = {.pfnNextGetInstanceProcAddr = gipa};
    VkLayerInstanceCreateInfo ic = {.sType = VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO,
                                   .function = VK_LAYER_LINK_INFO, .u.pLayerInfo = &il};
    VkInstanceCreateInfo ici = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pNext = &ic};
    VkInstance got_instance;
    assert(CreateInstance(&ici, NULL, &got_instance) == VK_SUCCESS && got_instance == instance);
    VkPhysicalDeviceMemoryProperties p;
    PFN_vkGetPhysicalDeviceMemoryProperties get = (void *)DroidDeckVramCap_GetInstanceProcAddr(instance, "vkGetPhysicalDeviceMemoryProperties");
    get(physical, &p);
    assert(p.memoryHeaps[0].size == g_cap && p.memoryHeaps[1].size == (4ULL << 30));
    assert(p.memoryHeaps[2].size == (512ULL << 20));
    assert(p.memoryTypes[0].propertyFlags == (VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT));

    const char *names[] = {"vkGetPhysicalDeviceMemoryProperties2", "vkGetPhysicalDeviceMemoryProperties2KHR"};
    for (size_t i = 0; i < 2; i++) {
        VkPhysicalDeviceMemoryBudgetPropertiesEXT b = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_BUDGET_PROPERTIES_EXT};
        VkPhysicalDeviceIDProperties id = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES, .pNext = &b};
        VkPhysicalDeviceMemoryProperties2 p2 = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_PROPERTIES_2, .pNext = &id};
        PFN_vkGetPhysicalDeviceMemoryProperties2 get2 = (void *)DroidDeckVramCap_GetInstanceProcAddr(instance, names[i]);
        get2(physical, &p2);
        assert(b.heapBudget[0] == g_cap && b.heapBudget[1] == (3ULL << 30));
        assert(b.heapBudget[2] == (256ULL << 20));
        assert(b.heapUsage[0] == (3ULL << 30));
        assert(p2.pNext == &id && id.pNext == &b);
    }

    VkLayerDeviceLink dl = {.pfnNextGetInstanceProcAddr = gipa, .pfnNextGetDeviceProcAddr = gdpa};
    VkLayerDeviceCreateInfo dc = {.sType = VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO,
                                 .function = VK_LAYER_LINK_INFO, .u.pLayerInfo = &dl};
    VkDeviceCreateInfo dci = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, .pNext = &dc};
    VkDevice got_device;
    assert(CreateDevice(physical, &dci, NULL, &got_device) == VK_SUCCESS && got_device == device);
    PFN_vkAllocateMemory next_allocate = (void *)DroidDeckVramCap_GetDeviceProcAddr(device, "vkAllocateMemory");
    PFN_vkFreeMemory next_free = (void *)DroidDeckVramCap_GetDeviceProcAddr(device, "vkFreeMemory");
    assert(next_allocate == allocate && next_free == release);
    VkMemoryAllocateInfo ai = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = 1536ULL << 20};
    VkDeviceMemory blocks[2];
    for (unsigned i = 0; i < 2; i++) assert(next_allocate(device, &ai, NULL, &blocks[i]) == VK_SUCCESS);
    assert(allocations == 2);
    for (unsigned i = 0; i < 2; i++) next_free(device, blocks[i], NULL);
    assert(frees == 2);

    g_cap = 0;
    assert(DroidDeckVramCap_GetInstanceProcAddr(instance, names[0]) == (PFN_vkVoidFunction)memory_properties2);
    assert(DroidDeckVramCap_GetInstanceProcAddr(instance, "vkGetPhysicalDeviceMemoryProperties") == (PFN_vkVoidFunction)memory_properties);
    PFN_vkDestroyDevice destroy = (void *)DroidDeckVramCap_GetInstanceProcAddr(instance, "vkDestroyDevice");
    assert(destroy == DestroyDevice);
    destroy(device, NULL);
    DestroyInstance(instance, NULL);
    assert(destroyed_devices == 1 && destroyed_instances == 1 && !g_devs && !g_insts);
    puts("test_vramcap_budget: ok");
    return 0;
}
