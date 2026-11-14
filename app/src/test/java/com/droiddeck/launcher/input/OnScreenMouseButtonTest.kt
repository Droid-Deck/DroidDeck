package com.droiddeck.launcher.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnScreenMouseButtonTest {
    private val button = InputSurfaceElement(InputControl.A, .8f, .6f, .1f, .1f, id = "custom:left")
    private val stick = InputSurfaceElement(InputControl.LS, .1f, .6f, .2f, .3f, id = "custom:stick")
    private val legacyA = InputSurfaceElement(InputControl.A, .8f, .6f, .1f, .1f)
    private val game = InputProfile.defaults(InputProfile.GAME)
    private val desktop = InputProfile.defaults(InputProfile.DESKTOP)
    private val overlay = InputControl.entries.map { InputSurfaceElement(it, .1f, .1f, .1f, .1f) }

    private fun profile(vararg elements: Pair<String, InputAction>, clicks: Map<String, InputAction> = emptyMap(), base: InputProfile = game) =
        base.copy(elements = mapOf(InputRole.PRIMARY to elements.toMap()), clicks = mapOf(InputRole.PRIMARY to clicks))

    @Test fun shownButtonBoundToAMouseButtonIsDetected() {
        listOf(272, 273, 274).forEach { code ->
            assertTrue(profile(button.id to InputAction.MouseButton(code)).showsMouseButton(InputRole.PRIMARY, listOf(stick, button), game))
        }
    }

    @Test fun stickClickBoundToAMouseButtonIsDetected() {
        val profile = profile(stick.id to InputAction.Native(), clicks = mapOf(stick.id to InputAction.MouseButton(272)))
        assertTrue(profile.showsMouseButton(InputRole.PRIMARY, listOf(stick), game))
    }

    @Test fun legacyButtonUsesItsRoleBinding() {
        val profile = game.withBinding(InputRole.PRIMARY, InputControl.A, InputAction.MouseButton(272))
        assertTrue(profile.showsMouseButton(InputRole.PRIMARY, listOf(legacyA), game))
        assertFalse(profile.showsMouseButton(InputRole.SECONDARY, listOf(legacyA), game))
    }

    @Test fun stockDesktopOverlayKeepsTapsClicking() {
        assertFalse(desktop.showsMouseButton(InputRole.PRIMARY, overlay, desktop))
    }

    @Test fun userBoundMouseButtonInDesktopIsDetected() {
        val rebound = desktop.withBinding(InputRole.PRIMARY, InputControl.X, InputAction.MouseButton(272))
        assertTrue(rebound.showsMouseButton(InputRole.PRIMARY, overlay, desktop))
        val stickClick = desktop.withBinding(InputRole.PRIMARY, InputControl.L3, InputAction.MouseButton(274))
        assertTrue(stickClick.showsMouseButton(InputRole.PRIMARY, overlay, desktop))
        assertTrue(profile(button.id to InputAction.MouseButton(272), base = desktop).showsMouseButton(InputRole.PRIMARY, overlay + button, desktop))
    }

    @Test fun desktopBindingsOnAGameBaseProfileAreUserPicked() {
        val copied = desktop.copy(id = "custom")
        assertTrue(copied.showsMouseButton(InputRole.PRIMARY, overlay, game))
    }

    @Test fun layoutWithoutAMouseButtonKeepsTapsClicking() {
        val profile = profile(button.id to InputAction.Keys(listOf(57)), stick.id to InputAction.Pointer())
        assertFalse(profile.showsMouseButton(InputRole.PRIMARY, listOf(stick, button), game))
        assertFalse(game.showsMouseButton(InputRole.PRIMARY, listOf(legacyA, stick) + overlay, game))
    }

    @Test fun hiddenMouseButtonDoesNotCount() {
        assertFalse(profile(button.id to InputAction.MouseButton(272)).showsMouseButton(InputRole.PRIMARY, listOf(stick), game))
        assertFalse(profile(button.id to InputAction.MouseButton(272)).showsMouseButton(InputRole.PRIMARY, emptyList(), game))
    }

    @Test fun physicalMouseBindingsDoNotCount() {
        val profile = game.withBinding(InputRole.PHYSICAL, InputControl.A, InputAction.MouseButton(272))
        assertFalse(profile.showsMouseButton(InputRole.PRIMARY, listOf(legacyA), game))
    }

    @Test fun invalidMouseCodeDoesNotCount() {
        assertFalse(profile(button.id to InputAction.MouseButton(1)).showsMouseButton(InputRole.PRIMARY, listOf(button), game))
    }
}
