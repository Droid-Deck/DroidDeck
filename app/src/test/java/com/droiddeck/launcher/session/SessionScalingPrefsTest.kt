package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionScalingPrefsTest {
    @Before fun clearSettings() {
        RuntimeEnvironment.getApplication().getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun explicitLegacyAspectAndCapKeepTheirSizeUntilAResolutionIsPicked() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        prefs.edit().putString("shape", SessionPrefs.SHAPE_EXACT).putInt("resolutionCap.steam", 900).commit()
        assertEquals("1200x900", SessionPrefs.resolutionChoice(context, "steam", 1440 to 1080))
        SessionPrefs.setResolutionChoice(context, "steam", "1600x900")
        prefs.edit().putString("shape", SessionPrefs.SHAPE_WIDE).commit()
        assertEquals("1600x900", SessionPrefs.resolutionChoice(context, "steam", 1440 to 1080))
        assertTrue(SessionPrefs.resolutionChosen(context, "steam"))
    }

    @Test fun matchScreenOverridesLegacyCustomAndAspectWithoutAffectingTheOtherMode() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        prefs.edit().putString("shape", SessionPrefs.SHAPE_WIDE).putString("customRes.steam", "1024x768")
            .putString("customRes.lxqt", "800x600").commit()
        assertEquals("1024x768", SessionPrefs.resolutionChoice(context, "steam", 1440 to 1080))
        SessionPrefs.setResolutionChoice(context, "steam", SessionDisplay.MATCH_SCREEN)
        val choice = SessionPrefs.resolutionChoice(context, "steam", 1440 to 1080)
        assertEquals(1440 to 1080, SessionDisplay.resolveChoice(1440 to 1080, choice))
        assertEquals("800x600", SessionPrefs.resolutionChoice(context, "lxqt", 1440 to 1080))
    }

    @Test fun freshSettingsUseTheConcreteDefaultAndCustomSizesAreNormalized() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("1280x720", SessionPrefs.resolutionChoice(context, "steam", 2400 to 1080))
        SessionPrefs.setResolutionChoice(context, "steam", "1025×769")
        assertEquals("1024x768", SessionPrefs.resolutionChoice(context, "steam", 2400 to 1080))
    }

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
