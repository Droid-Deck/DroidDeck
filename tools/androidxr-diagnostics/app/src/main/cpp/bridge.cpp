#include <jni.h>
#include "shared/xr/core.h"
#include "tools/vr/src/xr_tracking_file.h"
#ifdef DROIDDECK_XR_FIXTURES
#include "shared/xr/tests.h"
#endif
#include <sstream>
#include <vector>

using namespace droiddeck::xr;
struct Bridge { Core core; Output last; };
static jstring json(JNIEnv *env, const std::string &value) { return env->NewStringUTF(value.c_str()); }
static std::string report(const Output &out) {
    std::ostringstream s;
    auto pose = [&](const Pose &p) {
        s << "{\"flags\":" << p.flags << ",\"meters\":[" << p.meters[0] << ',' << p.meters[1] << ',' << p.meters[2]
          << "],\"xyzw\":[" << p.xyzw[0] << ',' << p.xyzw[1] << ',' << p.xyzw[2] << ',' << p.xyzw[3] << "]}";
    };
    s << "{\"provider\":" << int(out.provider) << ",\"session\":" << out.session << ",\"origin\":" << out.origin
      << ",\"ageNs\":" << out.ageNs << ",\"reason\":\"" << out.reason << "\",\"capabilities\":" << out.capabilities
      << ",\"predictedNs\":" << out.packet.predicted_ns << ",\"head\":";
    pose(out.head); s << ",\"hands\":[";
    for (int i = 0; i < 2; ++i) {
        const auto &h = out.hands[i];
        s << (i ? "," : "") << "{\"source\":" << int(h.source) << ",\"active\":" << (h.active ? "true" : "false")
          << ",\"pinch\":" << (h.pinch.down ? "true" : "false") << ",\"pinchPressed\":" << (h.pinch.pressed ? "true" : "false")
          << ",\"pinchReleased\":" << (h.pinch.released ? "true" : "false") << ",\"grasp\":" << (h.grasp.down ? "true" : "false")
          << ",\"graspPressed\":" << (h.grasp.pressed ? "true" : "false") << ",\"graspReleased\":" << (h.grasp.released ? "true" : "false") << ",\"pose\":";
        pose(h.grip); s << '}';
    }
    s << "]}"; return s.str();
}
extern "C" JNIEXPORT jlong JNICALL Java_com_droiddeck_xrdiagnostics_NativeBridge_create(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new Bridge);
}
extern "C" JNIEXPORT void JNICALL Java_com_droiddeck_xrdiagnostics_NativeBridge_destroy(JNIEnv *, jobject, jlong id) {
    delete reinterpret_cast<Bridge *>(id);
}
extern "C" JNIEXPORT void JNICALL Java_com_droiddeck_xrdiagnostics_NativeBridge_begin(JNIEnv *, jobject, jlong id, jint provider, jlong session, jlong origin) {
    reinterpret_cast<Bridge *>(id)->core.begin(static_cast<Provider>(provider), session, origin);
}
extern "C" JNIEXPORT jstring JNICALL Java_com_droiddeck_xrdiagnostics_NativeBridge_update(
    JNIEnv *env, jobject, jlong id, jlong session, jlong origin, jlong sampled, jlong now,
    jboolean connected, jboolean focused, jfloatArray values, jint capabilities) {
    if (env->GetArrayLength(values) != 30) return json(env, "{\"error\":\"expected 30 sample values\"}");
    float v[30]; env->GetFloatArrayRegion(values, 0, 30, v);
    Sample s; s.provider = Provider::Jetpack; s.session = session; s.origin = origin;
    s.sampled = {sampled, Clock::Monotonic}; s.connected = connected; s.focused = focused;
    s.capabilities = capabilities;
    auto pose = [&](int offset) {
        return Pose{{v[offset], v[offset+1], v[offset+2]}, {v[offset+3], v[offset+4], v[offset+5], v[offset+6]},
            std::isfinite(v[offset+7]) && v[offset+7] >= 0 && v[offset+7] <= 15 ? uint32_t(v[offset+7]) : 0u};
    };
    s.head = pose(0);
    for (int side = 0; side < 2; ++side) {
        auto &h = s.hands[side]; h.grip = pose(8 + 8*side);
        h.available = v[28 + side] > 0;
        h.source = h.grip.flags ? Source::JetpackJoints : Source::JetpackInteraction;
        if (std::isfinite(v[24 + side*2])) h.pinch = v[24 + side*2];
        if (std::isfinite(v[25 + side*2])) h.grasp = v[25 + side*2];
    }
    auto *b = reinterpret_cast<Bridge *>(id);
    b->last = b->core.update(s, {now, Clock::Monotonic});
    return json(env, report(b->last));
}
extern "C" JNIEXPORT jstring JNICALL Java_com_droiddeck_xrdiagnostics_NativeBridge_fixture(JNIEnv *env, jobject, jlong id, jint step, jlong now) {
#ifdef DROIDDECK_XR_FIXTURES
    auto *b = reinterpret_cast<Bridge *>(id);
    auto s = droiddeck::xr::fixture(step, now);
    if (step == 0 || step == 7 || step == 11) b->core.begin(Provider::Fixture, s.session, s.origin);
    b->last = b->core.update(s, {now, Clock::Monotonic});
    auto value = report(b->last);
    value.pop_back();
    return json(env, value + ",\"fixtureStep\":" + std::to_string(step) + ",\"fixtureScenario\":\"" + fixtureName(step) + "\"}");
#else
    (void)id; (void)step; (void)now;
    return json(env, "{\"error\":\"fixtures excluded from release\"}");
#endif
}
extern "C" JNIEXPORT jstring JNICALL Java_com_droiddeck_xrdiagnostics_NativeBridge_tests(JNIEnv *env, jobject, jstring directory) {
#ifdef DROIDDECK_XR_FIXTURES
    const char *path = env->GetStringUTFChars(directory, nullptr);
    std::string dir(path); env->ReleaseStringUTFChars(directory, path);
    try { return json(env, "{\"source\":\"synthetic\",\"passed\":true,\"checks\":" + std::to_string(runTests(dir)) + "}"); }
    catch (const std::exception &) { return json(env, "{\"source\":\"synthetic\",\"passed\":false}"); }
#else
    (void)directory;
    return json(env, "{\"error\":\"fixtures excluded from release\"}");
#endif
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_droiddeck_xrdiagnostics_NativeBridge_roundTrip(JNIEnv *env, jobject, jlong id, jstring directory) {
    const char *path = env->GetStringUTFChars(directory, nullptr);
    std::string name = std::string(path) + "/xr-live-XXXXXX"; env->ReleaseStringUTFChars(directory, path);
    std::vector<char> filename(name.begin(), name.end()); filename.push_back(0);
    int fd = mkstemp(filename.data()); if (fd < 0) return false;
    bool ok = false;
    if (!ftruncate(fd, sizeof(DroidDeckXrTracking))) {
        auto *mapping = static_cast<DroidDeckXrTracking *>(mmap(nullptr, sizeof(DroidDeckXrTracking), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0));
        if (mapping != MAP_FAILED) {
            auto packet = reinterpret_cast<Bridge *>(id)->last.packet;
            publish(mapping, packet);
            XrTrackingFile reader(filename.data()); DroidDeckXrTracking read{};
            ok = reader.Read(&read); packet.seq = read.seq;
            ok = ok && !std::memcmp(&packet, &read, sizeof(packet));
            munmap(mapping, sizeof(DroidDeckXrTracking));
        }
    }
    close(fd); unlink(filename.data()); return ok;
}
