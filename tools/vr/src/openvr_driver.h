#pragma once

//========= Copyright Valve Corporation ============//

#ifndef _OPENVR_DRIVER_API
#define _OPENVR_DRIVER_API

#include <stdint.h>




namespace vr
{
	static const uint32_t k_nSteamVRVersionMajor = 2;
	static const uint32_t k_nSteamVRVersionMinor = 15;
	static const uint32_t k_nSteamVRVersionBuild = 6;
}


#ifndef _INCLUDE_CORE_VRTYPES_PUBLIC_H
#define _INCLUDE_CORE_VRTYPES_PUBLIC_H

namespace vr
{
#pragma pack( push, 8 )

typedef uint32_t PropertyTypeTag_t;

struct HmdMatrix34_t
{
	float m[3][4];
};

struct HmdMatrix33_t
{
	float m[3][3];
};

struct HmdMatrix44_t
{
	float m[4][4];
};

struct HmdVector3_t
{
	float v[3];
};

struct HmdVector4_t
{
	float v[4];
};

struct HmdVector3d_t
{
	double v[3];
};

struct HmdVector2_t
{
	float v[2];
};

struct HmdQuaternion_t
{
	double w, x, y, z;
};

struct HmdQuaternionf_t
{
	float w, x, y, z;
};

struct HmdColor_t
{
	float r, g, b, a;
};

struct HmdQuad_t
{
	HmdVector3_t vCorners[ 4 ];
};

struct HmdRect2_t
{
	HmdVector2_t vTopLeft;
	HmdVector2_t vBottomRight;
};

struct VRBoneTransform_t
{
	HmdVector4_t position;
	HmdQuaternionf_t orientation;
};

struct VREyeTrackingData_t
{
	bool bActive;
	bool bValid;
	bool bTracked;

	vr::HmdVector3_t vGazeOrigin;
	vr::HmdVector3_t vGazeTarget;
};

struct DistortionCoordinates_t
{
	float rfRed[2];
	float rfGreen[2];
	float rfBlue[2];
};

enum EVREye
{
	Eye_Left = 0,
	Eye_Right = 1
};

enum ETextureType
{
	TextureType_Invalid = -1,
	TextureType_DirectX = 0,
	TextureType_OpenGL = 1,
	TextureType_Vulkan = 2,
	TextureType_IOSurface = 3,
	TextureType_DirectX12 = 4,
	TextureType_DXGISharedHandle = 5,

	TextureType_Metal = 6,



	TextureType_Reserved = 7,
	TextureType_SharedTextureHandle = 8,
};

enum EColorSpace
{
	ColorSpace_Auto = 0,
	ColorSpace_Gamma = 1,
	ColorSpace_Linear = 2,
};

struct Texture_t
{
	void* handle;
	ETextureType eType;
	EColorSpace eColorSpace;
};

struct VRTextureBounds_t
{
	float uMin, vMin;
	float uMax, vMax;
};

struct VRTextureWithPose_t : public Texture_t
{
	HmdMatrix34_t mDeviceToAbsoluteTracking;
};

struct VRTextureDepthInfo_t
{
	void* handle;
	HmdMatrix44_t mProjection;
	HmdVector2_t vRange;
};

struct VRTextureWithDepth_t : public Texture_t
{
	VRTextureDepthInfo_t depth;
};

struct VRTextureWithPoseAndDepth_t : public VRTextureWithPose_t
{
	VRTextureDepthInfo_t depth;
};

struct VRTextureMotionInfo_t
{
	void *handle;
	HmdMatrix44_t mDeltaPose;
};

struct VRTextureWithMotion_t : VRTextureWithPoseAndDepth_t
{
	VRTextureMotionInfo_t motion;
};

#if defined(__linux__) || defined(__APPLE__)
typedef uint64_t vrshared_uint64_t __attribute__ ((aligned(8)));
typedef double vrshared_double __attribute__ ((aligned(8)));
#else
typedef uint64_t vrshared_uint64_t;
typedef double vrshared_double;
#endif

static const uint32_t MaxDmabufPlaneCount = 4;

struct DmabufPlane_t
{
	uint32_t unOffset;
	uint32_t unStride;
	int32_t nFd;
};

struct DmabufAttributes_t
{
	void *pNext;

	uint32_t unWidth;
	uint32_t unHeight;
	uint32_t unDepth;
	uint32_t unMipLevels;
	uint32_t unArrayLayers;
	uint32_t unSampleCount;
	uint32_t unFormat;
	uint64_t ulModifier;

	uint32_t unPlaneCount;
	DmabufPlane_t plane[MaxDmabufPlaneCount];
};

#pragma pack( pop )

}

#endif


#ifndef _INCLUDE_VRTYPES_H
#define _INCLUDE_VRTYPES_H

struct VkDevice_T;
struct VkPhysicalDevice_T;
struct VkInstance_T;
struct VkQueue_T;

struct ID3D12Resource;
struct ID3D12CommandQueue;

namespace vr
{
#pragma pack( push, 8 )

typedef uint32_t SpatialAnchorHandle_t;

typedef void* glSharedTextureHandle_t;
typedef int32_t glInt_t;
typedef uint32_t glUInt_t;


typedef uint64_t SharedTextureHandle_t;
#define INVALID_SHARED_TEXTURE_HANDLE	((vr::SharedTextureHandle_t)0)

enum ETrackingResult
{
	TrackingResult_Uninitialized			= 1,

	TrackingResult_Calibrating_InProgress	= 100,
	TrackingResult_Calibrating_OutOfRange	= 101,

	TrackingResult_Running_OK				= 200,
	TrackingResult_Running_OutOfRange		= 201,

	TrackingResult_Fallback_RotationOnly	= 300,
};

typedef uint32_t DriverId_t;
static const uint32_t k_nDriverNone = 0xFFFFFFFF;

static const uint32_t k_unMaxDriverDebugResponseSize = 32768;

typedef uint32_t TrackedDeviceIndex_t;
static const uint32_t k_unTrackedDeviceIndex_Hmd = 0;
static const uint32_t k_unMaxTrackedDeviceCount = 64;
static const uint32_t k_unTrackedDeviceIndexOther = 0xFFFFFFFE;
static const uint32_t k_unTrackedDeviceIndexInvalid = 0xFFFFFFFF;

enum ETrackedDeviceClass
{
	TrackedDeviceClass_Invalid = 0,
	TrackedDeviceClass_HMD = 1,
	TrackedDeviceClass_Controller = 2,
	TrackedDeviceClass_GenericTracker = 3,
	TrackedDeviceClass_TrackingReference = 4,
	TrackedDeviceClass_DisplayRedirect = 5,

	TrackedDeviceClass_Max
};


enum ETrackedControllerRole
{
	TrackedControllerRole_Invalid = 0,
	TrackedControllerRole_LeftHand = 1,
	TrackedControllerRole_RightHand = 2,
	TrackedControllerRole_OptOut = 3,
	TrackedControllerRole_Treadmill = 4,
	TrackedControllerRole_Stylus = 5,
	TrackedControllerRole_Max = 5
};


inline bool IsRoleAllowedAsHand( ETrackedControllerRole eRole )
{
	switch ( eRole )
	{
	case TrackedControllerRole_Invalid:
	case TrackedControllerRole_LeftHand:
	case TrackedControllerRole_RightHand:
		return true;
	default:
		return false;
	}
}


struct TrackedDevicePose_t
{
	HmdMatrix34_t mDeviceToAbsoluteTracking;
	HmdVector3_t vVelocity;
	HmdVector3_t vAngularVelocity;
	ETrackingResult eTrackingResult;
	bool bPoseIsValid;

	bool bDeviceIsConnected;
};

enum ETrackingUniverseOrigin
{
	TrackingUniverseSeated = 0,
	TrackingUniverseStanding = 1,
	TrackingUniverseRawAndUncalibrated = 2,
};

enum EAdditionalRadioFeatures
{
	AdditionalRadioFeatures_None           = 0x00000000,
	AdditionalRadioFeatures_HTCLinkBox     = 0x00000001,
	AdditionalRadioFeatures_InternalDongle = 0x00000002,
	AdditionalRadioFeatures_ExternalDongle = 0x00000004,
};

typedef uint64_t WebConsoleHandle_t;
#define INVALID_WEB_CONSOLE_HANDLE	((vr::WebConsoleHandle_t)0)

typedef uint64_t PropertyContainerHandle_t;
typedef uint32_t PropertyTypeTag_t;

static const PropertyContainerHandle_t k_ulInvalidPropertyContainer = 0;
static const PropertyTypeTag_t k_unInvalidPropertyTag = 0;

typedef PropertyContainerHandle_t DriverHandle_t;
static const PropertyContainerHandle_t k_ulInvalidDriverHandle = 0;

static const PropertyTypeTag_t k_unFloatPropertyTag = 1;
static const PropertyTypeTag_t k_unInt32PropertyTag = 2;
static const PropertyTypeTag_t k_unUint64PropertyTag = 3;
static const PropertyTypeTag_t k_unBoolPropertyTag = 4;
static const PropertyTypeTag_t k_unStringPropertyTag = 5;
static const PropertyTypeTag_t k_unErrorPropertyTag = 6;
static const PropertyTypeTag_t k_unDoublePropertyTag = 7;

static const PropertyTypeTag_t k_unHmdMatrix34PropertyTag = 20;
static const PropertyTypeTag_t k_unHmdMatrix44PropertyTag = 21;
static const PropertyTypeTag_t k_unHmdVector3PropertyTag = 22;
static const PropertyTypeTag_t k_unHmdVector4PropertyTag = 23;
static const PropertyTypeTag_t k_unHmdVector2PropertyTag = 24;
static const PropertyTypeTag_t k_unHmdQuadPropertyTag = 25;

static const PropertyTypeTag_t k_unHiddenAreaPropertyTag = 30;
static const PropertyTypeTag_t k_unPathHandleInfoTag = 31;
static const PropertyTypeTag_t k_unActionPropertyTag = 32;
static const PropertyTypeTag_t k_unInputValuePropertyTag = 33;
static const PropertyTypeTag_t k_unWildcardPropertyTag = 34;
static const PropertyTypeTag_t k_unHapticVibrationPropertyTag = 35;
static const PropertyTypeTag_t k_unSkeletonPropertyTag = 36;

static const PropertyTypeTag_t k_unSpatialAnchorPosePropertyTag = 40;
static const PropertyTypeTag_t k_unJsonPropertyTag = 41;
static const PropertyTypeTag_t k_unActiveActionSetPropertyTag = 42;

static const PropertyTypeTag_t k_unOpenVRInternalReserved_Start = 1000;
static const PropertyTypeTag_t k_unOpenVRInternalReserved_End = 10000;


enum ETrackedDeviceProperty
{
	Prop_Invalid								= 0,

	Prop_TrackingSystemName_String				= 1000,
	Prop_ModelNumber_String						= 1001,
	Prop_SerialNumber_String					= 1002,
	Prop_RenderModelName_String					= 1003,
	Prop_WillDriftInYaw_Bool					= 1004,
	Prop_ManufacturerName_String				= 1005,
	Prop_TrackingFirmwareVersion_String			= 1006,
	Prop_HardwareRevision_String				= 1007,
	Prop_AllWirelessDongleDescriptions_String	= 1008,
	Prop_ConnectedWirelessDongle_String			= 1009,
	Prop_DeviceIsWireless_Bool					= 1010,
	Prop_DeviceIsCharging_Bool					= 1011,
	Prop_DeviceBatteryPercentage_Float			= 1012,
	Prop_StatusDisplayTransform_Matrix34		= 1013,
	Prop_Firmware_UpdateAvailable_Bool			= 1014,
	Prop_Firmware_ManualUpdate_Bool				= 1015,
	Prop_Firmware_ManualUpdateURL_String		= 1016,
	Prop_HardwareRevision_Uint64				= 1017,
	Prop_FirmwareVersion_Uint64					= 1018,
	Prop_FPGAVersion_Uint64						= 1019,
	Prop_VRCVersion_Uint64						= 1020,
	Prop_RadioVersion_Uint64					= 1021,
	Prop_DongleVersion_Uint64					= 1022,
	Prop_BlockServerShutdown_Bool				= 1023,
	Prop_CanUnifyCoordinateSystemWithHmd_Bool	= 1024,
	Prop_ContainsProximitySensor_Bool			= 1025,
	Prop_DeviceProvidesBatteryStatus_Bool		= 1026,
	Prop_DeviceCanPowerOff_Bool					= 1027,
	Prop_Firmware_ProgrammingTarget_String		= 1028,
	Prop_DeviceClass_Int32						= 1029,
	Prop_HasCamera_Bool							= 1030,
	Prop_DriverVersion_String                   = 1031,
	Prop_Firmware_ForceUpdateRequired_Bool      = 1032,
	Prop_ViveSystemButtonFixRequired_Bool		= 1033,
	Prop_ParentDriver_Uint64					= 1034,
	Prop_ResourceRoot_String					= 1035,
	Prop_RegisteredDeviceType_String			= 1036,
	Prop_InputProfilePath_String				= 1037,
	Prop_NeverTracked_Bool						= 1038,
	Prop_NumCameras_Int32						= 1039,
	Prop_CameraFrameLayout_Int32				= 1040,
	Prop_CameraStreamFormat_Int32				= 1041,
	Prop_AdditionalDeviceSettingsPath_String	= 1042,
	Prop_Identifiable_Bool						= 1043,
	Prop_BootloaderVersion_Uint64			    = 1044,
	Prop_AdditionalSystemReportData_String		= 1045,
	Prop_CompositeFirmwareVersion_String        = 1046,
	Prop_Firmware_RemindUpdate_Bool             = 1047,
	Prop_PeripheralApplicationVersion_Uint64	= 1048,
	Prop_ManufacturerSerialNumber_String		= 1049,
	Prop_ComputedSerialNumber_String			= 1050,
	Prop_EstimatedDeviceFirstUseTime_Int32		= 1051,
	Prop_DevicePowerUsage_Float					= 1052,
	Prop_IgnoreMotionForStandby_Bool			= 1053,
	Prop_ActualTrackingSystemName_String		= 1054,
	Prop_AllowCameraToggle_Bool					= 1055,
	Prop_AllowLightSourceFrequency_Bool			= 1056,
	Prop_SteamRemoteClientID_Uint64				= 1057,
	Prop_Reserved_1058							= 1058,
	Prop_Reserved_1059							= 1059,
	Prop_Reserved_1060							= 1060,

	Prop_ReportsTimeSinceVSync_Bool				= 2000,
	Prop_SecondsFromVsyncToPhotons_Float		= 2001,
	Prop_DisplayFrequency_Float					= 2002,
	Prop_UserIpdMeters_Float					= 2003,
	Prop_CurrentUniverseId_Uint64				= 2004,
	Prop_PreviousUniverseId_Uint64_deprecated	= Prop_Invalid,
	Prop_DisplayFirmwareVersion_Uint64			= 2006,
	Prop_IsOnDesktop_Bool						= 2007,
	Prop_DisplayMCType_Int32					= 2008,
	Prop_DisplayMCOffset_Float					= 2009,
	Prop_DisplayMCScale_Float					= 2010,
	Prop_EdidVendorID_Int32						= 2011,
	Prop_DisplayMCImageLeft_String              = 2012,
	Prop_DisplayMCImageRight_String             = 2013,
	Prop_DisplayGCBlackClamp_Float				= 2014,
	Prop_EdidProductID_Int32					= 2015,
	Prop_CameraToHeadTransform_Matrix34			= 2016,
	Prop_DisplayGCType_Int32					= 2017,
	Prop_DisplayGCOffset_Float					= 2018,
	Prop_DisplayGCScale_Float					= 2019,
	Prop_DisplayGCPrescale_Float				= 2020,
	Prop_DisplayGCImage_String					= 2021,
	Prop_LensCenterLeftU_Float					= 2022,
	Prop_LensCenterLeftV_Float					= 2023,
	Prop_LensCenterRightU_Float					= 2024,
	Prop_LensCenterRightV_Float					= 2025,
	Prop_UserHeadToEyeDepthMeters_Float			= 2026,
	Prop_CameraFirmwareVersion_Uint64			= 2027,
	Prop_CameraFirmwareDescription_String		= 2028,
	Prop_DisplayFPGAVersion_Uint64				= 2029,
	Prop_DisplayBootloaderVersion_Uint64		= 2030,
	Prop_DisplayHardwareVersion_Uint64			= 2031,
	Prop_AudioFirmwareVersion_Uint64			= 2032,
	Prop_CameraCompatibilityMode_Int32			= 2033,
	Prop_ScreenshotHorizontalFieldOfViewDegrees_Float = 2034,
	Prop_ScreenshotVerticalFieldOfViewDegrees_Float = 2035,
	Prop_DisplaySuppressed_Bool					= 2036,
	Prop_DisplayAllowNightMode_Bool				= 2037,
	Prop_DisplayMCImageWidth_Int32				= 2038,
	Prop_DisplayMCImageHeight_Int32				= 2039,
	Prop_DisplayMCImageNumChannels_Int32		= 2040,
	Prop_DisplayMCImageData_Binary				= 2041,
	Prop_SecondsFromPhotonsToVblank_Float		= 2042,
	Prop_DriverDirectModeSendsVsyncEvents_Bool	= 2043,
	Prop_DisplayDebugMode_Bool					= 2044,
	Prop_GraphicsAdapterLuid_Uint64				= 2045,
	Prop_DriverProvidedChaperonePath_String		= 2048,
	Prop_ExpectedTrackingReferenceCount_Int32	= 2049,
	Prop_ExpectedControllerCount_Int32			= 2050,
	Prop_NamedIconPathControllerLeftDeviceOff_String	= 2051,
	Prop_NamedIconPathControllerRightDeviceOff_String	= 2052,
	Prop_NamedIconPathTrackingReferenceDeviceOff_String	= 2053,
	Prop_DoNotApplyPrediction_Bool				= 2054,
	Prop_CameraToHeadTransforms_Matrix34_Array	= 2055,
	Prop_DistortionMeshResolution_Int32			= 2056,
	Prop_DriverIsDrawingControllers_Bool		= 2057,
	Prop_DriverRequestsApplicationPause_Bool	= 2058,
	Prop_DriverRequestsReducedRendering_Bool	= 2059,
	Prop_MinimumIpdStepMeters_Float				= 2060,
	Prop_AudioBridgeFirmwareVersion_Uint64		= 2061,
	Prop_ImageBridgeFirmwareVersion_Uint64		= 2062,
	Prop_ImuToHeadTransform_Matrix34			= 2063,
	Prop_ImuFactoryGyroBias_Vector3				= 2064,
	Prop_ImuFactoryGyroScale_Vector3			= 2065,
	Prop_ImuFactoryAccelerometerBias_Vector3	= 2066,
	Prop_ImuFactoryAccelerometerScale_Vector3	= 2067,
	Prop_ConfigurationIncludesLighthouse20Features_Bool = 2069,
	Prop_AdditionalRadioFeatures_Uint64         = 2070,
	Prop_CameraWhiteBalance_Vector4_Array		= 2071,
	Prop_CameraDistortionFunction_Int32_Array	= 2072,
	Prop_CameraDistortionCoefficients_Float_Array = 2073,
	Prop_ExpectedControllerType_String			= 2074,
	Prop_HmdTrackingStyle_Int32					= 2075,
	Prop_DriverProvidedChaperoneVisibility_Bool = 2076,
	Prop_HmdColumnCorrectionSettingPrefix_String = 2077,
	Prop_CameraSupportsCompatibilityModes_Bool	= 2078,
	Prop_SupportsRoomViewDepthProjection_Bool	= 2079,
	Prop_DisplayAvailableFrameRates_Float_Array = 2080,
	Prop_DisplaySupportsMultipleFramerates_Bool = 2081,
	Prop_DisplayColorMultLeft_Vector3			= 2082,
	Prop_DisplayColorMultRight_Vector3			= 2083,
	Prop_DisplaySupportsRuntimeFramerateChange_Bool = 2084,
	Prop_DisplaySupportsAnalogGain_Bool 		= 2085,
	Prop_DisplayMinAnalogGain_Float 			= 2086,
	Prop_DisplayMaxAnalogGain_Float 			= 2087,
    Prop_CameraExposureTime_Float               = 2088,
    Prop_CameraGlobalGain_Float                 = 2089,
	Prop_DashboardScale_Float 					= 2091,
	Prop_Hmd_SupportsHDR10_Bool					= 2093,
	Prop_Hmd_EnableParallelRenderCameras_Bool	= 2094,
	Prop_DriverProvidedChaperoneJson_String		= 2095,
	Prop_ForceSystemLayerUseAppPoses_Bool		= 2096,
	Prop_DashboardLinkSupport_Int32				= 2097,
	Prop_DisplayMinUIAnalogGain_Float 			= 2098,

	Prop_IpdUIRangeMinMeters_Float 				= 2100,
	Prop_IpdUIRangeMaxMeters_Float 				= 2101,
	Prop_Hmd_SupportsHDCP14LegacyCompat_Bool	= 2102,
	Prop_Hmd_SupportsMicMonitoring_Bool 		= 2103,
	Prop_Hmd_SupportsDisplayPortTrainingMode_Bool	= 2104,
	Prop_Hmd_SupportsRoomViewDirect_Bool 		= 2105,
	Prop_Hmd_SupportsAppThrottling_Bool			= 2106,
	Prop_Hmd_SupportsGpuBusMonitoring_Bool		= 2107,
	Prop_DriverDisplaysIPDChanges_Bool			= 2108,
	Prop_Reserved_2110							= 2110,
	Prop_Reserved_2111							= 2111,
	Prop_Reserved_2112							= 2112,

	Prop_Hmd_MaxDistortedTextureWidth_Int32		= 2113,
	Prop_Hmd_MaxDistortedTextureHeight_Int32	= 2114,
	Prop_Hmd_AllowSupersampleFiltering_Bool		= 2115,

	Prop_Hmd_AllowsClientToControlTextureIndex  = 2116,
	Prop_Reserved_2117							= 2117,

	Prop_DriverRequestedMuraCorrectionMode_Int32		= 2200,
	Prop_DriverRequestedMuraFeather_InnerLeft_Int32		= 2201,
	Prop_DriverRequestedMuraFeather_InnerRight_Int32	= 2202,
	Prop_DriverRequestedMuraFeather_InnerTop_Int32		= 2203,
	Prop_DriverRequestedMuraFeather_InnerBottom_Int32	= 2204,
	Prop_DriverRequestedMuraFeather_OuterLeft_Int32		= 2205,
	Prop_DriverRequestedMuraFeather_OuterRight_Int32	= 2206,
	Prop_DriverRequestedMuraFeather_OuterTop_Int32		= 2207,
	Prop_DriverRequestedMuraFeather_OuterBottom_Int32	= 2208,

	Prop_Audio_DefaultPlaybackDeviceId_String				= 2300,
	Prop_Audio_DefaultRecordingDeviceId_String				= 2301,
	Prop_Audio_DefaultPlaybackDeviceVolume_Float			= 2302,
	Prop_Audio_SupportsDualSpeakerAndJackOutput_Bool		= 2303,
	Prop_Audio_DriverManagesPlaybackVolumeControl_Bool		= 2304,
	Prop_Audio_DriverPlaybackVolume_Float					= 2305,
	Prop_Audio_DriverPlaybackMute_Bool						= 2306,
	Prop_Audio_DriverManagesRecordingVolumeControl_Bool		= 2307,
	Prop_Audio_DriverRecordingVolume_Float					= 2308,
	Prop_Audio_DriverRecordingMute_Bool						= 2309,

	Prop_Audio_PipewirePlaybackNode_Int32					= 2400,
	Prop_Audio_PipewireRecordingNode_Int32					= 2401,

	Prop_AttachedDeviceId_String				= 3000,
	Prop_SupportedButtons_Uint64				= 3001,
	Prop_Axis0Type_Int32						= 3002,
	Prop_Axis1Type_Int32						= 3003,
	Prop_Axis2Type_Int32						= 3004,
	Prop_Axis3Type_Int32						= 3005,
	Prop_Axis4Type_Int32						= 3006,
	Prop_ControllerRoleHint_Int32				= 3007,

	Prop_FieldOfViewLeftDegrees_Float			= 4000,
	Prop_FieldOfViewRightDegrees_Float			= 4001,
	Prop_FieldOfViewTopDegrees_Float			= 4002,
	Prop_FieldOfViewBottomDegrees_Float			= 4003,
	Prop_TrackingRangeMinimumMeters_Float		= 4004,
	Prop_TrackingRangeMaximumMeters_Float		= 4005,
	Prop_ModeLabel_String						= 4006,
	Prop_CanWirelessIdentify_Bool               = 4007,
	Prop_Nonce_Int32                            = 4008,


	Prop_IconPathName_String						= 5000,
	Prop_NamedIconPathDeviceOff_String				= 5001,
	Prop_NamedIconPathDeviceSearching_String		= 5002,
	Prop_NamedIconPathDeviceSearchingAlert_String	= 5003,
	Prop_NamedIconPathDeviceReady_String			= 5004,
	Prop_NamedIconPathDeviceReadyAlert_String		= 5005,
	Prop_NamedIconPathDeviceNotReady_String			= 5006,
	Prop_NamedIconPathDeviceStandby_String			= 5007,
	Prop_NamedIconPathDeviceAlertLow_String			= 5008,
	Prop_NamedIconPathDeviceStandbyAlert_String		= 5009,


	Prop_DisplayHiddenArea_Binary_Start				= 5100,
	Prop_DisplayHiddenArea_Binary_End				= 5150,
	Prop_ParentContainer							= 5151,
	Prop_OverrideContainer_Uint64					= 5152,

	Prop_UserConfigPath_String					= 6000,
	Prop_InstallPath_String						= 6001,
	Prop_HasDisplayComponent_Bool				= 6002,
	Prop_HasControllerComponent_Bool			= 6003,
	Prop_HasCameraComponent_Bool				= 6004,
	Prop_HasDriverDirectModeComponent_Bool		= 6005,
	Prop_HasVirtualDisplayComponent_Bool		= 6006,
	Prop_HasSpatialAnchorsSupport_Bool			= 6007,
	Prop_SupportsXrTextureSets_Bool				= 6008,
	Prop_SupportsXrEyeGazeInteraction_Bool		= 6009,
	Prop_DeviceHasNoIMU_Bool					= 6010,
	Prop_UseAdvancedPrediction_Bool				= 6011,

	Prop_ControllerType_String					= 7000,

	Prop_ControllerHandSelectionPriority_Int32	= 7002,


	Prop_VendorSpecific_Reserved_Start			= 10000,
	Prop_VendorSpecific_Reserved_End			= 10999,

	Prop_Reserved_11000							= 11000,
	Prop_Reserved_11001							= 11001,
	Prop_Reserved_11002							= 11002,
	Prop_Reserved_11003							= 11003,
	Prop_Reserved_11004							= 11004,

	Prop_TrackedDeviceProperty_Max				= 1000000,
};

static const uint32_t k_unMaxPropertyStringSize = 32 * 1024;

enum ETrackedPropertyError
{
	TrackedProp_Success						= 0,
	TrackedProp_WrongDataType				= 1,
	TrackedProp_WrongDeviceClass			= 2,
	TrackedProp_BufferTooSmall				= 3,
	TrackedProp_UnknownProperty				= 4,
	TrackedProp_InvalidDevice				= 5,
	TrackedProp_CouldNotContactServer		= 6,
	TrackedProp_ValueNotProvidedByDevice	= 7,
	TrackedProp_StringExceedsMaximumLength	= 8,
	TrackedProp_NotYetAvailable				= 9,
	TrackedProp_PermissionDenied			= 10,
	TrackedProp_InvalidOperation			= 11,
	TrackedProp_CannotWriteToWildcards		= 12,
	TrackedProp_IPCReadFailure				= 13,
	TrackedProp_OutOfMemory					= 14,
	TrackedProp_InvalidContainer			= 15,
};

enum EHmdTrackingStyle
{
	HmdTrackingStyle_Unknown				= 0,

	HmdTrackingStyle_Lighthouse				= 1,
	HmdTrackingStyle_OutsideInCameras		= 2,
	HmdTrackingStyle_InsideOutCameras		= 3,
};

typedef uint64_t VRActionHandle_t;
typedef uint64_t VRActionSetHandle_t;
typedef uint64_t VRInputValueHandle_t;
typedef uint64_t VRInputComponentHandle_t;

static const VRActionHandle_t k_ulInvalidActionHandle = 0;
static const VRActionSetHandle_t k_ulInvalidActionSetHandle = 0;
static const VRInputValueHandle_t k_ulInvalidInputValueHandle = 0;
static const VRInputComponentHandle_t k_ulInvalidInputComponentHandle = 0;


enum EVRSubmitFlags
{
	Submit_Default = 0x00,

	Submit_LensDistortionAlreadyApplied = 0x01,

	Submit_GlRenderBuffer = 0x02,

	Submit_Reserved = 0x04,

	Submit_TextureWithPose = 0x08,

	Submit_TextureWithDepth = 0x10,

	Submit_FrameDiscontinuity = 0x20,

	Submit_VulkanTextureWithArrayData = 0x40,

	Submit_GlArrayTexture = 0x80,

	Submit_IsEgl = 0x100,

	Submit_TextureWithMotion = 0x200 | Submit_TextureWithPose | Submit_TextureWithDepth,

	Submit_Reserved2 = 0x08000,
	Submit_Reserved3 = 0x10000,
	Submit_Reserved4 = 0x20000,
	Submit_Reserved5 = 0x40000,
	Submit_Reserved6 = 0x80000,
};

struct VRVulkanTextureData_t
{
	uint64_t m_nImage;
	VkDevice_T *m_pDevice;
	VkPhysicalDevice_T *m_pPhysicalDevice;
	VkInstance_T *m_pInstance;
	VkQueue_T *m_pQueue;
	uint32_t m_nQueueFamilyIndex;
	uint32_t m_nWidth, m_nHeight, m_nFormat, m_nSampleCount;
};

struct VRVulkanTextureArrayData_t : public VRVulkanTextureData_t
{
	uint32_t m_unArrayIndex;
	uint32_t m_unArraySize;
};

struct D3D12TextureData_t
{
	ID3D12Resource *m_pResource;
	ID3D12CommandQueue *m_pCommandQueue;
	uint32_t m_nNodeMask;
};

enum EVRState
{
	VRState_Undefined = -1,
	VRState_Off = 0,
	VRState_Searching = 1,
	VRState_Searching_Alert = 2,
	VRState_Ready = 3,
	VRState_Ready_Alert = 4,
	VRState_NotReady = 5,
	VRState_Standby = 6,
	VRState_Ready_Alert_Low = 7,
};

enum EVREventType
{
	VREvent_None = 0,

	VREvent_TrackedDeviceActivated		= 100,
	VREvent_TrackedDeviceDeactivated	= 101,
	VREvent_TrackedDeviceUpdated		= 102,
	VREvent_TrackedDeviceUserInteractionStarted	= 103,
	VREvent_TrackedDeviceUserInteractionEnded	= 104,
	VREvent_IpdChanged					= 105,
	VREvent_EnterStandbyMode			= 106,
	VREvent_LeaveStandbyMode			= 107,
	VREvent_TrackedDeviceRoleChanged	= 108,
	VREvent_WatchdogWakeUpRequested		= 109,
	VREvent_LensDistortionChanged		= 110,
	VREvent_PropertyChanged				= 111,
	VREvent_WirelessDisconnect			= 112,
	VREvent_WirelessReconnect			= 113,
	VREvent_Reserved_0114				= 114,
	VREvent_Reserved_0115				= 115,

	VREvent_ButtonPress					= 200,
	VREvent_ButtonUnpress				= 201,
	VREvent_ButtonTouch					= 202,
	VREvent_ButtonUntouch				= 203,








	VREvent_Modal_Cancel				= 257,

	VREvent_MouseMove					= 300,
	VREvent_MouseButtonDown				= 301,
	VREvent_MouseButtonUp				= 302,
	VREvent_FocusEnter					= 303,
	VREvent_FocusLeave					= 304,
	VREvent_ScrollDiscrete				= 305,
	VREvent_TouchPadMove				= 306,
	VREvent_OverlayFocusChanged			= 307,
	VREvent_ReloadOverlays				= 308,
	VREvent_ScrollSmooth				= 309,
	VREvent_LockMousePosition			= 310,
	VREvent_UnlockMousePosition			= 311,

	VREvent_InputFocusCaptured			= 400,
	VREvent_InputFocusReleased			= 401,


	VREvent_SceneApplicationChanged		= 404,

	VREvent_InputFocusChanged			= 406,

	VREvent_SceneApplicationUsingWrongGraphicsAdapter = 408,
	VREvent_ActionBindingReloaded		 = 409,

	VREvent_HideRenderModels			= 410,
	VREvent_ShowRenderModels			= 411,

	VREvent_SceneApplicationStateChanged = 412,

	VREvent_SceneAppPipeDisconnected    = 413,

	VREvent_ConsoleOpened               = 420,
	VREvent_ConsoleClosed               = 421,

	VREvent_OverlayShown				= 500,
	VREvent_OverlayHidden				= 501,
	VREvent_DashboardActivated			= 502,
	VREvent_DashboardDeactivated		= 503,


	VREvent_ResetDashboard				= 506,

	VREvent_ImageLoaded					= 508,
	VREvent_ShowKeyboard				= 509,
	VREvent_HideKeyboard				= 510,
	VREvent_OverlayGamepadFocusGained	= 511,
	VREvent_OverlayGamepadFocusLost		= 512,
	VREvent_OverlaySharedTextureChanged = 513,


	VREvent_ScreenshotTriggered			= 516,
	VREvent_ImageFailed					= 517,
	VREvent_DashboardOverlayCreated		= 518,
	VREvent_SwitchGamepadFocus			= 519,


	VREvent_RequestScreenshot				= 520,
	VREvent_ScreenshotTaken					= 521,
	VREvent_ScreenshotFailed				= 522,
	VREvent_SubmitScreenshotToDashboard		= 523,
	VREvent_ScreenshotProgressToDashboard	= 524,

	VREvent_PrimaryDashboardDeviceChanged	= 525,
	VREvent_RoomViewShown					= 526,
	VREvent_RoomViewHidden					= 527,
	VREvent_ShowUI							= 528,
	VREvent_ShowDevTools					= 529,
	VREvent_DesktopViewUpdating				= 530,
	VREvent_DesktopViewReady				= 531,

	VREvent_StartDashboard					= 532,
	VREvent_ElevatePrism					= 533,
	VREvent_OverlayClosed					= 534,
	VREvent_DashboardThumbChanged			= 535,
	VREvent_DesktopMightBeVisible			= 536,
	VREvent_DesktopMightBeHidden			= 537,
	VREvent_MutualSteamCapabilitiesChanged	= 538,
	VREvent_OverlayCreated					= 539,
	VREvent_OverlayDestroyed				= 540,
	VREvent_OverlayNameChanged				= 544,

	VREvent_TrackingRecordingStarted		= 541,
	VREvent_TrackingRecordingStopped		= 542,
	VREvent_SetTrackingRecordingPath		= 543,

	VREvent_Reserved_0560  					= 560,
	VREvent_Reserved_0561  					= 561,
	VREvent_Reserved_0562					= 562,
	VREvent_Reserved_0563					= 563,

	VREvent_Notification_Shown				= 600,
	VREvent_Notification_Hidden				= 601,
	VREvent_Notification_BeginInteraction	= 602,
	VREvent_Notification_Destroyed			= 603,

	VREvent_Quit							= 700,
	VREvent_ProcessQuit						= 701,

	VREvent_QuitAcknowledged				= 703,
	VREvent_DriverRequestedQuit				= 704,
	VREvent_RestartRequested				= 705,
	VREvent_InvalidateSwapTextureSets		= 706,
	VREvent_RequestDisconnectWirelessHMD	= 707,

	VREvent_ChaperoneDataHasChanged			= 800,
	VREvent_ChaperoneUniverseHasChanged		= 801,
	VREvent_ChaperoneTempDataHasChanged		= 802,
	VREvent_ChaperoneSettingsHaveChanged	= 803,
	VREvent_SeatedZeroPoseReset				= 804,
	VREvent_ChaperoneFlushCache				= 805,
	VREvent_ChaperoneRoomSetupStarting	    = 806,
	VREvent_ChaperoneRoomSetupCommitted	    = 807,
	VREvent_StandingZeroPoseReset			= 808,
	VREvent_Reserved_0809  					= 809,
	VREvent_Reserved_0810  					= 810,
	VREvent_Reserved_0811  					= 811,
	VREvent_Reserved_0812  					= 812,
	VREvent_Reserved_0813  					= 813,
	VREvent_Reserved_0814  					= 814,

	VREvent_AudioSettingsHaveChanged		= 820,

	VREvent_BackgroundSettingHasChanged				= 850,
	VREvent_CameraSettingsHaveChanged				= 851,
	VREvent_ReprojectionSettingHasChanged			= 852,
	VREvent_ModelSkinSettingsHaveChanged			= 853,
	VREvent_EnvironmentSettingsHaveChanged			= 854,
	VREvent_PowerSettingsHaveChanged				= 855,
	VREvent_EnableHomeAppSettingsHaveChanged		= 856,
	VREvent_SteamVRSectionSettingChanged			= 857,
	VREvent_LighthouseSectionSettingChanged			= 858,
	VREvent_NullSectionSettingChanged				= 859,
	VREvent_UserInterfaceSectionSettingChanged		= 860,
	VREvent_NotificationsSectionSettingChanged		= 861,
	VREvent_KeyboardSectionSettingChanged			= 862,
	VREvent_PerfSectionSettingChanged				= 863,
	VREvent_DashboardSectionSettingChanged			= 864,
	VREvent_WebInterfaceSectionSettingChanged		= 865,
	VREvent_TrackersSectionSettingChanged			= 866,
	VREvent_LastKnownSectionSettingChanged			= 867,
	VREvent_DismissedWarningsSectionSettingChanged	= 868,
	VREvent_GpuSpeedSectionSettingChanged			= 869,
	VREvent_WindowsMRSectionSettingChanged			= 870,
	VREvent_OtherSectionSettingChanged				= 871,
	VREvent_AnyDriverSettingsChanged				= 872,
	VREvent_Reserved_0873							= 873,

	VREvent_StatusUpdate					= 900,

	VREvent_WebInterface_InstallDriverCompleted = 950,

	VREvent_MCImageUpdated					= 1000,

	VREvent_FirmwareUpdateStarted			= 1100,
	VREvent_FirmwareUpdateFinished			= 1101,

	VREvent_KeyboardClosed					= 1200,
	VREvent_KeyboardCharInput				= 1201,
	VREvent_KeyboardDone					= 1202,
	VREvent_KeyboardOpened_Global			= 1203,
	VREvent_KeyboardClosed_Global			= 1204,

	VREvent_ApplicationListUpdated				= 1303,
	VREvent_ApplicationMimeTypeLoad				= 1304,
	VREvent_ProcessConnected					= 1306,
	VREvent_ProcessDisconnected					= 1307,

	VREvent_Compositor_ChaperoneBoundsShown		= 1410,
	VREvent_Compositor_ChaperoneBoundsHidden	= 1411,
	VREvent_Compositor_DisplayDisconnected		= 1412,
	VREvent_Compositor_DisplayReconnected		= 1413,
	VREvent_Compositor_HDCPError				= 1414,
	VREvent_Compositor_ApplicationNotResponding	= 1415,
	VREvent_Compositor_ApplicationResumed		= 1416,
	VREvent_Compositor_OutOfVideoMemory			= 1417,
	VREvent_Compositor_DisplayModeNotSupported	= 1418,
	VREvent_Compositor_StageOverrideReady		= 1419,
	VREvent_Compositor_RequestDisconnectReconnect = 1420,

	VREvent_TrackedCamera_StartVideoStream  = 1500,
	VREvent_TrackedCamera_StopVideoStream   = 1501,
	VREvent_TrackedCamera_PauseVideoStream  = 1502,
	VREvent_TrackedCamera_ResumeVideoStream = 1503,
	VREvent_TrackedCamera_EditingSurface    = 1550,

	VREvent_PerformanceTest_EnableCapture	= 1600,
	VREvent_PerformanceTest_DisableCapture	= 1601,
	VREvent_PerformanceTest_FidelityLevel	= 1602,

	VREvent_MessageOverlay_Closed			= 1650,
	VREvent_MessageOverlayCloseRequested	= 1651,

	VREvent_Input_HapticVibration			= 1700,
	VREvent_Input_BindingLoadFailed			= 1701,
	VREvent_Input_BindingLoadSuccessful		= 1702,
	VREvent_Input_ActionManifestReloaded	= 1703,
	VREvent_Input_ActionManifestLoadFailed	= 1704,
	VREvent_Input_ProgressUpdate			= 1705,
	VREvent_Input_TrackerActivated			= 1706,
	VREvent_Input_BindingsUpdated			= 1707,
	VREvent_Input_BindingSubscriptionChanged = 1708,

	VREvent_SpatialAnchors_PoseUpdated		= 1800,
	VREvent_SpatialAnchors_DescriptorUpdated = 1801,
	VREvent_SpatialAnchors_RequestPoseUpdate = 1802,
	VREvent_SpatialAnchors_RequestDescriptorUpdate = 1803,

	VREvent_SystemReport_Started			= 1900,

	VREvent_Monitor_ShowHeadsetView			= 2000,
	VREvent_Monitor_HideHeadsetView			= 2001,

	VREvent_Audio_SetSpeakersVolume			= 2100,
	VREvent_Audio_SetSpeakersMute			= 2101,
	VREvent_Audio_SetMicrophoneVolume		= 2102,
	VREvent_Audio_SetMicrophoneMute			= 2103,

	VREvent_RenderModel_CountChanged       = 2200,

	VREvent_VendorSpecific_Reserved_Start	= 10000,
	VREvent_VendorSpecific_Reserved_End		= 19999,
};


enum EDeviceActivityLevel
{
	k_EDeviceActivityLevel_Unknown = -1,
	k_EDeviceActivityLevel_Idle = 0,
	k_EDeviceActivityLevel_UserInteraction = 1,
	k_EDeviceActivityLevel_UserInteraction_Timeout = 2,
	k_EDeviceActivityLevel_Standby = 3,
	k_EDeviceActivityLevel_Idle_Timeout = 4,
};


enum EVRButtonId
{
	k_EButton_System			= 0,
	k_EButton_ApplicationMenu	= 1,
	k_EButton_Grip				= 2,
	k_EButton_DPad_Left			= 3,
	k_EButton_DPad_Up			= 4,
	k_EButton_DPad_Right		= 5,
	k_EButton_DPad_Down			= 6,
	k_EButton_A					= 7,

	k_EButton_ProximitySensor   = 31,

	k_EButton_Axis0				= 32,
	k_EButton_Axis1				= 33,
	k_EButton_Axis2				= 34,
	k_EButton_Axis3				= 35,
	k_EButton_Axis4				= 36,

	k_EButton_SteamVR_Touchpad	= k_EButton_Axis0,
	k_EButton_SteamVR_Trigger	= k_EButton_Axis1,

	k_EButton_Dashboard_Back	= k_EButton_Grip,

	k_EButton_IndexController_A		= k_EButton_Grip,
	k_EButton_IndexController_B		= k_EButton_ApplicationMenu,
	k_EButton_IndexController_JoyStick	= k_EButton_Axis3,

	k_EButton_Reserved0			= 50,
	k_EButton_Reserved1			= 51,

	k_EButton_Max				= 64
};

inline uint64_t ButtonMaskFromId( EVRButtonId id ) { return 1ull << id; }

struct VREvent_Controller_t
{
	uint32_t button;
};


enum EVRMouseButton
{
	VRMouseButton_Left					= 0x0001,
	VRMouseButton_Right					= 0x0002,
	VRMouseButton_Middle				= 0x0004,
};


struct VREvent_Mouse_t
{
	float x, y;
	uint32_t button;

	uint32_t cursorIndex;
};

struct VREvent_Scroll_t
{
	float xdelta, ydelta;
	uint32_t unused;
	float viewportscale;

	uint32_t cursorIndex;
};

struct VREvent_TouchPadMove_t
{
	bool bFingerDown;

	float flSecondsFingerDown;

	float fValueXFirst;
	float fValueYFirst;

	float fValueXRaw;
	float fValueYRaw;
};

struct VREvent_Notification_t
{
	uint64_t ulUserValue;
	uint32_t notificationId;
};

struct VREvent_Process_t
{
	uint32_t pid;
	uint32_t oldPid;
	bool bForced;
	bool bConnectionLost;
};


struct VREvent_Overlay_t
{
	uint64_t overlayHandle;
	uint64_t devicePath;
	uint64_t memoryBlockId;

	uint32_t cursorIndex;
};


struct VREvent_Status_t
{
	uint32_t statusState;
};

struct VREvent_Keyboard_t
{
	char cNewInput[8];
	uint64_t uUserValue;
	uint64_t overlayHandle;
};

struct VREvent_Ipd_t
{
	float ipdMeters;
};

struct VREvent_Chaperone_t
{
	uint64_t m_nPreviousUniverse_deprecated;
	uint64_t m_nCurrentUniverse;
};

struct VREvent_Reserved_t
{
	uint64_t reserved0;
	uint64_t reserved1;
	uint64_t reserved2;
	uint64_t reserved3;
	uint64_t reserved4;
	uint64_t reserved5;
};

struct VREvent_PerformanceTest_t
{
	uint32_t m_nFidelityLevel;
};

struct VREvent_SeatedZeroPoseReset_t
{
	bool bResetBySystemMenu;
};

struct VREvent_Screenshot_t
{
	uint32_t handle;
	uint32_t type;
};

struct VREvent_ScreenshotProgress_t
{
	float progress;
};

struct VREvent_ApplicationLaunch_t
{
	uint32_t pid;
	uint32_t unArgsHandle;
};

struct VREvent_EditingCameraSurface_t
{
	uint64_t overlayHandle;
	uint32_t nVisualMode;
};

struct VREvent_MessageOverlay_t
{
	uint32_t unVRMessageOverlayResponse;
};

struct VREvent_Property_t
{
	PropertyContainerHandle_t container;
	ETrackedDeviceProperty prop;
};

struct VREvent_HapticVibration_t
{
	uint64_t containerHandle;
	uint64_t componentHandle;
	float fDurationSeconds;
	float fFrequency;
	float fAmplitude;
};

struct VREvent_WebConsole_t
{
	WebConsoleHandle_t webConsoleHandle;
};

struct VREvent_InputBindingLoad_t
{
	vr::PropertyContainerHandle_t ulAppContainer;
	uint64_t pathMessage;
	uint64_t pathUrl;
	uint64_t pathControllerType;
};

struct VREvent_InputActionManifestLoad_t
{
	uint64_t pathAppKey;
	uint64_t pathMessage;
	uint64_t pathMessageParam;
	uint64_t pathManifestPath;
};

struct VREvent_SpatialAnchor_t
{
	SpatialAnchorHandle_t unHandle;
};

struct VREvent_ProgressUpdate_t
{
	uint64_t ulApplicationPropertyContainer;
	uint64_t pathDevice;
	uint64_t pathInputSource;
	uint64_t pathProgressAction;
	uint64_t pathIcon;
	float fProgress;
};

enum EShowUIType
{
	ShowUI_ControllerBinding = 0,
	ShowUI_ManageTrackers = 1,
	ShowUI_Pairing = 3,
	ShowUI_Settings = 4,
	ShowUI_DebugCommands = 5,
	ShowUI_FullControllerBinding = 6,
	ShowUI_ManageDrivers = 7,
};

struct VREvent_ShowUI_t
{
	EShowUIType eType;
};

struct VREvent_ShowDevTools_t
{
	int32_t nBrowserIdentifier;
};

enum EHDCPError
{
	HDCPError_None = 0,
	HDCPError_LinkLost = 1,
	HDCPError_Tampered = 2,
	HDCPError_DeviceRevoked = 3,
	HDCPError_Unknown = 4
};

struct VREvent_HDCPError_t
{
	EHDCPError eCode;
};

struct VREvent_AudioVolumeControl_t
{
	float fVolumeLevel;
};

struct VREvent_AudioMuteControl_t
{
	bool bMute;
};

typedef union
{
	VREvent_Reserved_t reserved;
	VREvent_Controller_t controller;
	VREvent_Mouse_t mouse;
	VREvent_Scroll_t scroll;
	VREvent_Process_t process;
	VREvent_Notification_t notification;
	VREvent_Overlay_t overlay;
	VREvent_Status_t status;
	VREvent_Keyboard_t keyboard;
	VREvent_Ipd_t ipd;
	VREvent_Chaperone_t chaperone;
	VREvent_PerformanceTest_t performanceTest;
	VREvent_TouchPadMove_t touchPadMove;
	VREvent_SeatedZeroPoseReset_t seatedZeroPoseReset;
	VREvent_Screenshot_t screenshot;
	VREvent_ScreenshotProgress_t screenshotProgress;
	VREvent_ApplicationLaunch_t applicationLaunch;
	VREvent_EditingCameraSurface_t cameraSurface;
	VREvent_MessageOverlay_t messageOverlay;
	VREvent_Property_t property;
	VREvent_HapticVibration_t hapticVibration;
	VREvent_WebConsole_t webConsole;
	VREvent_InputBindingLoad_t inputBinding;
	VREvent_InputActionManifestLoad_t actionManifest;
	VREvent_SpatialAnchor_t spatialAnchor;
	VREvent_ProgressUpdate_t progressUpdate;
	VREvent_ShowUI_t showUi;
	VREvent_ShowDevTools_t showDevTools;
	VREvent_HDCPError_t hdcpError;
	VREvent_AudioVolumeControl_t audioVolumeControl;
	VREvent_AudioMuteControl_t audioMuteControl;
} VREvent_Data_t;


#if defined(__linux__) || defined(__APPLE__)
#pragma pack( push, 4 )
#endif

struct VREvent_t
{
	uint32_t eventType;
	TrackedDeviceIndex_t trackedDeviceIndex;
	float eventAgeSeconds;
	VREvent_Data_t data;
};

#if defined(__linux__) || defined(__APPLE__)
#pragma pack( pop )
#endif

typedef uint32_t VRComponentProperties;

enum EVRComponentProperty
{
	VRComponentProperty_IsStatic = (1 << 0),
	VRComponentProperty_IsVisible = (1 << 1),
	VRComponentProperty_IsTouched = (1 << 2),
	VRComponentProperty_IsPressed = (1 << 3),
	VRComponentProperty_IsScrolled = (1 << 4),
	VRComponentProperty_IsHighlighted = (1 << 5),
};


struct RenderModel_ComponentState_t
{
	HmdMatrix34_t mTrackingToComponentRenderModel;
	HmdMatrix34_t mTrackingToComponentLocal;
	VRComponentProperties uProperties;
};


enum EVRInputError
{
	VRInputError_None = 0,
	VRInputError_NameNotFound = 1,
	VRInputError_WrongType = 2,
	VRInputError_InvalidHandle = 3,
	VRInputError_InvalidParam = 4,
	VRInputError_NoSteam = 5,
	VRInputError_MaxCapacityReached = 6,
	VRInputError_IPCError = 7,
	VRInputError_NoActiveActionSet = 8,
	VRInputError_InvalidDevice = 9,
	VRInputError_InvalidSkeleton = 10,
	VRInputError_InvalidBoneCount = 11,
	VRInputError_InvalidCompressedData = 12,
	VRInputError_NoData = 13,
	VRInputError_BufferTooSmall = 14,
	VRInputError_MismatchedActionManifest = 15,
	VRInputError_MissingSkeletonData = 16,
	VRInputError_InvalidBoneIndex = 17,
	VRInputError_InvalidPriority = 18,
	VRInputError_PermissionDenied = 19,
	VRInputError_InvalidRenderModel = 20,
};

enum EVRSpatialAnchorError
{
	VRSpatialAnchorError_Success = 0,
	VRSpatialAnchorError_Internal = 1,
	VRSpatialAnchorError_UnknownHandle = 2,
	VRSpatialAnchorError_ArrayTooSmall = 3,
	VRSpatialAnchorError_InvalidDescriptorChar = 4,
	VRSpatialAnchorError_NotYetAvailable = 5,
	VRSpatialAnchorError_NotAvailableInThisUniverse = 6,
	VRSpatialAnchorError_PermanentlyUnavailable = 7,
	VRSpatialAnchorError_WrongDriver = 8,
	VRSpatialAnchorError_DescriptorTooLong = 9,
	VRSpatialAnchorError_Unknown = 10,
	VRSpatialAnchorError_NoRoomCalibration = 11,
	VRSpatialAnchorError_InvalidArgument = 12,
	VRSpatialAnchorError_UnknownDriver = 13,
};

struct HiddenAreaMesh_t
{
	const HmdVector2_t *pVertexData;
	uint32_t unTriangleCount;
};


enum EHiddenAreaMeshType
{
	k_eHiddenAreaMesh_Standard = 0,
	k_eHiddenAreaMesh_Inverse = 1,
	k_eHiddenAreaMesh_LineLoop = 2,

	k_eHiddenAreaMesh_Max = 3,
};


enum EVRControllerAxisType
{
	k_eControllerAxis_None = 0,
	k_eControllerAxis_TrackPad = 1,
	k_eControllerAxis_Joystick = 2,
	k_eControllerAxis_Trigger = 3,
};


struct VRControllerAxis_t
{
	float x;
	float y;
};


static const uint32_t k_unControllerStateAxisCount = 5;


#if defined(__linux__) || defined(__APPLE__)
#pragma pack( push, 4 )
#endif

struct VRControllerState001_t
{
	uint32_t unPacketNum;

	uint64_t ulButtonPressed;
	uint64_t ulButtonTouched;

	VRControllerAxis_t rAxis[ k_unControllerStateAxisCount ];
};
#if defined(__linux__) || defined(__APPLE__)
#pragma pack( pop )
#endif


typedef VRControllerState001_t VRControllerState_t;


enum EVRControllerEventOutputType
{
	ControllerEventOutput_OSEvents = 0,
	ControllerEventOutput_VREvents = 1,
};



enum ECollisionBoundsStyle
{
	COLLISION_BOUNDS_STYLE_BEGINNER = 0,
	COLLISION_BOUNDS_STYLE_INTERMEDIATE,
	COLLISION_BOUNDS_STYLE_SQUARES,
	COLLISION_BOUNDS_STYLE_ADVANCED,
	COLLISION_BOUNDS_STYLE_NONE,

	COLLISION_BOUNDS_STYLE_COUNT
};

typedef uint64_t VROverlayHandle_t;

static const VROverlayHandle_t k_ulOverlayHandleInvalid = 0;

enum EVROverlayError
{
	VROverlayError_None						= 0,

	VROverlayError_UnknownOverlay			= 10,
	VROverlayError_InvalidHandle			= 11,
	VROverlayError_PermissionDenied			= 12,
	VROverlayError_OverlayLimitExceeded		= 13,
	VROverlayError_WrongVisibilityType		= 14,
	VROverlayError_KeyTooLong				= 15,
	VROverlayError_NameTooLong				= 16,
	VROverlayError_KeyInUse					= 17,
	VROverlayError_WrongTransformType		= 18,
	VROverlayError_InvalidTrackedDevice		= 19,
	VROverlayError_InvalidParameter			= 20,
	VROverlayError_ThumbnailCantBeDestroyed	= 21,
	VROverlayError_ArrayTooSmall			= 22,
	VROverlayError_RequestFailed			= 23,
	VROverlayError_InvalidTexture			= 24,
	VROverlayError_UnableToLoadFile			= 25,
	VROverlayError_KeyboardAlreadyInUse		= 26,
	VROverlayError_NoNeighbor				= 27,
	VROverlayError_TooManyMaskPrimitives	= 29,
	VROverlayError_BadMaskPrimitive			= 30,
	VROverlayError_TextureAlreadyLocked		= 31,
	VROverlayError_TextureLockCapacityReached = 32,
	VROverlayError_TextureNotLocked			= 33,
	VROverlayError_TimedOut                 = 34,
};

enum EVRApplicationType
{
	VRApplication_Other = 0,
	VRApplication_Scene	= 1,
	VRApplication_Overlay = 2,
	VRApplication_Background = 3,

	VRApplication_Utility = 4,

	VRApplication_VRMonitor = 5,
	VRApplication_SteamWatchdog = 6,
	VRApplication_Bootstrapper = 7,
	VRApplication_WebHelper = 8,
	VRApplication_OpenXRInstance = 9,
	VRApplication_OpenXRScene = 10,
	VRApplication_OpenXROverlay = 11,
	VRApplication_Prism = 12,
	VRApplication_RoomView = 13,

	VRApplication_Max
};


inline bool IsOpenXRAppType( EVRApplicationType eType )
{
	return eType == VRApplication_OpenXRInstance
		|| eType == VRApplication_OpenXRScene
		|| eType == VRApplication_OpenXROverlay;
}


inline bool BAppTypeSubmitsEyeBuffers( EVRApplicationType eType )
{
	return eType == VRApplication_Scene
		|| eType == VRApplication_OpenXRScene
		|| eType == VRApplication_RoomView;
}


enum EVRFirmwareError
{
	VRFirmwareError_None = 0,
	VRFirmwareError_Success = 1,
	VRFirmwareError_Fail = 2,
};


enum EVRNotificationError
{
	VRNotificationError_OK = 0,
	VRNotificationError_InvalidNotificationId = 100,
	VRNotificationError_NotificationQueueFull = 101,
	VRNotificationError_InvalidOverlayHandle = 102,
	VRNotificationError_SystemWithUserValueAlreadyExists = 103,
	VRNotificationError_ServiceUnavailable = 104,
};


enum EVRSkeletalMotionRange
{
	VRSkeletalMotionRange_WithController = 0,

	VRSkeletalMotionRange_WithoutController = 1,
};

enum EVRSkeletalTrackingLevel
{
	VRSkeletalTracking_Estimated = 0,

	VRSkeletalTracking_Partial = 1,

	VRSkeletalTracking_Full = 2,

	VRSkeletalTrackingLevel_Count,
	VRSkeletalTrackingLevel_Max = VRSkeletalTrackingLevel_Count - 1
};


typedef int32_t BoneIndex_t;
const BoneIndex_t k_unInvalidBoneIndex = -1;



enum EVRInitError
{
	VRInitError_None	= 0,
	VRInitError_Unknown = 1,

	VRInitError_Init_InstallationNotFound			= 100,
	VRInitError_Init_InstallationCorrupt			= 101,
	VRInitError_Init_VRClientDLLNotFound			= 102,
	VRInitError_Init_FileNotFound					= 103,
	VRInitError_Init_FactoryNotFound				= 104,
	VRInitError_Init_InterfaceNotFound				= 105,
	VRInitError_Init_InvalidInterface				= 106,
	VRInitError_Init_UserConfigDirectoryInvalid		= 107,
	VRInitError_Init_HmdNotFound					= 108,
	VRInitError_Init_NotInitialized					= 109,
	VRInitError_Init_PathRegistryNotFound			= 110,
	VRInitError_Init_NoConfigPath					= 111,
	VRInitError_Init_NoLogPath						= 112,
	VRInitError_Init_PathRegistryNotWritable		= 113,
	VRInitError_Init_AppInfoInitFailed				= 114,
	VRInitError_Init_Retry							= 115,
	VRInitError_Init_InitCanceledByUser				= 116,
	VRInitError_Init_AnotherAppLaunching			= 117,
	VRInitError_Init_SettingsInitFailed				= 118,
	VRInitError_Init_ShuttingDown					= 119,
	VRInitError_Init_TooManyObjects					= 120,
	VRInitError_Init_NoServerForBackgroundApp		= 121,
	VRInitError_Init_NotSupportedWithCompositor		= 122,
	VRInitError_Init_NotAvailableToUtilityApps		= 123,
	VRInitError_Init_Internal				 		= 124,
	VRInitError_Init_HmdDriverIdIsNone		 		= 125,
	VRInitError_Init_HmdNotFoundPresenceFailed 		= 126,
	VRInitError_Init_VRMonitorNotFound				= 127,
	VRInitError_Init_VRMonitorStartupFailed			= 128,
	VRInitError_Init_LowPowerWatchdogNotSupported	= 129,
	VRInitError_Init_InvalidApplicationType			= 130,
	VRInitError_Init_NotAvailableToWatchdogApps		= 131,
	VRInitError_Init_WatchdogDisabledInSettings		= 132,
	VRInitError_Init_VRDashboardNotFound			= 133,
	VRInitError_Init_VRDashboardStartupFailed		= 134,
	VRInitError_Init_VRHomeNotFound					= 135,
	VRInitError_Init_VRHomeStartupFailed			= 136,
	VRInitError_Init_RebootingBusy					= 137,
	VRInitError_Init_FirmwareUpdateBusy				= 138,
	VRInitError_Init_FirmwareRecoveryBusy			= 139,
	VRInitError_Init_USBServiceBusy					= 140,
	VRInitError_Init_VRWebHelperStartupFailed		= 141,
	VRInitError_Init_TrackerManagerInitFailed		= 142,
	VRInitError_Init_AlreadyRunning					= 143,
	VRInitError_Init_FailedForVrMonitor				= 144,
	VRInitError_Init_PropertyManagerInitFailed		= 145,
	VRInitError_Init_WebServerFailed				= 146,
	VRInitError_Init_IllegalTypeTransition			= 147,
	VRInitError_Init_MismatchedRuntimes				= 148,
	VRInitError_Init_InvalidProcessId				= 149,
	VRInitError_Init_VRServiceStartupFailed			= 150,
	VRInitError_Init_PrismNeedsNewDrivers			= 151,
	VRInitError_Init_PrismStartupTimedOut			= 152,
	VRInitError_Init_CouldNotStartPrism				= 153,
	VRInitError_Init_PrismClientInitFailed			= 154,
	VRInitError_Init_PrismClientStartFailed			= 155,
	VRInitError_Init_PrismExitedUnexpectedly		= 156,
	VRInitError_Init_BadLuid						= 157,
	VRInitError_Init_NoServerForAppContainer		= 158,
	VRInitError_Init_DuplicateBootstrapper			= 159,
	VRInitError_Init_VRDashboardServicePending		= 160,
	VRInitError_Init_VRDashboardServiceTimeout		= 161,
	VRInitError_Init_VRDashboardServiceStopped		= 162,
	VRInitError_Init_VRDashboardAlreadyStarted		= 163,
	VRInitError_Init_VRDashboardCopyFailed			= 164,
	VRInitError_Init_VRDashboardTokenFailure		= 165,
	VRInitError_Init_VRDashboardEnvironmentFailure	= 166,
	VRInitError_Init_VRDashboardPathFailure			= 167,
	VRInitError_Init_InstallationTooOld				= 168,
	VRInitError_Init_ClientVersionAlreadyProvided	= 169,

	VRInitError_Driver_Failed						= 200,
	VRInitError_Driver_Unknown						= 201,
	VRInitError_Driver_HmdUnknown					= 202,
	VRInitError_Driver_NotLoaded					= 203,
	VRInitError_Driver_RuntimeOutOfDate				= 204,
	VRInitError_Driver_HmdInUse						= 205,
	VRInitError_Driver_NotCalibrated				= 206,
	VRInitError_Driver_CalibrationInvalid			= 207,
	VRInitError_Driver_HmdDisplayNotFound			= 208,
	VRInitError_Driver_TrackedDeviceInterfaceUnknown = 209,
	VRInitError_Driver_HmdDriverIdOutOfBounds		= 211,
	VRInitError_Driver_HmdDisplayMirrored			= 212,
	VRInitError_Driver_HmdDisplayNotFoundLaptop		= 213,
	VRInitError_Driver_PeerDriverNotInstalled		= 214,
	VRInitError_Driver_WirelessHmdNotConnected		= 215,


	VRInitError_IPC_ServerInitFailed				= 300,
	VRInitError_IPC_ConnectFailed					= 301,
	VRInitError_IPC_SharedStateInitFailed			= 302,
	VRInitError_IPC_CompositorInitFailed			= 303,
	VRInitError_IPC_MutexInitFailed					= 304,
	VRInitError_IPC_Failed							= 305,
	VRInitError_IPC_CompositorConnectFailed			= 306,
	VRInitError_IPC_CompositorInvalidConnectResponse = 307,
	VRInitError_IPC_ConnectFailedAfterMultipleAttempts = 308,
	VRInitError_IPC_ConnectFailedAfterTargetExited = 309,
	VRInitError_IPC_NamespaceUnavailable			 = 310,

	VRInitError_Compositor_Failed												= 400,
	VRInitError_Compositor_D3D11HardwareRequired								= 401,
	VRInitError_Compositor_FirmwareRequiresUpdate								= 402,
	VRInitError_Compositor_OverlayInitFailed									= 403,
	VRInitError_Compositor_ScreenshotsInitFailed								= 404,
	VRInitError_Compositor_UnableToCreateDevice									= 405,
	VRInitError_Compositor_SharedStateIsNull									= 406,
	VRInitError_Compositor_NotificationManagerIsNull							= 407,
	VRInitError_Compositor_ResourceManagerClientIsNull							= 408,
	VRInitError_Compositor_MessageOverlaySharedStateInitFailure					= 409,
	VRInitError_Compositor_PropertiesInterfaceIsNull							= 410,
	VRInitError_Compositor_CreateFullscreenWindowFailed							= 411,
	VRInitError_Compositor_SettingsInterfaceIsNull								= 412,
	VRInitError_Compositor_FailedToShowWindow									= 413,
	VRInitError_Compositor_DistortInterfaceIsNull								= 414,
	VRInitError_Compositor_DisplayFrequencyFailure								= 415,
	VRInitError_Compositor_RendererInitializationFailed							= 416,
	VRInitError_Compositor_DXGIFactoryInterfaceIsNull							= 417,
	VRInitError_Compositor_DXGIFactoryCreateFailed								= 418,
	VRInitError_Compositor_DXGIFactoryQueryFailed								= 419,
	VRInitError_Compositor_InvalidAdapterDesktop								= 420,
	VRInitError_Compositor_InvalidHmdAttachment									= 421,
	VRInitError_Compositor_InvalidOutputDesktop									= 422,
	VRInitError_Compositor_InvalidDeviceProvided								= 423,
	VRInitError_Compositor_D3D11RendererInitializationFailed					= 424,
	VRInitError_Compositor_FailedToFindDisplayMode								= 425,
	VRInitError_Compositor_FailedToCreateSwapChain								= 426,
	VRInitError_Compositor_FailedToGetBackBuffer								= 427,
	VRInitError_Compositor_FailedToCreateRenderTarget							= 428,
	VRInitError_Compositor_FailedToCreateDXGI2SwapChain							= 429,
	VRInitError_Compositor_FailedtoGetDXGI2BackBuffer							= 430,
	VRInitError_Compositor_FailedToCreateDXGI2RenderTarget						= 431,
	VRInitError_Compositor_FailedToGetDXGIDeviceInterface						= 432,
	VRInitError_Compositor_SelectDisplayMode									= 433,
	VRInitError_Compositor_FailedToCreateNvAPIRenderTargets						= 434,
	VRInitError_Compositor_NvAPISetDisplayMode									= 435,
	VRInitError_Compositor_FailedToCreateDirectModeDisplay						= 436,
	VRInitError_Compositor_InvalidHmdPropertyContainer							= 437,
	VRInitError_Compositor_UpdateDisplayFrequency								= 438,
	VRInitError_Compositor_CreateRasterizerState								= 439,
	VRInitError_Compositor_CreateWireframeRasterizerState						= 440,
	VRInitError_Compositor_CreateSamplerState									= 441,
	VRInitError_Compositor_CreateClampToBorderSamplerState						= 442,
	VRInitError_Compositor_CreateAnisoSamplerState								= 443,
	VRInitError_Compositor_CreateOverlaySamplerState							= 444,
	VRInitError_Compositor_CreatePanoramaSamplerState							= 445,
	VRInitError_Compositor_CreateFontSamplerState								= 446,
	VRInitError_Compositor_CreateNoBlendState									= 447,
	VRInitError_Compositor_CreateBlendState										= 448,
	VRInitError_Compositor_CreateAlphaBlendState								= 449,
	VRInitError_Compositor_CreateBlendStateMaskR								= 450,
	VRInitError_Compositor_CreateBlendStateMaskG								= 451,
	VRInitError_Compositor_CreateBlendStateMaskB								= 452,
	VRInitError_Compositor_CreateDepthStencilState								= 453,
	VRInitError_Compositor_CreateDepthStencilStateNoWrite						= 454,
	VRInitError_Compositor_CreateDepthStencilStateNoDepth						= 455,
	VRInitError_Compositor_CreateFlushTexture									= 456,
	VRInitError_Compositor_CreateDistortionSurfaces								= 457,
	VRInitError_Compositor_CreateConstantBuffer									= 458,
	VRInitError_Compositor_CreateHmdPoseConstantBuffer							= 459,
	VRInitError_Compositor_CreateHmdPoseStagingConstantBuffer					= 460,
	VRInitError_Compositor_CreateSharedFrameInfoConstantBuffer					= 461,
	VRInitError_Compositor_CreateOverlayConstantBuffer							= 462,
	VRInitError_Compositor_CreateSceneTextureIndexConstantBuffer				= 463,
	VRInitError_Compositor_CreateReadableSceneTextureIndexConstantBuffer		= 464,
	VRInitError_Compositor_CreateLayerGraphicsTextureIndexConstantBuffer		= 465,
	VRInitError_Compositor_CreateLayerComputeTextureIndexConstantBuffer			= 466,
	VRInitError_Compositor_CreateLayerComputeSceneTextureIndexConstantBuffer	= 467,
	VRInitError_Compositor_CreateComputeHmdPoseConstantBuffer					= 468,
	VRInitError_Compositor_CreateGeomConstantBuffer								= 469,
	VRInitError_Compositor_CreatePanelMaskConstantBuffer						= 470,
	VRInitError_Compositor_CreatePixelSimUBO									= 471,
	VRInitError_Compositor_CreateMSAARenderTextures								= 472,
	VRInitError_Compositor_CreateResolveRenderTextures							= 473,
	VRInitError_Compositor_CreateComputeResolveRenderTextures					= 474,
	VRInitError_Compositor_CreateDriverDirectModeResolveTextures				= 475,
	VRInitError_Compositor_OpenDriverDirectModeResolveTextures					= 476,
	VRInitError_Compositor_CreateFallbackSyncTexture							= 477,
	VRInitError_Compositor_ShareFallbackSyncTexture								= 478,
	VRInitError_Compositor_CreateOverlayIndexBuffer								= 479,
	VRInitError_Compositor_CreateOverlayVertexBuffer							= 480,
	VRInitError_Compositor_CreateTextVertexBuffer								= 481,
	VRInitError_Compositor_CreateTextIndexBuffer								= 482,
	VRInitError_Compositor_CreateMirrorTextures									= 483,
	VRInitError_Compositor_CreateLastFrameRenderTexture							= 484,
	VRInitError_Compositor_CreateMirrorOverlay									= 485,
	VRInitError_Compositor_FailedToCreateVirtualDisplayBackbuffer				= 486,
	VRInitError_Compositor_DisplayModeNotSupported								= 487,
	VRInitError_Compositor_CreateOverlayInvalidCall								= 488,
	VRInitError_Compositor_CreateOverlayAlreadyInitialized						= 489,
	VRInitError_Compositor_FailedToCreateMailbox								= 490,
	VRInitError_Compositor_WindowInterfaceIsNull								= 491,
	VRInitError_Compositor_SystemLayerCreateInstance							= 492,
	VRInitError_Compositor_SystemLayerCreateSession								= 493,
	VRInitError_Compositor_CreateInverseDistortUVs								= 494,
	VRInitError_Compositor_CreateBackbufferDepth								= 495,
	VRInitError_Compositor_CannotDRMLeaseDisplay								= 496,
	VRInitError_Compositor_CannotConnectToDisplayServer							= 497,
	VRInitError_Compositor_GnomeNoDRMLeasing									= 498,
	VRInitError_Compositor_FailedToInitializeEncoder							= 499,
	VRInitError_Compositor_CreateBlurTexture									= 500,

	VRInitError_VendorSpecific_UnableToConnectToOculusRuntime		= 1000,
	VRInitError_VendorSpecific_WindowsNotInDevMode					= 1001,
	VRInitError_VendorSpecific_OculusLinkNotEnabled					= 1002,

	VRInitError_VendorSpecific_HmdFound_CantOpenDevice 				= 1101,
	VRInitError_VendorSpecific_HmdFound_UnableToRequestConfigStart	= 1102,
	VRInitError_VendorSpecific_HmdFound_NoStoredConfig 				= 1103,
	VRInitError_VendorSpecific_HmdFound_ConfigTooBig 				= 1104,
	VRInitError_VendorSpecific_HmdFound_ConfigTooSmall 				= 1105,
	VRInitError_VendorSpecific_HmdFound_UnableToInitZLib 			= 1106,
	VRInitError_VendorSpecific_HmdFound_CantReadFirmwareVersion 	= 1107,
	VRInitError_VendorSpecific_HmdFound_UnableToSendUserDataStart	= 1108,
	VRInitError_VendorSpecific_HmdFound_UnableToGetUserDataStart	= 1109,
	VRInitError_VendorSpecific_HmdFound_UnableToGetUserDataNext		= 1110,
	VRInitError_VendorSpecific_HmdFound_UserDataAddressRange		= 1111,
	VRInitError_VendorSpecific_HmdFound_UserDataError				= 1112,
	VRInitError_VendorSpecific_HmdFound_ConfigFailedSanityCheck		= 1113,
	VRInitError_VendorSpecific_OculusRuntimeBadInstall				= 1114,
	VRInitError_VendorSpecific_HmdFound_UnexpectedConfiguration_1	= 1115,

	VRInitError_VendorSpecific_Oasis_UnlockRequired					= 1150,

	VRInitError_VendorSpecific_VRLink_OutdatedDriverMESA			= 1200,
	VRInitError_VendorSpecific_VRLink_OutdatedDriverNVIDIA			= 1201,
	VRInitError_VendorSpecific_VRLink_NoVideoSupport				= 1202,

	VRInitError_Steam_SteamInstallationNotFound = 2000,

	VRInitError_LastError
};

enum EVRScreenshotType
{
	VRScreenshotType_None = 0,
	VRScreenshotType_Mono = 1,
	VRScreenshotType_Stereo = 2,
	VRScreenshotType_Cubemap = 3,
	VRScreenshotType_MonoPanorama = 4,
	VRScreenshotType_StereoPanorama = 5
};

enum EVRScreenshotPropertyFilenames
{
	VRScreenshotPropertyFilenames_Preview = 0,
	VRScreenshotPropertyFilenames_VR = 1,
};

enum EVRTrackedCameraError
{
	VRTrackedCameraError_None                       = 0,
	VRTrackedCameraError_OperationFailed            = 100,
	VRTrackedCameraError_InvalidHandle              = 101,
	VRTrackedCameraError_InvalidFrameHeaderVersion  = 102,
	VRTrackedCameraError_OutOfHandles               = 103,
	VRTrackedCameraError_IPCFailure                 = 104,
	VRTrackedCameraError_NotSupportedForThisDevice  = 105,
	VRTrackedCameraError_SharedMemoryFailure        = 106,
	VRTrackedCameraError_FrameBufferingFailure      = 107,
	VRTrackedCameraError_StreamSetupFailure         = 108,
	VRTrackedCameraError_InvalidGLTextureId         = 109,
	VRTrackedCameraError_InvalidSharedTextureHandle = 110,
	VRTrackedCameraError_FailedToGetGLTextureId     = 111,
	VRTrackedCameraError_SharedTextureFailure       = 112,
	VRTrackedCameraError_NoFrameAvailable           = 113,
	VRTrackedCameraError_InvalidArgument            = 114,
	VRTrackedCameraError_InvalidFrameBufferSize     = 115,
};

enum EVRTrackedCameraFrameLayout
{
	EVRTrackedCameraFrameLayout_Mono				= 0x0001,
	EVRTrackedCameraFrameLayout_Stereo				= 0x0002,
	EVRTrackedCameraFrameLayout_VerticalLayout		= 0x0010,
	EVRTrackedCameraFrameLayout_HorizontalLayout	= 0x0020,
};

enum EVRTrackedCameraFrameType
{
	VRTrackedCameraFrameType_Distorted = 0,
	VRTrackedCameraFrameType_Undistorted,
	VRTrackedCameraFrameType_MaximumUndistorted,
	MAX_CAMERA_FRAME_TYPES
};

enum EVRDistortionFunctionType
{
	VRDistortionFunctionType_None,
	VRDistortionFunctionType_FTheta,
	VRDistortionFunctionType_Extended_FTheta,
	MAX_DISTORTION_FUNCTION_TYPES,
};

static const uint32_t k_unMaxDistortionFunctionParameters = 8;

typedef uint64_t TrackedCameraHandle_t;
#define INVALID_TRACKED_CAMERA_HANDLE	((vr::TrackedCameraHandle_t)0)

struct CameraVideoStreamFrameHeader_t
{
	EVRTrackedCameraFrameType eFrameType;

	uint32_t nWidth;
	uint32_t nHeight;
	uint32_t nBytesPerPixel;

	uint32_t nFrameSequence;

	TrackedDevicePose_t trackedDevicePose;

	uint64_t ulFrameExposureTime;
};

typedef uint32_t ScreenshotHandle_t;

static const uint32_t k_unScreenshotHandleInvalid = 0;

const uint32_t VRCompositor_ReprojectionReason_Cpu = 0x01;
const uint32_t VRCompositor_ReprojectionReason_Gpu = 0x02;
const uint32_t VRCompositor_ReprojectionAsync = 0x04;






const uint32_t VRCompositor_ReprojectionMotion = 0x08;

const uint32_t VRCompositor_PredictionMask = 0xF0;





const uint32_t VRCompositor_ThrottleMask = 0xF00;



#define VR_COMPOSITOR_ADDITIONAL_PREDICTED_FRAMES( timing ) ( ( ( timing ).m_nReprojectionFlags & vr::VRCompositor_PredictionMask ) >> 4 )
#define VR_COMPOSITOR_NUMBER_OF_THROTTLED_FRAMES( timing ) ( ( ( timing ).m_nReprojectionFlags & vr::VRCompositor_ThrottleMask ) >> 8 )

#if defined(__linux__) || defined(__APPLE__)
#pragma pack( push, 4 )
#endif
struct Compositor_FrameTiming
{
	uint32_t m_nSize;
	uint32_t m_nFrameIndex;
	uint32_t m_nNumFramePresents;
	uint32_t m_nNumMisPresented;
	uint32_t m_nNumDroppedFrames;
	uint32_t m_nReprojectionFlags;

	double m_flSystemTimeInSeconds;





	float m_flPreSubmitGpuMs;
	float m_flPostSubmitGpuMs;
	float m_flTotalRenderGpuMs;
	float m_flCompositorRenderGpuMs;
	float m_flCompositorRenderCpuMs;
	float m_flCompositorIdleCpuMs;


	float m_flClientFrameIntervalMs;
	float m_flPresentCallCpuMs;
	float m_flWaitForPresentCpuMs;
	float m_flSubmitFrameMs;


	float m_flWaitGetPosesCalledMs;
	float m_flNewPosesReadyMs;
	float m_flNewFrameReadyMs;
	float m_flCompositorUpdateStartMs;
	float m_flCompositorUpdateEndMs;
	float m_flCompositorRenderStartMs;

	vr::TrackedDevicePose_t m_HmdPose;

	uint32_t m_nNumVSyncsReadyForUse;
	uint32_t m_nNumVSyncsToFirstView;

	float m_flTransferLatencyMs;
};
#if defined(__linux__) || defined(__APPLE__)
#pragma pack( pop )
#endif

struct Compositor_BenchmarkResults
{
	float m_flMegaPixelsPerSecond;
	float m_flHmdRecommendedMegaPixelsPerSecond;
};

struct DriverDirectMode_FrameTiming
{
	uint32_t m_nSize;
	uint32_t m_nNumFramePresents;
	uint32_t m_nNumMisPresented;
	uint32_t m_nNumDroppedFrames;
	uint32_t m_nReprojectionFlags;
};


const uint32_t VRCompositor_ReprojectionMotion_Enabled = 0x100;
const uint32_t VRCompositor_ReprojectionMotion_ForcedOn = 0x200;
const uint32_t VRCompositor_ReprojectionMotion_AppThrottled = 0x400;


enum EVSync
{
	VSync_None,
	VSync_WaitRender,
	VSync_NoWaitRender,
};

enum EVRMuraCorrectionMode
{
	EVRMuraCorrectionMode_Default = 0,
	EVRMuraCorrectionMode_NoCorrection
};

enum Imu_OffScaleFlags
{
	OffScale_AccelX	= 0x01,
	OffScale_AccelY	= 0x02,
	OffScale_AccelZ	= 0x04,
	OffScale_GyroX	= 0x08,
	OffScale_GyroY	= 0x10,
	OffScale_GyroZ	= 0x20,
};

struct ImuSample_t
{
	double fSampleTime;
	HmdVector3d_t vAccel;
	HmdVector3d_t vGyro;
	uint32_t unOffScaleFlags;
};

enum class EVRDistortionChannel : uint32_t
{
	Red = 0,
	Green,
	Blue,
	InverseRed,
	InverseGreen,
	InverseBlue,
	Count
};

struct DistortionCoordinate_t
{
	float u, v;
};

#pragma pack( pop )

#if defined(_WIN32)

  #if !defined(OPENVR_BUILD_STATIC)
    #ifdef VR_API_EXPORT
      #define VR_INTERFACE extern "C" __declspec( dllexport )
    #else
      #define VR_INTERFACE extern "C" __declspec( dllimport )
    #endif
  #else
    #define VR_INTERFACE extern "C"
  #endif

#elif defined(__GNUC__) || defined(COMPILER_GCC) || defined(__APPLE__)

#ifdef VR_API_EXPORT
  #define VR_INTERFACE extern "C" __attribute__((visibility("default")))
#else
  #define VR_INTERFACE extern "C"
#endif

#else
  #error "Unsupported Platform."
#endif


#if defined( _WIN32 )
  #define VR_CALLTYPE __cdecl
#else
  #define VR_CALLTYPE
#endif

}
#endif


#ifdef API_GEN
# define VR_CLANG_ATTR(ATTR) __attribute__((annotate( ATTR )))
#else
# define VR_CLANG_ATTR(ATTR)
#endif

#define VR_METHOD_DESC(DESC) VR_CLANG_ATTR( "desc:" #DESC ";" )
#define VR_IGNOREATTR() VR_CLANG_ATTR( "ignore" )
#define VR_OUT_STRUCT() VR_CLANG_ATTR( "out_struct: ;" )
#define VR_OUT_STRING() VR_CLANG_ATTR( "out_string: ;" )
#define VR_OUT_ARRAY_CALL(COUNTER,FUNCTION,PARAMS) VR_CLANG_ATTR( "out_array_call:" #COUNTER "," #FUNCTION "," #PARAMS ";" )
#define VR_OUT_ARRAY_COUNT(COUNTER) VR_CLANG_ATTR( "out_array_count:" #COUNTER ";" )
#define VR_ARRAY_COUNT(COUNTER) VR_CLANG_ATTR( "array_count:" #COUNTER ";" )
#define VR_ARRAY_COUNT_D(COUNTER, DESC) VR_CLANG_ATTR( "array_count:" #COUNTER ";desc:" #DESC )
#define VR_BUFFER_COUNT(COUNTER) VR_CLANG_ATTR( "buffer_count:" #COUNTER ";" )
#define VR_OUT_BUFFER_COUNT(COUNTER) VR_CLANG_ATTR( "out_buffer_count:" #COUNTER ";" )
#define VR_OUT_STRING_COUNT(COUNTER) VR_CLANG_ATTR( "out_string_count:" #COUNTER ";" )


#ifndef _VRTRACKEDCAMERATYPES_H
#define _VRTRACKEDCAMERATYPES_H

namespace vr
{

#pragma pack( push, 8 )

enum ECameraVideoStreamFormat
{
	CVS_FORMAT_UNKNOWN = 0,
	CVS_FORMAT_RAW10 = 1,
	CVS_FORMAT_NV12 = 2,
	CVS_FORMAT_RGB24 = 3,
	CVS_FORMAT_NV12_2 = 4,
	CVS_FORMAT_YUYV16 = 5,
	CVS_FORMAT_BAYER16BG = 6,
	CVS_FORMAT_MJPEG = 7,
	CVS_FORMAT_RGBX32 = 8,
	CVS_MAX_FORMATS
};

enum ECameraCompatibilityMode
{
	CAMERA_COMPAT_MODE_BULK_DEFAULT = 0,
	CAMERA_COMPAT_MODE_BULK_64K_DMA = 1,
	CAMERA_COMPAT_MODE_BULK_16K_DMA = 2,
	CAMERA_COMPAT_MODE_BULK_8K_DMA = 3,
	CAMERA_COMPAT_MODE_ISO_52FPS = 4,
	CAMERA_COMPAT_MODE_ISO_50FPS = 5,
	CAMERA_COMPAT_MODE_ISO_48FPS = 6,
	CAMERA_COMPAT_MODE_ISO_46FPS = 7,
	CAMERA_COMPAT_MODE_ISO_44FPS = 8,
	CAMERA_COMPAT_MODE_ISO_42FPS = 9,
	CAMERA_COMPAT_MODE_ISO_40FPS = 10,
	CAMERA_COMPAT_MODE_ISO_35FPS = 11,
	CAMERA_COMPAT_MODE_ISO_30FPS = 12,
	CAMERA_COMPAT_MODE_ISO_15FPS = 13,
	MAX_CAMERA_COMPAT_MODES
};

enum ECameraRoomViewStyle
{
	CAMERA_ROOMVIEW_STYLE_DEFAULT = 0,
	CAMERA_ROOMVIEW_STYLE_EDGE_A = 1,
	CAMERA_ROOMVIEW_STYLE_EDGE_B = 2,
	CAMERA_ROOMVIEW_STYLE_VIDEO_TRANSLUSCENT = 3,
	CAMERA_ROOMVIEW_STYLE_VIDEO_OPAQUE = 4,
	CAMERA_ROOMVIEW_STYLE_COUNT = 5,
};

#ifdef _MSC_VER
#define VR_CAMERA_DECL_ALIGN( x ) __declspec( align( x ) )
#else
#define VR_CAMERA_DECL_ALIGN( x )
#endif

static const uint32_t k_unMaxCameras = 4;
static const uint32_t k_unMaxCameraFrameSharedHandles = 4;

VR_CAMERA_DECL_ALIGN( 8 ) struct CameraVideoStreamFrame_t
{
	ECameraVideoStreamFormat m_nStreamFormat;

	uint32_t m_nWidth;
	uint32_t m_nHeight;

	uint32_t m_nImageDataSize;

	uint32_t m_nFrameSequence;

	uint32_t m_nBufferIndex;
	uint32_t m_nBufferCount;

	uint32_t m_nExposureTime;

	uint32_t m_nISPFrameTimeStamp;
	uint32_t m_nISPReferenceTimeStamp;
	uint32_t m_nSyncCounter;

	uint32_t m_nCamSyncEvents;
	uint32_t m_nISPSyncEvents;

	double m_flReferenceCamSyncTime;

	double m_flFrameElapsedTime;
	double m_flFrameDeliveryRate;

	double m_flFrameCaptureTime_DriverAbsolute;
	double m_flFrameCaptureTime_ServerRelative;
	uint64_t m_nFrameCaptureTicks_ServerAbsolute;
	double m_flFrameCaptureTime_ClientRelative;

	double m_flSyncMarkerError;

	TrackedDevicePose_t m_RawTrackedDevicePose;

	uint64_t m_pImageData;
};

#pragma pack( pop )

}

#endif


#ifndef OPENVR_NO_STL
#include <string>
#endif

namespace vr
{
	enum EVRSettingsError
	{
		VRSettingsError_None = 0,
		VRSettingsError_IPCFailed = 1,
		VRSettingsError_WriteFailed = 2,
		VRSettingsError_ReadFailed = 3,
		VRSettingsError_JsonParseFailed = 4,
		VRSettingsError_UnsetSettingHasNoDefault = 5,
		VRSettingsError_AccessDenied = 6,
	};

	static const uint32_t k_unMaxSettingsKeyLength = 128;

	class IVRSettings
	{
	public:
		virtual const char *GetSettingsErrorNameFromEnum( EVRSettingsError eError ) = 0;

		virtual void SetBool( const char *pchSection, const char *pchSettingsKey, bool bValue, EVRSettingsError *peError = nullptr ) = 0;
		virtual void SetInt32( const char *pchSection, const char *pchSettingsKey, int32_t nValue, EVRSettingsError *peError = nullptr ) = 0;
		virtual void SetFloat( const char *pchSection, const char *pchSettingsKey, float flValue, EVRSettingsError *peError = nullptr ) = 0;
		virtual void SetString( const char *pchSection, const char *pchSettingsKey, const char *pchValue, EVRSettingsError *peError = nullptr ) = 0;

		virtual bool GetBool( const char *pchSection, const char *pchSettingsKey, EVRSettingsError *peError = nullptr ) = 0;
		virtual int32_t GetInt32( const char *pchSection, const char *pchSettingsKey, EVRSettingsError *peError = nullptr ) = 0;
		virtual float GetFloat( const char *pchSection, const char *pchSettingsKey, EVRSettingsError *peError = nullptr ) = 0;
		virtual void GetString( const char *pchSection, const char *pchSettingsKey, VR_OUT_STRING() char *pchValue, uint32_t unValueLen, EVRSettingsError *peError = nullptr ) = 0;

		virtual void RemoveSection( const char *pchSection, EVRSettingsError *peError = nullptr ) = 0;
		virtual void RemoveKeyInSection( const char *pchSection, const char *pchSettingsKey, EVRSettingsError *peError = nullptr ) = 0;
	};

	static const char * const IVRSettings_Version = "IVRSettings_003";

	class CVRSettingHelper
	{
		IVRSettings *m_pSettings;
	public:
		CVRSettingHelper( IVRSettings *pSettings )
		{
			m_pSettings = pSettings;
		}

		const char *GetSettingsErrorNameFromEnum( EVRSettingsError eError )
		{
			return m_pSettings->GetSettingsErrorNameFromEnum( eError );
		}

		void SetBool( const char *pchSection, const char *pchSettingsKey, bool bValue, EVRSettingsError *peError = nullptr )
		{
			m_pSettings->SetBool( pchSection, pchSettingsKey, bValue, peError );
		}

		void SetInt32( const char *pchSection, const char *pchSettingsKey, int32_t nValue, EVRSettingsError *peError = nullptr )
		{
			m_pSettings->SetInt32( pchSection, pchSettingsKey, nValue, peError );
		}
		void SetFloat( const char *pchSection, const char *pchSettingsKey, float flValue, EVRSettingsError *peError = nullptr )
		{
			m_pSettings->SetFloat( pchSection, pchSettingsKey, flValue, peError );
		}
		void SetString( const char *pchSection, const char *pchSettingsKey, const char *pchValue, EVRSettingsError *peError = nullptr )
		{
			m_pSettings->SetString( pchSection, pchSettingsKey, pchValue, peError );
		}
#ifndef OPENVR_NO_STL
		void SetString( const std::string & sSection, const std::string &  sSettingsKey, const std::string & sValue, EVRSettingsError *peError = nullptr )
		{
			m_pSettings->SetString( sSection.c_str(), sSettingsKey.c_str(), sValue.c_str(), peError );
		}
#endif

		bool GetBool( const char *pchSection, const char *pchSettingsKey, EVRSettingsError *peError = nullptr )
		{
			return m_pSettings->GetBool( pchSection, pchSettingsKey, peError );
		}
		int32_t GetInt32( const char *pchSection, const char *pchSettingsKey, EVRSettingsError *peError = nullptr )
		{
			return m_pSettings->GetInt32( pchSection, pchSettingsKey, peError );
		}
		float GetFloat( const char *pchSection, const char *pchSettingsKey, EVRSettingsError *peError = nullptr )
		{
			return m_pSettings->GetFloat( pchSection, pchSettingsKey, peError );
		}
		void GetString( const char *pchSection, const char *pchSettingsKey, VR_OUT_STRING() char *pchValue, uint32_t unValueLen, EVRSettingsError *peError = nullptr )
		{
			m_pSettings->GetString( pchSection, pchSettingsKey, pchValue, unValueLen, peError );
		}
#ifndef OPENVR_NO_STL
		std::string GetString( const std::string & sSection, const std::string & sSettingsKey, EVRSettingsError *peError = nullptr )
		{
			char buf[4096];
			vr::EVRSettingsError eError;
			m_pSettings->GetString( sSection.c_str(), sSettingsKey.c_str(), buf, sizeof( buf ), &eError );
			if ( peError )
				*peError = eError;
			if ( eError == vr::VRSettingsError_None )
				return buf;
			else
				return "";
		}
#endif

		void RemoveSection( const char *pchSection, EVRSettingsError *peError = nullptr )
		{
			m_pSettings->RemoveSection( pchSection, peError );
		}
		void RemoveKeyInSection( const char *pchSection, const char *pchSettingsKey, EVRSettingsError *peError = nullptr )
		{
			m_pSettings->RemoveKeyInSection( pchSection, pchSettingsKey, peError );
		}
	};


	static const char * const k_pch_SteamVR_Section = "steamvr";
	static const char * const k_pch_SteamVR_Contrast_Float = "contrast";
	static const char * const k_pch_SteamVR_RequireHmd_String = "requireHmd";
	static const char * const k_pch_SteamVR_ForcedDriverKey_String = "forcedDriver";
	static const char * const k_pch_SteamVR_ForcedHmdKey_String = "forcedHmd";
	static const char * const k_pch_SteamVR_DisplayDebug_Bool = "displayDebug";
	static const char * const k_pch_SteamVR_DebugProcessPipe_String = "debugProcessPipe";
	static const char * const k_pch_SteamVR_DisplayDebugX_Int32 = "displayDebugX";
	static const char * const k_pch_SteamVR_DisplayDebugY_Int32 = "displayDebugY";
	static const char * const k_pch_SteamVR_SendSystemButtonToAllApps_Bool= "sendSystemButtonToAllApps";
	static const char * const k_pch_SteamVR_LogLevel_Int32 = "loglevel";
	static const char * const k_pch_SteamVR_IPD_Float = "ipd";
	static const char * const k_pch_SteamVR_Background_String = "background";
	static const char * const k_pch_SteamVR_BackgroundUseDomeProjection_Bool = "backgroundUseDomeProjection";
	static const char * const k_pch_SteamVR_BackgroundCameraHeight_Float = "backgroundCameraHeight";
	static const char * const k_pch_SteamVR_BackgroundDomeRadius_Float = "backgroundDomeRadius";
	static const char * const k_pch_SteamVR_GridColor_String = "gridColor";
	static const char * const k_pch_SteamVR_PlayAreaColor_String = "playAreaColor";
	static const char * const k_pch_SteamVR_TrackingLossColor_String = "trackingLossColor";
	static const char * const k_pch_SteamVR_StartColor_String = "startColor";
	static const char * const k_pch_SteamVR_ShowStage_Bool = "showStage";
	static const char * const k_pch_SteamVR_DrawTrackingReferences_Bool = "drawTrackingReferences";
	static const char * const k_pch_SteamVR_ActivateMultipleDrivers_Bool = "activateMultipleDrivers";
	static const char * const k_pch_SteamVR_UsingSpeakers_Bool = "usingSpeakers";
	static const char * const k_pch_SteamVR_SpeakersForwardYawOffsetDegrees_Float = "speakersForwardYawOffsetDegrees";
	static const char * const k_pch_SteamVR_BaseStationPowerManagement_Int32 = "basestationPowerManagement";
	static const char * const k_pch_SteamVR_ShowBaseStationPowerManagementTip_Int32 = "ShowBaseStationPowerManagementTip";
	static const char * const k_pch_SteamVR_NeverKillProcesses_Bool = "neverKillProcesses";
	static const char * const k_pch_SteamVR_SupersampleScale_Float = "supersampleScale";
	static const char * const k_pch_SteamVR_MaxRecommendedResolution_Int32 = "maxRecommendedResolution";
	static const char * const k_pch_SteamVR_MotionSmoothing_Bool = "motionSmoothing";
	static const char * const k_pch_SteamVR_MotionSmoothingOverride_Int32 = "motionSmoothingOverride";
	static const char * const k_pch_SteamVR_FoveatedSharpening_Bool = "sharpening";
	static const char * const k_pch_SteamVR_FoveatedSharpeningOverride_Int32 = "sharpeningOverride";
	static const char * const k_pch_SteamVR_FramesToThrottle_Int32 = "framesToThrottle";
	static const char * const k_pch_SteamVR_AdditionalFramesToPredict_Int32 = "additionalFramesToPredict";
	static const char * const k_pch_SteamVR_WorldScale_Float = "worldScale";
	static const char * const k_pch_SteamVR_FovScale_Int32 = "fovScale";
	static const char * const k_pch_SteamVR_FovScaleInner_Int32 = "fovScaleInner";
	static const char * const k_pch_SteamVR_FovScaleUpper_Int32 = "fovScaleUpper";
	static const char * const k_pch_SteamVR_FovScaleLower_Int32 = "fovScaleLower";
	static const char * const k_pch_SteamVR_FovScaleFormat_Int32 = "fovScaleFormat";
	static const char * const k_pch_SteamVR_FovScaleLetterboxed_Bool = "fovScaleLetterboxed";
	static const char * const k_pch_SteamVR_DisableAsyncReprojection_Bool = "disableAsync";
	static const char * const k_pch_SteamVR_ForceFadeOnBadTracking_Bool = "forceFadeOnBadTracking";
	static const char * const k_pch_SteamVR_DefaultMirrorView_Int32 = "mirrorView";
	static const char * const k_pch_SteamVR_ShowLegacyMirrorView_Bool = "showLegacyMirrorView";
	static const char * const k_pch_SteamVR_MirrorViewVisibility_Bool = "showMirrorView";
	static const char * const k_pch_SteamVR_MirrorViewDisplayMode_Int32 = "mirrorViewDisplayMode";
	static const char * const k_pch_SteamVR_MirrorViewEye_Int32 = "mirrorViewEye";
	static const char * const k_pch_SteamVR_MirrorViewGeometry_String = "mirrorViewGeometry";
	static const char * const k_pch_SteamVR_MirrorViewGeometryMaximized_String = "mirrorViewGeometryMaximized";
	static const char * const k_pch_SteamVR_PerfGraphVisibility_Bool = "showPerfGraph";
	static const char * const k_pch_SteamVR_StartMonitorFromAppLaunch = "startMonitorFromAppLaunch";
	static const char * const k_pch_SteamVR_StartCompositorFromAppLaunch_Bool = "startCompositorFromAppLaunch";
	static const char * const k_pch_SteamVR_StartDashboardFromAppLaunch_Bool = "startDashboardFromAppLaunch";
	static const char * const k_pch_SteamVR_StartOverlayAppsFromDashboard_Bool = "startOverlayAppsFromDashboard";
	static const char * const k_pch_SteamVR_EnableHomeApp = "enableHomeApp";
	static const char * const k_pch_SteamVR_CycleBackgroundImageTimeSec_Int32 = "CycleBackgroundImageTimeSec";
	static const char * const k_pch_SteamVR_RetailDemo_Bool = "retailDemo";
	static const char * const k_pch_SteamVR_IpdOffset_Float = "ipdOffset";
	static const char * const k_pch_SteamVR_AllowSupersampleFiltering_Bool = "allowSupersampleFiltering";
	static const char * const k_pch_SteamVR_SupersampleManualOverride_Bool = "supersampleManualOverride";
	static const char * const k_pch_SteamVR_EnableLinuxVulkanAsync_Bool = "enableLinuxVulkanAsync";
	static const char * const k_pch_SteamVR_AllowDisplayLockedMode_Bool = "allowDisplayLockedMode";
	static const char * const k_pch_SteamVR_HaveStartedTutorialForNativeChaperoneDriver_Bool = "haveStartedTutorialForNativeChaperoneDriver";
	static const char * const k_pch_SteamVR_DebugInputBinding = "debugInputBinding";
	static const char * const k_pch_SteamVR_DoNotFadeToGrid = "doNotFadeToGrid";
	static const char * const k_pch_SteamVR_EnableSharedResourceJournaling = "enableSharedResourceJournaling";
	static const char * const k_pch_SteamVR_EnableSafeMode = "enableSafeMode";
	static const char * const k_pch_SteamVR_PreferredRefreshRate = "preferredRefreshRate";
	static const char * const k_pch_SteamVR_LastVersionNotice = "lastVersionNotice";
	static const char * const k_pch_SteamVR_LastVersionNoticeDate = "lastVersionNoticeDate";
	static const char * const k_pch_SteamVR_HmdDisplayColorGainR_Float = "hmdDisplayColorGainR";
	static const char * const k_pch_SteamVR_HmdDisplayColorGainG_Float = "hmdDisplayColorGainG";
	static const char * const k_pch_SteamVR_HmdDisplayColorGainB_Float = "hmdDisplayColorGainB";
	static const char * const k_pch_SteamVR_CustomIconStyle_String = "customIconStyle";
	static const char * const k_pch_SteamVR_CustomOffIconStyle_String = "customOffIconStyle";
	static const char * const k_pch_SteamVR_CustomIconForceUpdate_String = "customIconForceUpdate";
	static const char * const k_pch_SteamVR_AllowGlobalActionSetPriority = "globalActionSetPriority";
	static const char * const k_pch_SteamVR_OverlayRenderQuality = "overlayRenderQuality_2";
	static const char * const k_pch_SteamVR_BlockOculusSDKOnOpenVRLaunchOption_Bool = "blockOculusSDKOnOpenVRLaunchOption";
	static const char * const k_pch_SteamVR_BlockOculusSDKOnAllLaunches_Bool = "blockOculusSDKOnAllLaunches";
	static const char * const k_pch_SteamVR_HDCPLegacyCompatibility_Bool = "hdcp14legacyCompatibility";
	static const char * const k_pch_SteamVR_DisplayPortTrainingMode_Int = "displayPortTrainingMode";
	static const char * const k_pch_SteamVR_UsePrism_Bool = "usePrism";
	static const char * const k_pch_SteamVR_AllowFallbackMirrorWindowLinux_Bool = "allowFallbackMirrorWindowLinux";
	static const char * const k_pch_SteamVR_DisableKeyboardPrivacy_Bool = "disableKeyboardPrivacy";

	static const char * const k_pch_OpenXR_Section = "openxr";
	static const char * const k_pch_OpenXR_MetaUnityPluginCompatibility_Int32 = "metaUnityPluginCompatibility";

	static const char * const k_pch_DirectMode_Section = "direct_mode";
	static const char * const k_pch_DirectMode_Enable_Bool = "enable";
	static const char * const k_pch_DirectMode_Count_Int32 = "count";
	static const char * const k_pch_DirectMode_EdidVid_Int32 = "edidVid";
	static const char * const k_pch_DirectMode_EdidPid_Int32 = "edidPid";

	static const char * const k_pch_Lighthouse_Section = "driver_lighthouse";
	static const char * const k_pch_Lighthouse_DisableIMU_Bool = "disableimu";
	static const char * const k_pch_Lighthouse_DisableIMUExceptHMD_Bool = "disableimuexcepthmd";
	static const char * const k_pch_Lighthouse_UseDisambiguation_String = "usedisambiguation";
	static const char * const k_pch_Lighthouse_DisambiguationDebug_Int32 = "disambiguationdebug";
	static const char * const k_pch_Lighthouse_PrimaryBasestation_Int32 = "primarybasestation";
	static const char * const k_pch_Lighthouse_DBHistory_Bool = "dbhistory";
	static const char * const k_pch_Lighthouse_EnableBluetooth_Bool = "enableBluetooth";
	static const char * const k_pch_Lighthouse_PowerManagedBaseStations_String = "PowerManagedBaseStations";
	static const char * const k_pch_Lighthouse_PowerManagedBaseStations2_String = "PowerManagedBaseStations2";
	static const char * const k_pch_Lighthouse_InactivityTimeoutForBaseStations_Int32 = "InactivityTimeoutForBaseStations";
	static const char * const k_pch_Lighthouse_EnableImuFallback_Bool = "enableImuFallback";

	static const char * const k_pch_Null_Section = "driver_null";
	static const char * const k_pch_Null_SerialNumber_String = "serialNumber";
	static const char * const k_pch_Null_ModelNumber_String = "modelNumber";
	static const char * const k_pch_Null_WindowX_Int32 = "windowX";
	static const char * const k_pch_Null_WindowY_Int32 = "windowY";
	static const char * const k_pch_Null_WindowWidth_Int32 = "windowWidth";
	static const char * const k_pch_Null_WindowHeight_Int32 = "windowHeight";
	static const char * const k_pch_Null_RenderWidth_Int32 = "renderWidth";
	static const char * const k_pch_Null_RenderHeight_Int32 = "renderHeight";
	static const char * const k_pch_Null_SecondsFromVsyncToPhotons_Float = "secondsFromVsyncToPhotons";
	static const char * const k_pch_Null_DisplayFrequency_Float = "displayFrequency";

	static const char * const k_pch_WindowsMR_Section = "driver_holographic";

	static const char * const k_pch_UserInterface_Section = "userinterface";
	static const char * const k_pch_UserInterface_StatusAlwaysOnTop_Bool = "StatusAlwaysOnTop";
	static const char * const k_pch_UserInterface_MinimizeToTray_Bool = "MinimizeToTray";
	static const char * const k_pch_UserInterface_HidePopupsWhenStatusMinimized_Bool = "HidePopupsWhenStatusMinimized";
	static const char * const k_pch_UserInterface_Screenshots_Bool = "screenshots";
	static const char * const k_pch_UserInterface_ScreenshotType_Int = "screenshotType";
	static const char * const k_pch_UserInterface_CheckStatusInterval_Int = "vrmStatusCheckInterval";
	static const char * const k_pch_UserInterface_CheckForSteam_Bool = "vrmCheckForSteam";

	static const char * const k_pch_Notifications_Section = "notifications";
	static const char * const k_pch_Notifications_DoNotDisturb_Bool = "DoNotDisturb";

	static const char * const k_pch_Keyboard_Section = "keyboard";
	static const char * const k_pch_Keyboard_TutorialCompletions = "TutorialCompletions";
	static const char * const k_pch_Keyboard_ScaleX = "ScaleX";
	static const char * const k_pch_Keyboard_ScaleY = "ScaleY";
	static const char * const k_pch_Keyboard_OffsetLeftX = "OffsetLeftX";
	static const char * const k_pch_Keyboard_OffsetRightX = "OffsetRightX";
	static const char * const k_pch_Keyboard_OffsetY = "OffsetY";
	static const char * const k_pch_Keyboard_Smoothing = "Smoothing";

	static const char * const k_pch_Perf_Section = "perfcheck";
	static const char * const k_pch_Perf_PerfGraphInHMD_Bool = "perfGraphInHMD";
	static const char * const k_pch_Perf_AllowTimingStore_Bool = "allowTimingStore";
	static const char * const k_pch_Perf_SaveTimingsOnExit_Bool = "saveTimingsOnExit";
	static const char * const k_pch_Perf_TestData_Float = "perfTestData";
	static const char * const k_pch_Perf_GPUProfiling_Bool = "GPUProfiling";
	static const char * const k_pch_Perf_GpuBusMonitoring_Bool = "gpuBusMonitoring";

	static const char * const k_pch_CollisionBounds_Section = "collisionBounds";
	static const char * const k_pch_CollisionBounds_Style_Int32 = "CollisionBoundsStyle";
	static const char * const k_pch_CollisionBounds_GroundPerimeterOn_Bool = "CollisionBoundsGroundPerimeterOn";
	static const char * const k_pch_CollisionBounds_CenterMarkerOn_Bool = "CollisionBoundsCenterMarkerOn";
	static const char * const k_pch_CollisionBounds_PlaySpaceOn_Bool = "CollisionBoundsPlaySpaceOn";
	static const char * const k_pch_CollisionBounds_FadeDistance_Float = "CollisionBoundsFadeDistance";
	static const char * const k_pch_CollisionBounds_WallHeight_Float = "CollisionBoundsWallHeight";
	static const char * const k_pch_CollisionBounds_ColorGammaR_Int32 = "CollisionBoundsColorGammaR";
	static const char * const k_pch_CollisionBounds_ColorGammaG_Int32 = "CollisionBoundsColorGammaG";
	static const char * const k_pch_CollisionBounds_ColorGammaB_Int32 = "CollisionBoundsColorGammaB";
	static const char * const k_pch_CollisionBounds_ColorGammaA_Int32 = "CollisionBoundsColorGammaA";
	static const char * const k_pch_CollisionBounds_EnableDriverImport = "enableDriverBoundsImport";

	static const char * const k_pch_Camera_Section = "camera";
	static const char * const k_pch_Camera_EnableCamera_Bool = "enableCamera";
	static const char * const k_pch_Camera_ShowOnController_Bool = "showOnController";
	static const char * const k_pch_Camera_EnableCameraForCollisionBounds_Bool = "enableCameraForCollisionBounds";
	static const char * const k_pch_Camera_RoomView_Int32 = "roomView";
	static const char * const k_pch_Camera_BoundsColorGammaR_Int32 = "cameraBoundsColorGammaR";
	static const char * const k_pch_Camera_BoundsColorGammaG_Int32 = "cameraBoundsColorGammaG";
	static const char * const k_pch_Camera_BoundsColorGammaB_Int32 = "cameraBoundsColorGammaB";
	static const char * const k_pch_Camera_BoundsColorGammaA_Int32 = "cameraBoundsColorGammaA";
	static const char * const k_pch_Camera_BoundsStrength_Int32 = "cameraBoundsStrength";
	static const char * const k_pch_Camera_RoomViewStyle_Int32 = "roomViewStyle";

	static const char * const k_pch_audio_Section = "audio";
	static const char * const k_pch_audio_SetOsDefaultPlaybackDevice_Bool = "setOsDefaultPlaybackDevice";
	static const char * const k_pch_audio_EnablePlaybackDeviceOverride_Bool = "enablePlaybackDeviceOverride";
	static const char * const k_pch_audio_PlaybackDeviceOverride_String = "playbackDeviceOverride";
	static const char * const k_pch_audio_PlaybackDeviceOverrideName_String = "playbackDeviceOverrideName";
	static const char * const k_pch_audio_SetOsDefaultRecordingDevice_Bool = "setOsDefaultRecordingDevice";
	static const char * const k_pch_audio_EnableRecordingDeviceOverride_Bool = "enableRecordingDeviceOverride";
	static const char * const k_pch_audio_RecordingDeviceOverride_String = "recordingDeviceOverride";
	static const char * const k_pch_audio_RecordingDeviceOverrideName_String = "recordingDeviceOverrideName";
	static const char * const k_pch_audio_EnablePlaybackMirror_Bool = "enablePlaybackMirror";
	static const char * const k_pch_audio_PlaybackMirrorDevice_String = "playbackMirrorDevice";
	static const char * const k_pch_audio_PlaybackMirrorDeviceName_String = "playbackMirrorDeviceName";
	static const char * const k_pch_audio_OldPlaybackMirrorDevice_String = "onPlaybackMirrorDevice";
	static const char * const k_pch_audio_ActiveMirrorDevice_String = "activePlaybackMirrorDevice";
	static const char * const k_pch_audio_EnablePlaybackMirrorIndependentVolume_Bool = "enablePlaybackMirrorIndependentVolume";
	static const char * const k_pch_audio_LastHmdPlaybackDeviceId_String = "lastHmdPlaybackDeviceId";
	static const char * const k_pch_audio_VIVEHDMIGain = "viveHDMIGain";
	static const char * const k_pch_audio_DualSpeakerAndJackOutput_Bool = "dualSpeakerAndJackOutput";
	static const char * const k_pch_audio_MuteMicMonitor_Bool = "muteMicMonitor";

	static const char * const k_pch_Power_Section = "power";
	static const char * const k_pch_Power_PowerOffOnExit_Bool = "powerOffOnExit";
	static const char * const k_pch_Power_TurnOffScreensTimeout_Float = "turnOffScreensTimeout";
	static const char * const k_pch_Power_TurnOffControllersTimeout_Float = "turnOffControllersTimeout";
	static const char * const k_pch_Power_ReturnToWatchdogTimeout_Float = "returnToWatchdogTimeout";
	static const char * const k_pch_Power_AutoLaunchSteamVROnButtonPress = "autoLaunchSteamVROnButtonPress";
	static const char * const k_pch_Power_PauseCompositorOnStandby_Bool = "pauseCompositorOnStandby";
	static const char * const k_pch_Power_OverrideWindowsPowerScheme_Bool = "overrideWindowsPowerScheme";

	static const char * const k_pch_Dashboard_Section = "dashboard";
	static const char * const k_pch_Dashboard_EnableDashboard_Bool = "enableDashboard";
	static const char * const k_pch_Dashboard_ArcadeMode_Bool = "arcadeMode";
	static const char * const k_pch_Dashboard_Position = "position";
	static const char * const k_pch_Dashboard_DashboardScale = "dashboardScale";
	static const char * const k_pch_Dashboard_UseStandaloneSystemLayer = "standaloneSystemLayer";
	static const char * const k_pch_Dashboard_AllowSteamOverlays_Bool = "allowSteamOverlays";
	static const char * const k_pch_Dashboard_AllowVRGamepadUI_Bool = "allowVRGamepadUI";
	static const char * const k_pch_Dashboard_SteamMatchesHMDFramerate = "steamMatchesHMDFramerate";
	static const char * const k_pch_Dashboard_GrabHandleAcceleration = "grabHandleAcceleration";
	static const char * const k_pch_Dashboard_OverlayBacksideColor_String = "overlayBacksideColor";

	static const char * const k_pch_modelskin_Section = "modelskins";

	static const char * const k_pch_Driver_Enable_Bool = "enable";
	static const char * const k_pch_Driver_BlockedBySafemode_Bool = "blocked_by_safe_mode";
	static const char * const k_pch_Driver_LoadPriority_Int32 = "loadPriority";
	static const char * const k_pch_Driver_Hmd_AllowsClientToControlTextureIndex_Bool = "hmdAllowsClientToControlTextureIndex";
	static const char * const k_pch_Driver_ForceSystemLayerUseAppPoses_Bool = "forceSystemLayerUseAppPoses";

	static const char* const k_pch_WebInterface_Section = "WebInterface";

	static const char* const k_pch_VRWebHelper_Section = "VRWebHelper";
	static const char* const k_pch_VRWebHelper_DebuggerEnabled_Bool = "DebuggerEnabled";
	static const char* const k_pch_VRWebHelper_DebuggerPort_Int32 = "DebuggerPort";

	static const char* const k_pch_TrackingOverride_Section = "TrackingOverrides";

	static const char* const k_pch_App_BindingAutosaveURLSuffix_String = "AutosaveURL";
	static const char* const k_pch_App_BindingLegacyAPISuffix_String = "_legacy";
	static const char *const k_pch_App_BindingSteamVRInputAPISuffix_String = "_steamvrinput";
	static const char *const k_pch_App_BindingOpenXRAPISuffix_String = "_openxr";
	static const char* const k_pch_App_BindingCurrentURLSuffix_String = "CurrentURL";
	static const char* const k_pch_App_BindingPreviousURLSuffix_String = "PreviousURL";
	static const char* const k_pch_App_NeedToUpdateAutosaveSuffix_Bool = "NeedToUpdateAutosave";
	static const char* const k_pch_App_DominantHand_Int32 = "DominantHand";
	static const char* const k_pch_App_BlockOculusSDK_Bool = "blockOculusSDK";

	static const char * const k_pch_Trackers_Section = "trackers";

	static const char * const k_pch_DesktopUI_Section = "DesktopUI";

	static const char * const k_pch_LastKnown_Section = "LastKnown";
	static const char* const k_pch_LastKnown_HMDManufacturer_String = "HMDManufacturer";
	static const char *const k_pch_LastKnown_HMDModel_String = "HMDModel";
	static const char* const k_pch_LastKnown_ActualHMDDriver_String = "ActualHMDDriver";
	static const char* const k_pch_LastKnown_HMDSerialNumber_String = "HMDSerialNumber";
	static const char* const k_pch_LastKnown_HMDRemoteClientID_String = "RemoteClientID";

	static const char * const k_pch_DismissedWarnings_Section = "DismissedWarnings";

	static const char * const k_pch_Input_Section = "input";
	static const char* const k_pch_Input_LeftThumbstickRotation_Float = "leftThumbstickRotation";
	static const char* const k_pch_Input_RightThumbstickRotation_Float = "rightThumbstickRotation";
	static const char* const k_pch_Input_ThumbstickDeadzone_Float = "thumbstickDeadzone";

	static const char * const k_pch_GpuSpeed_Section = "GpuSpeed";

	static const char *const k_pch_XRRenderModelCache_Section = "XRRenderModelUuidCache";

}


namespace vr
{


struct DriverPoseQuaternion_t
{
	double w, x, y, z;
};

struct DriverPose_t
{
	double poseTimeOffset;

	vr::HmdQuaternion_t qWorldFromDriverRotation;
	double vecWorldFromDriverTranslation[ 3 ];

	vr::HmdQuaternion_t qDriverFromHeadRotation;
	double vecDriverFromHeadTranslation[ 3 ];

	double vecPosition[ 3 ];

	double vecVelocity[ 3 ];

	double vecAcceleration[ 3 ];

	vr::HmdQuaternion_t qRotation;

	double vecAngularVelocity[ 3 ];

	double vecAngularAcceleration[ 3 ];

	ETrackingResult result;

	bool poseIsValid;
	bool willDriftInYaw;
	bool shouldApplyHeadModel;
	bool deviceIsConnected;
};


class ITrackedDeviceServerDriver
{
public:

	virtual EVRInitError Activate( uint32_t unObjectId ) = 0;

	virtual void Deactivate() = 0;

	virtual void EnterStandby() = 0;

	virtual void *GetComponent( const char *pchComponentNameAndVersion ) = 0;

	virtual void DebugRequest( const char *pchRequest, char *pchResponseBuffer, uint32_t unResponseBufferSize ) = 0;

	virtual DriverPose_t GetPose() = 0;
};



static const char *ITrackedDeviceServerDriver_Version = "ITrackedDeviceServerDriver_005";

}


namespace vr
{


	class IVRDisplayComponent
	{
	public:


		virtual void GetWindowBounds( int32_t *pnX, int32_t *pnY, uint32_t *pnWidth, uint32_t *pnHeight ) = 0;

		virtual bool IsDisplayOnDesktop( ) = 0;

		virtual bool IsDisplayRealDisplay( ) = 0;

		virtual void GetRecommendedRenderTargetSize( uint32_t *pnWidth, uint32_t *pnHeight ) = 0;

		virtual void GetEyeOutputViewport( EVREye eEye, uint32_t *pnX, uint32_t *pnY, uint32_t *pnWidth, uint32_t *pnHeight ) = 0;

		virtual void GetProjectionRaw( EVREye eEye, float *pfLeft, float *pfRight, float *pfTop, float *pfBottom ) = 0;

		virtual DistortionCoordinates_t ComputeDistortion( EVREye eEye, float fU, float fV ) = 0;

		virtual bool ComputeInverseDistortion( HmdVector2_t *pResult, EVREye eEye, uint32_t unChannel, float fU, float fV ) = 0;
	};

	static const char *IVRDisplayComponent_Version = "IVRDisplayComponent_003";

}


namespace vr
{
	enum VRSwapTextureFlag
	{
		VRSwapTextureFlag_Shared_NTHandle = 1 << 0,
	};

	class IVRDriverDirectModeComponent
	{
	public:


		struct SwapTextureSetDesc_t
		{
			uint32_t nWidth;
			uint32_t nHeight;
			uint32_t nFormat;
			uint32_t nSampleCount;
		};

		struct SwapTextureSet_t
		{
			vr::SharedTextureHandle_t rSharedTextureHandles[ 3 ];
			uint32_t unTextureFlags;
		};

		virtual void CreateSwapTextureSet( uint32_t unPid, const SwapTextureSetDesc_t *pSwapTextureSetDesc, SwapTextureSet_t *pOutSwapTextureSet ) {}

		virtual void DestroySwapTextureSet( vr::SharedTextureHandle_t sharedTextureHandle ) {}

		virtual void DestroyAllSwapTextureSets( uint32_t unPid ) {}

		virtual void GetNextSwapTextureSetIndex( vr::SharedTextureHandle_t sharedTextureHandles[ 2 ], uint32_t( *pIndices )[ 2 ] ) {}

		struct SubmitLayerPerEye_t
		{
			vr::SharedTextureHandle_t hTexture, hDepthTexture;

			vr::VRTextureBounds_t bounds;

			vr::HmdMatrix44_t mProjection;

			vr::HmdMatrix34_t mHmdPose;

			float flHmdPosePredictionTimeInSecondsFromNow;
		};
		virtual void SubmitLayer( const SubmitLayerPerEye_t( &perEye )[ 2 ] ) {}

		virtual void Present( vr::SharedTextureHandle_t syncTexture ) {}

		struct Throttling_t
		{
			uint32_t nFramesToThrottle;
			uint32_t nAdditionalFramesToPredict;
		};
		virtual void PostPresent( const Throttling_t *pThrottling ) {}

		virtual void GetFrameTiming( DriverDirectMode_FrameTiming *pFrameTiming )
		{
			pFrameTiming->m_nReprojectionFlags = 0;
		}
	};

	static const char *IVRDriverDirectModeComponent_Version = "IVRDriverDirectModeComponent_009";

}


namespace vr
{
	class ICameraVideoSinkCallback
	{
	public:
		virtual void OnCameraVideoSinkCallback() = 0;
	};

	class IVRCameraComponent
	{
	public:
		virtual bool GetCameraFrameDimensions( vr::ECameraVideoStreamFormat nVideoStreamFormat, uint32_t *pWidth, uint32_t *pHeight ) = 0;
		virtual bool GetCameraFrameBufferingRequirements( int *pDefaultFrameQueueSize, uint32_t *pFrameBufferDataSize ) = 0;
		virtual bool SetCameraFrameBuffering( int nFrameBufferCount, void **ppFrameBuffers, uint32_t nFrameBufferDataSize ) = 0;
		virtual bool SetCameraVideoStreamFormat( vr::ECameraVideoStreamFormat nVideoStreamFormat ) = 0;
		virtual vr::ECameraVideoStreamFormat GetCameraVideoStreamFormat() = 0;
		virtual bool StartVideoStream() = 0;
		virtual void StopVideoStream() = 0;
		virtual bool IsVideoStreamActive( bool *pbPaused, float *pflElapsedTime ) = 0;
		virtual const vr::CameraVideoStreamFrame_t *GetVideoStreamFrame() = 0;
		virtual void ReleaseVideoStreamFrame( const vr::CameraVideoStreamFrame_t *pFrameImage ) = 0;
		virtual bool SetAutoExposure( bool bEnable ) = 0;
		virtual bool PauseVideoStream() = 0;
		virtual bool ResumeVideoStream() = 0;
		virtual bool GetCameraDistortion( uint32_t nCameraIndex, float flInputU, float flInputV, float *pflOutputU, float *pflOutputV ) = 0;
		virtual bool GetCameraProjection( uint32_t nCameraIndex, vr::EVRTrackedCameraFrameType eFrameType, float flZNear, float flZFar, vr::HmdMatrix44_t *pProjection ) = 0;
		virtual bool SetFrameRate( int nISPFrameRate, int nSensorFrameRate ) = 0;
		virtual bool SetCameraVideoSinkCallback( vr::ICameraVideoSinkCallback *pCameraVideoSinkCallback ) = 0;
		virtual bool GetCameraCompatibilityMode( vr::ECameraCompatibilityMode *pCameraCompatibilityMode ) = 0;
		virtual bool SetCameraCompatibilityMode( vr::ECameraCompatibilityMode nCameraCompatibilityMode ) = 0;
		virtual bool GetCameraFrameBounds( vr::EVRTrackedCameraFrameType eFrameType, uint32_t *pLeft, uint32_t *pTop, uint32_t *pWidth, uint32_t *pHeight ) = 0;
		virtual bool GetCameraIntrinsics( uint32_t nCameraIndex, vr::EVRTrackedCameraFrameType eFrameType, HmdVector2_t *pFocalLength, HmdVector2_t *pCenter, vr::EVRDistortionFunctionType *peDistortionType, double rCoefficients[ k_unMaxDistortionFunctionParameters ] ) = 0;
	};

	static const char *IVRCameraComponent_Version = "IVRCameraComponent_003";
}


namespace vr
{

class ITrackedDeviceServerDriver;
struct TrackedDeviceDriverInfo_t;
struct DriverPose_t;

class IVRDriverContext
{
public:
	virtual void *GetGenericInterface( const char *pchInterfaceVersion, EVRInitError *peError = nullptr ) = 0;

	virtual DriverHandle_t GetDriverHandle() = 0;
};


class IServerTrackedDeviceProvider
{
public:
	virtual EVRInitError Init( IVRDriverContext *pDriverContext ) = 0;

	virtual void Cleanup() = 0;

	virtual const char * const *GetInterfaceVersions() = 0;

	virtual void RunFrame() = 0;



	virtual bool ShouldBlockStandbyMode() = 0;

	virtual void EnterStandby() = 0;

	virtual void LeaveStandby() = 0;

};


static const char *IServerTrackedDeviceProvider_Version = "IServerTrackedDeviceProvider_004";




class IVRWatchdogProvider
{
public:
	virtual EVRInitError Init( IVRDriverContext *pDriverContext ) = 0;

	virtual void Cleanup() = 0;
};

static const char *IVRWatchdogProvider_Version = "IVRWatchdogProvider_001";




class IVRCompositorPluginProvider
{
public:
	virtual EVRInitError Init( IVRDriverContext *pDriverContext ) = 0;

	virtual void Cleanup() = 0;

	virtual const char * const *GetInterfaceVersions() = 0;

	virtual void *GetComponent( const char *pchComponentNameAndVersion ) = 0;
};

static const char *IVRCompositorPluginProvider_Version = "IVRCompositorPluginProvider_001";

}


#include <string>
#include <vector>

namespace vr
{

	static const PropertyContainerHandle_t k_ulDisplayRedirectContainer = 0x600000003;

	enum EPropertyWriteType
	{
		PropertyWrite_Set = 0,
		PropertyWrite_Erase = 1,
		PropertyWrite_SetError = 2
	};

	struct PropertyWrite_t
	{
		ETrackedDeviceProperty prop;
		EPropertyWriteType writeType;
		ETrackedPropertyError eSetError;
		void *pvBuffer;
		uint32_t unBufferSize;
		PropertyTypeTag_t unTag;
		ETrackedPropertyError eError;
	};

	struct PropertyRead_t
	{
		ETrackedDeviceProperty prop;
		void *pvBuffer;
		uint32_t unBufferSize;
		PropertyTypeTag_t unTag;
		uint32_t unRequiredBufferSize;
		ETrackedPropertyError eError;
	};


class IVRProperties
{
public:

	virtual ETrackedPropertyError ReadPropertyBatch( PropertyContainerHandle_t ulContainerHandle, PropertyRead_t *pBatch, uint32_t unBatchEntryCount ) = 0;

	virtual ETrackedPropertyError WritePropertyBatch( PropertyContainerHandle_t ulContainerHandle, PropertyWrite_t *pBatch, uint32_t unBatchEntryCount ) = 0;

	virtual const char *GetPropErrorNameFromEnum( ETrackedPropertyError error ) = 0;

	virtual PropertyContainerHandle_t TrackedDeviceToPropertyContainer( TrackedDeviceIndex_t nDevice ) = 0;
};

static const char * const IVRProperties_Version = "IVRProperties_001";

class CVRPropertyHelpers
{
public:
	CVRPropertyHelpers( IVRProperties * pProperties ) : m_pProperties( pProperties ) {}

	template <typename T>
	T GetTypedProperty( vr::PropertyContainerHandle_t ulContainer, vr::ETrackedDeviceProperty prop, vr::ETrackedPropertyError *peError );

	bool GetBoolProperty( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, ETrackedPropertyError *pError = 0L );
	float GetFloatProperty( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, ETrackedPropertyError *pError = 0L );
	int32_t GetInt32Property( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, ETrackedPropertyError *pError = 0L );
	uint64_t GetUint64Property( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, ETrackedPropertyError *pError = 0L );
	HmdVector2_t GetVec2Property( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, ETrackedPropertyError *pError = 0L );
	HmdVector3_t GetVec3Property( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, ETrackedPropertyError *pError = 0L );
	HmdVector4_t GetVec4Property( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, ETrackedPropertyError *pError = 0L );
	double GetDoubleProperty( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, ETrackedPropertyError *pError = 0L );

	uint32_t GetProperty( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, VR_OUT_STRING() void *pvBuffer, uint32_t unBufferSize, PropertyTypeTag_t *punTag, ETrackedPropertyError *pError = 0L );

	uint32_t GetStringProperty( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, VR_OUT_STRING() char *pchValue, uint32_t unBufferSize, ETrackedPropertyError *pError = 0L );

	std::string GetStringProperty( vr::PropertyContainerHandle_t ulContainer, vr::ETrackedDeviceProperty prop, vr::ETrackedPropertyError *peError = nullptr );

	template< typename T>
	ETrackedPropertyError GetPropertyVector( PropertyContainerHandle_t ulContainer, ETrackedDeviceProperty prop, PropertyTypeTag_t unExpectedTag, std::vector<T> *pvecResults );

	ETrackedPropertyError SetBoolProperty( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, bool bNewValue );
	ETrackedPropertyError SetFloatProperty( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, float fNewValue );
	ETrackedPropertyError SetInt32Property( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, int32_t nNewValue );
	ETrackedPropertyError SetUint64Property( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, uint64_t ulNewValue );
	ETrackedPropertyError SetVec2Property( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, const HmdVector2_t & vNewValue );
	ETrackedPropertyError SetVec3Property( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, const HmdVector3_t & vNewValue );
	ETrackedPropertyError SetVec4Property( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, const HmdVector4_t & vNewValue );
	ETrackedPropertyError SetDoubleProperty( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, double vNewValue );

	ETrackedPropertyError SetStringProperty( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, const char *pchNewValue );

	ETrackedPropertyError SetProperty( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, void *pvNewValue, uint32_t unNewValueSize, PropertyTypeTag_t unTag );

	ETrackedPropertyError SetPropertyError( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, ETrackedPropertyError eError );

	ETrackedPropertyError EraseProperty( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop );

	PropertyContainerHandle_t TrackedDeviceToPropertyContainer( TrackedDeviceIndex_t nDevice ) { return m_pProperties->TrackedDeviceToPropertyContainer( nDevice );  }

	template< typename T>
	ETrackedPropertyError SetPropertyVector( PropertyContainerHandle_t ulContainer, ETrackedDeviceProperty prop, PropertyTypeTag_t unExpectedTag, std::vector<T> *vecProperties );

	bool IsPropertySet( PropertyContainerHandle_t ulContainer, ETrackedDeviceProperty prop, ETrackedPropertyError *peError = nullptr );
private:
	template<typename T>
	T GetPropertyHelper( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, ETrackedPropertyError *pError, T bDefault, PropertyTypeTag_t unTypeTag );

	IVRProperties *m_pProperties;
};


template <>
inline std::string CVRPropertyHelpers::GetTypedProperty( vr::PropertyContainerHandle_t ulContainer, vr::ETrackedDeviceProperty prop, vr::ETrackedPropertyError *peError )
{
	return GetStringProperty( ulContainer, prop, peError );
}


template <>
inline bool CVRPropertyHelpers::GetTypedProperty( vr::PropertyContainerHandle_t ulContainer, vr::ETrackedDeviceProperty prop, vr::ETrackedPropertyError *peError )
{
	return GetBoolProperty( ulContainer, prop, peError );
}


template <>
inline float CVRPropertyHelpers::GetTypedProperty( vr::PropertyContainerHandle_t ulContainer, vr::ETrackedDeviceProperty prop, vr::ETrackedPropertyError *peError )
{
	return GetFloatProperty( ulContainer, prop, peError );
}


template <>
inline int32_t CVRPropertyHelpers::GetTypedProperty( vr::PropertyContainerHandle_t ulContainer, vr::ETrackedDeviceProperty prop, vr::ETrackedPropertyError *peError )
{
	return GetInt32Property( ulContainer, prop, peError );
}


template <>
inline uint64_t CVRPropertyHelpers::GetTypedProperty( vr::PropertyContainerHandle_t ulContainer, vr::ETrackedDeviceProperty prop, vr::ETrackedPropertyError *peError )
{
	return GetUint64Property( ulContainer, prop, peError );
}


template <>
inline HmdVector2_t CVRPropertyHelpers::GetTypedProperty( vr::PropertyContainerHandle_t ulContainer, vr::ETrackedDeviceProperty prop, vr::ETrackedPropertyError *peError )
{
	return GetVec2Property( ulContainer, prop, peError );
}


template <>
inline HmdVector3_t CVRPropertyHelpers::GetTypedProperty( vr::PropertyContainerHandle_t ulContainer, vr::ETrackedDeviceProperty prop, vr::ETrackedPropertyError *peError )
{
	return GetVec3Property( ulContainer, prop, peError );
}


template <>
inline HmdVector4_t CVRPropertyHelpers::GetTypedProperty( vr::PropertyContainerHandle_t ulContainer, vr::ETrackedDeviceProperty prop, vr::ETrackedPropertyError *peError )
{
	return GetVec4Property( ulContainer, prop, peError );
}


template <>
inline double CVRPropertyHelpers::GetTypedProperty( vr::PropertyContainerHandle_t ulContainer, vr::ETrackedDeviceProperty prop, vr::ETrackedPropertyError *peError )
{
	return GetDoubleProperty( ulContainer, prop, peError );
}


inline uint32_t CVRPropertyHelpers::GetProperty( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, VR_OUT_STRING() void *pvBuffer, uint32_t unBufferSize, PropertyTypeTag_t *punTag, ETrackedPropertyError *pError )
{
	PropertyRead_t batch;
	batch.prop = prop;
	batch.pvBuffer = pvBuffer;
	batch.unBufferSize = unBufferSize;

	m_pProperties->ReadPropertyBatch( ulContainerHandle, &batch, 1 );

	if ( pError )
	{
		*pError = batch.eError;
	}

	if ( punTag )
	{
		*punTag = batch.unTag;
	}

	return batch.unRequiredBufferSize;
}


inline ETrackedPropertyError CVRPropertyHelpers::SetProperty( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, void *pvNewValue, uint32_t unNewValueSize, PropertyTypeTag_t unTag )
{
	PropertyWrite_t batch;
	batch.writeType = PropertyWrite_Set;
	batch.prop = prop;
	batch.pvBuffer = pvNewValue;
	batch.unBufferSize = unNewValueSize;
	batch.unTag = unTag;

	m_pProperties->WritePropertyBatch( ulContainerHandle, &batch, 1 );

	return batch.eError;
}


inline uint32_t CVRPropertyHelpers::GetStringProperty( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, VR_OUT_STRING() char *pchValue, uint32_t unBufferSize, ETrackedPropertyError *pError )
{
	PropertyTypeTag_t unTag;
	ETrackedPropertyError error;
	uint32_t unRequiredSize = GetProperty( ulContainerHandle, prop, pchValue, unBufferSize, &unTag, &error );
	if ( unTag != k_unStringPropertyTag && error == TrackedProp_Success )
	{
		error = TrackedProp_WrongDataType;
	}

	if ( pError )
	{
		*pError = error;
	}

	if ( error != TrackedProp_Success )
	{
		if ( pchValue && unBufferSize )
		{
			*pchValue = '\0';
		}
	}

	return unRequiredSize;
}


inline std::string CVRPropertyHelpers::GetStringProperty( vr::PropertyContainerHandle_t ulContainer, vr::ETrackedDeviceProperty prop, vr::ETrackedPropertyError *peError )
{
	char buf[1024];
	vr::ETrackedPropertyError err;
	uint32_t unRequiredBufferLen = GetStringProperty( ulContainer, prop, buf, sizeof(buf), &err );

	std::string sResult;

	if ( err == TrackedProp_Success )
	{
		sResult = buf;
	}
	else if ( err == TrackedProp_BufferTooSmall )
	{
		char *pchBuffer = new char[unRequiredBufferLen];
		unRequiredBufferLen = GetStringProperty( ulContainer, prop, pchBuffer, unRequiredBufferLen, &err );
		sResult = pchBuffer;
		delete[] pchBuffer;
	}

	if ( peError )
	{
		*peError = err;
	}

	return sResult;
}


inline ETrackedPropertyError CVRPropertyHelpers::SetStringProperty( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, const char *pchNewValue )
{
	if ( !pchNewValue )
		return TrackedProp_InvalidOperation;

	const char *pchCurr = pchNewValue;
	while ( *pchCurr )
	{
		pchCurr++;
	}

	return SetProperty( ulContainerHandle, prop, (void *)pchNewValue, (uint32_t)(pchCurr - pchNewValue) + 1, k_unStringPropertyTag );
}


template<typename T>
inline T CVRPropertyHelpers::GetPropertyHelper( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, ETrackedPropertyError *pError, T bDefault, PropertyTypeTag_t unTypeTag )
{
	T bValue;
	ETrackedPropertyError eError;
	PropertyTypeTag_t unReadTag;
	GetProperty( ulContainerHandle, prop, &bValue, sizeof( bValue ), &unReadTag, &eError );
	if ( unReadTag != unTypeTag && eError == TrackedProp_Success )
	{
		eError = TrackedProp_WrongDataType;
	};

	if ( pError )
		*pError = eError;
	if ( eError != TrackedProp_Success )
	{
		return bDefault;
	}
	else
	{
		return bValue;
	}
}


inline bool CVRPropertyHelpers::GetBoolProperty( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, ETrackedPropertyError *pError )
{
	return GetPropertyHelper<bool>( ulContainerHandle, prop, pError, false, k_unBoolPropertyTag );
}

inline float CVRPropertyHelpers::GetFloatProperty( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, ETrackedPropertyError *pError )
{
	return GetPropertyHelper<float>( ulContainerHandle, prop, pError, 0.f, k_unFloatPropertyTag );
}

inline double CVRPropertyHelpers::GetDoubleProperty( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, ETrackedPropertyError *pError )
{
	return GetPropertyHelper<double>( ulContainerHandle, prop, pError, 0., k_unDoublePropertyTag );
}

inline int32_t CVRPropertyHelpers::GetInt32Property( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, ETrackedPropertyError *pError )
{
	return GetPropertyHelper<int32_t>( ulContainerHandle, prop, pError, 0, k_unInt32PropertyTag );
}

inline uint64_t CVRPropertyHelpers::GetUint64Property( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, ETrackedPropertyError *pError )
{
	return GetPropertyHelper<uint64_t>( ulContainerHandle, prop, pError, 0, k_unUint64PropertyTag );
}

inline HmdVector2_t CVRPropertyHelpers::GetVec2Property( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, ETrackedPropertyError *pError )
{
	HmdVector2_t defaultval = { { 0, 0 } };
	return GetPropertyHelper<HmdVector2_t>( ulContainerHandle, prop, pError, defaultval, k_unHmdVector2PropertyTag );
}

inline HmdVector3_t CVRPropertyHelpers::GetVec3Property( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, ETrackedPropertyError *pError )
{
	HmdVector3_t defaultval = { { 0, 0, 0 } };
	return GetPropertyHelper<HmdVector3_t>( ulContainerHandle, prop, pError, defaultval, k_unHmdVector3PropertyTag );
}

inline HmdVector4_t CVRPropertyHelpers::GetVec4Property( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, ETrackedPropertyError *pError )
{
	HmdVector4_t defaultval = { { 0, 0, 0, 0 } };
	return GetPropertyHelper<HmdVector4_t>( ulContainerHandle, prop, pError, defaultval, k_unHmdVector4PropertyTag );
}

inline ETrackedPropertyError CVRPropertyHelpers::SetBoolProperty( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, bool bNewValue )
{
	return SetProperty( ulContainerHandle, prop, &bNewValue, sizeof( bNewValue ), k_unBoolPropertyTag );
}

inline ETrackedPropertyError CVRPropertyHelpers::SetFloatProperty( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, float fNewValue )
{
	return SetProperty( ulContainerHandle, prop, &fNewValue, sizeof( fNewValue ), k_unFloatPropertyTag );
}

inline ETrackedPropertyError CVRPropertyHelpers::SetDoubleProperty( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, double fNewValue )
{
	return SetProperty( ulContainerHandle, prop, &fNewValue, sizeof( fNewValue ), k_unDoublePropertyTag );
}

inline ETrackedPropertyError CVRPropertyHelpers::SetInt32Property( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, int32_t nNewValue )
{
	return SetProperty( ulContainerHandle, prop, &nNewValue, sizeof( nNewValue ), k_unInt32PropertyTag );
}

inline ETrackedPropertyError CVRPropertyHelpers::SetUint64Property( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, uint64_t ulNewValue )
{
	return SetProperty( ulContainerHandle, prop, &ulNewValue, sizeof( ulNewValue ), k_unUint64PropertyTag );
}

inline ETrackedPropertyError CVRPropertyHelpers::SetVec2Property( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, const HmdVector2_t & vNewValue )
{
	return SetProperty( ulContainerHandle, prop, ( void * ) &vNewValue, sizeof( HmdVector2_t ), k_unHmdVector2PropertyTag );
}

inline ETrackedPropertyError CVRPropertyHelpers::SetVec3Property( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, const HmdVector3_t & vNewValue )
{
	return SetProperty( ulContainerHandle, prop, ( void * ) &vNewValue, sizeof( HmdVector3_t ), k_unHmdVector3PropertyTag );
}

inline ETrackedPropertyError CVRPropertyHelpers::SetVec4Property( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, const HmdVector4_t & vNewValue )
{
	return SetProperty( ulContainerHandle, prop, ( void * ) &vNewValue, sizeof( HmdVector4_t ), k_unHmdVector4PropertyTag );
}

inline ETrackedPropertyError CVRPropertyHelpers::SetPropertyError( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop, ETrackedPropertyError eError )
{
	PropertyWrite_t batch;
	batch.writeType = PropertyWrite_SetError;
	batch.prop = prop;
	batch.eSetError = eError;

	m_pProperties->WritePropertyBatch( ulContainerHandle, &batch, 1 );

	return batch.eError;
}

inline ETrackedPropertyError CVRPropertyHelpers::EraseProperty( PropertyContainerHandle_t ulContainerHandle, ETrackedDeviceProperty prop )
{
	PropertyWrite_t batch;
	batch.writeType = PropertyWrite_Erase;
	batch.prop = prop;

	m_pProperties->WritePropertyBatch( ulContainerHandle, &batch, 1 );

	return batch.eError;

}

template< typename T >
ETrackedPropertyError CVRPropertyHelpers::SetPropertyVector(PropertyContainerHandle_t ulContainer, vr::ETrackedDeviceProperty prop, PropertyTypeTag_t unTag, std::vector<T> *pvecProperties)
{
	return SetProperty( ulContainer, prop, &(*pvecProperties)[0], (uint32_t)(pvecProperties->size() * sizeof( T )), unTag );
}

template< typename T >
ETrackedPropertyError CVRPropertyHelpers::GetPropertyVector( PropertyContainerHandle_t ulContainer, ETrackedDeviceProperty prop, PropertyTypeTag_t unExpectedTag, std::vector<T> *pvecResults )
{
	ETrackedPropertyError err;
	PropertyTypeTag_t unTag;
	uint32_t unNeeded;
	if ( pvecResults->empty() )
		unNeeded = GetProperty( ulContainer, prop, nullptr, 0, &unTag, &err );
	else
		unNeeded = GetProperty( ulContainer, prop, &(*pvecResults)[0], (uint32_t)(pvecResults->size() * sizeof( T )), &unTag, &err );
	uint32_t unFound = unNeeded / sizeof( T );
	if ( err == TrackedProp_Success )
	{
		if ( unTag != unExpectedTag && unFound > 0 )
		{
			return TrackedProp_WrongDataType;
		}

		pvecResults->resize( unFound );
		return TrackedProp_Success;
	}
	else if ( err == TrackedProp_BufferTooSmall )
	{
		pvecResults->resize( unFound );
		unNeeded = GetProperty( ulContainer, prop, &(*pvecResults)[0], (uint32_t)(pvecResults->size() * sizeof( T )), &unTag, &err );
		unFound = unNeeded / sizeof( T );

		if ( err == TrackedProp_Success )
		{
			if ( unTag != unExpectedTag )
			{
				return TrackedProp_WrongDataType;
			}

			pvecResults->resize( unFound );
			return TrackedProp_Success;
		}
	}
	return err;
}

inline bool CVRPropertyHelpers::IsPropertySet( PropertyContainerHandle_t ulContainer, ETrackedDeviceProperty prop, ETrackedPropertyError *peError )
{
	ETrackedPropertyError error;
	GetProperty( ulContainer, prop, nullptr, 0, nullptr, &error );
	if ( peError )
		*peError = error;
	return error == TrackedProp_Success || error == TrackedProp_BufferTooSmall;
}

}




namespace vr
{
	enum EVRScalarType
	{
		VRScalarType_Absolute = 0,
		VRScalarType_Relative = 1,
	};


	enum EVRScalarUnits
	{
		VRScalarUnits_NormalizedOneSided = 0,
		VRScalarUnits_NormalizedTwoSided = 1,
	};

	class IVRDriverInput
	{
	public:

		virtual EVRInputError CreateBooleanComponent( PropertyContainerHandle_t ulContainer, const char *pchName, VRInputComponentHandle_t *pHandle ) = 0;

		virtual EVRInputError UpdateBooleanComponent( VRInputComponentHandle_t ulComponent, bool bNewValue, double fTimeOffset ) = 0;

		virtual EVRInputError CreateScalarComponent( PropertyContainerHandle_t ulContainer, const char *pchName, VRInputComponentHandle_t *pHandle, EVRScalarType eType, EVRScalarUnits eUnits ) = 0;

		virtual EVRInputError UpdateScalarComponent( VRInputComponentHandle_t ulComponent, float fNewValue, double fTimeOffset ) = 0;

		virtual EVRInputError CreateHapticComponent( PropertyContainerHandle_t ulContainer, const char *pchName, VRInputComponentHandle_t *pHandle ) = 0;

		virtual EVRInputError CreateSkeletonComponent( PropertyContainerHandle_t ulContainer, const char *pchName, const char *pchSkeletonPath, const char *pchBasePosePath, EVRSkeletalTrackingLevel eSkeletalTrackingLevel, const VRBoneTransform_t *pGripLimitTransforms, uint32_t unGripLimitTransformCount, VRInputComponentHandle_t *pHandle ) = 0;

		virtual EVRInputError UpdateSkeletonComponent( VRInputComponentHandle_t ulComponent, EVRSkeletalMotionRange eMotionRange, const VRBoneTransform_t *pTransforms, uint32_t unTransformCount ) = 0;

		virtual EVRInputError CreatePoseComponent( PropertyContainerHandle_t ulContainer, const char *pchName, VRInputComponentHandle_t *pHandle ) = 0;

		virtual EVRInputError UpdatePoseComponent( VRInputComponentHandle_t ulComponent, const HmdMatrix34_t *pMatPoseOffset, double fTimeOffset ) = 0;

		virtual EVRInputError CreateEyeTrackingComponent( PropertyContainerHandle_t ulContainer, const char *pchName, VRInputComponentHandle_t *pHandle ) = 0;

		virtual EVRInputError UpdateEyeTrackingComponent( VRInputComponentHandle_t ulComponent, const VREyeTrackingData_t *pEyeTrackingData, double fTimeOffset ) = 0;
	};

	static const char * const IVRDriverInput_Version = "IVRDriverInput_004";

}


namespace vr
{

class IVRDriverLog
{
public:
	virtual void Log( const char *pchLogMessage ) = 0;
};


static const char *IVRDriverLog_Version = "IVRDriverLog_001";

}


namespace vr
{

class ITrackedDeviceServerDriver;
struct TrackedDeviceDriverInfo_t;
struct DriverPose_t;

class IVRServerDriverHost
{
public:
	virtual bool TrackedDeviceAdded( const char *pchDeviceSerialNumber, ETrackedDeviceClass eDeviceClass, ITrackedDeviceServerDriver *pDriver ) = 0;

	virtual void TrackedDevicePoseUpdated( uint32_t unWhichDevice, const DriverPose_t & newPose, uint32_t unPoseStructSize ) = 0;

	virtual void VsyncEvent( double vsyncTimeOffsetSeconds ) = 0;

	virtual void VendorSpecificEvent( uint32_t unWhichDevice, vr::EVREventType eventType, const VREvent_Data_t & eventData, double eventTimeOffset ) = 0;

	virtual bool IsExiting() = 0;

	virtual bool PollNextEvent( VREvent_t *pEvent, uint32_t uncbVREvent ) = 0;

	virtual void GetRawTrackedDevicePoses( float fPredictedSecondsFromNow, TrackedDevicePose_t *pTrackedDevicePoseArray, uint32_t unTrackedDevicePoseArrayCount ) = 0;

	virtual void RequestRestart( const char *pchLocalizedReason, const char *pchExecutableToStart, const char *pchArguments, const char *pchWorkingDirectory ) = 0;

	virtual uint32_t GetFrameTimings( Compositor_FrameTiming *pTiming, uint32_t nFrames ) = 0;

	virtual void SetDisplayEyeToHead( uint32_t unWhichDevice, const HmdMatrix34_t & eyeToHeadLeft, const HmdMatrix34_t & eyeToHeadRight ) = 0;

	virtual void SetDisplayProjectionRaw( uint32_t unWhichDevice, const HmdRect2_t & eyeLeft, const HmdRect2_t & eyeRight ) = 0;

	virtual void SetRecommendedRenderTargetSize( uint32_t unWhichDevice, uint32_t nWidth, uint32_t nHeight ) = 0;
};

static const char *IVRServerDriverHost_Version = "IVRServerDriverHost_006";

}


namespace vr
{

class IVRCompositorDriverHost
{
public:
	virtual bool PollNextEvent( VREvent_t *pEvent, uint32_t uncbVREvent ) = 0;
};

static const char *IVRCompositorDriverHost_Version = "IVRCompositorDriverHost_001";

}


namespace vr
{

class CVRHiddenAreaHelpers
{
public:
	CVRHiddenAreaHelpers( IVRProperties *pProperties ) : m_pProperties( pProperties ) {}

	ETrackedPropertyError SetHiddenArea( EVREye eEye, EHiddenAreaMeshType type, HmdVector2_t *pVerts, uint32_t unVertCount );

	uint32_t GetHiddenArea( EVREye eEye, EHiddenAreaMeshType type, HmdVector2_t *pVerts, uint32_t unVertCount, ETrackedPropertyError *peError );

private:
	ETrackedDeviceProperty GetPropertyEnum( EVREye eEye, EHiddenAreaMeshType type )
	{
		return (ETrackedDeviceProperty)(Prop_DisplayHiddenArea_Binary_Start + ((int)type * 2) + (int)eEye);
	}

	IVRProperties *m_pProperties;
};


inline ETrackedPropertyError CVRHiddenAreaHelpers::SetHiddenArea( EVREye eEye, EHiddenAreaMeshType type, HmdVector2_t *pVerts, uint32_t unVertCount )
{
	ETrackedDeviceProperty prop = GetPropertyEnum( eEye, type );
	CVRPropertyHelpers propHelpers( m_pProperties );
	return propHelpers.SetProperty( propHelpers.TrackedDeviceToPropertyContainer( k_unTrackedDeviceIndex_Hmd ), prop, pVerts, sizeof( HmdVector2_t ) * unVertCount, k_unHiddenAreaPropertyTag );
}


inline uint32_t CVRHiddenAreaHelpers::GetHiddenArea( EVREye eEye, EHiddenAreaMeshType type, HmdVector2_t *pVerts, uint32_t unVertCount, ETrackedPropertyError *peError )
{
	ETrackedDeviceProperty prop = GetPropertyEnum( eEye, type );
	CVRPropertyHelpers propHelpers( m_pProperties );
	ETrackedPropertyError propError;
	PropertyTypeTag_t unTag;
	uint32_t unBytesNeeded = propHelpers.GetProperty( propHelpers.TrackedDeviceToPropertyContainer( k_unTrackedDeviceIndex_Hmd ), prop, pVerts, sizeof( HmdVector2_t )*unVertCount, &unTag, &propError );
	if ( propError == TrackedProp_Success && unTag != k_unHiddenAreaPropertyTag )
	{
		propError = TrackedProp_WrongDataType;
		unBytesNeeded = 0;
	}

	if ( peError )
	{
		*peError = propError;
	}

	return unBytesNeeded / sizeof( HmdVector2_t );
}

}


namespace vr
{

class IVRWatchdogHost
{
public:
	virtual void WatchdogWakeUp( vr::ETrackedDeviceClass eDeviceClass ) = 0;
};

static const char *IVRWatchdogHost_Version = "IVRWatchdogHost_002";

};




namespace vr
{
	struct PresentInfo_t
	{
		SharedTextureHandle_t backbufferTextureHandle;
		EVSync vsync;
		uint64_t nFrameId;
		double flVSyncTimeInSeconds;
	};

	class IVRVirtualDisplay
	{
	public:

		virtual void Present( const PresentInfo_t *pPresentInfo, uint32_t unPresentInfoSize ) = 0;

		virtual void WaitForPresent() = 0;

		virtual bool GetTimeSinceLastVsync( float *pfSecondsSinceLastVsync, uint64_t *pulFrameCounter ) = 0;
	};

	static const char *IVRVirtualDisplay_Version = "IVRVirtualDisplay_002";
}



namespace vr
{

class IVRResources
{
public:


	virtual uint32_t LoadSharedResource( const char *pchResourceName, char *pchBuffer, uint32_t unBufferLen ) = 0;

	virtual uint32_t GetResourceFullPath( const char *pchResourceName, const char *pchResourceTypeDirectory, VR_OUT_STRING() char *pchPathBuffer, uint32_t unBufferLen ) = 0;
};

static const char * const IVRResources_Version = "IVRResources_001";


}


namespace vr
{

typedef uint64_t IOBufferHandle_t;
static const uint64_t k_ulInvalidIOBufferHandle = 0;

	enum EIOBufferError
	{
		IOBuffer_Success                  = 0,
		IOBuffer_OperationFailed          = 100,
		IOBuffer_InvalidHandle            = 101,
		IOBuffer_InvalidArgument          = 102,
		IOBuffer_PathExists               = 103,
		IOBuffer_PathDoesNotExist         = 104,
		IOBuffer_Permission               = 105,
	};

	enum EIOBufferMode
	{
		IOBufferMode_Read                 = 0x0001,
		IOBufferMode_Write                = 0x0002,
		IOBufferMode_Create               = 0x0200,
	};

	class IVRIOBuffer
	{
	public:
		virtual vr::EIOBufferError Open( const char *pchPath, vr::EIOBufferMode mode, uint32_t unElementSize, uint32_t unElements, vr::IOBufferHandle_t *pulBuffer ) = 0;

		virtual vr::EIOBufferError Close( vr::IOBufferHandle_t ulBuffer ) = 0;

		virtual vr::EIOBufferError Read( vr::IOBufferHandle_t ulBuffer, void *pDst, uint32_t unBytes, uint32_t *punRead ) = 0;

		virtual vr::EIOBufferError Write( vr::IOBufferHandle_t ulBuffer, void *pSrc, uint32_t unBytes ) = 0;

		virtual vr::PropertyContainerHandle_t PropertyContainer( vr::IOBufferHandle_t ulBuffer ) = 0;

		virtual bool HasReaders( vr::IOBufferHandle_t ulBuffer ) = 0;
	};

	static const char * const IVRIOBuffer_Version = "IVRIOBuffer_002";
}


namespace vr
{

class IVRDriverManager
{
public:
	virtual uint32_t GetDriverCount() const = 0;

	virtual uint32_t GetDriverName( vr::DriverId_t nDriver, VR_OUT_STRING() char *pchValue, uint32_t unBufferSize ) = 0;

	virtual DriverHandle_t GetDriverHandle( const char *pchDriverName ) = 0;

	virtual bool IsEnabled( vr::DriverId_t nDriver ) const = 0;
};

static const char * const IVRDriverManager_Version = "IVRDriverManager_001";

}




namespace vr
{
	struct SpatialAnchorDriverPose_t
	{
		vr::HmdQuaternion_t qWorldRotation;
		vr::HmdVector3d_t vWorldTranslation;

		uint64_t ulRequiredUniverseId;


















		double fValidDuration;
	};

	class IVRDriverSpatialAnchors
	{
	public:

		virtual EVRSpatialAnchorError UpdateSpatialAnchorPose( SpatialAnchorHandle_t unHandle, const SpatialAnchorDriverPose_t *pPose ) = 0;

		virtual EVRSpatialAnchorError SetSpatialAnchorPoseError( SpatialAnchorHandle_t unHandle, EVRSpatialAnchorError eError, double fValidDuration ) = 0;

		virtual EVRSpatialAnchorError UpdateSpatialAnchorDescriptor( SpatialAnchorHandle_t unHandle, const char *pchDescriptor ) = 0;

		virtual EVRSpatialAnchorError GetSpatialAnchorPose( SpatialAnchorHandle_t unHandle, SpatialAnchorDriverPose_t *pDriverPoseOut ) = 0;

		virtual EVRSpatialAnchorError GetSpatialAnchorDescriptor( SpatialAnchorHandle_t unHandle, VR_OUT_STRING() char *pchDescriptorOut, uint32_t *punDescriptorBufferLenInOut, bool bDecorated ) = 0;

	};

	static const char * const IVRDriverSpatialAnchors_Version = "IVRDriverSpatialAnchors_001";

}


namespace vr
{

class IVRIPCResourceManagerClient
{
public:
	virtual bool NewSharedVulkanImage( uint32_t nImageFormat, uint32_t nWidth, uint32_t nHeight, bool bRenderable, bool bMappable, bool bComputeAccess, uint32_t unMipLevels, uint32_t unArrayLayerCount, uint32_t unAdditionalVkCreateFlags, uint32_t unAdditionalVkUsageFlags, vr::SharedTextureHandle_t *pSharedHandle ) = 0;

	virtual bool NewSharedVulkanBuffer( uint32_t nSize, uint32_t nUsageFlags, vr::SharedTextureHandle_t *pSharedHandle ) = 0;

	virtual bool NewSharedVulkanSemaphore( bool bCounting, vr::SharedTextureHandle_t *pSharedHandle ) = 0;

	virtual bool RefResource( vr::SharedTextureHandle_t hSharedHandle, uint64_t *pNewIpcHandle ) = 0;

	virtual bool UnrefResource( vr::SharedTextureHandle_t hSharedHandle ) = 0;

	virtual bool GetDmabufFormats( uint32_t *pOutFormatCount, uint32_t *pOutFormats ) = 0;

	virtual bool GetDmabufModifiers( vr::EVRApplicationType eApplicationType, uint32_t unDRMFormat, uint32_t *pOutModifierCount, uint64_t *pOutModifiers ) = 0;

	virtual bool ImportDmabuf( vr::EVRApplicationType eApplicationType, vr::DmabufAttributes_t *pDmabufAttributes, vr::SharedTextureHandle_t *pSharedHandle ) = 0;

	virtual bool ReceiveSharedFd( uint64_t ulIpcHandle, int *pOutFd ) = 0;

protected:
	virtual ~IVRIPCResourceManagerClient() {};
};

static const char *IVRIPCResourceManagerClient_Version = "IVRIPCResourceManagerClient_003";

}



namespace vr
{
#if !defined( OPENVR_INTERFACE_INTERNAL )
	static const char * const k_InterfaceVersions[] =
	{
		IVRSettings_Version,
		ITrackedDeviceServerDriver_Version,
		IVRDisplayComponent_Version,
		IVRDriverDirectModeComponent_Version,
		IVRCameraComponent_Version,
		IServerTrackedDeviceProvider_Version,
		IVRWatchdogProvider_Version,
		IVRVirtualDisplay_Version,
		IVRDriverManager_Version,
		IVRResources_Version,
		IVRCompositorPluginProvider_Version,
		IVRIPCResourceManagerClient_Version,
		nullptr
	};

	inline IVRDriverContext *&VRDriverContext()
	{
		static IVRDriverContext *pHost;
		return pHost;
	}

	class COpenVRDriverContext
	{
	public:
		COpenVRDriverContext() : m_propertyHelpers(nullptr), m_hiddenAreaHelpers(nullptr) { Clear(); }
		void Clear();

		EVRInitError InitServer();
		EVRInitError InitWatchdog();
		EVRInitError InitCompositor();

		IVRSettings *VRSettings()
		{
			if ( m_pVRSettings == nullptr )
			{
				EVRInitError eError;
				m_pVRSettings = (IVRSettings *)VRDriverContext()->GetGenericInterface( IVRSettings_Version, &eError );
			}
			return m_pVRSettings;
		}

		IVRProperties *VRPropertiesRaw()
		{
			if ( m_pVRProperties == nullptr )
			{
				EVRInitError eError;
				m_pVRProperties = (IVRProperties *)VRDriverContext()->GetGenericInterface( IVRProperties_Version, &eError );
				m_propertyHelpers = CVRPropertyHelpers( m_pVRProperties );
				m_hiddenAreaHelpers = CVRHiddenAreaHelpers( m_pVRProperties );
			}
			return m_pVRProperties;
		}

		CVRPropertyHelpers *VRProperties()
		{
			VRPropertiesRaw();
			return &m_propertyHelpers;
		}

		CVRHiddenAreaHelpers *VRHiddenArea()
		{
			VRPropertiesRaw();
			return &m_hiddenAreaHelpers;
		}

		IVRServerDriverHost *VRServerDriverHost()
		{
			if ( m_pVRServerDriverHost == nullptr )
			{
				EVRInitError eError;
				m_pVRServerDriverHost = (IVRServerDriverHost *)VRDriverContext()->GetGenericInterface( IVRServerDriverHost_Version, &eError );
			}
			return m_pVRServerDriverHost;
		}

		IVRWatchdogHost *VRWatchdogHost()
		{
			if ( m_pVRWatchdogHost == nullptr )
			{
				EVRInitError eError;
				m_pVRWatchdogHost = (IVRWatchdogHost *)VRDriverContext()->GetGenericInterface( IVRWatchdogHost_Version, &eError );
			}
			return m_pVRWatchdogHost;
		}

		IVRCompositorDriverHost *VRCompositorDriverHost()
		{
			if ( m_pVRCompositorDriverHost == nullptr )
			{
				EVRInitError eError;
				m_pVRCompositorDriverHost = ( IVRCompositorDriverHost * )VRDriverContext()->GetGenericInterface( IVRCompositorDriverHost_Version, &eError );
			}
			return m_pVRCompositorDriverHost;
		}

		IVRDriverLog *VRDriverLog()
		{
			if ( m_pVRDriverLog == nullptr )
			{
				EVRInitError eError;
				m_pVRDriverLog = (IVRDriverLog *)VRDriverContext()->GetGenericInterface( IVRDriverLog_Version, &eError );
			}
			return m_pVRDriverLog;
		}

		DriverHandle_t VR_CALLTYPE VRDriverHandle()
		{
			return VRDriverContext()->GetDriverHandle();
		}

		IVRDriverManager *VRDriverManager()
		{
			if ( !m_pVRDriverManager )
			{
				EVRInitError eError;
				m_pVRDriverManager = (IVRDriverManager *)VRDriverContext()->GetGenericInterface( IVRDriverManager_Version, &eError );
			}
			return m_pVRDriverManager;
		}

		IVRResources *VRResources()
		{
			if ( !m_pVRResources )
			{
				EVRInitError eError;
				m_pVRResources = (IVRResources *)VRDriverContext()->GetGenericInterface( IVRResources_Version, &eError );
			}
			return m_pVRResources;
		}

		IVRDriverInput *VRDriverInput()
		{
			if ( !m_pVRDriverInput )
			{
				EVRInitError eError;
				m_pVRDriverInput = (IVRDriverInput *)VRDriverContext()->GetGenericInterface( IVRDriverInput_Version, &eError );
			}
			return m_pVRDriverInput;
		}

		IVRIOBuffer *VRIOBuffer()
		{
			if ( !m_pVRIOBuffer )
			{
				EVRInitError eError;
				m_pVRIOBuffer = (IVRIOBuffer *)VRDriverContext()->GetGenericInterface( IVRIOBuffer_Version, &eError );
			}
			return m_pVRIOBuffer;
		}
		
		IVRDriverSpatialAnchors *VRDriverSpatialAnchors()
		{
			if ( !m_pVRDriverSpatialAnchors )
			{
				EVRInitError eError;
				m_pVRDriverSpatialAnchors = (IVRDriverSpatialAnchors *)VRDriverContext()->GetGenericInterface( IVRDriverSpatialAnchors_Version, &eError );
			}
			return m_pVRDriverSpatialAnchors;
		}

		IVRIPCResourceManagerClient *VRIPCResourceManager()
		{
			if ( m_pVRIPCResourceManager == nullptr )
			{
				EVRInitError eError;
				m_pVRIPCResourceManager = ( IVRIPCResourceManagerClient * )VRDriverContext()->GetGenericInterface( IVRIPCResourceManagerClient_Version, &eError );
			}
			return m_pVRIPCResourceManager;
		}

	private:
		CVRPropertyHelpers		m_propertyHelpers;
		CVRHiddenAreaHelpers	m_hiddenAreaHelpers;

		IVRSettings				*m_pVRSettings;
		IVRProperties			*m_pVRProperties;
		IVRServerDriverHost		*m_pVRServerDriverHost;
		IVRWatchdogHost			*m_pVRWatchdogHost;
		IVRCompositorDriverHost	*m_pVRCompositorDriverHost;
		IVRDriverLog			*m_pVRDriverLog;
		IVRDriverManager		*m_pVRDriverManager;
		IVRResources			*m_pVRResources;
		IVRDriverInput			*m_pVRDriverInput;
		IVRIOBuffer				*m_pVRIOBuffer;
		IVRDriverSpatialAnchors *m_pVRDriverSpatialAnchors;
		IVRIPCResourceManagerClient *m_pVRIPCResourceManager;
	};

	inline COpenVRDriverContext &OpenVRInternal_ModuleServerDriverContext()
	{
		static void *ctx[sizeof( COpenVRDriverContext ) / sizeof( void * )];
		return *(COpenVRDriverContext *)ctx;
	}

	inline IVRSettings *VR_CALLTYPE VRSettings() { return OpenVRInternal_ModuleServerDriverContext().VRSettings(); }
	inline IVRProperties *VR_CALLTYPE VRPropertiesRaw() { return OpenVRInternal_ModuleServerDriverContext().VRPropertiesRaw(); }
	inline CVRPropertyHelpers *VR_CALLTYPE VRProperties() { return OpenVRInternal_ModuleServerDriverContext().VRProperties(); }
	inline CVRHiddenAreaHelpers *VR_CALLTYPE VRHiddenArea() { return OpenVRInternal_ModuleServerDriverContext().VRHiddenArea(); }
	inline IVRDriverLog *VR_CALLTYPE VRDriverLog() { return OpenVRInternal_ModuleServerDriverContext().VRDriverLog(); }
	inline IVRServerDriverHost *VR_CALLTYPE VRServerDriverHost() { return OpenVRInternal_ModuleServerDriverContext().VRServerDriverHost(); }
	inline IVRWatchdogHost *VR_CALLTYPE VRWatchdogHost() { return OpenVRInternal_ModuleServerDriverContext().VRWatchdogHost(); }
	inline IVRCompositorDriverHost *VR_CALLTYPE VRCompositorDriverHost() { return OpenVRInternal_ModuleServerDriverContext().VRCompositorDriverHost(); }
	inline DriverHandle_t VR_CALLTYPE VRDriverHandle() { return OpenVRInternal_ModuleServerDriverContext().VRDriverHandle(); }
	inline IVRDriverManager *VR_CALLTYPE VRDriverManager() { return OpenVRInternal_ModuleServerDriverContext().VRDriverManager(); }
	inline IVRResources *VR_CALLTYPE VRResources() { return OpenVRInternal_ModuleServerDriverContext().VRResources(); }
	inline IVRDriverInput *VR_CALLTYPE VRDriverInput() { return OpenVRInternal_ModuleServerDriverContext().VRDriverInput(); }
	inline IVRIOBuffer *VR_CALLTYPE VRIOBuffer() { return OpenVRInternal_ModuleServerDriverContext().VRIOBuffer(); }
	inline IVRDriverSpatialAnchors *VR_CALLTYPE VRDriverSpatialAnchors() { return OpenVRInternal_ModuleServerDriverContext().VRDriverSpatialAnchors(); }
	inline IVRIPCResourceManagerClient *VR_CALLTYPE VRIPCResourceManager() { return OpenVRInternal_ModuleServerDriverContext().VRIPCResourceManager(); }

	inline void COpenVRDriverContext::Clear()
	{
		m_pVRSettings = nullptr;
		m_pVRProperties = nullptr;
		m_pVRServerDriverHost = nullptr;
		m_pVRWatchdogHost = nullptr;
		m_pVRCompositorDriverHost = nullptr;
		m_pVRDriverLog = nullptr;
		m_pVRDriverManager = nullptr;
		m_pVRResources = nullptr;
		m_pVRDriverInput = nullptr;
		m_pVRIOBuffer = nullptr;
		m_pVRDriverSpatialAnchors = nullptr;
		m_pVRIPCResourceManager = nullptr;
	}

	inline EVRInitError COpenVRDriverContext::InitServer()
	{
		Clear();
		if ( !VRServerDriverHost()
			|| !VRSettings()
			|| !VRProperties()
			|| !VRDriverLog() 
			|| !VRDriverManager()
			|| !VRResources() )
			return VRInitError_Init_InterfaceNotFound;
		return VRInitError_None;
	}

	inline EVRInitError COpenVRDriverContext::InitWatchdog()
	{
		Clear();
		if ( !VRWatchdogHost()
			|| !VRSettings()
			|| !VRDriverLog() )
			return VRInitError_Init_InterfaceNotFound;
		return VRInitError_None;
	}

	inline EVRInitError COpenVRDriverContext::InitCompositor()
	{
		Clear();
		if ( !VRCompositorDriverHost()
			|| !VRSettings()
			|| !VRProperties()
			|| !VRDriverLog()
			|| !VRDriverManager()
			|| !VRResources() )
			return VRInitError_Init_InterfaceNotFound;
		return VRInitError_None;
	}

	inline EVRInitError InitServerDriverContext( IVRDriverContext *pContext )
	{
		VRDriverContext() = pContext;
		return OpenVRInternal_ModuleServerDriverContext().InitServer();
	}

	inline EVRInitError InitWatchdogDriverContext( IVRDriverContext *pContext )
	{
		VRDriverContext() = pContext;
		return OpenVRInternal_ModuleServerDriverContext().InitWatchdog();
	}

	inline EVRInitError InitCompositorDriverContext( IVRDriverContext *pContext )
	{
		VRDriverContext() = pContext;
		return OpenVRInternal_ModuleServerDriverContext().InitCompositor();
	}

	inline void CleanupDriverContext()
	{
		VRDriverContext() = nullptr;
		OpenVRInternal_ModuleServerDriverContext().Clear();
	}

	#define VR_INIT_SERVER_DRIVER_CONTEXT( pContext ) \
			{ \
			vr::EVRInitError eError = vr::InitServerDriverContext( pContext ); \
			if( eError != vr::VRInitError_None ) \
				return eError; \
			}

	#define VR_CLEANUP_SERVER_DRIVER_CONTEXT() \
		vr::CleanupDriverContext();

	#define VR_INIT_WATCHDOG_DRIVER_CONTEXT( pContext ) \
			{ \
			vr::EVRInitError eError = vr::InitWatchdogDriverContext( pContext ); \
			if( eError != vr::VRInitError_None ) \
				return eError; \
			}

	#define VR_CLEANUP_WATCHDOG_DRIVER_CONTEXT() \
		vr::CleanupDriverContext();

#define VR_INIT_COMPOSITOR_DRIVER_CONTEXT( pContext ) \
			{ \
			vr::EVRInitError eError = vr::InitCompositorDriverContext( pContext ); \
			if( eError != vr::VRInitError_None ) \
				return eError; \
			}

#define VR_CLEANUP_COMPOSITOR_DRIVER_CONTEXT() \
		vr::CleanupDriverContext();


#endif

}


#endif


