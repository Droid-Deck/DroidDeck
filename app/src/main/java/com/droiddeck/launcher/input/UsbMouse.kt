package com.droiddeck.launcher.input

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice

/**
 * Reads a USB mouse from its HID interface, detaching it from Android meanwhile. Android only grants pointer
 * capture on the focused display, so an app shown elsewhere, as in REDMAGIC's cast mode, gets a cursor that
 * stops at the screen edge.
 */
class UsbMouse(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onMove(dx: Int, dy: Int)
        fun onButton(index: Int, down: Boolean)
        fun onWheel(steps: Int)
        /** Access was granted, or the mouse was lost. */
        fun onChanged()
    }

    private val usb by lazy { context.getSystemService(Context.USB_SERVICE) as UsbManager }
    private val main = Handler(Looper.getMainLooper())
    private val permissionAction by lazy { context.packageName + ".USB_MOUSE_PERMISSION" }
    private val asked = mutableSetOf<String>()
    private var receiverRegistered = false
    private var grab: Grab? = null

    val active: Boolean get() = grab != null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            Log.i(TAG, "USB mouse access ${if (granted) "granted" else "denied"}")
            if (granted) listener.onChanged()
        }
    }

    /** Returns whether the mouse is now read here; asks for access once per plugged-in mouse. */
    fun grab(): Boolean {
        if (grab != null) return true
        val (device, mouse) = findMouse() ?: return false
        if (!usb.hasPermission(device)) {
            if (asked.add(device.deviceName)) askPermission(device)
            return false
        }
        val endpoint = (0 until mouse.endpointCount).map(mouse::getEndpoint).firstOrNull {
            it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT
        } ?: return false
        val connection = usb.openDevice(device) ?: return false
        if (!connection.claimInterface(mouse, true)) {
            connection.close()
            return false
        }
        val descriptor = ByteArray(1024)
        val length = connection.controlTransfer(0x81, GET_DESCRIPTOR, HID_REPORT_DESCRIPTOR shl 8, mouse.id,
            descriptor, descriptor.size, CONTROL_TIMEOUT_MS)
        val parsed = if (length > 0) HidMouseReport.parse(descriptor.copyOf(length)) else null
        val boot = parsed == null && connection.controlTransfer(0x21, SET_PROTOCOL, 0, mouse.id, null, 0, CONTROL_TIMEOUT_MS) >= 0
        val format = parsed ?: HidMouseReport.BOOT.takeIf { boot }
        if (format == null) {
            connection.releaseInterface(mouse)
            connection.close()
            return false
        }
        Log.i(TAG, String.format(java.util.Locale.ROOT, "reading USB mouse %04x:%04x \"%s\" directly (%s)",
            device.vendorId, device.productId, device.productName, if (boot) "boot protocol" else "report ${format.reportId}"))
        grab = Grab(connection, mouse, endpoint, format, boot).also { it.thread.start() }
        return true
    }

    /** Hands the mouse back to Android. */
    fun release() {
        val current = grab ?: return
        grab = null
        current.running = false
    }

    fun close() {
        release()
        if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
        receiverRegistered = false
    }

    private fun findMouse(): Pair<UsbDevice, UsbInterface>? {
        val androidMice = InputDevice.getDeviceIds().asList().mapNotNull { InputDevice.getDevice(it) }
            .filter { !it.isVirtual && it.supportsSource(InputDevice.SOURCE_MOUSE) }
            .map { it.vendorId to it.productId }.toSet()
        return usb.deviceList.values.filter { (it.vendorId to it.productId) in androidMice }.firstNotNullOfOrNull { device ->
            (0 until device.interfaceCount).map(device::getInterface).firstOrNull(::isBootMouse)?.let { device to it }
        }
    }

    private fun askPermission(device: UsbDevice) {
        if (!receiverRegistered) {
            val filter = IntentFilter(permissionAction)
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(receiver, filter)
            receiverRegistered = true
        }
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = PendingIntent.getBroadcast(context, 0, Intent(permissionAction).setPackage(context.packageName), flags)
        runCatching { usb.requestPermission(device, intent) }.onFailure { Log.w(TAG, "requestPermission", it) }
    }

    private inner class Grab(
        private val connection: UsbDeviceConnection,
        private val mouse: UsbInterface,
        private val endpoint: UsbEndpoint,
        private val format: HidMouseReport,
        private val boot: Boolean,
    ) {
        @Volatile var running = true
        val thread = Thread(::read, "usb-mouse")

        // The reader owns the connection: closing it while a transfer is pending would free it under the transfer.
        private fun read() {
            val report = ByteArray(maxOf(endpoint.maxPacketSize, 8))
            var buttons = 0
            var failures = 0
            try {
                while (running) {
                    val started = SystemClock.uptimeMillis()
                    val length = connection.bulkTransfer(endpoint, report, report.size, READ_TIMEOUT_MS)
                    if (length <= 0) {
                        if (SystemClock.uptimeMillis() - started < READ_TIMEOUT_MS / 2 && ++failures >= MAX_FAILURES) {
                            main.post { lost() }
                            return
                        }
                        continue
                    }
                    failures = 0
                    val state = format.decode(report, length) ?: continue
                    val changed = buttons xor state.buttons
                    buttons = state.buttons
                    main.post { deliver(state, changed) }
                }
            } finally {
                if (boot) connection.controlTransfer(0x21, SET_PROTOCOL, 1, mouse.id, null, 0, CONTROL_TIMEOUT_MS)
                connection.releaseInterface(mouse)
                connection.close()
            }
        }

        private fun deliver(state: HidMouseReport.State, changed: Int) {
            if (grab !== this) return
            if (state.dx != 0 || state.dy != 0) listener.onMove(state.dx, state.dy)
            for (i in 0 until BUTTONS) if (changed and (1 shl i) != 0) listener.onButton(i, state.buttons and (1 shl i) != 0)
            if (state.wheel != 0) listener.onWheel(state.wheel)
        }

        private fun lost() {
            if (grab !== this) return
            Log.i(TAG, "USB mouse stopped responding")
            grab = null
            listener.onChanged()
        }
    }

    companion object {
        private const val TAG = "UsbMouse"
        private const val GET_DESCRIPTOR = 0x06
        private const val SET_PROTOCOL = 0x0b
        private const val HID_REPORT_DESCRIPTOR = 0x22
        private const val SUBCLASS_BOOT = 1
        private const val PROTOCOL_MOUSE = 2
        private const val CONTROL_TIMEOUT_MS = 1000
        private const val READ_TIMEOUT_MS = 100
        private const val MAX_FAILURES = 5
        private const val BUTTONS = 5

        private fun isBootMouse(iface: UsbInterface) = iface.interfaceClass == UsbConstants.USB_CLASS_HID &&
            iface.interfaceSubclass == SUBCLASS_BOOT && iface.interfaceProtocol == PROTOCOL_MOUSE

        /**
         * A process killed while reading a mouse leaves it with no driver until it is replugged. Claiming without
         * force only succeeds when no driver has the interface, and releasing it reattaches Android's.
         */
        fun returnLeftover(context: Context) {
            Thread({
                runCatching {
                    val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
                    for (device in usb.deviceList.values) {
                        if (!usb.hasPermission(device)) continue
                        val mouse = (0 until device.interfaceCount).map(device::getInterface).firstOrNull(::isBootMouse) ?: continue
                        val connection = usb.openDevice(device) ?: continue
                        if (connection.claimInterface(mouse, false)) {
                            connection.releaseInterface(mouse)
                            Log.i(TAG, "returned USB mouse \"${device.productName}\" to Android")
                        }
                        connection.close()
                    }
                }.onFailure { Log.w(TAG, "returning a USB mouse to Android failed", it) }
            }, "usb-mouse-return").start()
        }
    }
}
