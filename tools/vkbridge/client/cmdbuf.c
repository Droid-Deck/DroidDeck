/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Command buffers: vkCmd* calls are encoded into the command buffer's own stream (no round trip
 * each); vkEndCommandBuffer sends the stream in one message and the server replays it.
 *
 * Stream entry: u32 command id, u32 payload bytes, payload (8-aligned), padded to 8.
 */
#define _GNU_SOURCE
#include "vkb_client.h"
#include "gen/vkb_client_gen.h"

#include <string.h>

static __thread vkb_cmdbuf *tls_encoding;

int vkb_cb_begin_is_secondary(const VkCommandBufferBeginInfo *s)
{
    (void)s;
    return tls_encoding && tls_encoding->level == VK_COMMAND_BUFFER_LEVEL_SECONDARY;
}

vkb_enc *vkb_cmd_record_begin(VkCommandBuffer commandBuffer, uint32_t cmd)
{
    vkb_cmdbuf *cb = vkb_cb(commandBuffer);
    if (!cb->recording) {
        VKB_ONCE(VKB_LOG_WARN, "%s on a command buffer that is not recording (ignored; logged once)", vkb_cmd_names[cmd]);
        return NULL;
    }
    vkb_enc *e = &cb->stream;
    /* entries start 8-aligned */
    while (e->len & 7) vkb_enc_u8(e, 0);
    vkb_enc_u32(e, cmd);
    vkb_enc_u32(e, 0);
    cb->entry = e->len;
    return e;
}

void vkb_cmd_record_end(VkCommandBuffer commandBuffer)
{
    vkb_cmdbuf *cb = vkb_cb(commandBuffer);
    vkb_enc *e = &cb->stream;
    if (e->oom) {
        cb->broken = 1;
        return;
    }
    if (e->nfds) {
        VKB_ERR("internal: a recorded command carries a file descriptor");
        cb->broken = 1;
        e->nfds = 0;
    }
    uint32_t n = (uint32_t)(e->len - cb->entry);
    memcpy(e->buf + cb->entry - 4, &n, 4);
}

static void stream_reset(vkb_cmdbuf *cb)
{
    if (cb->stream.cap > (8u << 20)) {
        vkb_enc_free(&cb->stream);
        vkb_enc_init(&cb->stream);
    } else {
        vkb_enc_reset(&cb->stream);
    }
    cb->recording = 0;
    cb->broken = 0;
}

/* ------------------------------------------------------------------ pools */

static vkb_cmdpool *pool_get(vkb_device *dev, VkCommandPool pool, int create)
{
    vkb_cmdpool *p = vkb_map_get(&dev->pools, (uint64_t)pool);
    if (!p && create) {
        p = calloc(1, sizeof(*p));
        vkb_map_put(&dev->pools, (uint64_t)pool, p);
    }
    return p;
}

static void cmdbuf_free(vkb_cmdbuf *cb)
{
    vkb_enc_free(&cb->stream);
    free(cb);
}

void vkb_cmdpool_free(vkb_cmdpool *p)
{
    vkb_cmdbuf *cb = p->head;
    while (cb) {
        vkb_cmdbuf *n = cb->pool_next;
        cmdbuf_free(cb);
        cb = n;
    }
    free(p);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkAllocateCommandBuffers(VkDevice device, const VkCommandBufferAllocateInfo *pAllocateInfo,
                                                               VkCommandBuffer *pCommandBuffers)
{
    VkResult r = vkb_wire_vkAllocateCommandBuffers(device, pAllocateInfo, pCommandBuffers);
    if (r != VK_SUCCESS) {
        for (uint32_t i = 0; i < pAllocateInfo->commandBufferCount; i++) pCommandBuffers[i] = VK_NULL_HANDLE;
        return r;
    }
    vkb_device *dev = vkb_dev(device);
    pthread_mutex_lock(&dev->lock);
    vkb_cmdpool *p = pool_get(dev, pAllocateInfo->commandPool, 1);
    for (uint32_t i = 0; i < pAllocateInfo->commandBufferCount; i++) {
        vkb_cmdbuf *cb = vkb_cb(pCommandBuffers[i]);
        if (!cb) continue;
        cb->pool = pAllocateInfo->commandPool;
        cb->level = pAllocateInfo->level;
        cb->pool_next = p->head;
        cb->pool_prev = NULL;
        if (p->head) p->head->pool_prev = cb;
        p->head = cb;
    }
    pthread_mutex_unlock(&dev->lock);
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkFreeCommandBuffers(VkDevice device, VkCommandPool commandPool, uint32_t commandBufferCount,
                                                       const VkCommandBuffer *pCommandBuffers)
{
    vkb_wire_vkFreeCommandBuffers(device, commandPool, commandBufferCount, pCommandBuffers);
    vkb_device *dev = vkb_dev(device);
    pthread_mutex_lock(&dev->lock);
    vkb_cmdpool *p = pool_get(dev, commandPool, 0);
    for (uint32_t i = 0; i < commandBufferCount; i++) {
        vkb_cmdbuf *cb = vkb_cb(pCommandBuffers[i]);
        if (!cb) continue;
        if (p) {
            if (cb->pool_prev) cb->pool_prev->pool_next = cb->pool_next;
            else if (p->head == cb) p->head = cb->pool_next;
            if (cb->pool_next) cb->pool_next->pool_prev = cb->pool_prev;
        }
        cmdbuf_free(cb);
    }
    pthread_mutex_unlock(&dev->lock);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkDestroyCommandPool(VkDevice device, VkCommandPool commandPool, const VkAllocationCallbacks *pAllocator)
{
    if (!commandPool) return;
    vkb_wire_vkDestroyCommandPool(device, commandPool, pAllocator);
    vkb_device *dev = vkb_dev(device);
    pthread_mutex_lock(&dev->lock);
    vkb_cmdpool *p = vkb_map_remove(&dev->pools, (uint64_t)commandPool);
    pthread_mutex_unlock(&dev->lock);
    if (p) vkb_cmdpool_free(p);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkResetCommandPool(VkDevice device, VkCommandPool commandPool, VkCommandPoolResetFlags flags)
{
    VkResult r = vkb_wire_vkResetCommandPool(device, commandPool, flags);
    vkb_device *dev = vkb_dev(device);
    pthread_mutex_lock(&dev->lock);
    vkb_cmdpool *p = pool_get(dev, commandPool, 0);
    for (vkb_cmdbuf *cb = p ? p->head : NULL; cb; cb = cb->pool_next) stream_reset(cb);
    pthread_mutex_unlock(&dev->lock);
    return r;
}

/* ------------------------------------------------------------------ recording */

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkBeginCommandBuffer(VkCommandBuffer commandBuffer, const VkCommandBufferBeginInfo *pBeginInfo)
{
    vkb_cmdbuf *cb = vkb_cb(commandBuffer);
    stream_reset(cb);
    cb->recording = 1;
    vkb_enc *e = vkb_cmd_record_begin(commandBuffer, VKB_CMD_vkBeginCommandBuffer);
    vkb_enc_u64(e, cb->obj.remote);
    tls_encoding = cb;
    vkb_enc_u8(e, pBeginInfo != NULL);
    if (pBeginInfo) vkb_enc_VkCommandBufferBeginInfo(e, pBeginInfo);
    tls_encoding = NULL;
    vkb_cmd_record_end(commandBuffer);
    return cb->broken ? VK_ERROR_OUT_OF_HOST_MEMORY : VK_SUCCESS;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkEndCommandBuffer(VkCommandBuffer commandBuffer)
{
    vkb_cmdbuf *cb = vkb_cb(commandBuffer);
    if (!cb->recording) return VK_SUCCESS;
    cb->recording = 0;
    if (cb->broken || cb->stream.oom) {
        VKB_ERR("command buffer could not be recorded (out of memory)");
        stream_reset(cb);
        return VK_ERROR_OUT_OF_HOST_MEMORY;
    }
    vkb_call c;
    vkb_call_begin(&c, VKB_CMD_vkEndCommandBuffer, cb->obj.table);
    vkb_enc_u64(&c.e, cb->obj.remote);
    vkb_enc_blob(&c.e, cb->stream.buf ? cb->stream.buf : (const void *)"", cb->stream.len);
    VkResult r = VK_ERROR_DEVICE_LOST;
    if (vkb_async_results()) {
        /* Replayed on the server while the app goes on; ordered before its submit. */
        r = vkb_call_exec_async(&c) ? VK_SUCCESS : VK_ERROR_DEVICE_LOST;
    } else if (vkb_call_exec(&c)) {
        r = (VkResult)vkb_dec_u32(&c.d);
        vkb_call_end(&c);
    }
    stream_reset(cb);
    return r;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkResetCommandBuffer(VkCommandBuffer commandBuffer, VkCommandBufferResetFlags flags)
{
    stream_reset(vkb_cb(commandBuffer));
    return vkb_wire_vkResetCommandBuffer(commandBuffer, flags);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkCmdExecuteCommands(VkCommandBuffer commandBuffer, uint32_t commandBufferCount,
                                                       const VkCommandBuffer *pCommandBuffers)
{
    vkb_wire_vkCmdExecuteCommands(commandBuffer, commandBufferCount, pCommandBuffers);
}

/* Debug labels never reach the driver. */
VKAPI_ATTR void VKAPI_CALL vkb_ep_vkCmdBeginDebugUtilsLabelEXT(VkCommandBuffer commandBuffer, const VkDebugUtilsLabelEXT *pLabelInfo)
{
    (void)commandBuffer;
    (void)pLabelInfo;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkCmdEndDebugUtilsLabelEXT(VkCommandBuffer commandBuffer)
{
    (void)commandBuffer;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkCmdInsertDebugUtilsLabelEXT(VkCommandBuffer commandBuffer, const VkDebugUtilsLabelEXT *pLabelInfo)
{
    (void)commandBuffer;
    (void)pLabelInfo;
}
