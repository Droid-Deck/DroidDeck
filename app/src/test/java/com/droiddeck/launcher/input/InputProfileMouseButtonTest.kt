package com.droiddeck.launcher.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputProfileMouseButtonTest {
    private val a = InputSurfaceElement(InputControl.A, 0f, 0f, .2f, .2f)
    private val trackpad = InputSurfaceElement(InputControl.RIGHT_PAD, 0f, 0f, .5f, .5f)
    private val trigger = InputSurfaceElement(InputControl.RT, 0f, 0f, .2f, .2f)

    @Test fun gameDefaultsHaveNoMouseButton() {
        assertFalse(InputProfile.defaults(InputProfile.GAME).hasMouseButton(InputRole.PRIMARY, listOf(a, trigger, trackpad)))
    }

    @Test fun desktopDefaultsBindMouseButtonsToButtonsAndTriggers() {
        val desktop = InputProfile.defaults(InputProfile.DESKTOP)
        assertTrue(desktop.hasMouseButton(InputRole.PRIMARY, listOf(a)))
        assertTrue(desktop.hasMouseButton(InputRole.PRIMARY, listOf(trigger)))
    }

    @Test fun trackpadsAndKeyBindingsDoNotCount() {
        val desktop = InputProfile.defaults(InputProfile.DESKTOP)
        assertFalse(desktop.hasMouseButton(InputRole.PRIMARY, listOf(trackpad)))
        assertFalse(InputProfile.defaults(InputProfile.GAME).withBinding(InputRole.PRIMARY, InputControl.A, InputAction.Keys(listOf(28)))
            .hasMouseButton(InputRole.PRIMARY, listOf(a)))
    }

    @Test fun customElementCountsOnlyWhenItsOwnActionIsAMouseButton() {
        val custom = InputSurfaceElement(InputControl.A, 0f, 0f, .2f, .2f, id = "custom:1")
        val game = InputProfile.defaults(InputProfile.GAME)
        assertFalse(game.hasMouseButton(InputRole.PRIMARY, listOf(custom)))
        val bound = game.copy(elements = mapOf(InputRole.PRIMARY to mapOf("custom:1" to InputAction.MouseButton(272))))
        assertTrue(bound.hasMouseButton(InputRole.PRIMARY, listOf(custom)))
        assertFalse(bound.hasMouseButton(InputRole.SECONDARY, listOf(custom)))
    }
}
