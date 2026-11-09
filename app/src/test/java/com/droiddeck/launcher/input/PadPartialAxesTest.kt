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

    @Test fun onlyRxRyAxesProvideTheRightStick() {
        val device = device(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)
        motion(device, MotionEvent.AXIS_RX to 0.6f, MotionEvent.AXIS_RY to -0.6f)
        assertEquals(0.6f, pad().rightX)
        assertEquals(-0.6f, pad().rightY)
    }

    @Test fun rxRyStickWithoutZRzKeepsTriggersReleased() {
        val device = device(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)
        motion(device, MotionEvent.AXIS_RX to 0f, MotionEvent.AXIS_RY to 0f)
        assertEquals(0f, pad().leftTrigger)
        assertEquals(0f, pad().rightTrigger)
    }

    @Test fun rxRyStickReadsDedicatedTriggerAxes() {
        val device = device(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER)
        motion(device, MotionEvent.AXIS_RX to 0.6f, MotionEvent.AXIS_RY to 0f,
            MotionEvent.AXIS_LTRIGGER to 0.4f, MotionEvent.AXIS_RTRIGGER to 0f)
        assertEquals(0.6f, pad().rightX)
        assertEquals(0.4f, pad().leftTrigger)
        assertEquals(0f, pad().rightTrigger)
    }

    @Test fun leftTriggerFallsBackToBrakeWhenDedicatedAxisIsZero() {
        val device = device(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)
        motion(device, MotionEvent.AXIS_LTRIGGER to 0f, MotionEvent.AXIS_BRAKE to 0.7f)
        assertEquals(0.7f, pad().leftTrigger)
    }

    @Test fun rightTriggerFallsBackToGasWhenDedicatedAxisIsZero() {
        val device = device(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
        motion(device, MotionEvent.AXIS_RTRIGGER to 0f, MotionEvent.AXIS_GAS to 0.8f)
        assertEquals(0.8f, pad().rightTrigger)
    }

    @Test fun onlyZRzAxesProvideTheRightStick() {
        val device = device(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ)
        motion(device, MotionEvent.AXIS_Z to 0.6f, MotionEvent.AXIS_RZ to -0.6f)
        assertEquals(0.6f, pad().rightX)
        assertEquals(-0.6f, pad().rightY)
    }

    @Test fun bothPairsUseThePairAtRestNearCentreAsTheStick() {
        val device = device(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ)
        motion(device, MotionEvent.AXIS_RX to -1f, MotionEvent.AXIS_RY to -1f,
            MotionEvent.AXIS_Z to 0f, MotionEvent.AXIS_RZ to 0f)
        assertEquals(0f, pad().rightX)
        assertEquals(0f, pad().rightY)
        motion(device, MotionEvent.AXIS_RX to 0.2f, MotionEvent.AXIS_RY to -0.3f,
            MotionEvent.AXIS_Z to 0.7f, MotionEvent.AXIS_RZ to -0.8f)
        assertEquals(0.7f, pad().rightX)
        assertEquals(-0.8f, pad().rightY)
    }

    @Test fun bothPairsUseRxRyAsStickWhenZRzRestAtMinimum() {
        val device = device(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ)
        motion(device, MotionEvent.AXIS_RX to 0f, MotionEvent.AXIS_RY to 0f,
            MotionEvent.AXIS_Z to -1f, MotionEvent.AXIS_RZ to -1f)
        assertEquals(0f, pad().rightX)
        assertEquals(0f, pad().rightY)
        assertEquals(0f, pad().leftTrigger)
        assertEquals(0f, pad().rightTrigger)
        motion(device, MotionEvent.AXIS_RX to 0.6f, MotionEvent.AXIS_RY to -0.6f,
            MotionEvent.AXIS_Z to 1f, MotionEvent.AXIS_RZ to 1f)
        assertEquals(0.6f, pad().rightX)
        assertEquals(-0.6f, pad().rightY)
        assertEquals(1f, pad().leftTrigger)
        assertEquals(1f, pad().rightTrigger)
    }

    @Test fun nonWiredZeroToOneSignedZTriggersNormalizeFromTheirRange() {
        val device = device(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            zeroToOneAxes = setOf(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ))
        motion(device, MotionEvent.AXIS_RX to 0f, MotionEvent.AXIS_RY to 0f,
            MotionEvent.AXIS_Z to 0f, MotionEvent.AXIS_RZ to 0f)
        assertEquals(0f, pad().leftTrigger)
        assertEquals(0f, pad().rightTrigger)
        motion(device, MotionEvent.AXIS_RX to 0.6f, MotionEvent.AXIS_RY to -0.6f,
            MotionEvent.AXIS_Z to 1f, MotionEvent.AXIS_RZ to 1f)
        assertEquals(1f, pad().leftTrigger)
        assertEquals(1f, pad().rightTrigger)
    }

    @Test fun movingFirstEventDoesNotLockTheWrongStickPair() {
        val device = device(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ)
        motion(device, MotionEvent.AXIS_RX to 1f, MotionEvent.AXIS_RY to 0f,
            MotionEvent.AXIS_Z to 0f, MotionEvent.AXIS_RZ to 0f)
        assertEquals(0f, pad().rightX)
        assertEquals(0f, pad().rightY)
        motion(device, MotionEvent.AXIS_RX to 0f, MotionEvent.AXIS_RY to 0f,
            MotionEvent.AXIS_Z to -1f, MotionEvent.AXIS_RZ to -1f)
        motion(device, MotionEvent.AXIS_RX to 0.6f, MotionEvent.AXIS_RY to -0.6f,
            MotionEvent.AXIS_Z to -1f, MotionEvent.AXIS_RZ to -1f)
        assertEquals(0.6f, pad().rightX)
        assertEquals(-0.6f, pad().rightY)
    }

    @Test fun dedicatedRightTriggerWinsOverSignedRightTrigger() {
        val device = device(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ, MotionEvent.AXIS_RTRIGGER)
        motion(device, MotionEvent.AXIS_RX to 0f, MotionEvent.AXIS_RY to 0f,
            MotionEvent.AXIS_Z to -1f, MotionEvent.AXIS_RZ to -1f)
        motion(device, MotionEvent.AXIS_RX to 0.6f, MotionEvent.AXIS_RY to -0.6f,
            MotionEvent.AXIS_Z to -1f, MotionEvent.AXIS_RZ to -1f,
            MotionEvent.AXIS_RTRIGGER to 0.7f)
        assertEquals(0f, pad().leftTrigger)
        assertEquals(0.7f, pad().rightTrigger)
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

    @Test fun analogTriggerAxesWinOverTheirKeys() {
        val device = device(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER)
        motion(device, MotionEvent.AXIS_LTRIGGER to 0.3f)
        key(device, KeyEvent.KEYCODE_BUTTON_L2)
        assertEquals(0.3f, pad().leftTrigger)
        key(device, KeyEvent.KEYCODE_BUTTON_L2, repeat = 1)
        assertEquals(0.3f, pad().leftTrigger)
        motion(device, MotionEvent.AXIS_LTRIGGER to 0.6f)
        key(device, KeyEvent.KEYCODE_BUTTON_L2, repeat = 2)
        assertEquals(0.6f, pad().leftTrigger)
        motion(device, MotionEvent.AXIS_LTRIGGER to 0f)
        key(device, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.ACTION_UP)
        assertEquals(0f, pad().leftTrigger)
    }

    @Test fun bothAnalogTriggersHeldStayIndependent() {
        val device = device(MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS)
        motion(device, MotionEvent.AXIS_BRAKE to 0.4f)
        key(device, KeyEvent.KEYCODE_BUTTON_L2)
        motion(device, MotionEvent.AXIS_BRAKE to 0.4f, MotionEvent.AXIS_GAS to 0.7f)
        key(device, KeyEvent.KEYCODE_BUTTON_R2)
        key(device, KeyEvent.KEYCODE_BUTTON_R2, repeat = 1)
        assertEquals(0.4f, pad().leftTrigger)
        assertEquals(0.7f, pad().rightTrigger)
        motion(device, MotionEvent.AXIS_BRAKE to 0.9f, MotionEvent.AXIS_GAS to 0.2f)
        key(device, KeyEvent.KEYCODE_BUTTON_R2, repeat = 2)
        assertEquals(0.9f, pad().leftTrigger)
        assertEquals(0.2f, pad().rightTrigger)
    }

    @Test fun wiredXboxTriggerKeysDoNotOverrideTheirAxes() {
        val device = device(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, wired = true)
        motion(device, MotionEvent.AXIS_Z to 0f, MotionEvent.AXIS_RZ to -1f)
        key(device, KeyEvent.KEYCODE_BUTTON_L2)
        key(device, KeyEvent.KEYCODE_BUTTON_R2)
        assertEquals(0.5f, pad().leftTrigger)
        assertEquals(0f, pad().rightTrigger)
    }

    @Test fun digitalTriggerKeysStillPressAndRelease() {
        val device = device()
        key(device, KeyEvent.KEYCODE_BUTTON_L2)
        key(device, KeyEvent.KEYCODE_BUTTON_R2)
        key(device, KeyEvent.KEYCODE_BUTTON_L2, repeat = 1)
        assertEquals(1f, pad().leftTrigger)
        assertEquals(1f, pad().rightTrigger)
        key(device, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.ACTION_UP)
        assertEquals(0f, pad().leftTrigger)
        assertEquals(1f, pad().rightTrigger)
        key(device, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.ACTION_UP)
        assertEquals(0f, pad().rightTrigger)
    }

    @Test fun keyTriggerDecision() {
        assertEquals(1f, PadBridge.keyTrigger(0f, true, 0, false))
        assertEquals(0f, PadBridge.keyTrigger(1f, false, 0, false))
        assertEquals(0.25f, PadBridge.keyTrigger(0.25f, true, 1, false))
        assertEquals(0.25f, PadBridge.keyTrigger(0.25f, true, 0, true))
        assertEquals(0.25f, PadBridge.keyTrigger(0.25f, false, 0, true))
    }

    private fun pad(): PadState = requireNotNull(JoyConPadTest.RecordingWriter.pads[0])

    private fun key(device: InputDevice, code: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0) {
        assertTrue(bridge.onKeyEvent(KeyEvent(0L, 0L, action, code, repeat, 0,
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

    private fun device(vararg axes: Int, wired: Boolean = false, zeroToOneAxes: Set<Int> = emptySet()): InputDevice {
        val builder = InputDeviceBuilder.newBuilder().setId(60).setName("partial pad")
            .setDescriptor("partial-pad").setExternal(true)
            .setSources(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)
            .setKeyboardType(InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC)
            .setVendorId(if (wired) 0x20d6 else 0).setProductId(if (wired) 0x2063 else 0)
        for (axis in axes.toList() + MotionEvent.AXIS_X) {
            val min = if (axis in zeroToOneAxes) 0f else -1f
            builder.addMotionRange(axis, InputDevice.SOURCE_JOYSTICK, min, 1f, 0f, 0f, 0f)
        }
        return builder.build().also {
            shadowOf(RuntimeEnvironment.getApplication().getSystemService(InputManager::class.java)).addInputDevice(it)
        }
    }
}
