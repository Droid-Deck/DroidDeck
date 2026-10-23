#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

static PFN_vkGetInstanceProcAddr next_instance;
static PFN_vkGetDeviceProcAddr next_device;
static int gamescope;
static PFN_vkVoidFunction VKAPI_CALL instance_proc(VkInstance, const char *);
static PFN_vkVoidFunction VKAPI_CALL device_proc(VkDevice, const char *);

static VkResult VKAPI_CALL create_instance(const VkInstanceCreateInfo *info, const VkAllocationCallbacks *allocator, VkInstance *instance) {
    VkLayerInstanceCreateInfo *chain = (void *)info->pNext;
    while (chain && (chain->sType != VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO || chain->function != VK_LAYER_LINK_INFO)) chain = (void *)chain->pNext;
    if (!chain) return VK_ERROR_INITIALIZATION_FAILED;
    next_instance = chain->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    gamescope = info->pApplicationInfo && info->pApplicationInfo->pApplicationName &&
        !strcmp(info->pApplicationInfo->pApplicationName, "gamescope");
    PFN_vkCreateInstance create = (void *)next_instance(0, "vkCreateInstance");
    chain->u.pLayerInfo = chain->u.pLayerInfo->pNext;
    return create(info, allocator, instance);
}

static VkResult VKAPI_CALL create_device(VkPhysicalDevice physical, const VkDeviceCreateInfo *info, const VkAllocationCallbacks *allocator, VkDevice *device) {
    VkLayerDeviceCreateInfo *chain = (void *)info->pNext;
    while (chain && (chain->sType != VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO || chain->function != VK_LAYER_LINK_INFO)) chain = (void *)chain->pNext;
    if (!chain) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr get = chain->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    next_device = chain->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    PFN_vkCreateDevice create = (void *)get(0, "vkCreateDevice");
    chain->u.pLayerInfo = chain->u.pLayerInfo->pNext;
    return create(physical, info, allocator, device);
}

static uint64_t fingerprint(const void *data, size_t size) {
    const unsigned char *bytes = data;
    uint64_t hash = 14695981039346656037ULL;
    while (size--) hash = (hash ^ *bytes++) * 1099511628211ULL;
    return hash;
}

static VkResult VKAPI_CALL create_shader(VkDevice device, const VkShaderModuleCreateInfo *info, const VkAllocationCallbacks *allocator, VkShaderModule *module) {
    PFN_vkCreateShaderModule create = (void *)next_device(device, "vkCreateShaderModule");
    const char *directory = getenv("DROIDDECK_QUALCOMM_SHADERS");
    void *data = 0;
    VkShaderModuleCreateInfo replacement = *info;
    if (directory && gamescope) {
        char path[4096];
        snprintf(path, sizeof(path), "%s/%016llx.spv", directory, (unsigned long long)fingerprint(info->pCode, info->codeSize));
        FILE *file = fopen(path, "rb");
        if (file) {
            fseek(file, 0, SEEK_END);
            long size = ftell(file);
            rewind(file);
            if (size > 20 && size < 4194304 && !(size & 3)) {
                data = malloc(size);
                if (data && fread(data, 1, size, file) == (size_t)size && *(const uint32_t *)data == 0x07230203) {
                    replacement.codeSize = size;
                    replacement.pCode = data;
                    fprintf(stderr, "Qualcomm: substituting Gamescope shader %016llx\n", (unsigned long long)fingerprint(info->pCode, info->codeSize));
                }
            }
            fclose(file);
        }
    }
    VkResult result = create(device, &replacement, allocator, module);
    free(data);
    return result;
}

static PFN_vkVoidFunction VKAPI_CALL device_proc(VkDevice device, const char *name) {
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (void *)device_proc;
    if (!strcmp(name, "vkCreateShaderModule")) return (void *)create_shader;
    return next_device ? next_device(device, name) : 0;
}

static PFN_vkVoidFunction VKAPI_CALL instance_proc(VkInstance instance, const char *name) {
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (void *)instance_proc;
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (void *)device_proc;
    if (!strcmp(name, "vkCreateInstance")) return (void *)create_instance;
    if (!strcmp(name, "vkCreateDevice")) return (void *)create_device;
    if (!strcmp(name, "vkCreateShaderModule")) return (void *)create_shader;
    return next_instance ? next_instance(instance, name) : 0;
}

VKAPI_ATTR VkResult VKAPI_CALL vkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface *interface) {
    if (interface->loaderLayerInterfaceVersion > 2) interface->loaderLayerInterfaceVersion = 2;
    interface->pfnGetInstanceProcAddr = instance_proc;
    interface->pfnGetDeviceProcAddr = device_proc;
    interface->pfnGetPhysicalDeviceProcAddr = 0;
    return VK_SUCCESS;
}
