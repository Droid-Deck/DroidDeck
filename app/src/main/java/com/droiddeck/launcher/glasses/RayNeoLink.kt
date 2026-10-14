package com.droiddeck.launcher.glasses

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log

/** RayNeo command/response and IMU share one endpoint. Keep reads on a single worker. */
internal class RayNeoLink private constructor(
    private val connection: UsbDeviceConnection,
    private val endpoints: RayNeoEndpointSelection,
    private val listener: Listener,
    private val gt: Boolean,
    initialCalibration: RayNeoCalibration?,
) : GlassesLink {
    interface Listener {
        fun onReady()
        fun onPose(raw: ByteArray, xyzw: FloatArray, timeNs: Long)
        fun onLost()
        fun onCalibration(revision: Long, phase: CalibrationPhase, progress: Int) {}
        fun onCalibrationSaved(revision: Long, value: RayNeoCalibration?) {}
    }

    @Volatile private var running = true
    @Volatile override var ready = false
        private set
    @Volatile private var wantImu = false
    private val writeLock = Any()
    private val assembler = RayNeoPacketAssembler()
    private val readBuffer = ByteArray(maxOf(endpoints.inEndpoint.maxPacketSize, 64))
    private var failures = 0
    private lateinit var worker: Thread
    private var calibration = initialCalibration
    private data class CalibrationCommand(val revision: Long, val action: Int, val saved: RayNeoCalibration? = null)
    private val calibrationCommand = java.util.concurrent.atomic.AtomicReference<CalibrationCommand?>(null)
    fun startCalibration(revision: Long) { calibrationCommand.set(CalibrationCommand(revision, 1)) }
    fun cancelCalibration(revision: Long, saved: RayNeoCalibration?) { calibrationCommand.set(CalibrationCommand(revision, 2, saved)) }
    fun resetCalibration(revision: Long) { calibrationCommand.set(CalibrationCommand(revision, 3)) }
    override val hasImu = true

    override fun send(msgId: Int, payload: ByteArray): Boolean {
        if (!running) return false
        return when (msgId) {
            VitureProtocol.MSG_IMU -> {
                wantImu = payload.firstOrNull() == 1.toByte()
                true
            }
            VitureProtocol.MSG_DISPLAY -> {
                val mode = payload.firstOrNull()?.toInt()?.and(0xff)
                ready && (mode == RayNeoProtocol.CMD_SWITCH_TO_3D || mode == RayNeoProtocol.CMD_SWITCH_TO_2D) &&
                    command(mode)
            }
            // No VITURE brightness, volume, film, rate or raw command is sent to RayNeo.
            else -> false
        }
    }

    private fun command(cmd: Int): Boolean = synchronized(writeLock) {
        if (!running) return false
        val packet = RayNeoProtocol.buildCommandPacket(cmd, 0, endpoints.outEndpoint.maxPacketSize)
        connection.bulkTransfer(endpoints.outEndpoint, packet, packet.size, 250) == packet.size
    }

    private fun read(timeoutMs: Int): ByteArray? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (running && SystemClock.elapsedRealtime() < deadline) {
            assembler.nextPacket()?.let { return it }
            val began = SystemClock.elapsedRealtime()
            val count = connection.bulkTransfer(endpoints.inEndpoint, readBuffer, readBuffer.size, 50)
            if (count > 0) {
                failures = 0
                assembler.append(readBuffer, count)
            } else if (count < 0 && SystemClock.elapsedRealtime() - began < 25) {
                if (++failures >= 50) error("RayNeo USB link lost")
                SystemClock.sleep(5)
            }
        }
        return null
    }

    private fun response(cmd: Int, timeoutMs: Int): RayNeoPacket.Response? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (running && SystemClock.elapsedRealtime() < deadline) {
            val packet = read(100)?.let(RayNeoProtocol::classifyPacket)
            if (packet is RayNeoPacket.Response && packet.cmd == cmd) return packet
        }
        return null
    }

    private fun run() {
        var streaming = false
        try {
            check(command(RayNeoProtocol.CMD_ACQUIRE_DEVICE_INFO)) { "RayNeo device-info write failed" }
            val info = response(RayNeoProtocol.CMD_ACQUIRE_DEVICE_INFO, 2500)
                ?.let { RayNeoProtocol.parseDeviceInfo(it.raw) } ?: error("RayNeo device-info timed out")
            val rotation = if (info.boardId == RayNeoProtocol.BOARD_AIR_4_PRO) -20f else 0f
            var filter = RayNeoOrientationFilter(rotation, gt, calibration)
            var collector: RayNeoCalibrationCollector? = null
            var calibrationRevision = 0L
            var lastCalibrationUpdate = 0L
            var warmupUntil = 0L
            var lastSensor = 0L
            ready = true
            listener.onReady()
            while (running) {
                val request = calibrationCommand.getAndSet(null)
                if (request != null) calibrationRevision = request.revision
                when (request?.action) {
                    1 -> collector = RayNeoCalibrationCollector(RayNeoSensorAxes(rotation, gt), SystemClock.elapsedRealtime())
                    2 -> {
                        collector = null
                        if (calibration != request.saved) {
                            calibration = request.saved
                            filter = RayNeoOrientationFilter(rotation, gt, calibration)
                        }
                        listener.onCalibration(calibrationRevision, CalibrationPhase.IDLE, 0)
                    }
                    3 -> {
                        collector = null; calibration = null
                        filter = RayNeoOrientationFilter(rotation, gt)
                        listener.onCalibrationSaved(calibrationRevision, null)
                        listener.onCalibration(calibrationRevision, CalibrationPhase.IDLE, 0)
                    }
                }
                if (wantImu != streaming) {
                    val cmd = if (wantImu) RayNeoProtocol.CMD_OPEN_IMU else RayNeoProtocol.CMD_CLOSE_IMU
                    check(command(cmd) && response(cmd, 1500) != null) { "RayNeo IMU command timed out" }
                    streaming = cmd == RayNeoProtocol.CMD_OPEN_IMU
                    filter = RayNeoOrientationFilter(rotation, gt, calibration)
                    lastSensor = SystemClock.elapsedRealtime()
                    warmupUntil = lastSensor + 2000
                }
                val raw = read(100)
                if (streaming && SystemClock.elapsedRealtime() - lastSensor > 4000) error("RayNeo IMU stream stalled")
                if (raw == null) continue
                val packet = RayNeoProtocol.classifyPacket(raw)
                if (streaming && packet is RayNeoPacket.Sensor) {
                    lastSensor = SystemClock.elapsedRealtime()
                    collector?.let { c ->
                        val result = c.update(packet.sample, lastSensor)
                        if (result != null && calibrationCommand.get() == null) {
                            calibration = result
                            filter = RayNeoOrientationFilter(rotation, gt, result)
                            warmupUntil = lastSensor + 2000
                            listener.onCalibrationSaved(calibrationRevision, result)
                        }
                        if (lastSensor - lastCalibrationUpdate >= 200 || result != null || c.phase == CalibrationPhase.FAILED) {
                            listener.onCalibration(calibrationRevision, c.phase, c.progress)
                            lastCalibrationUpdate = lastSensor
                        }
                        if (result != null || c.phase == CalibrationPhase.FAILED) collector = null
                    }
                    val wxyz = filter.update(packet.sample)
                    if (wantImu && collector == null && lastSensor >= warmupUntil) {
                        // Normalize basis and component order at the hardware boundary.
                        listener.onPose(raw, RayNeoOrientationFilter.glassesPose(wxyz),
                            SystemClock.elapsedRealtimeNanos())
                    }
                }
            }
        } catch (e: Exception) {
            if (running) Log.w("RayNeoLink", "RayNeo stream failed", e)
        } finally {
            ready = false
            if (running) listener.onLost()
        }
    }

    override fun close() {
        synchronized(writeLock) {
            if (!running) return
            // Best effort: stop the sensor before releasing the claimed interface.
            if (ready) runCatching { command(RayNeoProtocol.CMD_CLOSE_IMU) }
            running = false
        }
        if (Thread.currentThread() !== worker) worker.join(500)
        runCatching { connection.releaseInterface(endpoints.usbInterface) }
        connection.close()
    }

    companion object {
        fun open(usb: UsbManager, device: UsbDevice, listener: Listener, calibration: RayNeoCalibration? = null): RayNeoLink? {
            val endpoints = RayNeoProtocol.selectEndpoints(device) ?: return null
            val conn = usb.openDevice(device) ?: return null
            if (!conn.claimInterface(endpoints.usbInterface, true)) {
                conn.close()
                return null
            }
            return RayNeoLink(conn, endpoints, listener, RayNeoProtocol.isGt(device.vendorId, device.productId), calibration).also { link ->
                link.worker = Thread({ link.run() }, "rayneo-imu").apply { isDaemon = true }
                link.worker.start()
            }
        }
    }
}
