#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

/* Exercise the real cache with Vulkan lifetime callbacks, without an Android surface or GPU. */
#define VK_PRESENT_H
const char *vkp_gpu_name(void) { return "cache test"; }
void droiddeck_log(const char *tag, const char *fmt, ...) { (void)tag; (void)fmt; }
#include "vk_loader.h"
struct vk_api g_vk;
#include "effects_chain.c"

static unsigned views, sets, freed_views, freed_sets;
static VkImageView bound_view;
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
    if (!strcmp(argv[1], "recycled")) {
        /* The owner destroys the image, then Vulkan reuses its handle at the same size. */
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
    puts("PASS");
    return 0;
}
