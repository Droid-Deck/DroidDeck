#include "../src/driver_droiddeck.cpp"
#include <cassert>
#include <map>

void droiddeck::Presenter::AddTextures(const uint64_t[3], const int[3], uint32_t, uint32_t, uint32_t, uint32_t) {}
void droiddeck::Presenter::RemoveTexture(uint64_t) {}
void droiddeck::Presenter::Submit(const EyeFrame &) {}
void droiddeck::Presenter::Shutdown() {}

struct Host : IVRServerDriverHost {
    std::vector<DriverPose_t> poses;
    const std::map<VRInputComponentHandle_t, float> *inputs = nullptr;
    std::vector<std::map<VRInputComponentHandle_t, float>> inputsAtPose;
    bool TrackedDeviceAdded(const char *, ETrackedDeviceClass, ITrackedDeviceServerDriver *) override { return true; }
    void TrackedDevicePoseUpdated(uint32_t, const DriverPose_t &p, uint32_t) override {
        poses.push_back(p); inputsAtPose.push_back(*inputs);
    }
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
    std::map<std::string, VRInputComponentHandle_t> handles;
    std::map<VRInputComponentHandle_t, EVRInputError> failures;
    EVRInputError CreateBooleanComponent(PropertyContainerHandle_t, const char *name, VRInputComponentHandle_t *h) override {
        *h = values.size()+1; values[*h]=0; handles[name]=*h; return VRInputError_None;
    }
    EVRInputError UpdateBooleanComponent(VRInputComponentHandle_t h, bool v, double) override {
        if (failures[h] != VRInputError_None) return failures[h];
        values[h]=v; return VRInputError_None;
    }
    EVRInputError CreateScalarComponent(PropertyContainerHandle_t c, const char *n, VRInputComponentHandle_t *h, EVRScalarType, EVRScalarUnits) override { return CreateBooleanComponent(c,n,h); }
    EVRInputError UpdateScalarComponent(VRInputComponentHandle_t h, float v, double) override {
        if (failures[h] != VRInputError_None) return failures[h];
        values[h]=v; return VRInputError_None;
    }
    EVRInputError CreateHapticComponent(PropertyContainerHandle_t c, const char *n, VRInputComponentHandle_t *h) override { return CreateBooleanComponent(c,n,h); }
    EVRInputError CreateSkeletonComponent(PropertyContainerHandle_t, const char *, const char *, const char *, EVRSkeletalTrackingLevel, const VRBoneTransform_t *, uint32_t, VRInputComponentHandle_t *) override { return VRInputError_None; }
    EVRInputError UpdateSkeletonComponent(VRInputComponentHandle_t, EVRSkeletalMotionRange, const VRBoneTransform_t *, uint32_t) override { return VRInputError_None; }
    EVRInputError CreatePoseComponent(PropertyContainerHandle_t, const char *, VRInputComponentHandle_t *) override { return VRInputError_None; }
    EVRInputError UpdatePoseComponent(VRInputComponentHandle_t, const HmdMatrix34_t *, double) override { return VRInputError_None; }
    EVRInputError CreateEyeTrackingComponent(PropertyContainerHandle_t, const char *, VRInputComponentHandle_t *) override { return VRInputError_None; }
    EVRInputError UpdateEyeTrackingComponent(VRInputComponentHandle_t, const VREyeTrackingData_t *, double) override { return VRInputError_None; }
};
struct DriverLog : IVRDriverLog {
    std::vector<std::string> lines;
    void Log(const char *message) override { lines.emplace_back(message); }
};
struct Context : IVRDriverContext {
    Host host;
    Input input;
    Properties properties;
    DriverLog log;
    Context() { host.inputs = &input.values; }
    void *GetGenericInterface(const char *name, EVRInitError *) override {
        if (!strcmp(name, IVRServerDriverHost_Version)) return &host;
        if (!strcmp(name, IVRDriverInput_Version)) return &input;
        if (!strcmp(name, IVRProperties_Version)) return &properties;
        if (!strcmp(name, IVRDriverLog_Version)) return &log;
        return nullptr;
    }
    DriverHandle_t GetDriverHandle() override { return 1; }
};

int main() {
    Context context;
    VRDriverContext() = &context;
    CardboardController controller(false);
    controller.SetProfile(PadProfile::Vive);
    assert(controller.Activate(1) == VRInitError_None);
    const auto gripHandle=context.input.handles.at("/input/grip/click");
    const auto clickHandle=context.input.handles.at("/input/trigger/click");
    const auto triggerHandle=context.input.handles.at("/input/trigger/value");
    const auto assertReleasedAtPose = [&] {
        const auto &inputs=context.host.inputsAtPose.back();
        assert(inputs.at(gripHandle)==0 && inputs.at(clickHandle)==0 && inputs.at(triggerHandle)==0);
    };
    dd_spatial_hand hand{};
    for (int i=0; i<500; ++i) controller.UpdateOptical(hand, false, nullptr, 1000000000+i, 1, 1);
    assert(context.host.poses.size() == 500);
    assert(context.log.lines.size()==2);
    for (const auto &pose : context.host.poses) {
        assert(pose.deviceIsConnected && !pose.poseIsValid);
        assert(pose.result == TrackingResult_Running_OutOfRange);
        assert(pose.qRotation.w == 1);
    }
    hand.pose = {1000000000, {1,2,3}, {0,0,0,1}, DD_SPATIAL_VALID | DD_SPATIAL_TRIGGER_VALID | DD_SPATIAL_GRIP_VALID};
    int64_t now=1000000000;
    uint64_t connection=1,origin=1;
    uint16_t epochs=0;
    const auto observe = [&](float trigger,uint32_t grip) {
        now+=80000000;hand.pose.time_ns=now;hand.trigger=trigger;hand.grip=grip;
        controller.UpdateOptical(hand,true,nullptr,now,connection,origin,epochs);
    };
    const auto missing = [&](bool headValid,int64_t elapsed) {
        now+=elapsed;controller.UpdateOptical({},headValid,nullptr,now,connection,origin,epochs);
    };
    const auto assertPressed = [&] {
        assert(context.host.poses.back().poseIsValid);
        assert(context.input.values.at(gripHandle)==1 && context.input.values.at(clickHandle)==1 &&
            context.input.values.at(triggerHandle)==1);
    };
    observe(0,0);observe(1,1);assertPressed();
    now+=200000000;controller.UpdateOptical(hand,true,nullptr,now,connection,origin,epochs);
    assertPressed();
    ++now;controller.UpdateOptical(hand,true,nullptr,now,connection,origin,epochs);
    assert(context.host.poses.back().deviceIsConnected && context.host.poses.back().poseIsValid);
    assertReleasedAtPose();
    now=hand.pose.time_ns+350000000;controller.UpdateOptical(hand,true,nullptr,now,connection,origin,epochs);
    assert(context.host.poses.back().poseIsValid);
    assert(std::abs(context.host.poses.back().poseTimeOffset+.35)<1e-9);
    assertReleasedAtPose();
    ++now;controller.UpdateOptical(hand,true,nullptr,now,connection,origin,epochs);
    assert(context.host.poses.back().deviceIsConnected && !context.host.poses.back().poseIsValid);
    assertReleasedAtPose();

    observe(0,0);observe(1,1);assertPressed();
    missing(true,50000000);
    assert(context.host.poses.back().poseIsValid && context.host.poses.back().vecPosition[0]==1);
    assert(std::abs(context.host.poses.back().poseTimeOffset+.05)<1e-9);
    assertReleasedAtPose();
    missing(true,150000000);
    assert(context.host.poses.back().poseIsValid);
    assert(std::abs(context.host.poses.back().poseTimeOffset+.2)<1e-9);
    missing(true,150000000);
    assert(context.host.poses.back().poseIsValid);
    assert(std::abs(context.host.poses.back().poseTimeOffset+.35)<1e-9);
    assertReleasedAtPose();
    missing(true,1);assert(!context.host.poses.back().poseIsValid);
    observe(1,1);assertReleasedAtPose();
    observe(0,0);observe(1,1);assertPressed();
    missing(false,10000000);
    assert(!context.host.poses.back().poseIsValid);assertReleasedAtPose();
    missing(true,10000000);assert(!context.host.poses.back().poseIsValid);

    observe(0,0);observe(1,1);assertPressed();
    epochs=1;observe(1,1);
    assert(context.host.poses.back().poseIsValid);
    assert(context.input.values.at(gripHandle)==1 && context.input.values.at(clickHandle)==0 && context.input.values.at(triggerHandle)==0);
    epochs=0x101;observe(1,1);assertReleasedAtPose();
    hand.trigger=0;hand.grip=0;
    controller.UpdateOptical(hand,true,nullptr,now+1,connection,origin,epochs);
    observe(1,1);assertReleasedAtPose();
    observe(0,0);observe(1,1);assertPressed();

    ++origin;missing(true,10000000);
    assert(!context.host.poses.back().poseIsValid);assertReleasedAtPose();
    observe(1,1);assertReleasedAtPose();
    observe(0,0);observe(1,1);assertPressed();
    ++connection;missing(true,10000000);
    assert(!context.host.poses.back().poseIsValid);assertReleasedAtPose();

    observe(0,0);observe(1,1);assertPressed();
    controller.Disconnect();
    assert(!context.host.poses.back().deviceIsConnected);assertReleasedAtPose();
    missing(true,10000000);assert(!context.host.poses.back().poseIsValid);
    observe(1,1);assertReleasedAtPose();
    controller.Update(controller.GetPose(),nullptr,false,0);
    missing(true,10000000);assert(!context.host.poses.back().poseIsValid);
    PadState pad{};pad.axes[0]=16384;pad.buttons=PAD_SELECT;
    controller.UpdateOptical({},true,&pad,++now,connection,origin,epochs);
    assert(!context.host.poses.back().poseIsValid);
    assert(std::any_of(context.input.values.begin(),context.input.values.end(),[](const auto &v){ return v.second!=0; }));

    observe(0,0);observe(1,1);assertPressed();
    context.input.failures[gripHandle]=VRInputError_IPCError;
    missing(false,10000000);
    assert(context.input.values.at(gripHandle)==1 && context.input.values.at(triggerHandle)==0);
    assert(context.log.lines.back().find("error=7/0/0")!=std::string::npos);
    const auto failedLogCount=context.log.lines.size();
    for(int i=0;i<10;++i) missing(false,1);
    assert(context.log.lines.size()==failedLogCount);
    context.input.failures[gripHandle]=VRInputError_None;
    missing(false,10000000);
    assertReleasedAtPose();
    assert(context.log.lines.back().find("error=0/0/0")!=std::string::npos);
    assert(context.log.lines.size()==failedLogCount+1);
    observe(1,1);assertReleasedAtPose();
    observe(0,0);observe(1,1);assertPressed();
    const dd_spatial_pose aimHead{now, {0,1.6f,0}, {0,0,0,1}, DD_SPATIAL_VALID};
    controller.UpdateOptical(hand,true,nullptr,now,connection,origin,epochs,&aimHead);
    assertPressed();
    const auto aimed = controller.GetPose();
    const auto expectedAim = ShoulderAim({0,1.6f,0}, {}, {1,2,3}, false, {});
    assert(std::abs(aimed.qRotation.w - expectedAim.w) < 1e-6);
    assert(std::abs(aimed.qRotation.y - expectedAim.y) < 1e-6);
    assert(aimed.poseTimeOffset == 0);
    controller.UpdateOptical(hand,true,nullptr,now,connection,origin,epochs);
    assertPressed();
    assert(controller.GetPose().qRotation.w == 1);
    const auto palmRotation=Normalize(AboutX(.4)*AboutY(-.6));
    hand.pose.rotation[0]=palmRotation.x;hand.pose.rotation[1]=palmRotation.y;
    hand.pose.rotation[2]=palmRotation.z;hand.pose.rotation[3]=palmRotation.w;
    controller.UpdateOptical(hand,true,nullptr,now,connection,++origin,epochs);
    const auto normal=controller.GetPose();
    assert(std::abs(normal.qRotation.w-palmRotation.w)<1e-6);
    assert(std::abs(normal.qRotation.x-palmRotation.x)<1e-6);
    assert(std::abs(normal.qRotation.y-palmRotation.y)<1e-6);
    assert(std::abs(normal.qRotation.z-palmRotation.z)<1e-6);
    assert(normal.vecPosition[0]==hand.pose.position[0] && normal.poseTimeOffset==0);
    controller.UpdateOptical({},true,nullptr,now+1,connection,origin,epochs,&aimHead);
    assertReleasedAtPose();
    assert(!controller.GetPose().poseIsValid);
    controller.Deactivate();
    for(const auto &value:context.input.values) assert(value.second==0);
    const auto poseCount=context.host.poses.size();
    controller.UpdateOptical(hand,true,nullptr,now+1,connection,origin,epochs);
    assert(context.host.poses.size()==poseCount);
    HandController joyCon(false);
    assert(joyCon.Activate(2) == VRInitError_None);
    const auto joyTrigger = context.input.handles.at("/input/trigger/click");
    droiddeck_joycon_hand joy{};
    joy.rotation[0] = 1;
    joy.time_ns = now;
    joy.buttons = DROIDDECK_JOYCON_TRIGGER;
    droiddeck_spatialpose spatial{};
    spatial.flags = DD_SPATIAL_ACTIVE | DD_SPATIAL_JOYCONS;
    spatial.connection = spatial.origin = 1;
    spatial.head = {now, {2, 1.6f, 3}, {0,0,0,1}, DD_SPATIAL_VALID};
    spatial.hands[0].pose = {now, {1.8f, 1.2f, 2.6f}, {0,0,0,1}, DD_SPATIAL_VALID};
    assert(joyCon.Update(&joy, false, {1,0,0,0}, now, &spatial));
    auto pose = joyCon.GetPose();
    assert(pose.poseIsValid && context.input.values.at(joyTrigger) == 1);
    Quat expectedRotation; Vec3 expectedPosition;
    IndexRawPose(false, {}, {1.8f, 1.2f, 2.6f}, &expectedRotation, &expectedPosition);
    assert(std::abs(pose.vecPosition[0] - expectedPosition.x) < 1e-5);
    assert(std::abs(pose.qRotation.w - expectedRotation.w) < 1e-5);
    spatial.hands[0].pose.flags = 0;
    joyCon.Update(&joy, false, {1,0,0,0}, now, &spatial);
    assert(joyCon.GetPose().poseIsValid && context.input.values.at(joyTrigger) == 1);
    const Quat turned = AboutY(.7);
    joy.rotation[0] = turned.w; joy.rotation[2] = turned.y;
    joyCon.Update(&joy, false, {1,0,0,0}, now, &spatial);
    const auto beforeLoss = joyCon.GetPose().qRotation;
    spatial.head.flags = 0;
    joyCon.Update(&joy, false, {1,0,0,0}, now, &spatial);
    assert(!joyCon.GetPose().poseIsValid && context.input.values.at(joyTrigger) == 0);
    assert(context.host.inputsAtPose.back().at(joyTrigger) == 0);
    spatial.head.flags = DD_SPATIAL_VALID;
    joyCon.Update(&joy, false, {1,0,0,0}, now, &spatial);
    assert(std::abs(joyCon.GetPose().qRotation.w - beforeLoss.w) < 1e-5);
    assert(std::abs(joyCon.GetPose().qRotation.y - beforeLoss.y) < 1e-5);
    spatial.origin++;
    spatial.head.position[0] = -2;
    joyCon.Update(&joy, false, {1,0,0,0}, now, &spatial);
    assert(joyCon.GetPose().poseIsValid && joyCon.GetPose().vecPosition[0] < -2);
    joyCon.Update(nullptr, false, {1,0,0,0}, now, &spatial);
    assert(!joyCon.GetPose().deviceIsConnected && context.input.values.at(joyTrigger) == 0);
    puts("PASS: optical controller recovery and Luma Joy-Con camera, fallback, head loss and origin changes");

    for (const PadProfile profile : {PadProfile::Index, PadProfile::Touch}) {
        auto &padContext = context;
        padContext.input.handles.clear();
        CardboardController pad(true);
        pad.SetProfile(profile);
        assert(pad.Activate(2) == VRInitError_None);
        const auto &h = padContext.input.handles;
        const std::string stick = profile == PadProfile::Touch ? "/input/joystick" : "/input/thumbstick";
        assert(!h.count("/input/trackpad/x") && h.count("/input/b/click") && h.count("/input/grip/value"));
        assert(h.count("/input/application_menu/click") == (profile == PadProfile::Touch));
        DriverPose_t head{};
        head.qRotation.w = 1;
        PadState state{};
        state.buttons = PAD_A | PAD_RB;
        state.axes[2] = 32767;
        state.axes[4] = 255;
        pad.Update(head, &state, false, 0);
        const auto value = [&](const std::string &name) { return padContext.input.values.at(h.at(name)); };
        assert(value("/input/a/click") == 1 && value("/input/b/click") == 0);
        assert(value(stick + "/x") == 1 && value(stick + "/touch") == 1 && value(stick + "/click") == 0);
        assert(value("/input/grip/value") == 1 && value("/input/trigger/value") == 1);
        pad.Update(head, &state, true, 0);
        assert(value(stick + "/x") == 0);
        state = {};
        state.buttons = PAD_R3;
        pad.Update(head, &state, false, 0);
        assert(value(stick + "/click") == 1);
        state.buttons = PAD_L3 | PAD_R3;
        pad.Update(head, &state, false, 0);
        assert(value(stick + "/click") == 0);
        dd_spatial_hand optical{};
        state = {};
        state.buttons = PAD_A;
        pad.UpdateOptical(optical, false, &state, 1000000000, 1, 1);
        assert(value("/input/a/click") == 1 && value(stick + "/click") == 0 && value(stick + "/touch") == 0);
        state.buttons = PAD_B | PAD_R3;
        pad.UpdateOptical(optical, false, &state, 1000000001, 1, 1);
        assert(value("/input/b/click") == 1 && value("/input/a/click") == 0 && value(stick + "/click") == 1);
        assert(!padContext.input.values.count(0));
    }
    puts("PASS: game pad hands as Index and Quest Touch thumbstick controllers");
}
