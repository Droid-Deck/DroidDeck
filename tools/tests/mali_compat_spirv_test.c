#include "mali_compat.h"

#include <spirv/unified1/spirv.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define OPCODE(word) ((word) & 0xffffu)
#define LENGTH(word) ((word) >> 16)

static const uint32_t newer[] = {
    0x07230203, 0x00010600, 0x00070000, 0x0000000a, 0x00000000, 0x00020011, 0x00000001, 0x0003000e,
    0x00000000, 0x00000001, 0x0007000f, 0x00000004, 0x00000001, 0x6e69616d, 0x00000000, 0x00000002,
    0x00000003, 0x00030010, 0x00000001, 0x00000007, 0x00040047, 0x00000002, 0x0000001e, 0x00000000,
    0x00020013, 0x00000004, 0x00030016, 0x00000005, 0x00000020, 0x00040020, 0x00000006, 0x00000003,
    0x00000005, 0x0004003b, 0x00000006, 0x00000002, 0x00000003, 0x00040020, 0x00000007, 0x00000006,
    0x00000005, 0x0004003b, 0x00000007, 0x00000003, 0x00000006, 0x00030021, 0x00000008, 0x00000004,
    0x00050036, 0x00000004, 0x00000001, 0x00000000, 0x00000008, 0x000200f8, 0x00000009, 0x00011140,
    0x00010038,
};
static const size_t newer_count = sizeof(newer) / sizeof(uint32_t);

static const uint32_t older[] = {
    0x07230203, 0x00010300, 0x00070000, 0x00000008, 0x00000000, 0x00020011, 0x00000001, 0x0003000e,
    0x00000000, 0x00000001, 0x0006000f, 0x00000004, 0x00000001, 0x6e69616d, 0x00000000, 0x00000002,
    0x00030010, 0x00000001, 0x00000007, 0x00040047, 0x00000002, 0x0000001e, 0x00000000, 0x00020013,
    0x00000003, 0x00030016, 0x00000004, 0x00000020, 0x00040020, 0x00000005, 0x00000003, 0x00000004,
    0x0004003b, 0x00000005, 0x00000002, 0x00000003, 0x00030021, 0x00000006, 0x00000003, 0x00050036,
    0x00000003, 0x00000001, 0x00000000, 0x00000006, 0x000200f8, 0x00000007, 0x000100fd, 0x00010038,
};
static const size_t older_count = sizeof(older) / sizeof(uint32_t);

static const uint32_t capability[] = {
    0x07230203, 0x00010600, 0x00070000, 0x00000008, 0x00000000, 0x00020011, 0x00000001, 0x00020011,
    0x00000046, 0x0003000e, 0x00000000, 0x00000001, 0x0006000f, 0x00000004, 0x00000001, 0x6e69616d,
    0x00000000, 0x00000002, 0x00030010, 0x00000001, 0x00000007, 0x00040047, 0x00000002, 0x0000001e,
    0x00000000, 0x00020013, 0x00000003, 0x00030016, 0x00000004, 0x00000020, 0x00040020, 0x00000005,
    0x00000003, 0x00000004, 0x0004003b, 0x00000005, 0x00000002, 0x00000003, 0x00030021, 0x00000006,
    0x00000003, 0x00050036, 0x00000003, 0x00000001, 0x00000000, 0x00000006, 0x000200f8, 0x00000007,
    0x000100fd, 0x00010038,
};
static const size_t capability_count = sizeof(capability) / sizeof(uint32_t);

static const uint32_t memory[] = {
    0x07230203, 0x00010600, 0x00070000, 0x00000008, 0x00000000, 0x00020011, 0x00000001, 0x00020011,
    0x000014e1, 0x0003000e, 0x00000000, 0x00000003, 0x0006000f, 0x00000004, 0x00000001, 0x6e69616d,
    0x00000000, 0x00000002, 0x00030010, 0x00000001, 0x00000007, 0x00040047, 0x00000002, 0x0000001e,
    0x00000000, 0x00020013, 0x00000003, 0x00030016, 0x00000004, 0x00000020, 0x00040020, 0x00000005,
    0x00000003, 0x00000004, 0x0004003b, 0x00000005, 0x00000002, 0x00000003, 0x00030021, 0x00000006,
    0x00000003, 0x00050036, 0x00000003, 0x00000001, 0x00000000, 0x00000006, 0x000200f8, 0x00000007,
    0x000100fd, 0x00010038,
};
static const size_t memory_count = sizeof(memory) / sizeof(uint32_t);

static const uint32_t zinklike[] = {
    0x07230203, 0x00010600, 0x00070000, 0x00000017, 0x00000000, 0x00020011, 0x00000001, 0x0003000e,
    0x00000000, 0x00000001, 0x0007000f, 0x00000004, 0x00000001, 0x6e69616d, 0x00000000, 0x00000002,
    0x00000003, 0x00030010, 0x00000001, 0x00000007, 0x00040047, 0x00000002, 0x0000001e, 0x00000000,
    0x00020013, 0x00000004, 0x00030016, 0x00000005, 0x00000020, 0x00040015, 0x00000006, 0x00000020,
    0x00000001, 0x00040017, 0x00000007, 0x00000005, 0x00000004, 0x0003001e, 0x00000008, 0x00000007,
    0x00040020, 0x00000009, 0x00000003, 0x00000005, 0x0004003b, 0x00000009, 0x00000002, 0x00000003,
    0x00040020, 0x0000000a, 0x00000006, 0x00000008, 0x00040020, 0x0000000b, 0x00000007, 0x00000008,
    0x0004003b, 0x0000000a, 0x00000003, 0x00000006, 0x00040020, 0x0000000c, 0x00000006, 0x00000005,
    0x00040020, 0x0000000d, 0x00000007, 0x00000005, 0x0004002b, 0x00000005, 0x0000000e, 0x3f800000,
    0x0004002b, 0x00000006, 0x0000000f, 0x00000000, 0x00030021, 0x00000010, 0x00000004, 0x00050036,
    0x00000004, 0x00000001, 0x00000000, 0x00000010, 0x000200f8, 0x00000011, 0x0004003b, 0x0000000b,
    0x00000012, 0x00000007, 0x00060041, 0x0000000d, 0x00000013, 0x00000012, 0x0000000f, 0x0000000f,
    0x0003003e, 0x00000013, 0x0000000e, 0x0004003d, 0x00000005, 0x00000014, 0x00000013, 0x00070050,
    0x00000007, 0x00000015, 0x00000014, 0x00000014, 0x00000014, 0x00000014, 0x00040041, 0x00000009,
    0x00000016, 0x00000002, 0x0003003e, 0x00000016, 0x00000014, 0x000100fd, 0x00010038,
};
static const size_t zinklike_count = sizeof(zinklike) / sizeof(uint32_t);

static const uint32_t nonuniform[] = {
    0x07230203, 0x00010600, 0x00070000, 0x00000009, 0x00000000, 0x00020011, 0x00000001, 0x00020011,
    0x000014b5, 0x0003000e, 0x00000000, 0x00000001, 0x0006000f, 0x00000004, 0x00000001, 0x6e69616d,
    0x00000000, 0x00000002, 0x00030010, 0x00000001, 0x00000007, 0x00040047, 0x00000002, 0x0000001e,
    0x00000000, 0x00020013, 0x00000003, 0x00030016, 0x00000004, 0x00000020, 0x00040020, 0x00000005,
    0x00000003, 0x00000004, 0x0004003b, 0x00000005, 0x00000002, 0x00000003, 0x0004002b, 0x00000004,
    0x00000006, 0x3f800000, 0x00030021, 0x00000007, 0x00000003, 0x00050036, 0x00000003, 0x00000001,
    0x00000000, 0x00000007, 0x000200f8, 0x00000008, 0x0003003e, 0x00000002, 0x00000006, 0x000100fd,
    0x00010038,
};
static const size_t nonuniform_count = sizeof(nonuniform) / sizeof(uint32_t);

static const uint32_t nonuniform_extension[] = {
    0x07230203, 0x00010600, 0x00070000, 0x00000009, 0x00000000, 0x00020011, 0x00000001, 0x00020011,
    0x000014b5, 0x0008000a, 0x5f565053, 0x5f545845, 0x63736564, 0x74706972, 0x695f726f, 0x7865646e,
    0x00676e69, 0x0003000e, 0x00000000, 0x00000001, 0x0006000f, 0x00000004, 0x00000001, 0x6e69616d,
    0x00000000, 0x00000002, 0x00030010, 0x00000001, 0x00000007, 0x00040047, 0x00000002, 0x0000001e,
    0x00000000, 0x00020013, 0x00000003, 0x00030016, 0x00000004, 0x00000020, 0x00040020, 0x00000005,
    0x00000003, 0x00000004, 0x0004003b, 0x00000005, 0x00000002, 0x00000003, 0x0004002b, 0x00000004,
    0x00000006, 0x3f800000, 0x00030021, 0x00000007, 0x00000003, 0x00050036, 0x00000003, 0x00000001,
    0x00000000, 0x00000007, 0x000200f8, 0x00000008, 0x0003003e, 0x00000002, 0x00000006, 0x000100fd,
    0x00010038,
};
static const size_t nonuniform_extension_count = sizeof(nonuniform_extension) / sizeof(uint32_t);

static const uint32_t select_struct[] = {
    0x07230203, 0x00010600, 0x00070000, 0x00000011, 0x00000000, 0x00020011, 0x00000001, 0x0003000e,
    0x00000000, 0x00000001, 0x0006000f, 0x00000004, 0x00000001, 0x6e69616d, 0x00000000, 0x00000002,
    0x00030010, 0x00000001, 0x00000007, 0x00040047, 0x00000002, 0x0000001e, 0x00000000, 0x00020013,
    0x00000003, 0x00020014, 0x00000004, 0x00030016, 0x00000005, 0x00000020, 0x0004001e, 0x00000006,
    0x00000005, 0x00000005, 0x00040020, 0x00000007, 0x00000003, 0x00000005, 0x0004003b, 0x00000007,
    0x00000002, 0x00000003, 0x0004002b, 0x00000005, 0x00000008, 0x3f800000, 0x0004002b, 0x00000005,
    0x00000009, 0x00000000, 0x00030029, 0x00000004, 0x0000000a, 0x0005002c, 0x00000006, 0x0000000b,
    0x00000008, 0x00000009, 0x0005002c, 0x00000006, 0x0000000c, 0x00000009, 0x00000008, 0x00030021,
    0x0000000d, 0x00000003, 0x00050036, 0x00000003, 0x00000001, 0x00000000, 0x0000000d, 0x000200f8,
    0x0000000e, 0x000600a9, 0x00000006, 0x0000000f, 0x0000000a, 0x0000000b, 0x0000000c, 0x00050051,
    0x00000005, 0x00000010, 0x0000000f, 0x00000000, 0x0003003e, 0x00000002, 0x00000010, 0x000100fd,
    0x00010038,
};
static const size_t select_struct_count = sizeof(select_struct) / sizeof(uint32_t);

static const uint32_t select_scalar[] = {
    0x07230203, 0x00010600, 0x00070000, 0x0000000d, 0x00000000, 0x00020011, 0x00000001, 0x0003000e,
    0x00000000, 0x00000001, 0x0006000f, 0x00000004, 0x00000001, 0x6e69616d, 0x00000000, 0x00000002,
    0x00030010, 0x00000001, 0x00000007, 0x00040047, 0x00000002, 0x0000001e, 0x00000000, 0x00020013,
    0x00000003, 0x00020014, 0x00000004, 0x00030016, 0x00000005, 0x00000020, 0x00040020, 0x00000006,
    0x00000003, 0x00000005, 0x0004003b, 0x00000006, 0x00000002, 0x00000003, 0x0004002b, 0x00000005,
    0x00000007, 0x3f800000, 0x0004002b, 0x00000005, 0x00000008, 0x00000000, 0x00030029, 0x00000004,
    0x00000009, 0x00030021, 0x0000000a, 0x00000003, 0x00050036, 0x00000003, 0x00000001, 0x00000000,
    0x0000000a, 0x000200f8, 0x0000000b, 0x000600a9, 0x00000005, 0x0000000c, 0x00000009, 0x00000007,
    0x00000008, 0x0003003e, 0x00000002, 0x0000000c, 0x000100fd, 0x00010038,
};
static const size_t select_scalar_count = sizeof(select_scalar) / sizeof(uint32_t);

static const uint32_t nonsemantic[] = {
    0x07230203, 0x00010600, 0x00070000, 0x0000000c, 0x00000000, 0x00020011, 0x00000001, 0x0008000b,
    0x00000001, 0x536e6f4e, 0x6e616d65, 0x2e636974, 0x75626544, 0x69725067, 0x0066746e, 0x0003000e,
    0x00000000, 0x00000001, 0x0006000f, 0x00000004, 0x00000002, 0x6e69616d, 0x00000000, 0x00000003,
    0x00030010, 0x00000002, 0x00000007, 0x00030007, 0x00000004, 0x00000078, 0x00040047, 0x00000003,
    0x0000001e, 0x00000000, 0x00020013, 0x00000005, 0x00030016, 0x00000006, 0x00000020, 0x00040020,
    0x00000007, 0x00000003, 0x00000006, 0x0004003b, 0x00000007, 0x00000003, 0x00000003, 0x0004002b,
    0x00000006, 0x00000008, 0x3f800000, 0x00030021, 0x00000009, 0x00000005, 0x00050036, 0x00000005,
    0x00000002, 0x00000000, 0x00000009, 0x000200f8, 0x0000000a, 0x0006000c, 0x00000005, 0x0000000b,
    0x00000001, 0x00000001, 0x00000004, 0x0003003e, 0x00000003, 0x00000008, 0x000100fd, 0x00010038,
};
static const size_t nonsemantic_count = sizeof(nonsemantic) / sizeof(uint32_t);

static const uint32_t nonsemantic_extension[] = {
    0x07230203, 0x00010600, 0x00070000, 0x0000000c, 0x00000000, 0x00020011, 0x00000001, 0x0008000a,
    0x5f565053, 0x5f52484b, 0x5f6e6f6e, 0x616d6573, 0x6369746e, 0x666e695f, 0x0000006f, 0x0008000b,
    0x00000001, 0x536e6f4e, 0x6e616d65, 0x2e636974, 0x75626544, 0x69725067, 0x0066746e, 0x0003000e,
    0x00000000, 0x00000001, 0x0006000f, 0x00000004, 0x00000002, 0x6e69616d, 0x00000000, 0x00000003,
    0x00030010, 0x00000002, 0x00000007, 0x00030007, 0x00000004, 0x00000078, 0x00040047, 0x00000003,
    0x0000001e, 0x00000000, 0x00020013, 0x00000005, 0x00030016, 0x00000006, 0x00000020, 0x00040020,
    0x00000007, 0x00000003, 0x00000006, 0x0004003b, 0x00000007, 0x00000003, 0x00000003, 0x0004002b,
    0x00000006, 0x00000008, 0x3f800000, 0x00030021, 0x00000009, 0x00000005, 0x00050036, 0x00000005,
    0x00000002, 0x00000000, 0x00000009, 0x000200f8, 0x0000000a, 0x0006000c, 0x00000005, 0x0000000b,
    0x00000001, 0x00000001, 0x00000004, 0x0003003e, 0x00000003, 0x00000008, 0x000100fd, 0x00010038,
};
static const size_t nonsemantic_extension_count = sizeof(nonsemantic_extension) / sizeof(uint32_t);

static int failures;

static void check(int condition, const char *what)
{
    if (!condition) {
        printf("FAIL: %s\n", what);
        failures++;
    }
}

static size_t count_opcodes(const uint32_t *words, size_t count, uint32_t opcode)
{
    size_t found = 0;
    for (size_t i = 5; i < count; i += LENGTH(words[i])) {
        if (OPCODE(words[i]) == opcode)
            found++;
    }
    return found;
}

static size_t interface_size(const uint32_t *words, size_t count)
{
    for (size_t i = 5; i < count; i += LENGTH(words[i])) {
        if (OPCODE(words[i]) != SpvOpEntryPoint)
            continue;
        size_t length = LENGTH(words[i]);
        size_t name = 3;
        while (name < length) {
            uint32_t word = words[i + name];
            if (!(word & 0xff) || !(word & 0xff00) || !(word & 0xff0000) || !(word & 0xff000000))
                break;
            name++;
        }
        return length - (name + 1);
    }
    return 0;
}

static void write_module(const char *path, const struct spirv_code *code)
{
    FILE *out = fopen(path, "wb");
    if (!out) {
        printf("FAIL: %s cannot be written\n", path);
        failures++;
        return;
    }
    fwrite(code->words, sizeof(uint32_t), code->count, out);
    fclose(out);
}

static struct device test_device;
static int failed_allocations;
static bool fail_allocations;
static int driver_calls;
static bool chain_was_layer_owned;
static VkStructureType first_link;
static uint32_t driver_version;
static uint32_t driver_subgroup_size;

void *test_calloc(size_t count, size_t size)
{
    if (fail_allocations) {
        failed_allocations++;
        return NULL;
    }
    if (size && count > SIZE_MAX / size)
        return NULL;
    size_t bytes = count * size;
    void *memory = malloc(bytes > 0 ? bytes : 1);
    if (memory)
        memset(memory, 0, bytes);
    return memory;
}

struct device *device_from_handle(const void *dispatchable)
{
    return &test_device;
}

void layer_log(const char *format, ...)
{
}

void *map_get(const struct handle_map *map, uint64_t key)
{
    return NULL;
}

void map_put(struct handle_map *map, uint64_t key, void *value)
{
}

void *map_remove(struct handle_map *map, uint64_t key)
{
    return NULL;
}

static void read_stage(const VkPipelineShaderStageCreateInfo *stage, const void *app_chain)
{
    driver_calls++;
    chain_was_layer_owned = stage->pNext != app_chain;
    first_link = stage->pNext ? ((const VkBaseInStructure *)stage->pNext)->sType : VK_STRUCTURE_TYPE_MAX_ENUM;
    driver_version = 0;
    driver_subgroup_size = 0;
    for (const VkBaseInStructure *s = stage->pNext; s; s = s->pNext) {
        if (s->sType == VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_REQUIRED_SUBGROUP_SIZE_CREATE_INFO)
            driver_subgroup_size = ((const VkPipelineShaderStageRequiredSubgroupSizeCreateInfo *)s)->requiredSubgroupSize;
        if (s->sType == VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO) {
            const VkShaderModuleCreateInfo *info = (const VkShaderModuleCreateInfo *)s;
            driver_version = spirv_version(info->pCode, info->codeSize / sizeof(uint32_t));
        }
    }
}

static const void *expected_chain;

static VkResult VKAPI_CALL driver_compute(VkDevice device, VkPipelineCache cache, uint32_t count,
                                          const VkComputePipelineCreateInfo *infos,
                                          const VkAllocationCallbacks *allocator, VkPipeline *pipelines)
{
    read_stage(&infos[0].stage, expected_chain);
    return VK_SUCCESS;
}

static VkResult VKAPI_CALL driver_graphics(VkDevice device, VkPipelineCache cache, uint32_t count,
                                           const VkGraphicsPipelineCreateInfo *infos,
                                           const VkAllocationCallbacks *allocator, VkPipeline *pipelines)
{
    read_stage(&infos[0].pStages[0], expected_chain);
    return VK_SUCCESS;
}

static const VkShaderModuleCreateInfo app_module = {
    .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
    .codeSize = sizeof(newer),
    .pCode = newer,
};

static const VkPipelineShaderStageRequiredSubgroupSizeCreateInfo app_subgroup = {
    .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_REQUIRED_SUBGROUP_SIZE_CREATE_INFO,
    .pNext = &app_module,
    .requiredSubgroupSize = 16,
};

static const VkApplicationInfo app_unknown = {
    .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
    .pNext = &app_module,
};

static const VkPipelineShaderStageCreateInfo app_fragment = {
    .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
    .pNext = &app_subgroup,
    .stage = VK_SHADER_STAGE_FRAGMENT_BIT,
    .pName = "main",
};

static void check_pipelines(void)
{
    VkDevice device = (VkDevice)&test_device;
    VkPipeline pipeline = VK_NULL_HANDLE;
    pthread_mutex_init(&test_device.lock, NULL);
    test_device.CreateComputePipelines = driver_compute;
    test_device.CreateGraphicsPipelines = driver_graphics;

    VkComputePipelineCreateInfo compute = {
        .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
        .stage = {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                  .pNext = &app_subgroup,
                  .stage = VK_SHADER_STAGE_COMPUTE_BIT,
                  .pName = "main"},
    };
    expected_chain = &app_subgroup;
    check(CreateComputePipelines(device, VK_NULL_HANDLE, 1, &compute, NULL, &pipeline) == VK_SUCCESS,
          "a compute pipeline with inline code behind another structure is created");
    check(chain_was_layer_owned, "the compute stage gets the layer's own chain");
    check(first_link == VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_REQUIRED_SUBGROUP_SIZE_CREATE_INFO,
          "the compute stage keeps its chain order");
    check(driver_subgroup_size == 16, "the compute stage keeps its required subgroup size");
    check(driver_version == 0x00010300u, "the compute stage's inline code reaches the driver as SPIR-V 1.3");
    check(app_module.pCode == newer && app_module.codeSize == sizeof(newer),
          "the application's shader module create info is untouched");
    check(compute.stage.pNext == &app_subgroup, "the application's compute stage is untouched");

    compute.stage.pNext = &app_module;
    expected_chain = &app_module;
    check(CreateComputePipelines(device, VK_NULL_HANDLE, 1, &compute, NULL, &pipeline) == VK_SUCCESS,
          "a compute pipeline with inline code first in its chain is created");
    check(chain_was_layer_owned && driver_version == 0x00010300u,
          "inline code first in the chain reaches the driver as SPIR-V 1.3");

    compute.stage.pNext = &app_unknown;
    expected_chain = &app_unknown;
    check(CreateComputePipelines(device, VK_NULL_HANDLE, 1, &compute, NULL, &pipeline) == VK_SUCCESS,
          "a compute pipeline behind an unknown structure is created");
    check(!chain_was_layer_owned && driver_version == 0x00010600u,
          "a chain the layer cannot copy reaches the driver as the application wrote it");

    VkGraphicsPipelineCreateInfo graphics = {
        .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
        .stageCount = 1,
        .pStages = &app_fragment,
    };
    expected_chain = &app_subgroup;
    check(CreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &graphics, NULL, &pipeline) == VK_SUCCESS,
          "a graphics pipeline with inline code behind another structure is created");
    check(chain_was_layer_owned && driver_subgroup_size == 16 && driver_version == 0x00010300u,
          "the graphics stage reaches the driver capped with its chain intact");
    check(app_fragment.pNext == &app_subgroup && graphics.pStages == &app_fragment,
          "the application's graphics stages are untouched");

    driver_calls = 0;
    failed_allocations = 0;
    fail_allocations = true;
    VkResult compute_result = CreateComputePipelines(device, VK_NULL_HANDLE, 1, &compute, NULL, &pipeline);
    VkResult graphics_result = CreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &graphics, NULL, &pipeline);
    fail_allocations = false;
    check(failed_allocations > 0, "the allocations were made to fail");
    check(compute_result == VK_ERROR_OUT_OF_HOST_MEMORY, "a compute pipeline without memory reports it");
    check(graphics_result == VK_ERROR_OUT_OF_HOST_MEMORY, "a graphics pipeline without memory reports it");
    check(driver_calls == 0, "the driver is not called without memory");
    pthread_mutex_destroy(&test_device.lock);
}

int main(int argc, char **argv)
{
    struct spirv_code capped = {0};
    check(spirv_cap_version(newer, newer_count, &capped), "a SPIR-V 1.6 module is capped");
    check(capped.words != NULL, "the capped module is there");
    if (capped.words) {
        check(capped.words[1] == 0x00010300u, "the capped module is SPIR-V 1.3");
        check(interface_size(capped.words, capped.count) == 1,
              "the entry point lists only the input and output variables");
        check(count_opcodes(capped.words, capped.count, SpvOpKill) == 1, "the discard is an OpKill");
        check(count_opcodes(capped.words, capped.count, SpvOpTerminateInvocation) == 0,
              "no OpTerminateInvocation is left");
        if (argc > 1)
            write_module(argv[1], &capped);
        free(capped.words);
    }

    struct spirv_code kept = {0};
    check(!spirv_cap_version(older, older_count, &kept), "a SPIR-V 1.3 module is left alone");
    check(kept.words == NULL, "the untouched module is not handed back");
    check(!spirv_cap_version(capability, capability_count, &kept),
          "a module with a capability from SPIR-V 1.5 is left alone");
    check(!spirv_cap_version(memory, memory_count, &kept), "a module with the Vulkan memory model is left alone");
    check(!spirv_cap_version(nonuniform, nonuniform_count, &kept),
          "a module with ShaderNonUniform and no SPV_EXT_descriptor_indexing is left alone");
    check(!spirv_cap_version(select_struct, select_struct_count, &kept),
          "a module selecting a structure is left alone");
    check(!spirv_cap_version(nonsemantic, nonsemantic_count, &kept),
          "a module importing a NonSemantic set without SPV_KHR_non_semantic_info is left alone");
    check(kept.words == NULL, "no rejected module is handed back");

    struct spirv_code extension = {0};
    check(spirv_cap_version(nonuniform_extension, nonuniform_extension_count, &extension),
          "a module declaring SPV_EXT_descriptor_indexing for ShaderNonUniform is capped");
    if (extension.words) {
        check(extension.words[1] == 0x00010300u, "the module with the extension is SPIR-V 1.3");
        if (argc > 3)
            write_module(argv[3], &extension);
        free(extension.words);
    }

    struct spirv_code scalar = {0};
    check(spirv_cap_version(select_scalar, select_scalar_count, &scalar), "a module selecting a scalar is capped");
    if (scalar.words) {
        check(count_opcodes(scalar.words, scalar.count, SpvOpSelect) == 1, "the scalar select is kept");
        if (argc > 4)
            write_module(argv[4], &scalar);
        free(scalar.words);
    }

    struct spirv_code debug = {0};
    check(spirv_cap_version(nonsemantic_extension, nonsemantic_extension_count, &debug),
          "a module declaring SPV_KHR_non_semantic_info for its NonSemantic import is capped");
    if (debug.words) {
        check(debug.words[1] == 0x00010300u, "the module with the NonSemantic import is SPIR-V 1.3");
        if (argc > 5)
            write_module(argv[5], &debug);
        free(debug.words);
    }

    struct spirv_code guest = {0};
    check(spirv_cap_version(zinklike, zinklike_count, &guest), "a guest module with a private global is capped");
    if (guest.words) {
        check(guest.words[1] == 0x00010300u, "the guest module is SPIR-V 1.3");
        check(interface_size(guest.words, guest.count) == 1,
              "the guest module lists only the input and output variables");
        check(count_opcodes(guest.words, guest.count, SpvOpFunction) == 1, "the guest module keeps its function");
        if (argc > 2)
            write_module(argv[2], &guest);
        free(guest.words);
    }

    check_pipelines();

    if (failures)
        printf("%d check(s) failed\n", failures);
    else
        printf("mali compat spirv ok\n");
    return failures ? 1 : 0;
}
