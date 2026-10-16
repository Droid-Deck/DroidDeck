#pragma once
#include "xr_tracking.h"
#include <cstring>
#include <cmath>
#include <mutex>
#include <string>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

class XrTrackingFile {
public:
    explicit XrTrackingFile(const char *path = "/run/droiddeck/" DROIDDECK_XR_FILE) : path_(path) {}
    ~XrTrackingFile() { if (mapping_) munmap(mapping_, sizeof(*mapping_)); }
    bool Read(DroidDeckXrTracking *packet) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!mapping_) {
            int descriptor = open(path_.c_str(), O_RDONLY | O_CLOEXEC);
            if (descriptor < 0) return false;
            struct stat status{};
            if (fstat(descriptor, &status) != 0 || status.st_size != sizeof(*mapping_)) {
                close(descriptor); return false;
            }
            void *mapping = mmap(nullptr, sizeof(*mapping_), PROT_READ, MAP_SHARED, descriptor, 0);
            close(descriptor);
            if (mapping == MAP_FAILED) return false;
            mapping_ = static_cast<DroidDeckXrTracking *>(mapping);
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            uint32_t sequence = __atomic_load_n(&mapping_->seq, __ATOMIC_ACQUIRE);
            if (sequence & 1u) continue;
            std::memcpy(packet, mapping_, sizeof(*packet));
            __atomic_thread_fence(__ATOMIC_ACQUIRE);
            if (__atomic_load_n(&mapping_->seq, __ATOMIC_RELAXED) == sequence)
                return packet->magic == DROIDDECK_XR_MAGIC && packet->version == DROIDDECK_XR_VERSION;
        }
        return false;
    }
    static int64_t Now() {
        timespec now{}; clock_gettime(CLOCK_MONOTONIC, &now);
        return (int64_t)now.tv_sec * 1000000000LL + now.tv_nsec;
    }
    static double PoseTimeOffset(int64_t predicted, int64_t now) {
        return predicted > 0 ? (static_cast<double>(predicted) - static_cast<double>(now)) / 1000000000.0 : 0;
    }
    static bool Fresh(const DroidDeckXrTracking &packet) {
        int64_t age = Now() - packet.sampled_ns;
        return packet.active && age >= 0 && age < 500000000LL && packet.period_ns >= 1000000LL
            && packet.period_ns <= 50000000LL && packet.eyes[0].width > 0 && packet.eyes[0].width <= 8192
            && packet.eyes[0].height > 0 && packet.eyes[0].height <= 8192;
    }
    static bool Tracked(const DroidDeckXrPose &pose) {
        if ((pose.flags & 15u) != 15u) return false;
        float lengthSquared = 0;
        for (float value : pose.orientation) {
            if (!std::isfinite(value)) return false;
            lengthSquared += value * value;
        }
        for (float value : pose.position) if (!std::isfinite(value)) return false;
        return std::abs(lengthSquared - 1.f) < 0.01f;
    }
private:
    DroidDeckXrTracking *mapping_ = nullptr;
    std::mutex mutex_;
    std::string path_;
};
