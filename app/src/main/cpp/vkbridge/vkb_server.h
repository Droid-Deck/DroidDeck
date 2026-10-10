/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * DroidDeck Vulkan bridge - server side (bionic on the device, glibc for host tests).
 *
 * The server owns the real Vulkan objects. Linux programs in the session talk to it through the
 * client ICD (tools/vkbridge/client) over a Unix socket; every connection is one client thread.
 */
#ifndef VKB_SERVER_H
#define VKB_SERVER_H

#include <pthread.h>
#include <stdint.h>

#include "vkb_server_iface.h"
#include "vkb_proto.h"
#include "vkb_log.h"
#include "gen/vkb_server_gen.h"

#define VKB_MAX_TABLES 65536
#define VKB_MAX_PDS 16

/* A client process (all connections that said hello with the same token). */
#define VKB_SEQ_RING 65536

typedef struct vkb_srv_proc {
    uint64_t token;
    int pid;
    int conns;
    struct vkb_srv_proc *next;
    /* Ordering of the process's asynchronous requests (vkb_msg_hdr.seq/barrier). */
    pthread_mutex_t olock;
    pthread_cond_t ocond;
    uint64_t watermark;            /* every async seq <= this has run */
    uint8_t done[VKB_SEQ_RING];    /* seqs above the watermark that have run */
    int conn_closed;               /* a connection ended (a seq may never arrive) */
} vkb_srv_proc;

typedef struct vkb_srv_conn {
    int sock;
    vkb_srv_proc *proc;
    /* Receive buffer: messages are parsed out of it in place (srv_recv). */
    uint8_t *rb;
    size_t rcap, rpos, rlen;
    int fdq[2 * VKB_MAX_FDS]; /* descriptors received ahead of their message's turn */
    int fdn;
    vkb_arena arena;
    pthread_t thread;
} vkb_srv_conn;

/* Startup knowledge about each physical device (keyed by deviceUUID). */
typedef struct vkb_pd_knowledge {
    uint8_t uuid[VK_UUID_SIZE];
    char name[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE];
    vkb_server_pd_info info;
    int valid;
} vkb_pd_knowledge;

/* A tracked device memory allocation. */
typedef struct vkb_srv_mem {
    VkDeviceMemory mem;
    VkDeviceSize size;
    uint32_t type;
    uint32_t strategy;
    void *host_ptr;          /* hostptr: server's mapping of the client's memfd */
    size_t host_len;
    void *mapped;            /* server-side vkMapMemory pointer (for flush/invalidate) */
    void *ahb;               /* AHardwareBuffer* (Android) */
    struct vkb_srv_mem *next;
} vkb_srv_mem;

struct vkb_emu_device;

typedef struct vkb_srv_table {
    uint32_t id;
    int is_device;
    vkb_srv_proc *proc;
    VkInstance instance;
    VkDevice device;
    VkPhysicalDevice physical;               /* device tables */
    struct vkb_srv_table *parent;            /* device -> instance */
    vkb_dispatch dt;                         /* what handlers call (may hold emulation wrappers) */
    vkb_dispatch real;                       /* the driver's own entry points */
    uint32_t api_version;
    const vkb_pd_knowledge *pd;
    pthread_mutex_t lock;
    vkb_srv_mem *mems[256];                  /* hash by handle */
    struct vkb_emu_device *emu;
    struct vkb_pcache *pcache;               /* server_pcache.c */
} vkb_srv_table;

/* server_main.c */
extern PFN_vkGetInstanceProcAddr vkb_gipa;
extern vkb_dispatch vkb_global_dt;
extern int vkb_verbose;

/* server_pcache.c */
void vkb_pcache_set_dir(const char *dir);
void vkb_pcache_device_init(vkb_srv_table *t);
void vkb_pcache_device_destroy(vkb_srv_table *t);

/* server_conn.c */
void vkb_srv_serve(int listen_fd);
vkb_srv_table *vkb_table_get(uint32_t id);
uint32_t vkb_table_register(vkb_srv_table *t);
void vkb_table_unregister(vkb_srv_table *t);
void vkb_srv_reply_status(vkb_srv_call *c, uint32_t status);
/* The table of the call being executed on this thread (emulation wrappers use it). */
vkb_srv_table *vkb_srv_current_table(void);
void vkb_srv_set_current_table(vkb_srv_table *t);
void vkb_proc_cleanup(vkb_srv_proc *p);

/* server_instance.c */
void vkb_selftest_all(void);
const vkb_pd_knowledge *vkb_pd_lookup(const vkb_dispatch *dt, VkPhysicalDevice pd);
extern vkb_pd_knowledge vkb_pds[VKB_MAX_PDS];
extern int vkb_npds;

/* server_memory.c */
void vkb_mem_selftest(vkb_pd_knowledge *k, VkInstance inst, const vkb_dispatch *idt, VkPhysicalDevice pd);
const char *const *vkb_mem_required_extensions(uint32_t strategy, uint32_t *count);
void vkb_mem_track(vkb_srv_table *dev, vkb_srv_mem *m);
vkb_srv_mem *vkb_mem_find(vkb_srv_table *dev, VkDeviceMemory mem, int remove);
void vkb_mem_release(vkb_srv_table *dev, vkb_srv_mem *m);
void vkb_mem_free_all(vkb_srv_table *dev);

/* emulation (server_emu*.c) */
uint32_t vkb_emu_detect(const vkb_dispatch *idt, VkPhysicalDevice pd, uint32_t *missing);
void vkb_emu_device_create_info(vkb_srv_table *inst, VkPhysicalDevice pd, uint32_t emu,
                                VkDeviceCreateInfo *ci, vkb_arena *a);
void vkb_emu_device_init(vkb_srv_table *dev, uint32_t emu);
void vkb_emu_device_destroy(vkb_srv_table *dev);

#endif
