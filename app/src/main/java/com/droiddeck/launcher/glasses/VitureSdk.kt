package com.droiddeck.launcher.glasses

import android.util.Log
import java.io.File
import java.util.Properties
import kotlin.math.sqrt

internal object VitureSdk {
    private val bridgeAvailable: Boolean by lazy {
        runCatching {
            System.loadLibrary("droiddeck_viture")
            true
        }.onFailure { Log.w("VitureSdk", "VITURE driver unavailable", it) }.getOrDefault(false)
    }
    private var bundledChecked = false
    @Volatile private var loaded = false

    val available: Boolean
        get() = loaded || synchronized(this) {
            if (!bundledChecked) {
                bundledChecked = true
                loaded = bridgeAvailable && runCatching { nativeAvailable() }.getOrDefault(false)
            }
            loaded
        }

    @Synchronized
    fun loadImported(directory: File): Boolean {
        if (!directory.isDirectory || !bridgeAvailable) return false
        val accepted = runCatching {
            val record = File(directory, "manifest.properties")
            if (!record.isFile || record.length() !in 1L..8192L) return@runCatching false
            val manifest = Properties().apply { record.inputStream().use { load(it) } }
            val expectedVersion = manifest.getProperty("version") ?: return@runCatching false
            if (!expectedVersion.matches(Regex("2\\.4\\.\\d+"))) return@runCatching false
            nativeLoad(directory.absolutePath, expectedVersion)
        }
            .onFailure { Log.w("VitureSdk", "Could not load imported VITURE SDK", it) }.getOrDefault(false)
        if (accepted) { loaded = true; bundledChecked = true }
        return accepted
    }

    fun marketName(pid: Int): String? = if (available) nativeMarketName(pid) else null
    external fun nativeAvailable(): Boolean
    private external fun nativeLoad(directory: String, expectedVersion: String): Boolean
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
    external fun nativeDeviceCapabilities(token: Long): Int
    external fun nativeDeviceValue(token: Long, key: Int): Float
    external fun nativeDeviceControl(token: Long, command: Int, value: Float = 0f, extra: Int = 0): Int
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

    const val CAP_FILM = 1
    const val CAP_DUTY_CYCLE = 2
    const val CAP_WEAR = 4
    const val CAP_NATIVE = 8
    const val CAP_DISPLAY_DEFAULT = 16
    const val CAP_DISPLAY_BUTTON = 32
    const val CAP_CARINA_EXPOSURE = 64
    const val CAP_CARINA_RESET = 128
    const val CAP_IMU_RATE = 256
    const val CAP_LIMITS = 512

    const val VALUE_BRIGHTNESS_MAX = 1
    const val VALUE_VOLUME_MAX = 2
    const val VALUE_FILM_MAX = 3
    const val VALUE_FILM = 4
    const val VALUE_DUTY_CYCLE = 5
    const val VALUE_WEAR = 6
    const val VALUE_NATIVE_MODE = 7
    const val VALUE_NATIVE_DOF = 8
    const val VALUE_NATIVE_DISPLAY = 9
    const val VALUE_NATIVE_DISTANCE = 10
    const val VALUE_NATIVE_SIZE = 11
    const val VALUE_NATIVE_SIDE = 12
    const val VALUE_IMU_RATE = 13
    const val VALUE_IMU_RATE_MASK = 14
    const val VALUE_CAMERA_VID = 15
    const val VALUE_CAMERA_PID = 16

    const val CONTROL_FILM = 1
    const val CONTROL_DUTY_CYCLE = 2
    const val CONTROL_NATIVE_MODE = 3
    const val CONTROL_NATIVE_DOF = 4
    const val CONTROL_NATIVE_RECENTER = 5
    const val CONTROL_NATIVE_DISPLAY = 6
    const val CONTROL_NATIVE_DISTANCE = 7
    const val CONTROL_NATIVE_SIZE = 8
    const val CONTROL_NATIVE_SIDE = 9
    const val CONTROL_DEFAULT_DISPLAY = 10
    const val CONTROL_DISPLAY_BUTTON = 11
    const val CONTROL_CARINA_AUTO_EXPOSURE = 12
    const val CONTROL_CARINA_MANUAL_EXPOSURE = 13
    const val CONTROL_CARINA_RESET = 14
    const val CONTROL_IMU_RATE = 15
    const val CONTROL_CARINA_RECENTER = 16
}

internal object VitureSpatialIdentity {
    fun fromSerialHash(hash: ByteArray?): String? = hash?.takeIf { it.size == 32 && it.any { b -> b != 0.toByte() } }
        ?.joinToString("", prefix = "viture:sn-sha256:") { "%02x".format(it.toInt() and 0xff) }
}

internal object VitureSdkPose {
    fun usesOpenGlAxes(carina: Boolean, family: VitureProtocol.Family?, productId: Int? = null) =
        carina || family == VitureProtocol.Family.PRO2 || productId == VitureProtocol.BEAST_PRODUCT_ID ||
            productId == VitureProtocol.PHANTOM_PRODUCT_ID

    fun toGlasses(wxyz: FloatArray, openGlAxes: Boolean): FloatArray? {
        if (wxyz.size != 4 || wxyz.any { !it.isFinite() }) return null
        val length = sqrt(wxyz.sumOf { it.toDouble() * it }.toFloat())
        if (!length.isFinite() || length < 0.5f || length > 1.5f) return null
        val q = if (openGlAxes) floatArrayOf(-wxyz[3], -wxyz[1], wxyz[2], wxyz[0])
            else floatArrayOf(wxyz[1], wxyz[2], wxyz[3], wxyz[0])
        return q.map { it / length }.toFloatArray()
    }
}
