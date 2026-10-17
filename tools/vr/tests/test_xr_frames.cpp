#include "../src/xr_frame_channel.h"
#include "../src/xr_frame_math.h"
#include <cassert>
#include <cstdio>
#include <limits>

static DroidDeckXrFrameHeader Header(uint64_t id = 1) {
    DroidDeckXrFrameHeader header{};
    header.magic = DROIDDECK_XR_FRAME_MAGIC; header.version = DROIDDECK_XR_FRAME_VERSION;
    header.width = 8; header.height = 4; header.bytes = 128; header.color_space = DROIDDECK_XR_COLOR_LINEAR_RGBA8;
    header.frame_id = id; header.captured_ns = xr_frame_now();
    for (int eye = 0; eye < 2; eye++) {
        for (int axis = 0; axis < 3; axis++) header.eye_pose[eye][axis][axis] = 1;
        header.eye_pose[eye][0][3] = eye == 0 ? -0.032f : 0.032f;
        header.eye_pose[eye][1][3] = 1.6f;
        header.projection[eye][0][0] = header.projection[eye][1][1] = 1;
        header.projection[eye][2][2] = -1.002f; header.projection[eye][2][3] = -0.1001f;
        header.projection[eye][3][2] = -1;
    }
    return header;
}

static int Connect(const std::string &path) {
    int descriptor = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    assert(descriptor >= 0);
    sockaddr_un address{}; address.sun_family = AF_UNIX;
    std::strcpy(address.sun_path, path.c_str());
    assert(connect(descriptor, reinterpret_cast<sockaddr *>(&address), sizeof(address)) == 0);
    return descriptor;
}

static void WaitRejected(XrFrameReceiver &receiver, uint64_t count) {
    int64_t deadline = xr_frame_now() + 1000000000LL;
    while (receiver.Rejected() < count && xr_frame_now() < deadline) usleep(1000);
    assert(receiver.Rejected() == count);
}

int main(int argc, char **argv) {
    char directory[256];
    std::snprintf(directory, sizeof(directory), "%s/droiddeck-frames-XXXXXX", argc > 1 ? argv[1] : "/tmp");
    assert(mkdtemp(directory));
    std::string socketPath = std::string(directory) + "/frames";
    auto valid = Header(); assert(xr_frame_valid(valid));
    valid.color_space = DROIDDECK_XR_COLOR_SRGB_RGBA8; assert(xr_frame_valid(valid));
    auto unknownColor = valid; unknownColor.color_space = 3; assert(!xr_frame_valid(unknownColor));
    auto invalid = valid; invalid.version = 3; assert(!xr_frame_valid(invalid));
    invalid = valid; invalid.width = 3; assert(!xr_frame_valid(invalid));
    invalid = valid; invalid.bytes--; assert(!xr_frame_valid(invalid));
    invalid = valid; invalid.width = 8192; invalid.height = 4096; assert(!xr_frame_valid(invalid));
    invalid = valid; invalid.eye_pose[0][0][0] = -1; assert(!xr_frame_valid(invalid));
    invalid = valid; invalid.eye_pose[0][1][1] = 2; assert(!xr_frame_valid(invalid));
    invalid = valid; invalid.projection[0][0][0] = std::numeric_limits<float>::quiet_NaN(); assert(!xr_frame_valid(invalid));
    invalid = valid; invalid.projection[0][3][2] = 0; assert(!xr_frame_valid(invalid));
    invalid = valid; invalid.projection[0][1][1] = -1; assert(!xr_frame_valid(invalid));
    auto view = xr_frame_view(valid, 0);
    assert(view.orientation[3] == 1 && view.position[0] == -0.032f && view.position[1] == 1.6f);
    assert(std::abs(view.fov[0] + 0.785398f) < 0.00001f && std::abs(view.fov[2] - 0.785398f) < 0.00001f);
    auto rotated = valid;
    rotated.eye_pose[0][0][0] = rotated.eye_pose[0][2][2] = 0;
    rotated.eye_pose[0][0][2] = 1; rotated.eye_pose[0][2][0] = -1;
    assert(xr_frame_valid(rotated)); view = xr_frame_view(rotated, 0);
    assert(std::abs(view.orientation[1] - 0.70710678f) < 0.00001f);
    float eyeToHead[3][4]{{1, 0, 0, 0.032f}, {0, 1, 0, 0}, {0, 0, 1, 0}};
    auto paired = rotated;
    xr_frame_eye_pose(paired.eye_pose[0], rotated.eye_pose[0], eyeToHead);
    rotated.eye_pose[0][1][3] = 9;
    assert(paired.eye_pose[0][1][3] == 1.6f && paired.eye_pose[0][2][3] == -0.032f);
    assert(xr_frame_valid(paired));
    for (int axis = 0; axis < 3; axis++) {
        rotated = valid;
        for (int other = 0; other < 3; other++) if (other != axis) rotated.eye_pose[0][other][other] = -1;
        assert(xr_frame_valid(rotated)); view = xr_frame_view(rotated, 0);
        assert(view.orientation[axis] == 1 && view.orientation[3] == 0);
    }
    valid.projection[0][0][2] = 0.2f; valid.projection[0][1][2] = -0.1f;
    view = xr_frame_view(valid, 0);
    assert(std::abs(std::tan(view.fov[0]) + 0.8f) < 0.00001f && std::abs(std::tan(view.fov[2]) - 0.9f) < 0.00001f);
    float left, right, top, bottom;
    xr_frame_projection_raw(view.fov, &left, &right, &top, &bottom);
    float xScale = 2 / (right - left), xOffset = (right + left) / (right - left);
    float yScale = 2 / (bottom - top), yOffset = (bottom + top) / (bottom - top);
    assert(std::abs(xScale - valid.projection[0][0][0]) < 0.00001f);
    assert(std::abs(yScale - valid.projection[0][1][1]) < 0.00001f);
    assert(std::abs(xOffset - valid.projection[0][0][2]) < 0.00001f);
    assert(std::abs(yOffset - valid.projection[0][1][2]) < 0.00001f);
    assert(std::abs(yScale * std::tan(view.fov[2]) - yOffset - 1) < 0.00001f);
    assert(std::abs(yScale * std::tan(view.fov[3]) - yOffset + 1) < 0.00001f);
    XrFrameReceiver receiver; assert(receiver.Start(socketPath));
    struct stat status{}; assert(stat(socketPath.c_str(), &status) == 0 && (status.st_mode & 0777) == 0600);
    XrFrameReceiver other; assert(!other.Start(socketPath));
    XrFrameSender sender;
    std::vector<unsigned char> pixels(valid.bytes, 17);
    assert(sender.Send(socketPath, valid, pixels.data()));
    pixels.assign(valid.bytes, 99);
    DroidDeckXrPixels taken; assert(receiver.Take(&taken));
    assert(taken.header.frame_id == 1 && taken.pixels[0] == 17 && taken.header.eye_pose[0][0][3] == -0.032f);
    assert(taken.header.color_space == DROIDDECK_XR_COLOR_SRGB_RGBA8);
    assert(!receiver.Take(&taken));
    usleep(350000);
    for (uint64_t id = 2; id <= 100; id++) {
        auto header = Header(id); header.eye_pose[0][0][3] = static_cast<float>(id);
        pixels.assign(header.bytes, static_cast<unsigned char>(id));
        assert(sender.Send(socketPath, header, pixels.data()));
    }
    assert(receiver.Take(&taken));
    assert(taken.header.frame_id == 100 && taken.header.eye_pose[0][0][3] == 100 && taken.pixels[0] == 100);
    assert(receiver.Received() == 100);
    assert(!sender.Send(socketPath, Header(100), pixels.data())); WaitRejected(receiver, 1);
    assert(sender.Send(socketPath, Header(101), pixels.data())); assert(receiver.Take(&taken));
    receiver.Stop(); assert(!sender.Send(socketPath, Header(102), pixels.data()));
    assert(receiver.Start(socketPath)); assert(sender.Send(socketPath, Header(1), pixels.data()));
    assert(receiver.Take(&taken));
    receiver.Stop(); assert(receiver.Start(socketPath));
    int delayed = Connect(socketPath);
    usleep(140000);
    auto delayedHeader = Header();
    assert(xr_frame_io(delayed, &delayedHeader, sizeof(delayedHeader), true, xr_frame_now() + 200000000LL));
    usleep(110000);
    assert(xr_frame_io(delayed, pixels.data(), pixels.size(), true, xr_frame_now() + 200000000LL));
    uint64_t acknowledged = 0;
    assert(xr_frame_io(delayed, &acknowledged, sizeof(acknowledged), false, xr_frame_now() + 200000000LL));
    assert(acknowledged == delayedHeader.frame_id && receiver.Take(&taken));
    close(delayed);
    receiver.Stop(); assert(receiver.Start(socketPath));
    int descriptor = Connect(socketPath); auto header = Header(); header.version = 3;
    assert(xr_frame_io(descriptor, &header, sizeof(header), true, xr_frame_now() + 200000000LL));
    close(descriptor); WaitRejected(receiver, 1); assert(!receiver.Take(&taken));
    descriptor = Connect(socketPath); header = Header(); header.captured_ns += 1000000000LL;
    assert(xr_frame_io(descriptor, &header, sizeof(header), true, xr_frame_now() + 200000000LL));
    close(descriptor); WaitRejected(receiver, 2);
    descriptor = Connect(socketPath); header = Header(); header.captured_ns -= 1000000000LL;
    assert(xr_frame_io(descriptor, &header, sizeof(header), true, xr_frame_now() + 200000000LL));
    close(descriptor); WaitRejected(receiver, 3);
    descriptor = Connect(socketPath); header = Header();
    assert(xr_frame_io(descriptor, &header, sizeof(header), true, xr_frame_now() + 200000000LL));
    assert(send(descriptor, pixels.data(), 1, MSG_NOSIGNAL) == 1);
    close(descriptor); WaitRejected(receiver, 4); assert(!receiver.Take(&taken));
    descriptor = Connect(socketPath); header = Header();
    assert(xr_frame_io(descriptor, &header, sizeof(header), true, xr_frame_now() + 200000000LL));
    WaitRejected(receiver, 5); close(descriptor);
    assert(!sender.Send(socketPath, Header(200), pixels.data()));
    assert(sender.Send(socketPath, Header(201), pixels.data()));
    receiver.Stop();
    int stale = socket(AF_UNIX, SOCK_STREAM, 0); sockaddr_un address{}; address.sun_family = AF_UNIX;
    std::strcpy(address.sun_path, socketPath.c_str());
    assert(bind(stale, reinterpret_cast<sockaddr *>(&address), sizeof(address)) == 0); close(stale);
    assert(receiver.Start(socketPath)); receiver.Stop();
    int regular = open(socketPath.c_str(), O_CREAT | O_RDWR, 0600); assert(regular >= 0); close(regular);
    assert(!receiver.Start(socketPath)); assert(stat(socketPath.c_str(), &status) == 0 && S_ISREG(status.st_mode));
    unlink(socketPath.c_str()); unlink((socketPath + ".lock").c_str()); rmdir(directory);
    std::puts("XR frame validation passed: owned pixels, paired render pose, latest mailbox, bounds, projection, restart, stale socket, replay, malformed and timeout rejection");
}
