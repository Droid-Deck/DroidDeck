#pragma once
#include <stdint.h>

static inline int64_t xr_display_last_tick(int64_t now, int64_t predicted, int64_t period) {
    if (now <= 0 || predicted <= 0 || period < 1000000 || period > 50000000) return 0;
    int64_t remainder = (now - predicted) % period;
    if (remainder < 0) remainder += period;
    return now - remainder;
}
