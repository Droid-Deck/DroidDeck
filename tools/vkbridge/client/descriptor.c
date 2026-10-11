/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Descriptor update templates and private data.
 *
 * A template's data is laid out by the app (offsets and strides into its own structures). The
 * server's copy of the template is created with packed entries instead, and every update packs
 * the app's data the same way before sending it, so the server passes the blob straight on.
 */
#define _GNU_SOURCE
#include "vkb_client.h"
#include "gen/vkb_client_gen.h"

#include <string.h>

typedef struct vkb_template {
    uint32_t count;
    size_t size;                          /* packed bytes */
    VkDescriptorUpdateTemplateEntry *app; /* the app's entries */
    size_t *packed;                       /* packed offset per entry */
    size_t *elem;                         /* element size per entry (inline: 1) */
} vkb_template;

static size_t elem_size(VkDescriptorType t)
{
    switch (t) {
    case VK_DESCRIPTOR_TYPE_SAMPLER:
    case VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER:
    case VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE:
    case VK_DESCRIPTOR_TYPE_STORAGE_IMAGE:
    case VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT:
        return sizeof(VkDescriptorImageInfo);
    case VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER:
    case VK_DESCRIPTOR_TYPE_STORAGE_BUFFER:
    case VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC:
    case VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC:
        return sizeof(VkDescriptorBufferInfo);
    case VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER:
    case VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER:
        return sizeof(VkBufferView);
    case VK_DESCRIPTOR_TYPE_INLINE_UNIFORM_BLOCK:
        return 1;
    default:
        return 8;
    }
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkCreateDescriptorUpdateTemplate(VkDevice device, const VkDescriptorUpdateTemplateCreateInfo *pCreateInfo,
                                                                       const VkAllocationCallbacks *pAllocator,
                                                                       VkDescriptorUpdateTemplate *pTemplate)
{
    uint32_t n = pCreateInfo->descriptorUpdateEntryCount;
    vkb_template *t = calloc(1, sizeof(*t));
    t->count = n;
    t->app = calloc(n ? n : 1, sizeof(*t->app));
    t->packed = calloc(n ? n : 1, sizeof(size_t));
    t->elem = calloc(n ? n : 1, sizeof(size_t));
    VkDescriptorUpdateTemplateEntry *packed = calloc(n ? n : 1, sizeof(*packed));
    size_t off = 0;
    for (uint32_t i = 0; i < n; i++) {
        const VkDescriptorUpdateTemplateEntry *e = &pCreateInfo->pDescriptorUpdateEntries[i];
        t->app[i] = *e;
        size_t es = elem_size(e->descriptorType);
        t->elem[i] = es;
        t->packed[i] = off;
        packed[i] = *e;
        packed[i].offset = off;
        packed[i].stride = es;
        off += (es * e->descriptorCount + 7) & ~(size_t)7;
    }
    t->size = off;
    VkDescriptorUpdateTemplateCreateInfo ci = *pCreateInfo;
    ci.pDescriptorUpdateEntries = packed;
    VkResult r = vkb_wire_vkCreateDescriptorUpdateTemplate(device, &ci, pAllocator, pTemplate);
    free(packed);
    if (r != VK_SUCCESS) {
        free(t->app);
        free(t->packed);
        free(t->elem);
        free(t);
        return r;
    }
    vkb_map_put(&vkb_dev(device)->templates, (uint64_t)*pTemplate, t);
    return r;
}

static void template_free(vkb_template *t)
{
    if (!t) return;
    free(t->app);
    free(t->packed);
    free(t->elem);
    free(t);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkDestroyDescriptorUpdateTemplate(VkDevice device, VkDescriptorUpdateTemplate tmpl,
                                                                    const VkAllocationCallbacks *pAllocator)
{
    if (!tmpl) return;
    template_free(vkb_map_remove(&vkb_dev(device)->templates, (uint64_t)tmpl));
    vkb_wire_vkDestroyDescriptorUpdateTemplate(device, tmpl, pAllocator);
}

static void pack(const vkb_template *t, const void *pData, uint8_t *out)
{
    const uint8_t *src = pData;
    for (uint32_t i = 0; i < t->count; i++) {
        const VkDescriptorUpdateTemplateEntry *e = &t->app[i];
        uint8_t *dst = out + t->packed[i];
        if (e->descriptorType == VK_DESCRIPTOR_TYPE_INLINE_UNIFORM_BLOCK) {
            memcpy(dst, src + e->offset, e->descriptorCount);
            continue;
        }
        size_t es = t->elem[i];
        for (uint32_t j = 0; j < e->descriptorCount; j++) memcpy(dst + j * es, src + e->offset + (size_t)j * e->stride, es);
    }
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkUpdateDescriptorSetWithTemplate(VkDevice device, VkDescriptorSet set, VkDescriptorUpdateTemplate tmpl,
                                                                    const void *pData)
{
    vkb_device *dev = vkb_dev(device);
    vkb_template *t = vkb_map_get(&dev->templates, (uint64_t)tmpl);
    if (!t) {
        VKB_ERR("vkUpdateDescriptorSetWithTemplate with an unknown template");
        return;
    }
    vkb_call c;
    vkb_call_begin(&c, VKB_CMD_vkbDescUpdateRaw, dev->obj.table);
    vkb_enc_u64(&c.e, dev->obj.remote);
    vkb_enc_bytes(&c.e, &set, sizeof(set));
    vkb_enc_bytes(&c.e, &tmpl, sizeof(tmpl));
    /* Packed straight into the request. */
    vkb_enc_u8(&c.e, 1);
    vkb_enc_u64(&c.e, t->size);
    size_t at = vkb_enc_raw(&c.e, NULL, t->size);
    if (!c.e.oom) pack(t, pData, c.e.buf + at);
    vkb_call_exec_async(&c);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkCmdPushDescriptorSetWithTemplate(VkCommandBuffer commandBuffer, VkDescriptorUpdateTemplate tmpl,
                                                                        VkPipelineLayout layout, uint32_t set, const void *pData)
{
    vkb_cmdbuf *cb = vkb_cb(commandBuffer);
    vkb_template *t = vkb_map_get(&cb->device->templates, (uint64_t)tmpl);
    if (!t) {
        VKB_ERR("vkCmdPushDescriptorSetWithTemplateKHR with an unknown template");
        return;
    }
    vkb_enc *e = vkb_cmd_record_begin(commandBuffer, VKB_CMD_vkbPushDescRaw);
    if (!e) return;
    vkb_enc_u64(e, cb->obj.remote);
    vkb_enc_bytes(e, &tmpl, sizeof(tmpl));
    vkb_enc_bytes(e, &layout, sizeof(layout));
    vkb_enc_u32(e, set);
    vkb_enc_u8(e, 1);
    vkb_enc_u64(e, t->size);
    size_t at = vkb_enc_raw(e, NULL, t->size);
    if (!e->oom) pack(t, pData, e->buf + at);
    vkb_cmd_record_end(commandBuffer);
}

/* ------------------------------------------------------------------ private data (client-local) */

typedef struct vkb_private_slot {
    vkb_map values;
} vkb_private_slot;

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkCreatePrivateDataSlot(VkDevice device, const VkPrivateDataSlotCreateInfo *pCreateInfo,
                                                              const VkAllocationCallbacks *pAllocator, VkPrivateDataSlot *pSlot)
{
    (void)device;
    (void)pCreateInfo;
    (void)pAllocator;
    vkb_private_slot *s = calloc(1, sizeof(*s));
    if (!s) return VK_ERROR_OUT_OF_HOST_MEMORY;
    vkb_map_init(&s->values);
    *pSlot = (VkPrivateDataSlot)(uintptr_t)s;
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkDestroyPrivateDataSlot(VkDevice device, VkPrivateDataSlot slot, const VkAllocationCallbacks *pAllocator)
{
    (void)device;
    (void)pAllocator;
    vkb_private_slot *s = (vkb_private_slot *)(uintptr_t)slot;
    if (!s) return;
    vkb_map_destroy(&s->values);
    free(s);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkSetPrivateData(VkDevice device, VkObjectType objectType, uint64_t objectHandle,
                                                       VkPrivateDataSlot slot, uint64_t data)
{
    (void)device;
    (void)objectType;
    vkb_private_slot *s = (vkb_private_slot *)(uintptr_t)slot;
    vkb_map_put(&s->values, objectHandle, (void *)(uintptr_t)data);
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetPrivateData(VkDevice device, VkObjectType objectType, uint64_t objectHandle,
                                                   VkPrivateDataSlot slot, uint64_t *pData)
{
    (void)device;
    (void)objectType;
    vkb_private_slot *s = (vkb_private_slot *)(uintptr_t)slot;
    *pData = (uint64_t)(uintptr_t)vkb_map_get(&s->values, objectHandle);
}

/* ------------------------------------------------------------------ debug utils (no-ops) */

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkSetDebugUtilsObjectNameEXT(VkDevice device, const VkDebugUtilsObjectNameInfoEXT *pNameInfo)
{
    (void)device;
    (void)pNameInfo;
    return VK_SUCCESS;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkSetDebugUtilsObjectTagEXT(VkDevice device, const VkDebugUtilsObjectTagInfoEXT *pTagInfo)
{
    (void)device;
    (void)pTagInfo;
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkQueueBeginDebugUtilsLabelEXT(VkQueue queue, const VkDebugUtilsLabelEXT *pLabelInfo)
{
    (void)queue;
    (void)pLabelInfo;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkQueueEndDebugUtilsLabelEXT(VkQueue queue)
{
    (void)queue;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkQueueInsertDebugUtilsLabelEXT(VkQueue queue, const VkDebugUtilsLabelEXT *pLabelInfo)
{
    (void)queue;
    (void)pLabelInfo;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkCreateDebugUtilsMessengerEXT(VkInstance instance, const VkDebugUtilsMessengerCreateInfoEXT *pCreateInfo,
                                                                     const VkAllocationCallbacks *pAllocator, VkDebugUtilsMessengerEXT *pMessenger)
{
    (void)instance;
    (void)pCreateInfo;
    (void)pAllocator;
    static uint64_t next = 1;
    *pMessenger = (VkDebugUtilsMessengerEXT)(uintptr_t)__atomic_fetch_add(&next, 1, __ATOMIC_RELAXED);
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkDestroyDebugUtilsMessengerEXT(VkInstance instance, VkDebugUtilsMessengerEXT messenger,
                                                                  const VkAllocationCallbacks *pAllocator)
{
    (void)instance;
    (void)messenger;
    (void)pAllocator;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkSubmitDebugUtilsMessageEXT(VkInstance instance, VkDebugUtilsMessageSeverityFlagBitsEXT severity,
                                                               VkDebugUtilsMessageTypeFlagsEXT types,
                                                               const VkDebugUtilsMessengerCallbackDataEXT *pData)
{
    (void)instance;
    (void)severity;
    (void)types;
    (void)pData;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkCmdPushDescriptorSetWithTemplate2(VkCommandBuffer commandBuffer,
                                                                       const VkPushDescriptorSetWithTemplateInfo *pInfo)
{
    vkb_ep_vkCmdPushDescriptorSetWithTemplate(commandBuffer, pInfo->descriptorUpdateTemplate, pInfo->layout, pInfo->set, pInfo->pData);
}

/* ------------------------------------------------------------------ host image copy (not offered) */

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkCopyMemoryToImage(VkDevice device, const VkCopyMemoryToImageInfo *pInfo)
{
    (void)device;
    (void)pInfo;
    return VK_ERROR_FEATURE_NOT_PRESENT;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkCopyImageToMemory(VkDevice device, const VkCopyImageToMemoryInfo *pInfo)
{
    (void)device;
    (void)pInfo;
    return VK_ERROR_FEATURE_NOT_PRESENT;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkCopyImageToImage(VkDevice device, const VkCopyImageToImageInfo *pInfo)
{
    (void)device;
    (void)pInfo;
    return VK_ERROR_FEATURE_NOT_PRESENT;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkTransitionImageLayout(VkDevice device, uint32_t transitionCount,
                                                              const VkHostImageLayoutTransitionInfo *pTransitions)
{
    (void)device;
    (void)transitionCount;
    (void)pTransitions;
    return VK_ERROR_FEATURE_NOT_PRESENT;
}

/* ------------------------------------------------------------------ descriptor set batching */

/*
 * DXVK allocates a descriptor set (or two) per draw; one round trip each was most of a frame's
 * time. Sets are allocated from the server in batches per (pool, layout) and handed out here. A
 * pool's reset or destruction drops what was cached for it (the reset freed those sets anyway).
 *
 * Where it can, the client keeps the pool's books: what each layout takes of every descriptor
 * type, and what the pool has left. A batch then asks for no more than fits, and a pool that is
 * full answers VK_ERROR_OUT_OF_POOL_MEMORY here, without a round trip (DXVK fills a pool every few
 * hundred draws). Pools and layouts the books cannot follow (sets freed one by one, inline uniform
 * blocks, mutable or variable-count descriptors) go by the driver's answers: a batch that does not
 * fit falls back to what the app asked for, so the app sees the out-of-pool error when it would.
 */
#define DS_BATCH 256
#define DS_TYPES 12 /* VK_DESCRIPTOR_TYPE_SAMPLER..INPUT_ATTACHMENT, then ACCELERATION_STRUCTURE_KHR */

static int ds_type_index(VkDescriptorType t)
{
    if ((uint32_t)t <= VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT) return (int)t;
    if (t == VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR) return 11;
    return -1;
}

typedef struct ds_cache {
    VkDescriptorPool pool;
    VkDescriptorSetLayout layout;
    VkDescriptorSet sets[DS_BATCH];
    uint32_t n;
    uint32_t batch;              /* grows from 4 to the pool's max_batch while the pool keeps up */
    struct ds_cache *next;
} ds_cache;

static pthread_mutex_t ds_lock = PTHREAD_MUTEX_INITIALIZER;
static ds_cache *ds_head;

/* Pools the app made, with the batch they allow: a pool sized for exactly what the app will
 * allocate (DXVK's sampler pool) must not lose capacity to prefetching. */
typedef struct ds_pool {
    VkDescriptorPool pool;
    uint32_t max_batch;
    int books;                   /* capacity below is kept */
    uint32_t max_sets, sets_left;
    uint32_t size[DS_TYPES], left[DS_TYPES];
    struct ds_pool *next;
} ds_pool;
static ds_pool *ds_pools;

/* Layouts with what one set of them takes; known = 0 when the books cannot follow it. */
typedef struct ds_layout {
    VkDescriptorSetLayout layout;
    int known;
    uint32_t count[DS_TYPES];
    struct ds_layout *next;
} ds_layout;
static ds_layout *ds_layouts;

static ds_pool *pool_find(VkDescriptorPool pool)
{
    for (ds_pool *p = ds_pools; p; p = p->next)
        if (p->pool == pool) return p;
    return NULL;
}

static ds_layout *layout_find(VkDescriptorSetLayout layout)
{
    for (ds_layout *l = ds_layouts; l; l = l->next)
        if (l->layout == layout) return l;
    return NULL;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkCreateDescriptorSetLayout(VkDevice device, const VkDescriptorSetLayoutCreateInfo *ci,
                                                                  const VkAllocationCallbacks *pAllocator, VkDescriptorSetLayout *pLayout)
{
    VkResult r = vkb_wire_vkCreateDescriptorSetLayout(device, ci, pAllocator, pLayout);
    if (r != VK_SUCCESS) return r;
    ds_layout *l = calloc(1, sizeof(*l));
    if (!l) return r;
    l->layout = *pLayout;
    /* Binding flags only matter for variable counts; anything else in the chain is left alone. */
    l->known = 1;
    for (const VkBaseInStructure *n = ci->pNext; n; n = n->pNext) {
        if (n->sType != VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_BINDING_FLAGS_CREATE_INFO) {
            l->known = 0;
            continue;
        }
        const VkDescriptorSetLayoutBindingFlagsCreateInfo *bf = (const void *)n;
        for (uint32_t i = 0; i < bf->bindingCount; i++)
            if (bf->pBindingFlags[i] & VK_DESCRIPTOR_BINDING_VARIABLE_DESCRIPTOR_COUNT_BIT) l->known = 0;
    }
    for (uint32_t i = 0; i < ci->bindingCount && l->known; i++) {
        int t = ds_type_index(ci->pBindings[i].descriptorType);
        if (t < 0) l->known = 0;
        else l->count[t] += ci->pBindings[i].descriptorCount;
    }
    pthread_mutex_lock(&ds_lock);
    l->next = ds_layouts;
    ds_layouts = l;
    pthread_mutex_unlock(&ds_lock);
    return r;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkDestroyDescriptorSetLayout(VkDevice device, VkDescriptorSetLayout layout,
                                                               const VkAllocationCallbacks *pAllocator)
{
    if (!layout) return;
    pthread_mutex_lock(&ds_lock);
    for (ds_layout **pp = &ds_layouts; *pp; pp = &(*pp)->next) {
        if ((*pp)->layout == layout) {
            ds_layout *l = *pp;
            *pp = l->next;
            free(l);
            break;
        }
    }
    /* Sets cached for it stay allocated in their pools, but a new layout may get its handle. */
    for (ds_cache **pp = &ds_head; *pp;) {
        if ((*pp)->layout == layout) {
            ds_cache *c = *pp;
            *pp = c->next;
            free(c);
        } else {
            pp = &(*pp)->next;
        }
    }
    pthread_mutex_unlock(&ds_lock);
    vkb_wire_vkDestroyDescriptorSetLayout(device, layout, pAllocator);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkCreateDescriptorPool(VkDevice device, const VkDescriptorPoolCreateInfo *ci,
                                                             const VkAllocationCallbacks *pAllocator, VkDescriptorPool *pPool)
{
    VkResult r = vkb_wire_vkCreateDescriptorPool(device, ci, pAllocator, pPool);
    if (r != VK_SUCCESS) return r;
    ds_pool *p = calloc(1, sizeof(*p));
    if (!p) return r;
    p->pool = *pPool;
    p->max_batch = ci->maxSets >= 256 ? ci->maxSets / 8 : 1;
    if (p->max_batch > DS_BATCH) p->max_batch = DS_BATCH;
    p->books = !ci->pNext && !(ci->flags & VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT);
    p->max_sets = p->sets_left = ci->maxSets;
    for (uint32_t i = 0; i < ci->poolSizeCount && p->books; i++) {
        int t = ds_type_index(ci->pPoolSizes[i].type);
        if (t < 0) p->books = 0;
        else p->size[t] += ci->pPoolSizes[i].descriptorCount;
    }
    memcpy(p->left, p->size, sizeof(p->left));
    VKB_DBG("descriptor pool: maxSets %u, %u pool sizes, prefetch up to %u%s", ci->maxSets, ci->poolSizeCount,
            p->max_batch, p->books ? ", capacity tracked" : "");
    pthread_mutex_lock(&ds_lock);
    p->next = ds_pools;
    ds_pools = p;
    pthread_mutex_unlock(&ds_lock);
    return r;
}

static ds_cache *ds_find(VkDescriptorPool pool, VkDescriptorSetLayout layout, int create)
{
    for (ds_cache *c = ds_head; c; c = c->next)
        if (c->pool == pool && c->layout == layout) return c;
    if (!create) return NULL;
    ds_cache *c = calloc(1, sizeof(*c));
    if (!c) return NULL;
    c->pool = pool;
    c->layout = layout;
    c->batch = 4;
    c->next = ds_head;
    ds_head = c;
    return c;
}

/* A reset frees the pool's sets, cached ones included; destruction also forgets the pool. The
 * learned batch size survives a reset (pools are reset every frame). */
static void ds_drop_pool(VkDescriptorPool pool, int destroy)
{
    pthread_mutex_lock(&ds_lock);
    ds_cache **pp = &ds_head;
    while (*pp) {
        if ((*pp)->pool == pool && destroy) {
            ds_cache *c = *pp;
            *pp = c->next;
            free(c);
        } else {
            if ((*pp)->pool == pool) (*pp)->n = 0;
            pp = &(*pp)->next;
        }
    }
    ds_pool *p = pool_find(pool);
    if (p && !destroy) {
        p->sets_left = p->max_sets;
        memcpy(p->left, p->size, sizeof(p->left));
    }
    pthread_mutex_unlock(&ds_lock);
}

/* How many sets of layout l still fit in pool p by the books; UINT32_MAX when not kept. */
static uint32_t ds_fits(const ds_pool *p, const ds_layout *l)
{
    if (!p->books || !l || !l->known) return UINT32_MAX;
    uint32_t n = p->sets_left;
    for (int t = 0; t < DS_TYPES; t++)
        if (l->count[t] && p->left[t] / l->count[t] < n) n = p->left[t] / l->count[t];
    return n;
}

/* Fills the cache for (pool, layout) with `want` sets; returns the driver's result. */
static VkResult ds_refill(VkDevice device, ds_pool *p, const ds_layout *l, ds_cache *c, uint32_t want)
{
    VkDescriptorSetLayout layouts[DS_BATCH];
    for (uint32_t i = 0; i < want; i++) layouts[i] = c->layout;
    VkDescriptorSetAllocateInfo ai = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO, NULL, c->pool, want, layouts};
    VkResult r = vkb_wire_vkAllocateDescriptorSets(device, &ai, c->sets + c->n);
    if (r != VK_SUCCESS) return r;
    c->n += want;
    if (ds_fits(p, l) != UINT32_MAX) {
        p->sets_left -= want;
        for (int t = 0; t < DS_TYPES; t++) p->left[t] -= want * l->count[t];
    }
    return r;
}

/*
 * Sets the client names itself: with the pool's books kept and every layout known, whether the
 * allocation fits is known here, so the sets get names (VKB_DS_TAG | n) at once and the server
 * allocates the driver's sets without a reply (server_dsmap.c). All or nothing, as the spec has
 * it. VK_ERROR_UNKNOWN: not for this path (a layout the books cannot follow).
 */
#define VKB_DS_TAG (1ull << 63)
#define DS_NAMED_MAX 1024

static VkResult ds_alloc_named(VkDevice device, ds_pool *p, const VkDescriptorSetAllocateInfo *ai, VkDescriptorSet *out)
{
    static uint64_t next_name;
    uint32_t n = ai->descriptorSetCount;
    if (!n || n > DS_NAMED_MAX) return VK_ERROR_UNKNOWN;
    uint64_t need[DS_TYPES] = {0};
    for (uint32_t i = 0; i < n; i++) {
        const ds_layout *l = layout_find(ai->pSetLayouts[i]);
        if (!l || !l->known) return VK_ERROR_UNKNOWN;
        for (int t = 0; t < DS_TYPES; t++) need[t] += l->count[t];
    }
    if (n > p->sets_left) return VK_ERROR_OUT_OF_POOL_MEMORY;
    for (int t = 0; t < DS_TYPES; t++)
        if (need[t] > p->left[t]) return VK_ERROR_OUT_OF_POOL_MEMORY;
    vkb_device *dev = vkb_dev(device);
    vkb_call c;
    vkb_call_begin(&c, VKB_CMD_vkbAllocDescSets, dev->obj.table);
    vkb_enc_u64(&c.e, dev->obj.remote);
    vkb_enc_bytes(&c.e, &ai->descriptorPool, sizeof(ai->descriptorPool));
    vkb_enc_u32(&c.e, n);
    for (uint32_t i = 0; i < n; i++) {
        out[i] = (VkDescriptorSet)(VKB_DS_TAG | __atomic_add_fetch(&next_name, 1, __ATOMIC_RELAXED));
        vkb_enc_bytes(&c.e, &ai->pSetLayouts[i], sizeof(ai->pSetLayouts[i]));
        vkb_enc_u64(&c.e, (uint64_t)out[i]);
    }
    if (!vkb_call_exec_async(&c)) {
        for (uint32_t i = 0; i < n; i++) out[i] = VK_NULL_HANDLE;
        return VK_ERROR_DEVICE_LOST;
    }
    p->sets_left -= n;
    for (int t = 0; t < DS_TYPES; t++) p->left[t] -= (uint32_t)need[t];
    return VK_SUCCESS;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkAllocateDescriptorSets(VkDevice device, const VkDescriptorSetAllocateInfo *ai, VkDescriptorSet *out)
{
    /* Variable descriptor counts and the like are per allocation: those go straight through. */
    if (ai->pNext || getenv("VKBRIDGE_SYNC")) return vkb_wire_vkAllocateDescriptorSets(device, ai, out);
    pthread_mutex_lock(&ds_lock);
    ds_pool *p = pool_find(ai->descriptorPool);
    if (!p || p->max_batch <= 1) {
        pthread_mutex_unlock(&ds_lock);
        return vkb_wire_vkAllocateDescriptorSets(device, ai, out);
    }
    if (p->books) {
        VkResult nr = ds_alloc_named(device, p, ai, out);
        if (nr != VK_ERROR_UNKNOWN) {
            pthread_mutex_unlock(&ds_lock);
            return nr;
        }
    }
    VkResult r = VK_SUCCESS;
    uint32_t done = 0;
    for (; done < ai->descriptorSetCount; done++) {
        ds_cache *c = ds_find(ai->descriptorPool, ai->pSetLayouts[done], 1);
        if (!c) {
            r = VK_ERROR_OUT_OF_HOST_MEMORY;
            break;
        }
        if (c->batch > p->max_batch) c->batch = p->max_batch;
        if (!c->n) {
            const ds_layout *l = layout_find(c->layout);
            uint32_t fits = ds_fits(p, l), want = c->batch < fits ? c->batch : fits;
            if (!want) {
                r = VK_ERROR_OUT_OF_POOL_MEMORY;
                break;
            }
            r = ds_refill(device, p, l, c, want);
            if ((r == VK_ERROR_OUT_OF_POOL_MEMORY || r == VK_ERROR_FRAGMENTED_POOL) && want > 1) {
                /* The driver disagrees with the batch (or the books): just what was asked for. */
                c->batch = want / 2 > 4 ? want / 2 : 4;
                r = ds_refill(device, p, l, c, 1);
            } else if (r == VK_SUCCESS && c->batch < p->max_batch && want == c->batch) {
                c->batch = c->batch * 2 > p->max_batch ? p->max_batch : c->batch * 2;
            }
            if (r != VK_SUCCESS) break;
        }
        out[done] = c->sets[--c->n];
    }
    if (r != VK_SUCCESS) {
        /* None allocated, as the spec has it: what was taken goes back to the cache. */
        for (uint32_t i = 0; i < done; i++) {
            ds_cache *c = ds_find(ai->descriptorPool, ai->pSetLayouts[i], 0);
            if (c && c->n < DS_BATCH) c->sets[c->n++] = out[i];
        }
        for (uint32_t i = 0; i < ai->descriptorSetCount; i++) out[i] = VK_NULL_HANDLE;
    }
    pthread_mutex_unlock(&ds_lock);
    return r;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkResetDescriptorPool(VkDevice device, VkDescriptorPool pool, VkDescriptorPoolResetFlags flags)
{
    ds_drop_pool(pool, 0);
    return vkb_wire_vkResetDescriptorPool(device, pool, flags);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkDestroyDescriptorPool(VkDevice device, VkDescriptorPool pool, const VkAllocationCallbacks *pAllocator)
{
    if (!pool) return;
    ds_drop_pool(pool, 1);
    pthread_mutex_lock(&ds_lock);
    for (ds_pool **pp = &ds_pools; *pp; pp = &(*pp)->next) {
        if ((*pp)->pool == pool) {
            ds_pool *p = *pp;
            *pp = p->next;
            free(p);
            break;
        }
    }
    pthread_mutex_unlock(&ds_lock);
    vkb_wire_vkDestroyDescriptorPool(device, pool, pAllocator);
}
