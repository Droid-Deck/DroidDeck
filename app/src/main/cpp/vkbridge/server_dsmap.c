/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Descriptor sets named by the client. Where the client keeps a pool's books it knows an
 * allocation will succeed, so it names the sets itself (VKB_DS_TAG | n) and sends the allocation
 * without waiting (vkbAllocDescSets) - DXVK allocates a set or two per draw, and a round trip
 * each was most of the bridge's remaining cost per frame. Here those names map to the driver's
 * sets: wrappers in the device's dispatch table translate them wherever a set goes back to the
 * driver (updates, template updates, binds, frees), and a pool's reset or destruction forgets its
 * sets. Sets the server named itself (the synchronous path) pass through untouched.
 */
#define _GNU_SOURCE
#include "vkb_server.h"

#include <stdlib.h>
#include <string.h>

#define VKB_DS_TAG (1ull << 63)
#define DS_BUCKETS 8192
#define DS_LOCAL 64

typedef struct ds_ent {
    uint64_t id;
    VkDescriptorSet set;
    struct ds_ent *next;      /* bucket chain */
    struct ds_ent *pool_next; /* the pool's sets */
} ds_ent;

typedef struct ds_pool {
    VkDescriptorPool pool;
    ds_ent *sets;
    struct ds_pool *next;
} ds_pool;

typedef struct vkb_dsmap {
    pthread_mutex_t lock;
    ds_ent *buckets[DS_BUCKETS];
    ds_pool *pools;
    int warned;
    PFN_vkAllocateDescriptorSets allocate;
    PFN_vkUpdateDescriptorSets update;
    PFN_vkUpdateDescriptorSetWithTemplate update_template;
    PFN_vkCmdBindDescriptorSets bind;
    PFN_vkCmdBindDescriptorSets2 bind2;
    PFN_vkFreeDescriptorSets free_sets;
    PFN_vkResetDescriptorPool reset_pool;
    PFN_vkDestroyDescriptorPool destroy_pool;
} vkb_dsmap;

static unsigned bucket(uint64_t id)
{
    id *= 0x9e3779b97f4a7c15ull;
    return (unsigned)(id >> 51) & (DS_BUCKETS - 1);
}

static vkb_dsmap *cur(void)
{
    vkb_srv_table *t = vkb_srv_current_table();
    return t ? t->dsmap : NULL;
}

static int tagged(VkDescriptorSet s)
{
    return ((uint64_t)s & VKB_DS_TAG) != 0;
}

/* The driver's set for a client name; VK_NULL_HANDLE when its allocation failed. */
static VkDescriptorSet lookup(vkb_dsmap *m, VkDescriptorSet s)
{
    if (!tagged(s)) return s;
    uint64_t id = (uint64_t)s;
    pthread_mutex_lock(&m->lock);
    ds_ent *e = m->buckets[bucket(id)];
    while (e && e->id != id) e = e->next;
    VkDescriptorSet r = e ? e->set : VK_NULL_HANDLE;
    pthread_mutex_unlock(&m->lock);
    return r;
}

static void missing(vkb_dsmap *m, const char *where)
{
    if (__atomic_fetch_add(&m->warned, 1, __ATOMIC_RELAXED) < 20)
        VKB_ERR("%s: a descriptor set the client allocated ahead does not exist (its allocation failed); skipped", where);
}

static ds_pool *pool_get(vkb_dsmap *m, VkDescriptorPool pool, int create)
{
    for (ds_pool *p = m->pools; p; p = p->next)
        if (p->pool == pool) return p;
    if (!create) return NULL;
    ds_pool *p = calloc(1, sizeof(*p));
    if (!p) return NULL;
    p->pool = pool;
    p->next = m->pools;
    m->pools = p;
    return p;
}

/* Forgets the pool's sets (a reset or destruction freed them); the record goes with destroy. */
static void pool_forget(vkb_dsmap *m, VkDescriptorPool pool, int destroy)
{
    pthread_mutex_lock(&m->lock);
    for (ds_pool **pp = &m->pools; *pp; pp = &(*pp)->next) {
        ds_pool *p = *pp;
        if (p->pool != pool) continue;
        while (p->sets) {
            ds_ent *e = p->sets;
            p->sets = e->pool_next;
            ds_ent **bp = &m->buckets[bucket(e->id)];
            while (*bp && *bp != e) bp = &(*bp)->next;
            if (*bp) *bp = e->next;
            free(e);
        }
        if (destroy) {
            *pp = p->next;
            free(p);
        }
        break;
    }
    pthread_mutex_unlock(&m->lock);
}

/* ------------------------------------------------------------------ the allocation request */

/* vkbAllocDescSets: device, pool, count, then per set its layout and the client's name. */
void vkb_sv_vkbAllocDescSets(vkb_srv_call *c)
{
    VkDevice dev = (VkDevice)(uintptr_t)vkb_dec_u64(&c->d);
    VkDescriptorPool pool;
    vkb_dec_bytes(&c->d, &pool, sizeof(pool));
    uint32_t n = vkb_dec_u32(&c->d);
    if (c->d.err || !c->table || !c->table->dsmap || n == 0 || n > 4096) {
        vkb_srv_bad_message(c);
        return;
    }
    vkb_dsmap *m = c->table->dsmap;
    VkDescriptorSetLayout layouts_local[DS_LOCAL], *layouts = n <= DS_LOCAL ? layouts_local : malloc(n * sizeof(*layouts));
    uint64_t ids_local[DS_LOCAL], *ids = n <= DS_LOCAL ? ids_local : malloc(n * sizeof(*ids));
    VkDescriptorSet sets_local[DS_LOCAL], *sets = n <= DS_LOCAL ? sets_local : malloc(n * sizeof(*sets));
    if (!layouts || !ids || !sets) goto out;
    for (uint32_t i = 0; i < n; i++) {
        vkb_dec_bytes(&c->d, &layouts[i], sizeof(layouts[i]));
        ids[i] = vkb_dec_u64(&c->d);
    }
    if (c->d.err) {
        vkb_srv_bad_message(c);
        goto out;
    }
    VkDescriptorSetAllocateInfo ai = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO, NULL, pool, n, layouts};
    VkResult r = m->allocate(dev, &ai, sets);
    if (r != VK_SUCCESS) {
        VKB_ERR("allocating %u descriptor sets the client named ahead failed: %d (the client's pool books disagree "
                "with the driver)", n, r);
        goto out;
    }
    pthread_mutex_lock(&m->lock);
    ds_pool *p = pool_get(m, pool, 1);
    for (uint32_t i = 0; i < n; i++) {
        ds_ent *e = malloc(sizeof(*e));
        if (!e || !p) {
            free(e);
            break;
        }
        e->id = ids[i];
        e->set = sets[i];
        unsigned b = bucket(e->id);
        e->next = m->buckets[b];
        m->buckets[b] = e;
        e->pool_next = p->sets;
        p->sets = e;
    }
    pthread_mutex_unlock(&m->lock);
out:
    if (layouts != layouts_local) free(layouts);
    if (ids != ids_local) free(ids);
    if (sets != sets_local) free(sets);
}

/* ------------------------------------------------------------------ wrappers */

static void VKAPI_CALL ds_UpdateDescriptorSets(VkDevice device, uint32_t nw, const VkWriteDescriptorSet *w, uint32_t nc,
                                               const VkCopyDescriptorSet *cp)
{
    vkb_dsmap *m = cur();
    int any = 0;
    for (uint32_t i = 0; i < nw && !any; i++) any = tagged(w[i].dstSet);
    for (uint32_t i = 0; i < nc && !any; i++) any = tagged(cp[i].srcSet) || tagged(cp[i].dstSet);
    if (!any) {
        m->update(device, nw, w, nc, cp);
        return;
    }
    VkWriteDescriptorSet w_local[DS_LOCAL], *w2 = nw <= DS_LOCAL ? w_local : malloc(nw * sizeof(*w2));
    VkCopyDescriptorSet c_local[DS_LOCAL], *c2 = nc <= DS_LOCAL ? c_local : malloc(nc * sizeof(*c2));
    if ((nw && !w2) || (nc && !c2)) goto out;
    uint32_t kw = 0, kc = 0;
    for (uint32_t i = 0; i < nw; i++) {
        w2[kw] = w[i];
        w2[kw].dstSet = lookup(m, w[i].dstSet);
        if (w2[kw].dstSet) kw++;
        else missing(m, "vkUpdateDescriptorSets");
    }
    for (uint32_t i = 0; i < nc; i++) {
        c2[kc] = cp[i];
        c2[kc].srcSet = lookup(m, cp[i].srcSet);
        c2[kc].dstSet = lookup(m, cp[i].dstSet);
        if (c2[kc].srcSet && c2[kc].dstSet) kc++;
        else missing(m, "vkUpdateDescriptorSets (copy)");
    }
    if (kw || kc) m->update(device, kw, w2, kc, c2);
out:
    if (w2 != w_local) free(w2);
    if (c2 != c_local) free(c2);
}

static void VKAPI_CALL ds_UpdateDescriptorSetWithTemplate(VkDevice device, VkDescriptorSet set, VkDescriptorUpdateTemplate tmpl,
                                                          const void *data)
{
    vkb_dsmap *m = cur();
    VkDescriptorSet s = lookup(m, set);
    if (!s) {
        missing(m, "vkUpdateDescriptorSetWithTemplate");
        return;
    }
    m->update_template(device, s, tmpl, data);
}

/* Translates n sets into out; 0 when one of them does not exist. */
static int translate(vkb_dsmap *m, uint32_t n, const VkDescriptorSet *in, VkDescriptorSet *out)
{
    for (uint32_t i = 0; i < n; i++) {
        /* VK_NULL_HANDLE is allowed in binds (graphicsPipelineLibrary) and stays so. */
        out[i] = in[i] ? lookup(m, in[i]) : VK_NULL_HANDLE;
        if (in[i] && !out[i]) return 0;
    }
    return 1;
}

static void VKAPI_CALL ds_CmdBindDescriptorSets(VkCommandBuffer cb, VkPipelineBindPoint bp, VkPipelineLayout layout, uint32_t first,
                                                uint32_t n, const VkDescriptorSet *sets, uint32_t ndyn, const uint32_t *dyn)
{
    vkb_dsmap *m = cur();
    VkDescriptorSet local[DS_LOCAL], *s = n <= DS_LOCAL ? local : malloc(n * sizeof(*s));
    if (!s) return;
    if (translate(m, n, sets, s)) m->bind(cb, bp, layout, first, n, s, ndyn, dyn);
    else missing(m, "vkCmdBindDescriptorSets");
    if (s != local) free(s);
}

static void VKAPI_CALL ds_CmdBindDescriptorSets2(VkCommandBuffer cb, const VkBindDescriptorSetsInfo *info)
{
    vkb_dsmap *m = cur();
    uint32_t n = info->descriptorSetCount;
    VkDescriptorSet local[DS_LOCAL], *s = n <= DS_LOCAL ? local : malloc(n * sizeof(*s));
    if (!s) return;
    VkBindDescriptorSetsInfo i2 = *info;
    i2.pDescriptorSets = s;
    if (translate(m, n, info->pDescriptorSets, s)) m->bind2(cb, &i2);
    else missing(m, "vkCmdBindDescriptorSets2");
    if (s != local) free(s);
}

static VkResult VKAPI_CALL ds_FreeDescriptorSets(VkDevice device, VkDescriptorPool pool, uint32_t n, const VkDescriptorSet *sets)
{
    vkb_dsmap *m = cur();
    VkDescriptorSet local[DS_LOCAL], *s = n <= DS_LOCAL ? local : malloc(n * sizeof(*s));
    if (!s) return VK_ERROR_OUT_OF_HOST_MEMORY;
    /* Named sets come only from pools without FREE_DESCRIPTOR_SET (the client keeps no books on
     * those), so this is an app error at worst: what does not exist is left out. */
    uint32_t k = 0;
    for (uint32_t i = 0; i < n; i++) {
        VkDescriptorSet t = sets[i] ? lookup(m, sets[i]) : VK_NULL_HANDLE;
        if (t || !sets[i]) s[k++] = t;
    }
    VkResult r = k ? m->free_sets(device, pool, k, s) : VK_SUCCESS;
    if (s != local) free(s);
    return r;
}

static VkResult VKAPI_CALL ds_ResetDescriptorPool(VkDevice device, VkDescriptorPool pool, VkDescriptorPoolResetFlags flags)
{
    vkb_dsmap *m = cur();
    pool_forget(m, pool, 0);
    return m->reset_pool(device, pool, flags);
}

static void VKAPI_CALL ds_DestroyDescriptorPool(VkDevice device, VkDescriptorPool pool, const VkAllocationCallbacks *a)
{
    vkb_dsmap *m = cur();
    if (pool) pool_forget(m, pool, 1);
    m->destroy_pool(device, pool, a);
}

/* ------------------------------------------------------------------ device hooks */

void vkb_dsmap_device_init(vkb_srv_table *t)
{
    vkb_dispatch *dt = &t->dt;
    if (!dt->vkAllocateDescriptorSets || !dt->vkUpdateDescriptorSets || !dt->vkCmdBindDescriptorSets ||
        !dt->vkResetDescriptorPool || !dt->vkDestroyDescriptorPool || !dt->vkFreeDescriptorSets)
        return;
    vkb_dsmap *m = calloc(1, sizeof(*m));
    if (!m) return;
    pthread_mutex_init(&m->lock, NULL);
    m->allocate = dt->vkAllocateDescriptorSets;
    m->update = dt->vkUpdateDescriptorSets;
    m->update_template = dt->vkUpdateDescriptorSetWithTemplate;
    m->bind = dt->vkCmdBindDescriptorSets;
    m->bind2 = dt->vkCmdBindDescriptorSets2;
    m->free_sets = dt->vkFreeDescriptorSets;
    m->reset_pool = dt->vkResetDescriptorPool;
    m->destroy_pool = dt->vkDestroyDescriptorPool;
    dt->vkUpdateDescriptorSets = ds_UpdateDescriptorSets;
    if (m->update_template) dt->vkUpdateDescriptorSetWithTemplate = ds_UpdateDescriptorSetWithTemplate;
    dt->vkCmdBindDescriptorSets = ds_CmdBindDescriptorSets;
    if (m->bind2) dt->vkCmdBindDescriptorSets2 = ds_CmdBindDescriptorSets2;
    dt->vkFreeDescriptorSets = ds_FreeDescriptorSets;
    dt->vkResetDescriptorPool = ds_ResetDescriptorPool;
    dt->vkDestroyDescriptorPool = ds_DestroyDescriptorPool;
    t->dsmap = m;
}

void vkb_dsmap_device_destroy(vkb_srv_table *t)
{
    vkb_dsmap *m = t->dsmap;
    if (!m) return;
    vkb_dispatch *dt = &t->dt;
    dt->vkUpdateDescriptorSets = m->update;
    if (m->update_template) dt->vkUpdateDescriptorSetWithTemplate = m->update_template;
    dt->vkCmdBindDescriptorSets = m->bind;
    if (m->bind2) dt->vkCmdBindDescriptorSets2 = m->bind2;
    dt->vkFreeDescriptorSets = m->free_sets;
    dt->vkResetDescriptorPool = m->reset_pool;
    dt->vkDestroyDescriptorPool = m->destroy_pool;
    while (m->pools) pool_forget(m, m->pools->pool, 1);
    pthread_mutex_destroy(&m->lock);
    free(m);
    t->dsmap = NULL;
}
