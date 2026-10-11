/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "vkb_wire.h"
#include "vkb_log.h"

#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <unistd.h>

/* ------------------------------------------------------------------ arena */

struct vkb_arena_block {
    vkb_arena_block *next;
    size_t used, cap;
    /* data follows, 16-aligned */
};

#define ARENA_HDR ((sizeof(vkb_arena_block) + 15) & ~(size_t)15)

void *vkb_arena_alloc(vkb_arena *a, size_t n)
{
    n = (n + 15) & ~(size_t)15;
    if (n == 0) n = 16;
    vkb_arena_block *b = a->head;
    if (!b || b->cap - b->used < n) {
        size_t cap = n > 65536 ? n : 65536;
        b = malloc(ARENA_HDR + cap);
        if (!b) return NULL;
        b->next = a->head;
        b->used = 0;
        b->cap = cap;
        a->head = b;
    }
    void *p = (uint8_t *)b + ARENA_HDR + b->used;
    b->used += n;
    a->used_total += n;
    memset(p, 0, n);
    return p;
}

void vkb_arena_reset(vkb_arena *a)
{
    /* Keep the most recent block if it is the default size; free the rest. */
    vkb_arena_block *b = a->head, *keep = NULL;
    while (b) {
        vkb_arena_block *n = b->next;
        if (!keep && b->cap == 65536) {
            keep = b;
            keep->next = NULL;
            keep->used = 0;
        } else {
            free(b);
        }
        b = n;
    }
    a->head = keep;
    a->used_total = 0;
}

void vkb_arena_free(vkb_arena *a)
{
    vkb_arena_block *b = a->head;
    while (b) {
        vkb_arena_block *n = b->next;
        free(b);
        b = n;
    }
    a->head = NULL;
}

/* ------------------------------------------------------------------ encoder */

void vkb_enc_init(vkb_enc *e)
{
    memset(e, 0, sizeof(*e));
}

void vkb_enc_reset(vkb_enc *e)
{
    e->len = 0;
    e->nfds = 0;
    e->oom = 0;
}

void vkb_enc_free(vkb_enc *e)
{
    free(e->buf);
    memset(e, 0, sizeof(*e));
}

static int enc_grow(vkb_enc *e, size_t add)
{
    if (e->oom) return 0;
    size_t need = e->len + add;
    if (need <= e->cap) return 1;
    size_t cap = e->cap ? e->cap : 4096;
    while (cap < need) cap *= 2;
    uint8_t *nb = realloc(e->buf, cap);
    if (!nb) {
        e->oom = 1;
        return 0;
    }
    e->buf = nb;
    e->cap = cap;
    return 1;
}

void vkb_enc_bytes(vkb_enc *e, const void *p, size_t n)
{
    if (!n || !enc_grow(e, n)) return;
    if (p) memcpy(e->buf + e->len, p, n);
    else memset(e->buf + e->len, 0, n);
    e->len += n;
}

void vkb_enc_u8(vkb_enc *e, uint8_t v) { vkb_enc_bytes(e, &v, 1); }
void vkb_enc_u32(vkb_enc *e, uint32_t v) { vkb_enc_bytes(e, &v, 4); }
void vkb_enc_u64(vkb_enc *e, uint64_t v) { vkb_enc_bytes(e, &v, 8); }

static void enc_align(vkb_enc *e)
{
    size_t pad = (8 - (e->len & 7)) & 7;
    if (pad) vkb_enc_bytes(e, NULL, pad);
}

size_t vkb_enc_raw(vkb_enc *e, const void *p, size_t n)
{
    enc_align(e);
    size_t off = e->len;
    vkb_enc_bytes(e, p, n);
    return off;
}

void vkb_enc_patch_u64(vkb_enc *e, size_t off, uint64_t v)
{
    if (e->oom || off + 8 > e->len) return;
    memcpy(e->buf + off, &v, 8);
}

void vkb_enc_str(vkb_enc *e, const char *s)
{
    if (!s) {
        vkb_enc_u8(e, 0);
        return;
    }
    uint32_t n = (uint32_t)strlen(s);
    vkb_enc_u8(e, 1);
    vkb_enc_u32(e, n);
    vkb_enc_bytes(e, s, n);
}

void vkb_enc_strarray(vkb_enc *e, const char *const *a, uint32_t n)
{
    if (!a) {
        vkb_enc_u8(e, 0);
        return;
    }
    vkb_enc_u8(e, 1);
    vkb_enc_u32(e, n);
    for (uint32_t i = 0; i < n; i++) vkb_enc_str(e, a[i]);
}

void vkb_enc_blob(vkb_enc *e, const void *p, size_t n)
{
    if (!p) {
        vkb_enc_u8(e, 0);
        return;
    }
    vkb_enc_u8(e, 1);
    vkb_enc_u64(e, n);
    vkb_enc_raw(e, p, n);
}

void vkb_enc_fd(vkb_enc *e, int fd)
{
    if (fd < 0) {
        vkb_enc_u32(e, VKB_FD_NONE);
        return;
    }
    if (e->nfds >= VKB_MAX_FDS) {
        VKB_ERR("too many file descriptors in one message");
        e->oom = 1;
        return;
    }
    e->fds[e->nfds] = fd;
    vkb_enc_u32(e, (uint32_t)e->nfds);
    e->nfds++;
}

void vkb_enc_append(vkb_enc *e, const vkb_enc *src)
{
    enc_align(e);
    /* fd indices inside src are relative to src; a stream that carries fds is not appendable. */
    if (src->nfds) {
        VKB_ERR("internal: appending a message that carries file descriptors");
        e->oom = 1;
        return;
    }
    vkb_enc_bytes(e, src->buf, src->len);
}

static const VkBaseInStructure *next_known(const void *p)
{
    const VkBaseInStructure *b = p;
    while (b) {
        if (vkb_struct_size(b->sType)) return b;
        VKB_ONCE(VKB_LOG_WARN, "pNext: structure type %d is not bridged; dropped (logged once)", (int)b->sType);
        b = b->pNext;
    }
    return NULL;
}

void vkb_enc_chain(vkb_enc *e, const void *pNext)
{
    const VkBaseInStructure *b = next_known(pNext);
    if (!b) {
        vkb_enc_u32(e, VKB_CHAIN_END);
        return;
    }
    vkb_enc_u32(e, (uint32_t)b->sType);
    vkb_enc_chain_elem(e, b);
}

/* Out-structs that carry caller-provided arrays (the two-call idiom inside a struct). An
 * "empty" one is always answered with no elements (its elements carry chains of their own). */
typedef struct out_arr {
    VkStructureType t;
    size_t count_off, ptr_off, elem;
    int empty;
} out_arr;

#define OA(T, ST, CNT, PTR, ELEM, EMPTY) {ST, offsetof(T, CNT), offsetof(T, PTR), sizeof(ELEM), EMPTY}
static const out_arr out_arrays[] = {
    OA(VkDrmFormatModifierPropertiesListEXT, VK_STRUCTURE_TYPE_DRM_FORMAT_MODIFIER_PROPERTIES_LIST_EXT,
       drmFormatModifierCount, pDrmFormatModifierProperties, VkDrmFormatModifierPropertiesEXT, 0),
    OA(VkDrmFormatModifierPropertiesList2EXT, VK_STRUCTURE_TYPE_DRM_FORMAT_MODIFIER_PROPERTIES_LIST_2_EXT,
       drmFormatModifierCount, pDrmFormatModifierProperties, VkDrmFormatModifierProperties2EXT, 0),
    OA(VkPhysicalDeviceVulkan14Properties, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_4_PROPERTIES,
       copySrcLayoutCount, pCopySrcLayouts, VkImageLayout, 0),
    OA(VkPhysicalDeviceVulkan14Properties, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_4_PROPERTIES,
       copyDstLayoutCount, pCopyDstLayouts, VkImageLayout, 0),
    OA(VkPhysicalDeviceHostImageCopyProperties, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_HOST_IMAGE_COPY_PROPERTIES,
       copySrcLayoutCount, pCopySrcLayouts, VkImageLayout, 0),
    OA(VkPhysicalDeviceHostImageCopyProperties, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_HOST_IMAGE_COPY_PROPERTIES,
       copyDstLayoutCount, pCopyDstLayouts, VkImageLayout, 0),
    OA(VkPhysicalDeviceLayeredApiPropertiesListKHR, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_LAYERED_API_PROPERTIES_LIST_KHR,
       layeredApiCount, pLayeredApis, VkPhysicalDeviceLayeredApiPropertiesKHR, 1),
};
#undef OA
#define N_OUT_ARRAYS (sizeof(out_arrays) / sizeof(out_arrays[0]))

static uint32_t rd32(const void *base, size_t off) { uint32_t v; memcpy(&v, (const uint8_t *)base + off, 4); return v; }
static void wr32(void *base, size_t off, uint32_t v) { memcpy((uint8_t *)base + off, &v, 4); }
static void *rdp(const void *base, size_t off) { void *v; memcpy(&v, (const uint8_t *)base + off, sizeof(v)); return v; }
static void wrp(void *base, size_t off, void *v) { memcpy((uint8_t *)base + off, &v, sizeof(v)); }

static const VkBaseInStructure *next_known_out(const void *p)
{
    const VkBaseInStructure *b = p;
    while (b) {
        if (vkb_struct_size(b->sType)) return b;
        VKB_ONCE(VKB_LOG_WARN, "output pNext: structure type %d is not bridged; left unfilled (logged once)", (int)b->sType);
        b = b->pNext;
    }
    return NULL;
}

void vkb_enc_out_template(vkb_enc *e, const void *s, size_t size)
{
    vkb_enc_raw(e, s, size);
    const VkBaseInStructure *b = next_known_out(((const VkBaseInStructure *)s)->pNext);
    while (b) {
        size_t sz = vkb_struct_size(b->sType);
        vkb_enc_u32(e, (uint32_t)b->sType);
        vkb_enc_raw(e, b, sz);
        for (size_t i = 0; i < N_OUT_ARRAYS; i++)
            if (out_arrays[i].t == b->sType) vkb_enc_u8(e, !out_arrays[i].empty && rdp(b, out_arrays[i].ptr_off) != NULL);
        b = next_known_out(b->pNext);
    }
    vkb_enc_u32(e, VKB_CHAIN_END);
}

void vkb_enc_out_struct(vkb_enc *e, const void *s, size_t size)
{
    vkb_enc_raw(e, s, size);
    const VkBaseInStructure *b = ((const VkBaseInStructure *)s)->pNext;
    for (; b; b = b->pNext) {
        size_t sz = vkb_struct_size(b->sType);
        if (!sz) continue;
        vkb_enc_u32(e, (uint32_t)b->sType);
        vkb_enc_raw(e, b, sz);
        for (size_t i = 0; i < N_OUT_ARRAYS; i++) {
            const out_arr *oa = &out_arrays[i];
            if (oa->t != b->sType) continue;
            const void *arr = rdp(b, oa->ptr_off);
            vkb_enc_u8(e, arr != NULL);
            if (arr) {
                uint32_t n = rd32(b, oa->count_off);
                vkb_enc_u32(e, n);
                vkb_enc_raw(e, arr, (size_t)n * oa->elem);
            }
        }
    }
    vkb_enc_u32(e, VKB_CHAIN_END);
}

/* ------------------------------------------------------------------ decoder */

void vkb_dec_init(vkb_dec *d, const void *buf, size_t len, vkb_arena *a, int *fds, int nfds)
{
    memset(d, 0, sizeof(*d));
    d->buf = buf;
    d->len = len;
    d->arena = a;
    d->fds = fds;
    d->nfds = nfds;
}

void vkb_dec_bytes(vkb_dec *d, void *dst, size_t n)
{
    if (d->err || d->len - d->pos < n) {
        d->err = 1;
        if (dst) memset(dst, 0, n);
        return;
    }
    if (dst) memcpy(dst, d->buf + d->pos, n);
    d->pos += n;
}

uint8_t vkb_dec_u8(vkb_dec *d) { uint8_t v; vkb_dec_bytes(d, &v, 1); return v; }
uint32_t vkb_dec_u32(vkb_dec *d) { uint32_t v; vkb_dec_bytes(d, &v, 4); return v; }
uint64_t vkb_dec_u64(vkb_dec *d) { uint64_t v; vkb_dec_bytes(d, &v, 8); return v; }

static void dec_align(vkb_dec *d)
{
    size_t pad = (8 - (d->pos & 7)) & 7;
    if (d->len - d->pos < pad) {
        d->err = 1;
        return;
    }
    d->pos += pad;
}

void vkb_dec_raw_into(vkb_dec *d, void *dst, size_t n)
{
    dec_align(d);
    vkb_dec_bytes(d, dst, n);
}

const void *vkb_dec_raw_view(vkb_dec *d, size_t n)
{
    dec_align(d);
    if (d->err || d->len - d->pos < n) {
        d->err = 1;
        return NULL;
    }
    const void *p = d->buf + d->pos;
    d->pos += n;
    return p;
}

void *vkb_dec_alloc(vkb_dec *d, size_t n)
{
    if (d->err) return NULL;
    if (n > ((size_t)1 << 32)) {
        d->err = 1;
        return NULL;
    }
    void *p = vkb_arena_alloc(d->arena, n);
    if (!p) d->err = 1;
    return p;
}

void *vkb_dec_alloc_zero(vkb_dec *d, size_t n) { return vkb_dec_alloc(d, n); }

const char *vkb_dec_str(vkb_dec *d)
{
    if (!vkb_dec_u8(d)) return NULL;
    uint32_t n = vkb_dec_u32(d);
    if (d->err || d->len - d->pos < n) {
        d->err = 1;
        return NULL;
    }
    char *s = vkb_dec_alloc(d, (size_t)n + 1);
    if (!s) return NULL;
    memcpy(s, d->buf + d->pos, n);
    s[n] = 0;
    d->pos += n;
    return s;
}

const char *const *vkb_dec_strarray(vkb_dec *d)
{
    if (!vkb_dec_u8(d)) return NULL;
    uint32_t n = vkb_dec_u32(d);
    if (d->err || n > (d->len - d->pos)) {
        d->err = 1;
        return NULL;
    }
    const char **a = vkb_dec_alloc(d, (size_t)(n ? n : 1) * sizeof(char *));
    for (uint32_t i = 0; a && i < n; i++) a[i] = vkb_dec_str(d);
    return a;
}

const void *vkb_dec_blob(vkb_dec *d, size_t *n)
{
    if (!vkb_dec_u8(d)) {
        if (n) *n = 0;
        return NULL;
    }
    uint64_t sz = vkb_dec_u64(d);
    if (n) *n = (size_t)sz;
    return vkb_dec_raw_view(d, (size_t)sz);
}

void vkb_dec_blob_into(vkb_dec *d, void *dst, size_t cap)
{
    size_t n = 0;
    const void *p = vkb_dec_blob(d, &n);
    if (p && dst) memcpy(dst, p, n < cap ? n : cap);
}

int vkb_dec_fd(vkb_dec *d)
{
    uint32_t i = vkb_dec_u32(d);
    if (i == VKB_FD_NONE || d->err) return -1;
    if ((int)i >= d->nfds || (d->fd_taken & (1u << i))) {
        d->err = 1;
        return -1;
    }
    d->fd_taken |= 1u << i;
    return d->fds[i];
}

void vkb_dec_close_untaken(vkb_dec *d)
{
    for (int i = 0; i < d->nfds; i++) {
        if (!(d->fd_taken & (1u << i)) && d->fds[i] >= 0) close(d->fds[i]);
    }
    d->fd_taken = 0xffffffffu;
}

const void *vkb_dec_chain(vkb_dec *d)
{
    uint32_t t = vkb_dec_u32(d);
    if (d->err || t == VKB_CHAIN_END) return NULL;
    size_t sz = vkb_struct_size((VkStructureType)t);
    if (!sz) {
        VKB_ERR("protocol: unknown structure type %u in a chain", t);
        d->err = 1;
        return NULL;
    }
    void *p = vkb_dec_alloc(d, sz);
    if (!p) return NULL;
    vkb_dec_chain_elem(d, (VkStructureType)t, p);
    return p;
}

void vkb_dec_out_template(vkb_dec *d, void *dst, size_t size)
{
    vkb_dec_raw_into(d, dst, size);
    VkBaseOutStructure *prev = dst;
    prev->pNext = NULL;
    for (;;) {
        uint32_t t = vkb_dec_u32(d);
        if (d->err || t == VKB_CHAIN_END) break;
        size_t sz = vkb_struct_size((VkStructureType)t);
        if (!sz) {
            d->err = 1;
            break;
        }
        VkBaseOutStructure *el = vkb_dec_alloc(d, sz);
        if (!el) break;
        vkb_dec_raw_into(d, el, sz);
        el->pNext = NULL;
        for (size_t i = 0; i < N_OUT_ARRAYS; i++) {
            const out_arr *oa = &out_arrays[i];
            if (oa->t != (VkStructureType)t) continue;
            void *arr = NULL;
            if (vkb_dec_u8(d)) {
                uint32_t n = rd32(el, oa->count_off);
                arr = vkb_dec_alloc(d, (size_t)(n ? n : 1) * oa->elem);
            } else {
                wr32(el, oa->count_off, 0);
            }
            wrp(el, oa->ptr_off, arr);
        }
        prev->pNext = el;
        prev = el;
    }
}

void vkb_dec_out_struct(vkb_dec *d, void *dst, size_t size)
{
    VkBaseOutStructure *top = dst;
    VkBaseOutStructure *keep = top->pNext;
    vkb_dec_raw_into(d, dst, size);
    top->pNext = keep;
    VkBaseOutStructure *app = (VkBaseOutStructure *)next_known_out(keep);
    for (;;) {
        uint32_t t = vkb_dec_u32(d);
        if (d->err || t == VKB_CHAIN_END) break;
        size_t sz = vkb_struct_size((VkStructureType)t);
        const uint8_t *src = vkb_dec_raw_view(d, sz);
        if (!src || !sz) {
            d->err = 1;
            break;
        }
        if (!app || (uint32_t)app->sType != t) {
            /* The server answered for a structure we did not send: protocol mismatch. */
            d->err = 1;
            break;
        }
        /* The caller's own pointers and array capacities survive the copy. */
        VkBaseOutStructure *keep_next = app->pNext;
        void *app_arr[4] = {0};
        uint32_t cap[4] = {0};
        int na = 0;
        for (size_t i = 0; i < N_OUT_ARRAYS && na < 4; i++) {
            if (out_arrays[i].t != (VkStructureType)t) continue;
            app_arr[na] = rdp(app, out_arrays[i].ptr_off);
            cap[na] = rd32(app, out_arrays[i].count_off);
            na++;
        }
        memcpy(app, src, sz);
        app->pNext = keep_next;
        na = 0;
        for (size_t i = 0; i < N_OUT_ARRAYS && na < 4; i++) {
            const out_arr *oa = &out_arrays[i];
            if (oa->t != (VkStructureType)t) continue;
            wrp(app, oa->ptr_off, app_arr[na]);
            uint8_t present = vkb_dec_u8(d);
            uint32_t n = 0;
            const uint8_t *arr_src = NULL;
            if (present) {
                n = vkb_dec_u32(d);
                arr_src = vkb_dec_raw_view(d, (size_t)n * oa->elem);
            }
            if (oa->empty) {
                wr32(app, oa->count_off, 0);
            } else if (app_arr[na] && arr_src) {
                uint32_t m = n < cap[na] ? n : cap[na];
                memcpy(app_arr[na], arr_src, (size_t)m * oa->elem);
                wr32(app, oa->count_off, m);
            }
            na++;
        }
        app = (VkBaseOutStructure *)next_known_out(keep_next);
    }
}

void vkb_close_fd(int fd)
{
    if (fd >= 0) close(fd);
}

/* ------------------------------------------------------------------ predicates */

int vkb_desc_has_image(VkDescriptorType t)
{
    return t == VK_DESCRIPTOR_TYPE_SAMPLER || t == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER ||
           t == VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE || t == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE ||
           t == VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT || t == VK_DESCRIPTOR_TYPE_SAMPLE_WEIGHT_IMAGE_QCOM ||
           t == VK_DESCRIPTOR_TYPE_BLOCK_MATCH_IMAGE_QCOM;
}

int vkb_desc_has_buffer(VkDescriptorType t)
{
    return t == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER || t == VK_DESCRIPTOR_TYPE_STORAGE_BUFFER ||
           t == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC || t == VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC;
}

int vkb_desc_has_texel(VkDescriptorType t)
{
    return t == VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER || t == VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER;
}

int vkb_gp_has_tess(const VkGraphicsPipelineCreateInfo *s)
{
    if (!s->pStages) return 1; /* library pieces: trust the pointer */
    for (uint32_t i = 0; i < s->stageCount; i++) {
        if (s->pStages[i].stage & (VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT | VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT))
            return 1;
    }
    return 0;
}

/* ------------------------------------------------------------------ transport */

int vkb_send_msg(int sock, const vkb_msg_hdr *h, const void *payload, const int *fds, int nfds)
{
    struct iovec iov[2];
    iov[0].iov_base = (void *)h;
    iov[0].iov_len = sizeof(*h);
    iov[1].iov_base = (void *)payload;
    iov[1].iov_len = h->size;
    union {
        char buf[CMSG_SPACE(sizeof(int) * VKB_MAX_FDS)];
        struct cmsghdr align;
    } cm;
    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = iov;
    msg.msg_iovlen = h->size ? 2 : 1;
    if (nfds > 0) {
        memset(&cm, 0, sizeof(cm));
        msg.msg_control = cm.buf;
        msg.msg_controllen = CMSG_SPACE(sizeof(int) * nfds);
        struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
        c->cmsg_level = SOL_SOCKET;
        c->cmsg_type = SCM_RIGHTS;
        c->cmsg_len = CMSG_LEN(sizeof(int) * nfds);
        memcpy(CMSG_DATA(c), fds, sizeof(int) * nfds);
    }
    size_t total = sizeof(*h) + h->size;
    size_t sent = 0;
    while (sent < total) {
        ssize_t r = sendmsg(sock, &msg, MSG_NOSIGNAL);
        if (r < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        sent += (size_t)r;
        if (sent >= total) break;
        /* Partial write: advance the iovecs; fds went with the first chunk. */
        msg.msg_control = NULL;
        msg.msg_controllen = 0;
        size_t skip = (size_t)r;
        while (msg.msg_iovlen && skip >= msg.msg_iov[0].iov_len) {
            skip -= msg.msg_iov[0].iov_len;
            msg.msg_iov++;
            msg.msg_iovlen--;
        }
        if (msg.msg_iovlen) {
            msg.msg_iov[0].iov_base = (uint8_t *)msg.msg_iov[0].iov_base + skip;
            msg.msg_iov[0].iov_len -= skip;
        }
    }
    return 0;
}

static int read_full(int sock, void *buf, size_t n)
{
    size_t got = 0;
    while (got < n) {
        ssize_t r = recv(sock, (uint8_t *)buf + got, n - got, 0);
        if (r == 0) return -1;
        if (r < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        got += (size_t)r;
    }
    return 0;
}

int vkb_recv_msg(int sock, vkb_msg_hdr *h, uint8_t **payload, size_t *cap, int *fds, int *nfds)
{
    union {
        char buf[CMSG_SPACE(sizeof(int) * VKB_MAX_FDS)];
        struct cmsghdr align;
    } cm;
    struct iovec iov = {h, sizeof(*h)};
    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cm.buf;
    msg.msg_controllen = sizeof(cm.buf);
    *nfds = 0;
    ssize_t r;
    do {
        r = recvmsg(sock, &msg, MSG_CMSG_CLOEXEC);
    } while (r < 0 && errno == EINTR);
    if (r <= 0) return -1;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS) {
            int n = (int)((c->cmsg_len - CMSG_LEN(0)) / sizeof(int));
            for (int i = 0; i < n; i++) {
                int fd;
                memcpy(&fd, CMSG_DATA(c) + i * sizeof(int), sizeof(int));
                if (*nfds < VKB_MAX_FDS) fds[(*nfds)++] = fd;
                else close(fd);
            }
        }
    }
    if ((size_t)r < sizeof(*h) && read_full(sock, (uint8_t *)h + r, sizeof(*h) - (size_t)r) < 0) goto fail;
    if (h->magic != VKB_MAGIC) {
        VKB_ERR("transport: bad message magic 0x%08x", h->magic);
        goto fail;
    }
    if (h->size) {
        if (h->size > *cap) {
            size_t nc = *cap ? *cap : 65536;
            while (nc < h->size) nc *= 2;
            uint8_t *nb = realloc(*payload, nc);
            if (!nb) goto fail;
            *payload = nb;
            *cap = nc;
        }
        if (read_full(sock, *payload, h->size) < 0) goto fail;
    }
    return 0;
fail:
    for (int i = 0; i < *nfds; i++) close(fds[i]);
    *nfds = 0;
    return -1;
}
