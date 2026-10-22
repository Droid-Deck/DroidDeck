package com.droiddeck.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min

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

    /** The colour, made brighter when it is too dark to show on the dark background. */
    fun readable(argb: Int): Int {
        if (Color(argb).luminance() >= MIN_LUMINANCE) return argb or 0xFF000000.toInt()
        val hsv = toHsv(argb)
        var v = hsv.v
        var out = argb
        while (v < 1f && Color(out).luminance() < MIN_LUMINANCE) {
            v = min(1f, v + 0.04f)
            out = fromHsv(hsv.h, hsv.s, v)
        }
        return out
    }

    /** A dark neutral tinted with the primary's hue; plain greys stay grey. */
    fun tint(primary: Int, saturation: Float, brightness: Float): Color {
        val hsv = toHsv(primary)
        val strength = (hsv.s / 0.35f).coerceIn(0f, 1f)
        return Color(fromHsv(hsv.h, saturation * strength, brightness))
    }

    fun palette(primary: Int, secondary: Int, label: Int, detail: Int): Palette {
        val first = readable(primary)
        val second = readable(secondary)
        val ink = (Color(first).luminance() + Color(second).luminance()) / 2f
        return Palette(
            Themes.GRADIENT, label, detail,
            background = tint(first, 0.50f, 0.045f), surface = tint(first, 0.45f, 0.075f), surfaceVariant = tint(first, 0.42f, 0.115f),
            line = tint(first, 0.40f, 0.16f), line2 = tint(first, 0.36f, 0.22f),
            onBackground = tint(first, 0.05f, 0.97f), onSurfaceVariant = tint(first, 0.16f, 0.68f),
            primary = Color(first), primary2 = Color(second), onPrimary = if (ink > 0.18f) Color(0xFF03111F) else Color.White, signal = Color(first),
            good = Color(0xFF4CD37F),
        )
    }
}
