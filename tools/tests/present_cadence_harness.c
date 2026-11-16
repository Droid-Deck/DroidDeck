#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "present_cadence.h"

#define MS 1000000LL
#define P120 8333333LL

static uint32_t g_seed;
static int64_t rand_ns(int64_t max) {
    g_seed = g_seed * 1103515245u + 12345u;
    return max > 0 ? (int64_t)((g_seed >> 8) % (uint32_t)(max + 1)) : 0;
}

static int64_t at_or_after(int64_t t, int64_t grid, int64_t period) {
    return present_cadence_vsync_at_or_after(t, grid, period);
}

struct run {
    unsigned frames, off, dropped, paced, late;
    int64_t latency_max, latency_sum;
};

/* The presenter applies a paced frame PRESENT_CADENCE_APPLY_NS before SurfaceFlinger's deadline for
 * its refresh, or as soon as it is ready if that is later, and fillers keep every refresh presented,
 * so a frame applied lead ahead of a refresh is shown on it. Frames are applied in order; an NDK layer
 * has no backpressure, so a frame ready on the same refresh as the one before it replaces it. */
static struct run simulate(int paced, int64_t period, const char *gaps, int64_t wait_max, int64_t jitter, int frames,
                           int warmup) {
    const int64_t grid = 1000 * MS + 3 * MS, lead = 7 * MS;
    const size_t n_gaps = strlen(gaps);
    struct present_cadence c;
    present_cadence_reset(&c);
    struct run r = {0};
    int64_t *nominal = calloc((size_t)frames, sizeof(int64_t));
    int64_t *arrival = calloc((size_t)frames, sizeof(int64_t));
    int64_t *ready = calloc((size_t)frames, sizeof(int64_t));
    int64_t t = 1000 * MS;
    g_seed = 12345;
    for (int i = 0; i < frames; i++) {
        t += (gaps[(size_t)i % n_gaps] - '0') * period;
        nominal[i] = t;
        arrival[i] = t + rand_ns(2 * jitter) - jitter;
        const int64_t applied = arrival[i] + rand_ns(wait_max);
        int64_t apply = applied;
        if (paced) {
            const int64_t target = present_cadence_plan(&c, arrival[i], grid, period, lead);
            if (target && target - lead - PRESENT_CADENCE_APPLY_NS > apply) apply = target - lead - PRESENT_CADENCE_APPLY_NS;
            if (present_cadence_applied(&c, applied) && i >= warmup) r.late++;
            if (target && i >= warmup) r.paced++;
        }
        int64_t v = at_or_after(apply + lead, grid, period);
        if (i && v < ready[i - 1]) v = ready[i - 1];
        ready[i] = v;
    }
    int last = -1;
    for (int i = 0; i < frames; i++) {
        if (i + 1 < frames && ready[i + 1] == ready[i]) {
            if (i >= warmup) r.dropped++;
            continue;
        }
        if (i >= warmup && last >= 0) {
            const int64_t shown_gap = (ready[i] - ready[last] + period / 2) / period;
            const int64_t game_gap = (nominal[i] - nominal[last] + period / 2) / period;
            if (shown_gap != game_gap) r.off++;
            r.frames++;
            const int64_t latency = ready[i] - arrival[i];
            r.latency_sum += latency;
            if (latency > r.latency_max) r.latency_max = latency;
        }
        last = i;
    }
    free(nominal);
    free(arrival);
    free(ready);
    return r;
}

static void report(const char *what, struct run r) {
    fprintf(stderr, "%s: %u of %u off cadence, %u dropped, %u late, %u paced, latency %.1f/%.1f ms\n", what, r.off,
            r.frames, r.dropped, r.late, r.paced, r.frames ? r.latency_sum / 1e6 / r.frames : 0.0, r.latency_max / 1e6);
}

static void expect(int ok, const char *what, struct run r) {
    if (ok) return;
    report(what, r);
    exit(1);
}

static void test_rounding(void) {
    assert(at_or_after(100, 100, 10) == 100);
    assert(at_or_after(101, 100, 10) == 110);
    assert(at_or_after(95, 100, 10) == 100);
    assert(at_or_after(90, 100, 10) == 90);
    assert(at_or_after(89, 100, 10) == 90);
    assert(at_or_after(-1, 100, 10) == 0);
}

static void test_copy_jitter_at_sub_half_rates(void) {
    const char *cadences[] = {"4", "3"};
    for (unsigned i = 0; i < 2; i++) {
        struct run before = simulate(0, P120, cadences[i], 15 * MS, 1 * MS, 3000, 300);
        struct run after = simulate(1, P120, cadences[i], 15 * MS, 1 * MS, 3000, 300);
        expect(before.off * 10 >= before.frames, "unpaced model lost the judder", before);
        expect(after.off * 200 <= after.frames && after.dropped == 0, "paced", after);
        expect(after.latency_max <= 34 * MS, "paced latency", after);
    }
}

static void test_arrival_jitter_does_not_break_the_cadence(void) {
    struct run after = simulate(1, P120, "4", 12 * MS, 3 * MS, 3000, 300);
    expect(after.off * 100 <= after.frames && after.dropped == 0 && after.latency_max <= 40 * MS,
           "paced with 3 ms arrival jitter", after);
}

static void test_light_load_adds_no_refresh(void) {
    struct run before = simulate(0, P120, "4", MS / 2, MS / 2, 1000, 200);
    struct run after = simulate(1, P120, "4", MS / 2, MS / 2, 1000, 200);
    expect(after.off == 0 && after.latency_max <= before.latency_max + P120, "light load", after);
}

static void test_fast_streams_stay_unpaced(void) {
    struct run full = simulate(1, P120, "1", 6 * MS, MS / 2, 1000, 200);
    expect(full.paced == 0, "full rate", full);
    struct run mostly = simulate(1, P120, "1112", 6 * MS, MS / 2, 1000, 200);
    expect(mostly.paced == 0, "mostly full rate", mostly);
}

static void test_mixed_cadence_drops_nothing(void) {
    struct run before = simulate(0, P120, "12", 10 * MS, 1 * MS, 2000, 300);
    struct run after = simulate(1, P120, "12", 10 * MS, 1 * MS, 2000, 300);
    expect(after.dropped == 0 && after.off * 100 <= after.frames && after.off < before.off, "1 and 2 refreshes", after);
}

static void test_switching_rates_drops_nothing(void) {
    char gaps[1801];
    for (int i = 0; i < 1800; i++) gaps[i] = (i / 300) % 2 ? '1' : '4';
    gaps[1800] = 0;
    struct run r = simulate(1, P120, gaps, 12 * MS, 1 * MS, 1800, 300);
    expect(r.dropped == 0, "30 fps to full rate and back", r);
}

static void test_latency_comes_back_after_load_drops(void) {
    const int64_t p = P120, grid = 1000 * MS + 3 * MS, lead = 7 * MS;
    struct present_cadence c;
    present_cadence_reset(&c);
    int64_t heavy = 0, light = 0, prev_target = 0;
    unsigned short_intervals = 0;
    for (int i = 0; i < 1200; i++) {
        const int64_t arrival = 1000 * MS + (int64_t)i * 4 * p;
        const int64_t wait = i < 300 ? 14 * MS : MS / 2;
        const int64_t target = present_cadence_plan(&c, arrival, grid, p, lead);
        present_cadence_applied(&c, arrival + wait);
        if (!target) continue;
        if (i == 299) heavy = target - arrival;
        if (prev_target && i > 20 && (target - prev_target + p / 2) / p < 4) short_intervals++;
        prev_target = target;
        light = target - arrival;
    }
    if (!(light < heavy) || light > MS / 2 + PRESENT_CADENCE_MARGIN_NS + PRESENT_CADENCE_APPLY_NS + lead + p || short_intervals > 2) {
        fprintf(stderr, "latency %.1f ms after the load dropped (was %.1f ms), %u short intervals\n", light / 1e6,
                heavy / 1e6, short_intervals);
        exit(1);
    }
}

static void test_pause_and_missing_timeline_start_over(void) {
    const int64_t p = P120, grid = 1000 * MS, lead = 7 * MS;
    struct present_cadence c;
    present_cadence_reset(&c);
    int64_t t = 1000 * MS, target = 0;
    for (int i = 0; i < 12; i++, t += 4 * p) target = present_cadence_plan(&c, t, grid, p, lead);
    assert(target != 0 && c.pacing);
    t += 40 * p;
    assert(present_cadence_plan(&c, t, grid, p, lead) == 0 && !c.pacing);
    t += 4 * p;
    assert(present_cadence_plan(&c, t, 0, 0, 0) == 0 && !c.pacing);
    for (int i = 0; i < 12; i++, t += 4 * p) target = present_cadence_plan(&c, t, grid, p, lead);
    assert(target != 0 && c.pacing);
}

static void test_timeline(void) {
    int64_t grid, lead, period;
    present_cadence_set_timeline(0, 0, 0, 0);
    assert(!present_cadence_timeline(10 * MS, &grid, &lead, &period));
    present_cadence_set_timeline(1030 * MS, 1023 * MS, P120, 1000 * MS);
    assert(present_cadence_timeline(1010 * MS, &grid, &lead, &period));
    assert(grid == 1030 * MS && lead == 7 * MS && period == P120);
    assert(!present_cadence_timeline(1000 * MS + PRESENT_CADENCE_TIMELINE_STALE_NS + 1, &grid, &lead, &period));
    present_cadence_set_timeline(1030 * MS, 1023 * MS, 0, 1000 * MS);
    assert(!present_cadence_timeline(1010 * MS, &grid, &lead, &period));
    present_cadence_set_timeline(1030 * MS, 1040 * MS, P120, 1000 * MS);
    assert(!present_cadence_timeline(1010 * MS, &grid, &lead, &period));
}

int main(int argc, char **argv) {
    if (argc > 1) {
        const char *gaps = argv[1];
        const int64_t wait = atoll(argv[2]) * MS / 10, jitter = atoll(argv[3]) * MS / 10;
        report("unpaced", simulate(0, P120, gaps, wait, jitter, 3000, 300));
        report("paced  ", simulate(1, P120, gaps, wait, jitter, 3000, 300));
        return 0;
    }
    test_rounding();
    test_copy_jitter_at_sub_half_rates();
    test_arrival_jitter_does_not_break_the_cadence();
    test_light_load_adds_no_refresh();
    test_fast_streams_stay_unpaced();
    test_mixed_cadence_drops_nothing();
    test_switching_rates_drops_nothing();
    test_latency_comes_back_after_load_drops();
    test_pause_and_missing_timeline_start_over();
    test_timeline();
    puts("PASS");
    return 0;
}
