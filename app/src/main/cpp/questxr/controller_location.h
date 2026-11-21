#pragma once
#include <cmath>
#include "space_pose.h"

inline bool xrFinitePose(const XrPosef &pose) {
    const auto &q = pose.orientation;
    const double norm = double(q.x) * q.x + double(q.y) * q.y + double(q.z) * q.z + double(q.w) * q.w;
    return std::isfinite(norm) && norm > 1e-12 &&
           std::isfinite(pose.position.x) && std::isfinite(pose.position.y) && std::isfinite(pose.position.z);
}

inline bool xrTrackedLocation(const XrSpaceLocation &location) {
    constexpr XrSpaceLocationFlags tracked =
        XR_SPACE_LOCATION_ORIENTATION_VALID_BIT | XR_SPACE_LOCATION_POSITION_VALID_BIT |
        XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT | XR_SPACE_LOCATION_POSITION_TRACKED_BIT;
    return (location.locationFlags & tracked) == tracked && xrFinitePose(location.pose);
}

struct XrControllerLocation {
    enum class Source { None, View, World };
    XrSpaceLocation location{XR_TYPE_SPACE_LOCATION, nullptr, 0, {{0, 0, 0, 1}, {0, 0, 0}}};
    XrSpaceLocation view{XR_TYPE_SPACE_LOCATION, nullptr, 0, {{0, 0, 0, 1}, {0, 0, 0}}};
    XrSpaceLocation world{XR_TYPE_SPACE_LOCATION, nullptr, 0, {{0, 0, 0, 1}, {0, 0, 0}}};
    XrResult viewResult = XR_ERROR_HANDLE_INVALID, worldResult = XR_ERROR_HANDLE_INVALID;
    bool worldQueried = false;
    Source source = Source::None;

    const char *sourceName() const {
        return source == Source::View ? "view" : source == Source::World ? "world" : "none";
    }
};

inline XrControllerLocation xrLocateController(PFN_xrLocateSpace locate, XrSpace space,
                                               XrSpace view, XrSpace world, XrTime time,
                                               const XrSpaceLocation &head) {
    XrControllerLocation result;
    if (!space) return result;
    result.viewResult = locate(space, view, time, &result.view);
    if (XR_FAILED(result.viewResult)) result.view.locationFlags = 0;
    if (XR_SUCCEEDED(result.viewResult) && xrFinitePose(head.pose) && xrFinitePose(result.view.pose)) {
        result.location = xrComposeLocation(head, result.view);
        if (!xrFinitePose(result.location.pose)) result.location = XrControllerLocation{}.location;
        if (result.location.locationFlags) result.source = XrControllerLocation::Source::View;
    }
    if (!xrTrackedLocation(result.location) && world) {
        result.worldQueried = true;
        result.worldResult = locate(space, world, time, &result.world);
        if (XR_FAILED(result.worldResult)) result.world.locationFlags = 0;
        if (XR_SUCCEEDED(result.worldResult) && xrTrackedLocation(result.world)) {
            result.location = result.world;
            result.source = XrControllerLocation::Source::World;
        }
    }
    return result;
}
