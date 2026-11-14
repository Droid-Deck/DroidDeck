#pragma once
#include "arm_model.h"
#include "xr_touch_input.h"
#include <array>
#include <cassert>

namespace droiddeck {

using ProfileMatrix = std::array<float, 16>;
enum class ProfileChannel { None, Trigger, Squeeze, StickClick, StickX, StickY, Primary, Secondary, Menu };
struct ProfileNode {
    int parent;
    ProfileMatrix rest;
    ProfileChannel channel;
    std::array<float, 3> minimumPosition;
    std::array<float, 4> minimumRotation;
    std::array<float, 3> maximumPosition;
    std::array<float, 4> maximumRotation;
};
struct ProfilePart { size_t node; ProfileMatrix inverseRest; };
struct ProfileAsset {
    const ProfileNode *nodes;
    size_t nodeCount;
    const ProfilePart *parts;
    size_t partCount;
    bool aimOrigin;
    std::array<float, 2> stickDirection;
};

inline ProfileMatrix ProfileMultiply(const ProfileMatrix &a, const ProfileMatrix &b) {
    ProfileMatrix result{};
    for (int row = 0; row < 4; ++row)
        for (int column = 0; column < 4; ++column)
            for (int inner = 0; inner < 4; ++inner)
                result[row * 4 + column] += a[row * 4 + inner] * b[inner * 4 + column];
    return result;
}

inline ProfileMatrix ProfilePose(const Quat &q, const Vec3 &p) {
    return {float(1 - 2 * (q.y * q.y + q.z * q.z)), float(2 * (q.x * q.y - q.w * q.z)), float(2 * (q.x * q.z + q.w * q.y)), float(p.x),
            float(2 * (q.x * q.y + q.w * q.z)), float(1 - 2 * (q.x * q.x + q.z * q.z)), float(2 * (q.y * q.z - q.w * q.x)), float(p.y),
            float(2 * (q.x * q.z - q.w * q.y)), float(2 * (q.y * q.z + q.w * q.x)), float(1 - 2 * (q.x * q.x + q.y * q.y)), float(p.z),
            0, 0, 0, 1};
}

inline ProfileMatrix ProfileIdentity() { return ProfilePose({}, {}); }

inline Quat ProfileSlerp(Quat a, Quat b, double t) {
    double dot = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z;
    if (dot < 0) { b = {-b.w, -b.x, -b.y, -b.z}; dot = -dot; }
    if (dot > .9995) return Nlerp(a, b, t);
    const double angle = std::acos(std::clamp(dot, 0., 1.));
    const double u = std::sin((1 - t) * angle) / std::sin(angle), v = std::sin(t * angle) / std::sin(angle);
    return Normalize({u * a.w + v * b.w, u * a.x + v * b.x, u * a.y + v * b.y, u * a.z + v * b.z});
}

class XrProfileAnimation {
public:
    void update(const ProfileAsset &asset, const XrTouchInput &input, const ProfileMatrix &base) {
        assert(asset.nodeCount <= world_.size() && asset.partCount <= parts.size());
        asset_ = &asset;
        input_ = input;
        ready_.fill(false);
        const float length = std::hypot(input_.stickX, input_.stickY);
        if (length > 1) { input_.stickX /= length; input_.stickY /= length; }
        for (size_t part = 0; part < asset.partCount; ++part)
            parts[part] = ProfileMultiply(base, ProfileMultiply(world(asset.parts[part].node), asset.parts[part].inverseRest));
        count = asset.partCount;
    }
    std::array<ProfileMatrix, 16> parts{};
    size_t count = 0;
private:
    float value(ProfileChannel channel) const {
        switch (channel) {
        case ProfileChannel::Trigger: return input_.trigger;
        case ProfileChannel::Squeeze: return input_.grip;
        case ProfileChannel::StickClick: return input_.stickClick;
        case ProfileChannel::StickX: return (input_.stickX * asset_->stickDirection[0] + 1) * .5f;
        case ProfileChannel::StickY: return (input_.stickY * asset_->stickDirection[1] + 1) * .5f;
        case ProfileChannel::Primary: return input_.primaryClick;
        case ProfileChannel::Secondary: return input_.secondaryClick;
        case ProfileChannel::Menu: return input_.systemClick;
        default: return 0;
        }
    }
    const ProfileMatrix &world(size_t index) {
        if (ready_[index]) return world_[index];
        const auto &node = asset_->nodes[index];
        auto local = node.rest;
        if (node.channel != ProfileChannel::None) {
            const auto t = value(node.channel);
            Vec3 position{};
            double *axes[] = {&position.x, &position.y, &position.z};
            for (int axis = 0; axis < 3; ++axis)
                *axes[axis] = node.minimumPosition[axis] * (1 - t) + node.maximumPosition[axis] * t;
            const auto &a = node.minimumRotation, &b = node.maximumRotation;
            local = ProfilePose(ProfileSlerp(Normalize({a[3], a[0], a[1], a[2]}),
                                             Normalize({b[3], b[0], b[1], b[2]}), t), position);
        }
        world_[index] = node.parent < 0 ? local : ProfileMultiply(world(node.parent), local);
        ready_[index] = true;
        return world_[index];
    }
    const ProfileAsset *asset_ = nullptr;
    XrTouchInput input_{};
    std::array<ProfileMatrix, 64> world_{};
    std::array<bool, 64> ready_{};
};
}

#include "xr_profile_animation_data.h"
