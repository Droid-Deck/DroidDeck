#include "mali_compat.h"

#include <assert.h>
#include <spirv/unified1/spirv.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

VKAPI_ATTR VkResult VKAPI_CALL vkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface *interface);

struct object { void *dispatch; };
static int instance_table, device_table;
static struct object mock_instance = {&instance_table};
static struct object mock_lacking = {&instance_table}, mock_capable = {&instance_table};
static struct object mock_device = {&device_table}, mock_command_buffer = {&device_table};

static VkBool32 driver_saw_dual_source;
static uint32_t *driver_code;
static size_t driver_code_count;
static VkPipelineColorBlendAttachmentState driver_attachments[4];
static uint32_t driver_attachment_count;
static VkColorBlendEquationEXT driver_equations[4];
static uint32_t driver_equation_count;

#define MAIN 1
#define VOID 2
#define FUNCTION 3
#define FLOAT 4
#define VEC4 5
#define OUTPUT_VEC4 6
#define COLOR 7
#define SECOND 8
#define ONE 9
#define ONES 10
#define OUTPUT_FLOAT 11
#define UINT 12
#define ZERO 13
#define LABEL 14
#define CHANNEL 15
#define BOUND 16

struct spirv { uint32_t w[256]; size_t n; };

static void op(struct spirv *s, SpvOp code, uint32_t count, const uint32_t *operands)
{
    s->w[s->n++] = (count + 1) << 16 | code;
    for (uint32_t i = 0; i < count; i++)
        s->w[s->n++] = operands[i];
}

#define OP(s, code, ...) \
    do { \
        const uint32_t operands_[] = {__VA_ARGS__}; \
        op(s, code, sizeof(operands_) / sizeof(operands_[0]), operands_); \
    } while (0)

static void fragment_shader(struct spirv *s, uint32_t version, bool dual_source)
{
    s->n = 0;
    const uint32_t header[5] = {SpvMagicNumber, version, 0, BOUND, 0};
    memcpy(s->w, header, sizeof(header));
    s->n = 5;
    OP(s, SpvOpCapability, SpvCapabilityShader);
    OP(s, SpvOpMemoryModel, SpvAddressingModelLogical, SpvMemoryModelGLSL450);
    OP(s, SpvOpEntryPoint, SpvExecutionModelFragment, MAIN, 0x6e69616d, 0, COLOR, SECOND);
    OP(s, SpvOpExecutionMode, MAIN, SpvExecutionModeOriginUpperLeft);
    OP(s, SpvOpDecorate, COLOR, SpvDecorationLocation, 0);
    if (dual_source) {
        OP(s, SpvOpDecorate, COLOR, SpvDecorationIndex, 0);
        OP(s, SpvOpDecorate, SECOND, SpvDecorationLocation, 0);
        OP(s, SpvOpDecorate, SECOND, SpvDecorationIndex, 1);
    } else {
        OP(s, SpvOpDecorate, SECOND, SpvDecorationLocation, 1);
    }
    OP(s, SpvOpTypeVoid, VOID);
    OP(s, SpvOpTypeFunction, FUNCTION, VOID);
    OP(s, SpvOpTypeFloat, FLOAT, 32);
    OP(s, SpvOpTypeVector, VEC4, FLOAT, 4);
    OP(s, SpvOpTypePointer, OUTPUT_VEC4, SpvStorageClassOutput, VEC4);
    OP(s, SpvOpTypePointer, OUTPUT_FLOAT, SpvStorageClassOutput, FLOAT);
    OP(s, SpvOpTypeInt, UINT, 32, 0);
    OP(s, SpvOpConstant, FLOAT, ONE, 0x3f800000);
    OP(s, SpvOpConstantComposite, VEC4, ONES, ONE, ONE, ONE, ONE);
    OP(s, SpvOpConstant, UINT, ZERO, 0);
    OP(s, SpvOpVariable, OUTPUT_VEC4, COLOR, SpvStorageClassOutput);
    OP(s, SpvOpVariable, OUTPUT_VEC4, SECOND, SpvStorageClassOutput);
    OP(s, SpvOpFunction, VOID, MAIN, SpvFunctionControlMaskNone, FUNCTION);
    OP(s, SpvOpLabel, LABEL);
    OP(s, SpvOpStore, COLOR, ONES);
    OP(s, SpvOpStore, SECOND, ONES);
    OP(s, SpvOpAccessChain, OUTPUT_FLOAT, CHANNEL, SECOND, ZERO);
    OP(s, SpvOpStore, CHANNEL, ONE);
    s->w[s->n++] = 1u << 16 | SpvOpReturn;
    s->w[s->n++] = 1u << 16 | SpvOpFunctionEnd;
}

static const uint32_t *find(const uint32_t *w, size_t count, SpvOp code, uint32_t position, uint32_t value)
{
    for (size_t i = 5; i < count; i += w[i] >> 16) {
        if ((w[i] & 0xffff) == code && (w[i] >> 16) > position && w[i + position] == value)
            return &w[i];
    }
    return NULL;
}

static uint32_t storage_of(const uint32_t *w, size_t count, uint32_t pointer_type)
{
    const uint32_t *type = find(w, count, SpvOpTypePointer, 1, pointer_type);
    assert(type);
    return type[2];
}

static bool in_interface(const uint32_t *w, size_t count, uint32_t id)
{
    const uint32_t *entry = find(w, count, SpvOpEntryPoint, 2, MAIN);
    assert(entry);
    for (uint32_t i = 5; i < entry[0] >> 16; i++) {
        if (entry[i] == id)
            return true;
    }
    return false;
}

static void save(const char *directory, const char *name, const uint32_t *words, size_t count)
{
    if (!directory)
        return;
    char path[512];
    snprintf(path, sizeof(path), "%s/%s", directory, name);
    FILE *file = fopen(path, "wb");
    assert(file);
    assert(fwrite(words, sizeof(uint32_t), count, file) == count);
    fclose(file);
}

static void check_stripped(const uint32_t *w, size_t count, bool second_listed)
{
    assert(!spirv_uses_dual_source(w, count));
    const uint32_t *second = find(w, count, SpvOpVariable, 2, SECOND);
    const uint32_t *color = find(w, count, SpvOpVariable, 2, COLOR);
    assert(second && second[3] == SpvStorageClassPrivate);
    assert(storage_of(w, count, second[1]) == SpvStorageClassPrivate);
    assert(color && color[3] == SpvStorageClassOutput && color[1] == OUTPUT_VEC4);
    assert(!find(w, count, SpvOpDecorate, 1, SECOND));
    const uint32_t *location = find(w, count, SpvOpDecorate, 1, COLOR);
    assert(location && location[2] == SpvDecorationLocation && location[3] == 0);
    const uint32_t *channel = find(w, count, SpvOpAccessChain, 2, CHANNEL);
    assert(channel && storage_of(w, count, channel[1]) == SpvStorageClassPrivate);
    assert(storage_of(w, count, OUTPUT_FLOAT) == SpvStorageClassOutput);
    assert(in_interface(w, count, COLOR));
    assert(in_interface(w, count, SECOND) == second_listed);
}

static void test_spirv(const char *directory)
{
    struct spirv dual, plain;
    struct spirv_code out;

    fragment_shader(&dual, 0x00010000, true);
    save(directory, "dual-1.0.spv", dual.w, dual.n);
    assert(spirv_uses_dual_source(dual.w, dual.n));
    assert(spirv_strip_dual_source(dual.w, dual.n, &out));
    check_stripped(out.words, out.count, false);
    save(directory, "stripped-1.0.spv", out.words, out.count);
    free(out.words);

    fragment_shader(&dual, 0x00010400, true);
    assert(spirv_strip_dual_source(dual.w, dual.n, &out));
    check_stripped(out.words, out.count, true);
    save(directory, "stripped-1.4.spv", out.words, out.count);
    free(out.words);

    fragment_shader(&plain, 0x00010000, false);
    save(directory, "plain-1.0.spv", plain.w, plain.n);
    assert(!spirv_uses_dual_source(plain.w, plain.n));
    assert(spirv_strip_dual_source(plain.w, plain.n, &out));
    assert(out.count == plain.n && !memcmp(out.words, plain.w, plain.n * sizeof(uint32_t)));
    free(out.words);

    uint32_t truncated[5] = {SpvMagicNumber, 0x00010000, 0, BOUND, 0};
    assert(!spirv_uses_dual_source(truncated, 5));
    assert(!spirv_strip_dual_source(truncated, 5, &out));
}

static VkResult VKAPI_CALL mock_create_instance(const VkInstanceCreateInfo *info, const VkAllocationCallbacks *allocator,
                                                VkInstance *instance)
{
    *instance = (VkInstance)&mock_instance;
    return VK_SUCCESS;
}

static void VKAPI_CALL mock_destroy_instance(VkInstance instance, const VkAllocationCallbacks *allocator) {}

static VkResult VKAPI_CALL mock_extensions(VkPhysicalDevice physical, const char *layer, uint32_t *count,
                                           VkExtensionProperties *properties)
{
    *count = 0;
    return VK_SUCCESS;
}

static void VKAPI_CALL mock_features(VkPhysicalDevice physical, VkPhysicalDeviceFeatures *features)
{
    memset(features, 0, sizeof(*features));
    features->dualSrcBlend = physical == (VkPhysicalDevice)&mock_capable;
}

static void VKAPI_CALL mock_features2(VkPhysicalDevice physical, VkPhysicalDeviceFeatures2 *features)
{
    mock_features(physical, &features->features);
}

static void VKAPI_CALL mock_properties(VkPhysicalDevice physical, VkPhysicalDeviceProperties *properties)
{
    memset(properties, 0, sizeof(*properties));
    strcpy(properties->deviceName, "mock");
    properties->limits.maxFragmentDualSrcAttachments = physical == (VkPhysicalDevice)&mock_capable;
}

static void VKAPI_CALL mock_properties2(VkPhysicalDevice physical, VkPhysicalDeviceProperties2 *properties)
{
    mock_properties(physical, &properties->properties);
}

static void VKAPI_CALL mock_memory_properties(VkPhysicalDevice physical, VkPhysicalDeviceMemoryProperties *properties)
{
    memset(properties, 0, sizeof(*properties));
}

static void VKAPI_CALL mock_format_properties2(VkPhysicalDevice physical, VkFormat format, VkFormatProperties2 *out)
{
    memset(&out->formatProperties, 0, sizeof(out->formatProperties));
}

static VkResult VKAPI_CALL mock_image_format_properties2(VkPhysicalDevice physical,
                                                         const VkPhysicalDeviceImageFormatInfo2 *info,
                                                         VkImageFormatProperties2 *properties)
{
    return VK_ERROR_FORMAT_NOT_SUPPORTED;
}

static VkResult VKAPI_CALL mock_create_device(VkPhysicalDevice physical, const VkDeviceCreateInfo *info,
                                              const VkAllocationCallbacks *allocator, VkDevice *device)
{
    driver_saw_dual_source = info->pEnabledFeatures && info->pEnabledFeatures->dualSrcBlend;
    for (const VkBaseInStructure *p = info->pNext; p; p = p->pNext) {
        if (p->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2 &&
            ((const VkPhysicalDeviceFeatures2 *)p)->features.dualSrcBlend)
            driver_saw_dual_source = VK_TRUE;
    }
    VkPhysicalDeviceFeatures supported;
    mock_features(physical, &supported);
    if (driver_saw_dual_source && !supported.dualSrcBlend)
        return VK_ERROR_FEATURE_NOT_PRESENT;
    *device = (VkDevice)&mock_device;
    return VK_SUCCESS;
}

static void VKAPI_CALL mock_destroy_device(VkDevice device, const VkAllocationCallbacks *allocator) {}

static void capture_code(const uint32_t *words, size_t size)
{
    free(driver_code);
    driver_code_count = size / sizeof(uint32_t);
    driver_code = malloc(size);
    memcpy(driver_code, words, size);
}

static VkResult VKAPI_CALL mock_create_shader_module(VkDevice device, const VkShaderModuleCreateInfo *info,
                                                     const VkAllocationCallbacks *allocator, VkShaderModule *module)
{
    capture_code(info->pCode, info->codeSize);
    *module = (VkShaderModule)0x1000;
    return VK_SUCCESS;
}

static void VKAPI_CALL mock_destroy_shader_module(VkDevice device, VkShaderModule module,
                                                  const VkAllocationCallbacks *allocator) {}

static VkResult VKAPI_CALL mock_create_graphics_pipelines(VkDevice device, VkPipelineCache cache, uint32_t count,
                                                          const VkGraphicsPipelineCreateInfo *infos,
                                                          const VkAllocationCallbacks *allocator, VkPipeline *pipelines)
{
    const VkPipelineColorBlendStateCreateInfo *blend = infos[0].pColorBlendState;
    driver_attachment_count = blend->attachmentCount;
    memcpy(driver_attachments, blend->pAttachments, blend->attachmentCount * sizeof(*driver_attachments));
    const VkShaderModuleCreateInfo *module = infos[0].pStages[0].pNext;
    assert(module && module->sType == VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
    capture_code(module->pCode, module->codeSize);
    pipelines[0] = (VkPipeline)0x2000;
    return VK_SUCCESS;
}

static void VKAPI_CALL mock_set_blend_equation(VkCommandBuffer cmd, uint32_t first, uint32_t count,
                                               const VkColorBlendEquationEXT *equations)
{
    for (uint32_t i = 0; i < count; i++)
        driver_equations[first + i] = equations[i];
    if (first + count > driver_equation_count)
        driver_equation_count = first + count;
}

#define MOCK(name, function) if (!strcmp(requested, name)) return (PFN_vkVoidFunction)function
static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL mock_gipa(VkInstance instance, const char *requested)
{
    MOCK("vkCreateInstance", mock_create_instance);
    MOCK("vkDestroyInstance", mock_destroy_instance);
    MOCK("vkCreateDevice", mock_create_device);
    MOCK("vkEnumerateDeviceExtensionProperties", mock_extensions);
    MOCK("vkGetPhysicalDeviceFeatures", mock_features);
    MOCK("vkGetPhysicalDeviceFeatures2", mock_features2);
    MOCK("vkGetPhysicalDeviceProperties", mock_properties);
    MOCK("vkGetPhysicalDeviceProperties2", mock_properties2);
    MOCK("vkGetPhysicalDeviceMemoryProperties", mock_memory_properties);
    MOCK("vkGetPhysicalDeviceFormatProperties2", mock_format_properties2);
    MOCK("vkGetPhysicalDeviceImageFormatProperties2", mock_image_format_properties2);
    return NULL;
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL mock_gdpa(VkDevice device, const char *requested)
{
    MOCK("vkDestroyDevice", mock_destroy_device);
    MOCK("vkCreateShaderModule", mock_create_shader_module);
    MOCK("vkDestroyShaderModule", mock_destroy_shader_module);
    MOCK("vkCreateGraphicsPipelines", mock_create_graphics_pipelines);
    MOCK("vkCmdSetColorBlendEquationEXT", mock_set_blend_equation);
    return NULL;
}

static PFN_vkGetInstanceProcAddr layer_gipa;
static PFN_vkGetDeviceProcAddr layer_gdpa;

static VkResult create_device(VkPhysicalDevice physical, const void *chain, const VkPhysicalDeviceFeatures *enabled,
                              VkDevice *device)
{
    VkLayerDeviceLink link = {NULL, mock_gipa, mock_gdpa};
    VkLayerDeviceCreateInfo loader = {VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO, chain, VK_LAYER_LINK_INFO, {&link}};
    VkDeviceCreateInfo info = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, &loader, 0, 0, NULL, 0, NULL, 0, NULL, enabled};
    PFN_vkCreateDevice create = (PFN_vkCreateDevice)layer_gipa((VkInstance)&mock_instance, "vkCreateDevice");
    return create(physical, &info, NULL, device);
}

static void destroy_device(VkDevice device)
{
    ((PFN_vkDestroyDevice)layer_gdpa(device, "vkDestroyDevice"))(device, NULL);
}

static const VkPipelineColorBlendAttachmentState dual_attachment = {
    VK_TRUE, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC1_COLOR, VK_BLEND_OP_ADD,
    VK_BLEND_FACTOR_SRC1_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC1_ALPHA, VK_BLEND_OP_ADD, 0xf,
};

static const VkPipelineColorBlendAttachmentState plain_attachment = {
    VK_TRUE, VK_BLEND_FACTOR_DST_COLOR, VK_BLEND_FACTOR_CONSTANT_ALPHA, VK_BLEND_OP_ADD,
    VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA, VK_BLEND_OP_ADD, 0xf,
};

static void create_pipeline(VkDevice device, VkPipelineColorBlendAttachmentState *attachments,
                            VkShaderModuleCreateInfo *module)
{
    VkPipelineShaderStageCreateInfo stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, module, 0,
                                             VK_SHADER_STAGE_FRAGMENT_BIT, VK_NULL_HANDLE, "main", NULL};
    VkPipelineColorBlendStateCreateInfo blend = {VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO, NULL, 0,
                                                 VK_FALSE, VK_LOGIC_OP_COPY, 2, attachments, {0}};
    VkGraphicsPipelineCreateInfo info = {.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO, .stageCount = 1,
                                         .pStages = &stage, .pColorBlendState = &blend};
    VkPipeline pipeline;
    PFN_vkCreateGraphicsPipelines create =
        (PFN_vkCreateGraphicsPipelines)layer_gdpa(device, "vkCreateGraphicsPipelines");
    assert(create(device, VK_NULL_HANDLE, 1, &info, NULL, &pipeline) == VK_SUCCESS);
    assert(stage.pNext == module && blend.pAttachments == attachments);
}

static bool same_attachment(const VkPipelineColorBlendAttachmentState *a, const VkPipelineColorBlendAttachmentState *b)
{
    return !memcmp(a, b, sizeof(*a));
}

static void test_lacking_device(void)
{
    VkPhysicalDevice physical = (VkPhysicalDevice)&mock_lacking;
    VkPhysicalDeviceFeatures features;
    ((PFN_vkGetPhysicalDeviceFeatures)layer_gipa((VkInstance)&mock_instance, "vkGetPhysicalDeviceFeatures"))(physical,
                                                                                                         &features);
    assert(features.dualSrcBlend);
    VkPhysicalDeviceFeatures2 queried = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    ((PFN_vkGetPhysicalDeviceFeatures2)layer_gipa((VkInstance)&mock_instance, "vkGetPhysicalDeviceFeatures2"))(physical,
                                                                                                           &queried);
    assert(queried.features.dualSrcBlend);
    VkPhysicalDeviceProperties properties;
    ((PFN_vkGetPhysicalDeviceProperties)layer_gipa((VkInstance)&mock_instance, "vkGetPhysicalDeviceProperties"))(
        physical, &properties);
    assert(properties.limits.maxFragmentDualSrcAttachments == 1);

    VkDevice device;
    VkPhysicalDeviceFeatures enabled = {.dualSrcBlend = VK_TRUE};
    assert(create_device(physical, NULL, &enabled, &device) == VK_SUCCESS);
    assert(!driver_saw_dual_source && enabled.dualSrcBlend);
    destroy_device(device);

    VkPhysicalDeviceFeatures2 chained = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, NULL, {.dualSrcBlend = VK_TRUE}};
    assert(create_device(physical, &chained, NULL, &device) == VK_SUCCESS);
    assert(!driver_saw_dual_source && chained.features.dualSrcBlend);

    struct spirv dual;
    fragment_shader(&dual, 0x00010300, true);
    VkShaderModuleCreateInfo module = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, NULL, 0, dual.n * sizeof(uint32_t),
                                       dual.w};
    VkShaderModule handle;
    assert(((PFN_vkCreateShaderModule)layer_gdpa(device, "vkCreateShaderModule"))(device, &module, NULL, &handle) ==
           VK_SUCCESS);
    check_stripped(driver_code, driver_code_count, false);
    ((PFN_vkDestroyShaderModule)layer_gdpa(device, "vkDestroyShaderModule"))(device, handle, NULL);

    VkPipelineColorBlendAttachmentState attachments[2] = {dual_attachment, plain_attachment};
    create_pipeline(device, attachments, &module);
    check_stripped(driver_code, driver_code_count, false);
    assert(module.pCode == dual.w && spirv_uses_dual_source(module.pCode, module.codeSize / sizeof(uint32_t)));
    assert(driver_attachment_count == 2);
    assert(driver_attachments[0].srcColorBlendFactor == VK_BLEND_FACTOR_ONE);
    assert(driver_attachments[0].dstColorBlendFactor == VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);
    assert(driver_attachments[0].srcAlphaBlendFactor == VK_BLEND_FACTOR_SRC_ALPHA);
    assert(driver_attachments[0].dstAlphaBlendFactor == VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);
    assert(driver_attachments[0].colorWriteMask == 0xf && driver_attachments[0].blendEnable);
    assert(same_attachment(&driver_attachments[1], &plain_attachment));
    assert(same_attachment(&attachments[0], &dual_attachment));

    VkColorBlendEquationEXT equations[2] = {
        {VK_BLEND_FACTOR_SRC1_COLOR, VK_BLEND_FACTOR_ONE_MINUS_SRC1_COLOR, VK_BLEND_OP_ADD, VK_BLEND_FACTOR_ONE,
         VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD},
        {VK_BLEND_FACTOR_DST_COLOR, VK_BLEND_FACTOR_SRC_COLOR, VK_BLEND_OP_ADD, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
         VK_BLEND_FACTOR_CONSTANT_COLOR, VK_BLEND_OP_ADD},
    };
    VkCommandBuffer cmd = (VkCommandBuffer)&mock_command_buffer;
    PFN_vkCmdSetColorBlendEquationEXT set_equation =
        (PFN_vkCmdSetColorBlendEquationEXT)layer_gdpa(device, "vkCmdSetColorBlendEquationEXT");
    assert(set_equation != (PFN_vkCmdSetColorBlendEquationEXT)mock_set_blend_equation);
    set_equation(cmd, 1, 2, equations);
    assert(driver_equation_count == 3);
    assert(driver_equations[1].srcColorBlendFactor == VK_BLEND_FACTOR_SRC_ALPHA);
    assert(driver_equations[1].dstColorBlendFactor == VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);
    assert(driver_equations[1].srcAlphaBlendFactor == VK_BLEND_FACTOR_ONE);
    assert(!memcmp(&driver_equations[2], &equations[1], sizeof(equations[1])));
    assert(equations[0].srcColorBlendFactor == VK_BLEND_FACTOR_SRC1_COLOR);
    destroy_device(device);
}

static void test_capable_device(void)
{
    VkPhysicalDevice physical = (VkPhysicalDevice)&mock_capable;
    VkPhysicalDeviceProperties properties;
    ((PFN_vkGetPhysicalDeviceProperties)layer_gipa((VkInstance)&mock_instance, "vkGetPhysicalDeviceProperties"))(
        physical, &properties);
    assert(properties.limits.maxFragmentDualSrcAttachments == 1);

    VkDevice device;
    VkPhysicalDeviceFeatures enabled = {.dualSrcBlend = VK_TRUE};
    assert(create_device(physical, NULL, &enabled, &device) == VK_SUCCESS && driver_saw_dual_source);
    assert(layer_gdpa(device, "vkCreateGraphicsPipelines") == (PFN_vkVoidFunction)mock_create_graphics_pipelines);
    assert(layer_gdpa(device, "vkCreateShaderModule") == (PFN_vkVoidFunction)mock_create_shader_module);
    assert(layer_gdpa(device, "vkCmdSetColorBlendEquationEXT") == (PFN_vkVoidFunction)mock_set_blend_equation);
    destroy_device(device);
}

int main(int argc, char **argv)
{
    test_spirv(argc > 1 ? argv[1] : NULL);

    setenv("WINEPREFIX", "/nonexistent", 1);
    unsetenv("DROIDDECK_MALI_COMPAT");
    unsetenv("DROIDDECK_MALI_COMPAT_TRACE");
    VkNegotiateLayerInterface negotiate = {LAYER_NEGOTIATE_INTERFACE_STRUCT, NULL, 2, NULL, NULL, NULL};
    assert(vkNegotiateLoaderLayerInterfaceVersion(&negotiate) == VK_SUCCESS);
    layer_gipa = negotiate.pfnGetInstanceProcAddr;
    layer_gdpa = negotiate.pfnGetDeviceProcAddr;

    VkLayerInstanceLink link = {NULL, mock_gipa, NULL};
    VkLayerInstanceCreateInfo loader = {VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO, NULL, VK_LAYER_LINK_INFO, {&link}};
    VkInstanceCreateInfo info = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pNext = &loader};
    VkInstance instance;
    PFN_vkCreateInstance create_instance = (PFN_vkCreateInstance)layer_gipa(NULL, "vkCreateInstance");
    assert(create_instance(&info, NULL, &instance) == VK_SUCCESS);

    test_lacking_device();
    test_capable_device();

    ((PFN_vkDestroyInstance)layer_gipa(instance, "vkDestroyInstance"))(instance, NULL);
    free(driver_code);
    printf("mali compat layer ok\n");
    return 0;
}
