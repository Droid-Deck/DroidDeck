#pragma once
#include "../../../app/src/main/cpp/waylandcomp/src/spatialpose_shm.h"
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
inline bool SpatialFresh(const dd_spatial_pose &p, int64_t now) {
    if (!(p.flags & DD_SPATIAL_VALID) || p.time_ns <= 0 || now < p.time_ns || now-p.time_ns > 100000000LL) return false;
    for (float v : p.position) if (!std::isfinite(v)) return false;
    float n = 0;
    for (float v : p.rotation) { if (!std::isfinite(v)) return false; n += v*v; }
    return n > 0.99f && n < 1.01f;
}
struct OpticalInputs {
    bool armed = false, click = false, grip = false;
    float trigger = 0;
    uint64_t connection = 0, origin = 0;
    void Reset() { armed = click = grip = false; trigger = 0; }
    bool Update(const dd_spatial_hand &hand, bool headValid, int64_t now, uint64_t nextConnection, uint64_t nextOrigin) {
        if (connection != nextConnection || origin != nextOrigin) {
            Reset(); connection = nextConnection; origin = nextOrigin;
        }
        if (!headValid || !SpatialFresh(hand.pose, now) || !std::isfinite(hand.trigger) || hand.trigger < 0 || hand.trigger > 1) {
            Reset(); return false;
        }
        if (hand.trigger < 0.25f && !hand.grip) armed = true;
        trigger = armed ? hand.trigger : 0;
        click = click ? trigger > 0.45f : trigger > 0.65f;
        grip = armed && hand.grip;
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
