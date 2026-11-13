package com.droiddeck.launcher.glasses

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import java.io.File

internal interface VitureDeviceLink : GlassesLink {
    val trackingEnabled: Boolean
    fun deviceCapabilities(): Int
    fun deviceValue(key: Int): Float
    fun deviceDisplayMode(): Int
    fun deviceControl(command: Int, value: Float = 0f, extra: Int = 0): Int
}

internal class VitureSdkLink private constructor(
    private val connection: UsbDeviceConnection,
    private val token: Long,
    private val listener: Listener,
    family: VitureProtocol.Family?,
    productId: Int,
    private val spatial: Boolean,
) : VitureDeviceLink {
    interface Listener {
        fun onValue(msgId: Int, value: Int)
        fun onPose(q: FloatArray, timeNs: Long)
        fun onSpatial(pose: FloatArray, timeNs: Long, valid: Boolean) {}
        fun onDeviceState(id: Int, value: Int) {}
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
    @Volatile private var pollDelayMs = 4L
    private var carinaPollingRate = 3
    override val trackingEnabled: Boolean get() = tracking
    override val ready: Boolean get() = running
    override val hasImu = true
    private val openGlAxes = VitureSdkPose.usesOpenGlAxes(isCarina, family, productId)
    private val worker = Thread({ poll() }, "viture-sdk-pose").apply { isDaemon = true; start() }

    override fun deviceCapabilities(): Int = synchronized(lock) {
        if (running) VitureSdk.nativeDeviceCapabilities(token) or (if (isCarina) VitureSdk.CAP_IMU_RATE else 0) else 0
    }

    override fun deviceValue(key: Int): Float = synchronized(lock) {
        if (!running) -10f else when {
            isCarina && key == VitureSdk.VALUE_IMU_RATE -> carinaPollingRate.toFloat()
            isCarina && key == VitureSdk.VALUE_IMU_RATE_MASK -> ((1 shl 3) or (1 shl 4) or (1 shl 5)).toFloat()
            else -> VitureSdk.nativeDeviceValue(token, key)
        }
    }

    override fun deviceDisplayMode(): Int = synchronized(lock) {
        if (running) VitureSdk.nativeCommand(token, VitureSdk.GET_DISPLAY, 0) else -10
    }

    override fun deviceControl(command: Int, value: Float, extra: Int): Int = synchronized(lock) {
        if (!running) return -10
        if (isCarina && command == VitureSdk.CONTROL_IMU_RATE) {
            if (value !in listOf(3f, 4f, 5f)) return -1
            carinaPollingRate = value.toInt()
            pollDelayMs = when (carinaPollingRate) { 5 -> 1L; 4 -> 2L; else -> 4L }
            return 0
        }
        val result = VitureSdk.nativeDeviceControl(token, command, value, extra)
        if (result == 0 && command == VitureSdk.CONTROL_IMU_RATE) {
            pollDelayMs = if (value >= 4f) 1L else 4L
        }
        result
    }

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
                if (running) repeat(8) {
                    if (VitureSdk.nativeNextState(token, event) == 1) {
                        listener.onDeviceState(event[0], event[1])
                        when (event[0]) {
                        0 -> listener.onValue(VitureProtocol.MSG_GET_BRIGHTNESS, event[1])
                        1 -> listener.onValue(VitureProtocol.MSG_GET_VOLUME, event[1])
                        2 -> send(VitureProtocol.MSG_GET_DISPLAY)
                        3 -> listener.onValue(VitureProtocol.MSG_FILM, event[1])
                        }
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
            } else if (timestamp == -2L || timestamp == -3L || timestamp == -10L || timestamp == -11L) {
                if (++failures >= 10) {
                    Log.w("VitureSdkLink", "closing after $failures pose USB errors; last SDK result: $timestamp")
                    listener.onLost()
                    break
                }
            }
            SystemClock.sleep(if (tracking) pollDelayMs else 25)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!running) return
            running = false
            VitureSdk.nativeClose(token)
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
            return runCatching { VitureSdkLink(conn, token, listener, family, device.productId, spatial) }.getOrElse {
                VitureSdk.nativeClose(token)
                conn.close()
                Log.w("VitureSdkLink", "SDK worker could not start", it)
                null
            }
        }
    }
}
