#include "../../main/cpp/viture/sdk_loader.h"
#include <jni.h>
#include <cassert>
#include <vector>

extern "C" jlong Java_com_droiddeck_launcher_glasses_VitureCamera_nativeOpen(JNIEnv*, jobject, jint, jint, jint);
extern "C" jbyteArray Java_com_droiddeck_launcher_glasses_VitureCamera_nativeFrame(JNIEnv*, jobject, jlong);
extern "C" void Java_com_droiddeck_launcher_glasses_VitureCamera_nativeClose(JNIEnv*, jobject, jlong);

namespace {
VitureSdkApi api;
bool loaded = true;
int start_result = 0;
int stopped = 0;
int destroyed = 0;
XRCameraFrameCallback callback = nullptr;
void* callback_data = nullptr;
int handle = 1;

int valid(int vid, int pid) { return vid == 1 && pid == 2; }
XRCameraProviderHandle create(int, int, int) { return &handle; }
int start(XRCameraProviderHandle, XRCameraFrameCallback receive, void* data) {
    callback = receive;
    callback_data = data;
    return start_result;
}
void deliver(std::vector<uint8_t> bytes, int format = XR_CAMERA_FORMAT_MJPEG, uint32_t width = 1920) {
    XRCameraFrame frame{bytes.data(), static_cast<uint32_t>(bytes.size()), width, 1080, format, 0, 0};
    callback(&frame, callback_data);
}
int stop(XRCameraProviderHandle) {
    // A callback completing during stop must not publish another frame.
    deliver({0xff, 0xd8, 0x22, 0xff, 0xd9});
    ++stopped;
    return 0;
}
void destroy(XRCameraProviderHandle) { ++destroyed; callback = nullptr; callback_data = nullptr; }
}

const VitureSdkApi* viture_loaded_api() { return loaded ? &api : nullptr; }

int main() {
    api.xr_camera_provider_is_valid_camera = valid;
    api.xr_camera_provider_create = create;
    api.xr_camera_provider_start = start;
    api.xr_camera_provider_stop = stop;
    api.xr_camera_provider_destroy = destroy;
    JNIEnv env;
    auto open = [&] { return Java_com_droiddeck_launcher_glasses_VitureCamera_nativeOpen(&env, nullptr, 1, 2, 3); };
    auto read = [&](jlong token) { return Java_com_droiddeck_launcher_glasses_VitureCamera_nativeFrame(&env, nullptr, token); };
    auto close = [&](jlong token) { Java_com_droiddeck_launcher_glasses_VitureCamera_nativeClose(&env, nullptr, token); };
    loaded = false;
    assert(open() < 0);
    loaded = true;
    assert(Java_com_droiddeck_launcher_glasses_VitureCamera_nativeOpen(&env, nullptr, 3, 4, 3) < 0);
    assert(Java_com_droiddeck_launcher_glasses_VitureCamera_nativeOpen(&env, nullptr, 1, 2, -1) < 0);
    const auto token = open();
    assert(token > 0);
    assert(open() < 0);
    assert(!read(token));
    deliver({0, 1, 2, 3});
    deliver({0xff, 0xd8, 0x11, 0xff, 0xd9}, 1);
    deliver({0xff, 0xd8, 0x11, 0xff, 0xd9}, 0, 65535);
    assert(!read(token));
    deliver({0xff, 0xd8, 0x11, 0xff, 0xd9});
    deliver({0xff, 0xd8, 0x22, 0xff, 0xd9, 0, 0});
    auto bytes = read(token);
    assert(bytes && bytes->size() == 5 && (*bytes)[2] == 0x22);
    delete bytes;
    assert(!read(token));
    close(token);
    assert(stopped == 1 && destroyed == 1);
    assert(!read(token));
    close(token);
    assert(stopped == 1 && destroyed == 1);
    start_result = -5;
    assert(open() == -5);
    assert(stopped == 2 && destroyed == 2);
    start_result = 0;
    const auto replacement = open();
    assert(replacement > 0 && replacement != token);
    close(token);
    deliver({0xff, 0xd8, 0x33, 0xff, 0xd9});
    assert(!read(token));
    bytes = read(replacement);
    assert(bytes && (*bytes)[2] == 0x33);
    delete bytes;
    close(replacement);
    assert(stopped == 3 && destroyed == 3);
}
