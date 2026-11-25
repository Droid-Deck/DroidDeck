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

    @Test fun shownButtonBoundToTheLeftMouseButtonIsDetected() {
        assertTrue(profile(button.id to InputAction.MouseButton(272)).showsLeftMouseButton(InputRole.PRIMARY, listOf(stick, button), game))
    }

    @Test fun rightOrMiddleMouseButtonAloneKeepsTapsClicking() {
        listOf(273, 274, 275, 276).forEach { code ->
            assertFalse(profile(button.id to InputAction.MouseButton(code)).showsLeftMouseButton(InputRole.PRIMARY, listOf(stick, button), game))
            val stickClick = profile(stick.id to InputAction.Native(), clicks = mapOf(stick.id to InputAction.MouseButton(code)))
            assertFalse(stickClick.showsLeftMouseButton(InputRole.PRIMARY, listOf(stick), game))
            assertFalse(game.withBinding(InputRole.PRIMARY, InputControl.A, InputAction.MouseButton(code)).showsLeftMouseButton(InputRole.PRIMARY, listOf(legacyA), game))
        }
    }

    @Test fun rightButtonNextToALeftButtonStillStopsTapClicks() {
        val right = InputSurfaceElement(InputControl.B, .7f, .6f, .1f, .1f, id = "custom:right")
        val both = profile(button.id to InputAction.MouseButton(272), right.id to InputAction.MouseButton(273))
        assertTrue(both.showsLeftMouseButton(InputRole.PRIMARY, listOf(button, right), game))
        assertFalse(both.showsLeftMouseButton(InputRole.PRIMARY, listOf(right), game))
    }

    @Test fun stickClickBoundToAMouseButtonIsDetected() {
        val profile = profile(stick.id to InputAction.Native(), clicks = mapOf(stick.id to InputAction.MouseButton(272)))
        assertTrue(profile.showsLeftMouseButton(InputRole.PRIMARY, listOf(stick), game))
    }

    @Test fun legacyButtonUsesItsRoleBinding() {
        val profile = game.withBinding(InputRole.PRIMARY, InputControl.A, InputAction.MouseButton(272))
        assertTrue(profile.showsLeftMouseButton(InputRole.PRIMARY, listOf(legacyA), game))
        assertFalse(profile.showsLeftMouseButton(InputRole.SECONDARY, listOf(legacyA), game))
    }

    @Test fun stockDesktopOverlayKeepsTapsClicking() {
        assertFalse(desktop.showsLeftMouseButton(InputRole.PRIMARY, overlay, desktop))
    }

    @Test fun userBoundMouseButtonInDesktopIsDetected() {
        val rebound = desktop.withBinding(InputRole.PRIMARY, InputControl.X, InputAction.MouseButton(272))
        assertTrue(rebound.showsLeftMouseButton(InputRole.PRIMARY, overlay, desktop))
        val stickClick = desktop.withBinding(InputRole.PRIMARY, InputControl.L3, InputAction.MouseButton(272))
        assertTrue(stickClick.showsLeftMouseButton(InputRole.PRIMARY, overlay, desktop))
        val middleStickClick = desktop.withBinding(InputRole.PRIMARY, InputControl.L3, InputAction.MouseButton(274))
        assertFalse(middleStickClick.showsLeftMouseButton(InputRole.PRIMARY, overlay, desktop))
        assertTrue(profile(button.id to InputAction.MouseButton(272), base = desktop).showsLeftMouseButton(InputRole.PRIMARY, overlay + button, desktop))
    }

    @Test fun desktopBindingsOnAGameBaseProfileAreUserPicked() {
        val copied = desktop.copy(id = "custom")
        assertTrue(copied.showsLeftMouseButton(InputRole.PRIMARY, overlay, game))
    }

    @Test fun layoutWithoutAMouseButtonKeepsTapsClicking() {
        val profile = profile(button.id to InputAction.Keys(listOf(57)), stick.id to InputAction.Pointer())
        assertFalse(profile.showsLeftMouseButton(InputRole.PRIMARY, listOf(stick, button), game))
        assertFalse(game.showsLeftMouseButton(InputRole.PRIMARY, listOf(legacyA, stick) + overlay, game))
    }

    @Test fun hiddenMouseButtonDoesNotCount() {
        assertFalse(profile(button.id to InputAction.MouseButton(272)).showsLeftMouseButton(InputRole.PRIMARY, listOf(stick), game))
        assertFalse(profile(button.id to InputAction.MouseButton(272)).showsLeftMouseButton(InputRole.PRIMARY, emptyList(), game))
    }

    @Test fun physicalMouseBindingsDoNotCount() {
        val profile = game.withBinding(InputRole.PHYSICAL, InputControl.A, InputAction.MouseButton(272))
        assertFalse(profile.showsLeftMouseButton(InputRole.PRIMARY, listOf(legacyA), game))
    }

    @Test fun invalidMouseCodeDoesNotCount() {
        assertFalse(profile(button.id to InputAction.MouseButton(1)).showsLeftMouseButton(InputRole.PRIMARY, listOf(button), game))
    }
}
