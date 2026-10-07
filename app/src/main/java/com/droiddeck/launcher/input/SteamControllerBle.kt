package com.droiddeck.launcher.input

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * The Steam Controller (2025, "Triton") over Bluetooth LE, read the way Steam Link reads it (SDL,
 * HIDDeviceBLESteamController.java and SDL_hidapi_steam_triton.c): the app connects to the
 * controller's own Valve GATT service, subscribes to its state report and keeps lizard mode off.
 * Android pairs it as a mouse and a keyboard (lizard mode), which is all a game would otherwise
 * get; read here, every control reaches the session's Steam Deck controller instead - sticks,
 * buttons and triggers through [PadBridge], the back grips and both trackpads through
 * [FakeInputWriter.writeDeckControls], gyro and accelerometer through [FakeInputWriter.writeMotion].
 * The Triton reports motion and trackpads in the Deck's own axes and units, so both pass through.
 *
 * The controller turns lizard mode back on by itself a few seconds after it last heard otherwise,
 * so the setting is sent again every [LIZARD_REFRESH_MS], and a session that ends (or an app that
 * dies) leaves it a mouse again with nothing to undo.
 */
@SuppressLint("MissingPermission") // BLUETOOTH is granted at install below targetSdk 31
class SteamControllerBle(private val context: Context, private val bridge: PadBridge) {
    private val thread = HandlerThread("steam-controller-ble").apply { start() }
    private val handler = Handler(thread.looper)
    private var gatt: BluetoothGatt? = null
    private var running = false
    private var input: UUID? = null
    private var reportId = 0
    private var lizardOffSent = false
    private var reportedNone = false
    private var discoveries = 0
    private val pads = ShortArray(4)
    private val pressure = ShortArray(2)
    private val accel = ShortArray(3)
    private val gyro = ShortArray(3)
    private var lastButtons = 0
    private val lastAxes = ShortArray(6)

    /** Looks for a paired Steam Controller and keeps a connection to it until [stop]. */
    fun start() = handler.post {
        if (running) return@post
        running = true
        Log.i(TAG, "looking for a paired Steam Controller")
        connect()
    }

    /** Lets the controller go (it returns to lizard mode by itself); this object is done after. */
    fun stop() = handler.post {
        running = false
        handler.removeCallbacksAndMessages(null)
        disconnect("session ended")
        thread.quitSafely()
    }

    private fun connect() {
        if (!running || gatt != null) return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val bonded = runCatching { adapter?.takeIf(BluetoothAdapter::isEnabled)?.bondedDevices }
            .onFailure { Log.w(TAG, "paired devices unreadable", it) }
            .getOrNull()
        val device = bonded?.firstOrNull(::isSteamController)
        if (device == null) {
            // Once per session, so a controller that is never picked up says why.
            if (!reportedNone) {
                reportedNone = true
                Log.i(TAG, "no paired Steam Controller (Bluetooth ${if (adapter?.isEnabled == true) "on" else "off"}; paired: " +
                    (bonded?.joinToString { "\"${it.name}\" type ${it.type}" } ?: "unreadable") + "); looking again every ${RETRY_MS / 1000} s")
            }
            handler.postDelayed(::connect, RETRY_MS)
            return
        }
        Log.i(TAG, "connecting to \"${device.name}\"")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
    }

    private fun disconnect(why: String) {
        val g = gatt ?: return
        gatt = null
        discoveries = 0
        input = null
        lizardOffSent = false
        connected = false
        g.disconnect()
        g.close()
        release()
        Log.i(TAG, "disconnected: $why")
        listener?.invoke()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnect("link lost (status $status)")
                if (running) handler.postDelayed(::connect, RETRY_MS)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            // The Triton needs data length extensions, which Android only enables for a large MTU
            // (517 is the value SDL found works); the subscription follows once it is set.
            if (!g.requestMtu(517)) subscribe(g)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (g === gatt) subscribe(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (g !== gatt || descriptor.characteristic.uuid != input) return
            Log.i(TAG, "subscribed to report 0x%02x (status %d)".format(reportId, status))
            connected = true
            listener?.invoke()
            refreshLizardMode()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (g === gatt && characteristic.uuid == REPORT && !lizardOffSent) {
                lizardOffSent = true
                Log.i(TAG, "lizard mode off (status $status)")
            }
        }

        // Android 13 hands the value over with the call; older ones only through the characteristic.
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (g === gatt && characteristic.uuid == input) onState(value)
        }

        @Deprecated("Below Android 13 only", ReplaceWith(""))
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (g === gatt && characteristic.uuid == input) onState(characteristic.value ?: return)
        }
    }

    private fun subscribe(g: BluetoothGatt) {
        val service = g.getService(SERVICE)
        if (service == null) {
            Log.w(TAG, "no Valve service on \"${g.device.name}\" (attempt ${discoveries + 1}); services: " +
                g.services.joinToString { it.uuid.toString().take(8) })
            // Android may answer from the service list its HID host cached, which can lack the
            // vendor service: ask the controller again before giving up on it.
            if (++discoveries < 3) {
                refreshCache(g)
                handler.postDelayed({ if (g === gatt) g.discoverServices() }, 500)
            } else {
                disconnect("no Valve service")
                if (running) handler.postDelayed(::connect, RETRY_MS)
            }
            return
        }
        // The timestamped report (0x47) where the firmware has it, else the plain BLE one (0x45);
        // both lay out the same fields, the first with a trackpad timestamp ahead of the pads.
        val chr = service.getCharacteristic(INPUT_0X47)?.also { reportId = 0x47 }
            ?: service.getCharacteristic(INPUT_0X45)?.also { reportId = 0x45 }
        if (chr == null) {
            disconnect("no state report characteristic")
            return
        }
        input = chr.uuid
        g.setCharacteristicNotification(chr, true)
        val cccd = chr.getDescriptor(CCCD) ?: return disconnect("state report cannot notify")
        @Suppress("DEPRECATION")
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        g.writeDescriptor(cccd)
    }

    /** BluetoothGatt.refresh(): hidden, but what every BLE app uses to drop a stale service cache. */
    private fun refreshCache(g: BluetoothGatt) {
        runCatching { g.javaClass.getMethod("refresh").invoke(g) }
            .onFailure { Log.w(TAG, "could not refresh the service cache", it) }
    }

    /** SDL's DisableSteamTritonLizardMode, on the BLE report characteristic: ID_SET_SETTINGS_VALUES
     *  (0x87), one 3-byte setting, SETTING_LIZARD_MODE (9) = LIZARD_MODE_OFF (0). */
    private fun refreshLizardMode() {
        val g = gatt ?: return
        if (!connected) return
        g.getService(SERVICE)?.getCharacteristic(REPORT)?.let { chr ->
            val msg = ByteArray(FEATURE_BYTES)
            msg[0] = 0x87.toByte(); msg[1] = 3; msg[2] = 9
            @Suppress("DEPRECATION")
            chr.value = msg
            @Suppress("DEPRECATION")
            g.writeCharacteristic(chr)
        }
        handler.postDelayed(::refreshLizardMode, LIZARD_REFRESH_MS)
    }

    /** One TritonMTUNoQuat_t (0x45) or TritonMTUNoQuat32TS_t (0x47), without the report id byte. */
    private fun onState(data: ByteArray) {
        if (data.size < STATE_BYTES) return
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val buttons = b.getInt(1)
        val pad = if (reportId == 0x47) 19 else 17
        // Triggers and sticks at 5..16. A held stick wanders by a few counts, which is not a player.
        var changed = buttons != lastButtons
        lastButtons = buttons
        for (i in 0 until 6) {
            val v = b.getShort(5 + i * 2)
            if (Math.abs(v - lastAxes[i]) > AXIS_NOISE) { changed = true; lastAxes[i] = v }
        }
        bridge.applyExternal({ s ->
            s.leftTrigger = b.getShort(5) / 32767f
            s.rightTrigger = b.getShort(7) / 32767f
            s.leftX = b.getShort(9) / 32767f
            s.leftY = -b.getShort(11) / 32767f
            s.rightX = b.getShort(13) / 32767f
            s.rightY = -b.getShort(15) / 32767f
            s.up = buttons and DPAD_UP != 0
            s.down = buttons and DPAD_DOWN != 0
            s.left = buttons and DPAD_LEFT != 0
            s.right = buttons and DPAD_RIGHT != 0
            for ((bit, button) in BUTTONS) s.press(button, buttons and bit != 0)
        }, changed)
        var controls = 0
        for ((bit, control) in DECK_CONTROLS) if (buttons and bit != 0) controls = controls or control
        for (i in 0 until 2) {
            val at = pad + i * 6
            pads[i * 2] = b.getShort(at)
            pads[i * 2 + 1] = b.getShort(at + 2)
            pressure[i] = minOf(b.getShort(at + 4).toInt() and 0xFFFF, Short.MAX_VALUE.toInt()).toShort()
        }
        FakeInputWriter.writeDeckControls(SLOT, controls, pads, pressure)
        for (i in 0 until 3) {
            accel[i] = b.getShort(33 + i * 2)
            gyro[i] = b.getShort(39 + i * 2)
        }
        FakeInputWriter.writeMotion(SLOT, accel, gyro)
    }

    /** Everything let go, so a link lost mid-press leaves nothing held in the game. */
    private fun release() {
        bridge.applyExternal({ it.clear() }, false)
        lastButtons = 0
        lastAxes.fill(0)
        pads.fill(0); pressure.fill(0); accel.fill(0); gyro.fill(0)
        FakeInputWriter.writeDeckControls(SLOT, 0, pads, pressure)
        FakeInputWriter.writeMotion(SLOT, accel, gyro)
    }

    companion object {
        private const val TAG = "SteamControllerBle"
        /** The Deck controller's ring slot (PadBridge, DeckControls, PadMotion). */
        private const val SLOT = 0
        private const val RETRY_MS = 5_000L
        private const val LIZARD_REFRESH_MS = 3_000L
        /** SDL's 64-byte feature report less the report id and the last byte, as it goes over the air. */
        private const val FEATURE_BYTES = 62
        /** Through the last gyro axis of either state report. */
        private const val STATE_BYTES = 45
        private const val AXIS_NOISE = 1024

        private val SERVICE = UUID.fromString("100F6C32-1735-4313-B402-38567131E5F3")
        private val INPUT_0X45 = UUID.fromString("100F6C7A-1735-4313-B402-38567131E5F3")
        private val INPUT_0X47 = UUID.fromString("100F6C7C-1735-4313-B402-38567131E5F3")
        private val REPORT = UUID.fromString("100F6C34-1735-4313-B402-38567131E5F3")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // TritonButtons (SDL_hidapi_steam_triton.c).
        private const val A = 0x00000001; private const val B = 0x00000002
        private const val X = 0x00000004; private const val Y = 0x00000008
        private const val QAM = 0x00000010; private const val R3 = 0x00000020
        private const val VIEW = 0x00000040; private const val R4 = 0x00000080
        private const val R5 = 0x00000100; private const val RB = 0x00000200
        private const val DPAD_DOWN = 0x00000400; private const val DPAD_RIGHT = 0x00000800
        private const val DPAD_LEFT = 0x00001000; private const val DPAD_UP = 0x00002000
        private const val MENU = 0x00004000; private const val L3 = 0x00008000
        private const val STEAM = 0x00010000; private const val L4 = 0x00020000
        private const val L5 = 0x00040000; private const val LB = 0x00080000
        private const val RIGHT_PAD_TOUCH = 0x00200000; private const val RIGHT_PAD_CLICK = 0x00400000
        private const val LEFT_PAD_TOUCH = 0x02000000; private const val LEFT_PAD_CLICK = 0x04000000

        /** As SDL maps them: the left of the two centre buttons (MENU) is Back, the right Start. */
        private val BUTTONS = listOf(
            A to PadState.A, B to PadState.B, X to PadState.X, Y to PadState.Y,
            LB to PadState.LB, RB to PadState.RB, MENU to PadState.SELECT, VIEW to PadState.START,
            L3 to PadState.L3, R3 to PadState.R3, STEAM to PadState.GUIDE, QAM to PadState.QAM,
        )
        /** Grips and trackpads, as DeckControls' bits (DECK_EXTRA_* in fakeinput_steam.cpp). */
        private val DECK_CONTROLS = listOf(
            L4 to DeckControls.L4, R4 to DeckControls.R4, L5 to DeckControls.L5, R5 to DeckControls.R5,
            LEFT_PAD_TOUCH to 16, RIGHT_PAD_TOUCH to 32, LEFT_PAD_CLICK to 64, RIGHT_PAD_CLICK to 128,
        )

        /** SDL's test (HIDDeviceManager.isSteamController): an LE device the Triton names itself as. */
        private fun isSteamController(device: BluetoothDevice): Boolean =
            device.type and BluetoothDevice.DEVICE_TYPE_LE != 0 &&
                (device.name ?: "").let { it.startsWith("Steam Ctrl") || it.startsWith("SteamController") }

        /** A Steam Controller is connected and feeding the pad; the phone's own gyro then stays off. */
        @Volatile @JvmStatic
        var connected = false
            private set

        /** Told (on the controller's thread) when [connected] changes. */
        @Volatile @JvmStatic
        var listener: (() -> Unit)? = null
    }
}
