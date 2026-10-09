/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * bcn_check: uploads random blocks of every BC format (every BC6H and BC7 mode forced in turn),
 * blits the texture to RGBA32F and compares each texel with bcdec's CPU decode. Run it natively
 * (the GPU's own BC decoder) and through the bridge with BC emulated.
 */
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

#include <dlfcn.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define BCDEC_IMPLEMENTATION
#define BCDEC_BC4BC5_PRECISE
#include "../third_party/bcdec/bcdec.h"

#define TEX 64 /* 16x16 blocks */

static PFN_vkGetInstanceProcAddr gipa;
static VkInstance inst;
static VkPhysicalDevice pd;
static VkDevice dev;
static VkQueue queue;
static VkCommandPool pool;
static VkPhysicalDeviceMemoryProperties mp;
#define F(n) static PFN_##n n
F(vkCreateInstance); F(vkEnumeratePhysicalDevices); F(vkGetPhysicalDeviceMemoryProperties); F(vkCreateDevice);
F(vkGetDeviceProcAddr); F(vkGetPhysicalDeviceFeatures); F(vkGetPhysicalDeviceFormatProperties); F(vkGetDeviceQueue);
F(vkCreateCommandPool); F(vkAllocateCommandBuffers); F(vkBeginCommandBuffer); F(vkEndCommandBuffer); F(vkQueueSubmit);
F(vkQueueWaitIdle); F(vkCreateBuffer); F(vkGetBufferMemoryRequirements); F(vkAllocateMemory); F(vkBindBufferMemory);
F(vkMapMemory); F(vkCreateImage); F(vkGetImageMemoryRequirements); F(vkBindImageMemory); F(vkCmdPipelineBarrier);
F(vkCmdCopyBufferToImage); F(vkCmdBlitImage); F(vkCmdCopyImageToBuffer); F(vkDestroyImage); F(vkDestroyBuffer);
F(vkFreeMemory); F(vkGetPhysicalDeviceProperties);
#undef F

static uint32_t find_type(uint32_t bits, VkMemoryPropertyFlags want)
{
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & want) == want) return i;
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
        if (bits & (1u << i)) return i;
    return 0;
}

static VkDeviceMemory bind_mem(VkMemoryRequirements r, VkMemoryPropertyFlags want)
{
    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, NULL, r.size, find_type(r.memoryTypeBits, want)};
    VkDeviceMemory m;
    vkAllocateMemory(dev, &ai, NULL, &m);
    return m;
}

static uint64_t rng = 0x9E3779B97F4A7C15ull;
static uint32_t rnd(void)
{
    rng ^= rng << 13;
    rng ^= rng >> 7;
    rng ^= rng << 17;
    return (uint32_t)(rng >> 16);
}

/* Random block; for BC6H/BC7 the mode is chosen by `mode` (cycled per block). */
static void make_block(VkFormat f, uint8_t *b, int bytes, int mode)
{
    for (int i = 0; i < bytes; i++) b[i] = (uint8_t)rnd();
    if (f == VK_FORMAT_BC7_UNORM_BLOCK) {
        int m = mode % 8;
        b[0] = (uint8_t)((b[0] & ~((1u << (m + 1)) - 1)) | (1u << m));
    } else if (f == VK_FORMAT_BC6H_UFLOAT_BLOCK || f == VK_FORMAT_BC6H_SFLOAT_BLOCK) {
        static const uint8_t codes[14] = {0, 1, 2, 6, 10, 14, 18, 22, 26, 30, 3, 7, 11, 15};
        int c = codes[mode % 14];
        if (c < 2) b[0] = (uint8_t)((b[0] & ~3u) | c);
        else b[0] = (uint8_t)((b[0] & ~31u) | c);
    }
}

static float h2f(uint16_t h)
{
    uint32_t s = (h >> 15) & 1, e = (h >> 10) & 31, m = h & 1023;
    float v;
    if (e == 0) v = ldexpf((float)m, -24);
    else if (e == 31) v = m ? NAN : INFINITY;
    else v = ldexpf((float)(m | 1024), (int)e - 25);
    return s ? -v : v;
}

/* Reference texel (RGBA float) at (x, y) of the texture made from `blocks`. */
static void reference(VkFormat f, const uint8_t *blocks, int bytes, int x, int y, float out[4])
{
    const uint8_t *b = blocks + ((y / 4) * (TEX / 4) + (x / 4)) * bytes;
    int tx = x & 3, ty = y & 3;
    uint8_t px8[16 * 4];
    int8_t spx[16 * 2];
    uint16_t px16[16 * 3];
    out[0] = out[1] = out[2] = 0;
    out[3] = 1;
    switch (f) {
    case VK_FORMAT_BC1_RGBA_UNORM_BLOCK: bcdec_bc1(b, px8, 16); break;
    case VK_FORMAT_BC2_UNORM_BLOCK: bcdec_bc2(b, px8, 16); break;
    case VK_FORMAT_BC3_UNORM_BLOCK: bcdec_bc3(b, px8, 16); break;
    case VK_FORMAT_BC7_UNORM_BLOCK: bcdec_bc7(b, px8, 16); break;
    case VK_FORMAT_BC4_UNORM_BLOCK: bcdec_bc4(b, px8, 4, 0); out[0] = px8[ty * 4 + tx] / 255.0f; return;
    case VK_FORMAT_BC4_SNORM_BLOCK: bcdec_bc4(b, spx, 4, 1); out[0] = fmaxf(spx[ty * 4 + tx] / 127.0f, -1.0f); return;
    case VK_FORMAT_BC5_UNORM_BLOCK:
        bcdec_bc5(b, px8, 8, 0);
        out[0] = px8[(ty * 4 + tx) * 2] / 255.0f;
        out[1] = px8[(ty * 4 + tx) * 2 + 1] / 255.0f;
        return;
    case VK_FORMAT_BC5_SNORM_BLOCK:
        bcdec_bc5(b, spx, 8, 1);
        out[0] = fmaxf(spx[(ty * 4 + tx) * 2] / 127.0f, -1.0f);
        out[1] = fmaxf(spx[(ty * 4 + tx) * 2 + 1] / 127.0f, -1.0f);
        return;
    case VK_FORMAT_BC6H_UFLOAT_BLOCK:
    case VK_FORMAT_BC6H_SFLOAT_BLOCK:
        bcdec_bc6h_half(b, px16, 12, f == VK_FORMAT_BC6H_SFLOAT_BLOCK);
        for (int c = 0; c < 3; c++) out[c] = h2f(px16[(ty * 4 + tx) * 3 + c]);
        return;
    default: return;
    }
    for (int c = 0; c < 4; c++) out[c] = px8[(ty * 4 + tx) * 4 + c] / 255.0f;
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

static void barrier(VkCommandBuffer cb, VkImage img, VkImageLayout from, VkImageLayout to)
{
    VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, NULL, VK_ACCESS_MEMORY_WRITE_BIT,
                              VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, from, to, VK_QUEUE_FAMILY_IGNORED,
                              VK_QUEUE_FAMILY_IGNORED, img, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, NULL, 0, NULL, 1, &b);
}

static int failures;

static void test_format(VkFormat f, const char *name, float tol)
{
    int bytes = (f == VK_FORMAT_BC1_RGBA_UNORM_BLOCK || f == VK_FORMAT_BC4_UNORM_BLOCK || f == VK_FORMAT_BC4_SNORM_BLOCK) ? 8 : 16;
    int nblocks = (TEX / 4) * (TEX / 4);
    uint8_t *blocks = malloc((size_t)nblocks * bytes);
    for (int i = 0; i < nblocks; i++) make_block(f, blocks + i * bytes, bytes, i);
    VkFormatProperties fp;
    vkGetPhysicalDeviceFormatProperties(pd, f, &fp);
    if (!(fp.optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_SRC_BIT)) {
        printf("SKIP %s: no blit from this format\n", name);
        free(blocks);
        return;
    }
    /* staging */
    VkBufferCreateInfo bci = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, NULL, 0, (VkDeviceSize)TEX * TEX * 16,
                              VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, VK_SHARING_MODE_EXCLUSIVE, 0, NULL};
    VkBuffer up, down;
    vkCreateBuffer(dev, &bci, NULL, &up);
    vkCreateBuffer(dev, &bci, NULL, &down);
    VkMemoryRequirements r;
    vkGetBufferMemoryRequirements(dev, up, &r);
    VkDeviceMemory upm = bind_mem(r, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    vkBindBufferMemory(dev, up, upm, 0);
    vkGetBufferMemoryRequirements(dev, down, &r);
    VkDeviceMemory downm = bind_mem(r, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    vkBindBufferMemory(dev, down, downm, 0);
    void *upp, *downp;
    vkMapMemory(dev, upm, 0, VK_WHOLE_SIZE, 0, &upp);
    vkMapMemory(dev, downm, 0, VK_WHOLE_SIZE, 0, &downp);
    /* upload at an odd 16-byte offset with a padded row, to exercise the decoder's addressing */
    const VkDeviceSize off = 48;
    const uint32_t row_tex = TEX + 8;
    memset(upp, 0xcd, (size_t)TEX * TEX * 16);
    for (int by = 0; by < TEX / 4; by++)
        memcpy((uint8_t *)upp + off + (size_t)by * (row_tex / 4) * bytes, blocks + (size_t)by * (TEX / 4) * bytes, (size_t)(TEX / 4) * bytes);
    /* images */
    VkImageCreateInfo ici = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, NULL, 0, VK_IMAGE_TYPE_2D, f, {TEX, TEX, 1}, 1, 1, VK_SAMPLE_COUNT_1_BIT,
                             VK_IMAGE_TILING_OPTIMAL, VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                             VK_SHARING_MODE_EXCLUSIVE, 0, NULL, VK_IMAGE_LAYOUT_UNDEFINED};
    VkImage bc, rgba;
    if (vkCreateImage(dev, &ici, NULL, &bc) != VK_SUCCESS) {
        printf("FAIL %s: cannot create the image\n", name);
        failures++;
        return;
    }
    VkMemoryRequirements ir;
    vkGetImageMemoryRequirements(dev, bc, &ir);
    VkDeviceMemory bcm = bind_mem(ir, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    vkBindImageMemory(dev, bc, bcm, 0);
    ici.format = VK_FORMAT_R32G32B32A32_SFLOAT;
    vkCreateImage(dev, &ici, NULL, &rgba);
    vkGetImageMemoryRequirements(dev, rgba, &ir);
    VkDeviceMemory rgbam = bind_mem(ir, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    vkBindImageMemory(dev, rgba, rgbam, 0);
    VkCommandBuffer cb = begin();
    barrier(cb, bc, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    VkBufferImageCopy c = {off, row_tex, 0, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {0, 0, 0}, {TEX, TEX, 1}};
    vkCmdCopyBufferToImage(cb, up, bc, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &c);
    barrier(cb, bc, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
    barrier(cb, rgba, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    VkImageBlit bl = {{VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {{0, 0, 0}, {TEX, TEX, 1}}, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {{0, 0, 0}, {TEX, TEX, 1}}};
    vkCmdBlitImage(cb, bc, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, rgba, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &bl, VK_FILTER_NEAREST);
    barrier(cb, rgba, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
    VkBufferImageCopy rb = {0, 0, 0, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {0, 0, 0}, {TEX, TEX, 1}};
    vkCmdCopyImageToBuffer(cb, rgba, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, down, 1, &rb);
    VkMemoryBarrier mb = {VK_STRUCTURE_TYPE_MEMORY_BARRIER, NULL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT};
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0, NULL, 0, NULL);
    vkEndCommandBuffer(cb);
    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, NULL, 0, NULL, NULL, 1, &cb, 0, NULL};
    vkQueueSubmit(queue, 1, &si, VK_NULL_HANDLE);
    vkQueueWaitIdle(queue);
    const float *got = downp;
    int bad = 0, nan_skip = 0;
    float worst = 0;
    int wx = 0, wy = 0, wc = 0;
    for (int y = 0; y < TEX; y++)
        for (int x = 0; x < TEX; x++) {
            float want[4];
            reference(f, blocks, bytes, x, y, want);
            for (int ch = 0; ch < 4; ch++) {
                float g = got[(y * TEX + x) * 4 + ch], w = want[ch];
                if (isnan(w) || isinf(w)) {
                    nan_skip++;
                    continue;
                }
                float d = fabsf(g - w);
                float lim = (f == VK_FORMAT_BC6H_UFLOAT_BLOCK || f == VK_FORMAT_BC6H_SFLOAT_BLOCK) ? tol * fmaxf(1.0f, fabsf(w)) : tol;
                if (!(d <= lim)) {
                    bad++;
                    if (!(d <= worst)) {
                        worst = d;
                        wx = x;
                        wy = y;
                        wc = ch;
                    }
                }
            }
        }
    if (bad) {
        float want[4];
        reference(f, blocks, bytes, wx, wy, want);
        printf("FAIL %s: %d of %d values differ from bcdec; worst %.4f at (%d,%d) ch %d: got %g want %g (block mode byte 0x%02x)\n", name, bad,
               TEX * TEX * 4, worst, wx, wy, wc, got[(wy * TEX + wx) * 4 + wc], want[wc],
               blocks[((wy / 4) * (TEX / 4) + (wx / 4)) * bytes]);
        failures++;
    } else {
        printf("PASS %s: %d texels match bcdec%s\n", name, TEX * TEX, nan_skip ? " (inf/nan references skipped)" : "");
    }
    vkDestroyImage(dev, bc, NULL);
    vkDestroyImage(dev, rgba, NULL);
    vkDestroyBuffer(dev, up, NULL);
    vkDestroyBuffer(dev, down, NULL);
    vkFreeMemory(dev, upm, NULL);
    vkFreeMemory(dev, downm, NULL);
    vkFreeMemory(dev, bcm, NULL);
    vkFreeMemory(dev, rgbam, NULL);
    free(blocks);
}

int main(int argc, char **argv)
{
    int gpu = argc > 1 ? atoi(argv[1]) : 0;
    void *lib = dlopen("libvulkan.so.1", RTLD_NOW);
    gipa = (PFN_vkGetInstanceProcAddr)dlsym(lib, "vkGetInstanceProcAddr");
#define G(n) n = (PFN_##n)gipa(inst, #n)
    G(vkCreateInstance);
    VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO, NULL, "bcn_check", 1, NULL, 0, VK_API_VERSION_1_1};
    VkInstanceCreateInfo ici = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, NULL, 0, &app, 0, NULL, 0, NULL};
    if (vkCreateInstance(&ici, NULL, &inst)) return 2;
    G(vkEnumeratePhysicalDevices); G(vkGetPhysicalDeviceMemoryProperties); G(vkCreateDevice); G(vkGetDeviceProcAddr);
    G(vkGetPhysicalDeviceFeatures); G(vkGetPhysicalDeviceFormatProperties); G(vkGetPhysicalDeviceProperties);
    uint32_t n = 8;
    VkPhysicalDevice pds[8];
    vkEnumeratePhysicalDevices(inst, &n, pds);
    pd = pds[gpu];
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(pd, &props);
    VkPhysicalDeviceFeatures feats;
    vkGetPhysicalDeviceFeatures(pd, &feats);
    printf("%s, textureCompressionBC=%u\n", props.deviceName, feats.textureCompressionBC);
    if (!feats.textureCompressionBC) return 1;
    vkGetPhysicalDeviceMemoryProperties(pd, &mp);
    float prio = 1;
    VkDeviceQueueCreateInfo qci = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, NULL, 0, 0, 1, &prio};
    VkPhysicalDeviceFeatures en = {0};
    en.textureCompressionBC = VK_TRUE;
    VkDeviceCreateInfo dci = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, NULL, 0, 1, &qci, 0, NULL, 0, NULL, &en};
    if (vkCreateDevice(pd, &dci, NULL, &dev)) return 2;
#undef G
#define G(n) n = (PFN_##n)vkGetDeviceProcAddr(dev, #n)
    G(vkGetDeviceQueue); G(vkCreateCommandPool); G(vkAllocateCommandBuffers); G(vkBeginCommandBuffer); G(vkEndCommandBuffer);
    G(vkQueueSubmit); G(vkQueueWaitIdle); G(vkCreateBuffer); G(vkGetBufferMemoryRequirements); G(vkAllocateMemory);
    G(vkBindBufferMemory); G(vkMapMemory); G(vkCreateImage); G(vkGetImageMemoryRequirements); G(vkBindImageMemory);
    G(vkCmdPipelineBarrier); G(vkCmdCopyBufferToImage); G(vkCmdBlitImage); G(vkCmdCopyImageToBuffer); G(vkDestroyImage);
    G(vkDestroyBuffer); G(vkFreeMemory);
#undef G
    vkGetDeviceQueue(dev, 0, 0, &queue);
    VkCommandPoolCreateInfo pci = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, NULL, 0, 0};
    vkCreateCommandPool(dev, &pci, NULL, &pool);
    float u8 = 1.5f / 255.0f;
    test_format(VK_FORMAT_BC1_RGBA_UNORM_BLOCK, "BC1", u8);
    test_format(VK_FORMAT_BC2_UNORM_BLOCK, "BC2", u8);
    test_format(VK_FORMAT_BC3_UNORM_BLOCK, "BC3", u8);
    test_format(VK_FORMAT_BC4_UNORM_BLOCK, "BC4 unorm", u8);
    test_format(VK_FORMAT_BC4_SNORM_BLOCK, "BC4 snorm", 1.5f / 127.0f);
    test_format(VK_FORMAT_BC5_UNORM_BLOCK, "BC5 unorm", u8);
    test_format(VK_FORMAT_BC5_SNORM_BLOCK, "BC5 snorm", 1.5f / 127.0f);
    test_format(VK_FORMAT_BC6H_UFLOAT_BLOCK, "BC6H ufloat (all 14 modes)", 0.002f);
    test_format(VK_FORMAT_BC6H_SFLOAT_BLOCK, "BC6H sfloat (all 14 modes)", 0.002f);
    test_format(VK_FORMAT_BC7_UNORM_BLOCK, "BC7 (all 8 modes)", u8);
    printf("%s\n", failures ? "FAILED" : "OK");
    return failures ? 1 : 0;
}
