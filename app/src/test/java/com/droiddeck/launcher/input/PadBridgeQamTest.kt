package com.droiddeck.launcher.input

import android.os.Handler
import android.os.Looper
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.droiddeck.launcher.session.SessionState
import java.nio.file.Files
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.InputDeviceBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.droiddeck.launcher.input.FakeInputWriter", "com.droiddeck.launcher.wayland.WaylandCompositor"],
    shadows = [JoyConPadTest.RecordingWriter::class, PadBridgeRoutingTest.RegisteredKeyEvent::class,
        PadBridgeRoutingTest.RegisteredMotionEvent::class, SessionGestureRoutingTest.CompositorShadow::class])
class PadBridgeQamTest {
    private val directory = Files.createTempDirectory("qam-pad").toFile()
    private lateinit var bridge: PadBridge

    @Before fun start() {
        JoyConPadTest.RecordingWriter.pads.clear()
        ReflectionHelpers.setStaticField(FakeInputWriter::class.java, "padType", FakeInputWriter.PAD_TYPE_DECK)
        SessionState.deckPad = true
        bridge = PadBridge(directory)
        assertTrue(bridge.start())
    }

    @After fun stop() {
        bridge.stop()
        SessionState.deckPad = false
        directory.deleteRecursively()
    }

    @Test fun onlyNativeQamControllersAcceptAHeldBack() {
        for (type in listOf(-1, FakeInputWriter.PAD_TYPE_DECK, FakeInputWriter.PAD_TYPE_STEAM,
            FakeInputWriter.PAD_TYPE_XBOX, FakeInputWriter.PAD_TYPE_DS4, FakeInputWriter.PAD_TYPE_DUALSENSE)) {
            ReflectionHelpers.setStaticField(FakeInputWriter::class.java, "padType", type)
            val supported = type <= FakeInputWriter.PAD_TYPE_STEAM
            assertEquals(supported, bridge.canHoldQam())
            bridge.setBackQamPressed(true)
            bridge.applyExternal({}, false)
            assertEquals(supported, pad().isDown(PadState.QAM))
            bridge.setBackQamPressed(false)
            advanceTime()
        }
        SessionState.deckPad = false
        assertFalse(bridge.canHoldQam())
    }

    @Test fun qamAndOtherButtonsShareAReportUntilBackIsReleased() {
        bridge.setBackQamPressed(true)
        advanceTime()
        bridge.applyExternal({ it.press(PadState.X, true); it.press(PadState.SELECT, true) }, true)
        advanceTime()
        assertTrue(pad().isDown(PadState.QAM))
        assertTrue(pad().isDown(PadState.X))
        assertTrue(pad().isDown(PadState.SELECT))
        bridge.setBackQamPressed(false)
        advanceTime()
        assertFalse(pad().isDown(PadState.QAM))
        assertTrue(pad().isDown(PadState.X))
        assertTrue(pad().isDown(PadState.SELECT))
    }

    @Test fun touchQamAndBackHaveIndependentReleases() {
        bridge.setBackQamPressed(true)
        bridge.setSystemButtons(false, true)
        bridge.setBackQamPressed(false)
        assertTrue(pad().isDown(PadState.QAM))
        bridge.setBackQamPressed(true)
        bridge.setSystemButtons(false, false)
        assertTrue(pad().isDown(PadState.QAM))
        bridge.setBackQamPressed(false)
        advanceTime()
        assertFalse(pad().isDown(PadState.QAM))
    }

    @Test fun aTimedQamTapCannotReleaseAHeldBack() {
        bridge.triggerQam()
        bridge.setBackQamPressed(true)
        advanceTime()
        assertTrue(pad().isDown(PadState.QAM))
        bridge.setBackQamPressed(false)
        assertFalse(pad().isDown(PadState.QAM))
    }

    @Test fun releasingAllOrStoppingClearsTheHold() {
        bridge.setBackQamPressed(true)
        bridge.releaseAll()
        assertFalse(pad().isDown(PadState.QAM))
        bridge.setBackQamPressed(true)
        bridge.stop()
        bridge.start()
        bridge.applyExternal({}, false)
        assertFalse(pad().isDown(PadState.QAM))
    }

    @Test fun disablingTheBuiltinPadClearsAndRejectsTheHold() {
        bridge.setBackQamPressed(true)
        bridge.setBuiltinPad(false)
        assertFalse(bridge.canHoldQam())
        assertFalse(pad().isDown(PadState.QAM))
        bridge.setBackQamPressed(true)
        bridge.setBuiltinPad(true)
        bridge.applyExternal({}, false)
        assertFalse(pad().isDown(PadState.QAM))
    }

    @Test fun aQuickPartnerPressReachesSteamWithQamAndReleasesIndependently() {
        val device = controller()
        val taps = mutableListOf<Boolean>()
        val input = BackQamInput(bridge::setBackQamPressed, taps::add)
        bridge.setOnChordInput {
            assertFalse(pad().isDown(PadState.X))
            input.onChordInput()
        }
        input.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
        assertTrue(bridge.onKeyEvent(key(device, KeyEvent.KEYCODE_BUTTON_X)))
        assertTrue(pad().isDown(PadState.QAM))
        assertFalse(pad().isDown(PadState.X))
        advance(79)
        assertFalse(pad().isDown(PadState.X))
        advance(1)
        assertTrue(pad().isDown(PadState.X))
        input.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), true)
        assertFalse(pad().isDown(PadState.QAM))
        assertTrue(pad().isDown(PadState.X))
        bridge.onKeyEvent(key(device, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.ACTION_UP))
        advance(80)
        assertFalse(pad().isDown(PadState.X))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(taps.isEmpty())
    }

    @Test fun onlyFreshAcceptedPrimaryControllerPressesPromoteAChord() {
        val device = controller()
        val second = controller(11)
        val promoted = mutableListOf<Boolean>()
        bridge.setOnChordInput { promoted.add(true) }
        bridge.onKeyEvent(key(device, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.ACTION_UP))
        bridge.onKeyEvent(key(device, KeyEvent.KEYCODE_BUTTON_X, repeat = 1))
        bridge.onKeyEvent(key(device, KeyEvent.KEYCODE_A))
        bridge.onKeyEvent(key(second, KeyEvent.KEYCODE_BUTTON_X))
        bridge.setExcluded { it == device.id }
        bridge.onKeyEvent(key(device, KeyEvent.KEYCODE_BUTTON_X))
        assertTrue(promoted.isEmpty())
        bridge.setExcluded { false }
        for (code in listOf(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_BUTTON_L2)) {
            bridge.onKeyEvent(key(device, code))
        }
        assertEquals(3, promoted.size)
    }

    @Test fun aQuickChordKeepsItsPressAndReleaseAfterTheModifierLead() {
        bridge.setBackQamPressed(true)
        bridge.applyExternal({ it.press(PadState.X, true) }, true)
        advance(20)
        bridge.applyExternal({ it.press(PadState.X, false) }, true)
        bridge.setBackQamPressed(false)
        assertTrue(pad().isDown(PadState.QAM))
        assertFalse(pad().isDown(PadState.X))
        advance(60)
        assertTrue(pad().isDown(PadState.QAM))
        assertTrue(pad().isDown(PadState.X))
        advance(19)
        assertTrue(pad().isDown(PadState.X))
        advance(1)
        assertFalse(pad().isDown(PadState.X))
        assertFalse(pad().isDown(PadState.QAM))
    }

    @Test fun cancellationDiscardsQueuedPartnersAndTheirReleases() {
        val input = BackQamInput(bridge::setBackQamPressed, {}, onCancel = bridge::cancelBackQam)
        input.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), true)
        input.onChordInput()
        bridge.applyExternal({ it.press(PadState.LB, true) }, true)
        input.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), true)
        input.cancel()
        advance(1000)
        assertFalse(pad().isDown(PadState.LB))
        assertFalse(pad().isDown(PadState.QAM))
        bridge.applyExternal({ it.press(PadState.X, true) }, true)
        input.cancel()
        assertTrue(pad().isDown(PadState.X))
    }

    @Test fun aSettledHoldDoesNotDelayLaterPartnerInput() {
        bridge.setBackQamPressed(true)
        advance(80)
        bridge.applyExternal({ it.press(PadState.LB, true) }, true)
        assertTrue(pad().isDown(PadState.LB))
        bridge.setBackQamPressed(false)
        assertFalse(pad().isDown(PadState.QAM))
    }

    @Test fun deliberateStickTriggerAndHatMotionPromoteChordsButNeutralAndDriftDoNot() {
        val device = controller()
        val promoted = mutableListOf<Boolean>()
        bridge.setOnChordInput { promoted.add(true) }
        for ((axis, value) in listOf(MotionEvent.AXIS_X to 0f, MotionEvent.AXIS_X to 0.1f,
            MotionEvent.AXIS_LTRIGGER to 0f, MotionEvent.AXIS_HAT_Y to 0f)) motion(device, axis, value)
        assertTrue(promoted.isEmpty())
        for ((axis, value) in listOf(MotionEvent.AXIS_X to -0.15f, MotionEvent.AXIS_LTRIGGER to 0.75f,
            MotionEvent.AXIS_HAT_Y to -1f)) motion(device, axis, value)
        assertEquals(3, promoted.size)
    }

    private fun controller(id: Int = 10): InputDevice {
        val builder = InputDeviceBuilder.newBuilder().setId(id).setName("QAM pad $id")
            .setDescriptor("qam-pad-$id").setExternal(true).setKeyboardType(InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC)
            .setSources(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)
        for (axis in listOf(MotionEvent.AXIS_X, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_HAT_Y)) {
            builder.addMotionRange(axis, InputDevice.SOURCE_JOYSTICK, 0f, 1f, 0f, 0f, 0f)
        }
        return builder.build().also {
            shadowOf(RuntimeEnvironment.getApplication().getSystemService(InputManager::class.java)).addInputDevice(it)
            bridge.applyExternal({}, false)
        }
    }

    private fun key(device: InputDevice, code: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0) =
        KeyEvent(0L, 0L, action, code, repeat, 0, device.id, 0, 0, InputDevice.SOURCE_GAMEPAD)

    private fun motion(device: InputDevice, axis: Int, value: Float) {
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0 }),
            arrayOf(MotionEvent.PointerCoords().apply { setAxisValue(axis, value) }),
            0, 0, 1f, 1f, device.id, 0, InputDevice.SOURCE_JOYSTICK, 0)
        try { assertTrue(bridge.onMotionEvent(event)) } finally { event.recycle() }
    }

    private fun pad() = requireNotNull(JoyConPadTest.RecordingWriter.pads[0])

    private fun advanceTime() {
        val handler = ReflectionHelpers.getStaticField<Handler>(PadBridge::class.java, "chordHandler")
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(2))
        advance(2000)
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
}
