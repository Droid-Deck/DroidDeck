#include "../src/xr_touch_input.h"
#include <cassert>
#include <cstdio>
#include <limits>

int main() {
    DroidDeckXrHand hand{};
    hand.active = 1;
    hand.trigger = 0.7f; hand.squeeze = 0.8f;
    hand.stick[0] = -0.4f; hand.stick[1] = 0.9f;
    auto input = xr_touch_input(hand, false);
    assert(input.trigger == hand.trigger && input.grip == hand.squeeze && input.triggerClick && input.gripClick);
    assert(input.stickX == -0.4f && input.stickY == 0.9f);
    assert(!input.stickTouch && !input.stickClick && !input.primaryClick && !input.secondaryClick && !input.systemClick);
    hand.buttons = DROIDDECK_XR_PRIMARY_CLICK;
    input = xr_touch_input(hand, false);
    assert(input.primaryClick && !input.secondaryClick && !input.stickClick && !input.systemClick && !input.primaryTouch);
    hand.buttons = DROIDDECK_XR_SECONDARY_CLICK;
    input = xr_touch_input(hand, true);
    assert(input.secondaryClick && !input.primaryClick && !input.systemClick && !input.stickClick);
    hand.buttons = DROIDDECK_XR_STICK_CLICK;
    input = xr_touch_input(hand, true);
    assert(input.stickClick && !input.primaryClick && !input.stickTouch);
    hand.buttons = DROIDDECK_XR_MENU_CLICK;
    assert(xr_touch_input(hand, false).systemClick);
    assert(!xr_touch_input(hand, true).systemClick);
    hand.buttons = 0;
    assert(!xr_touch_input(hand, false).systemClick);
    hand.buttons = DROIDDECK_XR_TRIGGER_TOUCH | DROIDDECK_XR_STICK_TOUCH | DROIDDECK_XR_PRIMARY_TOUCH |
                   DROIDDECK_XR_SECONDARY_TOUCH | DROIDDECK_XR_THUMBREST_TOUCH;
    hand.trigger = hand.squeeze = 0;
    input = xr_touch_input(hand, false);
    assert(input.triggerTouch && input.stickTouch && input.primaryTouch && input.secondaryTouch && input.thumbrestTouch);
    assert(!input.triggerClick && !input.gripClick && !input.primaryClick && !input.secondaryClick && !input.stickClick);
    hand.trigger = 2; hand.squeeze = -2; hand.stick[0] = -3; hand.stick[1] = 3;
    input = xr_touch_input(hand, false);
    assert(input.trigger == 1 && input.grip == 0 && input.stickX == -1 && input.stickY == 1);
    hand.trigger = std::numeric_limits<float>::quiet_NaN();
    hand.squeeze = std::numeric_limits<float>::infinity();
    hand.stick[0] = hand.trigger; hand.stick[1] = -hand.squeeze;
    input = xr_touch_input(hand, false);
    assert(input.trigger == 0 && input.grip == 0 && input.stickX == 0 && input.stickY == 0);
    hand.active = 0;
    hand.buttons = UINT32_MAX;
    hand.trigger = hand.squeeze = hand.stick[0] = hand.stick[1] = 1;
    input = xr_touch_input(hand, false);
    assert(input.trigger == 0 && input.grip == 0 && input.stickX == 0 && input.stickY == 0);
    assert(!input.triggerClick && !input.gripClick && !input.stickClick && !input.primaryClick && !input.secondaryClick && !input.systemClick);
    assert(!input.triggerTouch && !input.stickTouch && !input.primaryTouch && !input.secondaryTouch && !input.thumbrestTouch);
    std::puts("Touch input: independent face/stick/system, real capacitive touch, analog grip, clamping and inactive reset passed");
}
