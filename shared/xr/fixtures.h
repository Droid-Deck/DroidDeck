#pragma once
#ifndef DROIDDECK_XR_FIXTURES
#error Fixtures are restricted to explicitly enabled debug/test targets
#endif
#include "core.h"
#include <limits>

namespace droiddeck::xr {
inline Sample fixture(int step, int64_t now) {
    Sample s;
    s.provider = Provider::Fixture; s.session = step >= 11 ? 2 : 1; s.origin = step >= 7 ? 2 : 1;
    s.sampled = {now, Clock::Monotonic}; s.connected = true; s.focused = true;
    s.periodNs = 11111111; s.capabilities = Head | Hands | Pinch | Grasp | Aim;
    s.head = {{0, 1.6f, 0}, {0, 0, 0, 1}, 15};
    for (int side = 0; side < 2; ++side) {
        auto &h = s.hands[side]; h.source = Source::Fixture; h.available = true;
        h.grip = {{side ? .3f : -.2f, 1.2f, side ? -.6f : -.4f}, {0, 0, 0, 1}, 15};
        if (side == 0) h.grip.meters[0] += .01f * step;
        else h.grip.meters[2] -= .02f * step;
        h.pinch = 0; h.grasp = 0;
        s.eyes[side].pose = packetPose(s.head); s.eyes[side].width = s.eyes[side].height = 512;
    }
    if (step == 2 || step == 3 || step == 5) {
        s.hands[0].pinch = 1; s.hands[1].grasp = 1;
    }
    if (step == 3) s.hands[0].grip.flags = s.hands[1].grip.flags = 3;
    if (step == 4) s.focused = false;
    if (step == 6) s.inputAllowed = false;
    if (step == 8) s.hands[0].grip.xyzw = {0, 0, 0, 0};
    if (step == 9) s.head.meters[0] = std::numeric_limits<float>::quiet_NaN();
    if (step == 10) s.connected = false;
    return s;
}
inline const char *fixtureName(int step) {
    static const char *names[] = {"connect", "neutral", "left pinch/right grasp", "tracking loss while pressed",
        "focus loss", "resume held", "permission revoked", "new origin", "malformed quaternion",
        "nonfinite position", "disconnect", "reconnect", "neutral after reconnect"};
    return names[step % 13];
}
}
