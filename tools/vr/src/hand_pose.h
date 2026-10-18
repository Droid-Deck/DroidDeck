#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

#include "hand_skeleton.h"

namespace droiddeck {

constexpr int kHandJoints = 21;

namespace hand {

enum : int {
    kWrist = 0, kThumbCmc = 1, kThumbMcp = 2, kThumbIp = 3, kThumbTip = 4,
    kIndexMcp = 5, kIndexPip = 6, kIndexDip = 7, kIndexTip = 8,
    kMiddleMcp = 9, kMiddleTip = 12, kRingMcp = 13, kRingTip = 16, kPinkyMcp = 17, kPinkyTip = 20,
};

constexpr int kPalm[5] = {kWrist, kIndexMcp, kMiddleMcp, kRingMcp, kPinkyMcp};
constexpr int kCurlSteps = 20;

inline double Angle(const Vec3 &a, const Vec3 &b) {
    const double la = Length(a), lb = Length(b);
    if (la < 1e-9 || lb < 1e-9) return 0.0;
    return std::acos(std::clamp(Dot(a, b) / (la * lb), -1.0, 1.0));
}

inline double Bend(const Vec3 joints[kHandJoints], int finger) {
    if (finger == 0)
        return Angle(joints[kThumbMcp] - joints[kThumbCmc], joints[kThumbIp] - joints[kThumbMcp]) +
               Angle(joints[kThumbIp] - joints[kThumbMcp], joints[kThumbTip] - joints[kThumbIp]);
    const int mcp = kIndexMcp + (finger - 1) * 4;
    return Angle(joints[mcp] - joints[kWrist], joints[mcp + 1] - joints[mcp]) +
           Angle(joints[mcp + 1] - joints[mcp], joints[mcp + 2] - joints[mcp + 1]) +
           Angle(joints[mcp + 2] - joints[mcp + 1], joints[mcp + 3] - joints[mcp + 2]);
}

inline double &CurlOf(FingerCurls &c, int finger) {
    switch (finger) {
    case 0: return c.thumb;
    case 1: return c.index;
    case 2: return c.middle;
    case 3: return c.ring;
    default: return c.pinky;
    }
}

}

inline void HandJoints(bool right, const FingerCurls &curls, Vec3 out[kHandJoints]) {
    using namespace hand;
    vr::VRBoneTransform_t bones[kHandBones];
    HandSkeleton(right, curls, bones);
    const Quat wristRotation = ToQuat(bones[1].orientation);
    const Vec3 wrist = ToVec(bones[1].position);
    out[kWrist] = wrist;
    const auto chain = [&](int first, int count, Vec3 *joints) {
        Quat r = wristRotation;
        Vec3 p = wrist;
        for (int b = 0; b < count; b++) {
            p = p + Rotate(r, ToVec(bones[first + b].position));
            r = r * ToQuat(bones[first + b].orientation);
            joints[b] = p;
        }
    };
    chain(2, 4, &out[kThumbCmc]);
    for (int f = 0; f < 4; f++) {
        Vec3 joints[5];
        chain(6 + f * 5, 5, joints);
        for (int j = 0; j < 4; j++) out[kIndexMcp + f * 4 + j] = joints[j + 1];
    }
}

class HandModel {
public:
    explicit HandModel(bool right) : right_(right) {
        Vec3 open[kHandJoints], closed[kHandJoints];
        HandJoints(right, FingerCurls{}, open);
        FingerCurls fist;
        fist.thumb = fist.index = fist.middle = fist.ring = fist.pinky = 1.0;
        HandJoints(right, fist, closed);
        for (int i = 0; i < 5; i++) center_ = center_ + open[hand::kPalm[i]] * 0.2;
        const Vec3 normal = Cross(open[hand::kIndexMcp] - open[hand::kWrist], open[hand::kPinkyMcp] - open[hand::kWrist]);
        normalSign_ = Dot(normal, closed[hand::kMiddleTip] - open[hand::kMiddleTip]) >= 0.0 ? 1.0 : -1.0;
        for (int s = 0; s <= hand::kCurlSteps; s++) {
            FingerCurls c;
            c.thumb = c.index = c.middle = c.ring = c.pinky = double(s) / hand::kCurlSteps;
            Vec3 joints[kHandJoints];
            HandJoints(right, c, joints);
            for (int f = 0; f < 5; f++) bends_[f][s] = hand::Bend(joints, f);
        }
    }

    bool Right() const { return right_; }
    Vec3 PalmCenter() const { return center_; }

    bool Fit(const Vec3 joints[kHandJoints], const FingerCurls &curls, Quat *rotation, Vec3 *center, double *rms) const {
        Vec3 shape[kHandJoints], palm[5], model;
        HandJoints(right_, curls, shape);
        for (int i = 0; i < 5; i++) model = model + shape[hand::kPalm[i]] * 0.2;
        for (int i = 0; i < 5; i++) palm[i] = shape[hand::kPalm[i]] - model;
        Vec3 observed[5], mean;
        for (int i = 0; i < 5; i++) mean = mean + joints[hand::kPalm[i]] * 0.2;
        for (int i = 0; i < 5; i++) observed[i] = joints[hand::kPalm[i]] - mean;
        double S[3][3] = {};
        for (int i = 0; i < 5; i++) {
            const double m[3] = {palm[i].x, palm[i].y, palm[i].z};
            const double o[3] = {observed[i].x, observed[i].y, observed[i].z};
            for (int r = 0; r < 3; r++)
                for (int c = 0; c < 3; c++) S[r][c] += m[r] * o[c];
        }
        double N[4][4] = {
            {S[0][0] + S[1][1] + S[2][2], S[1][2] - S[2][1], S[2][0] - S[0][2], S[0][1] - S[1][0]},
            {S[1][2] - S[2][1], S[0][0] - S[1][1] - S[2][2], S[0][1] + S[1][0], S[2][0] + S[0][2]},
            {S[2][0] - S[0][2], S[0][1] + S[1][0], -S[0][0] + S[1][1] - S[2][2], S[1][2] + S[2][1]},
            {S[0][1] - S[1][0], S[2][0] + S[0][2], S[1][2] + S[2][1], -S[0][0] - S[1][1] + S[2][2]},
        };
        double q[4];
        if (!LargestEigenvector(N, q)) return false;
        const Quat fitted = Normalize({q[0], q[1], q[2], q[3]});
        double squares = 0.0;
        for (int i = 0; i < 5; i++) {
            const Vec3 d = Rotate(fitted, palm[i]) - observed[i];
            squares += Dot(d, d);
        }
        *rotation = fitted;
        if (center) *center = model;
        if (rms) *rms = std::sqrt(squares / 5.0);
        return true;
    }

    Vec3 PalmNormal(const Vec3 joints[kHandJoints]) const {
        const Vec3 n = Cross(joints[hand::kIndexMcp] - joints[hand::kWrist], joints[hand::kPinkyMcp] - joints[hand::kWrist]);
        const double length = Length(n);
        return length > 1e-9 ? n * (normalSign_ / length) : Vec3{};
    }

    FingerCurls Curls(const Vec3 joints[kHandJoints]) const {
        FingerCurls out;
        for (int f = 0; f < 5; f++) {
            const double bend = hand::Bend(joints, f);
            const double *table = bends_[f];
            double curl = bend <= table[0] ? 0.0 : 1.0;
            for (int s = 0; s < hand::kCurlSteps; s++) {
                if (bend < table[s] || bend > table[s + 1]) continue;
                const double span = table[s + 1] - table[s];
                curl = (s + (span > 1e-9 ? (bend - table[s]) / span : 0.0)) / hand::kCurlSteps;
                break;
            }
            hand::CurlOf(out, f) = curl;
        }
        return out;
    }

private:
    static bool LargestEigenvector(double A[4][4], double out[4]) {
        double V[4][4] = {{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1}};
        for (int sweep = 0; sweep < 50; sweep++) {
            double off = 0.0;
            for (int p = 0; p < 4; p++)
                for (int r = p + 1; r < 4; r++) off += A[p][r] * A[p][r];
            if (off < 1e-24) break;
            for (int p = 0; p < 4; p++)
                for (int r = p + 1; r < 4; r++) {
                    if (std::fabs(A[p][r]) < 1e-30) continue;
                    const double theta = (A[r][r] - A[p][p]) / (2.0 * A[p][r]);
                    const double t = (theta >= 0.0 ? 1.0 : -1.0) / (std::fabs(theta) + std::sqrt(theta * theta + 1.0));
                    const double c = 1.0 / std::sqrt(t * t + 1.0), s = t * c;
                    for (int k = 0; k < 4; k++) {
                        const double akp = A[k][p], akr = A[k][r];
                        A[k][p] = c * akp - s * akr;
                        A[k][r] = s * akp + c * akr;
                    }
                    for (int k = 0; k < 4; k++) {
                        const double apk = A[p][k], ark = A[r][k];
                        A[p][k] = c * apk - s * ark;
                        A[r][k] = s * apk + c * ark;
                    }
                    for (int k = 0; k < 4; k++) {
                        const double vkp = V[k][p], vkr = V[k][r];
                        V[k][p] = c * vkp - s * vkr;
                        V[k][r] = s * vkp + c * vkr;
                    }
                }
        }
        int best = 0;
        for (int i = 1; i < 4; i++)
            if (A[i][i] > A[best][best]) best = i;
        double length = 0.0;
        for (int k = 0; k < 4; k++) length += V[k][best] * V[k][best];
        if (!(length > 1e-12)) return false;
        for (int k = 0; k < 4; k++) out[k] = V[k][best] / std::sqrt(length);
        return true;
    }

    bool right_;
    Vec3 center_;
    double normalSign_ = 1.0;
    double bends_[5][hand::kCurlSteps + 1] = {};
};

class Debounce {
public:
    bool Update(bool in, int64_t nowNs, int64_t onNs, int64_t offNs) {
        if (in == on_) {
            pending_ = false;
            return on_;
        }
        if (!pending_) {
            pending_ = true;
            sinceNs_ = nowNs;
        }
        if (nowNs - sinceNs_ >= (in ? onNs : offNs)) {
            on_ = in;
            pending_ = false;
        }
        return on_;
    }
    void Reset() { on_ = pending_ = false; }
    bool On() const { return on_; }

private:
    bool on_ = false, pending_ = false;
    int64_t sinceNs_ = 0;
};

struct HandButtons {
    float trigger = 0.0f, grip = 0.0f;
    bool triggerClick = false, triggerTouch = false, gripClick = false;
    bool a = false, b = false, system = false, facing = false;
};

class HandGestures {
public:
    explicit HandGestures(bool right) : right_(right) {}

    HandButtons Update(const HandModel &model, const Vec3 joints[kHandJoints], const FingerCurls &curls, const Vec3 &palm,
                       const Vec3 &head, int64_t nowNs) {
        using namespace hand;
        const double index = Length(joints[kThumbTip] - joints[kIndexTip]);
        const double middle = Length(joints[kThumbTip] - joints[kMiddleTip]);
        const double ring = Length(joints[kThumbTip] - joints[kRingTip]);
        const Vec3 toHead = head - palm;
        const double distance = Length(toHead);
        const bool towards = distance > 1e-6 && Dot(model.PalmNormal(joints), toHead * (1.0 / distance)) > kFacing;
        HandButtons out;
        out.facing = facing_.Update(towards, nowNs, kFacingOnNs, kOffNs);
        const double fist = (curls.middle + curls.ring + curls.pinky) / 3.0;
        out.grip = (float)std::clamp((fist - kGripStart) / (kGripFull - kGripStart), 0.0, 1.0);
        out.gripClick = grip_.Update(gripClick_ ? out.grip > kGripRelease : out.grip >= 1.0f, nowNs, kOnNs, kOffNs);
        gripClick_ = out.gripClick;
        const bool pinch = pinch_.Update(index < (pinchHeld_ ? kPinchOff : kPinchOn), nowNs, kOnNs, kOffNs);
        pinchHeld_ = pinch;
        const bool loose = out.grip < 0.5f;
        const bool aPinch = a_.Update(loose && !pinch && Touched(ring, a_.On()), nowNs, kOnNs, kOffNs);
        const bool bPinch = b_.Update(loose && !pinch && Touched(middle, b_.On()), nowNs, kOnNs, kOffNs);
        if (out.facing) {
            if (right_) out.system = pinch;
            else out.b = pinch;
            return out;
        }
        out.trigger = (float)std::clamp((kPinchFar - index) / (kPinchFar - kPinchNear), 0.0, 1.0);
        out.triggerClick = pinch;
        out.triggerTouch = out.trigger > 0.0f;
        out.a = aPinch;
        out.b = bPinch;
        return out;
    }

    void Reset() {
        pinch_.Reset();
        a_.Reset();
        b_.Reset();
        grip_.Reset();
        facing_.Reset();
        pinchHeld_ = gripClick_ = false;
    }

    static constexpr double kPinchOn = 0.035, kPinchOff = 0.045, kPinchNear = 0.03, kPinchFar = 0.06;
    static constexpr double kTouchOn = 0.025, kTouchOff = 0.035;
    static constexpr double kGripStart = 0.35, kGripFull = 0.75, kGripRelease = 0.7;
    static constexpr double kFacing = 0.6;
    static constexpr int64_t kOnNs = 50000000, kOffNs = 100000000, kFacingOnNs = 300000000;

private:
    static bool Touched(double distance, bool held) { return distance < (held ? kTouchOff : kTouchOn); }

    bool right_;
    Debounce pinch_, a_, b_, grip_, facing_;
    bool pinchHeld_ = false, gripClick_ = false;
};

class SmoothTurn {
public:
    void Reset() { ready_ = false; }

    Quat Update(const Quat &target, double dt) {
        if (!ready_) {
            at_ = last_ = target;
            speed_ = 0.0;
            ready_ = true;
            return at_;
        }
        if (!(dt > 0.0)) return at_;
        const double moved = AngleDegrees(Conjugate(last_) * target) * M_PI / 180.0;
        last_ = target;
        speed_ += (moved / dt - speed_) * Alpha(dt, kSpeedCutoff);
        at_ = Nlerp(at_, target, Alpha(dt, kMinCutoff + kBeta * speed_));
        return at_;
    }

    static constexpr double kMinCutoff = 1.0, kBeta = 0.2, kSpeedCutoff = 1.0;

private:
    static double Alpha(double dt, double hz) { return 1.0 / (1.0 + 1.0 / (2.0 * M_PI * hz * dt)); }

    bool ready_ = false;
    Quat at_, last_;
    double speed_ = 0.0;
};

class SmoothPoint {
public:
    void Reset() { ready_ = false; }

    Vec3 Update(const Vec3 &target, double dt) {
        if (!ready_) {
            at_ = target;
            speed_ = 0.0;
            velocity_ = Vec3{};
            ready_ = true;
            return at_;
        }
        if (!(dt > 0.0)) return at_;
        const Vec3 before = at_;
        speed_ += (Length(target - at_) / dt - speed_) * Alpha(dt, kSpeedCutoff);
        at_ = at_ + (target - at_) * Alpha(dt, kMinCutoff + kBeta * speed_);
        velocity_ = velocity_ + ((at_ - before) * (1.0 / dt) - velocity_) * Alpha(dt, kSpeedCutoff);
        return at_;
    }

    Vec3 Velocity() const { return velocity_; }

    static constexpr double kMinCutoff = 1.0, kBeta = 0.5, kSpeedCutoff = 1.0;

private:
    static double Alpha(double dt, double hz) { return 1.0 / (1.0 + 1.0 / (2.0 * M_PI * hz * dt)); }

    bool ready_ = false;
    Vec3 at_, velocity_;
    double speed_ = 0.0;
};

struct CameraHandPose {
    bool visible = false;
    Quat rotation;
    Vec3 position, velocity;
    FingerCurls curls;
    HandButtons buttons;
};

class CameraHand {
public:
    explicit CameraHand(bool right) : model_(right), gestures_(right) {}

    void Reset() {
        Hide();
        shown_ = false;
    }

    void See(int64_t timeNs, const Vec3 &palm, const Vec3 *joints, const Vec3 &head, int64_t nowNs) {
        if (!joints) return;
        const FingerCurls curls = model_.Curls(joints);
        Quat rotation;
        Vec3 center;
        double rms = 1.0;
        if (!model_.Fit(joints, curls, &rotation, &center, &rms) || rms > kMaxRms) return;
        palm_ = palm;
        seenNs_ = timeNs;
        rms_ = rms;
        if (seen_ && AngleDegrees(Conjugate(target_) * rotation) > kMaxTurnDegrees) {
            const bool agrees = pending_ && AngleDegrees(Conjugate(pendingTurn_) * rotation) < kAgreeDegrees;
            pending_ = !agrees;
            pendingTurn_ = rotation;
            if (!agrees) return;
        }
        pending_ = false;
        seen_ = true;
        target_ = rotation;
        center_ = center;
        targetCurls_ = curls;
        buttons_ = gestures_.Update(model_, joints, curls, palm, head, nowNs);
    }

    CameraHandPose Update(int64_t nowNs, double dt) {
        CameraHandPose out;
        if (!seen_ || nowNs - seenNs_ > kHoldNs) {
            if (shown_) Hide();
            shown_ = false;
            return out;
        }
        if (!shown_) curls_ = targetCurls_;
        shown_ = true;
        const double follow = 1.0 - std::exp(-std::max(dt, 0.0) / kCurlSeconds);
        for (int f = 0; f < 5; f++) hand::CurlOf(curls_, f) += (hand::CurlOf(targetCurls_, f) - hand::CurlOf(curls_, f)) * follow;
        out.visible = true;
        out.rotation = turn_.Update(target_, dt);
        out.position = place_.Update(palm_, dt) - Rotate(out.rotation, center_);
        out.velocity = place_.Velocity();
        out.curls = curls_;
        out.buttons = buttons_;
        return out;
    }

    double Rms() const { return rms_; }

    static constexpr double kMaxRms = 0.02, kCurlSeconds = 0.08, kMaxTurnDegrees = 45.0, kAgreeDegrees = 25.0;
    static constexpr int64_t kHoldNs = 500000000;

private:
    void Hide() {
        place_.Reset();
        turn_.Reset();
        gestures_.Reset();
        buttons_ = HandButtons();
        seen_ = pending_ = false;
    }

    HandModel model_;
    HandGestures gestures_;
    SmoothPoint place_;
    SmoothTurn turn_;
    Quat target_, pendingTurn_;
    Vec3 center_, palm_;
    FingerCurls targetCurls_, curls_;
    HandButtons buttons_;
    int64_t seenNs_ = 0;
    double rms_ = 0.0;
    bool seen_ = false, pending_ = false, shown_ = false;
};
}
