// Adapted from Valve's OpenVR handskeletonsimulation driver sample (BSD-3-Clause, see OPENVR_LICENSE),
// which follows Moshi Turner's hand simulation in Monado.
#pragma once

#include "arm_model.h"
#include "openvr_driver.h"

namespace droiddeck {

constexpr uint32_t kHandBones = 31;

struct FingerCurls {
    double thumb = 0.0, index = 0.0, middle = 0.0, ring = 0.0, pinky = 0.0;
};

namespace hand {

constexpr double kDegree = M_PI / 180.0;

constexpr double kLengths[5][5] = {
    {0.05, 0.05, 0.035, 0.025, 0.0},
    {0.03, 0.073, 0.045, 0.025, 0.02},
    {0.01, 0.091, 0.049, 0.03, 0.02},
    {0.02, 0.073, 0.045, 0.03, 0.03},
    {0.03, 0.067, 0.03, 0.025, 0.02},
};

struct Joint {
    double swing[2] = {0.0, 0.0};
    double twist = 0.0;
};

struct Finger {
    Joint metacarpal, proximal;
    double intermediate = 0.0, distal = 0.0;
};

inline Quat SwingTwist(const Joint &j) {
    const double squared = j.swing[0] * j.swing[0] + j.swing[1] * j.swing[1];
    const double cosTwist = std::cos(j.twist * 0.5), sinTwist = std::sin(j.twist * 0.5);
    double cosSwing = 1.0, sinOver = 0.5;
    if (squared > 0.0) {
        const double theta = std::sqrt(squared);
        cosSwing = std::cos(theta * 0.5);
        sinOver = std::sin(theta * 0.5) / theta;
    }
    return {cosSwing * cosTwist, cosSwing * sinTwist,
            j.swing[1] * cosTwist * sinOver - j.swing[0] * sinTwist * sinOver,
            j.swing[0] * cosTwist * sinOver + j.swing[1] * sinTwist * sinOver};
}

inline Quat AboutZ(double radians) { return {std::cos(radians * 0.5), 0.0, 0.0, std::sin(radians * 0.5)}; }

inline vr::VRBoneTransform_t Bone(bool right, const Quat &q, Vec3 position) {
    if (right) position.x = -position.x;
    vr::VRBoneTransform_t t;
    t.position = {{(float)position.x, (float)position.y, (float)position.z, 1.0f}};
    t.orientation = {(float)q.w, (float)q.x, (float)q.y, (float)q.z};
    return t;
}

inline vr::VRBoneTransform_t Metacarpal(bool right, const Quat &orientation, double length) {
    Quat q = Quat{0.5, 0.5, -0.5, 0.5} * orientation;
    const Vec3 position = Rotate(q, {length, 0.0, 0.0});
    if (right) q = {q.x, -q.w, q.z, -q.y};
    return Bone(right, q, position);
}

inline Quat ToQuat(const vr::HmdQuaternionf_t &q) { return {q.w, q.x, q.y, q.z}; }
inline Vec3 ToVec(const vr::HmdVector4_t &v) { return {v.v[0], v.v[1], v.v[2]}; }

}

inline void HandSkeleton(bool right, const FingerCurls &curls, vr::VRBoneTransform_t out[kHandBones]) {
    using namespace hand;
    out[0] = {{{0.0f, 0.0f, 0.0f, 1.0f}}, {1.0f, 0.0f, 0.0f, 0.0f}};
    out[1] = {{{-0.034038f, 0.036503f, 0.164722f, 1.0f}}, {-0.055147f, -0.078608f, -0.920279f, 0.379296f}};
    if (right) {
        out[1].position.v[0] = -out[1].position.v[0];
        out[1].orientation.y = -out[1].orientation.y;
        out[1].orientation.z = -out[1].orientation.z;
    }

    Joint thumbMetacarpal, thumbProximal;
    thumbMetacarpal.swing[0] = 10 * kDegree + curls.thumb * 5 * kDegree;
    thumbMetacarpal.swing[1] = 40 * kDegree;
    thumbProximal.swing[0] = curls.thumb * 90 * kDegree;
    const double thumbDistal = curls.thumb * 90 * kDegree;
    out[2] = Metacarpal(right, SwingTwist(thumbMetacarpal), kLengths[0][0]);
    out[3] = Bone(right, SwingTwist(thumbProximal), {kLengths[0][1], 0.0, 0.0});
    out[4] = Bone(right, AboutZ(thumbDistal), {kLengths[0][2], 0.0, 0.0});
    out[5] = Bone(right, Quat{}, {kLengths[0][3], 0.0, 0.0});

    const double metacarpalSplay[4] = {13.0, 0.0, -15.0, -27.0};
    const double proximalSplay[4] = {3.0, 0.0, -1.0, -2.0};
    const double fingerCurls[4] = {curls.index, curls.middle, curls.ring, curls.pinky};
    for (int f = 0; f < 4; f++) {
        Finger finger;
        finger.metacarpal.swing[0] = fingerCurls[f] * 5 * kDegree;
        finger.metacarpal.swing[1] = metacarpalSplay[f] * kDegree;
        finger.proximal.swing[0] = fingerCurls[f] * 90 * kDegree;
        finger.proximal.swing[1] = proximalSplay[f] * kDegree;
        finger.intermediate = 5 * kDegree + fingerCurls[f] * 80 * kDegree;
        finger.distal = 5 * kDegree + fingerCurls[f] * 80 * kDegree;
        const int base = 6 + f * 5;
        const double *length = kLengths[f + 1];
        out[base] = Metacarpal(right, SwingTwist(finger.metacarpal), length[0]);
        out[base + 1] = Bone(right, SwingTwist(finger.proximal), {length[1], 0.0, 0.0});
        out[base + 2] = Bone(right, AboutZ(finger.intermediate), {length[2], 0.0, 0.0});
        out[base + 3] = Bone(right, AboutZ(finger.distal), {length[3], 0.0, 0.0});
        out[base + 4] = Bone(right, Quat{}, {length[4], 0.0, 0.0});
    }

    const int distal[5] = {4, 9, 14, 19, 24};
    for (int f = 0; f < 5; f++) {
        const int first = f == 0 ? 2 : 6 + (f - 1) * 5;
        Quat rotation = ToQuat(out[1].orientation);
        Vec3 position = ToVec(out[1].position);
        for (int b = first; b <= distal[f]; b++) {
            position = position + Rotate(rotation, ToVec(out[b].position));
            rotation = rotation * ToQuat(out[b].orientation);
        }
        out[26 + f] = {{{(float)position.x, (float)position.y, (float)position.z, 1.0f}},
                       {(float)rotation.w, (float)rotation.x, (float)rotation.y, (float)rotation.z}};
    }
}

}
