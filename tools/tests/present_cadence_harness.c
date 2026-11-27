#include <assert.h>
#include <errno.h>
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
    unsigned frames, off, paced, late, held_after;
    int64_t latency_max, latency_sum;
};

/* The presenter applies a paced frame PRESENT_CADENCE_APPLY_NS before SurfaceFlinger's deadline for
 * its refresh, or as soon as it is ready if that is later, and fillers keep every refresh presented,
 * so a frame applied lead ahead of a refresh is shown on it. An unpaced frame takes any frames still
 * held out with it. The game layer has backpressure, so frames are shown in order, one per refresh.
 *
 * A copied frame is applied once its copy is done, up to wait_max after it arrived. A zero-copy frame
 * is applied on arrival, but SurfaceFlinger takes it only once the client's GPU work on it is done, up
 * to wait_max later; the planner learns that wait when the next frame arrives. held_after counts the
 * frames from index held_from on that were given a target. */
static struct run simulate_run(int paced, int zero_copy, int64_t period, const char *gaps, int64_t wait_max,
                               int64_t jitter, int frames, int warmup, int held_from) {
    const int64_t grid = 1000 * MS + 3 * MS, lead = 7 * MS;
    const size_t n_gaps = strlen(gaps);
    struct present_cadence c;
    present_cadence_reset(&c);
    struct run r = {0};
    int64_t *nominal = calloc((size_t)frames, sizeof(int64_t));
    int64_t *arrival = calloc((size_t)frames, sizeof(int64_t));
    int64_t *apply = calloc((size_t)frames, sizeof(int64_t));
    int64_t *done = calloc((size_t)frames, sizeof(int64_t));
    int64_t t = 1000 * MS, prev_ref = 0, prev_done = 0;
    g_seed = 12345;
    for (int i = 0; i < frames; i++) {
        t += (gaps[(size_t)i % n_gaps] - '0') * period;
        nominal[i] = t;
        arrival[i] = t + rand_ns(2 * jitter) - jitter;
        done[i] = arrival[i] + rand_ns(wait_max);
        const int64_t applied = zero_copy ? arrival[i] : done[i];
        apply[i] = applied;
        if (paced) {
            const int64_t target = present_cadence_plan(&c, arrival[i], grid, period, lead);
            if (target && target - lead - PRESENT_CADENCE_APPLY_NS > applied)
                apply[i] = target - lead - PRESENT_CADENCE_APPLY_NS;
            for (int j = i - 1; !target && j >= 0 && apply[j] > applied; j--) apply[j] = applied;
            if (zero_copy) {
                if (prev_ref) present_cadence_note_wait(&c, prev_done - prev_ref);
                prev_ref = c.planned_ref_ns;
                prev_done = done[i];
            }
            if (present_cadence_applied(&c, applied) && i >= warmup) r.late++;
            if (target && i >= warmup) r.paced++;
            if (target && held_from > 0 && i >= held_from) r.held_after++;
        }
    }
    int64_t shown_prev = 0;
    for (int i = 0; i < frames; i++) {
        int64_t v = at_or_after((apply[i] > done[i] ? apply[i] : done[i]) + lead, grid, period);
        if (i && v <= shown_prev) v = shown_prev + period;
        if (i >= warmup) {
            const int64_t shown_gap = (v - shown_prev + period / 2) / period;
            const int64_t game_gap = (nominal[i] - nominal[i - 1] + period / 2) / period;
            if (shown_gap != game_gap) r.off++;
            r.frames++;
            const int64_t latency = v - arrival[i];
            r.latency_sum += latency;
            if (latency > r.latency_max) r.latency_max = latency;
        }
        shown_prev = v;
    }
    free(nominal);
    free(arrival);
    free(apply);
    free(done);
    return r;
}

static struct run simulate_path(int paced, int zero_copy, int64_t period, const char *gaps, int64_t wait_max,
                                int64_t jitter, int frames, int warmup) {
    return simulate_run(paced, zero_copy, period, gaps, wait_max, jitter, frames, warmup, 0);
}

static struct run simulate(int paced, int64_t period, const char *gaps, int64_t wait_max, int64_t jitter, int frames,
                           int warmup) {
    return simulate_path(paced, 0, period, gaps, wait_max, jitter, frames, warmup);
}

static void report(const char *what, struct run r) {
    fprintf(stderr, "%s: %u of %u off cadence, %u late, %u paced, latency %.1f/%.1f ms\n", what, r.off,
            r.frames, r.late, r.paced, r.frames ? r.latency_sum / 1e6 / r.frames : 0.0, r.latency_max / 1e6);
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
        expect(after.off * 200 <= after.frames, "paced", after);
        expect(after.latency_max <= 34 * MS, "paced latency", after);
    }
}

static void test_zero_copy_waits_for_the_gpu(void) {
    const char *cadences[] = {"4", "3"};
    for (unsigned i = 0; i < 2; i++) {
        struct run before = simulate_path(0, 1, P120, cadences[i], 15 * MS, 1 * MS, 3000, 300);
        struct run after = simulate_path(1, 1, P120, cadences[i], 15 * MS, 1 * MS, 3000, 300);
        expect(before.off * 10 >= before.frames, "unpaced zero-copy model lost the judder", before);
        expect(after.off * 200 <= after.frames, "paced zero-copy", after);
        expect(after.latency_max <= 34 * MS, "paced zero-copy latency", after);
    }
}

static void test_arrival_jitter_does_not_break_the_cadence(void) {
    struct run after = simulate(1, P120, "4", 12 * MS, 3 * MS, 3000, 300);
    expect(after.off * 100 <= after.frames && after.latency_max <= 40 * MS,
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

static void test_mixed_cadence(void) {
    struct run before = simulate(0, P120, "12", 10 * MS, 1 * MS, 2000, 300);
    struct run after = simulate(1, P120, "12", 10 * MS, 1 * MS, 2000, 300);
    expect(after.off * 100 <= after.frames && after.off < before.off, "1 and 2 refreshes", after);
}

static void test_full_rate_ends_the_hold(void) {
    char gaps[901];
    for (int i = 0; i < 900; i++) gaps[i] = i < 300 ? '4' : '1';
    gaps[900] = 0;
    struct run r = simulate_run(1, 0, P120, gaps, 12 * MS, 1 * MS, 900, 300, 300 + PRESENT_CADENCE_RATE_FRAMES);
    expect(r.held_after == 0, "frames still held after 30 fps became full rate", r);
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

static void test_slot_order_tolerates_grid_drift(void) {
    const int64_t p = P120, target = 1000 * MS;
    for (int64_t drift = -100000; drift <= 100000; drift += 25000) {
        const int64_t slot = target + drift;
        assert(present_cadence_slot_order(target, slot, p) == 0);
        assert(present_cadence_slot_order(target, slot + p, p) == -1);
        assert(present_cadence_slot_order(target, slot - p, p) == 1);
    }
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

static int parse_tenths(const char *text, int64_t *out) {
    char *end = NULL;
    errno = 0;
    const long v = strtol(text, &end, 10);
    if (errno || end == text || *end || v < 0 || v > 1000) return 0;
    *out = (int64_t)v * MS / 10;
    return 1;
}

int main(int argc, char **argv) {
    if (argc > 1) {
        const char *gaps = argv[1];
        int64_t wait = 0, jitter = 0;
        int gaps_ok = argc == 4 && gaps[0];
        for (const char *g = gaps; gaps_ok && *g; g++) gaps_ok = *g >= '1' && *g <= '9';
        if (!gaps_ok || !parse_tenths(argv[2], &wait) || !parse_tenths(argv[3], &jitter)) {
            fprintf(stderr, "usage: %s GAPS WAIT JITTER\n"
                            "  GAPS: refreshes between frames, repeated (digits 1-9, e.g. 4 or 23)\n"
                            "  WAIT, JITTER: longest copy wait and arrival jitter in tenths of a ms (0-1000)\n", argv[0]);
            return 2;
        }
        report("unpaced", simulate(0, P120, gaps, wait, jitter, 3000, 300));
        report("paced  ", simulate(1, P120, gaps, wait, jitter, 3000, 300));
        return 0;
    }
    test_rounding();
    test_copy_jitter_at_sub_half_rates();
    test_zero_copy_waits_for_the_gpu();
    test_arrival_jitter_does_not_break_the_cadence();
    test_light_load_adds_no_refresh();
    test_fast_streams_stay_unpaced();
    test_mixed_cadence();
    test_full_rate_ends_the_hold();
    test_latency_comes_back_after_load_drops();
    test_pause_and_missing_timeline_start_over();
    test_slot_order_tolerates_grid_drift();
    test_timeline();
    puts("PASS");
    return 0;
}
