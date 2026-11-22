package com.droiddeck.launcher.session

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], manifest = Config.NONE)
class PerfModeTest {
    private fun mode(id: Int, hz: Float, width: Int = 1080): Display.Mode =
        ShadowDisplayManager.ModeBuilder.modeBuilder(id)
            .setWidth(width).setHeight(2520).setRefreshRate(hz).build()

    @Test fun sixtyHzMirrorChoosesSixtyEvenThoughOneTwentyHasMatchingCadence() {
        val low = mode(1, 60f)
        val high = mode(2, 120f)
        assertEquals(low, PerfMode.selectMode(arrayOf(low, high), high, 60, 60f))
        assertEquals(high, PerfMode.selectMode(arrayOf(low, high), low, 0, Float.POSITIVE_INFINITY))
    }

    @Test fun aLowerGameLimitDoesNotOverrideTheGlassesRefreshCeiling() {
        val modes = arrayOf(mode(1, 60f), mode(2, 90f), mode(3, 120f))
        assertEquals(modes[0], PerfMode.selectMode(modes, modes[2], 30, 60f))
        assertEquals(modes[1], PerfMode.selectMode(modes, modes[2], 45, 90f))
    }

    @Test fun selectionPreservesResolutionAndUsesTheLowestModeIfTheCapIsUnsupported() {
        val low = mode(1, 90f)
        val high = mode(2, 120f)
        val otherResolution = mode(3, 60f, 720)
        assertEquals(low, PerfMode.selectMode(arrayOf(low, high, otherResolution), high, 60, 60f))
    }

    @Test fun anotherModeWithTheRequestedRefreshRateKeepsTheRequestInsteadOfSwitchingBackAndForth() {
        val sixty = mode(2, 60f)
        val requested = mode(1, 120f)
        val reported = mode(255, 120f, 1440)
        val modes = arrayOf(sixty, requested, reported)
        assertEquals(reported, PerfMode.selectMode(modes, reported, 0, Float.POSITIVE_INFINITY))
        assertFalse(PerfMode.needsChange(modes, requested.modeId, reported))
        assertFalse(PerfMode.needsChange(modes, reported.modeId, requested))
    }

    @Test fun aDifferentRefreshRateNoRequestOrAMissingModeStillChangesTheMode() {
        val sixty = mode(2, 60f)
        val oneTwenty = mode(1, 120f)
        val modes = arrayOf(sixty, oneTwenty)
        assertTrue(PerfMode.needsChange(modes, sixty.modeId, oneTwenty))
        assertTrue(PerfMode.needsChange(modes, oneTwenty.modeId, sixty))
        assertTrue(PerfMode.needsChange(modes, 0, oneTwenty))
        assertTrue(PerfMode.needsChange(modes, 99, oneTwenty))
    }

    @Test fun aGuestFrameRateChoosesTheHighestPanelRateItDividesEvenly() {
        val modes = arrayOf(mode(1, 60f), mode(2, 90f), mode(3, 120f), mode(4, 144f))
        val current = modes[3]
        assertEquals(modes[2], PerfMode.selectMode(modes, current, 60, Float.POSITIVE_INFINITY, 120))
        assertEquals(modes[2], PerfMode.selectMode(modes, current, 40, Float.POSITIVE_INFINITY, 120))
        assertEquals(modes[1], PerfMode.selectMode(modes, current, 45, Float.POSITIVE_INFINITY, 90))
        assertEquals(modes[3], PerfMode.selectMode(modes, current, 72, Float.POSITIVE_INFINITY, 144))
        assertEquals(modes[3], PerfMode.selectMode(modes, current, 50, Float.POSITIVE_INFINITY, 144))
    }

    @Test fun frameGenerationRaisesThePanelRateToItsOutputRate() {
        val modes = arrayOf(mode(1, 60f), mode(2, 90f), mode(3, 120f), mode(4, 144f))
        val current = modes[0]
        assertEquals(120, PerfMode.contentRate(60, 2, 0))
        assertEquals(60, PerfMode.contentRate(60, 1, 0))
        assertEquals(90, PerfMode.contentRate(40, 3, 90))
        assertEquals(0, PerfMode.contentRate(0, 2, 0))
        assertEquals(modes[2], PerfMode.selectMode(modes, current, PerfMode.contentRate(60, 2, 0), Float.POSITIVE_INFINITY))
        assertEquals(modes[1], PerfMode.selectMode(modes, current, PerfMode.contentRate(45, 2, 0), Float.POSITIVE_INFINITY))
        assertEquals(modes[3], PerfMode.selectMode(modes, current, PerfMode.contentRate(72, 2, 0), Float.POSITIVE_INFINITY))
        assertEquals(modes[3], PerfMode.selectMode(modes, current, PerfMode.contentRate(60, 3, 0), Float.POSITIVE_INFINITY))
        assertEquals(modes[2], PerfMode.selectMode(modes, current, PerfMode.contentRate(60, 4, 0), 120f))
    }

    @Test fun aGuestRefreshRequestWithoutAFrameRatePicksThatPanelRate() {
        val modes = arrayOf(mode(1, 60f), mode(2, 90f), mode(3, 120f))
        val current = modes[2]
        assertEquals(modes[0], PerfMode.selectMode(modes, current, 0, Float.POSITIVE_INFINITY, 60))
        assertEquals(modes[1], PerfMode.selectMode(modes, current, 0, Float.POSITIVE_INFINITY, 75))
        assertEquals(modes[1], PerfMode.selectMode(modes, current, 0, 90f, 120))
        assertEquals(modes[2], PerfMode.selectMode(modes, current, 0, Float.POSITIVE_INFINITY, 0))
    }

    @Test fun panelRatesListOnlyTheCurrentSizeWithinTheCap() {
        val modes = arrayOf(mode(1, 60f), mode(2, 120f), mode(3, 144f), mode(4, 90f, 720), mode(5, 59.94f))
        assertEquals(listOf(60, 120, 144), PerfMode.panelRates(modes, modes[1], Float.POSITIVE_INFINITY).toList())
        assertEquals(listOf(60, 120), PerfMode.panelRates(modes, modes[1], 120f).toList())
    }

    @Test fun aPanelTheSystemHoldsOffersOnlyItsCurrentRate() {
        val modes = arrayOf(mode(1, 60f), mode(2, 120f), mode(3, 185f))
        assertTrue(PerfMode.panelHeld(185f, 120f))
        assertTrue(PerfMode.panelHeld(60f, 120f))
        assertFalse(PerfMode.panelHeld(120f, 119.88f))
        assertFalse(PerfMode.panelHeld(0f, 120f))
        assertEquals(listOf(120), PerfMode.panelRates(modes, modes[1], Float.POSITIVE_INFINITY, true).toList())
        assertEquals(listOf(60, 120, 185), PerfMode.panelRates(modes, modes[1], Float.POSITIVE_INFINITY, false).toList())
    }

    @Test
    fun aSteadyRateBelowAnUnconstrainingLimiterSetsThePace() {
        assertEquals(30, PerfMode.pacedRate(30, 120, 120, 45))
        assertEquals(45, PerfMode.pacedRate(0, 0, 0, 45))
        assertEquals(40, PerfMode.pacedRate(0, 40, 120, 45))
        assertEquals(45, PerfMode.pacedRate(0, 120, 120, 45))
        assertEquals(120, PerfMode.pacedRate(0, 120, 120, 60))
        assertEquals(120, PerfMode.pacedRate(0, 120, 120, 0))
        assertEquals(60, PerfMode.pacedRate(0, 60, 0, 45))
    }
}
