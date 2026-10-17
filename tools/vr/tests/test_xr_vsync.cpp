#include "../src/xr_vsync_timing.h"
#include <atomic>
#include <cassert>
#include <cstdio>
#include <initializer_list>
#include <limits>
#include <thread>

int main() {
    using namespace std::chrono;
    using Clock = XrVsyncTiming::Clock;
    XrVsyncTiming timing;
    assert(timing.Deadline(0) == Clock::time_point(XrVsyncTiming::kDefaultPeriod));
    auto target = Clock::time_point(seconds(1));
    for (int64_t periodNs : {13888889LL, 11111111LL, 8333333LL}) {
        auto period = nanoseconds(periodNs);
        timing.Record(target, period);
        assert(timing.Deadline(0) == target + period);
        assert(timing.Deadline(2) == target + period * 3);
        assert(timing.Deadline(std::numeric_limits<uint32_t>::max()) == target + period * 4294967296LL);
        auto now = target - milliseconds(2);
        auto scheduled = target + period;
        assert(timing.Target(now, scheduled, 1000000000LL, period) == scheduled);
        assert(timing.Target(now, scheduled, 0, period) == scheduled);
        assert(timing.Target(now, scheduled, 900000000LL, period) == scheduled);
        assert(timing.Target(now, scheduled, 2000000000LL, period) == scheduled);
        auto prediction = target + period * 2;
        auto predictedNs = duration_cast<nanoseconds>(prediction.time_since_epoch()).count();
        assert(timing.Target(now, scheduled, predictedNs, period) == prediction);
    }
    auto first = Clock::time_point(seconds(1));
    auto second = Clock::time_point(seconds(2));
    auto firstPeriod = nanoseconds(13888889);
    auto secondPeriod = nanoseconds(8333333);
    timing.Record(first, firstPeriod);
    std::atomic<bool> finished{false};
    std::thread writer([&] {
        for (int count = 0; count < 100000; count++) {
            timing.Record(second, secondPeriod);
            timing.Record(first, firstPeriod);
        }
        finished = true;
    });
    do {
        auto deadline = timing.Deadline(0);
        assert(deadline == first + firstPeriod || deadline == second + secondPeriod);
    } while (!finished);
    writer.join();
    timing.Reset();
    assert(timing.Deadline(0) == Clock::time_point(XrVsyncTiming::kDefaultPeriod));
    std::puts("XR pacing validation passed: refresh rates, throttling, repeated predictions, atomic tick/period, restart");
}
