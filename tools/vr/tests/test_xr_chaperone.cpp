#include "../src/xr_chaperone.h"
#include <cassert>
#include <cstdio>

int main() {
    DroidDeckXrPose head{{0, 0, 0, 1}, {2, 1.6f, -3}, 15};
    XrChaperoneOrigin deferred;
    DroidDeckXrTracking tracking{};
    tracking.active = 1; tracking.period_ns = 11111111; tracking.sampled_ns = XrTrackingFile::Now();
    tracking.eyes[0].width = tracking.eyes[0].height = 4;
    unsigned publications = 0;
    auto publish = [&](const auto &pose) { publications++; assert(pose.position[1] == 1.6f); return true; };
    deferred.Update(tracking, publish); assert(publications == 0);
    tracking.head = head; tracking.head.flags = 0;
    deferred.Update(tracking, publish); assert(publications == 0);
    tracking.head = head;
    deferred.Update(tracking, publish); assert(publications == 1);
    tracking.head.position[1] = 2;
    deferred.Update(tracking, publish); assert(publications == 1);
    deferred.Reset(); tracking.head = head;
    deferred.Update(tracking, [&](const auto &) { publications++; return false; });
    deferred.Update(tracking, publish); assert(publications == 3);
    auto origin = xr_seated_origin(head);
    assert(origin.translation[0] == -2 && std::abs(origin.translation[1] + 1.6) < 1e-6 && origin.translation[2] == 3);
    head.orientation[1] = head.orientation[3] = std::sqrt(0.5f);
    origin = xr_seated_origin(head);
    assert(std::abs(origin.yaw + 1.57079632679) < 1e-6);
    assert(std::abs(origin.translation[0] + 3) < 1e-6 && std::abs(origin.translation[2] + 2) < 1e-6);
    std::puts(xr_chaperone_json(head, "2026-10-15T21:00:00Z").c_str());
}
