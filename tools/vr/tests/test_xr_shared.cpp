#include "../src/xr_frame_channel.h"
#include <cassert>
#include <cstdio>
#include <sys/syscall.h>

static DroidDeckXrFrameHeader Header() {
    DroidDeckXrFrameHeader header{};
    header.magic = DROIDDECK_XR_FRAME_MAGIC; header.version = DROIDDECK_XR_FRAME_VERSION;
    header.width = 8; header.height = 4; header.bytes = 128;
    header.color_space = DROIDDECK_XR_COLOR_SRGB_RGBA8; header.frame_id = 1; header.captured_ns = xr_frame_now();
    for (int eye = 0; eye < 2; eye++) {
        for (int axis = 0; axis < 3; axis++) header.eye_pose[eye][axis][axis] = 1;
        header.projection[eye][0][0] = header.projection[eye][1][1] = 1;
        header.projection[eye][3][2] = -1;
    }
    return header;
}

int main() {
    char directory[] = "/tmp/droiddeck-shared-XXXXXX"; assert(mkdtemp(directory));
    std::string path = std::string(directory) + "/frames";
    int listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0); assert(listener >= 0);
    sockaddr_un address{}; address.sun_family = AF_UNIX; std::strcpy(address.sun_path, path.c_str());
    assert(bind(listener, reinterpret_cast<sockaddr *>(&address), sizeof(address)) == 0);
    assert(listen(listener, 1) == 0);
    int buffer = syscall(SYS_memfd_create, "frame-test", 1); assert(buffer >= 0);
    assert(ftruncate(buffer, 128) == 0);
    unsigned char pixels[128]; std::memset(pixels, 27, sizeof(pixels));
    assert(pwrite(buffer, pixels, sizeof(pixels), 0) == sizeof(pixels));
    auto header = Header();
    XrFrameSender sender;
    std::thread peer([&] {
        int client = accept(listener, nullptr, nullptr); assert(client >= 0);
        DroidDeckXrFrameHeader received{}; XrFrameDescriptor shared;
        assert(xr_frame_header(client, received, shared, xr_frame_now() + 1000000000LL));
        assert(received.version == DROIDDECK_XR_FRAME_SHARED_VERSION && xr_frame_valid(received));
        assert(shared.fd >= 0 && (fcntl(shared.fd, F_GETFD) & FD_CLOEXEC));
        unsigned char copy[128]; assert(pread(shared.fd, copy, sizeof(copy), 0) == sizeof(copy));
        for (auto value : copy) assert(value == 27);
        assert(xr_frame_io(client, &received.frame_id, sizeof(received.frame_id), true, xr_frame_now() + 1000000000LL));
        close(client);
    });
    assert(sender.SendShared(path, header, buffer)); peer.join();
    assert(!sender.SharedLeaseLost());
    XrFrameSender lost;
    std::thread disconnected([&] {
        int client = accept(listener, nullptr, nullptr); assert(client >= 0);
        DroidDeckXrFrameHeader received{}; XrFrameDescriptor shared;
        assert(xr_frame_header(client, received, shared, xr_frame_now() + 1000000000LL));
        assert(shared.fd >= 0);
        close(client);
    });
    assert(!lost.SendShared(path, header, buffer)); disconnected.join();
    assert(lost.SharedLeaseLost());
    assert(!lost.SendShared(path, header, buffer));
    close(listener); unlink(path.c_str());
    XrFrameSender absent;
    assert(!absent.SendShared(path, header, buffer)); assert(!absent.SharedLeaseLost());
    XrFrameReceiver receiver; assert(receiver.Start(path));
    XrFrameSender invalid;
    // An ordinary file is not a DMA-BUF and must never be acknowledged as one.
    assert(!invalid.SendShared(path, Header(), buffer));
    assert(invalid.SharedLeaseLost() && receiver.Rejected() == 1);
    DroidDeckXrPixels frame; assert(!receiver.Take(&frame));
    receiver.Stop(); close(buffer); unlink((path + ".lock").c_str()); rmdir(directory);
    std::puts("XR shared handoff passed: descriptor ownership, copied payload, acknowledgement, disconnect quarantine, absent peer and invalid buffer rejection");
}
