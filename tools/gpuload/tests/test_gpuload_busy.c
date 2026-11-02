#include <assert.h>
#include <stdio.h>

#include "gpuload_busy.h"

int main(void) {
    const uint64_t max = 1000;
    assert(gpuload_interval(10, 20, max) == 10);
    // Each batch counts on its own, even when the counter restarted in between (later batch has smaller ticks).
    assert(gpuload_interval(900000, 900400, max) == 400);
    assert(gpuload_interval(50, 350, max) == 300);
    // Backwards, empty or too long: nothing.
    assert(gpuload_interval(60, 50, max) == 0);
    assert(gpuload_interval(70, 70, max) == 0);
    assert(gpuload_interval(100, 100 + max + 1, max) == 0);
    assert(gpuload_interval(100, 100 + max, max) == max);
    // Query results: both halves available and an end that differs from the last harvested one.
    assert(gpuload_result_is_new(1, 1, 500, 400));
    assert(!gpuload_result_is_new(1, 1, 400, 400));
    assert(!gpuload_result_is_new(1, 0, 500, 400));
    assert(!gpuload_result_is_new(0, 1, 500, 400));
    puts("test_gpuload_busy: ok");
    return 0;
}
