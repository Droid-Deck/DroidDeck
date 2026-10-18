package com.droiddeck.launcher.input

import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.InputDeviceBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.droiddeck.launcher.input.FakeInputWriter"],
    shadows = [PadBridgeRoutingTest.RegisteredKeyEvent::class, PadBridgeRoutingTest.RegisteredMotionEvent::class,
        JoyConPadTest.RecordingWriter::class])
class JoyConPadTest {
    private lateinit var dir: File
    private lateinit var bridge: PadBridge
    private lateinit var left: InputDevice
    private lateinit var right: InputDevice

    @Before fun setUp() {
        RecordingWriter.pads.clear()
        RecordingWriter.grips = 0
        dir = Files.createTempDirectory("joycon-pad").toFile()
        bridge = PadBridge(dir)
        left = joyCon(LEFT_PRODUCT, MotionEvent.AXIS_X, MotionEvent.AXIS_Y)
        right = joyCon(RIGHT_PRODUCT, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)
    }

    @After fun tearDown() {
        bridge.stop()
        dir.deleteRecursively()
    }

    @Test fun twoJoyConsBecomeOnePlayerWithBothSticks() {
        pair()
        assertEquals(left.id, PadBridge.joyConForSlot(0, JoyCon.LEFT))
        assertEquals(right.id, PadBridge.joyConForSlot(0, JoyCon.RIGHT))
        assertTrue(press(left, KeyEvent.KEYCODE_UNKNOWN, 0x220))
        assertTrue(press(right, KeyEvent.KEYCODE_BUTTON_X, BTN_NORTH))
        assertTrue(pad(0).up)
        assertTrue(pad(0).isDown(PadState.Y))
        assertTrue(bridge.onMotionEvent(motion(left, MotionEvent.AXIS_X to 0.75f, MotionEvent.AXIS_Y to -0.5f)))
        assertTrue(bridge.onMotionEvent(motion(right, MotionEvent.AXIS_RX to -0.6f, MotionEvent.AXIS_X to 0f)))
        assertEquals(0.75f, pad(0).leftX)
        assertEquals(-0.5f, pad(0).leftY)
        assertEquals(-0.6f, pad(0).rightX)
        assertNull("a second player appeared", RecordingWriter.pads[1])
        assertEquals(PadBridge.NO_CONTROLLER, PadBridge.joyConForSlot(1, JoyCon.LEFT))
    }

    @Test fun railButtonsAreShouldersOnlyForALoneJoyCon() {
        assertTrue(press(left, KeyEvent.KEYCODE_BUTTON_R1, BTN_TR))
        assertTrue(pad(0).isDown(PadState.RB))
        tap(right, KeyEvent.KEYCODE_BUTTON_A, BTN_SOUTH)
        assertFalse("a rail button held while pairing stayed down", pad(0).isDown(PadState.RB))
        assertTrue(bridge.onKeyEvent(JoyCon.normalize(key(left, KeyEvent.KEYCODE_BUTTON_R1, BTN_TR, KeyEvent.ACTION_UP))))
        assertTrue(press(right, KeyEvent.KEYCODE_BUTTON_R1, BTN_TR))
        assertTrue(press(left, KeyEvent.KEYCODE_BUTTON_R1, BTN_TR))
        assertTrue(bridge.onKeyEvent(JoyCon.normalize(key(left, KeyEvent.KEYCODE_BUTTON_R1, BTN_TR, KeyEvent.ACTION_UP))))
        assertTrue("the left rail released the right shoulder", pad(0).isDown(PadState.RB))
        assertTrue(press(right, KeyEvent.KEYCODE_BUTTON_L1, BTN_TL))
        assertFalse(pad(0).isDown(PadState.LB))
    }

    @Test fun captureOnTheFirstPlayerIsTheLeftGripUntilPairingReleasesIt() {
        assertTrue(press(left, KeyEvent.KEYCODE_BUTTON_Z, BTN_Z))
        assertEquals(DeckControls.L4, RecordingWriter.grips and DeckControls.L4)
        tap(right, KeyEvent.KEYCODE_BUTTON_A, BTN_SOUTH)
        assertEquals(0, RecordingWriter.grips)
    }

    @Test fun whenOneJoyConLeavesTheOtherKeepsThePlayer() {
        pair()
        assertTrue(press(right, KeyEvent.KEYCODE_BUTTON_A, BTN_SOUTH))
        bridge.onDeviceRemoved(left.id)
        assertEquals(PadBridge.NO_CONTROLLER, PadBridge.joyConForSlot(0, JoyCon.LEFT))
        assertEquals(PadBridge.NO_CONTROLLER, PadBridge.joyConForSlot(0, JoyCon.RIGHT))
        assertFalse("a held button survived the split", pad(0).isDown(PadState.A))
        assertEquals(right.id, PadBridge.controllerForSlot(0))
        tap(right, KeyEvent.KEYCODE_BUTTON_A, BTN_SOUTH)
        assertNull(RecordingWriter.pads[1])
        val another = joyCon(LEFT_PRODUCT, MotionEvent.AXIS_X, MotionEvent.AXIS_Y)
        tap(another, KeyEvent.KEYCODE_UNKNOWN, 0x221)
        assertEquals(another.id, PadBridge.joyConForSlot(0, JoyCon.LEFT))
        assertEquals(right.id, PadBridge.joyConForSlot(0, JoyCon.RIGHT))
        bridge.onDeviceRemoved(right.id)
        assertEquals(another.id, PadBridge.controllerForSlot(0))
        tap(another, KeyEvent.KEYCODE_UNKNOWN, 0x221)
        assertNull(RecordingWriter.pads[1])
    }

    @Test fun aSecondPlayerCanBeAJoyConPair() {
        val xbox = InputDeviceBuilder.newBuilder().setId(90).setName("pad").setDescriptor("pad-90").setExternal(true)
            .setVendorId(0x045e).setProductId(0x0b13).setSources(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)
            .setKeyboardType(InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC)
            .setKeyCharacterMap(KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)).build()
        register(xbox)
        tap(xbox, KeyEvent.KEYCODE_BUTTON_A, 0x130)
        pair()
        assertEquals(left.id, PadBridge.joyConForSlot(1, JoyCon.LEFT))
        assertEquals(right.id, PadBridge.joyConForSlot(1, JoyCon.RIGHT))
        assertEquals(PadBridge.NO_CONTROLLER, PadBridge.joyConForSlot(0, JoyCon.LEFT))
        assertTrue(press(right, KeyEvent.KEYCODE_BUTTON_B, 0x131))
        assertTrue(pad(1).isDown(PadState.B))
        assertNull(RecordingWriter.pads[2])
    }

    @Test fun joyConsHeldForVrAreLeftAlone() {
        bridge.setExcluded { it == left.id }
        assertFalse(bridge.onKeyEvent(key(left, KeyEvent.KEYCODE_BUTTON_L1, BTN_TL)))
        assertFalse(bridge.onMotionEvent(motion(left, MotionEvent.AXIS_X to 1f)))
        bridge.syncConnectedControllers()
        assertEquals(PadBridge.NO_CONTROLLER, PadBridge.joyConForSlot(0, JoyCon.LEFT))
        tap(right, KeyEvent.KEYCODE_BUTTON_A, BTN_SOUTH)
        assertEquals(right.id, PadBridge.controllerForSlot(0))
        assertEquals(PadBridge.NO_CONTROLLER, PadBridge.joyConForSlot(0, JoyCon.RIGHT))
        bridge.setExcluded { false }
        bridge.syncConnectedControllers()
        assertEquals(left.id, PadBridge.joyConForSlot(0, JoyCon.LEFT))
        assertEquals(right.id, PadBridge.joyConForSlot(0, JoyCon.RIGHT))
    }

    private fun pair() {
        tap(left, KeyEvent.KEYCODE_BUTTON_L1, BTN_TL)
        tap(right, KeyEvent.KEYCODE_BUTTON_R1, BTN_TR)
    }

    private fun pad(slot: Int): PadState = RecordingWriter.pads[slot] ?: PadState()

    private fun press(device: InputDevice, code: Int, scanCode: Int): Boolean =
        bridge.onKeyEvent(JoyCon.normalize(key(device, code, scanCode)))

    private fun tap(device: InputDevice, code: Int, scanCode: Int) {
        assertTrue(press(device, code, scanCode))
        assertTrue(bridge.onKeyEvent(JoyCon.normalize(key(device, code, scanCode, KeyEvent.ACTION_UP))))
    }

    private fun key(device: InputDevice, code: Int, scanCode: Int, action: Int = KeyEvent.ACTION_DOWN): KeyEvent {
        val event = KeyEvent(0L, 0L, action, code, 0, 0, device.id, scanCode, 0, InputDevice.SOURCE_GAMEPAD)
        assertSame(device, event.device)
        return event
    }

    private fun motion(device: InputDevice, vararg values: Pair<Int, Float>): MotionEvent {
        val coords = MotionEvent.PointerCoords().apply { for ((axis, value) in values) setAxisValue(axis, value) }
        return MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 1, arrayOf(MotionEvent.PointerProperties().apply { id = 0 }),
            arrayOf(coords), 0, 0, 1f, 1f, device.id, 0, InputDevice.SOURCE_JOYSTICK, 0)
    }

    private var nextId = 60

    private fun joyCon(product: Int, vararg axes: Int): InputDevice {
        val id = nextId++
        val builder = InputDeviceBuilder.newBuilder().setId(id).setName("Joy-Con $id").setDescriptor("joycon-$id")
            .setExternal(true).setVendorId(NINTENDO).setProductId(product)
            .setSources(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_KEYBOARD)
            .setKeyboardType(InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC)
            .setKeyCharacterMap(KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD))
        for (axis in axes) builder.addMotionRange(axis, InputDevice.SOURCE_JOYSTICK, -1f, 1f, 0f, 0f, 0f)
        return builder.build().also(::register)
    }

    private fun register(device: InputDevice) {
        val manager = RuntimeEnvironment.getApplication().getSystemService(InputManager::class.java)
        shadowOf(manager).addInputDevice(device)
        assertSame(device, InputDevice.getDevice(device.id))
    }

    private companion object {
        const val NINTENDO = 0x057e
        const val LEFT_PRODUCT = 0x2006
        const val RIGHT_PRODUCT = 0x2007
        const val BTN_SOUTH = 0x130
        const val BTN_NORTH = 0x133
        const val BTN_Z = 0x135
        const val BTN_TL = 0x136
        const val BTN_TR = 0x137
    }

    @Implements(FakeInputWriter::class)
    class RecordingWriter {
        private var slot = 0

        @Implementation fun __constructor__(fakeInputPath: String, slot: Int) { this.slot = slot }
        @Implementation fun open(): Boolean = true
        @Implementation fun close() = Unit
        @Implementation fun destroy() = Unit
        @Implementation fun writePad(state: PadState) { pads[slot] = PadState().apply { copyFrom(state) } }

        companion object {
            val pads = HashMap<Int, PadState>()
            var grips = 0

            @JvmStatic @Implementation fun __staticInitializer__() = Unit

            @JvmStatic @Implementation
            fun writeDeckControls(slot: Int, controls: Int, pads: ShortArray, pressure: ShortArray) { grips = controls }
        }
    }
}
