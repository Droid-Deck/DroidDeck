#pragma once
#include "xr_tracking.h"
#include <algorithm>
#include <cmath>

struct XrTouchInput {
    float trigger = 0, grip = 0, stickX = 0, stickY = 0;
    bool triggerClick = false, gripClick = false, stickClick = false;
    bool primaryClick = false, secondaryClick = false, systemClick = false;
    bool triggerTouch = false, stickTouch = false, primaryTouch = false, secondaryTouch = false, thumbrestTouch = false;
};

inline XrTouchInput xr_touch_input(const DroidDeckXrHand &hand, bool right) {
    XrTouchInput input;
    if (!hand.active) return input;
    auto scalar = [](float value, float minimum) { return std::isfinite(value) ? std::clamp(value, minimum, 1.f) : 0.f; };
    input.trigger = scalar(hand.trigger, 0);
    input.grip = scalar(hand.squeeze, 0);
    input.stickX = scalar(hand.stick[0], -1);
    input.stickY = scalar(hand.stick[1], -1);
    input.triggerClick = input.trigger > 0.6f;
    input.gripClick = input.grip > 0.6f;
    input.stickClick = hand.buttons & DROIDDECK_XR_STICK_CLICK;
    input.primaryClick = hand.buttons & DROIDDECK_XR_PRIMARY_CLICK;
    input.secondaryClick = hand.buttons & DROIDDECK_XR_SECONDARY_CLICK;
    input.systemClick = !right && (hand.buttons & DROIDDECK_XR_MENU_CLICK);
    input.triggerTouch = hand.buttons & DROIDDECK_XR_TRIGGER_TOUCH;
    input.stickTouch = hand.buttons & DROIDDECK_XR_STICK_TOUCH;
    input.primaryTouch = hand.buttons & DROIDDECK_XR_PRIMARY_TOUCH;
    input.secondaryTouch = hand.buttons & DROIDDECK_XR_SECONDARY_TOUCH;
    input.thumbrestTouch = hand.buttons & DROIDDECK_XR_THUMBREST_TOUCH;
    return input;
}
