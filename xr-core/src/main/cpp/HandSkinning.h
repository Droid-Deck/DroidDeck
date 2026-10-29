#pragma once
#include "xr_skeleton_math.h"
#include "xr_hand_math.h"
#include <array>

namespace droiddeck::xr {
using SkinMatrix = std::array<float, 16>;
static_assert(sizeof(SkinMatrix) == 64);
inline SkinMatrix skinIdentity() { return {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1}; }
inline SkinMatrix skinMultiply(const SkinMatrix &a, const SkinMatrix &b) {
    SkinMatrix result{};
    for (int column = 0; column < 4; ++column) for (int row = 0; row < 4; ++row)
        for (int k = 0; k < 4; ++k) result[column*4+row] += a[k*4+row] * b[column*4+k];
    return result;
}
inline SkinMatrix skinPose(const vr::VRBoneTransform_t &bone) {
    const auto &q = bone.orientation;
    DroidDeckXrPoseV2 pose{{bone.position.v[0],bone.position.v[1],bone.position.v[2]}, {q.x,q.y,q.z,q.w}, 3};
    const auto transform = handTransform(pose);
    auto result = skinIdentity();
    for (int row = 0; row < 3; ++row) for (int column = 0; column < 4; ++column)
        result[column*4+row] = transform.m[row][column];
    return result;
}
inline void handSkinPalette(const DroidDeckXrPoseV2 joints[26], bool right,
                            const SkinMatrix inverseBind[31], SkinMatrix palette[31]) {
    DroidDeckXrPoseV2 origin{{0,0,0}, {0,0,0,1}, 3};
    vr::VRBoneTransform_t bones[31];
    steamVrSkeleton(joints, origin, right, bones);
    SkinMatrix world[31];
    world[0] = skinIdentity();
    for (int bone = 1; bone < 31; ++bone) {
        const bool base = bone == 2 || bone == 6 || bone == 11 || bone == 16 || bone == 21;
        const int parent = bone >= 26 || bone == 1 ? 0 : base ? 1 : bone - 1;
        world[bone] = skinMultiply(world[parent], skinPose(bones[bone]));
    }
    for (int bone = 0; bone < 31; ++bone) palette[bone] = skinMultiply(world[bone], inverseBind[bone]);
}
}
