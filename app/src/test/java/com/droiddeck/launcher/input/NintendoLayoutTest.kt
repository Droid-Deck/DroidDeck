package com.droiddeck.launcher.input

import android.content.Context
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NintendoLayoutTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ControllerPrefs.prefs(context).edit().clear().commit()
    }

    @Test fun faceButtonsKeepTheirPositionsByDefault() {
        assertEquals(0, PadBridge.faceButton(KeyEvent.KEYCODE_BUTTON_A, false))
        assertEquals(1, PadBridge.faceButton(KeyEvent.KEYCODE_BUTTON_B, false))
        assertEquals(2, PadBridge.faceButton(KeyEvent.KEYCODE_BUTTON_X, false))
        assertEquals(3, PadBridge.faceButton(KeyEvent.KEYCODE_BUTTON_Y, false))
    }

    @Test fun nintendoLayoutSwapsAWithBAndXWithY() {
        assertEquals(1, PadBridge.faceButton(KeyEvent.KEYCODE_BUTTON_A, true))
        assertEquals(0, PadBridge.faceButton(KeyEvent.KEYCODE_BUTTON_B, true))
        assertEquals(3, PadBridge.faceButton(KeyEvent.KEYCODE_BUTTON_X, true))
        assertEquals(2, PadBridge.faceButton(KeyEvent.KEYCODE_BUTTON_Y, true))
    }

    @Test fun otherButtonsAreNotFaceButtons() {
        for (nintendo in listOf(false, true)) {
            for (key in listOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_BUTTON_MODE)) {
                assertEquals(-1, PadBridge.faceButton(key, nintendo))
            }
        }
    }

    @Test fun theLayoutIsKeptPerGameAndOffElsewhere() {
        ControllerPrefs.setNintendoLayout(context, "42", true)
        assertTrue(ControllerPrefs.nintendoLayout(context, "42"))
        assertFalse(ControllerPrefs.nintendoLayout(context, "43"))
        assertFalse(ControllerPrefs.nintendoLayout(context, null))
        assertFalse(ControllerPrefs.nintendoLayout(context, "program:/usr/bin/foo"))
        assertEquals(ControllerPrefs.DEFAULT_DEAD_ZONE_PERCENT, ControllerPrefs.read(context).deadZone)
        assertFalse(ControllerPrefs.read(context).customLayout)
    }

    @Test fun turningTheLayoutOffRemovesTheGameEntry() {
        ControllerPrefs.setNintendoLayout(context, "42", true)
        ControllerPrefs.setNintendoLayout(context, "42", false)
        assertFalse(ControllerPrefs.nintendoLayout(context, "42"))
        assertFalse(ControllerPrefs.prefs(context).contains("nintendo.42"))
    }
}
