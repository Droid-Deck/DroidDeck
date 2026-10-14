#include "openvr_driver.h"

#include <algorithm>
#include <string>
#include <atomic>
#include <cstdarg>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <sys/mman.h>
#include <mutex>
#include <thread>
#include <vector>
#include <unistd.h>

#include "../../../app/src/main/cpp/waylandcomp/src/headpose_shm.h"
#include "presenter.h"

#define DD_EXPORT extern "C" __attribute__((visibility("default")))

using namespace vr;

namespace {

const char *const kSettingsSection = "driver_droiddeck";
const char *const kPosePath = "/run/droiddeck/" DROIDDECK_HEADPOSE_FILE;

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
        double t = duration<double>(steady_clock::now().time_since_epoch()).count();
        double period = 1.0 / 60.0;
        *seconds = (float)fmod(t, period);
        *counter = (uint64_t)(t / period);
        return true;
    }

private:
    uint64_t frames_ = 0;
    std::chrono::steady_clock::time_point last_;
};

class Vsync {
public:
    static constexpr std::chrono::nanoseconds kInterval{16666667};
    static constexpr std::chrono::microseconds kLead{2000};

    static void Start() {
        if (running_.exchange(true)) return;
        thread_ = std::thread([] {
            auto next = std::chrono::steady_clock::now() + kInterval;
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
                next += kInterval;
                now = std::chrono::steady_clock::now();
                while (next <= now) next += kInterval;
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
    static inline std::thread thread_;
    static inline std::mutex mutex_;
    static inline std::chrono::steady_clock::time_point last_{};
};

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
        auto until = last + Vsync::kInterval * (1 + (t ? t->nFramesToThrottle : 0));
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

class CardboardController : public ITrackedDeviceServerDriver {
public:
    explicit CardboardController(bool right) : right_(right) {}

    EVRInitError Activate(uint32_t id) override {
        id_ = id;
        PropertyContainerHandle_t c = VRProperties()->TrackedDeviceToPropertyContainer(id_);
        VRProperties()->SetStringProperty(c, Prop_ModelNumber_String, "DroidDeck Pad Hand");
        VRProperties()->SetStringProperty(c, Prop_ManufacturerName_String, "DroidDeck");
        VRProperties()->SetStringProperty(c, Prop_TrackingSystemName_String, "droiddeck");
        VRProperties()->SetStringProperty(c, Prop_RenderModelName_String, "generic_controller");
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

    void Update(const HmdQuaternion_t &head, const PadState *pad) {
        if (id_ == k_unTrackedDeviceIndexInvalid) return;
        DriverPose_t p = {};
        p.qWorldFromDriverRotation.w = 1;
        p.qDriverFromHeadRotation.w = 1;
        p.qRotation = head;
        const double off[3] = {right_ ? 0.18 : -0.18, -0.35, -0.30};
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
        const float stickX = pad->axes[right_ ? 2 : 0] / 32767.0f, stickY = -pad->axes[right_ ? 3 : 1] / 32767.0f;
        const float trig = pad->axes[right_ ? 4 : 5] / 255.0f;
        const bool touch = stickX * stickX + stickY * stickY > 0.01f;
        in->UpdateBooleanComponent(system_, right_ && (b & PAD_GUIDE), 0);
        in->UpdateBooleanComponent(menu_, (b & (right_ ? PAD_START : PAD_SELECT)) != 0, 0);
        in->UpdateBooleanComponent(grip_, (b & (right_ ? PAD_RB : PAD_LB)) != 0, 0);
        in->UpdateScalarComponent(trigger_, trig, 0);
        in->UpdateBooleanComponent(triggerClick_, trig > 0.6f, 0);
        in->UpdateScalarComponent(padX_, touch ? stickX : 0.0f, 0);
        in->UpdateScalarComponent(padY_, touch ? stickY : 0.0f, 0);
        in->UpdateBooleanComponent(padTouch_, touch || (b & (right_ ? PAD_A : PAD_X)), 0);
        in->UpdateBooleanComponent(padClick_, (b & (right_ ? (PAD_A | PAD_R3) : (PAD_X | PAD_L3))) != 0, 0);
    }

private:
    bool right_;
    uint32_t id_ = k_unTrackedDeviceIndexInvalid;
    DriverPose_t pose_ = {};
    VRInputComponentHandle_t system_ = 0, menu_ = 0, grip_ = 0, triggerClick_ = 0, trigger_ = 0, padX_ = 0, padY_ = 0,
                             padClick_ = 0, padTouch_ = 0, haptic_ = 0;
};

class CardboardHmd : public ITrackedDeviceServerDriver, public IVRDisplayComponent {
public:
    EVRInitError Activate(uint32_t unObjectId) override {
        id_ = unObjectId;
        PropertyContainerHandle_t c = VRProperties()->TrackedDeviceToPropertyContainer(id_);
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
        }
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
        Log("HMD activated: %dx%d at %.0f Hz, eye tan(half fov) %.3f, eye aspect %.3f", width_, height_, hz, tanHalf_, eyeAspect_);

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
        *w = (uint32_t)(width_ / 2 * 1.4f);
        *h = (uint32_t)(height_ * 1.4f);
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
            if ((fresh || padChanged || now - lastSent > std::chrono::milliseconds(100)) && id_ != k_unTrackedDeviceIndexInvalid) {
                if (fresh) {
                    lastSeq = hp.seq;
                    const bool worn = (hp.flags & DROIDDECK_HEADPOSE_ENABLED) != 0;
                    if (worn != worn_) {
                        worn_ = worn;
                        VRDriverInput()->UpdateBooleanComponent(proximity_, worn, 0);
                        Log("headset %s (Cardboard mode %s)", worn ? "worn" : "taken off", worn ? "on" : "off");
                    }
                }
                DriverPose_t head = GetPose();
                VRServerDriverHost()->TrackedDevicePoseUpdated(id_, head, sizeof(DriverPose_t));
                for (CardboardController *hand : hands_) if (hand) hand->Update(head.qRotation, havePad ? &pad : nullptr);
                if (havePad) lastPad_ = pad;
                lastSent = now;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
    }

    uint32_t id_ = k_unTrackedDeviceIndexInvalid;
    int32_t width_ = 1920, height_ = 1080;
    float tanHalf_ = 1.0f, eyeAspect_ = 0.889f;
public:
    void SetHands(CardboardController *left, CardboardController *right) { hands_[0] = left; hands_[1] = right; }

private:
    PoseFile pose_;
    PadRing pad_;
    PadState lastPad_;
    VRInputComponentHandle_t proximity_ = 0;
    bool worn_ = true;
    CardboardController *hands_[2] = {nullptr, nullptr};
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
        return VRInitError_None;
    }
    void Cleanup() override { VR_CLEANUP_SERVER_DRIVER_CONTEXT(); }
    const char *const *GetInterfaceVersions() override { return k_InterfaceVersions; }
    void RunFrame() override {
        VREvent_t e;
        while (VRServerDriverHost()->PollNextEvent(&e, sizeof(e))) {}
    }
    bool ShouldBlockStandbyMode() override { return false; }
    void EnterStandby() override {}
    void LeaveStandby() override {}

private:
    CardboardHmd hmd_;
    CardboardController left_{false}, right_{true};
};

Provider g_provider;

}

DD_EXPORT void *HmdDriverFactory(const char *interfaceName, int *returnCode) {
    if (!strcmp(interfaceName, IServerTrackedDeviceProvider_Version)) return &g_provider;
    if (returnCode) *returnCode = VRInitError_Init_InterfaceNotFound;
    return nullptr;
}
