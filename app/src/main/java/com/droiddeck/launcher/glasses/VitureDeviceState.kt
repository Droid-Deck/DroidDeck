package com.droiddeck.launcher.glasses

import kotlin.math.roundToInt

data class VitureDeviceState(
    val capabilities: Int = 0,
    val brightnessMax: Int? = null,
    val volumeMax: Int? = null,
    val filmMax: Int? = null,
    val film: Float? = null,
    val dutyCycle: Int? = null,
    val worn: Boolean? = null,
    val nativeMode: Int? = null,
    val nativeDof: Int? = null,
    val displayMode: Int? = null,
    val distance: Int? = null,
    val size: Int? = null,
    val side: Boolean? = null,
    val imuRate: Int? = null,
    val imuRateMask: Int = 0,
    val cameraVid: Int = 0,
    val cameraPid: Int = 0,
    val displayButtonEnabled: Boolean? = null,
    val defaultDisplay: Int? = null,
    val automaticExposure: Boolean = true,
    val exposureMs: Float = 4f,
    val exposureGain: Int = 0,
    val displaySelected: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val supportsNative: Boolean get() = capabilities and 8 != 0
    val supportsDefaultDisplay: Boolean get() = capabilities and 16 != 0
    val supportsDisplayButton: Boolean get() = capabilities and 32 != 0
    val supportsExposure: Boolean get() = capabilities and 64 != 0
    val supportsReset: Boolean get() = capabilities and 128 != 0
    val nativeTracking: Boolean get() = nativeMode == 1 && nativeDof in 1..2
    val nativeTrackingKnown: Boolean get() = !supportsNative || nativeMode == 0 || (nativeMode == 1 && nativeDof in 0..2)
    val ultrawide: Boolean get() = nativeMode == 1 && displayMode in listOf(0x3d, 0x40)
    val filmLevel: Int? get() = film?.let { (it * (filmMax ?: 1)).roundToInt() }
    val rates: List<Int> get() = (0..5).filter { imuRateMask and (1 shl it) != 0 }
    val displayModes: List<VitureDisplayMode> get() = when (nativeMode) {
        0 -> BYPASS_MODES
        1 -> NATIVE_MODES
        else -> emptyList()
    }
    val selectedDisplayMode: VitureDisplayMode? get() = displayModes.firstOrNull { it.code == displayMode }

    internal fun withFallbackLimits(family: VitureProtocol.Family?) = copy(
        brightnessMax = brightnessMax ?: if (family == VitureProtocol.Family.ONE) 6 else 8,
        volumeMax = volumeMax ?: when (family) { VitureProtocol.Family.ONE -> 7; VitureProtocol.Family.BEAST -> 15; else -> 8 },
        filmMax = filmMax ?: film?.let { if (family == VitureProtocol.Family.BEAST) 8 else 1 },
    )

    internal fun retainingNativeTracking(previous: VitureDeviceState?) : VitureDeviceState {
        if (!supportsNative) return this
        val mode = nativeMode ?: previous?.nativeMode
        val restoreDisplay = nativeMode == null && mode == 1
        return copy(nativeMode = mode, nativeDof = if (mode == 1)
            nativeDof ?: previous?.takeIf { it.nativeMode == 1 }?.nativeDof else null,
            displayMode = if (restoreDisplay) previous?.displayMode else displayMode,
            distance = if (restoreDisplay) previous?.distance else distance,
            size = if (restoreDisplay) previous?.size else size,
            side = if (restoreDisplay) previous?.side else side)
    }

    companion object {
        val RATE_HZ = listOf(60, 90, 120, 240, 500, 1000)
        val BYPASS_MODES = listOf(
            VitureDisplayMode(0x31, 1920, 1080, 60), VitureDisplayMode(0x41, 1920, 1200, 60),
            VitureDisplayMode(0x35, 3840, 1080, 90, stereo = true), VitureDisplayMode(0x45, 3840, 1200, 90, stereo = true),
        )
        val NATIVE_MODES = listOf(
            VitureDisplayMode(0x31, 1920, 1080, 60), VitureDisplayMode(0x33, 1920, 1080, 120),
            VitureDisplayMode(0x34, 1920, 1200, 60), VitureDisplayMode(0x36, 1920, 1200, 120),
            VitureDisplayMode(0x37, 3840, 1080, 60, stereo = true), VitureDisplayMode(0x3a, 3840, 1200, 60, stereo = true),
            VitureDisplayMode(0x3d, 3840, 1080, 60, ultrawide = true), VitureDisplayMode(0x40, 3840, 1200, 60, ultrawide = true),
        )

        internal fun read(link: VitureDeviceLink, previous: VitureDeviceState? = null,
            family: VitureProtocol.Family? = null): VitureDeviceState {
            val old = previous ?: VitureDeviceState()
            fun value(key: Int) = link.deviceValue(key).takeIf { it.isFinite() && it >= 0 }
            fun integer(key: Int) = value(key)?.roundToInt()
            val capabilities = link.deviceCapabilities().coerceAtLeast(0)
            val native = capabilities and 8 != 0
            val mode = if (native) integer(VitureSdk.VALUE_NATIVE_MODE) else null
            val film = if (capabilities and VitureSdk.CAP_FILM != 0) value(VitureSdk.VALUE_FILM) else null
            return old.copy(
                capabilities = capabilities,
                brightnessMax = integer(VitureSdk.VALUE_BRIGHTNESS_MAX),
                volumeMax = integer(VitureSdk.VALUE_VOLUME_MAX),
                filmMax = if (capabilities and VitureSdk.CAP_FILM != 0) integer(VitureSdk.VALUE_FILM_MAX) else null,
                film = film,
                dutyCycle = if (capabilities and 2 != 0) integer(VitureSdk.VALUE_DUTY_CYCLE) else null,
                worn = if (capabilities and 4 != 0) integer(VitureSdk.VALUE_WEAR)?.let { it != 0 } else null,
                nativeMode = mode,
                nativeDof = if (mode == 1) integer(VitureSdk.VALUE_NATIVE_DOF) else null,
                displayMode = if (mode == 1) integer(VitureSdk.VALUE_NATIVE_DISPLAY) else link.deviceDisplayMode().takeIf { it >= 0 },
                distance = if (mode == 1) integer(VitureSdk.VALUE_NATIVE_DISTANCE) else null,
                size = if (mode == 1) integer(VitureSdk.VALUE_NATIVE_SIZE) else null,
                side = if (mode == 1) integer(VitureSdk.VALUE_NATIVE_SIDE)?.let { it != 0 } else null,
                imuRate = integer(VitureSdk.VALUE_IMU_RATE),
                imuRateMask = integer(VitureSdk.VALUE_IMU_RATE_MASK) ?: 0,
                cameraVid = integer(VitureSdk.VALUE_CAMERA_VID) ?: 0,
                cameraPid = integer(VitureSdk.VALUE_CAMERA_PID) ?: 0,
            ).withFallbackLimits(family).retainingNativeTracking(previous)
        }
    }
}

data class VitureDisplayMode(val code: Int, val width: Int, val height: Int, val hz: Int,
    val stereo: Boolean = false, val ultrawide: Boolean = false) {
    val presentationResolution: GlassesResolution get() = GlassesResolution(width / (if (width >= 3000) 2 else 1), height)
}
