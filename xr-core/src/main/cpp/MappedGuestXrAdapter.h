#pragma once
#include "xr_atomic_file.h"
#include "xr_hand_input.h"

namespace droiddeck::xr {

class MappedGuestXrAdapter {
public:
    bool open(const std::string &path, uint64_t generation, uint64_t epoch) {
        generation_ = generation;
        epoch_ = epoch;
        open_ = generation && epoch && file_.openWriter(path);
        if (open_) releaseInput(generation);
        return open_;
    }
    bool ready(uint64_t generation) const { return open_ && generation == generation_; }
    bool publishInput(const DroidDeckXrInputV2 &sample) {
        return ready(sample.session_generation) && sample.origin_epoch == epoch_ && inputHeaderValid(sample) && file_.write(sample);
    }
    bool resetOrigin(uint64_t epoch) {
        if (!open_ || !epoch || epoch <= epoch_) return false;
        releaseInput(generation_);
        epoch_ = epoch;
        releaseInput(generation_);
        return true;
    }
    void releaseInput(uint64_t generation) noexcept {
        if (!ready(generation)) return;
        auto neutral = droiddeck_xr_input_v2_neutral(generation, epoch_);
        neutral.sample_monotonic_ns = xr_monotonic_ns();
        file_.write(neutral);
    }
private:
    XrAtomicFile<DroidDeckXrInputV2> file_;
    uint64_t generation_ = 0, epoch_ = 0;
    bool open_ = false;
};

}
