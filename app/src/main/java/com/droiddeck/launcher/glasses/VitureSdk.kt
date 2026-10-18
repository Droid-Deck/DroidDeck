package com.droiddeck.launcher.glasses

import android.util.Log
import kotlin.math.sqrt

/** JNI adapter to VITURE's Android C SDK. The vendor binary is a local build input. */
internal object VitureSdk {
    val available: Boolean by lazy {
        runCatching {
            System.loadLibrary("droiddeck_viture")
            nativeAvailable()
        }.onFailure { Log.w("VitureSdk", "VITURE driver unavailable", it) }.getOrDefault(false)
    }

    fun marketName(pid: Int): String? = if (available) nativeMarketName(pid) else null
    external fun nativeAvailable(): Boolean
    external fun nativeMarketName(pid: Int): String?
    external fun nativeOpen(pid: Int, fd: Int, cacheDirectory: String, spatial: Boolean = false): Long
    external fun nativeSpatialPose(token: Long, pose: FloatArray, state: IntArray): Long
    external fun nativeCapture(token: Long, enabled: Boolean, ratePermille: Int)
    external fun nativeStereo(token: Long, left: java.nio.ByteBuffer, right: java.nio.ByteBuffer, metadata: DoubleArray, head: FloatArray, waitForPose: Boolean): Long
    external fun nativeCameraSynchronized(token: Long, sourceSeconds: Double): Boolean
    external fun nativeRgbSnapshot(vid: Int, pid: Int, fd: Int, metadata: LongArray, logFd: Int): ByteArray?
    external fun nativeType(token: Long): Int
    external fun nativeFirmware(token: Long): String?
    external fun nativeSerialHash(token: Long): ByteArray?
    external fun nativeSpatialDiagnostics(token: Long): DoubleArray?
    external fun nativeTracking(token: Long, enabled: Boolean): Int
    external fun nativeCommand(token: Long, command: Int, value: Int): Int
    /** Returns CLOCK_BOOTTIME receipt time, zero for no new pose, or a negative SDK error. */
    external fun nativePose(token: Long, wxyz: FloatArray): Long
    external fun nativeNextState(token: Long, event: IntArray): Int
    external fun nativeClose(token: Long)

    const val CARINA = 2
    const val GET_DISPLAY = 1
    const val SET_DISPLAY = 2
    const val GET_BRIGHTNESS = 3
    const val SET_BRIGHTNESS = 4
    const val GET_VOLUME = 5
    const val SET_VOLUME = 6
    const val SET_FILM = 7
}

internal object VitureSpatialIdentity {
    fun fromSerialHash(hash: ByteArray?): String? = hash?.takeIf { it.size == 32 && it.any { b -> b != 0.toByte() } }
        ?.joinToString("", prefix = "viture:sn-sha256:") { "%02x".format(it.toInt() and 0xff) }
}

internal object VitureSdkPose {
    // Pro 2 hardware captures use OpenGL axes despite the generic Gen2 callback's
    // NWU documentation. Keep that model-specific exception out of other Gen2 devices.
    fun usesOpenGlAxes(carina: Boolean, family: VitureProtocol.Family?) =
        carina || family == VitureProtocol.Family.PRO2

    /** SDK quaternions are wxyz; normalize their coordinate basis at the boundary. */
    fun toGlasses(wxyz: FloatArray, openGlAxes: Boolean): FloatArray? {
        if (wxyz.size != 4 || wxyz.any { !it.isFinite() }) return null
        val length = sqrt(wxyz.sumOf { it.toDouble() * it }.toFloat())
        if (!length.isFinite() || length < 0.5f || length > 1.5f) return null
        val q = if (openGlAxes) floatArrayOf(-wxyz[3], -wxyz[1], wxyz[2], wxyz[0])
            else floatArrayOf(wxyz[1], wxyz[2], wxyz[3], wxyz[0])
        return q.map { it / length }.toFloatArray()
    }
}
