package com.droiddeck.launcher.input

import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.SessionActivity
import java.io.File
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [JoyConPadTest.RecordingWriter::class])
@LooperMode(LooperMode.Mode.PAUSED)
class SessionSystemInputTest {
    private val bridge = PadBridge(File(RuntimeEnvironment.getApplication().cacheDir, "system-input"))
    private val activity = Robolectric.buildActivity(SessionActivity::class.java).get().also {
        ReflectionHelpers.setField(it, "padBridge", bridge)
    }
    private val output = RecordingOutput()
    private val router = InputRouter(output).also {
        bridge.setInputRouter(it)
        bridge.setInputProfile(InputProfile.defaults(InputProfile.GAME)
            .withBinding(InputRole.PRIMARY, InputControl.GUIDE, InputAction.Disabled)
            .withBinding(InputRole.PRIMARY, InputControl.L3, InputAction.Keys(listOf(29)))
            .withBinding(InputRole.PRIMARY, InputControl.R3, InputAction.Command(InputCommand.MENU)))
    }

    @After fun close() { bridge.stop(); advance(500) }

    @Test fun steamMenuBypassesPrimaryBindingsAndDisabledRouting() {
        bridge.setInputEnabled(false)
        invoke("sendSteamGuide")
        assertTrue(state().isDown(PadState.GUIDE))
        advance(90)
        assertFalse(state().isDown(PadState.GUIDE))
        assertTrue(output.events.isEmpty())
    }

    @Test fun vrStickSwitchBypassesPrimaryBindingsAndPreservesPhysicalHold() {
        bridge.applyExternal({ it.press(PadState.L3, true) }, true)
        invoke("switchVrSticks")
        assertTrue(state().isDown(PadState.L3))
        assertTrue(state().isDown(PadState.R3))
        advance(120)
        assertTrue(state().isDown(PadState.L3))
        assertFalse(state().isDown(PadState.R3))
        assertTrue(output.events.isEmpty())
    }

    @Test fun releasingSystemActionsCancelsOldVrReleaseWithoutEndingNewTap() {
        invoke("switchVrSticks")
        advance(60)
        bridge.releaseAll()
        assertFalse(state().isDown(PadState.L3))
        assertFalse(state().isDown(PadState.R3))
        invoke("switchVrSticks")
        advance(60)
        assertTrue(state().isDown(PadState.L3))
        assertTrue(state().isDown(PadState.R3))
        advance(60)
        assertFalse(state().isDown(PadState.L3))
        assertFalse(state().isDown(PadState.R3))
    }

    private fun state() = JoyConPadTest.RecordingWriter.pads[0]!!
    private fun invoke(name: String) = ReflectionHelpers.callInstanceMethod<Unit>(activity, name)
    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        shadowOf(ReflectionHelpers.getStaticField<Handler>(PadBridge::class.java, "chordHandler").looper).idle()
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
}
