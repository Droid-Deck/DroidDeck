#pragma once
#include <openxr/openxr.h>
#include <dlfcn.h>
#include <stdexcept>
#include <string>

#define DROIDDECK_XR_INSTANCE_FUNCTIONS(X) \
    X(xrAcquireSwapchainImage) X(xrApplyHapticFeedback) X(xrAttachSessionActionSets) \
    X(xrBeginFrame) X(xrBeginSession) X(xrCreateAction) X(xrCreateActionSet) \
    X(xrCreateActionSpace) X(xrCreateReferenceSpace) X(xrCreateSession) X(xrCreateSwapchain) \
    X(xrDestroyActionSet) X(xrDestroyInstance) X(xrDestroySession) X(xrDestroySpace) \
    X(xrDestroySwapchain) X(xrEndFrame) X(xrEndSession) X(xrEnumerateSwapchainFormats) \
    X(xrEnumerateSwapchainImages) X(xrEnumerateViewConfigurationViews) \
    X(xrGetActionStateBoolean) X(xrGetActionStateFloat) X(xrGetActionStatePose) \
    X(xrGetActionStateVector2f) X(xrGetSystem) X(xrLocateSpace) X(xrLocateViews) \
    X(xrPollEvent) X(xrReleaseSwapchainImage) X(xrRequestExitSession) X(xrStringToPath) \
    X(xrSuggestInteractionProfileBindings) X(xrSyncActions) X(xrWaitFrame) \
    X(xrWaitSwapchainImage) X(xrGetInstanceProperties) X(xrGetCurrentInteractionProfile) X(xrPathToString)

class OpenXrApi {
public:
    OpenXrApi() = default;
    OpenXrApi(const OpenXrApi &) = delete;
    OpenXrApi &operator=(const OpenXrApi &) = delete;
    ~OpenXrApi() { if (library_) dlclose(library_); }

    void loadLoader(const char *name) {
        if (library_) { dlclose(library_); library_ = nullptr; }
        xrGetInstanceProcAddr = nullptr;
        xrEnumerateInstanceExtensionProperties = nullptr;
        xrCreateInstance = nullptr;
        loaderName_ = name;
        library_ = dlopen(name, RTLD_NOW | RTLD_LOCAL);
        if (!library_) throw std::runtime_error(loaderName_ + ": " + dlerror());
        xrGetInstanceProcAddr = reinterpret_cast<PFN_xrGetInstanceProcAddr>(dlsym(library_, "xrGetInstanceProcAddr"));
        if (!xrGetInstanceProcAddr) throw std::runtime_error(loaderName_ + ": missing xrGetInstanceProcAddr");
        load(XR_NULL_HANDLE, "xrEnumerateInstanceExtensionProperties", xrEnumerateInstanceExtensionProperties);
        load(XR_NULL_HANDLE, "xrCreateInstance", xrCreateInstance);
    }

    void loadInstance(XrInstance instance) {
        load(instance, "xrDestroyInstance", xrDestroyInstance);
#define LOAD(name) load(instance, #name, name);
        DROIDDECK_XR_INSTANCE_FUNCTIONS(LOAD)
#undef LOAD
    }

    const std::string &loaderName() const { return loaderName_; }
    PFN_xrGetInstanceProcAddr xrGetInstanceProcAddr = nullptr;
    PFN_xrEnumerateInstanceExtensionProperties xrEnumerateInstanceExtensionProperties = nullptr;
    PFN_xrCreateInstance xrCreateInstance = nullptr;
#define DECLARE(name) PFN_##name name = nullptr;
    DROIDDECK_XR_INSTANCE_FUNCTIONS(DECLARE)
#undef DECLARE

private:
    template<class Function>
    void load(XrInstance instance, const char *name, Function &function) {
        const XrResult result = xrGetInstanceProcAddr(instance, name, reinterpret_cast<PFN_xrVoidFunction *>(&function));
        if (XR_FAILED(result) || !function)
            throw std::runtime_error(loaderName_ + ": unavailable " + name + " (" + std::to_string(result) + ")");
    }
    void *library_ = nullptr;
    std::string loaderName_;
};

#undef DROIDDECK_XR_INSTANCE_FUNCTIONS
