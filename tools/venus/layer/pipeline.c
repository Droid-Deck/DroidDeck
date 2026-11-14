#include "mali_compat.h"

#include <stdlib.h>
#include <string.h>

#define HANDLE(h) ((uint64_t)(uintptr_t)(h))
#define MAX_STAGES 8

/* A module with distances goes to the driver without them; its original stays here, for the
 * pipeline that can emulate its clip distances. */
VKAPI_ATTR VkResult VKAPI_CALL CreateShaderModule(VkDevice device, const VkShaderModuleCreateInfo *info,
                                                  const VkAllocationCallbacks *allocator, VkShaderModule *module)
{
    struct device *dev = device_from_handle(device);
    size_t count = info->codeSize / sizeof(uint32_t);
    if (!spirv_uses_distances(info->pCode, count))
        return dev->CreateShaderModule(device, info, allocator, module);

    struct spirv_code stripped;
    if (!spirv_strip_distances(info->pCode, count, &stripped)) {
        layer_log("could not take the clip and cull distances out of a shader module");
        return dev->CreateShaderModule(device, info, allocator, module);
    }
    VkShaderModuleCreateInfo copy = *info;
    copy.pCode = stripped.words;
    copy.codeSize = stripped.count * sizeof(uint32_t);
    VkResult result = dev->CreateShaderModule(device, &copy, allocator, module);
    free(stripped.words);
    if (result != VK_SUCCESS)
        return result;

    struct spirv_code *original = malloc(sizeof(*original));
    original->count = count;
    original->words = malloc(info->codeSize);
    memcpy(original->words, info->pCode, info->codeSize);
    pthread_mutex_lock(&dev->lock);
    map_put(&dev->shader_modules, HANDLE(*module), original);
    pthread_mutex_unlock(&dev->lock);
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL DestroyShaderModule(VkDevice device, VkShaderModule module,
                                               const VkAllocationCallbacks *allocator)
{
    struct device *dev = device_from_handle(device);
    pthread_mutex_lock(&dev->lock);
    struct spirv_code *original = map_remove(&dev->shader_modules, HANDLE(module));
    pthread_mutex_unlock(&dev->lock);
    if (original) {
        free(original->words);
        free(original);
    }
    dev->DestroyShaderModule(device, module, allocator);
}

struct stage_code {
    const uint32_t *words;
    size_t count;
    /* The application's own, when the code comes inline in the stage. */
    VkShaderModuleCreateInfo *inline_info;
    /* A module whose distances were already taken out at creation. */
    bool stripped_module;
};

static void find_stage_code(struct device *dev, const VkPipelineShaderStageCreateInfo *stage, struct stage_code *code)
{
    memset(code, 0, sizeof(*code));
    for (const VkBaseInStructure *s = stage->pNext; s; s = s->pNext) {
        if (s->sType == VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO) {
            code->inline_info = (VkShaderModuleCreateInfo *)s;
            code->words = code->inline_info->pCode;
            code->count = code->inline_info->codeSize / sizeof(uint32_t);
            return;
        }
    }
    if (stage->module == VK_NULL_HANDLE)
        return;
    pthread_mutex_lock(&dev->lock);
    struct spirv_code *original = map_get(&dev->shader_modules, HANDLE(stage->module));
    pthread_mutex_unlock(&dev->lock);
    if (original) {
        code->words = original->words;
        code->count = original->count;
        code->stripped_module = true;
    }
}

struct pipeline_rewrite {
    VkPipelineRasterizationStateCreateInfo rasterization;
    /* VK_EXT_depth_clip_enable's state, taken out of the application's chain for the call. */
    VkBaseOutStructure *clip_prev, *clip_node;
    VkPipelineViewportStateCreateInfo viewport;
    VkPipelineShaderStageCreateInfo stages[MAX_STAGES];
    VkShaderModuleCreateInfo modules[MAX_STAGES];
    struct spirv_code code[MAX_STAGES];
    VkShaderModule temporary[MAX_STAGES];
    /* Rewritten where they are, when they are not first in their stage's chain. */
    VkShaderModuleCreateInfo *in_place[MAX_STAGES];
    const uint32_t *in_place_code[MAX_STAGES];
    size_t in_place_size[MAX_STAGES];
};

static void use_code(struct device *dev, VkGraphicsPipelineCreateInfo *info, struct pipeline_rewrite *rw,
                     const struct stage_code *code, uint32_t i)
{
    if (info->pStages != rw->stages) {
        memcpy(rw->stages, info->pStages, info->stageCount * sizeof(*rw->stages));
        info->pStages = rw->stages;
    }
    const struct spirv_code *out = &rw->code[i];
    if (code->inline_info && rw->stages[i].pNext == code->inline_info) {
        rw->modules[i] = *code->inline_info;
        rw->modules[i].pCode = out->words;
        rw->modules[i].codeSize = out->count * sizeof(uint32_t);
        rw->stages[i].pNext = &rw->modules[i];
    } else if (code->inline_info) {
        rw->in_place[i] = code->inline_info;
        rw->in_place_code[i] = code->inline_info->pCode;
        rw->in_place_size[i] = code->inline_info->codeSize;
        code->inline_info->pCode = out->words;
        code->inline_info->codeSize = out->count * sizeof(uint32_t);
    } else {
        VkShaderModuleCreateInfo module_info = {
            .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
            .codeSize = out->count * sizeof(uint32_t),
            .pCode = out->words,
        };
        if (dev->CreateShaderModule(dev->handle, &module_info, NULL, &rw->temporary[i]) == VK_SUCCESS)
            rw->stages[i].module = rw->temporary[i];
    }
}

static int stage_index(const VkGraphicsPipelineCreateInfo *info, VkShaderStageFlagBits stage)
{
    for (uint32_t i = 0; i < info->stageCount; i++) {
        if (info->pStages[i].stage == stage)
            return (int)i;
    }
    return -1;
}

/*
 * Clip distances are emulated when the last stage before rasterization is the only one that
 * hands them on: a vertex shader feeding a geometry shader would need a varying of its own.
 * Anywhere else they are dropped, along with every cull distance.
 */
static void rewrite_shaders(struct device *dev, VkGraphicsPipelineCreateInfo *info, struct pipeline_rewrite *rw)
{
    uint32_t n = info->stageCount;
    if (!n || n > MAX_STAGES || !info->pStages)
        return;
    struct stage_code code[MAX_STAGES];
    bool uses[MAX_STAGES];
    bool any = false;
    for (uint32_t i = 0; i < n; i++) {
        find_stage_code(dev, &info->pStages[i], &code[i]);
        uses[i] = code[i].words && spirv_uses_distances(code[i].words, code[i].count);
        any |= uses[i];
    }
    if (!any)
        return;

    int producer = stage_index(info, VK_SHADER_STAGE_GEOMETRY_BIT);
    if (producer < 0)
        producer = stage_index(info, VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT);
    if (producer < 0)
        producer = stage_index(info, VK_SHADER_STAGE_VERTEX_BIT);
    int consumer = stage_index(info, VK_SHADER_STAGE_FRAGMENT_BIT);
    bool emulate = (dev->emulated & GAP_CLIP_DISTANCE) && producer >= 0 && consumer >= 0 && uses[producer] &&
                   code[consumer].words;
    for (uint32_t i = 0; i < n && emulate; i++) {
        if ((int)i != producer && (int)i != consumer && uses[i])
            emulate = false;
    }
    if (emulate) {
        struct clip_info produced, consumed;
        spirv_clip_info(code[producer].words, code[producer].count, CLIP_PRODUCER, &produced);
        spirv_clip_info(code[consumer].words, code[consumer].count, CLIP_CONSUMER, &consumed);
        uint32_t location = produced.locations_used > consumed.locations_used ? produced.locations_used
                                                                               : consumed.locations_used;
        emulate = produced.has_clip && location != UINT32_MAX &&
                  location + produced.clip_count <= dev->max_varying_locations &&
                  spirv_emulate_clip(code[producer].words, code[producer].count, CLIP_PRODUCER, location,
                                     produced.clip_count, &rw->code[producer]) &&
                  spirv_emulate_clip(code[consumer].words, code[consumer].count, CLIP_CONSUMER, location,
                                     produced.clip_count, &rw->code[consumer]);
        if (!emulate) {
            free(rw->code[producer].words);
            free(rw->code[consumer].words);
            rw->code[producer] = rw->code[consumer] = (struct spirv_code){0};
        }
    }

    for (uint32_t i = 0; i < n; i++) {
        if (!rw->code[i].words && uses[i] && !code[i].stripped_module &&
            !spirv_strip_distances(code[i].words, code[i].count, &rw->code[i])) {
            layer_log("could not take the clip and cull distances out of a pipeline's shader");
        }
        if (rw->code[i].words)
            use_code(dev, info, rw, &code[i], i);
    }
}

static void rewrite_pipeline(struct device *dev, VkGraphicsPipelineCreateInfo *info, struct pipeline_rewrite *rw)
{
    if ((dev->emulated & GAP_FILL_MODE) && info->pRasterizationState &&
        info->pRasterizationState->polygonMode != VK_POLYGON_MODE_FILL) {
        rw->rasterization = *info->pRasterizationState;
        rw->rasterization.polygonMode = VK_POLYGON_MODE_FILL;
        info->pRasterizationState = &rw->rasterization;
    }
    /* No VK_EXT_depth_clip_enable: depth clipping off is the nearest the driver has as depth clamp. */
    if ((dev->emulated & GAP_DEPTH_CLIP) && info->pRasterizationState) {
        VkBaseOutStructure *prev = (VkBaseOutStructure *)info->pRasterizationState;
        while (prev->pNext && prev->pNext->sType != VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_DEPTH_CLIP_STATE_CREATE_INFO_EXT)
            prev = prev->pNext;
        VkBaseOutStructure *node = prev->pNext;
        if (node) {
            bool clip = ((VkPipelineRasterizationDepthClipStateCreateInfoEXT *)node)->depthClipEnable;
            if (info->pRasterizationState != &rw->rasterization) {
                rw->rasterization = *info->pRasterizationState;
                if (prev == (VkBaseOutStructure *)info->pRasterizationState)
                    prev = (VkBaseOutStructure *)&rw->rasterization;
                info->pRasterizationState = &rw->rasterization;
            }
            if (!clip)
                rw->rasterization.depthClampEnable = VK_TRUE;
            if (prev != (VkBaseOutStructure *)&rw->rasterization) {
                rw->clip_prev = prev;
                rw->clip_node = node;
            }
            prev->pNext = node->pNext;
        }
    }
    if ((dev->emulated & GAP_MULTI_VIEWPORT) && info->pViewportState &&
        (info->pViewportState->viewportCount > 1 || info->pViewportState->scissorCount > 1)) {
        rw->viewport = *info->pViewportState;
        if (rw->viewport.viewportCount > 1)
            rw->viewport.viewportCount = 1;
        if (rw->viewport.scissorCount > 1)
            rw->viewport.scissorCount = 1;
        info->pViewportState = &rw->viewport;
    }
    if (dev->emulated & GAPS_SHADER)
        rewrite_shaders(dev, info, rw);
}

static void undo_rewrite(struct device *dev, struct pipeline_rewrite *rw)
{
    if (rw->clip_prev)
        rw->clip_prev->pNext = rw->clip_node;
    for (uint32_t i = 0; i < MAX_STAGES; i++) {
        if (rw->in_place[i]) {
            rw->in_place[i]->pCode = rw->in_place_code[i];
            rw->in_place[i]->codeSize = rw->in_place_size[i];
        }
        if (rw->temporary[i] != VK_NULL_HANDLE)
            dev->DestroyShaderModule(dev->handle, rw->temporary[i], NULL);
        free(rw->code[i].words);
    }
}

VKAPI_ATTR VkResult VKAPI_CALL CreateGraphicsPipelines(VkDevice device, VkPipelineCache cache, uint32_t count,
                                                       const VkGraphicsPipelineCreateInfo *infos,
                                                       const VkAllocationCallbacks *allocator, VkPipeline *pipelines)
{
    struct device *dev = device_from_handle(device);
    VkGraphicsPipelineCreateInfo *copies = malloc(count * sizeof(*copies));
    struct pipeline_rewrite *rewrites = calloc(count, sizeof(*rewrites));
    for (uint32_t i = 0; i < count; i++) {
        copies[i] = infos[i];
        rewrite_pipeline(dev, &copies[i], &rewrites[i]);
    }
    VkResult result = dev->CreateGraphicsPipelines(device, cache, count, copies, allocator, pipelines);
    for (uint32_t i = 0; i < count; i++)
        undo_rewrite(dev, &rewrites[i]);
    free(rewrites);
    free(copies);
    return result;
}

/* The driver has one viewport and one scissor; anything past the first is left out. */
VKAPI_ATTR void VKAPI_CALL CmdSetViewport(VkCommandBuffer cmd, uint32_t first, uint32_t count,
                                          const VkViewport *viewports)
{
    struct device *dev = device_from_handle(cmd);
    if (first == 0 && count > 0)
        dev->CmdSetViewport(cmd, 0, 1, viewports);
}

VKAPI_ATTR void VKAPI_CALL CmdSetScissor(VkCommandBuffer cmd, uint32_t first, uint32_t count, const VkRect2D *scissors)
{
    struct device *dev = device_from_handle(cmd);
    if (first == 0 && count > 0)
        dev->CmdSetScissor(cmd, 0, 1, scissors);
}

VKAPI_ATTR void VKAPI_CALL CmdSetViewportWithCount(VkCommandBuffer cmd, uint32_t count, const VkViewport *viewports)
{
    struct device *dev = device_from_handle(cmd);
    dev->CmdSetViewportWithCount(cmd, count > 1 ? 1 : count, viewports);
}

VKAPI_ATTR void VKAPI_CALL CmdSetScissorWithCount(VkCommandBuffer cmd, uint32_t count, const VkRect2D *scissors)
{
    struct device *dev = device_from_handle(cmd);
    dev->CmdSetScissorWithCount(cmd, count > 1 ? 1 : count, scissors);
}
