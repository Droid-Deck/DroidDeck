#include <assert.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "vk_present.h"
#include "hdr_compose.h"
#include "blend_pass.h"
#include "effects_chain.h"
#include "present_types.inc"

#define DIM 64
#define IMAGES 8
struct model_image {
    int live, w, h;
    VkImageLayout layout;
    uint32_t pixels[DIM][DIM];
    unsigned char written[DIM][DIM];
};
static struct model_image models[IMAGES];
static struct { VkImage img; VkDeviceMemory mem; int w, h; } g_scene;
static struct vkp_img_slot g_mixed, g_hdrscene;
static VkExtent2D g_extent;
static VkCommandBuffer g_cmds[1];
static uint32_t g_qfam;
static struct { int active; VkImage result; int rw, rh; } g_pass;
static struct vkp_image inputs[3];
static uint64_t trace;
static int clears, blits, blends, frame_seed, ensure_fail, encode_fail, map_fail, blend_fail, effects_on;

static void hash(uint64_t *state, uint64_t value) {
    *state ^= value;
    *state *= UINT64_C(1099511628211);
}
static void record(uint64_t value) { hash(&trace, value); }
static void number(float value) {
    uint32_t bits;
    memcpy(&bits, &value, sizeof(bits));
    record(bits);
}
static VkImage image(int id) { return (VkImage)(uintptr_t)id; }
static int image_id(VkImage handle) {
    int id = (int)(uintptr_t)handle;
    assert(id > 0 && id < IMAGES);
    return id;
}
static struct model_image *model(VkImage handle) {
    struct model_image *m = &models[image_id(handle)];
    assert(m->live);
    return m;
}
static void undefined_contents(struct model_image *m, int id) {
    for (int y = 0; y < m->h; y++)
        for (int x = 0; x < m->w; x++) {
            m->pixels[y][x] = UINT32_C(0x75432100) ^ (uint32_t)(frame_seed * 97 + id * 17 + y * DIM + x);
            m->written[y][x] = 0;
        }
}
static void allocate_model(int id, int w, int h) {
    assert(w > 0 && h > 0 && w <= DIM && h <= DIM);
    struct model_image *m = &models[id];
    m->live = 1; m->w = w; m->h = h;
    m->layout = VK_IMAGE_LAYOUT_UNDEFINED;
    undefined_contents(m, id);
}
static void blit_rect(uint64_t *state, const VkImageBlit *b) {
    for (int i = 0; i < 2; i++) {
        hash(state, (uint32_t)b->srcOffsets[i].x); hash(state, (uint32_t)b->srcOffsets[i].y);
        hash(state, (uint32_t)b->srcOffsets[i].z);
        hash(state, (uint32_t)b->dstOffsets[i].x); hash(state, (uint32_t)b->dstOffsets[i].y);
        hash(state, (uint32_t)b->dstOffsets[i].z);
    }
    hash(state, b->srcSubresource.aspectMask); hash(state, b->srcSubresource.mipLevel);
    hash(state, b->srcSubresource.baseArrayLayer); hash(state, b->srcSubresource.layerCount);
    hash(state, b->dstSubresource.aspectMask); hash(state, b->dstSubresource.mipLevel);
    hash(state, b->dstSubresource.baseArrayLayer); hash(state, b->dstSubresource.layerCount);
}
static uint32_t over(uint32_t src, uint32_t dst) {
    unsigned alpha = src >> 24, out = 0;
    for (int channel = 0; channel < 3; channel++) {
        unsigned s = (src >> (8 * channel)) & 255, d = (dst >> (8 * channel)) & 255;
        out |= ((s * alpha + d * (255 - alpha)) / 255) << (8 * channel);
    }
    return out | (alpha + (dst >> 24) * (255 - alpha) / 255) << 24;
}
static void model_blit(VkImage src, VkImage dst, const VkImageBlit *b, int blend) {
    struct model_image *s = model(src), *d = model(dst);
    int x0 = b->dstOffsets[0].x, x1 = b->dstOffsets[1].x;
    int y0 = b->dstOffsets[0].y, y1 = b->dstOffsets[1].y;
    assert(x0 >= 0 && y0 >= 0 && x1 <= d->w && y1 <= d->h && x1 > x0 && y1 > y0);
    for (int y = y0; y < y1; y++)
        for (int x = x0; x < x1; x++) {
            int sx = b->srcOffsets[0].x + (x - x0) * (b->srcOffsets[1].x - b->srcOffsets[0].x) / (x1 - x0);
            int sy = b->srcOffsets[0].y + (y - y0) * (b->srcOffsets[1].y - b->srcOffsets[0].y) / (y1 - y0);
            assert(sx >= 0 && sy >= 0 && sx < s->w && sy < s->h && s->written[sy][sx]);
            if (blend) assert(d->written[y][x]);
            d->pixels[y][x] = blend ? over(s->pixels[sy][sx], d->pixels[y][x]) : s->pixels[sy][sx];
            d->written[y][x] = 1;
        }
}
static void barrier(VkCommandBuffer cmd, VkPipelineStageFlags src, VkPipelineStageFlags dst,
                    VkDependencyFlags flags, uint32_t nm, const VkMemoryBarrier *mb,
                    uint32_t nb, const VkBufferMemoryBarrier *bb, uint32_t ni, const VkImageMemoryBarrier *ib) {
    record(1); record(src); record(dst); record(flags); record(nm); record(nb); record(ni);
    for (uint32_t i = 0; i < nm; i++) { record(mb[i].srcAccessMask); record(mb[i].dstAccessMask); }
    for (uint32_t i = 0; i < ni; i++) {
        int id = image_id(ib[i].image);
        record(id); record(ib[i].oldLayout); record(ib[i].newLayout);
        record(ib[i].srcAccessMask); record(ib[i].dstAccessMask);
        record(ib[i].srcQueueFamilyIndex); record(ib[i].dstQueueFamilyIndex);
        record(ib[i].subresourceRange.aspectMask); record(ib[i].subresourceRange.baseMipLevel);
        record(ib[i].subresourceRange.levelCount); record(ib[i].subresourceRange.baseArrayLayer);
        record(ib[i].subresourceRange.layerCount);
        model(ib[i].image)->layout = ib[i].newLayout;
        if (id >= 4 && ib[i].oldLayout == VK_IMAGE_LAYOUT_UNDEFINED &&
            ib[i].newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
            undefined_contents(model(ib[i].image), id);
    }
}
static void clear_image(VkCommandBuffer cmd, VkImage img, VkImageLayout layout,
                         const VkClearColorValue *value, uint32_t n, const VkImageSubresourceRange *range) {
    assert(layout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && n == 1);
    assert(value->float32[0] == 0 && value->float32[1] == 0 && value->float32[2] == 0 && value->float32[3] == 1);
    struct model_image *m = model(img);
    assert(m->layout == layout);
    for (int y = 0; y < m->h; y++)
        for (int x = 0; x < m->w; x++) {
            m->pixels[y][x] = UINT32_C(0xff000000);
            m->written[y][x] = 1;
        }
    clears++;
}
static void blit(VkCommandBuffer cmd, VkImage src, VkImageLayout sl, VkImage dst, VkImageLayout dl,
                  uint32_t n, const VkImageBlit *regions, VkFilter filter) {
    assert(n == 1);
    assert(model(src)->layout == sl && model(dst)->layout == dl);
    record(2); record(image_id(src)); record(sl); record(image_id(dst)); record(dl); record(n); record(filter);
    blit_rect(&trace, regions);
    model_blit(src, dst, regions, 0);
    blits++;
}
static VkResult reset_cmd(VkCommandBuffer cmd, VkCommandBufferResetFlags flags) {
    record(3); record(flags); return VK_SUCCESS;
}
static VkResult begin_cmd(VkCommandBuffer cmd, const VkCommandBufferBeginInfo *info) {
    record(4); record(info->flags); return VK_SUCCESS;
}
static VkResult end_cmd(VkCommandBuffer cmd) { record(5); return VK_SUCCESS; }
static struct {
    PFN_vkCmdPipelineBarrier CmdPipelineBarrier;
    PFN_vkCmdClearColorImage CmdClearColorImage;
    PFN_vkCmdBlitImage CmdBlitImage;
    PFN_vkResetCommandBuffer ResetCommandBuffer;
    PFN_vkBeginCommandBuffer BeginCommandBuffer;
    PFN_vkEndCommandBuffer EndCommandBuffer;
} g_vk = {barrier, clear_image, blit, reset_cmd, begin_cmd, end_cmd};

static int ensure_scene_image(int w, int h) {
    record(6); record(w); record(h);
    if (ensure_fail) return -1;
    if (!g_scene.img || g_scene.w != w || g_scene.h != h) allocate_model(4, w, h);
    g_scene.img = image(4); g_scene.w = w; g_scene.h = h;
    return 0;
}
static int ensure_img(struct vkp_img_slot *s, int w, int h, VkFormat fmt, VkImageUsageFlags usage, const char *what) {
    int id = s == &g_mixed ? 5 : 6;
    record(7); record(id); record(w); record(h); record(fmt); record(usage);
    if (ensure_fail) return -1;
    if (!s->img || s->w != w || s->h != h || s->fmt != fmt) allocate_model(id, w, h);
    s->img = image(id); s->w = w; s->h = h; s->fmt = fmt;
    return 0;
}
void blendp_begin_frame(void) { record(8); }
int blendp_draw(VkCommandBuffer cmd, VkImage src, VkFormat sf, int sw, int sh, int general,
                VkImage dst, VkFormat df, int dw, int dh, const VkImageBlit *b) {
    record(9); record(image_id(src)); record(sf); record(sw); record(sh); record(general);
    record(image_id(dst)); record(df); record(dw); record(dh); blit_rect(&trace, b); record(blend_fail);
    assert(model(src)->layout == (general ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL));
    assert(model(dst)->layout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    if (blend_fail) return -1;
    model_blit(src, dst, b, 1); blends++;
    return 0;
}
int hdrc_encode(VkCommandBuffer cmd, VkImage mixed, VkImage out, VkFormat fmt, int w, int h,
                const struct hdrc_params *p) {
    record(10); record(image_id(mixed)); record(image_id(out)); record(fmt); record(w); record(h);
    record(p->mode); number(p->sdr_white_nits); number(p->peak_nits); record(p->count); record(p->hdr_mask);
    for (int i = 0; i < p->count; i++)
        for (int j = 0; j < 4; j++) number(p->rects[i][j]);
    record(encode_fail);
    struct model_image *src = model(mixed), *dst = model(out);
    assert(src->layout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && dst->layout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
            assert(src->written[y][x]);
            if (!encode_fail) {
                dst->pixels[y][x] = src->pixels[y][x];
                dst->written[y][x] = 1;
            }
        }
    return encode_fail ? -1 : 0;
}
static float droiddeck_color_sdr_white(void) { return 203.0f; }
void droiddeck_log(const char *tag, const char *fmt, ...) { }
void vkp_pass_abort(void) { record(11); g_pass.active = 0; }
int vkp_update_map(int w, int h) { record(12); record(w); record(h); return map_fail ? -1 : 0; }
int vkp_effects_active(void) { return effects_on; }
void vkp_effects_set_formats(VkFormat src, VkFormat dst) { record(13); record(src); record(dst); }
VkImage vkp_effects_run(VkCommandBuffer cmd, VkImage scene, int sw, int sh, int ow, int oh, int *rw, int *rh) {
    record(14); record(image_id(scene)); record(sw); record(sh); record(ow); record(oh);
    struct model_image *m = model(scene);
    assert(m->layout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    for (int y = 0; y < sh; y++)
        for (int x = 0; x < sw; x++) assert(m->written[y][x]);
    *rw = sw; *rh = sh;
    return scene;
}

#include "present_functions.inc"

#if HAS_COVER_HELPERS
static int legacy_cover(const struct vkp_draw *draws, int n, int sw, int sh, int pass, VkImageBlit *out) {
    int covered = 0;
    for (int i = n - 1; i >= 0 && !covered; i--) {
        const struct vkp_draw *d = &draws[i];
        if (!d->img || d->blend || d->dx > 0 || d->dy > 0 || d->dx + d->dw < sw || d->dy + d->dh < sh) continue;
        covered = pass ? draw_to_scene_blit(d, sw, sh, out) : draw_to_blit(d, out);
    }
    return covered;
}
static void coverage_reference(void) {
    uint32_t seed = 97;
    for (int i = 0; i < 10000; i++) {
        struct vkp_draw d[3];
        for (int j = 0; j < 3; j++) {
            seed = seed * 1664525u + 1013904223u;
            d[j] = (struct vkp_draw){.img = seed & 1 ? &inputs[j] : NULL,
                .sx = 0, .sy = 0, .sw = 8, .sh = 6, .dx = (int)(seed % 7) - 3,
                .dy = (int)((seed >> 3) % 7) - 3, .dw = 4 + (int)((seed >> 6) % 10),
                .dh = 3 + (int)((seed >> 10) % 9), .blend = (int)((seed >> 20) & 1)};
        }
        g_extent = (VkExtent2D){24, 20};
        g_map.kx = g_map.ky = i % 2 ? 1.5f : 1.0f;
        g_map.off_x = 2; g_map.off_y = 1; g_map.rx = 1; g_map.ry = 0; g_map.rw = 22; g_map.rh = 19;
        for (int pass = 0; pass <= 1; pass++) {
            VkImageBlit old, now;
            int a = legacy_cover(d, i % 4, 8, 6, pass, &old);
            int b = find_opaque_cover(d, i % 4, 8, 6, pass, &now);
            assert(a == b);
            if (a) {
                uint64_t ah = 0, bh = 0;
                blit_rect(&ah, &old); blit_rect(&bh, &now);
                assert(ah == bh);
            }
        }
    }
}
#endif

static int arg(const char *text, int low, int high) {
    char *end;
    long value = strtol(text, &end, 10);
    if (!*text || *end || value < low || value > high) {
        fprintf(stderr, "invalid argument: %s\n", text);
        exit(2);
    }
    return (int)value;
}
static void setup_source(int id, int w, int h, int fmt, int dmabuf, int unsampled) {
    static const VkFormat formats[] = {VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_UNORM,
                                      VK_FORMAT_A2B10G10R10_UNORM_PACK32, VK_FORMAT_R16G16B16A16_SFLOAT};
    struct vkp_image *im = &inputs[id - 1];
    if (im->w != w || im->h != h) im->in_general = 0;
    im->image = image(id); im->w = w; im->h = h; im->fmt = formats[fmt];
    im->dmabuf = dmabuf; im->sampled = !unsampled;
    allocate_model(id, w, h);
    models[id].layout = dmabuf ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL :
                       im->in_general ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_PREINITIALIZED;
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
            models[id].pixels[y][x] = UINT32_C(0x80000000) | ((uint32_t)(x * 19 + y * 31 + frame_seed * 23 + id) & 0x00ffffff);
            models[id].written[y][x] = 1;
        }
}
int main(int argc, char **argv) {
    g_cmds[0] = (VkCommandBuffer)(uintptr_t)1;
    for (int i = 0; i < 3; i++) setup_source(i + 1, 8, 6, 0, 0, 0);
    if (argc == 2 && !strcmp(argv[1], "cover")) {
#if HAS_COVER_HELPERS
        coverage_reference();
        puts("PASS"); return 0;
#else
        fputs("coverage helper is absent\n", stderr); return 1;
#endif
    }
    if (argc != 9) { fputs("path case width height format dmabuf effects frames required\n", stderr); return 2; }
    int path = arg(argv[1], 0, 2), scenario = arg(argv[2], 0, 23);
    int width = arg(argv[3], 3, 32), height = arg(argv[4], 3, 32), fmt = arg(argv[5], 0, 3);
    int dmabuf = arg(argv[6], 0, 1), fx = arg(argv[7], 0, 1), frames = arg(argv[8], 1, 3);
    trace = UINT64_C(1469598103934665603);
    int result = 0;
    for (int frame = 0; frame < frames; frame++) {
        int w = width + (scenario == 23 && frame % 2), h = height + (scenario == 23 && frame % 2);
        frame_seed = frame + 1;
        g_extent = (VkExtent2D){(uint32_t)w, (uint32_t)h};
        memset(&g_map, 0, sizeof(g_map));
        g_map.kx = g_map.ky = 1; g_map.rw = w; g_map.rh = h;
        ensure_fail = scenario == 13; encode_fail = scenario == 14; map_fail = scenario == 15;
        blend_fail = scenario == 16; effects_on = fx;
        g_pass.active = scenario == 19;
        for (int i = 0; i < 3; i++) setup_source(i + 1, w, h, fmt, dmabuf, scenario == 17);
        struct vkp_draw d[8];
        for (int i = 0; i < 8; i++)
            d[i] = (struct vkp_draw){.img = &inputs[i % 3], .sw = (float)w, .sh = (float)h, .dw = w, .dh = h};
        int n = 1;
        if (scenario == 1 || scenario == 16 || scenario == 17) d[0].blend = 1;
        if (scenario == 2) { d[0].dx = 1; d[0].dw--; }
        if (scenario == 3) d[0].dw--;
        if (scenario == 4) { d[0].dy = 1; d[0].dh--; }
        if (scenario == 5) d[0].dh--;
        if (scenario == 6) { d[0].dx = d[0].dy = -1; d[0].dw += 2; d[0].dh += 2; }
        if (scenario == 7) { d[0].sx = d[0].sy = 1; d[0].sw -= 2; d[0].sh -= 2; }
        if (scenario == 8) { n = 2; d[1].blend = 1; d[1].dw /= 2; }
        if (scenario == 9) d[0].img = NULL;
        if (scenario == 10) d[0].sw = 0;
        if (scenario == 11) d[0].dx = w + 1;
        if (scenario == 12) n = 0;
        if (scenario == 18) n = 8;
        unsigned char hdr[8] = {1, 0, 1, 0, 1, 0, 1, 0};
        struct vkp_hdr_frame hf = {.is_hdr = hdr, .peak_nits = scenario == 21 ? -1.0f : 800.0f,
                                   .tonemap = scenario == 22};
        int rw = 0, rh = 0;
        if (path == 0)
            result = vkp_pass_begin(w, h, d, n, &rw, &rh);
        else if (path == 1) {
            allocate_model(7, w, h);
            models[7].layout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            for (int i = 0; i < 3; i++)
                models[i + 1].layout = dmabuf ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL : VK_IMAGE_LAYOUT_GENERAL;
            result = compose_hdr(g_cmds[0], d, n, scenario == 20 ? NULL : &hf, w, h, image(7),
                                 HDR_FMT, hf.tonemap ? HDRC_OUT_SDR : HDRC_OUT_PQ);
        } else
            result = vkp_pass_begin_hdr(w, h, d, n, scenario == 20 ? NULL : &hf, &rw, &rh);
        record((uint32_t)result); record(rw); record(rh);
    }
    uint64_t pixels = UINT64_C(1469598103934665603);
    for (int id = 4; id < IMAGES; id++) {
        struct model_image *m = &models[id];
        hash(&pixels, m->live);
        if (!m->live) continue;
        hash(&pixels, m->w); hash(&pixels, m->h);
        hash(&pixels, m->layout);
        for (int y = 0; y < m->h; y++)
            for (int x = 0; x < m->w; x++) {
                hash(&pixels, m->written[y][x]); hash(&pixels, m->pixels[y][x]);
            }
    }
    printf("clears=%d blits=%d blends=%d result=%d trace=%016" PRIx64 " pixels=%016" PRIx64 "\n",
           clears, blits, blends, result, trace, pixels);
    return 0;
}
