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
#include "gpu_frames.h"

static void check(XrResult result, const char *operation) {
    if (XR_FAILED(result)) throw std::runtime_error(std::string(operation) + ": " + std::to_string(result));
}

struct Eye {
    XrSwapchain swapchain = XR_NULL_HANDLE;
    int width = 0, height = 0;
    std::vector<XrSwapchainImageOpenGLESKHR> images;
};

class QuestXR {
public:
    explicit QuestXR(android_app *app) : app_(app) {}
    ~QuestXR() { cleanup(); }

    void run() {
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
            receiveFrame();
            pollEvents();
            if (running_ && !app_->destroyRequested) frame();
        }
        if (exit_ && !app_->destroyRequested) ANativeActivity_finish(app_->activity);
    }

    void health(const char *error = "") {
        int steamvrState = -1;
        if (statusCallback_) {
            steamvrState = env_->CallIntMethod(app_->activity->clazz, statusCallback_);
            if (env_->ExceptionCheck()) { env_->ExceptionClear(); steamvrState = -1; }
        }
        std::string path = std::string(app_->activity->externalDataPath) + "/xr-health.json";
        std::string temp = path + ".tmp";
        FILE *file = std::fopen(temp.c_str(), "w");
        if (!file) return;
        std::fprintf(file,
            "{\"frames\":%llu,\"rendered\":%llu,\"sessionState\":%d,\"headFlags\":%llu,"
            "\"headPosition\":[%.6f,%.6f,%.6f],\"controllersActive\":[%s,%s],"
            "\"periodNs\":%lld,\"surfaceSwapchain\":%s,\"source\":\"%s\",\"guestFrames\":%lld,\"steamvrState\":%d,"
            "\"receivedFrames\":%llu,\"rejectedFrames\":%llu,\"uploadedFrames\":%llu,\"sourceFrameId\":%llu,"
            "\"gpuReceivedFrames\":%llu,\"gpuDroppedFrames\":%llu,\"gpuVerifiedFrameId\":%llu,\"gpuChecksum\":%u,\"error\":\"%s\"}\n",
            (unsigned long long)frames_, (unsigned long long)rendered_, state_,
            (unsigned long long)head_.locationFlags,
            head_.pose.position.x, head_.pose.position.y, head_.pose.position.z,
            handActive_[0] ? "true" : "false", handActive_[1] ? "true" : "false",
            (long long)period_, "false", "frames",
            0LL, steamvrState, (unsigned long long)(receiver_.Received() + gpuFrames_.Received()),
            (unsigned long long)(receiver_.Rejected() + gpuFrames_.Rejected()),
            (unsigned long long)uploadedFrames_, (unsigned long long)sourceHeader_.frame_id,
            (unsigned long long)gpuFrames_.Received(), (unsigned long long)gpuFrames_.Dropped(),
            (unsigned long long)verifiedFrame_, gpuChecksum_, error);
        std::fclose(file);
        std::rename(temp.c_str(), path.c_str());
    }

private:
    android_app *app_;
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
    QuestGpuFrames gpuFrames_;
    DroidDeckXrFrameHeader sourceHeader_{};
    uint64_t uploadedFrames_ = 0, verifiedFrame_ = 0;
    uint32_t gpuChecksum_ = 0;
    GLuint sourceTexture_ = 0, sourceProgram_ = 0;
    GLuint cpuTexture_ = 0;
    jmethodID frameCallback_ = nullptr;
    jmethodID activeCallback_ = nullptr;
    jmethodID statusCallback_ = nullptr;
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
        std::string file = std::string(app_->activity->externalDataPath) + "/xr-capture.request";
        FILE *request = std::fopen(file.c_str(), "r");
        if (!request) return {};
        char token[34]{};
        size_t size = std::fread(token, 1, sizeof(token) - 1, request);
        std::fclose(request);
        unlink(file.c_str());
        if (size != 32 || std::strspn(token, "0123456789abcdef") != 32) return {};
        unlink((std::string(app_->activity->externalDataPath) + "/xr-capture.json").c_str());
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
        std::string file = std::string(app_->activity->externalDataPath) +
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
        std::string file = std::string(app_->activity->externalDataPath) + "/xr-capture.json";
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
        std::fprintf(output, "],\"sourceImage\":true,\"sourceColorSpace\":\"%s\",\"sourceEncoding\":%d,\"eyeEncoding\":%d,"
            "\"srgbWriteControl\":%s,\"srgbWriteEnabled\":%s}\n",
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
            if (AInputEvent_getType(event) != AINPUT_EVENT_TYPE_KEY || AKeyEvent_getKeyCode(event) != AKEYCODE_BACK
                || AKeyEvent_getAction(event) != AKEY_EVENT_ACTION_UP) return 0;
            auto *runtime = static_cast<QuestXR *>(app->userData);
            if (runtime->running_) {
                XrResult result = xrRequestExitSession(runtime->session_);
                if (XR_SUCCEEDED(result)) runtime->quitRequested_ = true;
                else __android_log_print(ANDROID_LOG_ERROR, "DroidDeckXR", "exit request failed: %d", result);
            } else ANativeActivity_finish(app->activity);
            return 1;
        };
        app_->activity->vm->AttachCurrentThread(&env_, nullptr);
        jclass activityClass = env_->GetObjectClass(app_->activity->clazz);
        jmethodID getIntent = env_->GetMethodID(activityClass, "getIntent", "()Landroid/content/Intent;");
        jobject intent = env_->CallObjectMethod(app_->activity->clazz, getIntent);
        jclass intentClass = env_->GetObjectClass(intent);
        jmethodID getBoolean = env_->GetMethodID(intentClass, "getBooleanExtra", "(Ljava/lang/String;Z)Z");
        jstring extra = env_->NewStringUTF("frame_verify");
        verifyPixels_ = env_->CallBooleanMethod(intent, getBoolean, extra, JNI_FALSE);
        env_->DeleteLocalRef(extra);
        activeCallback_ = env_->GetMethodID(activityClass, "onXrActive", "(Z)V");
        frameCallback_ = env_->GetMethodID(activityClass, "onXrFrame", "(J)V");
        statusCallback_ = env_->GetMethodID(activityClass, "onXrStatus", "()I");
        if (env_->ExceptionCheck()) { env_->ExceptionClear(); throw std::runtime_error("XR activity bridge methods unavailable"); }
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
        for (const auto &extension : extensions) {
            if (!std::strcmp(extension.extensionName, XR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME))
                timespecSupported_ = true;
            if (!std::strcmp(extension.extensionName, XR_EXT_PERFORMANCE_SETTINGS_EXTENSION_NAME))
                performanceSupported_ = true;
        }
        std::vector<const char *> enabled{XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME, XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME};
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
        if (requirements.minApiVersionSupported > XR_MAKE_VERSION(3, 0, 0)
            || requirements.maxApiVersionSupported < XR_MAKE_VERSION(3, 0, 0))
            throw std::runtime_error("Runtime does not support GLES 3.0");
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
        XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
        binding.display = display_; binding.config = config; binding.context = context_;
        XrSessionCreateInfo sessionInfo{XR_TYPE_SESSION_CREATE_INFO};
        sessionInfo.next = &binding; sessionInfo.systemId = system_;
        check(xrCreateSession(instance_, &sessionInfo, &session_), "xrCreateSession");
        XrReferenceSpaceCreateInfo reference{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
        reference.poseInReferenceSpace.orientation.w = 1;
        reference.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_STAGE;
        XrResult referenceResult = xrCreateReferenceSpace(session_, &reference, &world_);
        if (referenceResult == XR_ERROR_REFERENCE_SPACE_UNSUPPORTED) {
            reference.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
            reference.poseInReferenceSpace.position.y = -1.6f;
            check(xrCreateReferenceSpace(session_, &reference, &world_), "local standing space");
        } else check(referenceResult, "stage space");
        reference.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
        reference.poseInReferenceSpace.position.y = 0;
        check(xrCreateReferenceSpace(session_, &reference, &view_), "view space");
        initializeActions();
        initializeImages();
        initializeGraphics();
        initializeTracking();
        {
            std::string filename = std::string(app_->activity->internalDataPath) + "/.wayland-rt/" DROIDDECK_XR_FRAME_SOCKET;
            if (!receiver_.Start(filename)) throw std::runtime_error("XR frame receiver unavailable");
            bool gpu = !verifyPixels_ && gpuFrames_.Start(std::string(app_->activity->internalDataPath) + "/.wayland-rt/" DROIDDECK_XR_GPU_SOCKET, display_);
            __android_log_print(ANDROID_LOG_INFO, "DroidDeckXR", "GPU frame channel available=%d", gpu);
        }
        health();
    }

    void initializeActions() {
        hands_[0] = path("/user/hand/left"); hands_[1] = path("/user/hand/right");
        XrActionSetCreateInfo setInfo{XR_TYPE_ACTION_SET_CREATE_INFO};
        std::strcpy(setInfo.actionSetName, "quest"); std::strcpy(setInfo.localizedActionSetName, "Quest controllers");
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
        check(xrSuggestInteractionProfileBindings(instance_, &suggestion), "Touch bindings");
        XrSessionActionSetsAttachInfo attach{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
        attach.countActionSets = 1; attach.actionSets = &actions_;
        check(xrAttachSessionActionSets(session_, &attach), "attach actions");
        for (int hand = 0; hand < 2; hand++) {
            XrActionSpaceCreateInfo space{XR_TYPE_ACTION_SPACE_CREATE_INFO};
            space.action = grip_; space.subactionPath = hands_[hand]; space.poseInActionSpace.orientation.w = 1;
            check(xrCreateActionSpace(session_, &space, &handSpaces_[hand]), "controller space");
        }
    }

    void initializeImages() {
        uint32_t count = 0;
        check(xrEnumerateViewConfigurationViews(instance_, system_, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
            0, &count, nullptr), "view count");
        if (count != 2) throw std::runtime_error("Expected two stereo views");
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
            target.width = views[eye].recommendedImageRectWidth;
            target.height = views[eye].recommendedImageRectHeight;
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
        packet.active = state_ == XR_SESSION_STATE_VISIBLE || state_ == XR_SESSION_STATE_FOCUSED;
        packet.sampled_ns = (int64_t)now.tv_sec * 1000000000LL + now.tv_nsec;
        if (convertTime_) {
            timespec displayTime{};
            check(convertTime_(instance_, predicted, &displayTime), "display time conversion");
            packet.predicted_ns = (int64_t)displayTime.tv_sec * 1000000000LL + displayTime.tv_nsec;
        }
        packet.period_ns = period_;
        packet.head = posePacket(head_.pose, head_.locationFlags);
        for (int eye = 0; eye < 2; eye++) {
            packet.eyes[eye].pose = posePacket(views[eye].pose, head_.locationFlags);
            packet.eyes[eye].fov[0] = views[eye].fov.angleLeft;
            packet.eyes[eye].fov[1] = views[eye].fov.angleRight;
            packet.eyes[eye].fov[2] = views[eye].fov.angleUp;
            packet.eyes[eye].fov[3] = views[eye].fov.angleDown;
            packet.eyes[eye].width = eyes_[eye].width; packet.eyes[eye].height = eyes_[eye].height;
            packet.hands[eye] = inputs_[eye];
            packet.hands[eye].pose = posePacket(handLocations_[eye].pose, handLocations_[eye].locationFlags);
            packet.hands[eye].active = handActive_[eye];
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
        env_->CallVoidMethod(app_->activity->clazz, activeCallback_, static_cast<jboolean>(attach));
        if (env_->ExceptionCheck()) {
            env_->ExceptionDescribe(); env_->ExceptionClear(); throw std::runtime_error("Guest XR handshake failed");
        }
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
            XrHapticActionInfo info{XR_TYPE_HAPTIC_ACTION_INFO}; info.action = vibration_; info.subactionPath = hands_[hand];
            XrHapticVibration vibration{XR_TYPE_HAPTIC_VIBRATION};
            vibration.duration = pulse.duration_ns; vibration.amplitude = pulse.amplitude; vibration.frequency = pulse.frequency;
            check(xrApplyHapticFeedback(session_, &info, reinterpret_cast<XrHapticBaseHeader *>(&vibration)), "guest haptics");
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
        {
            glViewport(0, 0, eye.width, eye.height); glDisable(GL_DEPTH_TEST);
            glUseProgram(sourceProgram_); glBindVertexArray(vertexArray_);
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, sourceTexture_);
            glUniform1i(glGetUniformLocation(sourceProgram_, "source"), 0);
            glUniform1f(glGetUniformLocation(sourceProgram_, "eye"), &eye == &eyes_[1] ? 1.f : 0.f);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        if (glGetError() != GL_NO_ERROR) throw std::runtime_error("OpenGL render error");
        glFlush();
    }

    void pollEvents() {
        XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
        XrResult result;
        while ((result = xrPollEvent(instance_, &event)) == XR_SUCCESS) {
            if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                state_ = reinterpret_cast<XrEventDataSessionStateChanged *>(&event)->state;
                __android_log_print(ANDROID_LOG_INFO, "DroidDeckXR", "session state=%d", state_);
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
        check(xrLocateSpace(view_, world_, frameState.predictedDisplayTime, &head_), "head pose");
        if (state_ == XR_SESSION_STATE_FOCUSED) {
            XrActiveActionSet active{actions_, XR_NULL_PATH};
            XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO}; sync.countActiveActionSets = 1; sync.activeActionSets = &active;
            check(xrSyncActions(session_, &sync), "xrSyncActions");
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
        } else handActive_[0] = handActive_[1] = false;
        pollHaptics();
        XrViewLocateInfo locate{XR_TYPE_VIEW_LOCATE_INFO}; locate.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
        locate.displayTime = frameState.predictedDisplayTime; locate.space = world_;
        XrViewState viewState{XR_TYPE_VIEW_STATE}; uint32_t count = 0;
        XrView views[2]{{XR_TYPE_VIEW}, {XR_TYPE_VIEW}};
        check(xrLocateViews(session_, &locate, &viewState, 2, &count, views), "xrLocateViews");
        publishTracking(views, frameState.predictedDisplayTime);
        if (guestAttached_) {
            timespec now{}; clock_gettime(CLOCK_MONOTONIC, &now);
            env_->CallVoidMethod(app_->activity->clazz, frameCallback_, (jlong)now.tv_sec * 1000000000LL + now.tv_nsec);
            if (env_->ExceptionCheck()) { env_->ExceptionClear(); throw std::runtime_error("Guest frame callback failed"); }
        }
        XrCompositionLayerProjectionView projection[2]{{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}, {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}};
        bool valid = frameState.shouldRender && count == 2 &&
            (viewState.viewStateFlags & (XR_VIEW_STATE_ORIENTATION_VALID_BIT | XR_VIEW_STATE_POSITION_VALID_BIT))
            == (XR_VIEW_STATE_ORIENTATION_VALID_BIT | XR_VIEW_STATE_POSITION_VALID_BIT);
        {
            valid = valid && sourceHeader_.frame_id && xr_frame_now() - sourceHeader_.captured_ns < 500000000LL;
            if (valid) for (int eye = 0; eye < 2; eye++) {
                auto source = xr_frame_view(sourceHeader_, eye);
                views[eye].pose.orientation = {source.orientation[0], source.orientation[1], source.orientation[2], source.orientation[3]};
                views[eye].pose.position = {source.position[0], source.position[1], source.position[2]};
                views[eye].fov = {source.fov[0], source.fov[1], source.fov[2], source.fov[3]};
            }
        }
        const std::string capture = captureRequest();
        bool captured = valid;
        if (!capture.empty() && valid)
            captured = captureTexture(sourceTexture_, sourceHeader_.width, sourceHeader_.height, "source");
        if (valid) for (int eye = 0; eye < 2; eye++) {
            uint32_t image = 0;
            XrSwapchainImageAcquireInfo acquire{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
            check(xrAcquireSwapchainImage(eyes_[eye].swapchain, &acquire, &image), "acquire image");
            XrSwapchainImageWaitInfo imageWait{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO}; imageWait.timeout = XR_INFINITE_DURATION;
            check(xrWaitSwapchainImage(eyes_[eye].swapchain, &imageWait), "wait image");
            render(eyes_[eye], image);
            if (!capture.empty()) captured = captureTexture(eyes_[eye].images[image].image, eyes_[eye].width, eyes_[eye].height, eye ? "right" : "left") && captured;
            XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
            check(xrReleaseSwapchainImage(eyes_[eye].swapchain, &release), "release image");
            projection[eye].pose = views[eye].pose; projection[eye].fov = views[eye].fov;
            projection[eye].subImage.swapchain = eyes_[eye].swapchain;
            projection[eye].subImage.imageRect.extent = {eyes_[eye].width, eyes_[eye].height};
        }
        XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
        layer.space = world_; layer.viewCount = 2; layer.views = projection;
        const XrCompositionLayerBaseHeader *layers[] = {reinterpret_cast<XrCompositionLayerBaseHeader *>(&layer)};
        XrFrameEndInfo end{XR_TYPE_FRAME_END_INFO}; end.displayTime = frameState.predictedDisplayTime;
        end.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE; end.layerCount = valid ? 1 : 0; end.layers = layers;
        check(xrEndFrame(session_, &end), "xrEndFrame");
        if (!capture.empty()) captureResult(capture, captured, valid, projection, frameState.predictedDisplayTime);
        frames_++; if (valid) rendered_++;
        if (frames_ % 72 == 0) {
            health();
            __android_log_print(ANDROID_LOG_INFO, "DroidDeckXR", "frames=%llu rendered=%llu head=%llu hands=%d,%d period=%lld",
                (unsigned long long)frames_, (unsigned long long)rendered_, (unsigned long long)head_.locationFlags,
                handActive_[0], handActive_[1], (long long)period_);
        }
    }

    void cleanup() {
        receiver_.Stop();
        gpuFrames_.Stop();
        try { attachGuest(false); } catch (const std::exception &error) {
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
        if (env_) app_->activity->vm->DetachCurrentThread();
    }
};

void android_main(android_app *app) {
    QuestXR runtime(app);
    try { runtime.run(); }
    catch (const std::exception &error) {
        __android_log_print(ANDROID_LOG_ERROR, "DroidDeckXR", "%s", error.what());
        runtime.health(error.what());
        ANativeActivity_finish(app->activity);
    }
}
