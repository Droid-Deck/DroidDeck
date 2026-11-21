package com.droiddeck.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.droiddeck.launcher.input.InputControl
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal class SteamDeckButtonArt(context: Context) {
    private val density = context.resources.displayMetrics.density
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val arm = Path()
    private val bar = Path()
    private val rect = RectF()
    private var shineTop = Float.NaN
    private var shineHeight = Float.NaN

    private fun dp(value: Float) = value * density

    private fun glass(canvas: Canvas, shape: Path, pressed: Boolean, tint: Int = BODY) {
        shape.computeBounds(rect, false)
        body.color = if (pressed) PRESSED else tint
        body.setShadowLayer(dp(2.5f), 0f, dp(1.5f), SHADOW)
        canvas.drawPath(shape, body)
        body.clearShadowLayer()
        if (!pressed) {
            if (rect.top != shineTop || rect.height() != shineHeight) {
                shineTop = rect.top; shineHeight = rect.height()
                shine.shader = LinearGradient(0f, rect.top, 0f, rect.top + rect.height() * .55f, SHINE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            }
            canvas.drawPath(shape, shine)
        }
        edge.color = if (pressed) EDGE_PRESSED else EDGE
        edge.strokeWidth = dp(1.25f)
        canvas.drawPath(shape, edge)
    }

    private fun circle(cx: Float, cy: Float, r: Float): Path = path.apply { reset(); addCircle(cx, cy, r, Path.Direction.CW) }

    private fun roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float): Path = path.apply {
        reset(); addRoundRect(RectF(l, t, r, b), radius, radius, Path.Direction.CW)
    }

    private fun corners(l: Float, t: Float, r: Float, b: Float, tl: Float, tr: Float, br: Float, bl: Float): Path = path.apply {
        reset(); addRoundRect(RectF(l, t, r, b), floatArrayOf(tl, tl, tr, tr, br, br, bl, bl), Path.Direction.CW)
    }

    private fun text(canvas: Canvas, value: String, cx: Float, cy: Float, size: Float, color: Int, maxWidth: Float = Float.MAX_VALUE) {
        ink.textSize = size
        val measured = ink.measureText(value)
        if (measured > maxWidth) ink.textSize = size * maxWidth / measured
        ink.color = color
        canvas.drawText(value, cx, cy - (ink.ascent() + ink.descent()) / 2f, ink)
    }

    fun button(canvas: Canvas, width: Int, height: Int, control: InputControl, pressed: Boolean): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        if (min(w, h) <= dp(4f)) return false
        when (control) {
            InputControl.A, InputControl.B, InputControl.X, InputControl.Y -> {
                val radius = min(w, h) / 2f - dp(2f)
                glass(canvas, circle(w / 2f, h / 2f, radius), pressed)
                text(canvas, control.label, w / 2f, h / 2f, radius * .95f, if (pressed) ON_ACCENT else INK)
            }
            InputControl.LB, InputControl.RB, InputControl.LT, InputControl.RT -> shoulder(canvas, w, h, control, pressed)
            InputControl.L4, InputControl.L5, InputControl.R4, InputControl.R5 -> grip(canvas, w, h, control.label, pressed)
            InputControl.SELECT -> system(canvas, w, h, Shape.CHIP, Icon.VIEW, pressed)
            InputControl.START -> system(canvas, w, h, Shape.CHIP, Icon.MENU, pressed)
            InputControl.GUIDE -> system(canvas, w, h, Shape.PILL, Icon.STEAM, pressed)
            InputControl.QAM -> system(canvas, w, h, Shape.PILL, Icon.QAM, pressed)
            else -> return false
        }
        return true
    }

    fun keyboard(canvas: Canvas, w: Int, h: Int, pressed: Boolean) =
        system(canvas, w.toFloat(), h.toFloat(), Shape.ROUND, Icon.KEYBOARD, pressed)

    private fun shoulder(canvas: Canvas, w: Float, h: Float, control: InputControl, pressed: Boolean) {
        val inset = dp(2f)
        val l = inset; val t = inset; val r = w - inset; val b = h - inset
        val trigger = control == InputControl.LT || control == InputControl.RT
        val big = (b - t) * if (trigger) .9f else .62f
        val small = dp(5f)
        val left = control == InputControl.LT || control == InputControl.LB
        glass(canvas, if (left) corners(l, t, r, b, big, small, small, small) else corners(l, t, r, b, small, big, small, small), pressed)
        text(canvas, when (control) {
            InputControl.LB -> "L1"
            InputControl.RB -> "R1"
            InputControl.LT -> "L2"
            else -> "R2"
        }, w / 2f, h / 2f, (b - t) * .46f, if (pressed) ON_ACCENT else INK, (r - l) * .8f)
    }

    /** A back grip: a capsule along the long side of its box, at most 2.2 times as long as it is thick. */
    private fun grip(canvas: Canvas, w: Float, h: Float, label: String, pressed: Boolean) {
        val inset = dp(3f)
        val thick = (min(w, h) - 2 * inset).coerceAtLeast(1f)
        val long = (max(w, h) - 2 * inset).coerceAtMost(thick * 2.2f)
        val bw = if (w >= h) long else thick; val bh = if (w >= h) thick else long
        val cx = w / 2f; val cy = h / 2f
        glass(canvas, roundRect(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2, thick / 2), pressed)
        text(canvas, label, cx, cy, thick * .42f, if (pressed) ON_ACCENT else INK_SOFT)
    }

    private enum class Shape { ROUND, PILL, CHIP }
    private enum class Icon { VIEW, MENU, QAM, KEYBOARD, STEAM }

    private fun system(canvas: Canvas, w: Float, h: Float, shape: Shape, icon: Icon, pressed: Boolean, ink: Int = INK) {
        val cx = w / 2f; val cy = h / 2f
        val size = when (shape) {
            Shape.ROUND -> { val r = min(w, h) / 2f * VISUAL; glass(canvas, circle(cx, cy, r), pressed); r * 1.2f }
            Shape.PILL -> { val vh = min(h * VISUAL, w * VISUAL / 2.3f); val vw = vh * 2.3f
                glass(canvas, roundRect(cx - vw / 2, cy - vh / 2, cx + vw / 2, cy + vh / 2, vh / 2), pressed); vh * 1.1f }
            Shape.CHIP -> { val vh = min(h * VISUAL, w * VISUAL / 1.9f); val vw = vh * 1.9f
                glass(canvas, roundRect(cx - vw / 2, cy - vh / 2, cx + vw / 2, cy + vh / 2, vh * .32f), pressed); vh * 1.05f }
        }
        icon(canvas, cx, cy, size, icon, if (pressed) ON_ACCENT else ink)
    }

    private fun icon(canvas: Canvas, cx: Float, cy: Float, size: Float, icon: Icon, color: Int) {
        val u = size / 28f
        line.color = color; line.strokeWidth = 3.2f * u
        fill.color = color; fill.shader = null
        when (icon) {
            Icon.VIEW -> {
                canvas.drawRoundRect(RectF(cx - 12 * u, cy - 9 * u, cx + 3 * u, cy + 3 * u), 3 * u, 3 * u, line)
                fill.color = Color.argb(230, 14, 16, 20)
                canvas.drawRoundRect(RectF(cx - 4 * u, cy - 3 * u, cx + 11 * u, cy + 9 * u), 3 * u, 3 * u, fill)
                canvas.drawRoundRect(RectF(cx - 4 * u, cy - 3 * u, cx + 11 * u, cy + 9 * u), 3 * u, 3 * u, line)
            }
            Icon.MENU -> for (dy in intArrayOf(-7, 0, 7)) canvas.drawLine(cx - 11 * u, cy + dy * u, cx + 11 * u, cy + dy * u, line)
            Icon.QAM -> for (dx in intArrayOf(-10, 0, 10)) canvas.drawCircle(cx + dx * u, cy, 3.4f * u, fill)
            Icon.KEYBOARD -> {
                canvas.drawRoundRect(RectF(cx - 16 * u, cy - 11 * u, cx + 16 * u, cy + 11 * u), 4.5f * u, 4.5f * u, line)
                for (row in 0..1) for (col in 0..3) canvas.drawCircle(cx + (-9 + col * 6) * u, cy + (-4 + row * 5) * u, 1.7f * u, fill)
                line.strokeWidth = 2.6f * u; canvas.drawLine(cx - 6 * u, cy + 6 * u, cx + 6 * u, cy + 6 * u, line)
            }
            Icon.STEAM -> {
                ink.letterSpacing = .12f
                text(canvas, "STEAM", cx, cy, 14 * u, color, size * 1.9f)
                ink.letterSpacing = 0f
            }
        }
    }

    private fun chevron(canvas: Canvas, ax: Float, ay: Float, dx: Float, dy: Float, k: Float, color: Int, width: Float) {
        val px = -dy; val py = dx
        line.color = color; line.strokeWidth = width
        canvas.drawPath(path.apply {
            reset(); moveTo(ax - dx * k * .5f + px * k, ay - dy * k * .5f + py * k); lineTo(ax + dx * k * .6f, ay + dy * k * .6f)
            lineTo(ax - dx * k * .5f - px * k, ay - dy * k * .5f - py * k)
        }, line)
    }

    private fun cross(target: Path, cx: Float, cy: Float, s: Float, h: Float, rc: Float) = target.apply {
        reset()
        addRoundRect(RectF(cx - h, cy - s, cx + h, cy + s), rc, rc, Path.Direction.CW)
        bar.reset()
        bar.addRoundRect(RectF(cx - s, cy - h, cx + s, cy + h), rc, rc, Path.Direction.CW)
        op(bar, Path.Op.UNION)
    }

    fun dpad(canvas: Canvas, w: Float, h: Float, held: Int) {
        val cx = w / 2f
        val cy = h / 2f
        val arms = min(w, h) / 2f - dp(3f)
        if (arms <= 0f) return
        val half = arms * .37f
        glass(canvas, cross(arm, cx, cy, arms, half, half * .43f), false)
        for (i in 0..3) {
            val dx = when (i) { 1 -> 1f; 3 -> -1f; else -> 0f }
            val dy = when (i) { 0 -> -1f; 2 -> 1f; else -> 0f }
            val on = held and (1 shl i) != 0
            if (on && half > dp(2f)) {
                fill.shader = null
                fill.color = PRESSED
                val near = half + dp(1f)
                val far = arms - dp(2f)
                val side = half - dp(2f)
                rect.set(if (dx == 0f) cx - side else if (dx > 0) cx + near else cx - far,
                    if (dy == 0f) cy - side else if (dy > 0) cy + near else cy - far,
                    if (dx == 0f) cx + side else if (dx > 0) cx + far else cx - near,
                    if (dy == 0f) cy + side else if (dy > 0) cy + far else cy - near)
                canvas.drawRoundRect(rect, half * .4f, half * .4f, fill)
            }
            val m = (arms + half) / 2f
            chevron(canvas, cx + dx * m, cy + dy * m, dx, dy, half * .32f, if (on) ON_ACCENT else INK, half * .17f)
        }
        fill.shader = null
        fill.color = Color.argb(18, 255, 255, 255)
        canvas.drawCircle(cx, cy, half * .36f, fill)
    }

    fun stick(canvas: Canvas, cx: Float, cy: Float, radius: Float, kx: Float, ky: Float, active: Boolean) {
        fill.alpha = 255
        fill.shader = RadialGradient(cx, cy, radius, WELL_IN, WELL_OUT, Shader.TileMode.CLAMP)
        fill.setShadowLayer(dp(2.5f), 0f, dp(1.5f), SHADOW)
        canvas.drawCircle(cx, cy, radius, fill)
        fill.clearShadowLayer(); fill.shader = null
        edge.color = Color.argb(61, 255, 255, 255); edge.strokeWidth = dp(1.25f)
        canvas.drawCircle(cx, cy, radius, edge)
        line.color = Color.argb(71, 255, 255, 255); line.strokeWidth = dp(1.4f)
        for (i in 0 until 8) {
            val a = i * Math.PI / 4
            val c = cos(a).toFloat(); val n = sin(a).toFloat()
            canvas.drawLine(cx + c * (radius - dp(7f)), cy + n * (radius - dp(7f)), cx + c * (radius - dp(3.5f)), cy + n * (radius - dp(3.5f)), line)
        }
        if (active && (kx != 0f || ky != 0f)) {
            val angle = Math.toDegrees(atan2(ky, kx).toDouble()).toFloat()
            line.color = ACCENT; line.strokeWidth = dp(3f)
            val r = radius - dp(2f)
            canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), angle - 35f, 70f, false, line)
        }
        val k = radius * .46f
        val x = cx + kx; val y = cy + ky
        line.color = if (active) ACCENT else Color.argb(140, 226, 232, 240)
        line.strokeWidth = dp(2.8f)
        canvas.drawCircle(x, y, k * 1.14f, line)
        glass(canvas, circle(x, y, k), false)
        edge.color = Color.argb(36, 255, 255, 255)
        edge.strokeWidth = dp(1f)
        canvas.drawCircle(x, y, k * .66f, edge)
    }

    fun trackpad(canvas: Canvas, w: Float, h: Float, touching: Boolean, fx: Float, fy: Float) {
        if (w <= 0f || h <= 0f) return
        val inset = dp(1.5f)
        val radius = min(w, h) * .14f
        fill.shader = null; fill.color = Color.argb(51, 14, 16, 20)
        rect.set(inset, inset, w - inset, h - inset)
        canvas.drawRoundRect(rect, radius, radius, fill)
        edge.color = if (touching) Color.argb(191, 0x1A, 0x9F, 0xFF) else Color.argb(51, 255, 255, 255)
        edge.strokeWidth = dp(if (touching) 1.5f else 1f)
        canvas.drawRoundRect(rect, radius, radius, edge)
        if (!touching) return
        val glow = min(w, h) * .3f
        fill.shader = RadialGradient(fx, fy, glow, GLOW, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(fx, fy, glow, fill); fill.shader = null
        fill.color = ACCENT; canvas.drawCircle(fx, fy, dp(5f), fill)
    }

    companion object {
        private const val VISUAL = .72f
        private val ACCENT = Color.rgb(0x1A, 0x9F, 0xFF)
        private val ON_ACCENT = Color.rgb(0x03, 0x11, 0x1F)
        val INK = Color.rgb(0xF4, 0xF7, 0xFB)
        private val INK_SOFT = Color.argb(209, 0xF4, 0xF7, 0xFB)
        private val BODY = Color.argb(133, 14, 16, 20)
        private val PRESSED = Color.argb(235, 0x1A, 0x9F, 0xFF)
        private val SHINE = Color.argb(43, 255, 255, 255)
        private val EDGE = Color.argb(77, 255, 255, 255)
        private val EDGE_PRESSED = Color.argb(140, 255, 255, 255)
        private val SHADOW = Color.argb(97, 0, 0, 0)
        private val WELL_IN = Color.argb(140, 5, 7, 10)
        private val WELL_OUT = Color.argb(71, 14, 16, 20)
        private val GLOW = Color.argb(140, 0x1A, 0x9F, 0xFF)
    }
}
