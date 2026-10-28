/* SPDX-License-Identifier: Apache-2.0 */
#define VK_NO_PROTOTYPES
#include "inline_shader.h"
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const VkAllocationCallbacks allocator = {0};
static const VkDevice device = (VkDevice)(uintptr_t)0x1234;
static const uint32_t code[] = {0x07230203, 0x00010000, 0, 1, 0};
static unsigned allocations, outstanding, fail_allocation, creates, destroys, fail_create;
static unsigned live[32];

void *hybris_scaled_alloc(const VkAllocationCallbacks *a, size_t size,
    VkSystemAllocationScope scope)
{
    assert(a == &allocator && scope == VK_SYSTEM_ALLOCATION_SCOPE_COMMAND);
    if (++allocations == fail_allocation) return NULL;
    void *p = malloc(size);
    assert(p);
    ++outstanding;
    return p;
}

void hybris_scaled_free(const VkAllocationCallbacks *a, void *p)
{
    assert(a == &allocator);
    if (p) { assert(outstanding); --outstanding; free(p); }
}

static VkResult VKAPI_CALL create_module(VkDevice d, const VkShaderModuleCreateInfo *info,
    const VkAllocationCallbacks *a, VkShaderModule *module)
{
    assert(d == device && a == &allocator);
    assert(info->sType == VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
    assert(!info->pNext && !info->flags && info->pCode == code && info->codeSize == sizeof(code));
    unsigned id = ++creates;
    assert(id < sizeof(live) / sizeof(live[0]));
    if (id == fail_create) {
        /* Failure does not transfer ownership, even if a driver touched output. */
        *module = (VkShaderModule)(uintptr_t)0xf00d;
        return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    }
    assert(!live[id]);
    live[id] = 1;
    *module = (VkShaderModule)(uintptr_t)id;
    return VK_SUCCESS;
}

static void VKAPI_CALL destroy_module(VkDevice d, VkShaderModule module,
    const VkAllocationCallbacks *a)
{
    unsigned id = (unsigned)(uintptr_t)module;
    assert(d == device && a == &allocator && id < sizeof(live) / sizeof(live[0]));
    assert(live[id]);
    live[id] = 0;
    ++destroys;
}

static void reset(void)
{
    assert(!outstanding);
    for (unsigned i = 0; i < sizeof(live) / sizeof(live[0]); ++i) assert(!live[i]);
    allocations = creates = destroys = fail_allocation = fail_create = 0;
}

static VkShaderModuleCreateInfo module_info(void)
{
    return (VkShaderModuleCreateInfo){ .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = sizeof(code), .pCode = code };
}

static VkPipelineShaderStageCreateInfo stage_info(const void *next)
{
    return (VkPipelineShaderStageCreateInfo){
        .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .pNext = next,
        .flags = VK_PIPELINE_SHADER_STAGE_CREATE_ALLOW_VARYING_SUBGROUP_SIZE_BIT,
        .stage = VK_SHADER_STAGE_VERTEX_BIT, .pName = "main" };
}

static VkGraphicsPipelineCreateInfo pipeline_info(uint32_t count,
    const VkPipelineShaderStageCreateInfo *stages)
{
    return (VkGraphicsPipelineCreateInfo){ .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
        .stageCount = count, .pStages = stages };
}

static VkResult prepare(VkGraphicsPipelineCreateInfo *info, struct hybris_inline_stages *owned,
    const char **reason)
{
    return hybris_inline_prepare(device, info, &allocator, create_module, owned, reason);
}

static void release(struct hybris_inline_stages *owned)
{
    hybris_inline_release(device, &allocator, destroy_module, owned);
    assert(!owned->stages && !owned->modules && !owned->count);
}

static void successful_copy(void)
{
    reset();
    VkShaderModuleCreateInfo module = module_info(), original_module = module;
    uint32_t value = 17;
    VkSpecializationMapEntry entry = { .constantID = 3, .size = sizeof(value) };
    VkSpecializationInfo specialization = { .mapEntryCount = 1, .pMapEntries = &entry,
        .dataSize = sizeof(value), .pData = &value };
    VkPipelineShaderStageCreateInfo stages[] = { stage_info(&module), stage_info(NULL), stage_info(&module) };
    stages[0].pSpecializationInfo = &specialization;
    stages[1].module = (VkShaderModule)(uintptr_t)0x222;
    stages[2].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    VkPipelineShaderStageCreateInfo originals[3];
    memcpy(originals, stages, sizeof(stages));
    VkPipelineRenderingCreateInfo rendering = { .sType = VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO };
    VkGraphicsPipelineCreateInfo info = pipeline_info(3, stages);
    info.pNext = &rendering;
    struct hybris_inline_stages owned;
    const char *reason = NULL;
    assert(prepare(&info, &owned, &reason) == VK_SUCCESS && !reason);
    assert(info.pStages != stages && info.pNext == &rendering && creates == 2 && destroys == 0);
    for (unsigned i = 0; i < 3; ++i) {
        VkPipelineShaderStageCreateInfo expected = originals[i];
        if (i != 1) { expected.module = info.pStages[i].module; expected.pNext = NULL; }
        assert(!memcmp(&expected, &info.pStages[i], sizeof(expected)));
    }
    assert(!memcmp(stages, originals, sizeof(stages)) && !memcmp(&module, &original_module, sizeof(module)));
    assert(live[(uintptr_t)info.pStages[0].module] && live[(uintptr_t)info.pStages[2].module]);
    release(&owned);
    release(&owned); /* Safe cleanup after an earlier error/cleanup path. */
    assert(destroys == 2 && !outstanding);
}

static void allocation_and_module_failures(void)
{
    for (unsigned failure = 1; failure <= 4; ++failure) {
        reset();
        if (failure <= 2) fail_allocation = failure;
        else fail_create = failure - 2;
        VkShaderModuleCreateInfo module = module_info();
        VkPipelineShaderStageCreateInfo stages[] = {stage_info(&module), stage_info(&module)};
        VkGraphicsPipelineCreateInfo info = pipeline_info(2, stages);
        struct hybris_inline_stages owned;
        const char *reason = NULL;
        VkResult result = prepare(&info, &owned, &reason);
        assert(result == (failure <= 2 ? VK_ERROR_OUT_OF_HOST_MEMORY : VK_ERROR_OUT_OF_DEVICE_MEMORY));
        assert(!stages[0].module && !stages[1].module && stages[0].pNext == &module);
        release(&owned);
        assert(destroys == (failure == 4 ? 1u : 0u) && !outstanding);
    }
}

static void unsupported_chains(void)
{
    for (unsigned mode = 0; mode < 6; ++mode) {
        reset();
        VkShaderModuleCreateInfo module = module_info();
        VkBaseInStructure extra = { .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_REQUIRED_SUBGROUP_SIZE_CREATE_INFO };
        VkPipelineShaderStageCreateInfo stage = stage_info(&module);
        if (mode == 0) module.pNext = &extra;
        if (mode == 1) { extra.pNext = (const void *)&module; stage.pNext = &extra; }
        if (mode == 2) module.flags = 1;
        if (mode == 3) module.pCode = NULL;
        if (mode == 4) module.codeSize = 16;
        if (mode == 5) module.codeSize = 21;
        VkGraphicsPipelineCreateInfo info = pipeline_info(1, &stage);
        struct hybris_inline_stages owned;
        const char *reason = NULL;
        assert(prepare(&info, &owned, &reason) == VK_ERROR_UNKNOWN && reason);
        assert(!allocations && !creates && info.pStages == &stage);
        release(&owned);
    }
}

static void no_inline_and_batch_cleanup(void)
{
    reset();
    VkBaseInStructure extra = { .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_REQUIRED_SUBGROUP_SIZE_CREATE_INFO };
    VkPipelineShaderStageCreateInfo stage = stage_info(&extra);
    stage.module = (VkShaderModule)(uintptr_t)0x222;
    VkGraphicsPipelineCreateInfo info = pipeline_info(1, &stage);
    struct hybris_inline_stages owned;
    const char *reason = NULL;
    assert(prepare(&info, &owned, &reason) == VK_SUCCESS);
    assert(info.pStages == &stage && !allocations && !creates);
    release(&owned);
    info = pipeline_info(0, NULL);
    assert(prepare(&info, &owned, &reason) == VK_SUCCESS);
    release(&owned);
    VkShaderModuleCreateInfo module = module_info();
    stage = stage_info(&module);
    struct hybris_inline_stages batch[3];
    VkGraphicsPipelineCreateInfo infos[3];
    fail_create = 3;
    for (unsigned i = 0; i < 3; ++i) {
        infos[i] = pipeline_info(1, &stage);
        assert(prepare(&infos[i], &batch[i], &reason) ==
            (i == 2 ? VK_ERROR_OUT_OF_DEVICE_MEMORY : VK_SUCCESS));
    }
    /* Earlier pipelines retain their modules until the entire batch is done. */
    assert(live[1] && live[2] && destroys == 0);
    for (unsigned i = 0; i < 3; ++i) release(&batch[i]);
    assert(destroys == 2 && !outstanding);
}

int main(void)
{
    successful_copy();
    allocation_and_module_failures();
    unsupported_chains();
    no_inline_and_batch_cleanup();
    reset();
    puts("hybris inline shader modules ok");
    return 0;
}
