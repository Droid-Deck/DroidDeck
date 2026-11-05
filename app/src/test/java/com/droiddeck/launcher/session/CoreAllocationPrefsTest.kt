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
class CoreAllocationPrefsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val saved get() = app.getSharedPreferences("session", Context.MODE_PRIVATE)

    @Before fun clear() { saved.edit().clear().commit() }

    @Test fun absentPreferencesLeaveEveryCoreToTheScheduler() {
        assertFalse(SessionPrefs.clientCpusOverride(app))
        assertEquals("", SessionPrefs.clientCpus(app))
        assertEquals("", SessionPrefs.gameCpus(app))
    }

    @Test fun savedChoicesAreKept() {
        SessionPrefs.setClientCpusOverride(app, true)
        SessionPrefs.setClientCpus(app, "7")
        SessionPrefs.setGameCpus(app, "3,4,5,6")
        assertTrue(SessionPrefs.clientCpusOverride(app))
        assertEquals("7", SessionPrefs.clientCpus(app))
        assertEquals("3,4,5,6", SessionPrefs.gameCpus(app))
    }
}
