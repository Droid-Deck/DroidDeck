package com.droiddeck.launcher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator

/** CSS-style cubic-bezier easing; the y control points may leave 0..1 to overshoot. */
internal class CubicEase(private val x1: Float, private val y1: Float, private val x2: Float, private val y2: Float) : TimeInterpolator {
    private fun at(t: Float, a: Float, b: Float): Float { val u = 1f - t; return 3f * u * u * t * a + 3f * u * t * t * b + t * t * t }

    override fun getInterpolation(input: Float): Float {
        if (input <= 0f) return 0f
        if (input >= 1f) return 1f
        var lo = 0f; var hi = 1f; var t = input
        for (i in 0 until 24) {
            if (at(t, x1, x2) < input) lo = t else hi = t
            t = (lo + hi) / 2f
        }
        return at(t, y1, y2)
    }

    companion object {
        val EASE = CubicEase(.2f, .8f, .2f, 1f)
        val POP = CubicEase(.3f, 1.5f, .4f, 1f)
    }
}

internal fun motionOn() = Motion.scale > 0f

internal fun lerpColor(from: Int, to: Int, f: Float): Int {
    fun channel(shift: Int) = ((from ushr shift and 0xFF) + ((to ushr shift and 0xFF) - (from ushr shift and 0xFF)) * f).toInt().coerceIn(0, 255) shl shift
    return channel(24) or channel(16) or channel(8) or channel(0)
}

internal fun withAlpha(color: Int, alpha: Float) = (color and 0xFFFFFF) or ((((color ushr 24) * alpha).toInt().coerceIn(0, 255)) shl 24)

/** One animated float that redraws [owner] as it changes. Jumps straight to the end when the app's animations are off. */
internal class FloatAnim(private val owner: View, start: Float) {
    var value = start; private set
    private var from = start
    private var to = start
    private var ease: TimeInterpolator = CubicEase.EASE
    private var onEnd: (() -> Unit)? = null
    private var cancelled = false
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        interpolator = LinearInterpolator()
        addUpdateListener { value = from + (to - from) * ease.getInterpolation(it.animatedFraction); owner.invalidate() }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) { cancelled = false }
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) {
                if (cancelled) return
                value = to; owner.invalidate()
                onEnd?.also { onEnd = null }?.invoke()
            }
        })
    }

    fun animate(from: Float, to: Float, duration: Long, ease: TimeInterpolator = CubicEase.EASE, onEnd: (() -> Unit)? = null) {
        animator.cancel()
        this.from = from; this.to = to; this.ease = ease; this.onEnd = onEnd
        if (!motionOn() || duration <= 0L) { snap(to); this.onEnd = null; onEnd?.invoke(); return }
        value = from
        animator.duration = duration
        animator.start()
    }

    fun snap(value: Float) { animator.cancel(); this.value = value; owner.invalidate() }
}
