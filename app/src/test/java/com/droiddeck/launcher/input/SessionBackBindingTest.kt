package com.droiddeck.launcher.input

import android.hardware.input.InputManager
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.runtime.MutableState
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.LoadingState
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import java.io.File
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.InputDeviceBuilder
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [JoyConPadTest.RecordingWriter::class,
    PadBridgeRoutingTest.RegisteredKeyEvent::class])
class SessionBackBindingTest {
    private val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
    private val bridge = PadBridge(File(activity.cacheDir, "back-binding"))
    private val events = mutableListOf<Pair<Int, Boolean>>()
    private val router = InputRouter(object : InputOutput {
        override fun key(code: Int, down: Boolean) { events += code to down }
        override fun button(code: Int, down: Boolean) {}
        override fun pointer(dx: Float, dy: Float) {}
        override fun scroll(steps: Int) {}
        override fun command(command: InputCommand) {}
        override fun requestMotion() {}
    })
    private val bindings = PhysicalInputBindings(router)
    private val previousMode = SessionState.mode
    private val previousRunning = SessionState.running
    private val previousFrame = SessionState.firstFrameSeen
    private val previousDeck = SessionState.deckPad
    private val previousSuspended = SessionState.suspended

    @Before fun setUp() {
        SessionState.mode = SessionService.MODE_STEAM
        SessionState.running = true
        SessionState.firstFrameSeen = true
        SessionState.deckPad = true
        SessionState.suspended = false
        ReflectionHelpers.setStaticField(FakeInputWriter::class.java, "padType", FakeInputWriter.PAD_TYPE_DECK)
        ReflectionHelpers.setField(activity, "padBridge", bridge)
        ReflectionHelpers.setField(activity, "physicalInputBindings", bindings)
        ReflectionHelpers.setField(activity, "loading", LoadingState(activity).apply { visible = false })
        ReflectionHelpers.getField<MutableState<Boolean>>(activity, "resumed\$delegate").value = true
        val device = InputDeviceBuilder.newBuilder().setId(42).setName("Back key").setDescriptor("back-key")
            .setSources(InputDevice.SOURCE_KEYBOARD).setKeyboardType(InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC).build()
        shadowOf(activity.getSystemService(InputManager::class.java)).addInputDevice(device)
        bridge.setInputRouter(router)
        bridge.start()
    }

    @After fun close() {
        ReflectionHelpers.getField<BackQamInput>(activity, "backQamInput").cancel()
        bindings.close()
        bridge.stop()
        SessionState.mode = previousMode
        SessionState.running = previousRunning
        SessionState.firstFrameSeen = previousFrame
        SessionState.deckPad = previousDeck
        SessionState.suspended = previousSuspended
    }

    @Test fun capturedBackBindingTakesPrecedenceOverTheDefaultQamShortcut() {
        bindings.configure(listOf(PhysicalInputBinding(PhysicalInputKey.capture(event(true))!!, InputAction.Keys(listOf(28)))))
        assertTrue(activity.dispatchKeyEvent(event(true)))
        assertEquals(listOf(28 to true), events)
        advance(700)
        assertFalse(JoyConPadTest.RecordingWriter.pads[0]?.isDown(PadState.QAM) == true)
        assertTrue(activity.dispatchKeyEvent(event(false)))
        assertEquals(listOf(28 to true, 28 to false), events)
    }

    @Test fun anUnmappedBackKeyStillHoldsAndReleasesQam() {
        assertTrue(activity.dispatchKeyEvent(event(true)))
        advance(700)
        assertTrue(JoyConPadTest.RecordingWriter.pads[0]!!.isDown(PadState.QAM))
        assertTrue(activity.dispatchKeyEvent(event(false)))
        advance(1000)
        assertFalse(JoyConPadTest.RecordingWriter.pads[0]!!.isDown(PadState.QAM))
        assertTrue(events.isEmpty())
    }

    @Test fun capturedProfileBindingTakesPrecedenceOverGlobalShortcut() {
        assignMenuShortcut()
        bindings.configure(listOf(PhysicalInputBinding(PhysicalInputKey.capture(event(true))!!, InputAction.Keys(listOf(28)))))
        assertTrue(activity.dispatchKeyEvent(event(true)))
        assertTrue(activity.dispatchKeyEvent(event(false)))
        assertEquals(listOf(28 to true, 28 to false), events)
        assertFalse(drawerOpen())
    }

    @Test fun globalShortcutOverridesDefaultBackQamAndConsumesReleaseAcrossMenuChange() {
        assignMenuShortcut()
        assertTrue(activity.dispatchKeyEvent(event(true)))
        assertTrue(drawerOpen())
        advance(700)
        assertFalse(JoyConPadTest.RecordingWriter.pads[0]?.isDown(PadState.QAM) == true)
        assertTrue(activity.dispatchKeyEvent(event(false)))
        assertTrue(drawerOpen())
        assertTrue(activity.dispatchKeyEvent(event(true)))
        assertFalse(drawerOpen())
        assertTrue(activity.dispatchKeyEvent(event(false)))
        assertTrue(events.isEmpty())
    }

    @Test fun inputSettingsDoNotExecuteGlobalShortcuts() {
        assignMenuShortcut()
        ReflectionHelpers.getField<MutableState<Boolean>>(activity, "inputSettingsOpen\$delegate").value = true
        activity.dispatchKeyEvent(event(true))
        activity.dispatchKeyEvent(event(false))
        assertFalse(drawerOpen())
        assertTrue(events.isEmpty())
    }

    private fun assignMenuShortcut() {
        ReflectionHelpers.getField<PhysicalShortcutRouter>(activity, "physicalShortcuts").bindings =
            listOf(PhysicalShortcuts.Binding(PhysicalShortcuts.button(event(true))!!, PhysicalShortcuts.Action.DROIDDECK))
    }

    private fun drawerOpen(): Boolean =
        ReflectionHelpers.getField<MutableState<Boolean>>(activity, "drawerOpenState\$delegate").value

    private fun event(down: Boolean) = KeyEvent(0L, 0L, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
        KeyEvent.KEYCODE_BACK, 0, 0, 42, 158, 0, InputDevice.SOURCE_KEYBOARD)

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
}
