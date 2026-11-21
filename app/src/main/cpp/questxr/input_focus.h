#pragma once
#include "xr_tracking.h"

class XrInputFocus {
public:
    bool accept(bool focused, DroidDeckXrHand hands[2]) {
        if (focused) return true;
        for (int index = 0; index < 2; ++index) {
            auto &hand = hands[index];
            hand.buttons = 0;
            hand.trigger = hand.squeeze = hand.stick[0] = hand.stick[1] = 0;
        }
        return false;
    }
};
