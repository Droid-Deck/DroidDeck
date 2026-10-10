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
import java.util.ArrayDeque
import java.util.UUID

/**
 * Reads the Steam Controller (2025, Triton) through its Valve BLE service. State reports remain
 * native Triton packets and are published to the session's separate 28DE:1303 HID device. Steam's
 * output reports travel back to their matching BLE characteristics, preserving grip capacitance,
 * trackpads, sensors, and controller haptics without folding the controller into the Deck target.
 *
 * Lizard mode is refreshed while connected; when a session ends, the controller restores it itself.
 */
@SuppressLint("MissingPermission") // BLUETOOTH is granted at install below targetSdk 31
class SteamControllerBle(private val context: Context) {
    private val thread = HandlerThread("steam-controller-ble").apply { start() }
    private val handler = Handler(thread.looper)
    private val outputReports = mutableMapOf<Int, UUID>()
    /** GATT allows one operation at a time: writes carry a value, a feature read none. */
    private val queuedWrites = ArrayDeque<Pair<UUID, ByteArray?>>()
    private var gatt: BluetoothGatt? = null
    private var running = false
    private var input: UUID? = null
    private var reportId = 0
    private var lizardOffSent = false
    private var reportedNone = false
    private var discoveries = 0
    private var outputSequence = 0L
    private var outputWritePending = false
    /** The Steam client's feature read being answered, so the poll queues each one once. */
    private var featureRead = 0L

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
        outputWritePending = false
        featureRead = 0L
        queuedWrites.clear()
        outputReports.clear()
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
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect("service discovery failed (status $status)")
                if (running) handler.postDelayed(::connect, RETRY_MS)
                return
            }
            // The Triton needs data length extensions, which Android enables for a large MTU.
            if (!g.requestMtu(517)) subscribe(g)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (g === gatt) subscribe(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (g !== gatt || descriptor.characteristic.uuid != input) return
            Log.i(TAG, "subscribed to report 0x%02x (status %d)".format(reportId, status))
            connected = status == BluetoothGatt.GATT_SUCCESS
            listener?.invoke()
            if (connected) {
                refreshLizardMode()
                pollOutput()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (g !== gatt) return
            if (characteristic.uuid == REPORT && !lizardOffSent) {
                lizardOffSent = true
                Log.i(TAG, "lizard mode off (status $status)")
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "output report write failed for ${characteristic.uuid} (status $status)")
            }
            outputWritePending = false
            writeNextOutput()
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (g === gatt) onFeatureRead(value.takeIf { status == BluetoothGatt.GATT_SUCCESS })
        }

        @Deprecated("Below Android 13 only", ReplaceWith(""))
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (g === gatt) onFeatureRead(characteristic.value.takeIf { status == BluetoothGatt.GATT_SUCCESS })
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
        val chr = service.getCharacteristic(INPUT_0X47)?.also { reportId = 0x47 }
            ?: service.getCharacteristic(INPUT_0X45)?.also { reportId = 0x45 }
        if (chr == null) {
            disconnect("no state report characteristic")
            return
        }
        outputReports.clear()
        service.characteristics.forEach { characteristic ->
            val uuid = characteristic.uuid.toString().lowercase()
            if (uuid.startsWith(OUTPUT_UUID_PREFIX)) {
                val characteristicId = uuid.substring(6, 8).toIntOrNull(16) ?: return@forEach
                val outputId = characteristicId - OUTPUT_UUID_OFFSET
                if (outputId in 0x80..0x85) outputReports[outputId] = characteristic.uuid
            }
        }
        Log.i(TAG, "found Triton haptic reports: ${outputReports.keys.sorted().joinToString { "0x%02x".format(it) }}")
        input = chr.uuid
        g.setCharacteristicNotification(chr, true)
        val cccd = chr.getDescriptor(CCCD) ?: return disconnect("state report cannot notify")
        @Suppress("DEPRECATION")
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        g.writeDescriptor(cccd)
    }

    /** BluetoothGatt.refresh(): hidden, but used to drop stale service caches. */
    private fun refreshCache(g: BluetoothGatt) {
        runCatching { g.javaClass.getMethod("refresh").invoke(g) }
            .onFailure { Log.w(TAG, "could not refresh the service cache", it) }
    }

    /** SDL's DisableSteamTritonLizardMode: ID_SET_SETTINGS_VALUES (0x87), setting 9 = off. */
    private fun refreshLizardMode() {
        if (!connected) return
        val msg = ByteArray(FEATURE_BYTES)
        msg[0] = 0x87.toByte(); msg[1] = 3; msg[2] = 9
        enqueueOutput(REPORT, msg)
        handler.postDelayed(::refreshLizardMode, LIZARD_REFRESH_MS)
    }

    /** Preserve the full 0x45 or 0x47 packet so Triton-only bits reach Steam's native driver. */
    private fun onState(data: ByteArray) {
        if (data.size < STATE_BYTES) return
        FakeInputWriter.writeTritonState(SLOT, reportId, data)
    }

    /** A neutral report prevents a link lost mid-press from leaving controls held. */
    private fun release() {
        FakeInputWriter.writeTritonState(SLOT, if (reportId == 0x47) 0x47 else 0x45, ByteArray(STATE_BYTES))
    }

    /** Drain Steam HID output reports and write each to Triton's matching BLE characteristic. */
    private fun pollOutput() {
        if (!running || !connected || gatt == null) return
        FakeInputWriter.readTritonOutput(SLOT, outputSequence)?.let { output ->
            outputSequence = ByteBuffer.wrap(output, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
            sendOutputReport(output[8] != 0.toByte(), output.copyOfRange(9, output.size))
        }
        val request = FakeInputWriter.readTritonFeatureRequest(SLOT)
        if (request != 0L && request != featureRead) {
            featureRead = request
            // Behind any queued write, so a read answers the feature report written before it.
            queuedWrites.addLast(REPORT to null)
            writeNextOutput()
        }
        handler.postDelayed(::pollOutput, OUTPUT_POLL_MS)
    }

    /** The controller's answer to a feature read (its info, for one), or null when the read failed. */
    private fun onFeatureRead(value: ByteArray?) {
        outputWritePending = false
        if (featureRead != 0L) {
            if (value == null) Log.w(TAG, "feature read failed")
            FakeInputWriter.writeTritonFeatureReply(SLOT, featureRead, value)
        }
        writeNextOutput()
    }

    private fun sendOutputReport(feature: Boolean, report: ByteArray) {
        if (report.size < 2) return
        val characteristic = if (feature) {
            REPORT
        } else {
            val id = report[0].toInt() and 0xff
            outputReports[id] ?: run {
                Log.w(TAG, "no BLE output characteristic for report 0x%02x".format(id))
                return
            }
        }
        // SDL's BLE driver removes the HID report ID and trailing padding byte before transmission.
        enqueueOutput(characteristic, report.copyOfRange(1, report.size - 1))
    }

    private fun enqueueOutput(characteristic: UUID, value: ByteArray?) {
        queuedWrites.addLast(characteristic to value)
        writeNextOutput()
    }

    private fun writeNextOutput() {
        if (outputWritePending) return
        val g = gatt ?: return
        val (uuid, value) = queuedWrites.pollFirst() ?: return
        val characteristic = g.getService(SERVICE)?.getCharacteristic(uuid)
        if (characteristic == null) {
            Log.w(TAG, "BLE output characteristic $uuid disappeared")
            return
        }
        if (value == null) {
            if (g.readCharacteristic(characteristic)) {
                outputWritePending = true
            } else {
                queuedWrites.addFirst(uuid to null)
                handler.postDelayed(::writeNextOutput, OUTPUT_RETRY_MS)
            }
            return
        }
        @Suppress("DEPRECATION")
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        characteristic.value = value
        @Suppress("DEPRECATION")
        if (!g.writeCharacteristic(characteristic)) {
            queuedWrites.addFirst(uuid to value)
            handler.postDelayed(::writeNextOutput, OUTPUT_RETRY_MS)
        } else {
            outputWritePending = true
        }
    }

    companion object {
        private const val TAG = "SteamControllerBle"
        /** The paired controller's native Triton reports live in slot 0's extended ring. */
        private const val SLOT = 0
        private const val RETRY_MS = 5_000L
        private const val LIZARD_REFRESH_MS = 3_000L
        private const val OUTPUT_POLL_MS = 4L
        private const val OUTPUT_RETRY_MS = 10L
        /** SDL's 64-byte feature report less the report ID and the trailing byte. */
        private const val FEATURE_BYTES = 62
        private const val STATE_BYTES = 45
        private const val OUTPUT_UUID_PREFIX = "100f6c"
        private const val OUTPUT_UUID_OFFSET = 0x35

        private val SERVICE = UUID.fromString("100F6C32-1735-4313-B402-38567131E5F3")
        private val INPUT_0X45 = UUID.fromString("100F6C7A-1735-4313-B402-38567131E5F3")
        private val INPUT_0X47 = UUID.fromString("100F6C7C-1735-4313-B402-38567131E5F3")
        private val REPORT = UUID.fromString("100F6C34-1735-4313-B402-38567131E5F3")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private fun isSteamController(device: BluetoothDevice): Boolean =
            device.type and BluetoothDevice.DEVICE_TYPE_LE != 0 &&
                (device.name ?: "").let { it.startsWith("Steam Ctrl") || it.startsWith("SteamController") }

        /** Whether a session should expose a Triton HID node for a paired controller. */
        @JvmStatic
        fun hasPairedController(context: Context): Boolean {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return false
            return runCatching { adapter.isEnabled && adapter.bondedDevices.any(::isSteamController) }
                .onFailure { Log.w(TAG, "paired devices unreadable", it) }
                .getOrDefault(false)
        }

        /** A Steam Controller is connected and feeding its native HID reports. */
        @Volatile @JvmStatic
        var connected = false
            private set

        /** Notifies the activity when [connected] changes. */
        @Volatile @JvmStatic
        var listener: (() -> Unit)? = null
    }
}
