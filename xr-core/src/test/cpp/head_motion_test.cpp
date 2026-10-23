#include "xr_head_motion.h"
#include <cassert>
#include <cstdio>
#include <limits>

static DroidDeckXrTracking sample(double yaw = 0) {
    DroidDeckXrTracking packet{};
    packet.head.flags = 15;
    packet.head.orientation[1] = std::sin(yaw/2);
    packet.head.orientation[3] = std::cos(yaw/2);
    return packet;
}
static void near(double actual, double expected) { assert(std::abs(actual-expected) < 1e-4); }

int main() {
    XrHeadMotion motion;
    auto packet = sample();
    motion.Update(packet, 1000000000, 1);
    assert(packet.head_velocity_flags == 0);
    packet = sample(0.02); packet.head.position[0] = 0.01f;
    motion.Update(packet, 1010000000, 1);
    assert(packet.head_velocity_flags == 3);
    near(packet.head_linear_velocity[0], 1);
    near(packet.head_angular_velocity[1], 2);
    near(packet.head_angular_velocity[0], 0);
    packet = sample(0.04);
    for (float &q : packet.head.orientation) q = -q;
    packet.head_velocity_flags = DROIDDECK_XR_LINEAR_VELOCITY_VALID;
    packet.head_linear_velocity[0] = 0.7f;
    motion.Update(packet, 1020000000, 1);
    near(packet.head_angular_velocity[1], 2);
    near(packet.head_linear_velocity[0], 0.7);
    packet = sample(0.06);
    packet.head_velocity_flags = 3;
    packet.head_linear_velocity[0] = std::numeric_limits<float>::quiet_NaN();
    packet.head_angular_velocity[1] = 0.5f;
    motion.Update(packet, 1030000000, 1);
    near(packet.head_linear_velocity[0], 0);
    near(packet.head_angular_velocity[1], 0.5);
    packet = sample(1);
    motion.Update(packet, 1040000000, 2);
    assert(packet.head_velocity_flags == 0);
    packet = sample(2);
    motion.Update(packet, 1300000000, 2);
    assert(packet.head_velocity_flags == 0);
    packet = sample(2.02);
    motion.Update(packet, 1310000000, 2);
    near(packet.head_angular_velocity[1], 2);
    packet = sample(); packet.head.flags = 3;
    motion.Update(packet, 1320000000, 2);
    assert(packet.head_velocity_flags == 0);
    packet = sample(1);
    motion.Update(packet, 1330000000, 2);
    assert(packet.head_velocity_flags == 0);
    packet = sample(); packet.head.orientation[0] = std::numeric_limits<float>::quiet_NaN();
    motion.Update(packet, 1340000000, 2);
    assert(packet.head_velocity_flags == 0);
    packet = sample(); motion.Update(packet, 1350000000, 2);
    packet = sample(1); motion.Update(packet, 1340000000, 2);
    assert(packet.head_velocity_flags == 0);

    XrHeadMotion rotated;
    packet = sample();
    const double half = std::sqrt(0.5);
    packet.head.orientation[0] = packet.head.orientation[3] = half;
    rotated.Update(packet, 1000000000, 1);
    packet = sample();
    packet.head.orientation[0] = packet.head.orientation[3] = std::cos(0.01)*half;
    packet.head.orientation[1] = std::sin(0.01)*half;
    packet.head.orientation[2] = -std::sin(0.01)*half;
    rotated.Update(packet, 1010000000, 1);
    near(packet.head_angular_velocity[0], 0);
    near(packet.head_angular_velocity[1], 2);
    near(packet.head_angular_velocity[2], 0);
    std::puts("Head motion passed: native priority, world-space velocity, shortest rotation, origin/time/tracking resets");
}
