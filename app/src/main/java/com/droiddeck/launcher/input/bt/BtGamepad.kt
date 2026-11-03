package com.droiddeck.launcher.input.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import java.util.UUID

@SuppressLint("MissingPermission")
object BtGamepad {
    enum class Phase { OFF, STARTING, BLUETOOTH_OFF, UNSUPPORTED, READY, CONNECTING, CONNECTED }

    data class Status(val phase: Phase, val host: String? = null, val lightbar: Int? = null, val name: String? = null)

    private const val TAG = "BtGamepad"

    private val _status = MutableStateFlow(Status(Phase.OFF))
    val status: StateFlow<Status> = _status

    private var handler: Handler? = null
    private var app: Context? = null
    private var server: BluetoothGattServer? = null
    private var advertising = false
    private var profile = BtPadProfile.GENERIC
    private var encoder: BtPadEncoder = profile.encoder()
    private val pendingServices = ArrayDeque<BluetoothGattService>()
    private val reportRefs = HashMap<BluetoothGattCharacteristic, Pair<Int, Int>>()
    private val inputChars = HashMap<Int, BluetoothGattCharacteristic>()
    private val longReads = HashMap<BluetoothGattCharacteristic, ByteArray>()
    private val preparedWrites = HashMap<BluetoothGattCharacteristic, ByteArray>()
    private var host: BluetoothDevice? = null
    private var priorityLink: BluetoothGatt? = null
    private var notifyPending = false
    private var dirty = false
    private var mtu = 23
    private val latest = BtPadState()
    private val sent = BtPadState()
    private var sentOnce = false
    private var vibrator: Vibrator? = null
    @Volatile private var running = false
    private var pairingUntil = 0L
    private var pendingHost: BluetoothDevice? = null
    private var fastLinkPending = false
    private const val PAIRING_WINDOW_MS = 120_000L
    private const val MAX_MTU = 517
    private const val FAST_LINK_DELAY_MS = 1_500L

    fun start(context: Context, p: BtPadProfile) {
        val h = handler ?: init(context)
        h.post {
            running = true
            if (server != null && p == profile) {
                val attached = host
                if (attached != null) publish(Phase.CONNECTED, attached) else publish(Phase.READY)
                startAdvertising()
            } else {
                close()
                profile = p
                publish(Phase.STARTING)
                open()
            }
        }
    }

    private fun init(context: Context): Handler {
        val ctx = context.applicationContext
        app = ctx
        vibrator = ctx.getSystemService(Vibrator::class.java)
        val t = HandlerThread("bt-gamepad").apply { start() }
        val h = Handler(t.looper)
        handler = h
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> h.post { if (running) open() }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> h.post {
                        close()
                        publish(Phase.BLUETOOTH_OFF)
                    }
                }
            }
        }
        ctx.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), null, h)
        val bonds = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1) != BluetoothDevice.BOND_BONDED) return
                h.post {
                    val waiting = pendingHost ?: return@post
                    if (waiting.bondState == BluetoothDevice.BOND_BONDED) attach(waiting)
                }
            }
        }
        ctx.registerReceiver(bonds, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), null, h)
        return h
    }

    fun stop() {
        val h = handler ?: return
        h.post {
            running = false
            rumble(0, 0)
            pairingUntil = 0
            keepScanning()
            stopAdvertising()
            h.removeCallbacks(periodic)
            dropFastLink()
            host?.let { d -> runCatching { server?.cancelConnection(d) } }
            host = null
            _status.value = Status(Phase.OFF)
        }
    }

    fun switchProfile(p: BtPadProfile) {
        val h = handler ?: return
        h.post {
            if (p == profile && server != null) return@post
            profile = p
            close()
            publish(Phase.STARTING)
            h.postDelayed(::open, 300)
        }
    }

    fun update(s: BtPadState) {
        val h = handler ?: return
        synchronized(latest) { latest.set(s) }
        h.post(::sendIfChanged)
    }

    fun openPairingWindow() {
        val h = handler ?: return
        h.post {
            pairingUntil = System.currentTimeMillis() + PAIRING_WINDOW_MS
            keepScanning()
            if (host == null) {
                stopAdvertising()
                startAdvertising()
            }
        }
    }

    private fun keepScanning() {
        val adapter = adapter() ?: return
        val h = handler ?: return
        h.removeCallbacks(scanTick)
        if (host != null || System.currentTimeMillis() > pairingUntil) {
            runCatching { if (adapter.isDiscovering) adapter.cancelDiscovery() }
            return
        }
        runCatching { if (!adapter.isDiscovering) adapter.startDiscovery() }
        h.postDelayed(scanTick, 2_000)
    }

    private val scanTick = Runnable { keepScanning() }

    fun disconnect() {
        handler?.post { host?.let { d -> server?.cancelConnection(d) } }
    }

    private fun manager() = app?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private fun adapter(): BluetoothAdapter? = manager()?.adapter

    private fun open() {
        val ctx = app ?: return
        if (server != null || !running) return
        val manager = manager()
        val adapter = manager?.adapter
        if (adapter == null || !ctx.packageManager.hasSystemFeature("android.hardware.bluetooth_le")) {
            publish(Phase.UNSUPPORTED)
            return
        }
        if (!adapter.isEnabled) {
            publish(Phase.BLUETOOTH_OFF)
            return
        }
        if (adapter.bluetoothLeAdvertiser == null) {
            publish(Phase.UNSUPPORTED)
            return
        }
        encoder = profile.encoder()
        sentOnce = false
        val s = runCatching { manager.openGattServer(ctx, serverCallback) }
            .onFailure { Log.w(TAG, "no GATT server", it) }.getOrNull()
        if (s == null) {
            publish(Phase.UNSUPPORTED)
            return
        }
        server = s
        pendingServices.clear()
        pendingServices.add(deviceInfoService())
        pendingServices.add(hidService())
        addNextService()
    }

    private fun close() {
        stopAdvertising()
        pairingUntil = 0
        keepScanning()
        dropFastLink()
        server?.let { s ->
            runCatching { manager()?.getConnectedDevices(BluetoothProfile.GATT_SERVER)?.forEach { s.cancelConnection(it) } }
            runCatching { s.clearServices(); s.close() }
        }
        server = null
        host = null
        notifyPending = false
        dirty = false
        reportRefs.clear()
        inputChars.clear()
        longReads.clear()
        preparedWrites.clear()
    }

    private fun addNextService() {
        val s = server ?: return
        val next = pendingServices.pollFirst()
        if (next == null) {
            Log.i(TAG, "${profile.id}: services ready")
            startAdvertising()
            return
        }
        if (!s.addService(next)) {
            Log.w(TAG, "addService ${next.uuid} refused")
            publish(Phase.UNSUPPORTED)
        }
    }

    private fun deviceInfoService(): BluetoothGattService {
        val service = BluetoothGattService(uuid16(0x180A), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val pnp = BtPadEncoder.bytes(0x02, profile.vendorId and 0xFF, profile.vendorId shr 8,
            profile.productId and 0xFF, profile.productId shr 8, profile.version and 0xFF, profile.version shr 8)
        service.addCharacteristic(readOnly(0x2A50, pnp))
        service.addCharacteristic(readOnly(0x2A29, "DroidDeck".toByteArray()))
        return service
    }

    private fun hidService(): BluetoothGattService {
        val service = BluetoothGattService(uuid16(0x1812), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(readOnly(0x2A4A, BtPadEncoder.bytes(0x11, 0x01, 0x00, 0x02)))
        service.addCharacteristic(readOnly(0x2A4B, encoder.descriptor))
        service.addCharacteristic(BluetoothGattCharacteristic(uuid16(0x2A4C),
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        for ((id, type) in encoder.reports) {
            val props = when (type) {
                BtPadEncoder.INPUT -> BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY
                BtPadEncoder.OUTPUT -> BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                else -> BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE
            }
            val chr = BluetoothGattCharacteristic(uuid16(0x2A4D), props,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE)
            if (type == BtPadEncoder.INPUT) {
                chr.addDescriptor(cccd())
                inputChars[id] = chr
            }
            val ref = BluetoothGattDescriptor(uuid16(0x2908), BluetoothGattDescriptor.PERMISSION_READ)
            @Suppress("DEPRECATION")
            ref.value = BtPadEncoder.bytes(id, type)
            chr.addDescriptor(ref)
            reportRefs[chr] = id to type
            service.addCharacteristic(chr)
        }
        return service
    }

    private fun readOnly(uuid: Int, value: ByteArray): BluetoothGattCharacteristic {
        val chr = BluetoothGattCharacteristic(uuid16(uuid), BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
        @Suppress("DEPRECATION")
        chr.value = value
        return chr
    }

    private fun cccd() = BluetoothGattDescriptor(uuid16(0x2902),
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)

    private fun uuid16(v: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(v))

    private fun startAdvertising() {
        val advertiser = adapter()?.bluetoothLeAdvertiser ?: return
        if (advertising || server == null || host != null || !running) return
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(uuid16(0x1812)))
            .setIncludeDeviceName(true)
            .build()
        advertising = runCatching { advertiser.startAdvertisingSet(params, data, null, null, null, advertiseCallback); true }
            .onFailure { Log.w(TAG, "advertising refused", it) }.getOrDefault(false)
        if (!advertising) publish(Phase.UNSUPPORTED)
    }

    private fun stopAdvertising() {
        if (!advertising) return
        advertising = false
        runCatching { adapter()?.bluetoothLeAdvertiser?.stopAdvertisingSet(advertiseCallback) }
    }

    private val advertiseCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            Log.i(TAG, "advertising status $status")
            handler?.post {
                if (status == ADVERTISE_SUCCESS) {
                    if (host == null) publish(Phase.READY)
                } else {
                    advertising = false
                    publish(Phase.UNSUPPORTED)
                }
            }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            handler?.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "service ${service.uuid} failed: $status")
                    publish(Phase.UNSUPPORTED)
                } else addNextService()
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler?.post {
                Log.i(TAG, "${device.address}: state $newState status $status bond ${device.bondState}")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    stopAdvertising()
                    if (!running) {
                        server?.cancelConnection(device)
                        return@post
                    }
                    mtu = 23
                    if (device.bondState == BluetoothDevice.BOND_BONDED) attach(device)
                    else if (host == null) publish(Phase.CONNECTING, device)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED && pendingHost == device && host == null) {
                    pendingHost = null
                    publish(Phase.READY)
                    startAdvertising()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED && host == device) {
                    host = null
                    rumble(0, 0)
                    dropFastLink()
                    publish(Phase.READY)
                    startAdvertising()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED && host == null) {
                    publish(Phase.READY)
                    startAdvertising()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, newMtu: Int) {
            handler?.post {
                mtu = newMtu
                Log.i(TAG, "mtu $newMtu")
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, chr: BluetoothGattCharacteristic) {
            handler?.post {
                val value = if (offset > 0) longReads[chr] ?: valueOf(chr, device) else valueOf(chr, device).also { longReads[chr] = it }
                val reply = if (offset >= value.size) ByteArray(0) else value.copyOfRange(offset, value.size)
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, reply)
                if (chr.uuid == uuid16(0x2A4B)) attach(device)
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            handler?.post {
                @Suppress("DEPRECATION")
                val value = descriptor.value ?: if (descriptor.uuid == uuid16(0x2902)) BtPadEncoder.bytes(1, 0) else ByteArray(0)
                val reply = if (offset >= value.size) ByteArray(0) else value.copyOfRange(offset, value.size)
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, reply)
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            handler?.post {
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                if (descriptor.uuid == uuid16(0x2902) && descriptor.characteristic in reportRefs && value.isNotEmpty() &&
                    value[0].toInt() and 1 != 0) {
                    attach(device)
                    handler?.postDelayed({ startFastLink(device) }, FAST_LINK_DELAY_MS)
                }
            }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, chr: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            handler?.post {
                if (device == host && preparedWrite) {
                    val old = preparedWrites[chr] ?: ByteArray(0)
                    val merged = old.copyOf(maxOf(old.size, offset + value.size))
                    value.copyInto(merged, offset)
                    preparedWrites[chr] = merged
                } else if (device == host) written(chr, value)
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            handler?.post {
                if (execute && device == host) preparedWrites.forEach { (chr, v) -> written(chr, v) }
                preparedWrites.clear()
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            handler?.post {
                notifyPending = false
                if (dirty) sendNow()
            }
        }
    }

    private fun valueOf(chr: BluetoothGattCharacteristic, device: BluetoothDevice): ByteArray {
        val ref = reportRefs[chr]
        if (ref == null) {
            @Suppress("DEPRECATION")
            return chr.value ?: ByteArray(0)
        }
        val (id, type) = ref
        return when (type) {
            BtPadEncoder.INPUT -> if (id == encoder.inputId() && device == host) encoder.input(current()) else ByteArray(0)
            BtPadEncoder.FEATURE -> {
                host?.let { d -> handler?.postDelayed({ startFastLink(d) }, FAST_LINK_DELAY_MS) }
                encoder.feature(id).also { Log.i(TAG, "feature 0x%02x read -> %d".format(id, it?.size ?: -1)) } ?: ByteArray(0)
            }
            else -> ByteArray(0)
        }
    }

    private fun written(chr: BluetoothGattCharacteristic, value: ByteArray) {
        val (id, type) = reportRefs[chr] ?: return
        when (type) {
            BtPadEncoder.FEATURE -> {
                Log.i(TAG, "feature 0x%02x write %d bytes".format(id, value.size))
                encoder.setFeature(id, value)
            }
            BtPadEncoder.OUTPUT -> feedback(encoder.output(id, value))
        }
    }

    private fun attach(device: BluetoothDevice) {
        if (host != null) return
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            pendingHost = device
            publish(Phase.CONNECTING, device)
            return
        }
        pendingHost = null
        host = device
        stopAdvertising()
        pairingUntil = 0
        keepScanning()
        sentOnce = false
        notifyPending = false
        Log.i(TAG, "host ${device.address} attached")
        publish(Phase.CONNECTED, device)
        fastLinkPending = true
        handler?.removeCallbacks(periodic)
        handler?.post(periodic)
    }

    private fun startFastLink(device: BluetoothDevice) {
        if (host != device || !fastLinkPending) return
        fastLinkPending = false
        requestFastLink(device)
    }

    private fun requestFastLink(device: BluetoothDevice) {
        val ctx = app ?: return
        dropFastLink()
        priorityLink = runCatching {
            device.connectGatt(ctx, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED && !gatt.requestMtu(MAX_MTU)) {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, newMtu: Int, status: Int) {
                    handler?.post {
                        if (newMtu > mtu) mtu = newMtu
                        Log.i(TAG, "link mtu $newMtu")
                    }
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                }
            }, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
    }

    private fun dropFastLink() {
        priorityLink?.let { runCatching { it.disconnect(); it.close() } }
        priorityLink = null
    }

    private fun current(): BtPadState = BtPadState().also { s -> synchronized(latest) { s.set(latest) } }

    private fun sendIfChanged() {
        if (encoder.periodMs > 0) return
        if (sentOnce && current().sameAs(sent)) return
        sendNow()
    }

    private fun sendNow() {
        val s = server ?: return
        val device = host ?: return
        if (notifyPending) {
            dirty = true
            return
        }
        val chr = inputChars[encoder.inputId()] ?: return
        val state = current()
        val value = encoder.input(state)
        if (value.size > mtu - 3) {
            dirty = false
            return
        }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                s.notifyCharacteristicChanged(device, chr, false, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                chr.value = value
                @Suppress("DEPRECATION")
                s.notifyCharacteristicChanged(device, chr, false)
            }
        }.getOrDefault(false)
        dirty = !ok
        notifyPending = ok
        if (ok) {
            sent.set(state)
            sentOnce = true
        }
    }

    private val periodic: Runnable = object : Runnable {
        override fun run() {
            val h = handler ?: return
            if (host == null) return
            if (encoder.periodMs > 0 || dirty) sendNow()
            h.postDelayed(this, if (encoder.periodMs > 0) encoder.periodMs else 50)
        }
    }

    private fun feedback(f: BtFeedback?) {
        f ?: return
        if (f.strong >= 0) rumble(f.strong, f.weak)
        if (f.lightbar != null) _status.value = _status.value.copy(lightbar = f.lightbar)
    }

    private fun rumble(strong: Int, weak: Int) {
        val v = vibrator ?: return
        val amplitude = maxOf(strong, weak * 2 / 3).coerceIn(0, 255)
        runCatching {
            if (amplitude == 0) v.cancel()
            else v.vibrate(VibrationEffect.createOneShot(1_000, amplitude))
        }
    }

    private fun publish(phase: Phase, device: BluetoothDevice? = null) {
        if (!running) {
            _status.value = Status(Phase.OFF)
            return
        }
        val name = device?.let { runCatching { it.name }.getOrNull() ?: it.address }
        val own = runCatching { adapter()?.name }.getOrNull()
        _status.value = Status(phase, name, _status.value.lightbar.takeIf { phase == Phase.CONNECTED }, own)
    }
}
