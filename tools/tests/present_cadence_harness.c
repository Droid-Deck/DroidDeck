#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#include "present_cadence.h"

#define MS 1000000LL

static uint32_t g_seed = 12345;
static int64_t rand_ns(int64_t max) {
    g_seed = g_seed * 1103515245u + 12345u;
    return (int64_t)((g_seed >> 8) % (uint32_t)(max + 1));
}

static int64_t at_or_after(int64_t t, int64_t grid, int64_t period) {
    return present_cadence_vsync_at_or_after(t, grid, period);
}

/* SurfaceFlinger shows a transaction at the first vsync it was applied lead_ns ahead of whose
 * expected present time is after the desired present time. */
static int64_t shown_at(int64_t applied, int64_t desired, int64_t grid, int64_t period, int64_t lead) {
    int64_t v = at_or_after(applied + lead, grid, period);
    if (desired)
        while (desired >= v) v += period;
    return v;
}

struct run {
    unsigned frames, off, late;
    int64_t latency_max, latency_sum;
};

static struct run simulate(int paced, int64_t period, int every, int64_t wait_max, int64_t jitter, int frames,
                           int warmup) {
    const int64_t grid = 1000 * MS + 3 * MS;
    const int64_t lead = 7 * MS;
    struct present_cadence c;
    present_cadence_reset(&c);
    struct run r = {0};
    int64_t last_shown = 0;
    for (int i = 0; i < frames; i++) {
        const int64_t arrival = 1000 * MS + (int64_t)i * every * period + rand_ns(2 * jitter) - jitter;
        const int64_t applied = arrival + rand_ns(wait_max);
        int64_t desired = 0;
        if (paced) {
            const int64_t target = present_cadence_plan(&c, arrival, grid, period, lead);
            if (target) desired = target - period / 2;
            if (present_cadence_applied(&c, arrival, applied) && i >= warmup) r.late++;
        }
        const int64_t shown = shown_at(applied, desired, grid, period, lead);
        if (i >= warmup) {
            const int64_t vsyncs = (shown - last_shown + period / 2) / period;
            if (vsyncs != every) r.off++;
            r.frames++;
            const int64_t latency = shown - arrival;
            r.latency_sum += latency;
            if (latency > r.latency_max) r.latency_max = latency;
        }
        last_shown = shown;
    }
    return r;
}

static void test_rounding(void) {
    assert(at_or_after(100, 100, 10) == 100);
    assert(at_or_after(101, 100, 10) == 110);
    assert(at_or_after(95, 100, 10) == 100);
    assert(at_or_after(90, 100, 10) == 90);
    assert(at_or_after(89, 100, 10) == 90);
    assert(at_or_after(-1, 100, 10) == 0);
}

static void test_blit_jitter_at_quarter_rate(void) {
    const int64_t p120 = 8333333;
    struct run before = simulate(0, p120, 4, 15 * MS, 1 * MS, 2000, 200);
    struct run after = simulate(1, p120, 4, 15 * MS, 1 * MS, 2000, 200);
    if (before.off * 10 < before.frames) {
        fprintf(stderr, "unpaced model lost the judder: %u of %u off cadence\n", before.off, before.frames);
        exit(1);
    }
    if (after.off * 200 > after.frames) {
        fprintf(stderr, "paced 30 on 120: %u of %u off cadence, %u late\n", after.off, after.frames, after.late);
        exit(1);
    }
    if (after.latency_max > 34 * MS) {
        fprintf(stderr, "paced latency %.1f ms\n", after.latency_max / 1e6);
        exit(1);
    }
    struct run third = simulate(1, p120, 3, 15 * MS, 1 * MS, 2000, 200);
    if (third.off * 200 > third.frames) {
        fprintf(stderr, "paced 40 on 120: %u of %u off cadence\n", third.off, third.frames);
        exit(1);
    }
}

static void test_light_load_adds_no_slot(void) {
    const int64_t p120 = 8333333;
    struct run before = simulate(0, p120, 4, MS / 2, MS / 2, 1000, 200);
    struct run after = simulate(1, p120, 4, MS / 2, MS / 2, 1000, 200);
    assert(after.off == 0);
    if (after.latency_max > before.latency_max + p120) {
        fprintf(stderr, "light load: paced %.1f ms, unpaced %.1f ms\n", after.latency_max / 1e6, before.latency_max / 1e6);
        exit(1);
    }
}

static void test_refresh_rate_streams_stay_unpaced(void) {
    const int64_t p = 8333333, grid = 1000 * MS, lead = 7 * MS;
    struct present_cadence c;
    present_cadence_reset(&c);
    assert(present_cadence_plan(&c, 1000 * MS, grid, p, lead) == 0);
    for (int i = 1; i < 50; i++) assert(present_cadence_plan(&c, 1000 * MS + i * p, grid, p, lead) == 0);
    assert(present_cadence_plan(&c, 1000 * MS + 51 * p, grid, p, lead) != 0);
    assert(c.interval == 2);
}

static void test_pause_and_missing_timeline_start_over(void) {
    const int64_t p = 8333333, grid = 1000 * MS, lead = 7 * MS;
    struct present_cadence c;
    present_cadence_reset(&c);
    int64_t t = 1000 * MS;
    present_cadence_plan(&c, t, grid, p, lead);
    t += 4 * p;
    assert(present_cadence_plan(&c, t, grid, p, lead) != 0);
    t += 40 * p;
    assert(present_cadence_plan(&c, t, grid, p, lead) == 0);
    assert(c.last_target_ns == 0);
    t += 4 * p;
    assert(present_cadence_plan(&c, t, 0, 0, 0) == 0);
    t += 4 * p;
    const int64_t target = present_cadence_plan(&c, t, grid, p, lead);
    assert(target >= t + lead && target < t + lead + 2 * p);
}

static void test_latency_comes_back_after_load_drops(void) {
    const int64_t p = 8333333, grid = 1000 * MS + 3 * MS, lead = 7 * MS;
    struct present_cadence c;
    present_cadence_reset(&c);
    int64_t first_light = 0, last = 0;
    unsigned short_intervals = 0;
    int64_t prev_target = 0;
    for (int i = 0; i < 1200; i++) {
        const int64_t arrival = 1000 * MS + (int64_t)i * 4 * p;
        const int64_t wait = i < 300 ? 14 * MS : MS / 2;
        const int64_t target = present_cadence_plan(&c, arrival, grid, p, lead);
        present_cadence_applied(&c, arrival, arrival + wait);
        if (!target) continue;
        if (i == 299) first_light = target - arrival;
        if (prev_target && (target - prev_target + p / 2) / p < 4) short_intervals++;
        prev_target = target;
        last = target - arrival;
    }
    if (!(last < first_light)) {
        fprintf(stderr, "latency stayed at %.1f ms (was %.1f ms)\n", last / 1e6, first_light / 1e6);
        exit(1);
    }
    assert(last <= MS / 2 + PRESENT_CADENCE_MARGIN_NS + lead + p);
    assert(short_intervals <= 2);
}

static void test_timeline(void) {
    int64_t grid, lead, period;
    present_cadence_set_timeline(0, 0, 0, 0);
    assert(!present_cadence_timeline(10 * MS, &grid, &lead, &period));
    present_cadence_set_timeline(1030 * MS, 1023 * MS, 8333333, 1000 * MS);
    assert(present_cadence_timeline(1010 * MS, &grid, &lead, &period));
    assert(grid == 1030 * MS && lead == 7 * MS && period == 8333333);
    assert(!present_cadence_timeline(1000 * MS + PRESENT_CADENCE_TIMELINE_STALE_NS + 1, &grid, &lead, &period));
    present_cadence_set_timeline(1030 * MS, 1023 * MS, 0, 1000 * MS);
    assert(!present_cadence_timeline(1010 * MS, &grid, &lead, &period));
    present_cadence_set_timeline(1030 * MS, 1040 * MS, 8333333, 1000 * MS);
    assert(!present_cadence_timeline(1010 * MS, &grid, &lead, &period));
}

int main(void) {
    test_rounding();
    test_blit_jitter_at_quarter_rate();
    test_light_load_adds_no_slot();
    test_refresh_rate_streams_stay_unpaced();
    test_pause_and_missing_timeline_start_over();
    test_latency_comes_back_after_load_drops();
    test_timeline();
    puts("PASS");
    return 0;
}
