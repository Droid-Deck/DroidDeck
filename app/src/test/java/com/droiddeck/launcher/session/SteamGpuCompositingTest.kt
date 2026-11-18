package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SteamGpuCompositingTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        context.getSharedPreferences("session", 0).edit().clear().commit()
        assertTrue(SessionPrefs.steamGpuCompositingAllowed())
    }

    @Test fun eligibleRendererUsesGpuCompositingByDefault() {
        assertTrue(SessionPrefs.steamGpuCompositing(context))
    }

    @Test fun savedSoftwareChoiceIsPreserved() {
        context.getSharedPreferences("session", 0).edit()
            .putBoolean("steamGpuCompositing", false).commit()
        assertFalse(SessionPrefs.steamGpuCompositing(context))
    }

    @Test fun savedGpuChoiceIsPreserved() {
        context.getSharedPreferences("session", 0).edit()
            .putBoolean("steamGpuCompositing", true).commit()
        assertTrue(SessionPrefs.steamGpuCompositing(context))
    }

    @Test fun preferenceCanStillBeToggled() {
        SessionPrefs.setSteamGpuCompositing(context, false)
        assertFalse(SessionPrefs.steamGpuCompositing(context))
        SessionPrefs.setSteamGpuCompositing(context, true)
        assertTrue(SessionPrefs.steamGpuCompositing(context))
    }

    @Test fun untouchedPreferenceIsNotAChoice() {
        assertFalse(SessionPrefs.steamGpuCompositingChosen(context, SessionService.MODE_STEAM))
    }

    @Test fun savedGpuChoiceIsAChoice() {
        SessionPrefs.setSteamGpuCompositing(context, true)
        assertTrue(SessionPrefs.steamGpuCompositingChosen(context, SessionService.MODE_STEAM))
    }

    @Test fun savedSoftwareChoiceIsNotChosenGpu() {
        SessionPrefs.setSteamGpuCompositing(context, false)
        assertFalse(SessionPrefs.steamGpuCompositingChosen(context, SessionService.MODE_STEAM))
    }

    @Test fun desktopSessionNeverReceivesChosenGpuCompositing() {
        SessionPrefs.setSteamGpuCompositing(context, true)
        assertFalse(SessionPrefs.steamGpuCompositingChosen(context, SessionService.MODE_DESKTOP))
    }

    @Test fun runSessionNeverReceivesChosenGpuCompositing() {
        SessionPrefs.setSteamGpuCompositing(context, true)
        assertFalse(SessionPrefs.steamGpuCompositingChosen(context, SessionService.MODE_RUN))
    }

    @Test fun untouchedPreferenceShowsSoftwareOnSystemDriverPath() {
        assertFalse(SessionPrefs.steamGpuDrawingShown(context, true))
    }

    @Test fun untouchedPreferenceShowsGpuOnOpenDriverPath() {
        assertTrue(SessionPrefs.steamGpuDrawingShown(context, false))
    }

    @Test fun savedGpuChoiceIsShownOnBothPaths() {
        SessionPrefs.setSteamGpuCompositing(context, true)
        assertTrue(SessionPrefs.steamGpuDrawingShown(context, true))
        assertTrue(SessionPrefs.steamGpuDrawingShown(context, false))
    }

    @Test fun savedSoftwareChoiceIsShownOnBothPaths() {
        SessionPrefs.setSteamGpuCompositing(context, false)
        assertFalse(SessionPrefs.steamGpuDrawingShown(context, true))
        assertFalse(SessionPrefs.steamGpuDrawingShown(context, false))
    }

    @Test fun firstToggleFromUntouchedSystemDriverRowSavesGpu() {
        SessionPrefs.setSteamGpuCompositing(context, !SessionPrefs.steamGpuDrawingShown(context, true))
        assertTrue(SessionPrefs.steamGpuCompositingChosen(context, SessionService.MODE_STEAM))
    }

    @Test fun systemDriverPathIsOffOnDevicesWithoutThePowerVrDriver() {
        assertFalse(SessionPrefs.systemDriverSteamPath(context))
    }
}
