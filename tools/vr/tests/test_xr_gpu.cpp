#include "../src/xr_gpu_channel.h"
#include <cassert>
#include <cstdio>
#include <dirent.h>

static unsigned descriptors() {
    DIR *directory = opendir("/proc/self/fd"); assert(directory);
    unsigned count = 0; while (readdir(directory)) count++; closedir(directory); return count;
}

int main() {
    assert(xr_gpu_dimensions(3360, 1760));
    assert(!xr_gpu_dimensions(3, 4) && !xr_gpu_dimensions(8192, 4096));
    int pair[2]; assert(!socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, pair));
    int fd = memfd_create("xr-gpu-test", MFD_CLOEXEC); assert(fd >= 0 && !ftruncate(fd, 4096));
    XrGpuPoolInfo pool; int sent[3]{fd, fd, fd}; std::array<int, 3> received{{-1, -1, -1}};
    assert(xr_gpu_send(pair[0], &pool, sizeof(pool), sent, 3));
    assert(xr_gpu_receive(pair[1], &pool, sizeof(pool), received.data(), 3));
    for (int handle : received) { assert(fcntl(handle, F_GETFD) & FD_CLOEXEC); close(handle); }
    auto count = descriptors();
    assert(xr_gpu_send(pair[0], &pool, sizeof(pool), sent, 3));
    assert(!xr_gpu_receive(pair[1], &pool, sizeof(pool)) && descriptors() == count);
    assert(xr_gpu_send(pair[0], &pool, sizeof(pool), sent, 2));
    assert(!xr_gpu_receive(pair[1], &pool, sizeof(pool), received.data(), 3) && descriptors() == count);
    assert(xr_gpu_send(pair[0], &pool, sizeof(pool), sent, 3));
    assert(!xr_gpu_receive(pair[1], &pool, sizeof(pool) - 1, received.data(), 3) && descriptors() == count);
    close(pair[0]); close(pair[1]);

    char directory[] = "/tmp/dd-xr-gpu-XXXXXX"; assert(mkdtemp(directory));
    std::string filename = std::string(directory) + "/gpu";
    int listener = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0); assert(listener >= 0);
    sockaddr_un address{}; address.sun_family = AF_UNIX; strcpy(address.sun_path, filename.c_str());
    assert(!bind(listener, reinterpret_cast<sockaddr *>(&address), sizeof(address)) && !listen(listener, 1));
    for (unsigned attempt = 0; attempt < 3; attempt++) {
        std::thread server([&] {
            int peer = accept4(listener, nullptr, nullptr, SOCK_CLOEXEC); assert(peer >= 0 && xr_frame_peer(peer));
            XrGpuRequest request; assert(xr_gpu_wait(peer, 1000) && xr_gpu_receive(peer, &request, sizeof(request)));
            assert(request.width == 32 && request.height == 32);
            XrGpuPoolInfo reply; reply.width = reply.height = 32;
            for (auto &size : reply.sizes) size = attempt == 2 ? 4095 : 4096;
            assert(xr_gpu_send(peer, &reply, sizeof(reply), sent, 3));
            if (attempt != 2) {
                XrGpuFrame frame; assert(xr_gpu_wait(peer, 1000) && xr_gpu_receive(peer, &frame, sizeof(frame)));
                assert(frame.slot == 2 && frame.header.frame_id == 7);
                auto now = xr_frame_now();
                frame.header.captured_ns = now;
                assert(xr_gpu_frame_state(frame, 32, 32, 0, now) == XrGpuFrameState::Ready);
                assert(xr_gpu_frame_state(frame, 32, 32, 7, now) == XrGpuFrameState::Invalid);
                frame.header.captured_ns = now - 1000000000LL;
                assert(xr_gpu_frame_state(frame, 32, 32, 0, now) == XrGpuFrameState::Stale);
                frame.slot = 3;
                assert(xr_gpu_frame_state(frame, 32, 32, 0, now) == XrGpuFrameState::Invalid);
                if (!attempt) std::this_thread::sleep_for(std::chrono::milliseconds(700));
                uint64_t ack = attempt ? 8 : 7;
                assert(xr_gpu_send(peer, &ack, sizeof(ack)));
            }
            close(peer);
        });
        XrGpuSender sender; received.fill(-1);
        bool connected = sender.Connect(filename.c_str(), 32, 32, pool, received);
        assert(connected == (attempt != 2));
        if (connected) {
            for (int handle : received) close(handle);
            DroidDeckXrFrameHeader header{};
            header.magic = DROIDDECK_XR_FRAME_MAGIC; header.version = DROIDDECK_XR_FRAME_VERSION;
            header.width = header.height = 32; header.bytes = 4096; header.color_space = DROIDDECK_XR_COLOR_SRGB_RGBA8;
            header.frame_id = 7; header.captured_ns = xr_frame_now();
            for (int eye = 0; eye < 2; eye++) {
                for (int axis = 0; axis < 3; axis++) header.eye_pose[eye][axis][axis] = 1;
                header.projection[eye][0][0] = header.projection[eye][1][1] = 1;
                header.projection[eye][3][2] = -1;
            }
            assert(sender.Send(header, 2) == (attempt == 0));
            if (attempt) assert(!sender.Send(header, 2));
        } else for (int handle : received) assert(handle == -1);
        server.join();
    }
    close(fd); close(listener); unlink(filename.c_str()); rmdir(directory);
    puts("XR GPU transport passed: atomic FD ownership, malformed pool rejection, truncation cleanup and exact completion acknowledgement");
}
