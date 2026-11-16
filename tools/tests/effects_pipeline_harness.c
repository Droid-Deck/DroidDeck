#define _POSIX_C_SOURCE 200809L
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define VK_PRESENT_H
static unsigned errors;
const char *vkp_gpu_name(void) { return "pipeline test"; }
void droiddeck_log(const char *tag, const char *fmt, ...) {
    (void)fmt;
    if (!strcmp(tag, "error")) errors++;
}
#include "vk_loader.h"
struct vk_api g_vk;
#include "effects_chain.c"

enum { FXAA = 1, TOON = 2, COLOR = 4, CAS = 8, HDR = 16, NTSC = 32, CRT = 64, DEBAND = 128 };
enum { MAX_HANDLES = 4096 };
struct resource {
    char type;
    int kind, w, h;
    VkFormat format;
    uintptr_t parent;
};
static struct resource resources[MAX_HANDLES];
static unsigned image_ids[MAX_HANDLES], next_image_id, next_handle = 1000;
static unsigned pipeline_count, pipeline_attempts, draws, blits, renderpasses, order[256];
static int failed_pipeline = -1, failed_shader = -1;
static char failed_shared;
static int update_during_frame;
static VkFilter last_filter;
static uintptr_t bound_pipeline, bound_framebuffer;
static uint64_t trace = 14695981039346656037ull;
static const uint32_t *fragment_codes[P_COUNT] = {
    sgsr_code, sgsr_quality_code, nis_code, fsr_easu_code, fsr_rcas_code, cas_code, color_code,
    fxaa_code, toon_code, hdr_code, ntsc_code, crt_code, deband_code,
};

static uintptr_t handle(char type) {
    assert(next_handle < MAX_HANDLES);
    resources[next_handle].type = type;
    return next_handle++;
}
static void drop(uintptr_t object, char type) {
    assert(object < MAX_HANDLES && resources[object].type == type);
    memset(&resources[object], 0, sizeof(resources[object]));
}
static void hash(uint64_t value) {
    for (unsigned i = 0; i < 8; i++) {
        trace = (trace ^ (value & 255)) * 1099511628211ull;
        value >>= 8;
    }
}
static void hash_bytes(const void *data, size_t size) {
    const uint8_t *bytes = data;
    for (size_t i = 0; i < size; i++) hash(bytes[i]);
}
static void hash_image(uintptr_t image) {
    assert(resources[image].type == 'I' || resources[image].type == 'E');
    if (!image_ids[image]) image_ids[image] = ++next_image_id;
    hash(image_ids[image]);
    hash(resources[image].format); hash(resources[image].w); hash(resources[image].h);
}

#define CREATE(fn, Info, Type, tag) \
static VkResult VKAPI_CALL fn(VkDevice d, const Info *i, const VkAllocationCallbacks *a, Type *out) { \
    (void)d; (void)i; (void)a; \
    if (failed_shared == tag) return VK_ERROR_INITIALIZATION_FAILED; \
    *out = (Type)handle(tag); return VK_SUCCESS; \
}
#define DESTROY(fn, Type, tag) \
static void VKAPI_CALL fn(VkDevice d, Type value, const VkAllocationCallbacks *a) { \
    (void)d; (void)a; drop((uintptr_t)value, tag); \
}
CREATE(create_sampler, VkSamplerCreateInfo, VkSampler, 'S')
CREATE(create_layout, VkDescriptorSetLayoutCreateInfo, VkDescriptorSetLayout, 'L')
CREATE(create_pipeline_layout, VkPipelineLayoutCreateInfo, VkPipelineLayout, 'P')
CREATE(create_pool, VkDescriptorPoolCreateInfo, VkDescriptorPool, 'D')
CREATE(allocate_memory, VkMemoryAllocateInfo, VkDeviceMemory, 'A')
DESTROY(destroy_sampler, VkSampler, 'S')
DESTROY(destroy_layout, VkDescriptorSetLayout, 'L')
DESTROY(destroy_pipeline_layout, VkPipelineLayout, 'P')
DESTROY(destroy_pool, VkDescriptorPool, 'D')
DESTROY(destroy_module, VkShaderModule, 'M')
DESTROY(destroy_pipeline, VkPipeline, 'G')
DESTROY(destroy_renderpass, VkRenderPass, 'R')
DESTROY(destroy_image, VkImage, 'I')
DESTROY(destroy_view, VkImageView, 'V')
DESTROY(destroy_framebuffer, VkFramebuffer, 'F')
DESTROY(free_memory, VkDeviceMemory, 'A')

static VkResult VKAPI_CALL create_renderpass(VkDevice d, const VkRenderPassCreateInfo *info,
                                             const VkAllocationCallbacks *a, VkRenderPass *out) {
    (void)d; (void)a;
    if (failed_shared == 'R') return VK_ERROR_INITIALIZATION_FAILED;
    *out = (VkRenderPass)handle('R');
    resources[(uintptr_t)*out].format = info->pAttachments[0].format;
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL create_module(VkDevice d, const VkShaderModuleCreateInfo *info,
                                         const VkAllocationCallbacks *a, VkShaderModule *out) {
    (void)d; (void)a;
    int kind = P_COUNT;
    if (info->pCode != upscale_vert_code) {
        for (kind = 0; kind < P_COUNT && info->pCode != fragment_codes[kind]; kind++) {}
        assert(kind < P_COUNT);
    }
    if (failed_shader == kind) return VK_ERROR_INITIALIZATION_FAILED;
    *out = (VkShaderModule)handle('M');
    resources[(uintptr_t)*out].kind = kind;
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL create_pipelines(VkDevice d, VkPipelineCache cache, uint32_t count,
                                           const VkGraphicsPipelineCreateInfo *info,
                                           const VkAllocationCallbacks *a, VkPipeline *out) {
    (void)d; (void)cache; (void)a;
    assert(count == 1 && info->stageCount == 2);
    const int kind = resources[(uintptr_t)info->pStages[1].module].kind;
    pipeline_attempts++;
    if (failed_pipeline == kind) return VK_ERROR_INITIALIZATION_FAILED;
    *out = (VkPipeline)handle('G');
    resources[(uintptr_t)*out].kind = kind;
    resources[(uintptr_t)*out].format = resources[(uintptr_t)info->renderPass].format;
    pipeline_count++;
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL create_image(VkDevice d, const VkImageCreateInfo *info,
                                        const VkAllocationCallbacks *a, VkImage *out) {
    (void)d; (void)a;
    *out = (VkImage)handle('I');
    resources[(uintptr_t)*out].format = info->format;
    resources[(uintptr_t)*out].w = (int)info->extent.width;
    resources[(uintptr_t)*out].h = (int)info->extent.height;
    return VK_SUCCESS;
}
static void VKAPI_CALL requirements(VkDevice d, VkImage image, VkMemoryRequirements *out) {
    (void)d;
    assert(resources[(uintptr_t)image].type == 'I');
    *out = (VkMemoryRequirements){4096, 1, 1};
}
static VkResult VKAPI_CALL bind_memory(VkDevice d, VkImage image, VkDeviceMemory memory, VkDeviceSize offset) {
    (void)d; (void)offset;
    assert(resources[(uintptr_t)image].type == 'I' && resources[(uintptr_t)memory].type == 'A');
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL create_view(VkDevice d, const VkImageViewCreateInfo *info,
                                       const VkAllocationCallbacks *a, VkImageView *out) {
    (void)d; (void)a;
    assert(info->image);
    *out = (VkImageView)handle('V');
    resources[(uintptr_t)*out].parent = (uintptr_t)info->image;
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL create_framebuffer(VkDevice d, const VkFramebufferCreateInfo *info,
                                              const VkAllocationCallbacks *a, VkFramebuffer *out) {
    (void)d; (void)a;
    assert(info->attachmentCount == 1);
    *out = (VkFramebuffer)handle('F');
    resources[(uintptr_t)*out].parent = (uintptr_t)info->pAttachments[0];
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL allocate_set(VkDevice d, const VkDescriptorSetAllocateInfo *info, VkDescriptorSet *out) {
    (void)d;
    assert(info->descriptorSetCount == 1 && resources[(uintptr_t)info->descriptorPool].type == 'D');
    *out = (VkDescriptorSet)handle('T');
    return VK_SUCCESS;
}
static void VKAPI_CALL update_set(VkDevice d, uint32_t count, const VkWriteDescriptorSet *writes,
                                  uint32_t copies, const VkCopyDescriptorSet *copy) {
    (void)d; (void)copies; (void)copy;
    assert(count == 1);
    assert(resources[(uintptr_t)writes->dstSet].type == 'T');
    resources[(uintptr_t)writes->dstSet].parent = (uintptr_t)writes->pImageInfo->imageView;
}
static VkResult VKAPI_CALL free_set(VkDevice d, VkDescriptorPool pool, uint32_t count, const VkDescriptorSet *set) {
    (void)d;
    assert(count == 1 && resources[(uintptr_t)pool].type == 'D');
    drop((uintptr_t)*set, 'T');
    return VK_SUCCESS;
}
static void VKAPI_CALL begin_pass(VkCommandBuffer cmd, const VkRenderPassBeginInfo *info, VkSubpassContents contents) {
    (void)cmd; (void)contents;
    bound_framebuffer = (uintptr_t)info->framebuffer;
    assert(resources[bound_framebuffer].type == 'F');
    hash(1); hash_image(resources[resources[bound_framebuffer].parent].parent);
    hash_bytes(&info->renderArea, sizeof(info->renderArea));
    renderpasses++;
}
static void VKAPI_CALL viewport(VkCommandBuffer cmd, uint32_t first, uint32_t count, const VkViewport *value) {
    (void)cmd; (void)first;
    assert(count == 1); hash(2); hash_bytes(value, sizeof(*value));
}
static void VKAPI_CALL scissor(VkCommandBuffer cmd, uint32_t first, uint32_t count, const VkRect2D *value) {
    (void)cmd; (void)first;
    assert(count == 1); hash(3); hash_bytes(value, sizeof(*value));
}
static void VKAPI_CALL bind_pipeline(VkCommandBuffer cmd, VkPipelineBindPoint point, VkPipeline pipeline) {
    (void)cmd; (void)point;
    bound_pipeline = (uintptr_t)pipeline;
    assert(resources[bound_pipeline].type == 'G' && resources[bound_pipeline].format == g_fx_fmt);
    hash(4); hash(resources[bound_pipeline].kind);
}
static void VKAPI_CALL bind_set(VkCommandBuffer cmd, VkPipelineBindPoint point, VkPipelineLayout layout,
                                uint32_t first, uint32_t count, const VkDescriptorSet *set,
                                uint32_t offsets, const uint32_t *dynamic) {
    (void)cmd; (void)point; (void)layout; (void)first; (void)offsets; (void)dynamic;
    assert(count == 1 && resources[(uintptr_t)*set].type == 'T');
    hash(5); hash_image(resources[resources[(uintptr_t)*set].parent].parent);
}
static void VKAPI_CALL constants(VkCommandBuffer cmd, VkPipelineLayout layout, VkShaderStageFlags stages,
                                 uint32_t offset, uint32_t size, const void *value) {
    (void)cmd; (void)layout;
    hash(6); hash(stages); hash(offset); hash(size); hash_bytes(value, size);
}
static void VKAPI_CALL draw(VkCommandBuffer cmd, uint32_t vertices, uint32_t instances,
                            uint32_t first, uint32_t first_instance) {
    (void)cmd;
    assert(draws < sizeof(order) / sizeof(*order));
    order[draws++] = (unsigned)resources[bound_pipeline].kind;
    hash(7); hash(vertices); hash(instances); hash(first); hash(first_instance);
}
static void VKAPI_CALL end_pass(VkCommandBuffer cmd) { (void)cmd; hash(8); }
static void VKAPI_CALL pipeline_barrier(VkCommandBuffer cmd, VkPipelineStageFlags src, VkPipelineStageFlags dst,
                                        VkDependencyFlags flags, uint32_t nm, const VkMemoryBarrier *memory,
                                        uint32_t nb, const VkBufferMemoryBarrier *buffer,
                                        uint32_t ni, const VkImageMemoryBarrier *image) {
    (void)cmd; (void)memory; (void)buffer;
    assert(nm == 0 && nb == 0);
    if (update_during_frame) {
        update_during_frame = 0;
        vkp_effects_set_scaling(2);
        vkp_effects_set_screen(0, 0, 1, 100, 0, 1, 0, 0);
    }
    hash(9); hash(src); hash(dst); hash(flags); hash(ni);
    for (uint32_t i = 0; i < ni; i++) {
        hash_image((uintptr_t)image[i].image);
        hash(image[i].oldLayout); hash(image[i].newLayout);
        hash(image[i].srcAccessMask); hash(image[i].dstAccessMask);
    }
}
static void VKAPI_CALL blit(VkCommandBuffer cmd, VkImage src, VkImageLayout sl, VkImage dst,
                            VkImageLayout dl, uint32_t count, const VkImageBlit *region, VkFilter filter) {
    (void)cmd;
    assert(count == 1);
    hash(10); hash_image((uintptr_t)src); hash_image((uintptr_t)dst); hash(sl); hash(dl); hash(filter);
    hash_bytes(region, sizeof(*region)); blits++; last_filter = filter;
}

static void select_effects(int mask) {
    vkp_effects_set_screen(mask & COLOR ? 4 : 0, mask & COLOR ? 112 : 0,
                          mask & COLOR ? 1.1f : 1.0f, mask & COLOR ? 110 : 100,
                          mask & FXAA, mask & TOON, mask & CRT, mask & NTSC);
    vkp_effects_set_cas(mask & CAS, 60);
    vkp_effects_set_hdr(mask & HDR);
    vkp_effects_set_deband(mask & DEBAND, 100);
}
static VkImage render(int width, int height, int out_w, int out_h) {
    resources[10] = (struct resource){.type = 'E', .w = width, .h = height, .format = g_scene_fmt};
    int rw, rh;
    VkImage result = vkp_effects_run((VkCommandBuffer)(uintptr_t)1, (VkImage)(uintptr_t)10,
                                   width, height, out_w, out_h, &rw, &rh);
    hash(11); hash_image((uintptr_t)result); hash(rw); hash(rh);
    return result;
}
static void setup(void) {
    g_vk.CreateRenderPass = create_renderpass; g_vk.DestroyRenderPass = destroy_renderpass;
    g_vk.CreateSampler = create_sampler; g_vk.DestroySampler = destroy_sampler;
    g_vk.CreateDescriptorSetLayout = create_layout; g_vk.DestroyDescriptorSetLayout = destroy_layout;
    g_vk.CreatePipelineLayout = create_pipeline_layout; g_vk.DestroyPipelineLayout = destroy_pipeline_layout;
    g_vk.CreateDescriptorPool = create_pool; g_vk.DestroyDescriptorPool = destroy_pool;
    g_vk.CreateShaderModule = create_module; g_vk.DestroyShaderModule = destroy_module;
    g_vk.CreateGraphicsPipelines = create_pipelines; g_vk.DestroyPipeline = destroy_pipeline;
    g_vk.CreateImage = create_image; g_vk.DestroyImage = destroy_image;
    g_vk.GetImageMemoryRequirements = requirements; g_vk.BindImageMemory = bind_memory;
    g_vk.AllocateMemory = allocate_memory; g_vk.FreeMemory = free_memory;
    g_vk.CreateImageView = create_view; g_vk.DestroyImageView = destroy_view;
    g_vk.CreateFramebuffer = create_framebuffer; g_vk.DestroyFramebuffer = destroy_framebuffer;
    g_vk.AllocateDescriptorSets = allocate_set; g_vk.UpdateDescriptorSets = update_set; g_vk.FreeDescriptorSets = free_set;
    g_vk.CmdBeginRenderPass = begin_pass; g_vk.CmdEndRenderPass = end_pass;
    g_vk.CmdSetViewport = viewport; g_vk.CmdSetScissor = scissor;
    g_vk.CmdBindPipeline = bind_pipeline; g_vk.CmdBindDescriptorSets = bind_set;
    g_vk.CmdPushConstants = constants; g_vk.CmdDraw = draw;
    g_vk.CmdPipelineBarrier = pipeline_barrier; g_vk.CmdBlitImage = blit;
    VkPhysicalDeviceMemoryProperties memory = {.memoryTypeCount = 1};
    memory.memoryTypes[0].propertyFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
    vkp_effects_bind_device((VkDevice)(uintptr_t)1, (VkPhysicalDevice)(uintptr_t)2, &memory);
}
static void cleanup(void) {
    vkp_effects_destroy();
    for (unsigned i = 1000; i < next_handle; i++) assert(!resources[i].type);
}

int main(int argc, char **argv) {
    setup();
    if (argc == 3 && (!strcmp(argv[1], "pipeline-failure") || !strcmp(argv[1], "shader-failure"))) {
        const int kind = atoi(argv[2]);
        const int scaling[P_COUNT] = {3, 8, 7, 4, 4, 0, 0, 0, 0, 0, 0, 0, 0};
        const int masks[P_COUNT] = {0, 0, 0, 0, 0, CAS, COLOR, FXAA, TOON, HDR, NTSC, CRT, DEBAND};
        assert(kind >= 0 && kind < P_COUNT);
        vkp_effects_set_scaling(scaling[kind]);
        select_effects(masks[kind]);
        if (!strcmp(argv[1], "pipeline-failure")) failed_pipeline = kind;
        else failed_shader = kind;
        assert(render(1280, 720, 1920, 1080) == (VkImage)(uintptr_t)10);
        assert(!draws && !renderpasses && errors);
        const unsigned attempts = pipeline_attempts;
        render(1280, 720, 1920, 1080);
        assert(pipeline_attempts == attempts && !draws);
        cleanup();
        puts("PASS");
        return 0;
    }
    if (argc == 8 && !strcmp(argv[1], "trace")) {
        vkp_effects_set_scaling(atoi(argv[2]));
        select_effects(atoi(argv[3]));
        const VkFormat formats[] = {FX_FORMAT, VK_FORMAT_A2B10G10R10_UNORM_PACK32, VK_FORMAT_R16G16B16A16_SFLOAT};
        const int fmt = atoi(argv[7]);
        assert(fmt >= 0 && fmt < 3);
        vkp_effects_set_formats(formats[fmt], formats[fmt]);
        for (int i = 0; i < 2; i++) render(135, 73, atoi(argv[4]), atoi(argv[5]));
        assert((int)draws == atoi(argv[6]));
        printf("PASS pipelines=%u draws=%u blits=%u trace=%016llx\n",
               pipeline_count, draws, blits, (unsigned long long)trace);
        cleanup();
        return 0;
    }
    assert(argc == 2);
    const char *test = argv[1];
    if (!strcmp(test, "off") || !strcmp(test, "zero-cas")) {
        select_effects(0);
        if (!strcmp(test, "zero-cas")) vkp_effects_set_cas(1, 0);
        assert(render(1280, 720, 1920, 1080) == (VkImage)(uintptr_t)10);
        assert(!pipeline_count && !draws);
    } else if (!strcmp(test, "cas") || !strcmp(test, "resize") || !strcmp(test, "source-format")) {
        select_effects(CAS);
        for (int i = 0; i < 3; i++) {
            if (!strcmp(test, "source-format")) vkp_effects_set_formats(
                i & 1 ? VK_FORMAT_R16G16B16A16_SFLOAT : FX_FORMAT, FX_FORMAT);
            render(1280 + (!strcmp(test, "resize") ? i * 17 : 0), 720, 1280, 720);
            assert(pipeline_count == 1 && order[i] == P_CAS);
        }
    } else if (!strcmp(test, "switch")) {
        select_effects(CAS); render(1280, 720, 1280, 720);
        assert(pipeline_count == 1);
        select_effects(CAS | COLOR); render(1280, 720, 1280, 720);
        assert(pipeline_count == 2 && order[1] == P_COLOR && order[2] == P_CAS);
        select_effects(0); render(1280, 720, 1280, 720);
        select_effects(CAS); render(1280, 720, 1280, 720);
        assert(pipeline_count == 2);
    } else if (!strcmp(test, "scaling-switch")) {
        const int modes[] = {3, 4, 5, 6, 7, 8, 3};
        const unsigned counts[] = {1, 3, 3, 3, 4, 5, 5};
        select_effects(0);
        for (unsigned i = 0; i < sizeof(modes) / sizeof(*modes); i++) {
            vkp_effects_set_scaling(modes[i]);
            render(1280, 720, 1920, 1080);
            assert(pipeline_count == counts[i]);
        }
    } else if (!strcmp(test, "settings-snapshot")) {
        select_effects(CAS);
        update_during_frame = 1;
        render(1280, 720, 1920, 1080);
        assert(draws == 1 && order[0] == P_CAS && pipeline_count == 1 && last_filter == VK_FILTER_LINEAR);
        render(1280, 720, 1920, 1080);
        assert(draws == 3 && order[1] == P_TOON && order[2] == P_CAS &&
               pipeline_count == 2 && last_filter == VK_FILTER_NEAREST);
    } else if (!strcmp(test, "device-reset")) {
        select_effects(CAS); render(1280, 720, 1280, 720);
        cleanup();
        setup();
        render(1280, 720, 1280, 720);
        assert(pipeline_count == 2 && draws == 2);
    } else if (!strcmp(test, "formats")) {
        const VkFormat formats[] = {FX_FORMAT, VK_FORMAT_A2B10G10R10_UNORM_PACK32, VK_FORMAT_R16G16B16A16_SFLOAT, FX_FORMAT};
        select_effects(CAS);
        for (unsigned i = 0; i < 4; i++) {
            vkp_effects_set_formats(formats[i], formats[i]);
            render(1280, 720, 1280, 720);
            assert(pipeline_count == i + 1);
        }
    } else if (!strcmp(test, "unused-failure")) {
        failed_pipeline = P_NTSC;
        select_effects(CAS);
        assert(render(1280, 720, 1280, 720) != (VkImage)(uintptr_t)10);
        assert(pipeline_attempts == 1 && pipeline_count == 1 && draws == 1 && !errors);
    } else {
        if (!strcmp(test, "late-failure")) {
            select_effects(CAS); render(1280, 720, 1280, 720);
        }
        const unsigned before_draws = draws;
        select_effects(CAS | TOON);
        if (!strcmp(test, "fragment-failure")) failed_shader = P_TOON;
        else if (!strcmp(test, "vertex-failure")) failed_shader = P_COUNT;
        else if (!strcmp(test, "shared-failure")) failed_shared = 'R';
        else {
            assert(!strcmp(test, "required-failure") || !strcmp(test, "late-failure") ||
                   !strcmp(test, "failure-recovery"));
            failed_pipeline = P_TOON;
        }
        assert(render(1280, 720, 1280, 720) == (VkImage)(uintptr_t)10);
        assert(draws == before_draws && renderpasses == before_draws && errors);
        const unsigned attempts = pipeline_attempts;
        render(1280, 720, 1280, 720);
        assert(pipeline_attempts == attempts && draws == before_draws);
        if (!strcmp(test, "failure-recovery")) {
            failed_pipeline = -1;
            vkp_effects_set_formats(VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT);
            select_effects(CAS); render(1280, 720, 1280, 720);
            assert(draws == before_draws + 1);
        }
    }
    cleanup();
    puts("PASS");
    return 0;
}
