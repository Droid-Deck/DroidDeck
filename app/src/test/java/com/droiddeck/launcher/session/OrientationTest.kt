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

    @Test fun sessionChoicesPersistGloballyWithLandscapeAsTheDefault() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, SessionPrefs.orientation(context))
        for ((value, _) in SessionPrefs.orientationOptions) {
            SessionPrefs.setOrientation(context, value)
            assertEquals(value, SessionPrefs.orientation(context.createConfigurationContext(context.resources.configuration)))
        }
    }

    @Test fun invalidStoredOrientationFallsBackAndInvalidChoicePreservesTheSavedValue() {
        val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        prefs.edit().putInt("orientation", 999).commit()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, SessionPrefs.orientation(context))
        SessionPrefs.setOrientation(context, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)
        assertThrows(IllegalArgumentException::class.java) {
            SessionPrefs.setOrientation(context, 999)
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
        AppUiPrefs.setOrientation(context, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        SessionPrefs.setOrientation(context, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, AppUiPrefs.orientation(context))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, SessionPrefs.orientation(context))
    }

    @Test fun explicitOrientationTakesPriorityOverLargeFollowScreenWindows() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, SessionPrefs.windowOrientation(context, true, 700))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, SessionPrefs.windowOrientation(context, true, 400))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, SessionPrefs.windowOrientation(context, false, 700))
        for ((value, _) in SessionPrefs.orientationOptions) {
            SessionPrefs.setOrientation(context, value)
            assertEquals(value, SessionPrefs.windowOrientation(context, true, 700))
        }
    }

    @Test fun sessionPortraitUsesItsOwnLockOrTheCurrentRotation() {
        val config = android.content.res.Configuration(context.resources.configuration)
        config.orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT
        val portrait = context.createConfigurationContext(config)
        config.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val landscape = context.createConfigurationContext(config)
        assertFalse(SessionPrefs.portrait(portrait))
        SessionPrefs.setOrientation(context, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        assertTrue(SessionPrefs.portrait(landscape))
        SessionPrefs.setOrientation(context, ActivityInfo.SCREEN_ORIENTATION_USER)
        assertTrue(SessionPrefs.portrait(portrait))
        assertFalse(SessionPrefs.portrait(landscape))
    }

    @Test fun invalidLauncherOrientationFallsBackAndInvalidChoicePreservesPreference() {
        context.getSharedPreferences("app-ui", Context.MODE_PRIVATE).edit().putInt("orientation", 999).commit()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, AppUiPrefs.orientation(context))
        AppUiPrefs.setOrientation(context, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        assertThrows(IllegalArgumentException::class.java) { AppUiPrefs.setOrientation(context, 999) }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, AppUiPrefs.orientation(context))
    }

}
