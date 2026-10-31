package com.droiddeck.launcher.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HudDefaultTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun aNewInstallStartsWithTheHudOff() {
        assertFalse(SessionPrefs.hudEnabled(context))
    }

    @Test fun aHudTheUserTurnedOnStaysOn() {
        SessionPrefs.setHudEnabled(context, true)
        assertTrue(SessionPrefs.hudEnabled(context))
    }

    @Test fun aHudTheUserTurnedOffStaysOff() {
        SessionPrefs.setHudEnabled(context, false)
        assertFalse(SessionPrefs.hudEnabled(context))
    }
}
