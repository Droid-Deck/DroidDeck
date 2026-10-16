#pragma once
#include "../../../app/src/main/cpp/waylandcomp/src/spatialpose_shm.h"
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
    dd_spatial_hand last{};
    dd_spatial_pose filtered{}, rendered{};
    dd_spatial_pose stationary{}, quiet{};
    int64_t receivedAt = 0, renderedAt = 0;
    int64_t positionQuietSince = 0, rotationQuietSince = 0;
    float velocity[3]{};
    float positionTravel = 0, rotationTravel = 0;
    unsigned observations = 0;
    bool predict = false;
    bool positionAnchored = true, rotationAnchored = true;
    uint64_t connection = 0, origin = 0;
    void Reset() {
        last = {}; filtered = rendered = stationary = quiet = {};
        receivedAt = renderedAt = positionQuietSince = rotationQuietSince = 0;
        observations = 0; predict = false; positionAnchored = rotationAnchored = true;
        positionTravel = rotationTravel = 0; std::fill_n(velocity,3,0);
    }
    static float Distance(const float *a, const float *b) {
        float squared = 0;
        for (int i=0; i<3; ++i) squared += (a[i]-b[i])*(a[i]-b[i]);
        return std::sqrt(squared);
    }
    static float Angle(const float *a, const float *b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i=0; i<4; ++i) {
            dot += double(a[i])*b[i]; normA += double(a[i])*a[i]; normB += double(b[i])*b[i];
        }
        return float(2*std::acos(std::clamp(std::abs(dot)/std::sqrt(normA*normB),0.,1.)));
    }
    void Stabilize() {
        if (!observations) {
            stationary = quiet = filtered;
            positionQuietSince = rotationQuietSince = filtered.time_ns;
            positionTravel = rotationTravel = 0;
            positionAnchored = rotationAnchored = true;
        } else {
            const float positionDistance = Distance(filtered.position,quiet.position);
            if (positionAnchored) {
                if (Distance(filtered.position,stationary.position) > .003f) {
                    positionAnchored = false;
                    std::copy_n(filtered.position,3,quiet.position);
                    positionQuietSince = filtered.time_ns; positionTravel = 0;
                }
            } else if (positionDistance > .0015f) {
                std::copy_n(filtered.position,3,quiet.position);
                positionQuietSince = filtered.time_ns; positionTravel = 0;
            } else {
                positionTravel += Distance(filtered.position,last.pose.position);
                // Directed motion accumulates from a fixed anchor instead of disappearing into the deadband.
                if (filtered.time_ns-positionQuietSince >= 240000000 &&
                    (positionTravel < .00001f || positionDistance <= positionTravel*.5f) &&
                    Distance(filtered.position,rendered.position) <= .003f) {
                    positionAnchored = true; std::copy_n(rendered.position,3,stationary.position);
                }
            }
            const float rotationDistance = Angle(filtered.rotation,quiet.rotation);
            if (rotationAnchored) {
                if (Angle(filtered.rotation,stationary.rotation) > .017453293f) {
                    rotationAnchored = false;
                    std::copy_n(filtered.rotation,4,quiet.rotation);
                    rotationQuietSince = filtered.time_ns; rotationTravel = 0;
                }
            } else if (rotationDistance > .008726647f) {
                std::copy_n(filtered.rotation,4,quiet.rotation);
                rotationQuietSince = filtered.time_ns; rotationTravel = 0;
            } else {
                rotationTravel += Angle(filtered.rotation,last.pose.rotation);
                if (filtered.time_ns-rotationQuietSince >= 240000000 &&
                    (rotationTravel < .000001f || rotationDistance <= rotationTravel*.5f) &&
                    Angle(filtered.rotation,rendered.rotation) <= .017453293f) {
                    rotationAnchored = true; std::copy_n(rendered.rotation,4,stationary.rotation);
                }
            }
        }
        if (positionAnchored) { std::copy_n(stationary.position,3,filtered.position); predict = false; }
        if (rotationAnchored) std::copy_n(stationary.rotation,4,filtered.rotation);
    }
    bool Update(const dd_spatial_hand &hand, bool headValid, int64_t now, uint64_t nextConnection, uint64_t nextOrigin) {
        if (!headValid || connection != nextConnection || origin != nextOrigin) Reset();
        connection = nextConnection; origin = nextOrigin;
        if (!headValid) return false;
        if (!SpatialHandFresh(hand, now) || hand.pose.time_ns < last.pose.time_ns) {
            observations = 0; predict = false; receivedAt = 0; renderedAt = now;
            return SpatialHandFresh(last,now);
        }
        if (!observations && hand.pose.time_ns == last.pose.time_ns) return SpatialHandFresh(last,now);
        const bool interpolate = renderedAt > 0 && now >= renderedAt && SpatialHandFresh(last,now);
        if (hand.pose.time_ns > last.pose.time_ns) {
            const float dt = float(hand.pose.time_ns-last.pose.time_ns)/1e9f;
            if (observations && dt > 0 && dt <= .2f) {
                float speed2 = 0, acceleration2 = 0;
                for (int i=0; i<3; ++i) {
                    const float v = (hand.pose.position[i]-last.pose.position[i])/dt;
                    speed2 += v*v;
                    const float acceleration = (v-velocity[i])/dt;
                    acceleration2 += acceleration*acceleration;
                    velocity[i] = v;
                }
                predict = observations >= 2 && speed2 <= 2.25f && acceleration2 <= 36.f;
            } else {
                observations = 0; predict = false; std::fill_n(velocity,3,0);
            }
            filtered = hand.pose;
            float norm = 0;
            for (float v : filtered.rotation) norm += v*v;
            for (float &v : filtered.rotation) v /= std::sqrt(norm);
            Stabilize();
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
