#pragma once
#include "xr_skeleton.h"
#include "hand_pose.h"

namespace droiddeck::xr {
inline bool handModelPose(const DroidDeckXrPoseV2 joints[26], const HandModel &model, DroidDeckXrPoseV2 &pose) {
    constexpr int map[21] = {1, 2,3,4,5, 7,8,9,10, 12,13,14,15, 17,18,19,20, 22,23,24,25};
    Vec3 observed[21];
    for (int i=0; i<21; ++i) {
        const auto &p = joints[map[i]];
        if (!poseValid(p)) return false;
        observed[i] = {p.position[0], p.position[1], p.position[2]};
    }
    Quat q; Vec3 localCenter; double error = 0;
    if (!model.Fit(observed, model.Curls(observed), &q, &localCenter, &error) || error > 0.05) return false;
    Vec3 center;
    for (int joint : hand::kPalm) center = center + observed[joint] * 0.2;
    const auto p = center - Rotate(q, localCenter);
    pose = {{float(p.x),float(p.y),float(p.z)}, {float(q.x),float(q.y),float(q.z),float(q.w)}, 15};
    return poseValid(pose);
}

inline void steamVrSkeleton(const DroidDeckXrPoseV2 joints[26], const DroidDeckXrPoseV2 &raw, bool right,
                            vr::VRBoneTransform_t bones[31]) {
    const auto rotation = [](const DroidDeckXrPoseV2 &p) {
        return Quat{p.orientation[3], p.orientation[0], p.orientation[1], p.orientation[2]};
    };
    const auto position = [](const DroidDeckXrPoseV2 &p) { return Vec3{p.position[0], p.position[1], p.position[2]}; };
    // OpenXR joints point along -Z; SteamVR finger bones point along handed X.
    constexpr double h = 0.7071067811865476;
    const Quat basis = right ? Quat{h, 0, -h, 0} : Quat{0, -h, 0, h};
    Quat rotations[31]; Vec3 positions[31];
    rotations[0] = rotation(raw); positions[0] = position(raw);
    for (int bone = 1; bone <= 25; ++bone) {
        rotations[bone] = rotation(joints[bone]) * basis;
        positions[bone] = position(joints[bone]);
    }
    constexpr int distal[5] = {4, 9, 14, 19, 24};
    for (int finger = 0; finger < 5; ++finger) {
        rotations[26+finger] = rotations[distal[finger]];
        positions[26+finger] = positions[distal[finger]];
    }
    bones[0] = {{{0, 0, 0, 1}}, {1, 0, 0, 0}};
    for (int bone = 1; bone < 31; ++bone) {
        const bool base = bone == 2 || bone == 6 || bone == 11 || bone == 16 || bone == 21;
        const int parent = bone >= 26 || bone == 1 ? 0 : base ? 1 : bone - 1;
        const auto inverse = Conjugate(rotations[parent]);
        const auto q = Normalize(inverse * rotations[bone]);
        const auto p = Rotate(inverse, positions[bone] - positions[parent]);
        bones[bone] = {{{float(p.x), float(p.y), float(p.z), 1}}, {float(q.w), float(q.x), float(q.y), float(q.z)}};
    }
}
}
