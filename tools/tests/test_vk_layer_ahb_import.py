import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from tools.tests.test_vk_pending_completion import function

SRC = Path(__file__).resolve().parents[2] / "app/src/main/cpp/waylandcomp/src"

HARNESS = r'''
#include <assert.h>
#include <stddef.h>
#include <stdint.h>

#define VKP_VENDOR_QUALCOMM 0x5143
static int dev_state;
static uint32_t g_vendor_id;
static char *g_library_name;
static int g_ahb_import;
static struct { void *GetAndroidHardwareBufferPropertiesANDROID; } g_vk;
static int dev_init(void) { return dev_state; }
static int properties_stub;

%(can_import)s

static int can(uint32_t vendor, char *library, int ext) {
    dev_state = 0;
    g_vendor_id = vendor;
    g_library_name = library;
    g_ahb_import = ext;
    g_vk.GetAndroidHardwareBufferPropertiesANDROID = ext ? &properties_stub : NULL;
    return vkp_can_import_ahb();
}

int main(void) {
    char radv[] = "libvulkan_radeon.so";
    char panvk[] = "libvulkan_panfrost.so";
    const uint32_t samsung = 0x144d, arm = 0x13b5, imagination = 0x1010;

    assert(can(samsung, NULL, 1));
    assert(can(arm, NULL, 1));
    assert(can(imagination, NULL, 1));

    assert(!can(samsung, radv, 1));
    assert(!can(arm, panvk, 1));

    assert(!can(VKP_VENDOR_QUALCOMM, NULL, 1));
    assert(!can(samsung, NULL, 0));

    dev_state = -1;
    g_vendor_id = samsung; g_library_name = NULL; g_ahb_import = 1;
    g_vk.GetAndroidHardwareBufferPropertiesANDROID = &properties_stub;
    assert(!vkp_can_import_ahb());
    return 0;
}
'''


POOL_HARNESS = r'''
#define _GNU_SOURCE
#include <assert.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/types.h>

#define FOURCC(a, b, c, d) ((uint32_t)(a) | ((uint32_t)(b) << 8) | ((uint32_t)(c) << 16) | ((uint32_t)(d) << 24))
#define DRM_ABGR8888 FOURCC('A', 'B', '2', '4')
#define DRM_ABGR2101010 FOURCC('A', 'B', '3', '0')
#define AHB_RGBA8 1u
#define AHB_RGB10A2 0x2bu
#define MOD_LINEAR 0ULL
#define MOD_QCOM_COMPRESSED 0x0500000000000001ULL
#define SC_MOD_GRALLOC (~0ULL - 1)
#define SC_LAYER_AHB_USAGE_VENDOR_UBWC (1ULL << 28)
#define AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT (1ULL << 9)
#define AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE (1ULL << 8)
#define AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY (1ULL << 11)
#define AHARDWAREBUFFER_USAGE_CPU_READ_RARELY 2ULL

typedef struct AHardwareBuffer AHardwareBuffer;
typedef struct {
    uint32_t width, height, layers, format;
    uint64_t usage;
    uint32_t stride, rfu0;
    uint64_t rfu1;
} AHardwareBuffer_Desc;
struct droiddeck_native_handle { int version; int numFds; int numInts; int data[4]; };
struct vkp_image { int unused; };
struct slot { AHardwareBuffer *ahb; struct vkp_image *img; int w, h; uint32_t fmt; int release_fd; int busy; };
struct layer {
    const char *name;
    int pool_n;
    int alloc_tier;
    unsigned import_failed_fmts;
    uint64_t pool_modifier;
    int logged_w, logged_h, logged_tier;
    uint32_t logged_fmt;
    uint64_t logged_mod;
};

enum { TIER_UBWC, TIER_PLAIN, TIER_LINEAR };
static int g_alloc_failed;
static uint32_t g_hdr_pool_fmt = AHB_RGB10A2;

static struct droiddeck_native_handle handle = {.numFds = 1, .data = {3}};
static AHardwareBuffer *buffer = (AHardwareBuffer *)&handle;
static struct vkp_image image;
static int allocations, imports_10_bit, imports_8_bit, accept_10_bit, accept_8_bit;

static int AHardwareBuffer_allocate(const AHardwareBuffer_Desc *d, AHardwareBuffer **out) {
    (void)d; allocations++; *out = buffer; return 0;
}
static void AHardwareBuffer_describe(const AHardwareBuffer *b, AHardwareBuffer_Desc *d) {
    (void)b; d->stride = 1920; d->usage = 0;
}
static void AHardwareBuffer_release(AHardwareBuffer *b) { (void)b; }
static const void *native_handle(const AHardwareBuffer *b) { (void)b; return &handle; }
static struct { const void *(*getNativeHandle)(const AHardwareBuffer *); } api = {native_handle};
static int sniff_modifier(const struct droiddeck_native_handle *h, uint64_t *mod) { (void)h; (void)mod; return 0; }
static int vkp_can_import_ahb(void) { return 0; }
static struct vkp_image *vkp_image_import_ahb(AHardwareBuffer *b, char *info, size_t n) {
    (void)b; (void)info; (void)n; return NULL;
}
static struct vkp_image *vkp_image_import_dmabuf(int fd, uint32_t drm, uint64_t mod, int w, int h, uint32_t pitch,
                                                 uint32_t offset, int planes) {
    (void)fd; (void)mod; (void)w; (void)h; (void)pitch; (void)offset; (void)planes;
    if (drm == DRM_ABGR2101010) { imports_10_bit++; return accept_10_bit ? &image : NULL; }
    imports_8_bit++;
    return accept_8_bit ? &image : NULL;
}
static int handle_fd(const struct droiddeck_native_handle *h, off_t need, int *which) {
    (void)need; *which = 0; return h->data[0];
}
static void describe_handle(const struct droiddeck_native_handle *h, char *out, size_t n) { (void)h; snprintf(out, n, "-"); }
static const char *pool_kind(uint64_t mod) { return mod == SC_MOD_GRALLOC ? "gralloc-laid-out" : "linear"; }
static void droiddeck_log(const char *tag, const char *fmt, ...) { (void)tag; (void)fmt; }

%(tier_name)s
%(alloc_slot)s

static int take_free_slot(struct layer *l, int w, int h, uint32_t fmt, int *wait_fd) {
    struct slot s = {0};
    *wait_fd = -1;
    g_alloc_failed = 0;
    return alloc_slot(l, &s, w, h, fmt) == 0 ? 0 : -1;
}

%(take_hdr_slot)s

static int frame(struct layer *l) {
    int fd;
    return take_hdr_slot(l, 1920, 1080, &fd);
}

static void reset(int ten, int eight) {
    g_hdr_pool_fmt = AHB_RGB10A2;
    allocations = imports_10_bit = imports_8_bit = 0;
    accept_10_bit = ten;
    accept_8_bit = eight;
}

int main(void) {
    struct layer ten_bit_refused = {.name = "game", .pool_n = 3};
    reset(0, 1);
    assert(frame(&ten_bit_refused) == 0);
    assert(g_hdr_pool_fmt == AHB_RGBA8);
    assert(imports_10_bit == 1 && imports_8_bit == 1);
    assert(ten_bit_refused.alloc_tier == TIER_LINEAR);
    assert(frame(&ten_bit_refused) == 0);
    assert(imports_10_bit == 1 && imports_8_bit == 2);

    struct layer ten_bit_works = {.name = "game", .pool_n = 3};
    reset(1, 1);
    assert(frame(&ten_bit_works) == 0);
    assert(g_hdr_pool_fmt == AHB_RGB10A2);
    assert(imports_10_bit == 1 && imports_8_bit == 0);

    struct layer nothing_imports = {.name = "game", .pool_n = 3};
    reset(0, 0);
    assert(frame(&nothing_imports) == -1 && g_alloc_failed);
    assert(imports_10_bit == 1 && imports_8_bit == 1);
    const int tried = allocations;
    assert(frame(&nothing_imports) == -1 && g_alloc_failed);
    assert(frame(&nothing_imports) == -1 && g_alloc_failed);
    assert(allocations == tried);
    assert(imports_10_bit == 1 && imports_8_bit == 1);
    return 0;
}
'''

SNIFF_HARNESS = r'''
#include <assert.h>
#include <stdint.h>
#include <sys/types.h>

#define MOD_LINEAR 0ULL
#define MOD_QCOM_COMPRESSED 0x0500000000000001ULL

struct droiddeck_native_handle { int version; int numFds; int numInts; int data[40]; };
static off_t fake_fd_size;
static off_t test_lseek(int fd, off_t off, int whence) {
    (void)fd; (void)off;
    return whence == 2 ? fake_fd_size : 0;
}
#define SEEK_SET 0
#define SEEK_END 2
#define lseek test_lseek

%(sniff_modifier)s

int main(void) {
    uint64_t mod = 123;
    struct droiddeck_native_handle gmsm_ubwc = {
        .numFds = 2, .numInts = 2, .data = {3, 4, (int)0x676d736dU, (int)0x08000000U}
    };
    assert(sniff_modifier(&gmsm_ubwc, &mod) == 1 && mod == MOD_QCOM_COMPRESSED);

    struct droiddeck_native_handle gmsm_lin = {
        .numFds = 1, .numInts = 2, .data = {3, (int)0x676d736dU, 0x200}
    };
    assert(sniff_modifier(&gmsm_lin, &mod) == 1 && mod == MOD_LINEAR);

    struct droiddeck_native_handle snap_ubwc = {
        .numFds = 2, .numInts = 34,
        .data = {
            10, 11,
            0, 1, 0x0a000000, 5120, 1280, 720, 1280, 720, 1, 0, 1, 0x800, 0, 0x10000b00, 0, 3710976, 0
        }
    };
    fake_fd_size = 3710976;
    assert(sniff_modifier(&snap_ubwc, &mod) == 1 && mod == MOD_QCOM_COMPRESSED);

    snap_ubwc.data[2 + 2] = 0x200;
    assert(sniff_modifier(&snap_ubwc, &mod) == 1 && mod == MOD_LINEAR);

    fake_fd_size = 1024;
    assert(sniff_modifier(&snap_ubwc, &mod) == 0);

    fake_fd_size = 3710976;
    snap_ubwc.data[2 + 10] = 2;
    assert(sniff_modifier(&snap_ubwc, &mod) == 0);

    struct droiddeck_native_handle non_qti = {.numFds = 1, .numInts = 2, .data = {3, 0x12345678, 0}};
    assert(sniff_modifier(&non_qti, &mod) == 0);
    return 0;
}
'''


class LayerAhbImportTest(unittest.TestCase):
    def test_sniff_modifier_supports_legacy_gmsm_and_qti_snap_handles(self):
        compiler = shutil.which(os.environ.get("CC", "cc") or "gcc") or shutil.which("gcc")
        if not compiler:
            self.fail("A C compiler is required for the sniff_modifier test")
        body = function((SRC / "sc_layer.c").read_text(), "sniff_modifier")
        with tempfile.TemporaryDirectory(prefix="droiddeck-sniff-") as tmp:
            source = Path(tmp) / "sniff_test.c"
            source.write_text(SNIFF_HARNESS % {"sniff_modifier": body})
            program = Path(tmp) / "sniff-test"
            built = subprocess.run([compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", "-UNDEBUG", str(source),
                                    "-o", str(program)], capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(program)], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)

    def test_only_the_system_driver_imports_gralloc_layouts(self):
        compiler = shutil.which(os.environ.get("CC", "cc"))
        if not compiler:
            self.fail("A C compiler is required for the layer AHardwareBuffer import test")
        body = function((SRC / "vk_present.c").read_text(), "vkp_can_import_ahb")
        with tempfile.TemporaryDirectory(prefix="droiddeck-layer-ahb-") as tmp:
            source = Path(tmp) / "layer_ahb_import_test.c"
            source.write_text(HARNESS % {"can_import": body})
            program = Path(tmp) / "layer-ahb-import"
            built = subprocess.run([compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", "-UNDEBUG", str(source),
                                    "-o", str(program)], capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(program)], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)

    def test_a_failed_10_bit_pool_still_falls_back_to_8_bit_and_failures_are_latched_per_format(self):
        compiler = shutil.which(os.environ.get("CC", "cc"))
        if not compiler:
            self.fail("A C compiler is required for the layer pool fallback test")
        text = (SRC / "sc_layer.c").read_text()
        functions = {name: function(text, name) for name in ("tier_name", "alloc_slot", "take_hdr_slot")}
        with tempfile.TemporaryDirectory(prefix="droiddeck-layer-pool-") as tmp:
            source = Path(tmp) / "layer_pool_fallback_test.c"
            source.write_text(POOL_HARNESS % functions)
            program = Path(tmp) / "layer-pool-fallback"
            built = subprocess.run([compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter",
                                    "-UNDEBUG", str(source), "-o", str(program)], capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(program)], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)

if __name__ == "__main__":
    unittest.main()
