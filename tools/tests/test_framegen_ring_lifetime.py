import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from test_vk_pending_completion import function


class FramegenRingLifetimeTest(unittest.TestCase):
    def test_ring_forgets_effect_sources_before_releasing_images(self):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if not compiler:
            self.skipTest('A host C compiler is required')
        root = Path(__file__).resolve().parents[2]
        source = (root / 'app/src/main/cpp/waylandcomp/src/framegen_bridge.c').read_text()
        code = r'''
#include <assert.h>
#include <stdint.h>
#include <string.h>
#include <stddef.h>
static int g_dev, g_engine_ok = 1, idle, forgotten[4], images, views, memory, engine;
static uint32_t g_ring_n = 3;
static struct { int img, view, mem; } g_ring[3];
static void wait_idle(int d) { idle++; }
static void vkp_effects_forget_image(int image) { assert(idle == 1); forgotten[image]++; }
static void destroy_image(int d, int image, const void *a) {
    assert(forgotten[image] == 1); images++;
}
static void destroy_view(int d, int view, const void *a) { assert(idle == 1); views++; }
static void free_memory(int d, int mem, const void *a) { assert(images > memory); memory++; }
static void fge_forget_targets(void) { assert(images == 3); engine++; }
static struct {
    void (*DeviceWaitIdle)(int);
    void (*DestroyImage)(int, int, const void *);
    void (*DestroyImageView)(int, int, const void *);
    void (*FreeMemory)(int, int, const void *);
} vk = {wait_idle, destroy_image, destroy_view, free_memory};
'''
        code += function(source, 'destroy_ring')
        code += r'''
int main(void) {
    for (int i = 0; i < 3; i++) g_ring[i].img = g_ring[i].view = g_ring[i].mem = i + 1;
    destroy_ring();
    assert(g_ring_n == 0 && images == 3 && views == 3 && memory == 3 && engine == 1);
    for (int i = 0; i < 3; i++) assert(!g_ring[i].img && !g_ring[i].view && !g_ring[i].mem);
    destroy_ring();
    assert(idle == 1 && engine == 1);
}
'''
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            probe = folder / 'ring.c'
            binary = folder / 'ring'
            probe.write_text(code)
            subprocess.run([compiler, '-std=c11', str(probe), '-o', str(binary)], check=True,
                           capture_output=True, text=True)
            subprocess.run([str(binary)], check=True, capture_output=True, timeout=10)
