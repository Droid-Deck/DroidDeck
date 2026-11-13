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
import kotlin.math.roundToInt
import kotlin.math.sin
import com.droiddeck.launcher.R
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

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
    val hardwareControls: Boolean = true,
    val supportsDisplayModeControl: Boolean = true,
    val hasSunblock: Boolean = true,
    val controlError: String? = null,
    val tracking: Boolean = false,
    val diagonalFovDeg: Float = 46f,
    val noSignal: Boolean = false,
    val sbsSupport: Int = SBS_UNKNOWN,
    val sbs90: Boolean = false,
    val rates2d: List<Int> = listOf(60, 90, 120),
    val experimental: Boolean = false,
    val supportsScreen3dof: Boolean = false,
    val supports30Hz: Boolean = false,
    val supportsSpatial: Boolean = false,
    val resolutions: List<GlassesResolution> = emptyList(),
    val vrActive: Boolean = false,
    val vitureDetected: Boolean = false,
    val vitureSdkAvailable: Boolean = false,
    val viture: VitureDeviceState? = null,
) {
    val hasDisplay: Boolean get() = displayId != Display.INVALID_DISPLAY
    val usable: Boolean get() = hasDisplay && !noSignal
    val canControl: Boolean get() = connected && controllable
    val spatialConnected: Boolean get() = canControl && supportsSpatial
    fun usesSpatialControllers(settings: GlassesSettings) = spatialConnected && settings.positionalTracking &&
        (settings.handControllers || settings.joyConTracking)
    val can3d: Boolean get() = canControl && supportsDisplayModeControl && !noSignal && sbsSupport != SBS_FAILS
    val verticalFovDeg: Float get() {
        val half = Math.toRadians(diagonalFovDeg / 2.0)
        val aspect = if (width > 0 && height > 0)
            width.toDouble() / (if (sbs) 2 else 1) / height else 16.0 / 9.0
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
    val resolution2d: String = "",
    val resolution3d: String = "",
    val resolutionVr: String = "",
    val sunblock: Boolean? = null,
    val gameOnGlasses: Boolean = true,
    val headTracking: Boolean = true,
    val auto3d: Boolean = false,
    val picture3d: Int = PICTURE_AUTO,
    val screenWidthDm: Int = 20,
    val screenDistanceDm: Int = 30,
    val vrFovDeg: Int = VR_FOV_DEFAULT,
    val positionalTracking: Boolean = false,
    val handControllers: Boolean = false,
    val joyConTracking: Boolean = false,
    val steadyHandAim: Boolean = false,
    val eyeHeightCm: Int = 160,
) {
    val screen3dof: Boolean get() = picture3d == PICTURE_SCREEN && headTracking

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
    private const val KEY_SBS_FAILED = "sbsFailed"
    private const val SBS_CONFIRM_MS = 20_000L

    private val _state = MutableStateFlow(GlassesState())
    val state: StateFlow<GlassesState> = _state.asStateFlow()
    private val _settings = MutableStateFlow(GlassesSettings())
    val settings: StateFlow<GlassesSettings> = _settings.asStateFlow()
    private val _calibration = MutableStateFlow(GlassesCalibrationState())
    val calibration: StateFlow<GlassesCalibrationState> = _calibration.asStateFlow()
    private lateinit var calibrationStore: RayNeoCalibrationStore
    private var calibrationKey: String? = null
    private var calibrationRevision = 0L

    private lateinit var app: Context
    private lateinit var handler: Handler
    private lateinit var usb: UsbManager
    private lateinit var displays: DisplayManager
    @Volatile private var link: GlassesLink? = null
    @Volatile private var linkGeneration = 0L
    private var linkDevice: UsbDevice? = null
    private var model: GlassesModel? = null
    private var asked = false
    private var lastModeSentAt = 0L
    private val modeState = GlassesModeState(SBS_CONFIRM_MS)
    private val confirmMode = Runnable { refresh() }
    private val steamVrSpatialGate = SteamVrSpatialGate()
    private val resumeSteamVrSpatial = Runnable { updateSpatialMode() }
    private var glassesMode = 0
    private var autoModeSent = false
    private var nativeHostTracking: Boolean? = null
    private var vrNativeDof: Int? = null

    @Volatile private var sink: PoseSink? = null
    private val poseObservers = java.util.concurrent.CopyOnWriteArraySet<PoseSink>()
    @Volatile private var probe: ((ByteArray?, FloatArray?) -> Unit)? = null
    @Volatile private var latest: FloatArray? = null
    @Volatile private var imuPackets = 0L
    private val simulating = java.util.concurrent.atomic.AtomicReference<Thread?>(null)
    private val frames = ArrayDeque<String>()

    fun init(context: Context) {
        if (::handler.isInitialized) return
        app = context.applicationContext
        SpatialTracking.initialize(app)
        usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        displays = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        _settings.value = load()
        calibrationStore = RayNeoCalibrationStore(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        handler = Handler(HandlerThread("droiddeck-glasses").apply { start() }.looper)
        handler.post {
            runCatching {
                VitureSdkImport.installedDirectory(app)?.let(VitureSdk::loadImported)
            }.onFailure { Log.w(TAG, "Could not restore imported VITURE SDK", it) }
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

    internal suspend fun installVitureSdk(prepared: VitureSdkImport.Prepared): Unit = suspendCoroutine { result ->
        if (!::handler.isInitialized) {
            result.resumeWithException(IllegalStateException("Glasses are not initialized"))
        } else if (!handler.post {
            try {
                check(!VitureSdk.available) { "VITURE SDK is already active" }
                prepared.install(VitureSdk::loadImported)
                publish { it.copy(vitureSdkAvailable = true, controlError = null) }
                runCatching {
                    if (linkDevice?.vendorId == VitureProtocol.VENDOR_ID) closeLink()
                    refresh()
                }.onFailure {
                    Log.w(TAG, "SDK imported but glasses reconnection failed", it)
                    publish { state -> state.copy(controlError = app.getString(R.string.glasses_connection_failed)) }
                }
                result.resume(Unit)
            } catch (error: Exception) {
                result.resumeWithException(error)
            }
        }) {
            result.resumeWithException(IllegalStateException("Glasses service is unavailable"))
        }
    }

    fun startCalibration() = post {
        val l = link as? RayNeoLink ?: return@post
        if (!l.ready || _calibration.value.running || simulating.get() != null) return@post
        _calibration.value = _calibration.value.copy(phase = CalibrationPhase.SETTLING, progress = 0)
        l.startCalibration(++calibrationRevision)
        updateImu()
    }

    fun cancelCalibration() = post {
        (link as? RayNeoLink)?.cancelCalibration(++calibrationRevision, calibrationKey?.let(calibrationStore::load))
        _calibration.value = _calibration.value.copy(phase = CalibrationPhase.IDLE, progress = 0)
        updateImu()
    }

    fun resetCalibration() = post {
        (link as? RayNeoLink)?.resetCalibration(++calibrationRevision)
        calibrationKey?.let { calibrationStore.save(it, null) }
        _calibration.value = _calibration.value.copy(saved = false, phase = CalibrationPhase.IDLE, progress = 0)
        updateImu()
    }

    fun query() = post {
        val l = link ?: return@post
        listOf(VitureProtocol.MSG_GET_DISPLAY, VitureProtocol.MSG_GET_BRIGHTNESS, VitureProtocol.MSG_GET_VOLUME, VitureProtocol.MSG_GET_IMU).forEachIndexed { i, msg ->
            handler.postDelayed({ if (link === l) l.send(msg) }, VitureLink.COMMAND_GAP_MS * i)
        }
    }

    fun sendMode(mode: Int) = post {
        if (!_state.value.canControl || !_state.value.supportsDisplayModeControl) return@post
        if (link?.send(VitureProtocol.MSG_DISPLAY, byteArrayOf(mode.toByte())) == true) lastModeSentAt = SystemClock.uptimeMillis()
    }

    fun requestControl() = post {
        asked = false
        refresh(ask = true)
    }

    fun setRefreshHz(hz: Int) = post {
        if (hz == 30 && (_state.value.sbs || !GlassesRendering.native30HzAvailable(_settings.value, _state.value))) return@post
        update { it.copy(refreshHz = if (hz == 30) 30 else hz.coerceIn(60, 120)) }
        if (!_state.value.sbs) {
            if (_state.value.viture?.supportsNative == true) sendMode(false, preserveDisplayShape = true) else applyMode(force = true)
        }
    }

    fun setSbsHz(hz: Int) = post {
        if (hz == 30 && (!_state.value.sbs || !GlassesRendering.native30HzAvailable(_settings.value, _state.value))) return@post
        update { it.copy(sbsHz = when { hz == 30 -> 30; hz >= 90 -> 90; else -> 60 }) }
        if (hz != 30 && _state.value.sbs && _state.value.can3d) sendMode(true, preserveDisplayShape = true)
    }

    fun setResolution(profile: GlassesDisplayProfile, value: String) = post {
        val s = _state.value
        val choice = if (value == "auto") "" else value
        if (choice.isNotEmpty() && s.resolutions.none { it.key == choice }) return@post
        if (GlassesDisplayModes.choice(_settings.value, profile) == choice) return@post
        update { when (profile) {
            GlassesDisplayProfile.TWO_D -> it.copy(resolution2d = choice)
            GlassesDisplayProfile.THREE_D -> it.copy(resolution3d = choice)
            GlassesDisplayProfile.VR -> it.copy(resolutionVr = choice)
        } }
        val active = GlassesDisplayModes.profile(s.sbs, s.vrActive)
        val inheritedByVr = s.vrActive && _settings.value.resolutionVr.isEmpty() &&
            profile == GlassesDisplayModes.profile(s.sbs, false)
        if ((profile == active || inheritedByVr) && s.canControl && !s.switching3d &&
            model?.resolutions?.isNotEmpty() == true) sendMode(s.sbs)
    }

    fun prepareSteamVrStart(onPrepared: () -> Unit = {}) {
        if (!::handler.isInitialized) {
            steamVrSpatialGate.prepare()
            onPrepared()
            return
        }
        post {
            if (steamVrSpatialGate.prepare()) {
                handler.removeCallbacks(resumeSteamVrSpatial)
                updateSpatialMode()
            }
            onPrepared()
        }
    }

    fun cancelSteamVrStart() {
        if (!::handler.isInitialized) {
            steamVrSpatialGate.cancel()
            return
        }
        post {
            steamVrSpatialGate.cancel()
            handler.removeCallbacks(resumeSteamVrSpatial)
            updateSpatialMode()
        }
    }

    fun setVrActive(on: Boolean) = post {
        val s = _state.value
        if (on && s.viture?.nativeTracking == true) {
            if (!useHostTracking()) return@post
            vrNativeDof = s.viture.nativeDof
        }
        val trackingChanged = steamVrSpatialGate.setRunning(on, SystemClock.uptimeMillis())
        if (trackingChanged) {
            handler.removeCallbacks(resumeSteamVrSpatial)
            steamVrSpatialGate.deadline?.let {
                Log.i(TAG, "SteamVR RUNNING; spatial resume scheduled at uptime ${it}ms")
                handler.postAtTime(resumeSteamVrSpatial, it)
            }
        }
        if (s.vrActive == on) {
            if (trackingChanged) updateSpatialMode()
            return@post
        }
        val previous = GlassesDisplayModes.selected(_settings.value, s)
        publish { it.copy(vrActive = on) }
        if (!on) vrNativeDof?.let {
            vrNativeDof = null
            controlViture(VitureSdk.CONTROL_NATIVE_DOF, it.toFloat())
        }
        val restore2d = !on && modeState.stopVr()
        val stereo = (s.sbs || modeState.pendingStereo) && !restore2d
        val selected = GlassesDisplayModes.selected(_settings.value, _state.value, stereo)
        if (s.canControl && s.usable && (restore2d ||
            (previous != selected && model?.resolutions?.isNotEmpty() == true)) &&
            !(on && _settings.value.auto3d && !s.sbs)) sendMode(stereo)
        updateSpatialMode()
    }

    fun set3d(on: Boolean) = post {
        val s = _state.value
        if (!s.canControl || (on && s.noSignal)) return@post
        modeState.manual()
        if (on) {
            markSbsFailed(linkDevice?.productId, false)
            rememberSbs(GlassesState.SBS_UNKNOWN)
        }
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
        if (!s.canControl || s.brightnessMax == 0) return@post
        val v = level.coerceIn(0, s.brightnessMax)
        if (link?.send(VitureProtocol.MSG_BRIGHTNESS, byteArrayOf(v.toByte(), 0)) == true && link !is XrealLink)
            publish { it.copy(brightness = v) }
    }

    fun setVolume(level: Int) = post {
        val s = _state.value
        if (!s.canControl || s.volumeMax == 0) return@post
        val v = level.coerceIn(0, s.volumeMax)
        if (link?.send(VitureProtocol.MSG_VOLUME, byteArrayOf(v.toByte(), 0)) == true) publish { it.copy(volume = v) }
    }

    fun setSunblock(on: Boolean) = post {
        update { it.copy(sunblock = on) }
        sendFilm(on)
    }

    fun setVitureTint(level: Int) = post {
        val maximum = _state.value.viture?.filmMax?.takeIf { it > 0 } ?: return@post
        controlViture(VitureSdk.CONTROL_FILM, level.coerceIn(0, maximum).toFloat() / maximum)
    }

    internal fun setVitureControl(command: Int, value: Float = 0f, extra: Int = 0) = post {
        controlViture(command, value, extra)
    }

    fun setVitureNativeMode(native: Boolean) = post {
        val tall = _state.value.height == 1200
        controlViture(VitureSdk.CONTROL_NATIVE_MODE, if (native) 1f else 0f,
            if (native) { if (tall) 0x34 else 0x31 } else { if (tall) 0x41 else 0x31 })
    }

    fun setVitureDisplayMode(mode: Int) = post {
        val current = _state.value.viture ?: return@post
        if (current.displayModes.none { it.code == mode }) return@post
        if (current.nativeMode == 1) controlViture(VitureSdk.CONTROL_NATIVE_DISPLAY, mode.toFloat())
        else {
            val sdk = link as? VitureSdkLink ?: return@post
            val accepted = sdk.send(VitureProtocol.MSG_DISPLAY, byteArrayOf(mode.toByte()))
            if (accepted) {
                modeState.manualDisplayAccepted()
                readVitureDevice(sdk, current.copy(displaySelected = true, error = null))
                refresh()
            } else vitureError(-7)
        }
    }

    fun saveVitureDefaultDisplay() = post {
        val mode = (link as? VitureSdkLink)?.deviceDisplayMode() ?: return@post
        if (mode >= 0) controlViture(VitureSdk.CONTROL_DEFAULT_DISPLAY, mode.toFloat()) else vitureError(mode)
    }

    private fun vitureError(code: Int) = publish {
        it.copy(viture = it.viture?.copy(busy = false, error = app.getString(R.string.viture_control_failed, code)))
    }

    private fun controlViture(command: Int, value: Float, extra: Int = 0): Boolean {
        val sdk = link as? VitureDeviceLink ?: return false
        val old = _state.value.viture ?: return false
        if (!value.isFinite()) return false
        if (_state.value.vrActive && command == VitureSdk.CONTROL_NATIVE_DOF && value > 0) {
            vitureError(-10)
            return false
        }
        publish { it.copy(viture = old.copy(busy = true, error = null)) }
        val originReset = command == VitureSdk.CONTROL_CARINA_RESET || command == VitureSdk.CONTROL_CARINA_RECENTER
        val pause = originReset || command == VitureSdk.CONTROL_NATIVE_MODE || command == VitureSdk.CONTROL_NATIVE_DOF
        if (pause && sdk.trackingEnabled) {
            val paused = sdk.send(VitureProtocol.MSG_IMU, byteArrayOf(0))
            publish { it.copy(tracking = sdk.trackingEnabled) }
            if (!paused) {
                readVitureDevice(sdk, old.copy(busy = false))
                vitureError(-10)
                return false
            }
        }
        if (originReset) SpatialTracking.invalidateOrigin(_settings.value.eyeHeightCm / 100f)
        var rc = sdk.deviceControl(command, value, extra)
        if (rc == 0 && command == VitureSdk.CONTROL_NATIVE_MODE && value == 1f && !old.nativeTracking) {
            rc = sdk.deviceControl(VitureSdk.CONTROL_NATIVE_DOF, 0f)
        }
        if (rc < 0 && pause && old.supportsNative && !old.nativeTracking &&
            sdk.deviceValue(VitureSdk.VALUE_NATIVE_MODE) == 1f) {
            sdk.deviceControl(VitureSdk.CONTROL_NATIVE_DOF, 0f)
        }
        if (rc == 0) {
            if (command == VitureSdk.CONTROL_NATIVE_MODE && value == 0f) vrNativeDof = null
            val next = when (command) {
                VitureSdk.CONTROL_DISPLAY_BUTTON -> old.copy(displayButtonEnabled = value != 0f)
                VitureSdk.CONTROL_DEFAULT_DISPLAY -> old.copy(defaultDisplay = value.toInt())
                VitureSdk.CONTROL_CARINA_AUTO_EXPOSURE -> old.copy(automaticExposure = true)
                VitureSdk.CONTROL_CARINA_MANUAL_EXPOSURE -> old.copy(automaticExposure = false,
                    exposureMs = value.coerceIn(0.01f, 8f), exposureGain = extra.coerceIn(0, 15))
                VitureSdk.CONTROL_NATIVE_MODE, VitureSdk.CONTROL_NATIVE_DISPLAY -> old.copy(displaySelected = true)
                else -> old
            }
            readVitureDevice(sdk, next.copy(busy = false, error = null))
            if (command == VitureSdk.CONTROL_FILM) update { it.copy(sunblock = null) }
            if (originReset) SpatialTracking.recenter(false)
            if (command == VitureSdk.CONTROL_NATIVE_MODE || command == VitureSdk.CONTROL_NATIVE_DISPLAY) {
                modeState.manualDisplayAccepted()
                refresh()
            }
        } else {
            readVitureDevice(sdk, old.copy(busy = false))
            vitureError(rc)
        }
        updateImu()
        return rc == 0
    }

    private fun readVitureDevice(sdk: VitureDeviceLink, previous: VitureDeviceState? = _state.value.viture) {
        val device = VitureDeviceState.read(sdk, previous, (model as? VitureProtocol.Model)?.family)
        if (link !== sdk) return
        publish { it.copy(viture = device, brightnessMax = device.brightnessMax ?: it.brightnessMax,
            volumeMax = device.volumeMax ?: it.volumeMax, hasSunblock = (device.filmMax ?: 0) > 0,
            sbs = isStereoDisplay(it.width, device),
            sunblock = device.film?.let { voltage -> voltage > 0 } ?: it.sunblock) }
        syncVitureTracking(device)
    }

    private fun syncVitureTracking(device: VitureDeviceState) {
        if (!device.nativeTrackingKnown) return
        if (device.nativeTracking) {
            if (nativeHostTracking == null) nativeHostTracking = _settings.value.headTracking
            if (_settings.value.headTracking) update { it.copy(headTracking = false) }
        } else nativeHostTracking?.let { saved ->
            nativeHostTracking = null
            update { it.copy(headTracking = saved) }
        }
        updateImu()
    }

    private fun useHostTracking(): Boolean {
        if (_state.value.viture?.nativeTracking != true) return true
        return controlViture(VitureSdk.CONTROL_NATIVE_DOF, 0f)
    }

    fun setSbsSupport(support: Int) = post {
        val s = support.coerceIn(GlassesState.SBS_FAILS, GlassesState.SBS_WORKS)
        markSbsFailed(linkDevice?.productId, s == GlassesState.SBS_FAILS)
        rememberSbs(s)
    }
    fun setGameOnGlasses(on: Boolean) = post {
        val s = _state.value
        if (!on && s.canControl && (s.sbs || modeState.pendingStereo)) {
            modeState.manual()
            sendMode(false)
        }
        update {
            it.copy(
                gameOnGlasses = on,
                picture3d = if (!on && it.picture3d == GlassesSettings.PICTURE_SCREEN)
                    GlassesSettings.PICTURE_AUTO else it.picture3d,
            )
        }
    }
    fun set3dof(on: Boolean) = post {
        if (!_state.value.supportsScreen3dof) return@post
        if (on && !useHostTracking()) return@post
        update {
            it.copy(
                gameOnGlasses = on || it.gameOnGlasses,
                headTracking = on || it.headTracking,
                picture3d = if (on) GlassesSettings.PICTURE_SCREEN
                    else if (it.picture3d == GlassesSettings.PICTURE_SCREEN) GlassesSettings.PICTURE_AUTO else it.picture3d,
            )
        }
    }
    fun setPositionalTracking(on: Boolean) = post {
        if (!_state.value.spatialConnected || _settings.value.positionalTracking == on) return@post
        update { it.copy(positionalTracking = on, handControllers = it.handControllers && on,
            joyConTracking = it.joyConTracking && on) }
        if (!on) SpatialTracking.setControllers(false, false)
        closeLink()
        refresh()
    }
    fun setHandControllers(on: Boolean) = post {
        val wanted = on && _settings.value.positionalTracking && _state.value.spatialConnected
        update { it.copy(handControllers = wanted, joyConTracking = it.joyConTracking && !wanted) }
        SpatialTracking.setControllers(wanted, _settings.value.joyConTracking)
    }
    fun setSteadyHandAim(on: Boolean) = post {
        update { it.copy(steadyHandAim = on) }
        SpatialTracking.setSteadyHandAim(on)
    }
    fun setJoyConTracking(on: Boolean) = post {
        val wanted = on && _settings.value.positionalTracking && _state.value.spatialConnected
        update { it.copy(joyConTracking = wanted, handControllers = it.handControllers && !wanted) }
        SpatialTracking.setControllers(_settings.value.handControllers, wanted)
    }
    fun setEyeHeight(cm: Int) = post { update { it.copy(eyeHeightCm = cm.coerceIn(50, 250)) } }
    fun setSpatialFloor() = SpatialTracking.setFloor(_settings.value.eyeHeightCm / 100f)
    fun restartSpatialTracking() = post {
        if (_state.value.spatialConnected && _settings.value.positionalTracking) { closeLink(); refresh() }
    }

    fun setHeadTracking(on: Boolean) = post {
        if (on && !useHostTracking()) return@post
        update { it.copy(headTracking = on) }
        updateImu()
    }
    fun setAuto3d(on: Boolean) = post { update { it.copy(auto3d = on) } }
    fun setPicture3d(picture: Int) = post { update { it.copy(picture3d = picture.coerceIn(0, 2)) } }
    fun setScreenWidthDm(dm: Int) = post { update { it.copy(screenWidthDm = dm.coerceIn(5, 200)) } }
    fun setScreenDistanceDm(dm: Int) = post { update { it.copy(screenDistanceDm = dm.coerceIn(5, 200)) } }
    fun setVrFovDeg(deg: Int) = post { update { it.copy(vrFovDeg = deg.coerceIn(GlassesSettings.VR_FOV_MIN, GlassesSettings.VR_FOV_MAX)) } }

    fun outputHz(): Int = GlassesRendering.outputHz(_settings.value, _state.value)
    fun outputResolution(): GlassesResolution? = _state.value.viture?.takeIf { it.supportsNative }
        ?.selectedDisplayMode?.presentationResolution ?: GlassesDisplayModes.selected(_settings.value, _state.value)

    fun glassesDisplay(): Display? = _state.value.displayId.takeIf { it != Display.INVALID_DISPLAY }?.let { displays.getDisplay(it) }

    fun setPoseSink(s: PoseSink) {
        synchronized(this) { sink = s }
        post { updateImu() }
    }

    fun clearPoseSink(s: PoseSink) {
        synchronized(this) { if (sink === s) sink = null }
        post { updateImu() }
    }

    internal fun addPoseObserver(observer: PoseSink) {
        poseObservers.add(observer)
        post { updateImu() }
    }

    internal fun removePoseObserver(observer: PoseSink) {
        poseObservers.remove(observer)
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
            .put("controlError", s.controlError ?: JSONObject.NULL)
            .put("experimental", s.experimental)
            .put("supportsScreen3dof", s.supportsScreen3dof)
            .put("supportsDisplayModeControl", s.supportsDisplayModeControl)
            .put("supportsSpatial", s.supportsSpatial)
            .put("vitureDetected", s.vitureDetected).put("vitureSdkAvailable", s.vitureSdkAvailable)
            .put("viture", s.viture?.let { device ->
                JSONObject().put("capabilities", device.capabilities)
                    .put("brightnessMax", device.brightnessMax ?: JSONObject.NULL)
                    .put("volumeMax", device.volumeMax ?: JSONObject.NULL)
                    .put("filmMax", device.filmMax ?: JSONObject.NULL).put("filmLevel", device.filmLevel ?: JSONObject.NULL)
                    .put("dutyCycle", device.dutyCycle ?: JSONObject.NULL).put("worn", device.worn ?: JSONObject.NULL)
                    .put("nativeMode", device.nativeMode ?: JSONObject.NULL).put("nativeDof", device.nativeDof ?: JSONObject.NULL)
                    .put("displayMode", device.displayMode ?: JSONObject.NULL).put("ultrawide", device.ultrawide)
                    .put("displayDistance", device.distance ?: JSONObject.NULL).put("displaySize", device.size ?: JSONObject.NULL)
                    .put("sideMode", device.side ?: JSONObject.NULL).put("imuRate", device.imuRate ?: JSONObject.NULL)
                    .put("imuRateMask", device.imuRateMask).put("cameraVid", device.cameraVid).put("cameraPid", device.cameraPid)
                    .put("automaticExposure", device.automaticExposure).put("exposureMs", device.exposureMs.toDouble())
                    .put("exposureGain", device.exposureGain).put("busy", device.busy).put("error", device.error ?: JSONObject.NULL)
            } ?: JSONObject.NULL)
            .put("firmware", (link as? VitureSdkLink)?.firmware ?: JSONObject.NULL)
            .put("spatialSdk", (link as? VitureSdkLink)?.spatialDiagnostics()?.let {
                JSONObject().put("status",it[0]).put("callbackAgeMs",it[1]).put("cameraPoseDeltaMs",it[2])
            } ?: JSONObject.NULL)
            .put("spatial", SpatialTracking.diagnosticStatus())
            .put("supports30Hz", s.supports30Hz).put("vrActive", s.vrActive)
            .put("resolutions", JSONArray(s.resolutions.map { it.key }))
            .put("display", if (s.hasDisplay) JSONObject().put("id", s.displayId).put("width", s.width).put("height", s.height).put("hz", s.hz.toDouble()) else JSONObject.NULL)
            .put("sbs", s.sbs).put("deviceMode", glassesMode).put("switching3d", s.switching3d)
            .put("brightness", s.brightness).put("volume", s.volume)
            .put("brightnessMax", s.brightnessMax).put("volumeMax", s.volumeMax).put("sunblock", s.sunblock)
            .put("tracking", s.tracking).put("simulating", simulating.get() != null).put("imuPackets", imuPackets)
            .put("calibration", JSONObject().put("supported", _calibration.value.supported)
                .put("saved", _calibration.value.saved).put("phase", _calibration.value.phase.name)
                .put("progress", _calibration.value.progress))
            .put("badFrames", link?.badFrames ?: 0L)
            .put("pose", q?.let { JSONArray().put(it[0].toDouble()).put(it[1].toDouble()).put(it[2].toDouble()).put(it[3].toDouble()) } ?: JSONObject.NULL)
            .put("fovDeg", s.diagonalFovDeg.toDouble()).put("noSignal", s.noSignal).put("sbsSupport", s.sbsSupport)
            .put("settings", JSONObject().put("refreshHz", g.refreshHz).put("sbsHz", g.sbsHz).put("sunblock", g.sunblock ?: JSONObject.NULL)
                .put("gameOnGlasses", g.gameOnGlasses).put("headTracking", g.headTracking).put("auto3d", g.auto3d)
                .put("screen3dof", g.screen3dof)
                .put("resolution2d", g.resolution2d).put("resolution3d", g.resolution3d).put("resolutionVr", g.resolutionVr)
                .put("positionalTracking", g.positionalTracking).put("handControllers", g.handControllers)
                .put("joyConTracking", g.joyConTracking).put("eyeHeightCm", g.eyeHeightCm)
                .put("steadyHandAim", g.steadyHandAim)
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
        save(next.copy(headTracking = nativeHostTracking ?: next.headTracking))
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
                    if (intent.device()?.let { isGlassesVendor(it.vendorId) } == true) asked = false
                    refresh(ask = true)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (intent.device()?.let { isGlassesVendor(it.vendorId) } == true) {
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
        val devices = usb.deviceList.values.filter {
            it.vendorId == VitureProtocol.VENDOR_ID || deviceModel(it) != null
        }
        val known = devices.mapNotNull { device ->
            deviceModel(device)?.let { device to it }
        }
        val namedViture = devices.firstOrNull { device ->
            device.vendorId == VitureProtocol.VENDOR_ID &&
                VitureProtocol.isGlassesProductName(runCatching { device.productName }.getOrNull())
        }
        val selected = known.firstOrNull { canOpenModel(it.second) } ?: known.firstOrNull()
        val device = selected?.first
        val m = selected?.second
        val controllable = m?.let(::canOpenModel) == true
        val permitted = controllable && device != null && (m === XrealModel || usb.hasPermission(device))
        val identity = device?.let {
            GlassesModeState.Device(it.productId, if (permitted) runCatching { it.serialNumber }.getOrNull() else null)
        }
        if (modeState.updateDevice(identity, SystemClock.uptimeMillis())) {
            rememberSbs(if (identity != null && sbsFailed(identity.productId)) GlassesState.SBS_FAILS else GlassesState.SBS_UNKNOWN)
        }
        if (device == null || (linkDevice != null && linkDevice?.deviceName != device.deviceName)) closeLink()
        model = m
        val display = findDisplay(devices.isNotEmpty())
        val mode = display?.mode
        val width = mode?.physicalWidth ?: 0
        val height = mode?.physicalHeight ?: 0
        if (controllable && device != null && !permitted && (ask || !asked) && _state.value.permission != Permission.ASKING) askPermission(device)
        if (device != null && permitted && link == null) openLink(device)
        val sbsByDisplay = isStereoDisplay(width, _state.value.viture)
        val noSignal = display != null && width in 1 until 1280
        when (modeState.observe(sbsByDisplay, SystemClock.uptimeMillis())) {
            GlassesModeState.Confirmation.FAILED -> {
                Log.w(TAG, "3D signal was not confirmed at ${width}x$height; restoring 2D")
                if (noSignal) markSbsFailed(device?.productId, true)
                rememberSbs(GlassesState.SBS_FAILS)
            }
            else -> Unit
        }
        if (sbsByDisplay) {
            markSbsFailed(device?.productId, false)
            rememberSbs(GlassesState.SBS_WORKS)
        }
        publish {
            it.copy(
                present = device != null || display != null || namedViture != null,
                vitureDetected = namedViture != null || known.any { it.first.vendorId == VitureProtocol.VENDOR_ID } ||
                    display?.let { output -> output.name.contains("VITURE", ignoreCase = true) ||
                        displayName(output)?.contains("VITURE", ignoreCase = true) == true } == true,
                vitureSdkAvailable = VitureSdk.available,
                model = m?.name ?: namedViture?.let { runCatching { it.productName }.getOrNull() }
                    ?: display?.let(::displayName) ?: "VITURE",
                controllable = controllable,
                permission = when {
                    !controllable -> Permission.NONE
                    permitted -> Permission.GRANTED
                    it.permission == Permission.ASKING || it.permission == Permission.DENIED -> it.permission
                    else -> Permission.NONE
                },
                connected = link?.ready == true,
                controlError = when {
                    m is VitureProtocol.Model && m.needsSdk && !VitureSdk.available -> app.getString(R.string.viture_sdk_required)
                    device == null || link?.ready == true -> null
                    else -> it.controlError
                },
                displayId = display?.displayId ?: Display.INVALID_DISPLAY,
                width = width, height = height, hz = mode?.refreshRate ?: 0f,
                sbs = sbsByDisplay,
                switching3d = modeState.pendingStereo,
                brightnessMax = it.viture?.brightnessMax ?: m?.brightnessMax ?: 8,
                volumeMax = it.viture?.volumeMax ?: m?.volumeMax ?: 8,
                sunblock = if (m === XrealModel || it.viture != null) it.sunblock else _settings.value.sunblock ?: it.sunblock,
                hardwareControls = m?.hardwareControls == true &&
                    (device?.vendorId == VitureProtocol.VENDOR_ID || m === XrealModel || m is RokidModel),
                supportsDisplayModeControl = m?.supportsDisplayModeControl == true,
                hasSunblock = it.viture?.let { device -> (device.filmMax ?: 0) > 0 } ?: (m?.hasSunblock == true),
                diagonalFovDeg = (link as? RayNeoLink)?.diagonalFovDeg ?: m?.diagonalFovDeg ?: 46f,
                tracking = it.tracking && link != null,
                noSignal = noSignal,
                sbs90 = m?.supportsSbs90(mode?.let { GlassesResolution(width / (if (sbsByDisplay) 2 else 1), height) }) == true,
                rates2d = displayRefreshRates(m, display),
                experimental = m === XrealModel || (m is VitureProtocol.Model && m.family == VitureProtocol.Family.PRO2 && !m.needsSdk),
                supportsScreen3dof = link?.hasImu == true,
                supports30Hz = display?.let(::supports30Hz) == true,
                supportsSpatial = (link as? VitureSdkLink)?.isCarina == true,
                resolutions = m?.resolutions?.takeIf { modes -> modes.isNotEmpty() }
                    ?: display?.supportedModes.orEmpty().filter { mode ->
                        mode.physicalWidth >= 1280 && (mode.physicalWidth >= 3000) == sbsByDisplay
                    }.map { mode -> GlassesResolution(mode.physicalWidth / (if (sbsByDisplay) 2 else 1), mode.physicalHeight) }
                        .distinct().sortedWith(compareBy({ size -> size.height }, { size -> size.width })),
            )
        }
        if (link != null) applyMode(force = false)
        scheduleModeCheck()
    }

    internal fun isGlassesVendor(vid: Int) = vid == VitureProtocol.VENDOR_ID || RayNeoModel.isVendor(vid) ||
        vid == XrealModel.VENDOR_ID || vid == RokidModel.VENDOR_ID

    internal fun isStereoDisplay(width: Int, device: VitureDeviceState?): Boolean = width >= 3000 &&
        if (device?.supportsNative == true) device.selectedDisplayMode?.stereo == true else device?.ultrawide != true

    internal fun supports30Hz(display: Display): Boolean {
        val current = display.mode
        return display.supportedModes.any {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight &&
                abs(it.refreshRate - 30f) < 0.5f
        }
    }

    internal fun displayRefreshRates(model: GlassesModel?, display: Display?): List<Int> {
        val fallback = model?.rates2d ?: listOf(60, 90, 120)
        if (model?.usesAndroidRefreshRates != true || display == null) return fallback
        val current = display.mode
        return display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }.mapNotNull { it.refreshRate.takeIf { rate -> rate.isFinite() && rate >= 59f }?.roundToInt() }
            .distinct().sorted().ifEmpty { listOf(60) }
    }

    private fun canOpenModel(m: GlassesModel): Boolean = m.controllable &&
        (m !is VitureProtocol.Model || !m.needsSdk || VitureSdk.available)

    internal fun deviceModel(device: UsbDevice): GlassesModel? {
        val name = runCatching { device.productName }.getOrNull()
        return if (device.vendorId == VitureProtocol.VENDOR_ID) {
            VitureSdk.marketName(device.productId)?.let(VitureProtocol::sdkModel)
                ?: VitureProtocol.model(device.productId, name)
        }
        else if (XrealModel.matches(device.vendorId, device.productId)) XrealModel
        else RokidModel.model(device.vendorId, device.productId, name)
            ?: RayNeoModel.model(device.vendorId, device.productId, name)
    }

    internal fun isGlassesDisplayName(name: String) =
        name.contains("VITURE", ignoreCase = true) || RayNeoModel.isDisplay(name) || XrealModel.isDisplay(name) ||
            RokidModel.isDisplay(name)

    private fun findDisplay(usbPresent: Boolean): Display? {
        return selectGlassesDisplay(displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).toList(), usbPresent)
    }

    internal fun selectGlassesDisplay(displays: List<Display>, usbPresent: Boolean): Display? {
        val candidates = displays
            .filter { it.displayId != Display.DEFAULT_DISPLAY && it.flags and Display.FLAG_PRESENTATION != 0 }
        return candidates.firstOrNull { display ->
            isGlassesDisplayName(display.name) ||
                (Build.VERSION.SDK_INT >= 31 && display.deviceProductInfo?.let { product ->
                    product.name?.let(::isGlassesDisplayName) == true ||
                        RayNeoModel.isDisplayProduct(product.name, product.manufacturerPnpId, product.productId)
                } == true)
        }
            ?: candidates.singleOrNull()?.takeIf { usbPresent && unnamedPanel(it) }
    }

    private fun unnamedPanel(d: Display): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return d.deviceProductInfo?.name.isNullOrBlank()
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

    private fun updateSpatialMode() {
        val current = link as? VitureSdkLink ?: return
        if (!current.ready || !current.isCarina || current.spatialEnabled ==
            steamVrSpatialGate.spatialEnabled(_settings.value.positionalTracking, SystemClock.uptimeMillis())) return
        val device = linkDevice
        closeLink()
        refresh()
        val resumed = link as? VitureSdkLink
        if (!current.spatialEnabled && resumed?.isCarina == true && resumed.spatialEnabled &&
            device != null && linkDevice?.deviceName == device.deviceName) SpatialTracking.recenter(false)
    }

    private fun openLink(device: UsbDevice) {
        val generation = ++linkGeneration
        val spatial = steamVrSpatialGate.spatialEnabled(_settings.value.positionalTracking, SystemClock.uptimeMillis())
        calibrationKey = calibrationStore.key(device.vendorId, device.productId,
            runCatching { device.serialNumber }.getOrNull(), model?.name.orEmpty())
        val l = if (device.vendorId == VitureProtocol.VENDOR_ID) {
            if (device.productId == VitureProtocol.PRO2_PRODUCT_ID && !VitureSdk.available) {
                VitureV2Link.open(usb, device, v2Listener(generation))
            } else if (VitureSdk.available) {
                Log.i(TAG, "opening VITURE SDK pid=0x%04X spatial=%s".format(device.productId, spatial))
                VitureSdkLink.open(usb, device, File(app.noBackupFilesDir, "viture"), sdkListener(generation),
                    (model as? VitureProtocol.Model)?.family, spatial)
            } else VitureLink.open(usb, device, linkListener(generation))
        } else if (model === XrealModel) {
            XrealLink.open(app, xrealListener(generation))
        } else if (model is RokidModel) {
            RokidLink.open(usb, device, rokidListener(generation))
        } else {
            val baseKey = calibrationKey!!
            val gtUsb = RayNeoProtocol.isGt(device.vendorId, device.productId)
            RayNeoLink.open(usb, device, rayNeoListener(generation)) { board ->
                calibrationStore.load(RayNeoCalibrationStore.profileKey(baseKey, gtUsb, board))
            }
        }
        val opened = l ?: run {
            linkGeneration++
            Log.w(TAG, "could not open ${device.deviceName}")
            publish { it.copy(controlError = app.getString(R.string.glasses_connection_failed)) }
            return
        }
        link = opened
        if (opened is VitureSdkLink && opened.isCarina) SpatialTracking.connect(opened.sdkToken, generation,
            opened.spatialIdentity ?: runCatching { device.serialNumber }.getOrNull()?.takeIf { it.isNotBlank() }
                ?.let { "${device.vendorId}:${device.productId}:$it" },
            spatial, _settings.value.eyeHeightCm / 100f, _settings.value.handControllers,
            _settings.value.joyConTracking, _settings.value.steadyHandAim)
        else SpatialTracking.useLegacyTracking()
        _calibration.value = GlassesCalibrationState(supported = opened is RayNeoLink,
            saved = opened is RayNeoLink && calibrationKey?.let(calibrationStore::load) != null)
        linkDevice = device
        glassesMode = 0
        autoModeSent = false
        Log.i(TAG, "opened ${model?.name} pid=0x%04X imu=%s".format(device.productId, opened.hasImu))
        publish { it.copy(connected = opened.ready, controlError = null) }
        if (opened is VitureSdkLink) readVitureDevice(opened)
        listOf(VitureProtocol.MSG_GET_BRIGHTNESS, VitureProtocol.MSG_GET_VOLUME, VitureProtocol.MSG_GET_DISPLAY).forEachIndexed { i, msg ->
            handler.postDelayed({ if (link === opened) opened.send(msg) }, VitureLink.COMMAND_GAP_MS * (i + 1))
        }
        handler.postDelayed({
            if (link !== opened) return@postDelayed
            _settings.value.sunblock?.let { sendFilm(it) }
            finishLinkInitialization(opened, 3)
        }, 300)
    }

    private fun finishLinkInitialization(opened: GlassesLink, attempts: Int) {
        if (link !== opened) return
        if (opened is VitureDeviceLink) {
            readVitureDevice(opened)
            val device = _state.value.viture
            if (attempts > 1 && device != null && (!device.nativeTrackingKnown ||
                    (device.supportsNative && device.selectedDisplayMode == null))) {
                handler.postDelayed({ finishLinkInitialization(opened, attempts - 1) }, 300)
            }
        }
        updateImu()
        applyMode(force = false)
    }

    private fun xrealListener(generation: Long) = object : XrealLink.Listener {
        override fun onReady() = post {
            if (generation != linkGeneration) return@post
            publish { it.copy(connected = true, controlError = null) }
            autoModeSent = false
            updateImu()
            applyMode(force = false)
        }
        override fun onValue(message: Int, value: Int) = post {
            if (generation != linkGeneration) return@post
            when (message) {
                VitureProtocol.MSG_GET_DISPLAY -> glassesMode = value
                VitureProtocol.MSG_BRIGHTNESS -> publish { it.copy(brightness = value) }
                VitureProtocol.MSG_FILM -> publish { it.copy(sunblock = value != 0) }
            }
        }
        override fun onPose(raw: ByteArray, pose: FloatArray, timeNs: Long) {
            if (generation != linkGeneration) return
            imuPackets++
            probe?.invoke(raw, pose)
            if (simulating.get() == null) deliver(pose, timeNs)
        }
        override fun onUnavailable(networkMissing: Boolean) = post {
            if (generation != linkGeneration) return@post
            publish { it.copy(connected = false, tracking = false,
                controlError = app.getString(if (networkMissing) R.string.glasses_xreal_network_missing else R.string.glasses_xreal_connection_failed)) }
        }
    }

    private fun rokidListener(generation: Long) = object : RokidLink.Listener {
        override fun onReady() = post {
            if (generation != linkGeneration) return@post
            publish { it.copy(connected = true, controlError = null) }
            updateImu()
            applyMode(force = false)
        }
        override fun onValue(message: Int, value: Int) = post {
            if (generation != linkGeneration) return@post
            when (message) {
                VitureProtocol.MSG_GET_DISPLAY -> glassesMode = value
                VitureProtocol.MSG_BRIGHTNESS -> publish { it.copy(brightness = value) }
            }
        }
        override fun onPose(raw: ByteArray, pose: FloatArray, timeNs: Long) {
            if (generation != linkGeneration) return
            imuPackets++
            probe?.invoke(raw, pose)
            if (simulating.get() == null) deliver(pose, timeNs)
        }
        override fun onLost() = post {
            if (generation != linkGeneration) return@post
            closeLink()
            refresh()
        }
    }

    private fun sdkListener(generation: Long) = object : VitureSdkLink.Listener {
        override fun onDeviceState(id: Int, value: Int) = post {
            if (generation != linkGeneration) return@post
            val current = _state.value.viture ?: return@post
            val sdk = link as? VitureSdkLink ?: return@post
            val next = when (id) {
                2 -> {
                    val mode = sdk.deviceValue(VitureSdk.VALUE_NATIVE_MODE).toInt().takeIf { it >= 0 }
                    val display = if (mode == 1) sdk.deviceValue(VitureSdk.VALUE_NATIVE_DISPLAY).toInt() else sdk.deviceDisplayMode()
                    if (display < 0) return@post
                    current.copy(nativeMode = mode, displayMode = display).retainingNativeTracking(current)
                }
                3 -> current.copy(film = value.toFloat() / (current.filmMax ?: 1).coerceAtLeast(1))
                4 -> {
                    val mode = sdk.deviceValue(VitureSdk.VALUE_NATIVE_MODE).toInt()
                    if (mode !in 0..1) return@post
                    val dof = if (mode == 1) sdk.deviceValue(VitureSdk.VALUE_NATIVE_DOF).toInt() else 0
                    if (dof !in 0..2) return@post
                    current.copy(nativeMode = mode, nativeDof = dof)
                }
                5 -> current.copy(worn = value != 0)
                6 -> current.copy(distance = value)
                7 -> current.copy(size = value)
                else -> return@post
            }
            if (id == 4 && _state.value.vrActive && next.nativeTracking) {
                vrNativeDof = next.nativeDof
                controlViture(VitureSdk.CONTROL_NATIVE_DOF, 0f)
                return@post
            }
            publish { it.copy(viture = next) }
            if (id == 4) syncVitureTracking(next)
            if (id == 2) refresh()
        }
        override fun onSpatial(pose: FloatArray, timeNs: Long, valid: Boolean) {
            if (generation == linkGeneration) SpatialTracking.accept(pose, timeNs, generation, valid)
        }
        override fun onValue(msgId: Int, value: Int) = post {
            if (generation != linkGeneration) return@post
            when (msgId) {
                VitureProtocol.MSG_GET_DISPLAY -> glassesMode = value
                VitureProtocol.MSG_GET_BRIGHTNESS -> publish { it.copy(brightness = value) }
                VitureProtocol.MSG_GET_VOLUME -> publish { it.copy(volume = value) }
                VitureProtocol.MSG_FILM -> publish { it.copy(sunblock = value != 0) }
            }
        }
        override fun onPose(q: FloatArray, timeNs: Long) {
            if (generation != linkGeneration) return
            imuPackets++
            probe?.invoke(null, q)
            if (simulating.get() == null) deliver(q, timeNs)
        }
        override fun onLost() = post {
            if (generation != linkGeneration) return@post
            closeLink()
            publish { it.copy(controlError = app.getString(R.string.glasses_connection_failed)) }
        }
    }

    private fun v2Listener(generation: Long) = object : VitureV2Link.Listener {
        override fun onReady() = post {
            if (generation != linkGeneration) return@post
            val current = link as? VitureV2Link ?: return@post
            publish { it.copy(connected = current.ready, supportsScreen3dof = current.hasImu,
                tracking = current.trackingActive, controlError = null) }
            updateImu()
        }
        override fun onValue(msgId: Int, value: Int) = post {
            if (generation != linkGeneration) return@post
            when (msgId) {
                VitureProtocol.MSG_GET_DISPLAY -> glassesMode = value
                VitureProtocol.MSG_GET_BRIGHTNESS -> publish { it.copy(brightness = value) }
                VitureProtocol.MSG_GET_VOLUME -> publish { it.copy(volume = value) }
            }
        }
        override fun onPose(raw: ByteArray, q: FloatArray, timeNs: Long) {
            if (generation != linkGeneration) return
            imuPackets++
            probe?.invoke(raw, q)
            if (simulating.get() == null) deliver(q, timeNs)
        }
        override fun onTracking(active: Boolean) = post {
            if (generation != linkGeneration) return@post
            val current = link as? VitureV2Link ?: return@post
            publish { it.copy(tracking = active && current.trackingActive) }
        }
        override fun onLost() = post {
            if (generation != linkGeneration) return@post
            closeLink()
            publish { it.copy(controlError = app.getString(R.string.glasses_connection_failed)) }
        }
    }

    private fun rayNeoListener(generation: Long) = object : RayNeoLink.Listener {
        override fun onDeviceInfo(boardId: Int, calibrationSaved: Boolean) = post {
            if (generation != linkGeneration) return@post
            val device = linkDevice ?: return@post
            val base = calibrationStore.key(device.vendorId, device.productId,
                runCatching { device.serialNumber }.getOrNull(), model?.name.orEmpty())
            calibrationKey = RayNeoCalibrationStore.profileKey(base,
                RayNeoProtocol.isGt(device.vendorId, device.productId), boardId)
            _calibration.value = _calibration.value.copy(saved = calibrationSaved)
            (link as? RayNeoLink)?.diagonalFovDeg?.let { fov -> publish { it.copy(diagonalFovDeg = fov) } }
        }
        override fun onCalibration(revision: Long, phase: CalibrationPhase, progress: Int) = post {
            if (generation != linkGeneration || revision != calibrationRevision) return@post
            if (phase != CalibrationPhase.IDLE && !_calibration.value.running) return@post
            _calibration.value = _calibration.value.copy(phase = phase, progress = progress)
            if (!_calibration.value.running) updateImu()
        }
        override fun onCalibrationSaved(revision: Long, value: RayNeoCalibration?) = post {
            if (generation != linkGeneration || revision != calibrationRevision) return@post
            if (value != null && !_calibration.value.running) return@post
            calibrationKey?.let { calibrationStore.save(it, value) }
            _calibration.value = _calibration.value.copy(saved = value != null)
        }
        override fun onReady() = post {
            if (generation == linkGeneration) {
                publish { it.copy(connected = true) }
                updateImu()
                applyMode(force = false)
            }
        }
        override fun onPose(raw: ByteArray, xyzw: FloatArray, timeNs: Long) {
            if (generation != linkGeneration) return
            imuPackets++
            probe?.invoke(raw, xyzw)
            if (simulating.get() == null) deliver(xyzw, timeNs)
        }
        override fun onLost() = post {
            if (generation != linkGeneration) return@post
            closeLink()
            refresh()
        }
    }

    private fun closeLink() {
        val l = link ?: return
        linkGeneration++
        SpatialTracking.disconnect()
        link = null
        linkDevice = null
        calibrationKey = null
        _calibration.value = GlassesCalibrationState(phase = if (_calibration.value.running) CalibrationPhase.FAILED else CalibrationPhase.IDLE)
        glassesMode = 0
        l.close()
        nativeHostTracking?.let { saved -> update { it.copy(headTracking = saved) } }
        nativeHostTracking = null
        vrNativeDof = null
        publish { it.copy(connected = false, tracking = false, supportsScreen3dof = false, supportsSpatial = false, viture = null) }
        Log.i(TAG, "link closed")
    }

    private fun rememberSbs(support: Int) {
        if (_state.value.sbsSupport == support) return
        publish { it.copy(sbsSupport = support) }
    }

    private fun sbsFailed(productId: Int): Boolean =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_SBS_FAILED, null)?.contains(productId.toString(16)) == true

    private fun markSbsFailed(productId: Int?, failed: Boolean) {
        if (productId == null || sbsFailed(productId) == failed) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = prefs.getStringSet(KEY_SBS_FAILED, null).orEmpty()
        val id = productId.toString(16)
        prefs.edit().putStringSet(KEY_SBS_FAILED, if (failed) ids + id else ids - id).apply()
    }

    private fun applyMode(force: Boolean) {
        val s = _state.value
        val m = model ?: return
        if (!s.canControl || !s.supportsDisplayModeControl) return
        if (modeState.restorePending) {
            sendMode(false)
            return
        }
        if (s.viture?.supportsNative == true || s.viture?.displaySelected == true) return
        if (!s.usable || s.sbs || modeState.pendingStereo) return
        val hz = _settings.value.refreshHz
        if (hz == 30 && GlassesRendering.native30HzAvailable(_settings.value, s)) return
        if (glassesMode == m.mode(hz, false, outputResolution())) return
        if (!force) {
            if (autoModeSent || SystemClock.uptimeMillis() - lastModeSentAt < 8_000) return
            val resolution = outputResolution()
            if (glassesMode == 0 && abs(s.hz - hz) < 1.5f &&
                (resolution == null || (s.width == resolution.width && s.height == resolution.height))) return
            autoModeSent = true
        }
        sendMode(false)
    }

    private fun sendMode(sbs: Boolean, preserveDisplayShape: Boolean = false): Boolean {
        val m = model ?: return false
        if (!m.supportsDisplayModeControl) return false
        if (sbs && !_settings.value.gameOnGlasses) update { it.copy(gameOnGlasses = true) }
        val g = _settings.value
        val resolution = GlassesDisplayModes.selected(g, _state.value, sbs)
        val mode = m.mode(if (sbs) g.sbsHz else g.refreshHz, sbs, resolution)
        val device = _state.value.viture
        val sdk = link as? VitureDeviceLink
        val accepted = if (device?.supportsNative == true && sdk != null) {
            val current = if (preserveDisplayShape) device.selectedDisplayMode ?: return false else null
            val candidates = device.displayModes.filter { it.stereo == sbs && it.ultrawide == (current?.ultrawide == true) }
            val sized = if (current != null) candidates.filter { it.width == current.width && it.height == current.height }
                else candidates.filter { it.height == (resolution?.height ?: _state.value.height) }.ifEmpty { candidates }
            val requestedHz = if (sbs) g.sbsHz else g.refreshHz
            val selected = sized.filter { it.hz <= requestedHz }.maxByOrNull { it.hz } ?: sized.minByOrNull { it.hz }
            if (selected == null) false else {
                val success = if (device.nativeMode == 1) sdk.deviceControl(VitureSdk.CONTROL_NATIVE_DISPLAY, selected.code.toFloat()) == 0
                    else sdk.send(VitureProtocol.MSG_DISPLAY, byteArrayOf(selected.code.toByte()))
                if (success) publish { it.copy(viture = device.copy(displayMode = selected.code, displaySelected = true)) }
                success
            }
        } else link?.send(VitureProtocol.MSG_DISPLAY, byteArrayOf(mode.toByte())) == true
        if (accepted) {
            if (link is RayNeoLink) glassesMode = mode
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
        if (!_state.value.canControl || !_state.value.hardwareControls || !_state.value.hasSunblock) return
        if (link?.send(VitureProtocol.MSG_FILM, byteArrayOf(if (on) 1 else 0)) == true && link !is XrealLink)
            publish { it.copy(sunblock = on) }
    }

    private fun updateImu() {
        val l = link ?: return
        if (!l.ready) return
        if (_state.value.viture?.nativeTrackingKnown == false) return
        val want = (sink != null || poseObservers.isNotEmpty() || probe != null || _calibration.value.running || SpatialTracking.enabled) &&
            l.hasImu && _state.value.viture?.nativeTracking != true
        if (l !is VitureV2Link && want == _state.value.tracking) return
        val accepted = l.send(VitureProtocol.MSG_IMU, byteArrayOf(if (want) 1 else 0))
        if (want && !accepted) return
        if (want) l.send(VitureProtocol.MSG_IMU_RATE, byteArrayOf(VitureProtocol.IMU_RATE_240.toByte()))
        publish { it.copy(tracking = if (l is VitureV2Link) l.trackingActive else want) }
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
            probe?.invoke(payload, null)
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
        poseObservers.forEach { it.onPose(q[0], q[1], q[2], q[3], timeNs) }
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
        val raw = java.util.Collections.synchronizedList(mutableListOf<Pair<ByteArray?, FloatArray?>>())
        val counter = java.util.concurrent.atomic.AtomicInteger()
        val began = SystemClock.elapsedRealtime()
        probe = { payload, pose -> val n = counter.incrementAndGet(); if (raw.size < 4 || n % 60 == 0) raw.add(payload?.copyOf() to pose?.copyOf()) }
        post { updateImu() }
        SystemClock.sleep(ms)
        probe = null
        post { updateImu() }
        val seconds = (SystemClock.elapsedRealtime() - began) / 1000.0
        val count = counter.get()
        val samples = JSONArray()
        synchronized(raw) {
            raw.forEach { (b, pose) ->
                val rayneo = b != null && RayNeoProtocol.classifyPacket(b) is RayNeoPacket.Sensor
                val q = pose ?: if (b == null || rayneo) null else VitureProtocol.imuQuaternion(b)
                samples.put(JSONObject()
                    .put("hex", b?.joinToString("") { "%02X".format(it) } ?: JSONObject.NULL)
                    .put("euler", if (b == null || b.size < 12 || rayneo || pose != null) JSONObject.NULL else JSONArray().put(VitureProtocol.beFloat(b, 0).toDouble()).put(VitureProtocol.beFloat(b, 4).toDouble()).put(VitureProtocol.beFloat(b, 8).toDouble()))
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
            positionalTracking = p.getBoolean("positionalTracking", false),
            handControllers = p.getBoolean("handControllers", false),
            joyConTracking = p.getBoolean("joyConTracking", false),
            steadyHandAim = p.getBoolean("steadyHandAim", false),
            eyeHeightCm = p.getInt("eyeHeightCm", 160).coerceIn(50, 250),
            refreshHz = p.getInt("refreshHz", 120),
            sbsHz = p.getInt("sbsHz", 60),
            resolution2d = p.getString("resolution2d", "").orEmpty(),
            resolution3d = p.getString("resolution3d", "").orEmpty(),
            resolutionVr = p.getString("resolutionVr", "").orEmpty(),
            sunblock = if (p.contains("sunblock")) p.getBoolean("sunblock", false) else null,
            gameOnGlasses = p.getBoolean("gameOnGlasses", true),
            headTracking = p.getBoolean("headTracking", true),
            auto3d = p.getBoolean("auto3d", GlassesSettings().auto3d),
            picture3d = p.getInt("picture3d", GlassesSettings.PICTURE_AUTO),
            screenWidthDm = p.getInt("screenWidthDm", 20),
            screenDistanceDm = p.getInt("screenDistanceDm", 30),
            vrFovDeg = p.getInt("vrFovDeg", GlassesSettings.VR_FOV_DEFAULT).coerceIn(GlassesSettings.VR_FOV_MIN, GlassesSettings.VR_FOV_MAX),
        )
    }

    private fun save(s: GlassesSettings) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putBoolean("positionalTracking", s.positionalTracking)
            putBoolean("handControllers", s.handControllers)
            putBoolean("joyConTracking", s.joyConTracking)
            putBoolean("steadyHandAim", s.steadyHandAim)
            putInt("eyeHeightCm", s.eyeHeightCm)
            putInt("refreshHz", s.refreshHz)
            putInt("sbsHz", s.sbsHz)
            putString("resolution2d", s.resolution2d)
            putString("resolution3d", s.resolution3d)
            putString("resolutionVr", s.resolutionVr)
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
