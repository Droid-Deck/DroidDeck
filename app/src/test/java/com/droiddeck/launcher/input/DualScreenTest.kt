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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers

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

    @Test @Config(sdk = [34]) fun selectedInternalAndExternalDisplaysCanBeUsed() {
        withDisplay(INTERNAL, "Bottom screen") { internal ->
            assertEquals(internal.displayId, DualScreen.selectedSessionDisplay(displays, Display.DEFAULT_DISPLAY, internal.displayId)?.displayId)
        }
        withDisplay(EXTERNAL, "HDMI Screen") { external ->
            assertEquals(external.displayId, DualScreen.selectedSessionDisplay(displays, Display.DEFAULT_DISPLAY, external.displayId)?.displayId)
        }
    }

    @Test @Config(sdk = [34]) fun missingOrRemovedSelectedDisplaysCannotBeUsed() {
        assertNull(DualScreen.selectedSessionDisplay(displays, Display.DEFAULT_DISPLAY, Display.INVALID_DISPLAY))
        val id = ShadowDisplayManager.addDisplay("w640dp-h480dp", EXTERNAL)
        shadowOf(displays.getDisplay(id)).setFlags(Display.FLAG_PRESENTATION)
        ShadowDisplayManager.removeDisplay(id)
        assertNull(DualScreen.selectedSessionDisplay(displays, Display.DEFAULT_DISPLAY, id))
    }

    @Test fun knownModelsCountWhenTheTypeIsHidden() {
        assertTrue(DualScreen.isBuiltIn(null, "Retroid Pocket Duo"))
        assertTrue(DualScreen.isBuiltIn(null, " AYN Thor "))
        assertTrue(DualScreen.isBuiltIn(null, "AYN Thor Max"))
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

    @Test @Config(sdk = [34]) fun aWiredExternalDisplayDocksTheDevice() {
        withDisplay(EXTERNAL, "HDMI Screen") { assertTrue(DualScreen.dockedToDisplay(displays, Display.DEFAULT_DISPLAY)) }
        assertFalse(DualScreen.dockedToDisplay(displays, Display.DEFAULT_DISPLAY))
    }

    @Test @Config(sdk = [34]) fun glassesVirtualOverlayAndSwitchedOffDisplaysDoNotDock() {
        withDisplay(EXTERNAL, "VITURE Luma Ultra") { assertFalse(DualScreen.dockedToDisplay(displays, Display.DEFAULT_DISPLAY)) }
        withDisplay(VIRTUAL, "Screen recording") { assertFalse(DualScreen.dockedToDisplay(displays, Display.DEFAULT_DISPLAY)) }
        withDisplay(OVERLAY, "Overlay #1") { assertFalse(DualScreen.dockedToDisplay(displays, Display.DEFAULT_DISPLAY)) }
        withDisplay(EXTERNAL, "HDMI Screen") { display ->
            shadowOf(display).setState(Display.STATE_OFF)
            assertFalse(DualScreen.dockedToDisplay(displays, Display.DEFAULT_DISPLAY))
        }
    }

    @Test @Config(sdk = [34]) fun dockingLeavesTheBuiltInPanelWhenDualScreenIsForcedOn() {
        withDisplay(INTERNAL, "Bottom screen") { bottom ->
            withDisplay(EXTERNAL, "HDMI Screen") {
                assertTrue(DualScreen.dockedToDisplay(displays, Display.DEFAULT_DISPLAY))
                assertEquals(bottom.displayId, DualScreen.display(context, displays, Display.DEFAULT_DISPLAY, DualScreen.MODE_ON)?.displayId)
                assertNull(DualScreen.display(context, displays, Display.DEFAULT_DISPLAY, DualScreen.MODE_AUTO))
                assertNull(DualScreen.display(context, displays, Display.DEFAULT_DISPLAY, DualScreen.MODE_OFF))
            }
        }
    }

    @Test @Config(sdk = [34]) fun glassesOnUsbDoNotHideAnUnnamedDockDisplay() {
        val state = ReflectionHelpers.getField<kotlinx.coroutines.flow.MutableStateFlow<com.droiddeck.launcher.glasses.GlassesState>>(
            com.droiddeck.launcher.glasses.Glasses, "_state")
        val before = state.value
        try {
            state.value = before.copy(present = true)
            withDisplay(EXTERNAL, "") { assertFalse(DualScreen.dockedToDisplay(displays, Display.DEFAULT_DISPLAY)) }
            withDisplay(EXTERNAL, "VITURE Luma Ultra") {
                withDisplay(EXTERNAL, "") { assertTrue(DualScreen.dockedToDisplay(displays, Display.DEFAULT_DISPLAY)) }
            }
            withDisplay(EXTERNAL, "") { glasses ->
                state.value = before.copy(present = true, displayId = glasses.displayId)
                withDisplay(EXTERNAL, "") { assertTrue(DualScreen.dockedToDisplay(displays, Display.DEFAULT_DISPLAY)) }
            }
        } finally { state.value = before }
    }

    @Test fun onlyTheMomentOfDockingClosesAnAutomaticBottomPanel() {
        assertTrue(DualScreen.leavesBottomOnDock(wasDocked = false, docked = true, DualScreen.MODE_AUTO, automaticOnBuiltIn = true))
        assertFalse(DualScreen.leavesBottomOnDock(wasDocked = true, docked = true, DualScreen.MODE_AUTO, automaticOnBuiltIn = true))
        assertFalse(DualScreen.leavesBottomOnDock(wasDocked = false, docked = false, DualScreen.MODE_AUTO, automaticOnBuiltIn = true))
        assertFalse(DualScreen.leavesBottomOnDock(wasDocked = false, docked = true, DualScreen.MODE_AUTO, automaticOnBuiltIn = false))
        assertFalse(DualScreen.leavesBottomOnDock(wasDocked = false, docked = true, DualScreen.MODE_ON, automaticOnBuiltIn = true))
        assertFalse(DualScreen.leavesBottomOnDock(wasDocked = false, docked = true, DualScreen.MODE_OFF, automaticOnBuiltIn = true))
    }

    private companion object {
        const val INTERNAL = 1
        const val EXTERNAL = 2
        const val OVERLAY = 4
        const val VIRTUAL = 5
    }

    private fun withDisplay(type: Int, name: String, check: (Display) -> Unit) {
        val id = ShadowDisplayManager.addDisplay("w640dp-h480dp", type)
        try {
            val display = displays.getDisplay(id)
            shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
            shadowOf(display).setName(name)
            check(display)
        } finally { ShadowDisplayManager.removeDisplay(id) }
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

    @Test fun launcherAndSessionsKeepSeparateModes() {
        SessionPrefs.setDualScreenMode(context, DualScreen.MODE_OFF)
        assertEquals(DualScreen.MODE_OFF, SessionPrefs.dualScreenMode(context))
        assertEquals(DualScreen.MODE_AUTO, SessionPrefs.dualScreenSessionMode(context))
        SessionPrefs.setDualScreenSessionMode(context, DualScreen.MODE_ON)
        assertEquals(DualScreen.MODE_OFF, SessionPrefs.dualScreenMode(context))
        assertEquals(DualScreen.MODE_ON, SessionPrefs.dualScreenSessionMode(context))
    }

    @Test fun anOldDisabledMenuToggleTurnsBothOff() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putBoolean("dualScreenLauncher", false).commit()
        assertEquals(DualScreen.MODE_OFF, SessionPrefs.dualScreenMode(context))
        assertEquals(DualScreen.MODE_OFF, SessionPrefs.dualScreenSessionMode(context))
    }
}
