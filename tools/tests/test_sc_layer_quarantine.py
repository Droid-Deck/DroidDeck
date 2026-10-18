"""Compile production window teardown, pool drain and Vulkan image destructor.

SurfaceControl, Vulkan and AHardwareBuffer endpoints are fake. No device is used.
"""
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
import unittest

from test_vk_pending_completion import function

SRC = Path(__file__).resolve().parents[2] / 'app/src/main/cpp/waylandcomp/src'

HARNESS = r'''
#define _DEFAULT_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
typedef struct { int refs; } AHardwareBuffer;
typedef struct ASurfaceControl ASurfaceControl;
typedef struct ANativeWindow ANativeWindow;
typedef struct { int32_t left, top, right, bottom; } ARect;
#define POOL_MAX 5
#define SC_LAYER_COUNT 2
struct vkp_image { uintptr_t image, mem; void *map; };
/* PRODUCTION_STRUCTS */
static struct layer g_layers[SC_LAYER_COUNT];
static struct { int state; } api;
static int g_dev_state, g_pending_cb;
static uintptr_t g_dev = 1;
static unsigned base_hides, initializations, retirements, ahb_releases;
static unsigned destroyed, unmapped, freed_memory, forgotten;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static int64_t now_ns(void) { return 0; }
static void base_hide(int gone) { assert(gone == 1); ++base_hides; }
static void layers_init(void) { ++initializations; }
static void retire_sc(struct layer *l) { (void)l; ++retirements; }
static void AHardwareBuffer_release(AHardwareBuffer *ahb) {
    assert(ahb->refs == 1); --ahb->refs; ++ahb_releases;
}
static void unmap_fn(uintptr_t dev, uintptr_t mem) { (void)dev; (void)mem; ++unmapped; }
static void destroy_fn(uintptr_t dev, uintptr_t img, void *allocator) {
    (void)dev; (void)img; (void)allocator; ++destroyed;
}
static void free_fn(uintptr_t dev, uintptr_t mem, void *allocator) {
    (void)dev; (void)mem; (void)allocator; ++freed_memory;
}
static struct {
    void (*UnmapMemory)(uintptr_t, uintptr_t);
    void (*DestroyImage)(uintptr_t, uintptr_t, void *);
    void (*FreeMemory)(uintptr_t, uintptr_t, void *);
} g_vk = {unmap_fn, destroy_fn, free_fn};
static void blendp_forget_image(uintptr_t image) { (void)image; ++forgotten; }
/* PRODUCTION_FUNCTIONS */
int main(int argc, char **argv) {
    assert(argc == 4);
    g_dev_state = atoi(argv[1]); api.state = atoi(argv[2]);
    const int repeats = atoi(argv[3]);
    for (int i=0; i<SC_LAYER_COUNT; ++i)
        for (int j=0; j<POOL_MAX; ++j) g_layers[i].slots[j].release_fd = -1;
    struct vkp_image *img = calloc(1, sizeof(*img)); assert(img);
    img->image=2; img->mem=3; img->map=img;
    AHardwareBuffer ahb = {.refs=1};
    int pipefd[2]; assert(pipe(pipefd) == 0);
    assert(write(pipefd[1], "x", 1) == 1);
    struct slot *slot = &g_layers[0].slots[0];
    slot->ahb=&ahb; slot->img=img; slot->busy=1; slot->release_fd=pipefd[0];
    g_layers[0].first_logged=1;
    struct layer before[SC_LAYER_COUNT]; memcpy(before,g_layers,sizeof(before));
    for (int i=0; i<repeats; ++i) sc_layer_window_gone();
    if (g_dev_state == -3) {
        assert(!ahb_releases && ahb.refs == 1);
        assert(!base_hides && !initializations && !retirements);
        assert(!destroyed && !unmapped && !freed_memory && !forgotten);
        assert(memcmp(before,g_layers,sizeof(before)) == 0);
        assert(fcntl(pipefd[0],F_GETFD) >= 0);
        free(img); close(pipefd[0]);
    } else if (api.state == 1) {
        assert(base_hides == (unsigned)repeats);
        assert(initializations == (unsigned)repeats);
        assert(retirements == (unsigned)(SC_LAYER_COUNT*repeats));
        assert(ahb_releases == 1 && ahb.refs == 0);
        assert(destroyed == 1 && unmapped == 1 && freed_memory == 1 && forgotten == 1);
        assert(!slot->img && !slot->ahb && !slot->busy && slot->release_fd == -1);
        assert(!g_layers[0].first_logged);
        errno=0; assert(fcntl(pipefd[0],F_GETFD) == -1 && errno == EBADF);
    } else {
        assert(base_hides == (unsigned)repeats);
        assert(!initializations && !retirements && !ahb_releases);
        assert(memcmp(before,g_layers,sizeof(before)) == 0);
        free(img); close(pipefd[0]);
    }
    close(pipefd[1]);
    return 0;
}
'''


class ScLayerQuarantineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source_dir = Path(os.environ.get('DROIDDECK_WINDOW_TEST_SOURCE', SRC))
        sc = (source_dir / 'sc_layer.c').read_text()
        vk = (source_dir / 'vk_present.c').read_text()
        structs = '\n'.join(re.search(r'struct ' + name + r' \{.*?\n\};', sc, re.S).group()
                            for name in ('slot', 'layer'))
        production = '\n'.join([function(vk, 'vkp_completion_unknown'),
                                function(vk, 'vkp_image_destroy'),
                                function(sc, 'drain_and_free_pools'),
                                function(sc, 'sc_layer_window_gone')])
        probe = HARNESS.replace('/* PRODUCTION_STRUCTS */', structs)
        probe = probe.replace('/* PRODUCTION_FUNCTIONS */', production)
        cls.tmp = tempfile.TemporaryDirectory(prefix='droiddeck-window-quarantine-')
        cls.addClassCleanup(cls.tmp.cleanup)
        src = Path(cls.tmp.name) / 'probe.c'
        cls.binary = src.with_suffix('')
        src.write_text(probe)
        cc = shutil.which(os.environ.get('CC', 'cc'))
        if not cc:
            raise AssertionError('C compiler required')
        result = subprocess.run([cc, '-std=c11', '-Wall', '-Wextra', '-Werror',
                                 '-UNDEBUG', '-pthread',
                                 *shlex.split(os.environ.get('DROIDDECK_TEST_CFLAGS', '')),
                                 str(src), '-o', str(cls.binary)], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)

    def run_scenario(self, state, api=1, repeats=1):
        result = subprocess.run([str(self.binary), str(state), str(api), str(repeats)],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_unknown_completion_retains_ahb_image_fd_and_layer(self):
        self.run_scenario(-3)

    def test_unknown_completion_repeated_requests_do_not_retire(self):
        self.run_scenario(-3, repeats=100)

    def test_unknown_completion_stops_before_base_hide_with_no_api(self):
        self.run_scenario(-3, api=0)

    def test_healthy_teardown_releases_ahb_image_and_fd(self):
        self.run_scenario(1)

    def test_genuine_device_loss_keeps_cleanup(self):
        self.run_scenario(-2)

    def test_uninitialized_device_keeps_existing_cleanup(self):
        self.run_scenario(0)

    def test_healthy_no_api_keeps_base_hide_only(self):
        self.run_scenario(1, api=0)


if __name__ == '__main__':
    unittest.main()
