#define VK_NO_PROTOTYPES
#include <assert.h>
#include <stdint.h>
#include <string.h>
#include "vertex_stores.c"

static int virtual_cull = 1;
static uintptr_t next_pass = 0x100;
static unsigned custom_allocations, custom_frees;
static int fail_create;

static void *VKAPI_PTR allocate(void *user, size_t size, size_t alignment,
    VkSystemAllocationScope scope)
{
    (void)user; (void)alignment; (void)scope; ++custom_allocations; return malloc(size);
}
static void VKAPI_PTR release(void *user, void *memory)
{ (void)user; ++custom_frees; free(memory); }

int hybris_cull_active(VkPhysicalDevice physical) { (void)physical; return virtual_cull; }
int hybris_layer_lookup_physical(VkPhysicalDevice physical, struct hybris_layer_physical *out)
{ (void)physical; (void)out; return 0; }
static VkResult VKAPI_CALL backend_create_pass(VkDevice device,
    const VkRenderPassCreateInfo *info, const VkAllocationCallbacks *allocator, VkRenderPass *out)
{
    (void)device; (void)info; (void)allocator;
    if (fail_create) return VK_ERROR_UNKNOWN;
    *out = (VkRenderPass)next_pass++; return VK_SUCCESS;
}
static VkResult VKAPI_CALL backend_create_pass2(VkDevice device,
    const VkRenderPassCreateInfo2 *info, const VkAllocationCallbacks *allocator, VkRenderPass *out)
{
    (void)device; (void)info; (void)allocator;
    if (fail_create) return VK_ERROR_UNKNOWN;
    *out = (VkRenderPass)next_pass++; return VK_SUCCESS;
}
static void VKAPI_CALL backend_destroy_pass(VkDevice device, VkRenderPass pass,
    const VkAllocationCallbacks *allocator)
{ (void)device; (void)pass; (void)allocator; }
static PFN_vkVoidFunction VKAPI_CALL resolver(VkDevice device, const char *name)
{
    (void)device;
    if (!strcmp(name, "vkCreateRenderPass")) return (PFN_vkVoidFunction)backend_create_pass;
    if (!strcmp(name, "vkCreateRenderPass2") || !strcmp(name, "vkCreateRenderPass2KHR"))
        return (PFN_vkVoidFunction)backend_create_pass2;
    if (!strcmp(name, "vkDestroyRenderPass")) return (PFN_vkVoidFunction)backend_destroy_pass;
    return NULL;
}
int hybris_layer_lookup_device(VkDevice device, struct hybris_layer_device *out)
{
    *out = (struct hybris_layer_device){.handle = device, .physical = (VkPhysicalDevice)2,
        .resolver = resolver};
    return 1;
}

static void check(VkDevice device, VkRenderPass pass, uint32_t subpass,
    uint32_t mask, int known)
{
    VkGraphicsPipelineCreateInfo pipeline = {.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
        .renderPass = pass, .subpass = subpass};
    struct hybris_vertex_rendering rendering = {0};
    assert(hybris_vertex_rendering_info(device, &pipeline, &rendering) == VK_SUCCESS);
    assert(rendering.view_mask == mask && rendering.view_mask_known == known);
}
static unsigned tracked_passes(void)
{
    unsigned count = 0;
    for (struct render_pass *pass = passes; pass; pass = pass->next) ++count;
    return count;
}

int main(void)
{
    VkDevice device = (VkDevice)1;
    VkSubpassDescription subpasses[2] = {{0}, {0}};
    uint32_t masks[2] = {0, 3};
    VkRenderPassMultiviewCreateInfo multiview = {
        .sType = VK_STRUCTURE_TYPE_RENDER_PASS_MULTIVIEW_CREATE_INFO,
        .subpassCount = 2, .pViewMasks = masks};
    VkRenderPassCreateInfo legacy = {.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
        .pNext = &multiview, .subpassCount = 2, .pSubpasses = subpasses};
    VkAllocationCallbacks allocator = {.pfnAllocation = allocate, .pfnFree = release};
    VkRenderPass pass = VK_NULL_HANDLE;
    PFN_vkCreateRenderPass create = (PFN_vkCreateRenderPass)hybris_vertex_stores_proc("vkCreateRenderPass");
    assert(create(device, &legacy, &allocator, &pass) == VK_SUCCESS);
    check(device, pass, 0, 0, 1); check(device, pass, 1, 3, 1);

    multiview.subpassCount = 1;
    VkRenderPass unknown = VK_NULL_HANDLE;
    assert(create(device, &legacy, NULL, &unknown) == VK_SUCCESS);
    check(device, unknown, 1, 0, 0);

    VkSubpassDescription2 subpasses2[2] = {
        {.sType = VK_STRUCTURE_TYPE_SUBPASS_DESCRIPTION_2, .viewMask = 1},
        {.sType = VK_STRUCTURE_TYPE_SUBPASS_DESCRIPTION_2, .viewMask = 4}};
    VkRenderPassCreateInfo2 modern = {.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO_2,
        .subpassCount = 2, .pSubpasses = subpasses2};
    VkRenderPass pass2 = VK_NULL_HANDLE;
    PFN_vkCreateRenderPass2 create2 = (PFN_vkCreateRenderPass2)hybris_vertex_stores_proc("vkCreateRenderPass2");
    assert(create2(device, &modern, NULL, &pass2) == VK_SUCCESS);
    check(device, pass2, 0, 1, 1); check(device, pass2, 1, 4, 1);
    assert(tracked_passes() == 3);
    fail_create = 1;
    VkRenderPass failed = VK_NULL_HANDLE;
    unsigned allocations_before_failure = custom_allocations;
    unsigned frees_before_failure = custom_frees;
    assert(create(device, &legacy, &allocator, &failed) == VK_ERROR_UNKNOWN);
    assert(failed == VK_NULL_HANDLE && tracked_passes() == 3);
    assert(custom_allocations == allocations_before_failure + 1);
    assert(custom_frees == frees_before_failure + 1);
    fail_create = 0;

    VkPipelineMultisampleStateCreateInfo samples = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
        .rasterizationSamples = VK_SAMPLE_COUNT_4_BIT};
    VkPipelineRenderingCreateInfo dynamic = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO, .viewMask = 5};
    VkGraphicsPipelineCreateInfo pipeline = {.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
        .pNext = &dynamic, .pMultisampleState = &samples};
    struct hybris_vertex_rendering rendering = {0};
    assert(hybris_vertex_rendering_info(device, &pipeline, &rendering) == VK_SUCCESS);
    assert(rendering.view_mask == 5 && rendering.view_mask_known &&
        rendering.samples == VK_SAMPLE_COUNT_4_BIT);

    virtual_cull = 0;
    VkRenderPass native = VK_NULL_HANDLE;
    multiview.subpassCount = 2;
    assert(create(device, &legacy, NULL, &native) == VK_SUCCESS);
    pipeline = (VkGraphicsPipelineCreateInfo){.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
        .renderPass = native};
    assert(hybris_vertex_rendering_info(device, &pipeline, &rendering) == VK_ERROR_UNKNOWN);

    PFN_vkDestroyRenderPass destroy = (PFN_vkDestroyRenderPass)hybris_vertex_stores_proc("vkDestroyRenderPass");
    destroy(device, pass, NULL); destroy(device, unknown, NULL); destroy(device, pass2, NULL);
    assert(custom_allocations == 2 && custom_frees == 2);
    assert(!passes);
    virtual_cull = 1;
    VkRenderPass retired = VK_NULL_HANDLE;
    assert(create(device, &legacy, &allocator, &retired) == VK_SUCCESS);
    assert(tracked_passes() == 1 && custom_allocations == 3 && custom_frees == 2);
    hybris_vertex_stores_release_device(device);
    assert(!passes && custom_frees == 3);
    return 0;
}
