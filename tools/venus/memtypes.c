// Prints the system Vulkan driver's memory types and heaps, as Android apps see them; Venus
// rewrites them for the guest. Build in WSL and run over ADB (from Git Bash, MSYS_NO_PATHCONV=1):
//   /home/delo/android-sdk/ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android31-clang \
//     -O1 -o memtypes memtypes.c -lvulkan
//   adb push memtypes /data/local/tmp/ && adb shell "chmod 755 /data/local/tmp/memtypes && /data/local/tmp/memtypes"
#include <stdio.h>
#include <vulkan/vulkan.h>

int main(void)
{
    VkApplicationInfo app = { .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO, .apiVersion = VK_API_VERSION_1_1 };
    VkInstanceCreateInfo ici = { .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app };
    VkInstance inst;
    if (vkCreateInstance(&ici, NULL, &inst) != VK_SUCCESS) {
        printf("vkCreateInstance failed\n");
        return 1;
    }
    uint32_t count = 1;
    VkPhysicalDevice pd;
    vkEnumeratePhysicalDevices(inst, &count, &pd);
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(pd, &props);
    printf("%s\n", props.deviceName);
    VkPhysicalDeviceMemoryProperties mp;
    vkGetPhysicalDeviceMemoryProperties(pd, &mp);
    for (uint32_t i = 0; i < mp.memoryHeapCount; i++)
        printf("heap %u: %llu MiB flags 0x%x\n", i, (unsigned long long)(mp.memoryHeaps[i].size >> 20), mp.memoryHeaps[i].flags);
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++) {
        VkMemoryPropertyFlags f = mp.memoryTypes[i].propertyFlags;
        printf("type %u: heap %u flags 0x%x%s%s%s%s%s%s\n", i, mp.memoryTypes[i].heapIndex, f,
               f & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT ? " DEVICE_LOCAL" : "",
               f & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT ? " HOST_VISIBLE" : "",
               f & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT ? " HOST_COHERENT" : "",
               f & VK_MEMORY_PROPERTY_HOST_CACHED_BIT ? " HOST_CACHED" : "",
               f & VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT ? " LAZY" : "",
               f & VK_MEMORY_PROPERTY_PROTECTED_BIT ? " PROTECTED" : "");
    }
    vkDestroyInstance(inst, NULL);
    return 0;
}
