#pragma once
#include "xr_tracking_file.h"

inline std::string XrDisplayMode(const DroidDeckXrTracking &tracking) {
    if (!XrTrackingFile::Fresh(tracking)) return {};
    auto refresh = static_cast<uint32_t>(std::llround(1000000000.0 / tracking.period_ns));
    return std::to_string(tracking.eyes[0].width * 2) + "x" + std::to_string(tracking.eyes[0].height)
        + "@" + std::to_string(refresh);
}
