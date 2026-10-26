package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PerformanceModeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val session get() = app.getSharedPreferences("session", Context.MODE_PRIVATE)

    @Before fun clear() { session.edit().clear().commit() }

    @Test fun offByDefaultAndPerMode() {
        assertFalse(SessionPrefs.performanceMode(app, SessionService.MODE_STEAM))
        SessionPrefs.setPerformanceMode(app, SessionService.MODE_STEAM, true)
        assertTrue(SessionPrefs.performanceMode(app, SessionService.MODE_STEAM))
        assertFalse(SessionPrefs.performanceMode(app, SessionService.MODE_DESKTOP))
    }

    @Test fun offKeepsTodaysPacing() {
        assertEquals(0, SessionPrefs.compositorFpsLimit(0, false))
        assertEquals(60, SessionPrefs.compositorFpsLimit(60, false))
    }

    @Test fun onWithoutALimitIsUnpacedAndAChosenLimitStillPaces() {
        assertEquals(SessionPrefs.FPS_UNPACED, SessionPrefs.compositorFpsLimit(0, true))
        assertEquals(60, SessionPrefs.compositorFpsLimit(60, true))
    }

    @Test fun anOldUncappedFrameLimitReadsAsOff() {
        session.edit().putInt("fpsLimit.steam", -1).commit()
        assertEquals(0, SessionPrefs.fpsLimit(app, SessionService.MODE_STEAM))
        assertFalse(SessionPrefs.fpsLimitChoices.any { it.first < 0 })
    }
}
