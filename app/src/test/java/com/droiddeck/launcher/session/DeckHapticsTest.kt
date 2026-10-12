package com.droiddeck.launcher.session

import com.droiddeck.launcher.session.DeckHaptics.Companion.BOTH
import com.droiddeck.launcher.session.DeckHaptics.Companion.LEFT
import com.droiddeck.launcher.session.DeckHaptics.Companion.RIGHT
import org.junit.Assert.*
import org.junit.Test

class DeckHapticsTest {
    @Test fun theSlidersRangeRunsFromALightTickToFullStrength() {
        assertEquals(DeckHaptics.LOW_AMPLITUDE, DeckHaptics.amplitudeFor(-5))
        assertEquals(255, DeckHaptics.amplitudeFor(7))
        assertEquals(255, DeckHaptics.amplitudeFor(12))
        assertEquals(DeckHaptics.LOW_AMPLITUDE, DeckHaptics.amplitudeFor(-20))
        assertEquals(0, DeckHaptics.amplitudeFor(-60))
        val steps = (-5..7).map { DeckHaptics.amplitudeFor(it) }
        assertTrue("each slider step is stronger: $steps", steps.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun everyTickPlaysAtTheHigherOfTheTwoTrackpadsSliders() {
        val haptics = DeckHaptics()
        assertEquals(DeckHaptics.amplitudeFor(-5), haptics.amplitude(LEFT, -5))
        assertEquals(DeckHaptics.amplitudeFor(3), haptics.amplitude(RIGHT, 3))
        // The left pad's own tick still plays at the right slider's higher setting.
        assertEquals(DeckHaptics.amplitudeFor(3), haptics.amplitude(LEFT, -5))
        assertEquals(DeckHaptics.amplitudeFor(7), haptics.amplitude(LEFT, 7))
        // Turning the right slider down changes nothing while the left one is higher.
        assertEquals(DeckHaptics.amplitudeFor(7), haptics.amplitude(RIGHT, -5))
        // Lowering the left one again leaves the right one's latest gain on top.
        assertEquals(DeckHaptics.amplitudeFor(0), haptics.amplitude(LEFT, 0))
    }

    @Test fun aReportForBothTrackpadsSetsBoth() {
        val haptics = DeckHaptics()
        haptics.amplitude(LEFT, 7)
        assertEquals(DeckHaptics.amplitudeFor(-2), haptics.amplitude(BOTH, -2))
        assertEquals(DeckHaptics.amplitudeFor(-2), haptics.amplitude(RIGHT, -5).let { haptics.amplitude(BOTH, -2) })
    }
}
