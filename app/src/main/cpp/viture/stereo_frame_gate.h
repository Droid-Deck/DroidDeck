#pragma once
#include <cmath>
#include <cstdint>

struct StereoFrameGate {
    uint64_t received = 0, skipped = 0;
    void SetRatePermille(int rate) {
        if (rate != 500 && rate != 600 && rate != 800 && rate != 1000) rate = 500;
        if (ratePermille_ != rate) phase_ = 1000-rate;
        ratePermille_ = rate;
    }
    void SetHalfRate(bool enabled) {
        SetRatePermille(enabled ? 500 : 1000);
    }
    bool Accept(double source) {
        if (!std::isfinite(source) || source <= 0) return false;
        ++received;
        if (source <= lastSource_) phase_ = 1000-ratePermille_;
        lastSource_ = source;
        phase_ += ratePermille_;
        const bool copy = phase_ >= 1000;
        if (copy) phase_ -= 1000;
        if (!copy) ++skipped;
        return copy;
    }
private:
    int ratePermille_ = 1000, phase_ = 0;
    double lastSource_ = 0;
};
