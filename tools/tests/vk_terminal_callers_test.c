/* Compiled with the complete, unmodified production translation unit. The
 * compositor's take_shm/take_pixel functions are inserted verbatim by Python. */
#include "vk_present.c"
#include <assert.h>

struct vk_api g_vk;
static unsigned operations, acquires, submits, markers, waits, idle_calls, resets;
static unsigned destroys, releases, lost_notifications, presented_notifications;
static VkResult idle_answer, fence_answer, acquire_answer, present_answer;
static unsigned fail_acquire_at;
static int generated;

/* Every work-issuing callback is an oracle: no GPU operation after a terminal
 * result. Object destruction is counted separately for genuine device loss. */
static void live(void) { assert(g_dev_state == 1); ++operations; }
static VkResult acquire(VkDevice d, VkSwapchainKHR s, uint64_t t, VkSemaphore a, VkFence f, uint32_t *i) {
    live(); ++acquires; *i = 0;
    return acquires == fail_acquire_at ? acquire_answer : VK_SUCCESS;
}
static VkResult idle(VkDevice d) { live(); ++idle_calls; return idle_answer; }
static VkResult wait_fences(VkDevice d, uint32_t n, const VkFence *f, VkBool32 all, uint64_t t) {
    live(); ++waits; return fence_answer;
}
static VkResult submit(VkQueue q, uint32_t n, const VkSubmitInfo *s, VkFence f) {
    live(); ++submits; if (!s->commandBufferCount) ++markers; return VK_SUCCESS;
}
static VkResult present(VkQueue q, const VkPresentInfoKHR *p) { live(); return present_answer; }
static VkResult reset_cmd(VkCommandBuffer c, VkCommandBufferResetFlags f) { live(); ++resets; return VK_SUCCESS; }
static VkResult reset_fence(VkDevice d, uint32_t n, const VkFence *f) { live(); ++resets; return VK_SUCCESS; }
static VkResult begin_cmd(VkCommandBuffer c, const VkCommandBufferBeginInfo *b) { live(); return VK_SUCCESS; }
static VkResult end_cmd(VkCommandBuffer c) { live(); return VK_SUCCESS; }
static void barrier(VkCommandBuffer c, VkPipelineStageFlags s, VkPipelineStageFlags d, VkDependencyFlags f,
                    uint32_t nm, const VkMemoryBarrier *m, uint32_t nb, const VkBufferMemoryBarrier *b,
                    uint32_t ni, const VkImageMemoryBarrier *i) { live(); }
static void clear(VkCommandBuffer c, VkImage i, VkImageLayout l, const VkClearColorValue *v,
                  uint32_t n, const VkImageSubresourceRange *r) { live(); }
static void blit(VkCommandBuffer c, VkImage s, VkImageLayout sl, VkImage d, VkImageLayout dl,
                 uint32_t n, const VkImageBlit *b, VkFilter f) { live(); }
static void destroy_swap(VkDevice d, VkSwapchainKHR s, const VkAllocationCallbacks *a) { ++destroys; }
static void destroy_surface(VkInstance i, VkSurfaceKHR s, const VkAllocationCallbacks *a) { ++destroys; }
static void destroy_sem(VkDevice d, VkSemaphore s, const VkAllocationCallbacks *a) { live(); ++destroys; }
static VkResult create_sem(VkDevice d, const VkSemaphoreCreateInfo *c, const VkAllocationCallbacks *a, VkSemaphore *s) {
    live(); *s = (VkSemaphore)(uintptr_t)1; return VK_SUCCESS;
}
static void destroy_image(VkDevice d, VkImage i, const VkAllocationCallbacks *a) { ++destroys; }
static void free_memory(VkDevice d, VkDeviceMemory m, const VkAllocationCallbacks *a) { ++destroys; }
static void unmap(VkDevice d, VkDeviceMemory m) { ++destroys; }

/* External modules are not the subject of this fault injection. Their declared
 * signatures remain checked by the production headers. */
int __android_log_print(int p, const char *t, const char *f, ...) { return 0; }
void droiddeck_log(const char *t, const char *f, ...) {}
void droiddeck_on_first_frame(void) {}
int32_t ANativeWindow_getWidth(ANativeWindow *w) { return 64; }
int32_t ANativeWindow_getHeight(ANativeWindow *w) { return 64; }
void ANativeWindow_release(ANativeWindow *w) {}
void sc_layer_window_gone(void) {}
int sc_layer_present_pass(const struct vkp_draw *d, int n, int w, int h) { return 0; }
void sc_layer_hide(void) {}
int sc_layer_base_black(int w, int h) { return 0; }
void sc_layer_hide_base(void) {}
void sc_layer_hide_overlay(void) {}
void vkp_cardboard_bind_device(VkDevice d, const VkPhysicalDeviceMemoryProperties *m) {}
int vkp_cardboard_active(void) { return 0; }
void vkp_cardboard_begin_frame(void) {}
VkImage vkp_cardboard_run(VkCommandBuffer c, VkImage s, int sw, int sh, int ow, int oh) { return s; }
int vk_loader_open(const char *a, const char *b, const char *c) { assert(0); return -1; }
void vk_loader_load_instance(VkInstance i) { assert(0); }
void vk_loader_load_device(VkDevice d) { assert(0); }
PFN_vkGetInstanceProcAddr vk_loader_gipa(void) { return NULL; }
void vkp_effects_bind_device(VkDevice d, VkPhysicalDevice p, const VkPhysicalDeviceMemoryProperties *m) {}
int vkp_effects_active(void) { return 0; }
VkFilter vkp_effects_blit_filter(void) { return VK_FILTER_NEAREST; }
void vkp_effects_set_formats(VkFormat a, VkFormat b) {}
VkImage vkp_effects_run(VkCommandBuffer c, VkImage i, int w, int h, int ow, int oh, int *rw, int *rh) {
    *rw = w; *rh = h; return i;
}
const void *vkp_framegen_device_features(VkInstance i, PFN_vkGetInstanceProcAddr p, VkPhysicalDevice d) { return NULL; }
void vkp_framegen_device_ready(VkDevice d, VkQueue q, uint32_t f, int e) {}
void vkp_framegen_device_lost(void) { ++lost_notifications; }
int vkp_framegen_active(void) { return generated != 0; }
int vkp_framegen_extra_images(void) { return generated; }
int vkp_framegen_format_ok(VkFormat f) { return 0; }
int vkp_framegen_run(VkCommandBuffer c, VkImage s, VkImageView v, int w, int h,
                     VkFormat f, VkImage images[VKP_FG_MAX_GENERATIONS]) {
    for (int i = 0; i < generated; ++i) images[i] = s;
    return generated;
}
void vkp_framegen_presented(int n) { ++presented_notifications; }
void hdrc_bind_device(VkDevice d, const VkPhysicalDeviceMemoryProperties *m) {}
void hdrc_forget_image(VkImage i) {}
int hdrc_encode(VkCommandBuffer c, VkImage a, VkImage b, VkFormat f, int w, int h, const struct hdrc_params *p) { return -1; }
void blendp_bind_device(VkDevice d) {}
void blendp_forget_image(VkImage i) {}
void vkp_effects_forget_image(VkImage i) {}
void blendp_begin_frame(void) {}
int blendp_draw(VkCommandBuffer c, VkImage s, VkFormat sf, int sw, int sh, int sg,
                VkImage d, VkFormat df, int dw, int dh, const VkImageBlit *b) { return -1; }
int droiddeck_color_requested(void) { return 0; }
float droiddeck_color_sdr_white(void) { return 203.0f; }

enum surface_role { ROLE_NONE, ROLE_TOPLEVEL };
struct damage_list { int n; };
struct surface {
    struct vkp_image *shm_img;
    struct wl_resource *resource;
    int buf_w, buf_h, buf_alpha, has_content;
    /* Read by take_shm's game-frame detection; with no damage it stays off here. */
    int src_set; float src[4];
    struct damage_list damage_buf, damage_local;
    struct surface *parent; enum surface_role role; int mapped;
};
static void surface_size(const struct surface *s, int *w, int *h) { *w = s->buf_w; *h = s->buf_h; }
static void scene_size(int *w, int *h) { *w = *h = 0; }
static int64_t damage_area(const struct damage_list *d, int bound_w, int bound_h) { return 0; }
static int64_t now_ns(void) { return 0; }
static struct surface *g_hud_surface, *g_hud_shm;
static int64_t g_hud_shm_ns, g_hud_last_ns;
static int g_hud_new;
static void droiddeck_on_game_frame(void) {}
struct client_info { int asked_feedback, shm_frames; };
struct wl_shm_buffer { int w, h, stride; const void *data; };
#define WL_SHM_FORMAT_ARGB8888 0
static unsigned g_stat_shm;
static int wl_shm_buffer_get_width(struct wl_shm_buffer *b) { return b->w; }
static int wl_shm_buffer_get_height(struct wl_shm_buffer *b) { return b->h; }
static int wl_shm_buffer_get_stride(struct wl_shm_buffer *b) { return b->stride; }
static int wl_shm_buffer_get_format(struct wl_shm_buffer *b) { return WL_SHM_FORMAT_ARGB8888; }
static const void *wl_shm_buffer_get_data(struct wl_shm_buffer *b) { return b->data; }
static void wl_shm_buffer_begin_access(struct wl_shm_buffer *b) {}
static void wl_shm_buffer_end_access(struct wl_shm_buffer *b) {}
static void wl_buffer_send_release(struct wl_resource *b) { ++releases; }
static struct wl_client *wl_resource_get_client(struct wl_resource *r) { return NULL; }
static struct client_info *client_info_of(struct wl_client *c) { return NULL; }
/* This harness exercises copied SHM/pixel callers; DMA-BUF lifecycle paths are
 * compiled separately by test_vk_dmabuf_quarantine.py. */
struct dmabuf_buffer;
static struct dmabuf_buffer *get_dmabuf(struct wl_resource *buffer) { return NULL; }
/* COMPOSITOR_CALLERS */

static void setup(int extra) {
    g_vk = (struct vk_api){.AcquireNextImageKHR = acquire, .DeviceWaitIdle = idle,
        .WaitForFences = wait_fences, .QueueSubmit = submit, .QueuePresentKHR = present,
        .ResetCommandBuffer = reset_cmd, .ResetFences = reset_fence, .BeginCommandBuffer = begin_cmd,
        .EndCommandBuffer = end_cmd, .CmdPipelineBarrier = barrier, .CmdClearColorImage = clear,
        .CmdBlitImage = blit, .DestroySwapchainKHR = destroy_swap, .DestroySurfaceKHR = destroy_surface,
        .DestroySemaphore = destroy_sem, .CreateSemaphore = create_sem,
        .DestroyImage = destroy_image, .FreeMemory = free_memory, .UnmapMemory = unmap};
    idle_answer = fence_answer = acquire_answer = present_answer = VK_SUCCESS;
    generated = g_swap_extra = extra;
    g_dev_state = 1;
    g_dev = (VkDevice)(uintptr_t)1; g_inst = (VkInstance)(uintptr_t)1;
    g_window = (ANativeWindow *)(uintptr_t)1; g_queue = (VkQueue)(uintptr_t)1;
    g_swapchain = (VkSwapchainKHR)(uintptr_t)2; g_surface = (VkSurfaceKHR)(uintptr_t)3;
    g_images = calloc(1, sizeof(*g_images)); assert(g_images);
    *g_images = (VkImage)(uintptr_t)4; g_nimg = 1;
    g_extent = g_caps_extent = (VkExtent2D){64, 64};
    g_surface_checked_ns = INT64_MAX; /* no simulated geometry change */
    g_scene.img = (VkImage)(uintptr_t)5; g_scene.mem = (VkDeviceMemory)(uintptr_t)6;
    g_scene.w = g_scene.h = 64;
    for (int k = 0; k < MAX_PRESENTS; ++k) {
        g_cmds[k] = (VkCommandBuffer)(uintptr_t)(10 + k);
        g_acqs[k] = (VkSemaphore)(uintptr_t)(20 + k);
        g_rnds[k] = (VkSemaphore)(uintptr_t)(30 + k);
    }
    g_fence = (VkFence)(uintptr_t)40;
}

/* Probe the public GPU entry points after a terminal result. This includes an
 * active off-screen pass and consumed release fds, not just the render guard. */
static void assert_stopped(void) {
    unsigned before = operations, before_destroys = destroys;
    struct vkp_image src = {.image = (VkImage)(uintptr_t)7, .w = 1, .h = 1};
    struct vkp_image dst = {.image = (VkImage)(uintptr_t)8, .w = 1, .h = 1, .blit_dst = 1};
    uint32_t pixel = 0; uint64_t mod = 0;
    assert(vkp_render(64, 64, NULL, 0) == -1);
    assert(vkp_render_plain(64, 64) == -1);
    assert(vkp_render_hdr(64, 64, NULL, 0, NULL, NULL) == -1);
    assert(vkp_base_black(64, 64) == -1);
    assert(vkp_update_map(64, 64) == -1);
    assert(vkp_ready() == -1);
    assert(vkp_image_create_shm(1, 1) == NULL);
    assert(vkp_image_import_dmabuf(-1, 0, VKP_MOD_LINEAR, 1, 1, 4, 0, 0) == NULL);
    assert(vkp_dmabuf_modifiers(0, &mod, 1) == 0);
    assert(vkp_image_readback(&src, &pixel, 1) == -1);
    assert(vkp_pass_begin(64, 64, NULL, 0, NULL, NULL) == -1);
    int fd[2]; assert(pipe(fd) == 0);
    assert(vkp_blit_image(&src, &dst, fd[0]) == -1);
    errno = 0; assert(close(fd[0]) == -1 && errno == EBADF); close(fd[1]);
    g_pass.active = 1;
    assert(pipe(fd) == 0);
    assert(vkp_pass_copy_to(&dst, fd[0]) == -1 && !g_pass.active);
    errno = 0; assert(close(fd[0]) == -1 && errno == EBADF); close(fd[1]);
    g_pass.active = 1; vkp_pass_abort(); assert(!g_pass.active);
    assert(timed_wait(g_fence, UINT64_MAX) != VK_SUCCESS);
    assert(operations == before && destroys == before_destroys);
}

static void acquire_failure(int later, VkResult result) {
    setup(later ? 1 : 0);
    fail_acquire_at = later ? 2 : 1; acquire_answer = result;
    idle_answer = VK_ERROR_OUT_OF_HOST_MEMORY;
    VkImage *retained = g_images;
    assert(vkp_render(64, 64, NULL, 0) == -1);
    assert(g_dev_state == -3 && vkp_completion_unknown() && !vkp_device_lost());
    assert(acquires == fail_acquire_at && idle_calls == 1);
    assert(submits == (unsigned)later && markers == 0 && waits == 0);
    assert(destroys == 0 && g_images == retained && g_nimg == 1);
    assert(g_swapchain && g_surface && g_fence && g_scene.img && g_scene.mem);
    assert_stopped();
    free(g_images); /* test-owned fake resources only */
}

static void upload_timeout(int single_pixel) {
    setup(0);
    uint8_t bytes[48], saved[48], data[16];
    memset(bytes, 0xa5, sizeof(bytes)); memset(data, 0x3c, sizeof(data));
    struct vkp_image *img = calloc(1, sizeof(*img)); assert(img);
    *img = (struct vkp_image){.image = (VkImage)(uintptr_t)7, .mem = (VkDeviceMemory)(uintptr_t)8,
        .map = bytes, .offset = 3, .row_pitch = 16,
        .w = single_pixel ? 1 : 2, .h = single_pixel ? 1 : 2};
    struct surface s = {.shm_img = img};
    struct wl_shm_buffer b = {.w = img->w, .h = img->h, .stride = img->w * 4, .data = data};
    if (single_pixel) take_pixel(&s, data, NULL); else take_shm(&s, &b, NULL);
    assert(bytes[3] == 0x3c && bytes[0] == 0xa5 && releases == 1);
    memcpy(saved, bytes, sizeof(saved));
    struct vkp_image dst = {.image = (VkImage)(uintptr_t)9, .blit_dst = 1, .w = img->w, .h = img->h};
    fence_answer = VK_TIMEOUT;
    assert(vkp_blit_image(img, &dst, -1) == -1);
    assert(g_dev_state == -3 && submits == 1 && waits == 1);
    memset(data, 0x7e, sizeof(data));
    if (single_pixel) take_pixel(&s, data, NULL); else take_shm(&s, &b, NULL);
    assert(s.shm_img == img && memcmp(saved, bytes, sizeof(saved)) == 0 && releases == 2);
    vkp_image_destroy(img);
    assert(destroys == 0 && img->map == bytes && img->image && img->mem);
    assert_stopped();
    free(img); free(g_images); /* fake resources only, not production cleanup */
}

int main(int argc, char **argv) {
    assert(argc == 2);
    const char *name = argv[1];
    if (!strcmp(name, "first-out-of-date")) acquire_failure(0, VK_ERROR_OUT_OF_DATE_KHR);
    else if (!strcmp(name, "first-surface-lost")) acquire_failure(0, VK_ERROR_SURFACE_LOST_KHR);
    else if (!strcmp(name, "later-out-of-date")) acquire_failure(1, VK_ERROR_OUT_OF_DATE_KHR);
    else if (!strcmp(name, "later-surface-lost")) acquire_failure(1, VK_ERROR_SURFACE_LOST_KHR);
    else if (!strcmp(name, "shm-timeout")) upload_timeout(0);
    else if (!strcmp(name, "pixel-timeout")) upload_timeout(1);
    else if (!strcmp(name, "pass-terminal")) { setup(0); g_dev_state = -3; assert_stopped(); free(g_images); }
    else if (!strcmp(name, "normal") || !strcmp(name, "framegen")) {
        int extra = !strcmp(name, "framegen"); setup(extra);
        assert(vkp_render(64, 64, NULL, 0) == 0 && g_dev_state == 1);
        assert(acquires == (unsigned)(1 + extra) && submits == (unsigned)(1 + extra));
        assert(waits == 1 && markers == 0 && idle_calls == 0 && destroys == 0);
        assert(presented_notifications == (unsigned)extra); free(g_images);
    } else if (!strcmp(name, "later-timeout")) {
        setup(1); fail_acquire_at = 2; acquire_answer = VK_TIMEOUT;
        assert(vkp_render(64, 64, NULL, 0) == -1 && g_dev_state == 1);
        assert(acquires == 2 && submits == 2 && markers == 1 && waits == 1); free(g_images);
    } else if (!strcmp(name, "acquire-device-lost") || !strcmp(name, "present-device-lost")) {
        setup(1);
        if (!strcmp(name, "acquire-device-lost")) { fail_acquire_at = 2; acquire_answer = VK_ERROR_DEVICE_LOST; }
        else present_answer = VK_ERROR_DEVICE_LOST;
        assert(vkp_render(64, 64, NULL, 0) == -1 && vkp_device_lost());
        assert(!vkp_completion_unknown());
        assert(g_dev_state == -2 && lost_notifications == 1 && markers == 0 && waits == 0);
        assert(!g_images && !g_swapchain && !g_surface && destroys == 2);
        assert_stopped();
    } else assert(0 && "unknown scenario");
    return 0;
}
