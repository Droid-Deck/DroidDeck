package com.droiddeck.launcher.session

import android.hardware.lights.Light
import android.hardware.lights.LightState
import android.hardware.lights.LightsManager
import android.hardware.lights.LightsRequest
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import android.view.InputDevice
import androidx.annotation.RequiresApi
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.input.DualSense
import com.droiddeck.launcher.input.DualSenseManager
import com.droiddeck.launcher.input.PadBridge
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Milestone 2 (Requirement R3): Wired DualSense Bidirectional Output Bridge.
 *
 * Listens for 48-byte DualSense USB 0x02 output reports on the abstract UNIX domain socket
 * `@droiddeck-dualsense` and watches `${SessionState.fakeInputDir}/ds_output.bin`.
 *
 * Dispatches output reports to:
 * 1. Physical controller via `DualSenseManager.activeManager?.sendOutputReport(report)`
 *    (upstream to Windows host bridge TCP socket 127.0.0.1:21337 or direct USB UsbDeviceConnection)
 * 2. Directly configured `hostBridgeOutputStream` / `usbConnection` (for direct bridge use or testing)
 * 3. Android 12+ (API 31+) OS `InputDevice` APIs (`lightsManager` for RGB lightbar, `vibratorManager` for rumble)
 */
class DualSenseOutputBridge : SessionPart() {
    @Volatile private var running = false
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var activeClientSocket: LocalSocket? = null
    private var serverThread: Thread? = null
    private var fileWatchThread: Thread? = null
    @Volatile private var lastObservedFileSeq: Long = -1L
    @Volatile private var lastRawFileReport: ByteArray? = null

    @Volatile var hostBridgeOutputStream: OutputStream? = null
    @Volatile var usbConnection: UsbDeviceConnection? = null
    @Volatile var usbOutEndpoint: UsbEndpoint? = null
    @Volatile var usbInterface: UsbInterface? = null
    @Volatile var osInputDeviceId: Int = -1

    @Volatile private var activeLightsSession: AutoCloseable? = null

    @Synchronized
    override fun start() {
        if (running) return
        running = true
        activeBridge = this

        val s = try {
            LocalServerSocket(SOCKET_NAME)
        } catch (e: Exception) {
            Log.w(TAG, "dualsense: failed to listen on @$SOCKET_NAME: $e")
            null
        }
        server = s

        if (s != null) {
            serverThread = Thread({
                val buffer = ByteArray(REPORT_SIZE)
                while (running && server === s) {
                    val client = try {
                        s.accept()
                    } catch (e: Exception) {
                        break
                    }
                    activeClientSocket = client
                    try {
                        val dis = DataInputStream(client.inputStream)
                        while (running && server === s) {
                            dis.readFully(buffer)
                            dispatchOutputReport(buffer.clone())
                        }
                    } catch (e: EOFException) {
                        // Normal client disconnection
                    } catch (e: Exception) {
                        Log.d(TAG, "dualsense: socket read error: $e")
                    } finally {
                        activeClientSocket = null
                        try { client.close() } catch (_: Exception) {}
                    }
                }
            }, "dualsense-output-socket").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "dualsense: listening on @$SOCKET_NAME")
        }

        val existingDir = SessionState.fakeInputDir
        if (existingDir != null) {
            try {
                File(existingDir, FILE_NAME).delete()
            } catch (_: Exception) {
                // ignore
            }
        }
        lastObservedFileSeq = -1L
        lastRawFileReport = null
        startFileWatcher()
    }

    @Synchronized
    override fun stop() {
        running = false
        if (activeBridge === this) {
            activeBridge = null
        }

        val s = server
        server = null
        try {
            s?.close()
        } catch (e: Exception) {
            // ignore
        }

        try {
            activeClientSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        activeClientSocket = null

        serverThread?.interrupt()
        serverThread = null

        fileWatchThread?.interrupt()
        fileWatchThread = null

        try {
            activeLightsSession?.close()
        } catch (e: Exception) {
            // ignore
        }
        activeLightsSession = null
    }

    private fun startFileWatcher() {
        fileWatchThread = Thread({
            val record = ByteArray(RECORD_SIZE)
            while (running) {
                val dir = SessionState.fakeInputDir
                if (dir != null) {
                    val file = File(dir, FILE_NAME)
                    if (file.exists()) {
                        try {
                            RandomAccessFile(file, "r").use { raf ->
                                val len = raf.length()
                                if (len >= 56) {
                                    raf.seek(0)
                                    val read = raf.read(record)
                                    if (read >= 56) {
                                        val seq = (record[0].toLong() and 0xFF) or
                                                ((record[1].toLong() and 0xFF) shl 8) or
                                                ((record[2].toLong() and 0xFF) shl 16) or
                                                ((record[3].toLong() and 0xFF) shl 24)
                                        if (seq != lastObservedFileSeq) {
                                            lastObservedFileSeq = seq
                                            val copyLen = (record[4].toInt() and 0xFF) or
                                                    ((record[5].toInt() and 0xFF) shl 8) or
                                                    ((record[6].toInt() and 0xFF) shl 16) or
                                                    ((record[7].toInt() and 0xFF) shl 24)
                                            if (copyLen >= REPORT_SIZE && record[8] == REPORT_ID_OUTPUT) {
                                                val report = ByteArray(REPORT_SIZE)
                                                System.arraycopy(record, 8, report, 0, REPORT_SIZE)
                                                dispatchOutputReport(report)
                                            }
                                        }
                                    }
                                } else if (len == REPORT_SIZE.toLong()) {
                                    raf.seek(0)
                                    val read = raf.read(record, 0, REPORT_SIZE)
                                    if (read >= REPORT_SIZE && record[0] == REPORT_ID_OUTPUT) {
                                        val report = ByteArray(REPORT_SIZE)
                                        System.arraycopy(record, 0, report, 0, REPORT_SIZE)
                                        if (!report.contentEquals(lastRawFileReport)) {
                                            lastRawFileReport = report
                                            dispatchOutputReport(report)
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // ignore concurrent read/write jitter
                        }
                    } else {
                        lastObservedFileSeq = -1L
                        lastRawFileReport = null
                    }
                }
                try {
                    Thread.sleep(FILE_POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "dualsense-file-watcher").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Reads the latest 48-byte output report from [file].
     * Supports both 64-byte native record header format (seq + len + report) and raw 48-byte report format.
     */
    fun readReportFromFile(file: File): ByteArray? {
        if (!file.exists() || !file.canRead()) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val record = ByteArray(RECORD_SIZE)
                if (len >= 56) {
                    raf.seek(0)
                    val read = raf.read(record)
                    if (read >= 56) {
                        val copyLen = (record[4].toInt() and 0xFF) or
                                ((record[5].toInt() and 0xFF) shl 8) or
                                ((record[6].toInt() and 0xFF) shl 16) or
                                ((record[7].toInt() and 0xFF) shl 24)
                        if (copyLen >= REPORT_SIZE && record[8] == REPORT_ID_OUTPUT) {
                            val report = ByteArray(REPORT_SIZE)
                            System.arraycopy(record, 8, report, 0, REPORT_SIZE)
                            return report
                        }
                    }
                }
                if (len == REPORT_SIZE.toLong()) {
                    raf.seek(0)
                    val read = raf.read(record, 0, REPORT_SIZE)
                    if (read >= REPORT_SIZE && record[0] == REPORT_ID_OUTPUT) {
                        val report = ByteArray(REPORT_SIZE)
                        System.arraycopy(record, 0, report, 0, REPORT_SIZE)
                        return report
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Explicitly poll `${SessionState.fakeInputDir}/ds_output.bin` and dispatch if valid.
     */
    fun pollDsOutputFile(): ByteArray? {
        val dir = SessionState.fakeInputDir ?: return null
        val file = File(dir, FILE_NAME)
        val report = readReportFromFile(file) ?: return null
        dispatchOutputReport(report)
        return report
    }

    /**
     * Validates and dispatches a 48-byte output report across all supported routes:
     * - Physical controller via active DualSenseManager (TCP bridge or direct USB connection)
     * - Direct hostBridgeOutputStream override
     * - Direct usbConnection override
     * - Android 12+ (API 31+) InputDevice lightsManager and vibratorManager
     */
    fun dispatchOutputReport(report: ByteArray): Boolean {
        if (report.size != REPORT_SIZE || report[0] != REPORT_ID_OUTPUT) {
            return false
        }

        // 1. Forward to active DualSenseManager if available
        val manager = DualSenseManager.activeManager
        if (manager != null && manager.sendOutputReport(report)) {
            return true
        }

        // 2. Forward to direct Host USB HID Bridge socket if active
        hostBridgeOutputStream?.let { stream ->
            try {
                synchronized(stream) {
                    stream.write(report, 0, REPORT_SIZE)
                    stream.flush()
                }
                return true
            } catch (e: Exception) {
                Log.w(TAG, "dualsense: host bridge write failed: $e")
                hostBridgeOutputStream = null
            }
        }

        // 3. Forward to direct USB DualSense via UsbDeviceConnection
        usbConnection?.let { conn ->
            try {
                val ep = usbOutEndpoint
                if (ep != null) {
                    val sent = conn.bulkTransfer(ep, report, REPORT_SIZE, 1000)
                    if (sent == REPORT_SIZE) return true
                }
                val intf = usbInterface
                if (intf != null) {
                    val sent = conn.controlTransfer(0x21, 0x09, 0x0202, intf.id, report, REPORT_SIZE, 1000)
                    if (sent == REPORT_SIZE) return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "dualsense: USB dispatch failed: $e")
                usbConnection = null
            }
        }

        // 4. Fallback to OS InputDevice LightsManager / VibratorManager (API 31+)
        val deviceId = findDualSenseInputDeviceId()
        if (Build.VERSION.SDK_INT >= 31 && deviceId >= 0) {
            return dispatchToInputDevice(report, deviceId)
        }

        return false
    }

    fun findDualSenseInputDeviceId(): Int {
        if (osInputDeviceId >= 0) return osInputDeviceId
        val slotPad = PadBridge.controllerForSlot(0)
        if (slotPad != PadBridge.NO_CONTROLLER) {
            val dev = InputDevice.getDevice(slotPad)
            if (dev != null && DualSense.isDualSense(dev)) {
                return slotPad
            }
        }
        val ids = InputDevice.getDeviceIds()
        for (id in ids) {
            val dev = InputDevice.getDevice(id)
            if (dev != null && DualSense.isDualSense(dev)) {
                return id
            }
        }
        return -1
    }

    internal var lightsDispatcher: ((report: ByteArray, device: InputDevice) -> Boolean)? = null
    internal var vibratorDispatcher: ((report: ByteArray, device: InputDevice) -> Boolean)? = null

    @RequiresApi(31)
    fun dispatchToInputDevice(report: ByteArray, deviceId: Int): Boolean {
        val device = InputDevice.getDevice(deviceId) ?: return false
        var handled = false
        val enable0 = report[1].toInt() and 0xFF
        val enable1 = report[2].toInt() and 0xFF
        val clearVisibleLeds = DualSense.shouldClearVisibleLeds(report)

        // 1. Lights: RGB Lightbar (valid_flag1 & 0x04), Player ID LEDs (0x10), Mic Mute LED (0x01), Clear Visible LEDs (0x08)
        if ((enable1 and 0x15) != 0 || clearVisibleLeds) {
            val customLights = lightsDispatcher
            if (customLights != null) {
                if (customLights(report, device)) handled = true
            } else {
                try {
                    val lm = device.lightsManager
                    val lights = lm.lights
                    val reqBuilder = LightsRequest.Builder()
                    var addedAny = false

                    if ((enable1 and 0x04) != 0 || clearVisibleLeds) {
                        val color = if ((enable1 and 0x04) != 0) {
                            val r = report[45].toInt() and 0xFF
                            val g = report[46].toInt() and 0xFF
                            val b = report[47].toInt() and 0xFF
                            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        } else {
                            0xFF000000.toInt()
                        }
                        val rgbLight = lights.firstOrNull { it.hasRgbControl() }
                        if (rgbLight != null) {
                            reqBuilder.addLight(rgbLight, LightState.Builder().setColor(color).build())
                            addedAny = true
                        }
                    }

                    if ((enable1 and 0x10) != 0 || clearVisibleLeds) {
                        val playerId = if ((enable1 and 0x10) != 0) {
                            when (val playerMask = report[44].toInt() and 0x1F) {
                                0x04 -> 1
                                0x0A -> 2
                                0x15 -> 3
                                0x1B -> 4
                                0x1F -> 5
                                0x00 -> 0
                                else -> playerMask.coerceIn(0, 5)
                            }
                        } else {
                            0
                        }
                        val playerLight = lights.firstOrNull { it.type == Light.LIGHT_TYPE_PLAYER_ID }
                        if (playerLight != null) {
                            reqBuilder.addLight(playerLight, LightState.Builder().setPlayerId(playerId).build())
                            addedAny = true
                        }
                    }

                    if ((enable1 and 0x01) != 0) {
                        val micMode = report[9].toInt() and 0xFF
                        val micLight = lights.firstOrNull { it.type == Light.LIGHT_TYPE_MICROPHONE }
                        if (micLight != null) {
                            val micColor = if (micMode != 0) 0xFFFFFFFF.toInt() else 0
                            reqBuilder.addLight(micLight, LightState.Builder().setColor(micColor).build())
                            addedAny = true
                        }
                    }

                    if (addedAny) {
                        val session = (activeLightsSession as? LightsManager.LightsSession) ?: try {
                            lm.openSession().also { activeLightsSession = it }
                        } catch (e: Exception) {
                            null
                        }
                        if (session != null) {
                            session.requestLights(reqBuilder.build())
                            handled = true
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "dualsense: lights error: $e")
                    try { activeLightsSession?.close() } catch (_: Exception) {}
                    activeLightsSession = null
                }
            }
        }

        // 2. Rumble (valid_flag0 & 0x03 or valid_flag2 & 0x04)
        val hasRumble = (enable0 and 0x03) != 0 || (report.size > 39 && (report[39].toInt() and 0x04) != 0)
        if (hasRumble) {
            val customVib = vibratorDispatcher
            if (customVib != null) {
                if (customVib(report, device)) handled = true
            } else {
                try {
                    val vm = device.vibratorManager
                    val rightWeak = report[3].toInt() and 0xFF
                    val leftStrong = report[4].toInt() and 0xFF
                    if (DualSense.shouldClearRumble(report) || (leftStrong == 0 && rightWeak == 0)) {
                        vm.cancel()
                        handled = true
                    } else {
                        val ids = vm.vibratorIds
                        if (ids.size >= 2) {
                            val combined = CombinedVibration.startParallel()
                            if (leftStrong > 0) {
                                combined.addVibrator(ids[0], VibrationEffect.createOneShot(2000L, leftStrong))
                            }
                            if (rightWeak > 0) {
                                combined.addVibrator(ids[1], VibrationEffect.createOneShot(2000L, rightWeak))
                            }
                            vm.vibrate(combined.combine())
                            handled = true
                        } else if (ids.isNotEmpty()) {
                            val max = maxOf(leftStrong, rightWeak).coerceIn(1, 255)
                            vm.vibrate(CombinedVibration.createParallel(VibrationEffect.createOneShot(2000L, max)))
                            handled = true
                        } else {
                            val defaultVibrator = vm.defaultVibrator
                            if (defaultVibrator.hasVibrator()) {
                                val max = maxOf(leftStrong, rightWeak).coerceIn(1, 255)
                                vm.vibrate(CombinedVibration.createParallel(VibrationEffect.createOneShot(2000L, max)))
                                handled = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "dualsense: vibration error: $e")
                }
            }
        }
        return handled
    }

    companion object {
        private const val TAG = "DualSenseOutput"
        const val SOCKET_NAME = "droiddeck-dualsense"
        const val FILE_NAME = "ds_output.bin"
        const val REPORT_ID_OUTPUT: Byte = 0x02
        const val REPORT_SIZE = 48
        const val RECORD_SIZE = 64
        const val FILE_POLL_INTERVAL_MS = 50L

        @Volatile
        var activeBridge: DualSenseOutputBridge? = null
    }
}
