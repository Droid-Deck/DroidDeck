package com.droiddeck.launcher.glasses

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.wayland.WaylandCompositor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SpatialTrackingStatus(val message: String = "", val calibration: Boolean = false,
    val frames: Long = 0, val dropped: Long = 0, val processingMs: Float = 0f,
    val frameAgeMs: Float = -1f, val cameraHz: Float = 0f, val synchronized: Boolean = false,
    val trackingValid: Boolean = false, val needsRecenter: Boolean = false, val recenterPending: Boolean = false,
    val handStatus: String = "", val headFresh: Boolean = false,
    val cameraFrameDivisor: Int = 1, val rateLimitedFrames: Long = 0, val cameraCopyPermille: Int = 1000,
    val calibrationSource: String = "none", val calibrationVerified: Boolean = false)

object SpatialTracking {
    val session = SpatialSession()
    private val mutableStatus = MutableStateFlow(SpatialTrackingStatus())
    val status = mutableStatus.asStateFlow()
    @Volatile var enabled = false; private set
    @Volatile var handsEnabled = false; private set
    private var context: Context? = null
    private var engine: StereoHandEngine? = null
    private var diagnosticsEnabled = false
    private val buffer = ByteBuffer.allocateDirect(176).order(ByteOrder.LITTLE_ENDIAN)
    private var lastStatus = 0L
    @Volatile private var publisherAttached = false
    @Volatile private var spatialSelected = false
    private var outputUsesGlasses = true
    private var handsSelected = false
    private var steadyAimSelected = false
    @Volatile private var joyConsSelected = false
    internal val joyConsEnabled get() = enabled && joyConsSelected
    private var hadSpatialSession = false
    internal val selected: Boolean get() = spatialSelected
    private fun text(id: Int) = context?.getString(id).orEmpty()
    fun initialize(app: Context) {
        context = app.applicationContext
        mutableStatus.update { it.copy(
            message = it.message.ifEmpty { text(R.string.spatial_disabled) },
            handStatus = it.handStatus.ifEmpty { text(R.string.spatial_hands_off) },
        ) }
    }
    fun attachPublisher(path: String) {
        publisherAttached = WaylandCompositor.nativeSetSpatialFile(path)
        publish(System.nanoTime())
    }

    internal fun connect(token: Long, generation: Long, identity: String?, spatial: Boolean, eyeHeight: Float, hands: Boolean,
                         joyCons: Boolean = false, steadyAim: Boolean = false) {
        engine?.close(); engine = null
        enabled = spatial; handsEnabled = spatial && (hands || joyCons)
        spatialSelected = spatial; handsSelected = spatial && hands && !joyCons
        joyConsSelected = spatial && joyCons
        steadyAimSelected = steadyAim
        session.reset(generation, eyeHeight, spatial && hadSpatialSession)
        hadSpatialSession = hadSpatialSession || spatial
        if (spatial) context?.let { engine = StereoHandEngine(it, token, generation, identity) }
        mutableStatus.value = SpatialTrackingStatus(if (spatial) text(R.string.spatial_wait_stable) else text(R.string.spatial_disabled))
        engine?.setEnabled(handsEnabled)
        engine?.diagnostics(diagnosticsEnabled)
        publish(System.nanoTime())
    }
    internal fun disconnect() {
        enabled = false; handsEnabled = false
        session.reset(session.generation + 1, 1.6f)
        publish(System.nanoTime())
        engine?.close(); engine = null
        mutableStatus.value = SpatialTrackingStatus(text(R.string.spatial_disconnected))
        publish(System.nanoTime())
    }
    @Synchronized internal fun useLegacyTracking() {
        spatialSelected = false; handsSelected = false; joyConsSelected = false
        publish(System.nanoTime())
    }
    @Synchronized internal fun setOutputUsesGlasses(on: Boolean) {
        if (outputUsesGlasses == on) return
        outputUsesGlasses = on
        publish(System.nanoTime())
    }
    @Synchronized internal fun accept(values: FloatArray, time: Long, generation: Long, valid: Boolean) {
        if (!enabled || generation != session.generation) return
        val pose = SpatialPose.fromSdk(values)
        session.accept(SpatialSample(pose ?: SpatialPose(), time, generation, valid && pose != null))
        publish(time)
        if (time-lastStatus > 250_000_000L) {
            lastStatus = time
            refreshStatus(time)
        }
    }
    @Synchronized fun refreshStatus(now: Long? = null) {
        val checkedAt = now ?: System.nanoTime()
        val valid = session.valid(checkedAt)
        val pending = session.recenterPending
        val recenter = session.needsRecenter
        val fresh = session.latest()?.let { it.valid && checkedAt-it.timeNs in 0..100_000_000L } == true
        mutableStatus.update { it.copy(trackingValid = valid, needsRecenter = recenter, recenterPending = pending, headFresh = fresh,
            handStatus = engine?.handStatus?.message ?: text(R.string.spatial_hands_off),
            message = when {
                !enabled -> text(R.string.spatial_tracking_off)
                pending -> text(R.string.spatial_recenter_pending)
                !fresh -> text(R.string.spatial_tracking_lost)
                recenter -> text(R.string.spatial_origin_changed)
                else -> text(R.string.spatial_active)
            }) }
    }
    @Synchronized fun setControllers(hands: Boolean, joyCons: Boolean) {
        handsSelected = hands && !joyCons
        joyConsSelected = joyCons
        handsEnabled = enabled && (hands || joyCons)
        session.setHands(session.generation, SpatialHand(), SpatialHand())
        engine?.setEnabled(handsEnabled)
        publish(System.nanoTime())
    }
    @Synchronized fun setSteadyHandAim(on: Boolean) {
        steadyAimSelected = on
        publish(System.nanoTime())
    }
    fun setFloor(height: Float) { session.setFloor(height); refreshStatus(); publish(System.nanoTime()) }
    fun recenter(screen: Boolean) { session.recenter(screen); refreshStatus(); publish(System.nanoTime()) }
    fun diagnostics(on: Boolean) { diagnosticsEnabled = on; engine?.diagnostics(on) }
    fun calibrationStart() { engine?.startCalibration() }
    fun calibrationCapture() { engine?.captureCalibration() }
    fun calibrationSolve() { engine?.solveCalibration() }
    fun calibrationValidate(distanceMeters: Float) { engine?.validateCalibration(distanceMeters) }
    fun captureDiagnostic() { engine?.recordDiagnostic() }
    fun setGpu(gpu: Boolean) { engine?.setGpu(gpu) }
    fun setHandModel(lite: Boolean) { engine?.setModel(lite) }
    fun setHandHalfRate(halfRate: Boolean) { engine?.setHalfRate(halfRate) }
    fun setHandCpuParallel(parallel: Boolean) { engine?.setCpuParallel(parallel) }
    fun setHandAdaptiveInput(adaptive: Boolean) { engine?.setAdaptiveInput(adaptive) }
    fun setHandScheduledTracking(scheduled: Boolean) { engine?.setScheduledTracking(scheduled) }
    internal fun handLiteEnabled() = (engine?.model ?: HandModel.DEFAULT) == HandModel.LITE
    internal fun handHalfRateEnabled() = engine?.halfRateInput ?: true
    internal fun handAdaptiveInputEnabled() = engine?.adaptiveInput ?: true
    fun benchmarkSavedCapture(id: String) { checkNotNull(engine) { "Connect glasses and enable positional tracking" }.benchmarkSavedCapture(id) }
    internal fun diagnosticFrame() = engine?.preview
    internal fun diagnosticMessage() = engine?.message ?: text(R.string.spatial_connect_hint)
    internal fun calibrationProgress() = engine?.calibrationProgress ?: SpatialCalibrationProgress()
    internal fun gpuEnabled() = engine?.gpu ?: false
    fun diagnosticStatus(): org.json.JSONObject {
        val now=System.nanoTime();val sample=session.latest();val state=status.value;val progress=calibrationProgress()
        return org.json.JSONObject().put("enabled",enabled).put("valid",session.valid(now)).put("needsRecenter",session.needsRecenter)
            .put("joyConTracking",joyConsSelected)
            .put("steadyHandAim",handsSelected && steadyAimSelected)
            .put("positionEstimator",if (joyConsSelected) "joycon-wrist" else "full-palm")
            .put("recenterPending",session.recenterPending)
            .put("handMaxAgeMs",HandObservation.MAX_AGE_NS / 1_000_000)
            .put("handGpuEnabled",gpuEnabled()).put("handInferenceBackend",engine?.inferenceBackend ?: "not initialized")
            .put("handModel",(engine?.model ?: HandModel.DEFAULT).name).put("handHalfRate",handHalfRateEnabled())
            .put("handImagePreprocessing",HandImageBuffer.PREPROCESSING)
            .put("handLensCorrection",engine?.lensCorrection ?: "none")
            .put("handCpuParallel",engine?.cpuParallel ?: true)
            .put("handAdaptiveInput",handAdaptiveInputEnabled()).put("handScheduledTracking",engine?.scheduledTracking ?: true)
            .put("handInputRatePermille",engine?.inputRatePermille ?: 500)
            .put("handBenchmark",engine?.benchmark)
            .put("calibrationViews",progress.views).put("calibrationCollecting",progress.collecting)
            .put("capturePending",progress.capturing).put("captureMessage",progress.captureMessage)
            .put("calibrationFitted",progress.fitted).put("validationPending",progress.validating).put("validationMessage",progress.validationMessage)
            .put("calibrationIdentityAvailable",engine?.hasStableIdentity == true).put("handTracking",engine?.handStatus?.json())
            .put("calibrationSource",engine?.calibrationSource ?: "none").put("calibrationVerified",engine?.calibrationVerified == true)
            .put("generation",session.generation).put("origin",session.originGeneration)
            .put("ageMs",sample?.let { (now-it.timeNs)/1e6 } ?: -1.0)
            .put("position",sample?.pose?.position?.array()?.let { org.json.JSONArray(it.toList()) })
            .put("message",state.message).put("camera",diagnosticMessage()).put("frames",state.frames).put("dropped",state.dropped)
            .put("processingMs",state.processingMs.toDouble()).put("calibrated",state.calibration)
            .put("frameAgeMs",state.frameAgeMs.toDouble()).put("cameraHz",state.cameraHz.toDouble()).put("synchronized",state.synchronized)
            .put("cameraFrameDivisor",state.cameraFrameDivisor).put("rateLimitedFrames",state.rateLimitedFrames)
            .put("cameraCopyPermille",state.cameraCopyPermille)
            .put("leftValid",session.hand(0,now).valid).put("rightValid",session.hand(1,now).valid)
    }
    internal fun statistics(calibrated: Boolean, frames: Long, dropped: Long, ms: Float, age: Float, hz: Float, synchronized: Boolean,
                            frameDivisor: Int, rateLimited: Long, copyPermille: Int = 1000) {
        mutableStatus.update { it.copy(calibration = calibrated, frames = frames, dropped = dropped, processingMs = ms,
            frameAgeMs=age,cameraHz=hz,synchronized=synchronized,cameraFrameDivisor=frameDivisor,
            rateLimitedFrames=rateLimited,cameraCopyPermille=copyPermille,
            calibrationSource=engine?.calibrationSource ?: "none",calibrationVerified=engine?.calibrationVerified == true) }
    }
    @Synchronized internal fun publish(now: Long) {
        synchronized(session) {
        val hands = Array(2) { session.hand(it, now) }
        buffer.clear()
        buffer.putInt(0x53504444).putInt(4).putInt(176).putInt(0)
        buffer.putLong(session.generation).putLong(session.originGeneration)
        val modes = (if (spatialSelected) 1 else 0) or (if (handsSelected) 2 else 0) or
            (if (joyConsSelected) 4 else 0) or (if (handsSelected && steadyAimSelected) 8 else 0)
        buffer.putInt(if (outputUsesGlasses) modes else 0).putInt(session.inputEpochs())
        putPose(session.view(false) ?: SpatialPose(), session.latest()?.timeNs ?: 0, if(session.valid(now)) 1 else 0)
        for (i in 0..1) {
            val hand = hands[i]
            putPose(hand.pose, hand.timeNs, hand.flags())
            buffer.putFloat(if (hand.valid && hand.triggerValid) hand.trigger else 0f)
                .putInt(if (hand.valid && hand.gripValid && hand.grip) 1 else 0)
        }
        buffer.flip()
        if (publisherAttached) WaylandCompositor.nativePublishSpatial(buffer)
        }
    }
    private fun putPose(p: SpatialPose, time: Long, flags: Int) {
        buffer.putLong(time).putFloat(p.position.x).putFloat(p.position.y).putFloat(p.position.z)
        buffer.putFloat(p.rotation.x).putFloat(p.rotation.y).putFloat(p.rotation.z).putFloat(p.rotation.w)
        buffer.putInt(flags)
    }
}
