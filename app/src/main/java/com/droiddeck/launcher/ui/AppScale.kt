package com.droiddeck.launcher.ui

import android.content.res.Configuration
import androidx.compose.ui.unit.Density
import com.droiddeck.launcher.core.AppUiPrefs
import kotlin.math.roundToInt

internal fun scaledAppDensity(base: Density, percent: Int): Density {
    val scale = AppUiPrefs.normalizeScale(percent) / 100f
    return if (scale == 1f) base else Density(base.density * scale, base.fontScale)
}

/** Responsive layouts and popup height limits must see the same dp space as the scaled UI. */
internal fun scaledAppConfiguration(base: Configuration, percent: Int): Configuration {
    val scale = AppUiPrefs.normalizeScale(percent) / 100f
    if (scale == 1f) return base
    return Configuration(base).apply {
        if (densityDpi > 0) densityDpi = (densityDpi * scale).roundToInt()
        if (screenWidthDp > 0) screenWidthDp = (screenWidthDp / scale).roundToInt()
        if (screenHeightDp > 0) screenHeightDp = (screenHeightDp / scale).roundToInt()
        if (smallestScreenWidthDp > 0) smallestScreenWidthDp = (smallestScreenWidthDp / scale).roundToInt()
    }
}
