"""Exercise the renderer's source selection and both presentation paths without a GPU."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[2]


def function(source, signature):
    start = source.rindex(signature)
    return source[start:source.index('\n}\n', start) + 3]


class EffectsSourceResolutionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if not compiler:
            raise unittest.SkipTest('A host C compiler is required')
        source = (REPO / 'app/src/main/cpp/waylandcomp/src/vk_present.c').read_text()
        header = (REPO / 'app/src/main/cpp/waylandcomp/src/vk_present.h').read_text()
        effects = (REPO / 'app/src/main/cpp/waylandcomp/src/effects_chain.c').read_text()
        functions = '\n'.join(function(source, signature) for signature in (
            'static int map_draw(', 'static int draw_to_blit(',
            'static int draw_to_scene_blit(', 'static int effects_source_draw(',
            'static void effects_source_note(',
            'static void effects_overlay_draws(',
            'static int render_impl(', 'static int pass_begin_impl('))
        functions = (re.search(r'static int spatial_mode\([^\n]+', effects).group() + '\n' +
                     function(effects, 'int vkp_effects_upscaling(') +
                     function(effects, 'int vkp_effects_postprocessing(') + functions)
        image = source[source.index('struct vkp_image {'):source.index('\n};', source.index('struct vkp_image {')) + 3]
        draw = header[header.index('struct vkp_draw {'):header.index('\n};', header.index('struct vkp_draw {')) + 3]
        hdr = header[header.index('struct vkp_hdr_frame {'):header.index('\n};', header.index('struct vkp_hdr_frame {')) + 3]
        constants = sorted(set(re.findall(r'\bVK_[A-Z0-9_]+\b', functions + image)) -
                           {'VK_FORMAT_R8G8B8A8_UNORM'})
        definitions = '\n'.join(f'#define {name} {0 if name in ("VK_SUCCESS", "VK_NULL_HANDLE") else i + 1}'
                                for i, name in enumerate(constants))
        code = TYPES + definitions + '\n' + image + '\n' + draw + '\n' + hdr + STUBS + functions + CASES
        cls.temp = tempfile.TemporaryDirectory(prefix='droiddeck-effects-resolution-')
        cls.addClassCleanup(cls.temp.cleanup)
        folder = Path(cls.temp.name)
        (folder / 'resolution.c').write_text(code)
        cls.binary = folder / 'resolution'
        build = subprocess.run([compiler, '-std=c11', '-Wall', '-Wextra',
                                '-Wno-unused-parameter', '-Wno-unused-function',
                                str(folder / 'resolution.c'), '-o', str(cls.binary)],
                               capture_output=True, text=True)
        if build.returncode:
            raise RuntimeError(build.stderr)

    def check_case(self, case):
        result = subprocess.run([str(self.binary), case], capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout.strip(), 'PASS')

    def test_low_resolution_viewport_is_copied_at_native_buffer_size(self):
        self.check_case('helper')

    def test_ineligible_compositions_keep_the_logical_scene(self):
        self.check_case('rejections')

    def test_swapchain_path_uses_the_game_buffer_as_effects_input(self):
        self.check_case('render')

    def test_offscreen_pass_uses_the_game_buffer_as_effects_input(self):
        self.check_case('pass')

    def test_hdr_and_non_upscaling_effects_keep_the_logical_scene(self):
        self.check_case('gates')

    def test_each_scaling_mode_selects_the_correct_input(self):
        self.check_case('modes')

    def test_fullscreen_source_occludes_unrelated_background_draws(self):
        self.check_case('occlusion')

    def test_foreground_overlays_keep_their_geometry_after_upscaling(self):
        self.check_case('overlay')

    def test_failed_intermediate_allocation_draws_all_original_layers(self):
        self.check_case('allocation')

    def test_effects_failure_maps_overlays_to_the_returned_image_size(self):
        self.check_case('effect-failure')

    def test_offscreen_pass_acquires_foreground_sources_and_restores_result_layout(self):
        self.check_case('overlay-barriers')

    def test_postprocessing_keeps_foreground_inside_the_effect_chain(self):
        self.check_case('postprocessing')


TYPES = r'''
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
typedef int VkImage, VkDeviceMemory, VkFormat, VkCommandBuffer, VkResult, VkFilter;
typedef int VkImageLayout, VkPipelineStageFlags, VkImageUsageFlags;
typedef uint64_t VkDeviceSize;
typedef struct { unsigned width, height; } VkExtent2D;
typedef struct { int x, y, z; } VkOffset3D;
typedef struct { unsigned aspectMask, mipLevel, baseArrayLayer, layerCount; } VkImageSubresourceLayers;
typedef struct { unsigned aspectMask, baseMipLevel, levelCount, baseArrayLayer, layerCount; } VkImageSubresourceRange;
typedef struct {
    VkImageSubresourceLayers srcSubresource;
    VkOffset3D srcOffsets[2];
    VkImageSubresourceLayers dstSubresource;
    VkOffset3D dstOffsets[2];
} VkImageBlit;
typedef struct { int sType, flags; } VkCommandBufferBeginInfo;
typedef struct {
    int sType, oldLayout, newLayout, srcQueueFamilyIndex, dstQueueFamilyIndex;
    VkImage image;
    VkImageSubresourceRange subresourceRange;
    unsigned srcAccessMask, dstAccessMask;
} VkImageMemoryBarrier;
typedef struct { int sType; unsigned srcAccessMask, dstAccessMask; } VkMemoryBarrier;
typedef union { float float32[4]; } VkClearColorValue;
typedef struct {
    int sType;
    unsigned waitSemaphoreCount, commandBufferCount, signalSemaphoreCount;
    const int *pWaitSemaphores, *pWaitDstStageMask, *pCommandBuffers, *pSignalSemaphores;
} VkSubmitInfo;
typedef struct {
    int sType;
    unsigned waitSemaphoreCount, swapchainCount;
    const int *pWaitSemaphores, *pSwapchains;
    const unsigned *pImageIndices;
} VkPresentInfoKHR;
#define SCENE_FMT 100
#define HDR_FMT 101
#define FG_HDR_FMT 102
#define VK_FORMAT_R8G8B8A8_UNORM SCENE_FMT
#define VKP_FG_MAX_GENERATIONS 2
#define HDRC_OUT_PQ 1
#define HDRC_OUT_SDR 2
#define droiddeck_log(...) ((void)0)
#define LOGE(...) ((void)0)
struct droiddeck_color { uint32_t identity; };
'''


STUBS = r'''
static int noop(int handle, ...) { return VK_SUCCESS; }
static VkImageMemoryBarrier recorded_barriers[64];
static int barrier_count;
static int note_barriers(int cmd, int src_stage, int dst_stage, int flags,
                          unsigned memory_count, const VkMemoryBarrier *memory,
                          unsigned buffer_count, const void *buffers,
                          unsigned image_count, const VkImageMemoryBarrier *images) {
    for (unsigned i = 0; i < image_count; i++) {
        assert(barrier_count < 64);
        recorded_barriers[barrier_count++] = images[i];
    }
    return VK_SUCCESS;
}
static struct {
    int (*ResetCommandBuffer)(int, ...), (*BeginCommandBuffer)(int, ...);
    int (*CmdPipelineBarrier)(int, int, int, int, unsigned, const VkMemoryBarrier *,
                              unsigned, const void *, unsigned, const VkImageMemoryBarrier *);
    int (*CmdClearColorImage)(int, ...);
    int (*EndCommandBuffer)(int, ...), (*ResetFences)(int, ...);
    int (*QueueSubmit)(int, ...), (*QueuePresentKHR)(int, ...);
} g_vk = {noop, noop, note_barriers, noop, noop, noop, noop, noop};
static int g_dev_state, g_window = 1, g_swapchain, g_swap_extra;
static int g_colorspace_ext, g_swap_hdr_unavailable, g_swap_is_hdr, g_swap_hdr_want;
static int g_plain_frame, g_first_frame_done, g_base_black;
static int g_qfam, g_queue, g_dev, g_fence, g_swap_fmt = SCENE_FMT;
static int g_cmds[3], g_acqs[3], g_rnds[3], images[1] = {2}, *g_images = images;
static uint32_t g_swap_md_identity;
static VkExtent2D g_extent = {1920, 1080};
static struct { float kx, ky, off_x, off_y; int rx, ry, rw, rh; } g_map;
static struct { VkImage img; int w, h; } g_scene;
struct vkp_img_slot { VkImage img; int w, h; };
static struct vkp_img_slot g_hdrscene, g_fgscene;
static struct { int active, rw, rh; VkImage result; } g_pass;
static struct { int64_t present_ns, present_max_ns; unsigned presents; } g_perf;
static int effects_active = 1, framegen, cardboard;
static struct { int scaling; } g_cur = {4};
static int copy_count, effects_count, screen_count, hdr_count, fail_scene, fail_effects, postprocessing;
static int copied_w, copied_h, target_w, target_h;
static int effects_w, effects_h, output_w, output_h, logical_w, logical_h;
static VkImageBlit copied;
static struct vkp_draw recorded_draws[16];
static VkImageBlit recorded_blits[16];
static VkImage recorded_targets[16];
static int recorded_after_effects[16];
static void vkp_apply_window_request(void) {}
static void sc_layer_hide_base(void) {}
static void check_surface_changed(void) {}
static int dev_init(void) { return 0; }
static int vkp_framegen_extra_images(void) { return 0; }
static void destroy_swapchain(void) {}
static int acquire_image(int k, int wait, uint32_t *image, VkResult *result) {
    *image = 0; *result = VK_SUCCESS; return 0;
}
static void update_map(int w, int h) {
    g_map.kx = (float)g_extent.width / w; g_map.ky = (float)g_extent.height / h;
    g_map.rw = g_extent.width; g_map.rh = g_extent.height;
}
static int vkp_update_map(int w, int h) { update_map(w, h); return 0; }
static void send_hdr_metadata(const struct droiddeck_color *color) {}
static int vkp_effects_active(void) { return effects_active; }
static int vkp_effects_sync(void) { return 0; }
static int effect_count(const void *settings) { return postprocessing; }
static int vkp_framegen_active(void) { return framegen; }
static int vkp_cardboard_active(void) { return cardboard; }
static void vkp_cardboard_begin_frame(void) {}
static int vkp_framegen_format_ok(VkFormat format) { return 0; }
static int ensure_img(struct vkp_img_slot *slot, int w, int h, int format, int usage, const char *name) {
    slot->img = 3; slot->w = w; slot->h = h; return 0;
}
static void vk_format_short(int format, char *out, size_t size) { *out = 0; }
static int ensure_scene_image(int w, int h) {
    if (fail_scene) return -1;
    g_scene.img = 1; g_scene.w = w; g_scene.h = h; return 0;
}
static VkFilter vkp_effects_blit_filter(void) { return 0; }
static void blendp_begin_frame(void) {}
static int record_bars_clear(int cmd, int target, const VkImageBlit *blit) { return 1; }
static int compose_hdr(int cmd, const struct vkp_draw *draws, int n, const struct vkp_hdr_frame *hdr,
                       int w, int h, int target, int format, int output) {
    hdr_count++; return 0;
}
static void record_draw(int cmd, const struct vkp_draw *draw, const VkImageBlit *blit, int target,
                        int format, int w, int h, int filter) {
    assert(copy_count < 16);
    recorded_draws[copy_count] = *draw; recorded_blits[copy_count] = *blit;
    recorded_targets[copy_count] = target; recorded_after_effects[copy_count] = effects_count > 0;
    copy_count++; copied = *blit;
    copied_w = draw->dw; copied_h = draw->dh; target_w = w; target_h = h;
}
static void vkp_effects_set_formats(int input, int output) {}
static VkImage vkp_effects_run(int cmd, int source, int w, int h, int ow, int oh, int *rw, int *rh) {
    effects_count++; effects_w = w; effects_h = h; output_w = ow; output_h = oh;
    if (!hdr_count) assert(g_scene.w == w && g_scene.h == h);
    if (fail_effects) { *rw = w; *rh = h; return source; }
    *rw = ow; *rh = oh; return 4;
}
static int vkp_framegen_run(int cmd, int result, int depth, int w, int h, int format, int *generated) { return 0; }
static void record_screen_blit(int cmd, int result, int w, int h, int sw, int sh, int image, int filter, int cb) {
    screen_count++; logical_w = sw; logical_h = sh;
}
static void device_lost(const char *reason) {}
static int64_t perf_now(void) { return 0; }
static void perf_add(int64_t *sum, int64_t *max, unsigned *count, int64_t duration) {}
static VkResult timed_wait(int fence, uint64_t timeout) { return VK_SUCCESS; }
static void vkp_framegen_presented(int frames) {}
static const char *vk_result_name(int result) { return "success"; }
static void droiddeck_on_first_frame(void) {}
static void vkp_pass_abort(void) { g_pass.active = 0; }
'''


CASES = r'''
static struct vkp_image image = {.image = 10, .w = 1280, .h = 720, .sampled = 1};
static struct vkp_draw fullscreen(void) {
    return (struct vkp_draw){.img = &image, .sw = image.w, .sh = image.h, .dw = 1920, .dh = 1080};
}
static void reset(void) {
    copy_count = effects_count = screen_count = hdr_count = barrier_count = 0;
    effects_active = 1; g_cur.scaling = 4;
    framegen = cardboard = fail_scene = fail_effects = postprocessing = g_plain_frame = 0;
    g_pass.active = 0; g_extent = (VkExtent2D){1920, 1080};
    image.w = 1280; image.h = 720;
}
static void check_helper(void) {
    struct vkp_draw original = fullscreen(), before = original, selected;
    struct vkp_image image_before = image;
    assert(effects_source_draw(1920, 1080, &original, 1, &selected));
    assert(memcmp(&original, &before, sizeof(original)) == 0);
    assert(memcmp(&image, &image_before, sizeof(image)) == 0);
    assert(selected.img == original.img && selected.sw == 1280 && selected.sh == 720);
    assert(selected.dw == 1280 && selected.dh == 720);
    VkImageBlit blit;
    assert(draw_to_scene_blit(&selected, 1280, 720, &blit));
    assert(blit.srcOffsets[0].x == 0 && blit.srcOffsets[0].y == 0);
    assert(blit.dstOffsets[0].x == 0 && blit.dstOffsets[0].y == 0);
    assert(blit.srcOffsets[1].x == 1280 && blit.srcOffsets[1].y == 720);
    assert(blit.dstOffsets[1].x == 1280 && blit.dstOffsets[1].y == 720);
    image.w = 1920; image.h = 720;
    original = fullscreen();
    assert(effects_source_draw(1920, 1080, &original, 1, &selected));
    assert(selected.dw == 1920 && selected.dh == 720);
}
static void rejected(struct vkp_draw draw, int n) {
    struct vkp_draw selected = {0}, before = selected;
    assert(!effects_source_draw(1920, 1080, &draw, n, &selected));
    assert(memcmp(&selected, &before, sizeof(selected)) == 0);
}
static void check_rejections(void) {
    struct vkp_draw draw = fullscreen(), unused;
    assert(!effects_source_draw(1920, 1080, NULL, 1, &unused));
    rejected(draw, 0); rejected(draw, -1);
    draw.img = NULL; rejected(draw, 1);
    draw = fullscreen(); draw.blend = 1; rejected(draw, 1);
    draw = fullscreen(); draw.dx = 1; rejected(draw, 1);
    draw = fullscreen(); draw.dy = -1; rejected(draw, 1);
    draw = fullscreen(); draw.dw--; rejected(draw, 1);
    draw = fullscreen(); draw.dh++; rejected(draw, 1);
    draw = fullscreen(); draw.sx = 1; rejected(draw, 1);
    draw = fullscreen(); draw.sy = 0.5f; rejected(draw, 1);
    draw = fullscreen(); draw.sw--; rejected(draw, 1);
    draw = fullscreen(); draw.sh--; rejected(draw, 1);
    const int sizes[][2] = {{1920, 1080}, {1921, 720}, {1280, 1081}, {0, 720}, {1280, 0}, {-1, 720}};
    for (unsigned i = 0; i < sizeof(sizes) / sizeof(sizes[0]); i++) {
        image.w = sizes[i][0]; image.h = sizes[i][1]; rejected(fullscreen(), 1);
    }
    reset();
    struct vkp_image native = {.image = 13, .w = 1920, .h = 1080};
    struct vkp_draw layers[] = {fullscreen(), fullscreen()};
    layers[1].img = &native; layers[1].sw = 1920; layers[1].sh = 1080;
    assert(!effects_source_draw(1920, 1080, layers, 2, &unused));
    layers[1] = fullscreen(); layers[1].sx = 1; layers[1].sw--;
    assert(!effects_source_draw(1920, 1080, layers, 2, &unused));
}
static void run_path(int offscreen, const struct vkp_draw *draw, int n, const struct vkp_hdr_frame *hdr) {
    struct vkp_draw before[4];
    assert(n > 0 && n <= 4);
    memcpy(before, draw, n * sizeof(*draw));
    int rw = 0, rh = 0;
    assert((offscreen ? pass_begin_impl(1920, 1080, draw, n, hdr, &rw, &rh)
                      : render_impl(1920, 1080, draw, n, hdr, NULL)) == 0);
    assert(memcmp(draw, before, n * sizeof(*draw)) == 0);
    if (offscreen) assert(rw == (fail_effects ? effects_w : (int)g_extent.width) &&
                          rh == (fail_effects ? effects_h : (int)g_extent.height));
}
static void check_input(int w, int h) {
    assert(effects_count == 1 && effects_w == w && effects_h == h);
    assert(output_w == (int)g_extent.width && output_h == (int)g_extent.height);
}
static void check_path(int offscreen) {
    struct vkp_draw draw = fullscreen();
    run_path(offscreen, &draw, 1, NULL);
    check_input(1280, 720);
    assert(copy_count == 1 && copied_w == 1280 && copied_h == 720);
    assert(target_w == 1280 && target_h == 720);
    assert(copied.srcOffsets[1].x == copied.dstOffsets[1].x);
    assert(copied.srcOffsets[1].y == copied.dstOffsets[1].y);
    if (!offscreen) assert(screen_count == 1 && logical_w == 1920 && logical_h == 1080);
    reset(); image.w = 1920; image.h = 1080; draw = fullscreen();
    run_path(offscreen, &draw, 1, NULL); check_input(1920, 1080);
    reset(); g_extent = (VkExtent2D){2560, 1440}; draw = fullscreen();
    run_path(offscreen, &draw, 1, NULL); check_input(1280, 720);
    reset(); draw = fullscreen(); draw.dw = 1280; draw.dh = 720;
    int rw, rh;
    assert((offscreen ? pass_begin_impl(1280, 720, &draw, 1, NULL, &rw, &rh)
                      : render_impl(1280, 720, &draw, 1, NULL, NULL)) == 0);
    check_input(1280, 720);
}
static void check_gates(void) {
    for (int offscreen = 0; offscreen < 2; offscreen++) {
        reset(); g_cur.scaling = 0;
        struct vkp_draw draw = fullscreen();
        run_path(offscreen, &draw, 1, NULL); check_input(1920, 1080);
        assert(target_w == 1920 && target_h == 1080);
        reset();
        struct vkp_hdr_frame hdr = {0};
        run_path(offscreen, &draw, 1, &hdr); check_input(1920, 1080);
        assert(hdr_count == 1 && copy_count == 0);
        reset();
        struct vkp_draw desktop[] = {fullscreen(), fullscreen()};
        desktop[0].dw = 960;
        desktop[1].dx = 40; desktop[1].dy = 30; desktop[1].dw = 640; desktop[1].dh = 480;
        run_path(offscreen, desktop, 2, NULL); check_input(1920, 1080);
        reset(); draw = fullscreen(); draw.blend = 1;
        run_path(offscreen, &draw, 1, NULL); check_input(1920, 1080);
    }
    reset(); effects_active = 0; framegen = 1;
    struct vkp_draw draw = fullscreen();
    run_path(0, &draw, 1, NULL);
    assert(effects_count == 0 && target_w == 1920 && target_h == 1080);
    reset(); fail_scene = 1;
    run_path(0, &draw, 1, NULL);
    assert(effects_count == 0 && copied.dstOffsets[1].x == 1920 && copied.dstOffsets[1].y == 1080);
}
static void check_modes(void) {
    for (int mode = 0; mode <= 8; mode++) {
        for (int offscreen = 0; offscreen < 2; offscreen++) {
            reset(); g_cur.scaling = mode;
            int upscale = mode == 3 || mode == 4 || mode == 5 || mode == 7 || mode == 8;
            assert(vkp_effects_upscaling() == upscale);
            struct vkp_draw draw = fullscreen();
            run_path(offscreen, &draw, 1, NULL);
            check_input(upscale ? 1280 : 1920, upscale ? 720 : 1080);
        }
    }
}
static struct vkp_image background = {.image = 11, .w = 320, .h = 240, .sampled = 1};
static struct vkp_image cursor = {.image = 12, .w = 24, .h = 24, .dmabuf = 1, .sampled = 1};
static struct vkp_draw background_draw(void) {
    return (struct vkp_draw){.img = &background, .sw = 320, .sh = 240,
                            .dx = 40, .dy = 30, .dw = 640, .dh = 480, .blend = 1};
}
static struct vkp_draw cursor_draw(void) {
    return (struct vkp_draw){.img = &cursor, .sw = 24, .sh = 24,
                            .dx = 480, .dy = 270, .dw = 24, .dh = 24, .blend = 1};
}
static void check_occlusion(void) {
    struct vkp_draw layers[] = {background_draw(), fullscreen()}, selected;
    struct vkp_draw before[2];
    memcpy(before, layers, sizeof(layers));
    assert(effects_source_draw(1920, 1080, layers, 2, &selected) == 2);
    assert(selected.img == &image && selected.dw == 1280 && selected.dh == 720);
    assert(memcmp(layers, before, sizeof(layers)) == 0);
    for (int offscreen = 0; offscreen < 2; offscreen++) {
        reset();
        run_path(offscreen, layers, 2, NULL); check_input(1280, 720);
        assert(copy_count == 1 && recorded_draws[0].img == &image);
        assert(recorded_draws[0].dw == 1280 && recorded_draws[0].dh == 720);
        assert(target_w == 1280 && target_h == 720);
        assert(copied.srcOffsets[1].x == 1280 && copied.dstOffsets[1].x == 1280);
        assert(copied.srcOffsets[1].y == 720 && copied.dstOffsets[1].y == 720);
        if (!offscreen) assert(logical_w == 1920 && logical_h == 1080);
        reset(); g_cur.scaling = 6;
        run_path(offscreen, layers, 2, NULL); check_input(1920, 1080);
        assert(copy_count == 2);
        reset();
        struct vkp_hdr_frame hdr = {0};
        run_path(offscreen, layers, 2, &hdr); check_input(1920, 1080);
        assert(hdr_count == 1 && copy_count == 0);
    }
}
static void check_overlay(void) {
    for (int offscreen = 0; offscreen < 2; offscreen++) {
        for (int larger_output = 0; larger_output <= 1; larger_output++) {
            reset();
            if (larger_output) g_extent = (VkExtent2D){2560, 1440};
            struct vkp_draw layers[] = {background_draw(), fullscreen(), cursor_draw()}, selected;
            assert(effects_source_draw(1920, 1080, layers, 3, &selected) == 2);
            run_path(offscreen, layers, 3, NULL); check_input(1280, 720);
            assert(copy_count == 2 && recorded_draws[0].img == &image);
            assert(!recorded_after_effects[0] && recorded_targets[0] == g_scene.img);
            assert(recorded_draws[1].img == &cursor && recorded_draws[1].blend);
            assert(recorded_after_effects[1] && recorded_targets[1] == 4);
            assert(recorded_blits[1].dstOffsets[0].x == (larger_output ? 640 : 480));
            assert(recorded_blits[1].dstOffsets[0].y == (larger_output ? 360 : 270));
            assert(recorded_blits[1].dstOffsets[1].x - recorded_blits[1].dstOffsets[0].x ==
                   (larger_output ? 32 : 24));
            assert(recorded_blits[1].dstOffsets[1].y - recorded_blits[1].dstOffsets[0].y ==
                   (larger_output ? 32 : 24));
        }
        for (int blend = 0; blend <= 1; blend++) {
            reset();
            struct vkp_draw layers[] = {background_draw(), fullscreen(), background_draw(), cursor_draw()};
            layers[2].blend = blend;
            layers[3].sx = 4; layers[3].sy = 2; layers[3].sw = 12; layers[3].sh = 18;
            run_path(offscreen, layers, 4, NULL); check_input(1280, 720);
            assert(copy_count == 3 && recorded_draws[0].img == &image);
            assert(recorded_draws[1].img == &background);
            assert(recorded_draws[1].blend == blend && recorded_after_effects[1]);
            assert(recorded_draws[2].img == &cursor && recorded_after_effects[2]);
            assert(recorded_blits[1].dstOffsets[0].x == 40);
            assert(recorded_blits[1].dstOffsets[0].y == 30);
            assert(recorded_blits[2].srcOffsets[0].x == 4 && recorded_blits[2].srcOffsets[0].y == 2);
            assert(recorded_blits[2].srcOffsets[1].x == 16 && recorded_blits[2].srcOffsets[1].y == 20);
            assert(recorded_targets[1] == 4 && recorded_targets[2] == 4);
            assert(target_w == 1920 && target_h == 1080);
        }
    }
}
static void check_effect_failure(void) {
    for (int offscreen = 0; offscreen < 2; offscreen++) {
        reset(); fail_effects = 1;
        struct vkp_draw layers[] = {background_draw(), fullscreen(), cursor_draw()};
        run_path(offscreen, layers, 3, NULL); check_input(1280, 720);
        assert(copy_count == 2 && recorded_targets[1] == g_scene.img && recorded_after_effects[1]);
        assert(target_w == 1280 && target_h == 720);
        assert(recorded_blits[1].dstOffsets[0].x == 320 && recorded_blits[1].dstOffsets[0].y == 180);
        assert(recorded_blits[1].dstOffsets[1].x == 336 && recorded_blits[1].dstOffsets[1].y == 196);
    }
}
static int find_barrier(VkImage image, int from, int to) {
    for (int i = 0; i < barrier_count; i++)
        if (recorded_barriers[i].image == image && recorded_barriers[i].oldLayout == from &&
            recorded_barriers[i].newLayout == to) return i;
    return -1;
}
static void check_overlay_barriers(void) {
    for (int offscreen = 0; offscreen < 2; offscreen++) {
        reset();
        struct vkp_draw layers[] = {background_draw(), fullscreen(), cursor_draw()};
        run_path(offscreen, layers, 3, NULL); check_input(1280, 720);
        int acquired = find_barrier(cursor.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        assert(acquired >= 0 && recorded_barriers[acquired].srcQueueFamilyIndex == VK_QUEUE_FAMILY_FOREIGN_EXT);
        int writable = find_barrier(4, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        int readable = find_barrier(4, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        assert(writable > acquired && readable > writable);
    }
}
static void check_postprocessing(void) {
    for (int offscreen = 0; offscreen < 2; offscreen++) {
        reset(); postprocessing = 1;
        struct vkp_draw layers[] = {background_draw(), fullscreen(), cursor_draw()}, selected;
        assert(vkp_effects_postprocessing());
        assert(effects_source_draw(1920, 1080, layers, 2, &selected) == 2);
        assert(!effects_source_draw(1920, 1080, layers, 3, &selected));
        run_path(offscreen, layers, 3, NULL); check_input(1920, 1080);
        assert(copy_count == 3);
        for (int i = 0; i < copy_count; i++)
            assert(!recorded_after_effects[i] && recorded_targets[i] == g_scene.img);
    }
}
static void check_allocation(void) {
    fail_scene = 1;
    struct vkp_draw layers[] = {background_draw(), fullscreen()};
    run_path(0, layers, 2, NULL);
    assert(effects_count == 0 && copy_count == 2);
    assert(recorded_draws[0].img == &background && recorded_draws[1].img == &image);
    assert(recorded_draws[0].dw == 640 && recorded_draws[0].dh == 480);
    assert(recorded_draws[1].dw == 1920 && recorded_draws[1].dh == 1080);
    assert(recorded_blits[0].dstOffsets[0].x == 40 && recorded_blits[0].dstOffsets[0].y == 30);
    assert(recorded_blits[1].dstOffsets[1].x == 1920 && recorded_blits[1].dstOffsets[1].y == 1080);
    assert(target_w == 1920 && target_h == 1080);
}
int main(int argc, char **argv) {
    assert(argc == 2); reset();
    if (!strcmp(argv[1], "helper")) check_helper();
    else if (!strcmp(argv[1], "rejections")) check_rejections();
    else if (!strcmp(argv[1], "render")) check_path(0);
    else if (!strcmp(argv[1], "pass")) check_path(1);
    else if (!strcmp(argv[1], "gates")) check_gates();
    else if (!strcmp(argv[1], "modes")) check_modes();
    else if (!strcmp(argv[1], "occlusion")) check_occlusion();
    else if (!strcmp(argv[1], "overlay")) check_overlay();
    else if (!strcmp(argv[1], "allocation")) check_allocation();
    else if (!strcmp(argv[1], "effect-failure")) check_effect_failure();
    else if (!strcmp(argv[1], "overlay-barriers")) check_overlay_barriers();
    else if (!strcmp(argv[1], "postprocessing")) check_postprocessing();
    else assert(0);
    puts("PASS");
}
'''


if __name__ == '__main__':
    unittest.main()
