# The app's native side: the Wayland compositor the session presents into, the adrenotools loader
# it opens Turnip with, and one small library for the fake-input ring's memory fence.
cmake_minimum_required(VERSION 3.22.1)
project(droiddeck C CXX)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

# Every library the app builds is linked with 16 KB-aligned LOAD segments. A 16 KB-page kernel
# (Android 15+ devices) refuses to load a 4 KB-only ELF; a 4 KB kernel loads a 16 KB-aligned one
# unchanged (the alignment is a multiple of its page), so this costs nothing on today's devices
# but a little padding in the file. Prebuilt libraries under jniLibs/ carry their own alignment.
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

add_subdirectory(adrenotools)

# DXVK's DXBC->SPIR-V translator (zlib licence), vendored under thirdparty/dxbc from the same
# subset upstream lsfg-vk uses. Native LSFG frame generation needs it: the Lossless Scaling
# shader chain is DXBC in every build downloadable from Steam.
add_subdirectory(thirdparty/dxbc)

# The Win-FG chain self-identifies by two halves: the upstream tag it was copied from (hand-set,
# UPDATE IT when re-copying) and a hash over the sources themselves, computed here, which cannot
# rot. The compositor's frame-generation leg (waylandcomp/CMakeLists.txt) stamps both in.
set(WINFG_UPSTREAM "v0.3.0")
set(WINFG_CHAIN_SRC
        framegen/winfg/framegen.cpp
        framegen/winfg/framegen.hpp
        framegen/winfg/record_impl.inc
        framegen/winfg/config.hpp
        framegen/winfg/embedded_shaders.hpp
        framegen/winfg/winfg_engine.cpp
)
set(WINFG_HASH_INPUT "")
foreach(f ${WINFG_CHAIN_SRC})
    file(SHA256 "${CMAKE_CURRENT_SOURCE_DIR}/${f}" _h)
    string(APPEND WINFG_HASH_INPUT "${_h}")
endforeach()
string(SHA256 WINFG_CHAIN_HASH_FULL "${WINFG_HASH_INPUT}")
string(SUBSTRING "${WINFG_CHAIN_HASH_FULL}" 0 12 WINFG_CHAIN_HASH)
message(STATUS "win-fg chain: ${WINFG_UPSTREAM} (src ${WINFG_CHAIN_HASH})")

add_subdirectory(waylandcomp)
add_library(sdnative SHARED sdnative/sdnative.c)
target_link_libraries(sdnative log)

# The device info window's Vulkan section: the phone's own driver, asked once through the system
# loader (opened with dlopen, so a phone without Vulkan still loads the app).
add_library(deviceinfo SHARED deviceinfo/vkinfo.c)
target_link_libraries(deviceinfo dl)

# Rebuild Termux terminal-emulator's PTY JNI helper with the app's NDK instead of carrying its
# prebuilt libtermux.so with a redundant libstdc++.so dependency.
add_library(termux SHARED termux/termux.c)
