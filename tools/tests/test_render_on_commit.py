import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from test_output_refresh import SOURCE, extract_function


PREAMBLE = r'''
#include <assert.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>

struct wl_event_source { int armed; };
static struct wl_event_source idle_source, timer_source;
static void (*idle_func)(void *);
static int idles_added, timers_armed, renders, window_requests, effects_changes, pose_changes;
static int64_t clock_ns;
static int g_display_storage;
static void *g_display = &g_display_storage;
static struct wl_event_source *g_render_idle, *g_fallback_timer;
static int g_fallback_armed, g_dirty, g_hud_fresh, framegen;
static void *g_hud_surface;
static int64_t g_dirty_since_ns, g_last_vsync_ns, g_tick_vsync_ns, g_hud_last_ns, g_rendered_tick_ns;
static int g_tick_held;
static int64_t g_hold_missed_ns;
#define HOLD_RETRY_NS 1000000000LL
static int64_t g_refresh_ns = 16666667;
volatile int g_render_on_commit = 1;
volatile int g_release_unpaced = 1;
volatile int g_fps_limit;
static int64_t g_scene_due_ns;
static atomic_int g_stream_on;
static struct { unsigned ticks, ticks_late_2ms, commit_renders; int64_t tick_late_ns, tick_late_max_ns; } g_pace;
static struct { unsigned ticks; } g_perf;
static int64_t now_ns(void) { return clock_ns; }
static void *wl_display_get_event_loop(void *display) { return display; }
static struct wl_event_source *wl_event_loop_add_idle(void *loop, void (*func)(void *), void *data) {
    idles_added++; idle_func = func; return &idle_source;
}
static struct wl_event_source *wl_event_loop_add_timer(void *loop, int (*func)(void *), void *data) { return &timer_source; }
static int wl_event_source_timer_update(struct wl_event_source *source, int ms) { timers_armed++; return 0; }
static int vkp_framegen_active(void) { return framegen; }
static int vkp_apply_window_request(void) { return window_requests; }
static int vkp_effects_sync(void) { return effects_changes; }
static int vkp_cardboard_pose_changed(void) { return pose_changes; }
static void render_scene(void) { renders++; g_dirty = 0; g_dirty_since_ns = 0; g_rendered_tick_ns = g_tick_vsync_ns; g_tick_held = 0; }
static int on_fallback_timer(void *data) { return 0; }
static void on_commit_idle(void *data);
'''

CHECKS = r'''
static void run_idle(void) {
    void (*func)(void *) = idle_func;
    idle_func = NULL;
    if (func) func(NULL);
}

static void tick(int64_t t) { clock_ns = t; on_vsync(t); }

static void arrive(int64_t after_ns) { clock_ns += after_ns; schedule_render(); }

int main(void) {
    const int64_t refresh = 6944444, ms = 1000000;
    int64_t t = 1000000000LL;
    int r0;
    unsigned c0;
    g_refresh_ns = refresh;
    tick(t);
    assert(renders == 0);

    /* a new frame is drawn when it arrives */
    arrive(2 * ms);
    assert(idles_added == 1 && renders == 0);
    run_idle();
    assert(renders == 1 && g_pace.commit_renders == 1 && g_render_idle == NULL);

    /* a second frame in the same refresh is left for the next arrival, which is drawn when it arrives */
    arrive(1 * ms);
    assert(idles_added == 1);
    tick(t += refresh);
    assert(renders == 1 && g_tick_held == 1);
    arrive(2 * ms);
    assert(idles_added == 2);
    run_idle();
    assert(renders == 2 && g_pace.commit_renders == 2 && g_tick_held == 0);

    /* a frame no other frame follows is drawn at the tick after next */
    arrive(1 * ms);
    tick(t += refresh);
    assert(renders == 2);
    tick(t += refresh);
    assert(renders == 3 && g_pace.commit_renders == 2);

    /* after that, the tick draws every frame for a second, then holds one again */
    arrive(1 * ms);
    tick(t += refresh);
    assert(renders == 4 && g_tick_held == 0);
    t += HOLD_RETRY_NS;
    tick(t);
    arrive(1 * ms);
    run_idle();
    arrive(1 * ms);
    tick(t += refresh);
    assert(renders == 5 && g_tick_held == 1);
    arrive(2 * ms);
    run_idle();
    assert(renders == 6 && g_pace.commit_renders == 4);

    /* one frame per refresh, starting right after a tick draw, settles on drawing every frame when it arrives */
    window_requests = 1;
    tick(t += refresh);
    window_requests = 0;
    arrive(2 * ms);
    r0 = renders; c0 = g_pace.commit_renders;
    for (int i = 0; i < 10; i++) {
        tick(t += refresh);
        arrive(2 * ms);
        run_idle();
    }
    assert(renders - r0 == 10 && g_pace.commit_renders - c0 == 10);

    /* a client that draws again only after its frame callback (sent when the scene is drawn) keeps one frame
       per refresh: two held frames in 200 refreshes, about 1.4 s */
    r0 = renders; c0 = g_pace.commit_renders;
    {
        int seen = renders - 1;
        for (int i = 0; i < 200; i++) {
            if (renders != seen) { seen = renders; arrive(1 * ms); run_idle(); }
            tick(t += refresh);
        }
    }
    assert(renders - r0 == 198 && g_pace.commit_renders == c0);

    /* a window, effect or headset change at the tick draws the waiting frame now */
    t += HOLD_RETRY_NS;
    tick(t);
    arrive(1 * ms);
    run_idle();
    r0 = renders;
    arrive(1 * ms);
    window_requests = 1;
    tick(t += refresh);
    window_requests = 0;
    assert(renders - r0 == 1 && g_tick_held == 0);

    /* a draw queued before the tick still draws the frame once */
    tick(t += refresh);
    r0 = renders; c0 = g_pace.commit_renders;
    arrive(1 * ms);
    tick(t += refresh);
    assert(renders == r0);
    run_idle();
    assert(renders - r0 == 1 && g_pace.commit_renders - c0 == 1);
    tick(t += refresh);
    assert(renders - r0 == 1);

    /* streaming switched on after the draw was queued leaves the frame for the tick */
    tick(t += refresh);
    r0 = renders; c0 = g_pace.commit_renders;
    arrive(1 * ms);
    atomic_store(&g_stream_on, 1);
    run_idle();
    assert(renders == r0);
    tick(t += refresh);
    atomic_store(&g_stream_on, 0);
    assert(renders - r0 == 1 && g_pace.commit_renders == c0);

    /* paced output, frame generation, the switch off and streaming keep drawing on the tick */
    const int idles = idles_added;
    r0 = renders;
    for (int c = 0; c < 4; c++) {
        tick(t += refresh);
        if (c == 0) g_release_unpaced = 0;
        if (c == 1) framegen = 1;
        if (c == 2) g_render_on_commit = 0;
        if (c == 3) atomic_store(&g_stream_on, 1);
        arrive(1 * ms);
        assert(idles_added == idles);
        tick(t += refresh);
        g_release_unpaced = 1; framegen = 0; g_render_on_commit = 1; atomic_store(&g_stream_on, 0);
        assert(renders - r0 == c + 1);
    }

    /* without screen ticks the fallback timer still draws */
    clock_ns += 200000000LL;
    schedule_render();
    assert(idles_added == idles && g_fallback_armed == 1 && timers_armed == 1);
    puts("PASS");
    return 0;
}
'''


@unittest.skipUnless(sys.platform.startswith('linux'), 'needs a Linux host compiler')
class RenderOnCommitTest(unittest.TestCase):
    def test_a_new_frame_is_drawn_on_arrival_at_most_once_per_refresh(self):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if compiler is None:
            self.skipTest('A host C compiler is required')
        source = SOURCE.read_text()
        render = extract_function(source, 'render_scene')
        self.assertIn('g_rendered_tick_ns = g_tick_vsync_ns;', render)
        self.assertIn('g_tick_held = 0;', render)
        functions = ('scene_held_for_limit', 'commit_renders_on', 'render_on_commit', 'schedule_render', 'scene_waits_for_game', 'on_commit_idle', 'on_vsync')
        harness = PREAMBLE + '\n'.join(extract_function(source, name) for name in functions) + CHECKS
        with tempfile.TemporaryDirectory(prefix='droiddeck-render-on-commit-') as folder:
            path = Path(folder)
            (path / 'commit.c').write_text(harness)
            binary = path / 'commit-test'
            build = subprocess.run([compiler, '-std=c11', '-Wall', '-Wextra', '-Werror',
                                    '-Wno-unused-parameter', '-Wno-unused-function', '-O2',
                                    str(path / 'commit.c'), '-o', str(binary)], capture_output=True, text=True)
            self.assertEqual(0, build.returncode, build.stderr)
            result = subprocess.run([str(binary)], capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('PASS', result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
