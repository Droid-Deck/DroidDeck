package com.droiddeck.launcher.input

import java.io.File
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [JoyConPadTest.RecordingWriter::class])
class InputSourceOwnershipTest {
    private val bridge = PadBridge(File(RuntimeEnvironment.getApplication().cacheDir, "input-ownership"))
    private fun state() = JoyConPadTest.RecordingWriter.pads[0]!!
    @After fun close() { bridge.stop(); DeckControls.releaseAll(); DeckControls.setRouter(null) }

    @Test fun profileAndEnableChangesLeaveRingClosedUntilInputArrives() {
        bridge.setInputRouter(InputRouter(RecordingOutput()))
        bridge.setInputProfile(InputProfile.defaults(InputProfile.DESKTOP))
        bridge.setInputEnabled(false)
        bridge.setInputEnabled(true)
        assertFalse(ReflectionHelpers.getField<Boolean>(bridge, "open"))
        bridge.applyExternal({ it.press(PadState.X, true) }, true)
        assertTrue(ReflectionHelpers.getField<Boolean>(bridge, "open"))
    }

    @Test fun disablingRoutingPublishesReleaseToAnAlreadyOpenRing() {
        bridge.setInputRouter(InputRouter(RecordingOutput()))
        bridge.applyExternal({ it.press(PadState.A, true) }, true)
        assertTrue(state().isDown(PadState.A))
        bridge.setInputEnabled(false)
        assertFalse(state().isDown(PadState.A))
        bridge.setInputEnabled(true)
        assertFalse(state().isDown(PadState.A))
    }

    @Test fun repeatedEnableStateDoesNotRepublishPadOrDeckReports() {
        val router = InputRouter(RecordingOutput())
        bridge.setInputRouter(router)
        DeckControls.setRouter(router)
        bridge.applyExternal({ it.press(PadState.A, true) }, true)
        DeckControls.setGrip(DeckControls.L4, true)
        for (enabled in listOf(true, false, true)) {
            bridge.setInputEnabled(enabled)
            val padWrites = JoyConPadTest.RecordingWriter.padWrites
            val deckWrites = JoyConPadTest.RecordingWriter.deckWrites
            repeat(10) { bridge.setInputEnabled(enabled) }
            assertEquals(padWrites, JoyConPadTest.RecordingWriter.padWrites)
            assertEquals(deckWrites, JoyConPadTest.RecordingWriter.deckWrites)
        }
        assertFalse(state().isDown(PadState.A))
        assertEquals(0, JoyConPadTest.RecordingWriter.grips)
        bridge.applyExternal({ it.press(PadState.A, false) }, true)
        DeckControls.setGrip(DeckControls.L4, false)
        bridge.applyExternal({ it.press(PadState.A, true) }, true)
        DeckControls.setGrip(DeckControls.L4, true)
        assertTrue(state().isDown(PadState.A))
        assertEquals(DeckControls.L4, JoyConPadTest.RecordingWriter.grips)
    }

    @Test fun replacingADisabledRouterAllowsTheNewRouterToBeDisabled() {
        bridge.setInputRouter(InputRouter(RecordingOutput()))
        bridge.setInputEnabled(false)
        bridge.setInputRouter(InputRouter(RecordingOutput()))
        bridge.applyExternal({ it.press(PadState.A, true) }, true)
        assertTrue(state().isDown(PadState.A))
        bridge.setInputEnabled(false)
        assertFalse(state().isDown(PadState.A))
    }

    @Test fun attachingADisabledRouterAllowsItToBeEnabled() {
        bridge.setInputRouter(InputRouter(RecordingOutput()).also { it.setEnabled(false) })
        bridge.setInputEnabled(true)
        bridge.applyExternal({ it.press(PadState.A, true) }, true)
        assertTrue(state().isDown(PadState.A))
    }

    @Test fun replacingRouterReleasesRetainedPhysicalAndTouchHoldsUntilNeutral() {
        val before = RecordingOutput()
        val after = RecordingOutput()
        val profile = InputProfile.defaults(InputProfile.GAME)
            .withBinding(InputRole.PHYSICAL, InputControl.A, InputAction.Keys(listOf(29)))
            .withBinding(InputRole.SECONDARY, InputControl.B, InputAction.Keys(listOf(29)))
        bridge.setInputRouter(InputRouter(before).also { it.setProfile(profile) })
        bridge.applyExternal({ it.press(PadState.A, true) }, true)
        val touch = Any()
        bridge.applyTouch(touch, InputRole.SECONDARY) { it.press(PadState.B, true) }
        bridge.setInputRouter(InputRouter(after).also { it.setProfile(profile) })
        bridge.applyExternal({ it.press(PadState.X, true) }, true)
        assertEquals(listOf("key 29 true", "key 29 false"), before.events)
        assertTrue(after.events.isEmpty())
        bridge.applyExternal({ it.press(PadState.A, false) }, true)
        bridge.applyExternal({ it.press(PadState.A, true) }, true)
        assertEquals(listOf("key 29 true"), after.events)
        bridge.applyExternal({ it.press(PadState.A, false) }, true)
        assertEquals(listOf("key 29 true", "key 29 false"), after.events)
        bridge.applyTouch(touch, InputRole.SECONDARY) { it.press(PadState.B, false) }
        bridge.applyTouch(touch, InputRole.SECONDARY) { it.press(PadState.B, true) }
        assertEquals("key 29 true", after.events.last())
    }

    @Test fun replacingRouterReleasesRetainedNativeButtonsAndStick() {
        bridge.setInputRouter(InputRouter(RecordingOutput()))
        bridge.applyExternal({ it.press(PadState.A, true); it.leftX = .8f }, true)
        bridge.setInputRouter(InputRouter(RecordingOutput()))
        assertFalse(state().isDown(PadState.A))
        assertEquals(0f, state().leftX)
        bridge.applyExternal({ it.press(PadState.B, true) }, true)
        assertTrue(state().isDown(PadState.B))
        assertFalse(state().isDown(PadState.A))
        assertEquals(0f, state().leftX)
        bridge.applyExternal({ it.press(PadState.A, false); it.leftX = 0f }, true)
        bridge.applyExternal({ it.press(PadState.A, true); it.leftX = .8f }, true)
        assertTrue(state().isDown(PadState.A))
        assertEquals(.8f, state().leftX)
    }

    @Test fun replacingDeckRouterBlocksExistingGripAndContactUntilRelease() {
        DeckControls.setRouter(InputRouter(RecordingOutput()))
        val surface = DeckControls.source(InputRole.SECONDARY)
        try {
            surface.setGrip(DeckControls.L4, true)
            surface.setPad(false, true, .3f, .4f)
            DeckControls.setRouter(InputRouter(RecordingOutput()))
            assertEquals(0, JoyConPadTest.RecordingWriter.grips)
            surface.setPad(false, true, .5f, .6f)
            assertEquals(0, JoyConPadTest.RecordingWriter.grips)
            surface.setGrip(DeckControls.L4, false)
            surface.setPad(false, false)
            surface.setGrip(DeckControls.L4, true)
            surface.setPad(false, true, .2f, .3f)
            assertEquals(DeckControls.L4 or 16, JoyConPadTest.RecordingWriter.grips)
        } finally { surface.close() }
    }

    @Test fun releasingAllKeepsPhysicalGripsBlockedUntilNeutral() {
        val router = InputRouter(RecordingOutput())
        bridge.setInputRouter(router)
        DeckControls.setRouter(router)
        DeckControls.setGrip(DeckControls.L4, true)
        assertEquals(DeckControls.L4, JoyConPadTest.RecordingWriter.grips)
        bridge.releaseAll()
        DeckControls.setGrip(DeckControls.L4, true)
        assertEquals(0, JoyConPadTest.RecordingWriter.grips)
        DeckControls.setGrip(DeckControls.L4, false)
        DeckControls.setGrip(DeckControls.L4, true)
        assertEquals(DeckControls.L4, JoyConPadTest.RecordingWriter.grips)
    }

    @Test fun cancellingHomeReleasesDeckOutputAndKeepsGripsBlockedUntilNeutral() {
        val router = InputRouter(RecordingOutput())
        bridge.setInputRouter(router)
        DeckControls.setRouter(router)
        bridge.setHomeGuidePressed(true)
        DeckControls.setGrip(DeckControls.L4, true)
        assertEquals(DeckControls.L4, JoyConPadTest.RecordingWriter.grips)
        bridge.cancelHomeGuide()
        assertEquals(0, JoyConPadTest.RecordingWriter.grips)
        DeckControls.setGrip(DeckControls.L4, true)
        assertEquals(0, JoyConPadTest.RecordingWriter.grips)
        DeckControls.setGrip(DeckControls.L4, false)
        DeckControls.setGrip(DeckControls.L4, true)
        assertEquals(DeckControls.L4, JoyConPadTest.RecordingWriter.grips)
    }

    private class RecordingOutput : InputOutput {
        val events = mutableListOf<String>()
        override fun key(code: Int, down: Boolean) { events += "key $code $down" }
        override fun button(code: Int, down: Boolean) { events += "button $code $down" }
        override fun pointer(dx: Float, dy: Float) { events += "pointer" }
        override fun scroll(steps: Int) { events += "scroll" }
        override fun command(command: InputCommand) { events += command.name }
        override fun requestMotion() { events += "motion" }
    }

    @Test fun removingLastTouchSurfaceReleasesLegacyMouseRoute() {
        val routed = PadState()
        bridge.setTouchRoute(object : PadBridge.TouchRoute {
            override fun active() = true
            override fun accept(state: PadState) { routed.copyFrom(state) }
        })
        val first = Any(); val second = Any()
        bridge.applyTouch(first, InputRole.PRIMARY) { it.press(PadState.A, true) }
        bridge.applyTouch(second, InputRole.SECONDARY) { it.press(PadState.A, true) }
        bridge.releaseTouch(first)
        assertTrue(routed.isDown(PadState.A))
        bridge.releaseTouch(second)
        assertFalse(routed.isDown(PadState.A))
    }

    @Test fun nativeTrackpadKeepsItsOwnerUntilThatContactEnds() {
        val first = DeckControls.source(InputRole.PRIMARY)
        val second = DeckControls.source(InputRole.SECONDARY)
        try {
            first.setPad(false, true, .25f, .5f)
            val firstPosition = JoyConPadTest.RecordingWriter.deckPads.copyOf()
            second.setPad(false, true, -.75f, -.5f)
            assertArrayEquals(firstPosition, JoyConPadTest.RecordingWriter.deckPads)
            first.setPad(false, false)
            assertTrue(JoyConPadTest.RecordingWriter.deckPads[0] < 0)
            second.close()
            assertEquals(0, JoyConPadTest.RecordingWriter.grips and 16)
        } finally { first.close(); second.close() }
    }

    @Test fun closingOneSurfaceDoesNotReleaseAnotherSurfaceOrPhysicalPad() {
        val a = Any(); val b = Any()
        bridge.applyExternal({ it.press(PadState.A, true) }, true)
        bridge.applyTouch(a, InputRole.PRIMARY) { it.press(PadState.A, true); it.press(PadState.B, true) }
        bridge.applyTouch(b, InputRole.SECONDARY) { it.press(PadState.X, true) }
        bridge.releaseTouch(a)
        assertTrue(state().isDown(PadState.A)); assertTrue(state().isDown(PadState.X)); assertFalse(state().isDown(PadState.B))
        bridge.releaseTouch(b)
        assertTrue(state().isDown(PadState.A)); assertFalse(state().isDown(PadState.X))
    }

    @Test fun disconnectingSecondaryDeckSurfaceKeepsPhysicalGripHeld() {
        val surface = DeckControls.source(InputRole.SECONDARY)
        DeckControls.setGrip(DeckControls.L4, true)
        surface.setGrip(DeckControls.L4, true)
        surface.close()
        assertEquals(DeckControls.L4, JoyConPadTest.RecordingWriter.grips)
        DeckControls.setGrip(DeckControls.L4, false)
        assertEquals(0, JoyConPadTest.RecordingWriter.grips)
    }

    @Test fun nativePadAndTouchMappingsUseDifferentRoles() {
        val output = object : InputOutput {
            override fun key(code: Int, down: Boolean) {}
            override fun button(code: Int, down: Boolean) {}
            override fun pointer(dx: Float, dy: Float) {}
            override fun scroll(steps: Int) {}
            override fun command(command: InputCommand) {}
            override fun requestMotion() {}
        }
        val router = InputRouter(output)
        bridge.setInputRouter(router)
        bridge.setInputProfile(InputProfile.defaults(InputProfile.GAME).withBinding(InputRole.SECONDARY, InputControl.A, InputAction.Disabled))
        val touch = Any()
        bridge.applyTouch(touch, InputRole.SECONDARY) { it.press(PadState.A, true) }
        assertFalse(state().isDown(PadState.A))
        bridge.applyExternal({ it.press(PadState.A, true) }, true)
        assertTrue(state().isDown(PadState.A))
        bridge.releaseAll()
        assertFalse(state().isDown(PadState.A))
        bridge.applyExternal({ it.press(PadState.B, true) }, true)
        assertFalse(state().isDown(PadState.A))
        assertTrue(state().isDown(PadState.B))
        bridge.applyExternal({ it.press(PadState.A, false) }, true)
        bridge.applyExternal({ it.press(PadState.A, true) }, true)
        assertTrue(state().isDown(PadState.A))
    }
}
