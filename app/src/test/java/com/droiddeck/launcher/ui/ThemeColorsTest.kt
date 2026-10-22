package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
            assertTrue("${ThemeColors.hex(a.toInt())} ${ThemeColors.hex(b.toInt())}", contrast(p.onPrimary, p.primary) >= 2.99f && contrast(p.onPrimary, p.primary2) >= 2.99f)
        }
    }

    @Test fun onlyTheStopThatFailsIsNudged() {
        val p = ThemeColors.palette(0xFFFFEB3B.toInt(), 0xFF1E3A8A.toInt(), 0, 0)
        assertEquals(Color(0xFFFFEB3B), p.primary)
        assertTrue(p.primary2 != Color(0xFF1E3A8A))
        assertEquals(Color(0xFF03111F), p.onPrimary)
    }

    @Test fun thePaletteKeepsTheTwoColoursAndStaysDarkAndReadable() {
        val p = ThemeColors.palette(0xFF8B5CF6.toInt(), 0xFF22D3EE.toInt(), 0, 0)
        assertEquals(Color(0xFF8B5CF6), p.primary)
        assertEquals(p.primary, p.signal)
        assertEquals(Color(0xFF22D3EE), p.primary2)
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
        assertEquals(Color(0xFFFF5722), live.primary)
        assertEquals(Color(0xFF3F51B5), live.primary2)
        assertEquals(0xFFFF5722.toInt() to 0xFF3F51B5.toInt(), SessionPrefs.gradientColors(app))
        SessionPrefs.setTheme(app, Themes.GRADIENT)
        Themes.setGradient(app, Themes.DEFAULT_PRIMARY, Themes.DEFAULT_SECONDARY)
        SessionPrefs.setGradientColors(app, 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        assertEquals(Color(0xFF00FF00), Themes.current(app).primary)
    }

    @Test fun theGradientIsAChoiceInTheThemeList() {
        assertTrue(Themes.all.any { it.id == Themes.GRADIENT })
        assertEquals(Themes.GRAPHITE, Themes.byId("nonsense").id)
    }
}
