#include <dlfcn.h>
#include <stddef.h>
#include <string.h>

#ifndef VULKAN_THUNK
#define VULKAN_THUNK "/usr/lib/x86_64-linux-gnu/libvulkan.so.1"
#endif

#define VK_INCOMPLETE 5
#define VK_ERROR_INITIALIZATION_FAILED (-3)
#define EXTENSIONS 128

typedef int VkResult;
typedef void (*Function)(void);
typedef struct {
    char extensionName[256];
    unsigned int specVersion;
} VkExtensionProperties;
typedef VkResult (*Enumerate)(const char *, unsigned int *, VkExtensionProperties *);
typedef Function (*ProcAddr)(void *, const char *);

/* The driver cannot enumerate devices for an instance with VK_KHR_display, and gamescope's WSI layer aborts on that. */
static const char *const hidden_extensions[] = {
    "VK_KHR_display",
    "VK_KHR_get_display_properties2",
    "VK_EXT_direct_mode_display",
    "VK_EXT_acquire_xlib_display",
    "VK_EXT_acquire_drm_display",
    "VK_EXT_display_surface_counter",
};

static void *thunk(const char *name)
{
    void *library = dlopen(VULKAN_THUNK, RTLD_NOW | RTLD_NOLOAD);
    void *found = library ? dlsym(library, name) : NULL;
    if (library)
        dlclose(library);
    return found;
}

static int hidden(const char *name)
{
    for (size_t i = 0; i < sizeof(hidden_extensions) / sizeof(hidden_extensions[0]); i++)
        if (!strcmp(name, hidden_extensions[i]))
            return 1;
    return 0;
}

__attribute__((visibility("default"))) VkResult vkEnumerateInstanceExtensionProperties(const char *layer, unsigned int *count,
                                                                                        VkExtensionProperties *properties)
{
    static Enumerate real;
    VkExtensionProperties all[EXTENSIONS];
    unsigned int total = EXTENSIONS, kept = 0, room = properties ? *count : 0;
    VkResult result;
    if (!real)
        real = (Enumerate)thunk("vkEnumerateInstanceExtensionProperties");
    if (!real)
        return VK_ERROR_INITIALIZATION_FAILED;
    result = real(layer, &total, all);
    if (result < 0)
        return result;
    for (unsigned int i = 0; i < total; i++) {
        if (hidden(all[i].extensionName))
            continue;
        if (properties && kept < room)
            memcpy(&properties[kept], &all[i], sizeof(all[i]));
        kept++;
    }
    if (!properties) {
        *count = kept;
        return result;
    }
    *count = kept < room ? kept : room;
    return kept > room ? VK_INCOMPLETE : result;
}

__attribute__((visibility("default"))) Function vkGetInstanceProcAddr(void *instance, const char *name)
{
    static ProcAddr real;
    if (!strcmp(name, "vkGetInstanceProcAddr"))
        return (Function)vkGetInstanceProcAddr;
    if (!strcmp(name, "vkEnumerateInstanceExtensionProperties"))
        return (Function)vkEnumerateInstanceExtensionProperties;
    if (!real)
        real = (ProcAddr)thunk("vkGetInstanceProcAddr");
    return real ? real(instance, name) : NULL;
}
