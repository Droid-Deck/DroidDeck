package com.droiddeck.launcher.ui

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ToggleRow and the settings dialog use ToggleSwitch in both LTR and RTL.
 *
 * Its 52dp-wide track has a 22dp thumb and 4dp padding at each edge:
 * the physical thumb starts at x=4 in LTR, x=26 in RTL, and travels 22dp.
 * The switch's underlying state and callbacks remain the same in both directions.
 */
class ToggleSwitchRtlTest {
    private val trackWidth = 52f
    private val thumbWidth = 22f
    private val inset = 4f
    private val travel = trackWidth - 2 * inset - thumbWidth

    private fun thumbLeft(direction: LayoutDirection, fraction: Float): Float {
        val initial = if (direction == LayoutDirection.Rtl) trackWidth - inset - thumbWidth else inset
        return initial + toggleThumbTranslationPx(fraction, direction, travel)
    }

    @Test fun checkedThumbStaysFullyVisibleInArabicAndEnglish() {
        for (direction in LayoutDirection.entries) {
            for (fraction in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val left = thumbLeft(direction, fraction)
                assertTrue("$direction: left=$left", left >= inset)
                assertTrue("$direction: right=${left + thumbWidth}", left + thumbWidth <= trackWidth - inset)
            }
        }
    }

    @Test fun enabledThumbMovesTowardLogicalEnd() {
        assertEquals(4f, thumbLeft(LayoutDirection.Ltr, 0f), 0f)
        assertEquals(26f, thumbLeft(LayoutDirection.Ltr, 1f), 0f)
        assertEquals(26f, thumbLeft(LayoutDirection.Rtl, 0f), 0f)
        assertEquals(4f, thumbLeft(LayoutDirection.Rtl, 1f), 0f)
    }

    @Test fun switchingDirectionDoesNotAffectAnimationMidpoint() {
        assertEquals(15f, thumbLeft(LayoutDirection.Ltr, 0.5f), 0f)
        assertEquals(15f, thumbLeft(LayoutDirection.Rtl, 0.5f), 0f)
    }

    @Test fun fractionClampsWithoutDrawingBeyondTrack() {
        for (direction in LayoutDirection.entries) {
            assertEquals(thumbLeft(direction, 0f), thumbLeft(direction, -0.5f), 0f)
            assertEquals(thumbLeft(direction, 1f), thumbLeft(direction, 1.5f), 0f)
        }
    }
}
