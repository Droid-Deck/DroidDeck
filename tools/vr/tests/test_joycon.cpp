#include "arm_model.h"
#include "hand_skeleton.h"
#include "index_pose.h"
#include "joycon_shm.h"

#include <cstdio>
#include <cstdlib>

using namespace droiddeck;

static int failures = 0;

static void check(bool ok, const char *what) {
    if (ok) return;
    fprintf(stderr, "FAILED: %s\n", what);
    failures++;
}

static bool near(double a, double b, double tolerance = 1e-6) { return std::fabs(a - b) <= tolerance; }

static Vec3 Forward(const Quat &q) { return Rotate(q, {0.0, 0.0, -1.0}); }

static void restingHandsHangInFrontAtElbowHeight() {
    const Vec3 head{0.0, 1.6, 0.0};
    ArmModel right(true), left(false);
    const Vec3 r = right.Update(head, {}, {}, 0.0, 0.01);
    const Vec3 l = left.Update(head, {}, {}, 0.0, 0.01);
    check(near(r.x, 0.195, 1e-9) && near(l.x, -0.195, 1e-9), "rest hands sit 19.5 cm either side");
    check(near(r.y, 1.1, 1e-9) && near(l.y, 1.1, 1e-9), "rest hands hang 50 cm below the eyes");
    check(near(r.z, -0.305, 1e-9) && near(l.z, -0.305, 1e-9), "rest hands reach 30.5 cm forward");
}

static void raisingTheControllerExtendsTheArm() {
    const Vec3 head{0.0, 1.6, 0.0};
    ArmModel rest(true), raised(true);
    const Vec3 low = rest.Update(head, {}, {}, 0.0, 0.01);
    const Vec3 high = raised.Update(head, {}, AboutX(60.0 * M_PI / 180.0), 0.0, 0.01);
    check(high.y > low.y + 0.2, "pointing up lifts the hand");
    check(high.x < low.x, "an extended arm moves the elbow inward");
}

static void theTorsoOnlyFollowsTheHeadWhileTheControllerMoves() {
    const Vec3 head{0.0, 1.6, 0.0};
    ArmModel arm(true);
    arm.Update(head, {}, {}, 0.0, 0.01);
    const Quat lookLeft = AboutY(M_PI / 2);
    const Vec3 still = arm.Update(head, lookLeft, {}, 0.0, 0.5);
    check(near(still.x, 0.195, 1e-9), "a still controller keeps the torso where it was");
    for (int i = 0; i < 400; i++) arm.Update(head, lookLeft, lookLeft, 5.0, 0.01);
    const Vec3 turned = arm.Update(head, lookLeft, lookLeft, 0.0, 0.01);
    check(near(turned.z, -0.195, 1e-3) && near(turned.x, -0.305, 1e-3), "a moving controller lets the torso turn to the gaze");
    arm.Reset();
    const Vec3 snapped = arm.Update(head, {}, {}, 0.0, 0.01);
    check(near(snapped.x, 0.195, 1e-9), "a reset snaps the torso to the gaze");
}

static void headingAlignmentTurnsTheControllerToTheGaze() {
    double heading = 0.0;
    check(Heading(AboutY(0.7), &heading) && near(heading, 0.7), "heading of a yaw rotation");
    check(!Heading(AboutX(M_PI / 2), &heading), "a controller pointing straight up has no heading");
    const Quat hand = AboutY(-1.1) * AboutX(0.3);
    const Quat head = AboutY(0.4) * AboutX(-0.2);
    double headYaw = 0.0, handYaw = 0.0;
    check(Heading(head, &headYaw) && Heading(hand, &handYaw), "both headings exist");
    const Quat aligned = AboutY(headYaw - handYaw) * hand;
    check(Heading(aligned, &heading) && near(heading, 0.4), "the aligned controller faces where the head faces");
    check(near(Forward(aligned).y, Forward(hand).y), "alignment keeps the controller's pitch");
}

static void theIndexTipPointsWhereTheJoyConPoints() {
    for (bool right : {false, true}) {
        const Quat aim = AboutY(0.5) * AboutX(-0.3);
        const Vec3 grip{0.2, 1.1, -0.3};
        Quat raw;
        Vec3 position;
        IndexRawPose(right, aim, grip, &raw, &position);
        const Vec3 tip = Forward(raw * IndexTipRotation(right)), want = Forward(aim);
        check(near(tip.x, want.x) && near(tip.y, want.y) && near(tip.z, want.z), "the tip pose follows the Joy-Con");
        const Vec3 gripAt = position + Rotate(raw, {0.0, -0.015, 0.13});
        check(near(gripAt.x, grip.x) && near(gripAt.y, grip.y) && near(gripAt.z, grip.z), "the Index grip sits at the arm model's hand");
        const Vec3 handle = Forward(raw);
        check(handle.y > Forward(aim).y + 0.4, "the raw handle tilts up from the pointing direction");
    }
}

static void skeletonsAreFiniteMirroredAndNormalised() {
    vr::VRBoneTransform_t left[kHandBones], right[kHandBones];
    for (double curl : {0.0, 0.5, 1.0}) {
        FingerCurls curls;
        curls.thumb = curls.index = curls.middle = curls.ring = curls.pinky = curl;
        HandSkeleton(false, curls, left);
        HandSkeleton(true, curls, right);
        for (uint32_t b = 0; b < kHandBones; b++) {
            for (const auto *bones : {left, right}) {
                const auto &q = bones[b].orientation;
                check(near(std::sqrt(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z), 1.0, 1e-3), "bone orientations are unit quaternions");
            }
            for (int i = 0; i < 3; i++) check(std::isfinite(left[b].position.v[i]) && std::isfinite(right[b].position.v[i]), "bone positions are finite");
            check(left[b].position.v[3] == 1.0f && right[b].position.v[3] == 1.0f, "bone positions are points");
        }
        check(near(left[1].position.v[0], -right[1].position.v[0]), "the wrist is mirrored between hands");
        check(near(left[7].position.v[0], -right[7].position.v[0]), "finger bones are mirrored between hands");
    }
    FingerCurls open, fist;
    fist.index = 1.0;
    HandSkeleton(false, open, left);
    HandSkeleton(false, fist, right);
    const auto reach = [](const vr::VRBoneTransform_t *bones) {
        return std::hypot(bones[27].position.v[0] - bones[1].position.v[0], bones[27].position.v[1] - bones[1].position.v[1],
                          bones[27].position.v[2] - bones[1].position.v[2]);
    };
    check(reach(right) < reach(left) - 0.05, "a curled index finger pulls its tip towards the wrist");
}

static void sharedLayoutMatchesTheAppWriter() {
    check(offsetof(droiddeck_joycon, seq) == 8, "sequence offset");
    check(offsetof(droiddeck_joycon_hand, buttons) == 4, "buttons offset");
    check(offsetof(droiddeck_joycon_hand, rotation) == 8, "rotation offset");
    check(offsetof(droiddeck_joycon_hand, angular_velocity) == 24, "angular velocity offset");
    check(offsetof(droiddeck_joycon_hand, stick) == 36, "stick offset");
    check(offsetof(droiddeck_joycon_hand, battery) == 44, "battery offset");
    check(offsetof(droiddeck_joycon_hand, recenter) == 48, "recenter offset");
    check(DROIDDECK_JOYCON_TRIGGER == 1 && DROIDDECK_JOYCON_GRIP == 2 && DROIDDECK_JOYCON_A == 4 &&
              DROIDDECK_JOYCON_B == 8 && DROIDDECK_JOYCON_STICK == 16 && DROIDDECK_JOYCON_SYSTEM == 32,
          "button bits match JoyCon.kt");
}

int main() {
    restingHandsHangInFrontAtElbowHeight();
    raisingTheControllerExtendsTheArm();
    theTorsoOnlyFollowsTheHeadWhileTheControllerMoves();
    headingAlignmentTurnsTheControllerToTheGaze();
    theIndexTipPointsWhereTheJoyConPoints();
    skeletonsAreFiniteMirroredAndNormalised();
    sharedLayoutMatchesTheAppWriter();
    if (failures) {
        fprintf(stderr, "%d Joy-Con check(s) failed\n", failures);
        return 1;
    }
    printf("Joy-Con arm model, pose and layout checks passed\n");
    return 0;
}
