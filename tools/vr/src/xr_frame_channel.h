#pragma once
#include "xr_frame.h"
#include <atomic>
#include <cerrno>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <poll.h>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/ioctl.h>
#include <linux/dma-buf.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

inline int64_t xr_frame_now() {
    timespec now{}; clock_gettime(CLOCK_MONOTONIC, &now);
    return (int64_t)now.tv_sec * 1000000000LL + now.tv_nsec;
}

inline bool xr_frame_io(int descriptor, void *data, size_t size, bool write, int64_t deadline) {
    auto *bytes = static_cast<unsigned char *>(data);
    while (size) {
        int64_t remaining = deadline - xr_frame_now();
        if (remaining <= 0) return false;
        pollfd ready{descriptor, (short)(write ? POLLOUT : POLLIN), 0};
        int result = poll(&ready, 1, (int)((remaining + 999999) / 1000000));
        if (result < 0 && errno == EINTR) continue;
        if (result <= 0 || !(ready.revents & ready.events)) return false;
        ssize_t done = write ? send(descriptor, bytes, size, MSG_NOSIGNAL | MSG_DONTWAIT)
                             : recv(descriptor, bytes, size, MSG_DONTWAIT);
        if (done < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        if (done <= 0) return false;
        bytes += done; size -= done;
    }
    return true;
}

inline bool xr_frame_peer(int descriptor) {
    struct { pid_t pid; uid_t uid; gid_t gid; } peer{};
    socklen_t size = sizeof(peer);
    return getsockopt(descriptor, SOL_SOCKET, SO_PEERCRED, &peer, &size) == 0
        && size == sizeof(peer) && peer.uid == getuid();
}

struct XrFrameDescriptor {
    int fd = -1;
    ~XrFrameDescriptor() { if (fd >= 0) close(fd); }
    XrFrameDescriptor() = default;
    XrFrameDescriptor(const XrFrameDescriptor &) = delete;
    XrFrameDescriptor &operator=(const XrFrameDescriptor &) = delete;
};

inline bool xr_frame_header(int socket, DroidDeckXrFrameHeader &header, XrFrameDescriptor &shared, int64_t deadline) {
    for (;;) {
        int64_t remaining = deadline - xr_frame_now();
        if (remaining <= 0) return false;
        pollfd ready{socket, POLLIN, 0};
        int result = poll(&ready, 1, (int)((remaining + 999999) / 1000000));
        if (result < 0 && errno == EINTR) continue;
        if (result <= 0 || !(ready.revents & POLLIN)) return false;
        alignas(cmsghdr) unsigned char control[CMSG_SPACE(8 * sizeof(int))]{};
        iovec data{&header, sizeof(header)};
        msghdr message{}; message.msg_iov = &data; message.msg_iovlen = 1;
        message.msg_control = control; message.msg_controllen = sizeof(control);
        ssize_t bytes = recvmsg(socket, &message, MSG_DONTWAIT | MSG_CMSG_CLOEXEC);
        if (bytes < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        if (bytes <= 0) return false;
        unsigned count = 0;
        for (auto *item = CMSG_FIRSTHDR(&message); item; item = CMSG_NXTHDR(&message, item)) {
            if (item->cmsg_level != SOL_SOCKET || item->cmsg_type != SCM_RIGHTS) continue;
            size_t descriptors = (item->cmsg_len - CMSG_LEN(0)) / sizeof(int);
            auto *fds = reinterpret_cast<int *>(CMSG_DATA(item));
            for (size_t index = 0; index < descriptors; index++) {
                if (++count == 1) shared.fd = fds[index]; else close(fds[index]);
            }
        }
        if ((message.msg_flags & (MSG_CTRUNC | MSG_TRUNC)) || count > 1) return false;
        return xr_frame_io(socket, reinterpret_cast<unsigned char *>(&header) + bytes, sizeof(header) - bytes, false, deadline);
    }
}

inline bool xr_frame_shared_pixels(int descriptor, unsigned char *pixels, size_t bytes) {
    struct stat status{};
    if (fstat(descriptor, &status) != 0 || status.st_size < static_cast<int64_t>(bytes)) return false;
    dma_buf_sync sync{DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ};
    if (ioctl(descriptor, DMA_BUF_IOCTL_SYNC, &sync) != 0) return false;
    void *mapping = mmap(nullptr, bytes, PROT_READ, MAP_SHARED, descriptor, 0);
    bool success = mapping != MAP_FAILED;
    if (success) { std::memcpy(pixels, mapping, bytes); munmap(mapping, bytes); }
    sync.flags = DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ;
    return ioctl(descriptor, DMA_BUF_IOCTL_SYNC, &sync) == 0 && success;
}

struct DroidDeckXrPixels {
    DroidDeckXrFrameHeader header{};
    std::vector<unsigned char> pixels;
};

class XrFrameReceiver {
public:
    ~XrFrameReceiver() { Stop(); }
    bool Start(const std::string &filename) {
        if (listener_ >= 0 || filename.empty() || filename.size() >= sizeof(sockaddr_un::sun_path)) return false;
        lock_ = open((filename + ".lock").c_str(), O_CREAT | O_RDWR | O_CLOEXEC | O_NOFOLLOW, 0600);
        struct stat status{};
        if (lock_ < 0) return false;
        if (fstat(lock_, &status) != 0 || !S_ISREG(status.st_mode) || status.st_uid != getuid()
            || flock(lock_, LOCK_EX | LOCK_NB) != 0) { close(lock_); lock_ = -1; return false; }
        if (lstat(filename.c_str(), &status) == 0) {
            if (!S_ISSOCK(status.st_mode) || status.st_uid != getuid() || unlink(filename.c_str()) != 0) {
                close(lock_); lock_ = -1; return false;
            }
        } else if (errno != ENOENT) { close(lock_); lock_ = -1; return false; }
        sockaddr_un address{}; address.sun_family = AF_UNIX;
        std::strcpy(address.sun_path, filename.c_str());
        listener_ = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
        if (listener_ < 0) { Stop(); return false; }
        if (bind(listener_, reinterpret_cast<sockaddr *>(&address), sizeof(address)) != 0) { Stop(); return false; }
        filename_ = filename;
        if (chmod(filename.c_str(), 0600) != 0 || listen(listener_, 2) != 0) { Stop(); return false; }
        { std::lock_guard<std::mutex> lock(mutex_); pending_ = {}; }
        received_ = rejected_ = 0;
        stopping_ = false;
        thread_ = std::thread([this] { Loop(); });
        return true;
    }
    void Stop() {
        stopping_ = true;
        if (thread_.joinable()) thread_.join();
        if (listener_ >= 0) close(listener_);
        listener_ = -1;
        if (!filename_.empty()) unlink(filename_.c_str());
        filename_.clear();
        if (lock_ >= 0) close(lock_);
        lock_ = -1;
        std::lock_guard<std::mutex> lock(mutex_);
        pending_ = {}; recycled_.clear();
    }
    bool Take(DroidDeckXrPixels *out) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (pending_.pixels.empty()) return false;
        *out = std::move(pending_);
        pending_ = {};
        return true;
    }
    void Recycle(std::vector<unsigned char> pixels) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (recycled_.size() < 3) recycled_.push_back(std::move(pixels));
    }
    uint64_t Rejected() const { return rejected_; }
    uint64_t Received() const { return received_; }
private:
    int listener_ = -1;
    int lock_ = -1;
    std::string filename_;
    std::atomic<bool> stopping_{false};
    std::atomic<uint64_t> rejected_{0}, received_{0};
    std::thread thread_;
    std::mutex mutex_;
    DroidDeckXrPixels pending_;
    std::vector<std::vector<unsigned char>> recycled_;
    void Loop() {
        while (!stopping_) {
            pollfd ready{listener_, POLLIN, 0};
            if (poll(&ready, 1, 50) <= 0) continue;
            int client = accept4(listener_, nullptr, nullptr, SOCK_CLOEXEC | SOCK_NONBLOCK);
            if (client < 0) continue;
            if (!xr_frame_peer(client)) { rejected_++; close(client); continue; }
            uint64_t lastFrame = 0;
            while (!stopping_) {
                pollfd idle{client, POLLIN, 0};
                int available;
                do {
                    available = poll(&idle, 1, 50);
                } while (!stopping_ && (available == 0 || (available < 0 && errno == EINTR)));
                if (stopping_ || available <= 0 || !(idle.revents & POLLIN)) break;
                DroidDeckXrPixels frame;
                XrFrameDescriptor shared;
                int64_t deadline = xr_frame_now() + 200000000LL;
                if (!xr_frame_header(client, frame.header, shared, deadline)) break;
                int64_t now = xr_frame_now();
                if (!xr_frame_valid(frame.header) || frame.header.captured_ns > now
                    || now - frame.header.captured_ns > 500000000LL || frame.header.frame_id <= lastFrame
                    || (frame.header.version == DROIDDECK_XR_FRAME_SHARED_VERSION) != (shared.fd >= 0)) { rejected_++; break; }
                try {
                    {
                        std::lock_guard<std::mutex> lock(mutex_);
                        if (!recycled_.empty()) {
                            frame.pixels = std::move(recycled_.back()); recycled_.pop_back();
                        }
                    }
                    frame.pixels.resize(frame.header.bytes);
                }
                catch (...) { rejected_++; break; }
                deadline = xr_frame_now() + 200000000LL;
                bool copied = shared.fd >= 0 ? xr_frame_shared_pixels(shared.fd, frame.pixels.data(), frame.pixels.size())
                    : xr_frame_io(client, frame.pixels.data(), frame.pixels.size(), false, deadline);
                if (!copied) { rejected_++; break; }
                if (xr_frame_now() - frame.header.captured_ns > 500000000LL) { rejected_++; break; }
                uint64_t acknowledgement = frame.header.frame_id;
                lastFrame = acknowledgement;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    if (!pending_.pixels.empty() && recycled_.size() < 3) recycled_.push_back(std::move(pending_.pixels));
                    pending_ = std::move(frame);
                }
                received_++;
                if (!xr_frame_io(client, &acknowledgement, sizeof(acknowledgement), true, deadline)) break;
            }
            close(client);
        }
    }
};

class XrFrameSender {
public:
    XrFrameSender() = default;
    XrFrameSender(const XrFrameSender &) = delete;
    XrFrameSender &operator=(const XrFrameSender &) = delete;
    ~XrFrameSender() { Disconnect(); }
    bool Send(const std::string &filename, const DroidDeckXrFrameHeader &header, const unsigned char *pixels) {
        return SendFrame(filename, header, pixels, -1);
    }
    bool SendShared(const std::string &filename, DroidDeckXrFrameHeader header, int shared) {
        if (shared < 0 || sharedLeaseLost_) return false;
        header.version = DROIDDECK_XR_FRAME_SHARED_VERSION;
        return SendFrame(filename, header, nullptr, shared);
    }
    bool SharedLeaseLost() const { return sharedLeaseLost_; }
private:
    int descriptor_ = -1;
    bool sharedLeaseLost_ = false;
    std::string filename_;
    bool SendFrame(const std::string &filename, const DroidDeckXrFrameHeader &header, const unsigned char *pixels, int shared) {
        if ((!pixels && shared < 0) || !xr_frame_valid(header) || filename.empty()
            || filename.size() >= sizeof(sockaddr_un::sun_path)
            || (header.version == DROIDDECK_XR_FRAME_SHARED_VERSION) != (shared >= 0)) return false;
        if (filename_ != filename) Disconnect();
        int64_t deadline = xr_frame_now() + 200000000LL;
        if (descriptor_ < 0) {
            sockaddr_un address{}; address.sun_family = AF_UNIX;
            std::strcpy(address.sun_path, filename.c_str());
            descriptor_ = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
            if (descriptor_ < 0) return false;
            int result = connect(descriptor_, reinterpret_cast<sockaddr *>(&address), sizeof(address));
            if (result != 0 && errno == EINPROGRESS) {
                pollfd ready{descriptor_, POLLOUT, 0};
                int error = 0; socklen_t size = sizeof(error);
                result = poll(&ready, 1, 200) > 0 && getsockopt(descriptor_, SOL_SOCKET, SO_ERROR, &error, &size) == 0
                    && error == 0 ? 0 : -1;
            }
            if (result != 0 || !xr_frame_peer(descriptor_)) {
                Disconnect(); return false;
            }
            filename_ = filename;
        }
        uint64_t acknowledgement = 0;
        bool sent = false;
        if (shared >= 0) {
            iovec data{const_cast<DroidDeckXrFrameHeader *>(&header), sizeof(header)};
            alignas(cmsghdr) unsigned char control[CMSG_SPACE(sizeof(int))]{};
            msghdr message{}; message.msg_iov = &data; message.msg_iovlen = 1;
            message.msg_control = control; message.msg_controllen = sizeof(control);
            auto *item = CMSG_FIRSTHDR(&message); item->cmsg_level = SOL_SOCKET; item->cmsg_type = SCM_RIGHTS;
            item->cmsg_len = CMSG_LEN(sizeof(int)); std::memcpy(CMSG_DATA(item), &shared, sizeof(shared));
            ssize_t bytes;
            do { bytes = sendmsg(descriptor_, &message, MSG_NOSIGNAL | MSG_DONTWAIT); } while (bytes < 0 && errno == EINTR);
            if (bytes > 0) {
                sharedLeaseLost_ = true;
                sent = xr_frame_io(descriptor_, reinterpret_cast<unsigned char *>(data.iov_base) + bytes,
                                   sizeof(header) - bytes, true, deadline);
            }
        } else sent = xr_frame_io(descriptor_, const_cast<DroidDeckXrFrameHeader *>(&header), sizeof(header), true, deadline)
            && xr_frame_io(descriptor_, const_cast<unsigned char *>(pixels), header.bytes, true, deadline);
        if (!sent
            || !xr_frame_io(descriptor_, &acknowledgement, sizeof(acknowledgement), false, deadline)
            || acknowledgement != header.frame_id) { Disconnect(); return false; }
        sharedLeaseLost_ = false;
        return true;
    }
    void Disconnect() { if (descriptor_ >= 0) close(descriptor_); descriptor_ = -1; filename_.clear(); }
};
