/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Shader-side emulations, done when a graphics pipeline is created (both ends of the vertex ->
 * fragment interface are known then):
 *
 *  clip distance (shaderClipDistance missing): the last pre-rasterization stage's ClipDistance
 *    output becomes an ordinary varying at a free location; the fragment shader reads it and
 *    discards the fragment when any distance is negative. For planar clip distances this is the
 *    same set of pixels the hardware clipper would keep.
 *  cull distance (shaderCullDistance missing): the output becomes an unread varying - nothing is
 *    culled, which costs fragments but never drops geometry that should be visible.
 *  point size in geometry/tessellation (shaderTessellationAndGeometryPointSize missing): same.
 *
 * Shader modules are kept as SPIR-V so a pipeline can build rewritten variants; the driver's own
 * module is created from a variant that needs none of the missing features.
 */
#define _GNU_SOURCE
#include "server_emu.h"
#include "spirv_edit.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef struct shader_rec {
    VkShaderModule module;
    uint32_t *code;
    size_t size;
    struct shader_rec *next;
} shader_rec;

typedef struct shader_state {
    pthread_mutex_t lock;
    shader_rec *mods[1024];
} shader_state;

#define SHADER_EMU (VKB_EMU_CLIP_DISTANCE | VKB_EMU_CULL_DISTANCE | VKB_EMU_POINT_SIZE)

static unsigned mh(VkShaderModule m)
{
    uint64_t k = (uint64_t)m;
    k ^= k >> 31;
    k *= 0x94d049bb133111ebull;
    return (unsigned)(k >> 54);
}

static shader_state *state(vkb_emu_device *e) { return e->shaders; }

static const shader_rec *find_module(vkb_emu_device *e, VkShaderModule m)
{
    shader_state *s = state(e);
    pthread_mutex_lock(&s->lock);
    shader_rec *r = s->mods[mh(m)];
    while (r && r->module != m) r = r->next;
    pthread_mutex_unlock(&s->lock);
    return r;
}

/* ------------------------------------------------------------------ rewrites */

/* Finds a standalone Output/Input variable decorated with a builtin; 0 if none. 2 = in a block. */
static uint32_t builtin_var(const spv_mod *m, uint32_t builtin, uint32_t storage, int *in_block)
{
    *in_block = 0;
    for (uint32_t i = 0; i < m->count; i++) {
        const spv_inst *in = &m->ins[i];
        if (in->op == SpvOpDecorate && in->w[1] == SpvDecorationBuiltIn && in->w[2] == builtin) {
            int d = spv_find_def(m, in->w[0]);
            if (d >= 0 && m->ins[d].op == SpvOpVariable && m->ins[d].w[2] == storage) return in->w[0];
        }
        if (in->op == SpvOpMemberDecorate && in->w[2] == SpvDecorationBuiltIn && in->w[3] == builtin) *in_block = 1;
    }
    return 0;
}

static uint32_t var_pointee(const spv_mod *m, uint32_t var)
{
    int d = spv_find_def(m, var);
    if (d < 0) return 0;
    int p = spv_find_def(m, m->ins[d].w[0]);
    return p < 0 ? 0 : m->ins[p].w[2];
}

static void add_decoration(spv_mod *m, uint32_t id, uint32_t dec, uint32_t value)
{
    uint32_t ops[3] = {id, dec, value};
    spv_insert(m, spv_annotations_end(m), SpvOpDecorate, ops, 3);
}

/* Builtin output -> plain varying at *loc (advanced). Returns the element count, 0 if absent. */
static uint32_t demote_builtin(spv_mod *m, uint32_t builtin, uint32_t *loc, int *unsupported)
{
    int in_block;
    uint32_t var = builtin_var(m, builtin, SpvStorageClassOutput, &in_block);
    if (!var) {
        if (in_block) *unsupported = 1;
        return 0;
    }
    uint32_t pointee = var_pointee(m, var);
    uint32_t n = spv_array_length(m, pointee);
    if (!n) n = 1;
    spv_remove_decoration(m, var, SpvDecorationBuiltIn);
    add_decoration(m, var, SpvDecorationLocation, *loc);
    *loc += n;
    return n;
}

/* The pre-rasterization side. Returns the clip distance count (0: none), -1 if it cannot. */
static int rewrite_pre_raster(spv_mod *m, uint32_t emu, uint32_t clip_loc, int last_stage)
{
    int unsupported = 0;
    uint32_t loc = clip_loc;
    int clip = 0;
    if ((emu & VKB_EMU_CLIP_DISTANCE) && spv_has_capability(m, SpvCapabilityClipDistance)) {
        clip = (int)demote_builtin(m, SpvBuiltInClipDistance, &loc, &unsupported);
        spv_remove_capability(m, SpvCapabilityClipDistance);
    }
    if ((emu & VKB_EMU_CULL_DISTANCE) && spv_has_capability(m, SpvCapabilityCullDistance)) {
        demote_builtin(m, SpvBuiltInCullDistance, &loc, &unsupported);
        spv_remove_capability(m, SpvCapabilityCullDistance);
    }
    int model = spv_execution_model(m);
    if ((emu & VKB_EMU_POINT_SIZE) && (model == SpvExecutionModelGeometry || model == SpvExecutionModelTessellationEvaluation) &&
        (spv_has_capability(m, SpvCapabilityGeometryPointSize) || spv_has_capability(m, SpvCapabilityTessellationPointSize))) {
        demote_builtin(m, SpvBuiltInPointSize, &loc, &unsupported);
        spv_remove_capability(m, SpvCapabilityGeometryPointSize);
        spv_remove_capability(m, SpvCapabilityTessellationPointSize);
    }
    (void)last_stage;
    if (unsupported) VKB_ONCE(VKB_LOG_WARN, "emulation: clip/cull distance inside an output block is not handled (logged once)");
    return unsupported && !clip ? -1 : clip;
}

/* The fragment side: read the distances at `loc` and discard when any is negative. */
static int rewrite_fragment(spv_mod *m, uint32_t loc, uint32_t n)
{
    uint32_t f32 = spv_type_float(m, 32);
    uint32_t len = spv_const_u32(m, n);
    uint32_t arr = spv_type_array(m, f32, len);
    uint32_t parr = spv_type_pointer(m, SpvStorageClassInput, arr);
    uint32_t pf = spv_type_pointer(m, SpvStorageClassInput, f32);
    uint32_t tbool = spv_type_bool(m);
    uint32_t zero = spv_const_f32(m, 0.0f);
    uint32_t idx[16];
    for (uint32_t i = 0; i < n && i < 16; i++) idx[i] = spv_const_u32(m, i);
    uint32_t var = spv_new_id(m);
    uint32_t vops[3] = {parr, var, SpvStorageClassInput};
    spv_insert(m, spv_types_end(m), SpvOpVariable, vops, 3);
    add_decoration(m, var, SpvDecorationLocation, loc);
    spv_add_interface(m, var);

    int ep = spv_find_op(m, SpvOpEntryPoint, 0);
    if (ep < 0) return -1;
    uint32_t fn = m->ins[ep].w[1];
    int fi = -1;
    for (uint32_t i = 0; i < m->count; i++)
        if (m->ins[i].op == SpvOpFunction && m->ins[i].w[1] == fn) fi = (int)i;
    if (fi < 0) return -1;
    int li = spv_find_op(m, SpvOpLabel, (uint32_t)fi);
    if (li < 0) return -1;
    uint32_t first_label = m->ins[li].w[0];
    uint32_t at = (uint32_t)li + 1;
    while (at < m->count && (m->ins[at].op == SpvOpVariable || m->ins[at].op == SpvOpLine || m->ins[at].op == SpvOpNoLine)) at++;

    uint32_t any = 0;
    for (uint32_t i = 0; i < n && i < 16; i++) {
        uint32_t p = spv_new_id(m), v = spv_new_id(m), lt = spv_new_id(m);
        uint32_t ac[4] = {pf, p, var, idx[i]};
        at = spv_insert(m, at, SpvOpAccessChain, ac, 4) + 1;
        uint32_t ld[3] = {f32, v, p};
        at = spv_insert(m, at, SpvOpLoad, ld, 3) + 1;
        uint32_t cmp[4] = {tbool, lt, v, zero};
        at = spv_insert(m, at, SpvOpFOrdLessThan, cmp, 4) + 1;
        if (any) {
            uint32_t o = spv_new_id(m), orr[4] = {tbool, o, any, lt};
            at = spv_insert(m, at, SpvOpLogicalOr, orr, 4) + 1;
            any = o;
        } else {
            any = lt;
        }
    }
    uint32_t kill = spv_new_id(m), merge = spv_new_id(m);
    uint32_t sm[2] = {merge, 0};
    at = spv_insert(m, at, SpvOpSelectionMerge, sm, 2) + 1;
    uint32_t bc[3] = {any, kill, merge};
    at = spv_insert(m, at, SpvOpBranchConditional, bc, 3) + 1;
    at = spv_insert(m, at, SpvOpLabel, &kill, 1) + 1;
    at = spv_insert(m, at, spv_version(m) >= 0x00010600 ? SpvOpTerminateInvocation : SpvOpKill, NULL, 0) + 1;
    at = spv_insert(m, at, SpvOpLabel, &merge, 1) + 1;
    /* Phis that named the old entry block as a predecessor now come from the merge block. */
    for (uint32_t i = at; i < m->count; i++) {
        spv_inst *in = &m->ins[i];
        if (in->op != 245 /* OpPhi */) continue;
        for (uint32_t k = 3; k < (uint32_t)in->n - 1; k += 2)
            if (in->w[k] == first_label) in->w[k] = merge;
    }
    return m->err ? -1 : 0;
}

/* ------------------------------------------------------------------ module store */

static VkResult make_module(VkDevice device, const spv_mod *m, VkShaderModule *out)
{
    uint32_t *code;
    size_t size = spv_write(m, &code);
    if (!size) return VK_ERROR_OUT_OF_HOST_MEMORY;
    const char *dump = getenv("VKBRIDGE_DUMP_SPIRV");
    if (dump && *dump) {
        static int seq;
        char path[512];
        snprintf(path, sizeof(path), "%s/vkbridge-%d-%d.spv", dump, (int)getpid(), __atomic_fetch_add(&seq, 1, __ATOMIC_RELAXED));
        FILE *f = fopen(path, "wb");
        if (f) {
            fwrite(code, 1, size, f);
            fclose(f);
        }
    }
    VkShaderModuleCreateInfo ci = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, NULL, 0, size, code};
    VkResult r = vkb_emu_real()->vkCreateShaderModule(device, &ci, NULL, out);
    free(code);
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL emu_CreateShaderModule(VkDevice device, const VkShaderModuleCreateInfo *ci,
                                                            const VkAllocationCallbacks *pAllocator, VkShaderModule *pModule)
{
    vkb_emu_device *e = vkb_emu_cur();
    spv_mod m;
    VkResult r;
    int needs = 0;
    if (spv_parse(&m, ci->pCode, ci->codeSize) == 0) {
        needs = spv_has_capability(&m, SpvCapabilityClipDistance) || spv_has_capability(&m, SpvCapabilityCullDistance) ||
                spv_has_capability(&m, SpvCapabilityGeometryPointSize) || spv_has_capability(&m, SpvCapabilityTessellationPointSize);
    }
    if (needs) {
        /* The driver's module must not ask for what it lacks; pipelines use rewritten variants. */
        int maxo = spv_max_location(&m, SpvStorageClassOutput);
        rewrite_pre_raster(&m, e->flags, (uint32_t)(maxo + 1), 1);
        r = make_module(device, &m, pModule);
    } else {
        r = vkb_emu_real()->vkCreateShaderModule(device, ci, pAllocator, pModule);
    }
    spv_free(&m);
    if (r != VK_SUCCESS) return r;
    shader_rec *rec = calloc(1, sizeof(*rec));
    rec->module = *pModule;
    rec->size = ci->codeSize;
    rec->code = malloc(ci->codeSize);
    memcpy(rec->code, ci->pCode, ci->codeSize);
    shader_state *s = state(e);
    pthread_mutex_lock(&s->lock);
    unsigned h = mh(*pModule);
    rec->next = s->mods[h];
    s->mods[h] = rec;
    pthread_mutex_unlock(&s->lock);
    return r;
}

static VKAPI_ATTR void VKAPI_CALL emu_DestroyShaderModule(VkDevice device, VkShaderModule module, const VkAllocationCallbacks *pAllocator)
{
    vkb_emu_device *e = vkb_emu_cur();
    if (module) {
        shader_state *s = state(e);
        pthread_mutex_lock(&s->lock);
        shader_rec **pp = &s->mods[mh(module)];
        while (*pp && (*pp)->module != module) pp = &(*pp)->next;
        shader_rec *r = *pp;
        if (r) *pp = r->next;
        pthread_mutex_unlock(&s->lock);
        if (r) {
            free(r->code);
            free(r);
        }
    }
    vkb_emu_real()->vkDestroyShaderModule(device, module, pAllocator);
}

/* The SPIR-V of a stage: its module's (kept here) or the inline VkShaderModuleCreateInfo's. */
static int stage_code(vkb_emu_device *e, const VkPipelineShaderStageCreateInfo *st, const uint32_t **code, size_t *size)
{
    if (st->module) {
        const shader_rec *r = find_module(e, st->module);
        if (!r) return 0;
        *code = r->code;
        *size = r->size;
        return 1;
    }
    for (const VkBaseInStructure *b = st->pNext; b; b = b->pNext) {
        if (b->sType == VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO) {
            *code = ((const VkShaderModuleCreateInfo *)b)->pCode;
            *size = ((const VkShaderModuleCreateInfo *)b)->codeSize;
            return 1;
        }
    }
    return 0;
}

/* Replace a stage's shader by a rewritten module (recorded for destruction after the call). */
static void set_stage_module(VkDevice device, VkPipelineShaderStageCreateInfo *st, const spv_mod *m, VkShaderModule *mods,
                             uint32_t *nmods, uint32_t cap)
{
    VkShaderModule mod;
    if (*nmods >= cap || make_module(device, m, &mod) != VK_SUCCESS) return;
    mods[(*nmods)++] = mod;
    st->module = mod;
    vkb_emu_chain_take(st, VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
}

void vkb_emu_shader_pipeline(vkb_emu_device *e, VkDevice device, VkGraphicsPipelineCreateInfo *ci, VkShaderModule *mods,
                             uint32_t *nmods, uint32_t cap)
{
    if (!(e->flags & SHADER_EMU) || !ci->pStages) return;
    VkPipelineShaderStageCreateInfo *pre = NULL, *frag = NULL;
    int pre_rank = -1;
    for (uint32_t s = 0; s < ci->stageCount; s++) {
        VkPipelineShaderStageCreateInfo *st = (VkPipelineShaderStageCreateInfo *)&ci->pStages[s];
        int rank = st->stage == VK_SHADER_STAGE_VERTEX_BIT ? 0 : st->stage == VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT ? 1
                   : st->stage == VK_SHADER_STAGE_GEOMETRY_BIT ? 2 : -1;
        if (rank > pre_rank) {
            pre_rank = rank;
            pre = st;
        }
        if (st->stage == VK_SHADER_STAGE_FRAGMENT_BIT) frag = st;
    }
    if (!pre) return;
    const uint32_t *code;
    size_t size;
    if (!stage_code(e, pre, &code, &size)) return;
    spv_mod pm;
    if (spv_parse(&pm, code, size) != 0) return;
    if (!spv_has_capability(&pm, SpvCapabilityClipDistance) && !spv_has_capability(&pm, SpvCapabilityCullDistance) &&
        !spv_has_capability(&pm, SpvCapabilityGeometryPointSize) && !spv_has_capability(&pm, SpvCapabilityTessellationPointSize)) {
        spv_free(&pm);
        return;
    }
    spv_mod fm;
    int have_frag = 0;
    const uint32_t *fcode;
    size_t fsize;
    if (frag && stage_code(e, frag, &fcode, &fsize) && spv_parse(&fm, fcode, fsize) == 0) have_frag = 1;
    int loc = spv_max_location(&pm, SpvStorageClassOutput);
    if (have_frag) {
        int fl = spv_max_location(&fm, SpvStorageClassInput);
        if (fl > loc) loc = fl;
    }
    int clip = rewrite_pre_raster(&pm, e->flags, (uint32_t)(loc + 1), 1);
    if (clip >= 0) set_stage_module(device, pre, &pm, mods, nmods, cap);
    if (clip > 0) {
        if (have_frag) {
            if (rewrite_fragment(&fm, (uint32_t)(loc + 1), (uint32_t)clip) == 0) set_stage_module(device, frag, &fm, mods, nmods, cap);
        } else {
            VKB_ONCE(VKB_LOG_WARN, "emulation: clip distances in a pipeline without a fragment shader are not applied (logged once)");
        }
    }
    VKB_DBG("emulation: pipeline with %d clip distance(s) rewritten at location %d", clip, loc + 1);
    spv_free(&pm);
    if (have_frag) spv_free(&fm);
}

void vkb_emu_shader_install(vkb_srv_table *dev)
{
    vkb_emu_device *e = dev->emu;
    if (!(e->flags & SHADER_EMU)) return;
    shader_state *s = calloc(1, sizeof(*s));
    pthread_mutex_init(&s->lock, NULL);
    e->shaders = s;
    dev->dt.vkCreateShaderModule = emu_CreateShaderModule;
    dev->dt.vkDestroyShaderModule = emu_DestroyShaderModule;
}

void vkb_emu_shader_uninstall(vkb_srv_table *dev)
{
    shader_state *s = dev->emu->shaders;
    if (!s) return;
    for (int i = 0; i < 1024; i++) {
        while (s->mods[i]) {
            shader_rec *r = s->mods[i];
            s->mods[i] = r->next;
            free(r->code);
            free(r);
        }
    }
    pthread_mutex_destroy(&s->lock);
    free(s);
    dev->emu->shaders = NULL;
}
