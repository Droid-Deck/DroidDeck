package com.droiddeck.launcher.session

import android.app.Application
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class TolerantPrefsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val raw get() = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    @Before fun reset() {
        raw.edit().clear().commit()
    }

    @After fun cleanup() {
        raw.edit().clear().commit()
    }

    @Test fun aSwitchStoredAsTextReadsAsItsValue() {
        raw.edit().putString("gameDebugLogsEnabled", "true").putString("logs", "false").commit()

        assertTrue(SessionPrefs.gameDebugLogsEnabled(context))
        assertFalse(SessionPrefs.logsEnabled(context))
    }

    @Test fun unreadableTextFallsBackToTheDefault() {
        raw.edit().putString("gameDebugLogsEnabled", "yes").putString("logs", "").commit()

        assertFalse(SessionPrefs.gameDebugLogsEnabled(context))
        assertTrue(SessionPrefs.logsEnabled(context))
    }

    @Test fun everyTypedReadToleratesAnotherStoredType() {
        raw.edit()
            .putString("count", "7")
            .putString("big", "8000000000")
            .putString("ratio", "1.5")
            .putBoolean("label", true)
            .putInt("names", 3)
            .commit()
        val prefs = TolerantPrefs.session(context)

        assertEquals(7, prefs.getInt("count", 0))
        assertEquals(8_000_000_000L, prefs.getLong("big", 0L))
        assertEquals(1.5f, prefs.getFloat("ratio", 0f), 0f)
        assertEquals("true", prefs.getString("label", null))
        assertEquals(setOf("x"), prefs.getStringSet("names", mutableSetOf("x")))
        assertEquals(0, prefs.getInt("ratio", 0))
    }

    @Test fun correctlyTypedSettingsAreUnchanged() {
        raw.edit().putBoolean("gameDebugLogsEnabled", true).putBoolean("logs", false).putString("theme", "paper").commit()

        assertTrue(SessionPrefs.gameDebugLogsEnabled(context))
        assertFalse(SessionPrefs.logsEnabled(context))
        assertEquals("paper", SessionPrefs.theme(context))
    }

    @Test fun aSessionEndRepairsTheStoredType() {
        raw.edit().putString("gameDebugLogsEnabled", "true").commit()

        SessionPrefs.expireGameDebugLogs(context)

        assertEquals(false, raw.all["gameDebugLogsEnabled"])
    }
}
