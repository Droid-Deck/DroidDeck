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

    fun update(accelKernel: FloatArray, gyroKernel: FloatArray, dt: Float) = update(axes(accelKernel), axes(gyroKernel), dt)

    fun update(accel: Vec3, gyro: Vec3, dt: Float) {
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
        if (gravityValid && rate.length() < MOVING_RATE) {
            val predictedUp = rotation.conjugate().rotate(WORLD_UP)
            corrected += accel.normalized().cross(predictedUp) * (GAIN * (1f - abs(norm - GRAVITY) / GRAVITY_TOLERANCE))
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
        private const val MOVING_RATE = 1.0f

        fun axes(v: FloatArray) = Vec3(-v[1], v[2], -v[0])
    }
}

internal class JoyConReports {
    class Sample(val accel: FloatArray, val gyro: FloatArray, val dt: Float)

    private val pending = ArrayList<FloatArray>(4)
    private var pendingAt = 0L
    private var previousFirst: FloatArray? = null
    private var previousLast: FloatArray? = null
    private var previousSample: FloatArray? = null
    private var previousAt = 0L
    private var order = NEWEST_FIRST_START

    val size: Int get() = pending.size

    fun add(accel: FloatArray, gyro: FloatArray, arrivalNs: Long) {
        if (pending.isEmpty()) pendingAt = arrivalNs
        pending += floatArrayOf(accel[0], accel[1], accel[2], gyro[0], gyro[1], gyro[2])
    }

    fun flush(): List<Sample> {
        if (pending.isEmpty()) return emptyList()
        val first = pending.first()
        val last = pending.last()
        if (pending.size > 1) {
            if (previousFirst?.contentEquals(last) == true) order = (order + 1).coerceAtMost(ORDER_LIMIT)
            else if (previousLast?.contentEquals(first) == true) order = (order - 1).coerceAtLeast(-ORDER_LIMIT)
        }
        val chronological = if (order >= 0) pending.asReversed() else pending
        val fresh = ArrayList<FloatArray>(chronological.size)
        var before = previousSample
        for (sample in chronological) {
            if (before?.contentEquals(sample) != true) fresh += sample
            before = sample
        }
        val used = fresh.ifEmpty { listOf(chronological.last()) }
        val dt = if (previousAt == 0L) 0f else ((pendingAt - previousAt).coerceAtLeast(0L) * 1e-9f / used.size).coerceAtMost(MAX_STEP_S)
        val out = used.map { Sample(it.copyOfRange(0, 3), it.copyOfRange(3, 6), dt) }
        previousFirst = first
        previousLast = last
        previousSample = chronological.last()
        previousAt = pendingAt
        pending.clear()
        return out
    }

    companion object {
        const val SAMPLES_PER_REPORT = 3
        private const val NEWEST_FIRST_START = 2
        private const val ORDER_LIMIT = 20
        private const val MAX_STEP_S = 0.05f
    }
}
