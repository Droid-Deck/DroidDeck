import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

from tools.tests.test_vk_external_image_sync import vulkan_include_args
from tools.tests.test_vk_pending_completion import function

TESTS = Path(__file__).resolve().parent
SRC = TESTS.parents[1] / "app/src/main/cpp/waylandcomp/src"

LAYER_HARNESS = r'''
#include <assert.h>
#include <stdint.h>
#include <vulkan/vulkan.h>
#include "vk_external_image_sync.h"
#include "vk_linear_dmabuf.h"

typedef struct AHardwareBuffer AHardwareBuffer;
%(image)s
struct vkp_draw {
    struct vkp_image *img;
    float sx, sy, sw, sh;
    int dx, dy, dw, dh;
    int blend;
};
struct draw_list { struct vkp_draw *d; int n, cap; };
static const uint32_t g_qfam = 2;
%(foreign)s
%(is_dmabuf)s
%(width)s
%(height)s
static int black_fill_below(const struct draw_list *dl, int n) { (void)dl; (void)n; return 0; }
%(candidate)s

static int candidate(struct vkp_image *img) {
    struct vkp_draw d = {.img = img, .sw = 1560, .sh = 720, .dw = 1560, .dh = 720};
    struct draw_list dl = {.d = &d, .n = 1, .cap = 1};
    return layer_candidate(&dl, 1560, 720);
}

static int foreign_pair(struct vkp_image *img) {
    struct vkp_external_client_barrier_pair pair;
    return vkp_external_client_barrier_pair(img->image, g_qfam, foreign_image(img), img->blit_dst, &pair);
}

int main(void) {
    struct vkp_image staged = {.w = 1560, .h = 720, .dmabuf = 1, .staged = 1};
    assert(candidate(&staged) == 0);
    assert(!foreign_pair(&staged));

    struct vkp_image client = {.w = 1560, .h = 720, .dmabuf = 1};
    assert(candidate(&client) == 0);
    assert(foreign_pair(&client));

    struct vkp_image shm = {.w = 1560, .h = 720};
    assert(candidate(&shm) == -1);
    assert(!foreign_pair(&shm));

    struct vkp_image layer = {.w = 1560, .h = 720, .dmabuf = 1, .blit_dst = 1};
    assert(candidate(&layer) == -1);
    assert(!foreign_pair(&layer));
    return 0;
}
'''

MAPPED_HARNESS = r'''
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdint.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>
#include <linux/dma-buf.h>
#include <vulkan/vulkan.h>
#include "vk_linear_dmabuf.h"

typedef struct AHardwareBuffer AHardwareBuffer;
%(image)s

static void droiddeck_log(const char *tag, const char *fmt, ...) { (void)tag; (void)fmt; }
#define LOGE(...) droiddeck_log("error", __VA_ARGS__)

static const VkDevice g_dev = (VkDevice)(uintptr_t)0x10;
static const uint32_t g_qfam = 2;
static VkPhysicalDeviceMemoryProperties g_memprops;
static uint8_t staging[4096];
static uint32_t allocated_type = 99;
static int barriers, copies, freed_buffers, freed_memory;
static VkBufferMemoryBarrier first_buffer_barrier;
static uint32_t buffer_barrier_counts[4];

static void stub_barrier(VkCommandBuffer cmd, VkPipelineStageFlags src, VkPipelineStageFlags dst, VkDependencyFlags dep,
                         uint32_t nm, const VkMemoryBarrier *m, uint32_t nb, const VkBufferMemoryBarrier *b,
                         uint32_t ni, const VkImageMemoryBarrier *i) {
    (void)cmd; (void)src; (void)dst; (void)dep; (void)nm; (void)m; (void)ni; (void)i;
    if (barriers == 0 && nb) first_buffer_barrier = b[0];
    if (barriers < 4) buffer_barrier_counts[barriers] = nb;
    barriers++;
}
static void stub_copy(VkCommandBuffer cmd, VkBuffer b, VkImage i, VkImageLayout l, uint32_t n, const VkBufferImageCopy *r) {
    (void)cmd; (void)b; (void)i; (void)l; (void)n; (void)r;
    copies++;
}
static VkResult stub_create_buffer(VkDevice d, const VkBufferCreateInfo *ci, const VkAllocationCallbacks *a, VkBuffer *out) {
    (void)d; (void)a;
    assert(ci->pNext == NULL);
    assert(ci->usage == VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
    *out = (VkBuffer)(uintptr_t)0x20;
    return VK_SUCCESS;
}
static void stub_buffer_reqs(VkDevice d, VkBuffer b, VkMemoryRequirements *r) {
    (void)d; (void)b;
    r->size = sizeof(staging); r->alignment = 64; r->memoryTypeBits = 0x3;
}
static VkResult stub_allocate(VkDevice d, const VkMemoryAllocateInfo *info, const VkAllocationCallbacks *a, VkDeviceMemory *out) {
    (void)d; (void)a;
    assert(info->pNext == NULL);
    allocated_type = info->memoryTypeIndex;
    *out = (VkDeviceMemory)(uintptr_t)0x30;
    return VK_SUCCESS;
}
static VkResult stub_bind(VkDevice d, VkBuffer b, VkDeviceMemory m, VkDeviceSize o) { (void)d; (void)b; (void)m; (void)o; return VK_SUCCESS; }
static VkResult stub_map(VkDevice d, VkDeviceMemory m, VkDeviceSize o, VkDeviceSize s, VkMemoryMapFlags f, void **out) {
    (void)d; (void)m; (void)o; (void)s; (void)f;
    *out = staging;
    return VK_SUCCESS;
}
static void stub_destroy_image(VkDevice d, VkImage i, const VkAllocationCallbacks *a) { (void)d; (void)i; (void)a; }
static void stub_destroy_buffer(VkDevice d, VkBuffer b, const VkAllocationCallbacks *a) { (void)d; (void)b; (void)a; freed_buffers++; }
static void stub_free(VkDevice d, VkDeviceMemory m, const VkAllocationCallbacks *a) { (void)d; (void)m; (void)a; freed_memory++; }

static struct {
    PFN_vkCmdPipelineBarrier CmdPipelineBarrier;
    PFN_vkCmdCopyBufferToImage CmdCopyBufferToImage;
    PFN_vkCreateBuffer CreateBuffer;
    PFN_vkGetBufferMemoryRequirements GetBufferMemoryRequirements;
    PFN_vkAllocateMemory AllocateMemory;
    PFN_vkBindBufferMemory BindBufferMemory;
    PFN_vkMapMemory MapMemory;
    PFN_vkDestroyImage DestroyImage;
    PFN_vkDestroyBuffer DestroyBuffer;
    PFN_vkFreeMemory FreeMemory;
} g_vk = {stub_barrier, stub_copy, stub_create_buffer, stub_buffer_reqs, stub_allocate, stub_bind, stub_map,
          stub_destroy_image, stub_destroy_buffer, stub_free};

%(memory_type)s
%(copy)s
%(stage)s
%(destroy)s
%(map)s

static void fill(int fd, uint8_t seed, size_t n) {
    uint8_t buf[4096];
    for (size_t i = 0; i < n; i++) buf[i] = (uint8_t)(seed + i * 7);
    assert(pwrite(fd, buf, n, 0) == (ssize_t)n);
}

int main(void) {
    g_memprops.memoryTypeCount = 2;
    g_memprops.memoryTypes[0].propertyFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
    g_memprops.memoryTypes[1].propertyFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;

    VkBufferImageCopy copy;
    const VkDeviceSize need = vkp_linear_dmabuf_copy(VK_FORMAT_B8G8R8A8_UNORM, 12, 8, 64, 0, &copy);
    assert(need == 64 * 7 + 12 * 4);
    const size_t size = 1024;
    int fd = memfd_create("linear-frame", 0);
    assert(fd >= 0);
    assert(ftruncate(fd, (off_t)size) == 0);
    fill(fd, 1, size);

    struct vkp_image img = {.w = 12, .h = 8, .dmabuf = 1, .image = (VkImage)(uintptr_t)0x40};
    int take = fd;
    assert(map_dmabuf_buffer(&img, &take, size, need) == VK_SUCCESS);
    assert(take == -1);
    assert(img.cpu_fd == fd);
    assert(img.cpu_size == need);
    assert(img.cpu_dst == staging);
    assert(allocated_type == 1);
    img.linear_copy = copy;
    img.staged = 1;

    fill(fd, 9, size);
    uint8_t want[4096];
    assert(pread(fd, want, (size_t)need, 0) == (ssize_t)need);
    stage_linear_dmabuf((VkCommandBuffer)(uintptr_t)0x50, &img);
    assert(memcmp(staging, want, (size_t)need) == 0);
    assert(copies == 1 && barriers == 2);
    assert(first_buffer_barrier.srcQueueFamilyIndex == VK_QUEUE_FAMILY_IGNORED);
    assert(first_buffer_barrier.dstQueueFamilyIndex == VK_QUEUE_FAMILY_IGNORED);
    assert(buffer_barrier_counts[0] == 1 && buffer_barrier_counts[1] == 0);

    destroy_image_objects(&img);
    assert(img.cpu_src == NULL && img.cpu_dst == NULL);
    assert(freed_buffers == 1 && freed_memory == 1);
    assert(fcntl(fd, F_GETFD) == -1 && errno == EBADF);

    struct vkp_image imported = {.w = 12, .h = 8, .dmabuf = 1, .staged = 1, .image = (VkImage)(uintptr_t)0x41,
                                 .linear_buf = (VkBuffer)(uintptr_t)0x21, .linear_copy = copy};
    barriers = copies = 0;
    memset(staging, 0, sizeof(staging));
    stage_linear_dmabuf((VkCommandBuffer)(uintptr_t)0x50, &imported);
    assert(copies == 1 && barriers == 2);
    assert(first_buffer_barrier.srcQueueFamilyIndex == VK_QUEUE_FAMILY_FOREIGN_EXT);
    assert(first_buffer_barrier.dstQueueFamilyIndex == g_qfam);
    assert(buffer_barrier_counts[0] == 1 && buffer_barrier_counts[1] == 1);
    for (size_t i = 0; i < sizeof(staging); i++) assert(staging[i] == 0);

    struct vkp_image unmappable = {.w = 12, .h = 8, .dmabuf = 1};
    int bad = open("/dev/null", O_WRONLY);
    assert(bad >= 0);
    int keep = bad;
    assert(map_dmabuf_buffer(&unmappable, &keep, size, need) == VK_ERROR_MEMORY_MAP_FAILED);
    assert(keep == bad);
    assert(unmappable.cpu_src == NULL && unmappable.linear_buf == VK_NULL_HANDLE);
    close(bad);
    return 0;
}
'''


def compile_and_run(test, source, tmp, name):
    compiler = shutil.which(os.environ.get("CC", "cc"))
    if not compiler:
        test.fail("A C compiler is required for the linear dma-buf regression tests")
    program = Path(tmp) / name
    command = [
        compiler,
        "-std=c11",
        "-Wall",
        "-Wextra",
        "-Werror",
        "-UNDEBUG",
        "-I",
        str(SRC),
        *vulkan_include_args(),
        str(source),
        "-o",
        str(program),
    ]
    built = subprocess.run(command, capture_output=True, text=True)
    test.assertEqual(built.returncode, 0, built.stdout + built.stderr)
    ran = subprocess.run([str(program)], capture_output=True, text=True, timeout=30)
    test.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)


class VkLinearDmabufTest(unittest.TestCase):
    def test_linear_dmabuf_copy(self):
        with tempfile.TemporaryDirectory(prefix="droiddeck-vk-linear-") as tmp:
            compile_and_run(self, TESTS / "vk_linear_dmabuf_test.c", tmp, "vk-linear-dmabuf")

    def test_readback_orders_general_transition_after_staging_copy(self):
        body = function((SRC / "vk_present.c").read_text(), "vkp_image_readback")
        stage = body.index("stage_linear_dmabuf(cmd, src);")
        barrier = body.index("vkp_linear_dmabuf_readback_stage(src->in_general)")
        self.assertLess(stage, barrier)
        self.assertIn(".srcAccessMask = vkp_linear_dmabuf_readback_access(src->in_general)", body[stage:barrier])
        self.assertNotIn("CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT", body)

    def test_modifier_query_needs_drm_modifier_extension(self):
        body = function((SRC / "vk_present.c").read_text(), "vkp_image_import_dmabuf")
        query = re.search(r"const int modifier_sampled = (.*?);", body, re.S)
        self.assertIsNotNone(query, "modifier_sampled not found in vkp_image_import_dmabuf")
        terms = [t.strip() for t in query.group(1).split("&&")]
        self.assertIn("g_drm_modifiers", terms)
        self.assertLess(terms.index("g_drm_modifiers"), terms.index("modifier_importable(img->fmt, modifier, sampled_usage)"))
        self.assertIn("!as_blit_dst", terms)
        self.assertIn("g_vk.GetPhysicalDeviceImageFormatProperties2", terms)

    def test_staged_client_image_stays_layer_candidate(self):
        vk = (SRC / "vk_present.c").read_text()
        comp = (SRC / "compositor.c").read_text()
        image = re.search(r"^struct vkp_image \{.*?^\};", vk, re.M | re.S)
        self.assertIsNotNone(image, "struct vkp_image not found in vk_present.c")
        harness = LAYER_HARNESS % {
            "image": image.group(0),
            "foreign": function(vk, "foreign_image"),
            "is_dmabuf": function(vk, "vkp_image_is_dmabuf"),
            "width": function(vk, "vkp_image_width"),
            "height": function(vk, "vkp_image_height"),
            "candidate": function(comp, "layer_candidate"),
        }
        with tempfile.TemporaryDirectory(prefix="droiddeck-vk-linear-layer-") as tmp:
            source = Path(tmp) / "layer_candidate_test.c"
            source.write_text(harness)
            compile_and_run(self, source, tmp, "vk-linear-layer")

    def test_layer_import_failure_requests_compositor_fallback(self):
        source = (SRC / "sc_layer.c").read_text()
        for name in ("sc_layer_present", "sc_layer_present_overlay"):
            with self.subTest(layer=name):
                body = function(source, name)
                self.assertRegex(body, r"if \(idx < 0\) \{ log_drop\(l\); return g_alloc_failed \? -1 : 0; \}")

    def test_layer_blits_use_the_imported_output_row_pitch(self):
        source = (SRC / "vk_present.c").read_text()
        blit = function(source, "blit_to_layer")
        copy = blit.index("CmdCopyImageToBuffer")
        self.assertLess(blit.index("VkBufferImageCopy copy = dst->linear_copy"), copy)
        self.assertLess(blit.index("VK_ACCESS_TRANSFER_WRITE_BIT, 1"), copy)
        self.assertGreater(blit.index("VK_ACCESS_TRANSFER_WRITE_BIT, 0"), copy)
        for name in ("vkp_blit_image", "pass_finish"):
            self.assertIn("blit_to_layer(", function(source, name))

    def test_driver_without_dmabuf_import_copies_from_the_mapping(self):
        vk = (SRC / "vk_present.c").read_text()
        image = re.search(r"^struct vkp_image \{.*?^\};", vk, re.M | re.S)
        self.assertIsNotNone(image, "struct vkp_image not found in vk_present.c")
        harness = MAPPED_HARNESS % {
            "image": image.group(0),
            "memory_type": function(vk, "memory_type"),
            "copy": function(vk, "copy_mapped_dmabuf"),
            "stage": function(vk, "stage_linear_dmabuf"),
            "destroy": function(vk, "destroy_image_objects"),
            "map": function(vk, "map_dmabuf_buffer"),
        }
        with tempfile.TemporaryDirectory(prefix="droiddeck-vk-linear-mapped-") as tmp:
            source = Path(tmp) / "mapped_dmabuf_test.c"
            source.write_text(harness)
            compile_and_run(self, source, tmp, "vk-linear-mapped")

    def test_mapping_only_where_the_driver_imports_no_dmabufs(self):
        vk = (SRC / "vk_present.c").read_text()
        init = re.search(r"^\s+g_dmabuf_import = (.*?);", vk, re.M)
        self.assertIsNotNone(init, "g_dmabuf_import is never derived from the device extensions")
        terms = [t.strip() for t in init.group(1).split("&&")]
        self.assertEqual(terms, ['has_ext(exts, ne, "VK_KHR_external_memory_fd")',
                                 'has_ext(exts, ne, "VK_EXT_external_memory_dma_buf")'])
        self.assertIn("static int g_dmabuf_import = 1;", vk)
        body = function(vk, "import_linear_dmabuf")
        self.assertRegex(body, r"vkp_linear_dmabuf_mapped\(g_dmabuf_import, img->blit_dst\)\s*\?\s*map_dmabuf_buffer\(")
        self.assertIn("bind_dmabuf_buffer(img, &dupfd, size)", body)
        imp = function(vk, "vkp_image_import_dmabuf")
        self.assertIn("vkp_linear_dmabuf_through_buffer(g_drm_modifiers && g_dmabuf_import, modifier, as_blit_dst)", imp)
        self.assertIn("vkp_padded_linear_target_needs_layout(g_drm_modifiers, modifier, format, w, stride, offset)", imp)


if __name__ == "__main__":
    unittest.main()
