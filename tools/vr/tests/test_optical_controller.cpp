#include "../src/driver_droiddeck.cpp"
#include <array>
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
    std::map<std::pair<PropertyContainerHandle_t, ETrackedDeviceProperty>, std::string> strings;
    size_t writes = 0;
    ETrackedPropertyError ReadPropertyBatch(PropertyContainerHandle_t, PropertyRead_t *, uint32_t) override { return TrackedProp_Success; }
    ETrackedPropertyError WritePropertyBatch(PropertyContainerHandle_t container, PropertyWrite_t *batch, uint32_t count) override {
        writes += count;
        for (uint32_t i = 0; i < count; ++i)
            if (batch[i].unTag == k_unStringPropertyTag)
                strings[{container, batch[i].prop}] = static_cast<const char *>(batch[i].pvBuffer);
        return TrackedProp_Success;
    }
    const char *GetPropErrorNameFromEnum(ETrackedPropertyError) override { return "success"; }
    PropertyContainerHandle_t TrackedDeviceToPropertyContainer(TrackedDeviceIndex_t id) override { return id+1; }
};
struct Input : IVRDriverInput {
    std::map<VRInputComponentHandle_t, float> values;
    std::map<std::string, VRInputComponentHandle_t> handles;
    std::map<VRInputComponentHandle_t, EVRInputError> failures;
    std::map<VRInputComponentHandle_t, HmdMatrix34_t> poses;
    size_t poseUpdates = 0;
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
    EVRInputError CreatePoseComponent(PropertyContainerHandle_t c, const char *n, VRInputComponentHandle_t *h) override {
        return CreateBooleanComponent(c, n, h);
    }
    EVRInputError UpdatePoseComponent(VRInputComponentHandle_t h, const HmdMatrix34_t *pose, double) override {
        assert(h != 0); poses[h] = *pose; ++poseUpdates; return VRInputError_None;
    }
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

    char filename[] = "/tmp/droiddeck-xr-controller-XXXXXX";
    const int fd = mkstemp(filename);
    assert(fd >= 0);
    DroidDeckXrTracking tracking{};
    tracking.magic = DROIDDECK_XR_MAGIC; tracking.version = DROIDDECK_XR_VERSION;
    tracking.active = 1; tracking.sampled_ns = XrTrackingFile::Now(); tracking.period_ns = 11111111;
    tracking.eyes[0].width = tracking.eyes[0].height = 1024;
    assert(write(fd, &tracking, sizeof(tracking)) == sizeof(tracking));
    const auto property = [&](int id, ETrackedDeviceProperty prop) {
        return context.properties.strings.at({id + 1, prop});
    };
    for (bool right : {false, true}) {
        CardboardController xr(right, filename);
        assert(xr.Activate(3) == VRInitError_None && xr.XrMode());
        assert(property(3, Prop_RenderModelName_String).find("droiddeck_hand_") != std::string::npos);
        assert(property(3, Prop_ControllerType_String) == "oculus_touch");
        DroidDeckXrHand hand{};
        hand.active = 1; hand.profile = DROIDDECK_XR_CONTROLLER_PICO4S;
        hand.pose = {{0,0,0,1}, {1,2,3}, 15};
        hand.aim = {{0,0,0,1}, {1.01f,2.02f,2.97f}, 15};
        xr.UpdateXr(hand, 0);
        const auto grip = context.input.handles.at("/pose/grip"), tip = context.input.handles.at("/pose/tip");
        assert(property(3, Prop_ModelNumber_String) == "PICO 4 Ultra Controller");
        assert(property(3, Prop_RenderModelName_String) ==
               std::string("{droiddeck}droiddeck_pico_4u_") + (right ? "right" : "left"));
        assert(property(3, Prop_ManufacturerName_String) == "PICO");
        assert(xr.GetPose().vecPosition[0] == 1 && xr.GetPose().vecPosition[1] == 2);
        assert(xr.GetPose().vecPosition[2] == 3 && xr.GetPose().qRotation.w == 1);
        const auto writes = context.properties.writes;
        const auto assertAim = [&] {
            const auto &raw = xr.GetPose();
            DroidDeckXrPose corrected{{float(raw.qRotation.x), float(raw.qRotation.y), float(raw.qRotation.z), float(raw.qRotation.w)},
                                     {float(raw.vecPosition[0]), float(raw.vecPosition[1]), float(raw.vecPosition[2])}, 15};
            const auto rawMatrix = XrPoseMatrix(corrected), aimMatrix = XrPoseMatrix(hand.aim);
            const auto &local = context.input.poses.at(tip);
            for (int row = 0; row < 3; ++row) for (int column = 0; column < 4; ++column) {
                double world = column == 3 ? rawMatrix.m[row][3] : 0;
                for (int inner = 0; inner < 3; ++inner) world += rawMatrix.m[row][inner] * local.m[inner][column];
                assert(std::abs(world - aimMatrix.m[row][column]) < 1e-5);
            }
            for (const auto handle : {grip, context.input.handles.at("/pose/handgrip"),
                                     context.input.handles.at("/pose/openxr_grip")}) {
                const auto &gripPose = context.input.poses.at(handle);
                assert(gripPose.m[0][0] == 1 && gripPose.m[1][1] == 1 && gripPose.m[2][2] == 1);
                assert(gripPose.m[0][3] == 0 && gripPose.m[1][3] == 0 && gripPose.m[2][3] == 0);
            }
            const auto &openxrAim = context.input.poses.at(context.input.handles.at("/pose/openxr_aim"));
            assert(!std::memcmp(&openxrAim, &local, sizeof(local)));
            const auto *asset = XrProfileAsset(hand.profile, right);
            size_t bodies = 0;
            for (size_t part = 0; part < asset->partCount; ++part) {
                bool animated = false;
                for (int node = asset->parts[part].node; node >= 0; node = asset->nodes[node].parent)
                    animated |= asset->nodes[node].channel != ProfileChannel::None;
                if (animated) continue;
                ++bodies;
                const auto &body = context.input.poses.at(context.input.handles.at("/pose/profile_" + std::to_string(part)));
                for (int row = 0; row < 3; ++row) for (int column = 0; column < 4; ++column) {
                    double world = column == 3 ? rawMatrix.m[row][3] : 0;
                    for (int inner = 0; inner < 3; ++inner) world += rawMatrix.m[row][inner] * body.m[inner][column];
                    assert(std::abs(world - aimMatrix.m[row][column]) < 1e-5);
                }
            }
            assert(bodies > 0);
        };
        assertAim();
        const auto angle = float(std::sqrt(.5));
        hand.pose.orientation[1] = angle; hand.pose.orientation[3] = angle;
        xr.UpdateXr(hand, 0);
        assert(std::abs(xr.GetPose().vecPosition[0] - 1) < 1e-6);
        assert(xr.GetPose().vecPosition[2] == 3);
        assert(std::abs(xr.GetPose().qRotation.y - angle) < 1e-6);
        assertAim();
        assert(context.properties.writes == writes);
        const auto updates = context.input.poseUpdates;
        hand.aim.flags = 0;
        xr.UpdateXr(hand, 0);
        assert(context.input.poseUpdates == updates + 3 + XrProfileAsset(hand.profile, right)->partCount);
        hand.active = 0; hand.profile = DROIDDECK_XR_CONTROLLER_UNKNOWN;
        xr.UpdateXr(hand, 0);
        assert(!xr.GetPose().poseIsValid && context.properties.writes == writes);
        hand.active = 1; hand.profile = DROIDDECK_XR_CONTROLLER_PICO_NEO3;
        hand.pose.orientation[1] = 0; hand.pose.orientation[3] = 1;
        xr.UpdateXr(hand, 0);
        assert(property(3, Prop_ModelNumber_String) == "PICO Neo3 Controller");
        assert(xr.GetPose().vecPosition[0] == 1 && xr.GetPose().vecPosition[1] == 2);
        assert(xr.GetPose().qRotation.w == 1);
        hand.aim.flags = 15;
        hand.aim.orientation[0] = std::sin(-.2f); hand.aim.orientation[3] = std::cos(-.2f);
        xr.UpdateXr(hand, 0);
        assertAim();
        assert(xr.GetPose().qRotation.w == 1 && xr.GetPose().vecPosition[2] == 3);
        hand.aim.flags = 0;
        hand.profile = DROIDDECK_XR_CONTROLLER_PICO4;
        hand.stick[0] = .6f; hand.stick[1] = -.4f;
        xr.UpdateXr(hand, 0);
        assert(property(3, Prop_ModelNumber_String) == "PICO 4 Controller");
        assert(context.input.values.at(context.input.handles.at("/input/joystick/x")) == hand.stick[0]);
        assert(context.input.values.at(context.input.handles.at("/input/joystick/y")) == hand.stick[1]);
        hand.profile = DROIDDECK_XR_CONTROLLER_TOUCH;
        const auto touchUpdates = context.input.poseUpdates;
        xr.UpdateXr(hand, 0);
        assert(property(3, Prop_RenderModelName_String) ==
               std::string("oculus_quest_plus_controller_") + (right ? "right" : "left"));
        assert(xr.GetPose().vecPosition[0] == 1 && xr.GetPose().vecPosition[1] == 2 && xr.GetPose().qRotation.w == 1);
        assert(context.input.poseUpdates == touchUpdates + 3);
        hand.pose.flags = 0;
        xr.UpdateXr(hand, 0);
        assert(!xr.GetPose().poseIsValid);
    }
    char modelFolder[] = "/tmp/droiddeck-xr-model-space-XXXXXX";
    assert(mkdtemp(modelFolder));
    const auto root = std::filesystem::path(modelFolder);
    std::filesystem::create_directory(root / "installed");
    for (bool right : {false, true}) for (uint32_t profile : {
            DROIDDECK_XR_CONTROLLER_PICO4S, DROIDDECK_XR_CONTROLLER_PICO4,
            DROIDDECK_XR_CONTROLLER_PICO_NEO3, DROIDDECK_XR_CONTROLLER_TOUCH,
            DROIDDECK_XR_CONTROLLER_SIMPLE, DROIDDECK_XR_CONTROLLER_UNKNOWN}) {
        CardboardController xr(right, (root / DROIDDECK_XR_FILE).c_str(), (root / "installed").c_str());
        std::filesystem::copy_file(filename, root / DROIDDECK_XR_FILE, std::filesystem::copy_options::overwrite_existing);
        assert(xr.Activate(3) == VRInitError_None && xr.XrMode());
        DroidDeckXrHand hand{};
        hand.active = 1; hand.profile = profile;
        hand.model_key[0] = right ? 2 : 1;
        hand.pose = {{0, 0, 0, 1}, {1, 2, 3}, 15};
        hand.aim = {{0, 0, 0, 1}, {1.1f, 2.2f, 2.7f}, 15};
        const float half = float(std::sqrt(.5));
        hand.model = {{0, half, 0, half}, {1.02f, 2.03f, 2.96f}, 15};
        const auto name = XrRuntimeModelName(hand.model_key, right);
        std::filesystem::create_directories(root / "xr-models" / name);
        FILE *descriptor = fopen((root / "xr-models" / name / (name + ".json")).c_str(), "w");
        assert(descriptor); fputs("{}", descriptor); fclose(descriptor);
        xr.UpdateXr(hand, 0);
        assert(property(3, Prop_RenderModelName_String) == "{droiddeck}" + name);
        assert(std::abs(xr.GetPose().vecPosition[0] - hand.model.position[0]) < 1e-7);
        assert(std::filesystem::is_symlink(root / "installed" / name));
        const auto assertNativeComponents = [&] {
            const auto driver = xr.GetPose();
            const DroidDeckXrPose rendered{
                {float(driver.qRotation.x), float(driver.qRotation.y), float(driver.qRotation.z), float(driver.qRotation.w)},
                {float(driver.vecPosition[0]), float(driver.vecPosition[1]), float(driver.vecPosition[2])}, 15};
            const auto raw = XrPoseMatrix(rendered);
            for (const auto &component : std::array<std::pair<const char *, DroidDeckXrPose>, 5>{{
                 {"/pose/grip", hand.pose}, {"/pose/handgrip", hand.pose}, {"/pose/openxr_grip", hand.pose},
                 {"/pose/tip", hand.aim}, {"/pose/openxr_aim", hand.aim}}}) {
                if (!XrTrackingFile::Tracked(component.second)) continue;
                const auto world = XrPoseMatrix(component.second);
                const auto local = context.input.poses.at(context.input.handles.at(component.first));
                for (int row = 0; row < 3; ++row) for (int column = 0; column < 4; ++column) {
                    double reconstructed = column == 3 ? raw.m[row][3] : 0;
                    for (int inner = 0; inner < 3; ++inner) reconstructed += raw.m[row][inner] * local.m[inner][column];
                    assert(std::abs(reconstructed - world.m[row][column]) < 1e-5);
                }
            }
        };
        assertNativeComponents();
        XrModelStateFile modelState;
        assert(modelState.open((root / DROIDDECK_XR_MODEL_FILE).string(), true));
        DroidDeckXrModelState animation{};
        animation.sampled_ns = XrTrackingFile::Now();
        const int side = right ? 1 : 0;
        std::memcpy(animation.key[side], hand.model_key, sizeof(hand.model_key));
        animation.count[side] = 1;
        const auto node = XrPoseMatrix({{half, 0, 0, half}, {.003f, -.008f, -.005f}, 15});
        std::memcpy(animation.nodes[side][0].matrix, node.m, sizeof(node.m));
        animation.nodes[side][0].visible = 1;
        modelState.write(animation);
        const auto original = hand;
        std::memcpy(hand.model.position, hand.pose.position, sizeof(hand.model.position));
        hand.aim.orientation[0] = std::sin(-.3f);
        hand.aim.orientation[3] = std::cos(-.3f);
        hand.stick[0] = .7f; hand.stick[1] = -.6f;
        xr.UpdateXr(hand, 0);
        for (int axis = 0; axis < 3; ++axis) {
            const auto &expected = profile == DROIDDECK_XR_CONTROLLER_PICO4S ? hand.aim : hand.model;
            assert(xr.GetPose().vecPosition[axis] == expected.position[axis]);
        }
        assert(xr.GetPose().qRotation.y == half && xr.GetPose().qRotation.w == half);
        assert(xr.GetPose().qRotation.x == 0 && xr.GetPose().poseIsValid);
        assert(context.input.values.at(context.input.handles.at("/input/joystick/x")) == hand.stick[0]);
        assert(context.input.values.at(context.input.handles.at("/input/joystick/y")) == hand.stick[1]);
        const auto &animated = context.input.poses.at(context.input.handles.at("/pose/model_0"));
        assert(!std::memcmp(animated.m, node.m, sizeof(node.m)));
        assertNativeComponents();
        hand.aim.flags = 3;
        xr.UpdateXr(hand, 0);
        const bool stableOrigin = profile == DROIDDECK_XR_CONTROLLER_PICO4S;
        assert(std::abs(xr.GetPose().vecPosition[2] -
                        (stableOrigin ? hand.aim.position[2] : hand.model.position[2])) < 1e-6);
        hand.aim.flags = 15;
        hand.pose.flags = 3;
        xr.UpdateXr(hand, 0);
        assert(xr.GetPose().vecPosition[2] ==
               (stableOrigin ? hand.aim.position[2] : hand.model.position[2]));
        hand.pose.flags = 15;
        if (stableOrigin) {
            const auto localOffset = XrPosition(XrRelativePose(hand.model, hand.aim));
            const auto gripBinding = XrRelativePose(hand.pose, hand.model);
            for (int frame = 0; frame < 120; ++frame) {
                const auto rotation = Normalize(Quat{std::cos(frame*.02), std::sin(frame*.02), 0, 0});
                const auto gripRotation = Normalize(rotation * Conjugate(XrOrientation(gripBinding)));
                hand.model = {{float(rotation.x), float(rotation.y), float(rotation.z), float(rotation.w)},
                              {float(1 + frame*.01), 2, 3}, 15};
                hand.pose = {{float(gripRotation.x), float(gripRotation.y), float(gripRotation.z), float(gripRotation.w)},
                             {hand.model.position[0] + .002f, 2, 3}, 15};
                const auto expected = XrPosition(hand.model) + Rotate(rotation, localOffset);
                hand.aim = {{0, 0, 0, 1}, {float(expected.x), float(expected.y), float(expected.z)}, 15};
                xr.UpdateXr(hand, 0);
                assert(std::abs(xr.GetPose().vecPosition[0] - expected.x) < 1e-5);
                assert(std::abs(xr.GetPose().vecPosition[2] - expected.z) < 1e-5);
                assertNativeComponents();
                hand.aim = {};
                xr.UpdateXr(hand, 0);
                assert(xr.GetPose().poseIsValid);
                assert(std::abs(xr.GetPose().vecPosition[0] - expected.x) < 1e-5);
                assert(std::abs(xr.GetPose().vecPosition[2] - expected.z) < 1e-5);
                assert(xr.GetPose().qRotation.x == hand.model.orientation[0]);
                assertNativeComponents();
            }
            hand = original;
            std::memcpy(hand.model.position, hand.pose.position, sizeof(hand.model.position));
            xr.UpdateXr(hand, 0);
            const auto nativeName = property(3, Prop_RenderModelName_String);
            hand.model.flags = 0;
            xr.UpdateXr(hand, 0);
            assert(xr.GetPose().poseIsValid && property(3, Prop_RenderModelName_String) == nativeName);
            assert(xr.GetPose().vecPosition[2] == hand.aim.position[2]);
            assert(std::abs(xr.GetPose().qRotation.y - half) < 1e-6);
            assertNativeComponents();
            hand.aim.flags = 0;
            xr.UpdateXr(hand, 0);
            assert(xr.GetPose().poseIsValid);
            assert(std::abs(xr.GetPose().vecPosition[2] - hand.aim.position[2]) < 1e-6);
            hand.pose.flags = 0; hand.aim.flags = 15;
            xr.UpdateXr(hand, 0);
            assert(xr.GetPose().poseIsValid && xr.GetPose().vecPosition[2] == hand.aim.position[2]);
            hand.aim.flags = 0;
            xr.UpdateXr(hand, 0);
            assert(!xr.GetPose().poseIsValid && property(3, Prop_RenderModelName_String) == nativeName);
            hand.pose.flags = hand.aim.flags = hand.model.flags = 15;
            xr.UpdateXr(hand, 0);
            assert(xr.GetPose().vecPosition[2] == hand.aim.position[2]);
        }
        std::memcpy(hand.model.position, hand.aim.position, sizeof(hand.model.position));
        xr.UpdateXr(hand, 0);
        assert(xr.GetPose().vecPosition[2] == hand.model.position[2]);
        assert(xr.GetPose().qRotation.y == half);
        assertNativeComponents();
        hand = original;
        if (stableOrigin) std::memcpy(hand.model.position, hand.pose.position, sizeof(hand.model.position));
        hand.model.flags = 0;
        xr.UpdateXr(hand, 0);
        assert(xr.GetPose().poseIsValid);
        assert(xr.GetPose().vecPosition[0] == (stableOrigin ? hand.aim.position[0] : 1));
        assert(property(3, Prop_RenderModelName_String) ==
               (stableOrigin ? "{droiddeck}" + name : XrModel(profile, right).renderModel));
        hand.model.flags = 15;
        xr.UpdateXr(hand, 0);
        assert(property(3, Prop_RenderModelName_String) == "{droiddeck}" + name);
        hand.model_key[0] = 0;
        hand.buttons = DROIDDECK_XR_THUMBREST_TOUCH;
        xr.UpdateXr(hand, 0);
        assert(xr.GetPose().poseIsValid && xr.GetPose().vecPosition[0] == 1);
        assert(context.input.values.at(context.input.handles.at("/input/thumbrest/touch")) == 1);
        hand.model_key[0] = 77;
        xr.UpdateXr(hand, 0);
        assert(xr.GetPose().poseIsValid && xr.GetPose().vecPosition[0] == 1);
        assert(property(3, Prop_RenderModelName_String) == XrModel(profile, right).renderModel);
        if (stableOrigin) {
            hand.model_key[0] = 99;
            const auto replacement = XrRuntimeModelName(hand.model_key, right);
            std::filesystem::create_directories(root / "xr-models" / replacement);
            FILE *file = fopen((root / "xr-models" / replacement / (replacement + ".json")).c_str(), "w");
            assert(file); fputs("{}", file); fclose(file);
            xr.UpdateXrModel(profile, true, hand.model_key);
            hand.model = {{0, half, 0, half}, {1.02f, 2.03f, 2.96f}, 15};
            hand.aim.flags = 0;
            xr.UpdateXr(hand, 0);
            assert(xr.GetPose().vecPosition[2] == hand.model.position[2]);
            assert(property(3, Prop_RenderModelName_String) == "{droiddeck}" + replacement);
        }
    }
    std::filesystem::remove_all(root);
    close(fd); unlink(filename);
    puts("PASS: PICO4S native position origin, preserved rotation/animation/input, other profiles, reconstructed grip/aim and tracking recovery");
    puts("PASS: PICO4S origin continuity across moving pose disagreement, missing Aim/Grip/Model, complete loss, recovery and asset reset");
}
