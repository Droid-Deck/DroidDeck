#include "present_cadence.h"

#include <pthread.h>
#include <string.h>

static pthread_mutex_t g_timeline_lock = PTHREAD_MUTEX_INITIALIZER;
static int64_t g_timeline_grid_ns, g_timeline_lead_ns, g_timeline_period_ns, g_timeline_at_ns;

void present_cadence_set_timeline(int64_t expected_present_ns, int64_t deadline_ns, int64_t period_ns,
                                  int64_t now_ns) {
    pthread_mutex_lock(&g_timeline_lock);
    g_timeline_grid_ns = expected_present_ns;
    g_timeline_lead_ns = expected_present_ns - deadline_ns;
    g_timeline_period_ns = period_ns;
    g_timeline_at_ns = now_ns;
    pthread_mutex_unlock(&g_timeline_lock);
}

int present_cadence_timeline(int64_t now_ns, int64_t *grid_ns, int64_t *lead_ns, int64_t *period_ns) {
    pthread_mutex_lock(&g_timeline_lock);
    const int64_t at = g_timeline_at_ns, grid = g_timeline_grid_ns, lead = g_timeline_lead_ns,
                  period = g_timeline_period_ns;
    pthread_mutex_unlock(&g_timeline_lock);
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

int present_cadence_slot_order(int64_t target_ns, int64_t slot_ns, int64_t period_ns) {
    if (target_ns < slot_ns - period_ns / 2) return -1;
    return target_ns < slot_ns + period_ns / 2 ? 0 : 1;
}

static int64_t max_wait(const struct present_cadence *c) {
    int64_t m = 0;
    for (unsigned i = 0; i < c->n_waits; i++)
        if (c->waits_ns[i] > m) m = c->waits_ns[i];
    return m;
}

static void leave_pacing(struct present_cadence *c) {
    c->pacing = 0;
    c->cadence_ok = 0;
    c->spare_frames = 0;
    c->n_gaps = c->next_gap = 0;
}

static unsigned note_gap(struct present_cadence *c, int64_t k) {
    c->gaps[c->next_gap] = (unsigned)k;
    c->next_gap = (c->next_gap + 1) % PRESENT_CADENCE_RATE_FRAMES;
    if (c->n_gaps < PRESENT_CADENCE_RATE_FRAMES) c->n_gaps++;
    unsigned sum = 0;
    for (unsigned i = 0; i < c->n_gaps; i++) sum += c->gaps[i];
    return sum;
}

int64_t present_cadence_plan(struct present_cadence *c, int64_t arrival_ns, int64_t grid_ns,
                             int64_t period_ns, int64_t lead_ns) {
    c->target_ns = 0;
    c->interval = 0;
    c->planned_ref_ns = 0;
    if (period_ns <= 0 || grid_ns <= 0 || lead_ns < 0) {
        c->arrival_ref_ns = 0;
        leave_pacing(c);
        return 0;
    }
    const int64_t d = arrival_ns - c->arrival_ref_ns;
    int64_t k = -1;
    if (!c->arrival_ref_ns || d < -period_ns / 2 ||
        d >= PRESENT_CADENCE_MAX_GAP_VSYNCS * period_ns + period_ns / 2) {
        c->arrival_ref_ns = arrival_ns;
        leave_pacing(c);
    } else {
        k = (d + period_ns / 2) / period_ns;
        c->arrival_ref_ns += k * period_ns + (d - k * period_ns) / 8;
        const unsigned sum = note_gap(c, k);
        if (!c->pacing && c->n_gaps == PRESENT_CADENCE_RATE_FRAMES && sum >= PRESENT_CADENCE_ENTER_VSYNCS) {
            c->pacing = 1;
            c->cadence_ok = 0;
            c->spare_frames = 0;
        } else if (c->pacing && sum < PRESENT_CADENCE_EXIT_VSYNCS) {
            leave_pacing(c);
        }
        c->interval = (unsigned)k;
    }
    const int64_t ref = c->arrival_ref_ns;
    const int64_t first =
        present_cadence_vsync_at_or_after(
        ref + max_wait(c) + PRESENT_CADENCE_MARGIN_NS + PRESENT_CADENCE_APPLY_NS + lead_ns, grid_ns, period_ns);
    int64_t target;
    if (!c->pacing) {
        target = 0;
        c->last_target_ns = 0;
    } else {
        const int64_t step = k > 1 ? k : 1;
        int64_t locked = 0;
        if (c->cadence_ok && c->last_target_ns)
            locked = present_cadence_vsync_at_or_after(c->last_target_ns + step * period_ns - period_ns / 2,
                                                       grid_ns, period_ns);
        if (!locked || locked < first) {
            c->spare_frames = 0;
            target = first;
        } else if (step >= 2 && locked - period_ns >= first) {
            if (++c->spare_frames >= PRESENT_CADENCE_SPARE_FRAMES) {
                c->spare_frames = 0;
                target = locked - period_ns;
            } else {
                target = locked;
            }
        } else {
            c->spare_frames = 0;
            target = locked;
        }
        if (c->last_target_ns && target < c->last_target_ns + period_ns) target = c->last_target_ns + period_ns;
        c->cadence_ok = 1;
        c->last_target_ns = target;
    }
    if (target && target - arrival_ns > PRESENT_CADENCE_MAX_AHEAD_NS) {
        leave_pacing(c);
        c->last_target_ns = 0;
        target = 0;
    }
    c->planned_ref_ns = ref;
    c->target_ns = target;
    c->lead_ns = lead_ns;
    return target;
}

void present_cadence_note_wait(struct present_cadence *c, int64_t wait_ns) {
    c->waits_ns[c->next_wait] = wait_ns > 0 ? wait_ns : 0;
    c->next_wait = (c->next_wait + 1) % PRESENT_CADENCE_WAITS;
    if (c->n_waits < PRESENT_CADENCE_WAITS) c->n_waits++;
}

int present_cadence_applied(struct present_cadence *c, int64_t applied_ns) {
    if (!c->planned_ref_ns) return 0;
    present_cadence_note_wait(c, applied_ns - c->planned_ref_ns);
    c->planned_ref_ns = 0;
    return c->target_ns && applied_ns + PRESENT_CADENCE_APPLY_NS + c->lead_ns > c->target_ns;
}
