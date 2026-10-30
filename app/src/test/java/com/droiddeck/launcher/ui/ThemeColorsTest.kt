package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.droiddeck.launcher.session.SessionPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThemeColorsTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        app.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun near(expected: Int, actual: Int) {
        for (shift in listOf(16, 8, 0)) assertTrue("${ThemeColors.hex(expected)} vs ${ThemeColors.hex(actual)}", Math.abs((expected shr shift and 0xFF) - (actual shr shift and 0xFF)) <= 1)
    }

    @Test fun hueSaturationAndBrightnessComeBackAsTheSameColour() {
        for (argb in listOf(0xFF8B5CF6.toInt(), 0xFF22D3EE.toInt(), 0xFFFF0000.toInt(), 0xFF336699.toInt(), 0xFF808080.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
            val hsv = ThemeColors.toHsv(argb)
            near(argb, ThemeColors.fromHsv(hsv.h, hsv.s, hsv.v))
        }
    }

    @Test fun hexIsReadWithOrWithoutTheHashAndInShortForm() {
        assertEquals(0xFF8B5CF6.toInt(), ThemeColors.parseHex("#8B5CF6"))
        assertEquals(0xFF8B5CF6.toInt(), ThemeColors.parseHex("8b5cf6"))
        assertEquals(0xFFFF00AA.toInt(), ThemeColors.parseHex(" #f0a "))
        assertEquals("#8B5CF6", ThemeColors.hex(0xFF8B5CF6.toInt()))
        for (bad in listOf("", "#", "#12", "#1234", "#1234567", "zzzzzz", "#12345g")) assertNull(bad, ThemeColors.parseHex(bad))
    }

    @Test fun aTooDarkColourIsLiftedAndABrightOneIsLeftAlone() {
        val dark = ThemeColors.readable(0xFF050505.toInt())
        assertTrue(Color(dark).luminance() >= 0.10f)
        assertEquals(0xFF8B5CF6.toInt(), ThemeColors.readable(0xFF8B5CF6.toInt()))
    }

    @Test fun aDeepBlueThatBrightnessAloneCannotLiftIsMadePaler() {
        assertTrue(Color(ThemeColors.readable(0xFF0000FF.toInt())).luminance() >= 0.10f)
        assertTrue(Color(ThemeColors.readable(0xFF000000.toInt())).luminance() >= 0.10f)
    }

    @Test fun theTextColourSuitsTheWorseOfTheTwoGradientColours() {
        assertEquals(Color(0xFF03111F), ThemeColors.inkFor(0xFFFFEB3B.toInt(), 0xFF3B4FA8.toInt()))
        assertEquals(Color.White, ThemeColors.inkFor(0xFF3B4FA8.toInt(), 0xFF5B2FA8.toInt()))
        assertEquals(Color(0xFF03111F), ThemeColors.inkFor(0xFFFFFFFF.toInt(), 0xFFFFEB3B.toInt()))
    }

    private fun contrast(a: Color, b: Color) = (maxOf(a.luminance(), b.luminance()) + 0.05f) / (minOf(a.luminance(), b.luminance()) + 0.05f)

    @Test fun buttonTextIsReadableOnBothGradientColoursForAnyPair() {
        val picks = listOf(0xFFFFEB3B, 0xFF1E3A8A, 0xFF0000FF, 0xFFFF0000, 0xFF00FF00, 0xFF808080, 0xFFFFFFFF, 0xFF000000, 0xFF8B5CF6, 0xFF22D3EE, 0xFFFF5722, 0xFF3F51B5)
        for (a in picks) for (b in picks) {
            val p = ThemeColors.palette(a.toInt(), b.toInt(), 0, 0)
            val first = p.primary.toArgb()
            val second = p.primary2.toArgb()
            for (step in 0..100) {
                val fraction = step / 100f
                val color = Color(
                    0xFF000000.toInt() or
                        (channel(first, 16, second, fraction) shl 16) or
                        (channel(first, 8, second, fraction) shl 8) or
                        channel(first, 0, second, fraction),
                )
                assertTrue(
                    "${ThemeColors.hex(first)} ${ThemeColors.hex(second)} at $fraction",
                    contrast(p.onPrimary, color) >= 4.5f,
                )
            }
        }
    }

    private fun channel(first: Int, shift: Int, second: Int, fraction: Float): Int {
        val start = first shr shift and 0xFF
        val end = second shr shift and 0xFF
        return (start + (end - start) * fraction).toInt().coerceIn(0, 255)
    }

    @Test fun onlyTheStopThatFailsIsNudged() {
        val p = ThemeColors.palette(0xFFFFEB3B.toInt(), 0xFF1E3A8A.toInt(), 0, 0)
        assertEquals(Color(0xFFFFEB3B), p.primary)
        assertTrue(p.primary2 != Color(0xFF1E3A8A))
        assertEquals(Color(0xFF03111F), p.onPrimary)
    }

    @Test fun thePaletteKeepsTheTwoColoursAndStaysDarkAndReadable() {
        val p = ThemeColors.palette(0xFF8B5CF6.toInt(), 0xFF22D3EE.toInt(), 0, 0)
        assertEquals(p.primary, p.signal)
        assertEquals(Color(0xFF22D3EE), p.primary2)
        assertTrue(contrast(p.onPrimary, p.primary) >= 4.5f)
        assertTrue(contrast(p.onPrimary, p.primary2) >= 4.5f)
        assertTrue(p.background.luminance() < p.surface.luminance())
        assertTrue(p.surface.luminance() < p.surfaceVariant.luminance())
        assertTrue(p.background.luminance() < 0.02f)
        assertTrue(p.onBackground.luminance() > 0.8f)
        assertEquals(Color(0xFF03111F), ThemeColors.palette(0xFFFFEB3B.toInt(), 0xFFFFFFFF.toInt(), 0, 0).onPrimary)
        assertEquals(Color.White, ThemeColors.palette(0xFF1E40AF.toInt(), 0xFF1D4ED8.toInt(), 0, 0).onPrimary)
    }

    @Test fun aGreyPrimaryGivesGreyNeutrals() {
        val p = ThemeColors.palette(0xFFB0B0B0.toInt(), 0xFFFFFFFF.toInt(), 0, 0)
        assertEquals(p.background.red, p.background.blue, 0.002f)
    }

    @Test fun theGradientThemeFollowsTheSavedColoursAndComesBackAfterARestart() {
        Themes.setGradient(app, 0xFFFF5722.toInt(), 0xFF3F51B5.toInt())
        val live = Themes.byId(Themes.GRADIENT)
        assertEquals(0xFFFF5722.toInt() to 0xFF3F51B5.toInt(), SessionPrefs.gradientColors(app))
        assertTrue(contrast(live.onPrimary, live.primary) >= 4.5f)
        assertTrue(contrast(live.onPrimary, live.primary2) >= 4.5f)
        SessionPrefs.setTheme(app, Themes.GRADIENT)
        Themes.setGradient(app, Themes.DEFAULT_PRIMARY, Themes.DEFAULT_SECONDARY)
        SessionPrefs.setGradientColors(app, 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        assertEquals(Color(0xFF00FF00), Themes.current(app).primary)
    }

    @Test fun theGradientIsAChoiceInTheThemeList() {
        assertTrue(Themes.all.any { it.id == Themes.GRADIENT })
        assertEquals(Themes.GRAPHITE, Themes.byId("nonsense").id)
    }

    @Test fun innerGamerSitsJustAboveGradientInTheThemeList() {
        val ids = Themes.all.map { it.id }
        assertEquals(ids.indexOf(Themes.GRADIENT) - 1, ids.indexOf(Themes.INNER_GAMER))
    }

    @Test fun innerGamerButtonTextIsReadableAlongTheGradientAtEveryHue() {
        var hue = 0f
        while (hue < 360f) {
            val p = Themes.innerGamer(hue)
            assertEquals(Themes.INNER_GAMER, p.id)
            for (i in 0..100) {
                val f = i / 100f
                val blend = Color(
                    p.primary.red + (p.primary2.red - p.primary.red) * f,
                    p.primary.green + (p.primary2.green - p.primary.green) * f,
                    p.primary.blue + (p.primary2.blue - p.primary.blue) * f,
                )
                assertTrue("hue $hue at $f", contrast(p.onPrimary, blend) >= 4.49f)
            }
            hue += Themes.RGB_HUE_STEP
        }
    }

    @Test fun innerGamerFollowsTheHueAndTheSpeedIsSavedAndKeptInRange() {
        Themes.moveRgbHue(120f)
        assertEquals(Themes.innerGamer(120f).primary, Themes.byId(Themes.INNER_GAMER).primary)
        Themes.setRgbSpeed(app, 7)
        assertEquals(7, SessionPrefs.rgbSpeed(app))
        Themes.setRgbSpeed(app, 99)
        assertEquals(Themes.RGB_SPEEDS, Themes.rgbSpeed)
        Themes.setRgbSpeed(app, 0)
        assertEquals(1, SessionPrefs.rgbSpeed(app))
        Themes.setRgbSpeed(app, 1)
        assertTrue(Themes.rgbCycleMillis(1) > Themes.rgbCycleMillis(10))
    }
}
