package com.droiddeck.launcher.input

import android.content.Context
import com.droiddeck.launcher.glasses.Quaternion
import com.droiddeck.launcher.glasses.Vec3
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class JoyConAccelCalibration {
    var bias = Vec3(0f, 0f, 0f)
        private set
    var scale = 1f
        private set
    var ready = false
        private set
    private val sums = HashMap<Int, DoubleArray>()

    fun restore(saved: FloatArray?) {
        if (saved == null || saved.size != 4 || saved.any { it.isNaN() || it.isInfinite() }) return
        val b = Vec3(saved[0], saved[1], saved[2])
        if (!plausible(b, saved[3])) return
        bias = b
        scale = saved[3]
        ready = true
    }

    fun saved(): FloatArray? = if (ready) floatArrayOf(bias.x, bias.y, bias.z, scale) else null

    fun correct(raw: Vec3): Vec3 = if (ready) (raw - bias) * scale else raw

    fun addResting(raw: Vec3): Boolean {
        val n = raw.length()
        if (n < 1e-3f) return false
        val key = bin(raw.x / n) * 9 + bin(raw.y / n) * 3 + bin(raw.z / n)
        val s = sums.getOrPut(key) { DoubleArray(4) }
        s[0] += raw.x.toDouble()
        s[1] += raw.y.toDouble()
        s[2] += raw.z.toDouble()
        s[3] += 1.0
        return s[3] % SOLVE_EVERY == 0.0 && solve()
    }

    private fun solve(): Boolean {
        val points = sums.values.filter { it[3] >= MIN_SAMPLES }.map { doubleArrayOf(it[0] / it[3], it[1] / it[3], it[2] / it[3]) }
        if (points.size < MIN_BINS || smallestSpread(points) < MIN_SPREAD) return false
        val ata = Array(4) { DoubleArray(4) }
        val atb = DoubleArray(4)
        for (p in points) {
            val row = doubleArrayOf(2 * p[0], 2 * p[1], 2 * p[2], 1.0)
            val rhs = p[0] * p[0] + p[1] * p[1] + p[2] * p[2]
            for (i in 0..3) {
                atb[i] += row[i] * rhs
                for (j in 0..3) ata[i][j] += row[i] * row[j]
            }
        }
        val x = solve4(ata, atb) ?: return false
        val radius2 = x[3] + x[0] * x[0] + x[1] * x[1] + x[2] * x[2]
        if (radius2 <= 0) return false
        val b = Vec3(x[0].toFloat(), x[1].toFloat(), x[2].toFloat())
        val s = (GRAVITY / sqrt(radius2)).toFloat()
        if (!plausible(b, s)) return false
        bias = b
        scale = s
        ready = true
        return true
    }

    private fun bin(v: Float) = (v * 1.5f).roundToInt().coerceIn(-1, 1) + 1

    companion object {
        private const val GRAVITY = 9.80665
        private const val MIN_SAMPLES = 20.0
        private const val MIN_BINS = 5
        private const val MIN_SPREAD = 2.0
        private const val SOLVE_EVERY = 25.0
        private const val MAX_BIAS = 1.5f
        private const val MAX_SCALE_ERROR = 0.1f

        private fun plausible(b: Vec3, s: Float) = b.length() <= MAX_BIAS && abs(s - 1f) <= MAX_SCALE_ERROR

        private fun smallestSpread(points: List<DoubleArray>): Double {
            val mean = DoubleArray(3) { i -> points.sumOf { it[i] } / points.size }
            val c = Array(3) { DoubleArray(3) }
            for (p in points) for (i in 0..2) for (j in 0..2) c[i][j] += (p[i] - mean[i]) * (p[j] - mean[j])
            return sqrt(maxOf(smallestEigenvalue(c), 0.0))
        }

        private fun smallestEigenvalue(m: Array<DoubleArray>): Double {
            val a = Array(3) { m[it].copyOf() }
            repeat(50) {
                var p = 0
                var q = 1
                for ((i, j) in listOf(0 to 2, 1 to 2)) if (abs(a[i][j]) > abs(a[p][q])) { p = i; q = j }
                if (abs(a[p][q]) < 1e-12) return minOf(a[0][0], a[1][1], a[2][2])
                val theta = (a[q][q] - a[p][p]) / (2 * a[p][q])
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1)
                val s = t * c
                for (k in 0..2) {
                    val kp = a[k][p]
                    val kq = a[k][q]
                    a[k][p] = c * kp - s * kq
                    a[k][q] = s * kp + c * kq
                }
                for (k in 0..2) {
                    val pk = a[p][k]
                    val qk = a[q][k]
                    a[p][k] = c * pk - s * qk
                    a[q][k] = s * pk + c * qk
                }
            }
            return minOf(a[0][0], a[1][1], a[2][2])
        }

        private fun solve4(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val m = Array(4) { i -> DoubleArray(5) { j -> if (j < 4) a[i][j] else b[i] } }
            for (col in 0..3) {
                val pivot = (col..3).maxBy { abs(m[it][col]) }
                if (abs(m[pivot][col]) < 1e-9) return null
                val swap = m[col]
                m[col] = m[pivot]
                m[pivot] = swap
                for (r in 0..3) {
                    if (r == col) continue
                    val f = m[r][col] / m[col][col]
                    for (k in col..4) m[r][k] -= f * m[col][k]
                }
            }
            return DoubleArray(4) { m[it][4] / m[it][it] }
        }
    }
}

internal class JoyConMotion {
    var velocity = Vec3(0f, 0f, 0f)
        private set
    var displacement = Vec3(0f, 0f, 0f)
        private set
    var inertial = Vec3(0f, 0f, 0f)
        private set
    var resting = false
        private set
    private var gravity = Float.NaN
    private var average = Vec3(0f, 0f, 0f)
    private var calmFor = 0f
    private var stillFor = 0f
    private var movingFor = 0f

    fun update(rotation: Quaternion, accel: Vec3, rate: Vec3, calibrated: Boolean, dt: Float) {
        if (dt <= 0f) return
        if (gravity.isNaN()) {
            gravity = accel.length()
            average = accel
        }
        if (calibrated) gravity = GRAVITY
        average += (accel - average) * (dt / AVERAGE_SECONDS).coerceAtMost(1f)
        val world = rotation.rotate(accel)
        val linear = Vec3(world.x, world.y - gravity, world.z)
        inertial = inertial * exp(-dt / INERTIAL_SECONDS) + linear * dt
        val calm = rate.length() < CALM_RATE && (accel - average).length() < CALM_ACCEL
        calmFor = if (calm) calmFor + dt else 0f
        val still = rate.length() < CALM_RATE &&
            (linear.length() < STILL_ACCEL || calmFor >= if (calibrated) CALIBRATED_CALM_SECONDS else UNCALIBRATED_CALM_SECONDS)
        stillFor = if (still) stillFor + dt else 0f
        resting = calmFor >= RESTING_SECONDS && rate.length() < RESTING_RATE
        if (stillFor >= STILL_SECONDS) {
            movingFor = 0f
            velocity *= exp(-dt / SETTLE_SECONDS)
            if (!calibrated) gravity += (accel.length() - gravity) * (dt / GRAVITY_SECONDS).coerceAtMost(1f)
        } else {
            movingFor += dt
            val leak = if (movingFor < FREE_SECONDS) LEAK_SECONDS else LONG_MOVE_LEAK_SECONDS
            velocity = velocity * exp(-dt / leak) + linear * dt
        }
        displacement += velocity * dt
    }

    companion object {
        private const val GRAVITY = 9.80665f
        private const val AVERAGE_SECONDS = 0.1f
        private const val CALM_RATE = 0.2f
        private const val CALM_ACCEL = 0.25f
        private const val STILL_ACCEL = 0.12f
        private const val STILL_SECONDS = 0.2f
        private const val UNCALIBRATED_CALM_SECONDS = 0.5f
        private const val CALIBRATED_CALM_SECONDS = 2f
        private const val SETTLE_SECONDS = 0.05f
        private const val LEAK_SECONDS = 20f
        private const val FREE_SECONDS = 1f
        private const val LONG_MOVE_LEAK_SECONDS = 0.2f
        private const val GRAVITY_SECONDS = 0.5f
        private const val INERTIAL_SECONDS = 30f
        private const val RESTING_SECONDS = 0.3f
        private const val RESTING_RATE = 0.05f
    }
}

internal object JoyConCalibrations {
    fun load(context: Context, descriptor: String?): FloatArray? {
        if (descriptor.isNullOrEmpty()) return null
        val text = ControllerPrefs.prefs(context).getString(KEY + descriptor, null) ?: return null
        return text.split(',').mapNotNull { it.toFloatOrNull() }.toFloatArray().takeIf { it.size == 4 }
    }

    fun save(context: Context, descriptor: String?, values: FloatArray?) {
        if (descriptor.isNullOrEmpty() || values == null) return
        ControllerPrefs.prefs(context).edit().putString(KEY + descriptor, values.joinToString(",")).apply()
    }

    private const val KEY = "joyConAccel."
}
