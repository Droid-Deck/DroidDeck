#pragma once
#include "xr_frame_channel.h"
#include <array>

#define DROIDDECK_XR_GPU_SOCKET "droiddeck-xr-gpu"
constexpr uint32_t XR_GPU_MAGIC = 0x47584444u;
constexpr uint32_t XR_GPU_VERSION = 1;
constexpr unsigned XR_GPU_SLOTS = 3;
constexpr uint32_t XR_GPU_BGRA = 1;

struct XrGpuRequest { uint32_t magic = XR_GPU_MAGIC, version = XR_GPU_VERSION, width{}, height{}; };
struct XrGpuPoolInfo {
    uint32_t magic = XR_GPU_MAGIC, version = XR_GPU_VERSION, width{}, height{};
    uint64_t sizes[XR_GPU_SLOTS]{};
};
struct XrGpuFrame { DroidDeckXrFrameHeader header{}; uint32_t slot{}, flags{}; };
static_assert(sizeof(XrGpuRequest) == 16 && sizeof(XrGpuPoolInfo) == 40 && sizeof(XrGpuFrame) == 272,
              "XR GPU protocol layout");

inline bool xr_gpu_dimensions(uint32_t width, uint32_t height) {
    return width >= 2 && !(width & 1) && height && width <= 8192 && height <= 4096
        && uint64_t(width) * height * 4 <= DROIDDECK_XR_FRAME_MAX_BYTES;
}

enum class XrGpuFrameState { Invalid, Stale, Ready };
inline XrGpuFrameState xr_gpu_frame_state(const XrGpuFrame &frame, uint32_t width, uint32_t height,
                                         uint64_t previous, int64_t now) {
    if (frame.slot >= XR_GPU_SLOTS || (frame.flags & ~XR_GPU_BGRA) || !xr_frame_valid(frame.header)
        || frame.header.width != width || frame.header.height != height || frame.header.frame_id <= previous
        || frame.header.captured_ns > now + 100000000LL) return XrGpuFrameState::Invalid;
    return now - frame.header.captured_ns > 200000000LL ? XrGpuFrameState::Stale : XrGpuFrameState::Ready;
}

// SEQPACKET keeps descriptor transfer and its metadata in one indivisible message.
inline bool xr_gpu_send(int socket, const void *data, size_t size, const int *fds = nullptr, unsigned count = 0) {
    if (count > XR_GPU_SLOTS) return false;
    iovec bytes{const_cast<void *>(data), size};
    alignas(cmsghdr) unsigned char control[CMSG_SPACE(XR_GPU_SLOTS * sizeof(int))]{};
    msghdr message{}; message.msg_iov = &bytes; message.msg_iovlen = 1;
    if (count) {
        message.msg_control = control; message.msg_controllen = CMSG_SPACE(count * sizeof(int));
        auto *item = CMSG_FIRSTHDR(&message); item->cmsg_level = SOL_SOCKET; item->cmsg_type = SCM_RIGHTS;
        item->cmsg_len = CMSG_LEN(count * sizeof(int)); std::memcpy(CMSG_DATA(item), fds, count * sizeof(int));
    }
    ssize_t result;
    do { result = sendmsg(socket, &message, MSG_NOSIGNAL | MSG_DONTWAIT); } while (result < 0 && errno == EINTR);
    return result == static_cast<ssize_t>(size);
}

inline bool xr_gpu_receive(int socket, void *data, size_t size, int *fds = nullptr, unsigned count = 0) {
    if (count > XR_GPU_SLOTS) return false;
    iovec bytes{data, size};
    alignas(cmsghdr) unsigned char control[CMSG_SPACE(16 * sizeof(int))]{};
    msghdr message{}; message.msg_iov = &bytes; message.msg_iovlen = 1;
    message.msg_control = control; message.msg_controllen = sizeof(control);
    ssize_t result;
    do { result = recvmsg(socket, &message, MSG_DONTWAIT | MSG_CMSG_CLOEXEC); } while (result < 0 && errno == EINTR);
    unsigned received = 0;
    std::array<int, XR_GPU_SLOTS> owned{{-1, -1, -1}};
    for (auto *item = CMSG_FIRSTHDR(&message); result >= 0 && item; item = CMSG_NXTHDR(&message, item)) {
        if (item->cmsg_level != SOL_SOCKET || item->cmsg_type != SCM_RIGHTS) continue;
        auto *handles = reinterpret_cast<int *>(CMSG_DATA(item));
        for (unsigned index = 0; index < (item->cmsg_len - CMSG_LEN(0)) / sizeof(int); index++) {
            if (received < owned.size()) owned[received] = handles[index]; else close(handles[index]);
            received++;
        }
    }
    bool valid = result == static_cast<ssize_t>(size) && !(message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) && received == count;
    for (unsigned index = 0; index < owned.size(); index++) {
        if (valid && index < count) fds[index] = owned[index];
        else if (owned[index] >= 0) close(owned[index]);
    }
    return valid;
}

inline bool xr_gpu_wait(int socket, int timeout) {
    pollfd ready{socket, POLLIN, 0};
    int result;
    do { result = poll(&ready, 1, timeout); } while (result < 0 && errno == EINTR);
    return result > 0 && (ready.revents & POLLIN);
}

class XrGpuSender {
public:
    ~XrGpuSender() { Close(); }
    bool Connect(const char *filename, uint32_t width, uint32_t height, XrGpuPoolInfo &pool,
                 std::array<int, XR_GPU_SLOTS> &fds) {
        Close();
        if (!xr_gpu_dimensions(width, height) || !filename || strlen(filename) >= sizeof(sockaddr_un::sun_path)) return false;
        socket_ = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
        if (socket_ < 0) return false;
        sockaddr_un address{}; address.sun_family = AF_UNIX; std::strcpy(address.sun_path, filename);
        if (connect(socket_, reinterpret_cast<sockaddr *>(&address), sizeof(address)) || !xr_frame_peer(socket_)) { Close(); return false; }
        XrGpuRequest request; request.width = width; request.height = height;
        if (!xr_gpu_send(socket_, &request, sizeof(request)) || !xr_gpu_wait(socket_, 2000)
            || !xr_gpu_receive(socket_, &pool, sizeof(pool), fds.data(), fds.size())) { Close(); return false; }
        bool valid = pool.magic == XR_GPU_MAGIC && pool.version == XR_GPU_VERSION && pool.width == width && pool.height == height;
        const uint64_t bytes = uint64_t(width) * height * 4;
        for (unsigned index = 0; index < fds.size(); index++) {
            struct stat status{};
            if (fstat(fds[index], &status) || pool.sizes[index] < bytes || pool.sizes[index] > bytes + 65536
                || status.st_size != static_cast<int64_t>(pool.sizes[index])) valid = false;
        }
        if (!valid) { for (int &fd : fds) { close(fd); fd = -1; } Close(); }
        return valid;
    }
    bool Send(const DroidDeckXrFrameHeader &header, uint32_t slot, uint32_t flags = 0) {
        if (socket_ < 0 || slot >= XR_GPU_SLOTS || (flags & ~XR_GPU_BGRA) || !xr_frame_valid(header)) return false;
        XrGpuFrame frame; frame.header = header; frame.slot = slot; frame.flags = flags;
        uint64_t ack{};
        if (!xr_gpu_send(socket_, &frame, sizeof(frame)) || !xr_gpu_wait(socket_, 5000)
            || !xr_gpu_receive(socket_, &ack, sizeof(ack)) || ack != header.frame_id) { Close(); return false; }
        return true;
    }
    void Close() { if (socket_ >= 0) close(socket_); socket_ = -1; }
private:
    int socket_ = -1;
};
