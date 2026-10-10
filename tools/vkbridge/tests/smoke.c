/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * droiddeck-vkbridge-smoke: a self-checking Vulkan test, run at session start (Mali bridge on)
 * and on the host against the bridge. Every check compares real bytes; output goes to stdout
 * one line per check, exit status 0 only if all pass.
 *
 *   droiddeck-vkbridge-smoke [--gpu N] [--quiet]
 */
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "smoke_spv.h"

static PFN_vkGetInstanceProcAddr gipa;
static VkInstance inst;
static VkPhysicalDevice pd;
static VkDevice dev;
static VkQueue queue;
static uint32_t qfam;
static VkCommandPool pool;
static VkPhysicalDeviceMemoryProperties mp;
static int failures, checks;

#define I(name) PFN_##name name
static I(vkCreateInstance); static I(vkEnumeratePhysicalDevices); static I(vkGetPhysicalDeviceProperties);
static I(vkGetPhysicalDeviceQueueFamilyProperties); static I(vkGetPhysicalDeviceMemoryProperties); static I(vkCreateDevice);
static I(vkGetDeviceProcAddr); static I(vkDestroyInstance); static I(vkGetPhysicalDeviceFeatures);
static I(vkGetDeviceQueue); static I(vkCreateCommandPool); static I(vkAllocateCommandBuffers); static I(vkBeginCommandBuffer);
static I(vkEndCommandBuffer); static I(vkQueueSubmit); static I(vkQueueWaitIdle); static I(vkCreateBuffer);
static I(vkGetBufferMemoryRequirements); static I(vkAllocateMemory); static I(vkBindBufferMemory); static I(vkMapMemory);
static I(vkCmdFillBuffer); static I(vkCmdCopyBuffer); static I(vkCmdUpdateBuffer); static I(vkCmdPipelineBarrier);
static I(vkCreateShaderModule); static I(vkCreateDescriptorSetLayout); static I(vkCreatePipelineLayout);
static I(vkCreateComputePipelines); static I(vkCreateDescriptorPool); static I(vkAllocateDescriptorSets);
static I(vkUpdateDescriptorSets); static I(vkCmdBindPipeline); static I(vkCmdBindDescriptorSets); static I(vkCmdPushConstants);
static I(vkCmdDispatch); static I(vkCreateImage); static I(vkGetImageMemoryRequirements); static I(vkBindImageMemory);
static I(vkCmdClearColorImage); static I(vkCmdCopyImageToBuffer); static I(vkCreateImageView); static I(vkCreateRenderPass);
static I(vkCreateFramebuffer); static I(vkCreateGraphicsPipelines); static I(vkCmdBeginRenderPass); static I(vkCmdEndRenderPass);
static I(vkCmdDraw); static I(vkCreateSemaphore); static I(vkSignalSemaphore); static I(vkWaitSemaphores);
static I(vkGetSemaphoreCounterValue); static I(vkDestroyDevice); static I(vkFlushMappedMemoryRanges);
static I(vkInvalidateMappedMemoryRanges); static I(vkCmdSetViewport); static I(vkCmdSetScissor);
static I(vkCreateFence); static I(vkWaitForFences); static I(vkCmdCopyBufferToImage);
#undef I

static void check(int ok, const char *what, const char *detail)
{
    checks++;
    if (!ok) failures++;
    printf("%s %s%s%s\n", ok ? "PASS" : "FAIL", what, detail && *detail ? ": " : "", detail ? detail : "");
    fflush(stdout);
}

static double now_ms(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

static uint32_t find_type(uint32_t bits, VkMemoryPropertyFlags want)
{
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & want) == want) return i;
    return UINT32_MAX;
}

typedef struct {
    VkBuffer buf;
    VkDeviceMemory mem;
    void *ptr;
    VkMemoryPropertyFlags flags;
} buffer;

static int make_buffer(buffer *b, VkDeviceSize size, VkBufferUsageFlags usage, VkMemoryPropertyFlags want)
{
    VkBufferCreateInfo ci = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, NULL, 0, size, usage, VK_SHARING_MODE_EXCLUSIVE, 0, NULL};
    if (vkCreateBuffer(dev, &ci, NULL, &b->buf)) return 0;
    VkMemoryRequirements r;
    vkGetBufferMemoryRequirements(dev, b->buf, &r);
    uint32_t t = find_type(r.memoryTypeBits, want);
    if (t == UINT32_MAX) return 0;
    b->flags = mp.memoryTypes[t].propertyFlags;
    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, NULL, r.size, t};
    if (vkAllocateMemory(dev, &ai, NULL, &b->mem) || vkBindBufferMemory(dev, b->buf, b->mem, 0)) return 0;
    b->ptr = NULL;
    if (want & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) {
        if (vkMapMemory(dev, b->mem, 0, VK_WHOLE_SIZE, 0, &b->ptr)) return 0;
    }
    return 1;
}

static void sync_to_gpu(buffer *b)
{
    if (b->flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) return;
    VkMappedMemoryRange r = {VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE, NULL, b->mem, 0, VK_WHOLE_SIZE};
    vkFlushMappedMemoryRanges(dev, 1, &r);
}

static void sync_from_gpu(buffer *b)
{
    if (b->flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) return;
    VkMappedMemoryRange r = {VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE, NULL, b->mem, 0, VK_WHOLE_SIZE};
    vkInvalidateMappedMemoryRanges(dev, 1, &r);
}

static VkCommandBuffer begin(void)
{
    VkCommandBufferAllocateInfo ai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, NULL, pool, VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1};
    VkCommandBuffer cb;
    vkAllocateCommandBuffers(dev, &ai, &cb);
    VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, NULL, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, NULL};
    vkBeginCommandBuffer(cb, &bi);
    return cb;
}

static int submit(VkCommandBuffer cb)
{
    VkMemoryBarrier mb = {VK_STRUCTURE_TYPE_MEMORY_BARRIER, NULL, VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_HOST_READ_BIT};
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0, NULL, 0, NULL);
    if (vkEndCommandBuffer(cb)) return 0;
    VkFenceCreateInfo fci = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VkFence f;
    vkCreateFence(dev, &fci, NULL, &f);
    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, NULL, 0, NULL, NULL, 1, &cb, 0, NULL};
    return !vkQueueSubmit(queue, 1, &si, f) && !vkWaitForFences(dev, 1, &f, VK_TRUE, 10000000000ull);
}

static VkShaderModule shader(const uint32_t *code, size_t size)
{
    VkShaderModuleCreateInfo ci = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, NULL, 0, size, code};
    VkShaderModule m = VK_NULL_HANDLE;
    vkCreateShaderModule(dev, &ci, NULL, &m);
    return m;
}

/* ------------------------------------------------------------------ tests */

static void test_memory(void)
{
    char d[160];
    buffer b;
    const uint32_t n = 1 << 16;
    if (!make_buffer(&b, n * 4, VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                     VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) {
        check(0, "host-visible buffer", "cannot create/map");
        return;
    }
    uint32_t *p = b.ptr;
    for (uint32_t i = 0; i < n; i++) p[i] = i ^ 0x5a5a0000u;
    sync_to_gpu(&b);
    VkCommandBuffer cb = begin();
    vkCmdFillBuffer(cb, b.buf, 0, n, 0xC0FFEE00u);                         /* words [0, n/4) */
    VkBufferCopy c = {n * 2, n * 3, n};                                    /* words [n/2, 3n/4) -> [3n/4, n) */
    vkCmdCopyBuffer(cb, b.buf, b.buf, 1, &c);
    uint32_t upd[16];
    for (int i = 0; i < 16; i++) upd[i] = 0xABC00000u + (uint32_t)i;
    vkCmdUpdateBuffer(cb, b.buf, n + 64, sizeof(upd), upd);                 /* words [n/4 + 16, +16) */
    int ok = submit(cb);
    sync_from_gpu(&b);
    int bad = -1;
    for (uint32_t i = 0; ok && i < n; i++) {
        uint32_t want;
        if (i < n / 4) want = 0xC0FFEE00u;
        else if (i >= n / 4 + 16 && i < n / 4 + 32) want = 0xABC00000u + (i - n / 4 - 16);
        else if (i >= 3 * n / 4) want = (i - n / 4) ^ 0x5a5a0000u;
        else want = i ^ 0x5a5a0000u;
        if (p[i] != want) {
            bad = (int)i;
            break;
        }
    }
    snprintf(d, sizeof(d), bad < 0 ? "fill/copy/update seen through the mapping (memory flags 0x%x)" : "word %d wrong", bad < 0 ? (int)b.flags : bad);
    check(ok && bad < 0, "mapped memory + transfer commands", d);
}

static void test_compute(void)
{
    char d[160];
    const uint32_t n = 4096;
    buffer in, out;
    if (!make_buffer(&in, n * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) ||
        !make_buffer(&out, n * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) {
        check(0, "compute", "buffers");
        return;
    }
    for (uint32_t i = 0; i < n; i++) ((uint32_t *)in.ptr)[i] = i;
    sync_to_gpu(&in);
    VkDescriptorSetLayoutBinding binds[2] = {{0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, NULL},
                                             {1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, NULL}};
    VkDescriptorSetLayoutCreateInfo lci = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, NULL, 0, 2, binds};
    VkDescriptorSetLayout dsl;
    vkCreateDescriptorSetLayout(dev, &lci, NULL, &dsl);
    VkPushConstantRange pcr = {VK_SHADER_STAGE_COMPUTE_BIT, 0, 8};
    VkPipelineLayoutCreateInfo plci = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, NULL, 0, 1, &dsl, 1, &pcr};
    VkPipelineLayout pl;
    vkCreatePipelineLayout(dev, &plci, NULL, &pl);
    VkComputePipelineCreateInfo cpci = {VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    cpci.stage = (VkPipelineShaderStageCreateInfo){VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, NULL, 0, VK_SHADER_STAGE_COMPUTE_BIT,
                                                   shader(spv_smoke_comp, sizeof(spv_smoke_comp)), "main", NULL};
    cpci.layout = pl;
    VkPipeline pipe;
    double t0 = now_ms();
    VkResult r = vkCreateComputePipelines(dev, VK_NULL_HANDLE, 1, &cpci, NULL, &pipe);
    if (r) {
        check(0, "compute pipeline", "creation failed");
        return;
    }
    VkDescriptorPoolSize ps = {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 2};
    VkDescriptorPoolCreateInfo dpci = {VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, NULL, 0, 1, 1, &ps};
    VkDescriptorPool dp;
    vkCreateDescriptorPool(dev, &dpci, NULL, &dp);
    VkDescriptorSetAllocateInfo dsai = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO, NULL, dp, 1, &dsl};
    VkDescriptorSet set;
    vkAllocateDescriptorSets(dev, &dsai, &set);
    VkDescriptorBufferInfo bi[2] = {{in.buf, 0, VK_WHOLE_SIZE}, {out.buf, 0, VK_WHOLE_SIZE}};
    VkWriteDescriptorSet w[2] = {{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, NULL, set, 0, 0, 1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, NULL, &bi[0], NULL},
                                 {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, NULL, set, 1, 0, 1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, NULL, &bi[1], NULL}};
    vkUpdateDescriptorSets(dev, 2, w, 0, NULL);
    VkCommandBuffer cb = begin();
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipe);
    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pl, 0, 1, &set, 0, NULL);
    uint32_t pc[2] = {3, 7};
    vkCmdPushConstants(cb, pl, VK_SHADER_STAGE_COMPUTE_BIT, 0, 8, pc);
    vkCmdDispatch(cb, n / 64, 1, 1);
    int ok = submit(cb);
    sync_from_gpu(&out);
    int bad = -1;
    for (uint32_t i = 0; ok && i < n; i++)
        if (((uint32_t *)out.ptr)[i] != i * 3 + 7) {
            bad = (int)i;
            break;
        }
    snprintf(d, sizeof(d), bad < 0 ? "%u invocations correct (%.1f ms incl. pipeline)" : "element %d wrong", bad < 0 ? n : (unsigned)bad,
             now_ms() - t0);
    check(ok && bad < 0, "compute shader", d);
}

static VkImage make_image(VkFormat fmt, uint32_t w, uint32_t h, VkImageUsageFlags usage)
{
    VkImageCreateInfo ci = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, NULL, 0, VK_IMAGE_TYPE_2D, fmt, {w, h, 1}, 1, 1, VK_SAMPLE_COUNT_1_BIT,
                            VK_IMAGE_TILING_OPTIMAL, usage, VK_SHARING_MODE_EXCLUSIVE, 0, NULL, VK_IMAGE_LAYOUT_UNDEFINED};
    VkImage img;
    if (vkCreateImage(dev, &ci, NULL, &img)) return VK_NULL_HANDLE;
    VkMemoryRequirements r;
    vkGetImageMemoryRequirements(dev, img, &r);
    uint32_t t = find_type(r.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (t == UINT32_MAX) t = find_type(r.memoryTypeBits, 0);
    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, NULL, r.size, t};
    VkDeviceMemory m;
    if (vkAllocateMemory(dev, &ai, NULL, &m) || vkBindImageMemory(dev, img, m, 0)) return VK_NULL_HANDLE;
    return img;
}

static void barrier(VkCommandBuffer cb, VkImage img, VkImageLayout from, VkImageLayout to)
{
    VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, NULL, VK_ACCESS_MEMORY_WRITE_BIT,
                              VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, from, to, VK_QUEUE_FAMILY_IGNORED,
                              VK_QUEUE_FAMILY_IGNORED, img, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, NULL, 0, NULL, 1, &b);
}

static void test_image_clear(void)
{
    const uint32_t w = 64, h = 64;
    VkImage img = make_image(VK_FORMAT_R8G8B8A8_UNORM, w, h, VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
    buffer rb;
    if (!img || !make_buffer(&rb, w * h * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                             VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) {
        check(0, "image clear + readback", "setup");
        return;
    }
    VkCommandBuffer cb = begin();
    barrier(cb, img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    VkClearColorValue col = {.float32 = {1.0f, 0.5f, 0.0f, 1.0f}};
    VkImageSubresourceRange rng = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdClearColorImage(cb, img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &col, 1, &rng);
    /* upload a 8x8 block from the buffer over the corner, to test buffer->image too */
    for (int i = 0; i < 64; i++) ((uint32_t *)rb.ptr)[i] = 0xFF112233u;
    sync_to_gpu(&rb);
    VkBufferImageCopy up = {0, 8, 8, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {0, 0, 0}, {8, 8, 1}};
    vkCmdCopyBufferToImage(cb, rb.buf, img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &up);
    barrier(cb, img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
    VkBufferImageCopy c = {0, 0, 0, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {0, 0, 0}, {w, h, 1}};
    VkMemoryBarrier mb = {VK_STRUCTURE_TYPE_MEMORY_BARRIER, NULL, VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT};
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &mb, 0, NULL, 0, NULL);
    vkCmdCopyImageToBuffer(cb, img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, rb.buf, 1, &c);
    int ok = submit(cb);
    sync_from_gpu(&rb);
    uint32_t *p = rb.ptr;
    uint32_t want_clear = 0xFF0080FFu; /* R=255 G=128 B=0 A=255, little endian; 0.5 -> 127 or 128 */
    int bad = -1;
    for (uint32_t y = 0; ok && y < h; y++)
        for (uint32_t x = 0; x < w; x++) {
            uint32_t v = p[y * w + x];
            int corner = x < 8 && y < 8;
            int good = corner ? v == 0xFF112233u : ((v & 0xFFFF00FFu) == (want_clear & 0xFFFF00FFu) && ((v >> 8) & 0xFF) >= 127 && ((v >> 8) & 0xFF) <= 128);
            if (!good && bad < 0) bad = (int)(y * w + x);
        }
    char d[128];
    snprintf(d, sizeof(d), bad < 0 ? "64x64 RGBA8 clear, upload and readback exact" : "pixel %d = 0x%08x", bad, bad < 0 ? 0 : p[bad]);
    check(ok && bad < 0, "image clear/upload/readback", d);
}

static void test_draw(void)
{
    const uint32_t w = 128, h = 96;
    VkFormat fmt = VK_FORMAT_B8G8R8A8_UNORM;
    VkImage img = make_image(fmt, w, h, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
    buffer rb;
    if (!img || !make_buffer(&rb, w * h * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) {
        check(0, "draw", "setup");
        return;
    }
    VkImageViewCreateInfo vci = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, NULL, 0, img, VK_IMAGE_VIEW_TYPE_2D, fmt, {0},
                                 {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    VkImageView view;
    vkCreateImageView(dev, &vci, NULL, &view);
    VkAttachmentDescription att = {0, fmt, VK_SAMPLE_COUNT_1_BIT, VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE,
                                   VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_DONT_CARE, VK_IMAGE_LAYOUT_UNDEFINED,
                                   VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL};
    VkAttachmentReference ref = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub = {0, VK_PIPELINE_BIND_POINT_GRAPHICS, 0, NULL, 1, &ref, NULL, NULL, 0, NULL};
    VkRenderPassCreateInfo rpci = {VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO, NULL, 0, 1, &att, 1, &sub, 0, NULL};
    VkRenderPass rp;
    vkCreateRenderPass(dev, &rpci, NULL, &rp);
    VkFramebufferCreateInfo fci = {VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO, NULL, 0, rp, 1, &view, w, h, 1};
    VkFramebuffer fb;
    vkCreateFramebuffer(dev, &fci, NULL, &fb);
    VkPushConstantRange pcr = {VK_SHADER_STAGE_FRAGMENT_BIT, 0, 16};
    VkPipelineLayoutCreateInfo plci = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, NULL, 0, 0, NULL, 1, &pcr};
    VkPipelineLayout pl;
    vkCreatePipelineLayout(dev, &plci, NULL, &pl);
    VkPipelineShaderStageCreateInfo st[2] = {
        {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, NULL, 0, VK_SHADER_STAGE_VERTEX_BIT, shader(spv_smoke_vert, sizeof(spv_smoke_vert)), "main", NULL},
        {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, NULL, 0, VK_SHADER_STAGE_FRAGMENT_BIT, shader(spv_smoke_frag, sizeof(spv_smoke_frag)), "main", NULL}};
    VkPipelineVertexInputStateCreateInfo vi = {VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo ia = {VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO, NULL, 0, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, 0};
    VkPipelineViewportStateCreateInfo vp = {VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO, NULL, 0, 1, NULL, 1, NULL};
    VkPipelineRasterizationStateCreateInfo rs = {VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rs.polygonMode = VK_POLYGON_MODE_FILL;
    rs.cullMode = VK_CULL_MODE_NONE;
    rs.lineWidth = 1.0f;
    VkPipelineMultisampleStateCreateInfo ms = {VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO, NULL, 0, VK_SAMPLE_COUNT_1_BIT};
    VkPipelineColorBlendAttachmentState cba = {0};
    cba.colorWriteMask = 0xF;
    VkPipelineColorBlendStateCreateInfo cb_ = {VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO, NULL, 0, 0, 0, 1, &cba, {0}};
    VkDynamicState dyn[2] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo ds = {VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO, NULL, 0, 2, dyn};
    VkGraphicsPipelineCreateInfo gpci = {VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO, NULL, 0, 2, st, &vi, &ia, NULL, &vp, &rs, &ms, NULL, &cb_, &ds,
                                         pl, rp, 0, VK_NULL_HANDLE, -1};
    VkPipeline pipe;
    if (vkCreateGraphicsPipelines(dev, VK_NULL_HANDLE, 1, &gpci, NULL, &pipe)) {
        check(0, "graphics pipeline", "creation failed");
        return;
    }
    VkCommandBuffer cb = begin();
    VkClearValue clear = {.color = {.float32 = {0, 0, 0, 1}}};
    VkRenderPassBeginInfo rbi = {VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO, NULL, rp, fb, {{0, 0}, {w, h}}, 1, &clear};
    vkCmdBeginRenderPass(cb, &rbi, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);
    /* left half only: the right half keeps the clear colour */
    VkViewport v = {0, 0, (float)w, (float)h, 0, 1};
    VkRect2D sc = {{0, 0}, {w / 2, h}};
    vkCmdSetViewport(cb, 0, 1, &v);
    vkCmdSetScissor(cb, 0, 1, &sc);
    float color[4] = {0.0f, 1.0f, 0.0f, 1.0f};
    vkCmdPushConstants(cb, pl, VK_SHADER_STAGE_FRAGMENT_BIT, 0, 16, color);
    vkCmdDraw(cb, 3, 1, 0, 0);
    vkCmdEndRenderPass(cb);
    VkBufferImageCopy c = {0, 0, 0, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {0, 0, 0}, {w, h, 1}};
    vkCmdCopyImageToBuffer(cb, img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, rb.buf, 1, &c);
    int ok = submit(cb);
    sync_from_gpu(&rb);
    uint32_t *p = rb.ptr;
    int bad = -1;
    for (uint32_t y = 0; ok && y < h; y++)
        for (uint32_t x = 0; x < w; x++) {
            uint32_t want = x < w / 2 ? 0xFF00FF00u : 0xFF000000u; /* BGRA: green / black */
            if (p[y * w + x] != want && bad < 0) bad = (int)(y * w + x);
        }
    char d[128];
    snprintf(d, sizeof(d), bad < 0 ? "render pass + pipeline + scissor exact (%ux%u)" : "pixel %d = 0x%08x", bad < 0 ? w : (unsigned)bad,
             bad < 0 ? h : p[bad]);
    check(ok && bad < 0, "draw", d);
}

static void test_timeline(void)
{
    VkSemaphoreTypeCreateInfo t = {VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO, NULL, VK_SEMAPHORE_TYPE_TIMELINE, 5};
    VkSemaphoreCreateInfo ci = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, &t, 0};
    VkSemaphore s;
    if (!vkCreateSemaphore || vkCreateSemaphore(dev, &ci, NULL, &s) || !vkSignalSemaphore) {
        check(1, "timeline semaphore", "skipped (Vulkan 1.2 not available)");
        return;
    }
    VkSemaphoreSignalInfo si = {VK_STRUCTURE_TYPE_SEMAPHORE_SIGNAL_INFO, NULL, s, 9};
    vkSignalSemaphore(dev, &si);
    uint64_t v = 0;
    vkGetSemaphoreCounterValue(dev, s, &v);
    uint64_t want = 9;
    VkSemaphoreWaitInfo wi = {VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO, NULL, 0, 1, &s, &want};
    VkResult r = vkWaitSemaphores(dev, &wi, 1000000000ull);
    char d[64];
    snprintf(d, sizeof(d), "value %llu", (unsigned long long)v);
    check(v == 9 && r == VK_SUCCESS, "timeline semaphore", d);
}

static void test_roundtrip_rate(void)
{
    /* Bridge overhead: how many trivial synchronous calls per second. */
    VkFenceCreateInfo fci = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, NULL, VK_FENCE_CREATE_SIGNALED_BIT};
    VkFence f;
    vkCreateFence(dev, &fci, NULL, &f);
    PFN_vkGetFenceStatus gfs = (PFN_vkGetFenceStatus)vkGetDeviceProcAddr(dev, "vkGetFenceStatus");
    double t0 = now_ms();
    int n = 2000;
    for (int i = 0; i < n; i++) gfs(dev, f);
    double us = (now_ms() - t0) * 1000.0 / n;
    char d[64];
    snprintf(d, sizeof(d), "%.1f us per synchronous call", us);
    check(1, "call latency", d);
}

int main(int argc, char **argv)
{
    int gpu = 0;
    for (int i = 1; i < argc; i++)
        if (!strcmp(argv[i], "--gpu") && i + 1 < argc) gpu = atoi(argv[++i]);
    void *lib = dlopen("libvulkan.so.1", RTLD_NOW);
    if (!lib) {
        printf("FAIL loader: %s\n", dlerror());
        return 2;
    }
    gipa = (PFN_vkGetInstanceProcAddr)dlsym(lib, "vkGetInstanceProcAddr");
#define G(n) n = (PFN_##n)gipa(inst, #n)
    G(vkCreateInstance);
    VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO, NULL, "vkbridge-smoke", 1, "vkbridge", 1, VK_API_VERSION_1_2};
    VkInstanceCreateInfo ici = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, NULL, 0, &app, 0, NULL, 0, NULL};
    VkResult r = vkCreateInstance(&ici, NULL, &inst);
    check(r == VK_SUCCESS, "instance", r ? "vkCreateInstance failed" : "");
    if (r) return 1;
    G(vkEnumeratePhysicalDevices); G(vkGetPhysicalDeviceProperties); G(vkGetPhysicalDeviceQueueFamilyProperties);
    G(vkGetPhysicalDeviceMemoryProperties); G(vkCreateDevice); G(vkGetDeviceProcAddr); G(vkDestroyInstance);
    G(vkGetPhysicalDeviceFeatures);
    uint32_t n = 0;
    vkEnumeratePhysicalDevices(inst, &n, NULL);
    VkPhysicalDevice pds[8];
    if (n > 8) n = 8;
    vkEnumeratePhysicalDevices(inst, &n, pds);
    if ((uint32_t)gpu >= n) {
        check(0, "physical device", "none");
        return 1;
    }
    pd = pds[gpu];
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(pd, &props);
    char d[300];
    snprintf(d, sizeof(d), "%s, Vulkan %u.%u.%u", props.deviceName, VK_API_VERSION_MAJOR(props.apiVersion),
             VK_API_VERSION_MINOR(props.apiVersion), VK_API_VERSION_PATCH(props.apiVersion));
    check(1, "physical device", d);
    vkGetPhysicalDeviceMemoryProperties(pd, &mp);
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
        printf("     memory type %u: heap %u flags 0x%x\n", i, mp.memoryTypes[i].heapIndex, mp.memoryTypes[i].propertyFlags);
    uint32_t nq = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, NULL);
    VkQueueFamilyProperties qp[16];
    if (nq > 16) nq = 16;
    vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, qp);
    for (qfam = 0; qfam < nq; qfam++)
        if (qp[qfam].queueFlags & VK_QUEUE_GRAPHICS_BIT) break;
    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, NULL, 0, qfam, 1, &prio};
    VkPhysicalDeviceVulkan12Features f12 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    f12.timelineSemaphore = VK_TRUE;
    VkDeviceCreateInfo dci = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, props.apiVersion >= VK_API_VERSION_1_2 ? &f12 : NULL, 0, 1, &qci, 0, NULL, 0, NULL, NULL};
    r = vkCreateDevice(pd, &dci, NULL, &dev);
    check(r == VK_SUCCESS, "device", r ? "vkCreateDevice failed" : "");
    if (r) return 1;
#undef G
#define G(n) n = (PFN_##n)vkGetDeviceProcAddr(dev, #n)
    G(vkGetDeviceQueue); G(vkCreateCommandPool); G(vkAllocateCommandBuffers); G(vkBeginCommandBuffer); G(vkEndCommandBuffer);
    G(vkQueueSubmit); G(vkQueueWaitIdle); G(vkCreateBuffer); G(vkGetBufferMemoryRequirements); G(vkAllocateMemory);
    G(vkBindBufferMemory); G(vkMapMemory); G(vkCmdFillBuffer); G(vkCmdCopyBuffer); G(vkCmdUpdateBuffer); G(vkCmdPipelineBarrier);
    G(vkCreateShaderModule); G(vkCreateDescriptorSetLayout); G(vkCreatePipelineLayout); G(vkCreateComputePipelines);
    G(vkCreateDescriptorPool); G(vkAllocateDescriptorSets); G(vkUpdateDescriptorSets); G(vkCmdBindPipeline);
    G(vkCmdBindDescriptorSets); G(vkCmdPushConstants); G(vkCmdDispatch); G(vkCreateImage); G(vkGetImageMemoryRequirements);
    G(vkBindImageMemory); G(vkCmdClearColorImage); G(vkCmdCopyImageToBuffer); G(vkCreateImageView); G(vkCreateRenderPass);
    G(vkCreateFramebuffer); G(vkCreateGraphicsPipelines); G(vkCmdBeginRenderPass); G(vkCmdEndRenderPass); G(vkCmdDraw);
    G(vkCreateSemaphore); G(vkSignalSemaphore); G(vkWaitSemaphores); G(vkGetSemaphoreCounterValue); G(vkDestroyDevice);
    G(vkFlushMappedMemoryRanges); G(vkInvalidateMappedMemoryRanges); G(vkCmdSetViewport); G(vkCmdSetScissor);
    G(vkCreateFence); G(vkWaitForFences); G(vkCmdCopyBufferToImage);
#undef G
    vkGetDeviceQueue(dev, qfam, 0, &queue);
    VkCommandPoolCreateInfo pci = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, NULL, 0, qfam};
    vkCreateCommandPool(dev, &pci, NULL, &pool);

    test_memory();
    test_compute();
    test_image_clear();
    test_draw();
    test_timeline();
    test_roundtrip_rate();

    vkDestroyDevice(dev, NULL);
    vkDestroyInstance(inst, NULL);
    printf("%s: %d of %d checks passed\n", failures ? "FAILED" : "OK", checks - failures, checks);
    return failures ? 1 : 0;
}
