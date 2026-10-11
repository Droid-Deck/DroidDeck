/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Vertex attribute divisor (VK_EXT/KHR_vertex_attribute_divisor) on a GPU without it.
 *
 * A binding with divisor d != 1 fetches element firstInstance + i / d for instance i (d = 0: always
 * firstInstance). Emulated by giving such bindings a stride of 0 - every instance reads the same
 * element - and rebinding the buffer at that element before each draw. With d > 1 the draw is
 * split into runs of instances that share elements; each run keeps its own firstInstance, so
 * gl_InstanceIndex and every ordinary instance-rate attribute stay exactly as they were.
 * Indirect draws cannot be split on the CPU: divisor-0 bindings use element 0 there, d > 1 is
 * approximated the same way (logged once).
 */
#define _GNU_SOURCE
#include "server_emu.h"

#include <stdlib.h>
#include <string.h>

#define MAX_BIND 32

typedef struct div_info {
    uint32_t mask;                 /* bindings emulated */
    uint32_t divisor[MAX_BIND];
    uint32_t stride[MAX_BIND];     /* the app's stride (baked pipelines) */
    int dynamic_stride;            /* the pipeline takes strides at bind time */
    int dynamic_input;             /* the pipeline takes vertex input at record time */
    int split;                     /* some divisor > 1: draws are split */
    VkPipeline zero_base;          /* variant whose vertex shader reads BaseInstance as 0 */
    VkShaderModule zero_vs;        /* its vertex shader, between creation steps */
    uint32_t vs_stage;
} div_info;

typedef struct pipe_rec {
    VkPipeline p;
    div_info info;
    struct pipe_rec *next;
} pipe_rec;

typedef struct gfx_state {
    VkPipeline pipeline;           /* bound graphics pipeline */
    div_info pipe;                 /* the bound pipeline's */
    div_info dyn;                  /* from vkCmdSetVertexInputEXT */
    int use_dyn;
    VkBuffer buf[MAX_BIND];
    VkDeviceSize off[MAX_BIND], size[MAX_BIND], stride[MAX_BIND];
    uint32_t bound;                /* slots the app bound */
    uint32_t tampered;             /* slots we rebound */
} gfx_state;

typedef struct div_state {
    pthread_mutex_t lock;
    pipe_rec *b[256];
} div_state;

static div_state *dstate(vkb_emu_device *e) { return e->divisor; }

static unsigned ph(VkPipeline p)
{
    uint64_t k = (uint64_t)p;
    k ^= k >> 33;
    k *= 0xff51afd7ed558ccdull;
    return (unsigned)(k >> 56);
}

static const div_info *find_pipe(vkb_emu_device *e, VkPipeline p)
{
    div_state *s = dstate(e);
    pthread_mutex_lock(&s->lock);
    pipe_rec *r = s->b[ph(p)];
    while (r && r->p != p) r = r->next;
    pthread_mutex_unlock(&s->lock);
    return r ? &r->info : NULL;
}

static void store_pipe(vkb_emu_device *e, VkPipeline p, const div_info *info)
{
    div_state *s = dstate(e);
    pipe_rec *r = calloc(1, sizeof(*r));
    r->p = p;
    r->info = *info;
    pthread_mutex_lock(&s->lock);
    r->next = s->b[ph(p)];
    s->b[ph(p)] = r;
    pthread_mutex_unlock(&s->lock);
}

static void drop_pipe(vkb_emu_device *e, VkPipeline p)
{
    div_state *s = dstate(e);
    pthread_mutex_lock(&s->lock);
    pipe_rec **pp = &s->b[ph(p)];
    while (*pp && (*pp)->p != p) pp = &(*pp)->next;
    pipe_rec *r = *pp;
    if (r) *pp = r->next;
    pthread_mutex_unlock(&s->lock);
    if (r && r->info.zero_base) e->dev->real.vkDestroyPipeline(e->dev->device, r->info.zero_base, NULL);
    free(r);
}

/* ------------------------------------------------------------------ pipelines */

static int has_dynamic(const VkGraphicsPipelineCreateInfo *ci, VkDynamicState d)
{
    if (!ci->pDynamicState) return 0;
    for (uint32_t i = 0; i < ci->pDynamicState->dynamicStateCount; i++)
        if (ci->pDynamicState->pDynamicStates[i] == d) return 1;
    return 0;
}

/* Strips the divisor state of one create info; returns what the pipeline needs at draw time. */
void vkb_emu_divisor_pipeline(vkb_emu_device *e, VkDevice device, VkGraphicsPipelineCreateInfo *ci, div_info *out)
{
    memset(out, 0, sizeof(*out));
    if (!(e->flags & VKB_EMU_DIVISOR)) return;
    out->dynamic_stride = has_dynamic(ci, VK_DYNAMIC_STATE_VERTEX_INPUT_BINDING_STRIDE);
    out->dynamic_input = has_dynamic(ci, VK_DYNAMIC_STATE_VERTEX_INPUT_EXT);
    /* Pieces linked from libraries bring their own records. */
    for (const VkBaseInStructure *b = ci->pNext; b; b = b->pNext) {
        if (b->sType != VK_STRUCTURE_TYPE_PIPELINE_LIBRARY_CREATE_INFO_KHR) continue;
        const VkPipelineLibraryCreateInfoKHR *l = (const void *)b;
        for (uint32_t i = 0; i < l->libraryCount; i++) {
            const div_info *li = find_pipe(e, l->pLibraries[i]);
            if (!li || !li->mask) continue;
            for (uint32_t k = 0; k < MAX_BIND; k++)
                if (li->mask & (1u << k)) {
                    out->mask |= 1u << k;
                    out->divisor[k] = li->divisor[k];
                    out->stride[k] = li->stride[k];
                    if (li->divisor[k] > 1) out->split = 1;
                }
        }
    }
    VkPipelineVertexInputStateCreateInfo *vi = (VkPipelineVertexInputStateCreateInfo *)ci->pVertexInputState;
    if (!vi || out->dynamic_input) return;
    VkPipelineVertexInputDivisorStateCreateInfo *d =
        vkb_emu_chain_take(vi, VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_DIVISOR_STATE_CREATE_INFO);
    if (!d) return;
    VkVertexInputBindingDescription *bd = (VkVertexInputBindingDescription *)vi->pVertexBindingDescriptions;
    for (uint32_t i = 0; i < d->vertexBindingDivisorCount; i++) {
        uint32_t b = d->pVertexBindingDivisors[i].binding, div = d->pVertexBindingDivisors[i].divisor;
        if (div == 1 || b >= MAX_BIND) continue;
        for (uint32_t k = 0; k < vi->vertexBindingDescriptionCount; k++) {
            if (bd[k].binding != b) continue;
            out->mask |= 1u << b;
            out->divisor[b] = div;
            out->stride[b] = bd[k].stride;
            if (div > 1) out->split = 1;
            bd[k].stride = 0;
        }
    }
    /* The vertex shader's SPIR-V is still at hand here (it may be inline). */
    if (out->split && ci->pStages) {
        for (uint32_t st = 0; st < ci->stageCount; st++) {
            if (ci->pStages[st].stage != VK_SHADER_STAGE_VERTEX_BIT) continue;
            out->zero_vs = vkb_emu_shader_zero_base_instance(e, device, &ci->pStages[st]);
            out->vs_stage = st;
        }
    }
}

void vkb_emu_divisor_created(vkb_emu_device *e, VkDevice device, VkPipelineCache cache, const VkGraphicsPipelineCreateInfo *ci,
                             VkPipeline p, div_info *info)
{
    if (!(e->flags & VKB_EMU_DIVISOR)) return;
    if (!p) {
        if (info->zero_vs) vkb_emu_real()->vkDestroyShaderModule(device, info->zero_vs, NULL);
        return;
    }
    if (info->zero_vs) {
        /* Split draws move BaseInstance: a variant that reads it as 0 serves the usual
         * firstInstance = 0 draws exactly. */
        VkPipelineShaderStageCreateInfo *st = malloc(ci->stageCount * sizeof(*st));
        memcpy(st, ci->pStages, ci->stageCount * sizeof(*st));
        st[info->vs_stage].module = info->zero_vs;
        st[info->vs_stage].pNext = NULL;
        VkGraphicsPipelineCreateInfo v = *ci;
        v.pStages = st;
        if (vkb_emu_real()->vkCreateGraphicsPipelines(device, cache, 1, &v, NULL, &info->zero_base) != VK_SUCCESS)
            info->zero_base = VK_NULL_HANDLE;
        vkb_emu_real()->vkDestroyShaderModule(device, info->zero_vs, NULL);
        info->zero_vs = VK_NULL_HANDLE;
        free(st);
    }
    if (info->mask || info->dynamic_input || info->dynamic_stride) store_pipe(e, p, info);
    if (info->mask)
        VKB_DBG("divisor: pipeline %p emulates bindings 0x%x (dynamic stride %d, dynamic input %d, split %d, variant %p)", (void *)p,
                info->mask, info->dynamic_stride, info->dynamic_input, info->split, (void *)info->zero_base);
}

size_t vkb_emu_divisor_info_size(void) { return sizeof(div_info); }

static VKAPI_ATTR void VKAPI_CALL emu_DestroyPipeline(VkDevice device, VkPipeline p, const VkAllocationCallbacks *pAllocator)
{
    if (p) drop_pipe(vkb_emu_cur(), p);
    vkb_emu_real()->vkDestroyPipeline(device, p, pAllocator);
}

/* ------------------------------------------------------------------ command buffers */

static gfx_state *gfx(vkb_emu_device *e, VkCommandBuffer cb) { return vkb_emu_cb_gfx(e, cb, sizeof(gfx_state)); }

void vkb_emu_gfx_reset(void *g)
{
    if (g) memset(g, 0, sizeof(gfx_state));
}

void vkb_emu_gfx_free(void *g) { free(g); }

void vkb_emu_gfx_bind_pipeline(vkb_emu_device *e, VkCommandBuffer cb, VkPipeline p)
{
    if (!(e->flags & VKB_EMU_DIVISOR)) return;
    gfx_state *g = gfx(e, cb);
    g->pipeline = p;
    const div_info *i = find_pipe(e, p);
    if (i) g->pipe = *i;
    else memset(&g->pipe, 0, sizeof(g->pipe));
    g->use_dyn = g->pipe.dynamic_input;
}

static void record_bind(gfx_state *g, uint32_t first, uint32_t n, const VkBuffer *b, const VkDeviceSize *o, const VkDeviceSize *s,
                        const VkDeviceSize *st)
{
    for (uint32_t i = 0; i < n && first + i < MAX_BIND; i++) {
        uint32_t k = first + i;
        g->buf[k] = b[i];
        g->off[k] = o[i];
        g->size[k] = s ? s[i] : VK_WHOLE_SIZE;
        if (st) g->stride[k] = st[i];
        g->bound |= 1u << k;
        g->tampered &= ~(1u << k);
    }
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdBindVertexBuffers(VkCommandBuffer cb, uint32_t first, uint32_t n, const VkBuffer *b,
                                                          const VkDeviceSize *o)
{
    vkb_emu_device *e = vkb_emu_cur();
    record_bind(gfx(e, cb), first, n, b, o, NULL, NULL);
    vkb_emu_real()->vkCmdBindVertexBuffers(cb, first, n, b, o);
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdBindVertexBuffers2(VkCommandBuffer cb, uint32_t first, uint32_t n, const VkBuffer *b,
                                                           const VkDeviceSize *o, const VkDeviceSize *s, const VkDeviceSize *st)
{
    vkb_emu_device *e = vkb_emu_cur();
    record_bind(gfx(e, cb), first, n, b, o, s, st);
    vkb_emu_real()->vkCmdBindVertexBuffers2(cb, first, n, b, o, s, st);
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdSetVertexInputEXT(VkCommandBuffer cb, uint32_t nb, const VkVertexInputBindingDescription2EXT *bd,
                                                          uint32_t na, const VkVertexInputAttributeDescription2EXT *ad)
{
    vkb_emu_device *e = vkb_emu_cur();
    gfx_state *g = gfx(e, cb);
    memset(&g->dyn, 0, sizeof(g->dyn));
    g->dyn.dynamic_stride = 1;
    g->dyn.dynamic_input = 1;
    VkVertexInputBindingDescription2EXT *w = (VkVertexInputBindingDescription2EXT *)bd;
    for (uint32_t i = 0; i < nb; i++) {
        uint32_t b = w[i].binding;
        if (w[i].inputRate != VK_VERTEX_INPUT_RATE_INSTANCE || w[i].divisor == 1 || b >= MAX_BIND) continue;
        g->dyn.mask |= 1u << b;
        g->dyn.divisor[b] = w[i].divisor;
        g->dyn.stride[b] = w[i].stride;
        g->stride[b] = w[i].stride;
        w[i].stride = 0;
        w[i].divisor = 1;
    }
    vkb_emu_real()->vkCmdSetVertexInputEXT(cb, nb, bd, na, ad);
}

/* Rebinds the emulated bindings for instances starting at `inst` (relative to the draw), and puts
 * back any slot we changed that the current pipeline does not emulate. */
static void prepare(vkb_emu_device *e, VkCommandBuffer cb, gfx_state *g, const div_info *di, uint32_t first_instance, uint32_t inst)
{
    const vkb_dispatch *r = vkb_emu_real();
    for (uint32_t k = 0; k < MAX_BIND; k++) {
        uint32_t bit = 1u << k;
        if (!(g->bound & bit)) continue;
        if (di->mask & bit) {
            uint32_t d = di->divisor[k];
            VkDeviceSize stride = di->dynamic_input ? g->dyn.stride[k] : (di->dynamic_stride ? g->stride[k] : di->stride[k]);
            VkDeviceSize elem = (VkDeviceSize)first_instance + (d ? inst / d : 0);
            VkDeviceSize off = g->off[k] + elem * stride;
            if (di->dynamic_stride || di->dynamic_input) {
                VkDeviceSize size = g->size[k] == VK_WHOLE_SIZE ? VK_WHOLE_SIZE : (g->size[k] > elem * stride ? g->size[k] - elem * stride : 0);
                VkDeviceSize zero = 0;
                r->vkCmdBindVertexBuffers2(cb, k, 1, &g->buf[k], &off, size == VK_WHOLE_SIZE ? NULL : &size, &zero);
            } else {
                r->vkCmdBindVertexBuffers(cb, k, 1, &g->buf[k], &off);
            }
            g->tampered |= bit;
        } else if (g->tampered & bit) {
            if (di->dynamic_stride) {
                VkDeviceSize size = g->size[k];
                r->vkCmdBindVertexBuffers2(cb, k, 1, &g->buf[k], &g->off[k], size == VK_WHOLE_SIZE ? NULL : &size, &g->stride[k]);
            } else {
                r->vkCmdBindVertexBuffers(cb, k, 1, &g->buf[k], &g->off[k]);
            }
            g->tampered &= ~bit;
        }
    }
    (void)e;
}

static const div_info *active(gfx_state *g)
{
    return g->use_dyn ? &g->dyn : &g->pipe;
}

/* The next instance where some emulated binding moves to its next element. */
static uint32_t next_boundary(const div_info *di, uint32_t i, uint32_t count)
{
    uint32_t next = count;
    for (uint32_t k = 0; k < MAX_BIND; k++) {
        if (!(di->mask & (1u << k)) || di->divisor[k] <= 1) continue;
        uint32_t d = di->divisor[k], nb = (i / d + 1) * d;
        if (nb < next) next = nb;
    }
    return next;
}

/* A split draw binds the BaseInstance-as-0 variant when that keeps it exact. */
static int split_begin(vkb_emu_device *e, VkCommandBuffer cb, gfx_state *g, const div_info *di, uint32_t fi, uint32_t ic)
{
    (void)e;
    if (!di->split || next_boundary(di, 0, ic) >= ic) return 0;
    if (fi == 0 && di->zero_base) {
        vkb_emu_real()->vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, di->zero_base);
        return 1;
    }
    if (fi != 0) VKB_ONCE(VKB_LOG_WARN, "divisor emulation: a split draw with firstInstance > 0 may see SV_InstanceID offset (logged once)");
    (void)g;
    return 0;
}

static void split_end(VkCommandBuffer cb, gfx_state *g, int variant)
{
    if (variant) vkb_emu_real()->vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, g->pipeline);
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdDraw(VkCommandBuffer cb, uint32_t vc, uint32_t ic, uint32_t fv, uint32_t fi)
{
    vkb_emu_device *e = vkb_emu_cur();
    gfx_state *g = gfx(e, cb);
    const div_info *di = active(g);
    if (!di->mask && !g->tampered) {
        vkb_emu_real()->vkCmdDraw(cb, vc, ic, fv, fi);
        return;
    }
    VKB_DBG("divisor: draw %u instances from %u, bindings 0x%x (dyn %d), bound 0x%x", ic, fi, di->mask, g->use_dyn, g->bound);
    int variant = split_begin(e, cb, g, di, fi, ic);
    for (uint32_t i = 0; i < ic || (ic == 0 && i == 0);) {
        uint32_t end = next_boundary(di, i, ic);
        prepare(e, cb, g, di, fi, i);
        if (ic) vkb_emu_real()->vkCmdDraw(cb, vc, end - i, fv, fi + i);
        if (!ic) break;
        i = end;
    }
    split_end(cb, g, variant);
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdDrawIndexed(VkCommandBuffer cb, uint32_t xc, uint32_t ic, uint32_t fx, int32_t vo, uint32_t fi)
{
    vkb_emu_device *e = vkb_emu_cur();
    gfx_state *g = gfx(e, cb);
    const div_info *di = active(g);
    if (!di->mask && !g->tampered) {
        vkb_emu_real()->vkCmdDrawIndexed(cb, xc, ic, fx, vo, fi);
        return;
    }
    int variant = split_begin(e, cb, g, di, fi, ic);
    for (uint32_t i = 0; i < ic || (ic == 0 && i == 0);) {
        uint32_t end = next_boundary(di, i, ic);
        prepare(e, cb, g, di, fi, i);
        if (ic) vkb_emu_real()->vkCmdDrawIndexed(cb, xc, end - i, fx, vo, fi + i);
        if (!ic) break;
        i = end;
    }
    split_end(cb, g, variant);
}

static void indirect_prepare(vkb_emu_device *e, VkCommandBuffer cb)
{
    gfx_state *g = gfx(e, cb);
    const div_info *di = active(g);
    if (!di->mask && !g->tampered) return;
    for (uint32_t k = 0; k < MAX_BIND; k++)
        if ((di->mask & (1u << k)) && di->divisor[k] > 1)
            VKB_ONCE(VKB_LOG_WARN, "divisor emulation: an indirect draw with an instance divisor > 1 is approximated (logged once)");
    prepare(e, cb, g, di, 0, 0);
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdDrawIndirect(VkCommandBuffer cb, VkBuffer b, VkDeviceSize o, uint32_t n, uint32_t s)
{
    indirect_prepare(vkb_emu_cur(), cb);
    vkb_emu_real()->vkCmdDrawIndirect(cb, b, o, n, s);
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdDrawIndexedIndirect(VkCommandBuffer cb, VkBuffer b, VkDeviceSize o, uint32_t n, uint32_t s)
{
    indirect_prepare(vkb_emu_cur(), cb);
    vkb_emu_real()->vkCmdDrawIndexedIndirect(cb, b, o, n, s);
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdDrawIndirectCount(VkCommandBuffer cb, VkBuffer b, VkDeviceSize o, VkBuffer cbuf, VkDeviceSize co,
                                                          uint32_t max, uint32_t s)
{
    indirect_prepare(vkb_emu_cur(), cb);
    vkb_emu_real()->vkCmdDrawIndirectCount(cb, b, o, cbuf, co, max, s);
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdDrawIndexedIndirectCount(VkCommandBuffer cb, VkBuffer b, VkDeviceSize o, VkBuffer cbuf,
                                                                 VkDeviceSize co, uint32_t max, uint32_t s)
{
    indirect_prepare(vkb_emu_cur(), cb);
    vkb_emu_real()->vkCmdDrawIndexedIndirectCount(cb, b, o, cbuf, co, max, s);
}

void vkb_emu_divisor_install(vkb_srv_table *dev)
{
    vkb_emu_device *e = dev->emu;
    if (!(e->flags & VKB_EMU_DIVISOR)) return;
    div_state *s = calloc(1, sizeof(*s));
    pthread_mutex_init(&s->lock, NULL);
    e->divisor = s;
    vkb_dispatch *dt = &dev->dt;
    dt->vkDestroyPipeline = emu_DestroyPipeline;
    dt->vkCmdBindVertexBuffers = emu_CmdBindVertexBuffers;
    if (dev->real.vkCmdBindVertexBuffers2) dt->vkCmdBindVertexBuffers2 = emu_CmdBindVertexBuffers2;
    if (dev->real.vkCmdSetVertexInputEXT) dt->vkCmdSetVertexInputEXT = emu_CmdSetVertexInputEXT;
    dt->vkCmdDraw = emu_CmdDraw;
    dt->vkCmdDrawIndexed = emu_CmdDrawIndexed;
    dt->vkCmdDrawIndirect = emu_CmdDrawIndirect;
    dt->vkCmdDrawIndexedIndirect = emu_CmdDrawIndexedIndirect;
    if (dev->real.vkCmdDrawIndirectCount) dt->vkCmdDrawIndirectCount = emu_CmdDrawIndirectCount;
    if (dev->real.vkCmdDrawIndexedIndirectCount) dt->vkCmdDrawIndexedIndirectCount = emu_CmdDrawIndexedIndirectCount;
}

void vkb_emu_divisor_uninstall(vkb_srv_table *dev)
{
    div_state *s = dev->emu->divisor;
    if (!s) return;
    for (int i = 0; i < 256; i++) {
        while (s->b[i]) {
            pipe_rec *r = s->b[i];
            s->b[i] = r->next;
            free(r);
        }
    }
    pthread_mutex_destroy(&s->lock);
    free(s);
    dev->emu->divisor = NULL;
}
