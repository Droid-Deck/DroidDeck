package com.droiddeck.launcher.input

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
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

    @Volatile var onStateUpdated: ((DualSense.State) -> Unit)? = null
    @Volatile var onActivationChanged: ((Boolean) -> Unit)? = null

    private var tcpThread: Thread? = null
    @Volatile private var tcpSocket: Socket? = null
    @Volatile private var startGeneration: Int = 0
    @Volatile var tcpOutputStream: OutputStream? = null
    private val activeSources = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private var padBinRaf: RandomAccessFile? = null
    private var lastFakeInputDir: File? = null

    // Direct Android USB HID Reader / Writer state
    @Volatile var usbConnection: UsbDeviceConnection? = null
    @Volatile var usbInterface: UsbInterface? = null
    @Volatile var usbEndpointIn: UsbEndpoint? = null
    @Volatile var usbEndpointOut: UsbEndpoint? = null
    private var usbReaderThread: Thread? = null

    // Phase 2: Wireless (Bluetooth / InputDevice) state
    @Volatile var isEdgeConnected: Boolean = false
    @Volatile var btConnectTimestampMs: Long = 0L
    private var btOutputSeq: Int = 0
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
            usbConnection = connection
            usbInterface = hidIntf
            usbEndpointIn = epIn
            usbEndpointOut = epOut

            usbReaderThread = Thread({
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

    fun sendOutputReport(data: ByteArray): Boolean {
        if (data.size < DualSense.REPORT_SIZE_OUTPUT || data[0] != DualSense.REPORT_ID_OUTPUT) {
            return false
        }
        val tcpOut = tcpOutputStream
        if (tcpOut != null) {
            return try {
                synchronized(tcpOut) {
                    tcpOut.write(data, 0, DualSense.REPORT_SIZE_OUTPUT)
                    tcpOut.flush()
                }
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
                    conn.bulkTransfer(epOut, data, DualSense.REPORT_SIZE_OUTPUT, 1000) >= 0
                } else if (intf != null) {
                    // HID SET_REPORT: Request Type 0x21, Request 0x09, Value 0x0202 (Report Type Output | Report ID 2)
                    conn.controlTransfer(0x21, 0x09, 0x0202, intf.id, data, DualSense.REPORT_SIZE_OUTPUT, 1000) >= 0
                } else false
            } catch (e: Exception) {
                false
            }
            if (sent) return true
        }

        // Phase 2: Wireless Bluetooth 0x31 Output Report dispatch (Adaptive Triggers, LEDs, Rumble)
        val btSender = bluetoothReportSender
        if (btSender != null) {
            val btReport = buildNextBluetoothOutputReport(data)
            if (btSender(btReport)) return true
        }

        if (bluetoothHidHostProxy != null) {
            val btReport = buildNextBluetoothOutputReport(data)
            if (sendViaBluetoothHidHost(btReport)) return true
        }

        return false
    }

    private fun initBluetoothHidHost() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    if (profile == HID_HOST_PROFILE) {
                        bluetoothHidHostProxy = proxy
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
        return try {
            val devices = proxy.connectedDevices ?: return false
            val target: BluetoothDevice = devices.firstOrNull { dev ->
                val name = try { dev.name ?: "" } catch (_: Exception) { "" }
                name.contains("DualSense", ignoreCase = true) ||
                        name.contains("Wireless Controller", ignoreCase = true)
            } ?: return false

            val setReport = proxy.javaClass.getMethod(
                "setReport",
                BluetoothDevice::class.java,
                Byte::class.javaPrimitiveType,
                String::class.java
            )
            val hex = StringBuilder(btReport.size * 2)
            for (b in btReport) {
                val v = b.toInt() and 0xFF
                hex.append(HEX_CHARS[v ushr 4])
                hex.append(HEX_CHARS[v and 0x0F])
            }
            // REPORT_TYPE_OUTPUT = 2
            val ok = setReport.invoke(proxy, target, 2.toByte(), hex.toString()) as? Boolean
            ok == true
        } catch (e: Exception) {
            false
        }
    }

    fun isRawHidDelivering(): Boolean =
        isDualSenseActive && isRawHidActive && (SystemClock.uptimeMillis() - lastRawHidTimestamp < 1000L)

    fun shouldSuppressInputDevice(device: InputDevice?): Boolean {
        if (device == null) return false
        return DualSense.isDualSense(device) && isRawHidDelivering()
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
        }
    }

    fun onInputDeviceRemoved(deviceId: Int) {
        unregisterDeviceSensors(deviceId)
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
        FakeInputWriter.setPadType(FakeInputWriter.PAD_TYPE_DUALSENSE)
        SteamDeckPad.showPad(FakeInputWriter.PAD_TYPE_DUALSENSE)
        isDualSenseActive = true
        DeckControls.externalImuActive = true
        onActivationChanged?.invoke(true)
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
        isEdgeConnected = false
        btConnectTimestampMs = 0L
        btOutputSeq = 0
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
        onActivationChanged?.invoke(false)
        Log.i(TAG, "DualSense deactivated, restored preference to: $restorePref (padType: $padType)")
    }

    private fun startTcpBridgeClient() {
        if (!com.droiddeck.launcher.BuildConfig.DEBUG) return
        val gen = startGeneration
        tcpThread = Thread({
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
                                    // Send initial handshake output report (DroidDeck Cyan lightbar + Player 1 LED)
                                    sendOutputReport(
                                        DualSense.buildOutputReport(
                                            r = 0,
                                            g = 180,
                                            b = 255,
                                            playerLeds = 0x04,
                                            micLed = 1
                                        )
                                    )
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
        val state = DualSense.parseInputReport(data, offset) ?: return

        synchronized(this) {
            if (!isDualSenseActive) {
                if (hasDetached || stopped) return
                activeSources.add("raw")
                promoteToDualSense()
            }
        }

        lastRawHidTimestamp = SystemClock.uptimeMillis()
        isRawHidActive = true

        // 1. Update PadBridge buttons, sticks, triggers
        // Digital trigger click fallback for DualSense Edge trigger stops
        val lt = if (state.leftTrigger == 0.0f && state.l2Click) 1.0f else state.leftTrigger
        val rt = if (state.rightTrigger == 0.0f && state.r2Click) 1.0f else state.rightTrigger
        val padChanged = hasPadStateChanged(lastPadState, state)
        lastPadState = state

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

        // 2. Call DeckControls.setDualSenseTouch and setDualSenseExtras
        val x0 = DualSense.touchXToPad(state.touch0X)
        val y0 = DualSense.touchYToPad(state.touch0Y)
        val x1 = DualSense.touchXToPad(state.touch1X)
        val y1 = DualSense.touchYToPad(state.touch1Y)
        DeckControls.setDualSenseTouch(
            x0 = x0,
            y0 = y0,
            down0 = state.touch0Down,
            x1 = x1,
            y1 = y1,
            down1 = state.touch1Down,
            click = state.touchpadClick
        )
        DeckControls.setDualSenseExtras(
            mute = state.mute,
            l4 = state.l4,
            r4 = state.r4,
            l5 = state.l5,
            r5 = state.r5
        )

        // 3. Call FakeInputWriter.setDeckIMU(0, accel, gyro) with proper axis transformation:
        if (!state.isEdgeTuningActive) {
            val gyroZ = (-state.gyroZ.toInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            val gyro = shortArrayOf(state.gyroX, gyroZ, state.gyroY)
            val accel = shortArrayOf(
                (state.accelX.toInt() * 2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
                ((-state.accelZ.toInt()) * 2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
                (state.accelY.toInt() * 2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            )
            FakeInputWriter.setDeckIMU(0, accel, gyro)
        }

        // 4. Set DeckControls.externalImuActive = true (synchronized to prevent racing with demoteFromDualSense)
        synchronized(this) {
            if (isDualSenseActive) {
                DeckControls.externalImuActive = true
            }
        }

        // 5. Save normalized 64B 0x01 report to ${fakeInputDir}/pad.bin (normalizing 78B 0x31 BT reports)
        writePadBin(data, offset)

        // 6. Notify optional UI/activity state listener
        onStateUpdated?.invoke(state)
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
        val usb64 = DualSense.convertBtInputToUsb64(data, offset) ?: return
        try {
            if (dir != lastFakeInputDir || padBinRaf == null) {
                padBinRaf?.close()
                lastFakeInputDir = dir
                val file = File(dir, "pad.bin")
                file.parentFile?.mkdirs()
                padBinRaf = RandomAccessFile(file, "rw")
            }
            val raf = padBinRaf ?: return
            raf.seek(0)
            raf.write(usb64, 0, DualSense.REPORT_SIZE_INPUT)
            raf.setLength(DualSense.REPORT_SIZE_INPUT.toLong())
        } catch (e: Exception) {
            // ignore
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
        if (isRawHidDelivering()) return true

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
        if (isRawHidDelivering()) return true
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
        private const val RECONNECT_DELAY_MS = 1000L
        private const val HID_HOST_PROFILE = 4
        private const val SENSOR_SAMPLING_US = 4000
        const val KEYCODE_BUTTON_TOUCHPAD = 1004
        const val ACCEL_COUNTS_PER_M_S2 = 16384f / SensorManager.GRAVITY_EARTH
        const val GYRO_COUNTS_PER_RAD_S = (32768f / 2000f) * (180f / Math.PI.toFloat())
        private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

        @Volatile
        var activeManager: DualSenseManager? = null

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
