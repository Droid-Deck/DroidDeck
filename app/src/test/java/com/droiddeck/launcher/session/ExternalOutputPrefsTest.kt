package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class ExternalOutputPrefsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val saved get() = app.getSharedPreferences("session", Context.MODE_PRIVATE)

    @Before fun clear() { saved.edit().clear().commit() }

    @Test fun desktopStartsOnThisDevice() {
        assertFalse(SessionPrefs.externalGameOutput(app))
    }

    @Test fun desktopChoiceIsRemembered() {
        SessionPrefs.setExternalGameOutput(app, true)
        assertTrue(SessionPrefs.externalGameOutput(app))
        SessionPrefs.setExternalGameOutput(app, false)
        assertFalse(SessionPrefs.externalGameOutput(app))
    }

    @Test fun dockSavedResolutionRoundTripsAndClears() {
        SessionPrefs.setDockSavedResolution(app, SessionService.MODE_DESKTOP, "720p")
        assertEquals("720p", SessionPrefs.dockSavedResolution(app, SessionService.MODE_DESKTOP))
        SessionPrefs.clearDockSavedResolution(app, SessionService.MODE_DESKTOP)
        assertNull(SessionPrefs.dockSavedResolution(app, SessionService.MODE_DESKTOP))
    }
}
