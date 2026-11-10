package com.droiddeck.launcher.input

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GesturePrefsTest {
    @Test fun relativeMouseModeSurvivesSerializationAndRejectsUnknownValues() {
        val on = TouchGestureConfig(relativeMouseMode = TouchGestureConfig.RELATIVE_MOUSE_ON)
        assertEquals(TouchGestureConfig.RELATIVE_MOUSE_ON, TouchGestureConfig.fromJson(on.toJson()).relativeMouseMode)
        assertEquals(TouchGestureConfig.RELATIVE_MOUSE_AUTO, TouchGestureConfig.fromJson("""{"relativeMouseMode":9}""").relativeMouseMode)
        assertEquals(TouchGestureConfig.RELATIVE_MOUSE_AUTO, TouchGestureConfig.fromJson("{}").relativeMouseMode)
    }

    @Test fun autoNeverGoesRelativeForDirectTouchWithoutAMouse() {
        val auto = TouchGestureConfig.RELATIVE_MOUSE_AUTO
        assertEquals(TouchGestureConfig.RELATIVE_MOUSE_OFF, TouchGestureConfig.gamescopeRelativeMouse(auto, mouseAttached = false, touchpad = false))
        assertEquals(TouchGestureConfig.RELATIVE_MOUSE_PHYSICAL, TouchGestureConfig.gamescopeRelativeMouse(auto, mouseAttached = true, touchpad = false))
        assertEquals(TouchGestureConfig.RELATIVE_MOUSE_PHYSICAL, TouchGestureConfig.gamescopeRelativeMouse(auto, mouseAttached = true, touchpad = true))
        assertEquals(auto, TouchGestureConfig.gamescopeRelativeMouse(auto, mouseAttached = false, touchpad = true))
    }

    @Test fun aChosenRelativeMouseModeIsSentAsIs() {
        for (mode in listOf(TouchGestureConfig.RELATIVE_MOUSE_ON, TouchGestureConfig.RELATIVE_MOUSE_OFF))
            for (mouse in listOf(false, true)) for (pad in listOf(false, true))
                assertEquals(mode, TouchGestureConfig.gamescopeRelativeMouse(mode, mouse, pad))
    }

    @Test fun removedOptionsAreIgnoredWhenLoadingExistingSettings() {
        val old = """{"holdMouseButtonWhileTouchingEnabled":true,"holdMouseButtonWhileTouchingAction":"right_click",
            "showClickHighlight":true,"showCursorInTouchscreenMode":true,"tapAction":"key_E","longPressDelay":650}"""
        val config = TouchGestureConfig.fromJson(old)
        assertEquals("key_E", config.tapAction)
        assertEquals(650, config.longPressDelay)
        assertFalse(config.toJson().contains("holdMouseButtonWhileTouching"))
        assertFalse(config.toJson().contains("showClickHighlight"))
        assertFalse(config.toJson().contains("showCursorInTouchscreenMode"))
    }

    @Test fun gameOverridesFallBackToDefaultsAndSurviveSerialization() {
        val context = RuntimeEnvironment.getApplication()
        val defaults = TouchGestureConfig(tapAction = "key_E", mouseDragMovementMode = "relative")
        GesturePrefs.save(context, null, defaults)
        assertEquals(defaults, GesturePrefs.read(context, "123"))
        val game = defaults.copy(twoFingerTapAction = "show_keyboard", longPressDelay = 650)
        GesturePrefs.save(context, "123", game)
        assertEquals(game, GesturePrefs.read(context, "123"))
        assertEquals(defaults, GesturePrefs.read(context, "456"))
        GesturePrefs.clear(context, "123")
        assertEquals(defaults, GesturePrefs.read(context, "123"))
    }
}
