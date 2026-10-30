package com.droiddeck.launcher.input

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.droiddeck.launcher.session.SessionPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DualScreenTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val displays = context.getSystemService(DisplayManager::class.java)

    @Test fun internalPanelsCountAsBuiltIn() {
        assertTrue(DualScreen.isBuiltIn(1, "Anything"))
        assertFalse(DualScreen.isBuiltIn(2, "Retroid Pocket Duo"))
        assertFalse(DualScreen.isBuiltIn(5, "AYN Thor"))
    }

    @Test fun knownModelsCountWhenTheTypeIsHidden() {
        assertTrue(DualScreen.isBuiltIn(null, "Retroid Pocket Duo"))
        assertTrue(DualScreen.isBuiltIn(null, " AYN Thor "))
        assertFalse(DualScreen.isBuiltIn(null, "Pixel 8"))
    }

    @Test fun aSecondPanelNeedsATouchscreenOfItsOwnSize() {
        val top = 1920 to 1080
        val bottom = 1280 to 960
        assertTrue(DualScreen.hasOwnTouchPanel(bottom, top, listOf(1920 to 1080, 1280 to 960)))
        assertTrue(DualScreen.hasOwnTouchPanel(bottom, top, listOf(1080 to 1920, 960 to 1280)))
        assertTrue(DualScreen.hasOwnTouchPanel(bottom, top, listOf(1920 to 1080, 1279 to 959)))
        assertFalse(DualScreen.hasOwnTouchPanel(bottom, top, listOf(1920 to 1080)))
        assertFalse(DualScreen.hasOwnTouchPanel(bottom, top, emptyList()))
    }

    @Test fun aPhantomPanelTheSizeOfTheMainScreenIsNotASecondScreen() {
        val main = 1920 to 1080
        assertFalse(DualScreen.hasOwnTouchPanel(main, main, listOf(1920 to 1080)))
        assertFalse(DualScreen.hasOwnTouchPanel(main, main, listOf(1080 to 1920)))
        assertTrue(DualScreen.hasOwnTouchPanel(main, main, listOf(1920 to 1080, 1920 to 1080)))
        assertTrue(DualScreen.hasOwnTouchPanel(main, null, listOf(1920 to 1080)))
    }

    @Test fun oneScreenDevicesGetNoSecondScreenInAnyMode() {
        for (mode in DualScreen.modes) {
            SessionPrefs.setDualScreenMode(context, mode)
            assertNull(DualScreen.display(context, displays, Display.DEFAULT_DISPLAY))
            assertNull(DualScreen.anyDisplay(displays, Display.DEFAULT_DISPLAY))
        }
    }

    @Test fun modeDefaultsToAutomaticAndIgnoresUnknownValues() {
        assertEquals(DualScreen.MODE_AUTO, SessionPrefs.dualScreenMode(context))
        SessionPrefs.setDualScreenMode(context, "sideways")
        assertEquals(DualScreen.MODE_AUTO, SessionPrefs.dualScreenMode(context))
        SessionPrefs.setDualScreenMode(context, DualScreen.MODE_OFF)
        assertEquals(DualScreen.MODE_OFF, SessionPrefs.dualScreenMode(context))
    }
}
