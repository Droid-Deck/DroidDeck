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


class LayerAhbImportTest(unittest.TestCase):
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

    def test_pool_import_failure_on_the_last_tier_is_not_retried_every_frame(self):
        body = function((SRC / "sc_layer.c").read_text(), "alloc_slot")
        failure = body[body.index("if (!img) {"):]
        failure = failure[:failure.index("s->ahb = ahb;")]
        self.assertRegex(failure, r"l->alloc_tier = tier \+ 1;\s*if \(tier < TIER_LINEAR\) continue;\s*"
                                  r"g_alloc_failed = 1;\s*return -1;")
        self.assertIn("while (l->alloc_tier <= TIER_LINEAR)", body)


if __name__ == "__main__":
    unittest.main()
