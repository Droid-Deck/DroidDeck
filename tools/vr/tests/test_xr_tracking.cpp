#include "../src/xr_tracking_file.h"
#include "../src/xr_display_mode.h"
#include <cassert>
#include <cstdio>
#include <limits>

int main(int argc, char **argv) {
    assert(XrTrackingFile::PoseTimeOffset(1010000000LL, 1000000000LL) == 0.01);
    assert(XrTrackingFile::PoseTimeOffset(990000000LL, 1000000000LL) == -0.01);
    assert(XrTrackingFile::PoseTimeOffset(0, 1000000000LL) == 0);
    assert(XrTrackingFile::PoseTimeOffset(-1, 1000000000LL) == 0);
    char filename[256];
    std::snprintf(filename, sizeof(filename), "%s/droiddeck-xr-test-XXXXXX", argc > 1 ? argv[1] : "/tmp");
    int descriptor = mkstemp(filename);
    assert(descriptor >= 0);
    assert(ftruncate(descriptor, sizeof(DroidDeckXrTracking)) == 0);
    auto *writer = static_cast<DroidDeckXrTracking *>(mmap(nullptr, sizeof(DroidDeckXrTracking),
        PROT_READ | PROT_WRITE, MAP_SHARED, descriptor, 0));
    assert(writer != MAP_FAILED);
    {
        XrTrackingFile reader(filename);
        DroidDeckXrTracking packet{};
        assert(!reader.Read(&packet));
        writer->magic = DROIDDECK_XR_MAGIC; writer->version = DROIDDECK_XR_VERSION;
        writer->active = 1; writer->sampled_ns = XrTrackingFile::Now(); writer->period_ns = 13888889;
        writer->eyes[0].width = 1680; writer->eyes[0].height = 1760;
        writer->head.orientation[3] = 1; writer->head.position[1] = 1.6f; writer->head.flags = 15;
        assert(reader.Read(&packet)); assert(XrTrackingFile::Fresh(packet));
        assert(packet.head.position[1] == 1.6f);
        assert(XrTrackingFile::Tracked(packet.head));
        assert(XrDisplayMode(packet) == "3360x1760@72");
        auto displayTracking = packet;
        displayTracking.period_ns = 11111111;
        assert(XrDisplayMode(displayTracking) == "3360x1760@90");
        displayTracking.period_ns = 8333547;
        assert(XrDisplayMode(displayTracking) == "3360x1760@120");
        displayTracking.active = 0;
        assert(XrDisplayMode(displayTracking).empty());
        displayTracking = packet; displayTracking.sampled_ns -= 1000000000LL;
        assert(XrDisplayMode(displayTracking).empty());
        displayTracking = packet; displayTracking.period_ns = 0;
        assert(XrDisplayMode(displayTracking).empty());
        auto invalidPose = packet.head; invalidPose.flags = 7;
        assert(!XrTrackingFile::Tracked(invalidPose));
        invalidPose = packet.head; invalidPose.orientation[3] = 0;
        assert(!XrTrackingFile::Tracked(invalidPose));
        invalidPose = packet.head; invalidPose.position[0] = std::numeric_limits<float>::quiet_NaN();
        assert(!XrTrackingFile::Tracked(invalidPose));
        __atomic_store_n(&writer->seq, 1u, __ATOMIC_RELEASE);
        assert(!reader.Read(&packet));
        __atomic_store_n(&writer->seq, 2u, __ATOMIC_RELEASE);
        writer->version++;
        assert(!reader.Read(&packet));
        writer->version = DROIDDECK_XR_VERSION;
        writer->sampled_ns = XrTrackingFile::Now() - 1000000000LL;
        assert(reader.Read(&packet)); assert(!XrTrackingFile::Fresh(packet));
        writer->sampled_ns = XrTrackingFile::Now() + 1000000000LL;
        assert(reader.Read(&packet)); assert(!XrTrackingFile::Fresh(packet));
        writer->sampled_ns = XrTrackingFile::Now(); writer->active = 0;
        assert(reader.Read(&packet)); assert(!XrTrackingFile::Fresh(packet));
        writer->active = 1; writer->eyes[0].width = 9000;
        assert(reader.Read(&packet)); assert(!XrTrackingFile::Fresh(packet));
    }
    munmap(writer, sizeof(DroidDeckXrTracking));
    assert(ftruncate(descriptor, 16) == 0);
    {
        XrTrackingFile reader(filename);
        DroidDeckXrTracking packet{};
        assert(!reader.Read(&packet));
    }
    close(descriptor); unlink(filename);
    std::puts("XR IPC validation passed: valid, seqlock, version, stale, future, inactive, dimensions, truncated");
}
