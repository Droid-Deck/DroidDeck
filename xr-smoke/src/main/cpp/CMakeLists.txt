cmake_minimum_required(VERSION 3.22.1)
project(droiddeck_xr_smoke LANGUAGES C CXX)
add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../../../../xr-core/src/main/cpp" "${CMAKE_CURRENT_BINARY_DIR}/shared-xr")
droiddeck_add_xr_activity(droiddeckxr_smoke "${CMAKE_CURRENT_SOURCE_DIR}/entry.cpp")
