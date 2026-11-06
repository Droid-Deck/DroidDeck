#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define VK_PRESENT_H
const char *vkp_gpu_name(void) { return "cache test"; }
void droiddeck_log(const char *tag, const char *fmt, ...) { (void)tag; (void)fmt; }
#include "vk_loader.h"
struct vk_api g_vk;
#include "effects_chain.c"

static unsigned views, sets, freed_views, freed_sets;
static VkImageView bound_view;
static VkImage target_image;
static unsigned target_events, target_forgot_at, target_destroyed_at;

void blendp_forget_image(VkImage image) {
    assert(image == target_image && target_forgot_at == 0);
    target_forgot_at = ++target_events;
}

static void VKAPI_CALL destroy_image(VkDevice dev, VkImage image, const VkAllocationCallbacks *alloc) {
    (void)dev; (void)alloc;
    assert(image == target_image && target_forgot_at > 0);
    target_destroyed_at = ++target_events;
}

static void check_blend_cache_target_lifetime(void) {
    target_image = (VkImage)(uintptr_t)50;
    struct fx_target target = {.img = target_image};
    g_vk.DestroyImage = destroy_image;
    target_destroy(&target);
    assert(target_forgot_at == 1 && target_destroyed_at == 2);
    assert(target.img == VK_NULL_HANDLE);
    target_destroy(&target);
    assert(target_events == 2);
}

static VkResult VKAPI_CALL create_view(VkDevice dev, const VkImageViewCreateInfo *info,
                                      const VkAllocationCallbacks *alloc, VkImageView *out) {
    (void)dev; (void)alloc;
    assert(info->image != VK_NULL_HANDLE);
    *out = (VkImageView)(uintptr_t)(100 + ++views);
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL allocate_set(VkDevice dev, const VkDescriptorSetAllocateInfo *info,
                                       VkDescriptorSet *out) {
    (void)dev;
    assert(info->descriptorPool != VK_NULL_HANDLE && info->descriptorSetCount == 1);
    *out = (VkDescriptorSet)(uintptr_t)(200 + ++sets);
    return VK_SUCCESS;
}

static VkImageLayout restored_layout;
static VkPipelineStageFlags restored_stage;
static void VKAPI_CALL pipeline_barrier(VkCommandBuffer cmd, VkPipelineStageFlags src, VkPipelineStageFlags dst,
    VkDependencyFlags flags, uint32_t nm, const VkMemoryBarrier *m, uint32_t nb,
    const VkBufferMemoryBarrier *b, uint32_t ni, const VkImageMemoryBarrier *images) {
    for (uint32_t k = 0; k < ni; k++) {
        if (images[k].image == (VkImage)(uintptr_t)10 && images[k].newLayout == VK_IMAGE_LAYOUT_GENERAL) {
            restored_layout = images[k].oldLayout;
            restored_stage = src;
        }
    }
}
static void VKAPI_CALL begin_pass(VkCommandBuffer c, const VkRenderPassBeginInfo *i, VkSubpassContents s) {}
static void VKAPI_CALL viewport(VkCommandBuffer c, uint32_t f, uint32_t n, const VkViewport *v) {}
static void VKAPI_CALL scissor(VkCommandBuffer c, uint32_t f, uint32_t n, const VkRect2D *v) {}
static void VKAPI_CALL bind_pipeline(VkCommandBuffer c, VkPipelineBindPoint b, VkPipeline p) {}
static void VKAPI_CALL bind_sets(VkCommandBuffer c, VkPipelineBindPoint b, VkPipelineLayout l, uint32_t f,
    uint32_t n, const VkDescriptorSet *s, uint32_t nd, const uint32_t *d) {}
static void VKAPI_CALL push(VkCommandBuffer c, VkPipelineLayout l, VkShaderStageFlags s, uint32_t o,
    uint32_t n, const void *v) {}
static void VKAPI_CALL draw(VkCommandBuffer c, uint32_t n, uint32_t i, uint32_t f, uint32_t fi) {}
static void VKAPI_CALL end_pass(VkCommandBuffer c) {}
static void VKAPI_CALL blit(VkCommandBuffer c, VkImage a, VkImageLayout al, VkImage b, VkImageLayout bl,
    uint32_t n, const VkImageBlit *r, VkFilter f) {}

static void check_generated_settings_and_layout(void) {
    g_vk.CmdPipelineBarrier = pipeline_barrier;
    g_vk.CmdBeginRenderPass = begin_pass;
    g_vk.CmdSetViewport = viewport; g_vk.CmdSetScissor = scissor;
    g_vk.CmdBindPipeline = bind_pipeline; g_vk.CmdBindDescriptorSets = bind_sets;
    g_vk.CmdPushConstants = push; g_vk.CmdDraw = draw; g_vk.CmdEndRenderPass = end_pass;
    g_vk.CmdBlitImage = blit;
    g_ready = 1;
    for (int k = 0; k < 2; k++) {
        struct fx_target *t = &g_contexts[1].targets[k];
        t->img = (VkImage)(uintptr_t)(30 + k); t->w = 1920; t->h = 1080;
    }
    vkp_effects_set_scaling(3);
    assert(vkp_effects_prepare_generated());
    vkp_effects_set_scaling(0);
    vkp_effects_set_cas(1, 75);
    vkp_effects_sync();
    assert(g_cur.scaling == 0 && cas_active(&g_cur));
    assert(g_generated_settings.scaling == 3 && !effect_count(&g_generated_settings));
    int w, h;
    VkImage input = (VkImage)(uintptr_t)10;
    assert(vkp_effects_run_before_generated(VK_NULL_HANDLE, input, 1280, 720, &w, &h) == input);
    assert(w == 1280 && h == 720);
    assert(vkp_effects_run_generated(VK_NULL_HANDLE, input, 1280, 720, 1920, 1080, 0, &w, &h) != input);
    assert(w == 1920 && h == 1080);
    assert(restored_layout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    assert(restored_stage == VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
    g_generated_settings.scaling = 0;
    g_generated_settings.color_on = 1;
    assert(vkp_effects_run_generated(VK_NULL_HANDLE, input, 1280, 720, 1920, 1080, 0, &w, &h) != input);
    assert(restored_layout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
    assert(restored_stage == VK_PIPELINE_STAGE_TRANSFER_BIT);
    g_ready = -1;
    assert(vkp_effects_run_generated(VK_NULL_HANDLE, input, 1280, 720, 1920, 1080, 0, &w, &h) == input);
    assert(w == 1280 && h == 720);
    assert(restored_layout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
    assert(g_context == 0);
}
static void VKAPI_CALL update_set(VkDevice dev, uint32_t count, const VkWriteDescriptorSet *writes,
                                 uint32_t copies, const VkCopyDescriptorSet *copy) {
    (void)dev; (void)copies; (void)copy;
    assert(count == 1);
    bound_view = writes->pImageInfo->imageView;
}
static VkResult VKAPI_CALL free_set(VkDevice dev, VkDescriptorPool pool, uint32_t count,
                                   const VkDescriptorSet *set) {
    (void)dev; (void)pool;
    assert(pool != VK_NULL_HANDLE && count == 1 && *set != VK_NULL_HANDLE);
    freed_sets++;
    return VK_SUCCESS;
}
static void VKAPI_CALL destroy_view(VkDevice dev, VkImageView view, const VkAllocationCallbacks *alloc) {
    (void)dev; (void)alloc;
    assert(view != VK_NULL_HANDLE);
    freed_views++;
}

int main(int argc, char **argv) {
    assert(argc == 2);
    g_dev = (VkDevice)(uintptr_t)1;
    g_dpool = (VkDescriptorPool)(uintptr_t)2;
    g_dsl = (VkDescriptorSetLayout)(uintptr_t)3;
    g_sampler = (VkSampler)(uintptr_t)4;
    g_vk.CreateImageView = create_view;
    g_vk.AllocateDescriptorSets = allocate_set;
    g_vk.UpdateDescriptorSets = update_set;
    g_vk.FreeDescriptorSets = free_set;
    g_vk.DestroyImageView = destroy_view;
    VkImage source = (VkImage)(uintptr_t)10;
    assert(scene_ensure(source, 1280, 720) == 0);
    VkImageView original = bound_view;
    if (!strcmp(argv[1], "generated-layout")) {
        check_generated_settings_and_layout();
    } else if (!strcmp(argv[1], "generated")) {
        for (int k = 1; k < FX_CONTEXTS; k++) {
            g_context = k;
            assert(scene_ensure((VkImage)(uintptr_t)(10 + k), 1280, 720) == 0);
        }
        assert(sets == FX_CONTEXTS && !freed_sets);
        vkp_effects_release_generated_sources();
        assert(g_context == 0 && freed_sets == FX_CONTEXTS - 1);
        assert(g_scene.img == source && g_scene.view == original);
        vkp_effects_release_generated_sources();
        assert(freed_sets == FX_CONTEXTS - 1);
    } else if (!strcmp(argv[1], "recycled")) {
        vkp_effects_forget_image(source);
        assert(freed_sets == 1 && freed_views == 1);
        assert(scene_ensure(source, 1280, 720) == 0);
        assert(bound_view != original && views == 2 && sets == 2);
    } else if (!strcmp(argv[1], "unrelated")) {
        vkp_effects_forget_image((VkImage)(uintptr_t)11);
        vkp_effects_forget_image(VK_NULL_HANDLE);
        assert(scene_ensure(source, 1280, 720) == 0);
        assert(bound_view == original && views == 1 && !freed_views && !freed_sets);
    } else if (!strcmp(argv[1], "repeated")) {
        vkp_effects_forget_image(source);
        vkp_effects_forget_image(source);
        assert(freed_sets == 1 && freed_views == 1);
    } else {
        assert(!strcmp(argv[1], "resize"));
        assert(scene_ensure(source, 1920, 1080) == 0);
        assert(bound_view != original && views == 2 && freed_views == 1 && freed_sets == 1);
    }
    check_blend_cache_target_lifetime();
    puts("PASS");
    return 0;
}
