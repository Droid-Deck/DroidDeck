#pragma once
#include "xr_tracking.h"
#include <cmath>

class XrHeadMotion {
public:
    void Update(DroidDeckXrTracking &packet, int64_t time, uint64_t origin) {
        bool tracked = (packet.head.flags & 15u) == 15u;
        double norm = 0;
        for (float q : packet.head.orientation) { tracked &= std::isfinite(q); norm += q*q; }
        for (float p : packet.head.position) tracked &= std::isfinite(p);
        tracked &= std::abs(norm - 1.) < 0.01;
        if (!tracked || time <= 0) {
            previousTime_ = 0;
            packet.head_velocity_flags = 0;
            Clear(packet.head_linear_velocity); Clear(packet.head_angular_velocity);
            return;
        }
        Sanitize(packet.head_linear_velocity, packet.head_velocity_flags, DROIDDECK_XR_LINEAR_VELOCITY_VALID);
        Sanitize(packet.head_angular_velocity, packet.head_velocity_flags, DROIDDECK_XR_ANGULAR_VELOCITY_VALID);
        const int64_t delta = time - previousTime_;
        if (previousTime_ > 0 && origin == origin_ && delta >= 1000000 && delta <= 100000000) {
            const double seconds = delta * 1e-9;
            if (!(packet.head_velocity_flags & DROIDDECK_XR_LINEAR_VELOCITY_VALID)) {
                for (int axis = 0; axis < 3; axis++)
                    packet.head_linear_velocity[axis] = (packet.head.position[axis] - previous_.position[axis]) / seconds;
                packet.head_velocity_flags |= DROIDDECK_XR_LINEAR_VELOCITY_VALID;
            }
            if (!(packet.head_velocity_flags & DROIDDECK_XR_ANGULAR_VELOCITY_VALID)) {
                const auto &a = packet.head.orientation;
                const auto &b = previous_.orientation;
                // q_now * inverse(q_previous) expresses angular velocity in the shared world space.
                double q[4]{a[0]*b[3]-a[3]*b[0]-a[1]*b[2]+a[2]*b[1],
                            a[1]*b[3]-a[3]*b[1]-a[2]*b[0]+a[0]*b[2],
                            a[2]*b[3]-a[3]*b[2]-a[0]*b[1]+a[1]*b[0],
                            a[3]*b[3]+a[0]*b[0]+a[1]*b[1]+a[2]*b[2]};
                if (q[3] < 0) for (double &value : q) value = -value;
                const double sine = std::sqrt(q[0]*q[0]+q[1]*q[1]+q[2]*q[2]);
                const double scale = sine > 1e-9 ? 2*std::atan2(sine, q[3])/(sine*seconds) : 2/seconds;
                for (int axis = 0; axis < 3; axis++) packet.head_angular_velocity[axis] = q[axis]*scale;
                packet.head_velocity_flags |= DROIDDECK_XR_ANGULAR_VELOCITY_VALID;
            }
        }
        Sanitize(packet.head_linear_velocity, packet.head_velocity_flags, DROIDDECK_XR_LINEAR_VELOCITY_VALID);
        Sanitize(packet.head_angular_velocity, packet.head_velocity_flags, DROIDDECK_XR_ANGULAR_VELOCITY_VALID);
        previous_ = packet.head; previousTime_ = time; origin_ = origin;
    }
private:
    static void Clear(float velocity[3]) { for (int axis = 0; axis < 3; axis++) velocity[axis] = 0; }
    static void Sanitize(float velocity[3], uint32_t &flags, uint32_t bit) {
        bool valid = (flags & bit) != 0;
        for (int axis = 0; axis < 3; axis++) valid &= std::isfinite(velocity[axis]);
        if (!valid) { flags &= ~bit; Clear(velocity); }
    }
    DroidDeckXrPose previous_{};
    int64_t previousTime_ = 0;
    uint64_t origin_ = 0;
};
