#pragma once

#include <cstdint>

// Android C ABI used by the adapter, verified against VITURE SDK 2.4.0 and 2.4.2.
using XRDeviceProviderHandle = void*;
using XRCameraProviderHandle = void*;
using GlassStateCallback = void (*)(int, int);
using VitureImuPoseCallback = void (*)(float*, uint64_t);
using XRPoseCallback = void (*)(float*, double);
using XRVSyncCallback = void (*)(double);
using XRImuCallback = void (*)(float*, double);
using XRCameraCallback = void (*)(char*, char*, char*, char*, double, int, int);

struct XRCameraFrame {
    uint8_t* data;
    uint32_t size;
    uint32_t width;
    uint32_t height;
    int format;
    uint64_t timestamp;
    uint32_t sequence;
};
using XRCameraFrameCallback = void (*)(const XRCameraFrame*, void*);

constexpr int XR_DEVICE_TYPE_VITURE_CARINA = 2;
constexpr int XR_CAMERA_FORMAT_MJPEG = 0;
constexpr int VITURE_NATIVE_DOF_0 = 0;
constexpr int VITURE_IMU_MODE_POSE = 1;
constexpr int VITURE_IMU_FREQUENCY_LOW = 0;
constexpr int VITURE_IMU_FREQUENCY_MEDIUM_HIGH = 3;
constexpr int VITURE_IMU_FREQUENCY_ULTRA_HIGH = 5;
constexpr int VITURE_GLASSES_ERROR_NOT_SUPPORTED = -4;

#define VITURE_SDK_FUNCTIONS(F) \
    F(const char*, GetVersionString, ()) \
    F(XRDeviceProviderHandle, xr_device_provider_create, (int, int)) \
    F(int, xr_device_provider_initialize, (XRDeviceProviderHandle, const char*, const char*)) \
    F(int, xr_device_provider_start, (XRDeviceProviderHandle)) \
    F(int, xr_device_provider_stop, (XRDeviceProviderHandle)) \
    F(int, xr_device_provider_shutdown, (XRDeviceProviderHandle)) \
    F(void, xr_device_provider_destroy, (XRDeviceProviderHandle)) \
    F(int, xr_device_provider_get_device_type, (XRDeviceProviderHandle)) \
    F(int, xr_device_provider_is_product_id_valid, (int)) \
    F(int, xr_device_provider_is_product_support_native_dof, (int)) \
    F(int, xr_device_provider_is_product_support_imu_frequency, (int, int, int)) \
    F(int, xr_device_provider_get_market_name, (int, char*, int*)) \
    F(int, xr_device_provider_register_state_callback, (XRDeviceProviderHandle, GlassStateCallback)) \
    F(int, xr_device_provider_register_imu_pose_callback, (XRDeviceProviderHandle, VitureImuPoseCallback)) \
    F(int, xr_device_provider_open_imu, (XRDeviceProviderHandle, uint8_t, uint8_t)) \
    F(int, xr_device_provider_close_imu, (XRDeviceProviderHandle, uint8_t)) \
    F(int, xr_device_provider_register_callbacks_carina, (XRDeviceProviderHandle, XRPoseCallback, XRVSyncCallback, XRImuCallback, XRCameraCallback)) \
    F(int, xr_device_provider_set_dof_type_carina, (XRDeviceProviderHandle, int)) \
    F(int, xr_device_provider_get_gl_pose_carina, (XRDeviceProviderHandle, float*, double, int*)) \
    F(int, xr_device_provider_set_film_mode, (XRDeviceProviderHandle, float)) \
    F(int, xr_device_provider_get_display_mode, (XRDeviceProviderHandle)) \
    F(int, xr_device_provider_set_display_mode, (XRDeviceProviderHandle, int)) \
    F(int, xr_device_provider_get_brightness_level, (XRDeviceProviderHandle)) \
    F(int, xr_device_provider_set_brightness_level, (XRDeviceProviderHandle, int)) \
    F(int, xr_device_provider_get_volume_level, (XRDeviceProviderHandle)) \
    F(int, xr_device_provider_set_volume_level, (XRDeviceProviderHandle, int)) \
    F(int, xr_device_provider_get_glasses_version, (XRDeviceProviderHandle, char*, int*)) \
    F(int, xr_device_provider_get_sn_hash, (XRDeviceProviderHandle, uint8_t*)) \
    F(int, xr_device_provider_native_get_mode, (XRDeviceProviderHandle)) \
    F(int, xr_device_provider_native_set_mode, (XRDeviceProviderHandle, int)) \
    F(int, xr_device_provider_native_get_dof, (XRDeviceProviderHandle)) \
    F(int, xr_device_provider_native_set_dof, (XRDeviceProviderHandle, int)) \
    F(int, xr_device_provider_native_get_display_mode, (XRDeviceProviderHandle)) \
    F(int, xr_device_provider_native_set_display_mode, (XRDeviceProviderHandle, int)) \
    F(int, xr_camera_provider_is_valid_camera, (int, int)) \
    F(XRCameraProviderHandle, xr_camera_provider_create, (int, int, int)) \
    F(int, xr_camera_provider_start, (XRCameraProviderHandle, XRCameraFrameCallback, void*)) \
    F(int, xr_camera_provider_stop, (XRCameraProviderHandle)) \
    F(void, xr_camera_provider_destroy, (XRCameraProviderHandle)) \
    F(int, xr_camera_provider_is_streaming, (XRCameraProviderHandle))

#define VITURE_SDK_OPTIONAL_FUNCTIONS(F) \
    F(int, xr_device_provider_get_value_max, (XRDeviceProviderHandle, int, int*)) \
    F(int, xr_device_provider_get_film_mode, (XRDeviceProviderHandle, float*)) \
    F(int, xr_device_provider_get_duty_cycle, (XRDeviceProviderHandle)) \
    F(int, xr_device_provider_set_duty_cycle, (XRDeviceProviderHandle, int)) \
    F(int, xr_device_provider_get_wear_status, (XRDeviceProviderHandle, uint8_t*)) \
    F(int, xr_device_provider_set_default_display_mode, (XRDeviceProviderHandle, int)) \
    F(int, xr_device_provider_set_display_mode_button_enabled, (XRDeviceProviderHandle, int)) \
    F(int, xr_device_provider_native_recenter_dof, (XRDeviceProviderHandle)) \
    F(int, xr_device_provider_native_get_side_mode, (XRDeviceProviderHandle)) \
    F(int, xr_device_provider_native_set_side_mode, (XRDeviceProviderHandle, int)) \
    F(int, xr_device_provider_native_get_display_distance, (XRDeviceProviderHandle)) \
    F(int, xr_device_provider_native_set_display_distance, (XRDeviceProviderHandle, int)) \
    F(int, xr_device_provider_native_get_display_size, (XRDeviceProviderHandle)) \
    F(int, xr_device_provider_native_set_display_size, (XRDeviceProviderHandle, int)) \
    F(int, xr_device_provider_reset_pose_carina, (XRDeviceProviderHandle)) \
    F(int, xr_device_provider_reset_origin_carina, (XRDeviceProviderHandle, float*)) \
    F(int, xr_device_provider_set_auto_exposure_carina, (XRDeviceProviderHandle)) \
    F(int, xr_device_provider_set_manual_exposure_carina, (XRDeviceProviderHandle, float, int)) \
    F(int, xr_camera_provider_get_camera_vid, (int)) \
    F(int, xr_camera_provider_get_camera_pid, (int))

struct VitureSdkApi {
#define VITURE_DECLARE(result, name, arguments) result (*name) arguments = nullptr;
    VITURE_SDK_FUNCTIONS(VITURE_DECLARE)
    VITURE_SDK_OPTIONAL_FUNCTIONS(VITURE_DECLARE)
#undef VITURE_DECLARE
};
