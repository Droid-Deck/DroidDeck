#pragma once
#include "xr_tracking.h"
#include <algorithm>
#include <cmath>

struct XrInputEvidence {
    uint32_t buttonsSeen = 0;
    float triggerMax = 0, squeezeMax = 0;
    float stickMin[2]{}, stickMax[2]{};
    uint64_t hapticsApplied = 0, hapticsRejected = 0;

    void observe(const DroidDeckXrHand &hand, bool active) {
        if (!active) return;
        buttonsSeen |= hand.buttons;
        if (std::isfinite(hand.trigger)) triggerMax = std::max(triggerMax, hand.trigger);
        if (std::isfinite(hand.squeeze)) squeezeMax = std::max(squeezeMax, hand.squeeze);
        for (int axis = 0; axis < 2; ++axis) {
            if (!std::isfinite(hand.stick[axis])) continue;
            stickMin[axis] = std::min(stickMin[axis], hand.stick[axis]);
            stickMax[axis] = std::max(stickMax[axis], hand.stick[axis]);
        }
    }
};
