package com.droiddeck.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Colour maths for the custom gradient theme: hue, saturation and brightness in, a readable palette out. */
internal object ThemeColors {
    /** Hue 0..360, saturation and brightness 0..1. */
    class Hsv(val h: Float, val s: Float, val v: Float)

    fun toHsv(argb: Int): Hsv {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val top = max(r, max(g, b))
        val delta = top - min(r, min(g, b))
        val hue = when {
            delta == 0f -> 0f
            top == r -> 60f * (((g - b) / delta) % 6f)
            top == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return Hsv(if (hue < 0f) hue + 360f else hue, if (top == 0f) 0f else delta / top, top)
    }

    fun fromHsv(h: Float, s: Float, v: Float): Int =
        Color.hsv(h.mod(360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)).toArgb()

    fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    /** `#RRGGBB`, `RRGGBB` or `#RGB`; null for anything else. */
    fun parseHex(text: String): Int? {
        val digits = text.trim().removePrefix("#")
        if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val full = when (digits.length) {
            3 -> digits.map { "$it$it" }.joinToString("")
            6 -> digits
            else -> return null
        }
        return 0xFF000000.toInt() or full.toInt(16)
    }

    private const val MIN_LUMINANCE = 0.10f

    /** The colour, made brighter (then paler, for a deep blue that brightness alone cannot lift) when it is too dark to show on the dark background. */
    fun readable(argb: Int): Int {
        if (Color(argb).luminance() >= MIN_LUMINANCE) return argb or 0xFF000000.toInt()
        val hsv = toHsv(argb)
        var v = hsv.v
        var s = hsv.s
        var out = argb
        while (v < 1f && Color(out).luminance() < MIN_LUMINANCE) {
            v = min(1f, v + 0.04f)
            out = fromHsv(hsv.h, s, v)
        }
        while (s > 0f && Color(out).luminance() < MIN_LUMINANCE) {
            s = max(0f, s - 0.04f)
            out = fromHsv(hsv.h, s, v)
        }
        return out
    }

    /** A dark neutral tinted with the primary's hue; plain greys stay grey. */
    fun tint(primary: Int, saturation: Float, brightness: Float): Color {
        val hsv = toHsv(primary)
        val strength = (hsv.s / 0.35f).coerceIn(0f, 1f)
        return Color(fromHsv(hsv.h, saturation * strength, brightness))
    }

    private val DARK_INK = Color(0xFF03111F)

    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (max(x, y) + 0.05f) / (min(x, y) + 0.05f)
    }

    /** Dark or white text, whichever reads better on the worse of the two gradient colours. */
    internal fun inkFor(first: Int, second: Int): Color {
        fun worst(ink: Color) = min(contrast(ink, Color(first)), contrast(ink, Color(second)))
        return if (worst(DARK_INK) >= worst(Color.White)) DARK_INK else Color.White
    }

    private const val MIN_CONTRAST = 4.5f

    /** The colour nudged just enough, lighter for dark text and darker for white text, to give [ink] a readable contrast on it. */
    private fun fitTo(ink: Color, argb: Int): Int {
        fun short(color: Int) = contrast(ink, Color(color)) < MIN_CONTRAST
        if (!short(argb)) return argb
        val hsv = toHsv(argb)
        var v = hsv.v
        var s = hsv.s
        var out = argb
        if (ink == DARK_INK) {
            while (v < 1f && short(out)) { v = min(1f, v + 0.04f); out = fromHsv(hsv.h, s, v) }
            while (s > 0f && short(out)) { s = max(0f, s - 0.04f); out = fromHsv(hsv.h, s, v) }
        } else {
            while (v > 0f && short(out)) { v = max(0f, v - 0.04f); out = fromHsv(hsv.h, s, v) }
        }
        return out
    }

    private fun gradientColor(first: Int, second: Int, fraction: Float): Int {
        fun channel(shift: Int): Int {
            val a = first shr shift and 0xFF
            val b = second shr shift and 0xFF
            return (a + (b - a) * fraction).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun gradientFits(ink: Color, first: Int, second: Int): Boolean =
        (0..32).all { step ->
            val color = gradientColor(first, second, step / 32f)
            contrast(ink, Color(color)) >= MIN_CONTRAST + 0.02f
        }

    private fun moveTowardContrast(ink: Color, argb: Int): Int {
        val hsv = toHsv(argb)
        return if (ink == DARK_INK) {
            when {
                hsv.v < 1f -> fromHsv(hsv.h, hsv.s, min(1f, hsv.v + 0.04f))
                hsv.s > 0f -> fromHsv(hsv.h, max(0f, hsv.s - 0.04f), hsv.v)
                else -> argb
            }
        } else {
            fromHsv(hsv.h, hsv.s, max(0f, hsv.v - 0.04f))
        }
    }

    fun palette(primary: Int, secondary: Int, label: Int, detail: Int): Palette {
        val seen = readable(primary)
        val seenSecond = readable(secondary)
        val ink = inkFor(seen, seenSecond)
        var first = fitTo(ink, seen)
        var second = fitTo(ink, seenSecond)
        while (!gradientFits(ink, first, second)) {
            val nextFirst = moveTowardContrast(ink, first)
            val nextSecond = moveTowardContrast(ink, second)
            if (nextFirst == first && nextSecond == second) break
            first = nextFirst
            second = nextSecond
        }
        return Palette(
            Themes.GRADIENT, label, detail,
            background = tint(first, 0.50f, 0.045f), surface = tint(first, 0.45f, 0.075f), surfaceVariant = tint(first, 0.42f, 0.115f),
            line = tint(first, 0.40f, 0.16f), line2 = tint(first, 0.36f, 0.22f),
            onBackground = tint(first, 0.05f, 0.97f), onSurfaceVariant = tint(first, 0.16f, 0.68f),
            primary = Color(first), primary2 = Color(second), onPrimary = ink, signal = Color(first),
            good = Color(0xFF4CD37F),
        )
    }
}
