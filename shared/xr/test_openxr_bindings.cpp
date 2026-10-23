#include "../../app/src/main/cpp/questxr/action_bindings.h"
#include <cassert>
#include <iostream>
static XrResult result;
static int calls;
static XrResult XRAPI_PTR mock(XrInstance, const XrInteractionProfileSuggestedBinding *binding) {
    assert(binding->type == XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING);
    ++calls; return result;
}
int main() {
    XrInteractionProfileSuggestedBinding binding{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
    for (XrResult error : {XR_ERROR_PATH_UNSUPPORTED, XR_ERROR_FEATURE_UNSUPPORTED, XR_ERROR_PERMISSION_INSUFFICIENT}) {
        result = error; assert(!optionalXrBindings(XR_NULL_HANDLE, binding, mock));
    }
    result = XR_SUCCESS; assert(optionalXrBindings(XR_NULL_HANDLE, binding, mock));
    result = XR_ERROR_RUNTIME_FAILURE;
    bool failed = false;
    try { optionalXrBindings(XR_NULL_HANDLE, binding, mock); } catch (const std::runtime_error &) { failed = true; }
    assert(failed && calls == 5);
    std::cout << "Mock OpenXR binding dispatch: 5 cases passed; no runtime validation\n";
}
