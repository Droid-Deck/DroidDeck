#pragma once
#include <openxr/openxr.h>
#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <string>

namespace droiddeck::xr {
inline bool optionalXrBindings(XrInstance instance, const XrInteractionProfileSuggestedBinding &bindings,
                               PFN_xrSuggestInteractionProfileBindings dispatch) {
    const XrResult result = dispatch(instance, &bindings);
    if (result == XR_ERROR_PATH_UNSUPPORTED || result == XR_ERROR_FEATURE_UNSUPPORTED ||
        result == XR_ERROR_PERMISSION_INSUFFICIENT) return false;
    if (XR_FAILED(result)) throw std::runtime_error("XR input binding failure: " + std::to_string(result));
    return true;
}

inline XrVersion compatibleGlesVersion(XrVersion minimum, XrVersion maximum) {
    const XrVersion version = std::max<XrVersion>(XR_MAKE_VERSION(3, 0, 0), minimum);
    if (version > maximum || XR_VERSION_MAJOR(version) != 3 || XR_VERSION_MINOR(version) > 2)
        throw std::runtime_error("No supported GLES 3.x version within runtime requirements");
    return version;
}

inline bool validStereoViews(const XrView *views, uint32_t count, XrViewStateFlags flags) {
    constexpr XrViewStateFlags required = XR_VIEW_STATE_ORIENTATION_VALID_BIT | XR_VIEW_STATE_POSITION_VALID_BIT;
    if (count != 2 || (flags & required) != required) return false;
    for (uint32_t eye = 0; eye < count; ++eye) {
        const auto &view = views[eye];
        for (float value : {view.pose.position.x, view.pose.position.y, view.pose.position.z,
                            view.fov.angleLeft, view.fov.angleRight, view.fov.angleUp, view.fov.angleDown})
            if (!std::isfinite(value)) return false;
        const auto &q = view.pose.orientation;
        const double norm = double(q.x)*q.x + double(q.y)*q.y + double(q.z)*q.z + double(q.w)*q.w;
        if (!std::isfinite(norm) || std::abs(norm - 1.0) > 0.1) return false;
    }
    return true;
}
}
