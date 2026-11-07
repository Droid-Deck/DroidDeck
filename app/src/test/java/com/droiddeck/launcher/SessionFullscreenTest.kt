package com.droiddeck.launcher

import android.view.View
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
