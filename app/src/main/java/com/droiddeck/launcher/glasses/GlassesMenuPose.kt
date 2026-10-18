package com.droiddeck.launcher.glasses

import com.droiddeck.launcher.gpu.HeadPose

class GlassesMenuPose internal constructor(
    private val spatialEnabled: () -> Boolean = { SpatialTracking.enabled },
    private val spatialView: (Long) -> SpatialPose? = { now ->
        synchronized(SpatialTracking.session) {
            if (SpatialTracking.session.valid(now)) SpatialTracking.session.view(true) else null
        }
    },
    private val recenterSpatial: () -> Unit = { SpatialTracking.recenter(true) },
) : Glasses.PoseSink {
    data class Frame(
        val rotation: FloatArray,
        val position: FloatArray,
        val valid: Boolean,
        val spatial: Boolean,
    )

    private var tracking = true
    private var positional = false
    private var reference: HeadPose.Reference? = null
    private var latest: FloatArray? = null
    private var latestTime = 0L
    private var safe = identity()

    @Synchronized fun configure(settings: GlassesSettings) {
        if (tracking != settings.headTracking) {
            reference = null
            safe = identity()
        }
        tracking = settings.headTracking
        positional = settings.positionalTracking
    }

    @Synchronized override fun onPose(x: Float, y: Float, z: Float, w: Float, timeNs: Long) {
        val norm = x*x + y*y + z*z + w*w
        if (!norm.isFinite() || norm !in 0.25f..2.25f || timeNs <= latestTime) return
        val matrix = HeadPose.fromGlasses(x, y, z, w)
        if (matrix.any { !it.isFinite() }) return
        latest = matrix
        latestTime = timeNs
    }

    @Synchronized fun snapshot(now: Long = System.nanoTime()): Frame {
        if (!tracking) return identity()
        val spatial = positional && spatialEnabled()
        if (spatial) {
            val pose = spatialView(now)
            val normalized = pose?.rotation?.normalized()
            if (pose != null && pose.position.finite() && normalized != null) {
                safe = Frame(normalized.matrix(), pose.position.array(), true, true)
                return copy(safe)
            }
        } else {
            val matrix = latest
            if (matrix != null && latestTime > 0 && now-latestTime in 0..MAX_AGE_NS) {
                val anchor = reference ?: HeadPose.recenter(matrix, centerView = true).also { reference = it }
                safe = Frame(HeadPose.relative(anchor, matrix), FloatArray(3), true, false)
                return copy(safe)
            }
        }
        return copy(safe).copy(valid = false, spatial = spatial)
    }

    @Synchronized fun recenter() {
        if (positional && spatialEnabled()) recenterSpatial()
        else reference = null
    }

    @Synchronized fun reset() {
        reference = null
        latest = null
        latestTime = 0L
        safe = identity()
    }

    private fun copy(frame: Frame) = frame.copy(rotation = frame.rotation.copyOf(), position = frame.position.copyOf())

    private fun identity() = Frame(HeadPose.IDENTITY.copyOf(), FloatArray(3), false, false)

    companion object {
        internal const val MAX_AGE_NS = 100_000_000L
    }
}
