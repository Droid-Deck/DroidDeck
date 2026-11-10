package com.droiddeck.launcher.input

import org.junit.Assert.*
import org.junit.Test

class InputRouterTest {
    private class Output : InputOutput {
        val events = mutableListOf<String>()
        override fun key(code: Int, down: Boolean) { events += "key $code $down" }
        override fun button(code: Int, down: Boolean) { events += "button $code $down" }
        override fun pointer(dx: Float, dy: Float) { events += "pointer $dx $dy" }
        override fun scroll(steps: Int) { events += "scroll $steps" }
        override fun scrollHorizontal(steps: Int) { events += "horizontal $steps" }
        override fun command(command: InputCommand) { events += command.name }
        override fun requestMotion() { events += "motion" }
    }
    private val output = Output()
    private val router = InputRouter(output)
    private fun bind(control: InputControl, action: InputAction, role: InputRole = InputRole.PHYSICAL) =
        InputProfile.defaults(InputProfile.GAME).withBinding(role, control, action)

    @Test fun radialKeysKeepCustomDiagonalsAndReleaseSharedNeighborKeys() {
        router.setProfile(bind(InputControl.LS, InputAction.Directions(103, 106, 0, 105, listOf(30, 0, 0, 0))))
        val stick = router.channel(InputRole.PHYSICAL, InputControl.LS)
        stick.update(.7f, -.7f)
        assertEquals(listOf("key 30 true"), output.events)
        stick.update(-.7f, -.7f)
        assertEquals(listOf("key 30 true", "key 30 false", "key 105 true", "key 103 true"), output.events)
        output.events.clear()
        stick.update(0f, -1f)
        assertEquals(listOf("key 105 false"), output.events)
        router.releaseAll()
        assertEquals(listOf("key 105 false", "key 103 false"), output.events)
    }

    @Test fun horizontalStickUsesXAndKeepsTheVerticalWheelIndependent() {
        router.setProfile(bind(InputControl.RS, InputAction.Scroll(20f, true)).withBinding(InputRole.PHYSICAL, InputControl.LS, InputAction.Scroll(20f)))
        val horizontal = router.channel(InputRole.PHYSICAL, InputControl.RS)
        val vertical = router.channel(InputRole.PHYSICAL, InputControl.LS)
        horizontal.update(1f, 0f); vertical.update(0f, -1f)
        router.tick(.05f)
        assertTrue(output.events.contains("horizontal 1"))
        assertTrue(output.events.contains("scroll -1"))
        output.events.clear()
        horizontal.update(0f, 1f); vertical.update(1f, 0f)
        assertFalse(router.tick(.05f))
        assertTrue(output.events.isEmpty())
    }

    @Test fun horizontalTouchScrollFollowsContactAndDropsRemainderOnSuspend() {
        val channel = router.channel(InputRole.SECONDARY, InputControl.RIGHT_PAD, InputAction.Scroll(10f, true))
        channel.update(0f, 0f, true)
        channel.update(.25f, .8f, true)
        assertEquals(listOf("horizontal -2"), output.events)
        router.releaseAll()
        output.events.clear()
        channel.update(0f, 0f, false)
        channel.update(0f, 0f, true)
        channel.update(.06f, 0f, true)
        assertTrue(output.events.isEmpty())
    }

    @Test fun nativeProfileReturnsOriginalStateWithoutScheduling() {
        val source = router.pad(InputRole.PHYSICAL)
        val state = PadState().apply { leftX = .7f; press(PadState.A, true) }
        repeat(1000) { assertSame(state, source.route(state)) }
        assertTrue(output.events.isEmpty())
    }

    @Test fun retainedPadReturnsToNativeFastPathAfterHeldControlsReachNeutral() {
        val raw = PadState().apply { press(PadState.A, true); leftX = .8f }
        val source = router.pad(InputRole.PHYSICAL, raw)
        raw.press(PadState.B, true)
        val blocked = source.route(raw)
        assertFalse(blocked.isDown(PadState.A))
        assertEquals(0f, blocked.leftX)
        assertTrue(blocked.isDown(PadState.B))
        raw.clear()
        source.route(raw)
        raw.press(PadState.A, true)
        repeat(1000) { assertSame(raw, source.route(raw)) }
        assertTrue(output.events.isEmpty())
    }

    @Test fun localBindingConsumesOnlyItsOwnNativeControlAndReleasesModifiersLast() {
        router.setProfile(bind(InputControl.A, InputAction.Keys(listOf(29, 30))))
        val source = router.pad(InputRole.PHYSICAL)
        val state = PadState().apply { press(PadState.A, true); press(PadState.B, true); leftX = .4f }
        val routed = source.route(state)
        assertFalse(routed.isDown(PadState.A)); assertTrue(routed.isDown(PadState.B))
        assertEquals(.4f, routed.leftX)
        source.route(state)
        state.press(PadState.A, false); source.route(state)
        assertEquals(listOf("key 29 true", "key 30 true", "key 30 false", "key 29 false"), output.events)
    }

    @Test fun twoSourcesHoldingOneKeyDoNotReleaseEachOther() {
        val p = bind(InputControl.A, InputAction.Keys(listOf(57)))
            .withBinding(InputRole.SECONDARY, InputControl.B, InputAction.Keys(listOf(57)))
        router.setProfile(p)
        val physical = router.pad(InputRole.PHYSICAL)
        val touch = router.pad(InputRole.SECONDARY)
        physical.route(PadState().apply { press(PadState.A, true) })
        touch.route(PadState().apply { press(PadState.B, true) })
        touch.close()
        assertEquals(listOf("key 57 true"), output.events)
        physical.release()
        assertEquals(listOf("key 57 true", "key 57 false"), output.events)
    }

    @Test fun remapCombinesHeldTargetsRatherThanLastButtonWinning() {
        router.setProfile(bind(InputControl.A, InputAction.Native(InputControl.B)))
        val source = router.pad(InputRole.PHYSICAL)
        val raw = PadState().apply { press(PadState.A, true) }
        assertTrue(source.route(raw).isDown(PadState.B))
        raw.press(PadState.B, true); raw.press(PadState.A, false)
        assertTrue(source.route(raw).isDown(PadState.B))
        raw.press(PadState.B, false)
        assertFalse(source.route(raw).isDown(PadState.B))
    }

    @Test fun profileChangeBlocksHeldNativeInputUntilNeutral() {
        val source = router.pad(InputRole.PHYSICAL)
        val raw = PadState().apply { press(PadState.A, true) }
        source.route(raw)
        router.setProfile(bind(InputControl.A, InputAction.Keys(listOf(1))))
        source.route(raw)
        assertTrue(output.events.isEmpty())
        raw.press(PadState.A, false); source.route(raw)
        raw.press(PadState.A, true); source.route(raw)
        assertEquals(listOf("key 1 true"), output.events)
    }

    @Test fun changingLocalBindingReleasesOldKeyWithoutPressingNewOne() {
        router.setProfile(bind(InputControl.A, InputAction.Keys(listOf(1))))
        val source = router.pad(InputRole.PHYSICAL)
        val raw = PadState().apply { press(PadState.A, true) }
        source.route(raw)
        router.setProfile(bind(InputControl.A, InputAction.Keys(listOf(28))))
        source.route(raw)
        assertEquals(listOf("key 1 true", "key 1 false"), output.events)
    }

    @Test fun triggersUseHysteresisAndCommandsFireOnce() {
        router.setProfile(bind(InputControl.LT, InputAction.Command(InputCommand.MENU)))
        val source = router.pad(InputRole.PHYSICAL)
        val raw = PadState()
        for (value in listOf(.6f, .5f, .4f, .6f, .3f, .6f)) { raw.leftTrigger = value; source.route(raw) }
        assertEquals(listOf("MENU"), output.events)
        raw.leftTrigger = 0f; source.route(raw)
        assertEquals(listOf("MENU", "MENU"), output.events)
    }

    @Test fun commandsFireOnReleaseAndNotWhenCancelled() {
        router.setProfile(bind(InputControl.A, InputAction.Command(InputCommand.KEYBOARD), InputRole.PRIMARY))
        val button = router.channel(InputRole.PRIMARY, InputControl.A)
        button.update(1f)
        assertTrue(output.events.isEmpty())
        button.update(0f)
        assertEquals(listOf("KEYBOARD"), output.events)
        button.update(1f)
        button.release()
        button.update(0f)
        router.releaseAll()
        button.update(1f)
        router.releaseAll()
        button.update(0f)
        assertEquals(listOf("KEYBOARD"), output.events)
    }

    @Test fun pointerTicksOnlyWhileMovingAndOrdinaryKeysDoNotWaitForTick() {
        router.setProfile(InputProfile.defaults(InputProfile.DESKTOP))
        val source = router.pad(InputRole.PHYSICAL)
        val raw = PadState().apply { leftX = 1f; press(PadState.X, true) }
        source.route(raw)
        assertTrue(output.events.contains("key 28 true"))
        assertFalse(output.events.any { it.startsWith("pointer") })
        assertTrue(router.tick(.01f)); assertTrue(output.events.contains("pointer 9.0 0.0"))
        raw.clear(); source.route(raw)
        assertFalse(router.tick(.01f))
    }

    @Test fun trackpadUsesContactRelativeDeltasWithoutFirstTouchJump() {
        router.setProfile(bind(InputControl.RIGHT_PAD, InputAction.Pointer(1000f), InputRole.SECONDARY))
        val channel = router.channel(InputRole.SECONDARY, InputControl.RIGHT_PAD)
        assertNull(channel.update(.5f, .5f, true))
        assertTrue(output.events.isEmpty())
        channel.update(.6f, .6f, true)
        assertEquals(1, output.events.size)
        assertTrue(output.events[0].startsWith("pointer 100."))
        channel.update(0f, 0f, false)
        channel.update(-.9f, -.9f, true)
        assertEquals(1, output.events.size)
    }

    @Test fun stickScrollFollowsUpAndDownForEveryInputRoleAndStopsOnRelease() {
        for (role in InputRole.entries) {
            router.setProfile(bind(InputControl.RS, InputAction.Scroll(10f), role))
            val channel = router.channel(role, InputControl.RS)
            output.events.clear()
            channel.update(0f, -1f)
            repeat(2) { assertTrue(router.tick(.05f)) }
            assertEquals(listOf("motion", "scroll -1"), output.events)
            channel.update(0f, 1f)
            repeat(2) { assertTrue(router.tick(.05f)) }
            assertEquals("scroll 1", output.events.last())
            channel.release()
            val count = output.events.size
            assertFalse(router.tick(.05f))
            assertEquals(count, output.events.size)
            channel.close()
        }
    }

    @Test fun trackpadScrollKeepsItsContactDirectionAndDoesNotScheduleStickMotion() {
        router.setProfile(bind(InputControl.LEFT_PAD, InputAction.Scroll(10f), InputRole.SECONDARY))
        val channel = router.channel(InputRole.SECONDARY, InputControl.LEFT_PAD)
        channel.update(0f, 0f, true)
        channel.update(0f, .5f, true)
        channel.update(0f, 0f, true)
        assertEquals(listOf("scroll 5", "scroll -5"), output.events)
        assertFalse(router.tick(.05f))
    }

    @Test fun disablingRoutingReleasesKeysAndBlocksHeldInputOnResume() {
        router.setProfile(bind(InputControl.A, InputAction.Keys(listOf(1))))
        val source = router.pad(InputRole.PHYSICAL)
        val raw = PadState().apply { press(PadState.A, true) }
        source.route(raw); router.setEnabled(false); source.route(raw); router.setEnabled(true); source.route(raw)
        assertEquals(listOf("key 1 true", "key 1 false"), output.events)
        raw.clear(); source.route(raw); raw.press(PadState.A, true); source.route(raw)
        assertEquals("key 1 true", output.events.last())
    }

    @Test fun disabledSourcesDoNotLeakNativeButtons() {
        val source = router.pad(InputRole.PHYSICAL)
        router.setEnabled(false)
        assertFalse(source.route(PadState().apply { press(PadState.A, true) }).isDown(PadState.A))
    }

    @Test fun anOpenMenuAllowsTouchCommandsButStillBlocksGameAndPhysicalInput() {
        val menu = router.channel(InputRole.SECONDARY, InputControl.A, InputAction.Command(InputCommand.MENU))
        val physical = router.channel(InputRole.PHYSICAL, InputControl.A, InputAction.Command(InputCommand.MENU))
        val native = router.channel(InputRole.SECONDARY, InputControl.A, InputAction.Native())
        val key = router.channel(InputRole.PRIMARY, InputControl.A, InputAction.Keys(listOf(30)))
        val mouse = router.channel(InputRole.SECONDARY, InputControl.B, InputAction.MouseButton(272))
        val stick = router.channel(InputRole.SECONDARY, InputControl.LS, InputAction.Pointer())
        router.setEnabled(false)
        router.setTouchCommandsEnabled(true)
        for (channel in listOf(menu, physical, native, key, mouse, stick)) {
            assertNull(channel.update(1f))
            assertNull(channel.update(0f))
        }
        assertFalse(router.tick(.016f))
        assertEquals(listOf("MENU"), output.events)
    }

    @Test fun hidingTheMenuCancelsAPendingTouchCommandUntilAFreshPress() {
        val menu = router.channel(InputRole.SECONDARY, InputControl.A, InputAction.Command(InputCommand.MENU))
        router.setEnabled(false)
        router.setTouchCommandsEnabled(true)
        menu.update(1f)
        router.setTouchCommandsEnabled(false)
        router.setTouchCommandsEnabled(true)
        menu.update(0f)
        assertTrue(output.events.isEmpty())
        menu.update(1f); menu.update(0f)
        assertEquals(listOf("MENU"), output.events)
        output.events.clear()
        router.setTouchCommandsEnabled(false)
        menu.update(1f); menu.update(0f)
        assertTrue(output.events.isEmpty())
    }

    @Test fun lifecycleReleaseAlsoBlocksNativeIdentityRoute() {
        val source = router.pad(InputRole.PHYSICAL)
        val raw = PadState().apply { press(PadState.A, true) }
        source.route(raw); router.releaseAll()
        assertFalse(source.route(raw).isDown(PadState.A))
    }

    @Test fun mergedPadKeepsPhysicalButtonAndWholeStickVector() {
        val physical = PadState().apply { press(PadState.A, true); leftX = .8f; leftY = -.6f }
        physical.merge(PadState().apply { leftX = -.2f; leftY = .1f })
        assertTrue(physical.isDown(PadState.A)); assertEquals(.8f, physical.leftX); assertEquals(-.6f, physical.leftY)
    }

    @Test fun outputOwnershipKeepsKeyboardAndMappingHoldsIndependent() {
        val held = HeldInput(output::key)
        held.set(1, 29, true); held.set(4, 29, true); held.release(4)
        assertEquals(listOf("key 29 true"), output.events)
        held.clear(); assertEquals("key 29 false", output.events.last())
    }
}
