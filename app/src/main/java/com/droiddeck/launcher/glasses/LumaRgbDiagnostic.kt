package com.droiddeck.launcher.glasses

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

internal object LumaRgbDiagnostic {
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "spatial/rgb-diagnostic.json"))
    private fun save(context: Context, value: JSONObject) {
        val target = file(context)
        target.baseFile.parentFile?.mkdirs()
        val output = target.startWrite()
        try {
            output.write(value.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
        } catch (e: Exception) { target.failWrite(output); throw e }
    }

    private fun expired(value: JSONObject, nowMs: Long) = nowMs-value.optLong("startedMs") !in
        0..value.optLong("timeoutMs",15_000).coerceIn(15_000,45_000)

    private fun workerAlive(context: Context, pid: Int): Boolean = pid > 0 && pid != android.os.Process.myPid() &&
        runCatching {
            File("/proc/$pid/cmdline").readText().substringBefore('\u0000') == "${context.packageName}:rgb_diagnostic"
        }.getOrDefault(false)

    internal fun resolveStatus(value: JSONObject, nowMs: Long, workerAlive: Boolean): JSONObject {
        val pending = value.optString("state") == "capturing"
        if (workerAlive && !expired(value,nowMs)) {
            if (!pending) value.put("resultState",value.optString("state")).put("phase","finishing")
            return value.put("state","capturing").put("busy",true)
        }
        if (pending && (expired(value,nowMs) || value.optInt("workerPid") > 0 && !workerAlive))
            value.put("state","failed").put("message","RGB capture worker stopped or timed out")
        return value.put("busy",workerAlive || value.optString("state") == "capturing")
    }

    fun status(context: Context): JSONObject = runCatching {
        val value = JSONObject(file(context).readFully().toString(Charsets.UTF_8))
        val now = android.os.SystemClock.elapsedRealtime()
        val pid = value.optInt("workerPid")
        if (expired(value,now) && workerAlive(context,pid)) android.os.Process.killProcess(pid)
        resolveStatus(value,now,workerAlive(context,pid))
    }.getOrElse { JSONObject().put("state", "idle") }

    @Synchronized fun capture(context: Context, transport: String = "sdk", width: Int = 640, height: Int = 480,
                              fps: Int = 30, durationMs: Int = 5000): JSONObject {
        require(transport in listOf("sdk","uvc") && width in 160..1920 && height in 120..1080 &&
            fps in 5..30 && durationMs in 1000..30000) { "Invalid RGB capture options" }
        val app = context.applicationContext
        val old = status(app)
        if (old.optBoolean("busy")) return old
        val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usb.deviceList.values.singleOrNull { it.vendorId == 0x0c45 && it.productId == 0x636b }
        fun state(value: String, message: String): JSONObject {
            val result = JSONObject().put("state", value).put("message", message)
            save(app, result)
            return result
        }
        if (!Glasses.state.value.spatialConnected || device == null)
            return state("unavailable", "Connect the Luma Ultra glasses and RGB camera")
        if (app.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            return state("permission_required", "Grant Android camera permission to capture the RGB camera")
        if (!usb.hasPermission(device)) {
            val intent = Intent("${app.packageName}.RGB_DIAGNOSTIC_PERMISSION").setPackage(app.packageName)
            val pending = PendingIntent.getBroadcast(app, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            usb.requestPermission(device, pending)
            return state("permission_required", "Approve the USB camera permission, then request the capture again")
        }
        val result = JSONObject().put("state", "capturing").put("startedMs", android.os.SystemClock.elapsedRealtime())
            .put("timeoutMs",durationMs+15000).put("transport",transport)
        save(app, result)
        try { app.startService(Intent(app, LumaRgbCaptureService::class.java).putExtra("device", device.deviceName)
            .putExtra("transport",transport).putExtra("width",width).putExtra("height",height)
            .putExtra("fps",fps).putExtra("durationMs",durationMs)) }
        catch (e: Exception) { return state("failed", e.message ?: e.toString()) }
        return result
    }

    internal fun perform(context: Context, deviceName: String, transport: String, width: Int, height: Int,
                         fps: Int, durationMs: Int) {
        val folder=File(context.filesDir,"spatial/rgb-captures/${System.nanoTime()}").apply { mkdirs() }
        val startedMs=android.os.SystemClock.elapsedRealtime()
        val workerPid=android.os.Process.myPid()
        save(context,JSONObject().put("state","capturing").put("path",folder.path)
            .put("startedMs",startedMs).put("timeoutMs",durationMs+15000)
            .put("transport",transport).put("workerPid",workerPid))
        val result = try {
            val direct=transport=="uvc"
            check(if(direct) UvcRgbDiagnostic.available else VitureSdk.available) { "Requested RGB transport is unavailable" }
            val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val device = checkNotNull(usb.deviceList[deviceName]) { "RGB camera disconnected" }
            check(device.vendorId == 0x0c45 && device.productId == 0x636b && usb.hasPermission(device))
            val connection = checkNotNull(usb.openDevice(device)) { "Could not open RGB camera" }
            val metadata=LongArray(if(direct) 14 else 7)
            val jpeg=try {
                File(folder,"usb-descriptors.bin").writeBytes(connection.rawDescriptors)
                android.os.ParcelFileDescriptor.open(File(folder,"usb.log"),
                    android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_TRUNCATE or
                        android.os.ParcelFileDescriptor.MODE_WRITE_ONLY).use { log ->
                    if(direct) UvcRgbDiagnostic.nativeSnapshot(connection.fileDescriptor,width,height,fps,durationMs,metadata,log.fd)
                    else VitureSdk.nativeRgbSnapshot(device.vendorId,device.productId,connection.fileDescriptor,metadata,log.fd)
                }
            } finally { connection.close() }
            check(jpeg != null) { "RGB camera returned no frame ($transport status ${metadata[4]}, callbacks ${metadata[5]})" }
            File(folder,"rgb.jpg").writeBytes(jpeg)
            val info=JSONObject().put("state","completed").put("path",folder.path)
                .put("width",metadata[0]).put("height",metadata[1]).put("timestampNs",metadata[2])
                .put("sequence",metadata[3]).put("callbacks",metadata[5]).put("source","Luma Ultra UVC RGB")
                .put("receiptNs",metadata[6]).put("timingBasis","Host callback receipt; not exposure time")
                .put("snapshotAcquired",true)
            if(direct) {
                val health=UvcStreamHealth.assess(metadata,fps,durationMs)
                info.put("firstReceiptNs",metadata[7]).put("maxFrameGapMs",health.maximumGapMs)
                    .put("completeFrames",metadata[9]).put("invalidFrames",metadata[10]).put("bytes",metadata[11])
                    .put("observationEndNs",metadata[12]).put("tailFrameAgeMs",metadata[13]/1e6)
                    .put("observationMs",health.observationMs).put("observedFps",health.observedFps)
                    .put("streamHealthy",health.healthy)
            }
            info
        } catch (e: Exception) { JSONObject().put("state","failed").put("snapshotAcquired",false)
            .put("message",e.message ?: e.toString()) }
        result.put("path",folder.path).put("transport",transport).put("requestedWidth",width).put("requestedHeight",height)
            .put("requestedFps",fps).put("durationMs",durationMs).put("workerPid",workerPid)
            .put("startedMs",startedMs).put("timeoutMs",durationMs+15000)
        if (transport == "uvc" && !result.has("streamHealthy")) result.put("streamHealthy",false)
        File(folder,"frame.json").writeText(result.toString())
        save(context,result)
    }
}

class LumaRgbCaptureService : Service() {
    private var running = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            Thread({
                try { LumaRgbDiagnostic.perform(this,intent?.getStringExtra("device") ?: "",
                    intent?.getStringExtra("transport") ?: "sdk",intent?.getIntExtra("width",640) ?: 640,
                    intent?.getIntExtra("height",480) ?: 480,intent?.getIntExtra("fps",30) ?: 30,
                    intent?.getIntExtra("durationMs",5000) ?: 5000) }
                finally {
                    stopSelf()
                    // SDK 2.4.0 can crash while cancelling failed USB transfers; isolate each diagnostic.
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }, "luma-rgb-diagnostic").start()
        }
        return START_NOT_STICKY
    }
}
