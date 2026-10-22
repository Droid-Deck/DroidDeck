#pragma once
#include "arm_model.h"
#include "spatial_tracking.h"

namespace droiddeck {

struct LumaJoyConPose {
    Vec3 position;
    bool valid = false, camera = false;
};

class LumaJoyConPosition {
public:
    explicit LumaJoyConPosition(bool right) : side_(right ? 1 : 0), arm_(right) {}

    void Reset() { arm_.Reset(); motion_.Reset(); shown_ = false; blend_ = {}; lastCamera_ = {}; }

    LumaJoyConPose Update(const droiddeck_spatialpose &spatial, const Quat &rotation,
                         double angularSpeed, int64_t now, double dt) {
        if (!(spatial.flags & DD_SPATIAL_ACTIVE) || !(spatial.flags & DD_SPATIAL_JOYCONS) ||
            !SpatialFresh(spatial.head, now)) {
            Reset();
            return {};
        }
        if (connection_ != spatial.connection || origin_ != spatial.origin) {
            Reset();
            connection_ = spatial.connection; origin_ = spatial.origin;
        }
        dt = std::clamp(dt, 0.0, 0.1);
        const auto &h = spatial.head;
        const Vec3 head{h.position[0], h.position[1], h.position[2]};
        const Quat headRotation{h.rotation[3], h.rotation[0], h.rotation[1], h.rotation[2]};
        const Vec3 estimate = arm_.Update(head, headRotation, rotation, angularSpeed, dt);
        const auto &observation = spatial.hands[side_];
        const auto &p = observation.pose.position;
        const Vec3 measured{p[0], p[1], p[2]};
        if (SpatialHandFresh(observation, now) && Length(measured - head) <= 1.5 &&
            observation.pose.time_ns > lastCamera_.time_ns) {
            motion_.Observe(measured, observation.pose.time_ns);
            lastCamera_ = observation.pose;
        }
        const bool camera = SpatialFresh(lastCamera_, now, 200000000LL);
        if (camera) {
            const Vec3 source{lastCamera_.position[0], lastCamera_.position[1], lastCamera_.position[2]};
            position_ = shown_ ? position_ + (source - position_) * motion_.PositionAlpha(dt) : source;
            blend_ = {};
        } else {
            motion_.Reset();
            if (shown_ && camera_) blend_ = position_ - estimate;
            blend_ = blend_ * std::exp(-dt / .18);
            position_ = estimate + blend_;
        }
        shown_ = true; camera_ = camera;
        return {position_, true, camera};
    }

private:
    const int side_;
    ArmModel arm_;
    AdaptivePositionMotion motion_;
    uint64_t connection_ = 0, origin_ = 0;
    bool shown_ = false, camera_ = false;
    Vec3 position_, blend_;
    dd_spatial_pose lastCamera_{};
};

}
