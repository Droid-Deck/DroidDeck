#pragma once
#include "xr_tracking.h"
#include <cstring>
#include <cmath>
#include <dlfcn.h>
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
    // Android stamps these packets with the real monotonic clock. The session's clockpause preload holds the
    // guest's clock back by the time spent suspended, so read libc's clock directly.
    static int64_t Now() {
        using ClockGettime = int (*)(clockid_t, timespec *);
        static const ClockGettime real = [] {
            void *libc = dlopen("libc.so.6", RTLD_LAZY | RTLD_NOLOAD);
            void *symbol = libc ? dlsym(libc, "clock_gettime") : nullptr;
            return symbol ? reinterpret_cast<ClockGettime>(symbol) : &clock_gettime;
        }();
        timespec now{}; real(CLOCK_MONOTONIC, &now);
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

#define DROIDDECK_XR_LATCH_FILE "droiddeck-xr-latch"

// The compositor's capture latches one head pose per frame here; the driver hands SteamVR exactly that pose,
// so the frame drawn next and the pose the headset reprojects it with are the same sample.
class XrLatchWriter {
public:
    ~XrLatchWriter() { if (mapping_) munmap(mapping_, sizeof(*mapping_)); }
    void Write(const DroidDeckXrTracking &packet) {
        if (!mapping_ && !Open()) return;
        const uint32_t sequence = __atomic_load_n(&mapping_->seq, __ATOMIC_RELAXED) | 1u;
        __atomic_store_n(&mapping_->seq, sequence, __ATOMIC_RELAXED);
        __atomic_thread_fence(__ATOMIC_RELEASE);
        DroidDeckXrTracking copy = packet; copy.seq = sequence;
        std::memcpy(mapping_, &copy, sizeof(copy));
        __atomic_store_n(&mapping_->seq, sequence + 1, __ATOMIC_RELEASE);
    }
private:
    bool Open() {
        if (attempted_) return false;
        attempted_ = true;
        int descriptor = open("/run/droiddeck/" DROIDDECK_XR_LATCH_FILE, O_RDWR | O_CREAT | O_CLOEXEC, 0600);
        if (descriptor < 0) return false;
        void *mapping = ftruncate(descriptor, sizeof(*mapping_)) == 0
            ? mmap(nullptr, sizeof(*mapping_), PROT_READ | PROT_WRITE, MAP_SHARED, descriptor, 0) : MAP_FAILED;
        close(descriptor);
        if (mapping == MAP_FAILED) return false;
        mapping_ = static_cast<DroidDeckXrTracking *>(mapping);
        return true;
    }
    DroidDeckXrTracking *mapping_ = nullptr;
    bool attempted_ = false;
};
