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
class AvoidNotchPrefsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val saved get() = app.getSharedPreferences("session", Context.MODE_PRIVATE)

    @Before fun clear() { saved.edit().clear().commit() }

    @Test fun theNotchIsLeftAloneUntilAskedToAvoidIt() {
        assertFalse(SessionPrefs.avoidNotch(app))
    }

    @Test fun theChoiceIsKept() {
        SessionPrefs.setAvoidNotch(app, true)
        assertTrue(SessionPrefs.avoidNotch(app))
        SessionPrefs.setAvoidNotch(app, false)
        assertFalse(SessionPrefs.avoidNotch(app))
    }

    @Test fun resettingSettingsTurnsItOff() {
        SessionPrefs.setAvoidNotch(app, true)
        assertTrue(SettingsReset.resetAll(app, gameProfiles = false))
        assertFalse(SessionPrefs.avoidNotch(app))
    }
}
