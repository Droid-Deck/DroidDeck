"""Fault-inject the production fence wait and imported-image destructor.

No GPU is wedged: a fake dispatch table returns the same result a timed-out
submit would return. The compiled functions come from vk_present.c itself.
"""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[2] / 'app/src/main/cpp/waylandcomp/src/vk_present.c'


def function(text, name):
    match = re.search(r'^(?:static )?[^\n;]+\b' + name + r'\([^;]*?\)\s*\{', text, re.M)
    if not match:
        raise AssertionError('Production function not found: ' + name)
    start = match.start()
    pos = text.index('{', match.start())
    depth = 1
    end = pos + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


HARNESS = r'''
#include <assert.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdarg.h>
typedef int VkResult;
typedef uintptr_t VkFence;
#define VK_TRUE 1
#define VK_SUCCESS 0
#define VK_TIMEOUT 2
#define VK_ERROR_DEVICE_LOST -4
#define VK_ERROR_OUT_OF_HOST_MEMORY -1
#define VK_ERROR_UNKNOWN -13
static int g_dev_state, g_base_black;
static uintptr_t g_dev = 1;
static struct { int64_t wait_ns, wait_max_ns; unsigned waits; } g_perf;
static VkResult answer;
static unsigned destroyed, unmapped, freed_memory, forgotten, errors;
static int64_t perf_now(void) { return 10; }
static void perf_add(int64_t *s, int64_t *m, unsigned *c, int64_t d) { *s+=d; *m=d; ++*c; }
static void __attribute__((unused)) droiddeck_log(const char *tag, const char *fmt, ...) { (void)tag; (void)fmt; ++errors; }
#define LOGE(...) droiddeck_log("error", __VA_ARGS__)
static VkResult wait_fn(uintptr_t dev, unsigned n, const VkFence *f, int all, uint64_t timeout) {
    (void)dev; (void)n; (void)f; (void)all; (void)timeout; return answer;
}
static void unmap_fn(uintptr_t dev, uintptr_t mem) { (void)dev; (void)mem; ++unmapped; }
static void destroy_fn(uintptr_t dev, uintptr_t img, void *allocator) { (void)dev; (void)img; (void)allocator; ++destroyed; }
static void free_fn(uintptr_t dev, uintptr_t mem, void *allocator) { (void)dev; (void)mem; (void)allocator; ++freed_memory; }
static struct {
    VkResult (*WaitForFences)(uintptr_t, unsigned, const VkFence *, int, uint64_t);
    void (*UnmapMemory)(uintptr_t, uintptr_t);
    void (*DestroyImage)(uintptr_t, uintptr_t, void *);
    void (*FreeMemory)(uintptr_t, uintptr_t, void *);
} g_vk = {wait_fn, unmap_fn, destroy_fn, free_fn};
struct vkp_image { uintptr_t image, mem; void *map; };
static void blendp_forget_image(uintptr_t image) { (void)image; ++forgotten; }
/* PRODUCTION_FUNCTIONS */
static struct vkp_image *image(void) {
    struct vkp_image *p = calloc(1, sizeof(*p)); assert(p);
    p->image=1; p->mem=2; p->map=p; return p;
}
int main(void) {
    g_dev_state=1; answer=VK_SUCCESS;
    assert(timed_wait(1, 1000)==VK_SUCCESS);
    assert(g_dev_state==1);
    vkp_image_destroy(image());
    assert(destroyed==1 && unmapped==1 && freed_memory==1 && forgotten==1);

    g_dev_state=1; g_base_black=1; answer=VK_TIMEOUT;
    assert(timed_wait(1, 1000)==VK_TIMEOUT);
    assert(g_dev_state!=1 && g_dev_state!=-2); /* pending work is not DEVICE_LOST */
    struct vkp_image *pending=image();
    vkp_image_destroy(pending);
    assert(destroyed==1 && unmapped==1 && freed_memory==1 && forgotten==1);
    unsigned first_errors=errors;
    assert(first_errors>0);
    assert(timed_wait(1, 1000)==VK_ERROR_UNKNOWN);
    assert(errors==first_errors); /* one terminal diagnostic, no repeated recovery */
    free(pending); /* fake GPU exists only in this test */

    g_dev_state=1; answer=VK_ERROR_OUT_OF_HOST_MEMORY;
    assert(timed_wait(1, 1000)==VK_ERROR_OUT_OF_HOST_MEMORY);
    assert(g_dev_state!=1 && g_dev_state!=-2);
    pending=image(); vkp_image_destroy(pending);
    assert(destroyed==1 && unmapped==1 && freed_memory==1 && forgotten==1);
    free(pending);

    g_dev_state=1; answer=VK_ERROR_DEVICE_LOST;
    assert(timed_wait(1, 1000)==VK_ERROR_DEVICE_LOST);
    assert(g_dev_state==1); /* existing caller performs genuine device-loss cleanup */
    return 0;
}
'''

SWAPCHAIN_HARNESS = r'''
#include <assert.h>
#include <stdint.h>
#include <stdlib.h>
typedef int VkResult;
#define VK_SUCCESS 0
#define VK_ERROR_DEVICE_LOST -4
#define VK_ERROR_OUT_OF_HOST_MEMORY -1
#define VK_NULL_HANDLE 0
static int g_dev_state, g_base_black, g_swap_md_identity;
static uintptr_t g_dev=1, g_inst=1, g_swapchain, g_surface, *g_images;
static uint32_t g_nimg;
static unsigned resets, forgotten, destroyed, errors, lost;
static VkResult answer;
#define LOGE(...) (++errors)
static VkResult idle_fn(uintptr_t dev) { (void)dev; return answer; }
static void destroy_fn(uintptr_t dev, uintptr_t obj, void *alloc) {
    (void)dev; (void)obj; (void)alloc; ++destroyed;
}
static struct {
    VkResult (*DeviceWaitIdle)(uintptr_t);
    void (*DestroySwapchainKHR)(uintptr_t, uintptr_t, void *);
    void (*DestroySurfaceKHR)(uintptr_t, uintptr_t, void *);
} g_vk={idle_fn, destroy_fn, destroy_fn};
static void reset_sync(void) { ++resets; }
static void blendp_forget_image(uintptr_t img) { (void)img; ++forgotten; }
static void device_lost(const char *where) { (void)where; ++lost; g_dev_state=-2; }
/* PRODUCTION_FUNCTIONS */
static void setup(void) {
    g_dev_state=1; g_base_black=1; g_swap_md_identity=1;
    g_swapchain=2; g_surface=3; g_nimg=1;
    g_images=calloc(1,sizeof(*g_images)); assert(g_images); *g_images=4;
}
int main(void) {
    setup(); answer=VK_SUCCESS; destroy_swapchain();
    assert(resets==1 && destroyed==2 && forgotten==1);
    assert(!g_swapchain && !g_surface && !g_images && !g_nimg && !g_swap_md_identity);
    setup(); answer=VK_ERROR_OUT_OF_HOST_MEMORY; destroy_swapchain();
    assert(g_dev_state==-3 && errors==1);
    assert(resets==1 && destroyed==2 && forgotten==1);
    assert(g_swapchain && g_surface && g_images && g_nimg);
    destroy_swapchain();
    assert(errors==1 && resets==1 && destroyed==2);
    free(g_images); /* fake GPU resources only */
    setup(); answer=VK_ERROR_DEVICE_LOST; destroy_swapchain();
    assert(lost==1 && g_dev_state==-2);
    assert(resets==1 && destroyed==2 && forgotten==1);
    free(g_images);
    return 0;
}
'''


class VkPendingCompletionTest(unittest.TestCase):
    def compile_and_run(self, harness, names):
        cc = shutil.which(os.environ.get('CC', 'cc'))
        self.assertIsNotNone(cc, 'A C compiler is required')
        text = SOURCE.read_text()
        production = '\n'.join(function(text, n) for n in names)
        with tempfile.TemporaryDirectory(prefix='droiddeck-pending-') as tmp:
            source=Path(tmp)/'probe.c'; binary=Path(tmp)/'probe'
            source.write_text(harness.replace('/* PRODUCTION_FUNCTIONS */', production))
            built=subprocess.run([cc,'-std=c11','-Wall','-Wextra','-Werror','-UNDEBUG',str(source),'-o',str(binary)],capture_output=True,text=True)
            self.assertEqual(built.returncode,0,built.stdout+built.stderr)
            ran=subprocess.run([str(binary)],capture_output=True,text=True,timeout=10)
            self.assertEqual(ran.returncode,0,ran.stdout+ran.stderr)

    def test_unknown_completion_retains_gpu_resources(self):
        self.compile_and_run(HARNESS, ['quarantine_pending_work', 'timed_wait', 'vkp_image_destroy'])

    def test_failed_device_idle_does_not_destroy_swapchain(self):
        # LOGE's arguments must be evaluated as real production logging does.
        harness = SWAPCHAIN_HARNESS.replace('#define LOGE(...) (++errors)',
            'static void log_error(const char *fmt, int result, const char *where) { '
            '(void)fmt; (void)result; (void)where; ++errors; }\n#define LOGE(...) log_error(__VA_ARGS__)')
        self.compile_and_run(harness, ['quarantine_pending_work', 'destroy_swapchain'])


if __name__=='__main__':
    unittest.main()
