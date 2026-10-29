package com.droiddeck.launcher.input

import android.hardware.input.InputManager
import android.view.InputDevice
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
class StickDeadZoneTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val directory = Files.createTempDirectory("stick-dead-zone").toFile()
    private lateinit var bridge: PadBridge

    @Before fun start() {
        ControllerPrefs.prefs(context).edit().clear().commit()
        JoyConPadTest.RecordingWriter.pads.clear()
        bridge = PadBridge(directory)
        assertTrue(bridge.start())
    }

    @After fun stop() {
        bridge.stop()
        ControllerPrefs.prefs(context).edit().clear().commit()
        directory.deleteRecursively()
    }

    @Test fun preferencesDefaultToTwelveAndPersistZero() {
        assertEquals(12, ControllerPrefs.read(context).deadZone)
        assertEquals(listOf(0, 5, 8, 12, 15, 20, 25), ControllerPrefs.deadZones)
        ControllerPrefs.setDeadZone(context, 0)
        assertEquals(0, ControllerPrefs.read(context).deadZone)
    }

    @Test fun invalidPreferencesFallBackAndResetRestoresDefault() {
        ControllerPrefs.setDeadZone(context, 99)
        assertEquals(12, ControllerPrefs.deadZone(context))
        ControllerPrefs.setDeadZone(context, 25)
        ControllerPrefs.resetAll(context)
        assertEquals(12, ControllerPrefs.deadZone(context))
    }

    @Test fun defaultSuppressesBothAxesInsideTheCircle() {
        motion(device(), MotionEvent.AXIS_X to 0.05f, MotionEvent.AXIS_Y to -0.06f)
        assertEquals(0f, pad().leftX)
        assertEquals(0f, pad().leftY)
    }

    @Test fun diagonalOutsideTheCircleIsNotLostToAnAxisCross() {
        motion(device(), MotionEvent.AXIS_X to 0.09f, MotionEvent.AXIS_Y to -0.09f)
        assertEquals(0.09f, pad().leftX)
        assertEquals(-0.09f, pad().leftY)
    }

    @Test fun smallCrossAxisMotionSurvivesWhenTheStickIsHeldOver() {
        motion(device(), MotionEvent.AXIS_X to 0.6f, MotionEvent.AXIS_Y to 0.02f)
        assertEquals(0.6f, pad().leftX)
        assertEquals(0.02f, pad().leftY)
    }

    @Test fun zeroDisablesFilteringForBothSticks() {
        bridge.setStickDeadZone(0)
        motion(device(), MotionEvent.AXIS_X to 0.01f, MotionEvent.AXIS_Y to -0.02f,
            MotionEvent.AXIS_Z to 0.03f, MotionEvent.AXIS_RZ to -0.04f)
        assertEquals(0.01f, pad().leftX)
        assertEquals(-0.02f, pad().leftY)
        assertEquals(0.03f, pad().rightX)
        assertEquals(-0.04f, pad().rightY)
    }

    @Test fun changingTheSettingAffectsTheExistingBridge() {
        val device = device()
        bridge.setStickDeadZone(25)
        motion(device, MotionEvent.AXIS_X to 0.2f, MotionEvent.AXIS_Z to 0.4f)
        assertEquals(0f, pad().leftX)
        assertEquals(0.4f, pad().rightX)
        bridge.setStickDeadZone(0)
        motion(device, MotionEvent.AXIS_X to 0.2f)
        assertEquals(0.2f, pad().leftX)
    }

    @Test fun theBoundaryPassesThroughWithoutRescaling() {
        assertFalse(PadBridge.inStickDeadZone(0.12f, 0f, 0.12f))
        motion(device(), MotionEvent.AXIS_X to 0.12f)
        assertEquals(0.12f, pad().leftX)
    }

    @Test fun wiredXboxKeepsItsRightStickAndSignedTriggerAxes() {
        bridge.setStickDeadZone(0)
        motion(device(wired = true), MotionEvent.AXIS_RX to 0.02f, MotionEvent.AXIS_RY to -0.03f,
            MotionEvent.AXIS_Z to -1f, MotionEvent.AXIS_RZ to 1f)
        assertEquals(0.02f, pad().rightX)
        assertEquals(-0.03f, pad().rightY)
        assertEquals(0f, pad().leftTrigger)
        assertEquals(1f, pad().rightTrigger)
    }

    @Test fun joyConUsesTheSelectedDeadZone() {
        bridge.setStickDeadZone(0)
        motion(device(joyCon = true), MotionEvent.AXIS_X to 0.01f, MotionEvent.AXIS_Y to -0.02f)
        assertEquals(0.01f, pad().leftX)
        assertEquals(-0.02f, pad().leftY)
    }

    @Test fun stickSettingDoesNotChangeMouseAxisHelpers() {
        bridge.setStickDeadZone(0)
        val event = event(device(), MotionEvent.AXIS_Z to 0.02f, MotionEvent.AXIS_RZ to 0.6f)
        try {
            assertEquals(0f, PadBridge.rightX(event, false))
            assertEquals(0.6f, PadBridge.rightY(event, false))
        } finally { event.recycle() }
    }

    private fun pad() = requireNotNull(JoyConPadTest.RecordingWriter.pads[0])

    private fun motion(device: InputDevice, vararg axes: Pair<Int, Float>) {
        val event = event(device, *axes)
        try { assertTrue(bridge.onMotionEvent(event)) } finally { event.recycle() }
    }

    private fun event(device: InputDevice, vararg axes: Pair<Int, Float>): MotionEvent {
        val coords = MotionEvent.PointerCoords().apply { for ((axis, value) in axes) setAxisValue(axis, value) }
        return MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0 }), arrayOf(coords),
            0, 0, 1f, 1f, device.id, 0, InputDevice.SOURCE_JOYSTICK, 0)
    }

    private fun device(wired: Boolean = false, joyCon: Boolean = false): InputDevice {
        val builder = InputDeviceBuilder.newBuilder().setId(61).setName(if (joyCon) "Joy-Con (L)" else "dead-zone pad")
            .setDescriptor("dead-zone-pad").setExternal(true)
            .setSources(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)
            .setKeyboardType(InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC)
            .setVendorId(if (joyCon) 0x057e else if (wired) 0x20d6 else 0)
            .setProductId(if (joyCon) 0x2006 else if (wired) 0x2063 else 0)
        for (axis in intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)) {
            builder.addMotionRange(axis, InputDevice.SOURCE_JOYSTICK, -1f, 1f, 0f, 0f, 0f)
        }
        return builder.build().also {
            shadowOf(context.getSystemService(InputManager::class.java)).addInputDevice(it)
        }
    }
}
