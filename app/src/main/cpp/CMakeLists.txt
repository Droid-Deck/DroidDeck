cmake_minimum_required(VERSION 3.22.1)
project(droiddeck C CXX)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

add_subdirectory(adrenotools)

# DXVK's DXBC->SPIR-V translator is zlib licensed (vendored under thirdparty/dxbc).
add_subdirectory(thirdparty/dxbc)

set(DDFG_UPSTREAM "v0.3.0")
set(DDFG_CHAIN_SRC
        framegen/ddfg/framegen.cpp
        framegen/ddfg/framegen.hpp
        framegen/ddfg/record_impl.inc
        framegen/ddfg/config.hpp
        framegen/ddfg/embedded_shaders.hpp
        framegen/ddfg/ddfg_engine.cpp
)
set(DDFG_HASH_INPUT "")
foreach(f ${DDFG_CHAIN_SRC})
    file(SHA256 "${CMAKE_CURRENT_SOURCE_DIR}/${f}" _h)
    string(APPEND DDFG_HASH_INPUT "${_h}")
endforeach()
string(SHA256 DDFG_CHAIN_HASH_FULL "${DDFG_HASH_INPUT}")
string(SUBSTRING "${DDFG_CHAIN_HASH_FULL}" 0 12 DDFG_CHAIN_HASH)
message(STATUS "dd-fg chain: win-fg ${DDFG_UPSTREAM} (src ${DDFG_CHAIN_HASH})")

add_subdirectory(waylandcomp)
add_subdirectory(questxr)
add_library(sdnative SHARED sdnative/sdnative.c)
target_link_libraries(sdnative log)

add_library(deviceinfo SHARED deviceinfo/vkinfo.c)
target_link_libraries(deviceinfo dl)

add_library(termux SHARED termux/termux.c)

add_library(ddvenc SHARED venc/venc_server.cpp)
target_compile_features(ddvenc PRIVATE cxx_std_17)
target_link_libraries(ddvenc mediandk log)

# The optional proprietary SDK is supplied locally; its headers/libraries are not vendored.
if(DROIDDECK_VITURE_SDK_DIR)
    add_library(droiddeck_viture SHARED viture/sdk_bridge.cpp)
    target_compile_features(droiddeck_viture PRIVATE cxx_std_17)
    target_include_directories(droiddeck_viture PRIVATE "${DROIDDECK_VITURE_SDK_DIR}/include")
    target_link_directories(droiddeck_viture PRIVATE "${DROIDDECK_VITURE_SDK_DIR}/android/${ANDROID_ABI}")
    target_link_libraries(droiddeck_viture PRIVATE glasses log)
endif()

if(DROIDDECK_UVC_DIR)
    add_library(rgb_usb SHARED IMPORTED)
    set_target_properties(rgb_usb PROPERTIES IMPORTED_LOCATION "${DROIDDECK_UVC_DIR}/${ANDROID_ABI}/libusb.so")
    add_library(rgb_uvc STATIC IMPORTED)
    set_target_properties(rgb_uvc PROPERTIES IMPORTED_LOCATION "${DROIDDECK_UVC_DIR}/${ANDROID_ABI}/libuvc.a")
    add_library(droiddeck_uvc SHARED viture/uvc_bridge.cpp)
    target_compile_features(droiddeck_uvc PRIVATE cxx_std_17)
    target_include_directories(droiddeck_uvc PRIVATE "${DROIDDECK_UVC_DIR}/include")
    target_link_libraries(droiddeck_uvc PRIVATE rgb_uvc rgb_usb log)
    target_link_options(droiddeck_uvc PRIVATE -Wl,-z,max-page-size=16384)
endif()
