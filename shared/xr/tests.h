#pragma once
#include "fixtures.h"
#include "../../tools/vr/src/xr_tracking_file.h"
#include <stdexcept>
#include <vector>
#include <cstdio>

namespace droiddeck::xr {
inline int runTests(const std::string &directory) {
    int checks = 0;
    auto expect = [&](bool ok, const char *name) { ++checks; if (!ok) throw std::runtime_error(name); };
    const int64_t now = 1000000000;
    const Time time{now, Clock::Monotonic};
    Core core; core.begin(Provider::Fixture, 1, 1);
    auto s = fixture(0, now);
    core.update(s, time); core.update(s, time);
    s.hands[0].pinch = 1; s.hands[1].grasp = 1;
    auto out = core.update(s, time);
    expect(out.packet.head.position[1] == 1.6f && out.packet.hands[0].pose.position[0] == -.2f &&
           out.packet.hands[1].pose.position[2] == -.6f, "meter scale and independent hands");
    expect(out.hands[0].pinch.pressed && out.hands[1].grasp.pressed && !out.hands[1].pinch.down, "independent edges");
    s.hands[0].pinch = .6f;
    expect(core.update(s, time).hands[0].pinch.down, "hysteresis holds");
    s.hands[0].pinch = .46f;
    expect(core.update(s, time).hands[0].pinch.down, "hysteresis jitter");
    s.hands[0].pinch = .45f;
    expect(core.update(s, time).hands[0].pinch.released, "hysteresis release");
    s.hands[0].pinch = .69f;
    expect(!core.update(s, time).hands[0].pinch.down, "below press threshold");
    s.hands[0].pinch = 1; core.update(s, time);
    s.hands[0].grip.flags = 3;
    out = core.update(s, time);
    expect(out.hands[0].pinch.released && !out.packet.hands[0].active && out.hands[0].grip.flags == 3,
           "valid but not tracked releases and retains diagnostic pose");
    s.hands[0].grip.flags = 15;
    expect(!core.update(s, time).hands[0].pinch.down, "reacquisition held must neutralize");
    s.hands[0].pinch = 0; core.update(s, time);
    s.hands[0].pinch = 1;
    expect(core.update(s, time).hands[0].pinch.pressed, "neutral rearms");
    for (int fault = 0; fault < 7; ++fault) {
        Core c; c.begin(Provider::Fixture, 1, 1);
        auto x = fixture(0, now); c.update(x, time); c.update(x, time);
        x.hands[0].pinch = 1; c.update(x, time);
        if (fault == 0) x.focused = false;
        if (fault == 1) x.connected = false;
        if (fault == 2) x.inputAllowed = false;
        if (fault == 3) x.origin = 2;
        if (fault == 4) x.session = 2;
        if (fault == 5) x.provider = Provider::Jetpack;
        if (fault == 6) x.sampled.ns -= 250000001;
        auto lost = c.update(x, time);
        expect(lost.hands[0].pinch.released && lost.packet.hands[0].trigger == 0 && !lost.packet.hands[0].active,
               "lifecycle/time/generation loss releases");
    }
    core.begin(Provider::Fixture, 1, 2);
    expect(!core.update(s, time).packet.active, "old origin rejected");
    s.origin = 2;
    expect(core.update(s, time).packet.active, "new origin accepted");
    s.controllers[0] = s.hands[0]; s.controllers[0].source = Source::Controller;
    s.controllers[0].grip.meters[0] = -.8f;
    out = core.update(s, time);
    expect(out.hands[0].source == Source::Controller && out.packet.hands[0].pose.position[0] == -.8f &&
           !out.hands[0].pinch.down, "controller priority and source release");
    s.controllers[0].grip.flags = 0;
    expect(!core.update(s, time).packet.hands[0].active, "controller dropout cannot activate simultaneous hand");
    s.controllers[0].grip.flags = 15; s.controllers[0].pinch = 1;
    expect(core.update(s, time).packet.hands[0].trigger == 0, "controller reacquisition also requires neutral");
    s.controllers[0].pinch = 0; core.update(s, time); s.controllers[0].pinch = .8f;
    expect(core.update(s, time).packet.hands[0].trigger == .8f, "physical controller analog preserved");
    s.controllers[0].pinch = std::numeric_limits<float>::infinity();
    expect(core.update(s, time).packet.hands[0].trigger == 0, "nonfinite gesture releases");
    s.controllers[0].pinch = .8f;
    expect(core.update(s, time).packet.hands[0].trigger == 0, "bad gesture needs neutral before rearming");
    Core restarted; restarted.begin(Provider::Fixture, 2, 1);
    auto restart = fixture(0, now); restart.session = 2;
    restarted.update(restart, time); restarted.update(restart, time);
    restart.hands[1].grasp = 1;
    expect(restarted.update(restart, time).hands[1].grasp.pressed, "new session can arm normally");
    restarted.begin(Provider::Fixture, 3, 1);
    expect(restarted.update(restart, time).hands[1].grasp.released, "restart releases prior grip");
    Pose p{{1, 2, -3}, {0, 0, .70710678f, .70710678f}, 15};
    auto q = openVrWxyz(p);
    expect(std::abs(q[0] - .70710678) < 1e-6 && q[1] == 0 && std::abs(q[3] - .70710678) < 1e-6,
           "known 90 degree Z xyzw to wxyz rotation");
    p.flags = 5;
    expect(validate(p).flags == 5 && validate(p).meters[0] == 0, "orientation only stays orientation only");
    p.flags = 3;
    expect(!tracked(validate(p)), "valid is not tracked");
    for (float bad : {0.f, 3.f, std::numeric_limits<float>::quiet_NaN(), std::numeric_limits<float>::infinity()}) {
        p = {{0, 0, 0}, {0, 0, 0, bad}, 15};
        expect(!(validate(p).flags & 1), "invalid quaternion");
    }
    for (float bad : {std::numeric_limits<float>::quiet_NaN(), std::numeric_limits<float>::infinity()}) {
        p = {{bad, 0, 0}, {0, 0, 0, 1}, 15};
        expect(!(validate(p).flags & 2), "nonfinite position");
    }
    expect(!age({now + 1, Clock::Monotonic}, time), "future timestamp rejected");
    expect(!age({now, Clock::BootTime}, time), "different clocks rejected");
    expect(prediction({now + 10000000, Clock::OpenXR}, time) == 0, "XrTime is not monotonic");
    expect(prediction({now + 10000000, Clock::Monotonic}, time) == now + 10000000, "converted prediction accepted");
    expect(prediction({INT64_MAX, Clock::Monotonic}, time) == 0, "unbounded prediction rejected");
    for (int side = 0; side < 2; ++side) for (int angle = 0; angle < 4; ++angle) {
        for (float bad : {std::numeric_limits<float>::quiet_NaN(), std::numeric_limits<float>::infinity(),
                          -std::numeric_limits<float>::infinity()}) {
            Core c; c.begin(Provider::Fixture, 1, 1);
            auto x = fixture(0, now); c.update(x, time); c.update(x, time);
            x.hands[0].pinch = 1; x.hands[1].grasp = 1; c.update(x, time);
            x.eyes[side].fov[angle] = bad;
            auto rejected = c.update(x, time);
            expect(!rejected.packet.active && rejected.hands[0].pinch.released && rejected.hands[1].grasp.released &&
                   !rejected.packet.hands[0].active && !rejected.packet.hands[1].active &&
                   std::isfinite(rejected.packet.eyes[side].fov[angle]), "nonfinite FOV rejects sample and releases inputs");
        }
    }
    Core fovCore; fovCore.begin(Provider::Fixture, 1, 1);
    auto finiteFov = fixture(0, now);
    for (auto &eye : finiteFov.eyes) {
        eye.fov[0] = -.8f; eye.fov[1] = .9f; eye.fov[2] = 1.f; eye.fov[3] = -.7f;
    }
    for (int64_t sampleAge : {0LL, 250000000LL, 250000001LL, -1LL}) {
        finiteFov.sampled.ns = now - sampleAge;
        auto accepted = fovCore.update(finiteFov, time);
        expect(bool(accepted.packet.active) == (sampleAge >= 0 && sampleAge <= 250000000), "finite FOV preserves freshness boundary");
        if (accepted.packet.active) expect(accepted.packet.eyes[1].fov[0] == -.8f && accepted.ageNs == sampleAge &&
            accepted.packet.sampled_ns == finiteFov.sampled.ns, "finite FOV and acquisition age preserved");
    }
    auto headOnly = fixture(0, now); headOnly.hands = {}; headOnly.capabilities = Head;
    Core headCore; headCore.begin(Provider::Fixture, 1, 1);
    out = headCore.update(headOnly, time);
    expect(out.packet.active && out.packet.head.flags == 15 && !out.packet.hands[0].active, "optional hands");
    headOnly.sampled.ns = now - 100000000;
    out = headCore.update(headOnly, time);
    expect(out.ageNs == 100000000 && out.packet.sampled_ns == 900000000, "publication retains acquisition age");
    auto interaction = headOnly;
    interaction.hands[1].source = Source::JetpackInteraction;
    interaction.hands[1].available = true; interaction.hands[1].pinch = 0;
    headCore.update(interaction, time); headCore.update(interaction, time);
    interaction.hands[1].pinch = 1;
    out = headCore.update(interaction, time);
    expect(out.hands[1].pinch.pressed && !out.packet.hands[1].active && !out.hands[1].grip.flags,
           "interaction event does not invent hand pose");
    std::string name = directory + "/xr-roundtrip-XXXXXX";
    std::vector<char> filename(name.begin(), name.end()); filename.push_back(0);
    int fd = mkstemp(filename.data());
    if (fd < 0) throw std::runtime_error("temporary packet file");
    if (ftruncate(fd, sizeof(DroidDeckXrTracking))) { close(fd); unlink(filename.data()); throw std::runtime_error("size packet file"); }
    auto *mapping = static_cast<DroidDeckXrTracking *>(mmap(nullptr, sizeof(DroidDeckXrTracking), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0));
    if (mapping == MAP_FAILED) { close(fd); unlink(filename.data()); throw std::runtime_error("map packet file"); }
    try {
        DroidDeckXrTracking packet = out.packet;
        packet.sampled_ns = XrTrackingFile::Now();
        packet.head = {{0, 0, 0, 1}, {1, 2, -3}, 15};
        publish(mapping, packet);
        XrTrackingFile reader(filename.data()); DroidDeckXrTracking read{};
        expect(reader.Read(&read) && XrTrackingFile::Fresh(read), "real production writer/reader roundtrip");
        packet.seq = read.seq;
        expect(std::memcmp(&packet, &read, sizeof(packet)) == 0, "all packet bytes roundtrip");
        const auto *bytes = reinterpret_cast<const unsigned char *>(&read);
        expect(sizeof(read) == 296 && bytes[0] == 0x44 && bytes[1] == 0x44 && bytes[2] == 0x58 && bytes[3] == 0x52 &&
               bytes[4] == 1 && bytes[54] == 0x80 && bytes[55] == 0x3f && bytes[66] == 0x40 && bytes[67] == 0xc0,
               "v1 little endian golden offsets");
        mapping->version = 2; expect(!reader.Read(&read), "version rejection");
        mapping->version = 1; mapping->seq = 3; expect(!reader.Read(&read), "torn publication rejected");
    } catch (...) { munmap(mapping, sizeof(DroidDeckXrTracking)); close(fd); unlink(filename.data()); throw; }
    munmap(mapping, sizeof(DroidDeckXrTracking));
    ftruncate(fd, 4);
    { XrTrackingFile reader(filename.data()); DroidDeckXrTracking read{}; expect(!reader.Read(&read), "length rejection"); }
    close(fd); unlink(filename.data());
    return checks;
}
}
