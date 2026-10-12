// SteamVR driver for DroidDeck's Cardboard mode: one 3DoF HMD whose pose is the phone's rotation
// sensor, read from the head pose file the app shares with the guest
// (app/src/main/cpp/waylandcomp/src/headpose_shm.h, /run/droiddeck/droiddeck-headpose).
//
// The HMD renders an undistorted side-by-side pair: the app's Cardboard pass, in its side-by-side
// mode, does the lens distortion for the viewer the user picked. So the projection here is exactly
// the frustum that pass samples (tan_half_fov, eye_aspect from the pose file), and the distortion is
// the identity.
//
// How the frames reach the screen is the open question this driver is for: see docs/cardboard-vr.md.
// It exposes IVRDisplayComponent with the display "on the desktop" (extended mode) at the session's
// size, and logs what vrserver / vrcompositor ask of it.
#include "openvr_driver.h"

#include <atomic>
#include <cstdarg>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <sys/mman.h>
#include <thread>
#include <unistd.h>

#include "../../../app/src/main/cpp/waylandcomp/src/headpose_shm.h"

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

// The shared pose file, mapped read-only; reads follow the seqlock in headpose_shm.h.
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
    // Row-major rotation -> quaternion (Shepperd's method).
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
        // Until the app has drawn an eye: the Cardboard pass's defaults for a 1920x1080 panel.
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
        VRProperties()->SetBoolProperty(c, Prop_IsOnDesktop_Bool, true);
        VRProperties()->SetBoolProperty(c, Prop_DisplayDebugMode_Bool, false);
        VRProperties()->SetBoolProperty(c, Prop_HasDriverDirectModeComponent_Bool, false);
        VRProperties()->SetBoolProperty(c, Prop_HasDisplayComponent_Bool, true);
        VRProperties()->SetBoolProperty(c, Prop_HasVirtualDisplayComponent_Bool, false);
        Log("HMD activated: %dx%d at %.0f Hz, eye tan(half fov) %.3f, eye aspect %.3f", width_, height_, hz, tanHalf_, eyeAspect_);

        running_ = true;
        thread_ = std::thread([this] { PoseLoop(); });
        return VRInitError_None;
    }

    void Deactivate() override {
        running_ = false;
        if (thread_.joinable()) thread_.join();
        id_ = k_unTrackedDeviceIndexInvalid;
    }

    void EnterStandby() override {}

    void *GetComponent(const char *name) override {
        Log("component asked for: %s", name);
        if (!strncmp(name, "IVRDisplayComponent_", 20)) return static_cast<IVRDisplayComponent *>(this);
        return nullptr;
    }

    void DebugRequest(const char *, char *resp, uint32_t size) override { if (size) resp[0] = 0; }

    DriverPose_t GetPose() override {
        DriverPose_t p = {};
        p.qWorldFromDriverRotation.w = 1;
        p.qDriverFromHeadRotation.w = 1;
        p.qRotation.w = 1;
        p.vecPosition[1] = 1.6; // standing eye height: 3DoF, the head never moves
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

    // ---- IVRDisplayComponent
    void GetWindowBounds(int32_t *x, int32_t *y, uint32_t *w, uint32_t *h) override {
        *x = 0; *y = 0; *w = (uint32_t)width_; *h = (uint32_t)height_;
    }
    bool IsDisplayOnDesktop() override { return true; }
    bool IsDisplayRealDisplay() override { return false; }
    void GetRecommendedRenderTargetSize(uint32_t *w, uint32_t *h) override {
        // Rendered a little larger than the panel: the lens pass magnifies the middle.
        *w = (uint32_t)(width_ / 2 * 1.4f);
        *h = (uint32_t)(height_ * 1.4f);
    }
    void GetEyeOutputViewport(EVREye eye, uint32_t *x, uint32_t *y, uint32_t *w, uint32_t *h) override {
        *y = 0; *w = (uint32_t)width_ / 2; *h = (uint32_t)height_;
        *x = eye == Eye_Left ? 0 : (uint32_t)width_ / 2;
    }
    void GetProjectionRaw(EVREye, float *left, float *right, float *top, float *bottom) override {
        // Exactly what the Cardboard pass samples from each half of a side-by-side frame.
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
            // A new sample, or a heartbeat so SteamVR never thinks the HMD went away.
            if ((fresh || now - lastSent > std::chrono::milliseconds(100)) && id_ != k_unTrackedDeviceIndexInvalid) {
                if (fresh) lastSeq = hp.seq;
                VRServerDriverHost()->TrackedDevicePoseUpdated(id_, GetPose(), sizeof(DriverPose_t));
                lastSent = now;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
    }

    uint32_t id_ = k_unTrackedDeviceIndexInvalid;
    int32_t width_ = 1920, height_ = 1080;
    float tanHalf_ = 1.0f, eyeAspect_ = 0.889f;
    PoseFile pose_;
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
};

Provider g_provider;

} // namespace

DD_EXPORT void *HmdDriverFactory(const char *interfaceName, int *returnCode) {
    if (!strcmp(interfaceName, IServerTrackedDeviceProvider_Version)) return &g_provider;
    if (returnCode) *returnCode = VRInitError_Init_InterfaceNotFound;
    return nullptr;
}
