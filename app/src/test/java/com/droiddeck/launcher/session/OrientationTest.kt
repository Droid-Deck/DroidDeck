package com.droiddeck.launcher.session

import android.content.Context
import android.content.pm.ActivityInfo
import com.droiddeck.launcher.core.AppUiPrefs
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
        context.getSharedPreferences("app-ui", Context.MODE_PRIVATE).edit().clear().commit()
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

    @Test fun launcherFollowsDeviceRotationAndPortraitDoesNotChangeSessions() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, AppUiPrefs.orientation(context))
        for ((value, _) in AppUiPrefs.orientationOptions) {
            AppUiPrefs.setOrientation(context, value)
            assertEquals(value, AppUiPrefs.orientation(context))
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, SessionPrefs.orientation(context))
        }
        AppUiPrefs.setOrientation(context, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        SessionPrefs.setOrientation(context, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, AppUiPrefs.orientation(context))
    }

    @Test fun invalidLauncherOrientationFallsBackAndInvalidChoicePreservesPreference() {
        context.getSharedPreferences("app-ui", Context.MODE_PRIVATE).edit().putInt("orientation", 999).commit()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, AppUiPrefs.orientation(context))
        AppUiPrefs.setOrientation(context, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        assertThrows(IllegalArgumentException::class.java) { AppUiPrefs.setOrientation(context, 999) }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, AppUiPrefs.orientation(context))
    }

}
