package com.droiddeck.launcher.glasses

import kotlin.math.abs

internal class XrealOrientationFilter {
    private val fusion = ImuFusion()

    fun update(sample: XrealProtocol.Sample): FloatArray? = fusion.update(sample.timeNs,
        Vec3(sample.accel.x, -sample.accel.y, -sample.accel.z),
        Vec3(sample.gyro.x, -sample.gyro.y, -sample.gyro.z))
}

internal class ImuFusion {
    private var previous = 0L
    private var rotation = Quaternion.IDENTITY
    private var initialized = false
    private var bias = Vec3(0f, 0f, 0f)

    fun update(timeNs: Long, accel: Vec3, gyro: Vec3): FloatArray? {
        if (timeNs <= previous) return null
        val dt = if (previous == 0L) 0f else (timeNs - previous) * 1e-9f
        previous = timeNs
        val norm = accel.length()
        val gravityValid = abs(norm - 9.81f) < 1.5f
        if (dt > 0.25f) initialized = false
        if (!initialized) {
            if (!gravityValid) return null
            val up = accel.normalized()
            val axis = up.cross(Vec3(0f, 1f, 0f))
            rotation = if (up.y < -0.9999f) Quaternion.fromAxisAngle(Vec3(1f, 0f, 0f), Math.PI.toFloat())
                else Quaternion(1f + up.y, axis.x, axis.y, axis.z).normalized()
            initialized = true
        } else if (dt in 0.0001f..0.05f) {
            if (gravityValid && gyro.length() < 0.04f) {
                val alpha = (dt * 0.5f).coerceAtMost(1f)
                bias = bias * (1f - alpha) + gyro * alpha
            }
            var rate = gyro - bias
            if (gravityValid) {
                val predictedUp = rotation.conjugate().rotate(Vec3(0f, 1f, 0f))
                rate += accel.normalized().cross(predictedUp) * 1.5f
            }
            rotation = rotation.integrateBodyRate(rate, dt).normalized()
        }
        return RayNeoOrientationFilter.glassesPose(floatArrayOf(rotation.w, rotation.x, rotation.y, rotation.z))
    }
}
