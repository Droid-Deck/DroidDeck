package com.droiddeck.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import com.droiddeck.launcher.input.DpadStyle
import com.droiddeck.launcher.input.FaceGlyphs
import com.droiddeck.launcher.input.InputControl
import com.droiddeck.launcher.input.PadKit
import com.droiddeck.launcher.input.StickCap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The on-screen controls' shared drawing: one glass material, shaped per virtual controller. */
internal class PadArt(context: Context) {
    val density = context.resources.displayMetrics.density
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

    fun dp(value: Float) = value * density

    fun glass(canvas: Canvas, shape: Path, pressed: Boolean, tint: Int = BODY) {
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

    fun circle(cx: Float, cy: Float, r: Float): Path = path.apply { reset(); addCircle(cx, cy, r, Path.Direction.CW) }

    fun roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float): Path = path.apply {
        reset(); addRoundRect(RectF(l, t, r, b), radius, radius, Path.Direction.CW)
    }

    private fun corners(l: Float, t: Float, r: Float, b: Float, tl: Float, tr: Float, br: Float, bl: Float): Path = path.apply {
        reset(); addRoundRect(RectF(l, t, r, b), floatArrayOf(tl, tl, tr, tr, br, br, bl, bl), Path.Direction.CW)
    }

    fun text(canvas: Canvas, value: String, cx: Float, cy: Float, size: Float, color: Int, maxWidth: Float = Float.MAX_VALUE) {
        ink.textSize = size
        val measured = ink.measureText(value)
        if (measured > maxWidth) ink.textSize = size * maxWidth / measured
        ink.color = color
        canvas.drawText(value, cx, cy - (ink.ascent() + ink.descent()) / 2f, ink)
    }

    fun face(canvas: Canvas, cx: Float, cy: Float, r: Float, control: InputControl, kit: PadKit, pressed: Boolean) {
        glass(canvas, circle(cx, cy, r), pressed)
        when (kit.face) {
            FaceGlyphs.COLOR_SYMBOLS -> symbol(canvas, cx, cy, r, control, if (pressed) ON_ACCENT else SYMBOL_COLORS[control] ?: INK)
            FaceGlyphs.MONO_SYMBOLS -> symbol(canvas, cx, cy, r, control, if (pressed) ON_ACCENT else INK_SOFT)
            FaceGlyphs.COLOR_LETTERS -> text(canvas, control.label, cx, cy, r * .95f, if (pressed) ON_ACCENT else LETTER_COLORS[control] ?: INK)
            FaceGlyphs.MONO_LETTERS -> text(canvas, control.label, cx, cy, r * .95f, if (pressed) ON_ACCENT else INK)
        }
    }

    private fun symbol(canvas: Canvas, cx: Float, cy: Float, r: Float, control: InputControl, color: Int) {
        val s = r * .4f
        line.color = color; line.strokeWidth = r * .12f
        when (control) {
            InputControl.A -> { canvas.drawLine(cx - s, cy - s, cx + s, cy + s, line); canvas.drawLine(cx + s, cy - s, cx - s, cy + s, line) }
            InputControl.B -> canvas.drawCircle(cx, cy, s * 1.05f, line)
            InputControl.X -> canvas.drawRoundRect(RectF(cx - s * .95f, cy - s * .95f, cx + s * .95f, cy + s * .95f), s * .18f, s * .18f, line)
            else -> canvas.drawPath(path.apply { reset(); moveTo(cx, cy - s * 1.1f); lineTo(cx + s * 1.1f, cy + s * .85f); lineTo(cx - s * 1.1f, cy + s * .85f); close() }, line)
        }
    }

    fun shoulder(canvas: Canvas, w: Float, h: Float, control: InputControl, kit: PadKit, pressed: Boolean) {
        val inset = dp(2f)
        val l = inset; val t = inset; val r = w - inset; val b = h - inset
        val trigger = control == InputControl.LT || control == InputControl.RT
        val big = (b - t) * if (trigger) .9f else .62f
        val small = dp(5f)
        val left = control == InputControl.LT || control == InputControl.LB
        glass(canvas, if (left) corners(l, t, r, b, big, small, small, small) else corners(l, t, r, b, small, big, small, small), pressed)
        text(canvas, kit.label(control), w / 2f, h / 2f, (b - t) * .46f, if (pressed) ON_ACCENT else INK, (r - l) * .8f)
    }

    /** A back grip: a capsule along the long side of its box, at most 2.2 times as long as it is thick. */
    fun grip(canvas: Canvas, w: Float, h: Float, label: String, pressed: Boolean) {
        val inset = dp(3f)
        val thick = (min(w, h) - 2 * inset).coerceAtLeast(1f)
        val long = (max(w, h) - 2 * inset).coerceAtMost(thick * 2.2f)
        val bw = if (w >= h) long else thick; val bh = if (w >= h) thick else long
        val cx = w / 2f; val cy = h / 2f
        glass(canvas, roundRect(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2, thick / 2), pressed)
        text(canvas, label, cx, cy, thick * .42f, if (pressed) ON_ACCENT else INK_SOFT)
    }

    fun small(canvas: Canvas, w: Float, h: Float, label: String, pressed: Boolean) {
        val r = min(w, h) / 2f * VISUAL
        glass(canvas, circle(w / 2f, h / 2f, r), pressed)
        text(canvas, label, w / 2f, h / 2f, r * .62f, if (pressed) ON_ACCENT else INK)
    }

    enum class Shape { ROUND, PILL, CHIP }
    enum class Icon { VIEW, MENU, QAM, KEYBOARD, BACK, START, CREATE, HOME, STEAM, SHARE, OPTIONS, MARK }

    fun system(canvas: Canvas, w: Float, h: Float, shape: Shape, icon: Icon, pressed: Boolean, ink: Int = INK) {
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

    fun icon(canvas: Canvas, cx: Float, cy: Float, size: Float, icon: Icon, color: Int) {
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
            Icon.BACK, Icon.START -> {
                val f = if (icon == Icon.START) 1f else -1f
                line.strokeWidth = 2.6f * u
                canvas.drawPath(path.apply { reset(); moveTo(cx - 6 * f * u, cy - 8 * u); lineTo(cx + 7 * f * u, cy); lineTo(cx - 6 * f * u, cy + 8 * u); close() }, line)
            }
            Icon.CREATE -> {
                line.strokeWidth = 2.8f * u
                canvas.drawLine(cx - 8 * u, cy + 8 * u, cx - 2 * u, cy - 8 * u, line)
                canvas.drawLine(cx + u, cy + 8 * u, cx + 4 * u, cy - 2 * u, line)
                canvas.drawLine(cx + 8 * u, cy + 8 * u, cx + 9 * u, cy + 3 * u, line)
            }
            Icon.HOME -> canvas.drawPath(path.apply {
                reset(); moveTo(cx - 13 * u, cy - u); lineTo(cx, cy - 13 * u); lineTo(cx + 13 * u, cy - u)
                moveTo(cx - 9 * u, cy - 4 * u); lineTo(cx - 9 * u, cy + 11 * u); lineTo(cx + 9 * u, cy + 11 * u); lineTo(cx + 9 * u, cy - 4 * u)
            }, line)
            Icon.STEAM, Icon.SHARE, Icon.OPTIONS -> {
                ink.letterSpacing = .12f
                text(canvas, when (icon) { Icon.STEAM -> "STEAM"; Icon.SHARE -> "SHARE"; else -> "OPTIONS" }, cx, cy, 14 * u, color, size * 1.9f)
                ink.letterSpacing = 0f
            }
            Icon.MARK -> mark(canvas, cx, cy, size * .5f, color, if (color == ON_ACCENT) ON_ACCENT else ACCENT)
        }
    }

    fun mark(canvas: Canvas, cx: Float, cy: Float, radius: Float, ink: Int, orb: Int) {
        val k = radius / 111.86f
        canvas.save()
        canvas.translate(cx - 71.98f * k, cy - 111.86f * k)
        canvas.scale(k, k)
        fill.shader = null; fill.color = ink
        canvas.drawPath(MARK, fill)
        fill.color = orb
        canvas.drawCircle(63.98f, 111.86f, 55.98f, fill)
        canvas.restore()
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

    /** [held] bits: up 1, right 2, down 4, left 8. */
    fun dpad(canvas: Canvas, w: Float, h: Float, kit: PadKit, held: Int, chevrons: Boolean = true, ink: Int = INK) {
        val cx = w / 2f; val cy = h / 2f
        val s = min(w, h) / 2f - dp(3f)
        val dirs = arrayOf(floatArrayOf(0f, -1f), floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(-1f, 0f))
        when (kit.dpad) {
            DpadStyle.CROSS, DpadStyle.DISC -> {
                val disc = kit.dpad == DpadStyle.DISC
                val arms = if (disc) s * .78f else s
                val half = if (disc) s * .25f else s * .37f
                if (disc) {
                    glass(canvas, circle(cx, cy, s), false)
                    fill.shader = null; fill.color = Color.argb(18, 255, 255, 255)
                    canvas.drawPath(cross(arm, cx, cy, arms, half, half * .5f), fill)
                    edge.color = Color.argb(56, 255, 255, 255); edge.strokeWidth = dp(1f)
                    canvas.drawPath(arm, edge)
                } else glass(canvas, cross(arm, cx, cy, arms, half, half * .43f), false)
                for (i in 0..3) {
                    val dx = dirs[i][0]; val dy = dirs[i][1]
                    val on = held and (1 shl i) != 0
                    if (on) {
                        fill.shader = null; fill.color = PRESSED
                        val near = half + dp(1f); val far = arms - dp(2f); val side = half - dp(2f)
                        rect.set(if (dx == 0f) cx - side else if (dx > 0) cx + near else cx - far, if (dy == 0f) cy - side else if (dy > 0) cy + near else cy - far,
                            if (dx == 0f) cx + side else if (dx > 0) cx + far else cx - near, if (dy == 0f) cy + side else if (dy > 0) cy + far else cy - near)
                        canvas.drawRoundRect(rect, half * .4f, half * .4f, fill)
                    }
                    val m = (arms + half) / 2f
                    if (chevrons) chevron(canvas, cx + dx * m, cy + dy * m, dx, dy, half * .32f, if (on) ON_ACCENT else ink, half * .17f)
                }
                if (!disc) { fill.shader = null; fill.color = Color.argb(18, 255, 255, 255); canvas.drawCircle(cx, cy, half * .36f, fill) }
            }
            DpadStyle.ARROWS -> {
                fill.shader = null; fill.color = Color.argb(56, 0, 0, 0)
                canvas.drawCircle(cx, cy, s, fill)
                edge.color = Color.argb(36, 255, 255, 255); edge.strokeWidth = dp(1f)
                canvas.drawCircle(cx, cy, s, edge)
                val bw = s * .5f; val tip = s * .14f; val base = s * .38f; val top = s * .9f; val rc = s * .11f
                for (i in 0..3) {
                    canvas.save()
                    canvas.rotate(i * 90f, cx, cy)
                    arm.reset()
                    arm.moveTo(cx, cy - tip); arm.lineTo(cx - bw / 2, cy - base); arm.lineTo(cx - bw / 2, cy - top + rc)
                    arm.quadTo(cx - bw / 2, cy - top, cx - bw / 2 + rc, cy - top); arm.lineTo(cx + bw / 2 - rc, cy - top)
                    arm.quadTo(cx + bw / 2, cy - top, cx + bw / 2, cy - top + rc); arm.lineTo(cx + bw / 2, cy - base); arm.close()
                    val on = held and (1 shl i) != 0
                    glass(canvas, arm, on)
                    val ty = cy - (base + top) / 2f; val ts = s * .07f
                    fill.shader = null; fill.color = if (on) ON_ACCENT else INK_SOFT
                    if (chevrons) canvas.drawPath(path.apply { reset(); moveTo(cx, ty - ts); lineTo(cx + ts, ty + ts * .7f); lineTo(cx - ts, ty + ts * .7f); close() }, fill)
                    canvas.restore()
                }
            }
        }
    }

    fun stick(canvas: Canvas, cx: Float, cy: Float, radius: Float, kit: PadKit, kx: Float, ky: Float, active: Boolean) {
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
        if (kit.stick == StickCap.RINGED) {
            line.color = if (active) ACCENT else Color.argb(140, 226, 232, 240); line.strokeWidth = dp(2.8f)
            canvas.drawCircle(x, y, k * 1.14f, line)
        }
        glass(canvas, circle(x, y, k), false)
        when (kit.stick) {
            StickCap.DISHED -> {
                fill.shader = RadialGradient(x, y - k * .1f, k * .74f, Color.argb(107, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawCircle(x, y, k * .74f, fill); fill.shader = null
                edge.color = if (active) ACCENT else Color.argb(51, 255, 255, 255); edge.strokeWidth = dp(1f)
                canvas.drawCircle(x, y, k * .74f, edge)
            }
            StickCap.KNURLED -> {
                line.color = if (active) ACCENT else Color.argb(66, 255, 255, 255); line.strokeWidth = dp(2.3f)
                line.pathEffect = DashPathEffect(floatArrayOf(dp(1.2f), dp(1.9f)), 0f)
                canvas.drawCircle(x, y, k * .9f, line); line.pathEffect = null
                fill.color = Color.argb(56, 0, 0, 0); canvas.drawCircle(x, y, k * .6f, fill)
            }
            StickCap.RINGED -> { edge.color = Color.argb(36, 255, 255, 255); edge.strokeWidth = dp(1f); canvas.drawCircle(x, y, k * .66f, edge) }
        }
    }

    fun trackpad(canvas: Canvas, w: Float, h: Float, touching: Boolean, fx: Float, fy: Float) {
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

    fun label(canvas: Canvas, w: Float, h: Float, round: Boolean, value: String, pressed: Boolean) {
        val inset = dp(2f)
        glass(canvas, if (round) circle(w / 2f, h / 2f, min(w, h) / 2f - inset) else roundRect(inset, inset, w - inset, h - inset, min(w, h) * .25f), pressed)
        text(canvas, value, w / 2f, h / 2f, min(h * .38f, dp(18f)), if (pressed) ON_ACCENT else INK, w * .82f)
    }

    companion object {
        const val VISUAL = .72f
        val ACCENT = Color.rgb(0x1A, 0x9F, 0xFF)
        val ON_ACCENT = Color.rgb(0x03, 0x11, 0x1F)
        val INK = Color.rgb(0xF4, 0xF7, 0xFB)
        val INK_SOFT = Color.argb(209, 0xF4, 0xF7, 0xFB)
        private val BODY = Color.argb(133, 14, 16, 20)
        private val PRESSED = Color.argb(235, 0x1A, 0x9F, 0xFF)
        private val SHINE = Color.argb(43, 255, 255, 255)
        private val EDGE = Color.argb(77, 255, 255, 255)
        private val EDGE_PRESSED = Color.argb(140, 255, 255, 255)
        private val SHADOW = Color.argb(97, 0, 0, 0)
        private val WELL_IN = Color.argb(140, 5, 7, 10)
        private val WELL_OUT = Color.argb(71, 14, 16, 20)
        private val GLOW = Color.argb(140, 0x1A, 0x9F, 0xFF)
        private val LETTER_COLORS = mapOf(InputControl.A to Color.rgb(0x5C, 0xCB, 0x5F), InputControl.B to Color.rgb(0xF0, 0x50, 0x4A),
            InputControl.X to Color.rgb(0x4C, 0x9B, 0xF0), InputControl.Y to Color.rgb(0xF5, 0xC0, 0x2E))
        private val SYMBOL_COLORS = mapOf(InputControl.A to Color.rgb(0x8D, 0xB4, 0xF2), InputControl.B to Color.rgb(0xF2, 0x61, 0x6B),
            InputControl.X to Color.rgb(0xEE, 0x93, 0xD6), InputControl.Y to Color.rgb(0x45, 0xCB, 0xAA))
        private const val MARK_DATA = "M-31.98 0H56.055V40.489A71.81 71.81 0 0 0 56.055 183.231V223.72H-31.98Z" +
            "M71.885 0.28A111.86 111.86 0 0 1 71.885 223.44V183.234A71.81 71.81 0 0 0 71.885 40.486Z"
        val MARK: Path by lazy { PathParser.createPathFromPathData(MARK_DATA) }
    }
}
