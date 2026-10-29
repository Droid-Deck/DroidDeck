package com.droiddeck.launcher.input

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.SteamDeckPad
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class DualSenseManager(private val context: Context) {
    @Volatile var isDualSenseActive: Boolean = false
        private set

    @Volatile var cachedPreference: String? = null
        private set

    private var lastPadState: DualSense.State? = null

    @Volatile private var running = false
    @Volatile private var stopped = false
    @Volatile private var hasDetached = false

    @Volatile var isRawHidActive: Boolean = false
        private set
    @Volatile private var lastRawHidTimestamp = 0L
    /** Vendor/product of the pad read over a claimed USB connection; null when raw frames come from the host bridge. */
    @Volatile private var rawUsbIds: Pair<Int, Int>? = null
    /** The Android input device that duplicates the raw-HID pad, or -1 when it cannot be told apart from other pads. */
    @Volatile private var rawTwinDeviceId = -1

    @Volatile var onStateUpdated: ((DualSense.State) -> Unit)? = null
    @Volatile var onActivationChanged: ((Boolean) -> Unit)? = null
    @Volatile var uiOverlayActive: Boolean = false

    private var tcpThread: Thread? = null
    @Volatile private var tcpSocket: Socket? = null
    @Volatile private var startGeneration: Int = 0
    @Volatile var tcpOutputStream: OutputStream? = null
    private val activeSources = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private var padBinRaf: RandomAccessFile? = null
    private var lastFakeInputDir: File? = null
    private var lastPadBinBytes: ByteArray? = null
    private val padBinScratch = ByteArray(DualSense.REPORT_SIZE_INPUT)
    private val lastWrittenPadBin = ByteArray(DualSense.REPORT_SIZE_INPUT)
    private var hasLastWrittenPadBin = false
    private var padBinLengthSet = false

    // Direct Android USB HID Reader / Writer state
    @Volatile var usbConnection: UsbDeviceConnection? = null
    @Volatile var usbInterface: UsbInterface? = null
    @Volatile var usbEndpointIn: UsbEndpoint? = null
    @Volatile var usbEndpointOut: UsbEndpoint? = null
    @Volatile var usbCalibration: DualSense.Calibration? = null
    @Volatile var rawCalibration05: ByteArray? = null
        private set
    @Volatile var usbFirmwareInfo: DualSense.FirmwareInfo? = null
    @Volatile var usbPairingInfo: DualSense.PairingInfo? = null
    val motionNoiseCalibration = DualSense.MotionNoiseCalibration()
    private var usbReaderThread: Thread? = null

    private var bluetoothReportReceiverRegistered = false
    private val bluetoothReportReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent == null) return
            val action = intent.action
            if (action == "android.bluetooth.hidhost.profile.action.REPORT" ||
                action == "android.bluetooth.input.profile.action.REPORT"
            ) {
                val reportData = intent.getByteArrayExtra("android.bluetooth.hidhost.profile.extra.REPORT")
                    ?: intent.getByteArrayExtra("android.bluetooth.input.profile.extra.REPORT")
                    ?: intent.getByteArrayExtra("android.bluetooth.profile.extra.REPORT")
                    ?: return
                onBluetoothFeatureReportReceived(reportData)
            }
        }
    }

    // Phase 2: Wireless (Bluetooth / InputDevice) state
    @Volatile var isEdgeConnected: Boolean = false
    @Volatile private var routesPadAudio = false
    @Volatile private var lightbarMode: String = ControllerPrefs.LIGHTBAR_TINT
    @Volatile private var lightbarTint: Int = ControllerPrefs.STEAM_BLUE
    @Volatile private var playerLedsMode: String = ControllerPrefs.PLAYER_LEDS_P1
    @Volatile private var playerLedBrightness: Int = DualSense.PLAYER_LED_BRIGHTNESS_HIGH
    @Volatile private var micLedMode: String = ControllerPrefs.MIC_LED_MUTE
    @Volatile private var triggerMode: String = ControllerPrefs.TRIGGER_AUTO
    @Volatile private var leftTriggerMode: String = ControllerPrefs.TRIGGER_AUTO
    @Volatile private var rightTriggerMode: String = ControllerPrefs.TRIGGER_AUTO
    @Volatile private var touchpadEnabled: Boolean = true
    @Volatile private var gyroAutoCalEnabled: Boolean = true
    @Volatile var gameNativeTriggersActive: Boolean = false
        private set
    @Volatile private var lastBatteryPercent: Int = 100
    @Volatile private var lastBatteryCharging: Boolean = false
    @Volatile private var lastHeadphonesPlugged: Boolean = false
    @Volatile private var lastAppliedBatteryBucket: Int = -1
    /** Null until the first report after start, so the routing is sent once even if the jack never changes. */
    @Volatile private var routedHeadphones: Boolean? = null
    @Volatile private var lastRoutingFailureMs = 0L
    @Volatile var btConnectTimestampMs: Long = 0L
    private var btOutputSeq: Int = 0
    @Volatile var pendingBtLightbarReport: ByteArray? = null
    @Volatile var lastBtOutputSentMs: Long = 0L
        private set
    @Volatile private var lastBtSentPayload: ByteArray? = null
    @Volatile var pendingBtRumbleReport: ByteArray? = null
    @Volatile var hardwareMicMuted: Boolean = false
    private var lastRawMuteButton: Boolean = false
    @Volatile var rumbleWatchdogArmed: Boolean = false
        private set
    @Volatile var lastRumbleActiveMs: Long = 0L
        private set
    private var lastFallbackLeftMotor: Int = 0
    private var lastFallbackRightMotor: Int = 0
    @Volatile var bluetoothReportSender: ((ByteArray) -> Boolean)? = null
    @Volatile private var bluetoothHidHostProxy: BluetoothProfile? = null

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private val registeredSensorManagers = ConcurrentHashMap<Int, SensorManager>()
    private val wirelessAccel = ShortArray(3)
    private val wirelessGyro = ShortArray(3)

    // OS InputDevice state for wireless touchpad & extra buttons (Mute, L4/R4/L5/R5)
    @Volatile private var osTouch0X: Short = 0
    @Volatile private var osTouch0Y: Short = 0
    @Volatile private var osTouch0Down: Boolean = false
    @Volatile private var osTouch1X: Short = 0
    @Volatile private var osTouch1Y: Short = 0
    @Volatile private var osTouch1Down: Boolean = false
    @Volatile private var osTouchpadClickDown: Boolean = false
    @Volatile private var osMuteDown: Boolean = false
    @Volatile private var osL4Down: Boolean = false
    @Volatile private var osR4Down: Boolean = false
    @Volatile private var osL5Down: Boolean = false
    @Volatile private var osR5Down: Boolean = false

    private val deviceSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            val ev = event ?: return
            val sensor = ev.sensor ?: return
            onInputDeviceSensorEvent(sensor.type, ev.values)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            this@DualSenseManager.onInputDeviceAdded(deviceId)
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            this@DualSenseManager.onInputDeviceRemoved(deviceId)
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            val dev = getInputDevice(deviceId)
            if (dev != null && DualSense.isDualSense(dev)) {
                this@DualSenseManager.onInputDeviceAdded(deviceId)
            } else {
                this@DualSenseManager.onInputDeviceRemoved(deviceId)
            }
        }
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        stopped = false
        hasDetached = false
        loadUserPreferences()
        routedHeadphones = null
        startGeneration++
        activeManager = this

        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        try {
            inputManager?.registerInputDeviceListener(inputDeviceListener, null)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register input device listener: $e")
        }

        initBluetoothHidHost()
        checkConnectedDevices()
        startTcpBridgeClient()
    }

    @Synchronized
    fun stop() {
        running = false
        stopped = true
        hasDetached = true
        startGeneration++
        val wasActiveManager = activeManager === this
        if (wasActiveManager) {
            activeManager = null
        }

        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        try {
            inputManager?.unregisterInputDeviceListener(inputDeviceListener)
        } catch (e: Exception) {
            // ignore
        }

        unregisterAllDeviceSensors()
        closeBluetoothHidHost()

        try {
            tcpSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        tcpSocket = null
        tcpThread?.interrupt()
        tcpThread = null
        tcpOutputStream = null

        closeUsbDevice()

        activeSources.clear()
        if (wasActiveManager) {
            demoteFromDualSense()
            liveStatus = StatusSnapshot()
        } else {
            isDualSenseActive = false
        }

        try {
            padBinRaf?.close()
        } catch (e: Exception) {
            // ignore
        }
        padBinRaf = null
        lastFakeInputDir = null
        lastPadBinBytes = null
        hasLastWrittenPadBin = false
        padBinLengthSet = false
    }

    fun checkConnectedDevices() {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        usbManager?.deviceList?.values?.forEach { device ->
            if (DualSense.isDualSense(device)) {
                onUsbDeviceAttached(device)
            }
        }

        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        inputManager?.inputDeviceIds?.forEach { id ->
            val dev = getInputDevice(id)
            if (dev != null && DualSense.isDualSense(dev)) {
                onInputDeviceAdded(id)
            }
        }
    }

    private fun getInputDevice(deviceId: Int): InputDevice? = try {
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        inputManager?.getInputDevice(deviceId)
    } catch (e: Exception) {
        null
    }

    fun onUsbDeviceAttached(device: UsbDevice) {
        if (stopped) return
        if (!DualSense.isDualSense(device)) return
        hasDetached = false
        if (DualSense.isEdge(device)) isEdgeConnected = true
        val key = "usb:${device.deviceName}"
        activeSources.add(key)
        openUsbDevice(device)
        promoteToDualSense()
    }

    fun onUsbDeviceDetached(device: UsbDevice) {
        val key = "usb:${device.deviceName}"
        closeUsbDevice()
        if (activeSources.remove(key) && activeSources.isEmpty()) {
            hasDetached = true
            demoteFromDualSense()
        }
    }

    private fun openUsbDevice(device: UsbDevice) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        try {
            val connection = usbManager.openDevice(device) ?: return
            var hidIntf: UsbInterface? = null
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null

            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_HID || i == 0) {
                    for (e in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(e)
                        if (ep.direction == UsbConstants.USB_DIR_IN && ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                            epIn = ep
                        } else if (ep.direction == UsbConstants.USB_DIR_OUT && ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                            epOut = ep
                        }
                    }
                    if (epIn != null) {
                        hidIntf = intf
                        break
                    }
                }
            }

            if (hidIntf == null || epIn == null) {
                connection.close()
                return
            }

            if (!connection.claimInterface(hidIntf, true)) {
                connection.close()
                return
            }

            closeUsbDevice()
            rawUsbIds = device.vendorId to device.productId
            usbConnection = connection
            usbInterface = hidIntf
            usbEndpointIn = epIn
            usbEndpointOut = epOut
            usbCalibration = queryUsbCalibration(connection, hidIntf)
            usbFirmwareInfo = queryUsbFirmwareInfo(connection, hidIntf)
            usbPairingInfo = queryUsbPairingInfo(connection, hidIntf)
            motionNoiseCalibration.reset()
            loadUserPreferences()
            applyUserPreferences()

            usbReaderThread = Thread({
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
                } catch (_: Throwable) {}
                val buffer = ByteArray(DualSense.REPORT_SIZE_INPUT)
                while (running && usbConnection === connection && !Thread.currentThread().isInterrupted) {
                    val read = connection.bulkTransfer(epIn, buffer, buffer.size, 1000)
                    if (read == DualSense.REPORT_SIZE_INPUT) {
                        onInputReport(buffer, 0)
                    } else if (read < 0 && usbConnection !== connection) {
                        break
                    }
                }
            }, "dualsense-usb-reader").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open DualSense USB device: $e")
        }
    }

    private fun queryUsbCalibration(connection: UsbDeviceConnection, hidIntf: UsbInterface): DualSense.Calibration? = try {
        val buf = ByteArray(DualSense.REPORT_SIZE_CALIBRATION)
        buf[0] = DualSense.REPORT_ID_CALIBRATION
        val len = connection.controlTransfer(0xA1, 0x01, 0x0305, hidIntf.id, buf, buf.size, 1000)
        if (len >= DualSense.REPORT_SIZE_CALIBRATION) {
            writeCalibration(buf)
            DualSense.parseCalibrationReport(buf)
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    @Synchronized
    fun writeCalibration(calReport: ByteArray): Boolean {
        if (calReport.size < DualSense.REPORT_SIZE_CALIBRATION) return false
        if (calReport[0] != DualSense.REPORT_ID_CALIBRATION) return false
        val cal = calReport.copyOf(DualSense.REPORT_SIZE_CALIBRATION)
        rawCalibration05 = cal
        val dir = SessionState.fakeInputDir ?: return false
        return try {
            val file = File(dir, "cal_05.bin")
            file.parentFile?.mkdirs()
            file.writeBytes(cal)
            true
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    fun writeCalibration05(data: ByteArray): Boolean {
        return writeCalibration(data)
    }

    private fun syncCalibrationToFakeInputDir(dir: File, cal: ByteArray? = rawCalibration05) {
        if (cal == null || cal.size < DualSense.REPORT_SIZE_CALIBRATION) return
        try {
            val file = File(dir, "cal_05.bin")
            file.parentFile?.mkdirs()
            if (!file.exists() || file.length() != cal.size.toLong()) {
                file.writeBytes(cal)
                Log.i(TAG, "Wrote ${cal.size} bytes of genuine Feature 0x05 calibration to ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write cal_05.bin: $e")
        }
    }

    fun onBluetoothFeatureReportReceived(reportData: ByteArray) {
        val calBuf: ByteArray = if (reportData.size >= DualSense.REPORT_SIZE_CALIBRATION && reportData[0] == DualSense.REPORT_ID_CALIBRATION) {
            reportData.copyOf(DualSense.REPORT_SIZE_CALIBRATION)
        } else if (reportData.size >= DualSense.REPORT_SIZE_CALIBRATION - 1) {
            ByteArray(DualSense.REPORT_SIZE_CALIBRATION).apply {
                this[0] = DualSense.REPORT_ID_CALIBRATION
                System.arraycopy(reportData, 0, this, 1, DualSense.REPORT_SIZE_CALIBRATION - 1)
            }
        } else {
            return
        }
        val cal = DualSense.parseCalibrationReport(calBuf)
        if (cal != null) {
            usbCalibration = cal
            writeCalibration(calBuf)
            Log.i(TAG, "Processed Bluetooth Feature 0x05 calibration (${calBuf.size} bytes)")
        }
    }

    private fun queryUsbFirmwareInfo(connection: UsbDeviceConnection, hidIntf: UsbInterface): DualSense.FirmwareInfo? = try {
        val buf = ByteArray(DualSense.REPORT_SIZE_FIRMWARE)
        buf[0] = DualSense.REPORT_ID_FIRMWARE
        val len = connection.controlTransfer(0xA1, 0x01, 0x0320, hidIntf.id, buf, buf.size, 1000)
        if (len >= DualSense.REPORT_SIZE_FIRMWARE) DualSense.parseFirmwareInfoReport(buf) else null
    } catch (e: Exception) {
        null
    }

    private fun queryUsbPairingInfo(connection: UsbDeviceConnection, hidIntf: UsbInterface): DualSense.PairingInfo? = try {
        val buf = ByteArray(DualSense.REPORT_SIZE_PAIRING)
        buf[0] = DualSense.REPORT_ID_PAIRING
        val len = connection.controlTransfer(0xA1, 0x01, 0x0309, hidIntf.id, buf, buf.size, 1000)
        if (len >= DualSense.REPORT_SIZE_PAIRING) DualSense.parsePairingInfoReport(buf) else null
    } catch (e: Exception) {
        null
    }

    private fun closeUsbDevice() {
        usbReaderThread?.interrupt()
        usbReaderThread = null
        try {
            usbInterface?.let { usbConnection?.releaseInterface(it) }
            usbConnection?.close()
        } catch (e: Exception) {
            // ignore
        }
        usbInterface = null
        usbConnection = null
        usbEndpointIn = null
        usbEndpointOut = null
        usbCalibration = null
        rawCalibration05 = null
        val dir = SessionState.fakeInputDir
        if (dir != null) {
            try {
                File(dir, "cal_05.bin").delete()
            } catch (_: Exception) {}
        }
        usbFirmwareInfo = null
        usbPairingInfo = null
        rawUsbIds = null
        rawTwinDeviceId = -1
        routedHeadphones = null
        hardwareMicMuted = false
        lastRawMuteButton = false
        rumbleWatchdogArmed = false
        lastRumbleActiveMs = 0L
        lastFallbackLeftMotor = 0
        lastFallbackRightMotor = 0
    }

    /**
     * Builds the next 78-byte 0x31 Bluetooth output report from a 48-byte 0x02 USB output report,
     * applying the 4-bit sequence counter (0..15), DualSense Edge flags, 0xA2 CRC-32, and
     * the 3.0-second Bluetooth startup Lightbar lockup guard.
     */
    fun buildNextBluetoothOutputReport(
        usbReport: ByteArray,
        nowMs: Long = SystemClock.uptimeMillis()
    ): ByteArray {
        val btInit = btConnectTimestampMs > 0L &&
                (nowMs - btConnectTimestampMs) in 0 until DualSense.BT_LIGHTBAR_GUARD_MS
        val hasLightbar = usbReport.size >= DualSense.REPORT_SIZE_OUTPUT &&
                (usbReport[2].toInt() and 0x04) != 0
        if (btInit && hasLightbar) {
            pendingBtLightbarReport = usbReport.copyOf(DualSense.REPORT_SIZE_OUTPUT)
        } else if (!btInit && hasLightbar) {
            pendingBtLightbarReport = null
        }
        val seq = synchronized(this) {
            val s = btOutputSeq
            btOutputSeq = (btOutputSeq + 1) and 0x0F
            s
        }
        return DualSense.buildBluetoothOutputReport(
            usbReport = usbReport,
            seq = seq,
            isEdge = isEdgeConnected,
            btInitializing = btInit
        )
    }

    fun flushPendingBluetoothLightbar(nowMs: Long = SystemClock.uptimeMillis()): Boolean {
        if (btConnectTimestampMs <= 0L || (nowMs - btConnectTimestampMs) < DualSense.BT_LIGHTBAR_GUARD_MS) {
            return false
        }
        val pending = pendingBtLightbarReport ?: return false
        pendingBtLightbarReport = null
        return sendOutputReport(pending, nowMs)
    }

    private fun updateRumbleWatchdog(report: ByteArray, nowMs: Long) {
        if (report.size < DualSense.REPORT_SIZE_OUTPUT || report[0] != DualSense.REPORT_ID_OUTPUT) return
        val hasRumble = (report[1].toInt() and 0x03) != 0 || (report[39].toInt() and 0x04) != 0
        if (!hasRumble && !DualSense.shouldClearRumble(report)) return
        if (DualSense.shouldClearRumble(report)) {
            rumbleWatchdogArmed = false
            lastRumbleActiveMs = 0L
        } else if ((report[3].toInt() and 0xFF) > 0 || (report[4].toInt() and 0xFF) > 0) {
            rumbleWatchdogArmed = true
            lastRumbleActiveMs = nowMs
        }
    }

    fun checkRumbleWatchdog(nowMs: Long = SystemClock.uptimeMillis()): Boolean {
        if (!rumbleWatchdogArmed) return false
        if (nowMs - lastRumbleActiveMs < DualSense.RUMBLE_WATCHDOG_MS) return false
        rumbleWatchdogArmed = false
        lastRumbleActiveMs = 0L
        return sendOutputReport(DualSense.buildRumbleClearReport(), nowMs)
    }

    fun sendHapticRumbleFallback(
        leftMotor: Int,
        rightMotor: Int,
        nowMs: Long = SystemClock.uptimeMillis()
    ): Boolean {
        val clampedL = leftMotor.coerceIn(0, 255)
        val clampedR = rightMotor.coerceIn(0, 255)
        if (clampedL == lastFallbackLeftMotor && clampedR == lastFallbackRightMotor && clampedL == 0 && clampedR == 0) {
            return false
        }
        lastFallbackLeftMotor = clampedL
        lastFallbackRightMotor = clampedR
        val report = if (clampedL == 0 && clampedR == 0) {
            DualSense.buildRumbleClearReport()
        } else {
            DualSense.buildOutputReport(
                leftMotor = clampedL,
                rightMotor = clampedR,
                updateLightbar = false,
                lightbarSetup = 0
            )
        }
        return sendOutputReport(report, nowMs)
    }

    private fun loadUserPreferences() {
        val prefs = ControllerPrefs.read(context)
        routesPadAudio = prefs.dualSenseAudio
        lightbarMode = prefs.dualSenseLightbarMode
        lightbarTint = prefs.tint
        playerLedsMode = prefs.dualSensePlayerLeds
        playerLedBrightness = prefs.dualSensePlayerLedBrightness
        micLedMode = prefs.dualSenseMicLedMode
        triggerMode = prefs.dualSenseTriggerMode
        leftTriggerMode = prefs.dualSenseLeftTriggerMode
        rightTriggerMode = prefs.dualSenseRightTriggerMode
        touchpadEnabled = prefs.dualSenseTouchpad
        gyroAutoCalEnabled = prefs.dualSenseGyroAutoCal
        if (!touchpadEnabled) {
            DeckControls.setDualSenseTouch(0, 0, false, 0, 0, false, false)
        }
    }

    fun refreshUserPreferences(clearTriggersIfAuto: Boolean = false) {
        loadUserPreferences()
        routedHeadphones = null
        if (isDualSenseActive) {
            applyUserPreferences(clearTriggersIfAuto)
            updateLiveStatus()
        }
    }

    fun applyUserPreferences(clearTriggersIfAuto: Boolean = false): Boolean {
        val (r, g, b) = DualSense.lightbarRgbForMode(lightbarMode, lightbarTint, lastBatteryPercent)
        val playerLeds = DualSense.playerLedMaskForMode(playerLedsMode, lastBatteryPercent)
        val micLed = DualSense.micLedForMode(micLedMode, hardwareMicMuted)
        val leftEffect = DualSense.triggerPresetEffect(leftTriggerMode)
            ?: if (clearTriggersIfAuto) DualSense.TriggerEffects.off() else null
        val rightEffect = DualSense.triggerPresetEffect(rightTriggerMode)
            ?: if (clearTriggersIfAuto) DualSense.TriggerEffects.off() else null
        lastAppliedBatteryBucket = lastBatteryPercent / 20
        val report = DualSense.buildOutputReport(
            r = r,
            g = g,
            b = b,
            micLed = micLed,
            playerLeds = playerLeds,
            playerLedBrightness = playerLedBrightness,
            rightTriggerEffect = rightEffect,
            leftTriggerEffect = leftEffect,
            isEdge = isEdgeConnected,
            updateLightbar = true,
            updatePlayerLeds = true,
        )
        if (micLedMode == ControllerPrefs.MIC_LED_OFF) {
            report[2] = (report[2].toInt() or 0x01).toByte()
            report[9] = 0
        }
        return sendOutputReport(report)
    }

    /**
     * Automatic priority switching (Auto-Passthrough) between game-driven native DualSense
     * adaptive triggers (`pad_Ctrl` / `ds_output.bin` / `@droiddeck-dualsense`) and the user's
     * configured DSX profile:
     * - When the game sends native adaptive trigger commands (`enable0 & 0x0C` with an active mode),
     *   native game triggers immediately take priority (`gameNativeTriggersActive = true`).
     * - When the game sends a rumble-only report without native trigger effects, the user's
     *   configured DSX L2/R2 trigger presets are preserved on top of the game's rumble.
     */
    fun sendGameOutputReport(
        report: ByteArray,
        nowMs: Long = SystemClock.uptimeMillis()
    ): Boolean {
        if (report.size < DualSense.REPORT_SIZE_OUTPUT || report[0] != DualSense.REPORT_ID_OUTPUT) {
            return false
        }
        if (DualSense.hasActiveTriggerEffect(report)) {
            gameNativeTriggersActive = true
            return sendOutputReport(report, nowMs)
        }
        val enable0 = report[1].toInt() and 0xFF
        val gameTouchesTriggers = (enable0 and 0x0C) != 0
        val leftEffect = DualSense.triggerPresetEffect(leftTriggerMode)
        val rightEffect = DualSense.triggerPresetEffect(rightTriggerMode)
        if (!gameNativeTriggersActive && (leftEffect != null || rightEffect != null)) {
            val merged = report.copyOf(DualSense.REPORT_SIZE_OUTPUT)
            var e0 = merged[1].toInt() and 0xFF
            var e1 = merged[2].toInt() and 0xFF
            if (rightEffect != null && (e0 and 0x04) == 0) {
                e0 = e0 or 0x04
                e1 = e1 or 0x40
                System.arraycopy(rightEffect, 0, merged, 11, 11)
            }
            if (leftEffect != null && (e0 and 0x08) == 0) {
                e0 = e0 or 0x08
                e1 = e1 or 0x40
                System.arraycopy(leftEffect, 0, merged, 22, 11)
            }
            merged[1] = e0.toByte()
            merged[2] = e1.toByte()
            return sendOutputReport(merged, nowMs)
        }
        if (gameTouchesTriggers && !DualSense.hasActiveTriggerEffect(report)) {
            gameNativeTriggersActive = false
            if (leftEffect != null || rightEffect != null) {
                val merged = report.copyOf(DualSense.REPORT_SIZE_OUTPUT)
                if (rightEffect != null) {
                    merged[1] = (merged[1].toInt() or 0x04).toByte()
                    merged[2] = (merged[2].toInt() or 0x40).toByte()
                    System.arraycopy(rightEffect, 0, merged, 11, 11)
                }
                if (leftEffect != null) {
                    merged[1] = (merged[1].toInt() or 0x08).toByte()
                    merged[2] = (merged[2].toInt() or 0x40).toByte()
                    System.arraycopy(leftEffect, 0, merged, 22, 11)
                }
                return sendOutputReport(merged, nowMs)
            }
        }
        return sendOutputReport(report, nowMs)
    }

    fun onGameOutputSessionStopped() {
        val hadNative = gameNativeTriggersActive
        gameNativeTriggersActive = false
        if (isDualSenseActive) {
            applyUserPreferences(clearTriggersIfAuto = hadNative)
        }
    }

    private fun updateLiveStatus() {
        if (activeManager !== this && !isDualSenseActive) return
        val transport = when {
            usbConnection != null -> "USB"
            tcpOutputStream != null || activeSources.contains("tcp") -> "Bridge"
            activeSources.any { it.startsWith("input:") } -> "Bluetooth"
            isRawHidActive -> "USB"
            else -> ""
        }
        val next = StatusSnapshot(
            connected = isDualSenseActive,
            isEdge = isEdgeConnected,
            transport = transport,
            batteryPercent = lastBatteryPercent,
            isCharging = lastBatteryCharging,
            headphonesPlugged = lastHeadphonesPlugged,
            gyroCalibrated = gyroAutoCalEnabled && motionNoiseCalibration.isCalibrated,
        )
        if (liveStatus != next) {
            liveStatus = next
        }
    }

    internal fun preserveSpeakerRoutingIfNeeded(data: ByteArray): ByteArray =
        if (routesPadAudio && routedHeadphones == false) DualSense.preserveSpeakerRouting(data) else data

    fun sendOutputReport(data: ByteArray, nowMs: Long = SystemClock.uptimeMillis()): Boolean {
        if (data.size < DualSense.REPORT_SIZE_OUTPUT || data[0] != DualSense.REPORT_ID_OUTPUT) {
            return false
        }
        val wiredData = preserveSpeakerRoutingIfNeeded(data)
        val tcpOut = tcpOutputStream
        if (tcpOut != null) {
            return try {
                synchronized(tcpOut) {
                    tcpOut.write(wiredData, 0, DualSense.REPORT_SIZE_OUTPUT)
                    tcpOut.flush()
                }
                updateRumbleWatchdog(wiredData, nowMs)
                true
            } catch (_: android.os.NetworkOnMainThreadException) {
                val copy = wiredData.copyOf(DualSense.REPORT_SIZE_OUTPUT)
                Thread({
                    try {
                        synchronized(tcpOut) {
                            tcpOut.write(copy, 0, DualSense.REPORT_SIZE_OUTPUT)
                            tcpOut.flush()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send output report to host TCP bridge: $e")
                        if (tcpOutputStream === tcpOut) {
                            tcpOutputStream = null
                        }
                    }
                }, "dualsense-tcp-out").apply {
                    isDaemon = true
                    start()
                }
                updateRumbleWatchdog(copy, nowMs)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send output report to host TCP bridge: $e")
                tcpOutputStream = null
                false
            }
        }
        val conn = usbConnection
        if (conn != null) {
            val epOut = usbEndpointOut
            val intf = usbInterface
            val sent = try {
                if (epOut != null) {
                    conn.bulkTransfer(epOut, wiredData, DualSense.REPORT_SIZE_OUTPUT, 1000) == DualSense.REPORT_SIZE_OUTPUT
                } else if (intf != null) {
                    // HID SET_REPORT: Request Type 0x21, Request 0x09, Value 0x0202 (Report Type Output | Report ID 2)
                    conn.controlTransfer(0x21, 0x09, 0x0202, intf.id, wiredData, DualSense.REPORT_SIZE_OUTPUT, 1000) == DualSense.REPORT_SIZE_OUTPUT
                } else false
            } catch (e: Exception) {
                false
            }
            if (sent) {
                updateRumbleWatchdog(wiredData, nowMs)
                return true
            }
        }

        // Phase 2: Wireless Bluetooth 0x31 Output Report dispatch (Adaptive Triggers, LEDs, Rumble)
        val btSender = bluetoothReportSender
        if (btSender != null || bluetoothHidHostProxy != null) {
            val prevBt = lastBtSentPayload
            if (prevBt != null && DualSense.isSameOutputPayload(prevBt, data)) {
                updateRumbleWatchdog(data, nowMs)
                return true
            }
            if (prevBt != null &&
                DualSense.isRumbleOnlyChange(prevBt, data) &&
                lastBtOutputSentMs > 0L &&
                (nowMs - lastBtOutputSentMs) in 0 until BT_RUMBLE_COALESCE_MS
            ) {
                pendingBtRumbleReport = data.copyOf(DualSense.REPORT_SIZE_OUTPUT)
                updateRumbleWatchdog(data, nowMs)
                return true
            }
            pendingBtRumbleReport = null
            val btReport = buildNextBluetoothOutputReport(data, nowMs)
            val sent = if (btSender != null) {
                btSender(btReport)
            } else {
                sendViaBluetoothHidHost(btReport)
            }
            if (sent) {
                lastBtOutputSentMs = nowMs
                lastBtSentPayload = data.copyOf(DualSense.REPORT_SIZE_OUTPUT)
                updateRumbleWatchdog(data, nowMs)
                return true
            }
        }

        return false
    }

    fun flushPendingBluetoothRumble(nowMs: Long = SystemClock.uptimeMillis()): Boolean {
        val pending = pendingBtRumbleReport ?: return false
        if (lastBtOutputSentMs > 0L && (nowMs - lastBtOutputSentMs) in 0 until BT_RUMBLE_COALESCE_MS) {
            return false
        }
        pendingBtRumbleReport = null
        return sendOutputReport(pending, nowMs)
    }

    /**
     * Sends a Bluetooth-only report (haptics 0x32) as is. The host bridge gets it framed as [0xB2, length LE16,
     * report] so it can tell it from the fixed 48-byte 0x02 reports; on a phone it goes out on the HID interrupt
     * channel through BluetoothHidHost.sendData. A wired pad plays haptics as audio instead, so USB is not tried.
     */
    fun sendBluetoothReport(report: ByteArray): Boolean {
        val tcpOut = tcpOutputStream
        if (tcpOut != null) {
            return try {
                synchronized(tcpOut) {
                    tcpOut.write(byteArrayOf(BRIDGE_FRAME_MARKER, (report.size and 0xFF).toByte(), (report.size shr 8).toByte()))
                    tcpOut.write(report)
                    tcpOut.flush()
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send a Bluetooth report to host TCP bridge: $e")
                tcpOutputStream = null
                false
            }
        }
        if (bluetoothHidHostProxy != null) return sendDataViaBluetoothHidHost(report)
        return false
    }

    private fun hasBluetoothHidPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.Manifest.permission.BLUETOOTH_CONNECT
        } else {
            android.Manifest.permission.BLUETOOTH_ADMIN
        }
        return try {
            context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    private fun sendDataViaBluetoothHidHost(report: ByteArray): Boolean {
        val proxy = bluetoothHidHostProxy ?: return false
        if (!hasBluetoothHidPermission()) {
            if (!loggedSendDataFailure) {
                loggedSendDataFailure = true
                Log.w(TAG, "BluetoothHidHost.sendData lacks Bluetooth permission; Bluetooth haptics stay off")
            }
            return false
        }
        return try {
            val target = connectedBluetoothPad(proxy) ?: return false
            val sendData = proxy.javaClass.getMethod("sendData", BluetoothDevice::class.java, String::class.java)
            sendData.invoke(proxy, target, toHex(report)) as? Boolean == true
        } catch (e: Exception) {
            if (!loggedSendDataFailure) {
                loggedSendDataFailure = true
                Log.w(TAG, "BluetoothHidHost.sendData is not available; Bluetooth haptics stay off: $e")
            }
            false
        }
    }

    @Volatile private var loggedSendDataFailure = false
    @Volatile private var loggedSetReportFailure = false
    @Volatile private var loggedGetReportFailure = false

    private fun connectedBluetoothPad(proxy: BluetoothProfile): BluetoothDevice? =
        proxy.connectedDevices?.firstOrNull { dev ->
            val name = try { dev.name ?: "" } catch (_: Exception) { "" }
            name.contains("DualSense", ignoreCase = true) || name.contains("Wireless Controller", ignoreCase = true)
        }

    private fun toHex(bytes: ByteArray): String {
        val hex = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            hex.append(HEX_CHARS[v ushr 4])
            hex.append(HEX_CHARS[v and 0x0F])
        }
        return hex.toString()
    }

    private fun initBluetoothHidHost() {
        try {
            val filter = IntentFilter().apply {
                addAction("android.bluetooth.hidhost.profile.action.REPORT")
                addAction("android.bluetooth.input.profile.action.REPORT")
            }
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    context.registerReceiver(bluetoothReportReceiver, filter, Context.RECEIVER_EXPORTED)
                } catch (_: Exception) {
                    context.registerReceiver(bluetoothReportReceiver, filter)
                }
            } else {
                context.registerReceiver(bluetoothReportReceiver, filter)
            }
            bluetoothReportReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Unable to register Bluetooth report receiver: $e")
        }
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    if (profile == HID_HOST_PROFILE) {
                        bluetoothHidHostProxy = proxy
                        requestBluetoothCalibrationFeature()
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == HID_HOST_PROFILE) {
                        bluetoothHidHostProxy = null
                    }
                }
            }, HID_HOST_PROFILE)
        } catch (e: Exception) {
            // Bluetooth not supported or permission not granted
        }
    }

    private fun closeBluetoothHidHost() {
        if (bluetoothReportReceiverRegistered) {
            try {
                context.unregisterReceiver(bluetoothReportReceiver)
            } catch (_: Exception) {}
            bluetoothReportReceiverRegistered = false
        }
        val proxy = bluetoothHidHostProxy ?: return
        bluetoothHidHostProxy = null
        try {
            BluetoothAdapter.getDefaultAdapter()?.closeProfileProxy(HID_HOST_PROFILE, proxy)
        } catch (e: Exception) {
            // ignore
        }
    }

    @Suppress("MissingPermission")
    private fun sendViaBluetoothHidHost(btReport: ByteArray): Boolean {
        val proxy = bluetoothHidHostProxy ?: return false
        if (!hasBluetoothHidPermission()) {
            if (!loggedSetReportFailure) {
                loggedSetReportFailure = true
                Log.w(TAG, "BluetoothHidHost.setReport lacks Bluetooth permission; Bluetooth output stays off")
            }
            return false
        }
        return try {
            val target = connectedBluetoothPad(proxy) ?: return false
            val setReport = proxy.javaClass.getMethod(
                "setReport",
                BluetoothDevice::class.java,
                Byte::class.javaPrimitiveType,
                String::class.java
            )
            // REPORT_TYPE_OUTPUT = 2
            val ok = setReport.invoke(proxy, target, 2.toByte(), toHex(btReport)) as? Boolean
            ok == true
        } catch (e: Exception) {
            if (!loggedSetReportFailure) {
                loggedSetReportFailure = true
                Log.w(TAG, "BluetoothHidHost.setReport is not available; Bluetooth output stays off: $e")
            }
            false
        }
    }

    @Suppress("MissingPermission")
    private fun requestBluetoothCalibrationFeature(): Boolean {
        val proxy = bluetoothHidHostProxy ?: return false
        if (!hasBluetoothHidPermission()) {
            if (!loggedGetReportFailure) {
                loggedGetReportFailure = true
                Log.w(TAG, "BluetoothHidHost.getReport lacks Bluetooth permission; extended 0x31 request skipped")
            }
            return false
        }
        return try {
            val target = connectedBluetoothPad(proxy) ?: return false
            val getReport = proxy.javaClass.getMethod(
                "getReport",
                BluetoothDevice::class.java,
                Byte::class.javaPrimitiveType,
                Byte::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            // REPORT_TYPE_FEATURE = 3, Report ID = 0x05 (switches Bluetooth DualSense into extended 0x31 report mode)
            (getReport.invoke(proxy, target, 3.toByte(), DualSense.REPORT_ID_CALIBRATION, DualSense.REPORT_SIZE_CALIBRATION) as? Boolean) == true
        } catch (e: Exception) {
            if (!loggedGetReportFailure) {
                loggedGetReportFailure = true
                Log.w(TAG, "BluetoothHidHost.getReport is not available; extended 0x31 request skipped: $e")
            }
            false
        }
    }

    fun isRawHidDelivering(): Boolean =
        isDualSenseActive && isRawHidActive && (SystemClock.uptimeMillis() - lastRawHidTimestamp < 1000L)

    fun shouldSuppressInputDevice(device: InputDevice?): Boolean {
        if (device == null || !isRawHidDelivering()) return false
        return device.id == rawTwinDeviceId
    }

    /** Maps the raw-HID pad to its Android duplicate only when a claimed USB pad has a single non-Bluetooth match. */
    private fun resolveRawTwin() {
        val ids = rawUsbIds
        if (ids == null) {
            rawTwinDeviceId = -1
            return
        }
        val candidates = InputDevice.getDeviceIds().toList().mapNotNull { getInputDevice(it) }
            .filter { DualSense.isDualSense(it) }
            .map { RawTwinCandidate(it.id, it.vendorId, it.productId, isBluetoothInputDevice(it)) }
        rawTwinDeviceId = pickRawTwin(candidates, ids)
    }

    fun onInputDeviceAdded(deviceId: Int) {
        if (stopped) return
        val dev = getInputDevice(deviceId)
        if (dev != null && DualSense.isDualSense(dev)) {
            hasDetached = false
            if (DualSense.isEdge(dev)) isEdgeConnected = true
            if (btConnectTimestampMs == 0L) {
                btConnectTimestampMs = SystemClock.uptimeMillis()
            }
            val key = "input:$deviceId"
            activeSources.add(key)
            promoteToDualSense()
            registerDeviceSensors(dev)
            if (isBluetoothInputDevice(dev)) requestBluetoothCalibrationFeature()
            if (isRawHidDelivering()) resolveRawTwin()
        }
    }

    fun onInputDeviceRemoved(deviceId: Int) {
        unregisterDeviceSensors(deviceId)
        if (deviceId == rawTwinDeviceId) rawTwinDeviceId = -1
        val key = "input:$deviceId"
        if (activeSources.remove(key) && activeSources.isEmpty()) {
            hasDetached = true
            demoteFromDualSense()
        }
    }

    private fun registerDeviceSensors(device: InputDevice) {
        if (Build.VERSION.SDK_INT < 31) return
        if (registeredSensorManagers.containsKey(device.id)) return
        try {
            val sm = device.sensorManager ?: return
            val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (gyro == null && accel == null) return

            val handler = synchronized(this) {
                var h = sensorHandler
                if (h == null) {
                    val t = HandlerThread("dualsense-wireless-imu", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
                    sensorThread = t
                    h = Handler(t.looper)
                    sensorHandler = h
                }
                h
            }
            var registered = false
            if (gyro != null && sm.registerListener(deviceSensorListener, gyro, SENSOR_SAMPLING_US, handler)) {
                registered = true
            }
            if (accel != null && sm.registerListener(deviceSensorListener, accel, SENSOR_SAMPLING_US, handler)) {
                registered = true
            }
            if (registered) {
                registeredSensorManagers[device.id] = sm
                Log.i(TAG, "Registered wireless IMU sensors for DualSense deviceId=${device.id} (gyro=${gyro != null}, accel=${accel != null})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register DualSense InputDevice sensors: $e")
        }
    }

    private fun unregisterDeviceSensors(deviceId: Int) {
        val sm = registeredSensorManagers.remove(deviceId) ?: return
        try {
            sm.unregisterListener(deviceSensorListener)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun unregisterAllDeviceSensors() {
        for ((_, sm) in registeredSensorManagers) {
            try {
                sm.unregisterListener(deviceSensorListener)
            } catch (e: Exception) {
                // ignore
            }
        }
        registeredSensorManagers.clear()
        synchronized(this) {
            sensorThread?.quitSafely()
            sensorThread = null
            sensorHandler = null
        }
        wirelessAccel.fill(0)
        wirelessGyro.fill(0)
    }

    /**
     * Processes a hardware Gyroscope (rad/s) or Accelerometer (m/s^2) event from a DualSense
     * `InputDevice.getSensorManager()` when connected wirelessly over Bluetooth.
     * Automatically suppressed when raw 64B/78B HID reports are actively delivering IMU data.
     */
    fun onInputDeviceSensorEvent(sensorType: Int, values: FloatArray): Boolean {
        if (stopped || !isDualSenseActive || isRawHidDelivering()) return false
        if (values.size < 3) return false

        val scale: Float
        val target: ShortArray
        when (sensorType) {
            Sensor.TYPE_GYROSCOPE -> {
                scale = GYRO_COUNTS_PER_RAD_S
                target = wirelessGyro
            }
            Sensor.TYPE_ACCELEROMETER -> {
                scale = ACCEL_COUNTS_PER_M_S2
                target = wirelessAccel
            }
            else -> return false
        }

        synchronized(this) {
            if (!isDualSenseActive || isRawHidDelivering()) return false
            target[0] = (values[0] * scale).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            target[1] = (-values[2] * scale).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            target[2] = (values[1] * scale).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            FakeInputWriter.setDeckIMU(0, wirelessAccel, wirelessGyro)
            DeckControls.externalImuActive = true
        }
        return true
    }

    fun onRawSourceAttached() {
        if (stopped) return
        hasDetached = false
        activeSources.add("raw")
        promoteToDualSense()
    }

    fun onRawSourceDetached() {
        if (activeSources.remove("raw") && activeSources.isEmpty()) {
            hasDetached = true
            demoteFromDualSense()
        }
    }

    @Synchronized
    fun promoteToDualSense() {
        if (stopped) return
        activeManager = this
        if (isDualSenseActive) return
        val prev = SessionPrefs.steamController(context)
        cachedPreference = prev
        val targetPadType = if (isEdgeConnected) FakeInputWriter.PAD_TYPE_DUALSENSE_EDGE else FakeInputWriter.PAD_TYPE_DUALSENSE
        FakeInputWriter.setPadType(targetPadType)
        SteamDeckPad.showPad(targetPadType)
        isDualSenseActive = true
        DeckControls.externalImuActive = true
        loadUserPreferences()
        updateLiveStatus()
        onActivationChanged?.invoke(true)
        val dir = SessionState.fakeInputDir
        if (dir != null) {
            syncCalibrationToFakeInputDir(dir)
        }
        Log.i(TAG, "DualSense activated as primary controller (cached preference: $prev -> dualsense)")
    }

    @Synchronized
    fun demoteFromDualSense() {
        if (!isDualSenseActive) return
        val currentPref = SessionPrefs.steamController(context)
        val restorePref = if (currentPref != SessionPrefs.CONTROLLER_DUALSENSE) {
            currentPref
        } else {
            cachedPreference ?: SessionPrefs.CONTROLLER_DECK
        }
        val padType = SessionPrefs.padType(restorePref)
        FakeInputWriter.setPadType(padType)
        SteamDeckPad.showPad(padType)
        isDualSenseActive = false
        DeckControls.externalImuActive = false
        isRawHidActive = false
        rawTwinDeviceId = -1
        routedHeadphones = null
        isEdgeConnected = false
        rawCalibration05 = null
        val dir = SessionState.fakeInputDir
        if (dir != null) {
            try {
                File(dir, "cal_05.bin").delete()
            } catch (_: Exception) {}
        }
        btConnectTimestampMs = 0L
        btOutputSeq = 0
        pendingBtLightbarReport = null
        lastBtOutputSentMs = 0L
        lastBtSentPayload = null
        pendingBtRumbleReport = null
        gameNativeTriggersActive = false
        rumbleWatchdogArmed = false
        lastRumbleActiveMs = 0L
        lastFallbackLeftMotor = 0
        lastFallbackRightMotor = 0
        motionNoiseCalibration.reset()
        hardwareMicMuted = false
        lastRawMuteButton = false
        cachedPreference = null
        lastPadState = null
        osTouch0X = 0
        osTouch0Y = 0
        osTouch0Down = false
        osTouch1X = 0
        osTouch1Y = 0
        osTouch1Down = false
        osTouchpadClickDown = false
        osMuteDown = false
        osL4Down = false
        osR4Down = false
        osL5Down = false
        osR5Down = false
        wirelessAccel.fill(0)
        wirelessGyro.fill(0)
        FakeInputWriter.writeMotion(0, ShortArray(3), ShortArray(3))
        DeckControls.releaseAll()
        SessionState.padBridge?.releaseAll()
        if (activeManager === this) liveStatus = StatusSnapshot()
        onActivationChanged?.invoke(false)
        Log.i(TAG, "DualSense deactivated, restored preference to: $restorePref (padType: $padType)")
    }

    private fun startTcpBridgeClient() {
        if (!com.droiddeck.launcher.BuildConfig.DEBUG) return
        val gen = startGeneration
        tcpThread = Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (_: Throwable) {}
            while (running && gen == startGeneration) {
                try {
                    Socket("127.0.0.1", TCP_PORT).use { socket ->
                        if (!running || gen != startGeneration) return@use
                        tcpSocket = socket
                        socket.tcpNoDelay = true
                        val out = socket.getOutputStream()
                        tcpOutputStream = out
                        try {
                            val input = DataInputStream(socket.getInputStream())
                            val buffer = ByteArray(DualSense.REPORT_SIZE_INPUT)
                            var handshakeSent = false
                            while (running && gen == startGeneration) {
                                input.readFully(buffer)
                                if (!handshakeSent) {
                                    handshakeSent = true
                                    Log.i(TAG, "Connected to DualSense host bridge on 127.0.0.1:$TCP_PORT")
                                    hasDetached = false
                                    activeSources.add("tcp")
                                    promoteToDualSense()
                                    applyUserPreferences()
                                }
                                onInputReport(buffer)
                            }
                        } finally {
                            if (gen == startGeneration) {
                                tcpOutputStream = null
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Host bridge not running or disconnected
                } finally {
                    if (gen == startGeneration) {
                        tcpSocket = null
                        tcpOutputStream = null
                        activeSources.remove("raw")
                        if (activeSources.remove("tcp") && activeSources.isEmpty()) {
                            hasDetached = true
                            demoteFromDualSense()
                        }
                    }
                }
                if (running && gen == startGeneration) {
                    try {
                        Thread.sleep(RECONNECT_DELAY_MS)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        }, "dualsense-tcp-client").apply {
            isDaemon = true
            start()
        }
    }

    fun onInputReport(data: ByteArray, offset: Int = 0) {
        if (stopped) return
        val state = DualSense.parseInputReport(
            data = data,
            offset = offset,
            calibration = usbCalibration,
            isEdge = isEdgeConnected,
            motionCalibration = if (gyroAutoCalEnabled) motionNoiseCalibration else null
        ) ?: return

        synchronized(this) {
            if (!isDualSenseActive) {
                if (hasDetached || stopped) return
                activeSources.add("raw")
                promoteToDualSense()
            }
        }

        val rawWasStale = !isRawHidDelivering()
        lastRawHidTimestamp = SystemClock.uptimeMillis()
        isRawHidActive = true
        lastBatteryPercent = state.batteryPercent
        lastBatteryCharging = state.isCharging
        lastHeadphonesPlugged = state.headphonesPlugged
        if (rawWasStale) {
            resolveRawTwin()
            routedHeadphones = null
        }
        val batteryBucket = state.batteryPercent / 20
        if (batteryBucket != lastAppliedBatteryBucket &&
            (lightbarMode == ControllerPrefs.LIGHTBAR_BATTERY || playerLedsMode == ControllerPrefs.PLAYER_LEDS_BATTERY)
        ) {
            applyUserPreferences()
        }
        updateLiveStatus()
        if (routesPadAudio && (usbConnection != null || tcpOutputStream != null) && routedHeadphones != state.headphonesPlugged &&
            lastRawHidTimestamp - lastRoutingFailureMs >= ROUTING_RETRY_MS) {
            val prevRouted = routedHeadphones
            routedHeadphones = state.headphonesPlugged
            if (sendOutputReport(DualSense.buildAudioRoutingReport(state.headphonesPlugged))) {
                Log.i(TAG, "DualSense audio routed to " + if (state.headphonesPlugged) "the headphone jack" else "the speaker")
            } else {
                routedHeadphones = prevRouted
                lastRoutingFailureMs = lastRawHidTimestamp
            }
        }

        // 1. Update PadBridge buttons, sticks, triggers
        // Digital trigger click fallback for DualSense Edge trigger stops
        val lt = if (state.leftTrigger == 0.0f && state.l2Click) 1.0f else state.leftTrigger
        val rt = if (state.rightTrigger == 0.0f && state.r2Click) 1.0f else state.rightTrigger
        val padChanged = hasPadStateChanged(lastPadState, state)
        lastPadState = state

        if (!uiOverlayActive) {
            SessionState.padBridge?.applyExternal({ padState ->
                padState.leftX = state.leftX
                padState.leftY = state.leftY
                padState.rightX = state.rightX
                padState.rightY = state.rightY
                padState.leftTrigger = lt
                padState.rightTrigger = rt
                padState.up = state.dpadUp
                padState.right = state.dpadRight
                padState.down = state.dpadDown
                padState.left = state.dpadLeft
                padState.press(PadState.X, state.square)
                padState.press(PadState.A, state.cross)
                padState.press(PadState.B, state.circle)
                padState.press(PadState.Y, state.triangle)
                padState.press(PadState.LB, state.l1)
                padState.press(PadState.RB, state.r1)
                padState.press(PadState.SELECT, state.create)
                padState.press(PadState.START, state.options)
                padState.press(PadState.L3, state.l3)
                padState.press(PadState.R3, state.r3)
                padState.press(PadState.GUIDE, state.ps)
            }, padChanged)
        }

        if (state.mute && !lastRawMuteButton) {
            hardwareMicMuted = !hardwareMicMuted
            if (micLedMode == ControllerPrefs.MIC_LED_MUTE) {
                sendOutputReport(DualSense.buildMicMuteReport(hardwareMicMuted))
            }
        }
        lastRawMuteButton = state.mute
        flushPendingBluetoothLightbar(lastRawHidTimestamp)
        flushPendingBluetoothRumble(lastRawHidTimestamp)
        checkRumbleWatchdog(lastRawHidTimestamp)

        // 2-4. Publish guest-visible touch, extras, and IMU state under the manager lock so a report
        // in flight during demoteFromDualSense() cannot overwrite the zeroed ring state.
        val x0 = if (touchpadEnabled) DualSense.touchXToPad(state.touch0X) else 0
        val y0 = if (touchpadEnabled) DualSense.touchYToPad(state.touch0Y) else 0
        val x1 = if (touchpadEnabled) DualSense.touchXToPad(state.touch1X) else 0
        val y1 = if (touchpadEnabled) DualSense.touchYToPad(state.touch1Y) else 0
        synchronized(this) {
            if (stopped || !isDualSenseActive) return
            if (!uiOverlayActive) {
                DeckControls.setDualSenseTouch(
                    x0 = x0,
                    y0 = y0,
                    down0 = touchpadEnabled && state.touch0Down,
                    x1 = x1,
                    y1 = y1,
                    down1 = touchpadEnabled && state.touch1Down,
                    click = touchpadEnabled && state.touchpadClick
                )
                DeckControls.setDualSenseExtras(
                    mute = state.mute,
                    l4 = state.l4,
                    r4 = state.r4,
                    l5 = state.l5,
                    r5 = state.r5,
                    headphonesPlugged = state.headphonesPlugged,
                    batteryStatusByte = state.batteryStatusByte
                )
                // Bytes 49..52 are a running counter on a standard DualSense; only the Edge reuses them for tuning.
                if (!(isEdgeConnected && state.isEdgeTuningActive)) {
                    val gyroZ = (-state.gyroZ.toInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    val gyro = shortArrayOf(state.gyroX, gyroZ, state.gyroY)
                    val accel = shortArrayOf(
                        (state.accelX.toInt() * 2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
                        ((-state.accelZ.toInt()) * 2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
                        (state.accelY.toInt() * 2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    )
                    FakeInputWriter.setDeckIMU(0, accel, gyro)
                }
            }
            DeckControls.externalImuActive = true
        }

        // 5. Save normalized 64B 0x01 report to ${fakeInputDir}/pad.bin (normalizing 78B 0x31 BT reports)
        if (!uiOverlayActive) {
            writePadBin(data, offset)
        }

        // 6. Notify optional UI/activity state listener under the activation lock
        synchronized(this) {
            if (!stopped && isDualSenseActive) {
                onStateUpdated?.invoke(state)
            }
        }
    }

    private fun hasPadStateChanged(prev: DualSense.State?, curr: DualSense.State): Boolean {
        if (prev == null) return true
        return prev.leftX != curr.leftX ||
            prev.leftY != curr.leftY ||
            prev.rightX != curr.rightX ||
            prev.rightY != curr.rightY ||
            prev.leftTrigger != curr.leftTrigger ||
            prev.rightTrigger != curr.rightTrigger ||
            prev.l2Click != curr.l2Click ||
            prev.r2Click != curr.r2Click ||
            prev.dpadUp != curr.dpadUp ||
            prev.dpadDown != curr.dpadDown ||
            prev.dpadLeft != curr.dpadLeft ||
            prev.dpadRight != curr.dpadRight ||
            prev.square != curr.square ||
            prev.cross != curr.cross ||
            prev.circle != curr.circle ||
            prev.triangle != curr.triangle ||
            prev.l1 != curr.l1 ||
            prev.r1 != curr.r1 ||
            prev.create != curr.create ||
            prev.options != curr.options ||
            prev.l3 != curr.l3 ||
            prev.r3 != curr.r3 ||
            prev.ps != curr.ps
    }

    @Synchronized
    private fun writePadBin(data: ByteArray, offset: Int) {
        val dir = SessionState.fakeInputDir ?: return
        if (!DualSense.convertBtInputToUsb64Into(data, offset, padBinScratch)) return
        try {
            if (dir != lastFakeInputDir || padBinRaf == null) {
                padBinRaf?.close()
                lastFakeInputDir = dir
                lastPadBinBytes = null
                hasLastWrittenPadBin = false
                padBinLengthSet = false
                val file = File(dir, "pad.bin")
                file.parentFile?.mkdirs()
                padBinRaf = RandomAccessFile(file, "rw")
                syncCalibrationToFakeInputDir(dir)
            }
            val raf = padBinRaf ?: return
            if (hasLastWrittenPadBin && padBinLengthSet &&
                DualSense.isSamePadBinState(lastWrittenPadBin, padBinScratch, isEdgeConnected)
            ) {
                return
            }
            raf.seek(0)
            raf.write(padBinScratch, 0, DualSense.REPORT_SIZE_INPUT)
            if (!padBinLengthSet) {
                raf.setLength(DualSense.REPORT_SIZE_INPUT.toLong())
                padBinLengthSet = true
            }
            System.arraycopy(padBinScratch, 0, lastWrittenPadBin, 0, DualSense.REPORT_SIZE_INPUT)
            hasLastWrittenPadBin = true
            lastPadBinBytes = lastWrittenPadBin
        } catch (e: Exception) {
            padBinLengthSet = false
        }
    }

    fun onTouchpadEvent(event: MotionEvent): Boolean {
        val device = getInputDevice(event.deviceId) ?: event.device
        if (!isDualSenseActive) {
            if (DualSense.isDualSense(device) && !hasDetached && !stopped) {
                onInputDeviceAdded(event.deviceId)
            }
            if (!isDualSenseActive) return false
        }
        if (isRawHidDelivering()) return shouldSuppressInputDevice(device)
        if (!touchpadEnabled) return true

        val action = event.actionMasked
        val pointerCount = event.pointerCount
        val liftedIdx = if (action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        val allUp = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || pointerCount <= 0

        val activeIndices = ArrayList<Int>(2)
        if (!allUp) {
            for (i in 0 until pointerCount) {
                if (i != liftedIdx) {
                    activeIndices.add(i)
                    if (activeIndices.size == 2) break
                }
            }
        }

        val rangeX = event.device?.getMotionRange(MotionEvent.AXIS_X, event.source)
        val rangeY = event.device?.getMotionRange(MotionEvent.AXIS_Y, event.source)
        val maxX = rangeX?.max?.takeIf { it > 0 } ?: (DualSense.TOUCHPAD_WIDTH - 1).toFloat()
        val maxY = rangeY?.max?.takeIf { it > 0 } ?: (DualSense.TOUCHPAD_HEIGHT - 1).toFloat()

        val down0 = activeIndices.isNotEmpty()
        val idx0 = if (down0) activeIndices[0] else 0
        val rawX0 = if (down0) ((event.getX(idx0) / maxX) * (DualSense.TOUCHPAD_WIDTH - 1)).toInt() else 0
        val rawY0 = if (down0) ((event.getY(idx0) / maxY) * (DualSense.TOUCHPAD_HEIGHT - 1)).toInt() else 0

        val down1 = activeIndices.size > 1
        val idx1 = if (down1) activeIndices[1] else 0
        val rawX1 = if (down1) ((event.getX(idx1) / maxX) * (DualSense.TOUCHPAD_WIDTH - 1)).toInt() else 0
        val rawY1 = if (down1) ((event.getY(idx1) / maxY) * (DualSense.TOUCHPAD_HEIGHT - 1)).toInt() else 0

        val x0 = DualSense.touchXToPad(rawX0)
        val y0 = DualSense.touchYToPad(rawY0)
        val x1 = DualSense.touchXToPad(rawX1)
        val y1 = DualSense.touchYToPad(rawY1)

        osTouch0X = x0
        osTouch0Y = y0
        osTouch0Down = down0
        osTouch1X = x1
        osTouch1Y = y1
        osTouch1Down = down1

        val buttonPrimary = (event.buttonState and MotionEvent.BUTTON_PRIMARY) != 0
        val click = buttonPrimary || osTouchpadClickDown
        DeckControls.setDualSenseTouch(x0, y0, down0, x1, y1, down1, click = click)
        return true
    }

    /**
     * Handles wireless DualSense / DualSense Edge extra buttons (Touchpad click, Mic Mute,
     * Edge L4/R4 function buttons and L5/R5 back paddles) delivered via Android KeyEvents.
     * Standard gamepad buttons return false so PadBridge processes them normally.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val device = getInputDevice(event.deviceId) ?: event.device
        if (!DualSense.isDualSense(device)) return false
        if (isRawHidDelivering()) return shouldSuppressInputDevice(device)
        val action = event.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false
        val down = action == KeyEvent.ACTION_DOWN
        val keyCode = event.keyCode
        val scanCode = event.scanCode

        // 1. Touchpad Click (KEYCODE_BUTTON_TOUCHPAD = 1004 or BTN_LEFT = 272 / 317 from touchpad)
        if (keyCode == KEYCODE_BUTTON_TOUCHPAD ||
            scanCode == 317 ||
            (scanCode == 272 && (event.isFromSource(InputDevice.SOURCE_TOUCHPAD) || event.isFromSource(InputDevice.SOURCE_MOUSE)))
        ) {
            if (!touchpadEnabled) return true
            osTouchpadClickDown = down
            DeckControls.setDualSenseTouch(
                osTouch0X, osTouch0Y, osTouch0Down,
                osTouch1X, osTouch1Y, osTouch1Down,
                click = down
            )
            return true
        }

        // 2. Mic Mute Button (KEYCODE_MUTE = 91, KEYCODE_VOLUME_MUTE = 164, KEY_MICMUTE = 248)
        if (keyCode == KeyEvent.KEYCODE_MUTE || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE || scanCode == 248) {
            if (down && !osMuteDown) {
                hardwareMicMuted = !hardwareMicMuted
                if (micLedMode == ControllerPrefs.MIC_LED_MUTE) {
                    sendOutputReport(DualSense.buildMicMuteReport(hardwareMicMuted))
                }
            }
            osMuteDown = down
            DeckControls.setDualSenseExtras(osMuteDown, osL4Down, osR4Down, osL5Down, osR5Down)
            return true
        }

        // 3. DualSense Edge Extra Buttons (L4/R4 Function buttons & L5/R5 Back Paddles)
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_1 -> {
                osL4Down = down
                DeckControls.setDualSenseExtras(osMuteDown, osL4Down, osR4Down, osL5Down, osR5Down)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_2 -> {
                osR4Down = down
                DeckControls.setDualSenseExtras(osMuteDown, osL4Down, osR4Down, osL5Down, osR5Down)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_3 -> {
                osL5Down = down
                DeckControls.setDualSenseExtras(osMuteDown, osL4Down, osR4Down, osL5Down, osR5Down)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_4 -> {
                osR5Down = down
                DeckControls.setDualSenseExtras(osMuteDown, osL4Down, osR4Down, osL5Down, osR5Down)
                return true
            }
        }

        return false
    }

    companion object {
        private const val TAG = "DualSenseManager"
        const val TCP_PORT = 21337
        /** Minimum interval (8 ms = 125 Hz) between coalesced Bluetooth 0x31 rumble-only output packets. */
        const val BT_RUMBLE_COALESCE_MS = 8L
        /** First byte of a variable-length bridge frame; a plain output report always starts with 0x02. */
        const val BRIDGE_FRAME_MARKER: Byte = 0xB2.toByte()
        private const val RECONNECT_DELAY_MS = 1000L
        private const val ROUTING_RETRY_MS = 1000L
        private const val HID_HOST_PROFILE = 4
        private const val SENSOR_SAMPLING_US = 4000
        const val KEYCODE_BUTTON_TOUCHPAD = 1004
        const val ACCEL_COUNTS_PER_M_S2 = 16384f / SensorManager.GRAVITY_EARTH
        const val GYRO_COUNTS_PER_RAD_S = (32768f / 2000f) * (180f / Math.PI.toFloat())
        private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

        @Volatile
        var activeManager: DualSenseManager? = null

        data class StatusSnapshot(
            val connected: Boolean = false,
            val isEdge: Boolean = false,
            val transport: String = "",
            val batteryPercent: Int = 100,
            val isCharging: Boolean = false,
            val headphonesPlugged: Boolean = false,
            val gyroCalibrated: Boolean = false,
        )

        private val liveStatusState = androidx.compose.runtime.mutableStateOf(StatusSnapshot())
        var liveStatus: StatusSnapshot
            get() = liveStatusState.value
            internal set(value) { liveStatusState.value = value }

        @JvmStatic
        fun refreshActivePreferences(context: Context, clearTriggersIfAuto: Boolean = false) {
            val mgr = activeManager ?: return
            mgr.loadUserPreferences()
            mgr.routedHeadphones = null
            if (mgr.isDualSenseActive) {
                mgr.updateLiveStatus()
                Thread({
                    mgr.applyUserPreferences(clearTriggersIfAuto)
                }, "dualsense-prefs-apply").apply {
                    isDaemon = true
                    start()
                }
            }
        }

        internal data class RawTwinCandidate(
            val id: Int,
            val vendorId: Int,
            val productId: Int,
            val bluetooth: Boolean = false,
        )

        internal fun isBluetoothInputDevice(device: InputDevice): Boolean {
            val address = runCatching {
                device.javaClass.getMethod("getBluetoothAddress").invoke(device) as? String
            }.getOrNull()
            if (!address.isNullOrBlank()) return true
            return Regex("Bluetooth Address:\\s*(?!null\\b)\\S+", RegexOption.IGNORE_CASE)
                .containsMatchIn(device.toString())
        }

        /** The single non-Bluetooth candidate that can duplicate a claimed USB raw pad, else -1. */
        internal fun pickRawTwin(candidates: List<RawTwinCandidate>, rawUsbIds: Pair<Int, Int>?): Int {
            if (rawUsbIds == null) return -1
            val matching = candidates.filter {
                !it.bluetooth && it.vendorId == rawUsbIds.first && it.productId == rawUsbIds.second
            }
            return matching.singleOrNull()?.id ?: -1
        }

        @JvmStatic
        fun shouldSuppress(device: InputDevice?): Boolean =
            activeManager?.shouldSuppressInputDevice(device) == true

        @JvmStatic
        fun effectiveSteamController(context: Context): String =
            if (activeManager?.isDualSenseActive == true) {
                SessionPrefs.CONTROLLER_DUALSENSE
            } else {
                SessionPrefs.steamController(context)
            }
    }
}
