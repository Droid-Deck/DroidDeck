package com.droiddeck.launcher.glasses

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

internal class VitureLink private constructor(
    private val connection: UsbDeviceConnection,
    private val mcu: UsbInterface,
    private val mcuOut: UsbEndpoint,
    private val mcuIn: UsbEndpoint?,
    private val imu: UsbInterface?,
    private val imuIn: UsbEndpoint?,
    private val listener: Listener,
) : GlassesLink {
    interface Listener {
        fun onFrame(frame: VitureProtocol.Frame)
        fun onImu(payload: ByteArray, timeNs: Long)
        fun onLost()
    }

    @Volatile private var running = true
    private val writeLock = Any()
    private var lastSendAt = 0L
    private val readers = mutableListOf<Thread>()
    private val bad = AtomicLong()

    override val hasImu: Boolean get() = imuIn != null
    override val badFrames: Long get() = bad.get()

    private fun startReaders() {
        mcuIn?.let { ep -> readers += reader("viture-mcu", ep) { buf, n -> onPacket(buf, n, false) } }
        imuIn?.let { ep ->
            readers += reader("viture-imu", ep) { buf, n -> onPacket(buf, n, true) }
        }
    }

    private fun onPacket(buf: ByteArray, size: Int, imuEndpoint: Boolean) {
        val frame = VitureProtocol.parse(buf, size) ?: return
        if (!frame.crcOk) {
            if (bad.incrementAndGet() == 1L) Log.w(TAG, "dropping frames with a bad checksum")
            return
        }
        if (imuEndpoint && frame.magic == VitureProtocol.MAGIC_IMU)
            listener.onImu(frame.payload, SystemClock.elapsedRealtimeNanos())
        else listener.onFrame(frame)
    }

    private fun reader(name: String, ep: UsbEndpoint, onPacket: (ByteArray, Int) -> Unit): Thread =
        Thread({
            val buf = ByteArray(VitureProtocol.PACKET_SIZE)
            var failures = 0
            while (running) {
                val started = SystemClock.uptimeMillis()
                val n = connection.bulkTransfer(ep, buf, buf.size, READ_TIMEOUT_MS)
                if (!running) break
                if (n > 0) {
                    failures = 0
                    runCatching { onPacket(buf, n) }.onFailure { Log.w(TAG, "$name packet", it) }
                } else if (n < 0 && SystemClock.uptimeMillis() - started < READ_TIMEOUT_MS / 2) {
                    failures++
                    SystemClock.sleep(2)
                    if (failures >= LOST_AFTER) {
                        Log.w(TAG, "$name: reads keep failing, link lost")
                        if (running) listener.onLost()
                        break
                    }
                }
            }
        }, name).apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }

    override fun send(msgId: Int, payload: ByteArray): Boolean {
        val pkt = VitureProtocol.packet(msgId, payload)
        val n = synchronized(writeLock) {
            if (!running) return false
            val wait = lastSendAt + COMMAND_GAP_MS - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            connection.bulkTransfer(mcuOut, pkt, pkt.size, WRITE_TIMEOUT_MS).also { lastSendAt = SystemClock.uptimeMillis() }
        }
        Log.i(TAG, "send 0x%04X [%s] -> %d".format(msgId, payload.joinToString("") { "%02X".format(it) }, n))
        return n == pkt.size
    }

    override fun close() {
        synchronized(writeLock) { running = false }
        val self = Thread.currentThread()
        readers.forEach { if (it !== self) runCatching { it.join(READ_TIMEOUT_MS * 4L) } }
        runCatching { connection.releaseInterface(mcu) }
        imu?.let { runCatching { connection.releaseInterface(it) } }
        runCatching { connection.close() }
    }

    companion object {
        private const val TAG = "VitureLink"
        private const val READ_TIMEOUT_MS = 100
        private const val WRITE_TIMEOUT_MS = 250
        private const val LOST_AFTER = 50
        const val COMMAND_GAP_MS = 60L

        fun open(usb: UsbManager, device: UsbDevice, listener: Listener): VitureLink? {
            val hid = (0 until device.interfaceCount).map(device::getInterface)
                .filter { it.interfaceClass == UsbConstants.USB_CLASS_HID }
                .sortedBy { it.id }
            for (intf in hid) Log.i(TAG, "iface ${intf.id} eps " + (0 until intf.endpointCount).joinToString {
                val ep = intf.getEndpoint(it)
                "0x%02X".format(ep.address)
            })
            val mcu = hid.lastOrNull { it.outEndpoint() != null } ?: return null
            val imu = hid.firstOrNull { it.id != mcu.id && it.inEndpoint() != null }
            val conn = usb.openDevice(device) ?: return null
            if (!conn.claimInterface(mcu, true)) {
                conn.close()
                return null
            }
            val imuClaimed = imu != null && conn.claimInterface(imu, true)
            val link = VitureLink(conn, mcu, mcu.outEndpoint()!!, mcu.inEndpoint(),
                imu.takeIf { imuClaimed }, imu.takeIf { imuClaimed }?.inEndpoint(), listener)
            link.startReaders()
            return link
        }

        private fun UsbInterface.outEndpoint(): UsbEndpoint? = (0 until endpointCount).map(::getEndpoint)
            .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_INT }

        private fun UsbInterface.inEndpoint(): UsbEndpoint? = (0 until endpointCount).map(::getEndpoint)
            .firstOrNull { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT }
    }
}
