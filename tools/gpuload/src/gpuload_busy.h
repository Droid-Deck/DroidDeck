// Busy-time bookkeeping shared by VK_LAYER_DROIDDECK_gpu_load and DroidDeck's compositor, kept free of Vulkan so it
// can be tested on its own.
#ifndef DROIDDECK_GPULOAD_BUSY_H
#define DROIDDECK_GPULOAD_BUSY_H

#include <stdint.h>

// GPU ticks between the timestamp written before a batch and the one written after it. Batches on one queue run one
// after the other, so these add up without overlap. Ticks are never compared across batches: on Adreno the always-on
// counter behind the timestamps restarts when the GPU powers down between batches (seen on the Retroid Pocket Duo).
// Anything backwards or longer than max_ticks is a bogus pair and counts as nothing.
static inline uint64_t gpuload_interval(uint64_t start, uint64_t end, uint64_t max_ticks) {
    return end > start && end - start <= max_ticks ? end - start : 0;
}

// A timestamp query slot is reused once its end is available; until the GPU runs the slot's reset again the
// query still shows the previous end, so a result equal to the last one harvested is the old one.
static inline int gpuload_result_is_new(uint64_t start_avail, uint64_t end_avail, uint64_t end, uint64_t last_end) {
    return start_avail && end_avail && end != last_end;
}

#endif
