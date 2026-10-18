#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <array>
#include <vector>
#include <deque>
#include <cmath>
#include <cstring>
#include <cstdint>
#include <memory>
#include <mutex>
#include <condition_variable>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cerrno>
#include <string>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <time.h>
#include "viture_glasses_provider.h"
#include "viture_device.h"
#include "viture_device_carina.h"
#include "viture_camera_provider.h"
#include "viture_protocol_public.h"
#include "viture_result.h"
#include "viture_version.h"
#include "display_modes.h"
#include "stereo_frame_gate.h"

namespace {
// SDK callbacks have no user-data parameter. Only one provider may exist at a time.
std::mutex api_mutex;
std::mutex pose_mutex;
std::array<float, 4> latest_pose{};
int64_t latest_time = 0;
bool accepting_pose = false;
bool accepting_state = false;
std::array<int, 6> state_values{};
unsigned state_pending = 0;

void on_state(int id, int value) {
    std::lock_guard<std::mutex> lock(pose_mutex);
    if (!accepting_state || id < 0 || id >= int(state_values.size())) return;
    state_values[id] = value;
    state_pending |= 1u << id;
}

void clear_state(bool accept) {
    std::lock_guard<std::mutex> lock(pose_mutex);
    accepting_state = accept;
    state_pending = 0;
}

int64_t now_ns() {
    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return int64_t(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

void on_pose(float* data, uint64_t) {
    if (!data) return;
    std::lock_guard<std::mutex> lock(pose_mutex);
    if (!accepting_pose) return;
    std::copy_n(data + 3, 4, latest_pose.begin());
    // SDK device ticks are not Android's elapsed-realtime clock.
    latest_time = now_ns();
}

void clear_pose(bool accept) {
    std::lock_guard<std::mutex> lock(pose_mutex);
    accepting_pose = accept;
    latest_time = 0;
}

int64_t monotonic_ns() {
    timespec ts{}; clock_gettime(CLOCK_MONOTONIC, &ts);
    return int64_t(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}
struct CameraPose { double time; std::array<float, 7> pose; };
std::mutex camera_mutex;
std::deque<CameraPose> camera_poses;
std::vector<uint8_t> camera_left, camera_right;
bool camera_accepting = false, camera_wanted = false;
int camera_width = 0, camera_height = 0;
uint64_t camera_sequence = 0, camera_consumed = 0;
StereoFrameGate camera_gate;
uint64_t camera_input_sequence = 0;
int64_t camera_receipt = 0;
int64_t camera_pose_receipt = 0;
double camera_source = 0;
const CameraPose *matched_camera_pose(double source) {
    const CameraPose *best = nullptr;
    double distance = 0.005;
    for (const auto &p : camera_poses) if (std::abs(p.time-source) < distance) {
        best = &p; distance = std::abs(p.time-source);
    }
    return best;
}
void on_carina_pose(float *pose, double stamp) {
    if (!pose || !std::isfinite(stamp)) return;
    std::lock_guard<std::mutex> lock(camera_mutex);
    if (!camera_accepting) return;
    camera_pose_receipt = monotonic_ns();
    CameraPose p{stamp, {}}; std::copy_n(pose, 7, p.pose.begin());
    camera_poses.push_back(p);
    while (camera_poses.size() > 64) camera_poses.pop_front();
}
void on_camera(char *left, char *right, char *, char *, double stamp, int width, int height) {
    if (!left || !right || width <= 0 || height <= 0 || width > 2048 || height > 2048 || !std::isfinite(stamp)) return;
    const auto received = monotonic_ns();
    std::lock_guard<std::mutex> lock(camera_mutex);
    if (!camera_accepting || !camera_wanted) return;
    if (!camera_gate.Accept(stamp)) return;
    const size_t size = size_t(width) * height;
    camera_left.resize(size); camera_right.resize(size);
    memcpy(camera_left.data(), left, size); memcpy(camera_right.data(), right, size);
    camera_width = width; camera_height = height; camera_source = stamp; camera_receipt = received;
    camera_input_sequence = camera_gate.received;
    ++camera_sequence;
}
void clear_camera(bool enabled) {
    std::lock_guard<std::mutex> lock(camera_mutex);
    camera_accepting = enabled; camera_wanted = false;
    camera_poses.clear(); camera_left.clear(); camera_right.clear();
    camera_sequence = camera_consumed = 0;
    camera_gate = {}; camera_input_sequence = 0;
    camera_pose_receipt = 0;
}

struct Session {
    XRDeviceProviderHandle device = nullptr;
    int pid = 0;
    int type = -1;
    bool initialized = false;
    bool started = false;
    bool tracking = false;
    bool native_dof = false;
    bool switched_native = false;
    int original_bypass_display = -1;
    int saved_dof = -1;
    int64_t consumed_time = 0;
    jlong token = 0;
    int spatial_status = 1;
};
std::unique_ptr<Session> session;
jlong next_token = 0;

void report(const char* step, int rc) {
    if (rc < 0) __android_log_print(ANDROID_LOG_WARN, "VitureSdk", "%s: %d", step, rc);
}

int suspend_native_dof(Session& s) {
    if (!s.native_dof) return 0;
    int mode = xr_device_provider_native_get_mode(s.device);
    if (mode < 0) return mode;
    if (mode != 1) return 0;
    if (s.saved_dof < 0) {
        int dof = xr_device_provider_native_get_dof(s.device);
        if (dof < 0) return dof;
        s.saved_dof = dof;
    }
    return xr_device_provider_native_set_dof(s.device, VITURE_NATIVE_DOF_0);
}

void restore_native_dof(Session& s) {
    if (s.saved_dof >= 0 && xr_device_provider_native_get_mode(s.device) == 1) {
        report("restore native DOF", xr_device_provider_native_set_dof(s.device, s.saved_dof));
    }
    s.saved_dof = -1;
}

int tracking(Session& s, bool enabled) {
    if (s.tracking == enabled) return 0;
    if (!enabled) {
        clear_pose(false);
        int rc = s.type == XR_DEVICE_TYPE_VITURE_CARINA ? 0 :
            xr_device_provider_close_imu(s.device, VITURE_IMU_MODE_POSE);
        s.tracking = false;
        restore_native_dof(s);
        return rc;
    }
    int rc = suspend_native_dof(s);
    if (rc < 0) return rc;
    clear_pose(true);
    s.consumed_time = 0;
    if (s.type != XR_DEVICE_TYPE_VITURE_CARINA) {
        int frequency = VITURE_IMU_FREQUENCY_MEDIUM_HIGH;
        while (frequency >= VITURE_IMU_FREQUENCY_LOW &&
               !xr_device_provider_is_product_support_imu_frequency(s.pid, VITURE_IMU_MODE_POSE, frequency)) --frequency;
        rc = frequency < 0 ? VITURE_GLASSES_ERROR_NOT_SUPPORTED :
            xr_device_provider_open_imu(s.device, VITURE_IMU_MODE_POSE, frequency);
    }
    if (rc == 0) s.tracking = true;
    else { clear_pose(false); restore_native_dof(s); }
    return rc;
}

void destroy(Session& s) {
    clear_camera(false);
    clear_pose(false);
    clear_state(false);
    if (s.device) {
        if (s.tracking) report("close tracking", tracking(s, false));
        if (s.switched_native && s.original_bypass_display >= 0) {
            int rc = xr_device_provider_native_set_mode(s.device, 0);
            if (rc == 0) rc = xr_device_provider_set_display_mode(s.device, s.original_bypass_display);
            report("restore bypass mode", rc);
        }
        if (s.started) report("stop", xr_device_provider_stop(s.device));
        if (s.initialized) report("shutdown", xr_device_provider_shutdown(s.device));
        xr_device_provider_destroy(s.device);
        s.device = nullptr;
    }
}

int get_display(Session& s) {
    if (s.native_dof) {
        const int mode = xr_device_provider_native_get_mode(s.device);
        if (mode < 0) return mode;
        if (mode == 1) {
            const int value = xr_device_provider_native_get_display_mode(s.device);
            return value < 0 ? value : droiddeck::viture::from_native_mode(value);
        }
    }
    return xr_device_provider_get_display_mode(s.device);
}

int set_display(Session& s, int standard) {
    if (!s.native_dof) return xr_device_provider_set_display_mode(s.device, standard);
    int mode = xr_device_provider_native_get_mode(s.device);
    if (mode < 0) return mode;
    if (mode == 0) {
        int rc = xr_device_provider_set_display_mode(s.device, standard);
        if (rc != VITURE_GLASSES_ERROR_NOT_SUPPORTED) return rc;
        // Older Beast firmware exposes fewer bypass modes. Fall back to its
        // separate native table only when the SDK explicitly rejects the mode.
    }
    const int native = droiddeck::viture::to_native_mode(standard);
    if (native < 0) return native;
    if (mode == 0) {
        if (!s.switched_native) {
            s.original_bypass_display = xr_device_provider_get_display_mode(s.device);
            if (s.original_bypass_display < 0) return s.original_bypass_display;
        }
        int rc = xr_device_provider_native_set_mode(s.device, 1);
        if (rc < 0) return rc;
        s.switched_native = true;
    }
    int rc = xr_device_provider_native_set_display_mode(s.device, native);
    if (rc == 0 && s.tracking) rc = suspend_native_dof(s);
    if (rc < 0 && mode == 0) {
        restore_native_dof(s);
        int rollback = xr_device_provider_native_set_mode(s.device, 0);
        if (rollback == 0) rollback = xr_device_provider_set_display_mode(s.device, s.original_bypass_display);
        report("rollback display mode", rollback);
        if (rollback == 0) s.switched_native = false;
    }
    return rc;
}
}

#define JNI_METHOD(name) Java_com_droiddeck_launcher_glasses_VitureSdk_##name
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(nativeAvailable)(JNIEnv*, jobject) {
    __android_log_print(ANDROID_LOG_INFO, "VitureSdk", "SDK %s", GetVersionString());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(nativeMarketName)(JNIEnv* env, jobject, jint pid) {
    std::lock_guard<std::mutex> lock(api_mutex);
    if (!xr_device_provider_is_product_id_valid(pid)) return nullptr;
    char name[64]{};
    int length = sizeof(name) - 1;
    if (xr_device_provider_get_market_name(pid, name, &length) != 0) return nullptr;
    name[sizeof(name) - 1] = 0;
    return env->NewStringUTF(name);
}

extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(nativeOpen)(JNIEnv* env, jobject, jint pid, jint fd, jstring cache, jboolean spatial) {
    std::lock_guard<std::mutex> lock(api_mutex);
    if (session || fd < 0 || !cache || !xr_device_provider_is_product_id_valid(pid)) return 0;
    auto s = std::make_unique<Session>();
    s->pid = pid;
    s->device = xr_device_provider_create(pid, fd);
    if (!s->device) return 0;
    s->type = xr_device_provider_get_device_type(s->device);
    s->native_dof = xr_device_provider_is_product_support_native_dof(pid) == 1;
    int rc = s->type < 0 ? s->type : 0;
    if (rc == 0) {
        clear_state(true);
        rc = xr_device_provider_register_state_callback(s->device, on_state);
    }
    if (rc == 0) {
        rc = s->type == XR_DEVICE_TYPE_VITURE_CARINA ?
            xr_device_provider_set_dof_type_carina(s->device, spatial ? 1 : 0) :
            xr_device_provider_register_imu_pose_callback(s->device, on_pose);
    }
    if (rc == 0) {
        const char* directory = env->GetStringUTFChars(cache, nullptr);
        if (!directory) { destroy(*s); return 0; }
        rc = xr_device_provider_initialize(s->device, nullptr, directory);
        env->ReleaseStringUTFChars(cache, directory);
        s->initialized = rc == 0;
    }
    if (rc == 0 && s->type == XR_DEVICE_TYPE_VITURE_CARINA && spatial) {
        clear_camera(true);
        rc = xr_device_provider_register_callbacks_carina(s->device, on_carina_pose, nullptr, nullptr, on_camera);
    }
    if (rc == 0) {
        rc = xr_device_provider_start(s->device);
        s->started = rc == 0;
    }
    if (rc != 0) { report("initialize/start", rc); destroy(*s); return 0; }
    s->token = ++next_token;
    session = std::move(s);
    return session->token;
}

extern "C" JNIEXPORT jint JNICALL JNI_METHOD(nativeType)(JNIEnv*, jobject, jlong token) {
    std::lock_guard<std::mutex> lock(api_mutex);
    return session && session->token == token ? session->type : -1;
}

extern "C" JNIEXPORT jint JNICALL JNI_METHOD(nativeTracking)(JNIEnv*, jobject, jlong token, jboolean enabled) {
    std::lock_guard<std::mutex> lock(api_mutex);
    return session && session->token == token ? tracking(*session, enabled) : -1;
}

extern "C" JNIEXPORT jint JNICALL JNI_METHOD(nativeCommand)(JNIEnv*, jobject, jlong token, jint command, jint value) {
    std::lock_guard<std::mutex> lock(api_mutex);
    if (!session || session->token != token) return -1;
    auto& s = *session;
    switch (command) {
        case 1: return get_display(s);
        case 2: return set_display(s, value);
        case 3: return xr_device_provider_get_brightness_level(s.device);
        case 4: return xr_device_provider_set_brightness_level(s.device, value);
        case 5: return xr_device_provider_get_volume_level(s.device);
        case 6: return xr_device_provider_set_volume_level(s.device, value);
        case 7: return xr_device_provider_set_film_mode(s.device, value ? 1.0f : 0.0f);
        default: return VITURE_GLASSES_ERROR_NOT_SUPPORTED;
    }
}

extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(nativePose)(JNIEnv* env, jobject, jlong token, jfloatArray output) {
    std::lock_guard<std::mutex> lock(api_mutex);
    if (!session || session->token != token || !output || env->GetArrayLength(output) != 4) return -1;
    auto& s = *session;
    if (!s.tracking) return 0;
    if (s.type == XR_DEVICE_TYPE_VITURE_CARINA) {
        float pose[7]{};
        int status = 1;
        int rc = xr_device_provider_get_gl_pose_carina(s.device, pose, 0.0, &status);
        if (rc < 0) return rc;
        if (status != 0) return 0;
        env->SetFloatArrayRegion(output, 0, 4, pose + 3);
        return now_ns();
    }
    std::lock_guard<std::mutex> pose_lock(pose_mutex);
    if (!latest_time || latest_time == s.consumed_time) return 0;
    env->SetFloatArrayRegion(output, 0, 4, latest_pose.data());
    s.consumed_time = latest_time;
    return latest_time;
}

extern "C" JNIEXPORT jint JNICALL JNI_METHOD(nativeNextState)(JNIEnv* env, jobject, jlong token, jintArray output) {
    std::lock_guard<std::mutex> lock(api_mutex);
    if (!session || session->token != token || !output || env->GetArrayLength(output) != 2) return -1;
    std::lock_guard<std::mutex> state_lock(pose_mutex);
    for (int id = 0; id < int(state_values.size()); ++id) {
        if (!(state_pending & (1u << id))) continue;
        state_pending &= ~(1u << id);
        const jint value[] = {id, state_values[id]};
        env->SetIntArrayRegion(output, 0, 2, value);
        return 1;
    }
    return 0;
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeClose)(JNIEnv*, jobject, jlong token) {
    std::lock_guard<std::mutex> lock(api_mutex);
    if (session && session->token == token) { destroy(*session); session.reset(); }
}

extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(nativeSpatialPose)(JNIEnv* env, jobject, jlong token,
        jfloatArray output, jintArray state) {
    std::lock_guard<std::mutex> lock(api_mutex);
    if (!session || session->token != token || !session->tracking || session->type != XR_DEVICE_TYPE_VITURE_CARINA ||
        !output || !state || env->GetArrayLength(output) != 7 || env->GetArrayLength(state) != 1) return 0;
    float pose[7]{}; int status = 1;
    int rc = xr_device_provider_get_gl_pose_carina(session->device, pose, 0.0, &status);
    session->spatial_status = rc == 0 ? status : rc;
    jint valid = rc == 0 ? status : rc;
    if (valid == 0) {
        std::lock_guard<std::mutex> lock(camera_mutex);
        if (monotonic_ns()-camera_pose_receipt > 100000000LL) valid = 1;
    }
    env->SetIntArrayRegion(state, 0, 1, &valid);
    env->SetFloatArrayRegion(output, 0, 7, pose);
    return monotonic_ns();
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeCapture)(JNIEnv*, jobject, jlong token, jboolean wanted, jint ratePermille) {
    std::lock_guard<std::mutex> api(api_mutex);
    if (!session || session->token != token) return;
    std::lock_guard<std::mutex> lock(camera_mutex);
    camera_wanted = wanted;
    camera_gate.SetRatePermille(wanted ? ratePermille : 1000);
    if (!wanted) camera_consumed = camera_sequence;
}
extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(nativeFirmware)(JNIEnv* env, jobject, jlong token) {
    std::lock_guard<std::mutex> api(api_mutex);
    if (!session || session->token != token) return nullptr;
    char value[256]{}; int length = 255;
    if (xr_device_provider_get_glasses_version(session->device, value, &length) != 0) return nullptr;
    value[255] = 0;
    return env->NewStringUTF(value);
}
extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(nativeSerialHash)(JNIEnv* env, jobject, jlong token) {
    std::lock_guard<std::mutex> api(api_mutex);
    if (!session || session->token != token) return nullptr;
    uint8_t hash[32]{};
    const int rc = xr_device_provider_get_sn_hash(session->device, hash);
    if (rc != 0) { report("read device identity", rc); return nullptr; }
    auto result = env->NewByteArray(32);
    if (result) env->SetByteArrayRegion(result, 0, 32, reinterpret_cast<const jbyte*>(hash));
    return result;
}
extern "C" JNIEXPORT jdoubleArray JNICALL JNI_METHOD(nativeSpatialDiagnostics)(JNIEnv* env, jobject, jlong token) {
    std::lock_guard<std::mutex> api(api_mutex);
    if (!session || session->token != token) return nullptr;
    std::lock_guard<std::mutex> lock(camera_mutex);
    double values[3] = {double(session->spatial_status), camera_pose_receipt ? (monotonic_ns()-camera_pose_receipt)/1e6 : -1.0,
        camera_poses.empty() ? -1.0 : (camera_poses.back().time-camera_source)*1000.0};
    auto result=env->NewDoubleArray(3);
    if(result)env->SetDoubleArrayRegion(result,0,3,values);
    return result;
}
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(nativeStereo)(JNIEnv* env, jobject, jlong token,
        jobject left, jobject right, jdoubleArray metadata, jfloatArray head, jboolean waitForPose) {
    std::lock_guard<std::mutex> api(api_mutex);
    if (!session || session->token != token || !left || !right || !metadata || !head ||
        env->GetArrayLength(metadata) != 9 || env->GetArrayLength(head) != 7) return 0;
    std::lock_guard<std::mutex> lock(camera_mutex);
    if (!camera_wanted || camera_sequence == camera_consumed || camera_left.empty()) return 0;
    const auto size = camera_left.size();
    if (env->GetDirectBufferCapacity(left) < jlong(size) || env->GetDirectBufferCapacity(right) < jlong(size)) return -1;
    auto l = env->GetDirectBufferAddress(left), r = env->GetDirectBufferAddress(right);
    if (!l || !r) return -1;
    const CameraPose *best = matched_camera_pose(camera_source);
    if (waitForPose && !best && monotonic_ns()-camera_receipt < 35000000LL) return 0;
    memcpy(l, camera_left.data(), size); memcpy(r, camera_right.data(), size);
    double meta[9] = {double(camera_width), double(camera_height), camera_source, double(camera_receipt),
                       double(token), double(camera_sequence), best ? 1.0 : 0.0,
                       double(camera_input_sequence), double(camera_gate.skipped)};
    env->SetDoubleArrayRegion(metadata, 0, 9, meta);
    if (best) env->SetFloatArrayRegion(head, 0, 7, best->pose.data());
    camera_consumed = camera_sequence;
    return jlong(camera_sequence);
}
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(nativeCameraSynchronized)(JNIEnv*, jobject, jlong token, jdouble source) {
    std::lock_guard<std::mutex> api(api_mutex);
    if (!session || session->token != token || !std::isfinite(source)) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(camera_mutex);
    return camera_accepting && matched_camera_pose(source) != nullptr;
}

namespace {
struct RgbUsbLog {
    int savedStderr;
    bool hadLevel;
    std::string oldLevel;
    explicit RgbUsbLog(int fd) : savedStderr(dup(STDERR_FILENO)),
            hadLevel(getenv("LIBUSB_DEBUG") != nullptr), oldLevel(hadLevel ? getenv("LIBUSB_DEBUG") : "") {
        fflush(stderr);
        if (savedStderr >= 0) dup2(fd, STDERR_FILENO);
        setenv("LIBUSB_DEBUG", "4", 1);
    }
    ~RgbUsbLog() {
        fflush(stderr);
        if (savedStderr >= 0) { dup2(savedStderr, STDERR_FILENO); close(savedStderr); }
        if (hadLevel) setenv("LIBUSB_DEBUG", oldLevel.c_str(), 1); else unsetenv("LIBUSB_DEBUG");
    }
};

void rgb_usb_state(int fd, const char* stage) {
    uint8_t alternate = 0;
    usbdevfs_ctrltransfer request{};
    request.bRequestType=0x81; request.bRequest=0x0a; request.wIndex=1;
    request.wLength=1; request.timeout=200; request.data=&alternate;
    int result=ioctl(fd, USBDEVFS_CONTROL, &request);
    fprintf(stderr,"RGB %s GET_INTERFACE result=%d errno=%d alternate=%u\n",stage,result,result<0 ? errno : 0,alternate);
    for (int selector : {1, 2}) {
        uint8_t size[2]{};
        request.bRequestType=0xa1; request.bRequest=0x85; request.wValue=selector << 8;
        request.wLength=2; request.data=size;
        result=ioctl(fd,USBDEVFS_CONTROL,&request);
        const unsigned length=result==2 ? unsigned(size[0]) | unsigned(size[1]) << 8 : 26;
        uint8_t bytes[64]{};
        request.bRequest=0x81; request.wLength=std::clamp(length,26u,64u); request.data=bytes;
        result=ioctl(fd,USBDEVFS_CONTROL,&request);
        fprintf(stderr,"RGB %s %s result=%d errno=%d bytes=",stage,selector==1 ? "PROBE" : "COMMIT",result,result<0 ? errno : 0);
        for (int i=0;i<std::max(0,result);++i) fprintf(stderr,"%02x",bytes[i]);
        fprintf(stderr,"\n");
    }
    fflush(stderr);
}
}

extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(nativeRgbSnapshot)(JNIEnv* env, jobject,
        jint vid, jint pid, jint fd, jlongArray metadata, jint logFd) {
    if (fd < 0 || !metadata || env->GetArrayLength(metadata) != 7 ||
        !xr_camera_provider_is_valid_camera(vid, pid)) return nullptr;
    RgbUsbLog usbLog(logFd);
    rgb_usb_state(fd,"before-create");
    struct Capture {
        std::mutex mutex;
        std::condition_variable ready;
        std::vector<uint8_t> jpeg;
        unsigned frames = 0;
        jlong values[7]{};
    } capture;
    auto camera = xr_camera_provider_create(vid, pid, fd);
    if (!camera) { fprintf(stderr,"RGB provider create failed\n"); return nullptr; }
    const int result = xr_camera_provider_start(camera, [](const XRCameraFrame* frame, void* data) {
        auto& c = *static_cast<Capture*>(data);
        std::lock_guard<std::mutex> lock(c.mutex);
        ++c.frames;
        if (!frame || !frame->data || frame->format != XR_CAMERA_FORMAT_MJPEG ||
            frame->size < 4 || frame->size > 4 * 1024 * 1024) return;
        c.jpeg.assign(frame->data, frame->data + frame->size);
        c.values[0] = frame->width; c.values[1] = frame->height;
        c.values[2] = frame->timestamp; c.values[3] = frame->sequence;
        c.values[6] = monotonic_ns();
        c.ready.notify_one();
    }, &capture);
    fprintf(stderr,"RGB start result=%d streaming=%d\n",result,xr_camera_provider_is_streaming(camera));
    rgb_usb_state(fd,"after-start");
    if (result == 0) {
        {
            std::unique_lock<std::mutex> lock(capture.mutex);
            capture.ready.wait_for(lock, std::chrono::seconds(5), [&] { return capture.frames >= 10 && !capture.jpeg.empty(); });
        }
        fprintf(stderr,"RGB stop result=%d\n",xr_camera_provider_stop(camera));
    }
    xr_camera_provider_destroy(camera);
    rgb_usb_state(fd,"after-destroy");
    capture.values[4] = result;
    capture.values[5] = capture.frames;
    env->SetLongArrayRegion(metadata, 0, 7, capture.values);
    if (capture.jpeg.empty()) return nullptr;
    auto bytes = env->NewByteArray(static_cast<jsize>(capture.jpeg.size()));
    if (bytes) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(capture.jpeg.size()),
        reinterpret_cast<const jbyte*>(capture.jpeg.data()));
    return bytes;
}
