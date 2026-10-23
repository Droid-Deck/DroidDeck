#include <jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <android_native_app_glue.h>
#include <time.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <array>
#include <vector>
#include <utility>
#include <string>
#include <stdexcept>
#include <cstdio>
#include <cstring>
#include <cmath>
#include <chrono>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cerrno>
#include <cstddef>
#include <ctime>
#include "xr_tracking.h"
#include "xr_frame_channel.h"
#include "xr_frame_math.h"
#include "GpuFrames.h"
#include "OpenXrAndroid.h"
#include "MappedGuestXrAdapter.h"
#include "xr_skeleton.h"
#include "Json.h"
#include "ScopeExit.h"
#include <algorithm>

using namespace droiddeck::xr;

static void check(XrResult result, const char *operation) {
    if (XR_FAILED(result)) throw std::runtime_error(std::string(operation) + ": " + std::to_string(result));
}

struct Eye {
    XrSwapchain swapchain = XR_NULL_HANDLE;
    int width = 0, height = 0;
    std::vector<XrSwapchainImageOpenGLESKHR> images;
};

struct RuntimeSession { uint64_t generation, originEpoch; };

namespace {
class AndroidCallbacks {
public:
    AndroidCallbacks(android_app *app, JNIEnv *env) : app_(app), env_(env) {
        jclass type = env_->GetObjectClass(app_->activity->clazz);
        active_ = env_->GetMethodID(type, "onXrActive", "(Z)V");
        frame_ = env_->GetMethodID(type, "onXrFrame", "(J)V");
        status_ = env_->GetMethodID(type, "onXrStatus", "()I");
        error_ = env_->GetMethodID(type, "onXrError", "(Ljava/lang/String;)V");
        env_->DeleteLocalRef(type);
        checked();
    }
    void initializeGamepad() {
        jclass type = env_->GetObjectClass(app_->activity->clazz);
        gamepadKey_ = env_->GetMethodID(type, "onXrGamepadKey", "(IIIIIIIIJJ)Z");
        gamepadMotion_ = env_->GetMethodID(type, "onXrGamepadMotion", "(IIIJJ[F)Z");
        gamepadFocus_ = env_->GetMethodID(type, "onXrInputFocus", "(Z)V");
        gamepadMenu_ = env_->GetMethodID(type, "onXrGamepadMenu", "()I");
        env_->DeleteLocalRef(type);
        checked();
    }
    void onInputFocus(bool focused) {
        if (!gamepadFocus_) return;
        env_->CallVoidMethod(app_->activity->clazz, gamepadFocus_, jboolean(focused)); checked();
    }
    uint32_t gamepadMenu() {
        if (!gamepadMenu_) return 0;
        const auto result = env_->CallIntMethod(app_->activity->clazz, gamepadMenu_); checked();
        return uint32_t(result);
    }
    bool onInput(AInputEvent *event) {
        if (!gamepadKey_) return false;
        jboolean handled = false;
        if (AInputEvent_getType(event) == AINPUT_EVENT_TYPE_KEY) {
            handled = env_->CallBooleanMethod(app_->activity->clazz, gamepadKey_,
                AInputEvent_getDeviceId(event), AInputEvent_getSource(event), AKeyEvent_getAction(event),
                AKeyEvent_getKeyCode(event), AKeyEvent_getScanCode(event), AKeyEvent_getRepeatCount(event),
                AKeyEvent_getMetaState(event), AKeyEvent_getFlags(event),
                jlong(AKeyEvent_getDownTime(event) / 1000000), jlong(AKeyEvent_getEventTime(event) / 1000000));
        } else if (AInputEvent_getType(event) == AINPUT_EVENT_TYPE_MOTION &&
            (AInputEvent_getSource(event) & AINPUT_SOURCE_JOYSTICK) == AINPUT_SOURCE_JOYSTICK &&
            AMotionEvent_getPointerCount(event) > 0) {
            constexpr int axes[] = {AMOTION_EVENT_AXIS_X, AMOTION_EVENT_AXIS_Y, AMOTION_EVENT_AXIS_Z, AMOTION_EVENT_AXIS_RZ,
                AMOTION_EVENT_AXIS_RX, AMOTION_EVENT_AXIS_RY, AMOTION_EVENT_AXIS_HAT_X, AMOTION_EVENT_AXIS_HAT_Y,
                AMOTION_EVENT_AXIS_LTRIGGER, AMOTION_EVENT_AXIS_RTRIGGER, AMOTION_EVENT_AXIS_BRAKE, AMOTION_EVENT_AXIS_GAS};
            jfloat values[12];
            for (int i = 0; i < 12; i++) values[i] = AMotionEvent_getAxisValue(event, axes[i], 0);
            jfloatArray array = env_->NewFloatArray(12);
            if (!array) { checked(); return false; }
            env_->SetFloatArrayRegion(array, 0, 12, values);
            handled = env_->CallBooleanMethod(app_->activity->clazz, gamepadMotion_,
                AInputEvent_getDeviceId(event), AInputEvent_getSource(event), AMotionEvent_getAction(event),
                jlong(AMotionEvent_getDownTime(event) / 1000000), jlong(AMotionEvent_getEventTime(event) / 1000000), array);
            env_->DeleteLocalRef(array);
        }
        checked();
        return handled;
    }
    void onActiveChanged(bool active) {
        env_->CallVoidMethod(app_->activity->clazz, active_, jboolean(active)); checked();
    }
    void onFrame(int64_t time) {
        env_->CallVoidMethod(app_->activity->clazz, frame_, jlong(time)); checked();
    }
    int guestStatus() {
        int result = env_->CallIntMethod(app_->activity->clazz, status_);
        if (env_->ExceptionCheck()) { env_->ExceptionClear(); return -1; }
        return result;
    }
    void onError(const std::string &message) {
        jstring text = env_->NewStringUTF(message.c_str());
        env_->CallVoidMethod(app_->activity->clazz, error_, text);
        env_->DeleteLocalRef(text);
        if (env_->ExceptionCheck()) env_->ExceptionClear();
    }
private:
    void checked() {
        if (!env_->ExceptionCheck()) return;
        env_->ExceptionDescribe(); env_->ExceptionClear();
        throw std::runtime_error("Android XR callback failed");
    }
    android_app *app_;
    JNIEnv *env_;
    jmethodID gamepadKey_ = nullptr, gamepadMotion_ = nullptr, gamepadFocus_ = nullptr, gamepadMenu_ = nullptr;
    jmethodID active_ = nullptr, frame_ = nullptr, status_ = nullptr, error_ = nullptr;
};
}

class OpenXrRuntime {
public:
    OpenXrRuntime(android_app *app, JNIEnv *env) : app_(app), env_(env) {}
    ~OpenXrRuntime() { cleanup(); }

    bool run(const RuntimeSession &session, AndroidCallbacks &callbacks) {
        runtimeSession_ = session;
        callbacks_ = &callbacks;

        try { runLoop(); }
        catch (const std::exception &error) {
            __android_log_print(ANDROID_LOG_ERROR, "DroidDeckXR", "runtime: %s", error.what());
            health(error.what());
            callbacks_->onError(error.what());
            return false;
        }
        return true;
    }
    void runLoop() {
        initialize();
        while (!app_->destroyRequested && !exit_) {
            android_poll_source *source = nullptr;
            int events = 0;
            int pollTimeout = running_ ? 0 : 100;
            for (int eventCount = 0; eventCount < 32; eventCount++) {
                if (ALooper_pollOnce(pollTimeout, nullptr, &events, reinterpret_cast<void **>(&source)) < 0) break;
                if (source) source->process(app_, source);
                if (app_->destroyRequested) break;
                pollTimeout = 0;
            }
            pollEvents();
            if (running_ && !app_->destroyRequested) frame();
        }
        if (exit_ && !app_->destroyRequested) ANativeActivity_finish(app_->activity);
    }

    void health(const char *error = "") {
        const int steamvrState = callbacks_ ? callbacks_->guestStatus() : -1;
        const std::string encodedError = jsonString(error);
        std::string path = std::string(dataPath()) + "/xr-health.json";
        std::string temp = path + ".tmp";
        FILE *file = std::fopen(temp.c_str(), "w");
        if (!file) return;
        std::fprintf(file,
            "{\"schemaVersion\":3,\"backend\":\"openxr\",\"frames\":%llu,\"rendered\":%llu,\"sessionState\":%d,\"headFlags\":%llu,"
            "\"headPosition\":[%.6f,%.6f,%.6f],\"controllersActive\":[%s,%s],\"handsActive\":[%s,%s],"
            "\"periodNs\":%lld,\"surfaceSwapchain\":%s,\"source\":\"%s\",\"guestFrames\":%lld,\"steamvrState\":%d,"
            "\"receivedFrames\":%llu,\"rejectedFrames\":%llu,\"uploadedFrames\":%llu,\"sourceFrameId\":%llu,"
            "\"gpuReceivedFrames\":%llu,\"gpuDroppedFrames\":%llu,\"gpuAsyncCompletion\":%s,\"gpuCompletionFailures\":%llu,\"gpuVerifiedFrameId\":%llu,\"gpuChecksum\":%u,\"error\":%s,\"runtime\":%s,\"runtimeVersion\":%s,\"inputMode\":\"%s\",\"generation\":%llu,\"originEpoch\":%llu,\"profiles\":[%s,%s],\"handPinch\":[%g,%g],\"handSelect\":[%g,%g],\"handPinchPresses\":[%llu,%llu],\"handPinchReleases\":[%llu,%llu],\"floorKnown\":%s,\"handProviders\":[%s,%s],\"eyeSeparationMeters\":%g,\"skeletonActive\":[%u,%u],\"jointPermission\":%s,\"transferMs\":%g,\"sourceAgeMs\":%g,\"gamepadKeyEvents\":%llu,\"gamepadMotionEvents\":%llu,\"gamepadMenuSequence\":%u}\n",
            (unsigned long long)frames_, (unsigned long long)rendered_, state_,
            (unsigned long long)head_.locationFlags,
            finite(head_.pose.position.x), finite(head_.pose.position.y), finite(head_.pose.position.z),
            (!handMode_ && handActive_[0]) ? "true" : "false", (!handMode_ && handActive_[1]) ? "true" : "false",
            (handMode_ && handActive_[0]) ? "true" : "false", (handMode_ && handActive_[1]) ? "true" : "false",
            (long long)period_, "false", "frames",
            0LL, steamvrState, (unsigned long long)(receiver_.Received() + gpuFrames_.Received()),
            (unsigned long long)(receiver_.Rejected() + gpuFrames_.Rejected()),
            (unsigned long long)uploadedFrames_, (unsigned long long)sourceHeader_.frame_id,
            (unsigned long long)gpuFrames_.Received(), (unsigned long long)gpuFrames_.Dropped(),
            gpuFrames_.AsyncCompletion() ? "true" : "false", (unsigned long long)gpuFrames_.CompletionFailures(),
            (unsigned long long)verifiedFrame_, gpuChecksum_, encodedError.c_str(),
            jsonString(runtimeName_).c_str(), jsonString(runtimeVersion_).c_str(),
            handMode_ ? "hands" : "controllers", (unsigned long long)runtimeSession_.generation,
            (unsigned long long)runtimeSession_.originEpoch, jsonString(profiles_[0]).c_str(), jsonString(profiles_[1]).c_str(),
            finite(handPacket_.hands[0].pinch), finite(handPacket_.hands[1].pinch),
            finite(handPacket_.hands[0].select), finite(handPacket_.hands[1].select),
            (unsigned long long)pinchPresses_[0], (unsigned long long)pinchPresses_[1],
            (unsigned long long)pinchReleases_[0], (unsigned long long)pinchReleases_[1], floorKnown_ ? "true" : "false", jsonString(handProviders_[0]).c_str(), jsonString(handProviders_[1]).c_str(),
            finite(eyeSeparation_), skeleton_.active[0], skeleton_.active[1], jointPermission_ ? "true" : "false", finite(transferMs_), finite(sourceAgeMs_),
            (unsigned long long)gamepadKeys_, (unsigned long long)gamepadMotions_, handPacket_.gamepad_menu_sequence);
        std::fclose(file);
        std::rename(temp.c_str(), path.c_str());
    }

private:
    android_app *app_;
    std::string runtimeName_, runtimeVersion_;
    RuntimeSession runtimeSession_{};
    AndroidCallbacks *callbacks_ = nullptr;
    MappedGuestXrAdapter inputAdapter_;
    DroidDeckXrInputV2 handPacket_{};
    uint64_t gamepadKeys_ = 0, gamepadMotions_ = 0;
    DroidDeckXrSkeleton skeleton_{};
    XrAtomicFile<DroidDeckXrSkeleton> skeletonFile_;
    double transferMs_ = 0, sourceAgeMs_ = 0;
    bool floorKnown_ = false, jointPermission_ = false;
    bool handExtension_ = false, jointTrackingExtension_ = false, jointAimExtension_ = false, localFloorExtension_ = false;
    bool handMode_ = false, handDataSourceExtension_ = false;
    std::string inputPolicy_ = "auto";
    XrAction handSelect_ = XR_NULL_HANDLE, selectReady_ = XR_NULL_HANDLE;
    XrAction handGrip_ = XR_NULL_HANDLE, handPinch_ = XR_NULL_HANDLE, handGrasp_ = XR_NULL_HANDLE;
    XrSpace handGripSpaces_[2]{};
    XrHandTrackerEXT trackers_[2]{};
    PFN_xrCreateHandTrackerEXT createTracker_ = nullptr;
    PFN_xrLocateHandJointsEXT locateJoints_ = nullptr;
    PFN_xrDestroyHandTrackerEXT destroyTracker_ = nullptr;
    SteamVrHandBridge inputMonitor_[2];
    bool pinchDown_[2]{};
    uint64_t pinchPresses_[2]{}, pinchReleases_[2]{};
    std::string profiles_[2], handProviders_[2];
    XrAction aim_ = XR_NULL_HANDLE;
    XrAction pinchReady_ = XR_NULL_HANDLE, graspReady_ = XR_NULL_HANDLE;
    XrSpace aimSpaces_[2]{};
    XrSpaceLocation aimLocations_[2]{{XR_TYPE_SPACE_LOCATION}, {XR_TYPE_SPACE_LOCATION}};
    XrEnvironmentBlendMode blendMode_ = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    XrViewStateFlags viewFlags_ = 0;
    float eyeSeparation_ = 0;
    XrReferenceSpaceType worldType_ = XR_REFERENCE_SPACE_TYPE_LOCAL;
    std::vector<XrTime> spaceChangeTimes_;
    unsigned neutralFrames_ = 0;
    const char *dataPath() const {
        return app_->activity->externalDataPath ? app_->activity->externalDataPath : app_->activity->internalDataPath;
    }
    static float finite(float value) { return std::isfinite(value) ? value : 0.0f; }
    JNIEnv *env_ = nullptr;
    XrInstance instance_ = XR_NULL_HANDLE;
    XrSession session_ = XR_NULL_HANDLE;
    XrSystemId system_ = XR_NULL_SYSTEM_ID;
    XrSpace world_ = XR_NULL_HANDLE, view_ = XR_NULL_HANDLE;
    XrActionSet actions_ = XR_NULL_HANDLE;
    XrAction grip_ = XR_NULL_HANDLE, trigger_ = XR_NULL_HANDLE, vibration_ = XR_NULL_HANDLE;
    XrAction squeeze_ = XR_NULL_HANDLE, stick_ = XR_NULL_HANDLE, stickClick_ = XR_NULL_HANDLE;
    XrAction primary_ = XR_NULL_HANDLE, secondary_ = XR_NULL_HANDLE, menu_ = XR_NULL_HANDLE;
    XrAction triggerTouch_ = XR_NULL_HANDLE, stickTouch_ = XR_NULL_HANDLE, primaryTouch_ = XR_NULL_HANDLE;
    XrAction secondaryTouch_ = XR_NULL_HANDLE, thumbrestTouch_ = XR_NULL_HANDLE;
    DroidDeckXrHand inputs_[2]{};
    PFN_xrConvertTimeToTimespecTimeKHR convertTime_ = nullptr;
    bool timespecSupported_ = false;
    PFN_xrPerfSettingsSetPerformanceLevelEXT setPerformance_ = nullptr;
    bool performanceSupported_ = false;
    XrPath hands_[2]{};
    XrSpace handSpaces_[2]{};
    XrSpaceLocation head_{XR_TYPE_SPACE_LOCATION};
    XrSpaceLocation handLocations_[2]{{XR_TYPE_SPACE_LOCATION}, {XR_TYPE_SPACE_LOCATION}};
    bool handActive_[2]{};
    bool running_ = false, exit_ = false, quitRequested_ = false;
    bool guestAttached_ = false, verifyPixels_ = false;
    XrFrameReceiver receiver_;
    XrGpuFrames gpuFrames_;
    DroidDeckXrFrameHeader sourceHeader_{};
    uint64_t uploadedFrames_ = 0, verifiedFrame_ = 0;
    uint32_t gpuChecksum_ = 0;
    GLuint sourceTexture_ = 0, sourceProgram_ = 0;
    GLuint cpuTexture_ = 0;
    DroidDeckXrTracking *tracking_ = nullptr;
    DroidDeckXrHaptics *haptics_ = nullptr;
    uint64_t lastPulse_[2]{};
    XrSessionState state_ = XR_SESSION_STATE_UNKNOWN;
    uint64_t frames_ = 0, rendered_ = 0;
    XrDuration period_ = 0;
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface surface_ = EGL_NO_SURFACE;
    std::array<Eye, 2> eyes_;
    GLuint framebuffer_ = 0, vertexArray_ = 0;
    GLint sourceEncoding_ = 0, eyeEncoding_ = 0;

    std::string captureRequest() {
        if (frames_ % 9) return {};
        std::string file = std::string(dataPath()) + "/xr-capture.request";
        FILE *request = std::fopen(file.c_str(), "r");
        if (!request) return {};
        char token[34]{};
        size_t size = std::fread(token, 1, sizeof(token) - 1, request);
        std::fclose(request);
        unlink(file.c_str());
        if (size != 32 || std::strspn(token, "0123456789abcdef") != 32) return {};
        unlink((std::string(dataPath()) + "/xr-capture.json").c_str());
        return token;
    }

    bool captureTexture(GLuint texture, int width, int height, const char *name) {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        GLint encoding = 0;
        glGetFramebufferAttachmentParameteriv(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                                              GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING, &encoding);
        if (!std::strcmp(name, "source")) sourceEncoding_ = encoding;
        else eyeEncoding_ = encoding;
        std::vector<unsigned char> pixels((size_t)width * height * 4);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
        bool ok = glGetError() == GL_NO_ERROR;
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        if (!ok) return false;
        std::string file = std::string(dataPath()) +
            "/xr-capture-" + name + ".ppm";
        std::string temporary = file + ".tmp";
        FILE *output = std::fopen(temporary.c_str(), "wb");
        if (!output) return false;
        ok = std::fprintf(output, "P6\n%d %d\n255\n", width, height) > 0;
        std::vector<unsigned char> row((size_t)width * 3);
        for (int y = height - 1; y >= 0 && ok; y--) {
            for (int x = 0; x < width; x++)
                std::memcpy(row.data() + x * 3, pixels.data() + ((size_t)y * width + x) * 4, 3);
            ok = std::fwrite(row.data(), 1, row.size(), output) == row.size();
        }
        if (std::fclose(output) != 0) ok = false;
        if (!ok) { unlink(temporary.c_str()); return false; }
        return std::rename(temporary.c_str(), file.c_str()) == 0;
    }

    void captureResult(const std::string &token, bool ok, bool valid,
                       const XrCompositionLayerProjectionView (&views)[2], XrTime displayTime) {
        std::string file = std::string(dataPath()) + "/xr-capture.json";
        std::string temporary = file + ".tmp";
        FILE *output = std::fopen(temporary.c_str(), "w");
        if (!output) return;
        std::fprintf(output, "{\"id\":\"%s\",\"ok\":%s,\"validLayer\":%s,\"frame\":%llu,"
            "\"sourceFrameId\":%llu,\"displayTime\":%lld,\"sessionState\":%d,\"eyes\":[",
            token.c_str(), ok ? "true" : "false", valid ? "true" : "false",
            (unsigned long long)frames_, (unsigned long long)sourceHeader_.frame_id, (long long)displayTime, state_);
        for (int eye = 0; eye < 2; eye++) {
            const auto &pose = views[eye].pose;
            const auto &fov = views[eye].fov;
            std::fprintf(output, "%s{\"width\":%d,\"height\":%d,\"position\":[%g,%g,%g],"
                "\"orientation\":[%g,%g,%g,%g],\"fov\":[%g,%g,%g,%g]}",
                eye ? "," : "", eyes_[eye].width, eyes_[eye].height,
                pose.position.x, pose.position.y, pose.position.z,
                pose.orientation.x, pose.orientation.y, pose.orientation.z, pose.orientation.w,
                fov.angleLeft, fov.angleRight, fov.angleUp, fov.angleDown);
        }
        const char *extensions = reinterpret_cast<const char *>(glGetString(GL_EXTENSIONS));
        const bool srgbControl = extensions && std::strstr(extensions, "GL_EXT_sRGB_write_control");
        std::fprintf(output, "],\"sourceImage\":%s,\"sourceColorSpace\":\"%s\",\"sourceEncoding\":%d,\"eyeEncoding\":%d,"
            "\"srgbWriteControl\":%s,\"srgbWriteEnabled\":%s}\n",
            sourceHeader_.frame_id ? "true" : "false",
            sourceHeader_.color_space == DROIDDECK_XR_COLOR_SRGB_RGBA8 ? "srgb" : "linear", sourceEncoding_, eyeEncoding_,
            srgbControl ? "true" : "false", srgbControl && glIsEnabled(0x8DB9) ? "true" : "false");
        if (std::fclose(output) == 0) std::rename(temporary.c_str(), file.c_str());
    }

    XrPath path(const char *text) {
        XrPath result{};
        check(xrStringToPath(instance_, text, &result), "xrStringToPath");
        return result;
    }

    void initialize() {
        app_->userData = this;
        app_->onInputEvent = [](android_app *app, AInputEvent *event) -> int32_t {
            auto *runtime = static_cast<OpenXrRuntime *>(app->userData);
            if (runtime->callbacks_->onInput(event)) {
                if (AInputEvent_getType(event) == AINPUT_EVENT_TYPE_KEY) runtime->gamepadKeys_++;
                else runtime->gamepadMotions_++;
                return 1;
            }
            if (AInputEvent_getType(event) != AINPUT_EVENT_TYPE_KEY || AKeyEvent_getKeyCode(event) != AKEYCODE_BACK
                || AKeyEvent_getAction(event) != AKEY_EVENT_ACTION_UP) return 0;
            if (runtime->running_) {
                XrResult result = xrRequestExitSession(runtime->session_);
                if (XR_SUCCEEDED(result)) runtime->quitRequested_ = true;
                else __android_log_print(ANDROID_LOG_ERROR, "DroidDeckXR", "exit request failed: %d", result);
            } else ANativeActivity_finish(app->activity);
            return 1;
        };
        jclass activityClass = env_->GetObjectClass(app_->activity->clazz);
        jmethodID getIntent = env_->GetMethodID(activityClass, "getIntent", "()Landroid/content/Intent;");
        jobject intent = env_->CallObjectMethod(app_->activity->clazz, getIntent);
        jclass intentClass = env_->GetObjectClass(intent);
        jmethodID getBoolean = env_->GetMethodID(intentClass, "getBooleanExtra", "(Ljava/lang/String;Z)Z");
        jstring extra = env_->NewStringUTF("frame_verify");
        verifyPixels_ = env_->CallBooleanMethod(intent, getBoolean, extra, JNI_FALSE);
        env_->DeleteLocalRef(extra);
        callbacks_->initializeGamepad();
        jmethodID getString = env_->GetMethodID(intentClass, "getStringExtra", "(Ljava/lang/String;)Ljava/lang/String;");
        extra = env_->NewStringUTF("xr_input");
        auto inputPolicy = static_cast<jstring>(env_->CallObjectMethod(intent, getString, extra));
        if (inputPolicy) {
            const char *text = env_->GetStringUTFChars(inputPolicy, nullptr);
            inputPolicy_ = text;
            env_->ReleaseStringUTFChars(inputPolicy, text); env_->DeleteLocalRef(inputPolicy);
        }
        env_->DeleteLocalRef(extra);
        if (inputPolicy_ != "auto" && inputPolicy_ != "hands" && inputPolicy_ != "controllers")
            throw std::runtime_error("Unknown XR input policy");
        handMode_ = inputPolicy_ == "hands";
        if (env_->ExceptionCheck()) { env_->ExceptionClear(); throw std::runtime_error("XR activity configuration failed"); }
        jmethodID checkPermission = env_->GetMethodID(activityClass, "checkSelfPermission", "(Ljava/lang/String;)I");
        for (const char *permission : {"com.oculus.permission.HAND_TRACKING", "android.permission.HAND_TRACKING"}) {
            jstring name = env_->NewStringUTF(permission);
            jointPermission_ |= env_->CallIntMethod(app_->activity->clazz, checkPermission, name) == 0;
            env_->DeleteLocalRef(name);
        }
        if (env_->ExceptionCheck()) { env_->ExceptionClear(); jointPermission_ = false; }
        env_->DeleteLocalRef(intentClass); env_->DeleteLocalRef(intent); env_->DeleteLocalRef(activityClass);
        PFN_xrInitializeLoaderKHR initializeLoader = nullptr;
        check(xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR",
            reinterpret_cast<PFN_xrVoidFunction *>(&initializeLoader)), "loader init entrypoint");
        XrLoaderInitInfoAndroidKHR loader{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
        loader.applicationVM = app_->activity->vm;
        loader.applicationContext = app_->activity->clazz;
        check(initializeLoader(reinterpret_cast<XrLoaderInitInfoBaseHeaderKHR *>(&loader)), "xrInitializeLoaderKHR");
        uint32_t count = 0;
        check(xrEnumerateInstanceExtensionProperties(nullptr, 0, &count, nullptr), "extensions count");
        std::vector<XrExtensionProperties> extensions(count, {XR_TYPE_EXTENSION_PROPERTIES});
        check(xrEnumerateInstanceExtensionProperties(nullptr, count, &count, extensions.data()), "extensions");
        auto hasExtension = [&](const char *name) {
            return std::any_of(extensions.begin(), extensions.end(), [&](const auto &extension) {
                return !std::strcmp(extension.extensionName, name);
            });
        };
        if (!hasExtension(XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME) || !hasExtension(XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME))
            throw std::runtime_error("Required Android/GLES OpenXR extensions unavailable");
        handExtension_ = inputPolicy_ != "controllers" && hasExtension("XR_EXT_hand_interaction");
        jointTrackingExtension_ = inputPolicy_ != "controllers" && jointPermission_ && hasExtension(XR_EXT_HAND_TRACKING_EXTENSION_NAME);
        jointAimExtension_ = jointTrackingExtension_ && hasExtension(XR_FB_HAND_TRACKING_AIM_EXTENSION_NAME);
        handDataSourceExtension_ = jointTrackingExtension_ && hasExtension(XR_EXT_HAND_TRACKING_DATA_SOURCE_EXTENSION_NAME);
        localFloorExtension_ = hasExtension("XR_EXT_local_floor");
        if (inputPolicy_ == "hands" && !handExtension_ && !jointAimExtension_) {

            throw std::runtime_error("No permitted OpenXR hand input provider available");
        }
        for (const auto &extension : extensions) {
            if (!std::strcmp(extension.extensionName, XR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME))
                timespecSupported_ = true;
            if (!std::strcmp(extension.extensionName, XR_EXT_PERFORMANCE_SETTINGS_EXTENSION_NAME))
                performanceSupported_ = true;
        }
        std::vector<const char *> enabled{XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME, XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME};
        if (handExtension_) enabled.push_back("XR_EXT_hand_interaction");
        if (jointTrackingExtension_) enabled.push_back(XR_EXT_HAND_TRACKING_EXTENSION_NAME);
        if (jointAimExtension_) enabled.push_back(XR_FB_HAND_TRACKING_AIM_EXTENSION_NAME);
        if (handDataSourceExtension_) enabled.push_back(XR_EXT_HAND_TRACKING_DATA_SOURCE_EXTENSION_NAME);
        if (localFloorExtension_) enabled.push_back("XR_EXT_local_floor");
        if (timespecSupported_) enabled.push_back(XR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME);
        if (performanceSupported_) enabled.push_back(XR_EXT_PERFORMANCE_SETTINGS_EXTENSION_NAME);
        XrInstanceCreateInfoAndroidKHR androidInfo{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
        androidInfo.applicationVM = app_->activity->vm;
        androidInfo.applicationActivity = app_->activity->clazz;
        XrInstanceCreateInfo create{XR_TYPE_INSTANCE_CREATE_INFO};
        create.next = &androidInfo;
        std::strcpy(create.applicationInfo.applicationName, "DroidDeck XR");
        create.applicationInfo.applicationVersion = 1;
        create.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 0);
        create.enabledExtensionCount = enabled.size();
        create.enabledExtensionNames = enabled.data();
        check(xrCreateInstance(&create, &instance_), "xrCreateInstance");
        XrInstanceProperties runtimeProperties{XR_TYPE_INSTANCE_PROPERTIES};
        check(xrGetInstanceProperties(instance_, &runtimeProperties), "runtime properties");
        runtimeName_ = runtimeProperties.runtimeName;
        runtimeVersion_ = std::to_string(XR_VERSION_MAJOR(runtimeProperties.runtimeVersion)) + "." +
            std::to_string(XR_VERSION_MINOR(runtimeProperties.runtimeVersion)) + "." + std::to_string(XR_VERSION_PATCH(runtimeProperties.runtimeVersion));
        if (performanceSupported_) {
            XrResult result = xrGetInstanceProcAddr(instance_, "xrPerfSettingsSetPerformanceLevelEXT",
                reinterpret_cast<PFN_xrVoidFunction *>(&setPerformance_));
            if (XR_FAILED(result)) setPerformance_ = nullptr;
        }
        if (timespecSupported_) check(xrGetInstanceProcAddr(instance_, "xrConvertTimeToTimespecTimeKHR",
            reinterpret_cast<PFN_xrVoidFunction *>(&convertTime_)), "time conversion entrypoint");
        XrSystemGetInfo systemInfo{XR_TYPE_SYSTEM_GET_INFO};
        systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
        check(xrGetSystem(instance_, &systemInfo, &system_), "xrGetSystem");

        PFN_xrGetOpenGLESGraphicsRequirementsKHR requirementsFunction = nullptr;
        check(xrGetInstanceProcAddr(instance_, "xrGetOpenGLESGraphicsRequirementsKHR",
            reinterpret_cast<PFN_xrVoidFunction *>(&requirementsFunction)), "GLES requirements entrypoint");
        XrGraphicsRequirementsOpenGLESKHR requirements{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
        check(requirementsFunction(instance_, system_, &requirements), "GLES requirements");
        if (requirements.minApiVersionSupported > XR_MAKE_VERSION(3, 2, 0)
            || requirements.maxApiVersionSupported < XR_MAKE_VERSION(3, 0, 0))
            throw std::runtime_error("Runtime has no supported GLES 3.x version");
        display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (!eglInitialize(display_, nullptr, nullptr)) throw std::runtime_error("eglInitialize failed");
        const EGLint attributes[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT | EGL_WINDOW_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_DEPTH_SIZE, 24, EGL_NONE};
        EGLConfig config{};
        EGLint found = 0;
        if (!eglChooseConfig(display_, attributes, &config, 1, &found) || found != 1)
            throw std::runtime_error("EGL config unavailable");
        const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        context_ = eglCreateContext(display_, config, EGL_NO_CONTEXT, contextAttributes);
        const EGLint surfaceAttributes[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
        surface_ = eglCreatePbufferSurface(display_, config, surfaceAttributes);
        if (!eglMakeCurrent(display_, surface_, surface_, context_)) throw std::runtime_error("eglMakeCurrent failed");
        GLint glMajor = 0, glMinor = 0;
        glGetIntegerv(GL_MAJOR_VERSION, &glMajor); glGetIntegerv(GL_MINOR_VERSION, &glMinor);
        XrVersion actualGl = XR_MAKE_VERSION(glMajor, glMinor, 0);
        if (actualGl < requirements.minApiVersionSupported || actualGl > requirements.maxApiVersionSupported)
            throw std::runtime_error("Actual GLES version outside runtime requirements");
        XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
        binding.display = display_; binding.config = config; binding.context = context_;
        XrSessionCreateInfo sessionInfo{XR_TYPE_SESSION_CREATE_INFO};
        sessionInfo.next = &binding; sessionInfo.systemId = system_;
        check(xrCreateSession(instance_, &sessionInfo, &session_), "xrCreateSession");
        XrReferenceSpaceCreateInfo reference{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
        reference.poseInReferenceSpace.orientation.w = 1;
        uint32_t spaceCount = 0;
        check(xrEnumerateReferenceSpaces(session_, 0, &spaceCount, nullptr), "reference space count");
        std::vector<XrReferenceSpaceType> spaces(spaceCount);
        check(xrEnumerateReferenceSpaces(session_, spaceCount, &spaceCount, spaces.data()), "reference spaces");
        auto supportsSpace = [&](XrReferenceSpaceType value) { return std::find(spaces.begin(), spaces.end(), value) != spaces.end(); };
        reference.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
        if (supportsSpace(XR_REFERENCE_SPACE_TYPE_STAGE)) {
            reference.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_STAGE; floorKnown_ = true;
        } else if (localFloorExtension_ && supportsSpace(XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR_EXT)) {
            reference.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR_EXT; floorKnown_ = true;
        }
        worldType_ = reference.referenceSpaceType;
        check(xrCreateReferenceSpace(session_, &reference, &world_), "world space");
        reference.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
        reference.poseInReferenceSpace.position.y = 0;
        check(xrCreateReferenceSpace(session_, &reference, &view_), "view space");
        initializeActions();
        initializeImages();
        initializeGraphics();
        handPacket_ = droiddeck_xr_input_v2_neutral(runtimeSession_.generation, runtimeSession_.originEpoch);
        initializeTracking();
        const std::string directory = std::string(app_->activity->internalDataPath) + "/.wayland-rt/";
        if (!inputAdapter_.open(directory + DROIDDECK_XR_INPUT_V2_FILE,
                                               runtimeSession_.generation, runtimeSession_.originEpoch))
            throw std::runtime_error("XR hand input channel unavailable or already owned");
        if (!skeletonFile_.openWriter(directory + DROIDDECK_XR_SKELETON_FILE))
            throw std::runtime_error("XR skeleton channel unavailable or already owned");
        if (!receiver_.Start(directory + DROIDDECK_XR_FRAME_SOCKET)) throw std::runtime_error("XR frame receiver unavailable");
        bool gpu = !verifyPixels_ && gpuFrames_.Start(directory + DROIDDECK_XR_GPU_SOCKET, display_);
        __android_log_print(ANDROID_LOG_INFO, "DroidDeckXR", "GPU channel available=%d", gpu);

        health();
    }

    void initializeHandActions() {
        struct Binding { const char *name; const char *component; XrActionType type; XrAction *action; };
        const Binding bindings[] = {
            {"hand_aim", "/input/aim/pose", XR_ACTION_TYPE_POSE_INPUT, &aim_},
            {"hand_grip", "/input/grip/pose", XR_ACTION_TYPE_POSE_INPUT, &handGrip_},
            {"hand_select", "/input/aim_activate_ext/value", XR_ACTION_TYPE_FLOAT_INPUT, &handSelect_},
            {"hand_select_ready", "/input/aim_activate_ext/ready_ext", XR_ACTION_TYPE_BOOLEAN_INPUT, &selectReady_},
            {"hand_pinch", "/input/pinch_ext/value", XR_ACTION_TYPE_FLOAT_INPUT, &handPinch_},
            {"hand_pinch_ready", "/input/pinch_ext/ready_ext", XR_ACTION_TYPE_BOOLEAN_INPUT, &pinchReady_},
            {"hand_grasp", "/input/grasp_ext/value", XR_ACTION_TYPE_FLOAT_INPUT, &handGrasp_},
            {"hand_grasp_ready", "/input/grasp_ext/ready_ext", XR_ACTION_TYPE_BOOLEAN_INPUT, &graspReady_},
        };
        std::vector<XrActionSuggestedBinding> suggestions;
        for (const auto &binding : bindings) {
            XrActionCreateInfo create{XR_TYPE_ACTION_CREATE_INFO};
            std::strcpy(create.actionName, binding.name); std::strcpy(create.localizedActionName, binding.name);
            create.actionType = binding.type; create.countSubactionPaths = 2; create.subactionPaths = hands_;
            check(xrCreateAction(actions_, &create, binding.action), "create hand action");
            for (const char *hand : {"left", "right"}) {
                const std::string component = std::string("/user/hand/") + hand + binding.component;
                suggestions.push_back({*binding.action, path(component.c_str())});
            }
        }
        XrInteractionProfileSuggestedBinding suggest{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
        suggest.interactionProfile = path("/interaction_profiles/ext/hand_interaction_ext");
        suggest.countSuggestedBindings = suggestions.size(); suggest.suggestedBindings = suggestions.data();
        XrResult bindingResult = xrSuggestInteractionProfileBindings(instance_, &suggest);
        if (bindingResult == XR_ERROR_PATH_UNSUPPORTED && jointAimExtension_) { handExtension_ = false; return; }
        check(bindingResult, "hand interaction bindings");
        for (int hand = 0; hand < 2; ++hand) {
            XrActionSpaceCreateInfo space{XR_TYPE_ACTION_SPACE_CREATE_INFO};
            space.subactionPath = hands_[hand]; space.poseInActionSpace.orientation.w = 1;
            space.action = handGrip_; check(xrCreateActionSpace(session_, &space, &handGripSpaces_[hand]), "hand grip space");
            space.action = aim_; check(xrCreateActionSpace(session_, &space, &aimSpaces_[hand]), "hand aim space");
        }
    }

    void updateProfiles() {
        if (!session_) return;
        for (int hand = 0; hand < 2; ++hand) {
            if (!hands_[hand]) continue;
            XrInteractionProfileState state{XR_TYPE_INTERACTION_PROFILE_STATE};
            check(xrGetCurrentInteractionProfile(session_, hands_[hand], &state), "current hand profile");
            profiles_[hand].clear();
            if (state.interactionProfile != XR_NULL_PATH) {
                uint32_t length = 0;
                check(xrPathToString(instance_, state.interactionProfile, 0, &length, nullptr), "profile length");
                std::vector<char> text(length);
                check(xrPathToString(instance_, state.interactionProfile, length, &length, text.data()), "profile path");
                profiles_[hand] = text.data();
            }
        }
    }

    void updateInputTelemetry() {
        for (int hand = 0; hand < 2; ++hand) {
            const bool down = inputMonitor_[hand].update(handPacket_, hand, handPacket_.sample_monotonic_ns).triggerDown;
            if (down && !pinchDown_[hand]) ++pinchPresses_[hand];
            if (!down && pinchDown_[hand]) ++pinchReleases_[hand];
            pinchDown_[hand] = down;
        }
    }

    void releaseHands() noexcept {
        handActive_[0] = handActive_[1] = false;
        handPacket_ = droiddeck_xr_input_v2_neutral(runtimeSession_.generation, runtimeSession_.originEpoch);
        handPacket_.sample_monotonic_ns = xr_monotonic_ns();
        if (handMode_) handPacket_.flags |= DROIDDECK_XR_INPUT_HAND_MODE;
        updateInputTelemetry();
        inputAdapter_.publishInput(handPacket_);
        releaseSkeleton();
    }

    void releaseSkeleton() {
        skeleton_ = {};
        skeleton_.session_generation = runtimeSession_.generation;
        skeleton_.origin_epoch = runtimeSession_.originEpoch;
        skeleton_.sample_monotonic_ns = xr_monotonic_ns();
        skeletonFile_.write(skeleton_);
    }

    void sampleHands(XrTime predicted) {
        handPacket_ = droiddeck_xr_input_v2_neutral(runtimeSession_.generation, runtimeSession_.originEpoch);
        handPacket_.sample_monotonic_ns = xr_monotonic_ns();
        skeleton_ = {};
        skeleton_.session_generation = runtimeSession_.generation;
        skeleton_.origin_epoch = runtimeSession_.originEpoch;
        skeleton_.sample_monotonic_ns = handPacket_.sample_monotonic_ns;
        handProviders_[0].clear(); handProviders_[1].clear();
        if (convertTime_) {
            timespec converted{};
            check(convertTime_(instance_, predicted, &converted), "hand predicted time conversion");
            handPacket_.predicted_monotonic_ns = int64_t(converted.tv_sec) * 1000000000LL + converted.tv_nsec;
            handPacket_.flags |= DROIDDECK_XR_INPUT_PREDICTED_TIME_CONVERTIBLE;
        }
        if (state_ == XR_SESSION_STATE_FOCUSED && !neutralFrames_) {
            handPacket_.flags |= DROIDDECK_XR_INPUT_FOCUSED | DROIDDECK_XR_INPUT_GAMEPAD_MENU;
            handPacket_.gamepad_menu_sequence = callbacks_->gamepadMenu();
            for (int hand = 0; hand < 2; ++hand) {
                if (!handExtension_) continue;
                auto &output = handPacket_.hands[hand];
                handProviders_[hand] = "ext-hand-interaction";
                output.source = DROIDDECK_XR_SOURCE_RUNTIME_ACTION;
                output.capability_bits = DROIDDECK_XR_PINCH | DROIDDECK_XR_GRASP;
                auto pose = [&](XrAction action, XrSpace space, XrSpaceLocation &location, uint32_t bit) {
                    XrActionStateGetInfo get{XR_TYPE_ACTION_STATE_GET_INFO}; get.action = action; get.subactionPath = hands_[hand];
                    XrActionStatePose value{XR_TYPE_ACTION_STATE_POSE};
                    check(xrGetActionStatePose(session_, &get, &value), "hand pose action");
                    location = {XR_TYPE_SPACE_LOCATION};
                    if (value.isActive) {
                        output.status_bits |= bit;
                        check(xrLocateSpace(space, world_, predicted, &location), "locate hand action");
                    }
                    DroidDeckXrPoseV2 packet{};
                    packet.position[0] = location.pose.position.x; packet.position[1] = location.pose.position.y; packet.position[2] = location.pose.position.z;
                    packet.orientation[0] = location.pose.orientation.x; packet.orientation[1] = location.pose.orientation.y;
                    packet.orientation[2] = location.pose.orientation.z; packet.orientation[3] = location.pose.orientation.w;
                    packet.flags = location.locationFlags;
                    return packet;
                };
                output.aim = pose(aim_, aimSpaces_[hand], aimLocations_[hand], DROIDDECK_XR_AIM_ACTIVE);
                output.grip = pose(handGrip_, handGripSpaces_[hand], handLocations_[hand], DROIDDECK_XR_GRIP_ACTIVE);
                auto scalar = [&](XrAction action, XrAction readyAction, uint32_t bit, uint32_t activeBit) {
                    XrActionStateGetInfo get{XR_TYPE_ACTION_STATE_GET_INFO}; get.action = readyAction; get.subactionPath = hands_[hand];
                    XrActionStateBoolean ready{XR_TYPE_ACTION_STATE_BOOLEAN};
                    check(xrGetActionStateBoolean(session_, &get, &ready), "hand ready action");
                    if (ready.isActive && ready.currentState) output.ready_bits |= bit;
                    get.action = action;
                    XrActionStateFloat value{XR_TYPE_ACTION_STATE_FLOAT};
                    check(xrGetActionStateFloat(session_, &get, &value), "hand scalar action");
                    if (value.isActive) { output.status_bits |= activeBit; output.capability_bits |= bit; }
                    return value.isActive && (output.ready_bits & bit) && std::isfinite(value.currentState)
                        ? std::clamp(value.currentState, 0.0f, 1.0f) : 0.0f;
                };
                output.select = scalar(handSelect_, selectReady_, DROIDDECK_XR_SELECT, DROIDDECK_XR_SELECT_ACTIVE);
                output.pinch = scalar(handPinch_, pinchReady_, DROIDDECK_XR_PINCH, DROIDDECK_XR_PINCH_ACTIVE);
                output.grasp = scalar(handGrasp_, graspReady_, DROIDDECK_XR_GRASP, DROIDDECK_XR_GRASP_ACTIVE);
            }
            sampleJointAim(predicted);
            bool acquired = false;
            for (const auto &hand : handPacket_.hands)
                acquired |= poseValid(hand.grip) && (hand.status_bits & DROIDDECK_XR_GRIP_ACTIVE);
            const bool touchProfile = profiles_[0].find("touch_controller") != std::string::npos ||
                profiles_[1].find("touch_controller") != std::string::npos;
            const bool nextMode = inputPolicy_ == "hands" || (inputPolicy_ == "auto" && (acquired || (handMode_ && !touchProfile)));
            if (nextMode != handMode_) {
                handMode_ = nextMode;
                handPacket_.hands[0] = {}; handPacket_.hands[1] = {};
                neutralFrames_ = 1;
            }
        } else if (neutralFrames_) --neutralFrames_;
        if (handMode_) handPacket_.flags |= DROIDDECK_XR_INPUT_HAND_MODE;
        if (handMode_) for (int hand = 0; hand < 2; ++hand)
            handActive_[hand] = poseTracked(handPacket_.hands[hand].grip) &&
                (handPacket_.hands[hand].status_bits & DROIDDECK_XR_GRIP_ACTIVE);
        updateInputTelemetry();
        if (!skeletonFile_.write(skeleton_)) throw std::runtime_error("Hand skeleton publication failed");
        if (!inputAdapter_.publishInput(handPacket_)) throw std::runtime_error("Hand input publication failed");
    }

    static DroidDeckXrPoseV2 inputPose(const XrPosef &pose, XrSpaceLocationFlags flags) {
        return {{pose.position.x, pose.position.y, pose.position.z},
                {pose.orientation.x, pose.orientation.y, pose.orientation.z, pose.orientation.w}, uint32_t(flags)};
    }

    void sampleJointAim(XrTime predicted) {
        if (!jointTrackingExtension_) return;
        for (int hand = 0; hand < 2; ++hand) {
            auto &output = handPacket_.hands[hand];
            if (!trackers_[hand]) continue;
            XrHandJointLocationEXT joints[XR_HAND_JOINT_COUNT_EXT]{};
            XrHandTrackingAimStateFB aim{XR_TYPE_HAND_TRACKING_AIM_STATE_FB};
            XrHandTrackingDataSourceStateEXT source{XR_TYPE_HAND_TRACKING_DATA_SOURCE_STATE_EXT};
            if (handDataSourceExtension_ && jointAimExtension_) aim.next = &source;
            XrHandJointLocationsEXT locations{XR_TYPE_HAND_JOINT_LOCATIONS_EXT};
            locations.next = jointAimExtension_ ? static_cast<void *>(&aim) : handDataSourceExtension_ ? static_cast<void *>(&source) : nullptr; locations.jointCount = XR_HAND_JOINT_COUNT_EXT; locations.jointLocations = joints;
            XrHandJointsLocateInfoEXT locate{XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT}; locate.baseSpace = world_; locate.time = predicted;
            check(locateJoints_(trackers_[hand], &locate, &locations), "locate runtime hand aim");
            const auto &palm = joints[XR_HAND_JOINT_PALM_EXT];
            if (handDataSourceExtension_ && (!source.isActive || source.dataSource != XR_HAND_TRACKING_DATA_SOURCE_UNOBSTRUCTED_EXT)) continue;
            if (!handDataSourceExtension_ && profiles_[hand].find("touch_controller") != std::string::npos) continue;
            if (locations.isActive) {
                skeleton_.active[hand] = 1;
                for (int joint = 0; joint < XR_HAND_JOINT_COUNT_EXT; ++joint)
                    skeleton_.joints[hand][joint] = inputPose(joints[joint].pose, joints[joint].locationFlags);
            }
            if (poseValid(output.grip) || !jointAimExtension_) continue;
            if (!locations.isActive || (aim.status & XR_HAND_TRACKING_AIM_SYSTEM_GESTURE_BIT_FB) ||
                !(aim.status & XR_HAND_TRACKING_AIM_VALID_BIT_FB)) continue;
            output = {};
            handProviders_[hand] = "fb-hand-aim";
            output.source = DROIDDECK_XR_SOURCE_RUNTIME_ACTION;
            output.grip = inputPose(palm.pose, palm.locationFlags);
            output.aim = inputPose(aim.aimPose, palm.locationFlags);
            output.status_bits = DROIDDECK_XR_GRIP_ACTIVE | DROIDDECK_XR_AIM_ACTIVE |
                DROIDDECK_XR_PINCH_ACTIVE;
            output.capability_bits = DROIDDECK_XR_PINCH;
            output.ready_bits = output.capability_bits;
            output.pinch = std::isfinite(aim.pinchStrengthIndex) ? std::clamp(aim.pinchStrengthIndex, 0.0f, 1.0f) : 0;
        }
    }

    void initializeActions() {
        hands_[0] = path("/user/hand/left"); hands_[1] = path("/user/hand/right");
        XrActionSetCreateInfo setInfo{XR_TYPE_ACTION_SET_CREATE_INFO};
        std::strcpy(setInfo.actionSetName, "tracked_input"); std::strcpy(setInfo.localizedActionSetName, "Tracked input");
        check(xrCreateActionSet(instance_, &setInfo, &actions_), "action set");
        auto action = [&](const char *name, XrActionType type, XrAction *handle) {
            XrActionCreateInfo info{XR_TYPE_ACTION_CREATE_INFO};
            std::strcpy(info.actionName, name); std::strcpy(info.localizedActionName, name);
            info.actionType = type; info.countSubactionPaths = 2; info.subactionPaths = hands_;
            check(xrCreateAction(actions_, &info, handle), "create action");
        };
        action("grip_pose", XR_ACTION_TYPE_POSE_INPUT, &grip_);
        action("trigger", XR_ACTION_TYPE_FLOAT_INPUT, &trigger_);
        action("squeeze", XR_ACTION_TYPE_FLOAT_INPUT, &squeeze_);
        action("stick", XR_ACTION_TYPE_VECTOR2F_INPUT, &stick_);
        action("stick_click", XR_ACTION_TYPE_BOOLEAN_INPUT, &stickClick_);
        action("primary", XR_ACTION_TYPE_BOOLEAN_INPUT, &primary_);
        action("secondary", XR_ACTION_TYPE_BOOLEAN_INPUT, &secondary_);
        action("menu", XR_ACTION_TYPE_BOOLEAN_INPUT, &menu_);
        action("trigger_touch", XR_ACTION_TYPE_BOOLEAN_INPUT, &triggerTouch_);
        action("stick_touch", XR_ACTION_TYPE_BOOLEAN_INPUT, &stickTouch_);
        action("primary_touch", XR_ACTION_TYPE_BOOLEAN_INPUT, &primaryTouch_);
        action("secondary_touch", XR_ACTION_TYPE_BOOLEAN_INPUT, &secondaryTouch_);
        action("thumbrest_touch", XR_ACTION_TYPE_BOOLEAN_INPUT, &thumbrestTouch_);
        action("vibration", XR_ACTION_TYPE_VIBRATION_OUTPUT, &vibration_);
        std::vector<XrActionSuggestedBinding> bindings;
        for (const char *hand : {"left", "right"}) {
            std::string root = std::string("/user/hand/") + hand;
            bindings.push_back({grip_, path((root + "/input/grip/pose").c_str())});
            bindings.push_back({trigger_, path((root + "/input/trigger/value").c_str())});
            bindings.push_back({triggerTouch_, path((root + "/input/trigger/touch").c_str())});
            bindings.push_back({squeeze_, path((root + "/input/squeeze/value").c_str())});
            bindings.push_back({stick_, path((root + "/input/thumbstick").c_str())});
            bindings.push_back({stickClick_, path((root + "/input/thumbstick/click").c_str())});
            bindings.push_back({stickTouch_, path((root + "/input/thumbstick/touch").c_str())});
            bindings.push_back({thumbrestTouch_, path((root + "/input/thumbrest/touch").c_str())});
            bindings.push_back({primary_, path((root + (std::strcmp(hand, "left") == 0 ? "/input/x/click" : "/input/a/click")).c_str())});
            bindings.push_back({secondary_, path((root + (std::strcmp(hand, "left") == 0 ? "/input/y/click" : "/input/b/click")).c_str())});
            bindings.push_back({primaryTouch_, path((root + (std::strcmp(hand, "left") == 0 ? "/input/x/touch" : "/input/a/touch")).c_str())});
            bindings.push_back({secondaryTouch_, path((root + (std::strcmp(hand, "left") == 0 ? "/input/y/touch" : "/input/b/touch")).c_str())});
            bindings.push_back({vibration_, path((root + "/output/haptic").c_str())});
        }
        bindings.push_back({menu_, path("/user/hand/left/input/menu/click")});
        XrInteractionProfileSuggestedBinding suggestion{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
        suggestion.interactionProfile = path("/interaction_profiles/oculus/touch_controller");
        suggestion.countSuggestedBindings = bindings.size(); suggestion.suggestedBindings = bindings.data();
        XrResult touchBindings = xrSuggestInteractionProfileBindings(instance_, &suggestion);
        if (touchBindings != XR_ERROR_PATH_UNSUPPORTED) check(touchBindings, "Touch bindings");
        if (handExtension_) initializeHandActions();
        XrSessionActionSetsAttachInfo attach{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
        attach.countActionSets = 1; attach.actionSets = &actions_;
        check(xrAttachSessionActionSets(session_, &attach), "attach actions");
        for (int hand = 0; hand < 2; hand++) {
            XrActionSpaceCreateInfo space{XR_TYPE_ACTION_SPACE_CREATE_INFO};
            space.action = grip_; space.subactionPath = hands_[hand]; space.poseInActionSpace.orientation.w = 1;
            check(xrCreateActionSpace(session_, &space, &handSpaces_[hand]), "controller space");
        }
        if (jointTrackingExtension_) {
            check(xrGetInstanceProcAddr(instance_, "xrCreateHandTrackerEXT", reinterpret_cast<PFN_xrVoidFunction *>(&createTracker_)), "create tracker entry");
            check(xrGetInstanceProcAddr(instance_, "xrLocateHandJointsEXT", reinterpret_cast<PFN_xrVoidFunction *>(&locateJoints_)), "locate joints entry");
            check(xrGetInstanceProcAddr(instance_, "xrDestroyHandTrackerEXT", reinterpret_cast<PFN_xrVoidFunction *>(&destroyTracker_)), "destroy tracker entry");
            XrSystemHandTrackingPropertiesEXT hands{XR_TYPE_SYSTEM_HAND_TRACKING_PROPERTIES_EXT};
            XrSystemProperties properties{XR_TYPE_SYSTEM_PROPERTIES}; properties.next = &hands;
            check(xrGetSystemProperties(instance_, system_, &properties), "hand tracking properties");
            if (hands.supportsHandTracking) for (int hand = 0; hand < 2; ++hand) {
                XrHandTrackingDataSourceEXT requested = XR_HAND_TRACKING_DATA_SOURCE_UNOBSTRUCTED_EXT;
                XrHandTrackingDataSourceInfoEXT source{XR_TYPE_HAND_TRACKING_DATA_SOURCE_INFO_EXT};
                source.requestedDataSourceCount = 1; source.requestedDataSources = &requested;
                XrHandTrackerCreateInfoEXT create{XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT};
                if (handDataSourceExtension_) create.next = &source;
                create.hand = hand ? XR_HAND_RIGHT_EXT : XR_HAND_LEFT_EXT; create.handJointSet = XR_HAND_JOINT_SET_DEFAULT_EXT;
                XrResult result = createTracker_(session_, &create, &trackers_[hand]);
                if (XR_FAILED(result)) __android_log_print(ANDROID_LOG_WARN, "DroidDeckXR", "hand tracker unavailable: %d", result);
            }
            jointTrackingExtension_ = trackers_[0] || trackers_[1];
            jointAimExtension_ = jointAimExtension_ && jointTrackingExtension_;
        }
        if (inputPolicy_ == "hands" && !handExtension_ && !jointAimExtension_)
            throw std::runtime_error("Hand trackers unavailable; check hand tracking permission");
    }

    void initializeImages() {
        uint32_t count = 0;
        check(xrEnumerateViewConfigurationViews(instance_, system_, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
            0, &count, nullptr), "view count");
        if (count != 2) { throw std::runtime_error("Expected two stereo views"); }
        uint32_t blendCount = 0;
        check(xrEnumerateEnvironmentBlendModes(instance_, system_, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
              0, &blendCount, nullptr), "blend mode count");
        std::vector<XrEnvironmentBlendMode> modes(blendCount);
        check(xrEnumerateEnvironmentBlendModes(instance_, system_, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
              blendCount, &blendCount, modes.data()), "blend modes");
        if (modes.empty()) throw std::runtime_error("No environment blend modes");
        blendMode_ = std::find(modes.begin(), modes.end(), XR_ENVIRONMENT_BLEND_MODE_OPAQUE) != modes.end()
            ? XR_ENVIRONMENT_BLEND_MODE_OPAQUE : modes.front();
        std::vector<XrViewConfigurationView> views(count, {XR_TYPE_VIEW_CONFIGURATION_VIEW});
        check(xrEnumerateViewConfigurationViews(instance_, system_, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
            count, &count, views.data()), "view configuration");
        check(xrEnumerateSwapchainFormats(session_, 0, &count, nullptr), "format count");
        std::vector<int64_t> formats(count);
        check(xrEnumerateSwapchainFormats(session_, count, &count, formats.data()), "formats");
        int64_t format = 0;
        for (int64_t candidate : formats) if (candidate == GL_SRGB8_ALPHA8) format = candidate;
        if (!format) throw std::runtime_error("sRGB swapchain unavailable");
        for (int eye = 0; eye < 2; eye++) {
            Eye &target = eyes_[eye];
            target.width = std::max(2, int(views[eye].recommendedImageRectWidth) & ~1);
            target.height = std::max(2, int(views[eye].recommendedImageRectHeight) & ~1);
            XrSwapchainCreateInfo create{XR_TYPE_SWAPCHAIN_CREATE_INFO};
            create.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
            create.format = format; create.sampleCount = 1;
            create.width = target.width; create.height = target.height;
            create.faceCount = 1; create.arraySize = 1; create.mipCount = 1;
            check(xrCreateSwapchain(session_, &create, &target.swapchain), "swapchain");
            check(xrEnumerateSwapchainImages(target.swapchain, 0, &count, nullptr), "image count");
            target.images.resize(count, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
            check(xrEnumerateSwapchainImages(target.swapchain, count, &count,
                reinterpret_cast<XrSwapchainImageBaseHeader *>(target.images.data())), "images");
        }
    }

    void initializeTracking() {
        std::string directory = std::string(app_->activity->internalDataPath) + "/.wayland-rt";
        if (mkdir(directory.c_str(), 0700) != 0 && errno != EEXIST)
            throw std::runtime_error("XR tracking directory unavailable");
        auto mapFile = [&](const char *name, size_t size) {
            std::string filename = directory + "/" + name;
            int descriptor = open(filename.c_str(), O_CREAT | O_RDWR | O_CLOEXEC, 0600);
            if (descriptor < 0) throw std::runtime_error("XR IPC file unavailable");
            if (ftruncate(descriptor, size) != 0) {
                close(descriptor); throw std::runtime_error("XR IPC file size failed");
            }
            void *mapping = mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, descriptor, 0);
            close(descriptor);
            if (mapping == MAP_FAILED) throw std::runtime_error("XR IPC map failed");
            return mapping;
        };
        tracking_ = static_cast<DroidDeckXrTracking *>(mapFile(DROIDDECK_XR_FILE, sizeof(DroidDeckXrTracking)));
        haptics_ = static_cast<DroidDeckXrHaptics *>(mapFile(DROIDDECK_XR_HAPTIC_FILE, sizeof(DroidDeckXrHaptics)));
        haptics_->magic = DROIDDECK_XR_HAPTIC_MAGIC; haptics_->version = DROIDDECK_XR_VERSION;
        for (int hand = 0; hand < 2; hand++) lastPulse_[hand] = haptics_->hands[hand].id;
    }

    static DroidDeckXrPose posePacket(const XrPosef &pose, XrSpaceLocationFlags flags) {
        return {{pose.orientation.x, pose.orientation.y, pose.orientation.z, pose.orientation.w},
                {pose.position.x, pose.position.y, pose.position.z}, (uint32_t)flags};
    }

    void publishTracking(const XrView views[2], XrTime predicted) {
        if (!tracking_) return;
        timespec now{}; clock_gettime(CLOCK_MONOTONIC, &now);
        DroidDeckXrTracking packet{};
        packet.magic = DROIDDECK_XR_MAGIC; packet.version = DROIDDECK_XR_VERSION;
        packet.active = (state_ == XR_SESSION_STATE_VISIBLE || state_ == XR_SESSION_STATE_FOCUSED)
            ? DROIDDECK_ANDROID_XR_ACTIVE : 0;
        packet.sampled_ns = (int64_t)now.tv_sec * 1000000000LL + now.tv_nsec;
        if (convertTime_) {
            timespec displayTime{};
            check(convertTime_(instance_, predicted, &displayTime), "display time conversion");
            packet.predicted_ns = (int64_t)displayTime.tv_sec * 1000000000LL + displayTime.tv_nsec;
        }
        packet.period_ns = period_;
        packet.head = posePacket(head_.pose, head_.locationFlags);
        for (int eye = 0; eye < 2; eye++) {
            packet.eyes[eye].pose = posePacket(views[eye].pose, viewFlags_);
            packet.eyes[eye].fov[0] = views[eye].fov.angleLeft;
            packet.eyes[eye].fov[1] = views[eye].fov.angleRight;
            packet.eyes[eye].fov[2] = views[eye].fov.angleUp;
            packet.eyes[eye].fov[3] = views[eye].fov.angleDown;
            packet.eyes[eye].width = eyes_[eye].width; packet.eyes[eye].height = eyes_[eye].height;
            packet.hands[eye] = inputs_[eye];
            packet.hands[eye].pose = posePacket(handLocations_[eye].pose, handLocations_[eye].locationFlags);
            packet.hands[eye].active = handMode_ ? false : handActive_[eye];
        }
        uint32_t sequence = __atomic_load_n(&tracking_->seq, __ATOMIC_RELAXED);
        __atomic_store_n(&tracking_->seq, sequence | 1u, __ATOMIC_SEQ_CST);
        __atomic_thread_fence(__ATOMIC_SEQ_CST);
        std::memcpy(tracking_, &packet, offsetof(DroidDeckXrTracking, seq));
        std::memcpy(reinterpret_cast<char *>(tracking_) + offsetof(DroidDeckXrTracking, active),
            reinterpret_cast<const char *>(&packet) + offsetof(DroidDeckXrTracking, active),
            sizeof(packet) - offsetof(DroidDeckXrTracking, active));
        __atomic_store_n(&tracking_->seq, (sequence | 1u) + 1u, __ATOMIC_RELEASE);
    }

    void attachGuest(bool attach) {
        if (attach == guestAttached_) return;
        if (callbacks_) callbacks_->onActiveChanged(attach);
        guestAttached_ = attach;
    }

    void pollHaptics() {
        if (!haptics_) return;
        DroidDeckXrHaptics packet{};
        uint32_t sequence = __atomic_load_n(&haptics_->seq, __ATOMIC_ACQUIRE);
        if (sequence & 1u) return;
        std::memcpy(&packet, haptics_, sizeof(packet));
        __atomic_thread_fence(__ATOMIC_ACQUIRE);
        if (__atomic_load_n(&haptics_->seq, __ATOMIC_RELAXED) != sequence
            || packet.magic != DROIDDECK_XR_HAPTIC_MAGIC || packet.version != DROIDDECK_XR_VERSION) return;
        for (int hand = 0; hand < 2; hand++) {
            const auto &pulse = packet.hands[hand];
            if (pulse.id == lastPulse_[hand]) continue;
            lastPulse_[hand] = pulse.id;
            if (state_ != XR_SESSION_STATE_FOCUSED || !handActive_[hand]
                || pulse.duration_ns < 1000000 || pulse.duration_ns > 1000000000
                || !std::isfinite(pulse.amplitude) || pulse.amplitude < 0 || pulse.amplitude > 1
                || !std::isfinite(pulse.frequency) || pulse.frequency < 0) continue;
            if (handMode_) continue;
            XrHapticActionInfo info{XR_TYPE_HAPTIC_ACTION_INFO}; info.action = vibration_; info.subactionPath = hands_[hand];
            XrHapticVibration vibration{XR_TYPE_HAPTIC_VIBRATION};
            vibration.duration = pulse.duration_ns; vibration.amplitude = pulse.amplitude; vibration.frequency = pulse.frequency;
            XrResult result = xrApplyHapticFeedback(session_, &info, reinterpret_cast<XrHapticBaseHeader *>(&vibration));
            if (XR_FAILED(result)) __android_log_print(ANDROID_LOG_WARN, "DroidDeckXR", "Haptic unsupported: %d", result);
        }
    }

    GLuint shader(GLenum type, const char *text) {
        GLuint handle = glCreateShader(type);
        glShaderSource(handle, 1, &text, nullptr); glCompileShader(handle);
        GLint success = 0; glGetShaderiv(handle, GL_COMPILE_STATUS, &success);
        if (!success) { glDeleteShader(handle); throw std::runtime_error("Shader compile failed"); }
        return handle;
    }

    void initializeGraphics() {
        glGenFramebuffers(1, &framebuffer_);
        glGenVertexArrays(1, &vertexArray_);
        GLuint vertex = shader(GL_VERTEX_SHADER,
            "#version 300 es\nout vec2 uv; uniform float eye; void main(){vec2 coordinate=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));uv=vec2(coordinate.x*0.5+eye*0.5,1.0-coordinate.y);gl_Position=vec4(coordinate*2.0-1.0,0.0,1.0);}");
        GLuint fragment = shader(GL_FRAGMENT_SHADER,
            "#version 300 es\nprecision highp float; in vec2 uv; uniform sampler2D source; uniform float eye; out vec4 outputColor; void main(){float edge=0.5/float(textureSize(source,0).x);vec2 coordinate=vec2(clamp(uv.x,eye*0.5+edge,(eye+1.0)*0.5-edge),uv.y);outputColor=texture(source,coordinate);}");
        sourceProgram_ = glCreateProgram(); glAttachShader(sourceProgram_, vertex); glAttachShader(sourceProgram_, fragment);
        glLinkProgram(sourceProgram_); glDeleteShader(vertex); glDeleteShader(fragment);
        GLint linked = 0; glGetProgramiv(sourceProgram_, GL_LINK_STATUS, &linked);
        if (!linked) throw std::runtime_error("Frame shader link failed");
        glGenTextures(1, &sourceTexture_); glBindTexture(GL_TEXTURE_2D, sourceTexture_);
        cpuTexture_ = sourceTexture_;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    void receiveFrame() {
        if (gpuFrames_.Take(sourceHeader_, sourceTexture_)) {
            uploadedFrames_++;
            if (uploadedFrames_ % 72 == 1) health();
            return;
        }
        DroidDeckXrPixels frame;
        if (!receiver_.Take(&frame)) return;
        GLint maximum = 0; glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maximum);
        if (frame.header.width > static_cast<uint32_t>(maximum) || frame.header.height > static_cast<uint32_t>(maximum))
            throw std::runtime_error("XR source exceeds GPU texture limit");
        sourceTexture_ = cpuTexture_;
        glBindTexture(GL_TEXTURE_2D, sourceTexture_);
        GLenum encoding = frame.header.color_space == DROIDDECK_XR_COLOR_SRGB_RGBA8 ? GL_SRGB8_ALPHA8 : GL_RGBA8;
        glTexImage2D(GL_TEXTURE_2D, 0, encoding, frame.header.width, frame.header.height, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, frame.pixels.data());
        if (verifyPixels_) {
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, 0);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, sourceTexture_, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw std::runtime_error("Frame readback framebuffer incomplete");
            std::vector<unsigned char> readback(frame.header.bytes);
            glReadPixels(0, 0, frame.header.width, frame.header.height, GL_RGBA, GL_UNSIGNED_BYTE, readback.data());
            if (readback != frame.pixels) throw std::runtime_error("Frame GPU readback does not match owned pixels");
            gpuChecksum_ = 2166136261u;
            for (unsigned char value : readback) gpuChecksum_ = (gpuChecksum_ ^ value) * 16777619u;
            verifiedFrame_ = frame.header.frame_id;
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        }
        if (glGetError() != GL_NO_ERROR) throw std::runtime_error("Frame texture upload failed");
        sourceHeader_ = frame.header;
        receiver_.Recycle(std::move(frame.pixels));
        uploadedFrames_++;
        if (verifyPixels_ || uploadedFrames_ % 72 == 1) health();
    }

    void render(Eye &eye, uint32_t image) {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, eye.images[image].image, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
            throw std::runtime_error("Incomplete eye framebuffer");
        glViewport(0, 0, eye.width, eye.height); glDisable(GL_DEPTH_TEST);
        glUseProgram(sourceProgram_); glBindVertexArray(vertexArray_);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, sourceTexture_);
        glUniform1i(glGetUniformLocation(sourceProgram_, "source"), 0);
        glUniform1f(glGetUniformLocation(sourceProgram_, "eye"), &eye == &eyes_[1] ? 1.f : 0.f);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        if (glGetError() != GL_NO_ERROR) throw std::runtime_error("OpenGL render error");
        glFlush();
    }

    void pollEvents() {
        XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
        XrResult result;
        while ((result = xrPollEvent(instance_, &event)) == XR_SUCCESS) {
            if (event.type == XR_TYPE_EVENT_DATA_INTERACTION_PROFILE_CHANGED) { neutralFrames_ = 2; updateProfiles(); }
            else if (event.type == XR_TYPE_EVENT_DATA_REFERENCE_SPACE_CHANGE_PENDING) {
                const auto &change = *reinterpret_cast<XrEventDataReferenceSpaceChangePending *>(&event);
                __android_log_print(ANDROID_LOG_INFO, "DroidDeckXR", "reference space change type=%d world=%d", change.referenceSpaceType, worldType_);
                if (change.referenceSpaceType == worldType_) spaceChangeTimes_.push_back(change.changeTime);
            }
            else if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                state_ = reinterpret_cast<XrEventDataSessionStateChanged *>(&event)->state;
                __android_log_print(ANDROID_LOG_INFO, "DroidDeckXR", "session state=%d", state_);
                callbacks_->onInputFocus(state_ == XR_SESSION_STATE_FOCUSED);
                if (state_ != XR_SESSION_STATE_FOCUSED) releaseHands();
                attachGuest(state_ == XR_SESSION_STATE_VISIBLE || state_ == XR_SESSION_STATE_FOCUSED);
                if (state_ == XR_SESSION_STATE_READY) {
                    XrSessionBeginInfo begin{XR_TYPE_SESSION_BEGIN_INFO};
                    begin.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                    check(xrBeginSession(session_, &begin), "xrBeginSession"); running_ = true;
                    if (setPerformance_) {
                        for (auto domain : {XR_PERF_SETTINGS_DOMAIN_CPU_EXT, XR_PERF_SETTINGS_DOMAIN_GPU_EXT}) {
                            XrResult request = setPerformance_(session_, domain, XR_PERF_SETTINGS_LEVEL_SUSTAINED_HIGH_EXT);
                            __android_log_print(ANDROID_LOG_INFO, "DroidDeckXR", "sustained performance domain=%d result=%d", domain, request);
                        }
                    }
                } else if (state_ == XR_SESSION_STATE_STOPPING) {
                    check(xrEndSession(session_), "xrEndSession"); running_ = false;
                    if (quitRequested_) exit_ = true;
                } else if (state_ == XR_SESSION_STATE_EXITING || state_ == XR_SESSION_STATE_LOSS_PENDING) exit_ = true;
                health();
            } else if (event.type == XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING) exit_ = true;
            else if (event.type == XR_TYPE_EVENT_DATA_PERF_SETTINGS_EXT) {
                const auto &performance = *reinterpret_cast<XrEventDataPerfSettingsEXT *>(&event);
                __android_log_print(ANDROID_LOG_INFO, "DroidDeckXR", "performance domain=%d subdomain=%d level=%d->%d",
                                    performance.domain, performance.subDomain, performance.fromLevel, performance.toLevel);
            }
            event = {XR_TYPE_EVENT_DATA_BUFFER};
        }
        check(result, "xrPollEvent");
    }

    void frame() {
        XrFrameWaitInfo wait{XR_TYPE_FRAME_WAIT_INFO};
        XrFrameState frameState{XR_TYPE_FRAME_STATE};
        check(xrWaitFrame(session_, &wait, &frameState), "xrWaitFrame");
        period_ = frameState.predictedDisplayPeriod;
        XrFrameBeginInfo begin{XR_TYPE_FRAME_BEGIN_INFO}; check(xrBeginFrame(session_, &begin), "xrBeginFrame");
        ScopeExit frameGuard([&] {
            XrFrameEndInfo empty{XR_TYPE_FRAME_END_INFO}; empty.displayTime = frameState.predictedDisplayTime;
            empty.environmentBlendMode = blendMode_;
            xrEndFrame(session_, &empty);
        });
        if (std::any_of(spaceChangeTimes_.begin(), spaceChangeTimes_.end(),
                        [&](XrTime change) { return change <= frameState.predictedDisplayTime; })) {
            releaseHands();
            ++runtimeSession_.originEpoch;
            if (!inputAdapter_.resetOrigin(runtimeSession_.originEpoch))
                throw std::runtime_error("XR input origin reset failed");
            releaseHands();
            if (uploadedFrames_ != 0)
                throw std::runtime_error("XR reference space changed; restart SteamVR to refresh frame poses");
            // Initial localization can change the origin before any guest frame is received.
            spaceChangeTimes_.erase(std::remove_if(spaceChangeTimes_.begin(), spaceChangeTimes_.end(),
                [&](XrTime change) { return change <= frameState.predictedDisplayTime; }), spaceChangeTimes_.end());
        }
        check(xrLocateSpace(view_, world_, frameState.predictedDisplayTime, &head_), "head pose");
        handActive_[0] = handActive_[1] = false;
        if (state_ == XR_SESSION_STATE_FOCUSED) {
            XrActiveActionSet active{actions_, XR_NULL_PATH};
            XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO}; sync.countActiveActionSets = 1; sync.activeActionSets = &active;
            check(xrSyncActions(session_, &sync), "xrSyncActions");
            if (frames_ % 30 == 0) updateProfiles();
        }
        sampleHands(frameState.predictedDisplayTime);
        if (!handMode_ && state_ == XR_SESSION_STATE_FOCUSED && !neutralFrames_) {
            for (int hand = 0; hand < 2; hand++) {
                XrActionStateGetInfo get{XR_TYPE_ACTION_STATE_GET_INFO}; get.action = grip_; get.subactionPath = hands_[hand];
                XrActionStatePose pose{XR_TYPE_ACTION_STATE_POSE}; check(xrGetActionStatePose(session_, &get, &pose), "grip state");
                handActive_[hand] = pose.isActive;
                check(xrLocateSpace(handSpaces_[hand], world_, frameState.predictedDisplayTime, &handLocations_[hand]), "hand pose");
                get.action = trigger_;
                XrActionStateFloat trigger{XR_TYPE_ACTION_STATE_FLOAT}; check(xrGetActionStateFloat(session_, &get, &trigger), "trigger state");
                inputs_[hand].trigger = trigger.isActive ? trigger.currentState : 0;
                get.action = squeeze_;
                XrActionStateFloat squeeze{XR_TYPE_ACTION_STATE_FLOAT}; check(xrGetActionStateFloat(session_, &get, &squeeze), "squeeze state");
                inputs_[hand].squeeze = squeeze.isActive ? squeeze.currentState : 0;
                get.action = stick_;
                XrActionStateVector2f stick{XR_TYPE_ACTION_STATE_VECTOR2F}; check(xrGetActionStateVector2f(session_, &get, &stick), "stick state");
                inputs_[hand].stick[0] = stick.isActive ? stick.currentState.x : 0;
                inputs_[hand].stick[1] = stick.isActive ? stick.currentState.y : 0;
                inputs_[hand].buttons = 0;
                const std::pair<XrAction, uint32_t> buttons[] = {
                    {stickClick_, DROIDDECK_XR_STICK_CLICK}, {primary_, DROIDDECK_XR_PRIMARY_CLICK},
                    {secondary_, DROIDDECK_XR_SECONDARY_CLICK}, {menu_, DROIDDECK_XR_MENU_CLICK},
                    {triggerTouch_, DROIDDECK_XR_TRIGGER_TOUCH}, {stickTouch_, DROIDDECK_XR_STICK_TOUCH},
                    {primaryTouch_, DROIDDECK_XR_PRIMARY_TOUCH}, {secondaryTouch_, DROIDDECK_XR_SECONDARY_TOUCH},
                    {thumbrestTouch_, DROIDDECK_XR_THUMBREST_TOUCH}};
                for (const auto &button : buttons) {
                    get.action = button.first;
                    XrActionStateBoolean value{XR_TYPE_ACTION_STATE_BOOLEAN}; check(xrGetActionStateBoolean(session_, &get, &value), "button state");
                    if (value.isActive && value.currentState) inputs_[hand].buttons |= button.second;
                }
            }
        }
        pollHaptics();
        XrViewLocateInfo locate{XR_TYPE_VIEW_LOCATE_INFO}; locate.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
        locate.displayTime = frameState.predictedDisplayTime; locate.space = world_;
        XrViewState viewState{XR_TYPE_VIEW_STATE}; uint32_t count = 0;
        XrView views[2]{{XR_TYPE_VIEW}, {XR_TYPE_VIEW}};
        check(xrLocateViews(session_, &locate, &viewState, 2, &count, views), "xrLocateViews");
        viewFlags_ = viewState.viewStateFlags;
        eyeSeparation_ = 0;
        if (count == 2) {
            const auto &a = views[0].pose.position; const auto &b = views[1].pose.position;
            eyeSeparation_ = std::sqrt((a.x-b.x)*(a.x-b.x) + (a.y-b.y)*(a.y-b.y) + (a.z-b.z)*(a.z-b.z));
        }
        publishTracking(views, frameState.predictedDisplayTime);
        if (guestAttached_ && callbacks_) callbacks_->onFrame(xr_monotonic_ns());
        XrCompositionLayerProjectionView projection[2]{{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}, {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}};
        bool valid = frameState.shouldRender && count == 2 &&
            (viewState.viewStateFlags & (XR_VIEW_STATE_ORIENTATION_VALID_BIT | XR_VIEW_STATE_POSITION_VALID_BIT))
            == (XR_VIEW_STATE_ORIENTATION_VALID_BIT | XR_VIEW_STATE_POSITION_VALID_BIT);
        const auto transferBegin = xr_monotonic_ns();
        receiveFrame();
        transferMs_ = (xr_monotonic_ns() - transferBegin) / 1e6;
        sourceAgeMs_ = sourceHeader_.frame_id ? (xr_frame_now() - sourceHeader_.captured_ns) / 1e6 : 0;
        valid = valid && sourceHeader_.frame_id && xr_frame_now() - sourceHeader_.captured_ns < 500000000LL;
        if (valid) for (int eye = 0; eye < 2; eye++) {
            auto source = xr_frame_view(sourceHeader_, eye);
            views[eye].pose.orientation = {source.orientation[0], source.orientation[1], source.orientation[2], source.orientation[3]};
            views[eye].pose.position = {source.position[0], source.position[1], source.position[2]};
            views[eye].fov = {source.fov[0], source.fov[1], source.fov[2], source.fov[3]};
        }
        const std::string capture = captureRequest();
        bool captured = valid;
        if (!capture.empty() && valid)
            captured = captureTexture(sourceTexture_, sourceHeader_.width, sourceHeader_.height, "source");
        if (valid) for (int eye = 0; eye < 2; eye++) {
            uint32_t image = 0;
            XrSwapchainImageAcquireInfo acquire{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
            check(xrAcquireSwapchainImage(eyes_[eye].swapchain, &acquire, &image), "acquire image");
            bool waited = false;
            ScopeExit imageGuard([&] {
                if (waited) {
                    XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
                    xrReleaseSwapchainImage(eyes_[eye].swapchain, &release);
                }
            });
            XrSwapchainImageWaitInfo imageWait{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO}; imageWait.timeout = 500000000LL;
            XrResult ready = xrWaitSwapchainImage(eyes_[eye].swapchain, &imageWait);
            if (ready != XR_SUCCESS) throw std::runtime_error("Swapchain wait timed out or session was lost: " + std::to_string(ready));
            waited = true;
            render(eyes_[eye], image);
            if (!capture.empty()) captured = captureTexture(eyes_[eye].images[image].image, eyes_[eye].width, eyes_[eye].height, eye ? "right" : "left") && captured;
            XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
            XrResult released = xrReleaseSwapchainImage(eyes_[eye].swapchain, &release);
            imageGuard.dismiss();
            check(released, "release image");
            projection[eye].pose = views[eye].pose; projection[eye].fov = views[eye].fov;
            projection[eye].subImage.swapchain = eyes_[eye].swapchain;
            projection[eye].subImage.imageRect.extent = {eyes_[eye].width, eyes_[eye].height};
        }
        XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
        layer.space = world_; layer.viewCount = 2; layer.views = projection;
        const XrCompositionLayerBaseHeader *layers[] = {reinterpret_cast<XrCompositionLayerBaseHeader *>(&layer)};
        XrFrameEndInfo end{XR_TYPE_FRAME_END_INFO}; end.displayTime = frameState.predictedDisplayTime;
        end.environmentBlendMode = blendMode_; end.layerCount = valid ? 1 : 0; end.layers = layers;
        XrResult ended = xrEndFrame(session_, &end);
        frameGuard.dismiss();
        check(ended, "xrEndFrame");
        if (!capture.empty()) captureResult(capture, captured, valid, projection, frameState.predictedDisplayTime);
        frames_++; if (valid) rendered_++;
        if (frames_ % 30 == 0) {
            health();
            __android_log_print(ANDROID_LOG_INFO, "DroidDeckXR", "frames=%llu rendered=%llu head=%llu hands=%d,%d period=%lld",
                (unsigned long long)frames_, (unsigned long long)rendered_, (unsigned long long)head_.locationFlags,
                handActive_[0], handActive_[1], (long long)period_);
        }
    }

    void cleanup() {
        releaseHands();
        receiver_.Stop();
        gpuFrames_.Stop();
        try { if (callbacks_) callbacks_->onInputFocus(false); attachGuest(false); } catch (const std::exception &error) {
            __android_log_print(ANDROID_LOG_ERROR, "DroidDeckXR", "cleanup: %s", error.what());
        }
        if (tracking_) {
            __atomic_store_n(&tracking_->active, 0u, __ATOMIC_RELEASE);
            munmap(tracking_, sizeof(DroidDeckXrTracking));
        }
        if (haptics_) munmap(haptics_, sizeof(DroidDeckXrHaptics));
        if (display_ != EGL_NO_DISPLAY && context_ != EGL_NO_CONTEXT) {
            glDeleteFramebuffers(1, &framebuffer_); glDeleteVertexArrays(1, &vertexArray_);
            glDeleteTextures(1, &cpuTexture_); glDeleteProgram(sourceProgram_);
        }
        for (auto &eye : eyes_) if (eye.swapchain) xrDestroySwapchain(eye.swapchain);
        for (auto space : handSpaces_) if (space) xrDestroySpace(space);
        for (auto space : aimSpaces_) if (space) xrDestroySpace(space);
        for (auto space : handGripSpaces_) if (space) xrDestroySpace(space);
        for (auto tracker : trackers_) if (tracker && destroyTracker_) destroyTracker_(tracker);
        if (view_) xrDestroySpace(view_); if (world_) xrDestroySpace(world_);
        if (session_) xrDestroySession(session_);
        if (actions_) xrDestroyActionSet(actions_);
        if (instance_) xrDestroyInstance(instance_);
        if (display_ != EGL_NO_DISPLAY) {
            eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (surface_ != EGL_NO_SURFACE) eglDestroySurface(display_, surface_);
            if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
            eglTerminate(display_);
        }
    }
};

namespace {
void finishAndroidActivity(android_app *app) noexcept {
    app->onInputEvent = nullptr;
    app->userData = nullptr;
    if (!app->destroyRequested) ANativeActivity_finish(app->activity);
    // The UI thread waits for glue lifecycle acknowledgements, even after initialization fails.
    while (!app->destroyRequested) {
        android_poll_source *source = nullptr;
        int events = 0;
        if (ALooper_pollOnce(100, nullptr, &events, reinterpret_cast<void **>(&source)) >= 0 && source)
            source->process(app, source);
    }
}
}

void droiddeck::xr::runAndroidXr(android_app *app) noexcept {
    JNIEnv *env = nullptr;
    if (app->activity->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        finishAndroidActivity(app); return;
    }
    try {
        AndroidCallbacks callbacks(app, env);
        OpenXrRuntime runtime(app, env);
        RuntimeSession session{uint64_t(xr_monotonic_ns()), 1};
        const auto result = runtime.run(session, callbacks);
        if (!result && !app->destroyRequested) ANativeActivity_finish(app->activity);
    } catch (const std::exception &error) {
        __android_log_print(ANDROID_LOG_ERROR, "DroidDeckXR", "%s", error.what());
        ANativeActivity_finish(app->activity);
    }
    finishAndroidActivity(app);
    app->activity->vm->DetachCurrentThread();
}
