#include "openvr_driver.h"

#include <algorithm>
#include <string>
#include <atomic>
#include <cstdarg>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <cstring>
#include <cstddef>
#include <fcntl.h>
#include <map>
#include <sched.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <mutex>
#include <thread>
#include <vector>
#include <unistd.h>

#include "../../../app/src/main/cpp/waylandcomp/src/headpose_shm.h"
#include "arm_model.h"
#include "hand_motion.h"
#include "hand_pose.h"
#include "handcam_shm.h"
#include "hand_skeleton.h"
#include "index_pose.h"
#include "pad_controller_config.h"
#include "joycon_shm.h"
#include "presenter.h"
#include "spatial_tracking.h"
#include "luma_joycon.h"
#include "render_target.h"
#include "xr_tracking_file.h"
#include "xr_frame_math.h"
#include "xr_vsync_timing.h"
#include "xr_touch_input.h"
#include "xr_chaperone.h"

#define DD_EXPORT extern "C" __attribute__((visibility("default")))

using namespace vr;
using namespace droiddeck;

namespace {

const char *const kSettingsSection = "driver_droiddeck";
const char *const kPosePath = "/run/droiddeck/" DROIDDECK_HEADPOSE_FILE;
const char *const kJoyConPath = "/run/droiddeck/" DROIDDECK_JOYCON_FILE;
const char *const kHandCamPath = "/run/droiddeck/" DROIDDECK_HANDCAM_FILE;
const char *const kHandTraceFlag = "/run/droiddeck/droiddeck-handtrace.on";
const char *const kHandTracePath = "/run/droiddeck/droiddeck-handtrace.csv";
constexpr float kMinPulseMs = 30.0f;
constexpr int64_t kJoyConStaleNs = 500000000;
constexpr int64_t kHandCamStaleNs = 300000000;
constexpr int64_t kBareHandsStaleNs = 2000000000;

void Log(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
void Log(const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (VRDriverLog()) VRDriverLog()->Log(buf);
    fprintf(stderr, "[droiddeck] %s\n", buf);
}

class HandTrace {
public:
    void Poll(int64_t nowNs) {
        if (checkedNs_ && nowNs - checkedNs_ < 1000000000) return;
        checkedNs_ = nowNs;
        const bool want = access(kHandTraceFlag, F_OK) == 0;
        if (want && !file_) {
            file_ = fopen(kHandTracePath, "a");
            if (file_) {
                fprintf(file_, "S,%lld\n", (long long)nowNs);
                Log("hand trace started");
            }
        } else if (!want && file_) {
            fclose(file_);
            file_ = nullptr;
            Log("hand trace stopped");
        }
        if (file_) fflush(file_);
    }

    FILE *File() const { return file_; }

private:
    FILE *file_ = nullptr;
    int64_t checkedNs_ = 0;
};

HandTrace gHandTrace;

class PoseFile {
public:
    bool Read(droiddeck_headpose *out) {
        if (!map_ && !Open()) return false;
        for (int tries = 0; tries < 8; tries++) {
            uint32_t a = __atomic_load_n(&map_->seq, __ATOMIC_ACQUIRE);
            if (a & 1u) continue;
            memcpy(out, (const void *)map_, sizeof(*out));
            __atomic_thread_fence(__ATOMIC_ACQUIRE);
            if (__atomic_load_n(&map_->seq, __ATOMIC_RELAXED) == a)
                return out->magic == DROIDDECK_HEADPOSE_MAGIC && out->version == DROIDDECK_HEADPOSE_VERSION;
        }
        return false;
    }
    ~PoseFile() { if (map_) munmap((void *)map_, sizeof(*map_)); }

private:
    bool Open() {
        int fd = open(kPosePath, O_RDONLY | O_CLOEXEC);
        if (fd < 0) return false;
        void *p = mmap(nullptr, sizeof(droiddeck_headpose), PROT_READ, MAP_SHARED, fd, 0);
        close(fd);
        if (p == MAP_FAILED) return false;
        map_ = (volatile droiddeck_headpose *)p;
        Log("head pose file mapped: %s", kPosePath);
        return true;
    }
    volatile droiddeck_headpose *map_ = nullptr;
};

HmdQuaternion_t QuatFromMatrix(const float m[9]) {
    HmdQuaternion_t q;
    float tr = m[0] + m[4] + m[8];
    if (tr > 0.0f) {
        float s = sqrtf(tr + 1.0f) * 2.0f;
        q.w = 0.25f * s; q.x = (m[7] - m[5]) / s; q.y = (m[2] - m[6]) / s; q.z = (m[3] - m[1]) / s;
    } else if (m[0] > m[4] && m[0] > m[8]) {
        float s = sqrtf(1.0f + m[0] - m[4] - m[8]) * 2.0f;
        q.w = (m[7] - m[5]) / s; q.x = 0.25f * s; q.y = (m[1] + m[3]) / s; q.z = (m[2] + m[6]) / s;
    } else if (m[4] > m[8]) {
        float s = sqrtf(1.0f + m[4] - m[0] - m[8]) * 2.0f;
        q.w = (m[2] - m[6]) / s; q.x = (m[1] + m[3]) / s; q.y = 0.25f * s; q.z = (m[5] + m[7]) / s;
    } else {
        float s = sqrtf(1.0f + m[8] - m[0] - m[4]) * 2.0f;
        q.w = (m[3] - m[1]) / s; q.x = (m[2] + m[6]) / s; q.y = (m[5] + m[7]) / s; q.z = 0.25f * s;
    }
    return q;
}

double VsyncPeriodSeconds();

HmdMatrix34_t XrPoseMatrix(const DroidDeckXrPose &pose) {
    const float qx = pose.orientation[0], qy = pose.orientation[1], qz = pose.orientation[2], qw = pose.orientation[3];
    HmdMatrix34_t matrix{};
    matrix.m[0][0] = 1 - 2 * (qy * qy + qz * qz); matrix.m[0][1] = 2 * (qx * qy - qw * qz); matrix.m[0][2] = 2 * (qx * qz + qw * qy);
    matrix.m[1][0] = 2 * (qx * qy + qw * qz); matrix.m[1][1] = 1 - 2 * (qx * qx + qz * qz); matrix.m[1][2] = 2 * (qy * qz - qw * qx);
    matrix.m[2][0] = 2 * (qx * qz - qw * qy); matrix.m[2][1] = 2 * (qy * qz + qw * qx); matrix.m[2][2] = 1 - 2 * (qx * qx + qy * qy);
    for (int axis = 0; axis < 3; axis++) matrix.m[axis][3] = pose.position[axis];
    return matrix;
}

HmdMatrix34_t XrEyeToHead(const DroidDeckXrPose &head, const DroidDeckXrPose &eye) {
    HmdMatrix34_t headMatrix = XrPoseMatrix(head), eyeMatrix = XrPoseMatrix(eye), result{};
    for (int row = 0; row < 3; row++) {
        for (int column = 0; column < 3; column++)
            for (int inner = 0; inner < 3; inner++) result.m[row][column] += headMatrix.m[inner][row] * eyeMatrix.m[inner][column];
        for (int inner = 0; inner < 3; inner++)
            result.m[row][3] += headMatrix.m[inner][row] * (eyeMatrix.m[inner][3] - headMatrix.m[inner][3]);
    }
    return result;
}

class VirtualDisplay : public IVRVirtualDisplay {
public:
    void Present(const PresentInfo_t *info, uint32_t size) override {
        frames_++;
        if (frames_ <= 5 || frames_ % 600 == 0)
            Log("virtual display Present #%llu: handle 0x%llx vsync %d frame %llu (info %u bytes)",
                (unsigned long long)frames_, (unsigned long long)info->backbufferTextureHandle, (int)info->vsync,
                (unsigned long long)info->nFrameId, size);
        last_ = std::chrono::steady_clock::now();
    }
    void WaitForPresent() override {}
    bool GetTimeSinceLastVsync(float *seconds, uint64_t *counter) override {
        using namespace std::chrono;
        const double period = VsyncPeriodSeconds();
        std::lock_guard<std::mutex> lock(vsyncMutex_);
        const double t = duration<double>(steady_clock::now().time_since_epoch()).count();
        if (period != period_) {
            vsyncBase_ = period_ > 0.0 ? vsyncBase_ + (uint64_t)((t - vsyncAt_) / period_) : (uint64_t)(t / period);
            vsyncAt_ = t;
            period_ = period;
        }
        const double since = t - vsyncAt_;
        *seconds = (float)fmod(since, period);
        *counter = vsyncBase_ + (uint64_t)(since / period);
        return true;
    }

private:
    uint64_t frames_ = 0;
    std::chrono::steady_clock::time_point last_;
    std::mutex vsyncMutex_;
    double period_ = 0.0, vsyncAt_ = 0.0;
    uint64_t vsyncBase_ = 0;
};

class Vsync {
public:
    static constexpr std::chrono::microseconds kLead{2000};

    static std::chrono::nanoseconds Interval() { return std::chrono::nanoseconds(intervalNs_.load()); }
    static void SetRate(float hz) {
        if (hz >= 24.0f && hz <= 240.0f) intervalNs_ = (int64_t)(1e9 / hz);
    }

    static void Start() {
        if (running_.exchange(true)) return;
        timing_.Reset();
        thread_ = std::thread([] {
            XrTrackingFile xrFile;
            auto next = std::chrono::steady_clock::now() + Interval();
            while (running_) {
                auto interval = Interval();
                DroidDeckXrTracking xr;
                if (xrFile.Read(&xr) && XrTrackingFile::Fresh(xr)) {
                    interval = std::chrono::nanoseconds(xr.period_ns);
                    auto now = std::chrono::steady_clock::now();
                    next = timing_.Target(now, next, xr.predicted_ns, interval);
                }
                auto announce = next - kLead;
                std::this_thread::sleep_until(announce);
                auto now = std::chrono::steady_clock::now();
                double offset = std::chrono::duration<double>(next - now).count();
                timing_.Record(next, interval);
                VRServerDriverHost()->VsyncEvent(offset);
                next += interval;
                now = std::chrono::steady_clock::now();
                while (next <= now) next += interval;
            }
        });
    }
    static void Stop() {
        running_ = false;
        if (thread_.joinable()) thread_.join();
    }
    static std::chrono::steady_clock::time_point Deadline(uint32_t framesToThrottle) {
        return timing_.Deadline(framesToThrottle);
    }

private:
    static inline std::atomic<bool> running_{false};
    static inline std::atomic<int64_t> intervalNs_{16666667};
    static inline std::thread thread_;
    static inline XrVsyncTiming timing_;
};

double VsyncPeriodSeconds() { return Vsync::Interval().count() / 1e9; }

class DirectMode : public IVRDriverDirectModeComponent {
public:
    void ConfigureXr(const DroidDeckXrTracking &tracking) {
        xrWidth_ = tracking.eyes[0].width * 2; xrHeight_ = tracking.eyes[0].height;
        for (int eye = 0; eye < 2; eye++) eyeToHead_[eye] = XrEyeToHead(tracking.head, tracking.eyes[eye].pose);
    }
    struct Set {
        SharedTextureHandle_t handles[3] = {};
        int fds[3] = {-1, -1, -1};
        uint32_t pid = 0, index = 0;
        SwapTextureSetDesc_t desc = {};
    };

    void CreateSwapTextureSet(uint32_t pid, const SwapTextureSetDesc_t *d, SwapTextureSet_t *out) override {
        Log("CreateSwapTextureSet pid %u: format %u, %ux%u, %u samples", pid, d->nFormat, d->nWidth, d->nHeight, d->nSampleCount);
        Set set;
        set.pid = pid;
        set.desc = *d;
        const uint32_t usage = 0x1   | 0x4   | 0x80  ;
        const uint32_t create = 0x8  ;
        for (int i = 0; i < 3; i++) {
            SharedTextureHandle_t h = 0;
            // Compute access makes SteamVR substitute UNORM for sRGB, darkening direct compositor output.
            if (!VRIPCResourceManager()->NewSharedVulkanImage(d->nFormat, d->nWidth, d->nHeight, true, false, false, 1, 1,
                                                               create, usage, &h)) {
                Log("NewSharedVulkanImage failed");
                return;
            }
            uint64_t ipc = 0;
            VRIPCResourceManager()->RefResource(h, &ipc);
            int fd = -1;
            if (!VRIPCResourceManager()->ReceiveSharedFd(ipc, &fd)) Log("ReceiveSharedFd failed");
            set.handles[i] = h;
            set.fds[i] = fd;
            out->rSharedTextureHandles[i] = h;
        }
        out->unTextureFlags = 0;
        Log("  -> handles 0x%llx 0x%llx 0x%llx, fds %d %d %d", (unsigned long long)set.handles[0],
            (unsigned long long)set.handles[1], (unsigned long long)set.handles[2], set.fds[0], set.fds[1], set.fds[2]);
        droiddeck::Presenter::AddTextures(set.handles, set.fds, d->nFormat, d->nWidth, d->nHeight, pid);
        std::lock_guard<std::mutex> lock(mutex_);
        sets_.push_back(set);
    }

    void DestroySwapTextureSet(SharedTextureHandle_t h) override {
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto it = sets_.begin(); it != sets_.end(); ++it) {
            if (it->handles[0] == h || it->handles[1] == h || it->handles[2] == h) {
                Release(*it);
                sets_.erase(it);
                return;
            }
        }
    }

    void DestroyAllSwapTextureSets(uint32_t pid) override {
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto it = sets_.begin(); it != sets_.end();) {
            if (it->pid == pid) { Release(*it); it = sets_.erase(it); } else ++it;
        }
    }

    void GetNextSwapTextureSetIndex(SharedTextureHandle_t handles[2], uint32_t (*indices)[2]) override {
        std::lock_guard<std::mutex> lock(mutex_);
        for (int e = 0; e < 2; e++) {
            Set *s = Find(handles[e]);
            if (!s) { (*indices)[e] = 0; continue; }
            s->index = (s->index + 1) % 3;
            (*indices)[e] = s->index;
        }
    }

    void SubmitLayer(const SubmitLayerPerEye_t (&eyes)[2]) override {
        layers_++;
        if (layerInFrame_++ == 0) {
            frame_.render = {};
            if (xrWidth_) {
                frame_.render.magic = DROIDDECK_XR_FRAME_MAGIC; frame_.render.version = DROIDDECK_XR_FRAME_VERSION;
                frame_.render.width = xrWidth_; frame_.render.height = xrHeight_;
                frame_.render.bytes = xrWidth_ * xrHeight_ * 4;
                frame_.render.color_space = DROIDDECK_XR_COLOR_LINEAR_RGBA8;
            }
            for (int e = 0; e < 2; e++) {
                frame_.texture[e] = eyes[e].hTexture;
                frame_.bounds[e][0] = eyes[e].bounds.uMin; frame_.bounds[e][1] = eyes[e].bounds.vMin;
                frame_.bounds[e][2] = eyes[e].bounds.uMax; frame_.bounds[e][3] = eyes[e].bounds.vMax;
                std::memcpy(frame_.render.projection[e], eyes[e].mProjection.m, sizeof(eyes[e].mProjection.m));
                xr_frame_eye_pose(frame_.render.eye_pose[e], eyes[e].mHmdPose.m, eyeToHead_[e].m);
            }
        }
        if (frames_ < 3)
            Log("SubmitLayer: textures 0x%llx / 0x%llx, bounds %.2f,%.2f-%.2f,%.2f",
                (unsigned long long)eyes[0].hTexture, (unsigned long long)eyes[1].hTexture,
                eyes[0].bounds.uMin, eyes[0].bounds.vMin, eyes[0].bounds.uMax, eyes[0].bounds.vMax);
    }

    void Present(SharedTextureHandle_t sync) override {
        frames_++;
        frame_.render.frame_id = frames_; frame_.render.captured_ns = XrTrackingFile::Now();
        frame_.layers = layerInFrame_;
        if (layerInFrame_) droiddeck::Presenter::Submit(frame_);
        if (layerInFrame_ != lastLayerCount_) {
            static int said;
            if (said++ < 20) Log("frame %llu: %d layer(s) (was %d)", (unsigned long long)frames_, layerInFrame_, lastLayerCount_);
            lastLayerCount_ = layerInFrame_;
        }
        layerInFrame_ = 0;
        if (frames_ <= 3 || frames_ % 600 == 0)
            Log("Present #%llu (%llu layers so far), sync 0x%llx", (unsigned long long)frames_,
                (unsigned long long)layers_, (unsigned long long)sync);
    }

    void PostPresent(const Throttling_t *t) override {
        auto until = Vsync::Deadline(t ? t->nFramesToThrottle : 0);
        if (until > std::chrono::steady_clock::now()) std::this_thread::sleep_until(until);
    }

    void GetFrameTiming(DriverDirectMode_FrameTiming *t) override { t->m_nReprojectionFlags = 0; }

private:
    Set *Find(SharedTextureHandle_t h) {
        for (auto &s : sets_)
            for (int i = 0; i < 3; i++)
                if (s.handles[i] == h) return &s;
        return nullptr;
    }
    static void Release(Set &s) {
        for (int i = 0; i < 3; i++) {
            if (s.handles[i]) droiddeck::Presenter::RemoveTexture(s.handles[i]);
            if (s.fds[i] >= 0) close(s.fds[i]);
            if (s.handles[i]) VRIPCResourceManager()->UnrefResource(s.handles[i]);
        }
    }

    std::mutex mutex_;
    std::vector<Set> sets_;
    uint64_t frames_ = 0, layers_ = 0;
    int layerInFrame_ = 0, lastLayerCount_ = -1;
    droiddeck::EyeFrame frame_{};
    uint32_t xrWidth_ = 0, xrHeight_ = 0;
    HmdMatrix34_t eyeToHead_[2]{};
};

struct PadState {
    uint32_t buttons = 0;
    int16_t axes[8] = {};
};
enum : uint32_t {
    PAD_A = 1u << 0, PAD_B = 1u << 1, PAD_X = 1u << 2, PAD_Y = 1u << 3, PAD_LB = 1u << 4, PAD_RB = 1u << 5,
    PAD_SELECT = 1u << 6, PAD_START = 1u << 7, PAD_L3 = 1u << 8, PAD_R3 = 1u << 9, PAD_GUIDE = 1u << 10,
};

class PadRing {
public:
    bool Read(PadState *out) {
        if (!map_ && !Open()) return false;
        for (int tries = 0; tries < 8; tries++) {
            uint64_t a = __atomic_load_n((const uint64_t *)(map_ + 32), __ATOMIC_ACQUIRE);
            if (a & 1u) continue;
            memcpy(&out->buttons, (const void *)(map_ + 40), 4);
            memcpy(out->axes, (const void *)(map_ + 44), 16);
            __atomic_thread_fence(__ATOMIC_ACQUIRE);
            if (__atomic_load_n((const uint64_t *)(map_ + 32), __ATOMIC_RELAXED) == a) return true;
        }
        return false;
    }
    ~PadRing() { if (map_) munmap((void *)map_, 64); }

private:
    bool Open() {
        auto now = std::chrono::steady_clock::now();
        if (now < retry_) return false;
        retry_ = now + std::chrono::seconds(2);
        std::string path;
        if (const char *env = getenv("DROIDDECK_VR_PAD_RING")) path = env;
        if (path.empty()) {
            const char *paths = getenv("FAKE_EVDEV_MEMFD_PATHS");
            if (paths && !strncmp(paths, "0=", 2)) path.assign(paths + 2, strcspn(paths + 2, ";"));
        }
        if (path.empty()) {
            if (const char *dir = getenv("BL_LAUNCH_DIR")) path = std::string(dir) + "/dev/fakeinput-rings/ring0";
        }
        if (path.empty()) return false;
        int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) return false;
        void *p = mmap(nullptr, 64, PROT_READ, MAP_SHARED, fd, 0);
        close(fd);
        if (p == MAP_FAILED) return false;
        uint32_t magic;
        memcpy(&magic, p, 4);
        if (magic != 0x46494252u) { munmap(p, 64); return false; }
        map_ = (const volatile uint8_t *)p;
        Log("game pad ring mapped: %s", path.c_str());
        return true;
    }
    const volatile uint8_t *map_ = nullptr;
    std::chrono::steady_clock::time_point retry_{};
};

template <typename T>
class ShmFile {
public:
    ShmFile(const char *path, uint32_t magic, uint32_t version, const char *name)
        : path_(path), magic_(magic), version_(version), name_(name) {}
    bool Read(T *out) {
        if (!map_ && !Open()) return false;
        for (int tries = 0; tries < 64; tries++) {
            if (tries) sched_yield();
            const uint32_t a = __atomic_load_n(&map_->seq, __ATOMIC_ACQUIRE);
            if (a & 1u) continue;
            memcpy(out, (const void *)map_, sizeof(*out));
            __atomic_thread_fence(__ATOMIC_ACQUIRE);
            if (__atomic_load_n(&map_->seq, __ATOMIC_RELAXED) == a) return out->magic == magic_ && out->version == version_;
        }
        return false;
    }
    ~ShmFile() { if (map_) munmap((void *)map_, sizeof(*map_)); }

private:
    bool Open() {
        const auto now = std::chrono::steady_clock::now();
        if (now < retry_) return false;
        retry_ = now + std::chrono::seconds(2);
        int fd = open(path_, O_RDONLY | O_CLOEXEC);
        if (fd < 0) return false;
        struct stat st;
        if (fstat(fd, &st) != 0 || st.st_size < (off_t)sizeof(T)) {
            close(fd);
            return false;
        }
        void *p = mmap(nullptr, sizeof(T), PROT_READ, MAP_SHARED, fd, 0);
        close(fd);
        if (p == MAP_FAILED) return false;
        map_ = (volatile T *)p;
        Log("%s file mapped: %s", name_, path_);
        return true;
    }
    const char *const path_;
    const uint32_t magic_, version_;
    const char *const name_;
    volatile T *map_ = nullptr;
    std::chrono::steady_clock::time_point retry_{};
};

void SendRumble(uint16_t slot, const VREvent_HapticVibration_t &h) {
    if (!(h.fAmplitude > 0.0f) || !(h.fFrequency > 0.0f)) return;
    const float seconds = std::isfinite(h.fDurationSeconds) ? h.fDurationSeconds : 0.0f;
    const uint16_t ms = (uint16_t)std::clamp(seconds * 1000.0f, kMinPulseMs, 5000.0f);
    const uint16_t strength = (uint16_t)std::lround(std::min(h.fAmplitude, 1.0f) * 65535.0f);
    const int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (fd < 0) return;
    sockaddr_un addr = {};
    addr.sun_family = AF_UNIX;
    const char name[] = "droiddeck-rumble";
    memcpy(addr.sun_path + 1, name, sizeof(name) - 1);
    const socklen_t length = (socklen_t)(offsetof(sockaddr_un, sun_path) + sizeof(name));
    if (connect(fd, (const sockaddr *)&addr, length) == 0) {
        const uint16_t data[4] = {strength, strength, ms, slot};
        send(fd, data, sizeof(data), MSG_DONTWAIT | MSG_NOSIGNAL);
    }
    close(fd);
}

int64_t MonotonicNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

bool JoyConLive(const droiddeck_joycon_hand &hand, int64_t nowNs) {
    return (hand.flags & DROIDDECK_JOYCON_CONNECTED) && hand.time_ns > 0 &&
           nowNs - hand.time_ns < kJoyConStaleNs && hand.time_ns - nowNs < kJoyConStaleNs;
}

class HandController : public ITrackedDeviceServerDriver {
public:
    explicit HandController(bool right) : right_(right), arm_(right), camera_(right), luma_(right) {}

    EVRInitError Activate(uint32_t id) override {
        auto props = VRProperties();
        const PropertyContainerHandle_t c = props->TrackedDeviceToPropertyContainer(id);
        props->SetStringProperty(c, Prop_ModelNumber_String, right_ ? "Knuckles Right" : "Knuckles Left");
        props->SetStringProperty(c, Prop_ManufacturerName_String, "DroidDeck");
        props->SetStringProperty(c, Prop_TrackingSystemName_String, "droiddeck");
        props->SetStringProperty(c, Prop_RenderModelName_String, RenderModel(expectBare_.load()));
        props->SetStringProperty(c, Prop_ControllerType_String, "knuckles");
        props->SetStringProperty(c, Prop_InputProfilePath_String, "{indexcontroller}/input/index_controller_profile.json");
        props->SetStringProperty(c, Prop_RegisteredDeviceType_String, right_ ? "droiddeck/hand_right" : "droiddeck/hand_left");
        props->SetInt32Property(c, Prop_ControllerRoleHint_Int32,
                                right_ ? TrackedControllerRole_RightHand : TrackedControllerRole_LeftHand);
        props->SetInt32Property(c, Prop_DeviceClass_Int32, TrackedDeviceClass_Controller);
        props->SetInt32Property(c, Prop_ControllerHandSelectionPriority_Int32, 100);
        props->SetBoolProperty(c, Prop_DeviceProvidesBatteryStatus_Bool, true);
        props->SetBoolProperty(c, Prop_DeviceCanPowerOff_Bool, false);
        container_ = c;
        pose_.qWorldFromDriverRotation.w = 1;
        pose_.qDriverFromHeadRotation.w = 1;
        pose_.qRotation.w = 1;
        pose_.result = TrackingResult_Uninitialized;
        auto in = VRDriverInput();
        const auto boolean = [&](const char *path, VRInputComponentHandle_t *h) { in->CreateBooleanComponent(c, path, h); };
        const auto oneSided = [&](const char *path, VRInputComponentHandle_t *h) {
            in->CreateScalarComponent(c, path, h, VRScalarType_Absolute, VRScalarUnits_NormalizedOneSided);
        };
        const auto twoSided = [&](const char *path, VRInputComponentHandle_t *h) {
            in->CreateScalarComponent(c, path, h, VRScalarType_Absolute, VRScalarUnits_NormalizedTwoSided);
        };
        boolean("/input/system/click", &systemClick_);
        boolean("/input/system/touch", &systemTouch_);
        boolean("/input/a/click", &aClick_);
        boolean("/input/a/touch", &aTouch_);
        boolean("/input/b/click", &bClick_);
        boolean("/input/b/touch", &bTouch_);
        boolean("/input/trigger/click", &triggerClick_);
        boolean("/input/trigger/touch", &triggerTouch_);
        oneSided("/input/trigger/value", &trigger_);
        boolean("/input/grip/touch", &gripTouch_);
        oneSided("/input/grip/value", &grip_);
        oneSided("/input/grip/force", &gripForce_);
        twoSided("/input/thumbstick/x", &stickX_);
        twoSided("/input/thumbstick/y", &stickY_);
        boolean("/input/thumbstick/click", &stickClick_);
        boolean("/input/thumbstick/touch", &stickTouch_);
        twoSided("/input/trackpad/x", &padX_);
        twoSided("/input/trackpad/y", &padY_);
        oneSided("/input/trackpad/force", &padForce_);
        boolean("/input/trackpad/touch", &padTouch_);
        oneSided("/input/finger/index", &fingers_[0]);
        oneSided("/input/finger/middle", &fingers_[1]);
        oneSided("/input/finger/ring", &fingers_[2]);
        oneSided("/input/finger/pinky", &fingers_[3]);
        in->CreateSkeletonComponent(c, right_ ? "/input/skeleton/right" : "/input/skeleton/left",
                                    right_ ? "/skeleton/hand/right" : "/skeleton/hand/left", "/pose/raw",
                                    VRSkeletalTracking_Estimated, nullptr, 0, &skeleton_);
        in->CreateHapticComponent(c, "/output/haptic", &haptic_);
        SendSkeleton();
        id_.store(id, std::memory_order_release);
        Log("%s hand activated", right_ ? "right" : "left");
        return VRInitError_None;
    }
    void Deactivate() override { id_.store(k_unTrackedDeviceIndexInvalid, std::memory_order_release); }
    void EnterStandby() override {}
    void *GetComponent(const char *) override { return nullptr; }
    void DebugRequest(const char *, char *resp, uint32_t size) override { if (size) resp[0] = 0; }

    DriverPose_t GetPose() override {
        std::lock_guard<std::mutex> lock(poseMutex_);
        return pose_;
    }

    void ExpectBare(bool bare) { expectBare_ = bare; }

    VRInputComponentHandle_t Haptic() const {
        return id_.load(std::memory_order_acquire) == k_unTrackedDeviceIndexInvalid || bare_.load() ? 0 : haptic_;
    }

    void SeeCamera(const Vec3 &fromHead, const Vec3 *joints, int64_t timeNs, bool estimated, int64_t nowNs) {
        if (connected_) {
            if (!aligning_) kalman_.Measure(timeNs, kHeadPosition + fromHead, estimated ? kEstimatedSigma : kCameraSigma);
        } else if (bare_.load()) {
            camera_.See(timeNs, kHeadPosition + fromHead, joints, kHeadPosition, nowNs);
        }
    }

    bool Update(const droiddeck_joycon_hand *hand, bool bare, const HmdQuaternion_t &headPose, int64_t nowNs,
                const droiddeck_spatialpose *spatial = nullptr) {
        const uint32_t id = id_.load(std::memory_order_acquire);
        if (id == k_unTrackedDeviceIndexInvalid) return false;
        const auto now = std::chrono::steady_clock::now();
        const double dt = std::min(std::chrono::duration<double>(now - lastUpdate_).count(), 0.1);
        lastUpdate_ = now;
        if (lumaMode_ != (spatial != nullptr) || (spatial &&
            (lumaConnection_ != spatial->connection || lumaOrigin_ != spatial->origin))) {
            Disconnect(id);
            luma_.Reset();
            lumaMode_ = spatial != nullptr;
            if (spatial) { lumaConnection_ = spatial->connection; lumaOrigin_ = spatial->origin; }
        }
        if (spatial && !SpatialFresh(spatial->head, nowNs)) {
            if (connected_ && hand) {
                luma_.Reset();
                lumaSeen_ = false;
                DriverPose_t p = GetPose();
                p.poseIsValid = false;
                p.result = TrackingResult_Running_OutOfRange;
                p.poseTimeOffset = 0;
                std::fill_n(p.vecVelocity, 3, 0.0);
                std::fill_n(p.vecAngularVelocity, 3, 0.0);
                droiddeck_joycon_hand idle{};
                SendInputs(idle, 1.0);
                {
                    std::lock_guard<std::mutex> lock(poseMutex_);
                    pose_ = p;
                }
                VRServerDriverHost()->TrackedDevicePoseUpdated(id, p, sizeof(p));
            } else Disconnect(id);
            LeaveBare(id);
            return true;
        }
        if (!hand) {
            Disconnect(id);
            if (bare) {
                UpdateBare(id, nowNs, dt);
                return true;
            }
            LeaveBare(id);
            return false;
        }
        LeaveBare(id);
        const Quat head{headPose.w, headPose.x, headPose.y, headPose.z};
        if (spatial) {
            double norm = 0;
            for (float v : hand->rotation) norm += v * v;
            if (!std::isfinite(norm) || norm < 0.5 || norm > 1.5 ||
                !std::all_of(std::begin(hand->angular_velocity), std::end(hand->angular_velocity),
                             [](float v) { return std::isfinite(v); })) {
                Disconnect(id);
                return true;
            }
        }
        const Quat q = Normalize({hand->rotation[0], hand->rotation[1], hand->rotation[2], hand->rotation[3]});
        if (!connected_ || hand->recenter != recenter_) {
            recenter_ = hand->recenter;
            aligning_ = true;
        }
        connected_ = true;
        double headYaw = 0.0, handYaw = 0.0;
        if (aligning_ && Heading(head, &headYaw) && Heading(q, &handYaw)) {
            yaw_ = headYaw - handYaw;
            aligning_ = false;
            arm_.Reset();
            follower_.Reset();
            kalman_.Reset();
            luma_.Reset();
            shown_ = false;
            Log("%s Joy-Con facing forward", right_ ? "right" : "left");
        }
        const Quat turn = AboutY(yaw_);
        const Quat aligned = turn * q;
        const Vec3 rate{hand->angular_velocity[0], hand->angular_velocity[1], hand->angular_velocity[2]};
        Vec3 grip, velocity;
        if (spatial) {
            const auto tracked = luma_.Update(*spatial, aligned, Length(rate), nowNs, dt);
            if (!tracked.valid || aligning_) {
                Disconnect(id);
                return true;
            }
            grip = tracked.position;
            if (!lumaSeen_ || lumaCamera_ != tracked.camera) {
                Log("%s Luma Joy-Con position: %s", right_ ? "right" : "left",
                    tracked.camera ? "stereo hand" : "6DoF arm estimate");
                lumaSeen_ = true; lumaCamera_ = tracked.camera;
            }
        } else {
            const Vec3 palm = arm_.Update(kHeadPosition, head, aligned, Length(rate), dt);
            const Vec3 moved = Rotate(turn, {hand->displacement[0], hand->displacement[1], hand->displacement[2]});
            const Vec3 inertial = Rotate(turn, {hand->inertial_velocity[0], hand->inertial_velocity[1], hand->inertial_velocity[2]});
            const bool holding = (hand->buttons & (DROIDDECK_JOYCON_TRIGGER | DROIDDECK_JOYCON_GRIP)) != 0;
            kalman_.Input(hand->time_ns - kJoyConDelayNs, inertial, Length(rate));
            const bool camera = kalman_.Ready() && kalman_.TimeNs() - kalman_.SeenNs() <= kCameraHoldNs;
            bool switched = false;
            if (camera != cameraPlaced_) {
                cameraPlaced_ = camera;
                switched = true;
                Log("%s Joy-Con hand placed by %s", right_ ? "right" : "left", camera ? "the camera" : "the arm model");
                if (!camera && shown_) follower_.Place(shownAt_, moved);
            }
            const double settle = holding ? kHoldingSettleSeconds : kSettleSeconds;
            const Vec3 source = camera ? kalman_.Position() : follower_.Update(palm, moved, dt, settle, kMaxOffset);
            velocity = camera ? kalman_.Velocity() : Rotate(turn, {hand->velocity[0], hand->velocity[1], hand->velocity[2]});
            if (shown_ && (switched || Length(source - source_) > kJump)) blend_ = shownAt_ - source;
            else if (!shown_) blend_ = Vec3{};
            blend_ = blend_ * std::exp(-dt / kBlendSeconds);
            grip = source + blend_;
            source_ = source;
            shownAt_ = grip;
            shown_ = true;
            if (FILE *trace = gHandTrace.File()) {
                const Vec3 k = kalman_.Ready() ? kalman_.Position() : Vec3{};
                fprintf(trace, "J,%lld,%d,%lld,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.3f,%.4f,%.4f,%.4f,%u,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%d\n",
                        (long long)nowNs, right_ ? 1 : 0, (long long)hand->time_ns, moved.x, moved.y, moved.z, palm.x, palm.y, palm.z,
                        camera ? 1 : 0, k.x, k.y, k.z, grip.x, grip.y, grip.z, Length(rate), velocity.x, velocity.y, velocity.z,
                        hand->buttons, aligned.w, aligned.x, aligned.y, aligned.z, inertial.x, inertial.y, inertial.z, kalman_.Ready() ? 1 : 0);
            }
        }
        Quat raw;
        Vec3 position;
        IndexRawPose(right_, aligned, grip, &raw, &position);
        const Vec3 spin = Rotate(aligned, rate);
        DriverPose_t p = {};
        p.qWorldFromDriverRotation.w = 1;
        p.qDriverFromHeadRotation.w = 1;
        p.qRotation = {raw.w, raw.x, raw.y, raw.z};
        p.vecPosition[0] = position.x;
        p.vecPosition[1] = position.y;
        p.vecPosition[2] = position.z;
        p.vecVelocity[0] = velocity.x;
        p.vecVelocity[1] = velocity.y;
        p.vecVelocity[2] = velocity.z;
        p.vecAngularVelocity[0] = spin.x;
        p.vecAngularVelocity[1] = spin.y;
        p.vecAngularVelocity[2] = spin.z;
        p.poseTimeOffset = spatial ? 0.0 : -std::max(0.0, (nowNs - hand->time_ns) * 1e-9);
        p.willDriftInYaw = true;
        p.poseIsValid = true;
        p.result = TrackingResult_Running_OK;
        p.deviceIsConnected = true;
        {
            std::lock_guard<std::mutex> lock(poseMutex_);
            pose_ = p;
        }
        VRServerDriverHost()->TrackedDevicePoseUpdated(id, p, sizeof(p));
        SendInputs(*hand, dt);
        SendBattery(*hand);
        return true;
    }

private:
    void UpdateBare(uint32_t id, int64_t nowNs, double dt) {
        if (!bare_.load()) {
            bare_ = true;
            camera_.Reset();
            bareSeen_ = false;
            VRProperties()->SetBoolProperty(container_, Prop_DeviceProvidesBatteryStatus_Bool, false);
            VRProperties()->SetStringProperty(container_, Prop_RenderModelName_String, RenderModel(true));
            Log("%s hand follows the camera (no controller connected)", right_ ? "right" : "left");
        }
        const CameraHandPose hand = camera_.Update(nowNs, dt);
        if (hand.visible != bareSeen_) {
            bareSeen_ = hand.visible;
            Log("%s camera hand %s", right_ ? "right" : "left", hand.visible ? "seen" : "lost");
        }
        DriverPose_t p;
        {
            std::lock_guard<std::mutex> lock(poseMutex_);
            p = pose_;
        }
        p.qWorldFromDriverRotation = {1, 0, 0, 0};
        p.qDriverFromHeadRotation = {1, 0, 0, 0};
        p.deviceIsConnected = true;
        p.willDriftInYaw = false;
        p.poseTimeOffset = 0.0;
        for (int i = 0; i < 3; i++) p.vecAngularVelocity[i] = 0.0;
        if (hand.visible) {
            p.qRotation = {hand.rotation.w, hand.rotation.x, hand.rotation.y, hand.rotation.z};
            p.vecPosition[0] = hand.position.x;
            p.vecPosition[1] = hand.position.y;
            p.vecPosition[2] = hand.position.z;
            p.vecVelocity[0] = hand.velocity.x;
            p.vecVelocity[1] = hand.velocity.y;
            p.vecVelocity[2] = hand.velocity.z;
            p.poseIsValid = true;
            p.result = TrackingResult_Running_OK;
        } else {
            for (int i = 0; i < 3; i++) p.vecVelocity[i] = 0.0;
            p.poseIsValid = false;
            p.result = TrackingResult_Running_OutOfRange;
        }
        {
            std::lock_guard<std::mutex> lock(poseMutex_);
            pose_ = p;
        }
        VRServerDriverHost()->TrackedDevicePoseUpdated(id, p, sizeof(p));
        if (FILE *trace = gHandTrace.File()) {
            const HandButtons &b = hand.buttons;
            fprintf(trace, "B,%lld,%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d%d%d%d%d%d%d,%.4f\n",
                    (long long)nowNs, right_ ? 1 : 0, hand.visible ? 1 : 0, hand.position.x, hand.position.y, hand.position.z,
                    hand.rotation.w, hand.rotation.x, hand.rotation.y, hand.rotation.z, hand.curls.thumb, hand.curls.index,
                    hand.curls.middle, hand.curls.ring, hand.curls.pinky, b.trigger, b.grip, b.triggerClick, b.gripClick, b.a, b.b,
                    b.system, b.facing, b.triggerTouch, camera_.Rms());
        }
        SendBareInputs(hand);
    }

    void LeaveBare(uint32_t id) {
        if (!bare_.load()) return;
        bare_ = false;
        camera_.Reset();
        VRProperties()->SetBoolProperty(container_, Prop_DeviceProvidesBatteryStatus_Bool, true);
        VRProperties()->SetStringProperty(container_, Prop_RenderModelName_String, RenderModel(false));
        if (!connected_) {
            DriverPose_t p = {};
            p.qWorldFromDriverRotation.w = 1;
            p.qDriverFromHeadRotation.w = 1;
            p.qRotation.w = 1;
            p.result = TrackingResult_Uninitialized;
            {
                std::lock_guard<std::mutex> lock(poseMutex_);
                pose_ = p;
            }
            VRServerDriverHost()->TrackedDevicePoseUpdated(id, p, sizeof(p));
            droiddeck_joycon_hand idle = {};
            SendInputs(idle, 1.0);
        }
        Log("%s hand no longer follows the camera", right_ ? "right" : "left");
    }

    void SendBareInputs(const CameraHandPose &hand) {
        const HandButtons &b = hand.buttons;
        SetBool(systemClick_, b.system);
        SetBool(systemTouch_, b.system);
        SetBool(aClick_, b.a);
        SetBool(aTouch_, b.a);
        SetBool(bClick_, b.b);
        SetBool(bTouch_, b.b);
        SetBool(triggerClick_, b.triggerClick);
        SetBool(triggerTouch_, b.triggerTouch);
        SetScalar(trigger_, b.trigger);
        SetBool(gripTouch_, b.grip >= kGripTouch);
        SetScalar(grip_, b.grip);
        SetScalar(gripForce_, b.gripClick ? 1.0f : 0.0f);
        SetScalar(stickX_, 0.0f);
        SetScalar(stickY_, 0.0f);
        SetBool(stickClick_, false);
        SetBool(stickTouch_, false);
        SetScalar(padX_, 0.0f);
        SetScalar(padY_, 0.0f);
        SetScalar(padForce_, 0.0f);
        SetBool(padTouch_, false);
        if (hand.visible) curls_ = hand.curls;
        SetScalar(fingers_[0], (float)curls_.index);
        SetScalar(fingers_[1], (float)curls_.middle);
        SetScalar(fingers_[2], (float)curls_.ring);
        SetScalar(fingers_[3], (float)curls_.pinky);
        if (std::chrono::steady_clock::now() - lastSkeleton_ >= kSkeletonPeriod) SendSkeleton();
    }

    const char *RenderModel(bool bare) const {
        if (bare) return right_ ? "{droiddeck}droiddeck_hand_right" : "{droiddeck}droiddeck_hand_left";
        return right_ ? "{indexcontroller}valve_controller_knu_1_0_right" : "{indexcontroller}valve_controller_knu_1_0_left";
    }

    void Disconnect(uint32_t id) {
        if (!connected_) return;
        connected_ = false;
        lumaSeen_ = false;
        follower_.Reset();
        kalman_.Reset();
        luma_.Reset();
        shown_ = false;
        DriverPose_t p = {};
        p.qWorldFromDriverRotation.w = 1;
        p.qDriverFromHeadRotation.w = 1;
        p.qRotation.w = 1;
        p.result = TrackingResult_Uninitialized;
        {
            std::lock_guard<std::mutex> lock(poseMutex_);
            pose_ = p;
        }
        droiddeck_joycon_hand idle = {};
        SendInputs(idle, 1.0);
        VRServerDriverHost()->TrackedDevicePoseUpdated(id, p, sizeof(p));
        Log("%s Joy-Con hand disconnected", right_ ? "right" : "left");
    }

    void SendInputs(const droiddeck_joycon_hand &hand, double dt) {
        const uint32_t b = hand.buttons;
        const float x = std::clamp(hand.stick[0], -1.0f, 1.0f), y = std::clamp(hand.stick[1], -1.0f, 1.0f);
        const bool stickTouched = (b & DROIDDECK_JOYCON_STICK) || x * x + y * y > kStickTouch * kStickTouch;
        const bool trigger = b & DROIDDECK_JOYCON_TRIGGER, grip = b & DROIDDECK_JOYCON_GRIP;
        const bool thumb = stickTouched || (b & (DROIDDECK_JOYCON_A | DROIDDECK_JOYCON_B | DROIDDECK_JOYCON_SYSTEM)) != 0;
        const bool system = b & DROIDDECK_JOYCON_SYSTEM, a = b & DROIDDECK_JOYCON_A, bButton = b & DROIDDECK_JOYCON_B;
        SetBool(systemClick_, system);
        SetBool(systemTouch_, system);
        SetBool(aClick_, a);
        SetBool(aTouch_, a);
        SetBool(bClick_, bButton);
        SetBool(bTouch_, bButton);
        SetBool(triggerClick_, trigger);
        SetBool(triggerTouch_, trigger);
        SetScalar(trigger_, trigger ? 1.0f : 0.0f);
        SetBool(gripTouch_, grip);
        SetScalar(grip_, grip ? 1.0f : 0.0f);
        SetScalar(gripForce_, grip ? 1.0f : 0.0f);
        SetScalar(stickX_, x);
        SetScalar(stickY_, y);
        SetBool(stickClick_, (b & DROIDDECK_JOYCON_STICK) != 0);
        SetBool(stickTouch_, stickTouched);
        SetScalar(padX_, 0.0f);
        SetScalar(padY_, 0.0f);
        SetScalar(padForce_, 0.0f);
        SetBool(padTouch_, false);
        const double follow = 1.0 - std::exp(-dt / kCurlSeconds);
        curls_.thumb += ((thumb ? 1.0 : 0.0) - curls_.thumb) * follow;
        curls_.index += ((trigger ? 1.0 : 0.0) - curls_.index) * follow;
        const double rest = grip ? 1.0 : 0.0;
        curls_.middle += (rest - curls_.middle) * follow;
        curls_.ring = curls_.pinky = curls_.middle;
        SetScalar(fingers_[0], (float)curls_.index);
        for (int f = 1; f < 4; f++) SetScalar(fingers_[f], (float)curls_.middle);
        const auto now = std::chrono::steady_clock::now();
        if (now - lastSkeleton_ >= kSkeletonPeriod) SendSkeleton();
    }

    void SendSkeleton() {
        lastSkeleton_ = std::chrono::steady_clock::now();
        VRBoneTransform_t bones[kHandBones];
        HandSkeleton(right_, curls_, bones);
        VRDriverInput()->UpdateSkeletonComponent(skeleton_, VRSkeletalMotionRange_WithController, bones, kHandBones);
        VRDriverInput()->UpdateSkeletonComponent(skeleton_, VRSkeletalMotionRange_WithoutController, bones, kHandBones);
    }

    void SendBattery(const droiddeck_joycon_hand &hand) {
        const bool charging = hand.flags & DROIDDECK_JOYCON_CHARGING;
        if (hand.battery >= 0.0f && std::fabs(hand.battery - battery_) > 0.005f) {
            battery_ = hand.battery;
            VRProperties()->SetFloatProperty(container_, Prop_DeviceBatteryPercentage_Float, std::min(battery_, 1.0f));
        }
        if (charging != charging_) {
            charging_ = charging;
            VRProperties()->SetBoolProperty(container_, Prop_DeviceIsCharging_Bool, charging_);
        }
    }

    void SetBool(VRInputComponentHandle_t h, bool on) {
        auto &last = booleans_[h];
        if (last == (on ? 2 : 1)) return;
        last = on ? 2 : 1;
        VRDriverInput()->UpdateBooleanComponent(h, on, 0);
    }
    void SetScalar(VRInputComponentHandle_t h, float value) {
        auto it = scalars_.find(h);
        if (it != scalars_.end() && it->second == value) return;
        scalars_[h] = value;
        VRDriverInput()->UpdateScalarComponent(h, value, 0);
    }

    static constexpr float kStickTouch = 0.1f, kGripTouch = 0.5f;
    static constexpr double kCurlSeconds = 0.05;
    static constexpr std::chrono::milliseconds kSkeletonPeriod{11};
    static constexpr Vec3 kHeadPosition{0.0, 1.6, 0.0};
    static constexpr double kSettleSeconds = 0.6, kHoldingSettleSeconds = 1.5, kMaxOffset = 0.35;
    static constexpr double kCameraSigma = 0.03, kEstimatedSigma = 0.06, kJump = 0.08, kBlendSeconds = 0.25;
    static constexpr int64_t kCameraHoldNs = 750000000, kJoyConDelayNs = 45000000;
    const bool right_;
    ArmModel arm_;
    HandFollower follower_;
    HandKalman kalman_;
    CameraHand camera_;
    LumaJoyConPosition luma_;
    bool lumaMode_ = false, lumaSeen_ = false, lumaCamera_ = false;
    uint64_t lumaConnection_ = 0, lumaOrigin_ = 0;
    std::atomic<bool> bare_{false}, expectBare_{false};
    bool bareSeen_ = false;
    Vec3 source_, shownAt_, blend_;
    bool shown_ = false;
    bool cameraPlaced_ = false;
    std::atomic<uint32_t> id_{k_unTrackedDeviceIndexInvalid};
    PropertyContainerHandle_t container_ = k_ulInvalidPropertyContainer;
    std::mutex poseMutex_;
    DriverPose_t pose_ = {};
    bool connected_ = false, aligning_ = true, charging_ = false;
    uint32_t recenter_ = 0;
    double yaw_ = 0.0;
    float battery_ = -1.0f;
    FingerCurls curls_;
    std::map<VRInputComponentHandle_t, int> booleans_;
    std::map<VRInputComponentHandle_t, float> scalars_;
    std::chrono::steady_clock::time_point lastUpdate_ = std::chrono::steady_clock::now(), lastSkeleton_{};
    VRInputComponentHandle_t systemClick_ = 0, systemTouch_ = 0, aClick_ = 0, aTouch_ = 0, bClick_ = 0, bTouch_ = 0,
                             triggerClick_ = 0, triggerTouch_ = 0, trigger_ = 0, gripTouch_ = 0, grip_ = 0, gripForce_ = 0,
                             stickX_ = 0, stickY_ = 0, stickClick_ = 0, stickTouch_ = 0, padX_ = 0, padY_ = 0, padForce_ = 0,
                             padTouch_ = 0, fingers_[4] = {}, skeleton_ = 0, haptic_ = 0;
};

class CardboardController : public ITrackedDeviceServerDriver {
public:
    explicit CardboardController(bool right) : right_(right) {
        pose_.qWorldFromDriverRotation.w = pose_.qDriverFromHeadRotation.w = pose_.qRotation.w = 1;
    }

    void SetProfile(PadProfile profile) { profile_ = profile; }

    EVRInitError Activate(uint32_t id) override {
        id_ = id;
        PropertyContainerHandle_t c = VRProperties()->TrackedDeviceToPropertyContainer(id_);
        XrTrackingFile tracking;
        DroidDeckXrTracking packet{};
        xrMode_ = tracking.Read(&packet) && XrTrackingFile::Fresh(packet);
        const PadProfile profile = xrMode_ ? PadProfile::Touch : profile_;
        touch_ = profile == PadProfile::Touch;
        index_ = profile == PadProfile::Index;
        const char *model = touch_ ? "DroidDeck Quest Touch" : index_ ? "DroidDeck Index Hand" : "DroidDeck Pad Hand";
        VRProperties()->SetStringProperty(c, Prop_ModelNumber_String, model);
        VRProperties()->SetStringProperty(c, Prop_ManufacturerName_String, touch_ ? "Meta" : index_ ? "Valve" : "DroidDeck");
        VRProperties()->SetStringProperty(c, Prop_TrackingSystemName_String, "droiddeck");
        VRProperties()->SetStringProperty(c, Prop_RenderModelName_String, touch_ ?
            (right_ ? "oculus_quest_plus_controller_right" : "oculus_quest_plus_controller_left") :
            (right_ ? "{indexcontroller}valve_controller_knu_1_0_right" : "{indexcontroller}valve_controller_knu_1_0_left"));
        VRProperties()->SetStringProperty(c, Prop_ControllerType_String, touch_ ? "oculus_touch" : index_ ? "knuckles" : "vive_controller");
        VRProperties()->SetStringProperty(c, Prop_InputProfilePath_String, touch_ ? "{oculus}/input/touch_profile.json" :
            index_ ? "{indexcontroller}/input/index_controller_profile.json" : "{htc}/input/vive_controller_profile.json");
        VRProperties()->SetInt32Property(c, Prop_ControllerRoleHint_Int32,
                                         right_ ? TrackedControllerRole_RightHand : TrackedControllerRole_LeftHand);
        VRProperties()->SetInt32Property(c, Prop_DeviceClass_Int32, TrackedDeviceClass_Controller);
        auto in = VRDriverInput();
        const bool stick = touch_ || index_;
        const char *stickPath = touch_ ? "/input/joystick" : "/input/thumbstick";
        const auto path = [](const char *base, const char *leaf) { return std::string(base) + leaf; };
        in->CreateBooleanComponent(c, "/input/system/click", &system_);
        if (!index_) in->CreateBooleanComponent(c, "/input/application_menu/click", &menu_);
        in->CreateBooleanComponent(c, stick ? "/input/grip/touch" : "/input/grip/click", &grip_);
        in->CreateBooleanComponent(c, "/input/trigger/click", &triggerClick_);
        in->CreateScalarComponent(c, "/input/trigger/value", &trigger_, VRScalarType_Absolute, VRScalarUnits_NormalizedOneSided);
        const char *axisBase = stick ? stickPath : "/input/trackpad";
        in->CreateScalarComponent(c, path(axisBase, "/x").c_str(), &padX_, VRScalarType_Absolute, VRScalarUnits_NormalizedTwoSided);
        in->CreateScalarComponent(c, path(axisBase, "/y").c_str(), &padY_, VRScalarType_Absolute, VRScalarUnits_NormalizedTwoSided);
        in->CreateBooleanComponent(c, path(axisBase, "/click").c_str(), &padClick_);
        in->CreateBooleanComponent(c, path(axisBase, "/touch").c_str(), &padTouch_);
        if (stick) {
            const bool letters = touch_ && !right_;
            in->CreateScalarComponent(c, "/input/grip/value", &gripValue_, VRScalarType_Absolute, VRScalarUnits_NormalizedOneSided);
            if (index_) in->CreateScalarComponent(c, "/input/grip/force", &gripForce_, VRScalarType_Absolute, VRScalarUnits_NormalizedOneSided);
            in->CreateBooleanComponent(c, "/input/trigger/touch", &triggerTouch_);
            in->CreateBooleanComponent(c, letters ? "/input/x/click" : "/input/a/click", &primaryClick_);
            in->CreateBooleanComponent(c, letters ? "/input/y/click" : "/input/b/click", &secondaryClick_);
            in->CreateBooleanComponent(c, letters ? "/input/x/touch" : "/input/a/touch", &primaryTouch_);
            in->CreateBooleanComponent(c, letters ? "/input/y/touch" : "/input/b/touch", &secondaryTouch_);
            if (touch_) in->CreateBooleanComponent(c, "/input/thumbrest/touch", &thumbrestTouch_);
        }
        in->CreateHapticComponent(c, "/output/haptic", &haptic_);
        Log("%s hand activated (%s)", right_ ? "right" : "left", xrMode_ ? "Quest Touch" : model);
        return VRInitError_None;
    }
    void Deactivate() override { ClearInputs(); id_ = k_unTrackedDeviceIndexInvalid; }
    void EnterStandby() override {}
    void *GetComponent(const char *) override { return nullptr; }
    void DebugRequest(const char *, char *resp, uint32_t size) override { if (size) resp[0] = 0; }

    DriverPose_t GetPose() override { return pose_; }

    void ClearInputs() {
        const bool optical = opticalActive_;
        opticalInputs_.Reset();
        opticalPose_.Reset();
        steadyHandAim_ = false;
        opticalActive_ = false;
        lastOpticalState_ = ~0u;
        if (id_ == k_unTrackedDeviceIndexInvalid) return;
        auto in = VRDriverInput();
        EVRInputError error = VRInputError_None;
        for (VRInputComponentHandle_t h : {system_, menu_, grip_, triggerClick_, padClick_, padTouch_, triggerTouch_,
                                           primaryClick_, secondaryClick_, primaryTouch_, secondaryTouch_}) {
            if (!h) continue;
            const auto result = in->UpdateBooleanComponent(h, false, 0);
            if (result != VRInputError_None) error = result;
        }
        for (VRInputComponentHandle_t h : {trigger_, padX_, padY_, gripValue_, gripForce_}) {
            if (!h) continue;
            const auto result = in->UpdateScalarComponent(h, 0.0f, 0);
            if (result != VRInputError_None) error = result;
        }
        if (optical || error != VRInputError_None)
            Log("%s controller inputs cleared: error=%d", right_ ? "right" : "left", int(error));
    }

    void UpdateXr(const DroidDeckXrHand &hand, int64_t predicted) {
        if (id_ == k_unTrackedDeviceIndexInvalid) return;
        DriverPose_t pose{};
        pose.qWorldFromDriverRotation.w = pose.qDriverFromHeadRotation.w = 1;
        pose.qRotation.w = 1;
        pose.deviceIsConnected = hand.active;
        pose.poseIsValid = hand.active && XrTrackingFile::Tracked(hand.pose);
        if (pose.poseIsValid) {
            pose.qRotation = {hand.pose.orientation[3], hand.pose.orientation[0], hand.pose.orientation[1], hand.pose.orientation[2]};
            for (int axis = 0; axis < 3; axis++) pose.vecPosition[axis] = hand.pose.position[axis];
            pose.poseTimeOffset = XrTrackingFile::PoseTimeOffset(predicted, XrTrackingFile::Now());
        }
        pose.result = pose.poseIsValid ? TrackingResult_Running_OK : TrackingResult_Running_OutOfRange;
        pose_ = pose;
        VRServerDriverHost()->TrackedDevicePoseUpdated(id_, pose, sizeof(pose));
        XrTouchInput input = xr_touch_input(hand, right_);
        auto driverInput = VRDriverInput();
        driverInput->UpdateScalarComponent(trigger_, input.trigger, 0);
        driverInput->UpdateBooleanComponent(triggerClick_, input.triggerClick, 0);
        driverInput->UpdateBooleanComponent(grip_, input.gripClick, 0);
        driverInput->UpdateScalarComponent(padX_, input.stickX, 0);
        driverInput->UpdateScalarComponent(padY_, input.stickY, 0);
        driverInput->UpdateBooleanComponent(padClick_, input.stickClick, 0);
        driverInput->UpdateBooleanComponent(padTouch_, input.stickTouch, 0);
        driverInput->UpdateBooleanComponent(menu_, false, 0);
        driverInput->UpdateBooleanComponent(system_, input.systemClick, 0);
        if (xrMode_) {
            driverInput->UpdateScalarComponent(gripValue_, input.grip, 0);
            driverInput->UpdateBooleanComponent(triggerTouch_, input.triggerTouch, 0);
            driverInput->UpdateBooleanComponent(primaryClick_, input.primaryClick, 0);
            driverInput->UpdateBooleanComponent(secondaryClick_, input.secondaryClick, 0);
            driverInput->UpdateBooleanComponent(primaryTouch_, input.primaryTouch, 0);
            driverInput->UpdateBooleanComponent(secondaryTouch_, input.secondaryTouch, 0);
            driverInput->UpdateBooleanComponent(thumbrestTouch_, input.thumbrestTouch, 0);
        }
    }

    VRInputComponentHandle_t Haptic() const { return id_ == k_unTrackedDeviceIndexInvalid ? 0 : haptic_; }
    bool XrMode() const { return xrMode_; }

    void Disconnect() {
        ClearInputs();
        if (id_ == k_unTrackedDeviceIndexInvalid) return;
        DriverPose_t p = {};
        p.qWorldFromDriverRotation.w = 1;
        p.qDriverFromHeadRotation.w = 1;
        p.qRotation.w = 1;
        p.result = TrackingResult_Uninitialized;
        pose_ = p;
        VRServerDriverHost()->TrackedDevicePoseUpdated(id_, p, sizeof(p));
    }

    void UpdateOptical(const dd_spatial_hand &hand, bool headValid, const PadState *pad, int64_t now,
                       uint64_t connection, uint64_t origin, uint16_t inputEpochs = 0,
                       const dd_spatial_pose *aimHead = nullptr) {
        if (id_ == k_unTrackedDeviceIndexInvalid) return;
        opticalActive_ = true;
        const bool inputValid = opticalInputs_.Update(hand, headValid, now, connection, origin, inputEpochs);
        if (steadyHandAim_ != (aimHead != nullptr)) {
            opticalPose_.Reset();
            steadyHandAim_ = aimHead != nullptr;
        }
        auto visualHand = hand;
        if (aimHead && headValid && SpatialFresh(*aimHead, now) && SpatialHandFresh(hand, now)) {
            const auto &h = *aimHead;
            const auto &p = hand.pose;
            const auto q = droiddeck::ShoulderAim({h.position[0], h.position[1], h.position[2]},
                {h.rotation[3], h.rotation[0], h.rotation[1], h.rotation[2]},
                {p.position[0], p.position[1], p.position[2]}, right_,
                {p.rotation[3], p.rotation[0], p.rotation[1], p.rotation[2]});
            visualHand.pose.rotation[0] = q.x; visualHand.pose.rotation[1] = q.y;
            visualHand.pose.rotation[2] = q.z; visualHand.pose.rotation[3] = q.w;
        }
        const bool valid = opticalPose_.Update(visualHand, headValid, now, connection, origin);
        DriverPose_t p{};
        p.qWorldFromDriverRotation.w = p.qDriverFromHeadRotation.w = p.qRotation.w = 1;
        if (valid) {
            const auto &observed = opticalPose_.rendered;
            p.qRotation = {observed.rotation[3], observed.rotation[0], observed.rotation[1], observed.rotation[2]};
            for (int i=0; i<3; ++i) p.vecPosition[i] = observed.position[i];
            p.poseTimeOffset = double(observed.time_ns-now)/1e9;
        }
        p.deviceIsConnected = true; p.poseIsValid = valid;
        p.result = valid ? TrackingResult_Running_OK : TrackingResult_Running_OutOfRange;
        auto in = VRDriverInput();
        const uint32_t b = pad ? pad->buttons : 0;
        const auto gripError = in->UpdateBooleanComponent(grip_, opticalInputs_.grip, 0);
        if (gripValue_) in->UpdateScalarComponent(gripValue_, opticalInputs_.grip ? 1.0f : 0.0f, 0);
        const auto clickError = in->UpdateBooleanComponent(triggerClick_, opticalInputs_.click, 0);
        const auto triggerError = in->UpdateScalarComponent(trigger_, opticalInputs_.trigger, 0);
        if (triggerTouch_) in->UpdateBooleanComponent(triggerTouch_, opticalInputs_.trigger > 0.05f, 0);
        const float x = pad ? pad->axes[right_ ? 2 : 0]/32767.0f : 0;
        const float y = pad ? -pad->axes[right_ ? 3 : 1]/32767.0f : 0;
        UpdatePadButtons(b, x, y, true);
        LogOpticalInputs(hand, inputValid, valid, now, connection, origin, inputEpochs, gripError, clickError, triggerError);
        pose_ = p;
        VRServerDriverHost()->TrackedDevicePoseUpdated(id_, p, sizeof(p));
    }

    static constexpr float kReachSpeed = 0.6f, kReachMin = -0.2f, kReachMax = 1.0f;

    static bool Grabbing(const PadState &pad, bool right) {
        return pad.axes[right ? 4 : 5] > kGrabTrigger || (pad.buttons & (right ? PAD_RB : PAD_LB));
    }

    void Update(const DriverPose_t &headPose, const PadState *pad, bool sticksMoveHands, float reach) {
        const auto &head = headPose.qRotation;
        if (id_ == k_unTrackedDeviceIndexInvalid) return;
        if (opticalActive_) ClearInputs();
        const auto now = std::chrono::steady_clock::now();
        const float dt = std::min(std::chrono::duration<float>(now - lastUpdate_).count(), 0.1f);
        lastUpdate_ = now;
        const float follow = 1.0f - std::exp(-dt / kHandSmoothing);
        const bool grab = pad && Grabbing(*pad, right_);
        if (grab && pad->axes[7]) pull_ = std::clamp(pull_ - (pad->axes[7] > 0 ? 1.0f : -1.0f) * kReachSpeed * dt, kReachMin - reach, kReachMax - reach);
        else if (!grab) pull_ -= pull_ * follow;
        const float stickX = pad ? pad->axes[right_ ? 2 : 0] / 32767.0f : 0.0f, stickY = pad ? -pad->axes[right_ ? 3 : 1] / 32767.0f : 0.0f;
        float aimX = 0.0f, aimY = 0.0f;
        const float tilt = std::sqrt(stickX * stickX + stickY * stickY);
        if (sticksMoveHands && tilt > kStickDeadzone) {
            const float scale = std::min((tilt - kStickDeadzone) / (1.0f - kStickDeadzone), 1.0f) / tilt;
            aimX = stickX * scale;
            aimY = stickY * scale;
        }
        handX_ += (aimX * kHandRangeX - handX_) * follow;
        handY_ += (aimY * (aimY > 0.0f ? kHandRangeUp : kHandRangeDown) - handY_) * follow;
        DriverPose_t p = {};
        p.qWorldFromDriverRotation.w = 1;
        p.qDriverFromHeadRotation.w = 1;
        const double yaw = std::atan2(-handX_, kHandPivot) / 2, pitch = std::atan2(handY_, std::hypot(kHandPivot, handX_)) / 2;
        const double cy = std::cos(yaw), sy = std::sin(yaw), cp = std::cos(pitch), sp = std::sin(pitch);
        const double lw = cy * cp, lx = cy * sp, ly = sy * cp, lz = -sy * sp;
        p.qRotation.w = head.w * lw - head.x * lx - head.y * ly - head.z * lz;
        p.qRotation.x = head.w * lx + head.x * lw + head.y * lz - head.z * ly;
        p.qRotation.y = head.w * ly - head.x * lz + head.y * lw + head.z * lx;
        p.qRotation.z = head.w * lz + head.x * ly - head.y * lx + head.z * lw;
        const double off[3] = {(right_ ? 0.18 : -0.18) + handX_, -0.25 + handY_, -0.35 - reach - pull_};
        double m[3][3];
        const double w = head.w, x = head.x, y = head.y, z = head.z;
        m[0][0] = 1 - 2 * (y * y + z * z); m[0][1] = 2 * (x * y - z * w); m[0][2] = 2 * (x * z + y * w);
        m[1][0] = 2 * (x * y + z * w); m[1][1] = 1 - 2 * (x * x + z * z); m[1][2] = 2 * (y * z - x * w);
        m[2][0] = 2 * (x * z - y * w); m[2][1] = 2 * (y * z + x * w); m[2][2] = 1 - 2 * (x * x + y * y);
        for (int i = 0; i < 3; i++) p.vecPosition[i] = headPose.vecPosition[i] + m[i][0] * off[0] + m[i][1] * off[1] + m[i][2] * off[2];
        p.poseIsValid = true;
        p.result = TrackingResult_Running_OK;
        p.deviceIsConnected = true;
        pose_ = p;
        VRServerDriverHost()->TrackedDevicePoseUpdated(id_, p, sizeof(p));
        if (!pad) return;
        auto in = VRDriverInput();
        const uint32_t b = pad->buttons;
        const float trig = pad->axes[right_ ? 4 : 5] / 255.0f;
        const bool grip = (b & (right_ ? PAD_RB : PAD_LB)) != 0;
        in->UpdateScalarComponent(trigger_, trig, 0);
        in->UpdateBooleanComponent(triggerClick_, trig > 0.6f, 0);
        if (triggerTouch_) in->UpdateBooleanComponent(triggerTouch_, trig > 0.05f, 0);
        in->UpdateBooleanComponent(grip_, grip, 0);
        if (gripValue_) in->UpdateScalarComponent(gripValue_, grip ? 1.0f : 0.0f, 0);
        if (gripForce_) in->UpdateScalarComponent(gripForce_, grip ? 1.0f : 0.0f, 0);
        UpdatePadButtons(b, stickX, stickY, !sticksMoveHands);
    }

private:
    // Face buttons, menus and the stick, for both the game pad and optical hands.
    void UpdatePadButtons(uint32_t b, float stickX, float stickY, bool stickToGame) {
        auto in = VRDriverInput();
        const bool touch = stickToGame && stickX * stickX + stickY * stickY > 0.01f;
        in->UpdateBooleanComponent(system_, right_ && (b & PAD_GUIDE), 0);
        in->UpdateScalarComponent(padX_, touch ? stickX : 0.0f, 0);
        in->UpdateScalarComponent(padY_, touch ? stickY : 0.0f, 0);
        if (!touch_ && !index_) {
            in->UpdateBooleanComponent(menu_, (b & (right_ ? PAD_START : PAD_SELECT)) != 0, 0);
            in->UpdateBooleanComponent(padTouch_, touch || (b & (right_ ? PAD_A : PAD_X)), 0);
            in->UpdateBooleanComponent(padClick_, (b & (right_ ? (PAD_A | PAD_R3) : PAD_X)) != 0, 0);
            return;
        }
        // Touch and Index: face buttons are their own inputs and the stick is a thumbstick.
        const bool primary = b & (right_ ? PAD_A : PAD_X), secondary = b & (right_ ? PAD_B : PAD_Y);
        const bool both = (b & PAD_L3) && (b & PAD_R3);
        if (menu_) in->UpdateBooleanComponent(menu_, !right_ && (b & (PAD_START | PAD_SELECT)), 0);
        in->UpdateBooleanComponent(primaryClick_, primary, 0);
        in->UpdateBooleanComponent(secondaryClick_, secondary, 0);
        in->UpdateBooleanComponent(primaryTouch_, primary, 0);
        in->UpdateBooleanComponent(secondaryTouch_, secondary, 0);
        in->UpdateBooleanComponent(padTouch_, touch, 0);
        in->UpdateBooleanComponent(padClick_, !both && (b & (right_ ? PAD_R3 : PAD_L3)), 0);
    }

    void LogOpticalInputs(const dd_spatial_hand &hand, bool inputValid, bool poseValid, int64_t now,
                          uint64_t connection, uint64_t origin, uint16_t inputEpochs, EVRInputError gripError,
                          EVRInputError clickError, EVRInputError triggerError) {
        const uint32_t triggerBand = !std::isfinite(hand.trigger) ? 3 : hand.trigger < .25f ? 0 : hand.trigger > .65f ? 2 : 1;
        const uint32_t state = uint32_t(poseValid) | uint32_t(inputValid)<<1 | uint32_t(opticalInputs_.click)<<2 |
            uint32_t(opticalInputs_.grip)<<3 | uint32_t(opticalInputs_.armed)<<4 | uint32_t(opticalInputs_.gripArmed)<<5 |
            (hand.pose.flags & 7u)<<6 | triggerBand<<9 | uint32_t(hand.grip != 0)<<11;
        const int errors[] = {int(gripError),int(clickError),int(triggerError)};
        if (state == lastOpticalState_ && connection == lastOpticalConnection_ && origin == lastOpticalOrigin_ &&
            inputEpochs == lastOpticalEpochs_ &&
            std::equal(errors,errors+3,lastOpticalErrors_)) return;
        lastOpticalState_ = state; lastOpticalConnection_ = connection; lastOpticalOrigin_ = origin;
        lastOpticalEpochs_ = inputEpochs;
        std::copy_n(errors,3,lastOpticalErrors_);
        Log("%s optical input pose=%d input=%d flags=%u raw=%.3f/%u out=%.3f/%d/%d armed=%d/%d error=%d/%d/%d ageMs=%.1f connection=%llu origin=%llu epochs=%u/%u",
            right_ ? "right" : "left", int(poseValid), int(inputValid), hand.pose.flags, double(hand.trigger), hand.grip,
            double(opticalInputs_.trigger), int(opticalInputs_.click), int(opticalInputs_.grip),
            int(opticalInputs_.armed), int(opticalInputs_.gripArmed), errors[0], errors[1], errors[2],
            hand.pose.time_ns > 0 ? double(now-hand.pose.time_ns)/1e6 : -1.,
            static_cast<unsigned long long>(connection), static_cast<unsigned long long>(origin),
            unsigned(inputEpochs & 255), unsigned(inputEpochs >> 8));
    }
    OpticalInputs opticalInputs_;
    OpticalPose opticalPose_;
    bool steadyHandAim_ = false;
    bool opticalActive_ = false;
    uint32_t lastOpticalState_ = ~0u;
    uint64_t lastOpticalConnection_ = 0, lastOpticalOrigin_ = 0;
    uint16_t lastOpticalEpochs_ = 0;
    int lastOpticalErrors_[3]{};
    static constexpr int16_t kGrabTrigger = 153;
    static constexpr float kStickDeadzone = 0.12f, kHandRangeX = 0.40f, kHandRangeUp = 0.50f, kHandRangeDown = 0.30f, kHandSmoothing = 0.05f, kHandPivot = 0.45f;
    bool right_;
    bool xrMode_ = false, touch_ = false, index_ = false;
    PadProfile profile_ = PadProfile::Index;
    uint32_t id_ = k_unTrackedDeviceIndexInvalid;
    DriverPose_t pose_ = {};
    float pull_ = 0.0f, handX_ = 0.0f, handY_ = 0.0f;
    std::chrono::steady_clock::time_point lastUpdate_ = std::chrono::steady_clock::now();
    VRInputComponentHandle_t system_ = 0, menu_ = 0, grip_ = 0, triggerClick_ = 0, trigger_ = 0, padX_ = 0, padY_ = 0,
                             padClick_ = 0, padTouch_ = 0, haptic_ = 0;
    VRInputComponentHandle_t gripValue_ = 0, gripForce_ = 0, triggerTouch_ = 0, primaryClick_ = 0, secondaryClick_ = 0,
                             primaryTouch_ = 0, secondaryTouch_ = 0, thumbrestTouch_ = 0;
};

class CardboardHmd : public ITrackedDeviceServerDriver, public IVRDisplayComponent {
public:
    EVRInitError Activate(uint32_t unObjectId) override {
        id_ = unObjectId;
        PropertyContainerHandle_t c = VRProperties()->TrackedDeviceToPropertyContainer(id_);
        container_ = c;
        width_ = VRSettings()->GetInt32(kSettingsSection, "windowWidth");
        height_ = VRSettings()->GetInt32(kSettingsSection, "windowHeight");
        if (width_ <= 0) width_ = 1920;
        if (height_ <= 0) height_ = 1080;
        float hz = VRSettings()->GetFloat(kSettingsSection, "displayFrequency");
        if (hz <= 0.0f) hz = 60.0f;
        DroidDeckXrTracking xr;
        xrMode_ = xr_.Read(&xr) && XrTrackingFile::Fresh(xr);
        if (xrMode_) {
            width_ = xr.eyes[0].width * 2; height_ = xr.eyes[0].height;
            hz = 1000000000.f / xr.period_ns;
        }
        tanHalf_ = 1.0f;
        eyeAspect_ = (width_ * 0.5f) / height_;
        droiddeck_headpose hp;
        if (pose_.Read(&hp) && hp.tan_half_fov > 0.0f) {
            tanHalf_ = hp.tan_half_fov;
            if (hp.eye_aspect > 0.0f) eyeAspect_ = hp.eye_aspect;
            sentMono_ = (hp.flags & DROIDDECK_HEADPOSE_MONO) != 0;
            renderHeight_ = hp.render_height;
            panelHeight_ = hp.eye_h;
        }
        if (!xrMode_ && pose_.Read(&hp) && hp.display_hz >= 24.0f) hz = hp.display_hz;
        Vsync::SetRate(hz);
        sentHz_ = hz;
        sentTan_ = tanHalf_;
        sentAspect_ = eyeAspect_;
        VRProperties()->SetStringProperty(c, Prop_ModelNumber_String, xrMode_ ? "DroidDeck Quest OpenXR" : "DroidDeck Cardboard");
        VRProperties()->SetStringProperty(c, Prop_ManufacturerName_String, "DroidDeck");
        VRProperties()->SetStringProperty(c, Prop_TrackingSystemName_String, "droiddeck");
        VRProperties()->SetFloatProperty(c, Prop_UserIpdMeters_Float, 0.064f);
        if (xrMode_) {
            VRServerDriverHost()->SetDisplayEyeToHead(id_, XrEyeToHead(xr.head, xr.eyes[0].pose), XrEyeToHead(xr.head, xr.eyes[1].pose));
            dm_.ConfigureXr(xr);
            float distanceSquared = 0;
            for (int axis = 0; axis < 3; axis++) {
                float difference = xr.eyes[0].pose.position[axis] - xr.eyes[1].pose.position[axis];
                distanceSquared += difference * difference;
            }
            VRProperties()->SetFloatProperty(c, Prop_UserIpdMeters_Float, std::sqrt(distanceSquared));
        }
        VRProperties()->SetFloatProperty(c, Prop_UserHeadToEyeDepthMeters_Float, 0.0f);
        VRProperties()->SetFloatProperty(c, Prop_DisplayFrequency_Float, hz);
        VRProperties()->SetFloatProperty(c, Prop_SecondsFromVsyncToPhotons_Float, 0.011f);
        VRProperties()->SetUint64Property(c, Prop_CurrentUniverseId_Uint64, 2);
        chaperone_.Reset();
        if (xrMode_) PublishChaperone(xr);
        VRProperties()->SetBoolProperty(c, Prop_IsOnDesktop_Bool, false);
        VRProperties()->SetBoolProperty(c, Prop_DisplayDebugMode_Bool, false);
        directMode_ = VRSettings()->GetBool(kSettingsSection, "directMode");
        const char *presentation = std::getenv("DROIDDECK_VR_PRESENT");
        if (xrMode_ && presentation && !std::strcmp(presentation, "questwsi")) directMode_ = false;
        VRProperties()->SetBoolProperty(c, Prop_HasDriverDirectModeComponent_Bool, directMode_);
        if (directMode_) {
            VRProperties()->SetBoolProperty(c, Prop_DriverDirectModeSendsVsyncEvents_Bool, true);
            VRProperties()->SetBoolProperty(c, Prop_Hmd_AllowsClientToControlTextureIndex, true);
        }
        VRProperties()->SetBoolProperty(c, Prop_HasDisplayComponent_Bool, true);
        virtualDisplay_ = VRSettings()->GetBool(kSettingsSection, "virtualDisplay");
        VRProperties()->SetBoolProperty(c, Prop_HasVirtualDisplayComponent_Bool, virtualDisplay_);
        VRProperties()->SetBoolProperty(c, Prop_ContainsProximitySensor_Bool, true);
        VRDriverInput()->CreateBooleanComponent(c, "/proximity", &proximity_);
        VRDriverInput()->UpdateBooleanComponent(proximity_, true, 0);
        Log("HMD activated: %dx%d at %.0f Hz, eye tan(half fov) %.3f, eye aspect %.3f", width_, height_, hz, tanHalf_.load(), eyeAspect_.load());
        uint32_t renderW, renderH;
        GetRecommendedRenderTargetSize(&renderW, &renderH);
        Log("recommended render target: %ux%u per eye (selected height %u)", renderW, renderH, renderHeight_.load());

        running_ = true;
        thread_ = std::thread([this] { PoseLoop(); });
        if (directMode_) Vsync::Start();
        return VRInitError_None;
    }

    bool Running() const { return running_; }

    void Deactivate() override {
        Vsync::Stop();
        droiddeck::Presenter::Shutdown();
        running_ = false;
        if (thread_.joinable()) thread_.join();
        id_ = k_unTrackedDeviceIndexInvalid;
    }

    void EnterStandby() override {}

    void *GetComponent(const char *name) override {
        Log("component asked for: %s", name);
        if (!strncmp(name, "IVRDisplayComponent_", 20)) return static_cast<IVRDisplayComponent *>(this);
        if (virtualDisplay_ && !strcmp(name, IVRVirtualDisplay_Version)) return static_cast<IVRVirtualDisplay *>(&vd_);
        if (directMode_ && !strncmp(name, "IVRDriverDirectModeComponent_", 29)) return static_cast<IVRDriverDirectModeComponent *>(&dm_);
        return nullptr;
    }

    void DebugRequest(const char *, char *resp, uint32_t size) override { if (size) resp[0] = 0; }

    DriverPose_t GetPose() override { return GetPose(nullptr); }

    DriverPose_t GetPose(droiddeck_spatialpose *snapshot) {
        std::lock_guard<std::mutex> lock(spatialMutex_);
        DriverPose_t p = {};
        p.qWorldFromDriverRotation.w = 1;
        p.qDriverFromHeadRotation.w = 1;
        p.qRotation.w = 1;
        DroidDeckXrTracking xr;
        bool freshXr = xr_.Read(&xr) && XrTrackingFile::Fresh(xr);
        if (freshXr || xrMode_) {
            p.deviceIsConnected = true;
            p.poseIsValid = freshXr && XrTrackingFile::Tracked(xr.head);
            p.result = p.poseIsValid ? TrackingResult_Running_OK : TrackingResult_Running_OutOfRange;
            if (p.poseIsValid) {
                p.qRotation = {xr.head.orientation[3], xr.head.orientation[0], xr.head.orientation[1], xr.head.orientation[2]};
                for (int axis = 0; axis < 3; axis++) p.vecPosition[axis] = xr.head.position[axis];
                p.poseTimeOffset = XrTrackingFile::PoseTimeOffset(xr.predicted_ns, XrTrackingFile::Now());
            }
            return p;
        }
        p.vecPosition[1] = 1.6;
        droiddeck_headpose hp;
        bool ok = pose_.Read(&hp);
        if (ok) {
            p.qRotation = QuatFromMatrix(hp.rot);
            p.poseTimeOffset = 0;
            if (hp.tan_half_fov > 0.0f) tanHalf_ = hp.tan_half_fov;
            if (hp.eye_aspect > 0.0f) eyeAspect_ = hp.eye_aspect;
        }
        p.poseIsValid = true;
        p.result = TrackingResult_Running_OK;
        p.deviceIsConnected = true;
        droiddeck_spatialpose spatial{};
        const bool spatialRead = spatial_.Read(&spatial);
        if (spatialRead) lastSpatialSnapshot_ = spatial;
        else {
            spatial = lastSpatialSnapshot_;
            spatial.head.flags = 0;
            for (auto &hand : spatial.hands) hand.pose.flags = 0;
        }
        if (snapshot) *snapshot = spatial;
        if (spatial.flags & DD_SPATIAL_ACTIVE) {
            if (SpatialFresh(spatial.head, SpatialNow())) {
                for (int i=0; i<3; ++i) p.vecPosition[i] = spatial.head.position[i];
                p.qRotation = {spatial.head.rotation[3], spatial.head.rotation[0], spatial.head.rotation[1], spatial.head.rotation[2]};
                lastSpatial_ = p;
            } else {
                if (lastSpatial_.deviceIsConnected) p = lastSpatial_;
                p.poseIsValid = false;
                p.result = TrackingResult_Running_OutOfRange;
            }
        }
        return p;
    }

    void GetWindowBounds(int32_t *x, int32_t *y, uint32_t *w, uint32_t *h) override {
        *x = 0; *y = 0; *w = (uint32_t)width_; *h = (uint32_t)height_;
    }
    bool IsDisplayOnDesktop() override { return !directMode_; }
    bool IsDisplayRealDisplay() override { return false; }
    void GetRecommendedRenderTargetSize(uint32_t *w, uint32_t *h) override {
        if (xrMode_) { *w = width_ / 2; *h = height_; return; }
        const auto size = droiddeck::RenderTarget(eyeAspect_, renderHeight_, panelHeight_);
        *w = size.width;
        *h = size.height;
    }
    void GetEyeOutputViewport(EVREye eye, uint32_t *x, uint32_t *y, uint32_t *w, uint32_t *h) override {
        *y = 0; *w = (uint32_t)width_ / 2; *h = (uint32_t)height_;
        *x = eye == Eye_Left ? 0 : (uint32_t)width_ / 2;
    }
    void GetProjectionRaw(EVREye eye, float *left, float *right, float *top, float *bottom) override {
        DroidDeckXrTracking xr;
        if (xrMode_ && xr_.Read(&xr) && XrTrackingFile::Fresh(xr)) {
            const float *fov = xr.eyes[eye == Eye_Right ? 1 : 0].fov;
            xr_frame_projection_raw(fov, left, right, top, bottom);
            return;
        }
        *left = -tanHalf_ * eyeAspect_; *right = tanHalf_ * eyeAspect_;
        *top = -tanHalf_; *bottom = tanHalf_;
    }
    DistortionCoordinates_t ComputeDistortion(EVREye, float u, float v) override {
        DistortionCoordinates_t d;
        d.rfRed[0] = d.rfGreen[0] = d.rfBlue[0] = u;
        d.rfRed[1] = d.rfGreen[1] = d.rfBlue[1] = v;
        return d;
    }
    bool ComputeInverseDistortion(HmdVector2_t *result, EVREye, uint32_t, float u, float v) override {
        result->v[0] = u; result->v[1] = v;
        return true;
    }

private:
    void UpdateEye(const droiddeck_headpose &hp) {
        sentTan_ = tanHalf_ = hp.tan_half_fov;
        sentAspect_ = eyeAspect_ = hp.eye_aspect;
        sentMono_ = (hp.flags & DROIDDECK_HEADPOSE_MONO) != 0;
        renderHeight_ = hp.render_height;
        panelHeight_ = hp.eye_h;
        HmdRect2_t r;
        r.vTopLeft.v[0] = -tanHalf_ * eyeAspect_;
        r.vTopLeft.v[1] = -tanHalf_;
        r.vBottomRight.v[0] = tanHalf_ * eyeAspect_;
        r.vBottomRight.v[1] = tanHalf_;
        VRServerDriverHost()->SetDisplayProjectionRaw(id_, r, r);
        uint32_t w = 0, h = 0;
        GetRecommendedRenderTargetSize(&w, &h);
        VRServerDriverHost()->SetRecommendedRenderTargetSize(id_, w, h);
        VRServerDriverHost()->VendorSpecificEvent(id_, VREvent_LensDistortionChanged, VREvent_Data_t{}, 0.0);
        Log("eye now tan(half fov) %.3f, aspect %.3f (%ux%u eye%s), render %ux%u", tanHalf_.load(), eyeAspect_.load(), hp.eye_w, hp.eye_h,
            (hp.flags & DROIDDECK_HEADPOSE_MONO) ? ", one eye shown" : "", w, h);
    }

    void UpdateRate(float hz) {
        sentHz_ = hz;
        Vsync::SetRate(hz);
        VRProperties()->SetFloatProperty(container_, Prop_DisplayFrequency_Float, hz);
        Log("panel now %.0f Hz", hz);
    }

    void PublishChaperone(const DroidDeckXrTracking &tracking) {
        chaperone_.Update(tracking, [this](const DroidDeckXrPose &head) {
            char timestamp[32];
            std::time_t now = std::time(nullptr); std::tm utc{}; gmtime_r(&now, &utc);
            std::strftime(timestamp, sizeof(timestamp), "%Y-%m-%dT%H:%M:%SZ", &utc);
            const auto json = xr_chaperone_json(head, timestamp);
            const auto container = VRProperties()->TrackedDeviceToPropertyContainer(id_);
            return VRProperties()->SetStringProperty(container, Prop_DriverProvidedChaperoneJson_String, json.c_str()) == TrackedProp_Success;
        });
    }
    void PoseLoop() {
        uint32_t lastSeq = 0;
        droiddeck_joycon joyCons = {};
        bool haveJoyCons = false;
        auto lastSent = std::chrono::steady_clock::now();
        while (running_) {
            DroidDeckXrTracking xr;
            if (xrMode_) {
                bool fresh = xr_.Read(&xr) && XrTrackingFile::Fresh(xr);
                if (fresh) PublishChaperone(xr);
                DriverPose_t head = GetPose();
                VRServerDriverHost()->TrackedDevicePoseUpdated(id_, head, sizeof(head));
                for (int hand = 0; hand < 2; hand++) if (CardboardController *pad = ActiveHand(hand)) {
                    DroidDeckXrHand state = fresh ? xr.hands[hand] : DroidDeckXrHand{};
                    pad->UpdateXr(state, fresh ? xr.predicted_ns : 0);
                }
                VRDriverInput()->UpdateBooleanComponent(proximity_, fresh, 0);
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
                continue;
            }
            droiddeck_headpose hp;
            bool fresh = pose_.Read(&hp) && hp.seq != lastSeq;
            auto now = std::chrono::steady_clock::now();
            PadState pad;
            const bool havePad = pad_.Read(&pad);
            const bool padChanged = havePad && (pad.buttons != lastPad_.buttons || memcmp(pad.axes, lastPad_.axes, sizeof(pad.axes)));
            droiddeck_joycon read;
            if (joyConFile_.Read(&read)) {
                joyCons = read;
                haveJoyCons = true;
            }
            const bool joyConsChanged = haveJoyCons && joyCons.seq != lastJoyConSeq_;
            if ((fresh || padChanged || joyConsChanged || now - lastSent > std::chrono::milliseconds(10)) && id_ != k_unTrackedDeviceIndexInvalid) {
                if (joyConsChanged) lastJoyConSeq_ = joyCons.seq;
                if (fresh) {
                    lastSeq = hp.seq;
                    if (hp.eye_w > 0 && hp.tan_half_fov > 0.0f && hp.eye_aspect > 0.0f &&
                        (fabsf(hp.tan_half_fov - sentTan_) > 1e-3f || fabsf(hp.eye_aspect - sentAspect_) > 1e-3f ||
                         ((hp.flags & DROIDDECK_HEADPOSE_MONO) != 0) != sentMono_ ||
                         hp.render_height != renderHeight_ || hp.eye_h != panelHeight_))
                        UpdateEye(hp);
                    if (hp.display_hz >= 24.0f && fabsf(hp.display_hz - sentHz_) > 0.5f) UpdateRate(hp.display_hz);
                    const bool worn = (hp.flags & DROIDDECK_HEADPOSE_ENABLED) != 0;
                    if (worn != worn_) {
                        worn_ = worn;
                        VRDriverInput()->UpdateBooleanComponent(proximity_, worn, 0);
                        Log("headset %s (Cardboard mode %s)", worn ? "worn" : "taken off", worn ? "on" : "off");
                    }
                }
                droiddeck_spatialpose spatial{};
                DriverPose_t head = GetPose(&spatial);
                VRServerDriverHost()->TrackedDevicePoseUpdated(id_, head, sizeof(DriverPose_t));
                const bool lumaJoyCons = (spatial.flags & DD_SPATIAL_ACTIVE) && (spatial.flags & DD_SPATIAL_JOYCONS);
                const bool optical = !lumaJoyCons && (spatial.flags & DD_SPATIAL_ACTIVE) && (spatial.flags & DD_SPATIAL_HANDS);
                const uint32_t chord = PAD_L3 | PAD_R3;
                if (!optical && havePad && (pad.buttons & chord) == chord && (lastPad_.buttons & chord) != chord) {
                    sticksMoveHands_.store(!sticksMoveHands_.load());
                    Log("sticks now move %s", sticksMoveHands_.load() ? "the hands" : "in the game");
                }
                const float dt = std::min(std::chrono::duration<float>(now - lastReach_).count(), 0.1f);
                lastReach_ = now;
                if (havePad && pad.axes[7] && !CardboardController::Grabbing(pad, false) && !CardboardController::Grabbing(pad, true))
                    reach_ = std::clamp(reach_ - (pad.axes[7] > 0 ? 1.0f : -1.0f) * CardboardController::kReachSpeed * dt,
                                        CardboardController::kReachMin, CardboardController::kReachMax);
                if (now - lastScan_ > std::chrono::seconds(1)) {
                    lastScan_ = now;
                    const bool other = OtherControllers();
                    if (other != otherControllers_) {
                        otherControllers_ = other;
                        Log("%s", other ? "other VR controllers connected: pad hands off" : "no other VR controllers: pad hands on");
                    }
                }
                const int64_t nowNs = MonotonicNs();
                gHandTrace.Poll(nowNs);
                double headYaw = 0.0;
                if (Heading({head.qRotation.w, head.qRotation.x, head.qRotation.y, head.qRotation.z}, &headYaw))
                    headings_.Add(nowNs, {-std::sin(headYaw), 0.0, -std::cos(headYaw)});
                droiddeck_handcam cam;
                if (handCamFile_.Read(&cam)) {
                    const bool running = (cam.flags & DROIDDECK_HANDCAM_RUNNING) != 0;
                    if (cam.frame != lastCamFrame_) {
                        lastCamFrame_ = cam.frame;
                        if (!lumaJoyCons && !optical && running && std::llabs(nowNs - cam.time_ns) < kHandCamStaleNs) SeeCamera(cam, nowNs);
                    }
                    bareFlag_ = running && (cam.flags & DROIDDECK_HANDCAM_BARE_HANDS);
                    bareTimeNs_ = cam.time_ns;
                }
                const bool bare = !otherControllers_ && !optical && !lumaJoyCons && bareFlag_ && std::llabs(nowNs - bareTimeNs_) < kBareHandsStaleNs;
                if (bare != bareHands_) {
                    bareHands_ = bare;
                    Log("%s", bare ? "no controllers: the camera tracks bare hands" : "camera bare hands off");
                }
                for (int side = 0; side < 2; side++) {
                    const droiddeck_joycon_hand *joyCon =
                        !otherControllers_ && !optical && haveJoyCons && JoyConLive(joyCons.hands[side], nowNs) ? &joyCons.hands[side] : nullptr;
                    if (handControllers_[side]) handControllers_[side]->ExpectBare(!joyCon && bare);
                    if (joyCon || bare) wantHand_[side] = true;
                    const bool covered = handControllers_[side] && handControllers_[side]->Update(joyCon, bare, head.qRotation, nowNs,
                        lumaJoyCons ? &spatial : nullptr);
                    CardboardController *hand = ActiveHand(side);
                    if (!hand) continue;
                    if (otherControllers_ || covered || bare || lumaJoyCons) {
                        if (!padHandOff_[side]) hand->Disconnect();
                        padHandOff_[side] = true;
                        continue;
                    }
                    padHandOff_[side] = false;
                    if (optical) hand->UpdateOptical(spatial.hands[side], SpatialFresh(spatial.head, SpatialNow()),
                        havePad ? &pad : nullptr, SpatialNow(), spatial.connection, spatial.origin,
                        uint16_t(spatial.input_epochs >> (side*16)),
                        spatial.flags & DD_SPATIAL_STEADY_AIM ? &spatial.head : nullptr);
                    else hand->Update(head, havePad ? &pad : nullptr, sticksMoveHands_.load(), reach_);
                }
                if (havePad) lastPad_ = pad;
                lastSent = now;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
    }

    void SeeCamera(const droiddeck_handcam &cam, int64_t nowNs) {
        Vec3 forward;
        const bool heading = headings_.At(cam.time_ns, &forward) && Length(forward) > 0.5;
        if ((cam.flags & DROIDDECK_HANDCAM_FACE) && heading) {
            const double dt = lastFaceNs_ ? std::max(0.0, (cam.time_ns - lastFaceNs_) * 1e-9) : 0.0;
            lastFaceNs_ = cam.time_ns;
            cameraYaw_.Add(std::atan2(-forward.x, -forward.z) - cam.face_yaw, dt);
        }
        double yaw = 0.0;
        const bool aligned = cameraYaw_.Get(&yaw);
        if (aligned != cameraAligned_) {
            cameraAligned_ = aligned;
            Log("hand camera %s", aligned ? "lined up with the headset" : "waiting to see your face");
        }
        if (FILE *trace = gHandTrace.File())
            fprintf(trace, "C,%lld,%u,%lld,%u,%.4f,%.4f,%d,%.4f,%u,%.4f,%.4f,%.4f,%u,%.4f,%.4f,%.4f\n", (long long)nowNs, cam.frame,
                    (long long)cam.time_ns, cam.flags, cam.face_yaw, heading ? std::atan2(-forward.x, -forward.z) : 0.0, aligned ? 1 : 0,
                    aligned ? yaw : 0.0, cam.hands[0].flags, cam.hands[0].position[0], cam.hands[0].position[1], cam.hands[0].position[2],
                    cam.hands[1].flags, cam.hands[1].position[0], cam.hands[1].position[1], cam.hands[1].position[2]);
        if (!aligned) return;
        const Quat turn = AboutY(yaw);
        for (int side = 0; side < 2; side++) {
            const droiddeck_handcam_hand &hand = cam.hands[side];
            if (!(hand.flags & DROIDDECK_HANDCAM_SEEN) || !handControllers_[side]) continue;
            Vec3 joints[kHandJoints];
            const bool haveJoints = (hand.flags & DROIDDECK_HANDCAM_JOINTS) != 0;
            if (haveJoints)
                for (int i = 0; i < kHandJoints; i++) joints[i] = Rotate(turn, {hand.joints[i][0], hand.joints[i][1], hand.joints[i][2]});
            handControllers_[side]->SeeCamera(Rotate(turn, {hand.position[0], hand.position[1], hand.position[2]}), haveJoints ? joints : nullptr,
                                              cam.time_ns, (hand.flags & DROIDDECK_HANDCAM_ESTIMATED) != 0, nowNs);
        }
    }

    static bool OtherControllers() {
        TrackedDevicePose_t poses[k_unMaxTrackedDeviceCount];
        VRServerDriverHost()->GetRawTrackedDevicePoses(0.0f, poses, k_unMaxTrackedDeviceCount);
        for (uint32_t i = 0; i < k_unMaxTrackedDeviceCount; i++) {
            if (!poses[i].bDeviceIsConnected) continue;
            PropertyContainerHandle_t c = VRProperties()->TrackedDeviceToPropertyContainer(i);
            if (VRProperties()->GetInt32Property(c, Prop_DeviceClass_Int32) != TrackedDeviceClass_Controller) continue;
            if (VRProperties()->GetStringProperty(c, Prop_TrackingSystemName_String) != "droiddeck") return true;
        }
        return false;
    }

    uint32_t id_ = k_unTrackedDeviceIndexInvalid;
    int32_t width_ = 1920, height_ = 1080;
    std::atomic<float> tanHalf_{1.0f}, eyeAspect_{0.889f};
    std::atomic<uint32_t> renderHeight_{720}, panelHeight_{0};
    float sentTan_ = 0.0f, sentAspect_ = 0.0f, sentHz_ = 0.0f;
    bool sentMono_ = false;
    PropertyContainerHandle_t container_ = k_ulInvalidPropertyContainer;
public:
    void SetHands(CardboardController *left, CardboardController *right) { hands_[0] = left; hands_[1] = right; }
    void SetSticksMoveHands(bool on) { sticksMoveHands_ = on; }

    // Called only from the pose thread: a replaced pair is disconnected there, so it never races an update.
    CardboardController *ActiveHand(int side) {
        CardboardController *wanted = hands_[side].load();
        if (wanted != active_[side]) {
            if (active_[side]) active_[side]->Disconnect();
            active_[side] = wanted;
            padHandOff_[side] = false;
        }
        return wanted;
    }
    void SetHandControllers(HandController *left, HandController *right) { handControllers_[0] = left; handControllers_[1] = right; }
    bool WantsHand(int side) const { return wantHand_[side].load(); }

private:
    PoseFile pose_;
    SpatialFile spatial_;
    std::mutex spatialMutex_;
    DriverPose_t lastSpatial_{};
    droiddeck_spatialpose lastSpatialSnapshot_{};
    XrTrackingFile xr_;
    XrChaperoneOrigin chaperone_;
    bool xrMode_ = false;
    PadRing pad_;
    PadState lastPad_;
    VRInputComponentHandle_t proximity_ = 0;
    bool worn_ = true;
    std::atomic<bool> sticksMoveHands_{false};
    float reach_ = 0.0f;
    std::chrono::steady_clock::time_point lastReach_ = std::chrono::steady_clock::now();
    std::chrono::steady_clock::time_point lastScan_{};
    bool otherControllers_ = false;
    std::atomic<CardboardController *> hands_[2] = {nullptr, nullptr};
    CardboardController *active_[2] = {nullptr, nullptr};
    bool padHandOff_[2] = {false, false};
    ShmFile<droiddeck_joycon> joyConFile_{kJoyConPath, DROIDDECK_JOYCON_MAGIC, DROIDDECK_JOYCON_VERSION, "Joy-Con"};
    uint32_t lastJoyConSeq_ = 0;
    ShmFile<droiddeck_handcam> handCamFile_{kHandCamPath, DROIDDECK_HANDCAM_MAGIC, DROIDDECK_HANDCAM_VERSION, "hand camera"};
    uint32_t lastCamFrame_ = 0;
    int64_t lastFaceNs_ = 0;
    TimeHistory headings_;
    CameraYaw cameraYaw_;
    bool cameraAligned_ = false;
    bool bareHands_ = false, bareFlag_ = false;
    int64_t bareTimeNs_ = 0;
    HandController *handControllers_[2] = {nullptr, nullptr};
    std::atomic<bool> wantHand_[2] = {false, false};
    bool virtualDisplay_ = false, directMode_ = false;
    VirtualDisplay vd_;
    DirectMode dm_;
    std::atomic<bool> running_{false};
    std::thread thread_;
};

class Provider : public IServerTrackedDeviceProvider {
public:
    ~Provider() {
        if (hmd_.Running()) hmd_.Deactivate();
    }

    EVRInitError Init(IVRDriverContext *ctx) override {
        VR_INIT_SERVER_DRIVER_CONTEXT(ctx);
        focusEnabled_ = access("/run/droiddeck/droiddeck-xr-frames", F_OK) == 0;
        Log("DroidDeck Cardboard driver loaded");
        if (!VRServerDriverHost()->TrackedDeviceAdded("droiddeck-cardboard-hmd", TrackedDeviceClass_HMD, &hmd_))
            Log("TrackedDeviceAdded failed");
        padsEnabled_ = VRSettings()->GetBool(kSettingsSection, "gamepadControllers");
        ApplyPadConfig(true);
        hmd_.SetHandControllers(&handLeft_, &handRight_);
        return VRInitError_None;
    }
    void Cleanup() override {
        if (focusEnabled_) unlink(kFocusPath);
        if (haptics_) { munmap(haptics_, sizeof(*haptics_)); haptics_ = nullptr; }
        VR_CLEANUP_SERVER_DRIVER_CONTEXT();
    }
    const char *const *GetInterfaceVersions() override { return k_InterfaceVersions; }
    void RunFrame() override {
        const auto now = std::chrono::steady_clock::now();
        if (now - lastConfigCheck_ > std::chrono::seconds(1)) {
            lastConfigCheck_ = now;
            ApplyPadConfig(false);
        }
        for (int side = 0; side < 2; side++) {
            if (handAdded_[side] || !hmd_.WantsHand(side)) continue;
            handAdded_[side] = true;
            if (!VRServerDriverHost()->TrackedDeviceAdded(side ? "droiddeck-hand-right" : "droiddeck-hand-left",
                                                          TrackedDeviceClass_Controller, side ? &handRight_ : &handLeft_))
                Log("%s hand could not be added", side ? "right" : "left");
        }
        VREvent_t e;
        while (VRServerDriverHost()->PollNextEvent(&e, sizeof(e))) {
            if (focusEnabled_) {
                bool changed = false;
                if (e.eventType == VREvent_SceneApplicationChanged) { scenePid_ = e.data.process.pid; changed = true; }
                if (e.eventType == VREvent_DashboardActivated) { dashboard_ = true; changed = true; }
                if (e.eventType == VREvent_DashboardDeactivated) { dashboard_ = false; changed = true; }
                if (changed) PublishFocus();
            }
            if (e.eventType != VREvent_Input_HapticVibration) continue;
            const VREvent_HapticVibration_t &h = e.data.hapticVibration;
            if (!h.componentHandle) continue;
            if (h.componentHandle == handLeft_.Haptic()) SendRumble(DROIDDECK_JOYCON_RUMBLE_SLOT, h);
            else if (h.componentHandle == handRight_.Haptic()) SendRumble(DROIDDECK_JOYCON_RUMBLE_SLOT + 1, h);
            else for (int p = 0; p < kPadProfiles; p++) {
                if (!padAdded_[p] || (h.componentHandle != pads_[p][0].Haptic() && h.componentHandle != pads_[p][1].Haptic())) continue;
                if (pads_[p][0].XrMode()) SendHaptic(h.componentHandle == pads_[p][1].Haptic() ? 1 : 0, h);
                else SendRumble(0, h);
                break;
            }
        }
    }
    bool ShouldBlockStandbyMode() override { return false; }
    void EnterStandby() override {}
    void LeaveStandby() override {}

private:
    static constexpr const char *kFocusPath = "/run/droiddeck/droiddeck-steamvr/scene-focus.json";
    bool focusEnabled_ = false, dashboard_ = true;
    uint32_t scenePid_ = 0;
    void PublishFocus() {
        const std::string temporary = std::string(kFocusPath) + ".tmp";
        int fd = open(temporary.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600);
        if (fd < 0) return;
        char body[128];
        int length = snprintf(body, sizeof(body), "{\"serverPid\":%d,\"scenePid\":%u,\"dashboard\":%s}\n",
                              getpid(), scenePid_, dashboard_ ? "true" : "false");
        bool written = write(fd, body, length) == length;
        close(fd);
        if (written) rename(temporary.c_str(), kFocusPath);
        else unlink(temporary.c_str());
    }
    DroidDeckXrHaptics *haptics_ = nullptr;
    void SendHaptic(int hand, const VREvent_HapticVibration_t &pulse) {
        if (!std::isfinite(pulse.fAmplitude) || !std::isfinite(pulse.fFrequency) || !std::isfinite(pulse.fDurationSeconds)) return;
        if (!haptics_) {
            int descriptor = open("/run/droiddeck/" DROIDDECK_XR_HAPTIC_FILE, O_RDWR | O_CLOEXEC);
            if (descriptor < 0) return;
            struct stat status{};
            if (fstat(descriptor, &status) != 0 || status.st_size != sizeof(*haptics_)) { close(descriptor); return; }
            void *mapping = mmap(nullptr, sizeof(*haptics_), PROT_READ | PROT_WRITE, MAP_SHARED, descriptor, 0);
            close(descriptor);
            if (mapping == MAP_FAILED) return;
            haptics_ = static_cast<DroidDeckXrHaptics *>(mapping);
        }
        uint32_t sequence = __atomic_load_n(&haptics_->seq, __ATOMIC_RELAXED);
        __atomic_store_n(&haptics_->seq, sequence | 1u, __ATOMIC_SEQ_CST);
        __atomic_thread_fence(__ATOMIC_SEQ_CST);
        auto &target = haptics_->hands[hand];
        target.id++;
        target.duration_ns = (int64_t)(std::clamp(pulse.fDurationSeconds, 0.001f, 1.f) * 1000000000.0);
        target.amplitude = std::clamp(pulse.fAmplitude, 0.f, 1.f);
        target.frequency = std::max(pulse.fFrequency, 0.f);
        __atomic_store_n(&haptics_->seq, (sequence | 1u) + 1u, __ATOMIC_RELEASE);
    }
    // SteamVR fixes a device's input profile when it activates, so each type gets its own pair,
    // added the first time it is chosen; devices cannot be removed, only disconnected.
    void ApplyPadConfig(bool initial) {
        // The file is a few bytes, so compare what it says; two quick writes can share an mtime.
        const PadControllerConfig pads = read_pad_controller_config("/run/droiddeck/" DROIDDECK_VR_CONTROLLERS_FILE);
        if (!initial && pads.profile == configProfile_ && pads.sticksMoveHands == configSticks_) return;
        configProfile_ = pads.profile;
        if (initial || pads.sticksMoveHands != configSticks_) hmd_.SetSticksMoveHands(pads.sticksMoveHands);
        configSticks_ = pads.sticksMoveHands;
        const PadProfile profile = padsEnabled_ ? pads.profile : PadProfile::Off;
        if (!initial && profile == profile_) return;
        profile_ = profile;
        Log("game pad hands: %s, sticks start in %s", pad_profile_name(profile), pads.sticksMoveHands ? "the hands" : "the game");
        if (profile == PadProfile::Off) { hmd_.SetHands(nullptr, nullptr); return; }
        const int p = int(profile) - 1;
        if (!padAdded_[p]) {
            static const char *const kNames[kPadProfiles][2] = {
                {"droiddeck-pad-left", "droiddeck-pad-right"},
                {"droiddeck-pad-touch-left", "droiddeck-pad-touch-right"},
                {"droiddeck-pad-vive-left", "droiddeck-pad-vive-right"},
            };
            for (int side = 0; side < 2; side++) {
                pads_[p][side].SetProfile(profile);
                VRServerDriverHost()->TrackedDeviceAdded(kNames[p][side], TrackedDeviceClass_Controller, &pads_[p][side]);
            }
            padAdded_[p] = true;
        }
        hmd_.SetHands(&pads_[p][0], &pads_[p][1]);
    }

    static constexpr int kPadProfiles = 3;
    CardboardHmd hmd_;
    CardboardController pads_[kPadProfiles][2] = {{CardboardController(false), CardboardController(true)},
                                                  {CardboardController(false), CardboardController(true)},
                                                  {CardboardController(false), CardboardController(true)}};
    bool padAdded_[kPadProfiles] = {false, false, false};
    bool padsEnabled_ = true, configSticks_ = false;
    PadProfile profile_ = PadProfile::Off;
    PadProfile configProfile_ = PadProfile::Index;
    std::chrono::steady_clock::time_point lastConfigCheck_{};
    HandController handLeft_{false}, handRight_{true};
    bool handAdded_[2] = {false, false};
};

Provider g_provider;

}

DD_EXPORT void *HmdDriverFactory(const char *interfaceName, int *returnCode) {
    if (!strcmp(interfaceName, IServerTrackedDeviceProvider_Version)) return &g_provider;
    if (returnCode) *returnCode = VRInitError_Init_InterfaceNotFound;
    return nullptr;
}
