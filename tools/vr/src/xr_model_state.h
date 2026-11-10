#pragma once
#include <cstdint>
#include <cstddef>
#include <cstring>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include "xr_tracking_file.h"

constexpr uint32_t DROIDDECK_XR_MODEL_MAGIC = 0x4d584444;
constexpr uint32_t DROIDDECK_XR_MODEL_VERSION = 1;
constexpr uint32_t DROIDDECK_XR_MODEL_NODES = 256;
constexpr const char *DROIDDECK_XR_MODEL_FILE = "droiddeck-xr-model-state";

struct DroidDeckXrModelNode {
    float matrix[12]{};
    uint32_t visible = 0;
};

struct DroidDeckXrModelState {
    uint32_t magic = DROIDDECK_XR_MODEL_MAGIC, version = DROIDDECK_XR_MODEL_VERSION, seq = 0;
    uint32_t count[2]{};
    uint8_t key[2][16]{};
    int64_t sampled_ns = 0;
    DroidDeckXrModelNode nodes[2][DROIDDECK_XR_MODEL_NODES]{};
};

class XrModelStateFile {
public:
    XrModelStateFile() = default;
    XrModelStateFile(const XrModelStateFile &) = delete;
    XrModelStateFile &operator=(const XrModelStateFile &) = delete;
    ~XrModelStateFile() { if (mapping_) munmap(mapping_, sizeof(*mapping_)); }

    bool open(const std::string &path, bool writer) {
        if (mapping_) return true;
        int fd = ::open(path.c_str(), (writer ? O_RDWR | O_CREAT : O_RDONLY) | O_CLOEXEC, 0600);
        if (fd < 0) return false;
        struct stat info{};
        bool valid = writer ? ftruncate(fd, sizeof(*mapping_)) == 0 :
            fstat(fd, &info) == 0 && info.st_size == sizeof(*mapping_);
        void *memory = valid ? mmap(nullptr, sizeof(*mapping_), PROT_READ | (writer ? PROT_WRITE : 0),
                                    MAP_SHARED, fd, 0) : MAP_FAILED;
        close(fd);
        if (memory == MAP_FAILED) return false;
        mapping_ = static_cast<DroidDeckXrModelState *>(memory);
        if (writer) write(DroidDeckXrModelState{});
        return true;
    }

    void write(const DroidDeckXrModelState &state) {
        if (!mapping_) return;
        const uint32_t seq = __atomic_load_n(&mapping_->seq, __ATOMIC_RELAXED);
        __atomic_store_n(&mapping_->seq, seq | 1u, __ATOMIC_SEQ_CST);
        __atomic_thread_fence(__ATOMIC_SEQ_CST);
        std::memcpy(reinterpret_cast<char *>(mapping_), &state, offsetof(DroidDeckXrModelState, seq));
        constexpr auto offset = offsetof(DroidDeckXrModelState, count);
        std::memcpy(reinterpret_cast<char *>(mapping_) + offset,
                    reinterpret_cast<const char *>(&state) + offset, sizeof(state) - offset);
        __atomic_store_n(&mapping_->seq, (seq + 2) & ~1u, __ATOMIC_RELEASE);
    }

    bool read(DroidDeckXrModelState &state) const {
        if (!mapping_) return false;
        for (int retry = 0; retry < 8; ++retry) {
            const auto seq = __atomic_load_n(&mapping_->seq, __ATOMIC_ACQUIRE);
            if (seq & 1) continue;
            std::memcpy(&state, mapping_, sizeof(state));
            __atomic_thread_fence(__ATOMIC_ACQUIRE);
            if (__atomic_load_n(&mapping_->seq, __ATOMIC_RELAXED) == seq) {
                const auto age = XrTrackingFile::Now() - state.sampled_ns;
                return state.magic == DROIDDECK_XR_MODEL_MAGIC && state.version == DROIDDECK_XR_MODEL_VERSION &&
                    state.count[0] <= DROIDDECK_XR_MODEL_NODES && state.count[1] <= DROIDDECK_XR_MODEL_NODES &&
                    age >= 0 && age < 500000000;
            }
        }
        return false;
    }
private:
    DroidDeckXrModelState *mapping_ = nullptr;
};
