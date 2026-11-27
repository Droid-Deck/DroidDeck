package com.droiddeck.launcher.ui

import android.animation.ValueAnimator
import android.graphics.Color
import android.view.View
import android.view.animation.PathInterpolator

internal class ControlLook {
    var accent = SIGNAL; private set
    var ink = NEUTRAL_INK; private set
    var stroke = alpha(NEUTRAL_INK, NEUTRAL_STROKE); private set
    private var hotFill = GLASS
    private var latchedFill = GLASS
    private var pressedFill = GLASS

    init { tint(null) }

    fun tint(tint: Int?) {
        accent = (tint ?: SIGNAL) or OPAQUE
        ink = tint?.let { light(it, INK_LIGHT) } ?: NEUTRAL_INK
        stroke = if (tint == null) alpha(NEUTRAL_INK, NEUTRAL_STROKE) else alpha(accent, STROKE)
        hotFill = over(alpha(accent, HOT), GLASS)
        latchedFill = over(alpha(accent, LATCHED), GLASS)
        pressedFill = over(alpha(accent, PRESSED), GLASS)
    }

    fun fill(press: Float, hot: Float = 0f, latched: Boolean = false) =
        mix(mix(if (latched) latchedFill else GLASS, hotFill, hot), pressedFill, press)

    fun stroke(press: Float, hot: Float = 0f) = mix(stroke, accent, maxOf(press, hot))

    fun ink(press: Float) = mix(ink, Color.WHITE, press)

    companion object {
        const val STROKE = .55f
        const val HOT = .14f
        const val LATCHED = .16f
        const val PRESSED = .45f
        const val GLOW = .40f
        const val GLOW_HOT = .22f
        const val INK_LIGHT = .77f
        private const val NEUTRAL_STROKE = .22f
        private const val OPAQUE = 0xFF shl 24
        val SIGNAL = Color.rgb(0x1A, 0x9F, 0xFF)
        val NEUTRAL_INK = Color.rgb(0xF2, 0xF4, 0xF7)
        val GLASS = Color.argb(140, 0x0A, 0x0B, 0x0D)
        val BACKDROP = Color.rgb(0x0A, 0x0B, 0x0D)

        fun alpha(color: Int, alpha: Float) = (color and 0xFFFFFF) or ((alpha * 255f + .5f).toInt().coerceIn(0, 255) shl 24)

        fun light(color: Int, f: Float) = Color.rgb(
            (Color.red(color) + (255 - Color.red(color)) * f).toInt(),
            (Color.green(color) + (255 - Color.green(color)) * f).toInt(),
            (Color.blue(color) + (255 - Color.blue(color)) * f).toInt())

        fun mix(from: Int, to: Int, t: Float): Int {
            if (t <= 0f) return from
            if (t >= 1f) return to
            fun channel(shift: Int) = ((from ushr shift and 0xFF) + ((to ushr shift and 0xFF) - (from ushr shift and 0xFF)) * t + .5f).toInt() shl shift
            return channel(24) or channel(16) or channel(8) or channel(0)
        }

        fun over(top: Int, bottom: Int): Int {
            val ta = Color.alpha(top) / 255f
            val ba = Color.alpha(bottom) / 255f
            val a = ta + ba * (1f - ta)
            if (a <= 0f) return Color.TRANSPARENT
            fun channel(t: Int, b: Int) = ((t * ta + b * ba * (1f - ta)) / a + .5f).toInt()
            return Color.argb((a * 255f + .5f).toInt(), channel(Color.red(top), Color.red(bottom)),
                channel(Color.green(top), Color.green(bottom)), channel(Color.blue(top), Color.blue(bottom)))
        }
    }
}

/** Press progress for one control: 0 at rest, 1 fully pressed; release springs back past 0. */
internal class ControlMotion(private val view: View, private val squash: Boolean) : ValueAnimator.AnimatorUpdateListener {
    var value = 0f; private set
    val shown get() = value.coerceIn(0f, 1f)
    private var from = 0f
    private var to = 0f
    private var animator: ValueAnimator? = null

    fun press(down: Boolean) {
        val target = if (down) 1f else 0f
        if (to == target) return
        to = target
        if (Motion.scale == 0f || !view.isAttachedToWindow) { snap(target); return }
        from = value
        val running = animator ?: ValueAnimator.ofFloat(0f, 1f).also { it.addUpdateListener(this); animator = it }
        running.cancel()
        running.duration = if (down) PRESS_MS else RELEASE_MS
        running.interpolator = if (down) EASE else EASE_POP
        running.start()
    }

    fun snap(target: Float) {
        animator?.cancel()
        to = target
        set(target)
    }

    override fun onAnimationUpdate(animation: ValueAnimator) = set(from + (to - from) * animation.animatedFraction)

    private fun set(v: Float) {
        value = v
        if (squash) { val scale = 1f - SQUASH * v; view.scaleX = scale; view.scaleY = scale }
        view.invalidate()
    }

    companion object {
        const val SQUASH = .06f
        const val PRESS_MS = 70L
        const val RELEASE_MS = 160L
        private val EASE = PathInterpolator(.2f, .8f, .2f, 1f)
        private val EASE_POP = PathInterpolator(.3f, 1.5f, .4f, 1f)
    }
}
