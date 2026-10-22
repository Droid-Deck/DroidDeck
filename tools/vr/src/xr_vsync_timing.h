#pragma once
#include <chrono>
#include <cstdint>
#include <mutex>

class XrVsyncTiming {
public:
    using Clock = std::chrono::steady_clock;
    static constexpr std::chrono::nanoseconds kDefaultPeriod{16666667};

    void Reset() { Record({}, kDefaultPeriod); }

    Clock::time_point Target(Clock::time_point now, Clock::time_point scheduled,
                             int64_t predictedNs, std::chrono::nanoseconds period) const {
        std::lock_guard<std::mutex> lock(mutex_);
        auto predicted = Clock::time_point(std::chrono::nanoseconds(predictedNs));
        return predictedNs > 0 && predicted > now && predicted > last_ && predicted < now + period * 3
            ? predicted : scheduled;
    }

    void Record(Clock::time_point target, std::chrono::nanoseconds period) {
        std::lock_guard<std::mutex> lock(mutex_);
        last_ = target;
        period_ = period;
    }

    Clock::time_point Deadline(uint32_t framesToThrottle) const {
        std::lock_guard<std::mutex> lock(mutex_);
        return last_ + period_ * (1 + static_cast<int64_t>(framesToThrottle));
    }

private:
    mutable std::mutex mutex_;
    Clock::time_point last_{};
    std::chrono::nanoseconds period_{kDefaultPeriod};
};
