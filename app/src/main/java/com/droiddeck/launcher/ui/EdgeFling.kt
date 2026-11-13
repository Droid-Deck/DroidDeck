package com.droiddeck.launcher.ui

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Where something dragged and let go against the side edges, like a chat head, comes to rest:
 * a quick sideways flick sends it to the side it was thrown toward from wherever it is; a slower
 * release goes to the side its coasting would carry it to; and its height carries on with the
 * throw. Velocities are pixels per second.
 */
internal object EdgeFling {
    /** How far a throw coasts, in seconds of its release velocity. */
    const val COAST_S = 0.18f
    /** A sideways speed past this many dp per second picks its side outright. */
    private const val FLICK_DP_S = 600f

    fun toRight(centerX: Float, span: Float, vx: Float, density: Float): Boolean {
        if (abs(vx) > FLICK_DP_S * density) return vx > 0
        return centerX + vx * COAST_S > span / 2
    }

    fun restY(y: Float, vy: Float, min: Float, max: Float) = (y + vy * COAST_S).coerceIn(min, max.coerceAtLeast(min))
}

/**
 * A spring in two dimensions that starts with the velocity it was let go with, stepped on each
 * display frame, so a throw flows into its settle instead of starting over from rest.
 */
internal class FlingSpring(
    private val stiffness: Float = 340f,
    dampingRatio: Float = 0.74f,
    private val onFrame: (Float, Float) -> Unit,
) : Choreographer.FrameCallback {
    private val damping = 2f * dampingRatio * sqrt(stiffness)
    private var x = 0f
    private var y = 0f
    private var vx = 0f
    private var vy = 0f
    private var toX = 0f
    private var toY = 0f
    private var last = 0L
    private var onEnd: (() -> Unit)? = null
    var running = false
        private set

    fun start(fromX: Float, fromY: Float, velocityX: Float, velocityY: Float, targetX: Float, targetY: Float, end: (() -> Unit)? = null) {
        cancel()
        x = fromX; y = fromY; vx = velocityX; vy = velocityY; toX = targetX; toY = targetY
        onEnd = end
        if (Motion.scale == 0f) {
            onFrame(toX, toY)
            end?.invoke()
            return
        }
        running = true
        last = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun cancel() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (last == 0L) 1f / 120f else ((frameTimeNanos - last) / 1e9f).coerceIn(0f, 1f / 20f)
        last = frameTimeNanos
        // Small fixed steps keep the spring stable however long a frame took.
        var left = dt / Motion.scale.coerceAtLeast(0.1f)
        while (left > 0f) {
            val h = minOf(left, 1f / 480f)
            vx += (-stiffness * (x - toX) - damping * vx) * h
            vy += (-stiffness * (y - toY) - damping * vy) * h
            x += vx * h
            y += vy * h
            left -= h
        }
        val settled = abs(x - toX) < 0.5f && abs(y - toY) < 0.5f && abs(vx) < 20f && abs(vy) < 20f
        if (settled) {
            running = false
            onFrame(toX, toY)
            onEnd?.invoke()
            return
        }
        onFrame(x, y)
        Choreographer.getInstance().postFrameCallback(this)
    }
}
