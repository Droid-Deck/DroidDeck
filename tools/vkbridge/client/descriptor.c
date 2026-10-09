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
    if (vkb_call_exec(&c)) vkb_call_end(&c);
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
