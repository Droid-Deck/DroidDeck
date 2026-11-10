# WFG (WinNative/Winlator Frame Generator, MIT): Dense Inverse Search (DIS) optical flow and
# the frame interpolator built on it, as one static library linked into the Wayland compositor.
#
# Layout:
#   include/vkr_dis.h      public API (plain Vulkan types)
#   include/shaders/*.comp GLSL compute shaders, compiled to SPIR-V headers at build time
#   src/                   implementation and WFG's own Vulkan dispatch table (vk_dispatch.*),
#                          bound to the compositor's driver at run time

if(NOT GLSLC)
    if(CMAKE_HOST_WIN32)
        set(WFG_GLSLC_HOST "windows-x86_64")
        set(WFG_GLSLC_EXE "glslc.exe")
    elseif(CMAKE_HOST_APPLE)
        set(WFG_GLSLC_HOST "darwin-x86_64")
        set(WFG_GLSLC_EXE "glslc")
    else()
        set(WFG_GLSLC_HOST "linux-x86_64")
        set(WFG_GLSLC_EXE "glslc")
    endif()
    set(GLSLC "${ANDROID_NDK}/shader-tools/${WFG_GLSLC_HOST}/${WFG_GLSLC_EXE}")
endif()
if(NOT EXISTS "${GLSLC}")
    message(FATAL_ERROR "glslc not found at ${GLSLC}; check the NDK installation")
endif()

set(DIS_SHADER_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/include/shaders")
set(DIS_SHADER_OUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/shaders")
set(DIS_BIN2C_SCRIPT "${CMAKE_CURRENT_SOURCE_DIR}/bin2c.cmake")
file(MAKE_DIRECTORY "${DIS_SHADER_OUT_DIR}")

set(DIS_SHADERS
    dis_luma_r16
    dis_luma_r32
    dis_gradient
    dis_inverse_search
    dis_propagate
    dis_densify
    dis_interpolate
    dis_hist
    dis_side
    dis_flow_pack
    dis_splat
    dis_fill
    dis_vr_prep
    dis_vr_d1
    dis_vr_d2
    dis_vr_w
    dis_vr_coef
    dis_vr_sor
    dis_vr_add
    dis_fb_poly
    dis_fb_hsum
    dis_fb_vsolve
)

set(DIS_SHADER_HEADERS "")
foreach(base ${DIS_SHADERS})
    set(var   "${base}_comp")
    set(input "${DIS_SHADER_SRC_DIR}/${base}.comp")
    set(spv   "${DIS_SHADER_OUT_DIR}/${var}.spv")
    set(hdr   "${DIS_SHADER_OUT_DIR}/${var}.spv.h")
    add_custom_command(
        OUTPUT  "${hdr}"
        COMMAND "${GLSLC}" --target-env=vulkan1.1 -O "${input}" -o "${spv}"
        COMMAND "${CMAKE_COMMAND}"
            -DINPUT_FILE=${spv}
            -DOUTPUT_FILE=${hdr}
            -DVAR_NAME=${var}
            -P "${DIS_BIN2C_SCRIPT}"
        DEPENDS "${input}" "${DIS_BIN2C_SCRIPT}"
        COMMENT "Compiling WFG shader ${base}.comp -> ${var}.spv.h"
        VERBATIM
    )
    list(APPEND DIS_SHADER_HEADERS "${hdr}")
endforeach()

add_custom_target(wfg_shaders DEPENDS ${DIS_SHADER_HEADERS})

add_library(wfg STATIC
        src/vkr_dis.c
        src/vk_dispatch.c
)
add_dependencies(wfg wfg_shaders)

set_target_properties(wfg PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_options(wfg PRIVATE -O2 -Wall -Wextra -fvisibility=hidden)
target_compile_definitions(wfg PRIVATE VK_USE_PLATFORM_ANDROID_KHR)
target_include_directories(wfg
        PUBLIC
            ${CMAKE_CURRENT_SOURCE_DIR}/include
        PRIVATE
            ${CMAKE_CURRENT_SOURCE_DIR}/src
            ${CMAKE_CURRENT_BINARY_DIR}
)
target_link_libraries(wfg PRIVATE log dl)
