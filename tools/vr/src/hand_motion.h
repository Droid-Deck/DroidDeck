#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

#include "arm_model.h"

namespace droiddeck {

class HandFollower {
public:
    void Reset() { placed_ = false; }

    void Place(const Vec3 &at, const Vec3 &moved) {
        at_ = at;
        lastMoved_ = moved;
        placed_ = true;
    }

    Vec3 Update(const Vec3 &anchor, const Vec3 &moved, double dt, double anchorSeconds, double maxOffset) {
        if (!placed_) {
            at_ = anchor;
            placed_ = true;
        } else {
            at_ = at_ + (moved - lastMoved_);
            const double pull = 1.0 - std::exp(-std::max(dt, 0.0) / anchorSeconds);
            at_ = at_ + (anchor - at_) * pull;
            const Vec3 off = at_ - anchor;
            const double length = Length(off);
            if (length > maxOffset) at_ = anchor + off * (maxOffset / length);
        }
        lastMoved_ = moved;
        return at_;
    }

private:
    bool placed_ = false;
    Vec3 at_, lastMoved_;
};

class TimeHistory {
public:
    void Clear() { count_ = next_ = 0; }

    void Add(int64_t timeNs, const Vec3 &value) {
        if (count_ && timeNs <= time_[Last()]) {
            if (timeNs == time_[Last()]) value_[Last()] = value;
            return;
        }
        time_[next_] = timeNs;
        value_[next_] = value;
        next_ = (next_ + 1) % kSize;
        count_ = std::min(count_ + 1, kSize);
    }

    bool At(int64_t timeNs, Vec3 *out) const {
        if (!count_) return false;
        int newer = Last();
        if (timeNs >= time_[newer]) {
            *out = value_[newer];
            return true;
        }
        for (int i = 1; i < count_; i++) {
            const int older = (newer - 1 + kSize) % kSize;
            if (time_[older] <= timeNs) {
                const double t = double(timeNs - time_[older]) / double(time_[newer] - time_[older]);
                *out = value_[older] + (value_[newer] - value_[older]) * t;
                return true;
            }
            newer = older;
        }
        *out = value_[newer];
        return true;
    }

private:
    int Last() const { return (next_ - 1 + kSize) % kSize; }

    static constexpr int kSize = 256;
    int64_t time_[kSize] = {};
    Vec3 value_[kSize];
    int count_ = 0, next_ = 0;
};

class CameraYaw {
public:
    void Reset() {
        c_ = s_ = weight_ = 0.0;
        outliers_ = 0;
    }

    void Add(double yaw, double dt) {
        double current = 0.0;
        if (Get(&current) && std::fabs(std::remainder(yaw - current, 2.0 * M_PI)) > kJump) {
            if (++outliers_ < kOutlierFrames) return;
            Reset();
        }
        outliers_ = 0;
        const double keep = std::exp(-std::max(dt, 0.0) / kSeconds);
        c_ = c_ * keep + std::cos(yaw);
        s_ = s_ * keep + std::sin(yaw);
        weight_ = weight_ * keep + 1.0;
    }

    bool Get(double *yaw) const {
        if (weight_ < kMinWeight || std::hypot(c_, s_) < kMinAgreement * weight_) return false;
        *yaw = std::atan2(s_, c_);
        return true;
    }

private:
    static constexpr double kSeconds = 3.0, kMinWeight = 4.0, kMinAgreement = 0.7, kJump = 0.6;
    static constexpr int kOutlierFrames = 8;
    double c_ = 0.0, s_ = 0.0, weight_ = 0.0;
    int outliers_ = 0;
};

class HandKalman {
public:
    void Reset() {
        ready_ = false;
        haveInput_ = false;
        count_ = 0;
        outliers_ = 0;
        seenNs_ = 0;
        stillSeconds_ = 0.0;
    }

    void Forget() {
        ready_ = false;
        outliers_ = 0;
    }

    bool Ready() const { return ready_; }
    int64_t SeenNs() const { return seenNs_; }
    int64_t TimeNs() const { return lastNs_; }
    Vec3 Position() const { return {axes_[0].x[0], axes_[1].x[0], axes_[2].x[0]}; }
    Vec3 Velocity() const { return {axes_[0].x[1], axes_[1].x[1], axes_[2].x[1]}; }
    bool Still() const { return stillSeconds_ > kStillSeconds; }

    void Input(int64_t timeNs, const Vec3 &inertial, double turnRate) {
        if (haveInput_ && timeNs <= lastNs_) return;
        if (!haveInput_ || timeNs - lastNs_ > kGapNs) {
            Reset();
            haveInput_ = true;
            lastNs_ = timeNs;
            lastInertial_ = inertial;
            return;
        }
        Step step;
        step.timeNs = timeNs;
        step.dt = (timeNs - lastNs_) * 1e-9;
        step.du = inertial - lastInertial_;
        lastNs_ = timeNs;
        lastInertial_ = inertial;
        const bool calm = turnRate < kStillTurn && Length(step.du) < kStillAccel * step.dt;
        stillSeconds_ = calm ? stillSeconds_ + step.dt : 0.0;
        if (ready_) {
            Propagate(axes_, step.du, step.dt, Fade(timeNs - seenNs_));
            if (Still()) Stop(axes_);
            for (int a = 0; a < 3; a++) step.after[a] = axes_[a];
        }
        steps_[next_] = step;
        next_ = (next_ + 1) % kSteps;
        count_ = std::min(count_ + 1, kSteps);
    }

    bool Measure(int64_t timeNs, const Vec3 &at, double sigma) {
        if (!haveInput_ || timeNs > lastNs_ + kFutureNs || (count_ == 0 && timeNs < lastNs_)) return false;
        int k = -1;
        for (int i = 0; i < count_; i++) {
            const Step &s = steps_[Index(i)];
            if (s.timeNs >= timeNs) {
                k = i;
                break;
            }
        }
        const double z[3] = {at.x, at.y, at.z};
        if (k < 0) {
            if (!ready_) Start(axes_, z, sigma);
            else if (!Accept(axes_, z, sigma)) return false;
            else Correct(axes_, z, sigma);
            ready_ = true;
            seenNs_ = timeNs;
            return true;
        }
        const Step &first = steps_[Index(k)];
        if (timeNs < first.timeNs - int64_t(first.dt * 1e9)) return false;
        Axis axes[3];
        if (ready_) {
            if (k == 0) return false;
            for (int a = 0; a < 3; a++) axes[a] = steps_[Index(k - 1)].after[a];
        }
        const double frac = std::clamp((timeNs - (first.timeNs - first.dt * 1e9)) / (first.dt * 1e9), 0.0, 1.0);
        if (ready_) {
            Propagate(axes, first.du * frac, first.dt * frac, Fade(timeNs - seenNs_));
            if (!Accept(axes, z, sigma)) return false;
            Correct(axes, z, sigma);
        } else {
            Start(axes, z, sigma);
        }
        Propagate(axes, first.du * (1.0 - frac), first.dt * (1.0 - frac), kDampingSeconds);
        for (int a = 0; a < 3; a++) steps_[Index(k)].after[a] = axes[a];
        for (int i = k + 1; i < count_; i++) {
            Step &s = steps_[Index(i)];
            Propagate(axes, s.du, s.dt, Fade(s.timeNs - timeNs));
            for (int a = 0; a < 3; a++) s.after[a] = axes[a];
        }
        for (int a = 0; a < 3; a++) axes_[a] = axes[a];
        ready_ = true;
        seenNs_ = timeNs;
        return true;
    }

private:
    struct Axis {
        double x[2] = {0.0, 0.0};
        double P[2][2] = {};
    };
    struct Step {
        int64_t timeNs = 0;
        double dt = 0.0;
        Vec3 du;
        Axis after[3];
    };

    int Index(int i) const { return (next_ - count_ + i + kSteps) % kSteps; }

    static void Start(Axis *axes, const double *z, double sigma) {
        for (int a = 0; a < 3; a++) {
            axes[a] = Axis();
            axes[a].x[0] = z[a];
            axes[a].P[0][0] = sigma * sigma;
            axes[a].P[1][1] = kStartSpeed * kStartSpeed;
        }
    }

    bool Accept(const Axis *axes, const double *z, double sigma) {
        double d2 = 0.0;
        for (int a = 0; a < 3; a++) {
            const double y = z[a] - axes[a].x[0];
            d2 += y * y / (axes[a].P[0][0] + sigma * sigma);
        }
        if (d2 <= kGate) {
            outliers_ = 0;
            return true;
        }
        if (++outliers_ < kOutliers) return false;
        outliers_ = 0;
        ready_ = false;
        return true;
    }

    void Correct(Axis *axes, const double *z, double sigma) const {
        if (!ready_) {
            Start(axes, z, sigma);
            return;
        }
        for (int a = 0; a < 3; a++) Update(axes[a], 0, z[a], sigma);
    }

    static void Update(Axis &s, int state, double z, double sigma) {
        const double S = s.P[state][state] + sigma * sigma;
        const double K[2] = {s.P[0][state] / S, s.P[1][state] / S};
        const double y = z - s.x[state];
        s.x[0] += K[0] * y;
        s.x[1] += K[1] * y;
        const double p00 = s.P[0][0] - K[0] * s.P[state][0];
        const double p01 = s.P[0][1] - K[0] * s.P[state][1];
        const double p11 = s.P[1][1] - K[1] * s.P[state][1];
        s.P[0][0] = p00;
        s.P[0][1] = s.P[1][0] = p01;
        s.P[1][1] = p11;
    }

    static void Stop(Axis *axes) {
        for (int a = 0; a < 3; a++) Update(axes[a], 1, 0.0, kStillSpeed);
    }

    static double Fade(int64_t sinceSeenNs) { return sinceSeenNs > kStaleNs ? kStaleDampingSeconds : kDampingSeconds; }

    static void Propagate(Axis *axes, const Vec3 &du, double dt, double damping) {
        if (dt <= 0.0) return;
        const double motion = Length(du);
        const double g = kMotionError * kMotionError * motion * motion;
        const double d[3] = {du.x, du.y, du.z};
        const double keep = std::exp(-dt / damping);
        const double q00 = kNoise * dt * dt * dt / 3.0 + g * dt * dt / 4.0;
        const double q01 = kNoise * dt * dt / 2.0 + g * dt / 2.0;
        const double q11 = kNoise * dt + g;
        for (int a = 0; a < 3; a++) {
            Axis &s = axes[a];
            s.x[0] += s.x[1] * dt + 0.5 * d[a] * dt;
            s.x[1] = (s.x[1] + d[a]) * keep;
            const double p00 = s.P[0][0] + 2.0 * dt * s.P[0][1] + dt * dt * s.P[1][1] + q00;
            const double p01 = keep * (s.P[0][1] + dt * s.P[1][1]) + q01;
            const double p11 = keep * keep * s.P[1][1] + q11;
            s.P[0][0] = p00;
            s.P[0][1] = s.P[1][0] = p01;
            s.P[1][1] = p11;
        }
    }

    static constexpr int kSteps = 192;
    static constexpr int64_t kGapNs = 250000000;
    static constexpr int64_t kFutureNs = 50000000, kStaleNs = 200000000;
    static constexpr double kNoise = 2.0, kMotionError = 0.3, kDampingSeconds = 0.3, kStaleDampingSeconds = 0.1;
    static constexpr double kStartSpeed = 0.5;
    static constexpr double kStillTurn = 0.3, kStillAccel = 0.6, kStillSeconds = 0.1, kStillSpeed = 0.05;
    static constexpr double kGate = 25.0;
    static constexpr int kOutliers = 3;
    Step steps_[kSteps];
    Axis axes_[3];
    Vec3 lastInertial_;
    int64_t lastNs_ = 0, seenNs_ = 0;
    double stillSeconds_ = 0.0;
    int count_ = 0, next_ = 0, outliers_ = 0;
    bool ready_ = false, haveInput_ = false;
};

}
