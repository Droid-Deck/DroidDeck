/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Descriptor update templates. The client repacks the app's data to the layout of the template
 * it created on the server (entries rewritten to packed offsets), so the blob is used as is.
 */
#define _GNU_SOURCE
#include "vkb_server.h"

#include <string.h>

void vkb_sv_vkbDescUpdateRaw(vkb_srv_call *c)
{
    VkDevice dev = (VkDevice)(uintptr_t)vkb_dec_u64(&c->d);
    VkDescriptorSet set;
    VkDescriptorUpdateTemplate tmpl;
    vkb_dec_bytes(&c->d, &set, sizeof(set));
    vkb_dec_bytes(&c->d, &tmpl, sizeof(tmpl));
    size_t n = 0;
    const void *data = vkb_dec_blob(&c->d, &n);
    if (c->d.err || !c->table) {
        vkb_srv_bad_message(c);
        return;
    }
    if (!c->dt->vkUpdateDescriptorSetWithTemplate) {
        vkb_srv_missing(c, "vkUpdateDescriptorSetWithTemplate");
        return;
    }
    c->dt->vkUpdateDescriptorSetWithTemplate(dev, set, tmpl, data);
}

void vkb_sv_vkbPushDescRaw(vkb_srv_call *c)
{
    VkCommandBuffer cb = (VkCommandBuffer)(uintptr_t)vkb_dec_u64(&c->d);
    VkDescriptorUpdateTemplate tmpl;
    VkPipelineLayout layout;
    vkb_dec_bytes(&c->d, &tmpl, sizeof(tmpl));
    vkb_dec_bytes(&c->d, &layout, sizeof(layout));
    uint32_t set = vkb_dec_u32(&c->d);
    size_t n = 0;
    const void *data = vkb_dec_blob(&c->d, &n);
    if (c->d.err || !c->table) {
        vkb_srv_bad_message(c);
        return;
    }
    if (!c->dt->vkCmdPushDescriptorSetWithTemplate) {
        vkb_srv_missing(c, "vkCmdPushDescriptorSetWithTemplate");
        return;
    }
    c->dt->vkCmdPushDescriptorSetWithTemplate(cb, tmpl, layout, set, data);
}
