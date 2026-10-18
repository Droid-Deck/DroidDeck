// Adapted from informalTechCode/RayNeo-Air-Series-3DoF-Scaffold (Apache-2.0).
// See docs/third-party/rayneo/. Returns scaffold quaternions in (w, x, y, z) order.
package com.droiddeck.launcher.glasses

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class Vec3(val x: Float, val y: Float, val z: Float) {
    fun length(): Float = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3 {
        val n = length()
        if (n < 1e-6f) {
            return Vec3(0f, 0f, 0f)
        }
        return Vec3(x / n, y / n, z / n)
    }

    fun cross(other: Vec3): Vec3 {
        return Vec3(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x
        )
    }

    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Float): Vec3 = Vec3(x * scale, y * scale, z * scale)
}

internal data class Quaternion(val w: Float, val x: Float, val y: Float, val z: Float) {
    operator fun times(other: Quaternion): Quaternion {
        return Quaternion(
            w = w * other.w - x * other.x - y * other.y - z * other.z,
            x = w * other.x + x * other.w + y * other.z - z * other.y,
            y = w * other.y - x * other.z + y * other.w + z * other.x,
            z = w * other.z + x * other.y - y * other.x + z * other.w
        )
    }

    operator fun times(scale: Float): Quaternion {
        return Quaternion(w * scale, x * scale, y * scale, z * scale)
    }

    fun normalized(): Quaternion {
        val n = sqrt(w * w + x * x + y * y + z * z)
        if (n < 1e-6f) {
            return IDENTITY
        }
        return Quaternion(w / n, x / n, y / n, z / n)
    }

    fun conjugate(): Quaternion = Quaternion(w, -x, -y, -z)

    fun rotate(v: Vec3): Vec3 {
        val qv = Quaternion(0f, v.x, v.y, v.z)
        val r = this * qv * conjugate()
        return Vec3(r.x, r.y, r.z)
    }

    fun integrateBodyRate(omegaRadPerSec: Vec3, dtSec: Float): Quaternion {
        val omega = Quaternion(0f, omegaRadPerSec.x, omegaRadPerSec.y, omegaRadPerSec.z)
        val qDot = (this * omega) * 0.5f
        return Quaternion(
            w + qDot.w * dtSec,
            x + qDot.x * dtSec,
            y + qDot.y * dtSec,
            z + qDot.z * dtSec
        )
    }

    companion object {
        val IDENTITY = Quaternion(1f, 0f, 0f, 0f)

        fun fromAxisAngle(axis: Vec3, angleRad: Float): Quaternion {
            val nAxis = axis.normalized()
            val half = angleRad * 0.5f
            val s = sin(half)
            val c = cos(half)
            return Quaternion(c, nAxis.x * s, nAxis.y * s, nAxis.z * s).normalized()
        }
    }
}

internal data class RayNeoSensorProfile(val rotationXDeg: Float, val gtMaxAxes: Boolean) {
    companion object {
        fun forDevice(gtUsb: Boolean, boardId: Int) = when {
            gtUsb && boardId == RayNeoProtocol.BOARD_GT -> RayNeoSensorProfile(-25f, false)
            gtUsb -> RayNeoSensorProfile(35f, true)
            boardId == RayNeoProtocol.BOARD_AIR_4_PRO -> RayNeoSensorProfile(-20f, false)
            else -> RayNeoSensorProfile(0f, false)
        }
    }
}

internal class RayNeoSensorAxes(imuRotationXDeg: Float, private val gt: Boolean) {
    private val rotation = Quaternion.fromAxisAngle(Vec3(1f, 0f, 0f),
        Math.toRadians((if (gt) 35f else imuRotationXDeg).toDouble()).toFloat())

    fun map(v: Vec3): Vec3 = rotation.rotate(if (gt) Vec3(-v.y, v.z, -v.x) else v)
}

internal class RayNeoOrientationFilter(
    imuRotationXDeg: Float,
    gt: Boolean = false,
    private val calibration: RayNeoCalibration? = null,
) {
    private val worldUp = Vec3(0f, 1f, 0f)
    private val accelGain = 1.5f
    private val dpsToRad = (Math.PI.toFloat() / 180f)
    private val gravityMps2 = 9.81f
    private val stationaryAccelTol = 1.25f
    private val stationaryGyroRadPerSec = 0.18f
    private val gyroBiasUpdateHz = 0.5f
    private val axes = RayNeoSensorAxes(imuRotationXDeg, gt)

    private var q = Quaternion.IDENTITY
    private var gravityInitialized = false
    private var gyroBias = Vec3(0f, 0f, 0f)
    private var lastTick100us: Long = -1L
    private var lastRealtimeNs: Long = 0L

    companion object {
        /** Scaffold: right +X, up +Y, back +Z. VITURE: right -Y, up +Z, back -X.
         * Conjugate by HeadPose.GLASSES_AXES before entering the unchanged VITURE pose path.
         * This is a basis conversion, not a projection or seated-origin adjustment.
         */
        fun glassesPose(wxyz: FloatArray): FloatArray =
            floatArrayOf(-wxyz[3], -wxyz[1], wxyz[2], wxyz[0])
    }

    fun update(sample: RayNeoSensorSample): FloatArray {
        var accel = Vec3(sample.accelMps2[0], sample.accelMps2[1], sample.accelMps2[2])
        var gyro = Vec3(sample.gyroDps[0], sample.gyroDps[1], sample.gyroDps[2])
        calibration?.let { gyro -= it.gyroBiasDps }
        accel = axes.map(accel)
        gyro = axes.map(gyro) * dpsToRad
        calibration?.let {
            accel = it.level.rotate(accel)
            gyro = it.level.rotate(gyro)
        }

        val nowNs = System.nanoTime()
        var dtSec = 0.01f
        if (lastTick100us >= 0L && sample.deviceTick100us != lastTick100us) {
            dtSec = (((sample.deviceTick100us - lastTick100us) and 0xffffffffL).toFloat() * 1e-4f)
        } else if (lastRealtimeNs > 0L && nowNs > lastRealtimeNs) {
            dtSec = ((nowNs - lastRealtimeNs).toFloat() * 1e-9f)
        }
        if (dtSec < 0.001f || dtSec > 0.1f) {
            dtSec = 0.01f
        }
        lastTick100us = sample.deviceTick100us
        lastRealtimeNs = nowNs

        val accelNorm = accel.length()
        val stationary =
            kotlin.math.abs(accelNorm - gravityMps2) < stationaryAccelTol &&
                gyro.length() < stationaryGyroRadPerSec
        if (stationary) {
            val alpha = minOf(1f, dtSec * gyroBiasUpdateHz)
            gyroBias = gyroBias * (1f - alpha) + gyro * alpha
        }
        gyro -= gyroBias

        if (accelNorm > 1e-3f) {
            val measuredUp = accel.normalized()
            if (!gravityInitialized) {
                // Align gravity before publishing the first pose. Starting at
                // identity and settling after recenter can rotate the horizon.
                val axis = measuredUp.cross(worldUp)
                q = if (measuredUp.y < -0.9999f) {
                    Quaternion.fromAxisAngle(Vec3(1f, 0f, 0f), Math.PI.toFloat())
                } else {
                    Quaternion(1f + measuredUp.y, axis.x, axis.y, axis.z).normalized()
                }
                gravityInitialized = true
            }
            val predictedUp = q.conjugate().rotate(worldUp).normalized()
            // q maps body to world: measured x predicted reduces gravity error.
            val correction = measuredUp.cross(predictedUp)
            gyro += correction * accelGain
        }

        q = q.integrateBodyRate(gyro, dtSec).normalized()
        return floatArrayOf(q.w, q.x, q.y, q.z)
    }
}
