package com.droiddeck.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimaryButtonContrastTest {
    @Test fun electricTextButtonsRemainReadableOnPageAndDialogSurfaces() {
        val palette = Themes.byId(Themes.ELECTRIC)
        for (background in listOf(palette.background, palette.surface)) {
            assertTrue("Electric text button: ${contrast(palette.primary, background)}",
                contrast(palette.primary, background) >= 4.5f)
        }
    }

    @Test fun normalSizedPrimaryTextMeetsContrastAcrossEveryThemeGradient() {
        for (palette in Themes.all) {
            for (step in 0..100) {
                val fraction = step / 100f
                val start = palette.primary
                val end = palette.primary2
                val background = Color(
                    start.red + (end.red - start.red) * fraction,
                    start.green + (end.green - start.green) * fraction,
                    start.blue + (end.blue - start.blue) * fraction,
                )
                val ratio = contrast(palette.onPrimary, background)
                assertTrue("${palette.id} gradient $step: $ratio", ratio >= 4.5f)
            }
        }
    }

    private fun contrast(a: Color, b: Color): Float {
        val light = maxOf(a.luminance(), b.luminance())
        val dark = minOf(a.luminance(), b.luminance())
        return (light + 0.05f) / (dark + 0.05f)
    }
}
