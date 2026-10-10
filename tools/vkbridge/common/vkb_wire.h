/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Wire primitives shared by the bridge's client ICD (glibc) and server (bionic).
 * See tools/vkbridge/README.md for the protocol.
 */
#ifndef VKB_WIRE_H
#define VKB_WIRE_H

#include <stddef.h>
#include <stdint.h>
#include "gen/vkb_gen.h"

#define VKB_MAX_FDS 16
#define VKB_CHAIN_END 0x7FFFFFFFu
#define VKB_FD_NONE 0xFFFFFFFFu

/* ---- arena: per-call scratch memory on the decoding side ---- */
typedef struct vkb_arena_block vkb_arena_block;
typedef struct vkb_arena {
    vkb_arena_block *head;
    size_t used_total;
} vkb_arena;
void *vkb_arena_alloc(vkb_arena *a, size_t n);
void vkb_arena_reset(vkb_arena *a);
void vkb_arena_free(vkb_arena *a);

/* ---- encoder ---- */
struct vkb_enc {
    uint8_t *buf;
    size_t len, cap;
    int fds[VKB_MAX_FDS];
    int nfds;
    int oom;
};
void vkb_enc_init(vkb_enc *e);
void vkb_enc_reset(vkb_enc *e);   /* keeps the buffer */
void vkb_enc_free(vkb_enc *e);
void vkb_enc_bytes(vkb_enc *e, const void *p, size_t n);
void vkb_enc_u8(vkb_enc *e, uint8_t v);
void vkb_enc_u32(vkb_enc *e, uint32_t v);
void vkb_enc_u64(vkb_enc *e, uint64_t v);
size_t vkb_enc_raw(vkb_enc *e, const void *p, size_t n);          /* 8-aligned; returns offset */
void vkb_enc_patch_u64(vkb_enc *e, size_t off, uint64_t v);
void vkb_enc_str(vkb_enc *e, const char *s);
void vkb_enc_strarray(vkb_enc *e, const char *const *a, uint32_t n);
void vkb_enc_blob(vkb_enc *e, const void *p, size_t n);
void vkb_enc_fd(vkb_enc *e, int fd);                                 /* sent with the message */
void vkb_enc_chain(vkb_enc *e, const void *pNext);
void vkb_enc_out_template(vkb_enc *e, const void *s, size_t size);   /* client */
void vkb_enc_out_struct(vkb_enc *e, const void *s, size_t size);     /* server */
void vkb_enc_append(vkb_enc *e, const vkb_enc *src);                 /* bytes + fds */

/* ---- decoder ---- */
struct vkb_dec {
    const uint8_t *buf;
    size_t pos, len;
    int err;
    vkb_arena *arena;
    int *fds;
    int nfds;
    uint32_t fd_taken;   /* bitmask of fds handed out */
};
void vkb_dec_init(vkb_dec *d, const void *buf, size_t len, vkb_arena *a, int *fds, int nfds);
void vkb_dec_bytes(vkb_dec *d, void *dst, size_t n);
uint8_t vkb_dec_u8(vkb_dec *d);
uint32_t vkb_dec_u32(vkb_dec *d);
uint64_t vkb_dec_u64(vkb_dec *d);
void vkb_dec_raw_into(vkb_dec *d, void *dst, size_t n);
const void *vkb_dec_raw_view(vkb_dec *d, size_t n);
void *vkb_dec_alloc(vkb_dec *d, size_t n);       /* zeroed */
void *vkb_dec_alloc_zero(vkb_dec *d, size_t n);
const char *vkb_dec_str(vkb_dec *d);
const char *const *vkb_dec_strarray(vkb_dec *d);
const void *vkb_dec_blob(vkb_dec *d, size_t *n);
void vkb_dec_blob_into(vkb_dec *d, void *dst, size_t cap);
int vkb_dec_fd(vkb_dec *d);                      /* caller owns the fd */
const void *vkb_dec_chain(vkb_dec *d);
void vkb_dec_out_template(vkb_dec *d, void *dst, size_t size);    /* server */
void vkb_dec_out_struct(vkb_dec *d, void *dst, size_t size);      /* client */
void vkb_dec_close_untaken(vkb_dec *d);

void vkb_close_fd(int fd);

/* Descriptor-type predicates used by generated encoders. */
int vkb_desc_has_image(VkDescriptorType t);
int vkb_desc_has_buffer(VkDescriptorType t);
int vkb_desc_has_texel(VkDescriptorType t);
int vkb_gp_has_tess(const VkGraphicsPipelineCreateInfo *s);
/* Defined per side: on the client it consults the command buffer's level. */
int vkb_cb_begin_is_secondary(const VkCommandBufferBeginInfo *s);

/* Defined per side: client maps a dispatchable handle to the server's value; server: identity. */
uint64_t vkb_remote(const void *dispatchable);

/* ---- transport ---- */
typedef struct vkb_msg_hdr {
    uint32_t magic;     /* VKB_MAGIC */
    uint32_t size;      /* payload bytes after the header */
    uint32_t cmd;       /* request: command id; reply: status (0 = ok) */
    uint32_t table;     /* request: server dispatch table id */
    uint32_t nfds;
    uint32_t flags;
    /* Ordering of asynchronous requests across a process's connections: an async request has
     * seq > 0; every request carries barrier = the highest async seq its process had issued
     * before it, and is not executed until all of those have been. */
    uint64_t seq;
    uint64_t barrier;
} vkb_msg_hdr;
#define VKB_MAGIC 0x32424b56u   /* "VKB2" */
#define VKB_F_NOREPLY 1u

/* Reply status codes (vkb_msg_hdr.cmd in replies). */
#define VKB_ST_OK 0u
#define VKB_ST_BAD_MESSAGE 1u
#define VKB_ST_MISSING 2u
#define VKB_ST_BAD_TABLE 3u

int vkb_send_msg(int sock, const vkb_msg_hdr *h, const void *payload, const int *fds, int nfds);
/* Receives one message; *payload is malloc'd (or reused when *cap suffices). Returns 0 on
 * success, -1 on EOF/error. fds[] receives up to VKB_MAX_FDS descriptors. */
int vkb_recv_msg(int sock, vkb_msg_hdr *h, uint8_t **payload, size_t *cap, int *fds, int *nfds);

#endif
