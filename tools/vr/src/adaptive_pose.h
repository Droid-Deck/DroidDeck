#pragma once
#include "arm_model.h"
#include <cstdint>

namespace droiddeck {

class AdaptivePositionMotion {
public:
    explicit AdaptivePositionMotion(double minimumCutoff = 1.5, double positionGain = 25.0)
        : minimumCutoff_(minimumCutoff), positionGain_(positionGain) {}
    void Reset() { time_ = 0; velocity_ = {}; }

    void Observe(const Vec3 &position, int64_t time) {
        if (time == time_) return;
        const double dt = double(time - time_) * 1e-9;
        if (time_ > 0 && dt > 0 && dt <= .2)
            velocity_ = velocity_ + ((position - position_) * (1.0 / dt) - velocity_) * Alpha(dt, 1.0);
        else velocity_ = {};
        position_ = position; time_ = time;
    }

    double PositionAlpha(double dt) const {
        return Alpha(dt, std::clamp(minimumCutoff_ + positionGain_ * Length(velocity_), minimumCutoff_, 25.0));
    }

private:
    const double minimumCutoff_, positionGain_;
    static double Alpha(double dt, double cutoff) { return -std::expm1(-2 * M_PI * cutoff * std::clamp(dt, 0.0, .2)); }
    int64_t time_ = 0;
    Vec3 position_, velocity_;
};

inline Quat ShoulderAim(const Vec3 &head, const Quat &headRotation, const Vec3 &palm, bool right, const Quat &fallback) {
    const Vec3 headRight = Rotate(headRotation, {1, 0, 0});
    double yaw = 0;
    if (!Heading(headRotation, &yaw)) yaw = std::atan2(-headRight.z, headRight.x);
    const Quat torso = AboutY(yaw);
    const Vec3 shoulder = head + Rotate(torso, {right ? .18 : -.18, -.28, 0});
    const Vec3 reach = palm - shoulder;
    const double distance = Length(reach);
    if (!std::isfinite(distance) || distance < .05) return fallback;
    const Vec3 forward = Rotate(torso, {0, 0, -1});
    return Normalize(FromTo(forward, reach * (1.0 / distance)) * torso);
}

}
