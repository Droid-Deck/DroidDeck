package com.droiddeck.launcher.glasses

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

internal class VitureCamera(context: Context, private val vendorId: Int, private val productId: Int) {
    enum class Status { PAUSED, CAMERA_PERMISSION, USB_PERMISSION, USB_DENIED, STARTING, LIVE, DISCONNECTED, FAILED }

    private val app = context.applicationContext
    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionAction = "${app.packageName}.VITURE_CAMERA_PERMISSION"
    private val worker = VitureCameraWorker()
    private val generation = AtomicLong()
    private val _status = MutableStateFlow(Status.PAUSED)
    val status = _status.asStateFlow()
    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame = _frame.asStateFlow()
    @Volatile private var closed = false
    @Volatile private var resumed = false
    @Volatile private var activeDevice: String? = null
    private var permissionDevice: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed) return
            if (!resumed) {
                if (intent.action == permissionAction) permissionDevice = null
                return
            }
            when (intent.action) {
                permissionAction -> {
                    val requested = permissionDevice ?: return
                    permissionDevice = null
                    val device = usb.deviceList[requested]
                    if (device != null && usb.hasPermission(device)) start()
                    else _status.value = if (device == null) Status.DISCONNECTED else Status.USB_DENIED
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val expected = activeDevice ?: permissionDevice
                    if (expected != null && usb.deviceList[expected] == null) {
                        permissionDevice = null
                        stop()
                        resumed = true
                        _status.value = Status.DISCONNECTED
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (activeDevice == null) start()
            }
        }
    }

    init {
        val filter = IntentFilter(permissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
    }

    @Synchronized fun start() {
        if (closed) return
        resumed = true
        if (activeDevice != null) return
        if (app.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            _status.value = Status.CAMERA_PERMISSION
            return
        }
        val device = usb.deviceList.values.singleOrNull { it.vendorId == vendorId && it.productId == productId }
        if (device == null) {
            _status.value = Status.DISCONNECTED
            return
        }
        if (!usb.hasPermission(device)) {
            _status.value = Status.USB_PERMISSION
            if (permissionDevice != device.deviceName) {
                permissionDevice = device.deviceName
                val pending = PendingIntent.getBroadcast(app, 4, Intent(permissionAction).setPackage(app.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                runCatching { usb.requestPermission(device, pending) }.onFailure {
                    permissionDevice = null
                    _status.value = Status.USB_DENIED
                    Log.w("VitureCamera", "Camera USB permission request failed", it)
                }
            }
            return
        }
        permissionDevice = null
        activeDevice = device.deviceName
        val current = generation.incrementAndGet()
        _status.value = Status.STARTING
        worker.execute { capture(device, current) }
    }

    @Synchronized fun stop() {
        resumed = false
        generation.incrementAndGet()
        activeDevice = null
        _frame.value = null
        _status.value = Status.PAUSED
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        stop()
        app.unregisterReceiver(receiver)
        worker.close()
    }

    private fun current(value: Long) = !closed && resumed && generation.get() == value

    private fun capture(device: UsbDevice, generationId: Long) {
        if (!current(generationId)) return
        var token = 0L
        var connection: android.hardware.usb.UsbDeviceConnection? = null
        var failure: Exception? = null
        try {
            check(VitureSdk.available) { "VITURE SDK unavailable" }
            connection = checkNotNull(usb.openDevice(device)) { "Camera USB connection unavailable" }
            if (!current(generationId)) return
            token = nativeOpen(vendorId, productId, connection.fileDescriptor)
            check(token > 0) { "Camera stream could not start: $token" }
            var lastFrame = SystemClock.elapsedRealtime()
            var received = false
            val options = BitmapFactory.Options().apply { inSampleSize = 2; inPreferredConfig = Bitmap.Config.RGB_565 }
            while (current(generationId)) {
                val bytes = nativeFrame(token)
                if (bytes != null) {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    if (bitmap != null) {
                        synchronized(this) {
                            if (current(generationId)) {
                                _frame.value = bitmap
                                _status.value = Status.LIVE
                            } else bitmap.recycle()
                        }
                        lastFrame = SystemClock.elapsedRealtime()
                        received = true
                    }
                }
                check(SystemClock.elapsedRealtime() - lastFrame < if (received) 3_000 else 8_000) { "Camera stopped delivering frames" }
                Thread.sleep(66)
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            if (token > 0) nativeClose(token)
            connection?.close()
            synchronized(this) {
                if (current(generationId)) {
                    activeDevice = null
                    failure?.let {
                        _frame.value = null
                        _status.value = Status.FAILED
                        Log.w("VitureCamera", "Live camera preview stopped", it)
                    }
                }
            }
        }
    }

    private external fun nativeOpen(vid: Int, pid: Int, fd: Int): Long
    private external fun nativeFrame(token: Long): ByteArray?
    private external fun nativeClose(token: Long)
}
