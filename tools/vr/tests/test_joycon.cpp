#include "arm_model.h"
#include "hand_motion.h"
#include "hand_mesh.h"
#include "hand_pose.h"
#include "hand_skeleton.h"
#include "index_pose.h"
#include "handcam_shm.h"
#include "joycon_shm.h"
#include "luma_joycon.h"

#include <cstdio>
#include <cstdlib>
#include <map>

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

static void measuredMovementCarriesTheHandAndTheAnchorPullsItBack() {
    HandFollower hand;
    const Vec3 anchor{0.2, 1.1, -0.3};
    const Vec3 start = hand.Update(anchor, {5.0, 5.0, 5.0}, 0.01, 0.6, 0.35);
    check(near(start.x, anchor.x) && near(start.y, anchor.y) && near(start.z, anchor.z), "the hand starts at the anchor");
    const Vec3 lifted = hand.Update(anchor, {5.0, 5.25, 5.0}, 0.0, 0.6, 0.35);
    check(near(lifted.y, 1.35) && near(lifted.x, 0.2) && near(lifted.z, -0.3), "a measured 25 cm lift raises the hand 25 cm");
    Vec3 held = lifted;
    for (int i = 0; i < 60; i++) held = hand.Update(anchor, {5.0, 5.25, 5.0}, 0.01, 0.6, 0.35);
    check(near(held.y, 1.1 + 0.25 * std::exp(-1.0), 1e-3), "a held hand settles back over the anchor's time constant");
    hand.Update(anchor, {5.0, 6.25, 5.0}, 0.0, 0.6, 0.35);
    const Vec3 far = hand.Update(anchor, {5.0, 6.25, 5.0}, 0.0, 0.6, 0.35);
    check(near(Length(far - anchor), 0.35, 1e-9), "the hand stays within reach of the anchor");
    hand.Reset();
    const Vec3 reset = hand.Update({0.0, 1.0, 0.0}, {9.0, 9.0, 9.0}, 0.01, 0.6, 0.35);
    check(near(reset.x, 0.0) && near(reset.y, 1.0) && near(reset.z, 0.0), "a reset puts the hand back on the anchor");
    const Vec3 after = hand.Update({0.0, 1.0, 0.0}, {9.0, 9.0, 8.9}, 0.0, 0.6, 0.35);
    check(near(after.z, -0.1), "movement after a reset counts from the reset");
}

static void feedJoyCon(HandKalman &k, double from, double to, double (*pos)(double), double bias, double turn) {
    const double dt = 0.01;
    for (double t = from + dt; t < to + 1e-9; t += dt) {
        const double v = (pos(t + 1e-4) - pos(t - 1e-4)) / 2e-4;
        k.Input(int64_t(t * 1e9), {0.0, v + bias * t, 0.0}, turn);
    }
}

static double wave(double t) { return 1.0 + 0.2 * std::sin(2.0 * M_PI * 0.7 * t); }

static void theFilterPlacesTheHandWhereItIsNowNotWhenThePictureWasTaken() {
    HandKalman k;
    const int64_t ms = 1000000;
    k.Input(0, {0.0, 0.0, 0.0}, 1.0);
    check(!k.Measure(-5 * ms, {0.0, 1.0, 0.0}, 0.02), "a sighting from before the Joy-Con started is skipped");
    double t = 0.0;
    double worst = 0.0, squares = 0.0, pictureSquares = 0.0;
    int counted = 0;
    unsigned noise = 12345;
    for (int frame = 0; frame < 150; frame++) {
        feedJoyCon(k, t, t + 1.0 / 15.0, wave, 0.3, 1.0);
        t += 1.0 / 15.0;
        const double seen = t - 0.1;
        noise = noise * 1103515245u + 12345u;
        const double jitter = (int((noise >> 16) % 2001) - 1000) / 1000.0 * 0.02;
        k.Measure(int64_t(seen * 1e9), {0.3, wave(seen) + jitter, -0.4}, 0.03);
        if (frame > 45) {
            const double error = k.Position().y - wave(t);
            const double picture = wave(seen) + jitter - wave(t);
            worst = std::max(worst, std::fabs(error));
            squares += error * error;
            pictureSquares += picture * picture;
            counted++;
        }
    }
    check(k.Ready(), "the filter runs once the camera has seen the hand");
    check(std::sqrt(squares / counted) < 0.55 * std::sqrt(pictureSquares / counted) && worst < 0.07,
          "a waving hand is shown nearer where it is now than the 0.1 s late, noisy picture, despite a drifting Joy-Con");
    check(near(k.Position().x, 0.3, 0.01) && near(k.Position().z, -0.4, 0.01), "still axes stay on the camera");
}

static void aLateSightingCorrectsThePastAndKeepsTheMoveSince() {
    HandKalman k;
    const int64_t ms = 1000000;
    for (int i = 0; i <= 20; i++) k.Input(i * 10 * ms, {0.0, 0.0, 0.0}, 1.0);
    check(k.Measure(200 * ms, {0.0, 1.0, 0.0}, 0.01), "the first sighting starts the filter");
    for (int i = 0; i < 30; i++) k.Measure((200 + i) * ms, {0.0, 1.0, 0.0}, 0.01);
    for (int i = 21; i <= 30; i++) k.Input(i * 10 * ms, {0.0, 1.0, 0.0}, 1.0);
    const double moved = k.Position().y - 1.0;
    check(moved > 0.08, "a quick Joy-Con lift moves the hand at once");
    k.Measure(205 * ms, {0.0, 1.0, 0.0}, 0.01);
    check(near(k.Position().y - 1.0, moved, 0.03), "a late sighting from before the lift does not undo it");
}

static void aWildSightingIsIgnoredUntilItRepeats() {
    HandKalman k;
    const int64_t ms = 1000000;
    for (int i = 0; i <= 60; i++) {
        k.Input(i * 10 * ms, {0.0, 0.0, 0.0}, 1.0);
        if (i % 7 == 0) k.Measure(i * 10 * ms, {0.2, 1.1, -0.3}, 0.02);
    }
    check(!k.Measure(600 * ms, {-0.4, 1.1, -0.3}, 0.02), "one sighting far from the hand is ignored");
    check(!k.Measure(600 * ms, {-0.4, 1.1, -0.3}, 0.02), "a second one is ignored too");
    check(near(k.Position().x, 0.2, 0.02), "the hand stays where it was");
    check(k.Measure(600 * ms, {-0.4, 1.1, -0.3}, 0.02) && near(k.Position().x, -0.4, 0.03), "a third sighting in a row moves the hand there");
    k.Input(2000 * ms, {0.0, 0.0, 0.0}, 1.0);
    check(!k.Ready(), "a long gap in Joy-Con reports starts over");
}

static double slide(double t) { return t < 1.0 ? 0.5 * t : 0.5; }
static double flat(double) { return 0.0; }

static double lostAfterSlide(double turn, double seconds, double *speed) {
    HandKalman k;
    k.Input(0, {0.0, 0.0, 0.0}, 1.0);
    double t = 0.0;
    while (t < 1.0 - 1e-9) {
        feedJoyCon(k, t, t + 1.0 / 15.0, flat, 0.0, 1.0);
        t += 1.0 / 15.0;
        k.Measure(int64_t(t * 1e9), {slide(t), 1.0, -0.4}, 0.03);
    }
    feedJoyCon(k, t, t + seconds, flat, 0.0, turn);
    *speed = Length(k.Velocity());
    return k.Position().x - slide(t);
}

static void aHandTheCameraLosesSettlesInsteadOfRunningAway() {
    double speed = 0.0;
    const double moving = lostAfterSlide(1.0, 2.0, &speed);
    check(moving > 0.0 && moving < 0.1 && speed < 0.01,
          "a hand the Joy-Con never felt moving settles within 10 cm of its last sighting instead of sliding on");
    const double still = lostAfterSlide(0.0, 0.5, &speed);
    check(still > -0.01 && still < 0.06 && speed < 0.01, "a still Joy-Con stops the hand within a few cm");
}

static void posed(const HandModel &model, const FingerCurls &curls, const Quat &turn, const Vec3 &at, Vec3 out[kHandJoints]) {
    Vec3 joints[kHandJoints];
    HandJoints(model.Right(), curls, joints);
    for (int i = 0; i < kHandJoints; i++) out[i] = at + Rotate(turn, joints[i] - model.PalmCenter());
}

static void cameraFingerCurlsReadBackFromTheJoints() {
    for (bool right : {false, true}) {
        const HandModel model(right);
        for (double c : {0.0, 0.2, 0.5, 0.8, 1.0}) {
            FingerCurls curls;
            curls.thumb = c;
            curls.index = 1.0 - c;
            curls.middle = curls.ring = curls.pinky = c;
            Vec3 joints[kHandJoints];
            posed(model, curls, Normalize(AboutY(0.7) * AboutX(-0.4)), {0.1, 1.2, -0.4}, joints);
            const FingerCurls read = model.Curls(joints);
            check(near(read.thumb, curls.thumb, 0.02) && near(read.index, curls.index, 0.02) && near(read.middle, c, 0.02) &&
                      near(read.ring, c, 0.02) && near(read.pinky, c, 0.02),
                  "finger curls read from camera joints match the curls that made them");
        }
    }
}

static void thePalmFitFindsTheHandsTurnAndPlace() {
    for (bool right : {false, true}) {
        const HandModel model(right);
        const Quat turn = Normalize(AboutY(-2.1) * AboutX(0.9) * Quat{0.9, 0.0, 0.0, 0.43});
        FingerCurls curls;
        curls.index = 0.6;
        curls.middle = curls.ring = curls.pinky = 0.9;
        Vec3 joints[kHandJoints];
        posed(model, curls, turn, {-0.2, 1.1, -0.35}, joints);
        Quat fitted;
        Vec3 center;
        double rms = 1.0;
        check(model.Fit(joints, model.Curls(joints), &fitted, &center, &rms), "the palm fit converges");
        check(AngleDegrees(Conjugate(turn) * fitted) < 0.5 && rms < 0.001, "the palm fit recovers the hand's turn even with bent fingers");
        Vec3 shape[kHandJoints];
        HandJoints(right, curls, shape);
        check(Length(Rotate(fitted, shape[hand::kIndexTip] - center) - (joints[hand::kIndexTip] - (joints[0] + joints[5] + joints[9] + joints[13] + joints[17]) * 0.2)) < 0.002,
              "the fitted skeleton puts the fingertips where the camera saw them");
        unsigned noise = 99;
        for (int i = 0; i < kHandJoints; i++) {
            noise = noise * 1103515245u + 12345u;
            const double n = (int((noise >> 16) % 2001) - 1000) / 1000.0 * 0.004;
            joints[i] = joints[i] + Vec3{n, -n, n * 0.5};
        }
        check(model.Fit(joints, model.Curls(joints), &fitted, &center, &rms) && AngleDegrees(Conjugate(turn) * fitted) < 6.0,
              "4 mm of landmark noise moves the fit a few degrees");
    }
}

static void thePalmNormalPointsOutOfThePalm() {
    for (bool right : {false, true}) {
        const HandModel model(right);
        Vec3 open[kHandJoints], fist[kHandJoints];
        FingerCurls closed;
        closed.index = closed.middle = closed.ring = closed.pinky = 1.0;
        posed(model, FingerCurls{}, Quat{}, {}, open);
        posed(model, closed, Quat{}, {}, fist);
        const Vec3 normal = model.PalmNormal(open);
        check(near(Length(normal), 1.0, 1e-6) && Dot(normal, fist[hand::kIndexTip] - open[hand::kIndexTip]) > 0.0,
              "the palm normal points the way the fingers close");
    }
}

static void pinchesAndFistsBecomeButtons() {
    const HandModel model(true);
    HandGestures gestures(true);
    const int64_t ms = 1000000;
    Vec3 joints[kHandJoints];
    posed(model, FingerCurls{}, Quat{}, {0.2, 1.2, -0.4}, joints);
    const Vec3 palm{0.2, 1.2, -0.4};
    const Vec3 normal = model.PalmNormal(joints);
    const Vec3 away = palm - normal * 0.5;
    HandButtons b = gestures.Update(model, joints, model.Curls(joints), palm, away, 0);
    check(!b.triggerClick && !b.gripClick && !b.system && b.trigger == 0.0f, "an open hand presses nothing");
    joints[hand::kThumbTip] = joints[hand::kIndexTip] + Vec3{0.01, 0.0, 0.0};
    b = gestures.Update(model, joints, model.Curls(joints), palm, away, 10 * ms);
    check(!b.triggerClick && b.trigger > 0.9f, "a pinch pulls the trigger at once but clicks only after it holds");
    b = gestures.Update(model, joints, model.Curls(joints), palm, away, 70 * ms);
    check(b.triggerClick && !b.system, "a held pinch clicks the trigger");
    joints[hand::kThumbTip] = joints[hand::kIndexTip] + Vec3{0.04, 0.0, 0.0};
    b = gestures.Update(model, joints, model.Curls(joints), palm, away, 90 * ms);
    check(b.triggerClick, "a pinch that loosens a little stays clicked");
    joints[hand::kThumbTip] = joints[hand::kIndexTip] + Vec3{0.08, 0.0, 0.0};
    gestures.Update(model, joints, model.Curls(joints), palm, away, 100 * ms);
    b = gestures.Update(model, joints, model.Curls(joints), palm, away, 210 * ms);
    check(!b.triggerClick && b.trigger == 0.0f, "opening the pinch releases the trigger");
    joints[hand::kThumbTip] = joints[hand::kIndexTip] + Vec3{0.0, 0.03, 0.0};
    gestures.Update(model, joints, model.Curls(joints), palm, away, 300 * ms);
    b = gestures.Update(model, joints, model.Curls(joints), palm, away, 360 * ms);
    check(b.triggerClick && b.trigger == 1.0f, "fingertips the camera reads 3 cm apart still pinch");

    HandGestures fist(true);
    FingerCurls closed;
    closed.middle = closed.ring = closed.pinky = 1.0;
    posed(model, closed, Quat{}, palm, joints);
    fist.Update(model, joints, model.Curls(joints), palm, away, 0);
    b = fist.Update(model, joints, model.Curls(joints), palm, away, 60 * ms);
    check(b.gripClick && b.grip == 1.0f && !b.a && !b.b, "a fist grips without pressing A or B");
}

static void aPalmTurnedToTheFaceOpensTheMenus() {
    const HandModel rightModel(true), leftModel(false);
    HandGestures right(true), left(false);
    const int64_t ms = 1000000;
    const Vec3 palm{0.0, 1.3, -0.35};
    for (int side = 0; side < 2; side++) {
        const HandModel &model = side ? rightModel : leftModel;
        HandGestures &gestures = side ? right : left;
        Vec3 joints[kHandJoints];
        posed(model, FingerCurls{}, Quat{}, palm, joints);
        const Vec3 face = palm + model.PalmNormal(joints) * 0.4;
        HandButtons b = gestures.Update(model, joints, model.Curls(joints), palm, face, 0);
        check(!b.facing, "a palm must face the headset for a moment before it counts");
        joints[hand::kThumbTip] = joints[hand::kIndexTip];
        gestures.Update(model, joints, model.Curls(joints), palm, face, 310 * ms);
        b = gestures.Update(model, joints, model.Curls(joints), palm, face, 380 * ms);
        check(b.facing && !b.triggerClick && b.trigger == 0.0f, "a pinch with the palm facing the headset is not a trigger pull");
        check(side ? (b.system && !b.b) : (b.b && !b.system),
              side ? "a right palm pinch presses the system button (SteamVR menu)" : "a left palm pinch presses B (game menu)");
    }
}

static void aBareHandFollowsTheCameraAndHidesWhenLost() {
    const int64_t ms = 1000000;
    const Vec3 head{0.0, 1.6, 0.0};
    for (int side = 0; side < 2; side++) {
        CameraHand hand(side == 1);
        const Quat turn = Normalize(AboutY(side ? -0.4 : 0.4) * AboutX(-0.3));
        FingerCurls curls;
        curls.index = 0.2;
        curls.middle = curls.ring = curls.pinky = 0.4;
        Vec3 shape[kHandJoints], mean, seen[kHandJoints];
        HandJoints(side == 1, curls, shape);
        for (int i : hand::kPalm) mean = mean + shape[i] * 0.2;
        for (int i = 0; i < kHandJoints; i++) seen[i] = Rotate(turn, shape[i] - mean);
        Vec3 palm{side ? 0.15 : -0.15, 1.2, -0.4};
        CameraHandPose pose;
        int64_t t = 0;
        const auto run = [&](int64_t until, bool camera) {
            for (; t <= until; t += 10 * ms) {
                if (camera && t % (70 * ms) == 0) hand.See(t - 90 * ms, palm, seen, head, t);
                pose = hand.Update(t, 0.01);
            }
        };
        run(90 * ms, false);
        check(!pose.visible, "a camera hand stays hidden until the camera sees it");
        run(1000 * ms, true);
        check(pose.visible && AngleDegrees(Conjugate(turn) * pose.rotation) < 1.0, "a camera hand turns the way the camera saw it");
        check(Length(pose.position - (palm - Rotate(turn, mean))) < 0.01, "a camera hand's skeleton root sits so its palm lands on the camera's palm");
        check(std::fabs(pose.curls.index - 0.2) < 0.05 && std::fabs(pose.curls.ring - 0.4) < 0.05, "a camera hand bends its fingers as seen");
        check(!pose.buttons.triggerClick, "a loose hand pulls nothing");
        seen[hand::kThumbTip] = seen[hand::kIndexTip];
        run(1200 * ms, true);
        check(pose.buttons.triggerClick, "a pinch the camera sees clicks the trigger");
        run(1800 * ms, false);
        check(!pose.visible && !pose.buttons.triggerClick, "a hand the camera loses hides and lets go");
        palm = {side ? -0.1 : 0.1, 1.45, -0.3};
        run(2400 * ms, true);
        check(pose.visible && Length(pose.position - (palm - Rotate(turn, mean))) < 0.01, "a hand that comes back somewhere else shows up there");
    }
}

static void theTurnSmootherSettlesAndKeepsUpWithFastTurns() {
    SmoothTurn slow, fast;
    Quat out = slow.Update(Quat{}, 0.01);
    const Quat jitter = AboutX(4.0 * M_PI / 180.0);
    double worst = 0.0;
    for (int i = 0; i < 300; i++) {
        out = slow.Update((i / 7) % 2 ? jitter : Conjugate(jitter), 0.01);
        if (i > 100) worst = std::max(worst, AngleDegrees(out));
    }
    check(worst < 2.5, "frame-to-frame wobble of a still hand is damped");
    fast.Update(Quat{}, 0.01);
    for (int i = 1; i <= 50; i++) out = fast.Update(AboutY(3.0 * i * 0.01), 0.01);
    check(AngleDegrees(Conjugate(AboutY(1.5)) * out) < 25.0, "a fast turn is followed closely");
}

static void theHandRenderModelIsAClosedOutwardFacingHand() {
    for (int right = 0; right < 2; right++) {
        for (HandPart part : {HandPart::Hand, HandPart::ThumbIndex}) {
            for (int closed = 0; closed < 2; closed++) {
                const HandMesh m = BuildHandPart(right, part, closed);
                bool finite = !m.faces.empty() && m.points.size() < 65000, cuff = false;
                size_t inward = 0;
                for (const Vec3 &p : m.points)
                    finite = finite && std::isfinite(p.x) && std::isfinite(p.y) && std::isfinite(p.z) && Length(p) < 0.3;
                for (double tint : m.tints) cuff = cuff || tint > 0.99;
                std::map<std::pair<int, int>, int> edges;
                for (const auto &f : m.faces) {
                    const Vec3 n = Cross(m.points[f[1]] - m.points[f[0]], m.points[f[2]] - m.points[f[0]]);
                    if (Dot(n, m.normals[f[0]] + m.normals[f[1]] + m.normals[f[2]]) < 0.0) inward++;
                    for (int e = 0; e < 3; e++) edges[{f[e], f[(e + 1) % 3]}]++;
                }
                bool sealed = true;
                for (const auto &entry : edges) sealed = sealed && edges.count({entry.first.second, entry.first.first}) == 1;
                check(finite, "hand model parts are small, finite meshes SteamVR can load");
                check(inward * 1000 < m.faces.size(), "hand model faces point out");
                check(sealed, "hand model parts are watertight");
                check(cuff, "the glove has a cuff at the wrist");
            }
        }
        Vec3 pinch[kHandJoints];
        HandShape(right, true, false, pinch);
        check(Length(pinch[hand::kThumbTip] - pinch[hand::kIndexTip]) < 0.015, "the pinch model's thumb meets the index finger");
        Vec3 open[kHandJoints];
        HandShape(right, false, false, open);
        check((open[hand::kThumbTip].x > 0.0) == !right, "the hand models are mirrored for the left and right hands");
        const std::string json = HandModelJson(right);
        for (const char *name : {"hand_open.obj", "hand_fist.obj", "thumb_index_open.obj", "thumb_index_pinch.obj", "\"tip\"", "\"base\"",
                                 "\"handgrip\"", "\"pressed_path\": \"/input/trigger/click\"", "\"pressed_path\": \"/input/grip/touch\"",
                                 "\"controller_button\": 33", "\"press\": true"})
            check(json.find(name) != std::string::npos, "the hand model lists its parts, their inputs and the pointer poses");
    }
    const std::vector<uint8_t> png = GlovePng(64, {246, 246, 249}, {224, 227, 233}, glove::kDarkest);
    check(png.size() > 40 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G', "the hand texture is a PNG");
}

static void sharedLayoutMatchesTheAppWriter() {
    check(offsetof(droiddeck_joycon, seq) == 8, "sequence offset");
    check(offsetof(droiddeck_joycon_hand, buttons) == 4, "buttons offset");
    check(offsetof(droiddeck_joycon_hand, rotation) == 8, "rotation offset");
    check(offsetof(droiddeck_joycon_hand, angular_velocity) == 24, "angular velocity offset");
    check(offsetof(droiddeck_joycon_hand, stick) == 36, "stick offset");
    check(offsetof(droiddeck_joycon_hand, battery) == 44, "battery offset");
    check(offsetof(droiddeck_joycon_hand, recenter) == 48, "recenter offset");
    check(offsetof(droiddeck_joycon_hand, velocity) == 76, "velocity offset");
    check(offsetof(droiddeck_joycon_hand, inertial_velocity) == 88, "inertial velocity offset");
    check(sizeof(droiddeck_joycon) == 224, "file size");
    check(DROIDDECK_JOYCON_VERSION == 3, "file version");
    check(DROIDDECK_JOYCON_TRIGGER == 1 && DROIDDECK_JOYCON_GRIP == 2 && DROIDDECK_JOYCON_A == 4 &&
              DROIDDECK_JOYCON_B == 8 && DROIDDECK_JOYCON_STICK == 16 && DROIDDECK_JOYCON_SYSTEM == 32,
          "button bits match JoyCon.kt");
}

static void historyInterpolatesBetweenSamples() {
    TimeHistory history;
    Vec3 out;
    check(!history.At(0, &out), "an empty history has nothing to give");
    history.Add(1000, {0.0, 0.0, 0.0});
    history.Add(11000, {0.1, 0.0, -0.2});
    history.Add(5000, {9.0, 9.0, 9.0});
    check(history.At(6000, &out) && near(out.x, 0.05) && near(out.z, -0.1), "a time between samples is interpolated");
    check(history.At(50000, &out) && near(out.x, 0.1), "a time after the newest sample uses the newest");
    check(history.At(0, &out) && near(out.x, 0.0), "a time before the oldest sample uses the oldest");
    history.Add(11000, {0.2, 0.0, 0.0});
    check(history.At(11000, &out) && near(out.x, 0.2), "a repeated time replaces its sample");
    for (int i = 0; i < 1000; i++) history.Add(20000 + i * 1000, {double(i), 0.0, 0.0});
    check(history.At(0, &out) && out.x > 700.0, "only the newest samples are kept");
}

static void theCameraYawAveragesFaceReadingsAndFollowsARecenter() {
    CameraYaw yaw;
    double out = 0.0;
    for (int i = 0; i < 3; i++) yaw.Add(0.5 + (i % 2 ? 0.1 : -0.1), 1.0 / 15.0);
    check(!yaw.Get(&out), "a few readings are not enough");
    for (int i = 3; i < 30; i++) yaw.Add(0.5 + (i % 2 ? 0.1 : -0.1), 1.0 / 15.0);
    check(yaw.Get(&out) && near(out, 0.5, 0.02), "noisy readings average out");
    for (int i = 0; i < 7; i++) yaw.Add(1.7, 1.0 / 15.0);
    check(yaw.Get(&out) && near(out, 0.5, 0.02), "a short run of far readings is ignored");
    for (int i = 0; i < 6; i++) yaw.Add(1.7, 1.0 / 15.0);
    check(yaw.Get(&out) && near(out, 1.7, 1e-6), "a lasting change starts a new average");
    CameraYaw wrapped;
    for (int i = 0; i < 20; i++) wrapped.Add(i % 2 ? M_PI - 0.05 : -M_PI + 0.05, 1.0 / 15.0);
    check(wrapped.Get(&out) && near(std::fabs(out), M_PI, 0.01), "readings either side of half a turn average to half a turn");
}

static void handCameraLayoutMatchesTheAppWriter() {
    check(offsetof(droiddeck_handcam, seq) == 8, "camera sequence offset");
    check(offsetof(droiddeck_handcam, flags) == 12, "camera flags offset");
    check(offsetof(droiddeck_handcam, frame) == 24, "camera frame offset");
    check(offsetof(droiddeck_handcam, face_yaw) == 28, "camera face yaw offset");
    check(offsetof(droiddeck_handcam_hand, position) == 4, "camera hand position offset");
    check(offsetof(droiddeck_handcam, hands) + sizeof(droiddeck_handcam_hand) + offsetof(droiddeck_handcam_hand, joints) == 316,
          "second hand's joints offset");
    check(DROIDDECK_HANDCAM_VERSION == 2 && DROIDDECK_HANDCAM_RUNNING == 1 && DROIDDECK_HANDCAM_FACE == 2 &&
              DROIDDECK_HANDCAM_BARE_HANDS == 4 && DROIDDECK_HANDCAM_SEEN == 1 && DROIDDECK_HANDCAM_ESTIMATED == 2 &&
              DROIDDECK_HANDCAM_JOINTS == 4 && DROIDDECK_HANDCAM_JOINT_COUNT == kHandJoints,
          "camera flags match HandCameraShm.kt");
}

static void lumaStereoFallsBackInTheHeadTrackingOrigin() {
    int64_t now = 1000000000;
    droiddeck_spatialpose s{};
    s.flags = DD_SPATIAL_ACTIVE | DD_SPATIAL_JOYCONS;
    s.connection = s.origin = 1;
    s.head = {now, {2, 1.7f, 3}, {0, 0, 0, 1}, DD_SPATIAL_VALID};
    s.hands[0].pose = {now, {1.7f, 1.3f, 2.6f}, {0, 0, 0, 1}, DD_SPATIAL_VALID};
    s.hands[1].pose = {now, {2.3f, 1.3f, 2.6f}, {0, 0, 0, 1}, DD_SPATIAL_VALID};
    LumaJoyConPosition left(false), right(true);
    auto l = left.Update(s, {}, 0, now, .01);
    auto r = right.Update(s, {}, 0, now, .01);
    check(l.valid && l.camera && r.camera && near(l.position.x, 1.7) && near(r.position.x, 2.3),
          "stereo positions retain their world coordinates and handedness");
    s.head.position[0] += .1f;
    l = left.Update(s, {}, 0, now, .01);
    check(near(l.position.x, 1.7), "moving the head does not move a world-space stereo observation");
    now += 200000001;
    s.head.time_ns = now;
    l = left.Update(s, {}, 0, now, .01);
    check(l.valid && !l.camera && Length(l.position - Vec3{1.7, 1.3, 2.6}) < .04,
          "stale stereo falls back with a continuous transition");
    for (int i=0; i<100; ++i) l = left.Update(s, {}, 0, now, .01);
    ArmModel expected(false);
    const auto anchor = expected.Update({2.1, 1.7, 3}, {}, {}, 0, .01);
    check(Length(l.position - anchor) < .003, "fallback settles to the translated 6DoF head anchor");
    s.hands[0].pose.time_ns = now;
    auto reacquired = left.Update(s, {}, 0, now, .01);
    check(reacquired.camera && Length(reacquired.position - l.position) < .04, "reacquisition blends without a snap");
    s.origin++;
    s.head.position[0] = 0;
    s.hands[0].pose = {};
    l = left.Update(s, {}, 0, now, .01);
    check(l.valid && !l.camera && near(l.position.x, -.195), "origin changes discard the old world position and blend");
    s.head.time_ns = now - 100000001;
    check(!left.Update(s, {}, 0, now, .01).valid, "head loss invalidates instead of guessing a world origin");
    s.head.time_ns = now;
    s.hands[0].pose = {now + 1, {0, 1.3f, 3}, {0,0,0,1}, DD_SPATIAL_VALID};
    check(!left.Update(s, {}, 0, now, .01).camera, "future camera observations are rejected");
    s.hands[0].pose.time_ns = now;
    s.hands[0].pose.position[0] = 99;
    check(!left.Update(s, {}, 0, now, .01).camera, "implausible hand positions use the arm model");
    s.flags = DD_SPATIAL_ACTIVE;
    check(!left.Update(s, {}, 0, now, .01).valid, "the experimental flag is required");
}

static void lumaReachSurvivesBriefDetectionMissesWithoutRefreshingOldDepth() {
    int64_t now = 1000000000;
    droiddeck_spatialpose s{};
    s.flags = DD_SPATIAL_ACTIVE | DD_SPATIAL_JOYCONS;
    s.connection = s.origin = 1;
    s.head = {now, {0, 1.7f, 0}, {0,0,0,1}, DD_SPATIAL_VALID};
    s.hands[0].pose = {now, {-.2f, 1.3f, -.3f}, {0,0,0,1}, DD_SPATIAL_VALID};
    LumaJoyConPosition tracker(false);
    auto pose = tracker.Update(s, {}, 0, now, .01);
    s.hands[0] = {};
    for (int i=0; i<19; ++i) {
        now += 10000000; s.head.time_ns = now;
        pose = tracker.Update(s, {}, 0, now, .01);
        check(pose.camera && near(pose.position.z, -.3), "brief misses retain the last measured reach");
    }
    now += 20000000; s.head.time_ns = now;
    check(!tracker.Update(s, {}, 0, now, .02).camera, "held depth expires at its original capture timestamp");
    for (int i=0; i<100; ++i) {
        now += 10000000; s.head.time_ns = now;
        if (i % 16 == 0)
            s.hands[0].pose = {now - 60000000, {-.2f, 1.3f, -.7f}, {0,0,0,1}, DD_SPATIAL_VALID};
        if (i % 16 == 8) s.hands[0] = {};
        pose = tracker.Update(s, {}, 0, now, .01);
    }
    check(pose.position.z < -.65, "intermittent stereo observations extend reach instead of repeatedly resetting to the arm model");
    s.connection++;
    s.hands[0] = {};
    pose = tracker.Update(s, {}, 0, now, .01);
    check(!pose.camera && near(pose.position.z, -.305), "reconnection cannot reuse cached camera depth");
}

int main() {
    lumaReachSurvivesBriefDetectionMissesWithoutRefreshingOldDepth();
    lumaStereoFallsBackInTheHeadTrackingOrigin();
    restingHandsHangInFrontAtElbowHeight();
    raisingTheControllerExtendsTheArm();
    theTorsoOnlyFollowsTheHeadWhileTheControllerMoves();
    headingAlignmentTurnsTheControllerToTheGaze();
    theIndexTipPointsWhereTheJoyConPoints();
    skeletonsAreFiniteMirroredAndNormalised();
    measuredMovementCarriesTheHandAndTheAnchorPullsItBack();
    sharedLayoutMatchesTheAppWriter();
    historyInterpolatesBetweenSamples();
    theFilterPlacesTheHandWhereItIsNowNotWhenThePictureWasTaken();
    aLateSightingCorrectsThePastAndKeepsTheMoveSince();
    aWildSightingIsIgnoredUntilItRepeats();
    aHandTheCameraLosesSettlesInsteadOfRunningAway();
    theCameraYawAveragesFaceReadingsAndFollowsARecenter();
    handCameraLayoutMatchesTheAppWriter();
    cameraFingerCurlsReadBackFromTheJoints();
    thePalmFitFindsTheHandsTurnAndPlace();
    thePalmNormalPointsOutOfThePalm();
    pinchesAndFistsBecomeButtons();
    aPalmTurnedToTheFaceOpensTheMenus();
    aBareHandFollowsTheCameraAndHidesWhenLost();
    theTurnSmootherSettlesAndKeepsUpWithFastTurns();
    theHandRenderModelIsAClosedOutwardFacingHand();
    if (failures) {
        fprintf(stderr, "%d Joy-Con check(s) failed\n", failures);
        return 1;
    }
    printf("Joy-Con arm model, movement, camera, pose and layout checks passed\n");
    return 0;
}
