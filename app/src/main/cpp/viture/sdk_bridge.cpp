#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <array>
#include <cstdint>
#include <memory>
#include <mutex>
#include <time.h>
#include "viture_glasses_provider.h"
#include "viture_device.h"
#include "viture_device_carina.h"
#include "viture_protocol_public.h"
#include "viture_result.h"
#include "viture_version.h"
#include "display_modes.h"

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

extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(nativeOpen)(JNIEnv* env, jobject, jint pid, jint fd, jstring cache) {
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
            xr_device_provider_set_dof_type_carina(s->device, 0) :
            xr_device_provider_register_imu_pose_callback(s->device, on_pose);
    }
    if (rc == 0) {
        const char* directory = env->GetStringUTFChars(cache, nullptr);
        if (!directory) { destroy(*s); return 0; }
        rc = xr_device_provider_initialize(s->device, nullptr, directory);
        env->ReleaseStringUTFChars(cache, directory);
        s->initialized = rc == 0;
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
