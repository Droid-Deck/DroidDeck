#pragma once
#include "xr_hand_input.h"

#define DROIDDECK_XR_SKELETON_FILE "droiddeck-xr-skeleton-v1"

struct DroidDeckXrSkeleton {
    uint32_t magic = 0x31535844;
    uint16_t version = 1, header_bytes = 64;
    uint32_t byte_size = sizeof(DroidDeckXrSkeleton), sequence = 0;
    uint64_t session_generation = 0, origin_epoch = 0;
    int64_t sample_monotonic_ns = 0;
    uint32_t active[2]{};
    uint32_t reserved[4]{};
    DroidDeckXrPoseV2 joints[2][26]{};
};
static_assert(offsetof(DroidDeckXrSkeleton, joints) == 64 && sizeof(DroidDeckXrSkeleton) == 1728, "skeleton ABI");

namespace droiddeck::xr {
inline bool skeletonFresh(const DroidDeckXrSkeleton &skeleton, const DroidDeckXrInputV2 &input, int hand, int64_t now) {
    if (hand < 0 || hand > 1 || skeleton.magic != 0x31535844 || skeleton.version != 1 ||
        skeleton.header_bytes != 64 || skeleton.byte_size != sizeof(skeleton) || !skeleton.active[hand] ||
        skeleton.session_generation != input.session_generation || skeleton.origin_epoch != input.origin_epoch ||
        !inputFresh(input, now) || !(input.flags & DROIDDECK_XR_INPUT_HAND_MODE) ||
        !(input.flags & DROIDDECK_XR_INPUT_FOCUSED) || skeleton.sample_monotonic_ns <= 0 ||
        now < skeleton.sample_monotonic_ns || now - skeleton.sample_monotonic_ns > 100000000LL) return false;
    for (const auto &joint : skeleton.joints[hand]) if (!poseValid(joint)) return false;
    return true;
}
}
