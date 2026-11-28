package com.droiddeck.launcher.input

import android.os.Looper
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BackTapsTest {
    private val seen = mutableListOf<Boolean>()
    private var atOnce = true
    private var window = 500L
    private val taps = BackTaps({ window }, { atOnce }, seen::add)

    @Test fun aSingleTapActsWithoutWaitingForTheWindow() {
        taps.tap()
        assertEquals(listOf(false), seen)
        assertTrue(taps.pending)
        advance(500)
        assertEquals(listOf(false), seen)
        assertFalse(taps.pending)
    }

    @Test fun aSecondTapInsideTheWindowAddsTheDoubleAction() {
        taps.tap()
        advance(499)
        taps.tap()
        assertEquals(listOf(false, true), seen)
        assertFalse(taps.pending)
        advance(1000)
        assertEquals(listOf(false, true), seen)
    }

    @Test fun aTapAfterTheWindowIsANewSingleTap() {
        taps.tap()
        advance(500)
        taps.tap()
        assertEquals(listOf(false, false), seen)
    }

    @Test fun aDeferredSingleTapWaitsForTheWindow() {
        atOnce = false
        taps.tap()
        advance(499)
        assertTrue(seen.isEmpty())
        advance(1)
        assertEquals(listOf(false), seen)
    }

    @Test fun aDeferredDoubleTapProducesOnlyTheDoubleAction() {
        atOnce = false
        taps.tap()
        advance(100)
        taps.tap()
        advance(1000)
        assertEquals(listOf(true), seen)
    }

    @Test fun theConfiguredWindowAppliesInBothModes() {
        for (mode in listOf(true, false)) for (ms in listOf(100L, 800L)) {
            seen.clear()
            atOnce = mode
            window = ms
            taps.tap()
            advance(ms - 1)
            taps.tap()
            assertEquals(true, seen.last())
            advance(ms)
            assertFalse(taps.pending)
        }
    }

    @Test fun holdKeepsTheWindowOpenUntilTheNextTap() {
        taps.tap()
        advance(400)
        taps.hold()
        advance(5000)
        assertTrue(taps.pending)
        taps.tap()
        assertEquals(listOf(false, true), seen)
    }

    @Test fun cancelDropsADeferredTapAndClosesTheWindow() {
        atOnce = false
        taps.tap()
        taps.cancel()
        advance(1000)
        assertTrue(seen.isEmpty())
        assertFalse(taps.pending)
        taps.tap()
        advance(500)
        assertEquals(listOf(false), seen)
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
}
