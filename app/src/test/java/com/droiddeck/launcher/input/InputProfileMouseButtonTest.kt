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

    @Test fun stockDesktopBindingsDoNotCount() {
        val desktop = InputProfile.defaults(InputProfile.DESKTOP)
        assertFalse(desktop.hasMouseButton(InputRole.PRIMARY, listOf(a, trigger)))
    }

    @Test fun reboundDesktopButtonAndGameBaseMouseBindingsCount() {
        val desktop = InputProfile.defaults(InputProfile.DESKTOP)
        assertTrue(desktop.withBinding(InputRole.PRIMARY, InputControl.A, InputAction.MouseButton(274)).hasMouseButton(InputRole.PRIMARY, listOf(a)))
        assertFalse(desktop.withBinding(InputRole.PRIMARY, InputControl.A, InputAction.Keys(listOf(28))).hasMouseButton(InputRole.PRIMARY, listOf(a)))
        assertTrue(InputProfile.defaults(InputProfile.GAME).withBinding(InputRole.PRIMARY, InputControl.RT, InputAction.MouseButton(272))
            .hasMouseButton(InputRole.PRIMARY, listOf(trigger)))
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

    @Test fun savedDesktopProfileComparesAgainstItsDesktopBase() {
        val saved = InputProfile.defaults(InputProfile.DESKTOP).copy(id = "saved:1", base = InputProfile.DESKTOP)
        assertFalse(saved.hasMouseButton(InputRole.PRIMARY, listOf(a, trigger)))
        assertTrue(saved.withBinding(InputRole.PRIMARY, InputControl.A, InputAction.MouseButton(274)).hasMouseButton(InputRole.PRIMARY, listOf(a)))
    }

    @Test fun configuredStickAndTrackpadClickMouseButtonsCount() {
        val stick = InputSurfaceElement(InputControl.LS, 0f, 0f, .3f, .3f)
        val desktop = InputProfile.defaults(InputProfile.DESKTOP)
        assertFalse(desktop.hasMouseButton(InputRole.PRIMARY, listOf(stick, trackpad)))
        assertFalse(InputProfile.defaults(InputProfile.GAME).hasMouseButton(InputRole.PRIMARY, listOf(stick, trackpad)))
        val stickClick = desktop.copy(clicks = mapOf(InputRole.PRIMARY to mapOf(stick.id to InputAction.MouseButton(274))))
        assertTrue(stickClick.hasMouseButton(InputRole.PRIMARY, listOf(stick)))
        assertFalse(stickClick.hasMouseButton(InputRole.SECONDARY, listOf(stick)))
        val padClick = InputProfile.defaults(InputProfile.GAME).copy(clicks = mapOf(InputRole.PRIMARY to mapOf(trackpad.id to InputAction.MouseButton(272))))
        assertTrue(padClick.hasMouseButton(InputRole.PRIMARY, listOf(trackpad)))
    }

    @Test fun secondaryRoleBindingCountsOnlyForTheSecondaryRole() {
        val secondary = InputProfile.defaults(InputProfile.GAME).withBinding(InputRole.SECONDARY, InputControl.A, InputAction.MouseButton(272))
        assertTrue(secondary.hasMouseButton(InputRole.SECONDARY, listOf(a)))
        assertFalse(secondary.hasMouseButton(InputRole.PRIMARY, listOf(a)))
    }
}
