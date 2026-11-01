// Busy-time bookkeeping for VK_LAYER_DROIDDECK_gpu_load, kept free of Vulkan so it can be tested on its own.
#ifndef DROIDDECK_GPULOAD_BUSY_H
#define DROIDDECK_GPULOAD_BUSY_H

#include <stdint.h>

// GPU timestamps of one wrapped submission: start is written before the app's command buffers, end after.
// Intervals from several queues can overlap; covered_until keeps overlapping time from counting twice.
struct gpuload_busy {
    uint64_t covered_until;  // GPU ticks
    uint64_t busy_ticks;
};

// The longest single submission we believe, in ticks; anything longer is a wrapped or bogus timestamp.
static inline int gpuload_add_interval(struct gpuload_busy *b, uint64_t start, uint64_t end, uint64_t max_ticks) {
    if (end <= start || end - start > max_ticks) return 0;
    if (start < b->covered_until) start = b->covered_until;
    if (end <= start) return 0;
    b->busy_ticks += end - start;
    b->covered_until = end;
    return 1;
}

// A timestamp query slot is reused once its end is available; until the GPU runs the slot's reset again the
// query still shows the previous end, so a result equal to the last one harvested is the old one.
static inline int gpuload_result_is_new(uint64_t start_avail, uint64_t end_avail, uint64_t end, uint64_t last_end) {
    return start_avail && end_avail && end != last_end;
}

#endif
