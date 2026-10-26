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
    std::map<std::pair<PropertyContainerHandle_t, ETrackedDeviceProperty>, std::string> strings;
    ETrackedPropertyError WritePropertyBatch(PropertyContainerHandle_t container, PropertyWrite_t *writes, uint32_t count) override {
        for (uint32_t i = 0; i < count; ++i) {
            if (writes[i].unTag == k_unStringPropertyTag && writes[i].pvBuffer)
                strings[{container, writes[i].prop}] = static_cast<const char *>(writes[i].pvBuffer);
            writes[i].eError = TrackedProp_Success;
        }
        return TrackedProp_Success;
    }
    const char *GetPropErrorNameFromEnum(ETrackedPropertyError) override { return "success"; }
    PropertyContainerHandle_t TrackedDeviceToPropertyContainer(TrackedDeviceIndex_t id) override { return id+1; }
};
struct Input : IVRDriverInput {
    std::map<VRInputComponentHandle_t, float> values;
    std::map<std::string, VRInputComponentHandle_t> handles;
    std::map<VRInputComponentHandle_t, EVRInputError> failures;
    unsigned skeletonUpdates = 0;
    std::map<VRInputComponentHandle_t, HmdMatrix34_t> poses;
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
    EVRInputError CreateSkeletonComponent(PropertyContainerHandle_t c, const char *name, const char *, const char *, EVRSkeletalTrackingLevel, const VRBoneTransform_t *, uint32_t, VRInputComponentHandle_t *h) override {
        return CreateBooleanComponent(c, name, h);
    }
    EVRInputError UpdateSkeletonComponent(VRInputComponentHandle_t h, EVRSkeletalMotionRange, const VRBoneTransform_t *bones, uint32_t count) override {
        assert(h && count == 31);
        for (uint32_t i = 0; i < count; ++i) {
            for (float p : bones[i].position.v) assert(std::isfinite(p));
            assert(std::isfinite(bones[i].orientation.w));
        }
        ++skeletonUpdates;
        return VRInputError_None;
    }
    EVRInputError CreatePoseComponent(PropertyContainerHandle_t c, const char *name, VRInputComponentHandle_t *h) override { return CreateBooleanComponent(c, name, h); }
    EVRInputError UpdatePoseComponent(VRInputComponentHandle_t h, const HmdMatrix34_t *pose, double) override { poses[h] = *pose; return VRInputError_None; }
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
    assert(controller.Activate(1) == VRInitError_None);
    const auto xrClick = context.input.handles.at("/input/trigger/click");
    const auto xrTrigger = context.input.handles.at("/input/trigger/value");
    DroidDeckXrSkeleton xrSkeleton{};
    auto xrPacket = droiddeck_xr_input_v2_neutral(101, 1);
    xrPacket.sample_monotonic_ns = now;
    xrPacket.flags = DROIDDECK_XR_INPUT_FOCUSED | DROIDDECK_XR_INPUT_HAND_MODE;
    auto &xrHand = xrPacket.hands[0];
    xrHand.source = DROIDDECK_XR_SOURCE_RUNTIME_ACTION;
    xrHand.grip.flags = xrHand.aim.flags = 15;
    xrHand.status_bits = 31; xrHand.ready_bits = xrHand.capability_bits = DROIDDECK_XR_PINCH | DROIDDECK_XR_GRASP;
    xrHand.grip.position[0] = .3f;
    xrHand.aim.position[0] = 2;
    controller.UpdateXrHand(xrPacket, xrSkeleton, now, false);
    controller.UpdateXrHand(xrPacket, xrSkeleton, now, false);
    xrHand.pinch = 1;
    controller.UpdateXrHand(xrPacket, xrSkeleton, now, false);
    assert(context.input.values.at(xrClick) == 1 && context.input.values.at(xrTrigger) == 1);
    assert(controller.GetPose().vecPosition[0] == xrHand.grip.position[0]);
    const auto systemHandle = context.input.handles.at("/input/system/click");
    controller.UpdateXrHand(xrPacket, xrSkeleton, now, true, true);
    assert(context.input.values.at(xrClick) == 0 && context.input.values.at(xrTrigger) == 0);
    assert(context.input.values.at(systemHandle) == 1);
    controller.UpdateXrHand(xrPacket, xrSkeleton, now, false, true);
    assert(context.input.values.at(systemHandle) == 0 && context.input.values.at(xrTrigger) == 0);
    xrHand.select = 1;
    xrHand.pinch = 0;
    controller.UpdateXrHand(xrPacket, xrSkeleton, now, false);
    assert(context.input.values.at(xrClick) == 0);
    xrHand.pinch = 1;
    controller.UpdateXrHand(xrPacket, xrSkeleton, now, false);
    assert(context.input.values.at(xrClick) == 1);
    xrPacket.flags &= ~DROIDDECK_XR_INPUT_FOCUSED;
    controller.UpdateXrHand(xrPacket, xrSkeleton, now, false);
    assert(context.host.inputsAtPose.back().at(xrClick) == 0 && !controller.GetPose().poseIsValid);
    xrPacket.flags |= DROIDDECK_XR_INPUT_FOCUSED;
    controller.UpdateXrHand(xrPacket, xrSkeleton, now, false);
    assert(context.input.values.at(xrClick) == 0 && context.input.values.at(xrTrigger) == 0);
    xrHand.pinch = 0; controller.UpdateXrHand(xrPacket, xrSkeleton, now, false);
    xrHand.pinch = 1; controller.UpdateXrHand(xrPacket, xrSkeleton, now, false);
    assert(context.input.values.at(xrClick) == 1);
    controller.UpdateXrHand(xrPacket, xrSkeleton, now+100000001, false);
    assert(context.input.values.at(xrClick) == 0 && !controller.GetPose().poseIsValid);
    controller.UpdateXrHand(xrPacket, xrSkeleton, now, false);
    assert(context.input.values.at(xrClick) == 0);
    xrHand.pinch = 0; controller.UpdateXrHand(xrPacket, xrSkeleton, now, false);
    xrHand.pinch = 1; controller.UpdateXrHand(xrPacket, xrSkeleton, now, false);
    assert(context.input.values.at(xrClick) == 1);
    xrHand.grip.flags = 7;
    controller.UpdateXrHand(xrPacket, xrSkeleton, now, false);
    assert(context.input.values.at(xrClick) == 0 && !controller.GetPose().poseIsValid);
    xrHand = {};
    controller.UpdateXrHand(xrPacket, xrSkeleton, now, true, true);
    assert(controller.GetPose().deviceIsConnected && !controller.GetPose().poseIsValid);
    assert(context.input.values.at(systemHandle) == 1);
    controller.UpdateXrHand(xrPacket, xrSkeleton, now, false);
    assert(context.input.values.at(systemHandle) == 0);
    controller.UpdateXrHand(xrPacket, xrSkeleton, now + 100000001, true);
    assert(context.input.values.at(systemHandle) == 0 && !controller.GetPose().deviceIsConnected);
    controller.Deactivate();
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
    Context xrContext;
    VR_CLEANUP_SERVER_DRIVER_CONTEXT();
    VRDriverContext() = &xrContext;
    CardboardController trackedHand(false);
    assert(trackedHand.ConfigureAndroidXr());
    assert(trackedHand.Activate(3) == VRInitError_None);
    assert(xrContext.input.skeletonUpdates == 2);
    const auto handTrigger = xrContext.input.handles.at("/input/trigger/value");
    const auto handProfile = xrContext.properties.strings.at({4, Prop_InputProfilePath_String});
    assert(handProfile == "{indexcontroller}/input/index_controller_profile.json");
    assert(xrContext.input.handles.count("/input/thumbstick/x"));
    assert(!trackedHand.ConfigureAndroidXr());
    auto handPacket = droiddeck_xr_input_v2_neutral(77, 1);
    handPacket.sample_monotonic_ns = 2000000000;
    handPacket.flags = DROIDDECK_XR_INPUT_FOCUSED | DROIDDECK_XR_INPUT_HAND_MODE;
    auto &sample = handPacket.hands[0];
    sample.source = DROIDDECK_XR_SOURCE_RUNTIME_ACTION;
    sample.grip.flags = sample.aim.flags = 15;
    sample.status_bits = DROIDDECK_XR_GRIP_ACTIVE | DROIDDECK_XR_AIM_ACTIVE | DROIDDECK_XR_PINCH_ACTIVE;
    sample.capability_bits = sample.ready_bits = DROIDDECK_XR_PINCH;
    DroidDeckXrSkeleton noSkeleton{};
    trackedHand.UpdateXrHand(handPacket, noSkeleton, 2000000000, false);
    trackedHand.UpdateXrHand(handPacket, noSkeleton, 2000000000, false);
    sample.pinch = 1;
    trackedHand.UpdateXrHand(handPacket, noSkeleton, 2000000000, false);
    assert(xrContext.input.values.at(handTrigger) == 1);
    noSkeleton.session_generation = 77;
    noSkeleton.origin_epoch = 1;
    noSkeleton.sample_monotonic_ns = 2000000000;
    noSkeleton.active[0] = 1;
    for (int joint = 0; joint < 26; ++joint) {
        noSkeleton.joints[0][joint] = sample.grip;
        noSkeleton.joints[0][joint].position[0] = 0.01f * joint;
    }
    trackedHand.UpdateXrHand(handPacket, noSkeleton, 2000000000, false);
    assert(xrContext.input.skeletonUpdates == 4);
    assert(xrContext.properties.strings.at({4, Prop_RenderModelName_String}) == "{droiddeck}droiddeck_hand_left");
    handPacket.flags |= DROIDDECK_XR_INPUT_LOCAL_HAND_MESH;
    trackedHand.UpdateXrHand(handPacket, noSkeleton, 2000000000, false);
    assert(xrContext.properties.strings.at({4, Prop_RenderModelName_String}) == "{droiddeck}droiddeck_hand_left_xr");
    handPacket.flags &= ~DROIDDECK_XR_INPUT_LOCAL_HAND_MESH;
    trackedHand.UpdateXrHand(handPacket, noSkeleton, 2000000000, false);
    assert(xrContext.properties.strings.at({4, Prop_RenderModelName_String}) == "{droiddeck}droiddeck_hand_left");
    assert(!xrContext.input.poses.empty());
    trackedHand.Disconnect();
    assert(xrContext.input.values.at(handTrigger) == 0);
    assert(xrContext.properties.strings.at({4, Prop_InputProfilePath_String}) == handProfile);
    puts("PASS: Android XR hand profile stays immutable and disconnect releases held input");

    xrContext.input.handles.clear();
    CardboardController galaxyController(false);
    assert(galaxyController.ConfigureAndroidXr(false));
    assert(galaxyController.Activate(4) == VRInitError_None);
    assert(!galaxyController.ConfigureAndroidXr());
    const auto controllerProfile = xrContext.properties.strings.at({5, Prop_InputProfilePath_String});
    assert(controllerProfile == "{oculus}/input/touch_profile.json");
    assert(xrContext.properties.strings.at({5, Prop_ControllerType_String}) == "oculus_touch");
    assert(xrContext.properties.strings.at({4, Prop_ControllerType_String}) == "knuckles");
    const auto &controllerHandles = xrContext.input.handles;
    assert(controllerHandles.count("/input/joystick/x") && controllerHandles.count("/input/x/click"));
    assert(!controllerHandles.count("/input/thumbstick/x") && !controllerHandles.count("/input/skeleton/left"));
    assert(galaxyController.Haptic() && !trackedHand.Haptic());
    const auto controllerTrigger = controllerHandles.at("/input/trigger/value");
    const auto controllerThumbrest = controllerHandles.at("/input/thumbrest/touch");
    const auto controllerX = controllerHandles.at("/input/x/click");
    DroidDeckXrHand controllerState{};
    controllerState.active = 1;
    controllerState.pose.flags = 15;
    controllerState.pose.orientation[3] = 1;
    controllerState.trigger = 1;
    controllerState.buttons = DROIDDECK_XR_PRIMARY_CLICK | DROIDDECK_XR_THUMBREST_TOUCH;

    CardboardHmd routing;
    routing.SetHands(&trackedHand, nullptr);
    assert(routing.ActiveHand(0) == &trackedHand);
    sample.pinch = 0;
    trackedHand.UpdateXrHand(handPacket, noSkeleton, 2000000000, false);
    trackedHand.UpdateXrHand(handPacket, noSkeleton, 2000000000, false);
    sample.pinch = 1;
    trackedHand.UpdateXrHand(handPacket, noSkeleton, 2000000000, false);
    assert(xrContext.input.values.at(handTrigger) == 1);
    routing.SetHands(&galaxyController, nullptr);
    assert(routing.ActiveHand(0) == &galaxyController);
    assert(!trackedHand.GetPose().deviceIsConnected && xrContext.input.values.at(handTrigger) == 0);
    galaxyController.UpdateXr(controllerState, 0);
    assert(galaxyController.GetPose().poseIsValid);
    assert(xrContext.input.values.at(controllerTrigger) == 1);
    assert(xrContext.input.values.at(controllerX) == 1 && xrContext.input.values.at(controllerThumbrest) == 1);
    routing.SetHands(&trackedHand, nullptr);
    assert(routing.ActiveHand(0) == &trackedHand);
    assert(!galaxyController.GetPose().deviceIsConnected && xrContext.input.values.at(controllerTrigger) == 0);
    assert(xrContext.input.values.at(controllerX) == 0 && xrContext.input.values.at(controllerThumbrest) == 0);
    trackedHand.UpdateXrHand(handPacket, noSkeleton, 2000000000, false);
    assert(xrContext.input.values.at(handTrigger) == 0);
    assert(xrContext.properties.strings.at({4, Prop_InputProfilePath_String}) == handProfile);
    assert(xrContext.properties.strings.at({5, Prop_InputProfilePath_String}) == controllerProfile);
    assert(!xrContext.input.values.count(0));
    puts("PASS: Galaxy XR Touch and tracked hands keep separate profiles across switches and release held input");

}
