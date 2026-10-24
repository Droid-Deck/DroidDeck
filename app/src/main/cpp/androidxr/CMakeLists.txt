cmake_minimum_required(VERSION 3.22.1)
add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../../../../../xr-core/src/main/cpp" "${CMAKE_CURRENT_BINARY_DIR}/android-xr")
droiddeck_add_xr_activity(droiddeckandroidxr "${CMAKE_CURRENT_SOURCE_DIR}/android_xr.cpp")
