package com.droiddeck.launcher.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnScreenMouseButtonTest {
    private val button = InputSurfaceElement(InputControl.A, .8f, .6f, .1f, .1f, id = "custom:left")
    private val stick = InputSurfaceElement(InputControl.LS, .1f, .6f, .2f, .3f, id = "custom:stick")
    private val legacyA = InputSurfaceElement(InputControl.A, .8f, .6f, .1f, .1f)

    private fun profile(vararg elements: Pair<String, InputAction>, clicks: Map<String, InputAction> = emptyMap()) =
        InputProfile(InputProfile.GAME, elements = mapOf(InputRole.PRIMARY to elements.toMap()),
            clicks = mapOf(InputRole.PRIMARY to clicks))

    @Test fun shownButtonBoundToAMouseButtonIsDetected() {
        listOf(272, 273, 274).forEach { code ->
            assertTrue(profile(button.id to InputAction.MouseButton(code)).showsMouseButton(InputRole.PRIMARY, listOf(stick, button)))
        }
    }

    @Test fun stickClickBoundToAMouseButtonIsDetected() {
        val profile = profile(stick.id to InputAction.Native(), clicks = mapOf(stick.id to InputAction.MouseButton(272)))
        assertTrue(profile.showsMouseButton(InputRole.PRIMARY, listOf(stick)))
    }

    @Test fun legacyButtonUsesItsRoleBinding() {
        val profile = InputProfile(InputProfile.GAME).withBinding(InputRole.PRIMARY, InputControl.A, InputAction.MouseButton(272))
        assertTrue(profile.showsMouseButton(InputRole.PRIMARY, listOf(legacyA)))
        assertFalse(profile.showsMouseButton(InputRole.SECONDARY, listOf(legacyA)))
    }

    @Test fun layoutWithoutAMouseButtonKeepsTapsClicking() {
        val profile = profile(button.id to InputAction.Keys(listOf(57)), stick.id to InputAction.Pointer())
        assertFalse(profile.showsMouseButton(InputRole.PRIMARY, listOf(stick, button)))
        assertFalse(InputProfile.defaults(InputProfile.GAME).showsMouseButton(InputRole.PRIMARY, listOf(legacyA, stick)))
    }

    @Test fun hiddenMouseButtonDoesNotCount() {
        assertFalse(profile(button.id to InputAction.MouseButton(272)).showsMouseButton(InputRole.PRIMARY, listOf(stick)))
        assertFalse(profile(button.id to InputAction.MouseButton(272)).showsMouseButton(InputRole.PRIMARY, emptyList()))
    }

    @Test fun physicalMouseBindingsDoNotCount() {
        val profile = InputProfile(InputProfile.GAME).withBinding(InputRole.PHYSICAL, InputControl.A, InputAction.MouseButton(272))
        assertFalse(profile.showsMouseButton(InputRole.PRIMARY, listOf(legacyA)))
    }

    @Test fun invalidMouseCodeDoesNotCount() {
        assertFalse(profile(button.id to InputAction.MouseButton(1)).showsMouseButton(InputRole.PRIMARY, listOf(button)))
    }
}
