package com.droiddeck.launcher.input

import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
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
import org.robolectric.shadows.InputDeviceBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.droiddeck.launcher.input.FakeInputWriter"],
    shadows = [PadBridgeRoutingTest.RegisteredKeyEvent::class, PadBridgeRoutingTest.RegisteredMotionEvent::class,
        JoyConPadTest.RecordingWriter::class])
class XrGamepadInputTest {
    private val dir = Files.createTempDirectory("xr-pad").toFile()
    private lateinit var bridge: PadBridge
    private lateinit var input: XrGamepadInput
    private lateinit var device: InputDevice

    @Before fun setup() {
        JoyConPadTest.RecordingWriter.pads.clear()
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(InputManager::class.java)
        device = InputDeviceBuilder.newBuilder().setId(91).setName("Xbox").setDescriptor("xr-xbox")
            .setExternal(true).setVendorId(0x045e).setProductId(0x0b13)
            .setSources(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)
            .setKeyboardType(InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC)
            .setKeyCharacterMap(KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD))
            .addMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_JOYSTICK, -1f, 1f, 0f, 0f, 0f)
            .addMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_JOYSTICK, -1f, 1f, 0f, 0f, 0f).build()
        shadowOf(manager).addInputDevice(device)
        bridge = PadBridge(dir)
        input = XrGamepadInput(app) { bridge }
        input.resume()
        input.focus(true)
    }

    @After fun cleanup() { input.pause(); bridge.stop(); dir.deleteRecursively() }

    private fun key(code: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0) =
        input.key(KeyEvent(1L, 2L, action, code, repeat, 0, device.id, 0, 0, InputDevice.SOURCE_GAMEPAD))
    private fun pad() = JoyConPadTest.RecordingWriter.pads[0] ?: PadState()

    @Test fun buttonsAndNativeAxesReachExistingPadBridge() {
        assertTrue(key(KeyEvent.KEYCODE_BUTTON_A)); assertTrue(pad().isDown(PadState.A))
        assertTrue(key(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_UP)); assertFalse(pad().isDown(PadState.A))
        assertTrue(key(KeyEvent.KEYCODE_BUTTON_B)); assertTrue(pad().isDown(PadState.B))
        assertTrue(input.nativeMotion(device.id, InputDevice.SOURCE_JOYSTICK, MotionEvent.ACTION_MOVE,
            1L, 2L, floatArrayOf(.75f, -.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)))
        assertEquals(.75f, pad().leftX); assertEquals(-.5f, pad().leftY)
    }

    @Test fun menuChordReleasesGuestKeysAndFiresOnceUntilBothReleased() {
        assertTrue(key(KeyEvent.KEYCODE_BUTTON_SELECT)); assertTrue(pad().isDown(PadState.SELECT))
        assertTrue(key(KeyEvent.KEYCODE_BUTTON_START)); assertEquals(1, input.menuSequence)
        assertFalse(pad().isDown(PadState.SELECT)); assertFalse(pad().isDown(PadState.START))
        key(KeyEvent.KEYCODE_BUTTON_START, repeat = 1); assertEquals(1, input.menuSequence)
        key(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.ACTION_UP)
        key(KeyEvent.KEYCODE_BUTTON_SELECT); assertEquals(1, input.menuSequence)
        key(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.ACTION_UP)
        key(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.ACTION_UP)
        key(KeyEvent.KEYCODE_BUTTON_START); assertTrue(pad().isDown(PadState.START))
        key(KeyEvent.KEYCODE_BUTTON_SELECT); assertEquals(2, input.menuSequence)
    }

    @Test fun guideDoesNotAlsoReachSteamClient() {
        key(KeyEvent.KEYCODE_BUTTON_MODE); assertEquals(1, input.menuSequence)
        assertFalse(pad().isDown(PadState.GUIDE))
        key(KeyEvent.KEYCODE_BUTTON_MODE, repeat = 1); assertEquals(1, input.menuSequence)
        key(KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.ACTION_UP)
        key(KeyEvent.KEYCODE_BUTTON_MODE); assertEquals(2, input.menuSequence)
    }

    @Test fun focusLossPauseAndDisconnectReleaseHeldInput() {
        key(KeyEvent.KEYCODE_BUTTON_A); input.focus(false); assertFalse(pad().isDown(PadState.A))
        assertTrue(key(KeyEvent.KEYCODE_BACK)); assertFalse(pad().isDown(PadState.SELECT))
        key(KeyEvent.KEYCODE_BUTTON_MODE); assertEquals(0, input.menuSequence)
        input.focus(true); key(KeyEvent.KEYCODE_BUTTON_B); input.pause(); assertFalse(pad().isDown(PadState.B))
        key(KeyEvent.KEYCODE_BUTTON_A); assertFalse(pad().isDown(PadState.A))
        input.resume(); key(KeyEvent.KEYCODE_BUTTON_A); input.onInputDeviceRemoved(device.id)
        assertFalse(pad().isDown(PadState.A))
    }

    @Test fun virtualKeysAreRejected() {
        assertFalse(input.key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)))
        assertEquals(0, input.menuSequence)
    }

    @Test fun genericHeadsetAxesAreFilteredOnlyInAndroidXr() {
        val headset = InputDeviceBuilder.newBuilder().setId(92).setName("Headset input")
            .setSources(InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_KEYBOARD)
            .setKeyboardType(InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC)
            .addMotionRange(MotionEvent.AXIS_GENERIC_1, InputDevice.SOURCE_JOYSTICK, -1f, 1f, 0f, 0f, 0f).build()
        assertTrue(PadBridge.isFromController(headset))
        assertFalse(XrGamepadInput.isController(headset))
        assertTrue(XrGamepadInput.isController(device))
    }

    @Test fun differentControllersCannotCombineMenuKeys() {
        val chord = XrGamepadMenu()
        assertFalse(chord.key(1, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.ACTION_DOWN, 0).request)
        assertFalse(chord.key(2, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.ACTION_DOWN, 0).request)
        chord.remove(1)
        assertFalse(chord.key(1, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.ACTION_DOWN, 0).request)
        chord.clear()
        assertFalse(chord.key(1, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.ACTION_DOWN, 0).request)
    }
}
