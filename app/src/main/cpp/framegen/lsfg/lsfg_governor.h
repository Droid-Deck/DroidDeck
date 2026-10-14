#pragma once

#include <chrono>
#include <cstdint>

namespace lsfg {

class ProbeGovernor {
public:
    void configure(uint32_t maxGenerations);

    uint32_t cap(uint32_t requested, float sourceRate, float loopRate);

    void reset();

    uint32_t accepted() const { return accepted_; }
    bool     probing()  const { return phase_ == Phase::Probing; }
    bool     wantsHistory() const {
        return !thermalBlocked_ && phase_ != Phase::Backoff;
    }
    int      thermalStatus() const { return thermal_; }

private:
    using Clock = std::chrono::steady_clock;

    enum class Phase { Baseline, Probing, Backoff };

    void enterBaseline(Clock::time_point now);
    void enterBackoff(Clock::time_point now);
    void pollThermal(Clock::time_point now);

    uint32_t maxGenerations_ = 0;
    uint32_t accepted_       = 0;
    Phase    phase_          = Phase::Baseline;

    Clock::time_point phaseStart_{};
    bool  havePhaseStart_ = false;

    float  sourceAccum_ = 0.0f, loopAccum_ = 0.0f;
    uint32_t samples_   = 0;

    float baselineSource_ = 0.0f, baselineLoop_ = 0.0f;
    bool  haveBaseline_   = false;

    uint32_t backoffStep_ = 0;

    int  thermal_ = -1;
    Clock::time_point lastThermalDecay_{};
    bool haveThermalDecay_ = false;
    bool thermalBlocked_ = false;
    uint32_t refusalLog_ = 0;
    Clock::time_point lastThermalPoll_{};
    bool haveThermalPoll_ = false;
};

}
