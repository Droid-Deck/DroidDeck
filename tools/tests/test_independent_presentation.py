import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from test_output_refresh import SOURCE, extract_function


class IndependentPresentationTest(unittest.TestCase):
    def compile_and_run(self, harness):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if not compiler:
            self.skipTest('A host C compiler is required')
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            source = folder / 'presentation.c'
            source.write_text(harness)
            binary = folder / 'presentation-test'
            subprocess.run([compiler, '-std=c11', '-Wall', '-Werror', str(source), '-o', str(binary)], check=True)
            subprocess.run([str(binary)], check=True, timeout=10)

    def test_ahb_primary_does_not_require_or_attempt_vulkan_import(self):
        source = SOURCE.read_text()
        start = source.index('static struct surface *window_primary(')
        function = source[start:source.index('\nstatic void window_parts_destroy', start)]
        harness = r'''
#include <assert.h>
#include <stddef.h>
struct surface {
    struct surface *children, *next;
    int has_content, buf_w, buf_h, sub_x, sub_y, src_set, dst_set;
    int src[4], dst[2], img;
    void *dmabuf_buf;
};
#define wl_list_for_each(s, head, member) for (s = *(head); s; s = s->next)
static int imports;
static int ahb_swapchain_has_ahb(void *buffer) { return buffer != NULL; }
static void *surface_image(struct surface *s) { imports++; return s->img ? s : NULL; }
''' + function + r'''
int main(void) {
    int ahb;
    struct surface root = {.has_content = 1, .buf_w = 800, .buf_h = 600, .dmabuf_buf = &ahb};
    assert(window_primary(&root, 800, 600) == &root && imports == 0);
    struct surface child = root;
    root.children = &child;
    assert(window_primary(&root, 800, 600) == &child && imports == 0);
    child.sub_x = 1;
    assert(window_primary(&root, 800, 600) == &root && imports == 0);
    child.sub_x = 0; child.dmabuf_buf = NULL; child.img = 1;
    assert(window_primary(&root, 800, 600) == &child && imports == 1);
    child.img = 0; root.children = NULL; root.dmabuf_buf = NULL;
    assert(window_primary(&root, 800, 600) == NULL && imports == 2);
    root.has_content = 0; root.dmabuf_buf = &ahb;
    assert(window_primary(&root, 800, 600) == NULL && imports == 2);
}
'''
        self.compile_and_run(harness)

    def test_idle_and_independent_only_frames_do_not_arm_scene_retries(self):
        source = SOURCE.read_text()
        render = extract_function(source, 'render_scene')
        begin = render.index('    rendered |= render_independent_windows();')
        end = render.index('    free(dl.d);', begin)
        tail = render[begin:end]
        harness = r'''
#include <assert.h>
#include <stdint.h>
#include <stddef.h>
struct surface { int drawn; void *feedback; };
static struct surface *g_surfaces;
#define wl_list_for_each(s, head, member) for (s = *(head); s; s = NULL)
static void *g_display, *g_frame_timer;
static int g_scene_dirty, g_stat_frames, g_hud_new, timers, independent;
static struct { int undrawn; } g_pace;
static int render_independent_windows(void) { return independent; }
static int64_t presented_ns(int64_t t) { return t; }
static void hud_frame_shown(int64_t t) {}
static void feedback_present_all(void *feedback, int64_t t) {}
static int feedback_discard_all(void *feedback) { return 0; }
static void fire_all_frames(void) {}
static void pace_without_output(void) {}
static void *wl_display_get_event_loop(void *display) { return display; }
static int on_frame_timer(void *data) { return 0; }
static void *wl_event_loop_add_timer(void *loop, int (*func)(void *), void *data) { return &timers; }
static void wl_event_source_timer_update(void *timer, int ms) { timers++; }
static void finish(int scene_requested, int scene_ready, int rendered) {
    struct surface *s;
    int64_t t_scene = 0;
''' + tail + r'''
}
int main(void) {
    finish(0, 1, 0);
    finish(0, 0, 0);
    independent = 1;
    finish(0, 1, 0);
    assert(timers == 0);
    g_scene_dirty = 1;
    finish(1, 1, 0);
    assert(timers == 1);
    g_scene_dirty = 0;
    finish(1, 0, 0);
    assert(timers == 2);
    finish(1, 1, 1);
    assert(timers == 2);
}
'''
        self.compile_and_run(harness)
