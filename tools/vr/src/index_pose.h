// Index controller tip and grip offsets in its raw frame, as Monado's vive_poses.c (BSL-1.0) lists them; see docs/third-party/joycon.
#pragma once

#include "arm_model.h"

namespace droiddeck {

inline Quat IndexTipRotation(bool right) {
    return AboutY((right ? 5.0 : -5.0) * M_PI / 180.0) * AboutX(-40.0 * M_PI / 180.0);
}

inline void IndexRawPose(bool right, const Quat &aim, const Vec3 &grip, Quat *raw, Vec3 *position) {
    *raw = Normalize(aim * Conjugate(IndexTipRotation(right)));
    *position = grip - Rotate(*raw, {0.0, -0.015, 0.13});
}

}
