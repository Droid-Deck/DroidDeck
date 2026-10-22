#include <libusb.h>
#include <libuvc/libuvc.h>
#include <libuvc/libuvc_internal.h>
#include <cstdio>
#include <thread>
#include <vector>

static int failAt;
static int submissions;
static int cancellations;
static bool invalidCancellation;
static std::vector<std::thread> completions;

extern "C" int __wrap_libusb_submit_transfer(libusb_transfer* transfer) {
    if (!transfer) return LIBUSB_ERROR_INVALID_PARAM;
    return submissions++ == failAt ? LIBUSB_ERROR_NO_MEM : LIBUSB_SUCCESS;
}

extern "C" int __wrap_libusb_cancel_transfer(libusb_transfer* transfer) {
    if (!transfer) {
        invalidCancellation = true;
        return LIBUSB_ERROR_INVALID_PARAM;
    }
    ++cancellations;
    completions.emplace_back([transfer] {
        transfer->status = LIBUSB_TRANSFER_CANCELLED;
        transfer->callback(transfer);
    });
    return LIBUSB_SUCCESS;
}

extern "C" int __wrap_libusb_set_interface_alt_setting(libusb_device_handle*, int, int) {
    return LIBUSB_SUCCESS;
}

extern "C" int __wrap_libusb_get_ss_endpoint_companion_descriptor(
        libusb_context*, const libusb_endpoint_descriptor*, libusb_ss_endpoint_companion_descriptor**) {
    return LIBUSB_ERROR_NOT_FOUND;
}

static void onFrame(uvc_frame_t*, void*) {}

static bool checkFailure(int failureIndex) {
    failAt = failureIndex;
    submissions = cancellations = 0;
    invalidCancellation = false;
    uvc_frame_desc_t frame{};
    frame.bFrameIndex = 1;
    frame.wWidth = 640;
    frame.wHeight = 480;
    uvc_format_desc_t format{};
    format.bFormatIndex = 1;
    format.frame_descs = &frame;
    memcpy(format.guidFormat, "MJPG", 4);
    frame.parent = &format;
    uvc_streaming_interface_t streamInterface{};
    streamInterface.bInterfaceNumber = 1;
    streamInterface.bEndpointAddress = 0x81;
    streamInterface.format_descs = &format;
    format.parent = &streamInterface;
    libusb_endpoint_descriptor endpoint{};
    endpoint.bEndpointAddress = 0x81;
    endpoint.bmAttributes = LIBUSB_TRANSFER_TYPE_ISOCHRONOUS;
    endpoint.wMaxPacketSize = 512;
    libusb_interface_descriptor alternatives[2]{};
    alternatives[0].bInterfaceNumber = alternatives[1].bInterfaceNumber = 1;
    alternatives[1].bAlternateSetting = 1;
    alternatives[1].bNumEndpoints = 1;
    alternatives[1].endpoint = &endpoint;
    libusb_interface interfaces[2]{};
    interfaces[1].num_altsetting = 2;
    interfaces[1].altsetting = alternatives;
    libusb_config_descriptor config{};
    config.bNumInterfaces = 2;
    config.interface = interfaces;
    uvc_device_info_t info{};
    info.config = &config;
    uvc_device_handle_t camera{};
    camera.info = &info;
    uvc_stream_handle_t stream{};
    stream.devh = &camera;
    stream.stream_if = &streamInterface;
    stream.cur_ctrl.bInterfaceNumber = 1;
    stream.cur_ctrl.bFormatIndex = 1;
    stream.cur_ctrl.bFrameIndex = 1;
    stream.cur_ctrl.dwMaxPayloadTransferSize = 512;
    stream.cur_ctrl.dwMaxVideoFrameSize = 8192;
    pthread_mutex_init(&stream.cb_mutex, nullptr);
    pthread_cond_init(&stream.cb_cond, nullptr);

    const int result = uvc_stream_start(&stream, onFrame, nullptr, 0);
    if (result == UVC_SUCCESS) uvc_stream_stop(&stream);
    for (auto& completion : completions) completion.join();
    completions.clear();
    bool valid = result == (failureIndex < 8 ? LIBUSB_ERROR_NO_MEM : UVC_SUCCESS)
            && !stream.running && cancellations == failureIndex && !invalidCancellation;
    for (auto* transfer : stream.transfers) valid = valid && transfer == nullptr;
    pthread_cond_destroy(&stream.cb_cond);
    pthread_mutex_destroy(&stream.cb_mutex);
    if (!valid) std::fprintf(stderr, "failureIndex=%d result=%d cancellations=%d\n", failureIndex, result, cancellations);
    return valid;
}

int main() {
    for (int iteration = 0; iteration < 20; ++iteration) {
        for (int failureIndex : {0, 3, 8}) {
            if (!checkFailure(failureIndex)) return 1;
        }
    }
    std::puts("PASS: 60 starts covering zero/partial/full submissions; no USB hardware calls");
    return 0;
}
