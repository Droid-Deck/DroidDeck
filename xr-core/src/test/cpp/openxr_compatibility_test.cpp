#include "OpenXrCompatibility.h"
#include <cassert>
#include <cstdio>
#include <limits>
#include <utility>

using namespace droiddeck::xr;

static XrResult bindingResult;
static int bindingCalls;
static XrResult XRAPI_PTR suggestBindings(XrInstance, const XrInteractionProfileSuggestedBinding *binding) {
    assert(binding->type == XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING);
    ++bindingCalls;
    return bindingResult;
}

int main() {
    XrInteractionProfileSuggestedBinding binding{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
    for (XrResult error : {XR_ERROR_PATH_UNSUPPORTED, XR_ERROR_FEATURE_UNSUPPORTED, XR_ERROR_PERMISSION_INSUFFICIENT}) {
        bindingResult = error;
        assert(!optionalXrBindings(XR_NULL_HANDLE, binding, suggestBindings));
    }
    bindingResult = XR_SUCCESS;
    assert(optionalXrBindings(XR_NULL_HANDLE, binding, suggestBindings));
    bindingResult = XR_ERROR_RUNTIME_FAILURE;
    bool failed = false;
    try { optionalXrBindings(XR_NULL_HANDLE, binding, suggestBindings); }
    catch (const std::runtime_error &) { failed = true; }
    assert(failed && bindingCalls == 5);

    const auto gl30 = XR_MAKE_VERSION(3, 0, 0), gl31 = XR_MAKE_VERSION(3, 1, 0), gl32 = XR_MAKE_VERSION(3, 2, 0);
    assert(compatibleGlesVersion(XR_MAKE_VERSION(2, 0, 0), gl32) == gl30);
    assert(compatibleGlesVersion(gl31, gl32) == gl31);
    assert(compatibleGlesVersion(gl32, gl32) == gl32);
    for (const auto &range : {std::pair{gl31, gl30}, std::pair{gl30, XR_MAKE_VERSION(2, 0, 0)},
                              std::pair{XR_MAKE_VERSION(3, 3, 0), XR_MAKE_VERSION(4, 0, 0)}}) {
        failed = false;
        try { compatibleGlesVersion(range.first, range.second); }
        catch (const std::runtime_error &) { failed = true; }
        assert(failed);
    }

    XrView views[2]{{XR_TYPE_VIEW}, {XR_TYPE_VIEW}};
    for (auto &view : views) {
        view.pose.orientation.w = 1;
        view.fov = {-0.7f, 0.7f, 0.7f, -0.7f};
    }
    constexpr auto validFlags = XR_VIEW_STATE_ORIENTATION_VALID_BIT | XR_VIEW_STATE_POSITION_VALID_BIT;
    assert(validStereoViews(views, 2, validFlags));
    assert(!validStereoViews(views, 1, validFlags));
    assert(!validStereoViews(views, 2, XR_VIEW_STATE_ORIENTATION_VALID_BIT));
    assert(!validStereoViews(views, 2, XR_VIEW_STATE_POSITION_VALID_BIT));
    for (auto &view : views) {
        for (float *field : {&view.fov.angleLeft, &view.fov.angleRight, &view.fov.angleUp, &view.fov.angleDown,
                            &view.pose.position.x, &view.pose.position.y, &view.pose.position.z,
                            &view.pose.orientation.x, &view.pose.orientation.y, &view.pose.orientation.z,
                            &view.pose.orientation.w}) {
            const float original = *field;
            for (float invalid : {std::numeric_limits<float>::quiet_NaN(), std::numeric_limits<float>::infinity()}) {
                *field = invalid;
                assert(!validStereoViews(views, 2, validFlags));
            }
            *field = original;
        }
        view.pose.orientation.w = 0;
        assert(!validStereoViews(views, 2, validFlags));
        view.pose.orientation.w = 1;
    }
    assert(validStereoViews(views, 2, validFlags));
    std::puts("OpenXR compatibility passed: optional profiles, GLES requirements, stereo sample validity");
}
