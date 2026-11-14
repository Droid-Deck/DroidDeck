package com.droiddeck.launcher.input

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

object PenAxes {
    const val AXIS_MAX = 65535

    fun pressure(value: Float): Int = scale(value)

    fun distance(value: Float, min: Float, max: Float): Int = if (max <= min) 0 else scale((value - min) / (max - min))

    fun tilt(tilt: Float, orientation: Float, out: IntArray): Boolean {
        if (tilt == 0f) return false
        val s = sin(orientation.toDouble())
        val c = cos(orientation.toDouble())
        val k = s * s * c * c
        val t = sin(tilt.toDouble()).let { it * it }
        val r = sqrt(2 * t / (1 + sqrt((1 - 4 * k * t).coerceAtLeast(0.0))))
        out[0] = centiDegrees(asin((-r * s).coerceIn(-1.0, 1.0)))
        out[1] = centiDegrees(asin((r * c).coerceIn(-1.0, 1.0)))
        return true
    }

    private fun centiDegrees(radians: Double): Int = round(radians * 18_000 / PI).toInt()

    private fun scale(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * AXIS_MAX + 0.5f).toInt()
}
