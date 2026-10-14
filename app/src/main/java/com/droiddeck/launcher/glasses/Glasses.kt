package com.droiddeck.launcher.glasses

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sin

data class GlassesState(
    val present: Boolean = false,
    val model: String = "VITURE",
    val controllable: Boolean = false,
    val permission: Glasses.Permission = Glasses.Permission.NONE,
    val connected: Boolean = false,
    val displayId: Int = Display.INVALID_DISPLAY,
    val width: Int = 0,
    val height: Int = 0,
    val hz: Float = 0f,
    val sbs: Boolean = false,
    val switching3d: Boolean = false,
    val brightness: Int = -1,
    val volume: Int = -1,
    val brightnessMax: Int = 8,
    val volumeMax: Int = 8,
    val sunblock: Boolean = false,
    val tracking: Boolean = false,
    val diagonalFovDeg: Float = 46f,
    val noSignal: Boolean = false,
    val sbsSupport: Int = SBS_UNKNOWN,
    val sbs90: Boolean = false,
) {
    val hasDisplay: Boolean get() = displayId != Display.INVALID_DISPLAY
    val usable: Boolean get() = hasDisplay && !noSignal
    val canControl: Boolean get() = connected && controllable
    val can3d: Boolean get() = canControl && !noSignal && sbsSupport != SBS_FAILS
    val verticalFovDeg: Float get() {
        val half = Math.toRadians(diagonalFovDeg / 2.0)
        val aspect = 16.0 / 9.0
        val v = kotlin.math.atan(kotlin.math.tan(half) / kotlin.math.sqrt(1 + aspect * aspect))
        return Math.toDegrees(2 * v).toFloat()
    }

    companion object {
        const val SBS_UNKNOWN = 0
        const val SBS_WORKS = 1
        const val SBS_FAILS = -1
    }
}

data class GlassesSettings(
    val refreshHz: Int = 120,
    val sbsHz: Int = 60,
    val sunblock: Boolean? = null,
    val gameOnGlasses: Boolean = true,
    val headTracking: Boolean = true,
    val auto3d: Boolean = true,
    val picture3d: Int = PICTURE_AUTO,
    val screenWidthDm: Int = 20,
    val screenDistanceDm: Int = 30,
    val vrFovDeg: Int = VR_FOV_DEFAULT,
) {
    companion object {
        const val VR_FOV_MIN = 24
        const val VR_FOV_MAX = 90
        const val VR_FOV_DEFAULT = 90
        const val PICTURE_AUTO = 0
        const val PICTURE_SCREEN = 1
        const val PICTURE_SBS = 2
    }
}

@SuppressLint("StaticFieldLeak")
object Glasses {
    enum class Permission { NONE, ASKING, DENIED, GRANTED }

    fun interface PoseSink {
        fun onPose(x: Float, y: Float, z: Float, w: Float, timeNs: Long)
    }

    private const val TAG = "Glasses"
    private const val ACTION_PERMISSION = "com.droiddeck.launcher.glasses.USB_PERMISSION"
    private const val PREFS = "glasses"
    private const val KEY_SBS_SUPPORT = "sbsSupport"
    private const val SBS_CONFIRM_MS = 20_000L

    private val _state = MutableStateFlow(GlassesState())
    val state: StateFlow<GlassesState> = _state.asStateFlow()
    private val _settings = MutableStateFlow(GlassesSettings())
    val settings: StateFlow<GlassesSettings> = _settings.asStateFlow()

    private lateinit var app: Context
    private lateinit var handler: Handler
    private lateinit var usb: UsbManager
    private lateinit var displays: DisplayManager
    @Volatile private var link: VitureLink? = null
    @Volatile private var linkGeneration = 0L
    private var linkDevice: UsbDevice? = null
    private var model: VitureProtocol.Model? = null
    private var asked = false
    private var lastModeSentAt = 0L
    private val modeState = GlassesModeState(SBS_CONFIRM_MS)
    private val confirmMode = Runnable { refresh() }
    private var glassesMode = 0
    private var autoModeSent = false

    @Volatile private var sink: PoseSink? = null
    @Volatile private var probe: ((ByteArray) -> Unit)? = null
    @Volatile private var latest: FloatArray? = null
    @Volatile private var imuPackets = 0L
    private val simulating = java.util.concurrent.atomic.AtomicReference<Thread?>(null)
    private val frames = ArrayDeque<String>()

    fun init(context: Context) {
        if (::handler.isInitialized) return
        app = context.applicationContext
        usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        displays = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        _settings.value = load()
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_SBS_SUPPORT).apply()
        handler = Handler(HandlerThread("droiddeck-glasses").apply { start() }.looper)
        handler.post {
            val filter = IntentFilter(ACTION_PERMISSION).apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(receiver, filter, null, handler)
            displays.registerDisplayListener(displayListener, handler)
            refresh()
        }
    }

    fun rescan() = post { refresh() }

    fun query() = post {
        val l = link ?: return@post
        listOf(VitureProtocol.MSG_GET_DISPLAY, VitureProtocol.MSG_GET_BRIGHTNESS, VitureProtocol.MSG_GET_VOLUME, VitureProtocol.MSG_GET_IMU).forEachIndexed { i, msg ->
            handler.postDelayed({ if (link === l) l.send(msg) }, VitureLink.COMMAND_GAP_MS * i)
        }
    }

    fun sendMode(mode: Int) = post {
        if (!_state.value.canControl) return@post
        if (link?.send(VitureProtocol.MSG_DISPLAY, byteArrayOf(mode.toByte())) == true) lastModeSentAt = SystemClock.uptimeMillis()
    }

    fun requestControl() = post {
        asked = false
        refresh(ask = true)
    }

    fun setRefreshHz(hz: Int) = post {
        update { it.copy(refreshHz = hz.coerceIn(60, 120)) }
        if (!_state.value.sbs) applyMode(force = true)
    }

    fun setSbsHz(hz: Int) = post {
        update { it.copy(sbsHz = if (hz >= 90) 90 else 60) }
        if (_state.value.sbs && _state.value.can3d) sendMode(true)
    }

    fun set3d(on: Boolean) = post {
        modeState.manual()
        val s = _state.value
        if (!s.canControl || (on && s.noSignal)) return@post
        if (on) rememberSbs(GlassesState.SBS_UNKNOWN)
        sendMode(on)
    }

    fun set3dForVr(on: Boolean) = post {
        val s = _state.value
        if (on) {
            if (!s.can3d || s.sbs || modeState.pendingStereo) return@post
            if (sendMode(true)) modeState.startVr()
        } else if (modeState.stopVr() && s.canControl) {
            sendMode(false)
        }
    }

    fun setBrightness(level: Int) = post {
        val s = _state.value
        if (!s.canControl) return@post
        val v = level.coerceIn(0, s.brightnessMax)
        if (link?.send(VitureProtocol.MSG_BRIGHTNESS, byteArrayOf(v.toByte(), 0)) == true) publish { it.copy(brightness = v) }
    }

    fun setVolume(level: Int) = post {
        val s = _state.value
        if (!s.canControl) return@post
        val v = level.coerceIn(0, s.volumeMax)
        if (link?.send(VitureProtocol.MSG_VOLUME, byteArrayOf(v.toByte(), 0)) == true) publish { it.copy(volume = v) }
    }

    fun setSunblock(on: Boolean) = post {
        update { it.copy(sunblock = on) }
        sendFilm(on)
    }

    fun setSbsSupport(support: Int) = post { rememberSbs(support.coerceIn(GlassesState.SBS_FAILS, GlassesState.SBS_WORKS)) }
    fun setGameOnGlasses(on: Boolean) = post { update { it.copy(gameOnGlasses = on) } }
    fun setHeadTracking(on: Boolean) = post { update { it.copy(headTracking = on) } }
    fun setAuto3d(on: Boolean) = post { update { it.copy(auto3d = on) } }
    fun setPicture3d(picture: Int) = post { update { it.copy(picture3d = picture.coerceIn(0, 2)) } }
    fun setScreenWidthDm(dm: Int) = post { update { it.copy(screenWidthDm = dm.coerceIn(5, 200)) } }
    fun setScreenDistanceDm(dm: Int) = post { update { it.copy(screenDistanceDm = dm.coerceIn(5, 200)) } }
    fun setVrFovDeg(deg: Int) = post { update { it.copy(vrFovDeg = deg.coerceIn(GlassesSettings.VR_FOV_MIN, GlassesSettings.VR_FOV_MAX)) } }

    fun outputHz(): Int {
        val s = _state.value
        val g = _settings.value
        return if (s.sbs) if (s.sbs90) g.sbsHz else 60 else g.refreshHz
    }

    fun glassesDisplay(): Display? = _state.value.displayId.takeIf { it != Display.INVALID_DISPLAY }?.let { displays.getDisplay(it) }

    fun setPoseSink(s: PoseSink) {
        synchronized(this) { sink = s }
        post { updateImu() }
    }

    fun clearPoseSink(s: PoseSink) {
        synchronized(this) { if (sink === s) sink = null }
        post { updateImu() }
    }

    fun simulate(seconds: Int, holdYaw: Double? = null, holdPitch: Double? = null): Boolean {
        val hold = holdYaw != null || holdPitch != null
        val t = Thread({
            val start = SystemClock.elapsedRealtimeNanos()
            val end = start + seconds * 1_000_000_000L
            while (SystemClock.elapsedRealtimeNanos() < end && simulating.get() === Thread.currentThread()) {
                val now = SystemClock.elapsedRealtimeNanos()
                val t = (now - start) / 1e9
                val yaw = if (hold) holdYaw ?: 0.0 else 40.0 * sin(t * 2 * Math.PI / 6.0)
                val pitch = if (hold) holdPitch ?: 0.0 else 15.0 * sin(t * 2 * Math.PI / 4.0)
                val roll = if (hold) 0.0 else 5.0 * sin(t * 2 * Math.PI / 5.0)
                val q = VitureProtocol.eulerZxy(roll.toFloat(), pitch.toFloat(), yaw.toFloat())
                deliver(q, now)
                SystemClock.sleep(4)
            }
            simulating.compareAndSet(Thread.currentThread(), null)
        }, "glasses-simulate")
        if (!simulating.compareAndSet(null, t)) return false
        t.start()
        return true
    }

    fun stopSimulation() {
        simulating.set(null)
    }

    fun status(): JSONObject {
        val s = _state.value
        val g = _settings.value
        val q = latest
        return JSONObject()
            .put("present", s.present).put("model", s.model).put("controllable", s.controllable)
            .put("permission", s.permission.name).put("connected", s.connected)
            .put("display", if (s.hasDisplay) JSONObject().put("id", s.displayId).put("width", s.width).put("height", s.height).put("hz", s.hz.toDouble()) else JSONObject.NULL)
            .put("sbs", s.sbs).put("deviceMode", glassesMode).put("switching3d", s.switching3d)
            .put("brightness", s.brightness).put("volume", s.volume)
            .put("brightnessMax", s.brightnessMax).put("volumeMax", s.volumeMax).put("sunblock", s.sunblock)
            .put("tracking", s.tracking).put("simulating", simulating.get() != null).put("imuPackets", imuPackets)
            .put("pose", q?.let { JSONArray().put(it[0].toDouble()).put(it[1].toDouble()).put(it[2].toDouble()).put(it[3].toDouble()) } ?: JSONObject.NULL)
            .put("fovDeg", s.diagonalFovDeg.toDouble()).put("noSignal", s.noSignal).put("sbsSupport", s.sbsSupport)
            .put("settings", JSONObject().put("refreshHz", g.refreshHz).put("sbsHz", g.sbsHz).put("sunblock", g.sunblock ?: JSONObject.NULL)
                .put("gameOnGlasses", g.gameOnGlasses).put("headTracking", g.headTracking).put("auto3d", g.auto3d)
                .put("picture3d", g.picture3d).put("screenWidthDm", g.screenWidthDm).put("screenDistanceDm", g.screenDistanceDm)
                .put("vrFovDeg", g.vrFovDeg))
    }

    fun recentFrames(): JSONArray = synchronized(frames) { JSONArray(frames.toList()) }

    fun sendRaw(msgId: Int, payload: ByteArray): Boolean {
        val l = link ?: return false
        return l.send(msgId, payload)
    }

    private fun post(block: () -> Unit) {
        if (!::handler.isInitialized) return
        handler.post(block)
    }

    private fun publish(transform: (GlassesState) -> GlassesState) {
        _state.value = transform(_state.value)
    }

    private fun update(transform: (GlassesSettings) -> GlassesSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        save(next)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "USB permission ${if (granted) "granted" else "denied"}")
                    if (!granted) publish { it.copy(permission = Permission.DENIED) }
                    refresh()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (intent.device()?.vendorId == VitureProtocol.VENDOR_ID) asked = false
                    refresh(ask = true)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (intent.device()?.vendorId == VitureProtocol.VENDOR_ID) {
                    refresh()
                }
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == _state.value.displayId || displayId != Display.DEFAULT_DISPLAY) refresh()
        }
    }

    private fun refresh(ask: Boolean = false) {
        val devices = usb.deviceList.values.filter { it.vendorId == VitureProtocol.VENDOR_ID }
        val known = devices.mapNotNull { device ->
            VitureProtocol.model(device.productId, runCatching { device.productName }.getOrNull())?.let { device to it }
        }
        val selected = known.firstOrNull { it.second.controllable } ?: known.firstOrNull()
        val device = selected?.first
        val m = selected?.second
        val controllable = m?.controllable == true
        val permitted = controllable && device != null && usb.hasPermission(device)
        val identity = device?.let {
            GlassesModeState.Device(it.productId, if (permitted) runCatching { it.serialNumber }.getOrNull() else null)
        }
        if (modeState.updateDevice(identity, SystemClock.uptimeMillis())) {
            rememberSbs(GlassesState.SBS_UNKNOWN)
        }
        if (device == null || (linkDevice != null && linkDevice?.deviceName != device.deviceName)) closeLink()
        model = m
        val display = findDisplay(devices.isNotEmpty())
        val mode = display?.mode
        val width = mode?.physicalWidth ?: 0
        val height = mode?.physicalHeight ?: 0
        val sbsByDisplay = width >= 3000
        val noSignal = display != null && width in 1 until 1280
        when (modeState.observe(sbsByDisplay, SystemClock.uptimeMillis())) {
            GlassesModeState.Confirmation.FAILED -> {
                Log.w(TAG, "3D signal was not confirmed at ${width}x$height; restoring 2D")
                rememberSbs(GlassesState.SBS_FAILS)
            }
            else -> Unit
        }
        if (sbsByDisplay) rememberSbs(GlassesState.SBS_WORKS)
        if (controllable && device != null && !permitted && (ask || !asked) && _state.value.permission != Permission.ASKING) askPermission(device)
        if (device != null && permitted && link == null) openLink(device)
        publish {
            it.copy(
                present = devices.isNotEmpty() || display != null,
                model = m?.name ?: display?.let(::displayName) ?: "VITURE",
                controllable = controllable,
                permission = when {
                    !controllable -> Permission.NONE
                    permitted -> Permission.GRANTED
                    it.permission == Permission.ASKING || it.permission == Permission.DENIED -> it.permission
                    else -> Permission.NONE
                },
                connected = link != null,
                displayId = display?.displayId ?: Display.INVALID_DISPLAY,
                width = width, height = height, hz = mode?.refreshRate ?: 0f,
                sbs = sbsByDisplay,
                switching3d = modeState.pendingStereo,
                brightnessMax = m?.brightnessMax ?: 8, volumeMax = m?.volumeMax ?: 8,
                sunblock = _settings.value.sunblock ?: it.sunblock,
                diagonalFovDeg = m?.diagonalFovDeg ?: 46f,
                tracking = it.tracking && link != null,
                noSignal = noSignal,
                sbs90 = m?.sbs90 == true,
            )
        }
        if (link != null) applyMode(force = false)
        scheduleModeCheck()
    }

    private fun findDisplay(usbPresent: Boolean): Display? {
        val candidates = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .filter { it.displayId != Display.DEFAULT_DISPLAY && it.flags and Display.FLAG_PRESENTATION != 0 }
        return candidates.firstOrNull { displayName(it)?.contains("VITURE", ignoreCase = true) == true }
            ?: candidates.singleOrNull()?.takeIf { usbPresent && unnamedPanel(it) }
    }

    private fun unnamedPanel(d: Display): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val info = d.deviceProductInfo ?: return false
        return info.name.isNullOrBlank()
    }

    private fun displayName(d: Display): String? {
        if (Build.VERSION.SDK_INT >= 31) d.deviceProductInfo?.name?.takeIf { it.isNotBlank() }?.let { return it }
        return d.name
    }

    private fun askPermission(device: UsbDevice) {
        asked = true
        publish { it.copy(permission = Permission.ASKING) }
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(app, 0, Intent(ACTION_PERMISSION).setPackage(app.packageName), flags)
        runCatching { usb.requestPermission(device, pi) }.onFailure { Log.w(TAG, "requestPermission", it) }
    }

    private fun openLink(device: UsbDevice) {
        val generation = ++linkGeneration
        val l = VitureLink.open(usb, device, linkListener(generation)) ?: run {
            linkGeneration++
            Log.w(TAG, "could not open ${device.deviceName}")
            return
        }
        link = l
        linkDevice = device
        glassesMode = 0
        autoModeSent = false
        Log.i(TAG, "opened ${model?.name} pid=0x%04X imu=%s".format(device.productId, l.hasImu))
        publish { it.copy(connected = true) }
        listOf(VitureProtocol.MSG_GET_BRIGHTNESS, VitureProtocol.MSG_GET_VOLUME, VitureProtocol.MSG_GET_DISPLAY).forEachIndexed { i, msg ->
            handler.postDelayed({ if (link === l) l.send(msg) }, VitureLink.COMMAND_GAP_MS * (i + 1))
        }
        handler.postDelayed({
            if (link !== l) return@postDelayed
            _settings.value.sunblock?.let { sendFilm(it) }
            updateImu()
            applyMode(force = false)
        }, 300)
    }

    private fun closeLink() {
        val l = link ?: return
        linkGeneration++
        link = null
        linkDevice = null
        glassesMode = 0
        l.close()
        publish { it.copy(connected = false, tracking = false) }
        Log.i(TAG, "link closed")
    }

    private fun rememberSbs(support: Int) {
        if (_state.value.sbsSupport == support) return
        publish { it.copy(sbsSupport = support) }
    }

    private fun applyMode(force: Boolean) {
        val s = _state.value
        val m = model ?: return
        if (!s.canControl) return
        if (modeState.restorePending) {
            sendMode(false)
            return
        }
        if (!s.usable || s.sbs || modeState.pendingStereo) return
        val hz = _settings.value.refreshHz
        if (glassesMode == m.mode(hz, false)) return
        if (!force) {
            if (autoModeSent || SystemClock.uptimeMillis() - lastModeSentAt < 8_000) return
            if (glassesMode == 0 && abs(s.hz - hz) < 1.5f) return
            autoModeSent = true
        }
        sendMode(false)
    }

    private fun sendMode(sbs: Boolean): Boolean {
        val m = model ?: return false
        val g = _settings.value
        val mode = m.mode(if (sbs) g.sbsHz else g.refreshHz, sbs)
        if (link?.send(VitureProtocol.MSG_DISPLAY, byteArrayOf(mode.toByte())) == true) {
            lastModeSentAt = SystemClock.uptimeMillis()
            modeState.sent(sbs, lastModeSentAt)
            publish { it.copy(switching3d = modeState.pendingStereo) }
            scheduleModeCheck()
            return true
        }
        return false
    }

    private fun scheduleModeCheck() {
        handler.removeCallbacks(confirmMode)
        modeState.nextCheckAt?.let { at ->
            handler.postDelayed(confirmMode, (at - SystemClock.uptimeMillis()).coerceAtLeast(0))
        }
    }

    private fun sendFilm(on: Boolean) {
        if (!_state.value.canControl) return
        if (link?.send(VitureProtocol.MSG_FILM, byteArrayOf(if (on) 1 else 0)) == true) publish { it.copy(sunblock = on) }
    }

    private fun updateImu() {
        val l = link ?: return
        val want = (sink != null || probe != null) && l.hasImu
        if (want == _state.value.tracking) return
        l.send(VitureProtocol.MSG_IMU, byteArrayOf(if (want) 1 else 0))
        if (want) l.send(VitureProtocol.MSG_IMU_RATE, byteArrayOf(VitureProtocol.IMU_RATE_240.toByte()))
        publish { it.copy(tracking = want) }
    }

    private fun linkListener(generation: Long) = object : VitureLink.Listener {
        override fun onFrame(frame: VitureProtocol.Frame) = post {
            if (generation != linkGeneration) return@post
            val line = "%s 0x%04X %s".format(
                when (frame.magic) { VitureProtocol.MAGIC_REPORT -> "FD"; VitureProtocol.MAGIC_COMMAND -> "FE"; else -> "FC" },
                frame.msgId, frame.payload.joinToString("") { "%02X".format(it) },
            )
            synchronized(frames) {
                frames.addLast(line)
                while (frames.size > 64) frames.removeFirst()
            }
            Log.i(TAG, "frame $line")
            onReport(frame)
        }

        override fun onImu(payload: ByteArray, timeNs: Long) {
            if (generation != linkGeneration) return
            imuPackets++
            probe?.invoke(payload)
            if (simulating.get() != null) return
            VitureProtocol.imuQuaternion(payload)?.let { deliver(it, timeNs) }
        }

        override fun onLost() = post {
            if (generation != linkGeneration) return@post
            closeLink()
            refresh()
        }
    }

    private fun deliver(q: FloatArray, timeNs: Long) {
        latest = q
        sink?.onPose(q[0], q[1], q[2], q[3], timeNs)
    }

    private fun onReport(frame: VitureProtocol.Frame) {
        val p = frame.payload
        val event = p.firstOrNull()?.toInt()?.and(0xFF) ?: return
        val answer = if (p.size >= 2 && event == 0) p[1].toInt() and 0xFF else null
        when (frame.msgId) {
            VitureProtocol.EVENT_DISPLAY -> {
                Log.i(TAG, "glasses switched to mode 0x%02X".format(event))
                glassesMode = event
            }
            VitureProtocol.EVENT_DISPLAY_STEP -> model?.modeForStep(event)?.let { mode ->
                Log.i(TAG, "glasses report mode 0x%02X".format(mode))
                glassesMode = mode
            }
            VitureProtocol.EVENT_BRIGHTNESS -> publish { it.copy(brightness = event) }
            VitureProtocol.EVENT_VOLUME -> publish { it.copy(volume = event) }
            VitureProtocol.MSG_GET_BRIGHTNESS, VitureProtocol.MSG_BRIGHTNESS -> answer?.let { v -> publish { it.copy(brightness = v) } }
            VitureProtocol.MSG_GET_VOLUME, VitureProtocol.MSG_VOLUME -> answer?.let { v -> publish { it.copy(volume = v) } }
            VitureProtocol.MSG_DISPLAY -> if (event != 0) {
                Log.w(TAG, "glasses refused display mode 0x%02X".format(p.getOrNull(1)?.toInt()?.and(0xFF) ?: 0))
                modeState.cancelPending()
                publish { it.copy(switching3d = false) }
                scheduleModeCheck()
            } else answer?.let { glassesMode = it }
            VitureProtocol.MSG_GET_DISPLAY -> answer?.let { v ->
                glassesMode = v
            }
        }
    }

    fun sampleImu(ms: Long): JSONObject {
        val raw = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val counter = java.util.concurrent.atomic.AtomicInteger()
        val began = SystemClock.elapsedRealtime()
        probe = { payload -> val n = counter.incrementAndGet(); if (raw.size < 4 || n % 60 == 0) raw.add(payload.copyOf()) }
        post { updateImu() }
        SystemClock.sleep(ms)
        probe = null
        post { updateImu() }
        val seconds = (SystemClock.elapsedRealtime() - began) / 1000.0
        val count = counter.get()
        val samples = JSONArray()
        synchronized(raw) {
            raw.forEach { b ->
                val q = VitureProtocol.imuQuaternion(b)
                samples.put(JSONObject()
                    .put("hex", b.joinToString("") { "%02X".format(it) })
                    .put("euler", JSONArray().put(VitureProtocol.beFloat(b, 0).toDouble()).put(VitureProtocol.beFloat(b, 4).toDouble()).put(VitureProtocol.beFloat(b, 8).toDouble()))
                    .put("quat", q?.let { JSONArray().put(it[0].toDouble()).put(it[1].toDouble()).put(it[2].toDouble()).put(it[3].toDouble()) } ?: JSONObject.NULL))
            }
        }
        return JSONObject().put("packets", count).put("rateHz", count / seconds).put("samples", samples)
    }

    private fun Intent.device(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun load(): GlassesSettings {
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return GlassesSettings(
            refreshHz = p.getInt("refreshHz", 120),
            sbsHz = p.getInt("sbsHz", 60),
            sunblock = if (p.contains("sunblock")) p.getBoolean("sunblock", false) else null,
            gameOnGlasses = p.getBoolean("gameOnGlasses", true),
            headTracking = p.getBoolean("headTracking", true),
            auto3d = p.getBoolean("auto3d", true),
            picture3d = p.getInt("picture3d", GlassesSettings.PICTURE_AUTO),
            screenWidthDm = p.getInt("screenWidthDm", 20),
            screenDistanceDm = p.getInt("screenDistanceDm", 30),
            vrFovDeg = p.getInt("vrFovDeg", GlassesSettings.VR_FOV_DEFAULT).coerceIn(GlassesSettings.VR_FOV_MIN, GlassesSettings.VR_FOV_MAX),
        )
    }

    private fun save(s: GlassesSettings) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putInt("refreshHz", s.refreshHz)
            putInt("sbsHz", s.sbsHz)
            s.sunblock?.let { putBoolean("sunblock", it) } ?: remove("sunblock")
            putBoolean("gameOnGlasses", s.gameOnGlasses)
            putBoolean("headTracking", s.headTracking)
            putBoolean("auto3d", s.auto3d)
            putInt("picture3d", s.picture3d)
            putInt("screenWidthDm", s.screenWidthDm)
            putInt("screenDistanceDm", s.screenDistanceDm)
            putInt("vrFovDeg", s.vrFovDeg)
        }.apply()
    }
}
