#include "adaptive_pose.h"
#include <cassert>
#include <cstdio>

using namespace droiddeck;

int main() {
    for (const int period : {10, 20}) {
        AdaptivePositionMotion motion;
        double position = 0, previous = 0, energy = 0, oldEnergy = 0, target = 0;
        for (int frame = 0; frame < 2000; ++frame) {
            if (frame % period == 0) {
                target = (frame / period % 2 ? 1 : -1) * .004;
                motion.Observe({target, 0, 0}, 1000000000LL + frame * 4000000LL);
            }
            position += (target - position) * motion.PositionAlpha(.004);
            previous += (target - previous) * -std::expm1(-.004 / .06);
            if (frame > 1000) { energy += position * position; oldEnergy += previous * previous; }
        }
        assert(energy < oldEnergy * .81);
        motion.Reset(); position = previous = 0;
        for (int frame = 0; frame < 500; ++frame) {
            target = .5 * frame * .004;
            motion.Observe({target, 0, 0}, 1000000000LL + frame * 4000000LL);
            position += (target - position) * motion.PositionAlpha(.004);
            previous += (target - previous) * -std::expm1(-.004 / .06);
        }
        assert(target - position < target - previous);
        assert(target - position < .006);
    }
    AdaptivePositionMotion motion;
    const double idle = motion.PositionAlpha(.01);
    motion.Observe({}, 1000000000);
    motion.Observe({.1, 0, 0}, 1080000000);
    assert(motion.PositionAlpha(.01) > idle);
    const double alpha = motion.PositionAlpha(.01);
    motion.Observe({9, 9, 9}, 1080000000);
    assert(motion.PositionAlpha(.01) == alpha);
    motion.Observe({}, 2000000000);
    assert(motion.PositionAlpha(.01) == idle);
    motion.Reset();
    assert(motion.PositionAlpha(.01) == idle);
    assert(motion.PositionAlpha(0) == 0);
    for (bool right : {false, true}) for (double yaw : {0., .8, -2.}) {
        const Vec3 head{1, 1.6, -2};
        const auto turn = AboutY(yaw);
        const Vec3 shoulder = head + Rotate(turn, {right ? .18 : -.18, -.28, 0});
        const Vec3 ray = Rotate(turn, {.2, .1, -.5});
        const auto aim = ShoulderAim(head, turn, shoulder + ray, right, AboutX(1));
        assert(Length(Rotate(aim, {0, 0, -1}) - ray * (1 / Length(ray))) < 1e-8);
        const auto flippedPalm = ShoulderAim(head, turn, shoulder + ray, right, AboutX(-2));
        assert(Length(Rotate(flippedPalm, {0, 0, -1}) - Rotate(aim, {0, 0, -1})) < 1e-8);
        assert(ShoulderAim(head, turn, shoulder, right, AboutX(1)).x == AboutX(1).x);
    }
    const auto vertical = ShoulderAim({}, AboutX(M_PI / 2), {.3, -.1, -.5}, true, {});
    assert(std::isfinite(vertical.w) && std::isfinite(vertical.y));
    puts("PASS: adaptive jitter, motion response, capture time, reset, and shoulder aim");
}
