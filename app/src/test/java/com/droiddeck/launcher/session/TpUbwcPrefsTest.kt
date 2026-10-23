package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.gpu.TpUbwcHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TpUbwcPrefsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        ReflectionHelpers.setStaticField(TpUbwcHint::class.java, "resolved", false)
        ReflectionHelpers.setStaticField(TpUbwcHint::class.java, "cached", null)
        ReflectionHelpers.setStaticField(TpUbwcHint::class.java, "activeMode", null)
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun missingOrInvalidChoiceUsesAuto() {
        assertEquals(SessionPrefs.TP_UBWC_AUTO, SessionPrefs.tpUbwcMode(context))
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putString("tpUbwcMode", "invalid").commit()
        assertEquals(SessionPrefs.TP_UBWC_AUTO, SessionPrefs.tpUbwcMode(context))
    }

    @Test fun overrideIsStoredWithoutChangingDriverChoices() {
        SessionPrefs.setAndroidDriver(context, "display")
        SessionPrefs.setLinuxDriver(context, "runtime")
        for (mode in listOf(SessionPrefs.TP_UBWC_ON, SessionPrefs.TP_UBWC_OFF, SessionPrefs.TP_UBWC_AUTO)) {
            SessionPrefs.setTpUbwcMode(context, mode)
            assertEquals(mode, SessionPrefs.tpUbwcMode(context))
            assertEquals("display", SessionPrefs.androidDriver(context))
            assertEquals("runtime", SessionPrefs.linuxDriver(context))
        }
    }

    @Test fun changingTheSavedChoiceKeepsTheProcessChoiceUntilRestart() {
        SessionPrefs.setTpUbwcMode(context, SessionPrefs.TP_UBWC_ON)
        val initial = TpUbwcHint.forThisDevice(context)
        SessionPrefs.setTpUbwcMode(context, SessionPrefs.TP_UBWC_OFF)
        assertTrue(TpUbwcHint.restartRequired(context))
        assertEquals(SessionPrefs.TP_UBWC_ON, TpUbwcHint.activeMode)
        assertEquals(initial, TpUbwcHint.forThisDevice(context))
        SessionPrefs.setTpUbwcMode(context, SessionPrefs.TP_UBWC_ON)
        assertFalse(TpUbwcHint.restartRequired(context))
    }
}
