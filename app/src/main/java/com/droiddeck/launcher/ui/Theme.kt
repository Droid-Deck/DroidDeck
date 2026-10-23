package com.droiddeck.launcher.ui

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.droiddeck.launcher.R
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import com.droiddeck.launcher.core.AppUiPrefs
import com.droiddeck.launcher.session.SessionPrefs

class Palette(
    val id: String, @StringRes val label: Int, @StringRes val detail: Int,
    val background: Color, val surface: Color, val surfaceVariant: Color, val line: Color, val line2: Color,
    val onBackground: Color, val onSurfaceVariant: Color,
    val primary: Color, val primary2: Color, val onPrimary: Color, val signal: Color,
    val good: Color, val error: Color = Color(0xFFFF8A80),
)

object Themes {
    const val GRAPHITE = "graphite"
    const val PAPER = "paper"
    const val ICON_BLUE = "blue"
    const val ELECTRIC = "electric"
    const val INNER_GAMER = "inner_gamer"
    const val GRADIENT = "gradient"

    const val DEFAULT_PRIMARY = 0xFF8B5CF6.toInt()
    const val DEFAULT_SECONDARY = 0xFF22D3EE.toInt()

    /** The two colours of the custom Gradient theme: primary, then secondary. Changing them restyles the app at once. */
    var gradientColors by mutableStateOf(DEFAULT_PRIMARY to DEFAULT_SECONDARY)
        private set

    const val RGB_SPEEDS = 10
    const val DEFAULT_RGB_SPEED = 3
    const val RGB_HUE_STEP = 3f

    /** How fast Inner Gamer cycles, 1 (slow) to [RGB_SPEEDS] (fast). */
    var rgbSpeed by mutableStateOf(DEFAULT_RGB_SPEED)
        private set

    /** Where Inner Gamer is on the colour wheel, in whole [RGB_HUE_STEP] steps so the app restyles only when the colour visibly moves. */
    var rgbHue by mutableStateOf(0f)
        private set

    fun moveRgbHue(hue: Float) {
        rgbHue = hue
    }

    fun setRgbSpeed(context: Context, speed: Int) {
        val value = speed.coerceIn(1, RGB_SPEEDS)
        SessionPrefs.setRgbSpeed(context, value)
        rgbSpeed = value
    }

    /** Milliseconds for one trip round the colour wheel. */
    fun rgbCycleMillis(speed: Int): Int = 30_000 / speed.coerceIn(1, RGB_SPEEDS)

    fun loadGradient(context: Context) {
        gradientColors = SessionPrefs.gradientColors(context)
        rgbSpeed = SessionPrefs.rgbSpeed(context)
    }

    /** The Inner Gamer palette at one point on the colour wheel. */
    fun innerGamer(hue: Float): Palette = ThemeColors.palette(
        Color.hsv(hue % 360f, 0.9f, 1f).toArgb(), Color.hsv((hue + 60f) % 360f, 0.9f, 1f).toArgb(),
        R.string.theme_inner_gamer, R.string.theme_inner_gamer_detail, INNER_GAMER,
    )

    fun setGradient(context: Context, primary: Int, secondary: Int) {
        SessionPrefs.setGradientColors(context, primary, secondary)
        gradientColors = primary to secondary
    }

    /** The theme the person chose, with the custom colours read first; for code that is not in a composition. */
    fun current(context: Context): Palette {
        val id = SessionPrefs.theme(context)
        if (id == GRADIENT || id == INNER_GAMER) loadGradient(context)
        return byId(id)
    }

    val all: List<Palette> = listOf(
        Palette(
            GRAPHITE, R.string.theme_graphite, R.string.theme_graphite_detail,
            background = Color(0xFF0A0B0D), surface = Color(0xFF121417), surfaceVariant = Color(0xFF1A1D22), line = Color(0xFF262A31), line2 = Color(0xFF343A43),
            onBackground = Color(0xFFF2F4F7), onSurfaceVariant = Color(0xFF9AA3AF),
            primary = Color(0xFF1A9FFF), primary2 = Color(0xFF1487DB), onPrimary = Color(0xFF03111F), signal = Color(0xFF1A9FFF),
            good = Color(0xFF4CD37F),
        ),
        Palette(
            PAPER, R.string.theme_paper, R.string.theme_paper_detail,
            background = Color(0xFF000000), surface = Color(0xFF0F0F10), surfaceVariant = Color(0xFF191A1C), line = Color(0xFF262729), line2 = Color(0xFF343638),
            onBackground = Color(0xFFF9F9F9), onSurfaceVariant = Color(0xFF8E9196),
            primary = Color(0xFFF9F9F9), primary2 = Color(0xFFD9DADD), onPrimary = Color(0xFF000000), signal = Color(0xFFF9F9F9),
            good = Color(0xFF4CD37F),
        ),
        Palette(
            ICON_BLUE, R.string.theme_icon_blue, R.string.theme_icon_blue_detail,
            background = Color(0xFF050608), surface = Color(0xFF0E1116), surfaceVariant = Color(0xFF161B23), line = Color(0xFF1F2630), line2 = Color(0xFF2A3340),
            onBackground = Color(0xFFF2F4F7), onSurfaceVariant = Color(0xFF8A93A0),
            primary = Color(0xFF136CE0), primary2 = Color(0xFF0B4FB0), onPrimary = Color(0xFFFFFFFF), signal = Color(0xFF136CE0),
            good = Color(0xFF4CD37F),
        ),
        Palette(
            ELECTRIC, R.string.theme_electric, R.string.theme_electric_detail,
            background = Color(0xFF070A10), surface = Color(0xFF0F1522), surfaceVariant = Color(0xFF16203A), line = Color(0xFF1E2A47), line2 = Color(0xFF2A3A5E),
            onBackground = Color(0xFFEEF3FF), onSurfaceVariant = Color(0xFF8B9AB8),
            primary = Color(0xFF2E86FF), primary2 = Color(0xFF2684FF), onPrimary = Color(0xFF03111F), signal = Color(0xFF2E86FF),
            good = Color(0xFF4CD37F),
        ),
        innerGamer(0f),
        ThemeColors.palette(DEFAULT_PRIMARY, DEFAULT_SECONDARY, R.string.theme_gradient, R.string.theme_gradient_detail),
    )

    fun byId(id: String): Palette {
        if (id == INNER_GAMER) return innerGamer(rgbHue)
        if (id == GRADIENT) return ThemeColors.palette(gradientColors.first, gradientColors.second, R.string.theme_gradient, R.string.theme_gradient_detail)
        return all.firstOrNull { it.id == id } ?: all[0]
    }
}

val LocalPalette = staticCompositionLocalOf { Themes.byId(Themes.GRAPHITE) }

val Palette.onSignal: Color get() = if (signal.luminance() > 0.18f) Color(0xFF03111F) else Color.White

@Composable
fun DroidDeckTheme(theme: String? = null, appScale: Int = AppUiPrefs.DEFAULT_SCALE, content: @Composable () -> Unit) {
    val id = theme ?: SessionPrefs.theme(LocalContext.current)
    if (id == Themes.INNER_GAMER) InnerGamerHue(Themes.rgbSpeed)
    val p = Themes.byId(id)
    val scheme = darkColorScheme(
        primary = p.primary, onPrimary = p.onPrimary, secondary = p.onSurfaceVariant,
        background = p.background, onBackground = p.onBackground,
        surface = p.surface, onSurface = p.onBackground,
        surfaceVariant = p.surfaceVariant, onSurfaceVariant = p.onSurfaceVariant,
        error = p.error,
    )
    val baseDensity = LocalDensity.current
    val baseConfiguration = LocalConfiguration.current
    val density = remember(baseDensity, appScale) { scaledAppDensity(baseDensity, appScale) }
    val configuration = remember(baseConfiguration, appScale) { scaledAppConfiguration(baseConfiguration, appScale) }
    CompositionLocalProvider(
        LocalPalette provides p,
        LocalDensity provides density,
        LocalConfiguration provides configuration,
    ) { MaterialTheme(colorScheme = scheme, content = content) }
}

/** Turns the colour wheel on the system's own animation clock; with animations off, in the app or in Android, the colour holds still. */
@Composable
private fun InnerGamerHue(speed: Int) {
    if (Motion.scale == 0f) return
    key(speed) { InnerGamerCycle(speed) }
}

/** One run of the cycle at one speed, picking up from the colour on screen so a new speed takes over at once without a jump. */
@Composable
private fun InnerGamerCycle(speed: Int) {
    val start = remember { Themes.rgbHue }
    val hue by rememberInfiniteTransition(label = "innerGamer").animateFloat(
        start, start + 360f, infiniteRepeatable(tween(Themes.rgbCycleMillis(speed).coerceAtLeast(1), easing = LinearEasing), RepeatMode.Restart), label = "hue",
    )
    val stepped = ((hue % 360f) / Themes.RGB_HUE_STEP).toInt() * Themes.RGB_HUE_STEP
    SideEffect { Themes.moveRgbHue(stepped) }
}
