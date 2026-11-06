package com.droiddeck.launcher.input

import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [JoyConPadTest.RecordingWriter::class])
class InputSurfaceSourceTest {
    private val events = mutableListOf<String>()
    private val router = InputRouter(object : InputOutput {
        override fun key(code: Int, down: Boolean) { events += "key:$code:$down" }
        override fun button(code: Int, down: Boolean) { events += "button:$code:$down" }
        override fun pointer(dx: Float, dy: Float) { events += "pointer" }
        override fun scroll(steps: Int) { events += "scroll" }
        override fun command(command: InputCommand) { events += command.name }
        override fun requestMotion() {}
    })
    private val bridge = PadBridge(File(RuntimeEnvironment.getApplication().cacheDir, "element-input"))
    private val sources = mutableListOf<InputSurfaceSource>()
    @Before fun setup() { bridge.setInputRouter(router); DeckControls.setRouter(router) }
    @After fun close() { sources.forEach { it.close() }; bridge.stop(); DeckControls.setRouter(null); DeckControls.releaseAll() }
    private fun button(id: String) = InputSurfaceElement(InputControl.A, .1f, .1f, .2f, .2f, id)
    private fun source(e: InputSurfaceElement) = InputSurfaceSource(bridge, InputRole.SECONDARY, e).also { sources += it }
    private fun bind(vararg actions: Pair<String, InputAction>) {
        bridge.setInputProfile(InputProfile("test", elements = mapOf(InputRole.SECONDARY to mapOf(*actions))))
    }
    private fun state() = JoyConPadTest.RecordingWriter.pads[0] ?: PadState()

    @Test fun toggleUsesSharedOwnershipAndReleasesOnMenuAndProfileChange() {
        bind("first" to InputAction.Keys(listOf(29)), "second" to InputAction.Keys(listOf(29)))
        val a = source(button("first").copy(toggle = true)); val b = source(button("second"))
        a.update(1f); a.update(0f)
        assertTrue(a.latched)
        assertEquals(listOf("key:29:true"), events)
        b.update(1f)
        a.update(1f); a.update(0f)
        assertFalse(a.latched)
        assertEquals(1, events.size)
        b.update(0f)
        assertEquals("key:29:false", events.last())
        a.update(1f); a.update(0f)
        bridge.setInputEnabled(false)
        assertFalse(a.latched)
        assertEquals("key:29:false", events.last())
        bridge.setInputEnabled(true)
        a.update(1f); a.update(0f)
        assertEquals("key:29:true", events.last())
        bind("first" to InputAction.Keys(listOf(57)))
        assertFalse(a.latched)
        assertEquals("key:29:false", events.last())
        a.update(1f); a.update(0f)
        assertEquals("key:57:true", events.last())
        a.release()
        assertEquals("key:57:false", events.last())
    }

    @Test fun nativeToggleReleasesWithoutRepressingAcrossProfileChange() {
        bind("first" to InputAction.Native(InputControl.B))
        val a = source(button("first").copy(toggle = true))
        a.update(1f); a.update(0f)
        assertTrue(state().isDown(PadState.B))
        bind("first" to InputAction.Native(InputControl.X))
        assertFalse(state().isDown(PadState.B))
        a.update(1f); a.update(0f)
        assertTrue(state().isDown(PadState.X))
        a.close()
        assertFalse(state().isDown(PadState.X))
    }

    @Test fun twoButtonsWithTheSameShapeHaveIndependentActions() {
        bind("first" to InputAction.Keys(listOf(29)), "second" to InputAction.Keys(listOf(57)))
        val a = source(button("first")); val b = source(button("second"))
        a.update(1f); b.update(1f); a.update(0f); b.update(0f)
        assertEquals(listOf("key:29:true", "key:57:true", "key:29:false", "key:57:false"), events)
    }

    @Test fun sharedLocalButtonRemainsHeldUntilBothElementsRelease() {
        bind("first" to InputAction.MouseButton(272), "second" to InputAction.MouseButton(272))
        val a = source(button("first")); val b = source(button("second"))
        a.update(1f); b.update(1f); a.close()
        assertEquals(listOf("button:272:true"), events)
        b.update(0f)
        assertEquals(listOf("button:272:true", "button:272:false"), events)
        a.update(1f)
        assertEquals(2, events.size)
    }

    @Test fun nativeTouchOutputIsNotRemappedTwiceAndKeepsOtherOwners() {
        bridge.setInputProfile(InputProfile("test", bindings = mapOf(InputRole.SECONDARY to mapOf(InputControl.B to InputAction.Disabled)),
            elements = mapOf(InputRole.SECONDARY to mapOf("first" to InputAction.Native(InputControl.B), "second" to InputAction.Native(InputControl.B)))))
        val a = source(button("first")); val b = source(button("second"))
        a.update(1f); b.update(1f)
        assertTrue(state().isDown(PadState.B))
        a.close()
        assertTrue(state().isDown(PadState.B))
        b.update(0f)
        assertFalse(state().isDown(PadState.B))
    }

    @Test fun profileChangeReleasesOldOutputAndRequiresNeutralBeforeNewAction() {
        bind("first" to InputAction.Keys(listOf(29)))
        val a = source(button("first")); a.update(1f)
        bind("first" to InputAction.Keys(listOf(57)))
        a.update(1f)
        assertEquals(listOf("key:29:true", "key:29:false"), events)
        a.update(0f); a.update(1f)
        assertEquals("key:57:true", events.last())
    }

    @Test fun unmappedNewButtonsDoNotInheritLegacyBindings() {
        bridge.setInputProfile(InputProfile.defaults(InputProfile.DESKTOP))
        source(button("new")).update(1f)
        assertTrue(events.isEmpty())
    }

    @Test fun stickClicksAreSuspendedWithTheRestOfInput() {
        val e = InputSurfaceElement(InputControl.LS, .1f, .1f, .3f, .3f, "stick", InputElementKind.STICK)
        bind(e.id to InputAction.Native(InputControl.RS))
        val stick = source(e)
        stick.click(true)
        assertTrue(state().isDown(PadState.R3))
        bridge.setInputEnabled(false)
        assertFalse(state().isDown(PadState.R3))
        bridge.setInputEnabled(true); stick.click(true)
        assertFalse(state().isDown(PadState.R3))
        stick.click(false); stick.click(true)
        assertTrue(state().isDown(PadState.R3))
        stick.close(); stick.click(true)
        assertFalse(state().isDown(PadState.R3))
    }

    @Test fun customTrackpadMovementAndClickHaveIndependentActions() {
        val e = InputSurfaceElement(InputControl.LEFT_PAD, .1f, .1f, .4f, .4f, "mouse", InputElementKind.TRACKPAD)
        bridge.setInputProfile(InputProfile("test", elements = mapOf(InputRole.SECONDARY to mapOf(e.id to InputAction.Pointer())),
            clicks = mapOf(InputRole.SECONDARY to mapOf(e.id to InputAction.MouseButton(272)))))
        val surface = source(e)
        surface.update(0f, 0f, true); surface.update(.2f, .2f, true); surface.click(true)
        assertEquals(listOf("pointer", "button:272:true"), events)
        bridge.setInputEnabled(false)
        assertEquals("button:272:false", events.last())
        bridge.setInputEnabled(true); surface.click(true)
        assertEquals(3, events.size)
        surface.click(false); surface.click(true); surface.close()
        assertEquals(listOf("pointer", "button:272:true", "button:272:false", "button:272:true", "button:272:false"), events)
    }

    @Test fun migratedTrackpadClickStillUsesTheExistingLogicalBinding() {
        bridge.setInputProfile(InputProfile.defaults(InputProfile.DESKTOP))
        val e = InputSurfaceElement(InputControl.RIGHT_PAD, .1f, .1f, .4f, .4f)
        val surface = source(e)
        surface.click(true); surface.click(false)
        assertEquals(listOf("button:272:true", "button:272:false"), events)
    }
}
