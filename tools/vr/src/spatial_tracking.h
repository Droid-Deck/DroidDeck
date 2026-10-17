#pragma once
#include "../../../app/src/main/cpp/waylandcomp/src/spatialpose_shm.h"
#include "adaptive_pose.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

inline int64_t SpatialNow() {
    timespec t{}; clock_gettime(CLOCK_MONOTONIC, &t);
    return int64_t(t.tv_sec)*1000000000LL+t.tv_nsec;
}
inline bool SpatialFresh(const dd_spatial_pose &p, int64_t now, int64_t maxAge = 100000000LL) {
    if (!(p.flags & DD_SPATIAL_VALID) || p.time_ns <= 0 || now < p.time_ns || now-p.time_ns > maxAge) return false;
    for (float v : p.position) if (!std::isfinite(v)) return false;
    float n = 0;
    for (float v : p.rotation) { if (!std::isfinite(v)) return false; n += v*v; }
    return n > 0.99f && n < 1.01f;
}
inline bool SpatialHandFresh(const dd_spatial_hand &hand, int64_t now) {
    return SpatialFresh(hand.pose, now, 200000000LL);
}
struct OpticalPose {
    static constexpr int64_t kVisualHoldNs = 350000000LL;
    dd_spatial_hand last{};
    dd_spatial_pose filtered{}, rendered{};
    int64_t receivedAt = 0, renderedAt = 0;
    float velocity[3]{};
    unsigned observations = 0;
    bool predict = false;
    uint64_t connection = 0, origin = 0;
    void Reset() {
        last = {}; filtered = rendered = {};
        receivedAt = renderedAt = 0;
        observations = 0; predict = false; std::fill_n(velocity,3,0);
    }
    bool HoldLastObservation(int64_t now) {
        observations = 0; predict = false; receivedAt = 0; renderedAt = now;
        if (SpatialFresh(last.pose,now,kVisualHoldNs)) return true;
        Reset();
        return false;
    }
    bool Update(const dd_spatial_hand &hand, bool headValid, int64_t now, uint64_t nextConnection, uint64_t nextOrigin) {
        if (!headValid || connection != nextConnection || origin != nextOrigin) Reset();
        connection = nextConnection; origin = nextOrigin;
        if (!headValid) return false;
        if (!SpatialHandFresh(hand, now) || hand.pose.time_ns < last.pose.time_ns)
            return HoldLastObservation(now);
        if (!observations && hand.pose.time_ns == last.pose.time_ns) return HoldLastObservation(now);
        const bool interpolate = observations > 0 && renderedAt > 0 && now >= renderedAt && SpatialHandFresh(last,now);
        if (hand.pose.time_ns > last.pose.time_ns) {
            const float dt = float(hand.pose.time_ns-last.pose.time_ns)/1e9f;
            if (observations && dt > 0 && dt <= .2f) {
                float speed2 = 0, previousSpeed2 = 0, direction = 0, acceleration2 = 0;
                for (int i=0; i<3; ++i) {
                    const float v = (hand.pose.position[i]-last.pose.position[i])/dt;
                    speed2 += v*v;
                    previousSpeed2 += velocity[i]*velocity[i];
                    direction += v*velocity[i];
                    const float acceleration = (v-velocity[i])/dt;
                    acceleration2 += acceleration*acceleration;
                    velocity[i] = v;
                }
                predict = observations >= 2 && speed2 <= 2.25f && acceleration2 <= 36.f &&
                    direction > 0 && direction*direction >= .64f*speed2*previousSpeed2;
            } else {
                observations = 0; predict = false; std::fill_n(velocity,3,0);
            }
            filtered = hand.pose;
            float norm = 0;
            for (float v : filtered.rotation) norm += v*v;
            for (float &v : filtered.rotation) v /= std::sqrt(norm);
            ++observations; last = hand; receivedAt = now;
        }
        auto target = filtered;
        if (predict) {
            // Inference already consumes most capture age; receipt-relative prediction advances between results.
            float seconds = std::clamp(float(now-receivedAt)/1e9f,0.f,.08f);
            float speed2 = 0;
            for (float v : velocity) speed2 += v*v;
            if (speed2 > 0) seconds = std::min(seconds,.02f/std::sqrt(speed2));
            for (int i=0; i<3; ++i) target.position[i] += velocity[i]*seconds;
        }
        if (interpolate) {
            const float alpha = 1.f-std::exp(-float(now-renderedAt)/15000000.f);
            for (int i=0; i<3; ++i) rendered.position[i] += alpha*(target.position[i]-rendered.position[i]);
            float dot = 0, norm = 0;
            for (int i=0; i<4; ++i) dot += rendered.rotation[i]*target.rotation[i];
            for (int i=0; i<4; ++i) {
                rendered.rotation[i] += alpha*((dot < 0 ? -1 : 1)*target.rotation[i]-rendered.rotation[i]);
                norm += rendered.rotation[i]*rendered.rotation[i];
            }
            for (float &v : rendered.rotation) v /= std::sqrt(norm);
            rendered.time_ns = target.time_ns; rendered.flags = target.flags;
        } else rendered = target;
        renderedAt = now;
        return SpatialHandFresh(last, now);
    }
};
struct OpticalInputs {
    bool armed = false, gripArmed = false, click = false, grip = false;
    float trigger = 0;
    uint64_t connection = 0, origin = 0;
    int64_t lastObservation = 0;
    uint16_t inputEpochs = 0;
    void Release() { armed = gripArmed = click = grip = false; trigger = 0; }
    void Reset() { Release(); lastObservation = 0; inputEpochs = 0; }
    bool Update(const dd_spatial_hand &hand, bool headValid, int64_t now, uint64_t nextConnection, uint64_t nextOrigin,
                uint16_t nextEpochs = 0) {
        if (connection != nextConnection || origin != nextOrigin) {
            Reset(); connection = nextConnection; origin = nextOrigin;
        }
        if ((inputEpochs & 0xff) != (nextEpochs & 0xff)) { armed = click = false; trigger = 0; }
        if ((inputEpochs >> 8) != (nextEpochs >> 8)) gripArmed = grip = false;
        inputEpochs = nextEpochs;
        if (!headValid || !SpatialHandFresh(hand, now)) {
            Release(); return false;
        }
        if (hand.pose.time_ns < lastObservation) { Release(); return false; }
        const bool newObservation = hand.pose.time_ns > lastObservation;
        if (lastObservation && now-lastObservation > 200000000LL) Release();
        const bool triggerValid = (hand.pose.flags & DD_SPATIAL_TRIGGER_VALID) && std::isfinite(hand.trigger) && hand.trigger >= 0 && hand.trigger <= 1;
        const bool gripValid = (hand.pose.flags & DD_SPATIAL_GRIP_VALID) && hand.grip <= 1;
        if (!triggerValid) { armed = click = false; trigger = 0; }
        if (!gripValid) gripArmed = grip = false;
        if (!newObservation) return true;
        lastObservation = hand.pose.time_ns;
        if (triggerValid) {
            if (hand.trigger < 0.25f) armed = true;
            trigger = armed ? hand.trigger : 0;
            click = click ? trigger > 0.45f : trigger > 0.65f;
        }
        if (gripValid) {
            if (!hand.grip) gripArmed = true;
            grip = gripArmed && hand.grip;
        }
        return true;
    }
};
class SpatialFile {
public:
    explicit SpatialFile(const char *path = "/run/droiddeck/" DROIDDECK_SPATIALPOSE_FILE) : path_(path) {}
    SpatialFile(const SpatialFile &) = delete;
    SpatialFile &operator=(const SpatialFile &) = delete;
    bool Read(droiddeck_spatialpose *out) {
        if (!map_) {
            int fd = open(path_, O_RDONLY | O_CLOEXEC);
            if (fd < 0) return false;
            struct stat st{};
            void *p = MAP_FAILED;
            if (fstat(fd, &st) == 0 && st.st_size == sizeof(*out))
                p = mmap(nullptr, sizeof(*out), PROT_READ, MAP_SHARED, fd, 0);
            close(fd);
            if (p == MAP_FAILED) return false;
            map_ = static_cast<droiddeck_spatialpose *>(p);
        }
        for (int i = 0; i < 8; ++i) {
            const auto seq = __atomic_load_n(&map_->seq, __ATOMIC_ACQUIRE);
            if (seq & 1u) continue;
            memcpy(out, map_, sizeof(*out));
            __atomic_thread_fence(__ATOMIC_ACQUIRE);
            if (seq == __atomic_load_n(&map_->seq, __ATOMIC_RELAXED))
                return out->magic == DROIDDECK_SPATIALPOSE_MAGIC && out->version == DROIDDECK_SPATIALPOSE_VERSION && out->size == sizeof(*out);
        }
        return false;
    }
    ~SpatialFile() { if (map_) munmap(map_, sizeof(*map_)); }
private:
    const char *path_;
    droiddeck_spatialpose *map_ = nullptr;
};
