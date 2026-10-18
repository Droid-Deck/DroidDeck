package com.droiddeck.launcher.glasses

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import java.io.File

/** SDK owns interface selection (including Ultra bulk endpoints) and all USB I/O. */
internal class VitureSdkLink private constructor(
    private val connection: UsbDeviceConnection,
    private val token: Long,
    private val listener: Listener,
    family: VitureProtocol.Family?,
    private val spatial: Boolean,
) : GlassesLink {
    interface Listener {
        fun onValue(msgId: Int, value: Int)
        fun onPose(q: FloatArray, timeNs: Long)
        fun onSpatial(pose: FloatArray, timeNs: Long, valid: Boolean) {}
        fun onLost()
    }

    internal val sdkToken get() = token
    internal val spatialEnabled get() = spatial
    internal val firmware = VitureSdk.nativeFirmware(token)
    internal fun spatialDiagnostics() = if (running && spatial) VitureSdk.nativeSpatialDiagnostics(token) else null
    internal val isCarina = VitureSdk.nativeType(token) == VitureSdk.CARINA
    internal val spatialIdentity = if (isCarina) VitureSpatialIdentity.fromSerialHash(VitureSdk.nativeSerialHash(token)) else null
    private val lock = Any()
    @Volatile private var running = true
    @Volatile private var tracking = false
    override val ready: Boolean get() = running
    override val hasImu = true
    private val openGlAxes = VitureSdkPose.usesOpenGlAxes(VitureSdk.nativeType(token) == VitureSdk.CARINA, family)
    private val worker = Thread({ poll() }, "viture-sdk-pose").apply { isDaemon = true; start() }

    override fun send(msgId: Int, payload: ByteArray): Boolean = synchronized(lock) {
        if (!running) return false
        val value = payload.firstOrNull()?.toInt()?.and(0xff) ?: 0
        when (msgId) {
            VitureProtocol.MSG_IMU -> {
                val wanted = value != 0
                if (wanted == tracking) return true
                val rc = VitureSdk.nativeTracking(token, wanted)
                if (rc == 0 || !wanted) tracking = wanted
                return result("tracking", rc)
            }
            // Frequency is negotiated against SDK capabilities, including Pro 2's pose limit.
            VitureProtocol.MSG_IMU_RATE -> return tracking
            VitureProtocol.MSG_GET_IMU -> return true
        }
        val operation = when (msgId) {
            VitureProtocol.MSG_GET_DISPLAY -> VitureSdk.GET_DISPLAY
            VitureProtocol.MSG_DISPLAY -> VitureSdk.SET_DISPLAY
            VitureProtocol.MSG_GET_BRIGHTNESS -> VitureSdk.GET_BRIGHTNESS
            VitureProtocol.MSG_BRIGHTNESS -> VitureSdk.SET_BRIGHTNESS
            VitureProtocol.MSG_GET_VOLUME -> VitureSdk.GET_VOLUME
            VitureProtocol.MSG_VOLUME -> VitureSdk.SET_VOLUME
            VitureProtocol.MSG_FILM -> VitureSdk.SET_FILM
            else -> return false
        }
        val rc = VitureSdk.nativeCommand(token, operation, value)
        if (rc < 0) return result("command $operation", rc)
        if (operation == VitureSdk.GET_DISPLAY || operation == VitureSdk.GET_BRIGHTNESS || operation == VitureSdk.GET_VOLUME)
            listener.onValue(msgId, rc)
        else if (operation == VitureSdk.SET_DISPLAY) {
            val actual = VitureSdk.nativeCommand(token, VitureSdk.GET_DISPLAY, 0)
            if (actual >= 0) listener.onValue(VitureProtocol.MSG_GET_DISPLAY, actual)
        }
        true
    }

    private fun result(operation: String, rc: Int): Boolean {
        if (rc < 0) Log.w("VitureSdkLink", "$operation failed: $rc")
        return rc == 0
    }

    private fun poll() {
        val pose = FloatArray(4)
        val spatialPose = FloatArray(7)
        val spatialStatus = IntArray(1)
        val event = IntArray(2)
        var failures = 0
        while (running) {
            val timestamp = synchronized(lock) {
                if (running) repeat(6) {
                    if (VitureSdk.nativeNextState(token, event) == 1) when (event[0]) {
                        0 -> listener.onValue(VitureProtocol.MSG_GET_BRIGHTNESS, event[1])
                        1 -> listener.onValue(VitureProtocol.MSG_GET_VOLUME, event[1])
                        // A device event can use a different display-mode namespace.
                        2 -> send(VitureProtocol.MSG_GET_DISPLAY)
                        3 -> listener.onValue(VitureProtocol.MSG_FILM, event[1])
                    }
                }
                if (!running || !tracking) 0L else if (spatial && isCarina) {
                    val time = VitureSdk.nativeSpatialPose(token, spatialPose, spatialStatus)
                    if (time > 0) listener.onSpatial(spatialPose, time, spatialStatus[0] == 0)
                    if (spatialStatus[0] == 0) {
                        spatialPose.copyInto(pose, 0, 3, 7)
                        time
                    } else if (spatialStatus[0] < 0) spatialStatus[0].toLong() else 0L
                } else VitureSdk.nativePose(token, pose)
            }
            if (timestamp > 0) {
                failures = 0
                VitureSdkPose.toGlasses(pose, openGlAxes)?.let { listener.onPose(it, timestamp) }
            } else if (timestamp == -2L || timestamp == -3L) {
                if (++failures >= 10) {
                    Log.w("VitureSdkLink", "closing after $failures pose USB errors; last SDK result: $timestamp")
                    listener.onLost()
                    break
                }
            }
            SystemClock.sleep(if (tracking) 4 else 25)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!running) return
            running = false
            VitureSdk.nativeClose(token)
            // Keep Java's descriptor alive until the SDK and its worker threads have stopped.
            connection.close()
        }
        if (Thread.currentThread() !== worker) worker.join(1000)
    }

    companion object {
        fun open(usb: UsbManager, device: UsbDevice, cacheDirectory: File, listener: Listener,
            family: VitureProtocol.Family? = null, spatial: Boolean = false): VitureSdkLink? {
            if (!VitureSdk.available) return null
            val conn = usb.openDevice(device) ?: return null
            val token = runCatching {
                cacheDirectory.mkdirs()
                VitureSdk.nativeOpen(device.productId, conn.fileDescriptor, cacheDirectory.absolutePath, spatial)
            }.onFailure { Log.w("VitureSdkLink", "SDK initialization failed", it) }.getOrDefault(0)
            if (token <= 0) { conn.close(); return null }
            return runCatching { VitureSdkLink(conn, token, listener, family, spatial) }.getOrElse {
                VitureSdk.nativeClose(token)
                conn.close()
                Log.w("VitureSdkLink", "SDK worker could not start", it)
                null
            }
        }
    }
}
