/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Host-visible memory shared with the client.
 *
 * A Linux program maps Vulkan memory and writes to it directly; those writes must land in the
 * pages the GPU reads. The server makes such memory shareable in one of three ways, chosen per
 * GPU by a self-test at startup that checks the GPU and both CPU mappings really see the same
 * bytes:
 *   hostptr  the client creates a memfd, the server maps it and imports it
 *            (VK_EXT_external_memory_host);
 *   dmabuf   the server allocates exportable memory and hands the client its dma-buf
 *            (VK_EXT_external_memory_dma_buf), which the client maps;
 *   ahb      the server allocates a BLOB AHardwareBuffer, imports it, and hands the client the
 *            dma-buf behind it (Android only);
 *   dmaheap  the server allocates from /dev/dma_heap (system-uncached, else system), imports that
 *            dma-buf into the driver and hands it to the client - what GameNative's wrapper does on
 *            Mali when the driver will not export one (Android; root-only on desktops).
 * VKBRIDGE_MEMORY=hostptr|dmabuf|dmaheap|ahb forces one.
 */
#define _GNU_SOURCE
#ifdef __ANDROID__
#define VK_USE_PLATFORM_ANDROID_KHR 1
#endif
#include "vkb_server.h"

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <linux/dma-heap.h>
#include <unistd.h>

#ifdef __ANDROID__
#include <vulkan/vulkan_android.h>
/* libnativewindow, opened at run time (getNativeHandle is not in the NDK headers). */
typedef struct {
    uint32_t width, height, layers, format;
    uint64_t usage;
    uint32_t stride, rfu0;
    uint64_t rfu1;
} vkb_ahb_desc;
typedef struct {
    int version, numFds, numInts;
    int data[0];
} vkb_native_handle;
static int (*p_AHardwareBuffer_allocate)(const vkb_ahb_desc *, struct AHardwareBuffer **);
static void (*p_AHardwareBuffer_release)(struct AHardwareBuffer *);
static const vkb_native_handle *(*p_AHardwareBuffer_getNativeHandle)(const struct AHardwareBuffer *);
#define AHB_FORMAT_BLOB 0x21
#define AHB_USAGE_CPU_READ_OFTEN 3ull
#define AHB_USAGE_CPU_WRITE_OFTEN (3ull << 4)
#define AHB_USAGE_GPU_DATA_BUFFER (1ull << 24)

static int ahb_load(void)
{
    static int state;
    if (state) return state > 0;
    void *h = dlopen("libnativewindow.so", RTLD_NOW);
    if (!h) h = dlopen("libandroid.so", RTLD_NOW);
    if (h) {
        p_AHardwareBuffer_allocate = dlsym(h, "AHardwareBuffer_allocate");
        p_AHardwareBuffer_release = dlsym(h, "AHardwareBuffer_release");
        p_AHardwareBuffer_getNativeHandle = dlsym(h, "AHardwareBuffer_getNativeHandle");
    }
    state = (p_AHardwareBuffer_allocate && p_AHardwareBuffer_release && p_AHardwareBuffer_getNativeHandle) ? 1 : -1;
    if (state < 0) VKB_WARN("AHardwareBuffer functions unavailable");
    return state > 0;
}
#endif

static const char *const ext_hostptr[] = {"VK_KHR_external_memory", "VK_EXT_external_memory_host"};
static const char *const ext_dmabuf[] = {"VK_KHR_external_memory", "VK_KHR_external_memory_fd", "VK_EXT_external_memory_dma_buf"};
#ifdef __ANDROID__
static const char *const ext_ahb[] = {"VK_KHR_external_memory", "VK_ANDROID_external_memory_android_hardware_buffer",
                                      "VK_EXT_queue_family_foreign", "VK_KHR_sampler_ycbcr_conversion",
                                      "VK_KHR_dedicated_allocation", "VK_KHR_get_memory_requirements2"};
#endif

const char *const *vkb_mem_required_extensions(uint32_t strategy, uint32_t *count)
{
    switch (strategy) {
    case VKB_MEM_HOSTPTR: *count = 2; return ext_hostptr;
    case VKB_MEM_DMABUF: *count = 3; return ext_dmabuf;
    case VKB_MEM_DMAHEAP: *count = 3; return ext_dmabuf;
#ifdef __ANDROID__
    case VKB_MEM_AHB: *count = 6; return ext_ahb;
#endif
    default: *count = 0; return NULL;
    }
}

static uint32_t strategy_handle_type(uint32_t s)
{
    switch (s) {
    case VKB_MEM_HOSTPTR: return VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT;
    case VKB_MEM_DMABUF: return VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
    case VKB_MEM_DMAHEAP: return VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
#ifdef __ANDROID__
    case VKB_MEM_AHB: return VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
#endif
    default: return 0;
    }
}

int vkb_memfd(const char *name, size_t size)
{
    int fd = (int)syscall(SYS_memfd_create, name, 1u /* MFD_CLOEXEC */);
    if (fd < 0) return -1;
    if (ftruncate(fd, (off_t)size) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

/* ------------------------------------------------------------------ tracking */

static unsigned mem_hash(VkDeviceMemory m)
{
    uint64_t v = (uint64_t)(uintptr_t)m;
    v ^= v >> 17;
    v *= 0x9E3779B97F4A7C15ull;
    return (unsigned)(v >> 56);
}

void vkb_mem_track(vkb_srv_table *dev, vkb_srv_mem *m)
{
    pthread_mutex_lock(&dev->lock);
    unsigned h = mem_hash(m->mem);
    m->next = dev->mems[h];
    dev->mems[h] = m;
    pthread_mutex_unlock(&dev->lock);
}

vkb_srv_mem *vkb_mem_find(vkb_srv_table *dev, VkDeviceMemory mem, int remove)
{
    pthread_mutex_lock(&dev->lock);
    vkb_srv_mem **pp = &dev->mems[mem_hash(mem)];
    while (*pp && (*pp)->mem != mem) pp = &(*pp)->next;
    vkb_srv_mem *m = *pp;
    if (m && remove) *pp = m->next;
    pthread_mutex_unlock(&dev->lock);
    return m;
}

/* Frees the server-side resources of an allocation (the VkDeviceMemory must already be freed). */
static void mem_release_backing(vkb_srv_mem *m)
{
    if (m->host_ptr) munmap(m->host_ptr, m->host_len);
#ifdef __ANDROID__
    if (m->ahb) p_AHardwareBuffer_release(m->ahb);
#endif
    free(m);
}

void vkb_mem_release(vkb_srv_table *dev, vkb_srv_mem *m)
{
    if (m->mapped) dev->real.vkUnmapMemory(dev->device, m->mem);
    dev->real.vkFreeMemory(dev->device, m->mem, NULL);
    mem_release_backing(m);
}

void vkb_mem_free_all(vkb_srv_table *dev)
{
    for (int i = 0; i < 256; i++) {
        vkb_srv_mem *m = dev->mems[i];
        dev->mems[i] = NULL;
        while (m) {
            vkb_srv_mem *n = m->next;
            vkb_mem_release(dev, m);
            m = n;
        }
    }
}

/* The dma-heap the dmaheap strategy allocates from, opened once: system-uncached first (the GPU
 * and the client both write through it without cache maintenance), else the cached system heap. */
static int dmaheap_fd(void)
{
    static int fd = -2;
    static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
    pthread_mutex_lock(&lock);
    if (fd == -2) {
        const char *names[] = {"/dev/dma_heap/system-uncached", "/dev/dma_heap/system"};
        if (getenv("VKBRIDGE_DMAHEAP_CACHED")) names[0] = "/dev/dma_heap/system";
        fd = -1;
        for (int i = 0; i < 2 && fd < 0; i++) {
            fd = open(names[i], O_RDONLY | O_CLOEXEC);
            if (fd >= 0) VKB_INFO("dmaheap: allocating from %s", names[i]);
            else VKB_INFO("dmaheap: %s: %s", names[i], strerror(errno));
        }
    }
    int r = fd;
    pthread_mutex_unlock(&lock);
    return r;
}

/* Unlinks every structure of type t from the chain starting at *head. */
static void chain_drop(const void **head, VkStructureType t)
{
    const VkBaseInStructure **pp = (const VkBaseInStructure **)head;
    while (*pp) {
        if ((*pp)->sType == t) *pp = (*pp)->pNext;
        else pp = (const VkBaseInStructure **)&((VkBaseInStructure *)*pp)->pNext;
    }
}

/* The driver's vkGetDeviceProcAddr (for entry points the dispatch table does not carry). */
PFN_vkGetDeviceProcAddr vkb_mem_gdpa;

/* ------------------------------------------------------------------ allocation */

/*
 * Allocates shared memory with strategy `s`. `ai` is the app's (or the self-test's) allocate
 * info; for hostptr, `client_fd` is the client's memfd (consumed). On success *out_fd is the fd
 * to send to the client (-1 for hostptr, where the client already has its pages).
 */
static VkResult alloc_shared(const vkb_dispatch *dt, VkDevice dev, uint32_t s, const VkMemoryAllocateInfo *ai,
                             int client_fd, vkb_srv_mem **out, int *out_fd)
{
    *out_fd = -1;
    *out = NULL;
    vkb_srv_mem *m = calloc(1, sizeof(*m));
    if (!m) {
        if (client_fd >= 0) close(client_fd);
        return VK_ERROR_OUT_OF_HOST_MEMORY;
    }
    m->size = ai->allocationSize;
    m->type = ai->memoryTypeIndex;
    m->strategy = s;
    VkMemoryAllocateInfo info = *ai;
    VkResult r = VK_ERROR_FEATURE_NOT_PRESENT;
    switch (s) {
    case VKB_MEM_HOSTPTR: {
        if (client_fd < 0) break;
        void *p = mmap(NULL, (size_t)ai->allocationSize, PROT_READ | PROT_WRITE, MAP_SHARED, client_fd, 0);
        close(client_fd);
        client_fd = -1;
        if (p == MAP_FAILED) {
            VKB_ERR("hostptr: mmap of the client's memory failed: %s", strerror(errno));
            r = VK_ERROR_OUT_OF_HOST_MEMORY;
            break;
        }
        m->host_ptr = p;
        m->host_len = (size_t)ai->allocationSize;
        VkMemoryHostPointerPropertiesEXT hp = {VK_STRUCTURE_TYPE_MEMORY_HOST_POINTER_PROPERTIES_EXT};
        if (!dt->vkGetMemoryHostPointerPropertiesEXT ||
            dt->vkGetMemoryHostPointerPropertiesEXT(dev, VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT, p, &hp) != VK_SUCCESS ||
            !(hp.memoryTypeBits & (1u << ai->memoryTypeIndex))) {
            r = VK_ERROR_INVALID_EXTERNAL_HANDLE;
            break;
        }
        VkImportMemoryHostPointerInfoEXT imp = {VK_STRUCTURE_TYPE_IMPORT_MEMORY_HOST_POINTER_INFO_EXT, info.pNext,
                                                VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT, p};
        info.pNext = &imp;
        r = dt->vkAllocateMemory(dev, &info, NULL, &m->mem);
        break;
    }
    case VKB_MEM_DMABUF: {
        VkExportMemoryAllocateInfo exp = {VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO, info.pNext,
                                          VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
        /* An export the app asked for itself is merged rather than duplicated. */
        for (VkBaseOutStructure *b = (VkBaseOutStructure *)info.pNext; b; b = b->pNext) {
            if (b->sType == VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO) {
                ((VkExportMemoryAllocateInfo *)b)->handleTypes |= VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
                exp.sType = VK_STRUCTURE_TYPE_MAX_ENUM;
            }
        }
        if (exp.sType != VK_STRUCTURE_TYPE_MAX_ENUM) info.pNext = &exp;
        r = dt->vkAllocateMemory(dev, &info, NULL, &m->mem);
        if (r != VK_SUCCESS) {
            VKB_INFO("dmabuf: exportable allocation of %llu bytes (type %u) failed: %d", (unsigned long long)info.allocationSize,
                    info.memoryTypeIndex, r);
            break;
        }
        VkMemoryGetFdInfoKHR gi = {VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR, NULL, m->mem, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
        int fd = -1;
        VkResult fr = dt->vkGetMemoryFdKHR ? dt->vkGetMemoryFdKHR(dev, &gi, &fd) : VK_ERROR_EXTENSION_NOT_PRESENT;
        if (fr != VK_SUCCESS || fd < 0) {
            VKB_INFO("dmabuf: vkGetMemoryFdKHR %s: %d (fd %d)", dt->vkGetMemoryFdKHR ? "failed" : "not loaded", fr, fd);
            dt->vkFreeMemory(dev, m->mem, NULL);
            m->mem = VK_NULL_HANDLE;
            r = VK_ERROR_INVALID_EXTERNAL_HANDLE;
            break;
        }
        *out_fd = fd;
        break;
    }
    case VKB_MEM_DMAHEAP: {
        int hfd = dmaheap_fd();
        if (hfd < 0) break;
        uint64_t len = (ai->allocationSize + 4095) & ~4095ull;
        struct dma_heap_allocation_data hd = {len, 0, O_RDWR | O_CLOEXEC, 0};
        if (ioctl(hfd, DMA_HEAP_IOCTL_ALLOC, &hd) < 0) {
            VKB_INFO("dmaheap: allocating %llu bytes failed: %s", (unsigned long long)len, strerror(errno));
            r = VK_ERROR_OUT_OF_DEVICE_MEMORY;
            break;
        }
        int fd = (int)hd.fd;
        VkMemoryFdPropertiesKHR fp = {VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR};
        VkResult pr = dt->vkGetMemoryFdPropertiesKHR
                          ? dt->vkGetMemoryFdPropertiesKHR(dev, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, fd, &fp)
                          : VK_ERROR_EXTENSION_NOT_PRESENT;
        if (pr != VK_SUCCESS || !(fp.memoryTypeBits & (1u << ai->memoryTypeIndex))) {
            VKB_INFO("dmaheap: fd properties %d, memory types 0x%x (want type %u)", pr, fp.memoryTypeBits, ai->memoryTypeIndex);
            close(fd);
            r = VK_ERROR_INVALID_EXTERNAL_HANDLE;
            break;
        }
        /* The import takes its own fd; ours goes to the client. A dedicated-allocation struct
         * with an imported dma-buf crashes some Mali drivers (GameNative mesa PR #8): dropped. */
        int imp_fd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
        VkImportMemoryFdInfoKHR imp = {VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR, info.pNext,
                                       VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, imp_fd};
        chain_drop(&imp.pNext, VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO);
        chain_drop(&imp.pNext, VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO);
        info.pNext = &imp;
        info.allocationSize = len;
        r = imp_fd >= 0 ? dt->vkAllocateMemory(dev, &info, NULL, &m->mem) : VK_ERROR_TOO_MANY_OBJECTS;
        if (r != VK_SUCCESS) {
            VKB_INFO("dmaheap: import of %llu bytes failed: %d", (unsigned long long)len, r);
            if (imp_fd >= 0) close(imp_fd); /* a failed import leaves the fd with us */
            close(fd);
            break;
        }
        *out_fd = fd;
        break;
    }
#ifdef __ANDROID__
    case VKB_MEM_AHB: {
        if (!ahb_load()) break;
        /* Through the device's own vkGetDeviceProcAddr: Android's loader answers NULL for it from
         * vkGetInstanceProcAddr(NULL, ...), which desktop loaders allow (the first device run). */
        PFN_vkGetAndroidHardwareBufferPropertiesANDROID get_props = NULL;
        if (vkb_mem_gdpa)
            get_props = (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)vkb_mem_gdpa(dev, "vkGetAndroidHardwareBufferPropertiesANDROID");
        if (!get_props) {
            VKB_INFO("ahb: vkGetAndroidHardwareBufferPropertiesANDROID not found");
            break;
        }
        uint64_t sz = ai->allocationSize;
        if (sz > 0xFFFFFFFFull) {
            r = VK_ERROR_OUT_OF_DEVICE_MEMORY;
            break;
        }
        /* CPU usage decides how gralloc caches the pages; VKBRIDGE_AHB_RARELY=1 asks for uncached. */
        uint64_t cpu = getenv("VKBRIDGE_AHB_RARELY") ? (2ull | (2ull << 4)) : (AHB_USAGE_CPU_READ_OFTEN | AHB_USAGE_CPU_WRITE_OFTEN);
        vkb_ahb_desc d = {(uint32_t)sz, 1, 1, AHB_FORMAT_BLOB, cpu | AHB_USAGE_GPU_DATA_BUFFER, 0, 0, 0};
        struct AHardwareBuffer *ahb = NULL;
        int ar = p_AHardwareBuffer_allocate(&d, &ahb);
        if (ar != 0 || !ahb) {
            VKB_INFO("ahb: AHardwareBuffer_allocate(%u bytes) failed: %d", (unsigned)sz, ar);
            r = VK_ERROR_OUT_OF_DEVICE_MEMORY;
            break;
        }
        m->ahb = ahb;
        VkAndroidHardwareBufferPropertiesANDROID props = {VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
        VkResult pr = get_props(dev, ahb, &props);
        if (pr != VK_SUCCESS || !(props.memoryTypeBits & (1u << ai->memoryTypeIndex))) {
            VKB_INFO("ahb: properties %d, memory types 0x%x (want type %u)", pr, props.memoryTypeBits, ai->memoryTypeIndex);
            r = VK_ERROR_INVALID_EXTERNAL_HANDLE;
            break;
        }
        VkImportAndroidHardwareBufferInfoANDROID imp = {VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID, info.pNext, ahb};
        info.pNext = &imp;
        info.allocationSize = props.allocationSize;
        r = dt->vkAllocateMemory(dev, &info, NULL, &m->mem);
        if (r != VK_SUCCESS) {
            VKB_INFO("ahb: import of %llu bytes failed: %d", (unsigned long long)info.allocationSize, r);
            break;
        }
        const vkb_native_handle *nh = p_AHardwareBuffer_getNativeHandle(ahb);
        if (!nh || nh->numFds < 1) {
            VKB_INFO("ahb: no fd in the native handle (%d)", nh ? nh->numFds : -1);
            dt->vkFreeMemory(dev, m->mem, NULL);
            m->mem = VK_NULL_HANDLE;
            r = VK_ERROR_INVALID_EXTERNAL_HANDLE;
            break;
        }
        /* Mali's gralloc can put more than one fd in the handle (metadata beside the pages): the
         * memory is the one at least as large as the allocation. */
        int pick = -1;
        for (int i = 0; i < nh->numFds && pick < 0; i++) {
            off_t end = lseek(nh->data[i], 0, SEEK_END);
            if (end >= (off_t)sz) pick = nh->data[i];
            if (nh->numFds > 1) VKB_INFO("ahb: handle fd %d of %d is %lld bytes", i + 1, nh->numFds, (long long)end);
        }
        if (pick < 0) {
            VKB_INFO("ahb: no fd in the handle covers %llu bytes", (unsigned long long)sz);
            dt->vkFreeMemory(dev, m->mem, NULL);
            m->mem = VK_NULL_HANDLE;
            r = VK_ERROR_INVALID_EXTERNAL_HANDLE;
            break;
        }
        *out_fd = fcntl(pick, F_DUPFD_CLOEXEC, 0);
        break;
    }
#endif
    default:
        break;
    }
    if (client_fd >= 0) close(client_fd);
    if (r != VK_SUCCESS) {
        mem_release_backing(m);
        return r;
    }
    *out = m;
    return VK_SUCCESS;
}

/* ------------------------------------------------------------------ handlers */

static void close_import_fds(const void *pNext)
{
    for (const VkBaseInStructure *b = pNext; b; b = b->pNext)
        if (b->sType == VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR) vkb_close_fd(((const VkImportMemoryFdInfoKHR *)b)->fd);
}

void vkb_sv_vkAllocateMemory(vkb_srv_call *c)
{
    VkDevice dev = (VkDevice)(uintptr_t)vkb_dec_u64(&c->d);
    VkMemoryAllocateInfo ai;
    int have = vkb_dec_u8(&c->d);
    if (have) vkb_dec_VkMemoryAllocateInfo(&c->d, &ai);
    uint32_t mode = vkb_dec_u32(&c->d);
    int client_fd = vkb_dec_fd(&c->d);
    vkb_srv_table *t = c->table;
    if (c->d.err || !have || !t || !t->is_device || t->device != dev) {
        if (client_fd >= 0) close(client_fd);
        vkb_srv_bad_message(c);
        return;
    }
    VkResult r;
    vkb_srv_mem *m = NULL;
    int out_fd = -1;
    if (mode == VKB_ALLOC_SHARED) {
        uint32_t s = t->pd ? t->pd->info.strategy : VKB_MEM_NONE;
        r = alloc_shared(c->dt, dev, s, &ai, client_fd, &m, &out_fd);
        if (r != VK_SUCCESS) VKB_WARN("shared allocation of %llu bytes (type %u, %s) failed: %d",
                                      (unsigned long long)ai.allocationSize, ai.memoryTypeIndex, vkb_mem_strategy_name(s), r);
    } else {
        if (client_fd >= 0) close(client_fd);
        m = calloc(1, sizeof(*m));
        m->size = ai.allocationSize;
        m->type = ai.memoryTypeIndex;
        r = c->dt->vkAllocateMemory(dev, &ai, NULL, &m->mem);
        if (r != VK_SUCCESS) {
            close_import_fds(ai.pNext);
            free(m);
            m = NULL;
        }
    }
    if (m) vkb_mem_track(t, m);
    vkb_enc_u32(&c->r, (uint32_t)r);
    vkb_enc_u64(&c->r, m ? (uint64_t)(uintptr_t)m->mem : 0);
    vkb_enc_fd(&c->r, out_fd);
    if (out_fd >= 0) vkb_srv_close_after_send(c, out_fd);
    vkb_enc_u64(&c->r, m ? (uint64_t)m->size : 0);
}

void vkb_sv_vkFreeMemory(vkb_srv_call *c)
{
    VkDevice dev = (VkDevice)(uintptr_t)vkb_dec_u64(&c->d);
    VkDeviceMemory mem;
    vkb_dec_bytes(&c->d, &mem, sizeof(mem));
    vkb_srv_table *t = c->table;
    if (c->d.err || !t || !t->is_device || t->device != dev) {
        vkb_srv_bad_message(c);
        return;
    }
    if (!mem) return;
    vkb_srv_mem *m = vkb_mem_find(t, mem, 1);
    if (m) {
        vkb_mem_release(t, m);
    } else {
        VKB_WARN("vkFreeMemory of untracked memory");
        c->dt->vkFreeMemory(dev, mem, NULL);
    }
}

static VkResult ensure_server_map(vkb_srv_table *t, vkb_srv_mem *m)
{
    if (m->mapped) return VK_SUCCESS;
    pthread_mutex_lock(&t->lock);
    VkResult r = VK_SUCCESS;
    if (!m->mapped) r = t->real.vkMapMemory(t->device, m->mem, 0, VK_WHOLE_SIZE, 0, &m->mapped);
    pthread_mutex_unlock(&t->lock);
    return r;
}

static void flush_or_invalidate(vkb_srv_call *c, int flush)
{
    VkDevice dev = (VkDevice)(uintptr_t)vkb_dec_u64(&c->d);
    uint32_t n = vkb_dec_u32(&c->d);
    vkb_srv_table *t = c->table;
    if (c->d.err || !t || !t->is_device || t->device != dev || n > 65536) {
        vkb_srv_bad_message(c);
        return;
    }
    VkMappedMemoryRange *ranges = vkb_dec_alloc(&c->d, (n ? n : 1) * sizeof(*ranges));
    for (uint32_t i = 0; ranges && i < n; i++) vkb_dec_VkMappedMemoryRange(&c->d, &ranges[i]);
    if (c->d.err) {
        vkb_srv_bad_message(c);
        return;
    }
    VkResult r = VK_SUCCESS;
    for (uint32_t i = 0; i < n && r == VK_SUCCESS; i++) {
        vkb_srv_mem *m = vkb_mem_find(t, ranges[i].memory, 0);
        if (m) r = ensure_server_map(t, m);
    }
    if (r == VK_SUCCESS && n) {
        r = flush ? c->dt->vkFlushMappedMemoryRanges(dev, n, ranges) : c->dt->vkInvalidateMappedMemoryRanges(dev, n, ranges);
    }
    vkb_enc_u32(&c->r, (uint32_t)r);
}

void vkb_sv_vkFlushMappedMemoryRanges(vkb_srv_call *c) { flush_or_invalidate(c, 1); }
void vkb_sv_vkInvalidateMappedMemoryRanges(vkb_srv_call *c) { flush_or_invalidate(c, 0); }

/* ------------------------------------------------------------------ self-test */

typedef struct {
    const vkb_dispatch *idt;
    vkb_dispatch dt;
    VkPhysicalDevice pd;
    VkDevice dev;
    VkQueue queue;
    VkCommandPool pool;
    uint32_t qf;
    VkPhysicalDeviceMemoryProperties mp;
    VkDeviceSize import_align;
} selftest;

static int submit_and_wait(selftest *st, VkCommandBuffer cb)
{
    VkFenceCreateInfo fci = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VkFence f;
    if (st->dt.vkCreateFence(st->dev, &fci, NULL, &f) != VK_SUCCESS) return 0;
    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, NULL, 0, NULL, NULL, 1, &cb, 0, NULL};
    int ok = st->dt.vkQueueSubmit(st->queue, 1, &si, f) == VK_SUCCESS &&
             st->dt.vkWaitForFences(st->dev, 1, &f, VK_TRUE, 5000000000ull) == VK_SUCCESS;
    st->dt.vkDestroyFence(st->dev, f, NULL);
    return ok;
}

#define TEST_SIZE (256u * 1024u)

/* One allocation of `type` with strategy `s`, checked from both sides. 1 = shared and coherent
 * (no flush needed), 2 = shared when flushed, 0 = not shared. */
static int test_type_mode(selftest *st, uint32_t s, uint32_t type, int no_flush);

/* Without flushes first: imported pages from an uncached heap are coherent in practice even where
 * the driver's type does not say HOST_COHERENT (Mali-G720: dma-heap imports land in type 1,
 * HOST_CACHED without COHERENT, and pass with no flush). Only if that fails, with flushes. */
static int test_type(selftest *st, uint32_t s, uint32_t type)
{
    int coherent_flag = (st->mp.memoryTypes[type].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
    if (!coherent_flag && !getenv("VKBRIDGE_SELFTEST_NOFLUSH")) {
        int r = test_type_mode(st, s, type, 1);
        if (r) {
            VKB_INFO("  %s, type %u: coherent in practice (no flushes needed)", vkb_mem_strategy_name(s), type);
            return 1;
        }
    }
    return test_type_mode(st, s, type, 0);
}

static int test_type_mode(selftest *st, uint32_t s, uint32_t type, int no_flush)
{
    const vkb_dispatch *dt = &st->dt;
    int result = 0;
    VkExternalMemoryBufferCreateInfo ext = {VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO, NULL, strategy_handle_type(s)};
    VkBufferCreateInfo bci = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, &ext, 0, TEST_SIZE,
                              VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT |
                                  VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT |
                                  VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                              VK_SHARING_MODE_EXCLUSIVE, 0, NULL};
    VkBuffer buf = VK_NULL_HANDLE;
    if (dt->vkCreateBuffer(st->dev, &bci, NULL, &buf) != VK_SUCCESS) return 0;
    VkMemoryRequirements req;
    dt->vkGetBufferMemoryRequirements(st->dev, buf, &req);
    if (!(req.memoryTypeBits & (1u << type))) {
        dt->vkDestroyBuffer(st->dev, buf, NULL);
        return 0;
    }
    VkDeviceSize size = req.size;
    if (s == VKB_MEM_HOSTPTR) size = (size + st->import_align - 1) & ~(st->import_align - 1);
    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, NULL, size, type};
    int cfd = -1;
    if (s == VKB_MEM_HOSTPTR) {
        cfd = vkb_memfd("vkbridge-selftest", (size_t)size);
        if (cfd < 0) {
            dt->vkDestroyBuffer(st->dev, buf, NULL);
            return 0;
        }
    }
    int keep_fd = s == VKB_MEM_HOSTPTR ? fcntl(cfd, F_DUPFD_CLOEXEC, 0) : -1;
    vkb_srv_mem *m = NULL;
    int out_fd = -1;
    VkResult r = alloc_shared(dt, st->dev, s, &ai, cfd, &m, &out_fd);
    if (r != VK_SUCCESS) {
        VKB_INFO("  %s, type %u: allocation failed (%d)", vkb_mem_strategy_name(s), type, r);
        if (keep_fd >= 0) close(keep_fd);
        dt->vkDestroyBuffer(st->dev, buf, NULL);
        return 0;
    }
    int map_fd = s == VKB_MEM_HOSTPTR ? keep_fd : out_fd;
    uint8_t *cmap = mmap(NULL, (size_t)m->size, PROT_READ | PROT_WRITE, MAP_SHARED, map_fd, 0);
    if (map_fd >= 0) close(map_fd);
    void *smap = NULL;
    if (cmap == MAP_FAILED) {
        VKB_INFO("  %s, type %u: the client cannot map the memory (%s)", vkb_mem_strategy_name(s), type, strerror(errno));
        cmap = NULL;
        goto out;
    }
    if (dt->vkBindBufferMemory(st->dev, buf, m->mem, 0) != VK_SUCCESS ||
        dt->vkMapMemory(st->dev, m->mem, 0, VK_WHOLE_SIZE, 0, &smap) != VK_SUCCESS) {
        VKB_INFO("  %s, type %u: bind/map failed", vkb_mem_strategy_name(s), type);
        goto out;
    }
    int coherent = (st->mp.memoryTypes[type].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
    /* VKBRIDGE_SELFTEST_NOFLUSH=1 forces it for every type. */
    if (no_flush || getenv("VKBRIDGE_SELFTEST_NOFLUSH")) coherent = 1;
    VkMappedMemoryRange whole = {VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE, NULL, m->mem, 0, VK_WHOLE_SIZE};

    /* 1: the two CPU mappings are the same pages. */
    for (uint32_t i = 0; i < TEST_SIZE / 4; i += 997) ((volatile uint32_t *)cmap)[i] = 0x11220000u + i;
    __sync_synchronize();
    for (uint32_t i = 0; i < TEST_SIZE / 4; i += 997) {
        if (((volatile uint32_t *)smap)[i] != 0x11220000u + i) {
            VKB_INFO("  %s, type %u: CPU mappings differ at word %u", vkb_mem_strategy_name(s), type, i);
            goto out;
        }
    }
    /* 2: GPU writes, client reads.  3: client writes, GPU copies, client reads the copy. */
    for (uint32_t i = TEST_SIZE / 8; i < TEST_SIZE / 8 + TEST_SIZE / 32; i++) ((volatile uint32_t *)cmap)[i] = 0x5a000000u ^ (i * 2654435761u);
    if (!coherent) dt->vkFlushMappedMemoryRanges(st->dev, 1, &whole);
    VkCommandBufferAllocateInfo cai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, NULL, st->pool, VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1};
    VkCommandBuffer cb;
    if (dt->vkAllocateCommandBuffers(st->dev, &cai, &cb) != VK_SUCCESS) goto out;
    VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, NULL, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, NULL};
    dt->vkBeginCommandBuffer(cb, &bi);
    dt->vkCmdFillBuffer(cb, buf, 0, TEST_SIZE / 8, 0xA5A5A5A5u);
    /* Layout (bytes): [0, N/8) GPU fill; [N/2, N/2 + N/8) written by the client and copied by
     * the GPU to [3N/4, 3N/4 + N/8). */
    VkBufferCopy region = {TEST_SIZE / 2, TEST_SIZE / 4 * 3, TEST_SIZE / 8};
    dt->vkCmdCopyBuffer(cb, buf, buf, 1, &region);
    VkMemoryBarrier mb = {VK_STRUCTURE_TYPE_MEMORY_BARRIER, NULL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT};
    dt->vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0, NULL, 0, NULL);
    dt->vkEndCommandBuffer(cb);
    int submitted = submit_and_wait(st, cb);
    dt->vkFreeCommandBuffers(st->dev, st->pool, 1, &cb);
    if (!submitted) {
        VKB_INFO("  %s, type %u: GPU submission failed", vkb_mem_strategy_name(s), type);
        goto out;
    }
    if (!coherent) dt->vkInvalidateMappedMemoryRanges(st->dev, 1, &whole);
    for (uint32_t i = 0; i < TEST_SIZE / 32; i++) {
        if (((volatile uint32_t *)cmap)[i] != 0xA5A5A5A5u) {
            VKB_INFO("  %s, type %u: the client does not see a GPU write (word %u = 0x%08x)", vkb_mem_strategy_name(s), type, i,
                     ((volatile uint32_t *)cmap)[i]);
            goto out;
        }
    }
    for (uint32_t i = 0; i < TEST_SIZE / 32; i++) {
        uint32_t want = 0x5a000000u ^ ((TEST_SIZE / 8 + i) * 2654435761u);
        uint32_t got = ((volatile uint32_t *)cmap)[TEST_SIZE / 16 * 3 + i];
        if (got != want) {
            VKB_INFO("  %s, type %u: the GPU does not see a client write (word %u = 0x%08x, want 0x%08x)", vkb_mem_strategy_name(s),
                     type, i, got, want);
            goto out;
        }
    }
    result = coherent ? 1 : 2;
    VKB_INFO("  %s, type %u (flags 0x%x): shared OK%s", vkb_mem_strategy_name(s), type, st->mp.memoryTypes[type].propertyFlags,
             coherent ? ", coherent" : ", needs flushes");
out:
    if (cmap) munmap(cmap, (size_t)m->size);
    if (smap) dt->vkUnmapMemory(st->dev, m->mem);
    dt->vkDestroyBuffer(st->dev, buf, NULL);
    dt->vkFreeMemory(st->dev, m->mem, NULL);
    mem_release_backing(m);
    return result;
}

static int has_ext(const VkExtensionProperties *e, uint32_t n, const char *name)
{
    for (uint32_t i = 0; i < n; i++)
        if (!strcmp(e[i].extensionName, name)) return 1;
    return 0;
}

/* What the driver says it can do with each kind of external buffer memory (the device runs). */
static void log_external_props(const vkb_dispatch *idt, VkPhysicalDevice pd)
{
    if (!idt->vkGetPhysicalDeviceExternalBufferProperties) return;
    static const struct { uint32_t bit; const char *name; } types[] = {
        {VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT, "opaque fd"},
        {VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, "dma-buf"},
        {VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT, "host allocation"},
#ifdef __ANDROID__
        {VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID, "AHB"},
#endif
    };
    for (size_t i = 0; i < sizeof(types) / sizeof(types[0]); i++) {
        VkPhysicalDeviceExternalBufferInfo bi = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_BUFFER_INFO, NULL, 0,
                                                 VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT |
                                                     VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                                                 (VkExternalMemoryHandleTypeFlagBits)types[i].bit};
        VkExternalBufferProperties ep = {VK_STRUCTURE_TYPE_EXTERNAL_BUFFER_PROPERTIES};
        idt->vkGetPhysicalDeviceExternalBufferProperties(pd, &bi, &ep);
        const VkExternalMemoryProperties *p = &ep.externalMemoryProperties;
        VKB_INFO("external buffers, %s: features 0x%x (%s%s%s), export from 0x%x, compatible 0x%x", types[i].name,
                 p->externalMemoryFeatures,
                 p->externalMemoryFeatures & VK_EXTERNAL_MEMORY_FEATURE_DEDICATED_ONLY_BIT ? "dedicated-only " : "",
                 p->externalMemoryFeatures & VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT ? "exportable " : "",
                 p->externalMemoryFeatures & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT ? "importable" : "",
                 p->exportFromImportedHandleTypes, p->compatibleHandleTypes);
    }
}

void vkb_mem_selftest(vkb_pd_knowledge *k, VkInstance inst, const vkb_dispatch *idt, VkPhysicalDevice pd)
{
    selftest st;
    memset(&st, 0, sizeof(st));
    st.idt = idt;
    st.pd = pd;
    idt->vkGetPhysicalDeviceMemoryProperties(pd, &st.mp);

    uint32_t nq = 0;
    idt->vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, NULL);
    VkQueueFamilyProperties qp[16];
    if (nq > 16) nq = 16;
    idt->vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, qp);
    st.qf = 0;
    for (uint32_t i = 0; i < nq; i++)
        if (qp[i].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) {
            st.qf = i;
            break;
        }

    uint32_t ne = 0;
    idt->vkEnumerateDeviceExtensionProperties(pd, NULL, &ne, NULL);
    VkExtensionProperties *ex = calloc(ne ? ne : 1, sizeof(*ex));
    idt->vkEnumerateDeviceExtensionProperties(pd, NULL, &ne, ex);

    uint32_t candidates[4];
    int nc = 0;
    const char *force = getenv("VKBRIDGE_MEMORY");
    /* GameNative's order on Mali: the driver's own export, a dma-heap import, then an AHB. */
    uint32_t order[] = {VKB_MEM_HOSTPTR, VKB_MEM_DMABUF, VKB_MEM_DMAHEAP, VKB_MEM_AHB};
    for (int i = 0; i < 4; i++) {
        uint32_t s = order[i];
        if (force && strcmp(force, vkb_mem_strategy_name(s))) continue;
        uint32_t n;
        const char *const *req = vkb_mem_required_extensions(s, &n);
        if (!req) continue;
        int ok = 1;
        for (uint32_t j = 0; j < n; j++)
            if (strcmp(req[j], "VK_KHR_external_memory") && strcmp(req[j], "VK_KHR_sampler_ycbcr_conversion") &&
                strcmp(req[j], "VK_KHR_dedicated_allocation") && strcmp(req[j], "VK_KHR_get_memory_requirements2") &&
                !has_ext(ex, ne, req[j]))
                ok = 0;
        if (ok) candidates[nc++] = s;
        else VKB_INFO("memory sharing: %s unavailable (driver lacks its extensions)", vkb_mem_strategy_name(s));
    }

    /* One test device with every candidate's extensions. */
    const char *exts[16];
    uint32_t nx = 0;
    for (int i = 0; i < nc; i++) {
        uint32_t n;
        const char *const *req = vkb_mem_required_extensions(candidates[i], &n);
        for (uint32_t j = 0; j < n; j++) {
            if (!has_ext(ex, ne, req[j])) continue;
            int dup = 0;
            for (uint32_t q = 0; q < nx; q++) dup |= !strcmp(exts[q], req[j]);
            if (!dup && nx < 16) exts[nx++] = req[j];
        }
    }
    free(ex);
    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, NULL, 0, st.qf, 1, &prio};
    VkDeviceCreateInfo dci = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, NULL, 0, 1, &qci, 0, NULL, nx, exts, NULL};
    if (idt->vkCreateDevice(pd, &dci, NULL, &st.dev) != VK_SUCCESS) {
        VKB_ERR("memory self-test: cannot create a device");
        return;
    }
    st.dt = *idt;
    PFN_vkGetDeviceProcAddr gdpa = (PFN_vkGetDeviceProcAddr)vkb_gipa(inst, "vkGetDeviceProcAddr");
    vkb_mem_gdpa = gdpa;
    log_external_props(idt, pd);
    vkb_dispatch_load_device(&st.dt, gdpa, st.dev);
    st.dt.vkGetDeviceQueue(st.dev, st.qf, 0, &st.queue);
    VkCommandPoolCreateInfo pci = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, NULL, 0, st.qf};
    st.dt.vkCreateCommandPool(st.dev, &pci, NULL, &st.pool);

    VkPhysicalDeviceExternalMemoryHostPropertiesEXT hostp = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_MEMORY_HOST_PROPERTIES_EXT};
    VkPhysicalDeviceProperties2 p2 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, &hostp};
    idt->vkGetPhysicalDeviceProperties2(pd, &p2);
    st.import_align = hostp.minImportedHostPointerAlignment ? hostp.minImportedHostPointerAlignment : 4096;
    if (st.import_align < 4096) st.import_align = 4096;

    k->info.strategy = VKB_MEM_NONE;
    for (int i = 0; i < nc; i++) {
        uint32_t s = candidates[i];
        uint32_t shareable = 0, coherent = 0;
        for (uint32_t t = 0; t < st.mp.memoryTypeCount; t++) {
            if (!(st.mp.memoryTypes[t].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) continue;
            if (st.mp.memoryTypes[t].propertyFlags & VK_MEMORY_PROPERTY_PROTECTED_BIT) continue;
            int res = test_type(&st, s, t);
            if (res) shareable |= 1u << t;
            if (res == 1) coherent |= 1u << t;
        }
        if (coherent) {
            k->info.strategy = s;
            k->info.buffer_handle_types = strategy_handle_type(s);
            k->info.shareable_types = shareable;
            k->info.coherent_types = coherent;
            k->info.import_alignment = st.import_align;
            break;
        }
        VKB_INFO("memory sharing: %s failed the self-test", vkb_mem_strategy_name(s));
    }
    if (k->info.strategy == VKB_MEM_NONE)
        VKB_ERR("memory sharing: no method works on %s; Linux programs will get no host-visible memory", k->name);

    st.dt.vkDestroyCommandPool(st.dev, st.pool, NULL);
    st.dt.vkDestroyDevice(st.dev, NULL);
}
