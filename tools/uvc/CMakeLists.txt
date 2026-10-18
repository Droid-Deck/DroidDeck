cmake_minimum_required(VERSION 3.22)
project(droiddeck_rgb_uvc VERSION 0.0.8 LANGUAGES C CXX)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(RGB_UVC_SOURCE_ROOT "" CACHE PATH "Extracted checksum-pinned libusb and libuvc sources")
if(NOT RGB_UVC_SOURCE_ROOT)
    message(FATAL_ERROR "Set RGB_UVC_SOURCE_ROOT or use tools/build_rgb_uvc.py")
endif()

set(USB_SOURCE "${RGB_UVC_SOURCE_ROOT}/libusb-1.0.30")
set(UVC_SOURCE "${RGB_UVC_SOURCE_ROOT}/libuvc-0.0.8")
add_library(rgb_usb SHARED
    ${USB_SOURCE}/libusb/core.c
    ${USB_SOURCE}/libusb/descriptor.c
    ${USB_SOURCE}/libusb/hotplug.c
    ${USB_SOURCE}/libusb/io.c
    ${USB_SOURCE}/libusb/sync.c
    ${USB_SOURCE}/libusb/strerror.c
    ${USB_SOURCE}/libusb/os/linux_usbfs.c
    ${USB_SOURCE}/libusb/os/events_posix.c
    ${USB_SOURCE}/libusb/os/threads_posix.c
    ${USB_SOURCE}/libusb/os/linux_netlink.c
)
set_target_properties(rgb_usb PROPERTIES OUTPUT_NAME usb)
target_include_directories(rgb_usb PUBLIC ${USB_SOURCE}/libusb PRIVATE ${USB_SOURCE}/android ${USB_SOURCE}/libusb/os)
target_compile_options(rgb_usb PRIVATE -fvisibility=hidden -pthread)
target_link_options(rgb_usb PRIVATE -Wl,--no-undefined -Wl,-z,max-page-size=16384)
target_link_libraries(rgb_usb PUBLIC log)

set(libuvc_VERSION_MAJOR 0)
set(libuvc_VERSION_MINOR 0)
set(libuvc_VERSION_PATCH 8)
set(libuvc_VERSION 0.0.8)
set(LIBUVC_HAS_JPEG OFF)
configure_file(${UVC_SOURCE}/include/libuvc/libuvc_config.h.in generated/libuvc/libuvc_config.h @ONLY)
add_library(rgb_uvc STATIC
    ${UVC_SOURCE}/src/ctrl.c
    ${UVC_SOURCE}/src/ctrl-gen.c
    ${UVC_SOURCE}/src/device.c
    ${UVC_SOURCE}/src/diag.c
    ${UVC_SOURCE}/src/frame.c
    ${UVC_SOURCE}/src/init.c
    ${UVC_SOURCE}/src/stream.c
    ${UVC_SOURCE}/src/misc.c
)
set_target_properties(rgb_uvc PROPERTIES OUTPUT_NAME uvc)
target_include_directories(rgb_uvc PUBLIC ${UVC_SOURCE}/include ${CMAKE_CURRENT_BINARY_DIR}/generated)
target_compile_definitions(rgb_uvc PRIVATE UVC_DEBUGGING LIBUVC_NUM_TRANSFER_BUFS=8)
target_compile_options(rgb_uvc PRIVATE -fvisibility=hidden -pthread)
target_link_libraries(rgb_uvc PUBLIC rgb_usb log)

add_executable(uvc_submit_failure_test submit_failure_test.cpp)
target_compile_features(uvc_submit_failure_test PRIVATE cxx_std_17)
target_compile_definitions(uvc_submit_failure_test PRIVATE LIBUVC_NUM_TRANSFER_BUFS=8)
target_link_libraries(uvc_submit_failure_test PRIVATE rgb_uvc)
target_link_options(uvc_submit_failure_test PRIVATE
    -Wl,--no-undefined -Wl,-z,max-page-size=16384
    -Wl,--wrap=libusb_submit_transfer -Wl,--wrap=libusb_cancel_transfer
    -Wl,--wrap=libusb_set_interface_alt_setting
    -Wl,--wrap=libusb_get_ss_endpoint_companion_descriptor
)
