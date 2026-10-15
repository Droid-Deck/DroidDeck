package com.droiddeck.launcher.glasses

import android.content.Context
import com.droiddeck.launcher.wayland.WaylandCompositor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SpatialTrackingStatus(val message: String = "Disabled", val calibration: Boolean = false,
    val frames: Long = 0, val dropped: Long = 0, val processingMs: Float = 0f,
    val frameAgeMs: Float = -1f, val cameraHz: Float = 0f, val synchronized: Boolean = false,
    val trackingValid: Boolean = false, val needsRecenter: Boolean = false, val recenterPending: Boolean = false)

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
    private var spatialSelected = false
    private var handsSelected = false
    private var hadSpatialSession = false
    fun initialize(app: Context) { context = app.applicationContext }
    fun attachPublisher(path: String) {
        publisherAttached = WaylandCompositor.nativeSetSpatialFile(path)
        publish(System.nanoTime())
    }

    internal fun connect(token: Long, generation: Long, identity: String?, spatial: Boolean, eyeHeight: Float, hands: Boolean) {
        engine?.close(); engine = null
        enabled = spatial; handsEnabled = spatial && hands
        spatialSelected = spatial; handsSelected = handsEnabled
        session.reset(generation, eyeHeight, spatial && hadSpatialSession)
        hadSpatialSession = hadSpatialSession || spatial
        if (spatial) context?.let { engine = StereoHandEngine(it, token, generation, identity) }
        mutableStatus.value = SpatialTrackingStatus(if (spatial) "Waiting for stable tracking" else "Disabled")
        engine?.setEnabled(handsEnabled)
        engine?.diagnostics(diagnosticsEnabled)
        publish(System.nanoTime())
    }
    internal fun disconnect() {
        enabled = false; handsEnabled = false
        session.reset(session.generation + 1, 1.6f)
        publish(System.nanoTime())
        engine?.close(); engine = null
        mutableStatus.value = SpatialTrackingStatus("Disconnected")
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
    @Synchronized fun refreshStatus(now: Long = System.nanoTime()) {
        val valid = session.valid(now)
        val pending = session.recenterPending
        val recenter = session.needsRecenter
        val fresh = session.latest()?.let { it.valid && now-it.timeNs in 0..100_000_000L } == true
        mutableStatus.update { it.copy(trackingValid = valid, needsRecenter = recenter, recenterPending = pending,
            message = when {
                !enabled -> "Positional tracking is off or glasses are disconnected"
                pending -> "Recenter requested — hold still while tracking stabilizes"
                !fresh -> "Tracking lost — face a well-lit area with visible detail"
                recenter -> "Tracking origin changed — tap Recenter, then hold still"
                else -> "6DoF active"
            }) }
    }
    @Synchronized fun setHands(enabled: Boolean) {
        handsEnabled = this.enabled && enabled
        handsSelected = handsEnabled
        session.setHands(session.generation, SpatialHand(), SpatialHand())
        engine?.setEnabled(handsEnabled)
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
    internal fun diagnosticFrame() = engine?.preview
    internal fun diagnosticMessage() = engine?.message ?: "Connect Luma Ultra and enable positional tracking."
    internal fun calibrationProgress() = engine?.calibrationProgress ?: SpatialCalibrationProgress()
    internal fun gpuEnabled() = engine?.gpu == true
    fun diagnosticStatus(): org.json.JSONObject {
        val now=System.nanoTime();val sample=session.latest();val state=status.value
        return org.json.JSONObject().put("enabled",enabled).put("valid",session.valid(now)).put("needsRecenter",session.needsRecenter)
            .put("recenterPending",session.recenterPending)
            .put("generation",session.generation).put("origin",session.originGeneration)
            .put("ageMs",sample?.let { (now-it.timeNs)/1e6 } ?: -1.0)
            .put("position",sample?.pose?.position?.array()?.let { org.json.JSONArray(it.toList()) })
            .put("message",state.message).put("camera",diagnosticMessage()).put("frames",state.frames).put("dropped",state.dropped)
            .put("processingMs",state.processingMs.toDouble()).put("calibrated",state.calibration)
            .put("frameAgeMs",state.frameAgeMs.toDouble()).put("cameraHz",state.cameraHz.toDouble()).put("synchronized",state.synchronized)
            .put("leftValid",session.hand(0,now).valid).put("rightValid",session.hand(1,now).valid)
    }
    internal fun statistics(calibrated: Boolean, frames: Long, dropped: Long, ms: Float, age: Float, hz: Float, synchronized: Boolean) {
        mutableStatus.update { it.copy(calibration = calibrated, frames = frames, dropped = dropped, processingMs = ms,
            frameAgeMs=age,cameraHz=hz,synchronized=synchronized) }
    }
    @Synchronized internal fun publish(now: Long) {
        synchronized(session) {
        buffer.clear()
        buffer.putInt(0x53504444).putInt(1).putInt(176).putInt(0)
        buffer.putLong(session.generation).putLong(session.originGeneration)
        buffer.putInt((if (spatialSelected) 1 else 0) or (if (handsSelected) 2 else 0)).putInt(0)
        putPose(session.view(false) ?: SpatialPose(), session.latest()?.timeNs ?: 0, session.valid(now))
        for (i in 0..1) {
            val hand = session.hand(i, now)
            putPose(hand.pose, hand.timeNs, hand.valid)
            buffer.putFloat(if (hand.valid) hand.trigger else 0f).putInt(if (hand.valid && hand.grip) 1 else 0)
        }
        buffer.flip()
        if (publisherAttached) WaylandCompositor.nativePublishSpatial(buffer)
        }
    }
    private fun putPose(p: SpatialPose, time: Long, valid: Boolean) {
        buffer.putLong(time).putFloat(p.position.x).putFloat(p.position.y).putFloat(p.position.z)
        buffer.putFloat(p.rotation.x).putFloat(p.rotation.y).putFloat(p.rotation.z).putFloat(p.rotation.w)
        buffer.putInt(if (valid) 1 else 0)
    }
}
