/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * SPIR-V fixes for Mali's shader compiler, applied to every module the app hands the driver.
 *
 * DXVK (1.7.3 and later) guards texture reads with OpSelect(cond, texel, vec4(0)) where cond is an
 * OpConstantComposite of OpSpecConstantTrue/False. Mali's compiler folds that composite as if it
 * were a plain constant - taking the spec constants at their default - and every texture reads
 * black. Declaring the composite OpSpecConstantComposite (same operands, same length) keeps it
 * specializable and the fold away. Found by leegao (bionic-vulkan-wrapper issue #93,
 * "OpCompositeConstant" write-up, 2025-08-10); Vortek patches the same pattern.
 *
 * The code is patched in place: it lives in the server's receive buffer (vkCreateShaderModule's
 * pCode, or a VkShaderModuleCreateInfo chained to a pipeline stage), so nothing is copied.
 * On for ARM GPUs; VKBRIDGE_SPIRV_FIX=1/0 forces it (host tests).
 */
#define _GNU_SOURCE
#include "vkb_server.h"

#include <stdlib.h>
#include <string.h>

#define OP_SPEC_CONSTANT_TRUE 48
#define OP_SPEC_CONSTANT_FALSE 49
#define OP_SPEC_CONSTANT 50
#define OP_SPEC_CONSTANT_COMPOSITE 51
#define OP_SPEC_CONSTANT_OP 52
#define OP_CONSTANT_COMPOSITE 44
#define OP_FUNCTION 54

typedef struct vkb_spvfix {
    PFN_vkCreateShaderModule create_module;
    PFN_vkCreateGraphicsPipelines create_gfx;
    PFN_vkCreateComputePipelines create_compute;
    unsigned long patched; /* composites changed, for the log */
} vkb_spvfix;

static vkb_spvfix *cur(void)
{
    vkb_srv_table *t = vkb_srv_current_table();
    return t ? t->spvfix : NULL;
}

/* Patches one module in place; returns the number of composites changed. */
static unsigned fix_module(uint32_t *w, size_t bytes)
{
    size_t n = bytes / 4;
    if (n < 5 || w[0] != 0x07230203u) return 0;
    uint32_t bound = w[3];
    if (!bound || bound > (1u << 24)) return 0;
    uint8_t *spec = calloc(bound, 1);
    if (!spec) return 0;
    unsigned changed = 0;
    for (size_t i = 5; i < n;) {
        uint32_t op = w[i] & 0xffff, len = w[i] >> 16;
        if (!len || i + len > n) break;
        if (op == OP_FUNCTION) break; /* constants all come before the first function */
        if ((op == OP_SPEC_CONSTANT_TRUE || op == OP_SPEC_CONSTANT_FALSE || op == OP_SPEC_CONSTANT ||
             op == OP_SPEC_CONSTANT_COMPOSITE || op == OP_SPEC_CONSTANT_OP) && len >= 3 && w[i + 2] < bound) {
            spec[w[i + 2]] = 1;
        } else if (op == OP_CONSTANT_COMPOSITE && len >= 3 && w[i + 2] < bound) {
            int any = 0;
            for (uint32_t k = 3; k < len; k++) any |= w[i + k] < bound && spec[w[i + k]];
            if (any) {
                /* A composite of spec constants is itself one: declared so, it is no longer folded. */
                w[i] = (len << 16) | OP_SPEC_CONSTANT_COMPOSITE;
                spec[w[i + 2]] = 1;
                changed++;
            }
        }
        i += len;
    }
    free(spec);
    return changed;
}

static void note(vkb_spvfix *f, unsigned changed)
{
    if (!changed) return;
    unsigned long before = __atomic_fetch_add(&f->patched, changed, __ATOMIC_RELAXED);
    if (!before) VKB_INFO("SPIR-V fix: spec-constant composites redeclared for Mali's compiler (first in this device)");
}

static void fix_stages(vkb_spvfix *f, uint32_t n, const VkPipelineShaderStageCreateInfo *st)
{
    for (uint32_t i = 0; i < n; i++)
        for (const VkBaseInStructure *b = st[i].pNext; b; b = b->pNext)
            if (b->sType == VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO) {
                const VkShaderModuleCreateInfo *mi = (const void *)b;
                note(f, fix_module((uint32_t *)mi->pCode, mi->codeSize));
            }
}

static VkResult VKAPI_CALL fx_CreateShaderModule(VkDevice device, const VkShaderModuleCreateInfo *ci, const VkAllocationCallbacks *a,
                                                 VkShaderModule *out)
{
    vkb_spvfix *f = cur();
    note(f, fix_module((uint32_t *)ci->pCode, ci->codeSize));
    return f->create_module(device, ci, a, out);
}

static VkResult VKAPI_CALL fx_CreateGraphicsPipelines(VkDevice device, VkPipelineCache cache, uint32_t n,
                                                      const VkGraphicsPipelineCreateInfo *ci, const VkAllocationCallbacks *a,
                                                      VkPipeline *out)
{
    vkb_spvfix *f = cur();
    for (uint32_t i = 0; i < n; i++) fix_stages(f, ci[i].stageCount, ci[i].pStages);
    return f->create_gfx(device, cache, n, ci, a, out);
}

static VkResult VKAPI_CALL fx_CreateComputePipelines(VkDevice device, VkPipelineCache cache, uint32_t n,
                                                     const VkComputePipelineCreateInfo *ci, const VkAllocationCallbacks *a,
                                                     VkPipeline *out)
{
    vkb_spvfix *f = cur();
    for (uint32_t i = 0; i < n; i++) fix_stages(f, 1, &ci[i].stage);
    return f->create_compute(device, cache, n, ci, a, out);
}

void vkb_spvfix_device_init(vkb_srv_table *t)
{
    VkPhysicalDeviceProperties p;
    t->real.vkGetPhysicalDeviceProperties(t->physical, &p);
    const char *env = getenv("VKBRIDGE_SPIRV_FIX");
    int on = env ? strcmp(env, "0") != 0 : p.vendorID == 0x13B5; /* ARM */
    if (!on || !t->dt.vkCreateShaderModule) return;
    vkb_spvfix *f = calloc(1, sizeof(*f));
    if (!f) return;
    vkb_dispatch *dt = &t->dt;
    f->create_module = dt->vkCreateShaderModule;
    f->create_gfx = dt->vkCreateGraphicsPipelines;
    f->create_compute = dt->vkCreateComputePipelines;
    dt->vkCreateShaderModule = fx_CreateShaderModule;
    dt->vkCreateGraphicsPipelines = fx_CreateGraphicsPipelines;
    dt->vkCreateComputePipelines = fx_CreateComputePipelines;
    t->spvfix = f;
    VKB_INFO("device %u: Mali SPIR-V fixes on", t->id);
}

void vkb_spvfix_device_destroy(vkb_srv_table *t)
{
    vkb_spvfix *f = t->spvfix;
    if (!f) return;
    t->dt.vkCreateShaderModule = f->create_module;
    t->dt.vkCreateGraphicsPipelines = f->create_gfx;
    t->dt.vkCreateComputePipelines = f->create_compute;
    if (f->patched) VKB_INFO("device %u: SPIR-V fix changed %lu composites", t->id, f->patched);
    free(f);
    t->spvfix = NULL;
}
