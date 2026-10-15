#include "openvr_driver.h"

#include <algorithm>
#include <string>
#include <atomic>
#include <cstdarg>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <cstddef>
#include <fcntl.h>
#include <map>
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
#include "hand_skeleton.h"
#include "index_pose.h"
#include "joycon_shm.h"
#include "presenter.h"
#include "render_target.h"

#define DD_EXPORT extern "C" __attribute__((visibility("default")))

using namespace vr;
using namespace droiddeck;

namespace {

const char *const kSettingsSection = "driver_droiddeck";
const char *const kPosePath = "/run/droiddeck/" DROIDDECK_HEADPOSE_FILE;
const char *const kJoyConPath = "/run/droiddeck/" DROIDDECK_JOYCON_FILE;
constexpr float kMinPulseMs = 30.0f;
constexpr int64_t kJoyConStaleNs = 500000000;

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
        thread_ = std::thread([] {
            auto next = std::chrono::steady_clock::now() + Interval();
            while (running_) {
                auto announce = next - kLead;
                std::this_thread::sleep_until(announce);
                auto now = std::chrono::steady_clock::now();
                double offset = std::chrono::duration<double>(announce - now).count();
                VRServerDriverHost()->VsyncEvent(offset);
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    last_ = next;
                }
                const auto interval = Interval();
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
    static std::chrono::steady_clock::time_point LastTick() {
        std::lock_guard<std::mutex> lock(mutex_);
        return last_;
    }

private:
    static inline std::atomic<bool> running_{false};
    static inline std::atomic<int64_t> intervalNs_{16666667};
    static inline std::thread thread_;
    static inline std::mutex mutex_;
    static inline std::chrono::steady_clock::time_point last_{};
};

double VsyncPeriodSeconds() { return Vsync::Interval().count() / 1e9; }

class DirectMode : public IVRDriverDirectModeComponent {
public:
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
            if (!VRIPCResourceManager()->NewSharedVulkanImage(d->nFormat, d->nWidth, d->nHeight, true, false, true, 1, 1,
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
        droiddeck::Presenter::AddTextures(set.handles, set.fds, d->nFormat, d->nWidth, d->nHeight);
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
            for (int e = 0; e < 2; e++) {
                frame_.texture[e] = eyes[e].hTexture;
                frame_.bounds[e][0] = eyes[e].bounds.uMin; frame_.bounds[e][1] = eyes[e].bounds.vMin;
                frame_.bounds[e][2] = eyes[e].bounds.uMax; frame_.bounds[e][3] = eyes[e].bounds.vMax;
            }
        }
        if (frames_ < 3)
            Log("SubmitLayer: textures 0x%llx / 0x%llx, bounds %.2f,%.2f-%.2f,%.2f",
                (unsigned long long)eyes[0].hTexture, (unsigned long long)eyes[1].hTexture,
                eyes[0].bounds.uMin, eyes[0].bounds.vMin, eyes[0].bounds.uMax, eyes[0].bounds.vMax);
    }

    void Present(SharedTextureHandle_t sync) override {
        frames_++;
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
        auto last = Vsync::LastTick();
        auto until = last + Vsync::Interval() * (1 + (t ? t->nFramesToThrottle : 0));
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

class JoyConFile {
public:
    bool Read(droiddeck_joycon *out) {
        if (!map_ && !Open()) return false;
        for (int tries = 0; tries < 8; tries++) {
            const uint32_t a = __atomic_load_n(&map_->seq, __ATOMIC_ACQUIRE);
            if (a & 1u) continue;
            memcpy(out, (const void *)map_, sizeof(*out));
            __atomic_thread_fence(__ATOMIC_ACQUIRE);
            if (__atomic_load_n(&map_->seq, __ATOMIC_RELAXED) == a)
                return out->magic == DROIDDECK_JOYCON_MAGIC && out->version == DROIDDECK_JOYCON_VERSION;
        }
        return false;
    }
    ~JoyConFile() { if (map_) munmap((void *)map_, sizeof(*map_)); }

private:
    bool Open() {
        const auto now = std::chrono::steady_clock::now();
        if (now < retry_) return false;
        retry_ = now + std::chrono::seconds(2);
        int fd = open(kJoyConPath, O_RDONLY | O_CLOEXEC);
        if (fd < 0) return false;
        struct stat st;
        if (fstat(fd, &st) != 0 || st.st_size < (off_t)sizeof(droiddeck_joycon)) {
            close(fd);
            return false;
        }
        void *p = mmap(nullptr, sizeof(droiddeck_joycon), PROT_READ, MAP_SHARED, fd, 0);
        close(fd);
        if (p == MAP_FAILED) return false;
        map_ = (volatile droiddeck_joycon *)p;
        Log("Joy-Con file mapped: %s", kJoyConPath);
        return true;
    }
    volatile droiddeck_joycon *map_ = nullptr;
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

class JoyConController : public ITrackedDeviceServerDriver {
public:
    explicit JoyConController(bool right) : right_(right), arm_(right) {}

    EVRInitError Activate(uint32_t id) override {
        auto props = VRProperties();
        const PropertyContainerHandle_t c = props->TrackedDeviceToPropertyContainer(id);
        props->SetStringProperty(c, Prop_ModelNumber_String, right_ ? "Knuckles Right" : "Knuckles Left");
        props->SetStringProperty(c, Prop_ManufacturerName_String, "DroidDeck");
        props->SetStringProperty(c, Prop_TrackingSystemName_String, "droiddeck");
        props->SetStringProperty(c, Prop_RenderModelName_String,
                                 right_ ? "{indexcontroller}valve_controller_knu_1_0_right" : "{indexcontroller}valve_controller_knu_1_0_left");
        props->SetStringProperty(c, Prop_ControllerType_String, "knuckles");
        props->SetStringProperty(c, Prop_InputProfilePath_String, "{indexcontroller}/input/index_controller_profile.json");
        props->SetStringProperty(c, Prop_RegisteredDeviceType_String, right_ ? "droiddeck/joycon_right" : "droiddeck/joycon_left");
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
        Log("%s Joy-Con hand activated", right_ ? "right" : "left");
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

    VRInputComponentHandle_t Haptic() const { return id_.load(std::memory_order_acquire) == k_unTrackedDeviceIndexInvalid ? 0 : haptic_; }

    bool Update(const droiddeck_joycon_hand *hand, const HmdQuaternion_t &headPose, int64_t nowNs) {
        const uint32_t id = id_.load(std::memory_order_acquire);
        if (id == k_unTrackedDeviceIndexInvalid) return false;
        const auto now = std::chrono::steady_clock::now();
        const double dt = std::min(std::chrono::duration<double>(now - lastUpdate_).count(), 0.1);
        lastUpdate_ = now;
        if (!hand) {
            Disconnect(id);
            return false;
        }
        const Quat head{headPose.w, headPose.x, headPose.y, headPose.z};
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
            Log("%s Joy-Con facing forward", right_ ? "right" : "left");
        }
        const Quat aligned = AboutY(yaw_) * q;
        const Vec3 rate{hand->angular_velocity[0], hand->angular_velocity[1], hand->angular_velocity[2]};
        const Vec3 palm = arm_.Update(kHeadPosition, head, aligned, Length(rate), dt);
        Quat raw;
        Vec3 position;
        IndexRawPose(right_, aligned, palm, &raw, &position);
        const Vec3 spin = Rotate(aligned, rate);
        DriverPose_t p = {};
        p.qWorldFromDriverRotation.w = 1;
        p.qDriverFromHeadRotation.w = 1;
        p.qRotation = {raw.w, raw.x, raw.y, raw.z};
        p.vecPosition[0] = position.x;
        p.vecPosition[1] = position.y;
        p.vecPosition[2] = position.z;
        p.vecAngularVelocity[0] = spin.x;
        p.vecAngularVelocity[1] = spin.y;
        p.vecAngularVelocity[2] = spin.z;
        p.poseTimeOffset = -std::max(0.0, (nowNs - hand->time_ns) * 1e-9);
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
    void Disconnect(uint32_t id) {
        if (!connected_) return;
        connected_ = false;
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

    static constexpr float kStickTouch = 0.1f;
    static constexpr double kCurlSeconds = 0.05;
    static constexpr std::chrono::milliseconds kSkeletonPeriod{11};
    static constexpr Vec3 kHeadPosition{0.0, 1.6, 0.0};
    const bool right_;
    ArmModel arm_;
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
    explicit CardboardController(bool right) : right_(right) {}

    EVRInitError Activate(uint32_t id) override {
        id_ = id;
        PropertyContainerHandle_t c = VRProperties()->TrackedDeviceToPropertyContainer(id_);
        VRProperties()->SetStringProperty(c, Prop_ModelNumber_String, "DroidDeck Pad Hand");
        VRProperties()->SetStringProperty(c, Prop_ManufacturerName_String, "DroidDeck");
        VRProperties()->SetStringProperty(c, Prop_TrackingSystemName_String, "droiddeck");
        VRProperties()->SetStringProperty(c, Prop_RenderModelName_String,
                                          right_ ? "{indexcontroller}valve_controller_knu_1_0_right" : "{indexcontroller}valve_controller_knu_1_0_left");
        VRProperties()->SetStringProperty(c, Prop_ControllerType_String, "vive_controller");
        VRProperties()->SetStringProperty(c, Prop_InputProfilePath_String, "{htc}/input/vive_controller_profile.json");
        VRProperties()->SetInt32Property(c, Prop_ControllerRoleHint_Int32,
                                         right_ ? TrackedControllerRole_RightHand : TrackedControllerRole_LeftHand);
        VRProperties()->SetInt32Property(c, Prop_DeviceClass_Int32, TrackedDeviceClass_Controller);
        auto in = VRDriverInput();
        in->CreateBooleanComponent(c, "/input/system/click", &system_);
        in->CreateBooleanComponent(c, "/input/application_menu/click", &menu_);
        in->CreateBooleanComponent(c, "/input/grip/click", &grip_);
        in->CreateBooleanComponent(c, "/input/trigger/click", &triggerClick_);
        in->CreateScalarComponent(c, "/input/trigger/value", &trigger_, VRScalarType_Absolute, VRScalarUnits_NormalizedOneSided);
        in->CreateScalarComponent(c, "/input/trackpad/x", &padX_, VRScalarType_Absolute, VRScalarUnits_NormalizedTwoSided);
        in->CreateScalarComponent(c, "/input/trackpad/y", &padY_, VRScalarType_Absolute, VRScalarUnits_NormalizedTwoSided);
        in->CreateBooleanComponent(c, "/input/trackpad/click", &padClick_);
        in->CreateBooleanComponent(c, "/input/trackpad/touch", &padTouch_);
        in->CreateHapticComponent(c, "/output/haptic", &haptic_);
        Log("%s hand activated (game pad)", right_ ? "right" : "left");
        return VRInitError_None;
    }
    void Deactivate() override { id_ = k_unTrackedDeviceIndexInvalid; }
    void EnterStandby() override {}
    void *GetComponent(const char *) override { return nullptr; }
    void DebugRequest(const char *, char *resp, uint32_t size) override { if (size) resp[0] = 0; }

    DriverPose_t GetPose() override { return pose_; }

    VRInputComponentHandle_t Haptic() const { return id_ == k_unTrackedDeviceIndexInvalid ? 0 : haptic_; }

    void Disconnect() {
        if (id_ == k_unTrackedDeviceIndexInvalid) return;
        DriverPose_t p = {};
        p.qWorldFromDriverRotation.w = 1;
        p.qDriverFromHeadRotation.w = 1;
        p.qRotation.w = 1;
        p.result = TrackingResult_Uninitialized;
        pose_ = p;
        VRServerDriverHost()->TrackedDevicePoseUpdated(id_, p, sizeof(p));
        auto in = VRDriverInput();
        for (VRInputComponentHandle_t h : {system_, menu_, grip_, triggerClick_, padClick_, padTouch_}) in->UpdateBooleanComponent(h, false, 0);
        for (VRInputComponentHandle_t h : {trigger_, padX_, padY_}) in->UpdateScalarComponent(h, 0.0f, 0);
    }

    static constexpr float kReachSpeed = 0.6f, kReachMin = -0.2f, kReachMax = 1.0f;

    static bool Grabbing(const PadState &pad, bool right) {
        return pad.axes[right ? 4 : 5] > kGrabTrigger || (pad.buttons & (right ? PAD_RB : PAD_LB));
    }

    void Update(const HmdQuaternion_t &head, const PadState *pad, bool sticksMoveHands, float reach) {
        if (id_ == k_unTrackedDeviceIndexInvalid) return;
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
        for (int i = 0; i < 3; i++) p.vecPosition[i] = (i == 1 ? 1.6 : 0.0) + m[i][0] * off[0] + m[i][1] * off[1] + m[i][2] * off[2];
        p.poseIsValid = true;
        p.result = TrackingResult_Running_OK;
        p.deviceIsConnected = true;
        pose_ = p;
        VRServerDriverHost()->TrackedDevicePoseUpdated(id_, p, sizeof(p));
        if (!pad) return;
        auto in = VRDriverInput();
        const uint32_t b = pad->buttons;
        const float trig = pad->axes[right_ ? 4 : 5] / 255.0f;
        const bool touch = !sticksMoveHands && stickX * stickX + stickY * stickY > 0.01f;
        in->UpdateBooleanComponent(system_, right_ && (b & PAD_GUIDE), 0);
        in->UpdateBooleanComponent(menu_, (b & (right_ ? PAD_START : PAD_SELECT)) != 0, 0);
        in->UpdateBooleanComponent(grip_, (b & (right_ ? PAD_RB : PAD_LB)) != 0, 0);
        in->UpdateScalarComponent(trigger_, trig, 0);
        in->UpdateBooleanComponent(triggerClick_, trig > 0.6f, 0);
        in->UpdateScalarComponent(padX_, touch ? stickX : 0.0f, 0);
        in->UpdateScalarComponent(padY_, touch ? stickY : 0.0f, 0);
        in->UpdateBooleanComponent(padTouch_, touch || (b & (right_ ? PAD_A : PAD_X)), 0);
        in->UpdateBooleanComponent(padClick_, (b & (right_ ? (PAD_A | PAD_R3) : PAD_X)) != 0, 0);
    }

private:
    static constexpr int16_t kGrabTrigger = 153;
    static constexpr float kStickDeadzone = 0.12f, kHandRangeX = 0.40f, kHandRangeUp = 0.50f, kHandRangeDown = 0.30f, kHandSmoothing = 0.05f, kHandPivot = 0.45f;
    bool right_;
    uint32_t id_ = k_unTrackedDeviceIndexInvalid;
    DriverPose_t pose_ = {};
    float pull_ = 0.0f, handX_ = 0.0f, handY_ = 0.0f;
    std::chrono::steady_clock::time_point lastUpdate_ = std::chrono::steady_clock::now();
    VRInputComponentHandle_t system_ = 0, menu_ = 0, grip_ = 0, triggerClick_ = 0, trigger_ = 0, padX_ = 0, padY_ = 0,
                             padClick_ = 0, padTouch_ = 0, haptic_ = 0;
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
        if (pose_.Read(&hp) && hp.display_hz >= 24.0f) hz = hp.display_hz;
        Vsync::SetRate(hz);
        sentHz_ = hz;
        sentTan_ = tanHalf_;
        sentAspect_ = eyeAspect_;
        VRProperties()->SetStringProperty(c, Prop_ModelNumber_String, "DroidDeck Cardboard");
        VRProperties()->SetStringProperty(c, Prop_ManufacturerName_String, "DroidDeck");
        VRProperties()->SetStringProperty(c, Prop_TrackingSystemName_String, "droiddeck");
        VRProperties()->SetFloatProperty(c, Prop_UserIpdMeters_Float, 0.064f);
        VRProperties()->SetFloatProperty(c, Prop_UserHeadToEyeDepthMeters_Float, 0.0f);
        VRProperties()->SetFloatProperty(c, Prop_DisplayFrequency_Float, hz);
        VRProperties()->SetFloatProperty(c, Prop_SecondsFromVsyncToPhotons_Float, 0.011f);
        VRProperties()->SetUint64Property(c, Prop_CurrentUniverseId_Uint64, 2);
        VRProperties()->SetBoolProperty(c, Prop_IsOnDesktop_Bool, false);
        VRProperties()->SetBoolProperty(c, Prop_DisplayDebugMode_Bool, false);
        directMode_ = VRSettings()->GetBool(kSettingsSection, "directMode");
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

    DriverPose_t GetPose() override {
        DriverPose_t p = {};
        p.qWorldFromDriverRotation.w = 1;
        p.qDriverFromHeadRotation.w = 1;
        p.qRotation.w = 1;
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
        return p;
    }

    void GetWindowBounds(int32_t *x, int32_t *y, uint32_t *w, uint32_t *h) override {
        *x = 0; *y = 0; *w = (uint32_t)width_; *h = (uint32_t)height_;
    }
    bool IsDisplayOnDesktop() override { return !directMode_; }
    bool IsDisplayRealDisplay() override { return false; }
    void GetRecommendedRenderTargetSize(uint32_t *w, uint32_t *h) override {
        const auto size = droiddeck::RenderTarget(eyeAspect_, renderHeight_, panelHeight_);
        *w = size.width;
        *h = size.height;
    }
    void GetEyeOutputViewport(EVREye eye, uint32_t *x, uint32_t *y, uint32_t *w, uint32_t *h) override {
        *y = 0; *w = (uint32_t)width_ / 2; *h = (uint32_t)height_;
        *x = eye == Eye_Left ? 0 : (uint32_t)width_ / 2;
    }
    void GetProjectionRaw(EVREye, float *left, float *right, float *top, float *bottom) override {
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

    void PoseLoop() {
        uint32_t lastSeq = 0;
        auto lastSent = std::chrono::steady_clock::now();
        while (running_) {
            droiddeck_headpose hp;
            bool fresh = pose_.Read(&hp) && hp.seq != lastSeq;
            auto now = std::chrono::steady_clock::now();
            PadState pad;
            const bool havePad = pad_.Read(&pad);
            const bool padChanged = havePad && (pad.buttons != lastPad_.buttons || memcmp(pad.axes, lastPad_.axes, sizeof(pad.axes)));
            droiddeck_joycon joyCons;
            const bool haveJoyCons = joyConFile_.Read(&joyCons);
            const bool joyConsChanged = haveJoyCons && joyCons.seq != lastJoyConSeq_;
            if ((fresh || padChanged || joyConsChanged || now - lastSent > std::chrono::milliseconds(100)) && id_ != k_unTrackedDeviceIndexInvalid) {
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
                DriverPose_t head = GetPose();
                VRServerDriverHost()->TrackedDevicePoseUpdated(id_, head, sizeof(DriverPose_t));
                if (havePad && (pad.buttons & PAD_L3) && !(lastPad_.buttons & PAD_L3)) {
                    sticksMoveHands_ = !sticksMoveHands_;
                    Log("sticks now move %s", sticksMoveHands_ ? "the hands" : "in the game");
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
                for (int side = 0; side < 2; side++) {
                    const droiddeck_joycon_hand *joyCon =
                        haveJoyCons && JoyConLive(joyCons.hands[side], nowNs) ? &joyCons.hands[side] : nullptr;
                    if (joyCon) wantJoyCon_[side] = true;
                    const bool covered = joyCons_[side] && joyCons_[side]->Update(joyCon, head.qRotation, nowNs);
                    CardboardController *hand = hands_[side];
                    if (!hand) continue;
                    if (otherControllers_ || covered) {
                        if (!padHandOff_[side]) hand->Disconnect();
                        padHandOff_[side] = true;
                        continue;
                    }
                    padHandOff_[side] = false;
                    hand->Update(head.qRotation, havePad ? &pad : nullptr, sticksMoveHands_, reach_);
                }
                if (havePad) lastPad_ = pad;
                lastSent = now;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
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
    void SetJoyCons(JoyConController *left, JoyConController *right) { joyCons_[0] = left; joyCons_[1] = right; }
    bool WantsJoyCon(int side) const { return wantJoyCon_[side].load(); }

private:
    PoseFile pose_;
    PadRing pad_;
    PadState lastPad_;
    VRInputComponentHandle_t proximity_ = 0;
    bool worn_ = true;
    bool sticksMoveHands_ = true;
    float reach_ = 0.0f;
    std::chrono::steady_clock::time_point lastReach_ = std::chrono::steady_clock::now();
    std::chrono::steady_clock::time_point lastScan_{};
    bool otherControllers_ = false;
    CardboardController *hands_[2] = {nullptr, nullptr};
    bool padHandOff_[2] = {false, false};
    JoyConFile joyConFile_;
    uint32_t lastJoyConSeq_ = 0;
    JoyConController *joyCons_[2] = {nullptr, nullptr};
    std::atomic<bool> wantJoyCon_[2] = {false, false};
    bool virtualDisplay_ = false, directMode_ = false;
    VirtualDisplay vd_;
    DirectMode dm_;
    std::atomic<bool> running_{false};
    std::thread thread_;
};

class Provider : public IServerTrackedDeviceProvider {
public:
    EVRInitError Init(IVRDriverContext *ctx) override {
        VR_INIT_SERVER_DRIVER_CONTEXT(ctx);
        Log("DroidDeck Cardboard driver loaded");
        if (!VRServerDriverHost()->TrackedDeviceAdded("droiddeck-cardboard-hmd", TrackedDeviceClass_HMD, &hmd_))
            Log("TrackedDeviceAdded failed");
        if (VRSettings()->GetBool(kSettingsSection, "gamepadControllers")) {
            VRServerDriverHost()->TrackedDeviceAdded("droiddeck-pad-left", TrackedDeviceClass_Controller, &left_);
            VRServerDriverHost()->TrackedDeviceAdded("droiddeck-pad-right", TrackedDeviceClass_Controller, &right_);
            hmd_.SetHands(&left_, &right_);
        }
        hmd_.SetJoyCons(&joyLeft_, &joyRight_);
        return VRInitError_None;
    }
    void Cleanup() override { VR_CLEANUP_SERVER_DRIVER_CONTEXT(); }
    const char *const *GetInterfaceVersions() override { return k_InterfaceVersions; }
    void RunFrame() override {
        for (int side = 0; side < 2; side++) {
            if (joyConAdded_[side] || !hmd_.WantsJoyCon(side)) continue;
            joyConAdded_[side] = true;
            if (!VRServerDriverHost()->TrackedDeviceAdded(side ? "droiddeck-joycon-right" : "droiddeck-joycon-left",
                                                          TrackedDeviceClass_Controller, side ? &joyRight_ : &joyLeft_))
                Log("%s Joy-Con hand could not be added", side ? "right" : "left");
        }
        VREvent_t e;
        while (VRServerDriverHost()->PollNextEvent(&e, sizeof(e))) {
            if (e.eventType != VREvent_Input_HapticVibration) continue;
            const VREvent_HapticVibration_t &h = e.data.hapticVibration;
            if (!h.componentHandle) continue;
            if (h.componentHandle == joyLeft_.Haptic()) SendRumble(DROIDDECK_JOYCON_RUMBLE_SLOT, h);
            else if (h.componentHandle == joyRight_.Haptic()) SendRumble(DROIDDECK_JOYCON_RUMBLE_SLOT + 1, h);
            else if (h.componentHandle == left_.Haptic() || h.componentHandle == right_.Haptic()) SendRumble(0, h);
        }
    }
    bool ShouldBlockStandbyMode() override { return false; }
    void EnterStandby() override {}
    void LeaveStandby() override {}

private:
    CardboardHmd hmd_;
    CardboardController left_{false}, right_{true};
    JoyConController joyLeft_{false}, joyRight_{true};
    bool joyConAdded_[2] = {false, false};
};

Provider g_provider;

}

DD_EXPORT void *HmdDriverFactory(const char *interfaceName, int *returnCode) {
    if (!strcmp(interfaceName, IServerTrackedDeviceProvider_Version)) return &g_provider;
    if (returnCode) *returnCode = VRInitError_Init_InterfaceNotFound;
    return nullptr;
}
