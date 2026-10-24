#pragma once
#include <openxr/openxr.h>
#include <stdexcept>
#include <string>

inline bool optionalXrBindings(XrInstance instance, const XrInteractionProfileSuggestedBinding &bindings,
                               PFN_xrSuggestInteractionProfileBindings dispatch) {
    const XrResult result = dispatch(instance, &bindings);
    if (result == XR_ERROR_PATH_UNSUPPORTED || result == XR_ERROR_FEATURE_UNSUPPORTED ||
        result == XR_ERROR_PERMISSION_INSUFFICIENT) return false;
    if (XR_FAILED(result)) throw std::runtime_error("XR input binding failure: " + std::to_string(result));
    return true;
}
