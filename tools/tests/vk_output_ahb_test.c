#include <assert.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "vk_loader.h"

#define PROP_VALUE_MAX 92
#define AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM 1
#define AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM 0x2b
#define AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE 0x100ULL
#define AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT 0x200ULL
#define AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY 0x800ULL
#define AHARDWAREBUFFER_USAGE_PROTECTED_CONTENT 0x4000ULL
#define AHARDWAREBUFFER_USAGE_GPU_MIPMAP_COMPLETE (1ULL << 26)
typedef struct {
    uint32_t width, height, layers, format;
    uint64_t usage;
} AHardwareBuffer_Desc;
typedef struct AHardwareBuffer { int references; AHardwareBuffer_Desc desc; } AHardwareBuffer;
static AHardwareBuffer buffer;
static VkDevice g_dev = (VkDevice)(uintptr_t)1;
static VkPhysicalDevice g_pd = (VkPhysicalDevice)(uintptr_t)2;
static uint32_t g_vendor_id;
static char *g_library_name;
static VkPhysicalDeviceMemoryProperties g_memprops;
struct vk_api g_vk;
static PFN_vkGetAndroidHardwareBufferPropertiesANDROID g_ahb_properties;
static VkAndroidHardwareBufferFormatPropertiesANDROID returned_format;
static VkAndroidHardwareBufferPropertiesANDROID returned_memory;
static VkImageFormatProperties returned_limits;
static VkExternalMemoryProperties returned_external;
static VkFormatFeatureFlags typed_features;
static int fail_init, force_linear, fail_properties, fail_query, fail_create, fail_allocate, fail_bind;
static int properties_calls, create_calls, destroy_calls, allocate_calls, free_calls, bind_calls;
static uint32_t allocated_type;
static int dev_init(void) { return fail_init ? -1 : 0; }
#define __system_property_get test_system_property_get
static int test_system_property_get(const char *name, char *value) {
    assert(strcmp(name, "debug.droiddeck.linear_output") == 0);
    strcpy(value, force_linear ? "1" : ""); return force_linear;
}
static void AHardwareBuffer_describe(const AHardwareBuffer *b, AHardwareBuffer_Desc *d) { *d = b->desc; }
static void AHardwareBuffer_acquire(AHardwareBuffer *b) { b->references++; }
static void AHardwareBuffer_release(AHardwareBuffer *b) { assert(b->references > 0); b->references--; }
static void blendp_forget_image(VkImage image) { (void)image; }
#define LOGE(...) ((void)0)
struct vkp_image;
void vkp_image_destroy(struct vkp_image *);
#include "vk_output_ahb_under_test.inc"

static VkResult properties(VkDevice dev, const struct AHardwareBuffer *b, VkAndroidHardwareBufferPropertiesANDROID *p) {
    assert(dev == g_dev && b == &buffer); properties_calls++;
    void *next = p->pNext; *p = returned_memory; p->pNext = next;
    *(VkAndroidHardwareBufferFormatPropertiesANDROID *)next = returned_format;
    return fail_properties ? VK_ERROR_INVALID_EXTERNAL_HANDLE : VK_SUCCESS;
}
static void formats(VkPhysicalDevice pd, VkFormat format, VkFormatProperties2 *p) {
    assert(pd == g_pd && format == returned_format.format);
    p->formatProperties.optimalTilingFeatures = typed_features;
}
static VkResult query(VkPhysicalDevice pd, const VkPhysicalDeviceImageFormatInfo2 *q, VkImageFormatProperties2 *p) {
    assert(pd == g_pd && q->format == returned_format.format);
    assert(q->type == VK_IMAGE_TYPE_2D && q->tiling == VK_IMAGE_TILING_OPTIMAL);
    assert(q->usage == VK_IMAGE_USAGE_TRANSFER_DST_BIT);
    const VkPhysicalDeviceExternalImageFormatInfo *ext = q->pNext;
    assert(ext->handleType == VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID);
    p->imageFormatProperties = returned_limits;
    ((VkExternalImageFormatProperties *)p->pNext)->externalMemoryProperties = returned_external;
    return fail_query ? VK_ERROR_FORMAT_NOT_SUPPORTED : VK_SUCCESS;
}
static VkResult create(VkDevice dev, const VkImageCreateInfo *c, const VkAllocationCallbacks *a, VkImage *image) {
    assert(dev == g_dev && !a); create_calls++;
    assert(c->format == returned_format.format && c->extent.width == buffer.desc.width && c->extent.height == buffer.desc.height);
    assert(c->extent.depth == 1 && c->arrayLayers == 1 && c->mipLevels == 1 && c->samples == VK_SAMPLE_COUNT_1_BIT);
    assert(c->tiling == VK_IMAGE_TILING_OPTIMAL && c->usage == VK_IMAGE_USAGE_TRANSFER_DST_BIT);
    assert(c->initialLayout == VK_IMAGE_LAYOUT_UNDEFINED && c->sharingMode == VK_SHARING_MODE_EXCLUSIVE);
    const VkExternalMemoryImageCreateInfo *ext = c->pNext;
    assert(ext->handleTypes == VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID);
    if (fail_create) return VK_ERROR_FORMAT_NOT_SUPPORTED;
    *image = (VkImage)(uintptr_t)3; return VK_SUCCESS;
}
static VkResult allocate(VkDevice dev, const VkMemoryAllocateInfo *a, const VkAllocationCallbacks *cb, VkDeviceMemory *memory) {
    assert(dev == g_dev && !cb); allocate_calls++;
    const VkMemoryDedicatedAllocateInfo *dedicated = a->pNext;
    assert(dedicated->sType == VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO && dedicated->image == (VkImage)(uintptr_t)3);
    const VkImportAndroidHardwareBufferInfoANDROID *import = dedicated->pNext;
    assert(import->sType == VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID && import->buffer == &buffer);
    assert(a->allocationSize == returned_memory.allocationSize);
    allocated_type = a->memoryTypeIndex;
    if (fail_allocate) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    buffer.references++; *memory = (VkDeviceMemory)(uintptr_t)4; return VK_SUCCESS;
}
static VkResult bind(VkDevice dev, VkImage image, VkDeviceMemory memory, VkDeviceSize offset) {
    assert(dev == g_dev && image == (VkImage)(uintptr_t)3 && memory == (VkDeviceMemory)(uintptr_t)4 && offset == 0);
    bind_calls++; return fail_bind ? VK_ERROR_OUT_OF_DEVICE_MEMORY : VK_SUCCESS;
}
static void destroy(VkDevice dev, VkImage image, const VkAllocationCallbacks *a) {
    assert(dev == g_dev && image == (VkImage)(uintptr_t)3 && !a); destroy_calls++;
}
static void free_memory(VkDevice dev, VkDeviceMemory mem, const VkAllocationCallbacks *a) {
    assert(dev == g_dev && mem == (VkDeviceMemory)(uintptr_t)4 && !a);
    assert(destroy_calls == 1); free_calls++; AHardwareBuffer_release(&buffer);
}
static void reset(void) {
    buffer = (AHardwareBuffer){.references = 1, .desc = {.width = 1280, .height = 720, .layers = 1,
        .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
        .usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY}};
    g_vendor_id = 0x1010; g_library_name = NULL; g_memprops.memoryTypeCount = 3;
    g_ahb_properties = properties;
    g_vk = (struct vk_api){.GetPhysicalDeviceFormatProperties2 = formats, .GetPhysicalDeviceImageFormatProperties2 = query,
        .CreateImage = create, .AllocateMemory = allocate, .BindImageMemory = bind, .DestroyImage = destroy, .FreeMemory = free_memory};
    returned_format = (VkAndroidHardwareBufferFormatPropertiesANDROID){.format = VK_FORMAT_R8G8B8A8_UNORM};
    returned_memory = (VkAndroidHardwareBufferPropertiesANDROID){.allocationSize = 4096, .memoryTypeBits = 4};
    returned_limits = (VkImageFormatProperties){.maxExtent = {4096, 4096, 1}, .maxMipLevels = 1, .maxArrayLayers = 1,
        .sampleCounts = VK_SAMPLE_COUNT_1_BIT};
    returned_external = (VkExternalMemoryProperties){.externalMemoryFeatures = VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT,
        .compatibleHandleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID};
    typed_features = VK_FORMAT_FEATURE_BLIT_DST_BIT;
    fail_init = force_linear = fail_properties = fail_query = fail_create = fail_allocate = fail_bind = 0;
    properties_calls = create_calls = destroy_calls = allocate_calls = free_calls = bind_calls = 0;
}
static struct vkp_image *import(void) { return vkp_image_import_output_ahb(&buffer, 1280, 720, buffer.desc.format); }
static void reject(void) { assert(!import()); assert(create_calls == 0 && buffer.references == 1); }
int main(void) {
    reset();
    struct vkp_image *img = import();
    assert(img && img->blit_dst && img->dmabuf && !img->sampled && img->w == 1280 && img->h == 720);
    assert(allocated_type == 2 && buffer.references == 3);
    vkp_image_destroy(img);
    assert(buffer.references == 1 && destroy_calls == 1 && free_calls == 1);
    reset(); buffer.desc.format = AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM;
    returned_format.format = VK_FORMAT_A2B10G10R10_UNORM_PACK32;
    img = import(); assert(img && img->fmt == returned_format.format); vkp_image_destroy(img); assert(buffer.references == 1);

    reset(); g_vendor_id = 0x5143; reject(); assert(!properties_calls);
    reset(); g_library_name = "libvulkan_freedreno.so"; reject(); assert(!properties_calls);
    reset(); force_linear = 1; reject(); assert(!properties_calls);
    reset(); fail_init = 1; reject(); assert(!properties_calls);
    reset(); g_ahb_properties = NULL; reject();
    reset(); g_vk.GetPhysicalDeviceFormatProperties2 = NULL; reject();
    reset(); g_vk.GetPhysicalDeviceImageFormatProperties2 = NULL; reject();
    reset(); assert(!vkp_image_import_output_ahb(NULL, 1280, 720, buffer.desc.format));
    reset(); assert(!vkp_image_import_output_ahb(&buffer, 0, 720, buffer.desc.format));
    reset(); buffer.desc.width++; reject(); assert(!properties_calls);
    reset(); buffer.desc.height++; reject();
    reset(); buffer.desc.layers = 2; reject();
    reset(); buffer.desc.format = 0x100; reject();
    reset(); buffer.desc.usage &= ~AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT; reject();
    reset(); buffer.desc.usage &= ~AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY; reject();
    reset(); buffer.desc.usage |= AHARDWAREBUFFER_USAGE_PROTECTED_CONTENT; reject();
    reset(); buffer.desc.usage |= AHARDWAREBUFFER_USAGE_GPU_MIPMAP_COMPLETE; reject();
    reset(); fail_properties = 1; reject();
    reset(); returned_format.format = VK_FORMAT_UNDEFINED; reject();
    reset(); returned_format.format = VK_FORMAT_B8G8R8A8_UNORM; reject();
    reset(); returned_memory.allocationSize = 0; reject();
    reset(); returned_memory.memoryTypeBits = 0; reject();
    reset(); returned_format.formatFeatures = VK_FORMAT_FEATURE_BLIT_DST_BIT; typed_features = 0; reject();
    reset(); fail_query = 1; reject();
    reset(); returned_external.externalMemoryFeatures = 0; reject();
    reset(); returned_external.compatibleHandleTypes = 0; reject();
    reset(); returned_limits.maxExtent.width = 1279; reject();
    reset(); returned_limits.maxExtent.height = 719; reject();
    reset(); returned_limits.maxExtent.depth = 0; reject();
    reset(); returned_limits.maxMipLevels = 0; reject();
    reset(); returned_limits.maxArrayLayers = 0; reject();
    reset(); returned_limits.sampleCounts = VK_SAMPLE_COUNT_4_BIT; reject();
    reset(); returned_memory.memoryTypeBits = 1u << 31; reject();
    reset(); g_memprops.memoryTypeCount = 0; reject();
    reset(); returned_memory.memoryTypeBits = 1u << 31; g_memprops.memoryTypeCount = 32;
    img = import(); assert(img && allocated_type == 31); vkp_image_destroy(img);

    reset(); fail_create = 1; assert(!import());
    assert(create_calls == 1 && destroy_calls == 0 && allocate_calls == 0 && buffer.references == 1);
    reset(); fail_allocate = 1; assert(!import());
    assert(destroy_calls == 1 && free_calls == 0 && bind_calls == 0 && buffer.references == 1);
    reset(); fail_bind = 1; assert(!import());
    assert(destroy_calls == 1 && free_calls == 1 && buffer.references == 1);
    return 0;
}
