#pragma once
#include "xr_input_v2.h"
#include "xr_touch_input.h"
#include <algorithm>
#include <cmath>
#include <cstdint>

namespace droiddeck::xr {

inline bool inputHeaderValid(const DroidDeckXrInputV2 &packet) {
    return packet.magic == DROIDDECK_XR_INPUT_V2_MAGIC && packet.version == 2 && packet.header_bytes == 64
        && packet.byte_size == sizeof(packet) && packet.session_generation && packet.origin_epoch;
}

inline bool inputFresh(const DroidDeckXrInputV2 &packet, int64_t now) {
    return inputHeaderValid(packet) && packet.sample_monotonic_ns > 0 && now >= packet.sample_monotonic_ns
        && now - packet.sample_monotonic_ns <= 100000000LL;
}

inline bool poseValid(const DroidDeckXrPoseV2 &pose) {
    if ((pose.flags & 3u) != 3u) return false;
    float length = 0;
    for (float value : pose.position) if (!std::isfinite(value)) return false;
    for (float value : pose.orientation) {
        if (!std::isfinite(value)) return false;
        length += value * value;
    }
    return std::abs(length - 1.0f) <= 0.01f;
}

inline bool poseTracked(const DroidDeckXrPoseV2 &pose) { return poseValid(pose) && (pose.flags & 15u) == 15u; }

struct HandOutput {
    bool active = false, triggerDown = false, graspDown = false;
    float trigger = 0, grasp = 0;
};

class ScalarLatch {
public:
    bool update(float value, bool ready) {
        if (!ready || !std::isfinite(value)) { reset(); return false; }
        if (value <= 0.25f) { armed_ = true; down_ = false; }
        else if (armed_ && value > XR_TOUCH_CLICK_THRESHOLD) down_ = true;
        return down_;
    }
    void reset() { armed_ = down_ = false; }
    bool armed() const { return armed_; }
private:
    bool armed_ = false, down_ = false;
};

class SteamVrHandBridge {
public:
    HandOutput update(const DroidDeckXrInputV2 &packet, int hand, int64_t now) {
        if (hand < 0 || hand > 1) return {};
        if (generation_ != packet.session_generation || epoch_ != packet.origin_epoch) {
            reset(); generation_ = packet.session_generation; epoch_ = packet.origin_epoch;
            return {};
        }
        const auto &input = packet.hands[hand];
        const bool valid = inputFresh(packet, now) &&
            (packet.flags & DROIDDECK_XR_INPUT_FOCUSED) && (packet.flags & DROIDDECK_XR_INPUT_HAND_MODE) && poseTracked(input.grip) &&
            (input.status_bits & DROIDDECK_XR_GRIP_ACTIVE) &&
            input.source == DROIDDECK_XR_SOURCE_RUNTIME_ACTION;
        if (!valid) { trigger_.reset(); grasp_.reset(); return {}; }
        const bool select = input.capability_bits & DROIDDECK_XR_SELECT;
        const uint32_t action = select ? DROIDDECK_XR_SELECT : DROIDDECK_XR_PINCH;
        const uint32_t active = select ? DROIDDECK_XR_SELECT_ACTIVE : DROIDDECK_XR_PINCH_ACTIVE;
        const float triggerValue = select ? input.select : input.pinch;
        HandOutput output;
        output.active = true;
        output.triggerDown = trigger_.update(triggerValue,
            (input.ready_bits & action) && (input.capability_bits & action) && (input.status_bits & active));
        output.graspDown = grasp_.update(input.grasp,
            (input.ready_bits & DROIDDECK_XR_GRASP) && (input.capability_bits & DROIDDECK_XR_GRASP)
            && (input.status_bits & DROIDDECK_XR_GRASP_ACTIVE));
        output.trigger = output.triggerDown ? 1.0f : 0.0f;
        output.grasp = grasp_.armed() && (input.ready_bits & DROIDDECK_XR_GRASP) && std::isfinite(input.grasp) ? std::clamp(input.grasp, 0.0f, 1.0f) : 0.0f;
        return output;
    }
    void reset() { trigger_.reset(); grasp_.reset(); }
private:
    ScalarLatch trigger_, grasp_;
    uint64_t generation_ = 0, epoch_ = 0;
};

class HandDashboardChord {
public:
    bool held() const { return fired_; }
    bool update(const DroidDeckXrInputV2 &packet, int64_t now) {
        auto pinch = packet;
        for (auto &hand : pinch.hands) hand.capability_bits &= ~DROIDDECK_XR_SELECT;
        const auto left = hands_[0].update(pinch, 0, now);
        const auto right = hands_[1].update(pinch, 1, now);
        if (!left.active || !right.active || !left.triggerDown || !right.triggerDown || now < started_) {
            started_ = 0; fired_ = false; return false;
        }
        if (!started_) started_ = now;
        if (now - started_ < 1000000000LL || fired_) return false;
        fired_ = true;
        return true;
    }
private:
    SteamVrHandBridge hands_[2];
    int64_t started_ = 0;
    bool fired_ = false;
};

class GamepadDashboardRequests {
public:
    bool update(const DroidDeckXrInputV2 &packet, int64_t now) {
        const bool valid = inputFresh(packet, now) && (packet.flags & DROIDDECK_XR_INPUT_FOCUSED)
            && (packet.flags & DROIDDECK_XR_INPUT_GAMEPAD_MENU);
        const bool changed = generation_ != packet.session_generation || epoch_ != packet.origin_epoch;
        const bool request = valid && armed_ && !changed && sequence_ != packet.gamepad_menu_sequence;
        generation_ = packet.session_generation; epoch_ = packet.origin_epoch;
        sequence_ = packet.gamepad_menu_sequence;
        armed_ = valid;
        return request;
    }
private:
    bool armed_ = false;
    uint64_t generation_ = 0, epoch_ = 0;
    uint32_t sequence_ = 0;
};

struct HandMenuButtons { bool suppress = false, system = false; };
inline HandMenuButtons handMenuButtons(int64_t requested, int64_t now, bool held) {
    const auto elapsed = now - requested;
    return {held || (requested > 0 && elapsed >= 0 && elapsed < 200000000LL),
            requested > 0 && elapsed >= 100000000LL && elapsed < 200000000LL};
}

}
