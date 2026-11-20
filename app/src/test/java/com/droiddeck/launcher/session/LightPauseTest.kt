package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightPauseTest {
    @Test
    fun theBounceAloneResumesLight() {
        val pause = LightPause()
        pause.paused(bounces = 3)
        assertEquals(LightPause.Resume.LIGHT, pause.resumed(bounces = 4))
        assertFalse(pause.timedOut())
    }

    @Test
    fun anUnrelatedPauseWithoutABounceCleansUpAtTheTimeout() {
        val pause = LightPause()
        pause.paused(bounces = 3)
        assertTrue(pause.timedOut())
        assertEquals(LightPause.Resume.NORMAL, pause.resumed(bounces = 3))
    }

    @Test
    fun aBounceWhileAnotherActivityHoldsThePauseStillCleansUpAtTheTimeout() {
        val pause = LightPause()
        pause.paused(bounces = 3)
        assertTrue(pause.timedOut())
        assertEquals(LightPause.Resume.NORMAL, pause.resumed(bounces = 4))
    }

    @Test
    fun aResumeBeforeTheTimeoutWithoutABounceCleansUpFirst() {
        val pause = LightPause()
        pause.paused(bounces = 3)
        assertEquals(LightPause.Resume.CLEANUP_FIRST, pause.resumed(bounces = 3))
        assertFalse(pause.timedOut())
    }

    @Test
    fun aStopAfterALightPauseCleansUpOnce() {
        val pause = LightPause()
        pause.paused(bounces = 3)
        assertTrue(pause.stopped())
        assertFalse(pause.timedOut())
        assertEquals(LightPause.Resume.NORMAL, pause.resumed(bounces = 4))
    }

    @Test
    fun aNormalPauseIsUntouched() {
        val pause = LightPause()
        assertFalse(pause.stopped())
        assertEquals(LightPause.Resume.NORMAL, pause.resumed(bounces = 0))
    }
}
