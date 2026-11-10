#pragma once
#include <openxr/openxr.h>
#include "arm_model.h"

inline XrSpaceLocation xrComposeLocation(const XrSpaceLocation &parent, const XrSpaceLocation &child) {
    XrSpaceLocation result{XR_TYPE_SPACE_LOCATION};
    result.pose.orientation.w = 1;
    const auto both = parent.locationFlags & child.locationFlags;
    const auto rotation = droiddeck::Normalize({parent.pose.orientation.w, parent.pose.orientation.x,
                                                parent.pose.orientation.y, parent.pose.orientation.z});
    if (both & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) {
        const auto q = droiddeck::Normalize(rotation * droiddeck::Normalize(
            {child.pose.orientation.w, child.pose.orientation.x, child.pose.orientation.y, child.pose.orientation.z}));
        result.pose.orientation = {float(q.x), float(q.y), float(q.z), float(q.w)};
        result.locationFlags |= XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
        if (both & XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT)
            result.locationFlags |= XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT;
    }
    if ((both & XR_SPACE_LOCATION_POSITION_VALID_BIT) &&
        (parent.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) {
        const auto p = droiddeck::Rotate(rotation, {child.pose.position.x, child.pose.position.y, child.pose.position.z});
        result.pose.position = {float(parent.pose.position.x + p.x), float(parent.pose.position.y + p.y),
                                float(parent.pose.position.z + p.z)};
        result.locationFlags |= XR_SPACE_LOCATION_POSITION_VALID_BIT;
        if ((both & XR_SPACE_LOCATION_POSITION_TRACKED_BIT) &&
            (parent.locationFlags & XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT))
            result.locationFlags |= XR_SPACE_LOCATION_POSITION_TRACKED_BIT;
    }
    return result;
}
