#pragma once
#include "../../tools/vr/src/xr_tracking.h"
#include <array>
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstring>
#include <optional>

namespace droiddeck::xr {
enum class Provider { None, OpenXR, Jetpack, Fixture };
enum class Source { None, Controller, HandInteraction, JetpackJoints, JetpackInteraction, Fixture };
enum class Clock { Unknown, Monotonic, BootTime, OpenXR };
enum Capability : uint32_t { Head = 1, Hands = 2, Pinch = 4, Grasp = 8, Aim = 16, Joints = 32 };
struct Time { int64_t ns = 0; Clock clock = Clock::Unknown; };
struct Pose {
    std::array<float, 3> meters{};
    std::array<float, 4> xyzw{0, 0, 0, 1};
    uint32_t flags = 0;
};
struct Input {
    Pose grip, aim;
    Source source = Source::None;
    bool available = false;
    std::optional<float> pinch, grasp;
    std::array<float, 2> stick{};
    uint32_t buttons = 0;
};
struct Sample {
    Provider provider = Provider::None;
    uint64_t session = 0, origin = 0;
    Time sampled, predicted;
    int64_t periodNs = 0;
    bool connected = false, focused = false, inputAllowed = true;
    uint32_t capabilities = 0;
    Pose head;
    std::array<Input, 2> controllers, hands;
    std::array<DroidDeckXrEye, 2> eyes{};
};
struct Edge { bool down = false, pressed = false, released = false; };
struct Hand {
    Pose grip, aim;
    Source source = Source::None;
    bool active = false;
    Edge pinch, grasp;
};
struct Output {
    DroidDeckXrTracking packet{};
    Pose head;
    std::array<Hand, 2> hands;
    int64_t ageNs = -1;
    uint64_t session = 0, origin = 0;
    uint32_t capabilities = 0;
    Provider provider = Provider::None;
    const char *reason = "unavailable";
};

inline Pose validate(Pose pose) {
    pose.flags &= 15u;
    if (pose.flags & 1u) {
        double norm = 0;
        for (float v : pose.xyzw) norm += double(v) * v;
        if (!std::isfinite(norm) || std::abs(norm - 1.0) > .1) pose.flags &= ~5u;
        else for (float &v : pose.xyzw) v /= float(std::sqrt(norm));
    }
    if (!(pose.flags & 1u)) { pose.xyzw = {0, 0, 0, 1}; pose.flags &= ~4u; }
    for (float v : pose.meters) if (!std::isfinite(v)) pose.flags &= ~10u;
    if (!(pose.flags & 2u)) { pose.meters = {}; pose.flags &= ~8u; }
    return pose;
}
inline bool tracked(const Pose &pose) { return pose.flags == 15u; }
inline DroidDeckXrPose packetPose(const Pose &pose) {
    const Pose valid = validate(pose);
    return {{valid.xyzw[0], valid.xyzw[1], valid.xyzw[2], valid.xyzw[3]},
            {valid.meters[0], valid.meters[1], valid.meters[2]}, valid.flags};
}
inline Pose fromPacket(const DroidDeckXrPose &p) {
    return {{p.position[0], p.position[1], p.position[2]},
            {p.orientation[0], p.orientation[1], p.orientation[2], p.orientation[3]}, p.flags};
}
inline std::array<double, 4> openVrWxyz(const Pose &p) {
    const auto q = validate(p).xyzw;
    return {q[3], q[0], q[1], q[2]};
}
inline std::optional<int64_t> age(Time sample, Time now) {
    if (sample.clock == Clock::Unknown || sample.clock != now.clock || sample.ns <= 0 || now.ns < sample.ns)
        return {};
    return now.ns - sample.ns;
}
inline int64_t prediction(Time predicted, Time now) {
    if (predicted.clock != Clock::Monotonic || now.clock != Clock::Monotonic || predicted.ns <= 0)
        return 0;
    const long double delta = static_cast<long double>(predicted.ns) - now.ns;
    return delta >= -250000000 && delta <= 100000000 ? predicted.ns : 0;
}

class Core {
public:
    void begin(Provider provider, uint64_t session, uint64_t origin) {
        provider_ = provider; session_ = session; origin_ = origin;
        for (auto &state : states_) state.resetPending = true;
    }
    Output update(const Sample &sample, Time now) {
        Output out;
        out.provider = provider_; out.session = session_; out.origin = origin_;
        out.capabilities = sample.capabilities;
        auto &packet = out.packet;
        packet.magic = DROIDDECK_XR_MAGIC; packet.version = DROIDDECK_XR_VERSION;
        packet.sampled_ns = sample.sampled.clock == Clock::Monotonic ? sample.sampled.ns : 0;
        packet.period_ns = sample.periodNs;
        auto sampleAge = age(sample.sampled, now);
        bool valid = sampleAge && *sampleAge <= 250000000;
        if (sampleAge) out.ageNs = *sampleAge;
        out.reason = valid ? "ok" : "stale, future, or incompatible clock";
        if (sample.provider != provider_ || provider_ == Provider::None || sample.session != session_ || sample.origin != origin_) {
            valid = false; out.reason = "provider/session/origin mismatch";
        }
        if (!sample.connected) { valid = false; out.reason = "disconnected"; }
        packet.active = valid;
        if (valid) {
            out.head = validate(sample.head);
            packet.head = packetPose(out.head);
            packet.predicted_ns = prediction(sample.predicted, now);
            for (int side = 0; side < 2; ++side) {
                packet.eyes[side] = sample.eyes[side];
                packet.eyes[side].pose = packetPose(fromPacket(sample.eyes[side].pose));
            }
        }
        for (int side = 0; side < 2; ++side) {
            auto &state = states_[side];
            auto &hand = out.hands[side];
            const auto &controller = sample.controllers[side];
            const auto &optical = sample.hands[side];
            // An available controller owns its side even during pose loss.
            const Input &input = controller.available ? controller : optical;
            const bool usable = valid && sample.focused && sample.inputAllowed && input.available;
            if (usable) {
                hand.grip = validate(input.grip); hand.aim = validate(input.aim); hand.source = input.source;
                hand.active = tracked(hand.grip);
            }
            const bool eventOnly = input.source == Source::JetpackInteraction;
            const bool allowed = usable && (hand.active || eventOnly);
            const bool transition = state.source != hand.source || state.resetPending;
            if (transition || !allowed) { state.pinchArmed = state.graspArmed = false; }
            hand.pinch = gesture(input.pinch, allowed && !transition, state.pinchArmed, state.pinch);
            hand.grasp = gesture(input.grasp, allowed && !transition, state.graspArmed, state.grasp);
            state.source = hand.source; state.resetPending = false;
            auto &wire = packet.hands[side];
            wire.pose = packetPose(hand.grip);
            wire.active = hand.active && allowed;
            if (wire.active) {
                if (input.source == Source::Controller) {
                    wire.trigger = state.pinchArmed ? scalar(input.pinch) : 0.f;
                    wire.squeeze = state.graspArmed ? scalar(input.grasp) : 0.f;
                    for (int axis = 0; axis < 2; ++axis)
                        wire.stick[axis] = std::isfinite(input.stick[axis]) ? std::clamp(input.stick[axis], -1.f, 1.f) : 0.f;
                    wire.buttons = input.buttons & 511u;
                    if (transition) { wire.trigger = wire.squeeze = 0; wire.buttons = 0; wire.stick[0] = wire.stick[1] = 0; }
                } else {
                    wire.trigger = hand.pinch.down ? 1.f : 0.f;
                    wire.squeeze = hand.grasp.down ? 1.f : 0.f;
                    wire.buttons = DROIDDECK_XR_HAND_SOURCE;
                }
            }
        }
        return out;
    }
private:
    struct State {
        Source source = Source::None;
        bool pinch = false, grasp = false, pinchArmed = false, graspArmed = false, resetPending = true;
    };
    std::array<State, 2> states_;
    Provider provider_ = Provider::None;
    uint64_t session_ = 0, origin_ = 0;
    static float scalar(std::optional<float> v) {
        return v && std::isfinite(*v) ? std::clamp(*v, 0.f, 1.f) : 0.f;
    }
    static Edge gesture(std::optional<float> value, bool allowed, bool &armed, bool &previous) {
        const bool present = value && std::isfinite(*value) && *value >= 0 && *value <= 1;
        bool down = false;
        if (!allowed || !present) armed = false;
        else {
            if (*value <= .45f) armed = true;
            if (armed) down = previous ? *value > .45f : *value >= .7f;
        }
        Edge result{down, down && !previous, !down && previous};
        previous = down;
        return result;
    }
};

inline void publish(DroidDeckXrTracking *mapping, const DroidDeckXrTracking &packet) {
    const uint32_t seq = __atomic_load_n(&mapping->seq, __ATOMIC_RELAXED);
    __atomic_store_n(&mapping->seq, seq | 1u, __ATOMIC_SEQ_CST);
    __atomic_thread_fence(__ATOMIC_SEQ_CST);
    std::memcpy(mapping, &packet, offsetof(DroidDeckXrTracking, seq));
    std::memcpy(reinterpret_cast<char *>(mapping) + offsetof(DroidDeckXrTracking, active),
                reinterpret_cast<const char *>(&packet) + offsetof(DroidDeckXrTracking, active),
                sizeof(packet) - offsetof(DroidDeckXrTracking, active));
    __atomic_store_n(&mapping->seq, (seq | 1u) + 1u, __ATOMIC_RELEASE);
}
}
