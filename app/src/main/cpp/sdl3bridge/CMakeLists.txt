set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST_LIBRARY OFF CACHE BOOL "" FORCE)
set(SDL_TESTS OFF CACHE BOOL "" FORCE)
set(SDL_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SDL_ANDROID_JAR OFF CACHE BOOL "" FORCE)
set(SDL_INSTALL OFF CACHE BOOL "" FORCE)
# The bridge draws nothing (SDL3 runs headless for controllers): no OpenGL ES, so libSDL3.so needs no libGLESv1_CM.so,
# which DroidDeck's APK check (tools/release/check_android_libraries.py) does not count as a system library.
set(SDL_OPENGLES OFF CACHE BOOL "" FORCE)
include(FetchContent)
if(POLICY CMP0135)
    cmake_policy(SET CMP0135 NEW)
endif()
set(SDL3_MANIFEST "${DROIDDECK_CONTROLLER_SOURCES_DIR}/sdl3/upstream.json")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${SDL3_MANIFEST}")
file(READ "${SDL3_MANIFEST}" SDL3_UPSTREAM)
string(JSON SDL3_ARCHIVE GET "${SDL3_UPSTREAM}" source archive)
string(JSON SDL3_SHA256 GET "${SDL3_UPSTREAM}" source sha256)
FetchContent_Declare(sdl3
    URL "${SDL3_ARCHIVE}"
    URL_HASH "SHA256=${SDL3_SHA256}"
)
FetchContent_MakeAvailable(sdl3)
add_library(main SHARED "${DROIDDECK_CONTROLLER_SOURCES_DIR}/sdlbridge/native/main.c")
target_link_libraries(main PRIVATE SDL3::SDL3 log)
