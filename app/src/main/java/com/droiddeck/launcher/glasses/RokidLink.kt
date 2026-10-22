package com.droiddeck.launcher.glasses

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log

/** Controls are vendor control transfers; the HID interrupt endpoint streams IMU reports unprompted. */
internal class RokidLink private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpoint: UsbEndpoint,
    private val listener: Listener,
) : GlassesLink {
    interface Listener {
        fun onReady()
        fun onValue(message: Int, value: Int)
        fun onPose(raw: ByteArray, pose: FloatArray, timeNs: Long)
        fun onLost()
    }

    @Volatile private var running = true
    @Volatile override var ready = false
        private set
    @Volatile private var tracking = false
    private val controlLock = Any()
    private lateinit var worker: Thread
    override val hasImu = true

    override fun send(msgId: Int, payload: ByteArray): Boolean {
        if (!running) return false
        val value = payload.firstOrNull()?.toInt()?.and(0xff) ?: -1
        return when (msgId) {
            VitureProtocol.MSG_IMU -> (value in 0..1).also { if (it) tracking = value == 1 }
            VitureProtocol.MSG_GET_DISPLAY -> display()?.let { listener.onValue(msgId, it) } != null
            VitureProtocol.MSG_DISPLAY -> value in RokidModel.MODES &&
                control(RokidModel.REQUEST_SET_DISPLAY, value, RokidModel.INDEX_DISPLAY) &&
                true.also { listener.onValue(VitureProtocol.MSG_GET_DISPLAY, value) }
            VitureProtocol.MSG_GET_BRIGHTNESS -> read(RokidModel.REQUEST_GET_BRIGHTNESS, RokidModel.INDEX_BRIGHTNESS)
                ?.let { listener.onValue(VitureProtocol.MSG_BRIGHTNESS, RokidModel.brightnessStep(it)) } != null
            VitureProtocol.MSG_BRIGHTNESS -> value in 0..RokidModel.BRIGHTNESS_STEPS &&
                control(RokidModel.REQUEST_SET_BRIGHTNESS, RokidModel.brightnessRaw(value), RokidModel.INDEX_BRIGHTNESS)
            else -> false // Volume is the glasses' USB audio device; there is no film or IMU-rate control.
        }
    }

    private fun display(): Int? = read(RokidModel.REQUEST_GET_DISPLAY, RokidModel.INDEX_DISPLAY)

    private fun read(request: Int, index: Int): Int? = synchronized(controlLock) {
        if (!running) return null
        val buffer = ByteArray(64)
        val count = connection.controlTransfer(VENDOR_IN, request, 0, index, buffer, buffer.size, TIMEOUT_MS)
        // Display mode is reported in the second byte, brightness in the first.
        val offset = if (request == RokidModel.REQUEST_GET_DISPLAY) 1 else 0
        if (count > offset) buffer[offset].toInt() and 0xff else null
    }

    private fun control(request: Int, value: Int, index: Int): Boolean = synchronized(controlLock) {
        running && connection.controlTransfer(VENDOR_OUT, request, value, index, byteArrayOf(1), 1, TIMEOUT_MS) >= 0
    }

    private fun run() {
        val buffer = ByteArray(maxOf(endpoint.maxPacketSize, 64))
        val parser = RokidProtocol.Parser()
        var fusion = ImuFusion()
        var wasTracking = false
        var brightness = -1
        var lastSensor = SystemClock.elapsedRealtime()
        try {
            ready = true
            listener.onReady()
            while (running) {
                val count = connection.bulkTransfer(endpoint, buffer, buffer.size, 250)
                val now = SystemClock.elapsedRealtime()
                val sample = if (count > 0) parser.parse(buffer, count) else null
                if (sample == null) {
                    if (now - lastSensor > 3000) error("Rokid IMU stream stalled")
                    continue
                }
                lastSensor = now
                sample.brightness?.takeIf { it != brightness }?.let {
                    brightness = it
                    listener.onValue(VitureProtocol.MSG_BRIGHTNESS, RokidModel.brightnessStep(it))
                }
                val enabled = tracking
                if (enabled && !wasTracking) fusion = ImuFusion()
                wasTracking = enabled
                if (!enabled) continue
                // Max captures: already right/up/back; level gravity +Y, turn left +gyroY, look up +gyroX, roll right -gyroZ.
                fusion.update(sample.timeNs, sample.accel, sample.gyro)?.let {
                    if (running && tracking) listener.onPose(buffer.copyOf(count), it, SystemClock.elapsedRealtimeNanos())
                }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "Rokid stream failed", e)
        } finally {
            ready = false
            if (running) listener.onLost()
        }
    }

    override fun close() {
        synchronized(controlLock) {
            if (!running) return
            running = false
        }
        if (Thread.currentThread() !== worker) worker.join(500)
        runCatching { connection.releaseInterface(usbInterface) }
        connection.close()
    }

    companion object {
        private const val TAG = "RokidLink"
        private const val TIMEOUT_MS = 250
        private const val VENDOR_IN = UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR
        private const val VENDOR_OUT = UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR

        internal fun sensorEndpoint(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass != UsbConstants.USB_CLASS_HID) continue
                for (e in 0 until iface.endpointCount) {
                    val endpoint = iface.getEndpoint(e)
                    if (endpoint.direction == UsbConstants.USB_DIR_IN &&
                        endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT) return iface to endpoint
                }
            }
            return null
        }

        fun open(usb: UsbManager, device: UsbDevice, listener: Listener): RokidLink? {
            val (iface, endpoint) = sensorEndpoint(device) ?: return null
            val conn = usb.openDevice(device) ?: return null
            if (!conn.claimInterface(iface, true)) {
                conn.close()
                return null
            }
            return RokidLink(conn, iface, endpoint, listener).also { link ->
                link.worker = Thread({ link.run() }, "rokid-imu").apply { isDaemon = true }
                link.worker.start()
            }
        }
    }
}
