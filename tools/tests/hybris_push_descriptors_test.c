/* Compile this only against the fully patched libhybris tree. */
#define VK_NO_PROTOTYPES
#include "bc_context.h"
#include "bc_policy.h"
#include "layer.h"
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define DEVICE ((VkDevice)(uintptr_t)0x10)
#define COMMAND ((VkCommandBuffer)(uintptr_t)0x20)
#define LAYOUT ((VkPipelineLayout)(uintptr_t)0x30)
#define BC_VIEW ((VkImageView)(uintptr_t)0xbc)
#define NATIVE_VIEW ((VkImageView)(uintptr_t)0x42)

static struct hybris_bc_device device;
static struct hybris_bc_command command;
static unsigned old_calls, info2_calls, template_calls, template2_calls, pipeline_calls;
static VkShaderStageFlags replay_stages;
static VkDescriptorImageInfo replay_images[2];
static VkDescriptorBufferInfo replay_buffer;
static unsigned replay_write_count;
static unsigned char replay_template[2 * sizeof(VkDescriptorImageInfo) + sizeof(VkDescriptorBufferInfo)];
static size_t replay_template_size;
static size_t expected_template_size;
static VkDescriptorUpdateTemplate replay_template_handle;
static unsigned fail_alloc;
static unsigned extension_calls;
static VkStructureType extension_type;
static uint64_t extension_values[2];
static void reset_state(void);

static void VKAPI_CALL physical_properties(VkPhysicalDevice physical,
    VkPhysicalDeviceProperties *out)
{
    (void)physical; memset(out, 0, sizeof(*out));
    out->apiVersion = VK_API_VERSION_1_4;
    out->limits.maxStorageBufferRange = 1024;
    out->limits.minStorageBufferOffsetAlignment = 16;
}
static void VKAPI_CALL physical_queues(VkPhysicalDevice physical, uint32_t *count,
    VkQueueFamilyProperties *out)
{
    (void)physical;
    if (!out) { *count = 1; return; }
    *count = 1;
    out[0] = (VkQueueFamilyProperties){.queueFlags = VK_QUEUE_COMPUTE_BIT,
        .minImageTransferGranularity = {1, 1, 1}};
}
static void VKAPI_CALL physical_formats(VkPhysicalDevice physical, VkFormat format,
    VkFormatProperties *out)
{
    (void)physical; (void)format;
    *out = (VkFormatProperties){.optimalTilingFeatures = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
        VK_FORMAT_FEATURE_TRANSFER_SRC_BIT | VK_FORMAT_FEATURE_TRANSFER_DST_BIT};
}
static void VKAPI_CALL physical_features2(VkPhysicalDevice physical,
    VkPhysicalDeviceFeatures2 *out)
{
    (void)physical;
    for (VkBaseOutStructure *node = out->pNext; node; node = node->pNext) {
        if (node->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_4_FEATURES)
            ((VkPhysicalDeviceVulkan14Features *)node)->pushDescriptor = VK_TRUE;
        if (node->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_6_FEATURES_KHR)
            ((VkPhysicalDeviceMaintenance6FeaturesKHR *)node)->maintenance6 = VK_TRUE;
    }
}
static PFN_vkVoidFunction VKAPI_CALL physical_resolver(VkInstance instance, const char *name)
{
    (void)instance;
#define PHYSICAL_ENTRY(text, function) if (!strcmp(name, text)) return (PFN_vkVoidFunction)function
    PHYSICAL_ENTRY("vkGetPhysicalDeviceProperties", physical_properties);
    PHYSICAL_ENTRY("vkGetPhysicalDeviceProperties2", physical_features2);
    PHYSICAL_ENTRY("vkGetPhysicalDeviceFeatures2", physical_features2);
    PHYSICAL_ENTRY("vkGetPhysicalDeviceQueueFamilyProperties", physical_queues);
    PHYSICAL_ENTRY("vkGetPhysicalDeviceFormatProperties", physical_formats);
#undef PHYSICAL_ENTRY
    return NULL;
}
int hybris_layer_lookup_physical(VkPhysicalDevice physical, struct hybris_layer_physical *out)
{
    if (physical != (VkPhysicalDevice)(uintptr_t)0x40) return 0;
    *out = (struct hybris_layer_physical){.instance = (VkInstance)(uintptr_t)0x41,
        .api_version = VK_API_VERSION_1_4, .resolver = physical_resolver};
    return 1;
}
int hybris_vendor_full_bc_feature(VkPhysicalDevice physical) { (void)physical; return 1; }
void hybris_vendor_adjust_bc_format(VkPhysicalDevice physical, VkFormat format,
    VkFormatProperties *properties) { (void)physical; (void)format; (void)properties; }
VkResult hybris_bc_device_add(VkDevice d, PFN_vkGetDeviceProcAddr r,
    const VkPhysicalDeviceMemoryProperties *m, const VkPhysicalDeviceProperties *p,
    unsigned mask, unsigned rgb, const VkAllocationCallbacks *a)
{ (void)d; (void)r; (void)m; (void)p; (void)mask; (void)rgb; (void)a; return VK_SUCCESS; }
VkFormat hybris_bc_image_format(VkFormat format, unsigned rgb_mask)
{ (void)rgb_mask; return format >= VK_FORMAT_BC1_RGB_UNORM_BLOCK && format <= VK_FORMAT_BC7_SRGB_BLOCK ? VK_FORMAT_R8G8B8A8_UNORM : VK_FORMAT_UNDEFINED; }
int hybris_bc_srgb_family(VkFormat format) { (void)format; return 1; }
int hybris_bc_view_compatible(VkFormat image, VkFormat view) { return image == view; }
PFN_vkVoidFunction hybris_bc_resources_proc(const char *name) { (void)name; return NULL; }
PFN_vkVoidFunction hybris_bc_record_proc(const char *name) { (void)name; return NULL; }
PFN_vkVoidFunction hybris_bc_barriers_proc(const char *name) { (void)name; return NULL; }
PFN_vkVoidFunction hybris_bc_image_copy_proc(const char *name) { (void)name; return NULL; }

static void clear_replay(void)
{
    old_calls = info2_calls = template_calls = template2_calls = pipeline_calls = 0;
    replay_stages = 0;
    replay_write_count = 0;
    memset(replay_images, 0, sizeof(replay_images));
    memset(&replay_buffer, 0, sizeof(replay_buffer));
    memset(replay_template, 0, sizeof(replay_template));
    replay_template_size = 0;
    replay_template_handle = VK_NULL_HANDLE;
    extension_calls = 0; extension_type = VK_STRUCTURE_TYPE_MAX_ENUM;
    extension_values[0] = extension_values[1] = 0;
}

void *push_test_calloc(size_t count, size_t size)
{
    if (fail_alloc) { --fail_alloc; return NULL; }
    size_t total = count * size;
    void *value = malloc(total ? total : 1);
    if (value) memset(value, 0, total);
    return value;
}

static void copy_writes(uint32_t count, const VkWriteDescriptorSet *writes)
{
    if (count == 1 && writes[0].pNext) {
        const VkBaseInStructure *node = writes[0].pNext;
        ++extension_calls; extension_type = node->sType;
        switch ((uint32_t)node->sType) {
#ifdef VK_KHR_acceleration_structure
        case VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR: {
            const VkWriteDescriptorSetAccelerationStructureKHR *p = (const void *)node;
            assert(p->accelerationStructureCount == 2);
            extension_values[0] = (uintptr_t)p->pAccelerationStructures[0];
            extension_values[1] = (uintptr_t)p->pAccelerationStructures[1]; break;
        }
#endif
#ifdef VK_NV_ray_tracing
        case VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_NV: {
            const VkWriteDescriptorSetAccelerationStructureNV *p = (const void *)node;
            assert(p->accelerationStructureCount == 2);
            extension_values[0] = (uintptr_t)p->pAccelerationStructures[0];
            extension_values[1] = (uintptr_t)p->pAccelerationStructures[1]; break;
        }
#endif
#ifdef VK_NV_partitioned_acceleration_structure
        case VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_PARTITIONED_ACCELERATION_STRUCTURE_NV: {
            const VkWriteDescriptorSetPartitionedAccelerationStructureNV *p = (const void *)node;
            assert(p->accelerationStructureCount == 2);
            extension_values[0] = p->pAccelerationStructures[0];
            extension_values[1] = p->pAccelerationStructures[1]; break;
        }
#endif
#ifdef HYBRIS_VK_ARM_TENSOR_PAYLOAD
        case VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_TENSOR_ARM: {
            const VkWriteDescriptorSetTensorARM *p = (const void *)node;
            assert(p->tensorViewCount == 2);
            extension_values[0] = (uintptr_t)p->pTensorViews[0];
            extension_values[1] = (uintptr_t)p->pTensorViews[1]; break;
        }
#endif
        default: assert(0);
        }
        return;
    }
    replay_write_count = count;
    assert(count == 2);
    assert(writes[0].descriptorType == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
#ifdef VK_QCOM_image_processing
        || writes[0].descriptorType == VK_DESCRIPTOR_TYPE_SAMPLE_WEIGHT_IMAGE_QCOM
        || writes[0].descriptorType == VK_DESCRIPTOR_TYPE_BLOCK_MATCH_IMAGE_QCOM
#endif
    );
    assert(writes[0].descriptorCount == 2);
    memcpy(replay_images, writes[0].pImageInfo, sizeof(replay_images));
    assert(writes[1].descriptorType == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
    memcpy(&replay_buffer, writes[1].pBufferInfo, sizeof(replay_buffer));
}

static void expect_extension(VkWriteDescriptorSet *write, uint64_t first, uint64_t second)
{
    clear_replay();
    ((PFN_vkCmdPushDescriptorSetKHR)hybris_bc_commands_proc("vkCmdPushDescriptorSetKHR"))(
        COMMAND, VK_PIPELINE_BIND_POINT_COMPUTE, LAYOUT, 2, 1, write);
    switch ((uint32_t)((const VkBaseInStructure *)write->pNext)->sType) {
#ifdef VK_KHR_acceleration_structure
    case VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR: {
        VkWriteDescriptorSetAccelerationStructureKHR *p = (void *)write->pNext;
        memset((void *)p->pAccelerationStructures, 0, 2 * sizeof(*p->pAccelerationStructures)); break;
    }
#endif
#ifdef VK_NV_ray_tracing
    case VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_NV: {
        VkWriteDescriptorSetAccelerationStructureNV *p = (void *)write->pNext;
        memset((void *)p->pAccelerationStructures, 0, 2 * sizeof(*p->pAccelerationStructures)); break;
    }
#endif
#ifdef VK_NV_partitioned_acceleration_structure
    case VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_PARTITIONED_ACCELERATION_STRUCTURE_NV: {
        VkWriteDescriptorSetPartitionedAccelerationStructureNV *p = (void *)write->pNext;
        memset((void *)p->pAccelerationStructures, 0, 2 * sizeof(*p->pAccelerationStructures)); break;
    }
#endif
#ifdef HYBRIS_VK_ARM_TENSOR_PAYLOAD
    case VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_TENSOR_ARM: {
        VkWriteDescriptorSetTensorARM *p = (void *)write->pNext;
        memset((void *)p->pTensorViews, 0, 2 * sizeof(*p->pTensorViews)); break;
    }
#endif
    default: assert(0);
    }
    clear_replay(); hybris_bc_restore_compute(&command.state, COMMAND);
    assert(extension_calls == 1 && extension_values[0] == first && extension_values[1] == second);
    reset_state();
}

static void VKAPI_CALL native_push(VkCommandBuffer command_buffer, VkPipelineBindPoint point,
    VkPipelineLayout layout, uint32_t set, uint32_t count, const VkWriteDescriptorSet *writes)
{
    (void)command_buffer; (void)layout; (void)set;
    assert(point == VK_PIPELINE_BIND_POINT_COMPUTE || point == VK_PIPELINE_BIND_POINT_GRAPHICS);
    ++old_calls;
    copy_writes(count, writes);
}
static void VKAPI_CALL native_push2(VkCommandBuffer command_buffer, const VkPushDescriptorSetInfo *info)
{
    (void)command_buffer;
    ++info2_calls;
    replay_stages = info->stageFlags;
    copy_writes(info->descriptorWriteCount, info->pDescriptorWrites);
}
static void copy_template(const void *data, VkDescriptorUpdateTemplate update_template)
{
    ++template_calls;
    replay_template_handle = update_template;
    replay_template_size = expected_template_size;
    memcpy(replay_template, data, replay_template_size);
}
static void VKAPI_CALL native_template(VkCommandBuffer command_buffer,
    VkDescriptorUpdateTemplate update_template, VkPipelineLayout layout, uint32_t set, const void *data)
{ (void)command_buffer; (void)layout; (void)set; copy_template(data, update_template); }
static void VKAPI_CALL native_template2(VkCommandBuffer command_buffer,
    const VkPushDescriptorSetWithTemplateInfo *info)
{
    (void)command_buffer;
    ++template2_calls;
    replay_template_handle = info->descriptorUpdateTemplate;
    replay_template_size = expected_template_size;
    memcpy(replay_template, info->pData, replay_template_size);
}
static VkResult VKAPI_CALL native_create_layout(VkDevice d, const VkPipelineLayoutCreateInfo *i,
    const VkAllocationCallbacks *a, VkPipelineLayout *out)
{ (void)d; (void)i; (void)a; *out = (VkPipelineLayout)(uintptr_t)0x31; return VK_SUCCESS; }
static void VKAPI_CALL native_destroy_layout(VkDevice d, VkPipelineLayout l, const VkAllocationCallbacks *a)
{ (void)d; (void)l; (void)a; }
static VkResult VKAPI_CALL native_create_template(VkDevice d,
    const VkDescriptorUpdateTemplateCreateInfo *i, const VkAllocationCallbacks *a,
    VkDescriptorUpdateTemplate *out)
{ static uintptr_t next = 0x51; (void)d; (void)i; (void)a; *out = (VkDescriptorUpdateTemplate)next++; return VK_SUCCESS; }
static void VKAPI_CALL native_destroy_template(VkDevice d, VkDescriptorUpdateTemplate t,
    const VkAllocationCallbacks *a) { (void)d; (void)t; (void)a; }
static void VKAPI_CALL native_pipeline(VkCommandBuffer c, VkPipelineBindPoint p, VkPipeline q)
{ (void)c; (void)p; (void)q; ++pipeline_calls; }
static VkResult VKAPI_CALL native_end(VkCommandBuffer c) { (void)c; return VK_SUCCESS; }

static PFN_vkVoidFunction VKAPI_CALL resolver(VkDevice d, const char *name)
{
    (void)d;
#define ENTRY(text, function) if (!strcmp(name, text)) return (PFN_vkVoidFunction)function
    ENTRY("vkCmdPushDescriptorSet", native_push);
    ENTRY("vkCmdPushDescriptorSetKHR", native_push);
    ENTRY("vkCmdPushDescriptorSet2", native_push2);
    ENTRY("vkCmdPushDescriptorSet2KHR", native_push2);
    ENTRY("vkCmdPushDescriptorSetWithTemplate", native_template);
    ENTRY("vkCmdPushDescriptorSetWithTemplateKHR", native_template);
    ENTRY("vkCmdPushDescriptorSetWithTemplate2", native_template2);
    ENTRY("vkCmdPushDescriptorSetWithTemplate2KHR", native_template2);
    ENTRY("vkCreatePipelineLayout", native_create_layout);
    ENTRY("vkDestroyPipelineLayout", native_destroy_layout);
    ENTRY("vkCreateDescriptorUpdateTemplate", native_create_template);
    ENTRY("vkCreateDescriptorUpdateTemplateKHR", native_create_template);
    ENTRY("vkDestroyDescriptorUpdateTemplate", native_destroy_template);
    ENTRY("vkDestroyDescriptorUpdateTemplateKHR", native_destroy_template);
    ENTRY("vkCmdBindPipeline", native_pipeline);
    ENTRY("vkEndCommandBuffer", native_end);
#undef ENTRY
    return NULL;
}

/* Commands not driven by this test still need their real production linkage. */
struct hybris_bc_device *hybris_bc_device_find(VkDevice d) { return d == DEVICE ? &device : NULL; }
struct hybris_bc_command *hybris_bc_command_find(VkCommandBuffer c, struct hybris_bc_device **owner)
{ if (c != COMMAND) { *owner = NULL; return NULL; } *owner = &device; return &command; }
void *hybris_bc_alloc(struct hybris_bc_device *d, size_t size) { (void)d; return push_test_calloc(1, size); }
void hybris_bc_free(struct hybris_bc_device *d, void *p) { (void)d; free(p); }
void hybris_bc_command_retire(struct hybris_bc_command *c) { hybris_bc_command_state_reset(&c->state); }
void hybris_bc_transfer_init(struct hybris_bc_transfer *t, const struct hybris_bc_decoder *d,
    const VkPhysicalDeviceMemoryProperties *m, const VkPhysicalDeviceLimits *l)
{ memset(t, 0, sizeof(*t)); t->decoder = d; t->memory = m; t->limits = l; }
void hybris_bc_transfer_finish(struct hybris_bc_transfer *t) { (void)t; }

static void reset_state(void)
{
    hybris_bc_command_state_reset(&command.state);
    clear_replay();
}
static void expect_images_and_buffer(const VkDescriptorImageInfo *images,
    const VkDescriptorBufferInfo *buffer)
{
    assert(replay_write_count == 2);
    assert(!memcmp(replay_images, images, sizeof(replay_images)));
    assert(!memcmp(&replay_buffer, buffer, sizeof(*buffer)));
}

int main(void)
{
    assert(!setenv("HYBRIS_BC_TEXTURES", "dxvk", 1));
    VkPhysicalDevice physical = (VkPhysicalDevice)(uintptr_t)0x40;
    assert(hybris_bc_extension_allowed(VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME));
    const char *extensions[] = {VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME,
        VK_KHR_MAINTENANCE_6_EXTENSION_NAME};
    VkDeviceCreateInfo device_info = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .enabledExtensionCount = 2, .ppEnabledExtensionNames = extensions};
    assert(hybris_bc_prepare_device(physical, &device_info) == VK_SUCCESS);
    VkPhysicalDeviceMaintenance6FeaturesKHR maintenance6 = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_6_FEATURES_KHR};
    VkPhysicalDeviceVulkan14Features version14 = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_4_FEATURES, .pNext = &maintenance6};
    VkPhysicalDeviceFeatures2 queried = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
        .pNext = &version14};
    PFN_vkGetPhysicalDeviceFeatures2 get_features = (PFN_vkGetPhysicalDeviceFeatures2)
        hybris_bc_policy_proc("vkGetPhysicalDeviceFeatures2");
    get_features(physical, &queried);
    assert(!version14.pushDescriptor && maintenance6.maintenance6);
    VkPhysicalDeviceProperties properties;
    ((PFN_vkGetPhysicalDeviceProperties)hybris_bc_policy_proc("vkGetPhysicalDeviceProperties"))(
        physical, &properties);
    assert(properties.apiVersion == VK_API_VERSION_1_3);

    device.handle = DEVICE; device.resolver = resolver; device.format_mask = 1;
    hybris_bc_layouts_init(&device.layouts, DEVICE, resolver);
    hybris_bc_command_state_init(&command.state, &device.layouts);
    VkPipelineLayoutCreateInfo layout_info = {.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    assert(hybris_bc_layout_add(&device.layouts, LAYOUT, &layout_info) == VK_SUCCESS);
    VkDescriptorImageInfo images[2] = {{.sampler = (VkSampler)1, .imageView = BC_VIEW,
        .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL}, {.sampler = (VkSampler)2,
        .imageView = NATIVE_VIEW, .imageLayout = VK_IMAGE_LAYOUT_GENERAL}};
    VkDescriptorBufferInfo buffer = {.buffer = (VkBuffer)(uintptr_t)0x55, .offset = 16, .range = 64};
    VkWriteDescriptorSet writes[2] = {{.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
        .dstBinding = 3, .dstArrayElement = 1, .descriptorCount = 2,
        .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, .pImageInfo = images},
        {.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstBinding = 4,
        .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, .pBufferInfo = &buffer}};

    PFN_vkCmdPushDescriptorSetKHR direct = (PFN_vkCmdPushDescriptorSetKHR)
        hybris_bc_commands_proc("vkCmdPushDescriptorSetKHR");
    assert(direct);
    direct(COMMAND, VK_PIPELINE_BIND_POINT_COMPUTE, LAYOUT, 2, 2, writes);
    VkDescriptorImageInfo original_images[2]; memcpy(original_images, images, sizeof(images));
    VkDescriptorBufferInfo original_buffer = buffer;
    memset(images, 0, sizeof(images)); memset(&buffer, 0, sizeof(buffer));
    clear_replay(); hybris_bc_restore_compute(&command.state, COMMAND);
    assert(old_calls == 1 && pipeline_calls == 0); expect_images_and_buffer(original_images, &original_buffer);
    reset_state();

#ifdef VK_QCOM_image_processing
    /* QCOM image descriptors use VkDescriptorImageInfo too; their type must
     * take the same owned-image path as ordinary sampled descriptors. */
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_SAMPLE_WEIGHT_IMAGE_QCOM;
    images[0] = original_images[0]; images[1] = original_images[1]; buffer = original_buffer;
    direct(COMMAND, VK_PIPELINE_BIND_POINT_COMPUTE, LAYOUT, 2, 2, writes);
    memset(images, 0, sizeof(images)); memset(&buffer, 0, sizeof(buffer));
    clear_replay(); hybris_bc_restore_compute(&command.state, COMMAND);
    assert(old_calls == 1); expect_images_and_buffer(original_images, &original_buffer);
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    reset_state();
#endif

    /* A recognized payload chained to the wrong descriptor type is irrelevant
     * metadata, not a reason to lose ordinary image/buffer writes. */
#ifdef VK_KHR_acceleration_structure
    VkAccelerationStructureKHR ignored_handles[2] = {(VkAccelerationStructureKHR)(uintptr_t)0x71,
        (VkAccelerationStructureKHR)(uintptr_t)0x72};
    VkWriteDescriptorSetAccelerationStructureKHR ignored_payload = {
        .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR,
        .accelerationStructureCount = 2, .pAccelerationStructures = ignored_handles};
    images[0] = original_images[0]; images[1] = original_images[1]; buffer = original_buffer;
    writes[1].pNext = &ignored_payload;
    direct(COMMAND, VK_PIPELINE_BIND_POINT_COMPUTE, LAYOUT, 2, 2, writes);
    memset(images, 0, sizeof(images)); memset(&buffer, 0, sizeof(buffer));
    clear_replay(); hybris_bc_restore_compute(&command.state, COMMAND);
    assert(old_calls == 1); expect_images_and_buffer(original_images, &original_buffer);
    writes[1].pNext = NULL; reset_state();
#endif

    PFN_vkCmdPushDescriptorSet2KHR direct2 = (PFN_vkCmdPushDescriptorSet2KHR)
        hybris_bc_commands_proc("vkCmdPushDescriptorSet2KHR");
    assert(direct2);
    images[0] = original_images[0]; images[1] = original_images[1]; buffer = original_buffer;
    VkPushDescriptorSetInfoKHR info = {.sType = VK_STRUCTURE_TYPE_PUSH_DESCRIPTOR_SET_INFO_KHR,
        .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
        .layout = LAYOUT, .set = 2, .descriptorWriteCount = 2, .pDescriptorWrites = writes};
    direct2(COMMAND, &info);
    info.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT; direct2(COMMAND, &info);
    memset(images, 0, sizeof(images)); memset(&buffer, 0, sizeof(buffer));
    clear_replay(); hybris_bc_restore_compute(&command.state, COMMAND);
    assert(info2_calls == 1 && replay_stages == VK_SHADER_STAGE_COMPUTE_BIT);
    expect_images_and_buffer(original_images, &original_buffer);
    reset_state();

    /* Extension payload chains own their handle/address arrays across replay. */
#ifdef VK_KHR_acceleration_structure
    VkAccelerationStructureKHR khr_handles[2] = {(VkAccelerationStructureKHR)(uintptr_t)0x81,
        (VkAccelerationStructureKHR)(uintptr_t)0x82};
    VkWriteDescriptorSetAccelerationStructureKHR khr_payload = {
        .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR,
        .accelerationStructureCount = 2, .pAccelerationStructures = khr_handles};
    VkWriteDescriptorSet extension_write = {.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
        .pNext = &khr_payload, .descriptorCount = 2,
        .descriptorType = VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR};
    expect_extension(&extension_write, 0x81, 0x82);
#endif
#ifdef VK_NV_ray_tracing
    VkAccelerationStructureNV nv_handles[2] = {(VkAccelerationStructureNV)(uintptr_t)0x91,
        (VkAccelerationStructureNV)(uintptr_t)0x92};
    VkWriteDescriptorSetAccelerationStructureNV nv_payload = {
        .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_NV,
        .accelerationStructureCount = 2, .pAccelerationStructures = nv_handles};
    extension_write = (VkWriteDescriptorSet){.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
        .pNext = &nv_payload, .descriptorCount = 2,
        .descriptorType = VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_NV};
    expect_extension(&extension_write, 0x91, 0x92);
#endif
#ifdef VK_NV_partitioned_acceleration_structure
    VkDeviceAddress partitioned[2] = {0xa1, 0xa2};
    VkWriteDescriptorSetPartitionedAccelerationStructureNV partitioned_payload = {
        .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_PARTITIONED_ACCELERATION_STRUCTURE_NV,
        .accelerationStructureCount = 2, .pAccelerationStructures = partitioned};
    extension_write = (VkWriteDescriptorSet){.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
        .pNext = &partitioned_payload, .descriptorCount = 2,
        .descriptorType = VK_DESCRIPTOR_TYPE_PARTITIONED_ACCELERATION_STRUCTURE_NV};
    expect_extension(&extension_write, 0xa1, 0xa2);
#endif
#ifdef HYBRIS_VK_ARM_TENSOR_PAYLOAD
    VkTensorViewARM tensors[2] = {(VkTensorViewARM)(uintptr_t)0xb1, (VkTensorViewARM)(uintptr_t)0xb2};
    VkWriteDescriptorSetTensorARM tensor_payload = {.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_TENSOR_ARM,
        .tensorViewCount = 2, .pTensorViews = tensors};
    extension_write = (VkWriteDescriptorSet){.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
        .pNext = &tensor_payload, .descriptorCount = 2, .descriptorType = VK_DESCRIPTOR_TYPE_TENSOR_ARM};
    expect_extension(&extension_write, 0xb1, 0xb2);
#endif

    VkDescriptorUpdateTemplateEntry entries[2] = {
        {.dstBinding = 3, .dstArrayElement = 1, .descriptorCount = 2,
         .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, .offset = 0,
         .stride = sizeof(VkDescriptorImageInfo)},
        {.dstBinding = 4, .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
         .offset = 2 * sizeof(VkDescriptorImageInfo), .stride = sizeof(VkDescriptorBufferInfo)}};
    VkDescriptorUpdateTemplateCreateInfo template_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_UPDATE_TEMPLATE_CREATE_INFO,
        .descriptorUpdateEntryCount = 2, .pDescriptorUpdateEntries = entries,
        .templateType = VK_DESCRIPTOR_UPDATE_TEMPLATE_TYPE_PUSH_DESCRIPTORS,
        .pipelineBindPoint = VK_PIPELINE_BIND_POINT_COMPUTE, .pipelineLayout = LAYOUT, .set = 2};
    PFN_vkCreateDescriptorUpdateTemplateKHR create = (PFN_vkCreateDescriptorUpdateTemplateKHR)
        hybris_bc_commands_proc("vkCreateDescriptorUpdateTemplateKHR");
    PFN_vkDestroyDescriptorUpdateTemplateKHR destroy = (PFN_vkDestroyDescriptorUpdateTemplateKHR)
        hybris_bc_commands_proc("vkDestroyDescriptorUpdateTemplateKHR");
    PFN_vkCmdPushDescriptorSetWithTemplateKHR template = (PFN_vkCmdPushDescriptorSetWithTemplateKHR)
        hybris_bc_commands_proc("vkCmdPushDescriptorSetWithTemplateKHR");
    assert(create && destroy && template);
    VkDescriptorUpdateTemplate update_template; assert(create(DEVICE, &template_info, NULL, &update_template) == VK_SUCCESS);
    expected_template_size = 2 * sizeof(VkDescriptorImageInfo) + sizeof(VkDescriptorBufferInfo);
    unsigned char template_data[sizeof(replay_template)];
    memcpy(template_data, original_images, sizeof(original_images));
    memcpy(template_data + 2 * sizeof(VkDescriptorImageInfo), &original_buffer, sizeof(original_buffer));
    template(COMMAND, update_template, LAYOUT, 2, template_data);
    destroy(DEVICE, update_template, NULL); memset(template_data, 0, sizeof(template_data));
    clear_replay(); hybris_bc_restore_compute(&command.state, COMMAND);
    assert(template_calls == 1 && replay_template_handle != update_template);
    assert(!memcmp(replay_template, original_images, sizeof(original_images)));
    assert(!memcmp(replay_template + 2 * sizeof(VkDescriptorImageInfo), &original_buffer, sizeof(original_buffer)));
    reset_state();

    /* Maintenance6's structure form has no stage mask: the push-template's
     * COMPUTE bind point decides whether it is journaled. */
    assert(create(DEVICE, &template_info, NULL, &update_template) == VK_SUCCESS);
    PFN_vkCmdPushDescriptorSetWithTemplate2KHR template2 =
        (PFN_vkCmdPushDescriptorSetWithTemplate2KHR)
        hybris_bc_commands_proc("vkCmdPushDescriptorSetWithTemplate2KHR");
    assert(template2);
    memcpy(template_data, original_images, sizeof(original_images));
    memcpy(template_data + 2 * sizeof(VkDescriptorImageInfo), &original_buffer, sizeof(original_buffer));
    VkPushDescriptorSetWithTemplateInfoKHR template_info2 = {
        .sType = VK_STRUCTURE_TYPE_PUSH_DESCRIPTOR_SET_WITH_TEMPLATE_INFO_KHR,
        .descriptorUpdateTemplate = update_template, .layout = LAYOUT, .set = 2,
        .pData = template_data};
    template2(COMMAND, &template_info2);
    destroy(DEVICE, update_template, NULL); memset(template_data, 0, sizeof(template_data));
    clear_replay(); hybris_bc_restore_compute(&command.state, COMMAND);
    assert(template2_calls == 1 && replay_template_handle != update_template);
    assert(!memcmp(replay_template, original_images, sizeof(original_images)));
    reset_state();

    /* These non-image descriptor types take the template packer's alternate
     * element-size paths. The caller storage is cleared before replay. */
#define EXTENSION_TEMPLATE(type, first, second) do { \
    VkDescriptorUpdateTemplateEntry entry = {.descriptorCount = 2, .descriptorType = (type), \
        .offset = 0, .stride = sizeof(uint64_t)}; \
    VkDescriptorUpdateTemplateCreateInfo info_template = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_UPDATE_TEMPLATE_CREATE_INFO, \
        .templateType = VK_DESCRIPTOR_UPDATE_TEMPLATE_TYPE_PUSH_DESCRIPTORS, .descriptorUpdateEntryCount = 1, \
        .pDescriptorUpdateEntries = &entry, .pipelineBindPoint = VK_PIPELINE_BIND_POINT_COMPUTE, \
        .pipelineLayout = LAYOUT, .set = 2}; \
    uint64_t values[2] = {(first), (second)}; \
    assert(create(DEVICE, &info_template, NULL, &update_template) == VK_SUCCESS); \
    expected_template_size = sizeof(values); template(COMMAND, update_template, LAYOUT, 2, values); \
    destroy(DEVICE, update_template, NULL); values[0] = values[1] = 0; \
    clear_replay(); hybris_bc_restore_compute(&command.state, COMMAND); \
    assert(template_calls == 1 && replay_template_handle != update_template); \
    assert(!memcmp(replay_template, (uint64_t[]){(first), (second)}, sizeof(values))); \
    reset_state(); \
} while (0)
#ifdef VK_KHR_acceleration_structure
    EXTENSION_TEMPLATE(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, 0xc1, 0xc2);
#endif
#ifdef VK_NV_ray_tracing
    EXTENSION_TEMPLATE(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_NV, 0xd1, 0xd2);
#endif
#ifdef VK_NV_partitioned_acceleration_structure
    EXTENSION_TEMPLATE(VK_DESCRIPTOR_TYPE_PARTITIONED_ACCELERATION_STRUCTURE_NV, 0xe1, 0xe2);
#endif
#ifdef HYBRIS_VK_ARM_TENSOR_PAYLOAD
    EXTENSION_TEMPLATE(VK_DESCRIPTOR_TYPE_TENSOR_ARM, 0xf1, 0xf2);
#endif
#undef EXTENSION_TEMPLATE
#ifdef VK_QCOM_image_processing
    VkDescriptorUpdateTemplateEntry qcom_entry = {.descriptorCount = 2,
        .descriptorType = VK_DESCRIPTOR_TYPE_SAMPLE_WEIGHT_IMAGE_QCOM, .offset = 0,
        .stride = sizeof(VkDescriptorImageInfo)};
    VkDescriptorUpdateTemplateCreateInfo qcom_template = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_UPDATE_TEMPLATE_CREATE_INFO,
        .templateType = VK_DESCRIPTOR_UPDATE_TEMPLATE_TYPE_PUSH_DESCRIPTORS,
        .descriptorUpdateEntryCount = 1, .pDescriptorUpdateEntries = &qcom_entry,
        .pipelineBindPoint = VK_PIPELINE_BIND_POINT_COMPUTE, .pipelineLayout = LAYOUT, .set = 2};
    VkDescriptorImageInfo qcom_data[2] = {original_images[0], original_images[1]};
    assert(create(DEVICE, &qcom_template, NULL, &update_template) == VK_SUCCESS);
    expected_template_size = sizeof(qcom_data); template(COMMAND, update_template, LAYOUT, 2, qcom_data);
    destroy(DEVICE, update_template, NULL); memset(qcom_data, 0, sizeof(qcom_data));
    clear_replay(); hybris_bc_restore_compute(&command.state, COMMAND);
    assert(template_calls == 1 && replay_template_handle != update_template);
    assert(!memcmp(replay_template, original_images, sizeof(qcom_data)));
    reset_state();
#endif

    /* No emulated format means no retained push state. */
    device.format_mask = 0; images[0] = original_images[0]; images[1] = original_images[1]; buffer = original_buffer;
    direct(COMMAND, VK_PIPELINE_BIND_POINT_COMPUTE, LAYOUT, 2, 2, writes);
    clear_replay(); hybris_bc_restore_compute(&command.state, COMMAND);
    assert(old_calls == 0 && info2_calls == 0 && template_calls == 0 && template2_calls == 0);
    device.format_mask = 1;

    /* A failed deep copy is reported by EndCommandBuffer; void command APIs
     * may not silently leave partially restorable state behind. */
    images[0] = original_images[0]; images[1] = original_images[1]; buffer = original_buffer;
    fail_alloc = 1;
    direct(COMMAND, VK_PIPELINE_BIND_POINT_COMPUTE, LAYOUT, 2, 2, writes);
    PFN_vkEndCommandBuffer end = (PFN_vkEndCommandBuffer)hybris_bc_commands_proc("vkEndCommandBuffer");
    assert(end(COMMAND) == VK_ERROR_OUT_OF_HOST_MEMORY);
    hybris_bc_command_state_reset(&command.state);
    fail_alloc = 0;

    /* Unsupported command pNext fails EndCommandBuffer rather than replaying partial state. */
    info.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    info.pNext = (const void *)(uintptr_t)1; direct2(COMMAND, &info);
    assert(end(COMMAND) != VK_SUCCESS);
    hybris_bc_command_state_reset(&command.state);
    hybris_bc_layouts_finish(&device.layouts);
    puts("hybris push descriptor journal ok");
    return 0;
}
