cmake_minimum_required(VERSION 3.5.0)
project(droiddeck_native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# HarmonyOS builds this module with the OHOS NDK toolchain supplied by
# hvigor (see entry/build-profile.json5 -> externalNativeOptions).
if(NOT DEFINED ENV{OHOS_SDK_NATIVE})
  message(FATAL_ERROR "OHOS_SDK_NATIVE is not set; build through DevEco Studio or hvigor")
endif()

add_library(droiddeck_native SHARED
  napi_init.cpp
  device/device.cpp
  render/vk_present.cpp
  render/vkp_image.cpp
  runtime/runtime.cpp
  runtime/install.cpp
  runtime/unpack.cpp
  input/gamepad.cpp
  surface/surface.cpp
  audio/audio.cpp
  audio/pulse_server.cpp
  compositor/ohos_modules.c
  compositor/compositor_ohos.c
  compositor/src/compositor.c
  compositor/src/wl_clipboard.c
  compositor/src/wl_color_mgmt.c
  compositor/src/wl_dmabuf.c
  compositor/src/wl_single_pixel.c
  compositor/src/wl_text_input.c
  compositor/src/wl_toplevel_icon.c
  compositor/src/droiddeck_ext.c
  compositor/generated/banner-ahb-v1-protocol.c
  compositor/generated/banner-desktop-v1-protocol.c
  compositor/generated/color-management-v1-protocol.c
  compositor/generated/linux-dmabuf-v1-protocol.c
  compositor/generated/pointer-constraints-unstable-v1-protocol.c
  compositor/generated/presentation-time-protocol.c
  compositor/generated/relative-pointer-unstable-v1-protocol.c
  compositor/generated/single-pixel-buffer-v1-protocol.c
  compositor/generated/text-input-unstable-v3-protocol.c
  compositor/generated/viewporter-protocol.c
  compositor/generated/wlr-data-control-unstable-v1-protocol.c
  compositor/generated/xdg-shell-protocol.c
  compositor/generated/xdg-toplevel-icon-v1-protocol.c
)

target_include_directories(droiddeck_native PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}
  ${CMAKE_CURRENT_SOURCE_DIR}/render
  ${CMAKE_CURRENT_SOURCE_DIR}/compat
  ${CMAKE_CURRENT_SOURCE_DIR}/compositor
  ${CMAKE_CURRENT_SOURCE_DIR}/compositor/src
  # The wlroots-patched Wayland headers come before the generated ones: the
  # compositor expects the data device manager's release request, which stock
  # Wayland does not have.
  ${CMAKE_CURRENT_SOURCE_DIR}/thirdparty/wayland/include
  ${CMAKE_CURRENT_SOURCE_DIR}/compositor/generated
  ${CMAKE_CURRENT_SOURCE_DIR}/thirdparty/zstd/include
)

target_compile_options(droiddeck_native PRIVATE
  -Wall
  -Wextra
  -Wno-unused-parameter
)

# The ArkUI framework looks the XComponent plugin entry points up by name in
# the loaded library, so the default symbol visibility has to stay on.

# N-API (ArkTS <-> C), hilog, the window surface, the audio renderer and the
# EGL/GLES stack used to identify the GPU and to present the guest desktop.
target_link_libraries(droiddeck_native
  PUBLIC
    libace_napi.z.so
    libace_ndk.z.so
    libhilog_ndk.z.so
    libnative_window.so
    libEGL.so
    libGLESv3.so
    libz.so
    # The musl build of libwayland-server that ships in the HAP.
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/arm64-v8a/libwayland-server.so
    # zstd, for upstream's .tar.zst runtime archives.
    ${CMAKE_CURRENT_SOURCE_DIR}/thirdparty/zstd/libzstd.a
)
