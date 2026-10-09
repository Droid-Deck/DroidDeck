/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * DroidDeck Vulkan bridge - client ICD (libvulkan_droidbridge.so, glibc).
 *
 * A Vulkan driver for the Vulkan loader inside the Linux session. It owns no GPU: every call is
 * encoded and executed by the server (app/src/main/cpp/vkbridge) on the device's real driver.
 * Command buffers are recorded locally and shipped at vkEndCommandBuffer; mapped memory is
 * shared pages (see server_memory.c); presentation (WSI) is implemented here.
 */
#ifndef VKB_CLIENT_H
#define VKB_CLIENT_H

#define VK_USE_PLATFORM_WAYLAND_KHR 1
#define VK_USE_PLATFORM_XCB_KHR 1
#define VK_USE_PLATFORM_XLIB_KHR 1

#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>

#include "vkb_wire.h"
#include "vkb_proto.h"
#include "vkb_log.h"
#include <vulkan/vulkan.h>
#include <vulkan/vk_icd.h>

#define VKB_EXPORT __attribute__((visibility("default")))
#define VKB_API_VERSION VK_MAKE_API_VERSION(0, 1, 3, VK_HEADER_VERSION)

/* ------------------------------------------------------------------ small hash map (u64 -> ptr) */
typedef struct vkb_map {
    uint64_t *keys;
    void **vals;
    uint32_t cap, count;
    pthread_mutex_t lock;
} vkb_map;
void vkb_map_init(vkb_map *m);
void vkb_map_destroy(vkb_map *m);
void vkb_map_put(vkb_map *m, uint64_t k, void *v);
void *vkb_map_get(vkb_map *m, uint64_t k);
void *vkb_map_remove(vkb_map *m, uint64_t k);

/* ------------------------------------------------------------------ dispatchable objects */
typedef struct vkb_object {
    VK_LOADER_DATA loader;     /* must be first: the loader keeps its dispatch pointer here */
    VkObjectType type;
    uint64_t remote;           /* the server's handle */
    uint32_t table;            /* the server's dispatch table for calls on this object */
} vkb_object;

typedef struct vkb_mem_type_map {
    uint32_t server_type;
    uint8_t shared;
} vkb_mem_type_map;

struct vkb_instance;

typedef struct vkb_physdev {
    vkb_object obj;
    struct vkb_instance *instance;
    vkb_server_pd_info info;
    VkPhysicalDeviceProperties props;
    VkPhysicalDeviceMemoryProperties server_mem;
    VkPhysicalDeviceMemoryProperties client_mem;   /* what the app sees */
    vkb_mem_type_map type_map[VK_MAX_MEMORY_TYPES];
    uint32_t heap_map[VK_MAX_MEMORY_HEAPS];         /* client heap -> server heap */
    VkExtensionProperties *exts;                    /* filtered device extensions */
    uint32_t next;
    int ready;
    pthread_mutex_t lock;
    struct vkb_physdev *list_next;
} vkb_physdev;

typedef struct vkb_instance {
    vkb_object obj;
    uint32_t api_version;
    vkb_physdev *pds;
    pthread_mutex_t lock;
    uint8_t ext_surface, ext_wayland, ext_xcb, ext_xlib, ext_headless, ext_surface_caps2, ext_debug_utils;
} vkb_instance;

struct vkb_queue;

typedef struct vkb_device {
    vkb_object obj;
    vkb_physdev *pd;
    vkb_instance *instance;
    struct vkb_queue *queues;
    pthread_mutex_t lock;
    vkb_map memories;        /* VkDeviceMemory -> vkb_memory* */
    vkb_map templates;       /* VkDescriptorUpdateTemplate -> vkb_template* */
    vkb_map pools;           /* VkCommandPool -> vkb_cmdpool* */
    vkb_map unshared;        /* VkBuffer/VkImage that cannot live in shared memory -> (void*)1 */
    vkb_map shared_images;   /* VkImage that may live in shared memory -> (void*)1 */
    const char **enabled_exts;
    uint32_t enabled_ext_count;
    uint8_t ext_swapchain;
} vkb_device;

typedef struct vkb_queue {
    vkb_object obj;
    vkb_device *device;
    uint32_t family, index, flags;
    struct vkb_queue *next;
} vkb_queue;

typedef struct vkb_cmdbuf {
    vkb_object obj;
    vkb_device *device;
    VkCommandPool pool;
    VkCommandBufferLevel level;
    vkb_enc stream;
    size_t entry;            /* offset of the open entry's payload */
    int recording;
    int broken;
    struct vkb_cmdbuf *pool_next, *pool_prev;
} vkb_cmdbuf;

typedef struct vkb_cmdpool {
    vkb_cmdbuf *head;
} vkb_cmdpool;

typedef struct vkb_memory {
    void *ptr;               /* client mapping (shared allocations) */
    VkDeviceSize size;
    uint32_t client_type;
    int shared;
} vkb_memory;

static inline vkb_device *vkb_dev(VkDevice d) { return (vkb_device *)d; }
static inline vkb_physdev *vkb_pd(VkPhysicalDevice p) { return (vkb_physdev *)p; }
static inline vkb_instance *vkb_inst(VkInstance i) { return (vkb_instance *)i; }
static inline vkb_cmdbuf *vkb_cb(VkCommandBuffer c) { return (vkb_cmdbuf *)c; }

/* ------------------------------------------------------------------ calls */
typedef struct vkb_call {
    vkb_enc e;
    vkb_dec d;
    uint32_t cmd, table, flags;
    struct vkb_conn *conn;
} vkb_call;

void vkb_call_begin(vkb_call *c, uint32_t cmd, uint32_t table);
/* Sends the request and waits for the reply. 0 = failed (the call is already released). */
int vkb_call_exec(vkb_call *c);
void vkb_call_end(vkb_call *c);
uint32_t vkb_table_of(const void *dispatchable);
int vkb_connected(void);
/* Per-thread transport cleanup for fork children. */
void vkb_conn_after_fork(void);

/* Command streams (cmdbuf.c). */
vkb_enc *vkb_cmd_record_begin(VkCommandBuffer cb, uint32_t cmd);
void vkb_cmd_record_end(VkCommandBuffer cb);

/* Dispatchable objects created by the server (generated stubs call this). */
void *vkb_wrap(VkObjectType type, const void *parent, uint64_t remote);

/* physdev.c */
int vkb_physdev_ready(vkb_physdev *pd);
uint32_t vkb_mem_bits_to_client(vkb_physdev *pd, uint32_t server_bits, int allow_shared);

/* memory.c */
void vkb_memory_init_device(vkb_device *d);

/* icd.c */
PFN_vkVoidFunction vkb_lookup_proc(const char *name, int level_max, vkb_device *dev, vkb_instance *inst);
int vkb_device_ext_enabled(const vkb_device *d, const char *name);

/* memory.c */
void *vkb_chain_clone(const void *pNext, void **mem);
int vkb_is_client_device_ext(const char *n);

/* misc */
int vkb_memfd(const char *name, size_t size);

#endif
