#include "present_cadence.h"

#include <stdatomic.h>
#include <string.h>

static _Atomic int64_t g_timeline_grid_ns;
static _Atomic int64_t g_timeline_lead_ns;
static _Atomic int64_t g_timeline_period_ns;
static _Atomic int64_t g_timeline_at_ns;

void present_cadence_set_timeline(int64_t expected_present_ns, int64_t deadline_ns, int64_t period_ns,
                                  int64_t now_ns) {
    atomic_store(&g_timeline_grid_ns, expected_present_ns);
    atomic_store(&g_timeline_lead_ns, expected_present_ns - deadline_ns);
    atomic_store(&g_timeline_period_ns, period_ns);
    atomic_store(&g_timeline_at_ns, now_ns);
}

int present_cadence_timeline(int64_t now_ns, int64_t *grid_ns, int64_t *lead_ns, int64_t *period_ns) {
    const int64_t at = atomic_load(&g_timeline_at_ns);
    const int64_t grid = atomic_load(&g_timeline_grid_ns);
    const int64_t lead = atomic_load(&g_timeline_lead_ns);
    const int64_t period = atomic_load(&g_timeline_period_ns);
    if (at <= 0 || now_ns - at > PRESENT_CADENCE_TIMELINE_STALE_NS) return 0;
    if (period < 2000000LL || period > 50000000LL || grid <= 0 || lead < 0 || lead > 4 * period) return 0;
    *grid_ns = grid;
    *lead_ns = lead;
    *period_ns = period;
    return 1;
}

void present_cadence_reset(struct present_cadence *c) {
    memset(c, 0, sizeof(*c));
}

int64_t present_cadence_vsync_at_or_after(int64_t t_ns, int64_t grid_ns, int64_t period_ns) {
    const int64_t d = t_ns - grid_ns;
    const int64_t n = d >= 0 ? (d + period_ns - 1) / period_ns : -(-d / period_ns);
    return grid_ns + n * period_ns;
}

static int64_t max_wait(const struct present_cadence *c) {
    int64_t m = 0;
    for (unsigned i = 0; i < c->n_waits; i++)
        if (c->waits_ns[i] > m) m = c->waits_ns[i];
    return m;
}

static void drop_cadence(struct present_cadence *c) {
    c->last_target_ns = 0;
    c->spare_frames = 0;
}

int64_t present_cadence_plan(struct present_cadence *c, int64_t arrival_ns, int64_t grid_ns,
                             int64_t period_ns, int64_t lead_ns) {
    const int64_t gap = c->last_arrival_ns ? arrival_ns - c->last_arrival_ns : 0;
    c->last_arrival_ns = arrival_ns;
    c->target_ns = 0;
    c->interval = 0;
    if (period_ns <= 0 || grid_ns <= 0 || lead_ns < 0 || gap <= 0) {
        drop_cadence(c);
        return 0;
    }
    const int64_t k = (gap + period_ns / 2) / period_ns;
    if (k < 2 || k > PRESENT_CADENCE_MAX_GAP_VSYNCS) {
        drop_cadence(c);
        return 0;
    }
    c->interval = (unsigned)k;
    const int64_t earliest = arrival_ns + max_wait(c) + PRESENT_CADENCE_MARGIN_NS + lead_ns;
    const int64_t first = present_cadence_vsync_at_or_after(earliest, grid_ns, period_ns);
    int64_t target = first;
    if (c->last_target_ns) {
        const int64_t locked = present_cadence_vsync_at_or_after(c->last_target_ns + k * period_ns - period_ns / 2,
                                                                 grid_ns, period_ns);
        if (locked < first) {
            c->spare_frames = 0;
        } else if (locked < first + period_ns) {
            c->spare_frames = 0;
            target = locked;
        } else if (++c->spare_frames < PRESENT_CADENCE_SPARE_FRAMES) {
            target = locked;
        } else {
            c->spare_frames = 0;
            target = locked - period_ns;
        }
    }
    if (target - arrival_ns > PRESENT_CADENCE_MAX_AHEAD_NS) {
        drop_cadence(c);
        return 0;
    }
    c->last_target_ns = target;
    c->target_ns = target;
    c->lead_ns = lead_ns;
    return target;
}

int present_cadence_applied(struct present_cadence *c, int64_t arrival_ns, int64_t applied_ns) {
    int64_t wait = applied_ns - arrival_ns;
    if (wait < 0) wait = 0;
    c->waits_ns[c->next_wait] = wait;
    c->next_wait = (c->next_wait + 1) % PRESENT_CADENCE_WAITS;
    if (c->n_waits < PRESENT_CADENCE_WAITS) c->n_waits++;
    return c->target_ns && applied_ns + c->lead_ns > c->target_ns;
}
