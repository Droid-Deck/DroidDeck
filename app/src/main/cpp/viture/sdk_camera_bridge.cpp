#include "sdk_loader.h"
#include "mjpeg_payload.h"
#include <jni.h>
#include <memory>
#include <mutex>
#include <vector>

namespace {
struct CameraStream {
    const VitureSdkApi* api;
    XRCameraProviderHandle handle = nullptr;
    std::mutex mutex;
    std::vector<uint8_t> frame;
    bool accepting = true;
};

std::mutex streams_mutex;
std::shared_ptr<CameraStream> stream;
jlong stream_token = 0;
jlong next_token = 1;

void receive_frame(const XRCameraFrame* frame, void* user) {
    if (!frame || frame->format != XR_CAMERA_FORMAT_MJPEG ||
        frame->width == 0 || frame->width > 1920 || frame->height == 0 || frame->height > 1080) return;
    const auto size = mjpeg_payload_size(frame->data, frame->size);
    if (!size) return;
    auto& camera = *static_cast<CameraStream*>(user);
    std::lock_guard<std::mutex> lock(camera.mutex);
    if (camera.accepting) camera.frame.assign(frame->data, frame->data + size);
}

void close_stream(const std::shared_ptr<CameraStream>& camera) {
    {
        std::lock_guard<std::mutex> lock(camera->mutex);
        camera->accepting = false;
        camera->frame.clear();
    }
    camera->api->xr_camera_provider_stop(camera->handle);
    // The SDK guarantees that stop has joined its frame callbacks before returning.
    camera->api->xr_camera_provider_destroy(camera->handle);
}
}

#define CAMERA_JNI(name) Java_com_droiddeck_launcher_glasses_VitureCamera_##name

extern "C" JNIEXPORT jlong JNICALL CAMERA_JNI(nativeOpen)(JNIEnv*, jobject, jint vid, jint pid, jint fd) {
    const auto* api = viture_loaded_api();
    if (!api || fd < 0 || !api->xr_camera_provider_is_valid_camera(vid, pid)) return -1;
    std::lock_guard<std::mutex> lock(streams_mutex);
    if (stream) return -2;
    auto camera = std::make_shared<CameraStream>();
    camera->api = api;
    camera->handle = api->xr_camera_provider_create(vid, pid, fd);
    if (!camera->handle) return -3;
    const auto result = api->xr_camera_provider_start(camera->handle, receive_frame, camera.get());
    if (result != 0) {
        close_stream(camera);
        return result < 0 ? result : -4;
    }
    stream = std::move(camera);
    stream_token = next_token++;
    return stream_token;
}

extern "C" JNIEXPORT jbyteArray JNICALL CAMERA_JNI(nativeFrame)(JNIEnv* env, jobject, jlong token) {
    std::shared_ptr<CameraStream> camera;
    {
        std::lock_guard<std::mutex> lock(streams_mutex);
        if (!stream || token != stream_token) return nullptr;
        camera = stream;
    }
    std::vector<uint8_t> bytes;
    {
        std::lock_guard<std::mutex> lock(camera->mutex);
        bytes.swap(camera->frame);
    }
    if (bytes.empty()) return nullptr;
    auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()),
        reinterpret_cast<const jbyte*>(bytes.data()));
    return result;
}

extern "C" JNIEXPORT void JNICALL CAMERA_JNI(nativeClose)(JNIEnv*, jobject, jlong token) {
    std::lock_guard<std::mutex> lock(streams_mutex);
    if (!stream || token != stream_token) return;
    close_stream(stream);
    stream.reset();
    stream_token = 0;
}
