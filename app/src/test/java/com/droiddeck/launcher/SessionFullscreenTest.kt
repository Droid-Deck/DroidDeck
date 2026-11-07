package com.droiddeck.launcher

import android.view.View
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionFullscreenTest {
    @Test fun fullscreenUsesTheSameImmersiveFlagsAsTheLauncher() {
        val flags = sessionSystemUiVisibility(fullscreen = true)

        assertTrue(flags and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY != 0)
        assertTrue(flags and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
        assertTrue(flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
        assertTrue(flags and View.SYSTEM_UI_FLAG_LAYOUT_STABLE != 0)
        assertTrue(flags and View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN != 0)
        assertTrue(flags and View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION != 0)
    }

    @Test fun nonFullscreenShowsBothAndroidSystemBars() {
        val flags = sessionSystemUiVisibility(fullscreen = false)

        assertEquals(View.SYSTEM_UI_FLAG_LAYOUT_STABLE, flags)
        assertFalse(flags and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
        assertFalse(flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
    }

    @Test fun cutoutPolicyMatchesTheLauncherFullscreenState() {
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
            sessionDisplayCutoutMode(fullscreen = true),
        )
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT,
            sessionDisplayCutoutMode(fullscreen = false),
        )
    }

    @Test fun nonFullscreenClearsTheThemeFullscreenWindowFlagAndFullscreenRestoresIt() {
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val fullscreenFlag = WindowManager.LayoutParams.FLAG_FULLSCREEN
        assertTrue(activity.window.attributes.flags and fullscreenFlag != 0)

        applySessionFullscreenWindow(activity.window, fullscreen = false)

        assertEquals(0, activity.window.attributes.flags and fullscreenFlag)
        assertEquals(View.SYSTEM_UI_FLAG_LAYOUT_STABLE, activity.window.decorView.systemUiVisibility)

        applySessionFullscreenWindow(activity.window, fullscreen = true)

        assertTrue(activity.window.attributes.flags and fullscreenFlag != 0)
        assertTrue(activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)

        applySessionFullscreenWindow(activity.window, fullscreen = false)

        assertEquals(0, activity.window.attributes.flags and fullscreenFlag)
    }

    @Test fun normalPrimaryLandscapeUsesFullHeightInBothFullscreenStates() {
        assertEquals(SESSION_SCALE_HEIGHT_FIT, sessionCompositorScaleMode(
            landscape = true,
            primaryDisplay = true,
            inPictureInPicture = false,
            opticalViewport = false,
            controlsBelow = false,
        ))
    }

    @Test fun specialAndPortraitOutputsKeepAspectFit() {
        val cases = listOf(
            sessionCompositorScaleMode(false, true, false, false, false),
            sessionCompositorScaleMode(true, false, false, false, false),
            sessionCompositorScaleMode(true, true, true, false, false),
            sessionCompositorScaleMode(true, true, false, true, false),
            sessionCompositorScaleMode(true, true, false, false, true),
        )

        cases.forEach { assertEquals(SESSION_SCALE_FIT, it) }
    }
}
