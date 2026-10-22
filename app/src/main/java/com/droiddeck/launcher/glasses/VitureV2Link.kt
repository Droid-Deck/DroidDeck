package com.droiddeck.launcher.glasses

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

internal class VitureV2Link internal constructor(
    private val transport: Transport,
    private val listener: Listener,
) : GlassesLink {
    interface Listener {
        fun onReady()
        fun onValue(msgId: Int, value: Int)
        fun onPose(raw: ByteArray, q: FloatArray, timeNs: Long)
        fun onTracking(active: Boolean)
        fun onLost()
    }

    internal data class Received(val bytes: ByteArray, val timeNs: Long)
    internal interface Transport {
        fun write(packet: ByteArray): Boolean
        fun read(timeoutMs: Long): Received?
        fun close()
    }

    private data class Command(val packet: ByteArray, val stream: Boolean? = null)
    private val commands = ArrayBlockingQueue<Command>(16)
    private val stateLock = Any()
    private val bad = AtomicLong()
    @Volatile private var running = true
    @Volatile private var wanted = true
    @Volatile override var ready = false
        private set
    @Volatile var trackingActive = false
        private set
    override val hasImu: Boolean get() = ready
    override val badFrames: Long get() = bad.get()
    @Volatile private var streamOn = false
    private var lastPoseAt = 0L
    private var lastTick: Long? = null
    private var poseNotBefore = 0L
    private val worker = Thread(::run, "viture-v2").apply { isDaemon = true; start() }

    override fun send(msgId: Int, payload: ByteArray): Boolean {
        synchronized(stateLock) {
            if (!running || !ready) return false
            if (msgId == VitureProtocol.MSG_IMU) {
                if (payload.size != 1 || payload[0].toInt() !in 0..1) return false
                val enabled = payload[0].toInt() == 1
                if (wanted == enabled) return true
                val command = Command(VitureV2Protocol.imuControl(
                    if (enabled) VitureV2Protocol.STREAM_POSE else VitureV2Protocol.STREAM_OFF), enabled)
                if (!commands.offer(command)) return false
                wanted = enabled
                if (!enabled) {
                    streamOn = false
                    tracking(false)
                }
                return true
            }
            if (msgId == VitureProtocol.MSG_GET_IMU) return true
            // Keep the measured 120 Hz mode; the generic controller requests 240 Hz.
            if (msgId == VitureProtocol.MSG_IMU_RATE) return false
            val query = when (msgId) {
                VitureProtocol.MSG_GET_BRIGHTNESS -> VitureV2Protocol.MSG_GET_BRIGHTNESS
                VitureProtocol.MSG_GET_VOLUME -> VitureV2Protocol.MSG_GET_VOLUME
                VitureProtocol.MSG_GET_DISPLAY -> VitureV2Protocol.MSG_GET_DISPLAY
                else -> return false
            }
            return payload.isEmpty() && commands.offer(Command(VitureV2Protocol.packet(query)))
        }
    }

    private fun tracking(active: Boolean) {
        if (trackingActive != active) {
            trackingActive = active
            listener.onTracking(active)
        }
    }

    private fun run() {
        try {
            if (!transport.write(VitureV2Protocol.imuControl(VitureV2Protocol.STREAM_POSE)))
                throw IOException("Could not start pose stream")
            poseNotBefore = System.nanoTime()
            streamOn = true
            lastPoseAt = System.nanoTime()
            while (running) {
                commands.poll()?.let { command ->
                    if (!transport.write(command.packet)) throw IOException("USB command failed")
                    val completedAt = System.nanoTime()
                    synchronized(stateLock) {
                        command.stream?.let {
                            streamOn = it
                            lastTick = null
                            poseNotBefore = completedAt
                            lastPoseAt = System.nanoTime()
                        }
                    }
                }
                transport.read(100)?.let(::receive)
                if (streamOn && System.nanoTime() - lastPoseAt > POSE_TIMEOUT_NS)
                    throw IOException("No fresh valid pose samples")
            }
        } catch (e: Exception) {
            if (running) {
                ready = false
                tracking(false)
                Log.w(TAG, "V2 connection lost: ${e.message}")
                listener.onLost()
            }
        } finally {
            running = false
            ready = false
            tracking(false)
            runCatching { transport.write(VitureV2Protocol.imuControl(VitureV2Protocol.STREAM_OFF)) }
            transport.close()
        }
    }

    private fun receive(received: Received) = synchronized(stateLock) {
        if (!running) return
        val frame = VitureV2Protocol.parse(received.bytes) ?: run { bad.incrementAndGet(); return }
        if (frame.msgId == VitureV2Protocol.EVENT_POSE) {
            val pose = VitureV2Protocol.pose(frame) ?: run { bad.incrementAndGet(); return }
            if (!streamOn || !wanted || received.timeNs < poseNotBefore) return
            val previous = lastTick
            if (previous != null) {
                val delta = (pose.deviceTick - previous) and 0xffffffffL
                if (delta == 0L || delta >= 0x80000000L) return
            }
            lastTick = pose.deviceTick
            lastPoseAt = System.nanoTime()
            val becameReady = !ready
            ready = true
            tracking(true)
            if (becameReady) listener.onReady()
            val q = pose.quaternion
            // Upstream right/up/back pose converted to the shared VITURE legacy basis.
            val shared = floatArrayOf(-q[2], -q[0], q[1], q[3])
            // Upstream does not establish device tick units; use host monotonic receipt time.
            if (running && wanted && streamOn) listener.onPose(received.bytes, shared, received.timeNs)
            return
        }
        VitureV2Protocol.reply(frame, VitureV2Protocol.MSG_IMU_CONTROL)?.let {
            if (!it.success) throw IOException("Pose stream rejected with status ${it.status}")
        }
        val queries = arrayOf(
            VitureV2Protocol.MSG_GET_BRIGHTNESS to VitureProtocol.MSG_GET_BRIGHTNESS,
            VitureV2Protocol.MSG_GET_VOLUME to VitureProtocol.MSG_GET_VOLUME,
            VitureV2Protocol.MSG_GET_DISPLAY to VitureProtocol.MSG_GET_DISPLAY,
        )
        for ((query, internalId) in queries) {
            val reply = VitureV2Protocol.reply(frame, query) ?: continue
            if (reply.success && reply.body.size == 1) listener.onValue(internalId, reply.body[0].toInt() and 255)
        }
    }

    override fun close() {
        synchronized(stateLock) {
            running = false
            ready = false
            wanted = false
            commands.clear()
        }
        if (Thread.currentThread() !== worker) worker.join(1500)
    }

    companion object {
        private const val TAG = "VitureV2Link"
        private const val POSE_TIMEOUT_NS = 3_000_000_000L
        const val PRODUCT_ID = 0x1301

        fun open(usb: UsbManager, device: UsbDevice, listener: Listener): VitureV2Link? {
            if (device.vendorId != VitureProtocol.VENDOR_ID || device.productId != PRODUCT_ID) return null
            val candidates = (0 until device.interfaceCount).map(device::getInterface).filter {
                it.interfaceClass == UsbConstants.USB_CLASS_HID && it.alternateSetting == 0 && it.endpointCount == 2
            }.mapNotNull { intf ->
                val endpoints = (0 until intf.endpointCount).map(intf::getEndpoint)
                val input = endpoints.singleOrNull { it.direction == UsbConstants.USB_DIR_IN &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT && it.maxPacketSize == 64 }
                val output = endpoints.singleOrNull { it.direction == UsbConstants.USB_DIR_OUT &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT && it.maxPacketSize == 64 }
                if (input != null && output != null) Triple(intf, input, output) else null
            }
            val (intf, input, output) = candidates.singleOrNull() ?: return null
            val connection = usb.openDevice(device) ?: return null
            if (!connection.claimInterface(intf, true)) { connection.close(); return null }
            return try {
                VitureV2Link(AndroidTransport(connection, intf, input, output), listener)
            } catch (e: Exception) {
                connection.releaseInterface(intf)
                connection.close()
                Log.w(TAG, "Could not initialize USB requests", e)
                null
            }
        }
    }

    private class AndroidTransport(
        private val connection: UsbDeviceConnection,
        private val intf: UsbInterface,
        input: UsbEndpoint,
        output: UsbEndpoint,
    ) : Transport {
        private val inputRequest = UsbRequest()
        private val outputRequest = UsbRequest()
        private val inputBuffer = ByteBuffer.allocateDirect(64)
        private var outputBuffer: ByteBuffer? = null
        private var outputPending = false
        private val received = ArrayDeque<Received>()

        init {
            try {
                if (!inputRequest.initialize(connection, input) || !outputRequest.initialize(connection, output))
                    throw IOException("USB request initialization failed")
                queueInput()
            } catch (e: Exception) {
                inputRequest.close()
                outputRequest.close()
                throw e
            }
        }

        private fun queueInput() {
            inputBuffer.clear()
            if (!inputRequest.queue(inputBuffer)) throw IOException("USB input queue failed")
        }

        private fun await(timeoutMs: Long) {
            val completed = try { connection.requestWait(timeoutMs) } catch (_: TimeoutException) { return }
                ?: throw IOException("USB device disconnected")
            if (completed === outputRequest) {
                outputPending = false
            } else if (completed === inputRequest) {
                val count = inputBuffer.position()
                if (count > 0) {
                    val bytes = ByteArray(count)
                    inputBuffer.flip()
                    inputBuffer.get(bytes)
                    if (received.size >= 512) throw IOException("USB input backlog overflow")
                    received.addLast(Received(bytes, System.nanoTime()))
                }
                queueInput()
            }
        }

        override fun write(packet: ByteArray): Boolean {
            if (outputPending) throw IOException("Previous USB output has not completed")
            val buffer = ByteBuffer.allocateDirect(packet.size).apply { put(packet); flip() }
            outputBuffer = buffer
            outputPending = outputRequest.queue(buffer)
            if (!outputPending) return false
            val deadline = System.nanoTime() + 250_000_000L
            while (outputPending && System.nanoTime() < deadline) await(25)
            if (outputPending) {
                outputRequest.cancel()
                throw IOException("USB output timed out")
            }
            return buffer.position() == packet.size
        }

        override fun read(timeoutMs: Long): Received? {
            if (received.isEmpty()) await(timeoutMs)
            return received.removeFirstOrNull()
        }

        override fun close() {
            inputRequest.cancel()
            if (outputPending) outputRequest.cancel()
            connection.releaseInterface(intf)
            connection.close()
            inputRequest.close()
            outputRequest.close()
            outputBuffer = null
        }
    }
}
