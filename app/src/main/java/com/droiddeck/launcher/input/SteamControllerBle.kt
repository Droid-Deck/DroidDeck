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
import android.os.SystemClock
import android.util.Log
import com.droiddeck.launcher.input.steamcontroller.Sc2015PacketReassembler
import com.droiddeck.launcher.input.steamcontroller.Sc2015ReportParser
import com.droiddeck.launcher.input.steamcontroller.Sc2015State
import com.droiddeck.launcher.input.steamcontroller.SteamControllerDebug
import com.droiddeck.launcher.input.steamcontroller.SteamControllerModel
import com.droiddeck.launcher.input.steamcontroller.SteamControllerUuids
import com.droiddeck.launcher.session.SessionState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.UUID

@SuppressLint("MissingPermission")
class SteamControllerBle(private val context: Context, router: InputRouter? = null) {
    private val inputLock = router ?: Any()
    private var routedInput = router?.let(::TritonInputRouter)
    @Volatile private var stopped = false
    private var started = false
    private val thread = HandlerThread("steam-controller-ble").apply { start() }
    private val handler = Handler(thread.looper)
    private val outputReports = mutableMapOf<Int, UUID>()
    private val queuedWrites = ArrayDeque<Pair<UUID, ByteArray?>>()
    private var gatt: BluetoothGatt? = null
    private var running = false
    private var input: UUID? = null
    private var reportId = 0
    private var model = SteamControllerModel.TRITON_2025
    private val reassembler2015 = Sc2015PacketReassembler()
    private val state2015 = Sc2015State()
    private val tritonBuffer2015 = ByteArray(STATE_BYTES)
    private var packetCounter2015 = 0
    private var connectTimeNs = 0L
    private var pendingFeatureCmd2015 = 0
    private var lastImuModeCmd2015: ByteArray? = null
    private var lastHapticWriteMs = 0L
    private var lizardOffSent = false
    private var reportedNone = false
    private var discoveries = 0
    private var outputSequence = 0L
    private var outputWritePending = false
    private var featureRead = 0L

    fun start(): Unit = synchronized(inputLock) {
        if (stopped || started) return@synchronized
        started = true
        synchronized(slotLock) {
            slotOwner = this
            connected = false
            activeModel = null
            FakeInputWriter.writeTritonState(SLOT, 0x45, ByteArray(STATE_BYTES))
            FakeInputWriter.setTritonConnected(SLOT, false)
        }
        handler.post {
            if (stopped || slotOwner !== this || running) return@post
            running = true
            Log.i(TAG, "looking for a paired Steam Controller")
            connect()
        }
    }

    private inline fun withSlot(block: () -> Unit) = synchronized(inputLock) {
        synchronized(slotLock) {
            if (!stopped && slotOwner === this) block()
        }
    }

    fun stop() {
        synchronized(inputLock) {
            if (stopped) return
            stopped = true
            routedInput?.close()
            routedInput = null
            synchronized(slotLock) {
                if (slotOwner === this) {
                    FakeInputWriter.writeTritonState(SLOT, if (reportId == 0x47) 0x47 else 0x45, ByteArray(STATE_BYTES))
                    connected = false
                    activeModel = null
                    FakeInputWriter.setTritonConnected(SLOT, false)
                    slotOwner = null
                }
            }
        }
        handler.post {
            running = false
            handler.removeCallbacksAndMessages(null)
            disconnect("session ended")
            thread.quitSafely()
        }
    }

    private fun connect() {
        if (stopped || slotOwner !== this || !running || gatt != null) return
        if (SessionState.suspended) {
            handler.postDelayed(::connect, RETRY_MS)
            return
        }
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
        reportedNone = false
        Log.i(TAG, "connecting to \"${device.name}\" (${device.address})")
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
        pendingFeatureCmd2015 = 0
        lastImuModeCmd2015 = null
        reassembler2015.reset()
        state2015.reset()
        queuedWrites.clear()
        outputReports.clear()
        synchronized(inputLock) {
            if (!stopped) {
                release()
                withSlot {
                    connected = false
                    activeModel = null
                    FakeInputWriter.setTritonConnected(SLOT, false)
                }
            }
        }
        g.disconnect()
        g.close()
        Log.i(TAG, "disconnected: $why")
        listener?.invoke()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return
            SteamControllerDebug.log("GATT connection state change: status=$status newState=$newState")
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
            SteamControllerDebug.log("GATT services discovered, requesting MTU 517")
            if (!g.requestMtu(517)) subscribe(g)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            SteamControllerDebug.log("GATT MTU changed: mtu=$mtu status=$status")
            if (g === gatt) subscribe(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            withSlot {
                if (g !== gatt || descriptor.characteristic.uuid != input) return
                Log.i(TAG, "subscribed to $model report 0x%02x (status %d)".format(reportId, status))
                connected = status == BluetoothGatt.GATT_SUCCESS
                activeModel = if (connected) model else null
                FakeInputWriter.setTritonConnected(SLOT, connected)
            }
            listener?.invoke()
            if (connected) {
                if (model == SteamControllerModel.ORIGINAL_2015) {
                    runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                }
                refreshLizardMode()
                pollOutput()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (g !== gatt) return
            if (characteristic.uuid == REPORT && !lizardOffSent) {
                lizardOffSent = true
                Log.i(TAG, "lizard mode off for $model (status $status)")
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

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (g === gatt && characteristic.uuid == input) {
                if (model == SteamControllerModel.ORIGINAL_2015) onNotification2015(value) else onState(value)
            }
        }

        @Deprecated("Below Android 13 only", ReplaceWith(""))
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (g === gatt && characteristic.uuid == input) {
                val value = characteristic.value ?: return
                if (model == SteamControllerModel.ORIGINAL_2015) onNotification2015(value) else onState(value)
            }
        }
    }

    private fun subscribe(g: BluetoothGatt) {
        if (stopped || slotOwner !== this) return
        val service = g.getService(SERVICE)
        if (service == null) {
            Log.w(TAG, "no Valve service on \"${g.device.name}\" (attempt ${discoveries + 1}); services: " +
                g.services.joinToString { it.uuid.toString().take(8) })
            if (++discoveries < 3) {
                refreshCache(g)
                handler.postDelayed({ if (g === gatt) g.discoverServices() }, 500)
            } else {
                disconnect("no Valve service")
                if (running) handler.postDelayed(::connect, RETRY_MS)
            }
            return
        }
        SteamControllerDebug.log("Valve service characteristics: " +
            service.characteristics.joinToString { "${it.uuid.toString().take(8)}(props=0x${it.properties.toString(16)})" })

        val chr = service.getCharacteristic(INPUT_0X47)?.also {
            reportId = 0x47
            model = SteamControllerModel.TRITON_2025
        } ?: service.getCharacteristic(INPUT_0X45)?.also {
            reportId = 0x45
            model = SteamControllerModel.TRITON_2025
        } ?: service.getCharacteristic(INPUT_2015)?.also {
            reportId = 0x45
            model = SteamControllerModel.ORIGINAL_2015
        }
        if (chr == null) {
            disconnect("no state report characteristic")
            return
        }
        outputReports.clear()
        if (model == SteamControllerModel.TRITON_2025) {
            service.characteristics.forEach { characteristic ->
                val uuid = characteristic.uuid.toString().lowercase()
                if (uuid.startsWith(OUTPUT_UUID_PREFIX)) {
                    val characteristicId = uuid.substring(6, 8).toIntOrNull(16) ?: return@forEach
                    val outputId = characteristicId - OUTPUT_UUID_OFFSET
                    if (outputId in 0x80..0x85) outputReports[outputId] = characteristic.uuid
                }
            }
            Log.i(TAG, "found Triton haptic reports: ${outputReports.keys.sorted().joinToString { "0x%02x".format(it) }}")
        } else {
            reassembler2015.reset()
            state2015.reset()
            packetCounter2015 = 0
            connectTimeNs = System.nanoTime()
            Log.i(TAG, "detected Steam Controller (2015 BLE) on characteristic ${chr.uuid.toString().take(8)}")
        }
        input = chr.uuid
        g.setCharacteristicNotification(chr, true)
        val cccd = chr.getDescriptor(CCCD) ?: return disconnect("state report cannot notify")
        val useIndicate = chr.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 &&
            chr.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        @Suppress("DEPRECATION")
        cccd.value = if (useIndicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        @Suppress("DEPRECATION")
        g.writeDescriptor(cccd)
    }

    private fun refreshCache(g: BluetoothGatt) {
        runCatching { g.javaClass.getMethod("refresh").invoke(g) }
            .onFailure { Log.w(TAG, "could not refresh the service cache", it) }
    }

    private fun refreshLizardMode() = withSlot {
        if (!connected) return@withSlot
        if (model == SteamControllerModel.ORIGINAL_2015) {
            enqueueOutput(REPORT, SteamControllerUuids.VALVE_MODE_2015.copyOf())
            lastImuModeCmd2015?.let { enqueueOutput(REPORT, it.copyOf()) }
        } else {
            val msg = ByteArray(FEATURE_BYTES)
            msg[0] = 0x87.toByte(); msg[1] = 3; msg[2] = 9
            enqueueOutput(REPORT, msg)
        }
        handler.postDelayed(::refreshLizardMode, LIZARD_REFRESH_MS)
    }

    private fun onNotification2015(packet: ByteArray) = withSlot {
        val deframed = reassembler2015.onNotification(packet) ?: return@withSlot
        when (val parsed = Sc2015ReportParser.parse(deframed, state2015)) {
            is Sc2015ReportParser.ParsedReport.State -> {
                val elapsedUs = (System.nanoTime() - connectTimeNs) / 1_000L
                val report = Sc2015ReportParser.toTritonReport(
                    state = state2015,
                    packetNum = ++packetCounter2015,
                    elapsedUs = elapsedUs,
                    out = tritonBuffer2015
                )
                onState(report)
            }
            is Sc2015ReportParser.ParsedReport.Status -> {
                batteryVoltageMv = parsed.voltageMv
                batteryPercent = parsed.batteryPercent
            }
            else -> Unit
        }
    }

    private fun onState(data: ByteArray) = withSlot {
        if (data.size < STATE_BYTES) return@withSlot
        val timing = InputTiming.begin()
        FakeInputWriter.writeTritonState(SLOT, reportId, routedInput?.route(data) ?: data)
        InputTiming.end(timing)
    }

    private fun release() = synchronized(inputLock) {
        if (stopped) return@synchronized
        routedInput?.release()
        withSlot {
            FakeInputWriter.writeTritonState(SLOT, if (reportId == 0x47) 0x47 else 0x45, ByteArray(STATE_BYTES))
        }
    }

    private fun pollOutput() = withSlot {
        if (!running || !connected || gatt == null) return@withSlot
        FakeInputWriter.readTritonOutput(SLOT, outputSequence)?.let { output ->
            outputSequence = ByteBuffer.wrap(output, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
            sendOutputReport(output[8] != 0.toByte(), output.copyOfRange(9, output.size))
        }
        val request = FakeInputWriter.readTritonFeatureRequest(SLOT)
        if (request != 0L && request != featureRead) {
            featureRead = request
            if (model == SteamControllerModel.ORIGINAL_2015) {
                val reply = Sc2015ReportParser.buildFeatureReply(pendingFeatureCmd2015, state2015.batteryVoltageMv)
                FakeInputWriter.writeTritonFeatureReply(SLOT, featureRead, reply)
            } else {
                queuedWrites.addLast(REPORT to null)
                writeNextOutput()
            }
        }
        handler.postDelayed(::pollOutput, OUTPUT_POLL_MS)
    }

    private fun onFeatureRead(value: ByteArray?) = withSlot {
        outputWritePending = false
        if (featureRead != 0L) {
            if (value == null) Log.w(TAG, "feature read failed")
            FakeInputWriter.writeTritonFeatureReply(SLOT, featureRead, value)
        }
        writeNextOutput()
    }

    private fun sendOutputReport(feature: Boolean, report: ByteArray) {
        if (report.size < 2) return
        if (model == SteamControllerModel.ORIGINAL_2015) {
            if (feature) {
                pendingFeatureCmd2015 = report[1].toInt() and 0xFF
                Sc2015ReportParser.extractImuModeCommand(report)?.let { imuCmd ->
                    lastImuModeCmd2015 = imuCmd
                    enqueueOutput(REPORT, imuCmd)
                }
            } else {
                val now = SystemClock.uptimeMillis()
                if (now - lastHapticWriteMs >= HAPTIC_MIN_INTERVAL_2015_MS) {
                    Sc2015ReportParser.toHapticPulseCommand(report)?.let { pulse ->
                        lastHapticWriteMs = now
                        enqueueOutput(REPORT, pulse)
                    }
                }
            }
            return
        }
        val characteristic = if (feature) {
            REPORT
        } else {
            val id = report[0].toInt() and 0xff
            outputReports[id] ?: run {
                Log.w(TAG, "no BLE output characteristic for report 0x%02x".format(id))
                return
            }
        }
        enqueueOutput(characteristic, report.copyOfRange(1, report.size - 1))
    }

    private fun enqueueOutput(characteristic: UUID, value: ByteArray?) {
        queuedWrites.addLast(characteristic to value)
        writeNextOutput()
    }

    private fun writeNextOutput() = withSlot {
        if (outputWritePending) return@withSlot
        val g = gatt ?: return@withSlot
        val (uuid, value) = queuedWrites.pollFirst() ?: return@withSlot
        val characteristic = g.getService(SERVICE)?.getCharacteristic(uuid)
        if (characteristic == null) {
            Log.w(TAG, "BLE output characteristic $uuid disappeared")
            return@withSlot
        }
        if (value == null) {
            if (g.readCharacteristic(characteristic)) {
                outputWritePending = true
            } else {
                queuedWrites.addFirst(uuid to null)
                handler.postDelayed(::writeNextOutput, OUTPUT_RETRY_MS)
            }
            return@withSlot
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
        private val slotLock = Any()
        @Volatile private var slotOwner: SteamControllerBle? = null
        private const val TAG = "SteamControllerBle"
        private const val SLOT = 0
        private const val RETRY_MS = 5_000L
        private const val LIZARD_REFRESH_MS = 3_000L
        private const val OUTPUT_POLL_MS = 4L
        private const val OUTPUT_RETRY_MS = 10L
        private const val HAPTIC_MIN_INTERVAL_2015_MS = 25L
        private const val FEATURE_BYTES = 62
        private const val STATE_BYTES = 45
        private const val OUTPUT_UUID_PREFIX = "100f6c"
        private const val OUTPUT_UUID_OFFSET = 0x35

        private val SERVICE = SteamControllerUuids.SERVICE
        private val INPUT_2015 = SteamControllerUuids.INPUT_2015
        private val INPUT_0X45 = SteamControllerUuids.INPUT_0X45
        private val INPUT_0X47 = SteamControllerUuids.INPUT_0X47
        private val REPORT = SteamControllerUuids.REPORT
        private val CCCD = SteamControllerUuids.CCCD

        internal fun isSteamController(device: BluetoothDevice): Boolean =
            device.type and BluetoothDevice.DEVICE_TYPE_LE != 0 &&
                SteamControllerUuids.isSteamControllerName(device.name)

        @JvmStatic
        fun hasPairedController(context: Context): Boolean {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return false
            return runCatching { adapter.isEnabled && adapter.bondedDevices.any(::isSteamController) }
                .onFailure { Log.w(TAG, "paired devices unreadable", it) }
                .getOrDefault(false)
        }

        @Volatile @JvmStatic
        var connected = false
            private set

        @Volatile @JvmStatic
        var activeModel: SteamControllerModel? = null
            private set

        @Volatile @JvmStatic
        var batteryVoltageMv = 0
            private set

        @Volatile @JvmStatic
        var batteryPercent = 100
            private set

        @Volatile @JvmStatic
        var listener: (() -> Unit)? = null
    }
}
