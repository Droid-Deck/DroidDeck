#include <jni.h>
#include <libusb.h>
#include <libuvc/libuvc.h>
#include "mjpeg_payload.h"
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <string>
#include <vector>
#include <time.h>
#include <unistd.h>

namespace {
struct UsbLog {
    int saved;
    bool hadLevel;
    std::string level;
    explicit UsbLog(int fd) : saved(dup(STDERR_FILENO)), hadLevel(getenv("LIBUSB_DEBUG") != nullptr),
            level(hadLevel ? getenv("LIBUSB_DEBUG") : "") {
        fflush(stderr);
        if (saved >= 0) dup2(fd, STDERR_FILENO);
        setenv("LIBUSB_DEBUG", "3", 1);
        libusb_set_log_cb(nullptr, [](libusb_context*, libusb_log_level, const char* text) {
            fputs(text, stderr);
        }, LIBUSB_LOG_CB_GLOBAL);
    }
    ~UsbLog() {
        libusb_set_log_cb(nullptr, nullptr, LIBUSB_LOG_CB_GLOBAL);
        fflush(stderr);
        if (saved >= 0) { dup2(saved, STDERR_FILENO); close(saved); }
        if (hadLevel) setenv("LIBUSB_DEBUG", level.c_str(), 1); else unsetenv("LIBUSB_DEBUG");
    }
};

struct Capture {
    std::mutex mutex;
    std::condition_variable ready;
    std::vector<uint8_t> jpeg;
    jlong values[14]{};
    bool accepting = true;
};

jlong monotonic_ns() {
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return now.tv_sec*1000000000LL+now.tv_nsec;
}

void finish_observation(Capture& capture) {
    std::lock_guard<std::mutex> lock(capture.mutex);
    if (!capture.accepting) return;
    capture.accepting = false;
    capture.values[12] = monotonic_ns();
    if (capture.values[6]) capture.values[13] = capture.values[12]-capture.values[6];
    capture.values[8] = std::max(capture.values[8],capture.values[13]);
}

void frame_received(uvc_frame_t* frame, void* user) {
    auto& capture = *static_cast<Capture*>(user);
    std::lock_guard<std::mutex> lock(capture.mutex);
    if (!capture.accepting) return;
    ++capture.values[5];
    const auto* bytes = frame ? static_cast<const uint8_t*>(frame->data) : nullptr;
    const size_t size = frame ? frame->data_bytes : 0;
    const size_t jpegSize = frame && frame->frame_format == UVC_FRAME_FORMAT_MJPEG ? mjpeg_payload_size(bytes,size) : 0;
    if (!jpegSize) {
        ++capture.values[10];
        return;
    }
    const jlong time = monotonic_ns();
    capture.values[0]=frame->width; capture.values[1]=frame->height; capture.values[3]=frame->sequence;
    if (!capture.values[7]) capture.values[7]=time;
    if (capture.values[6]) capture.values[8]=std::max(capture.values[8],time-capture.values[6]);
    capture.values[6]=time;
    ++capture.values[9];
    capture.values[11]+=jpegSize;
    capture.jpeg.assign(bytes,bytes+jpegSize);
    capture.ready.notify_one();
}
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_droiddeck_launcher_glasses_UvcRgbDiagnostic_nativeSnapshot(JNIEnv* env, jobject,
        jint fd, jint width, jint height, jint fps, jint durationMs, jlongArray metadata, jint logFd) {
    if (fd<0 || logFd<0 || !metadata || env->GetArrayLength(metadata)!=14 || width<160 || width>1920 ||
            height<120 || height>1080 || fps<5 || fps>30 || durationMs<1000 || durationMs>30000) return nullptr;
    UsbLog log(logFd);
    Capture capture;
    uvc_context_t* context=nullptr;
    uvc_device_handle_t* camera=nullptr;
    libusb_set_option(nullptr,LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    int result=uvc_init(&context,nullptr);
    fprintf(stderr,"UVC init=%d\n",result);
    if (result==0) {
        result=uvc_wrap(fd,context,&camera);
        fprintf(stderr,"UVC wrap=%d\n",result);
    }
    if (result==0) {
        uvc_print_diag(camera,stderr);
        uvc_stream_ctrl_t control{};
        result=uvc_get_stream_ctrl_format_size(camera,&control,UVC_FRAME_FORMAT_MJPEG,width,height,fps);
        fprintf(stderr,"UVC negotiate=%d requested=%dx%d@%d\n",result,width,height,fps);
        if (result==0) {
            uvc_print_stream_ctrl(&control,stderr);
            result=uvc_start_streaming(camera,&control,frame_received,&capture,0);
            fprintf(stderr,"UVC start=%d\n",result);
            if (result==0) {
                {
                    std::unique_lock<std::mutex> lock(capture.mutex);
                    if (capture.ready.wait_for(lock,std::chrono::seconds(5),[&] { return !capture.jpeg.empty(); }))
                        capture.ready.wait_for(lock,std::chrono::milliseconds(durationMs),[] { return false; });
                }
                finish_observation(capture);
                uvc_stop_streaming(camera);
            }
        }
    }
    finish_observation(capture);
    if (camera) uvc_close(camera);
    if (context) uvc_exit(context);
    capture.values[4]=result;
    fprintf(stderr,"UVC result=%d callbacks=%lld complete=%lld invalid=%lld maxGapMs=%.3f tailMs=%.3f\n",result,
        static_cast<long long>(capture.values[5]),static_cast<long long>(capture.values[9]),
        static_cast<long long>(capture.values[10]),capture.values[8]/1e6,capture.values[13]/1e6);
    env->SetLongArrayRegion(metadata,0,14,capture.values);
    if (result!=0 || capture.jpeg.empty()) return nullptr;
    auto bytes=env->NewByteArray(static_cast<jsize>(capture.jpeg.size()));
    if (bytes) env->SetByteArrayRegion(bytes,0,static_cast<jsize>(capture.jpeg.size()),
        reinterpret_cast<const jbyte*>(capture.jpeg.data()));
    return bytes;
}
