package com.droiddeck.launcher.session

import android.content.Context
import android.content.pm.ActivityInfo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OrientationTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearOrientation() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun landscapeChoicesPersistGloballyAndCanRestoreAutomaticRotation() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, SessionPrefs.orientation(context))
        for (value in listOf(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        )) {
            SessionPrefs.setOrientation(context, value)
            assertEquals(value, SessionPrefs.orientation(context.createConfigurationContext(context.resources.configuration)))
        }
    }

    @Test fun invalidStoredOrientationFallsBackAndInvalidChoicePreservesTheSavedValue() {
        val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        prefs.edit().putInt("orientation", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT).commit()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, SessionPrefs.orientation(context))
        SessionPrefs.setOrientation(context, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)
        assertThrows(IllegalArgumentException::class.java) {
            SessionPrefs.setOrientation(context, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, SessionPrefs.orientation(context))
    }
}
