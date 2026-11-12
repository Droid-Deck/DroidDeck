#pragma once
#include <cstdint>
#include <cstddef>
#include <cstring>
#include <string>
#include <type_traits>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

inline int64_t xr_monotonic_ns() {
    timespec time{};
    clock_gettime(CLOCK_MONOTONIC, &time);
    return int64_t(time.tv_sec) * 1000000000LL + time.tv_nsec;
}

template<class Packet> class XrAtomicFile {
    static_assert(std::is_trivially_copyable<Packet>::value, "packet must be POD");
    static_assert(sizeof(Packet) % 4 == 0 && offsetof(Packet, sequence) == 12, "atomic word layout");
    static_assert(__atomic_always_lock_free(4, nullptr), "process-shared atomics must be lock-free");
public:
    XrAtomicFile() = default;
    XrAtomicFile(const XrAtomicFile &) = delete;
    XrAtomicFile &operator=(const XrAtomicFile &) = delete;
    ~XrAtomicFile() { close(); }

    bool openWriter(const std::string &path) {
        close();
        fd_ = ::open(path.c_str(), O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0600);
        struct stat status{};
        if (fd_ < 0 || fstat(fd_, &status) || !S_ISREG(status.st_mode) || status.st_uid != getuid()
            || flock(fd_, LOCK_EX | LOCK_NB) || (status.st_size != 0 && status.st_size != sizeof(Packet))
            || fchmod(fd_, 0600) || ftruncate(fd_, sizeof(Packet))) { close(); return false; }
        void *map = mmap(nullptr, sizeof(Packet), PROT_READ | PROT_WRITE, MAP_SHARED, fd_, 0);
        if (map == MAP_FAILED) { close(); return false; }
        words_ = static_cast<uint32_t *>(map);
        writer_ = true;
        return true;
    }

    bool write(const Packet &packet) {
        if (!writer_ || !words_) return false;
        uint32_t words[sizeof(Packet) / 4];
        std::memcpy(words, &packet, sizeof(packet));
        uint32_t sequence = __atomic_load_n(words_ + 3, __ATOMIC_RELAXED) | 1u;
        __atomic_store_n(words_ + 3, sequence, __ATOMIC_SEQ_CST);
        // Publish the in-progress marker before any payload word on weakly ordered CPUs.
        __atomic_thread_fence(__ATOMIC_SEQ_CST);
        for (size_t i = 0; i < sizeof(Packet) / 4; ++i)
            if (i != 3) __atomic_store_n(words_ + i, words[i], __ATOMIC_RELAXED);
        __atomic_store_n(words_ + 3, sequence + 1u, __ATOMIC_RELEASE);
        return true;
    }

    bool read(const std::string &path, Packet &packet) {
        if (writer_) return false;
        struct stat current{}, mapped{};
        if (lstat(path.c_str(), &current) || !S_ISREG(current.st_mode) || current.st_size != sizeof(Packet)) {
            close(); return false;
        }
        if (fd_ >= 0 && (fstat(fd_, &mapped) || mapped.st_ino != current.st_ino || mapped.st_dev != current.st_dev)) close();
        if (fd_ < 0) {
            fd_ = ::open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
            if (fd_ < 0 || fstat(fd_, &mapped) || !S_ISREG(mapped.st_mode) || mapped.st_size != sizeof(Packet)) {
                close(); return false;
            }
            void *map = mmap(nullptr, sizeof(Packet), PROT_READ, MAP_SHARED, fd_, 0);
            if (map == MAP_FAILED) { close(); return false; }
            words_ = static_cast<uint32_t *>(map);
        }
        for (int attempt = 0; attempt < 3; ++attempt) {
            const uint32_t before = __atomic_load_n(words_ + 3, __ATOMIC_ACQUIRE);
            if (before & 1u) continue;
            uint32_t words[sizeof(Packet) / 4];
            for (size_t i = 0; i < sizeof(Packet) / 4; ++i) words[i] = __atomic_load_n(words_ + i, __ATOMIC_RELAXED);
            __atomic_thread_fence(__ATOMIC_ACQUIRE);
            const uint32_t after = __atomic_load_n(words_ + 3, __ATOMIC_ACQUIRE);
            if (before != after) continue;
            words[3] = after;
            std::memcpy(&packet, words, sizeof(packet));
            return true;
        }
        return false;
    }

    void close() {
        if (words_) munmap(words_, sizeof(Packet));
        if (fd_ >= 0) ::close(fd_);
        words_ = nullptr;
        fd_ = -1;
        writer_ = false;
    }
private:
    int fd_ = -1;
    uint32_t *words_ = nullptr;
    bool writer_ = false;
};
