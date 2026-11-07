#define VK_NO_PROTOTYPES
#include <assert.h>
#include <string.h>
#include <vulkan/vulkan.h>
#include "shader_policy.h"
#include "layer.h"
#include "robustness2.h"

static int virtual_cull = 1;
static VkResult VKAPI_CALL native_extensions(VkPhysicalDevice physical, const char *layer,
    uint32_t *count, VkExtensionProperties *properties)
{
    (void)physical; (void)layer;
    static const char *names[] = {VK_EXT_GRAPHICS_PIPELINE_LIBRARY_EXTENSION_NAME,
        VK_EXT_SHADER_OBJECT_EXTENSION_NAME, VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    if (!properties) { *count = 3; return VK_SUCCESS; }
    uint32_t n = *count < 3 ? *count : 3;
    for (uint32_t i = 0; i < n; ++i) {
        memset(&properties[i], 0, sizeof(properties[i]));
        strcpy(properties[i].extensionName, names[i]);
    }
    *count = n;
    return n < 3 ? VK_INCOMPLETE : VK_SUCCESS;
}
static PFN_vkVoidFunction VKAPI_CALL resolver(VkInstance instance, const char *name)
{
    (void)instance;
    return !strcmp(name, "vkEnumerateDeviceExtensionProperties") ?
        (PFN_vkVoidFunction)native_extensions : NULL;
}
int hybris_layer_lookup_physical(VkPhysicalDevice physical, struct hybris_layer_physical *out)
{ (void)physical; *out = (struct hybris_layer_physical){.resolver = resolver}; return 1; }
int hybris_clip_active(VkPhysicalDevice p) { (void)p; return 0; }
int hybris_cull_active(VkPhysicalDevice p) { (void)p; return virtual_cull; }
int hybris_stage_point_active(VkPhysicalDevice p) { (void)p; return 0; }
int hybris_clip_extension_allowed(const char *n)
{ return strcmp(n, VK_EXT_GRAPHICS_PIPELINE_LIBRARY_EXTENSION_NAME) && strcmp(n, VK_EXT_SHADER_OBJECT_EXTENSION_NAME); }
int hybris_vertex_stores_active(VkPhysicalDevice p) { (void)p; return 0; }
int hybris_shader_polygon_emulated(VkPhysicalDevice p) { (void)p; return 0; }
int hybris_dual_source_active(VkPhysicalDevice p) { (void)p; return 0; }
int hybris_dynamic_emulated(VkPhysicalDevice p) { (void)p; return 0; }
int hybris_maintenance5_emulated(VkPhysicalDevice p) { (void)p; return 0; }
int hybris_dynamic_native_extension(VkPhysicalDevice p, const char *n) { (void)p; (void)n; return 0; }
unsigned hybris_bc_physical_mask(VkPhysicalDevice p) { (void)p; return 0; }
int hybris_bc_enabled(void) { return 0; }
int hybris_scaled_enabled(void) { return 0; }
unsigned hybris_scaled_physical_mask(VkPhysicalDevice p,
    PFN_vkGetPhysicalDeviceFormatProperties q) { (void)p; (void)q; return 0; }
int hybris_bc_extension_allowed(const char *n) { (void)n; return 1; }
int hybris_multiviewport_active(VkPhysicalDevice p) { (void)p; return 0; }
int hybris_multidraw_active(VkPhysicalDevice p) { (void)p; return 0; }
VkResult hybris_robustness2_policy(VkPhysicalDevice p, struct hybris_robustness2_policy *o)
{ (void)p; memset(o, 0, sizeof(*o)); return VK_SUCCESS; }
void hybris_robustness2_features(VkPhysicalDevice p, VkPhysicalDeviceFeatures2 *o) { (void)p; (void)o; }
void hybris_dynamic_features(VkPhysicalDevice p, VkPhysicalDeviceFeatures2 *o) { (void)p; (void)o; }
void hybris_maintenance5_features(VkPhysicalDevice p, VkPhysicalDeviceFeatures2 *o) { (void)p; (void)o; }
void hybris_maintenance5_properties(VkPhysicalDevice p, VkPhysicalDeviceProperties2 *o) { (void)p; (void)o; }
PFN_vkVoidFunction hybris_bc_policy_proc(const char *n) { (void)n; return NULL; }
PFN_vkVoidFunction hybris_vendor_physical_proc(const char *n) { (void)n; return NULL; }
PFN_vkVoidFunction hybris_robustness2_physical_proc(const char *n) { (void)n; return NULL; }
PFN_vkVoidFunction hybris_clip_policy_proc(const char *n) { (void)n; return NULL; }
int hybris_scaled_format(PFN_vkGetPhysicalDeviceFormatProperties q, VkPhysicalDevice p,
    VkFormat f, VkFormatProperties *o) { (void)q; (void)p; (void)f; (void)o; return 0; }
int hybris_bc_format_properties(VkPhysicalDevice p, VkFormat f, VkFormatProperties *o)
{ (void)p; (void)f; (void)o; return 0; }

int main(void)
{
    VkPhysicalDevice physical = (VkPhysicalDevice)1;
    const char *extension = VK_EXT_SHADER_OBJECT_EXTENSION_NAME;
    VkDeviceCreateInfo device = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .enabledExtensionCount = 1, .ppEnabledExtensionNames = &extension};
    assert(hybris_shader_prepare_device(physical, &device) == VK_ERROR_EXTENSION_NOT_PRESENT);
    VkPhysicalDeviceShaderObjectFeaturesEXT shader = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_OBJECT_FEATURES_EXT, .shaderObject = VK_TRUE};
    device.enabledExtensionCount = 0; device.pNext = &shader;
    assert(hybris_shader_prepare_device(physical, &device) == VK_ERROR_FEATURE_NOT_PRESENT);
    PFN_vkEnumerateDeviceExtensionProperties enumerate =
        (PFN_vkEnumerateDeviceExtensionProperties)hybris_layer_physical_proc("vkEnumerateDeviceExtensionProperties");
    assert(enumerate);
    VkExtensionProperties properties[3]; uint32_t count = 3;
    assert(enumerate(physical, NULL, &count, properties) == VK_SUCCESS);
    assert(count == 1 && !strcmp(properties[0].extensionName, VK_KHR_SWAPCHAIN_EXTENSION_NAME));
    virtual_cull = 0; count = 3;
    assert(enumerate(physical, NULL, &count, properties) == VK_SUCCESS && count == 3);
    return 0;
}
