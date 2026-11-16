#ifndef PRESENT_CADENCE_H
#define PRESENT_CADENCE_H

#include <stdint.h>

#define PRESENT_CADENCE_WAITS 128
#define PRESENT_CADENCE_SPARE_FRAMES 128
#define PRESENT_CADENCE_MAX_GAP_VSYNCS 8
#define PRESENT_CADENCE_MARGIN_NS 1000000LL
#define PRESENT_CADENCE_MAX_AHEAD_NS 100000000LL
#define PRESENT_CADENCE_TIMELINE_STALE_NS 100000000LL

struct present_cadence {
    int64_t last_arrival_ns;
    int64_t last_target_ns;
    int64_t waits_ns[PRESENT_CADENCE_WAITS];
    unsigned n_waits, next_wait;
    unsigned spare_frames;
    unsigned interval;
    int64_t target_ns;
    int64_t lead_ns;
};

struct present_cadence_stats {
    unsigned paced, late, unpaced;
    unsigned interval;
    int64_t latency_ns, latency_max_ns;
};

/* Picks the vsync a frame that reached the compositor at arrival_ns is shown at, or 0 to show it
 * as soon as it is ready. grid_ns is any vsync's present time, lead_ns how long before a vsync a
 * transaction must be applied to be shown at it. */
int64_t present_cadence_plan(struct present_cadence *c, int64_t arrival_ns, int64_t grid_ns,
                             int64_t period_ns, int64_t lead_ns);
/* Records when the planned frame was applied; returns 1 when that was too late for its vsync. */
int present_cadence_applied(struct present_cadence *c, int64_t arrival_ns, int64_t applied_ns);
void present_cadence_reset(struct present_cadence *c);
int64_t present_cadence_vsync_at_or_after(int64_t t_ns, int64_t grid_ns, int64_t period_ns);

void present_cadence_set_timeline(int64_t expected_present_ns, int64_t deadline_ns, int64_t period_ns,
                                  int64_t now_ns);
int present_cadence_timeline(int64_t now_ns, int64_t *grid_ns, int64_t *lead_ns, int64_t *period_ns);

#endif
