/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Device memory on the client side: the memory-type translation of physdev.c applied to every
 * allocation and requirement, and mapping of shared allocations (server_memory.c explains how
 * they are shared).
 */
#define _GNU_SOURCE
#include "vkb_client.h"
#include "gen/vkb_client_gen.h"
#include "emu.h"

#include <errno.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

void vkb_memory_init_device(vkb_device *d)
{
    (void)d;
}

void vkb_memory_unmap_free(vkb_memory *m)
{
    if (m->ptr) munmap(m->ptr, (size_t)m->size);
    free(m);
}

/* ------------------------------------------------------------------ resources */

/* Shallow copy of a pNext chain (known structures only), in one malloc'd block (*mem). */
void *vkb_chain_clone(const void *pNext, void **mem)
{
    size_t total = 0;
    for (const VkBaseInStructure *b = pNext; b; b = b->pNext) total += (vkb_struct_size(b->sType) + 15) & ~(size_t)15;
    uint8_t *block = calloc(1, total ? total : 16);
    *mem = block;
    VkBaseOutStructure *head = NULL, *prev = NULL;
    size_t off = 0;
    for (const VkBaseInStructure *b = pNext; b; b = b->pNext) {
        size_t sz = vkb_struct_size(b->sType);
        if (!sz) continue;
        VkBaseOutStructure *n = (VkBaseOutStructure *)(block + off);
        memcpy(n, b, sz);
        n->pNext = NULL;
        off += (sz + 15) & ~(size_t)15;
        if (prev) prev->pNext = n;
        else head = n;
        prev = n;
    }
    return head;
}

static int can_share(vkb_device *dev)
{
    return dev->pd->info.strategy != VKB_MEM_NONE && dev->pd->info.buffer_handle_types;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkCreateBuffer(VkDevice device, const VkBufferCreateInfo *pCreateInfo,
                                                     const VkAllocationCallbacks *pAllocator, VkBuffer *pBuffer)
{
    vkb_device *dev = vkb_dev(device);
    if (!can_share(dev) || (pCreateInfo->flags & VK_BUFFER_CREATE_SPARSE_BINDING_BIT)) {
        VkResult r = vkb_wire_vkCreateBuffer(device, pCreateInfo, pAllocator, pBuffer);
        if (r == VK_SUCCESS) vkb_map_put(&dev->unshared, (uint64_t)*pBuffer, (void *)1);
        return r;
    }
    /* Every buffer may be bound to shared memory: say so at creation, as the spec requires. */
    VkBufferCreateInfo ci = *pCreateInfo;
    VkExternalMemoryBufferCreateInfo ext = {VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO, ci.pNext,
                                            dev->pd->info.buffer_handle_types};
    int had = 0;
    for (VkBaseOutStructure *b = (VkBaseOutStructure *)ci.pNext; b; b = b->pNext) {
        if (b->sType == VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO) had = 1;
    }
    VkResult r;
    if (had) {
        /* The app has its own external handle types: ours are added to a copy of its chain. */
        void *mem = NULL;
        ci.pNext = vkb_chain_clone(ci.pNext, &mem);
        for (VkBaseOutStructure *b = (VkBaseOutStructure *)ci.pNext; b; b = b->pNext)
            if (b->sType == VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO)
                ((VkExternalMemoryBufferCreateInfo *)b)->handleTypes |= dev->pd->info.buffer_handle_types;
        r = vkb_wire_vkCreateBuffer(device, &ci, pAllocator, pBuffer);
        free(mem);
        if (r != VK_SUCCESS && r != VK_ERROR_OUT_OF_HOST_MEMORY && r != VK_ERROR_OUT_OF_DEVICE_MEMORY) {
            r = vkb_wire_vkCreateBuffer(device, pCreateInfo, pAllocator, pBuffer);
            if (r == VK_SUCCESS) vkb_map_put(&dev->unshared, (uint64_t)*pBuffer, (void *)1);
        }
        return r;
    }
    ci.pNext = &ext;
    r = vkb_wire_vkCreateBuffer(device, &ci, pAllocator, pBuffer);
    if (r != VK_SUCCESS && r != VK_ERROR_OUT_OF_HOST_MEMORY && r != VK_ERROR_OUT_OF_DEVICE_MEMORY) {
        VKB_ONCE(VKB_LOG_WARN, "a buffer cannot be created shareable (%d); it gets device-only memory (logged once)", r);
        r = vkb_wire_vkCreateBuffer(device, pCreateInfo, pAllocator, pBuffer);
        if (r == VK_SUCCESS) vkb_map_put(&dev->unshared, (uint64_t)*pBuffer, (void *)1);
    }
    return r;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkDestroyBuffer(VkDevice device, VkBuffer buffer, const VkAllocationCallbacks *pAllocator)
{
    if (!buffer) return;
    vkb_map_remove(&vkb_dev(device)->unshared, (uint64_t)buffer);
    vkb_wire_vkDestroyBuffer(device, buffer, pAllocator);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkCreateImage(VkDevice device, const VkImageCreateInfo *pCreateInfo,
                                                    const VkAllocationCallbacks *pAllocator, VkImage *pImage)
{
    vkb_device *dev = vkb_dev(device);
    VkImageCreateInfo ci = *pCreateInfo;
    vkb_emu_image_create_info(dev, &ci);
    /* Linear images may be mapped by the app: those get the shared handle type when allowed. */
    if (can_share(dev) && ci.tiling == VK_IMAGE_TILING_LINEAR && !(ci.flags & VK_IMAGE_CREATE_SPARSE_BINDING_BIT)) {
        int had = 0;
        for (VkBaseOutStructure *b = (VkBaseOutStructure *)ci.pNext; b; b = b->pNext)
            had |= b->sType == VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
        if (!had) {
            VkExternalMemoryImageCreateInfo ext = {VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO, ci.pNext,
                                                   dev->pd->info.buffer_handle_types};
            VkImageCreateInfo c2 = ci;
            c2.pNext = &ext;
            VkResult r = vkb_wire_vkCreateImage(device, &c2, pAllocator, pImage);
            if (r == VK_SUCCESS) {
                vkb_map_put(&dev->shared_images, (uint64_t)*pImage, (void *)1);
                vkb_emu_image_created(dev, pCreateInfo, *pImage);
                return r;
            }
        }
    }
    VkResult r = vkb_wire_vkCreateImage(device, &ci, pAllocator, pImage);
    if (r == VK_SUCCESS) vkb_emu_image_created(dev, pCreateInfo, *pImage);
    return r;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkDestroyImage(VkDevice device, VkImage image, const VkAllocationCallbacks *pAllocator)
{
    if (!image) return;
    vkb_device *dev = vkb_dev(device);
    vkb_map_remove(&dev->shared_images, (uint64_t)image);
    vkb_emu_image_destroyed(dev, image);
    vkb_wire_vkDestroyImage(device, image, pAllocator);
}

/* ------------------------------------------------------------------ requirements */

static void fix_reqs(vkb_device *dev, VkMemoryRequirements *r, int allow_shared)
{
    r->memoryTypeBits = vkb_mem_bits_to_client(dev->pd, r->memoryTypeBits, allow_shared);
}

static int buffer_shareable(vkb_device *dev, VkBuffer b)
{
    return can_share(dev) && !vkb_map_get(&dev->unshared, (uint64_t)b);
}

static int image_shareable(vkb_device *dev, VkImage i)
{
    return vkb_map_get(&dev->shared_images, (uint64_t)i) != NULL;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetBufferMemoryRequirements(VkDevice device, VkBuffer buffer, VkMemoryRequirements *pReq)
{
    vkb_wire_vkGetBufferMemoryRequirements(device, buffer, pReq);
    fix_reqs(vkb_dev(device), pReq, buffer_shareable(vkb_dev(device), buffer));
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetBufferMemoryRequirements2(VkDevice device, const VkBufferMemoryRequirementsInfo2 *pInfo,
                                                                 VkMemoryRequirements2 *pReq)
{
    vkb_wire_vkGetBufferMemoryRequirements2(device, pInfo, pReq);
    fix_reqs(vkb_dev(device), &pReq->memoryRequirements, buffer_shareable(vkb_dev(device), pInfo->buffer));
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetImageMemoryRequirements(VkDevice device, VkImage image, VkMemoryRequirements *pReq)
{
    vkb_wire_vkGetImageMemoryRequirements(device, image, pReq);
    fix_reqs(vkb_dev(device), pReq, image_shareable(vkb_dev(device), image));
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetImageMemoryRequirements2(VkDevice device, const VkImageMemoryRequirementsInfo2 *pInfo,
                                                                VkMemoryRequirements2 *pReq)
{
    vkb_wire_vkGetImageMemoryRequirements2(device, pInfo, pReq);
    fix_reqs(vkb_dev(device), &pReq->memoryRequirements, image_shareable(vkb_dev(device), pInfo->image));
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetDeviceBufferMemoryRequirements(VkDevice device, const VkDeviceBufferMemoryRequirements *pInfo,
                                                                      VkMemoryRequirements2 *pReq)
{
    vkb_device *dev = vkb_dev(device);
    if (!can_share(dev)) {
        vkb_wire_vkGetDeviceBufferMemoryRequirements(device, pInfo, pReq);
        fix_reqs(dev, &pReq->memoryRequirements, 0);
        return;
    }
    VkBufferCreateInfo ci = *pInfo->pCreateInfo;
    VkExternalMemoryBufferCreateInfo ext = {VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO, ci.pNext,
                                            dev->pd->info.buffer_handle_types};
    int had = 0;
    for (VkBaseOutStructure *b = (VkBaseOutStructure *)ci.pNext; b; b = b->pNext)
        had |= b->sType == VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO;
    if (!had) ci.pNext = &ext;
    VkDeviceBufferMemoryRequirements info = *pInfo;
    info.pCreateInfo = &ci;
    vkb_wire_vkGetDeviceBufferMemoryRequirements(device, &info, pReq);
    fix_reqs(dev, &pReq->memoryRequirements, 1);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetDeviceImageMemoryRequirements(VkDevice device, const VkDeviceImageMemoryRequirements *pInfo,
                                                                     VkMemoryRequirements2 *pReq)
{
    vkb_device *dev = vkb_dev(device);
    VkImageCreateInfo ci = *pInfo->pCreateInfo;
    vkb_emu_image_create_info(dev, &ci);
    VkDeviceImageMemoryRequirements info = *pInfo;
    info.pCreateInfo = &ci;
    vkb_wire_vkGetDeviceImageMemoryRequirements(device, &info, pReq);
    fix_reqs(dev, &pReq->memoryRequirements, 0);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetImageSparseMemoryRequirements(VkDevice device, VkImage image, uint32_t *pCount,
                                                                     VkSparseImageMemoryRequirements *pReq)
{
    vkb_wire_vkGetImageSparseMemoryRequirements(device, image, pCount, pReq);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetImageSparseMemoryRequirements2(VkDevice device, const VkImageSparseMemoryRequirementsInfo2 *pInfo,
                                                                      uint32_t *pCount, VkSparseImageMemoryRequirements2 *pReq)
{
    vkb_wire_vkGetImageSparseMemoryRequirements2(device, pInfo, pCount, pReq);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetDeviceImageSparseMemoryRequirements(VkDevice device, const VkDeviceImageMemoryRequirements *pInfo,
                                                                           uint32_t *pCount, VkSparseImageMemoryRequirements2 *pReq)
{
    vkb_wire_vkGetDeviceImageSparseMemoryRequirements(device, pInfo, pCount, pReq);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkGetMemoryFdPropertiesKHR(VkDevice device, VkExternalMemoryHandleTypeFlagBits handleType, int fd,
                                                                 VkMemoryFdPropertiesKHR *pProps)
{
    VkResult r = vkb_wire_vkGetMemoryFdPropertiesKHR(device, handleType, fd, pProps);
    if (r == VK_SUCCESS) pProps->memoryTypeBits = vkb_mem_bits_to_client(vkb_dev(device)->pd, pProps->memoryTypeBits, 0);
    return r;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkBindBufferMemory(VkDevice device, VkBuffer buffer, VkDeviceMemory memory, VkDeviceSize offset)
{
    return vkb_wire_vkBindBufferMemory(device, buffer, memory, offset);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkBindBufferMemory2(VkDevice device, uint32_t n, const VkBindBufferMemoryInfo *p)
{
    return vkb_wire_vkBindBufferMemory2(device, n, p);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkBindImageMemory(VkDevice device, VkImage image, VkDeviceMemory memory, VkDeviceSize offset)
{
    return vkb_wire_vkBindImageMemory(device, image, memory, offset);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkBindImageMemory2(VkDevice device, uint32_t n, const VkBindImageMemoryInfo *p)
{
    return vkb_wire_vkBindImageMemory2(device, n, p);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkGetImageSubresourceLayout(VkDevice device, VkImage image, const VkImageSubresource *pSubresource,
                                                              VkSubresourceLayout *pLayout)
{
    vkb_wire_vkGetImageSubresourceLayout(device, image, pSubresource, pLayout);
}

/* ------------------------------------------------------------------ allocation and mapping */

static void close_import_fds(const void *pNext)
{
    for (const VkBaseInStructure *b = pNext; b; b = b->pNext) {
        if (b->sType == VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR) {
            int fd = ((const VkImportMemoryFdInfoKHR *)b)->fd;
            if (fd >= 0) close(fd);
        }
    }
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkAllocateMemory(VkDevice device, const VkMemoryAllocateInfo *pAllocateInfo,
                                                       const VkAllocationCallbacks *pAllocator, VkDeviceMemory *pMemory)
{
    (void)pAllocator;
    vkb_device *dev = vkb_dev(device);
    vkb_physdev *pd = dev->pd;
    uint32_t ct = pAllocateInfo->memoryTypeIndex;
    if (ct >= pd->client_mem.memoryTypeCount) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    VkMemoryAllocateInfo ai = *pAllocateInfo;
    ai.memoryTypeIndex = pd->type_map[ct].server_type;
    int shared = pd->type_map[ct].shared;
    /* An app-imported dma-buf/opaque fd keeps its own memory; it is not one of our shared pages. */
    for (const VkBaseInStructure *b = ai.pNext; b; b = b->pNext)
        if (b->sType == VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR) shared = 0;
    uint32_t strategy = pd->info.strategy;
    int memfd = -1;
    void *ptr = NULL;
    if (shared && strategy == VKB_MEM_HOSTPTR) {
        VkDeviceSize a = pd->info.import_alignment ? pd->info.import_alignment : 4096;
        ai.allocationSize = (ai.allocationSize + a - 1) & ~(a - 1);
        memfd = vkb_memfd("vkbridge-memory", (size_t)ai.allocationSize);
        if (memfd < 0) return VK_ERROR_OUT_OF_HOST_MEMORY;
        ptr = mmap(NULL, (size_t)ai.allocationSize, PROT_READ | PROT_WRITE, MAP_SHARED, memfd, 0);
        if (ptr == MAP_FAILED) {
            close(memfd);
            return VK_ERROR_OUT_OF_HOST_MEMORY;
        }
    }
    vkb_call c;
    vkb_call_begin(&c, VKB_CMD_vkAllocateMemory, dev->obj.table);
    vkb_enc_u64(&c.e, dev->obj.remote);
    vkb_enc_u8(&c.e, 1);
    vkb_enc_VkMemoryAllocateInfo(&c.e, &ai);
    vkb_enc_u32(&c.e, shared ? VKB_ALLOC_SHARED : VKB_ALLOC_PLAIN);
    vkb_enc_fd(&c.e, memfd);
    int ok = vkb_call_exec(&c);
    if (memfd >= 0) close(memfd);
    if (!ok) {
        if (ptr) munmap(ptr, (size_t)ai.allocationSize);
        return VK_ERROR_DEVICE_LOST;
    }
    VkResult r = (VkResult)vkb_dec_u32(&c.d);
    VkDeviceMemory mem = (VkDeviceMemory)vkb_dec_u64(&c.d);
    int fd = vkb_dec_fd(&c.d);
    uint64_t size = vkb_dec_u64(&c.d);
    vkb_call_end(&c);
    if (r != VK_SUCCESS) {
        if (ptr) munmap(ptr, (size_t)ai.allocationSize);
        if (fd >= 0) close(fd);
        return r;
    }
    /* The import succeeded: the fd the app handed over now belongs to the implementation, and the
     * server holds its own copy. */
    close_import_fds(pAllocateInfo->pNext);
    vkb_memory *m = calloc(1, sizeof(*m));
    m->client_type = ct;
    m->shared = shared;
    m->size = shared && strategy == VKB_MEM_HOSTPTR ? ai.allocationSize : size;
    if (shared && strategy != VKB_MEM_HOSTPTR) {
        if (fd < 0) {
            VKB_ERR("shared allocation came back without its dma-buf");
        } else {
            ptr = mmap(NULL, (size_t)m->size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
            if (ptr == MAP_FAILED) {
                VKB_ERR("cannot map shared memory (%llu bytes): %s", (unsigned long long)m->size, strerror(errno));
                ptr = NULL;
            }
        }
    }
    if (fd >= 0) close(fd);
    m->ptr = ptr;
    vkb_map_put(&dev->memories, (uint64_t)mem, m);
    *pMemory = mem;
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkFreeMemory(VkDevice device, VkDeviceMemory memory, const VkAllocationCallbacks *pAllocator)
{
    (void)pAllocator;
    if (!memory) return;
    vkb_device *dev = vkb_dev(device);
    vkb_memory *m = vkb_map_remove(&dev->memories, (uint64_t)memory);
    vkb_call c;
    vkb_call_begin(&c, VKB_CMD_vkFreeMemory, dev->obj.table);
    vkb_enc_u64(&c.e, dev->obj.remote);
    vkb_enc_u64(&c.e, (uint64_t)memory);
    if (vkb_call_exec(&c)) vkb_call_end(&c);
    if (m) vkb_memory_unmap_free(m);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkMapMemory(VkDevice device, VkDeviceMemory memory, VkDeviceSize offset, VkDeviceSize size,
                                                  VkMemoryMapFlags flags, void **ppData)
{
    (void)size;
    (void)flags;
    vkb_memory *m = vkb_map_get(&vkb_dev(device)->memories, (uint64_t)memory);
    if (!m || !m->ptr) {
        VKB_ONCE(VKB_LOG_WARN, "vkMapMemory on memory that is not host-visible through the bridge (logged once)");
        *ppData = NULL;
        return VK_ERROR_MEMORY_MAP_FAILED;
    }
    *ppData = (uint8_t *)m->ptr + offset;
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkUnmapMemory(VkDevice device, VkDeviceMemory memory)
{
    /* The mapping stays until vkFreeMemory: mapping again is free, and nothing else reuses it. */
    (void)device;
    (void)memory;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkMapMemory2(VkDevice device, const VkMemoryMapInfo *pInfo, void **ppData)
{
    return vkb_ep_vkMapMemory(device, pInfo->memory, pInfo->offset, pInfo->size, pInfo->flags, ppData);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkUnmapMemory2(VkDevice device, const VkMemoryUnmapInfo *pInfo)
{
    (void)device;
    (void)pInfo;
    return VK_SUCCESS;
}

static VkResult ranges_call(VkDevice device, uint32_t cmd, uint32_t n, const VkMappedMemoryRange *r)
{
    vkb_device *dev = vkb_dev(device);
    vkb_call c;
    vkb_call_begin(&c, cmd, dev->obj.table);
    vkb_enc_u64(&c.e, dev->obj.remote);
    vkb_enc_u32(&c.e, n);
    for (uint32_t i = 0; i < n; i++) vkb_enc_VkMappedMemoryRange(&c.e, &r[i]);
    if (!vkb_call_exec(&c)) return VK_ERROR_DEVICE_LOST;
    VkResult res = (VkResult)vkb_dec_u32(&c.d);
    vkb_call_end(&c);
    return res;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkFlushMappedMemoryRanges(VkDevice device, uint32_t n, const VkMappedMemoryRange *r)
{
    return ranges_call(device, VKB_CMD_vkFlushMappedMemoryRanges, n, r);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkInvalidateMappedMemoryRanges(VkDevice device, uint32_t n, const VkMappedMemoryRange *r)
{
    return ranges_call(device, VKB_CMD_vkInvalidateMappedMemoryRanges, n, r);
}
