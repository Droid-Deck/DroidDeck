package com.droiddeck.launcher.glasses

import kotlin.math.hypot

internal object JoyConFlowGate {
    const val MAX_SEED_AGE_NS = 750_000_000L
    fun fresh(seed: Long, previous: Long, now: Long) = seed > 0 && previous > 0 &&
        now > previous && now - previous <= 200_000_000L && now - seed in 0..MAX_SEED_AGE_NS

    fun point(x: Double, y: Double, backX: Double, backY: Double, oldX: Double, oldY: Double,
              error: Float, forward: Boolean, backward: Boolean, width: Int, height: Int): Boolean =
        forward && backward && error.isFinite() && error in 0f..20f &&
            x.isFinite() && y.isFinite() && backX.isFinite() && backY.isFinite() &&
            x >= 3 && y >= 3 && x < width - 3 && y < height - 3 &&
            hypot(backX - oldX, backY - oldY) <= 1.5 && hypot(x - oldX, y - oldY) <= 100
}
