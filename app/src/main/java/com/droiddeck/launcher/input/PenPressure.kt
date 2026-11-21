package com.droiddeck.launcher.input

import com.droiddeck.launcher.session.SessionPrefs
import java.util.Arrays

class PenPressure private constructor(x1: Float, y1: Float, x2: Float, y2: Float) {
    private val xs = FloatArray(STEPS + 1)
    private val ys = FloatArray(STEPS + 1)

    init {
        for (i in 1 until STEPS) {
            val t = i.toDouble() / STEPS
            xs[i] = bezier(t, x1, x2)
            ys[i] = bezier(t, y1, y2)
        }
        xs[STEPS] = 1f
        ys[STEPS] = 1f
    }

    fun map(pressure: Float): Float {
        if (pressure.isNaN() || pressure <= 0f) return 0f
        if (pressure >= 1f) return 1f
        val found = Arrays.binarySearch(xs, pressure)
        if (found >= 0) return ys[found]
        val i = -found - 1
        return ys[i - 1] + (ys[i] - ys[i - 1]) * (pressure - xs[i - 1]) / (xs[i] - xs[i - 1])
    }

    companion object {
        private const val STEPS = 1024
        private val SOFT = PenPressure(0f, 0.25f, 0.75f, 1f)
        private val FIRM = PenPressure(0.25f, 0f, 1f, 0.75f)

        fun forPreset(preset: String): PenPressure? = when (preset) {
            SessionPrefs.PEN_PRESSURE_SOFT -> SOFT
            SessionPrefs.PEN_PRESSURE_FIRM -> FIRM
            else -> null
        }

        private fun bezier(t: Double, p1: Float, p2: Float): Float {
            val u = 1 - t
            return (3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t).toFloat()
        }
    }
}
