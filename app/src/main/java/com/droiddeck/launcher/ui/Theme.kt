package com.droiddeck.launcher.ui

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
            primary = Color(0xFFF9F9F9), primary2 = Color(0xFFD9DADD), onPrimary = Color(0xFF000000), signal = Color(0xFF136CE0),
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
            primary = Color(0xFF2E86FF), primary2 = Color(0xFF136CE0), onPrimary = Color(0xFFFFFFFF), signal = Color(0xFF2E86FF),
            good = Color(0xFF4CD37F),
        ),
    )

    fun byId(id: String): Palette = all.firstOrNull { it.id == id } ?: all[0]
}

val LocalPalette = staticCompositionLocalOf { Themes.byId(Themes.GRAPHITE) }

/**
 * Text and icons drawn on a [Palette.signal] fill: near-black where the blue is light enough that
 * white would not read (Graphite's #1A9FFF gives white 2.8:1), white where it is deep.
 */
val Palette.onSignal: Color get() = if (signal.luminance() > 0.18f) Color(0xFF03111F) else Color.White

@Composable
fun DroidDeckTheme(theme: String? = null, appScale: Int = AppUiPrefs.DEFAULT_SCALE, content: @Composable () -> Unit) {
    val id = theme ?: SessionPrefs.theme(LocalContext.current)
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
