/* Cardboard pass - see cardboard.h. Compositor thread unless noted.
 *
 * The shader is cardboard.frag, pre-compiled like the rest of the compositor's SPIR-V:
 *     glslangValidator -V --vn cardboard_frag_code -o cardboard_frag.h cardboard.frag
 */
#define _POSIX_C_SOURCE 200809L
#include "cardboard.h"
#include <math.h>
#include <pthread.h>
#include <string.h>

#ifndef CARDBOARD_HOST_TEST
#include <fcntl.h>
#include <stdatomic.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>
#include "headpose_shm.h"
#endif

#ifndef CARDBOARD_HOST_TEST
#include "vk_loader.h"
#include "vk_present.h" /* droiddeck_log */
/* upscale_vert.h defines its array non-static, and effects_chain.c includes it too: a private name here. */
#define upscale_vert_code cardboard_upscale_vert_code
#include "upscale_vert.h"
#undef upscale_vert_code
#include "cardboard_frag.h"
#endif

/* ------------------------------------------------------------------ pure helpers */

static float clampf(float v, float lo, float hi) { return v < lo ? lo : v > hi ? hi : v; }

void cardboard_clamp(struct cardboard_params *p) {
    p->enabled = p->enabled ? 1 : 0;
    p->source = p->source == CARDBOARD_SOURCE_SBS ? CARDBOARD_SOURCE_SBS : CARDBOARD_SOURCE_SCREEN;
    p->ipd_m = clampf(p->ipd_m, 0.0f, 0.1f);
    p->screen_dist_m = clampf(p->screen_dist_m, 0.5f, 50.0f);
    p->screen_width_m = clampf(p->screen_width_m, 0.2f, 60.0f);
    p->k1 = clampf(p->k1, -1.0f, 2.0f);
    p->k2 = clampf(p->k2, -1.0f, 2.0f);
    p->fov_deg = clampf(p->fov_deg, 30.0f, 140.0f);
    p->lens_inset = clampf(p->lens_inset, -0.25f, 0.25f);
    p->chroma = clampf(p->chroma, 0.0f, 0.05f);
}

void cardboard_push_constants(const struct cardboard_params *p, const float pose[9],
                              int src_w, int src_h, int out_w, int out_h, float out[24]) {
    /* rot0..rot2: the pose's rows, each with one scalar riding in w. */
    for (int r = 0; r < 3; r++)
        for (int c = 0; c < 3; c++) out[r * 4 + c] = pose[r * 3 + c];
    out[3] = p->ipd_m * 0.5f;
    out[7] = p->screen_dist_m;
    out[11] = p->screen_width_m * 0.5f;
    /* lens */
    out[12] = p->k1;
    out[13] = p->k2;
    out[14] = tanf(p->fov_deg * 0.5f * (float)M_PI / 180.0f);
    out[15] = p->chroma;
    /* view */
    out[16] = p->lens_inset;
    out[17] = (src_w > 0 && src_h > 0) ? (float)src_w / (float)src_h : 16.0f / 9.0f;
    if (p->source == CARDBOARD_SOURCE_SBS) out[17] *= 0.5f; /* one eye's half */
    out[18] = (float)p->source;
    out[19] = (out_w > 0 && out_h > 0) ? ((float)out_w * 0.5f) / (float)out_h : 1.0f;
    out[20] = out[21] = out[22] = out[23] = 0.0f; /* reserved */
}

#ifndef CARDBOARD_HOST_TEST

/* ------------------------------------------------------------------ settings + pose */

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static struct cardboard_params g_set = {
    .enabled = 0, .source = CARDBOARD_SOURCE_SCREEN, .ipd_m = 0.064f, .screen_dist_m = 3.0f,
    .screen_width_m = 4.0f, .k1 = 0.22f, .k2 = 0.24f, .fov_deg = 90.0f, .lens_inset = 0.0f, .chroma = 0.0f};
static float g_pose[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
static volatile int g_changed = 1;

static int g_live;                   /* the last pose came from a sensor */
static void shm_publish(int live);   /* below: the guest's copy of the pose */

void vkp_cardboard_set(const struct cardboard_params *p) {
    struct cardboard_params c = *p;
    cardboard_clamp(&c);
    pthread_mutex_lock(&g_lock);
    int was = g_set.enabled;
    g_set = c;
    g_changed = 1;
    shm_publish(g_live);
    pthread_mutex_unlock(&g_lock);
    if (was != c.enabled || c.enabled)
        droiddeck_log("cardboard", "%s: %s, ipd %.0f mm, screen %.1f m wide at %.1f m, lens k1 %.2f k2 %.2f, fov %.0f, inset %.3f, chroma %.3f",
                      c.enabled ? "on" : "off", c.source == CARDBOARD_SOURCE_SBS ? "side-by-side source" : "virtual screen",
                      c.ipd_m * 1000.0f, c.screen_width_m, c.screen_dist_m, c.k1, c.k2, c.fov_deg, c.lens_inset, c.chroma);
}

/* The guest's copy of the pose (headpose_shm.h), mapped once a path is known. */
static struct droiddeck_headpose *g_shm;
static int g_eye_w, g_eye_h; /* the last eye viewport drawn, for the guest */

void vkp_cardboard_set_pose_file(const char *path) {
    pthread_mutex_lock(&g_lock);
    if (!g_shm && path && *path) {
        int fd = open(path, O_RDWR | O_CREAT | O_CLOEXEC, 0600);
        if (fd >= 0 && ftruncate(fd, sizeof(struct droiddeck_headpose)) == 0) {
            void *p = mmap(NULL, sizeof(struct droiddeck_headpose), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
            if (p != MAP_FAILED) {
                g_shm = p;
                memset(g_shm, 0, sizeof(*g_shm));
                g_shm->magic = DROIDDECK_HEADPOSE_MAGIC;
                g_shm->version = DROIDDECK_HEADPOSE_VERSION;
                memcpy(g_shm->rot, g_pose, sizeof(g_shm->rot));
            }
        }
        if (fd >= 0) close(fd);
        droiddeck_log("cardboard", g_shm ? "head pose shared with the guest at %s" : "could not share the head pose at %s", path);
    }
    pthread_mutex_unlock(&g_lock);
}

/* Under g_lock. */
static void shm_publish(int live) {
    if (!g_shm) return;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    atomic_uint *seq = (atomic_uint *)&g_shm->seq;
    unsigned s = atomic_load_explicit(seq, memory_order_relaxed);
    atomic_store_explicit(seq, s | 1u, memory_order_relaxed);
    atomic_thread_fence(memory_order_release);
    memcpy(g_shm->rot, g_pose, sizeof(g_shm->rot));
    g_shm->ipd_m = g_set.ipd_m;
    g_shm->flags = (live ? DROIDDECK_HEADPOSE_LIVE : 0u) | (g_set.enabled ? DROIDDECK_HEADPOSE_ENABLED : 0u);
    g_shm->time_ns = (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
    g_shm->tan_half_fov = tanf(g_set.fov_deg * 0.5f * (float)M_PI / 180.0f);
    g_shm->eye_w = (uint32_t)(g_eye_w > 0 ? g_eye_w : 0);
    g_shm->eye_h = (uint32_t)(g_eye_h > 0 ? g_eye_h : 0);
    g_shm->eye_aspect = g_eye_h > 0 ? (float)g_eye_w / (float)g_eye_h : 1.0f;
    atomic_thread_fence(memory_order_release);
    atomic_store_explicit(seq, (s | 1u) + 1u, memory_order_relaxed);
}


void vkp_cardboard_set_pose(const float m[9], int live) {
    pthread_mutex_lock(&g_lock);
    memcpy(g_pose, m, sizeof(g_pose));
    g_live = live;
    if (g_set.enabled && g_set.source == CARDBOARD_SOURCE_SCREEN) g_changed = 1;
    shm_publish(live);
    pthread_mutex_unlock(&g_lock);
}

static struct cardboard_params g_cur;
static float g_cur_pose[9];

static void snapshot(void) {
    pthread_mutex_lock(&g_lock);
    g_cur = g_set;
    memcpy(g_cur_pose, g_pose, sizeof(g_cur_pose));
    pthread_mutex_unlock(&g_lock);
}

int vkp_cardboard_active(void) {
    pthread_mutex_lock(&g_lock);
    int on = g_set.enabled;
    pthread_mutex_unlock(&g_lock);
    return on;
}

int vkp_cardboard_pose_changed(void) {
    if (!g_changed) return 0;
    g_changed = 0;
    return 1;
}

/* ------------------------------------------------------------------ Vulkan objects */

#define CB_FORMAT VK_FORMAT_R8G8B8A8_UNORM
#define CB_PC_SIZE (4 * 4 + 24 * 4) /* ndc + the 24 floats: 112 bytes, under the 128 guaranteed */
#define CB_MAX_SOURCES 8           /* source views per frame: the real frame + generated ones */

static VkDevice g_dev;
static VkPhysicalDeviceMemoryProperties g_mp;
static int g_ready; /* 0 = not built, 1 = ok, -1 = failed (said once) */
static VkRenderPass g_rp;
static VkDescriptorSetLayout g_dsl;
static VkPipelineLayout g_pl;
static VkDescriptorPool g_dpool;
static VkSampler g_sampler;
static VkPipeline g_pipe;
static struct { VkImage img; VkDeviceMemory mem; VkImageView view; VkFramebuffer fb; int w, h; } g_t;
static struct { VkImageView view; VkDescriptorSet ds; } g_src[CB_MAX_SOURCES];
static int g_nsrc;

void vkp_cardboard_bind_device(VkDevice dev, const VkPhysicalDeviceMemoryProperties *memprops) {
    g_dev = dev;
    g_mp = *memprops;
}

static int memtype(uint32_t bits, VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < g_mp.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (g_mp.memoryTypes[i].propertyFlags & want) == want) return (int)i;
    return -1;
}

static void sources_release(void) {
    for (int i = 0; i < g_nsrc; i++) {
        if (g_src[i].ds) g_vk.FreeDescriptorSets(g_dev, g_dpool, 1, &g_src[i].ds);
        if (g_src[i].view) g_vk.DestroyImageView(g_dev, g_src[i].view, NULL);
    }
    memset(g_src, 0, sizeof(g_src));
    g_nsrc = 0;
}

void vkp_cardboard_begin_frame(void) {
    if (g_ready == 1) sources_release();
}

static void target_destroy(void) {
    if (g_t.fb) g_vk.DestroyFramebuffer(g_dev, g_t.fb, NULL);
    if (g_t.view) g_vk.DestroyImageView(g_dev, g_t.view, NULL);
    if (g_t.img) g_vk.DestroyImage(g_dev, g_t.img, NULL);
    if (g_t.mem) g_vk.FreeMemory(g_dev, g_t.mem, NULL);
    memset(&g_t, 0, sizeof(g_t));
}

static VkImageView make_view(VkImage img) {
    VkImageView v = VK_NULL_HANDLE;
    VkImageViewCreateInfo ci = {.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, .image = img,
                                .viewType = VK_IMAGE_VIEW_TYPE_2D, .format = CB_FORMAT,
                                .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    if (g_vk.CreateImageView(g_dev, &ci, NULL, &v) != VK_SUCCESS) return VK_NULL_HANDLE;
    return v;
}

/* The panel-sized output: rendered into, then blitted 1:1 to the swapchain. Kept uncompressed
 * (mutable format) like the effect chain's targets: UBWC render-then-blit targets corrupt on
 * Adreno 740. */
static int target_ensure(int w, int h) {
    if (g_t.img && g_t.w == w && g_t.h == h) return 0;
    target_destroy();
    VkImageCreateInfo ii = {.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .flags = VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT,
                            .imageType = VK_IMAGE_TYPE_2D, .format = CB_FORMAT, .extent = {(uint32_t)w, (uint32_t)h, 1},
                            .mipLevels = 1, .arrayLayers = 1, .samples = VK_SAMPLE_COUNT_1_BIT,
                            .tiling = VK_IMAGE_TILING_OPTIMAL,
                            .usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                            .sharingMode = VK_SHARING_MODE_EXCLUSIVE, .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED};
    if (g_vk.CreateImage(g_dev, &ii, NULL, &g_t.img) != VK_SUCCESS) goto fail;
    VkMemoryRequirements req;
    g_vk.GetImageMemoryRequirements(g_dev, g_t.img, &req);
    int idx = memtype(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (idx < 0) idx = memtype(req.memoryTypeBits, 0);
    VkMemoryAllocateInfo mai = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = req.size,
                                .memoryTypeIndex = (uint32_t)(idx < 0 ? 0 : idx)};
    if (g_vk.AllocateMemory(g_dev, &mai, NULL, &g_t.mem) != VK_SUCCESS) goto fail;
    g_vk.BindImageMemory(g_dev, g_t.img, g_t.mem, 0);
    if (!(g_t.view = make_view(g_t.img))) goto fail;
    VkFramebufferCreateInfo fi = {.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO, .renderPass = g_rp,
                                  .attachmentCount = 1, .pAttachments = &g_t.view, .width = (uint32_t)w,
                                  .height = (uint32_t)h, .layers = 1};
    if (g_vk.CreateFramebuffer(g_dev, &fi, NULL, &g_t.fb) != VK_SUCCESS) goto fail;
    g_t.w = w;
    g_t.h = h;
    droiddeck_log("cardboard", "eye target %dx%d (two %dx%d eyes)", w, h, w / 2, h);
    return 0;
fail:
    droiddeck_log("error", "cardboard: could not create the %dx%d eye target", w, h);
    target_destroy();
    return -1;
}

static VkShaderModule shader(const uint32_t *code, size_t size) {
    VkShaderModule m = VK_NULL_HANDLE;
    VkShaderModuleCreateInfo ci = {.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, .codeSize = size, .pCode = code};
    if (g_vk.CreateShaderModule(g_dev, &ci, NULL, &m) != VK_SUCCESS) return VK_NULL_HANDLE;
    return m;
}

static void objects_destroy(void) {
    if (!g_dev) return;
    sources_release();
    target_destroy();
    if (g_pipe) g_vk.DestroyPipeline(g_dev, g_pipe, NULL);
    if (g_dpool) g_vk.DestroyDescriptorPool(g_dev, g_dpool, NULL);
    if (g_pl) g_vk.DestroyPipelineLayout(g_dev, g_pl, NULL);
    if (g_dsl) g_vk.DestroyDescriptorSetLayout(g_dev, g_dsl, NULL);
    if (g_sampler) g_vk.DestroySampler(g_dev, g_sampler, NULL);
    if (g_rp) g_vk.DestroyRenderPass(g_dev, g_rp, NULL);
    g_pipe = VK_NULL_HANDLE; g_dpool = VK_NULL_HANDLE; g_pl = VK_NULL_HANDLE;
    g_dsl = VK_NULL_HANDLE; g_sampler = VK_NULL_HANDLE; g_rp = VK_NULL_HANDLE;
}

static int objects_ensure(void) {
    if (g_ready) return g_ready == 1 ? 0 : -1;
    if (!g_dev || !g_vk.CreateGraphicsPipelines) goto fail;
    {
        /* One colour target, overwritten whole, left GENERAL for the 1:1 blit that follows. */
        VkAttachmentDescription att = {.format = CB_FORMAT, .samples = VK_SAMPLE_COUNT_1_BIT,
                                       .loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE, .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
                                       .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
                                       .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
                                       .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED, .finalLayout = VK_IMAGE_LAYOUT_GENERAL};
        VkAttachmentReference ref = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription sub = {.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS, .colorAttachmentCount = 1,
                                    .pColorAttachments = &ref};
        VkSubpassDependency deps[2] = {
            {.srcSubpass = VK_SUBPASS_EXTERNAL, .dstSubpass = 0,
             .srcStageMask = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
             .dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
             .srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT,
             .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT},
            {.srcSubpass = 0, .dstSubpass = VK_SUBPASS_EXTERNAL,
             .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
             .dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT,
             .srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT}};
        VkRenderPassCreateInfo rci = {.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO, .attachmentCount = 1,
                                      .pAttachments = &att, .subpassCount = 1, .pSubpasses = &sub,
                                      .dependencyCount = 2, .pDependencies = deps};
        if (g_vk.CreateRenderPass(g_dev, &rci, NULL, &g_rp) != VK_SUCCESS) goto fail;

        VkSamplerCreateInfo sci = {.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO, .magFilter = VK_FILTER_LINEAR,
                                   .minFilter = VK_FILTER_LINEAR, .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST,
                                   .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                                   .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                                   .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE};
        if (g_vk.CreateSampler(g_dev, &sci, NULL, &g_sampler) != VK_SUCCESS) goto fail;

        VkDescriptorSetLayoutBinding b = {.binding = 0, .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                                          .descriptorCount = 1, .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT};
        VkDescriptorSetLayoutCreateInfo dli = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
                                               .bindingCount = 1, .pBindings = &b};
        if (g_vk.CreateDescriptorSetLayout(g_dev, &dli, NULL, &g_dsl) != VK_SUCCESS) goto fail;
        VkPushConstantRange pcr = {.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                                   .offset = 0, .size = CB_PC_SIZE};
        VkPipelineLayoutCreateInfo pli = {.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, .setLayoutCount = 1,
                                          .pSetLayouts = &g_dsl, .pushConstantRangeCount = 1, .pPushConstantRanges = &pcr};
        if (g_vk.CreatePipelineLayout(g_dev, &pli, NULL, &g_pl) != VK_SUCCESS) goto fail;
        VkDescriptorPoolSize ps = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, CB_MAX_SOURCES};
        VkDescriptorPoolCreateInfo dpi = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
                                          .flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT,
                                          .maxSets = CB_MAX_SOURCES, .poolSizeCount = 1, .pPoolSizes = &ps};
        if (g_vk.CreateDescriptorPool(g_dev, &dpi, NULL, &g_dpool) != VK_SUCCESS) goto fail;

        VkShaderModule vert = shader(cardboard_upscale_vert_code, sizeof(cardboard_upscale_vert_code));
        VkShaderModule frag = shader(cardboard_frag_code, sizeof(cardboard_frag_code));
        if (!vert || !frag) {
            if (vert) g_vk.DestroyShaderModule(g_dev, vert, NULL);
            if (frag) g_vk.DestroyShaderModule(g_dev, frag, NULL);
            goto fail;
        }
        VkPipelineShaderStageCreateInfo st[2] = {
            {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_VERTEX_BIT, .module = vert, .pName = "main"},
            {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_FRAGMENT_BIT, .module = frag, .pName = "main"}};
        VkPipelineVertexInputStateCreateInfo vi = {.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
        VkPipelineInputAssemblyStateCreateInfo ia = {.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
                                                     .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP};
        VkDynamicState dyn[2] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
        VkPipelineDynamicStateCreateInfo ds = {.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
                                               .dynamicStateCount = 2, .pDynamicStates = dyn};
        VkPipelineViewportStateCreateInfo vp = {.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
                                                .viewportCount = 1, .scissorCount = 1};
        VkPipelineRasterizationStateCreateInfo rs = {.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
                                                     .polygonMode = VK_POLYGON_MODE_FILL, .cullMode = VK_CULL_MODE_NONE,
                                                     .frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE, .lineWidth = 1.0f};
        VkPipelineMultisampleStateCreateInfo ms = {.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
                                                   .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT};
        VkPipelineColorBlendAttachmentState ba = {.blendEnable = VK_FALSE, .colorWriteMask = 0xF};
        VkPipelineColorBlendStateCreateInfo cb = {.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
                                                  .attachmentCount = 1, .pAttachments = &ba};
        VkGraphicsPipelineCreateInfo pi = {.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO, .stageCount = 2,
                                           .pStages = st, .pVertexInputState = &vi, .pInputAssemblyState = &ia,
                                           .pViewportState = &vp, .pRasterizationState = &rs, .pMultisampleState = &ms,
                                           .pColorBlendState = &cb, .pDynamicState = &ds, .layout = g_pl,
                                           .renderPass = g_rp, .subpass = 0};
        VkResult r = g_vk.CreateGraphicsPipelines(g_dev, VK_NULL_HANDLE, 1, &pi, NULL, &g_pipe);
        g_vk.DestroyShaderModule(g_dev, vert, NULL);
        g_vk.DestroyShaderModule(g_dev, frag, NULL);
        if (r != VK_SUCCESS) goto fail;
    }
    g_ready = 1;
    droiddeck_log("cardboard", "pass ready on %s", vkp_gpu_name());
    return 0;
fail:
    objects_destroy();
    g_ready = -1;
    droiddeck_log("error", "cardboard: the pass could not be built on this driver; frames are shown flat");
    return -1;
}

static VkDescriptorSet source_ds(VkImage src) {
    if (g_nsrc >= CB_MAX_SOURCES) return VK_NULL_HANDLE;
    VkImageView view = make_view(src);
    if (!view) return VK_NULL_HANDLE;
    VkDescriptorSet ds = VK_NULL_HANDLE;
    VkDescriptorSetAllocateInfo ai = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
                                      .descriptorPool = g_dpool, .descriptorSetCount = 1, .pSetLayouts = &g_dsl};
    if (g_vk.AllocateDescriptorSets(g_dev, &ai, &ds) != VK_SUCCESS) {
        g_vk.DestroyImageView(g_dev, view, NULL);
        return VK_NULL_HANDLE;
    }
    VkDescriptorImageInfo ii = {.sampler = g_sampler, .imageView = view, .imageLayout = VK_IMAGE_LAYOUT_GENERAL};
    VkWriteDescriptorSet wr = {.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = ds, .dstBinding = 0,
                               .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                               .pImageInfo = &ii};
    g_vk.UpdateDescriptorSets(g_dev, 1, &wr, 0, NULL);
    g_src[g_nsrc].view = view;
    g_src[g_nsrc].ds = ds;
    g_nsrc++;
    return ds;
}

VkImage vkp_cardboard_run(VkCommandBuffer cmd, VkImage src, int src_w, int src_h, int out_w, int out_h) {
    if (out_w < 2 || out_h < 1 || objects_ensure() != 0 || target_ensure(out_w, out_h) != 0) return VK_NULL_HANDLE;
    VkDescriptorSet ds = source_ds(src);
    if (!ds) {
        static int said;
        if (!said) { said = 1; droiddeck_log("error", "cardboard: no descriptor for the frame; shown flat"); }
        return VK_NULL_HANDLE;
    }
    if (g_eye_w != out_w / 2 || g_eye_h != out_h) {
        pthread_mutex_lock(&g_lock);
        g_eye_w = out_w / 2;
        g_eye_h = out_h;
        shm_publish(g_live);
        pthread_mutex_unlock(&g_lock);
    }
    snapshot();
    struct { float ndc[4]; float rest[24]; } pc = {{-1.0f, -1.0f, 1.0f, 1.0f}, {0}};
    cardboard_push_constants(&g_cur, g_cur_pose, src_w, src_h, out_w, out_h, pc.rest);

    VkRenderPassBeginInfo rpi = {.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO, .renderPass = g_rp,
                                 .framebuffer = g_t.fb, .renderArea = {{0, 0}, {(uint32_t)out_w, (uint32_t)out_h}}};
    VkViewport vp = {0, 0, (float)out_w, (float)out_h, 0, 1};
    VkRect2D sc = {{0, 0}, {(uint32_t)out_w, (uint32_t)out_h}};
    g_vk.CmdBeginRenderPass(cmd, &rpi, VK_SUBPASS_CONTENTS_INLINE);
    g_vk.CmdSetViewport(cmd, 0, 1, &vp);
    g_vk.CmdSetScissor(cmd, 0, 1, &sc);
    g_vk.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, g_pipe);
    g_vk.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, g_pl, 0, 1, &ds, 0, NULL);
    g_vk.CmdPushConstants(cmd, g_pl, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(pc), &pc);
    g_vk.CmdDraw(cmd, 4, 1, 0, 0);
    g_vk.CmdEndRenderPass(cmd);
    return g_t.img;
}

#endif /* CARDBOARD_HOST_TEST */
