package com.droiddeck.launcher.input

import android.content.Context
import android.hardware.input.InputManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
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

class DualSenseManager(private val context: Context) {
    @Volatile var isDualSenseActive: Boolean = false
        private set

    @Volatile var cachedPreference: String? = null
        private set

    @Volatile private var running = false
    @Volatile private var stopped = false
    @Volatile private var hasDetached = false

    @Volatile var isRawHidActive: Boolean = false
        private set
    @Volatile private var lastRawHidTimestamp = 0L

    private var tcpThread: Thread? = null
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

    fun start() {
        if (running) return
        running = true
        stopped = false
        hasDetached = false
        activeManager = this

        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        try {
            inputManager?.registerInputDeviceListener(inputDeviceListener, null)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register input device listener: $e")
        }

        checkConnectedDevices()
        startTcpBridgeClient()
    }

    @Synchronized
    fun stop() {
        running = false
        stopped = true
        hasDetached = true
        if (activeManager === this) {
            activeManager = null
        }

        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        try {
            inputManager?.unregisterInputDeviceListener(inputDeviceListener)
        } catch (e: Exception) {
            // ignore
        }

        tcpThread?.interrupt()
        tcpThread = null
        tcpOutputStream = null

        closeUsbDevice()

        activeSources.clear()
        demoteFromDualSense()

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
        if (!DualSense.isDualSense(device)) return
        hasDetached = false
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
                while (running && usbConnection != null) {
                    val read = connection.bulkTransfer(epIn, buffer, buffer.size, 1000)
                    if (read == DualSense.REPORT_SIZE_INPUT) {
                        onInputReport(buffer, 0)
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
        val conn = usbConnection ?: return false
        val epOut = usbEndpointOut
        val intf = usbInterface
        return try {
            if (epOut != null) {
                conn.bulkTransfer(epOut, data, DualSense.REPORT_SIZE_OUTPUT, 1000) >= 0
            } else if (intf != null) {
                // HID SET_REPORT: Request Type 0x21, Request 0x09, Value 0x0202 (Report Type Output | Report ID 2)
                conn.controlTransfer(0x21, 0x09, 0x0202, intf.id, data, DualSense.REPORT_SIZE_OUTPUT, 1000) >= 0
            } else false
        } catch (e: Exception) {
            false
        }
    }

    fun isRawHidDelivering(): Boolean =
        isDualSenseActive && (SystemClock.uptimeMillis() - lastRawHidTimestamp < 1000L)

    fun shouldSuppressInputDevice(device: InputDevice?): Boolean {
        if (device == null) return false
        return DualSense.isDualSense(device) && isRawHidDelivering()
    }

    fun onInputDeviceAdded(deviceId: Int) {
        val dev = getInputDevice(deviceId)
        if (dev != null && DualSense.isDualSense(dev)) {
            hasDetached = false
            val key = "input:$deviceId"
            activeSources.add(key)
            promoteToDualSense()
        }
    }

    fun onInputDeviceRemoved(deviceId: Int) {
        val key = "input:$deviceId"
        if (activeSources.remove(key) && activeSources.isEmpty()) {
            hasDetached = true
            demoteFromDualSense()
        }
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
        if (isDualSenseActive) return
        val prev = SessionPrefs.steamController(context)
        cachedPreference = prev
        FakeInputWriter.setPadType(FakeInputWriter.PAD_TYPE_DUALSENSE)
        SteamDeckPad.showPad(FakeInputWriter.PAD_TYPE_DUALSENSE)
        isDualSenseActive = true
        DeckControls.externalImuActive = true
        Log.i(TAG, "DualSense activated as primary controller (cached preference: $prev)")
    }

    @Synchronized
    fun demoteFromDualSense() {
        if (!isDualSenseActive) return
        val restorePref = cachedPreference ?: SessionPrefs.CONTROLLER_DECK
        val padType = SessionPrefs.padType(restorePref)
        FakeInputWriter.setPadType(padType)
        SteamDeckPad.showPad(padType)
        isDualSenseActive = false
        DeckControls.externalImuActive = false
        isRawHidActive = false
        cachedPreference = null
        DeckControls.releaseAll()
        SessionState.padBridge?.releaseAll()
        Log.i(TAG, "DualSense deactivated, restored preference to: $restorePref (padType: $padType)")
    }

    private fun startTcpBridgeClient() {
        tcpThread = Thread({
            while (running) {
                try {
                    Socket("127.0.0.1", TCP_PORT).use { socket ->
                        socket.tcpNoDelay = true
                        val out = socket.getOutputStream()
                        tcpOutputStream = out
                        try {
                            Log.i(TAG, "Connected to DualSense host bridge on 127.0.0.1:$TCP_PORT")
                            hasDetached = false
                            activeSources.add("tcp")
                            promoteToDualSense()

                            val input = DataInputStream(socket.getInputStream())
                            val buffer = ByteArray(DualSense.REPORT_SIZE_INPUT)
                            while (running) {
                                input.readFully(buffer)
                                onInputReport(buffer)
                            }
                        } finally {
                            tcpOutputStream = null
                        }
                    }
                } catch (e: Exception) {
                    // Host bridge not running or disconnected
                } finally {
                    tcpOutputStream = null
                    activeSources.remove("raw")
                    if (activeSources.remove("tcp") && activeSources.isEmpty()) {
                        hasDetached = true
                        demoteFromDualSense()
                    }
                }
                if (running) {
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
        }, true)

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

        // 5. Save 64B raw report to ${fakeInputDir}/pad.bin
        writePadBin(data, offset)
    }

    @Synchronized
    private fun writePadBin(data: ByteArray, offset: Int) {
        val dir = SessionState.fakeInputDir ?: return
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
            raf.write(data, offset, DualSense.REPORT_SIZE_INPUT)
            raf.setLength(DualSense.REPORT_SIZE_INPUT.toLong())
        } catch (e: Exception) {
            // ignore
        }
    }

    fun onTouchpadEvent(event: MotionEvent): Boolean {
        if (!isDualSenseActive) return false
        val down0 = event.actionMasked != MotionEvent.ACTION_UP && event.pointerCount > 0
        val rangeX = event.device?.getMotionRange(MotionEvent.AXIS_X, event.source)
        val rangeY = event.device?.getMotionRange(MotionEvent.AXIS_Y, event.source)
        val maxX = rangeX?.max?.takeIf { it > 0 } ?: (DualSense.TOUCHPAD_WIDTH - 1).toFloat()
        val maxY = rangeY?.max?.takeIf { it > 0 } ?: (DualSense.TOUCHPAD_HEIGHT - 1).toFloat()

        val rawX0 = if (down0) ((event.getX(0) / maxX) * (DualSense.TOUCHPAD_WIDTH - 1)).toInt() else 0
        val rawY0 = if (down0) ((event.getY(0) / maxY) * (DualSense.TOUCHPAD_HEIGHT - 1)).toInt() else 0
        val down1 = down0 && event.pointerCount > 1
        val rawX1 = if (down1) ((event.getX(1) / maxX) * (DualSense.TOUCHPAD_WIDTH - 1)).toInt() else 0
        val rawY1 = if (down1) ((event.getY(1) / maxY) * (DualSense.TOUCHPAD_HEIGHT - 1)).toInt() else 0

        val x0 = DualSense.touchXToPad(rawX0)
        val y0 = DualSense.touchYToPad(rawY0)
        val x1 = DualSense.touchXToPad(rawX1)
        val y1 = DualSense.touchYToPad(rawY1)

        val click = (event.buttonState and MotionEvent.BUTTON_PRIMARY) != 0
        DeckControls.setDualSenseTouch(x0, y0, down0, x1, y1, down1, click = click)
        return true
    }

    companion object {
        private const val TAG = "DualSenseManager"
        const val TCP_PORT = 21337
        private const val RECONNECT_DELAY_MS = 1000L

        @Volatile
        var activeManager: DualSenseManager? = null

        @JvmStatic
        fun shouldSuppress(device: InputDevice?): Boolean =
            activeManager?.shouldSuppressInputDevice(device) == true
    }
}
