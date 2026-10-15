package com.droiddeck.launcher.input

import com.droiddeck.launcher.glasses.Quaternion
import com.droiddeck.launcher.glasses.Vec3
import kotlin.math.abs

internal class JoyConOrientation {
    var rotation = Quaternion.IDENTITY
        private set
    var rate = Vec3(0f, 0f, 0f)
        private set
    var ready = false
        private set
    private var bias = Vec3(0f, 0f, 0f)
    private var stillFor = 0f

    fun update(accelKernel: FloatArray, gyroKernel: FloatArray, dt: Float) {
        val accel = axes(accelKernel)
        val gyro = axes(gyroKernel)
        val norm = accel.length()
        val gravityValid = abs(norm - GRAVITY) < GRAVITY_TOLERANCE
        if (!ready) {
            if (!gravityValid) return
            val up = accel.normalized()
            val axis = up.cross(WORLD_UP)
            rotation = if (up.y < -0.9999f) Quaternion.fromAxisAngle(Vec3(1f, 0f, 0f), Math.PI.toFloat())
                else Quaternion(1f + up.y, axis.x, axis.y, axis.z).normalized()
            rate = Vec3(0f, 0f, 0f)
            ready = true
            return
        }
        if (dt <= 0f) return
        val still = gravityValid && abs(norm - GRAVITY) < STILL_ACCEL && (gyro - bias).length() < STILL_RATE
        stillFor = if (still) stillFor + dt else 0f
        if (stillFor >= STILL_SECONDS) {
            val alpha = (dt / BIAS_SECONDS).coerceAtMost(1f)
            bias = bias * (1f - alpha) + gyro * alpha
        }
        rate = gyro - bias
        var corrected = rate
        if (gravityValid) {
            val predictedUp = rotation.conjugate().rotate(WORLD_UP)
            corrected += accel.normalized().cross(predictedUp) * GAIN
        }
        rotation = rotation.integrateBodyRate(corrected, dt).normalized()
    }

    companion object {
        private val WORLD_UP = Vec3(0f, 1f, 0f)
        private const val GRAVITY = 9.80665f
        private const val GRAVITY_TOLERANCE = 1.0f
        private const val STILL_ACCEL = 0.5f
        private const val STILL_RATE = 0.05f
        private const val STILL_SECONDS = 0.3f
        private const val BIAS_SECONDS = 2f
        private const val GAIN = 1.0f

        fun axes(v: FloatArray) = Vec3(-v[1], v[2], -v[0])
    }
}

internal class JoyConSampleClock {
    private var scale = 0f
    private var last = 0L
    private var windowStamp = 0L
    private var windowArrival = 0L

    fun interval(stampNs: Long, arrivalNs: Long): Float {
        if (stampNs == last) return 0f
        if (last == 0L || stampNs < last || stampNs - last > MAX_GAP_NS) {
            last = stampNs
            windowStamp = stampNs
            windowArrival = arrivalNs
            return 0f
        }
        val raw = stampNs - last
        last = stampNs
        if (arrivalNs - windowArrival >= WINDOW_NS) {
            val measured = (arrivalNs - windowArrival).toFloat() / (stampNs - windowStamp)
            scale = if (scale == 0f) measured else scale + (measured - scale) * SMOOTHING
            windowStamp = stampNs
            windowArrival = arrivalNs
        }
        if (scale == 0f) return 0f
        return (raw * scale * 1e-9f).coerceAtMost(MAX_STEP_S)
    }

    companion object {
        private const val WINDOW_NS = 500_000_000L
        private const val MAX_GAP_NS = 1_000_000_000L
        private const val SMOOTHING = 0.25f
        private const val MAX_STEP_S = 0.05f
    }
}
