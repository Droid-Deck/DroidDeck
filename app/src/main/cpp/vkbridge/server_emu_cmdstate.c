/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * What an app has bound in a command buffer, for emulations that record commands of their own
 * into it (the BCn decoder's dispatches) and must leave the app's state as it was: the compute
 * pipeline, compute descriptor sets, push constants and push descriptors. Bind calls are kept as
 * they were made (a later call that covers an earlier one replaces it) and replayed in order.
 */
#define _GNU_SOURCE
#include "server_emu.h"

#include <stdlib.h>
#include <string.h>

typedef struct bind_sets {
    VkPipelineLayout layout;
    uint32_t first, count, ndyn;
    VkDescriptorSet sets[32];
    uint32_t dyn[64];
} bind_sets;

typedef struct push_consts {
    VkPipelineLayout layout;
    VkShaderStageFlags stages;
    uint32_t offset, size;
    uint8_t data[256];
} push_consts;

typedef struct push_tmpl {
    VkDescriptorUpdateTemplate tmpl;
    VkPipelineLayout layout;
    uint32_t set;
    void *data;
    size_t size;
} push_tmpl;

typedef struct cb_state {
    VkCommandBuffer cb;
    VkPipeline compute_pipeline;
    bind_sets *sets;
    uint32_t nsets, capsets;
    push_consts *pcs;
    uint32_t npcs, cappcs;
    push_tmpl *pts;
    uint32_t npts, cappts;
    void *gfx;            /* graphics-side state (divisor emulation) */
    /* Descriptor sets emulations record with (no push descriptors), alive until the next begin. */
    VkDescriptorPool *scratch;
    uint32_t nscratch, cur_scratch, used_scratch;
    struct cb_state *next;
} cb_state;

#define CB_BUCKETS 512
#define SCRATCH_SETS 64
typedef struct cmdstate {
    pthread_mutex_t lock;
    cb_state *b[CB_BUCKETS];
} cmdstate;

/* Packed size of the template data being pushed (set by the raw push handler, server_misc.c). */
__thread size_t vkb_emu_push_size;

static cmdstate *g_states_for(vkb_emu_device *e) { return (cmdstate *)e->cmdstate; }

static unsigned ch(VkCommandBuffer cb)
{
    uint64_t k = (uint64_t)(uintptr_t)cb;
    k ^= k >> 30;
    k *= 0xbf58476d1ce4e5b9ull;
    return (unsigned)(k >> 55) & (CB_BUCKETS - 1);
}

static cb_state *get(vkb_emu_device *e, VkCommandBuffer cb)
{
    cmdstate *s = g_states_for(e);
    pthread_mutex_lock(&s->lock);
    cb_state *c = s->b[ch(cb)];
    while (c && c->cb != cb) c = c->next;
    if (!c) {
        c = calloc(1, sizeof(*c));
        c->cb = cb;
        c->next = s->b[ch(cb)];
        s->b[ch(cb)] = c;
    }
    pthread_mutex_unlock(&s->lock);
    return c;
}

/* A descriptor set of layout `dsl` (storage buffers and images only) for a command the emulation
 * records into cb; it lives until cb is begun again, when the app has seen the work finish. */
VkDescriptorSet vkb_emu_cb_scratch_set(vkb_emu_device *e, VkCommandBuffer cb, VkDescriptorSetLayout dsl)
{
    cb_state *c = get(e, cb);
    const vkb_dispatch *r = &e->dev->real;
    for (;;) {
        if (c->cur_scratch >= c->nscratch) {
            VkDescriptorPoolSize sizes[2] = {{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, SCRATCH_SETS * 2},
                                             {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, SCRATCH_SETS * 2}};
            VkDescriptorPoolCreateInfo pci = {VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, NULL, 0, SCRATCH_SETS, 2, sizes};
            VkDescriptorPool pool;
            VkDescriptorPool *np = realloc(c->scratch, (c->nscratch + 1) * sizeof(*np));
            if (!np) return VK_NULL_HANDLE;
            c->scratch = np;
            if (r->vkCreateDescriptorPool(e->dev->device, &pci, NULL, &pool) != VK_SUCCESS) return VK_NULL_HANDLE;
            c->scratch[c->nscratch++] = pool;
        }
        if (c->used_scratch < SCRATCH_SETS) {
            VkDescriptorSetAllocateInfo ai = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO, NULL, c->scratch[c->cur_scratch], 1, &dsl};
            VkDescriptorSet set;
            if (r->vkAllocateDescriptorSets(e->dev->device, &ai, &set) == VK_SUCCESS) {
                c->used_scratch++;
                return set;
            }
        }
        c->cur_scratch++;
        c->used_scratch = 0;
    }
}

void *vkb_emu_cb_gfx(vkb_emu_device *e, VkCommandBuffer cb, size_t size)
{
    cb_state *c = get(e, cb);
    if (!c->gfx) c->gfx = calloc(1, size);
    return c->gfx;
}

static void reset(vkb_emu_device *e, cb_state *c)
{
    for (uint32_t i = 0; i < c->nscratch && i <= c->cur_scratch; i++)
        e->dev->real.vkResetDescriptorPool(e->dev->device, c->scratch[i], 0);
    c->cur_scratch = 0;
    c->used_scratch = 0;
    c->compute_pipeline = VK_NULL_HANDLE;
    c->nsets = 0;
    c->npcs = 0;
    for (uint32_t i = 0; i < c->npts; i++) free(c->pts[i].data);
    c->npts = 0;
    vkb_emu_gfx_reset(c->gfx);
}

/* ------------------------------------------------------------------ wrappers */

static VKAPI_ATTR VkResult VKAPI_CALL emu_BeginCommandBuffer(VkCommandBuffer cb, const VkCommandBufferBeginInfo *info)
{
    reset(vkb_emu_cur(), get(vkb_emu_cur(), cb));
    return vkb_emu_real()->vkBeginCommandBuffer(cb, info);
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdBindPipeline(VkCommandBuffer cb, VkPipelineBindPoint bp, VkPipeline p)
{
    vkb_emu_device *e = vkb_emu_cur();
    if (bp == VK_PIPELINE_BIND_POINT_COMPUTE) get(e, cb)->compute_pipeline = p;
    if (bp == VK_PIPELINE_BIND_POINT_GRAPHICS) vkb_emu_gfx_bind_pipeline(e, cb, p);
    vkb_emu_real()->vkCmdBindPipeline(cb, bp, p);
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdBindDescriptorSets(VkCommandBuffer cb, VkPipelineBindPoint bp, VkPipelineLayout layout,
                                                           uint32_t first, uint32_t count, const VkDescriptorSet *sets,
                                                           uint32_t ndyn, const uint32_t *dyn)
{
    if (bp == VK_PIPELINE_BIND_POINT_COMPUTE && count <= 32 && ndyn <= 64) {
        cb_state *c = get(vkb_emu_cur(), cb);
        /* Drop earlier calls this one covers completely. */
        uint32_t k = 0;
        for (uint32_t i = 0; i < c->nsets; i++) {
            bind_sets *b = &c->sets[i];
            if (b->first >= first && b->first + b->count <= first + count) continue;
            c->sets[k++] = *b;
        }
        c->nsets = k;
        if (c->nsets == c->capsets) {
            c->capsets = c->capsets ? c->capsets * 2 : 8;
            c->sets = realloc(c->sets, c->capsets * sizeof(*c->sets));
        }
        bind_sets *b = &c->sets[c->nsets++];
        b->layout = layout;
        b->first = first;
        b->count = count;
        b->ndyn = ndyn;
        memcpy(b->sets, sets, count * sizeof(*sets));
        if (ndyn) memcpy(b->dyn, dyn, ndyn * sizeof(*dyn));
    }
    vkb_emu_real()->vkCmdBindDescriptorSets(cb, bp, layout, first, count, sets, ndyn, dyn);
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdPushConstants(VkCommandBuffer cb, VkPipelineLayout layout, VkShaderStageFlags stages,
                                                      uint32_t offset, uint32_t size, const void *data)
{
    if ((stages & VK_SHADER_STAGE_COMPUTE_BIT) && offset + size <= 256) {
        cb_state *c = get(vkb_emu_cur(), cb);
        uint32_t k = 0;
        for (uint32_t i = 0; i < c->npcs; i++) {
            push_consts *p = &c->pcs[i];
            if (p->offset >= offset && p->offset + p->size <= offset + size && p->stages == stages) continue;
            c->pcs[k++] = *p;
        }
        c->npcs = k;
        if (c->npcs == c->cappcs) {
            c->cappcs = c->cappcs ? c->cappcs * 2 : 8;
            c->pcs = realloc(c->pcs, c->cappcs * sizeof(*c->pcs));
        }
        push_consts *p = &c->pcs[c->npcs++];
        p->layout = layout;
        p->stages = stages;
        p->offset = offset;
        p->size = size;
        memcpy(p->data + offset, data, size);
    }
    vkb_emu_real()->vkCmdPushConstants(cb, layout, stages, offset, size, data);
}

static VKAPI_ATTR void VKAPI_CALL emu_CmdPushDescriptorSetWithTemplate(VkCommandBuffer cb, VkDescriptorUpdateTemplate tmpl,
                                                                      VkPipelineLayout layout, uint32_t set, const void *data)
{
    /* Only the template's bind point decides; recording it for both costs nothing when replayed
     * into the same bind point it was made for. Sizes are unknown here: the bridge's raw template
     * path hands over the packed size (vkb_emu_push_size). */
    vkb_emu_device *e = vkb_emu_cur();
    cb_state *c = get(e, cb);
    size_t size = vkb_emu_push_size;
    if (size && size < (1u << 20)) {
        if (c->npts == c->cappts) {
            c->cappts = c->cappts ? c->cappts * 2 : 4;
            c->pts = realloc(c->pts, c->cappts * sizeof(*c->pts));
        }
        push_tmpl *p = &c->pts[c->npts++];
        p->tmpl = tmpl;
        p->layout = layout;
        p->set = set;
        p->size = size;
        p->data = malloc(size);
        memcpy(p->data, data, size);
    }
    vkb_emu_real()->vkCmdPushDescriptorSetWithTemplate(cb, tmpl, layout, set, data);
}

/* Puts back what the app had bound for compute after an emulation recorded its own dispatch. */
void vkb_emu_restore_compute(vkb_emu_device *e, VkCommandBuffer cb)
{
    cb_state *c = get(e, cb);
    const vkb_dispatch *r = vkb_emu_real();
    if (c->compute_pipeline) r->vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, c->compute_pipeline);
    for (uint32_t i = 0; i < c->nsets; i++) {
        bind_sets *b = &c->sets[i];
        r->vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, b->layout, b->first, b->count, b->sets, b->ndyn, b->dyn);
    }
    for (uint32_t i = 0; i < c->npts; i++) {
        push_tmpl *p = &c->pts[i];
        r->vkCmdPushDescriptorSetWithTemplate(cb, p->tmpl, p->layout, p->set, p->data);
    }
    for (uint32_t i = 0; i < c->npcs; i++) {
        push_consts *p = &c->pcs[i];
        r->vkCmdPushConstants(cb, p->layout, p->stages, p->offset, p->size, p->data + p->offset);
    }
}

void vkb_emu_cmdstate_install(vkb_srv_table *dev)
{
    vkb_emu_device *e = dev->emu;
    cmdstate *s = calloc(1, sizeof(*s));
    pthread_mutex_init(&s->lock, NULL);
    e->cmdstate = s;
    dev->dt.vkBeginCommandBuffer = emu_BeginCommandBuffer;
    dev->dt.vkCmdBindPipeline = emu_CmdBindPipeline;
    dev->dt.vkCmdBindDescriptorSets = emu_CmdBindDescriptorSets;
    dev->dt.vkCmdPushConstants = emu_CmdPushConstants;
    if (dev->real.vkCmdPushDescriptorSetWithTemplate) dev->dt.vkCmdPushDescriptorSetWithTemplate = emu_CmdPushDescriptorSetWithTemplate;
}

void vkb_emu_cmdstate_uninstall(vkb_srv_table *dev)
{
    cmdstate *s = dev->emu->cmdstate;
    if (!s) return;
    for (int i = 0; i < CB_BUCKETS; i++) {
        while (s->b[i]) {
            cb_state *c = s->b[i];
            s->b[i] = c->next;
            reset(dev->emu, c);
            for (uint32_t j = 0; j < c->nscratch; j++) dev->real.vkDestroyDescriptorPool(dev->device, c->scratch[j], NULL);
            free(c->scratch);
            free(c->sets);
            free(c->pcs);
            free(c->pts);
            vkb_emu_gfx_free(c->gfx);
            free(c);
        }
    }
    pthread_mutex_destroy(&s->lock);
    free(s);
    dev->emu->cmdstate = NULL;
}
