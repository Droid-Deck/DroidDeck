#include "../src/driver_droiddeck.cpp"
#include <cassert>
#include <map>

void droiddeck::Presenter::AddTextures(const uint64_t[3], const int[3], uint32_t, uint32_t, uint32_t) {}
void droiddeck::Presenter::RemoveTexture(uint64_t) {}
void droiddeck::Presenter::Submit(const EyeFrame &) {}
void droiddeck::Presenter::Shutdown() {}

struct Host : IVRServerDriverHost {
    std::vector<DriverPose_t> poses;
    bool TrackedDeviceAdded(const char *, ETrackedDeviceClass, ITrackedDeviceServerDriver *) override { return true; }
    void TrackedDevicePoseUpdated(uint32_t, const DriverPose_t &p, uint32_t) override { poses.push_back(p); }
    void VsyncEvent(double) override {}
    void VendorSpecificEvent(uint32_t, EVREventType, const VREvent_Data_t &, double) override {}
    bool IsExiting() override { return false; }
    bool PollNextEvent(VREvent_t *, uint32_t) override { return false; }
    void GetRawTrackedDevicePoses(float, TrackedDevicePose_t *, uint32_t) override {}
    void RequestRestart(const char *, const char *, const char *, const char *) override {}
    uint32_t GetFrameTimings(Compositor_FrameTiming *, uint32_t) override { return 0; }
    void SetDisplayEyeToHead(uint32_t, const HmdMatrix34_t &, const HmdMatrix34_t &) override {}
    void SetDisplayProjectionRaw(uint32_t, const HmdRect2_t &, const HmdRect2_t &) override {}
    void SetRecommendedRenderTargetSize(uint32_t, uint32_t, uint32_t) override {}
};
struct Properties : IVRProperties {
    ETrackedPropertyError ReadPropertyBatch(PropertyContainerHandle_t, PropertyRead_t *, uint32_t) override { return TrackedProp_Success; }
    ETrackedPropertyError WritePropertyBatch(PropertyContainerHandle_t, PropertyWrite_t *, uint32_t) override { return TrackedProp_Success; }
    const char *GetPropErrorNameFromEnum(ETrackedPropertyError) override { return "success"; }
    PropertyContainerHandle_t TrackedDeviceToPropertyContainer(TrackedDeviceIndex_t id) override { return id+1; }
};
struct Input : IVRDriverInput {
    std::map<VRInputComponentHandle_t, float> values;
    EVRInputError CreateBooleanComponent(PropertyContainerHandle_t, const char *, VRInputComponentHandle_t *h) override { *h = values.size()+1; values[*h]=0; return VRInputError_None; }
    EVRInputError UpdateBooleanComponent(VRInputComponentHandle_t h, bool v, double) override { values[h]=v; return VRInputError_None; }
    EVRInputError CreateScalarComponent(PropertyContainerHandle_t c, const char *n, VRInputComponentHandle_t *h, EVRScalarType, EVRScalarUnits) override { return CreateBooleanComponent(c,n,h); }
    EVRInputError UpdateScalarComponent(VRInputComponentHandle_t h, float v, double) override { values[h]=v; return VRInputError_None; }
    EVRInputError CreateHapticComponent(PropertyContainerHandle_t c, const char *n, VRInputComponentHandle_t *h) override { return CreateBooleanComponent(c,n,h); }
    EVRInputError CreateSkeletonComponent(PropertyContainerHandle_t, const char *, const char *, const char *, EVRSkeletalTrackingLevel, const VRBoneTransform_t *, uint32_t, VRInputComponentHandle_t *) override { return VRInputError_None; }
    EVRInputError UpdateSkeletonComponent(VRInputComponentHandle_t, EVRSkeletalMotionRange, const VRBoneTransform_t *, uint32_t) override { return VRInputError_None; }
    EVRInputError CreatePoseComponent(PropertyContainerHandle_t, const char *, VRInputComponentHandle_t *) override { return VRInputError_None; }
    EVRInputError UpdatePoseComponent(VRInputComponentHandle_t, const HmdMatrix34_t *, double) override { return VRInputError_None; }
    EVRInputError CreateEyeTrackingComponent(PropertyContainerHandle_t, const char *, VRInputComponentHandle_t *) override { return VRInputError_None; }
    EVRInputError UpdateEyeTrackingComponent(VRInputComponentHandle_t, const VREyeTrackingData_t *, double) override { return VRInputError_None; }
};
struct Context : IVRDriverContext {
    Host host;
    Input input;
    Properties properties;
    void *GetGenericInterface(const char *name, EVRInitError *) override {
        if (!strcmp(name, IVRServerDriverHost_Version)) return &host;
        if (!strcmp(name, IVRDriverInput_Version)) return &input;
        if (!strcmp(name, IVRProperties_Version)) return &properties;
        return nullptr;
    }
    DriverHandle_t GetDriverHandle() override { return 1; }
};

int main() {
    Context context;
    VRDriverContext() = &context;
    CardboardController controller(false);
    assert(controller.Activate(1) == VRInitError_None);
    dd_spatial_hand hand{};
    for (int i=0; i<500; ++i) controller.UpdateOptical(hand, false, nullptr, 1000000000+i, 1, 1);
    assert(context.host.poses.size() == 500);
    for (const auto &pose : context.host.poses) {
        assert(pose.deviceIsConnected && !pose.poseIsValid);
        assert(pose.result == TrackingResult_Running_OutOfRange);
        assert(pose.qRotation.w == 1);
    }
    hand.pose = {1000000000, {1,2,3}, {0,0,0,1}, DD_SPATIAL_VALID};
    controller.UpdateOptical(hand, true, nullptr, 1000000000, 1, 1);
    hand.trigger = 1; hand.grip = 1;
    controller.UpdateOptical(hand, true, nullptr, 1000000000, 1, 1);
    assert(context.host.poses.back().poseIsValid);
    assert(std::any_of(context.input.values.begin(), context.input.values.end(), [](const auto &v){ return v.second != 0; }));
    controller.UpdateOptical(hand, true, nullptr, 1100000001, 1, 1);
    assert(context.host.poses.back().deviceIsConnected && !context.host.poses.back().poseIsValid);
    for (const auto &v : context.input.values) assert(v.second == 0);
    controller.Disconnect();
    assert(!context.host.poses.back().deviceIsConnected);
    puts("PASS: optical tracking loss keeps controllers connected and releases inputs");
}
