package com.droiddeck.launcher.session

import android.content.ComponentCallbacks2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopUnderPressureTest {
    @Test
    fun onlyRunningLevelsCountAsPressure() {
        assertTrue(TopUnderPressure.isPressure(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertTrue(TopUnderPressure.isPressure(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertFalse(TopUnderPressure.isPressure(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertFalse(TopUnderPressure.isPressure(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
    }

    @Test
    fun reclaimsOnlyDuringASessionShortlyAfterPressure() {
        assertTrue(TopUnderPressure.shouldReclaim(10_000, 9_500, sessionRunning = true, finishing = false))
        assertFalse(TopUnderPressure.shouldReclaim(20_000, 9_500, sessionRunning = true, finishing = false))
        assertFalse(TopUnderPressure.shouldReclaim(10_000, 0, sessionRunning = true, finishing = false))
        assertFalse(TopUnderPressure.shouldReclaim(10_000, 9_500, sessionRunning = false, finishing = false))
        assertFalse(TopUnderPressure.shouldReclaim(10_000, 9_500, sessionRunning = true, finishing = true))
    }
}
