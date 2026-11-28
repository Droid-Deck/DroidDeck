#include "MappedGuestXrAdapter.h"
#include "xr_hand_math.h"
#include "xr_skeleton_math.h"
#include "ScopeExit.h"
#include "Json.h"
#include <cassert>
#include <atomic>
#include <limits>
#include <thread>
#include <sys/wait.h>
using namespace droiddeck::xr;

int main() {
    auto packet = droiddeck_xr_input_v2_neutral(1, 2);
    int64_t now = 1000000000;
    packet.sample_monotonic_ns = now;
    packet.flags = DROIDDECK_XR_INPUT_FOCUSED | DROIDDECK_XR_INPUT_HAND_MODE;
    auto &hand = packet.hands[0];
    hand.source = DROIDDECK_XR_SOURCE_RUNTIME_ACTION;
    hand.grip.flags = hand.aim.flags = 15;
    hand.status_bits = 31; hand.ready_bits = hand.capability_bits = DROIDDECK_XR_PINCH | DROIDDECK_XR_GRASP;
    SteamVrHandBridge bridge;
    assert(!bridge.update(packet, 0, now).active);
    hand.pinch = 1;
    assert(!bridge.update(packet, 0, now).triggerDown);
    hand.pinch = 0;
    assert(bridge.update(packet, 0, now).active);
    hand.pinch = 1;
    assert(bridge.update(packet, 0, now).triggerDown);
    hand.grip.flags = 7;
    assert(!bridge.update(packet, 0, now).active);
    hand.grip.flags = 15; hand.pinch = 0; bridge.update(packet, 0, now);
    hand.pinch = 1;
    assert(bridge.update(packet, 0, now).triggerDown);
    packet.flags &= ~DROIDDECK_XR_INPUT_FOCUSED;
    assert(!bridge.update(packet, 0, now).active);
    packet.flags |= DROIDDECK_XR_INPUT_FOCUSED;
    assert(!bridge.update(packet, 0, now).triggerDown);
    hand.pinch = 0; bridge.update(packet, 0, now);
    hand.pinch = 1;
    assert(!bridge.update(packet, 0, now+100000001).active);
    assert(!bridge.update(packet, 0, now-1).active);
    hand.pinch = hand.grasp = 0;
    assert(bridge.update(packet, 0, now).active);
    hand.pinch = hand.grasp = 1;
    auto output = bridge.update(packet, 0, now);
    assert(output.triggerDown && output.graspDown);
    ++packet.origin_epoch;
    assert(!bridge.update(packet, 0, now).active);
    assert(!bridge.update(packet, 0, now).triggerDown);
    hand.pinch = hand.grasp = 0; bridge.update(packet, 0, now);
    hand.source = DROIDDECK_XR_SOURCE_CONTROLLER;
    assert(!bridge.update(packet, 0, now).active);
    hand.source = DROIDDECK_XR_SOURCE_SYNTHETIC;
    assert(!bridge.update(packet, 0, now).active);
    hand.grip.orientation[0] = std::numeric_limits<float>::quiet_NaN();
    assert(!poseValid(hand.grip));
    hand.grip.orientation[0] = 0;

    auto grip = handTransform(hand.grip);
    grip.m[0][3] = 0.2f; grip.m[1][3] = -1.1f;
    HandTransform aim{{{0,0,1,0.03f},{0,1,0,-0.02f},{-1,0,0,0.05f}}};
    const auto offset = handCompose(handInverse(grip), aim);
    const auto restored = handCompose(grip, offset);
    for (int r=0;r<3;++r) for (int c=0;c<4;++c) assert(std::abs(restored.m[r][c]-aim.m[r][c]) < 0.00001f);

    ScalarLatch drag;
    assert(!drag.update(1, true));
    assert(!drag.update(0, true));
    assert(drag.update(1, true));
    for (float value : {0.59f, 0.4f, 0.8f, 0.3f}) assert(drag.update(value, true));
    assert(!drag.update(0.2f, true));
    assert(!drag.update(0.5f, true));
    assert(drag.update(1, true));
    assert(!drag.update(1, false));
    assert(!drag.update(1, true));

    auto chordPacket = droiddeck_xr_input_v2_neutral(999, 1);
    chordPacket.flags = DROIDDECK_XR_INPUT_HAND_MODE | DROIDDECK_XR_INPUT_FOCUSED;
    for (auto &h : chordPacket.hands) {
        h.grip.flags = h.aim.flags = 15; h.source = DROIDDECK_XR_SOURCE_RUNTIME_ACTION;
        h.ready_bits = h.capability_bits = 7; h.status_bits = 31;
    }
    HandDashboardChord chord;
    auto tick = [&](int64_t time) { chordPacket.sample_monotonic_ns = time; return chord.update(chordPacket, time); };
    assert(!tick(now)); assert(!tick(now+1));
    for (auto &h : chordPacket.hands) h.pinch = 1;
    assert(!tick(now+2)); assert(!tick(now+999999999));
    assert(tick(now+1000000002)); assert(!tick(now+2100000000));
    chordPacket.hands[0].pinch = 0; assert(!tick(now+2200000000));
    chordPacket.hands[0].pinch = 1; assert(!tick(now+2300000000));
    chordPacket.flags &= ~DROIDDECK_XR_INPUT_FOCUSED;
    assert(!tick(now+3400000000));
    chordPacket.flags |= DROIDDECK_XR_INPUT_FOCUSED;
    assert(!tick(now+4500000000));

    DroidDeckXrSkeleton skeleton;
    skeleton.session_generation = packet.session_generation; skeleton.origin_epoch = packet.origin_epoch;
    skeleton.sample_monotonic_ns = now; skeleton.active[0] = 1;
    for (int j=0; j<26; ++j) {
        skeleton.joints[0][j].flags = 15;
        skeleton.joints[0][j].orientation[3] = 1;
        skeleton.joints[0][j].position[0] = .002f*j;
        skeleton.joints[0][j].position[2] = -.01f*j;
    }
    assert(skeletonFresh(skeleton, packet, 0, now));
    assert(!skeletonFresh(skeleton, packet, 1, now));
    assert(!skeletonFresh(skeleton, packet, 0, now+100000001));
    ++skeleton.origin_epoch; assert(!skeletonFresh(skeleton, packet, 0, now)); --skeleton.origin_epoch;
    skeleton.joints[0][7].orientation[0] = std::numeric_limits<float>::quiet_NaN();
    assert(!skeletonFresh(skeleton, packet, 0, now)); skeleton.joints[0][7].orientation[0] = 0;
    for (bool right : {false, true}) {
        vr::VRBoneTransform_t bones[31];
        steamVrSkeleton(skeleton.joints[0], packet.hands[0].grip, right, bones);
        droiddeck::Quat worldQ[31]; droiddeck::Vec3 worldP[31];
        worldQ[0] = {}; worldP[0] = {};
        for (int b=1; b<31; ++b) {
            const bool base = b==2 || b==6 || b==11 || b==16 || b==21;
            const int parent = b>=26 || b==1 ? 0 : base ? 1 : b-1;
            const auto &v = bones[b].position.v; const auto &q = bones[b].orientation;
            worldP[b] = worldP[parent] + droiddeck::Rotate(worldQ[parent], {v[0],v[1],v[2]});
            worldQ[b] = worldQ[parent] * droiddeck::Quat{q.w,q.x,q.y,q.z};
            const int distal[5] = {4,9,14,19,24}; const int j = b>=26 ? distal[b-26] : b;
            assert(std::abs(worldP[b].x-skeleton.joints[0][j].position[0]) < 1e-6);
            assert(std::abs(worldP[b].z-skeleton.joints[0][j].position[2]) < 1e-6);
        }
    }

    SteamVrHandBridge selectBridge;
    auto selectPacket = droiddeck_xr_input_v2_neutral(1020, 1);
    selectPacket.flags = DROIDDECK_XR_INPUT_HAND_MODE | DROIDDECK_XR_INPUT_FOCUSED;
    selectPacket.sample_monotonic_ns = now;
    auto &selection = selectPacket.hands[0];
    selection.grip.flags = selection.aim.flags = 15; selection.source = DROIDDECK_XR_SOURCE_RUNTIME_ACTION;
    selection.capability_bits = selection.ready_bits = 7; selection.status_bits = 31;
    selectBridge.update(selectPacket, 0, now); selectBridge.update(selectPacket, 0, now);
    selection.select = 1;
    assert(selectBridge.update(selectPacket, 0, now).triggerDown);
    selection.select = .4f; selection.pinch = 0;
    assert(selectBridge.update(selectPacket, 0, now).triggerDown);
    selection.select = 0;
    assert(!selectBridge.update(selectPacket, 0, now).triggerDown);
    selection.pinch = 1;
    assert(!selectBridge.update(selectPacket, 0, now).triggerDown);

    GamepadDashboardRequests gamepadMenu;
    auto gamepad = droiddeck_xr_input_v2_neutral(21, 1);
    gamepad.sample_monotonic_ns = now;
    gamepad.flags = DROIDDECK_XR_INPUT_FOCUSED | DROIDDECK_XR_INPUT_GAMEPAD_MENU;
    assert(!gamepadMenu.update(gamepad, now));
    gamepad.gamepad_menu_sequence++;
    assert(gamepadMenu.update(gamepad, now));
    assert(!gamepadMenu.update(gamepad, now));
    gamepad.flags = 0;
    gamepad.gamepad_menu_sequence++;
    assert(!gamepadMenu.update(gamepad, now));
    gamepad.flags = DROIDDECK_XR_INPUT_FOCUSED | DROIDDECK_XR_INPUT_GAMEPAD_MENU;
    assert(!gamepadMenu.update(gamepad, now));
    gamepad.gamepad_menu_sequence++;
    assert(gamepadMenu.update(gamepad, now));
    gamepad.session_generation++;
    gamepad.gamepad_menu_sequence++;
    assert(!gamepadMenu.update(gamepad, now));
    gamepad.origin_epoch++;
    gamepad.gamepad_menu_sequence++;
    assert(!gamepadMenu.update(gamepad, now));
    gamepad.gamepad_menu_sequence++;
    assert(!gamepadMenu.update(gamepad, now + 100000001));
    assert(!gamepadMenu.update(gamepad, now));
    gamepad.gamepad_menu_sequence++;
    assert(gamepadMenu.update(gamepad, now));
    gamepad.gamepad_menu_sequence = UINT32_MAX;
    assert(gamepadMenu.update(gamepad, now));
    gamepad.gamepad_menu_sequence = 0;
    assert(gamepadMenu.update(gamepad, now));

    const auto menuStart = handMenuButtons(now, now, true);
    assert(menuStart.suppress && !menuStart.system);
    const auto menuPressed = handMenuButtons(now, now+100000000, true);
    assert(menuPressed.suppress && menuPressed.system);
    const auto menuHeld = handMenuButtons(now, now+200000000, true);
    assert(menuHeld.suppress && !menuHeld.system);
    const auto menuReleased = handMenuButtons(now, now+200000000, false);
    assert(!menuReleased.suppress && !menuReleased.system);
    for (bool right : {false, true}) {
        droiddeck::HandModel model(right);
        droiddeck::Vec3 local[21];
        droiddeck::HandJoints(right, {}, local);
        const auto turn = droiddeck::AboutY(.7) * droiddeck::AboutX(-.4);
        const droiddeck::Vec3 translation{.4,1.2,-.7};
        DroidDeckXrPoseV2 joints[26]{};
        constexpr int map[21] = {1,2,3,4,5,7,8,9,10,12,13,14,15,17,18,19,20,22,23,24,25};
        for (int i=0; i<21; ++i) {
            const auto world = translation + droiddeck::Rotate(turn, local[i]);
            joints[map[i]] = {{float(world.x),float(world.y),float(world.z)}, {0,0,0,1}, 15};
        }
        DroidDeckXrPoseV2 fitted;
        assert(handModelPose(joints, model, fitted));
        assert(std::abs(fitted.position[0]-translation.x) < 1e-5);
        assert(std::abs(fitted.position[1]-translation.y) < 1e-5);
        const double dot = fitted.orientation[3]*turn.w + fitted.orientation[0]*turn.x +
                           fitted.orientation[1]*turn.y + fitted.orientation[2]*turn.z;
        assert(std::abs(std::abs(dot)-1) < 1e-5);
    }

    int releases = 0;
    { ScopeExit guard([&]{++releases;}); }
    { ScopeExit guard([&]{++releases;}); guard.dismiss(); }
    assert(releases == 1);
    assert(jsonString("a\"\n\\\t") == "\"a\\\"\\u000a\\\\\\u0009\"");

    char directory[] = "/tmp/droiddeck-bridge-XXXXXX"; assert(mkdtemp(directory));
    const std::string path = std::string(directory) + "/input";
    XrAtomicFile<DroidDeckXrInputV2> writer, rival, reader;
    assert(writer.openWriter(path)); assert(!rival.openWriter(path));
    packet = droiddeck_xr_input_v2_neutral(5, 1); packet.sample_monotonic_ns = xr_monotonic_ns();
    assert(writer.write(packet));
    DroidDeckXrInputV2 copy{}; assert(reader.read(path, copy) && copy.session_generation == 5);
    std::atomic<bool> done{false};
    std::thread producer([&] {
        for (uint64_t n=1;n<10000;++n) {
            packet.session_generation = n; packet.origin_epoch = n; packet.predicted_monotonic_ns = int64_t(n);
            assert(writer.write(packet));
        }
        done.store(true);
    });
    unsigned reads = 0;
    while (!done.load()) if (reader.read(path, copy) && copy.predicted_monotonic_ns) {
        assert(copy.session_generation == copy.origin_epoch && copy.origin_epoch == uint64_t(copy.predicted_monotonic_ns)); ++reads;
    }
    producer.join(); assert(reader.read(path, copy)); assert(copy.session_generation == 9999);
    const pid_t child = fork(); assert(child >= 0);
    if (!child) {
        XrAtomicFile<DroidDeckXrInputV2> processReader; DroidDeckXrInputV2 processCopy{};
        _exit(processReader.read(path, processCopy) && processCopy.session_generation == 9999 ? 0 : 1);
    }
    int result = 0; assert(waitpid(child, &result, 0) == child && WIFEXITED(result) && WEXITSTATUS(result) == 0);
    writer.close();
    const std::string replacement = std::string(directory) + "/replacement";
    assert(writer.openWriter(replacement)); packet.session_generation = 123; assert(writer.write(packet));
    assert(rename(replacement.c_str(), path.c_str()) == 0);
    assert(reader.read(path, copy) && copy.session_generation == 123);
    writer.close(); unlink(path.c_str());
    assert(symlink("/dev/null", path.c_str()) == 0);
    assert(!reader.read(path, copy) && !writer.openWriter(path));
    unlink(path.c_str());
    {
        MappedGuestXrAdapter adapter;
        assert(adapter.open(path, 51, 1));
        auto sample = droiddeck_xr_input_v2_neutral(51, 1);
        sample.sample_monotonic_ns = xr_monotonic_ns();
        sample.flags = DROIDDECK_XR_INPUT_FOCUSED;
        assert(adapter.publishInput(sample));
        assert(adapter.resetOrigin(2));
        assert(reader.read(path, copy) && copy.origin_epoch == 2 && copy.flags == 0);
        assert(!adapter.publishInput(sample));
        sample.origin_epoch = 2;
        assert(adapter.publishInput(sample));
        assert(!adapter.resetOrigin(0) && !adapter.resetOrigin(1) && !adapter.resetOrigin(2));
        adapter.releaseInput(51);
        assert(reader.read(path, copy) && copy.origin_epoch == 2 && copy.flags == 0);
    }
    unlink(path.c_str()); rmdir(directory);
    std::printf("XR bridge safety, transforms and atomic IPC passed (%u concurrent reads)\n", reads);
}
