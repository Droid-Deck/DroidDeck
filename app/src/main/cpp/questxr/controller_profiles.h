#pragma once
#include <openxr/openxr.h>
#include <array>
#include <string>
#include <vector>
#include "xr_tracking.h"

struct XrControllerProfile {
    const char *path;
    const char *extension;
    bool thumbrest;
    bool physicalClicks;
    DroidDeckXrControllerProfile id;
};

inline constexpr std::array<XrControllerProfile, 6> xrControllerProfiles{{
    {"/interaction_profiles/khr/simple_controller", nullptr, false, false, DROIDDECK_XR_CONTROLLER_SIMPLE},
    {"/interaction_profiles/oculus/touch_controller", nullptr, true, false, DROIDDECK_XR_CONTROLLER_TOUCH},
    {"/interaction_profiles/bytedance/pico_neo3_controller", "XR_BD_controller_interaction", true, true, DROIDDECK_XR_CONTROLLER_PICO_NEO3},
    {"/interaction_profiles/bytedance/pico4_controller", "XR_BD_controller_interaction", true, true, DROIDDECK_XR_CONTROLLER_PICO4},
    {"/interaction_profiles/bytedance/pico4s_controller", "XR_BD_controller_interaction", true, true, DROIDDECK_XR_CONTROLLER_PICO4S},
    {"/interaction_profiles/bytedance/pico_ultra_controller_bd", "XR_BD_ultra_controller_interaction", true, true, DROIDDECK_XR_CONTROLLER_PICO4S},
}};

inline DroidDeckXrControllerProfile xr_controller_profile_id(const std::string &path) {
    for (const auto &profile : xrControllerProfiles)
        if (path == profile.path) return profile.id;
    return DROIDDECK_XR_CONTROLLER_UNKNOWN;
}

template<class Suggest, class Rejected>
XrResult xr_negotiate_controller_bindings(std::vector<XrActionSuggestedBinding> &bindings,
                                        Suggest suggest, Rejected rejected) {
    auto result = suggest(bindings);
    if (result != XR_ERROR_PATH_UNSUPPORTED) return result;
    std::vector<XrActionSuggestedBinding> accepted;
    for (const auto &binding : bindings) {
        auto candidate = accepted;
        candidate.push_back(binding);
        result = suggest(candidate);
        if (XR_SUCCEEDED(result)) accepted.push_back(binding);
        else if (result == XR_ERROR_PATH_UNSUPPORTED) rejected(binding.binding);
        else return result;
    }
    if (accepted.empty()) return XR_ERROR_PATH_UNSUPPORTED;
    bindings = std::move(accepted);
    return suggest(bindings);
}

struct XrControllerActions {
    XrAction grip, trigger, squeeze, stick, stickClick, primary, secondary, menu;
    XrAction triggerTouch, stickTouch, primaryTouch, secondaryTouch, thumbrestTouch, vibration;
    XrAction triggerClick, squeezeClick, aim;
};

template<class Path>
std::vector<XrActionSuggestedBinding> xr_controller_bindings(
        const XrControllerProfile &profile, const XrControllerActions &actions, Path path) {
    std::vector<XrActionSuggestedBinding> bindings;
    for (int hand = 0; hand < 2; ++hand) {
        const std::string root = hand ? "/user/hand/right" : "/user/hand/left";
        auto bind = [&](XrAction action, const char *suffix) {
            bindings.push_back({action, path((root + suffix).c_str())});
        };
        bind(actions.grip, "/input/grip/pose");
        bind(actions.aim, "/input/aim/pose");
        if (profile.id == DROIDDECK_XR_CONTROLLER_SIMPLE) {
            bind(actions.triggerClick, "/input/select/click");
            bind(actions.menu, "/input/menu/click");
            bind(actions.vibration, "/output/haptic");
            continue;
        }
        bind(actions.trigger, "/input/trigger/value");
        bind(actions.triggerTouch, "/input/trigger/touch");
        bind(actions.squeeze, "/input/squeeze/value");
        bind(actions.stick, "/input/thumbstick");
        bind(actions.stickClick, "/input/thumbstick/click");
        bind(actions.stickTouch, "/input/thumbstick/touch");
        bind(actions.primary, hand ? "/input/a/click" : "/input/x/click");
        bind(actions.secondary, hand ? "/input/b/click" : "/input/y/click");
        bind(actions.primaryTouch, hand ? "/input/a/touch" : "/input/x/touch");
        bind(actions.secondaryTouch, hand ? "/input/b/touch" : "/input/y/touch");
        bind(actions.vibration, "/output/haptic");
        if (!hand || profile.extension) bind(actions.menu, "/input/menu/click");
        if (profile.thumbrest) bind(actions.thumbrestTouch, "/input/thumbrest/touch");
        if (profile.physicalClicks) {
            bind(actions.triggerClick, "/input/trigger/click");
            bind(actions.squeezeClick, "/input/squeeze/click");
        }
    }
    return bindings;
}
