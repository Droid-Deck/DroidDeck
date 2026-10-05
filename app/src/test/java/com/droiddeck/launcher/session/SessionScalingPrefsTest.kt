package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionScalingPrefsTest {
    @Test fun legacySavedFiltersStillResolveToAVisibleEquivalentChoice() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        prefs.edit().putInt("upscaler", 1).putInt("upscaleSharpness", 65).commit()
        assertEquals(0, SessionPrefs.upscaler(context))
        prefs.edit().putInt("upscaler", 5).commit()
        assertEquals(4, SessionPrefs.upscaler(context))
        assertEquals(65, SessionPrefs.upscaleSharpness(context))
        SessionPrefs.setUpscaler(context, 5)
        assertEquals(4, prefs.getInt("upscaler", -1))
        SessionPrefs.setUpscaler(context, 1)
        assertEquals(0, prefs.getInt("upscaler", -1))
    }
}
