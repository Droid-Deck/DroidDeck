// Arm model ported from Google's GvrArmModel.cs (Google VR SDK for Unity, Apache-2.0), see docs/third-party/joycon.
#pragma once

#include <algorithm>
#include <cmath>

namespace droiddeck {

struct Vec3 {
    double x = 0.0, y = 0.0, z = 0.0;
};

struct Quat {
    double w = 1.0, x = 0.0, y = 0.0, z = 0.0;
};

inline Vec3 operator+(const Vec3 &a, const Vec3 &b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
inline Vec3 operator-(const Vec3 &a, const Vec3 &b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
inline Vec3 operator*(const Vec3 &a, double s) { return {a.x * s, a.y * s, a.z * s}; }
inline double Dot(const Vec3 &a, const Vec3 &b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
inline Vec3 Cross(const Vec3 &a, const Vec3 &b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
inline double Length(const Vec3 &a) { return std::sqrt(Dot(a, a)); }

inline Quat operator*(const Quat &a, const Quat &b) {
    return {a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
            a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
            a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
            a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w};
}

inline Quat Conjugate(const Quat &q) { return {q.w, -q.x, -q.y, -q.z}; }

inline Quat Normalize(const Quat &q) {
    const double n = std::sqrt(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z);
    if (!(n > 1e-9)) return {};
    return {q.w / n, q.x / n, q.y / n, q.z / n};
}

inline Vec3 Rotate(const Quat &q, const Vec3 &v) {
    const Vec3 u{q.x, q.y, q.z};
    const Vec3 t = Cross(u, v) * 2.0;
    return v + t * q.w + Cross(u, t);
}

inline Quat AboutX(double radians) { return {std::cos(radians / 2), std::sin(radians / 2), 0.0, 0.0}; }
inline Quat AboutY(double radians) { return {std::cos(radians / 2), 0.0, std::sin(radians / 2), 0.0}; }

inline Quat FromTo(const Vec3 &from, const Vec3 &to) {
    const double d = Dot(from, to);
    if (d < -0.999999) {
        Vec3 axis = Cross({1.0, 0.0, 0.0}, from);
        if (Length(axis) < 1e-6) axis = Cross({0.0, 1.0, 0.0}, from);
        axis = axis * (1.0 / Length(axis));
        return {0.0, axis.x, axis.y, axis.z};
    }
    const Vec3 c = Cross(from, to);
    return Normalize({1.0 + d, c.x, c.y, c.z});
}

inline Quat Nlerp(const Quat &a, const Quat &b, double t) {
    const double sign = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z < 0.0 ? -1.0 : 1.0;
    return Normalize({a.w + (sign * b.w - a.w) * t, a.x + (sign * b.x - a.x) * t,
                      a.y + (sign * b.y - a.y) * t, a.z + (sign * b.z - a.z) * t});
}

inline double AngleDegrees(const Quat &q) { return 2.0 * std::acos(std::min(std::fabs(q.w), 1.0)) * 180.0 / M_PI; }

inline bool Heading(const Quat &q, double *radians) {
    const Vec3 forward = Rotate(q, {0.0, 0.0, -1.0});
    if (std::hypot(forward.x, forward.z) < 0.25) return false;
    *radians = std::atan2(-forward.x, -forward.z);
    return true;
}

class ArmModel {
public:
    explicit ArmModel(bool right) : side_(right ? 1.0 : -1.0) {}

    void Reset() { torsoSet_ = false; }

    Vec3 Update(const Vec3 &head, const Quat &headRotation, const Quat &controller, double angularSpeed, double dt) {
        Vec3 gaze = Rotate(headRotation, {0.0, 0.0, -1.0});
        gaze.y = 0.0;
        const double length = Length(gaze);
        if (length > 1e-3) {
            gaze = gaze * (1.0 / length);
            if (!torsoSet_) {
                torso_ = gaze;
                torsoSet_ = true;
            } else {
                const double strength = std::clamp((angularSpeed - 0.2) / 45.0, 0.0, 0.1);
                const double follow = 1.0 - std::pow(1.0 - strength, std::max(dt, 0.0) * 60.0);
                const double angle = std::atan2(Cross(torso_, gaze).y, Dot(torso_, gaze));
                torso_ = Rotate(AboutY(angle * follow), torso_);
            }
        }
        const Quat torso = AboutY(std::atan2(-torso_.x, -torso_.z));
        const Vec3 forward = Rotate(Conjugate(torso) * controller, {0.0, 0.0, -1.0});
        const double elevation = std::asin(std::clamp(forward.y, -1.0, 1.0)) * 180.0 / M_PI;
        const Quat swing = FromTo({0.0, 0.0, -1.0}, forward);
        const double extension = std::clamp((elevation - kMinExtension) / (kMaxExtension - kMinExtension), 0.0, 1.0);
        const Vec3 elbow = Vec3{0.195 * side_, -0.5, -0.005} + Vec3{-0.13 * side_, 0.14, -0.08} * extension;
        const double suppression = 1.0 - std::pow(AngleDegrees(swing) / 180.0, 6.0);
        const double share = ((1.0 - kElbowBend) + kElbowBend * extension * kExtensionWeight) * suppression;
        const Quat lerp = Nlerp({}, swing, share);
        const Quat elbowRotation = torso * Conjugate(lerp) * swing;
        const Quat wristRotation = elbowRotation * lerp;
        const Vec3 elbowAt = head + Rotate(torso, elbow);
        const Vec3 wristAt = elbowAt + Rotate(elbowRotation, {0.0, 0.0, -0.25});
        return wristAt + Rotate(wristRotation, {0.0, 0.0, -0.05});
    }

private:
    static constexpr double kMinExtension = 7.0, kMaxExtension = 60.0, kElbowBend = 0.6, kExtensionWeight = 0.4;
    double side_;
    bool torsoSet_ = false;
    Vec3 torso_{0.0, 0.0, -1.0};
};

}
