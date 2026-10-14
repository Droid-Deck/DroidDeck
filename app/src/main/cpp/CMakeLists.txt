cmake_minimum_required(VERSION 3.22.1)
project(droiddeck C CXX)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

add_subdirectory(adrenotools)

# DXVK's DXBC->SPIR-V translator is zlib licensed (vendored under thirdparty/dxbc).
add_subdirectory(thirdparty/dxbc)

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

add_library(deviceinfo SHARED deviceinfo/vkinfo.c)
target_link_libraries(deviceinfo dl)

add_library(termux SHARED termux/termux.c)
