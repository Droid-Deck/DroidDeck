#include <assert.h>
#include <stdio.h>

#include "gpuload_busy.h"

int main(void) {
    struct gpuload_busy b = {0, 0};
    const uint64_t max = 1000;
    assert(gpuload_add_interval(&b, 10, 20, max) && b.busy_ticks == 10);
    // Gap: counted on its own.
    assert(gpuload_add_interval(&b, 30, 35, max) && b.busy_ticks == 15);
    // Overlaps what is already counted: only the new part.
    assert(gpuload_add_interval(&b, 32, 40, max) && b.busy_ticks == 20);
    // Fully inside: nothing new.
    assert(!gpuload_add_interval(&b, 33, 39, max) && b.busy_ticks == 20);
    // Backwards, empty or too long: refused.
    assert(!gpuload_add_interval(&b, 60, 50, max) && b.busy_ticks == 20);
    assert(!gpuload_add_interval(&b, 70, 70, max) && b.busy_ticks == 20);
    assert(!gpuload_add_interval(&b, 100, 100 + max + 1, max) && b.busy_ticks == 20);
    assert(b.covered_until == 40);
    // Query results: both halves available and an end that differs from the last harvested one.
    assert(gpuload_result_is_new(1, 1, 500, 400));
    assert(!gpuload_result_is_new(1, 1, 400, 400));
    assert(!gpuload_result_is_new(1, 0, 500, 400));
    assert(!gpuload_result_is_new(0, 1, 500, 400));
    puts("test_gpuload_busy: ok");
    return 0;
}
