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


if __name__ == "__main__":
    unittest.main()
