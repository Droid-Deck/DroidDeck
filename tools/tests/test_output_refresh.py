import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'app/src/main/cpp/waylandcomp/src/compositor.c'


def extract_function(source, name):
    match = re.search(r'^(?:static )?(?:void|int|uint32_t) ' + re.escape(name) + r'\([^;]*?\) \{',
                      source, re.MULTILINE)
    if match is None:
        raise AssertionError(f'Missing compositor function: {name}')
    cursor, depth = match.end(), 1
    while depth:
        depth += (source[cursor] == '{') - (source[cursor] == '}')
        cursor += 1
    return source[match.start():cursor]


PREAMBLE = r'''
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/ioctl.h>

struct wl_resource { int version, modes, done, hz, w, h; struct wl_resource *next; };
struct surface { int xdg_toplevel, toplevel_committed; struct surface *next; };
struct input_msg { int type, p1, p2, p3, p4; };
static struct wl_resource second = { .version = 1 };
static struct wl_resource first = { .version = 4, .next = &second };
static struct surface surface = { .xdg_toplevel = 1, .toplevel_committed = 1 };
static int g_input_pipe[2] = {-1, -1};
static atomic_int g_output_refresh_mhz = 60000;
static atomic_int g_output_refresh_pending;
static int g_output_announced_refresh_mhz = 60000;
static int g_output_w = 1920, g_output_h = 1080;
static int flushes, resizes, pointers, ticks;
static int scale_changes, scale_mode, scale_alignment, renders;
static void *g_display;
#define WL_OUTPUT_MODE_CURRENT 1
#define WL_OUTPUT_MODE_PREFERRED 2
#define wl_resource_for_each(r, list) for ((r) = &first; (r); (r) = (r)->next)
#define wl_list_for_each(s, list, link) for ((s) = &surface; (s); (s) = (s)->next)
static int wl_resource_get_version(struct wl_resource *r) { return r->version; }
static void wl_output_send_mode(struct wl_resource *r, int flags, int w, int h, int hz) {
    assert(flags == 3); r->modes++; r->hz = hz; r->w = w; r->h = h;
}
static void wl_output_send_done(struct wl_resource *r) { r->done++; }
static void wl_display_flush_clients(void *display) { (void)display; flushes++; }
static void send_toplevel_configure(struct surface *s) { (void)s; resizes++; }
#define droiddeck_log(...) ((void)0)
#define deliver_key(m) ((void)0)
#define pointer_event(...) ((void)0)
#define pointer_button(...) ((void)0)
#define scroll_event(...) ((void)0)
#define axis_event(...) ((void)0)
#define stream_take(...) ((void)0)
#define stream_forget_all(...) ((void)0)
#define pointer_delta(...) ((void)0)
#define deliver_touch(m) ((void)0)
#define deliver_pointer(m) (pointers++)
static void on_vsync(int64_t ns) { assert(ns > 0); ticks++; }
static void vk_present_set_scale_mode(int mode, int alignment) {
    scale_changes++; scale_mode = mode; scale_alignment = alignment;
}
static void schedule_render(void) { renders++; }
'''

CHECKS = r'''
static int queued(void) { int n; assert(ioctl(g_input_pipe[0], FIONREAD, &n) == 0); return n; }
static void drain(void) { on_input_readable(g_input_pipe[0], 0, NULL); }
static void expect(int modes, int hz, int resize_count) {
    assert(first.modes == modes && second.modes == modes);
    assert(first.hz == hz && second.hz == hz);
    assert(first.w == 1920 && second.w == 1920 && first.h == 1080 && second.h == 1080);
    assert(first.done == modes && second.done == 0);
    assert(resizes == resize_count);
    assert(pointers == 0);
}
int main(void) {
    droiddeck_wayland_set_output_refresh_rate(120000);
    assert(atomic_load(&g_output_refresh_mhz) == 120000);
    assert(!atomic_load(&g_output_refresh_pending));
    assert(first.modes == 0 && second.modes == 0);
    assert(pipe2(g_input_pipe, O_CLOEXEC | O_NONBLOCK) == 0);
    g_output_announced_refresh_mhz = atomic_load(&g_output_refresh_mhz);
    droiddeck_wayland_set_scale_mode(1, 4);
    assert(queued() == sizeof(struct input_msg));
    drain();
    assert(scale_changes == 1 && scale_mode == 1 && scale_alignment == 4 && renders == 1);
    assert(first.modes == 0 && second.modes == 0);
    droiddeck_wayland_set_output_refresh_rate(120000);
    assert(queued() == 0);
    droiddeck_wayland_set_output_refresh_rate(60000);
    assert(first.modes == 0 && queued() == sizeof(struct input_msg));
    drain(); expect(1, 60000, 0); assert(flushes == 1);
    droiddeck_wayland_set_output_refresh_rate(60000);
    assert(queued() == 0); drain(); expect(1, 60000, 0);
    droiddeck_wayland_set_output_refresh_rate(90000);
    droiddeck_wayland_set_output_refresh_rate(120000);
    drain(); expect(2, 120000, 0);
    droiddeck_wayland_set_output_refresh_rate(90000);
    droiddeck_wayland_set_output_refresh_rate(120000);
    drain(); expect(2, 120000, 0);
    droiddeck_wayland_set_output_refresh_rate(60000);
    droiddeck_wayland_output_resized();
    drain(); expect(3, 60000, 1); assert(flushes == 3);
    struct input_msg wake = {10, 0, 0, 0, 0};
    int n = 0;
    while (write(g_input_pipe[1], &wake, sizeof(wake)) == sizeof(wake)) n++;
    assert(n > 0 && errno == EAGAIN);
    droiddeck_wayland_set_output_refresh_rate(120000);
    drain(); expect(4, 120000, 1);
    droiddeck_wayland_set_output_refresh_rate(0);
    drain(); expect(5, 60000, 1);
    droiddeck_wayland_set_output_refresh_rate(-1);
    assert(queued() == 0);
    struct input_msg vsync = {5, 0, 16000000, 0, 0};
    assert(write(g_input_pipe[1], &vsync, sizeof(vsync)) == sizeof(vsync));
    drain(); expect(5, 60000, 1); assert(ticks == 1);
    assert(nominal_refresh_ns() == 16666666);
    assert(flushes == 5);
    assert(scale_changes == 1 && scale_mode == 1 && scale_alignment == 4 && renders == 1);
    droiddeck_wayland_set_scale_mode(1, 3);
    droiddeck_wayland_set_output_refresh_rate(90000);
    drain(); expect(6, 90000, 1);
    assert(scale_changes == 2 && scale_mode == 1 && scale_alignment == 3 && renders == 2);
    close(g_input_pipe[0]); close(g_input_pipe[1]);
    puts("PASS");
}
'''


@unittest.skipUnless(sys.platform.startswith('linux'), 'needs Linux nonblocking pipes')
class OutputRefreshTest(unittest.TestCase):
    def test_refresh_notifications_preserve_client_pacing_without_resizing(self):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if compiler is None:
            self.skipTest('A host C compiler is required')
        source = SOURCE.read_text()
        functions = ('nominal_refresh_ns', 'output_send_mode', 'output_notify_mode',
                     'output_resized', 'on_input_readable', 'droiddeck_wayland_output_resized',
                     'droiddeck_wayland_set_scale_mode',
                     'droiddeck_wayland_set_output_refresh_rate')
        harness = PREAMBLE + '\n'.join(extract_function(source, name) for name in functions) + CHECKS
        with tempfile.TemporaryDirectory(prefix='droiddeck-output-refresh-') as folder:
            path = Path(folder)
            (path / 'refresh.c').write_text(harness)
            binary = path / 'refresh-test'
            build = subprocess.run([compiler, '-std=c11', '-Wall', '-Wextra', '-Werror',
                                    '-Wno-unused-parameter', '-O2', str(path / 'refresh.c'),
                                    '-o', str(binary)], capture_output=True, text=True)
            self.assertEqual(0, build.returncode, build.stderr)
            result = subprocess.run([str(binary)], capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('PASS', result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
