package com.droiddeck.launcher.input

import android.hardware.input.InputManager
import android.view.InputDevice
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
class PadPartialAxesTest {
    private val directory = Files.createTempDirectory("partial-pad").toFile()
    private lateinit var bridge: PadBridge

    @Before fun start() {
        JoyConPadTest.RecordingWriter.pads.clear()
        bridge = PadBridge(directory)
        assertTrue(bridge.start())
    }

    @After fun stop() {
        bridge.stop()
        directory.deleteRecursively()
    }

    @Test fun leftTriggerAxisPreservesRightTriggerKey() = trigger(MotionEvent.AXIS_LTRIGGER, false)
    @Test fun brakeAxisPreservesRightTriggerKey() = trigger(MotionEvent.AXIS_BRAKE, false)
    @Test fun rightTriggerAxisPreservesLeftTriggerKey() = trigger(MotionEvent.AXIS_RTRIGGER, true)
    @Test fun gasAxisPreservesLeftTriggerKey() = trigger(MotionEvent.AXIS_GAS, true)

    private fun trigger(axis: Int, leftKey: Boolean) {
        val device = device(axis)
        key(device, if (leftKey) KeyEvent.KEYCODE_BUTTON_L2 else KeyEvent.KEYCODE_BUTTON_R2)
        motion(device, axis to 0.75f)
        assertEquals(1f, if (leftKey) pad().leftTrigger else pad().rightTrigger)
        assertEquals(0.75f, if (leftKey) pad().rightTrigger else pad().leftTrigger)
        motion(device, axis to 0f)
        assertEquals(1f, if (leftKey) pad().leftTrigger else pad().rightTrigger)
        assertEquals(0f, if (leftKey) pad().rightTrigger else pad().leftTrigger)
    }

    @Test fun horizontalHatPreservesVerticalKeys() {
        val device = device(MotionEvent.AXIS_HAT_X)
        key(device, KeyEvent.KEYCODE_DPAD_UP)
        motion(device, MotionEvent.AXIS_HAT_X to 1f)
        assertTrue(pad().up)
        assertTrue(pad().right)
        motion(device, MotionEvent.AXIS_HAT_X to 0f)
        assertTrue(pad().up)
        assertFalse(pad().right)
    }

    @Test fun verticalHatPreservesHorizontalKeys() {
        val device = device(MotionEvent.AXIS_HAT_Y)
        key(device, KeyEvent.KEYCODE_DPAD_LEFT)
        motion(device, MotionEvent.AXIS_HAT_Y to -1f)
        assertTrue(pad().left)
        assertTrue(pad().up)
        motion(device, MotionEvent.AXIS_HAT_Y to 0f)
        assertTrue(pad().left)
        assertFalse(pad().up)
    }

    @Test fun wiredXboxTriggersStillUseTheirSignedZAxes() {
        val device = device(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, wired = true)
        motion(device, MotionEvent.AXIS_Z to -1f, MotionEvent.AXIS_RZ to 1f,
            MotionEvent.AXIS_RX to 0.6f, MotionEvent.AXIS_RY to -0.6f)
        assertEquals(0f, pad().leftTrigger)
        assertEquals(1f, pad().rightTrigger)
        assertEquals(0.6f, pad().rightX)
        assertEquals(-0.6f, pad().rightY)
    }

    @Test fun sticksWithoutTriggerOrHatAxesPreserveAllDigitalControls() {
        val device = device()
        key(device, KeyEvent.KEYCODE_BUTTON_L2)
        key(device, KeyEvent.KEYCODE_BUTTON_R2)
        key(device, KeyEvent.KEYCODE_DPAD_DOWN)
        motion(device)
        assertEquals(1f, pad().leftTrigger)
        assertEquals(1f, pad().rightTrigger)
        assertTrue(pad().down)
    }

    private fun pad(): PadState = requireNotNull(JoyConPadTest.RecordingWriter.pads[0])

    private fun key(device: InputDevice, code: Int) {
        assertTrue(bridge.onKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, code, 0, 0,
            device.id, 0, 0, InputDevice.SOURCE_GAMEPAD)))
    }

    private fun motion(device: InputDevice, vararg values: Pair<Int, Float>) {
        val coords = MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_X, 0.5f)
            for ((axis, value) in values) setAxisValue(axis, value)
        }
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0 }), arrayOf(coords),
            0, 0, 1f, 1f, device.id, 0, InputDevice.SOURCE_JOYSTICK, 0)
        try { assertTrue(bridge.onMotionEvent(event)) } finally { event.recycle() }
    }

    private fun device(vararg axes: Int, wired: Boolean = false): InputDevice {
        val builder = InputDeviceBuilder.newBuilder().setId(60).setName("partial pad")
            .setDescriptor("partial-pad").setExternal(true)
            .setSources(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)
            .setKeyboardType(InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC)
            .setVendorId(if (wired) 0x20d6 else 0).setProductId(if (wired) 0x2063 else 0)
        for (axis in axes.toList() + MotionEvent.AXIS_X) {
            builder.addMotionRange(axis, InputDevice.SOURCE_JOYSTICK, -1f, 1f, 0f, 0f, 0f)
        }
        return builder.build().also {
            shadowOf(RuntimeEnvironment.getApplication().getSystemService(InputManager::class.java)).addInputDevice(it)
        }
    }
}
