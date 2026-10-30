package com.droiddeck.launcher.input

import android.hardware.input.InputManager
import android.os.Looper
import android.util.SparseArray
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
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
import org.robolectric.shadows.InputDeviceBuilder
import org.robolectric.util.ReflectionHelpers

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

    @Test fun changingDeadZonePreservesOfficialShortcutsAndControllerSettings() {
        val button = PhysicalShortcuts.Button("test-pad", "Test pad", KeyEvent.KEYCODE_BUTTON_MODE, 0)
        val binding = PhysicalShortcuts.Binding(button, PhysicalShortcuts.Action.QAM)
        PhysicalShortcuts.assign(context, button, binding.action)
        ControllerPrefs.setTarget(context, "a", "b")
        ControllerPrefs.setRumble(context, false)
        for (percent in ControllerPrefs.deadZones) {
            ControllerPrefs.setDeadZone(context, percent)
            val settings = ControllerPrefs.read(context)
            assertEquals(percent, settings.deadZone)
            assertEquals(listOf(binding), PhysicalShortcuts.read(context))
            assertEquals("b", settings.mapping["a"])
            assertFalse(settings.rumble)
        }
    }

    @Test fun assigningAnOfficialShortcutPreservesDeadZone() {
        ControllerPrefs.setDeadZone(context, 25)
        PhysicalShortcuts.assign(context,
            PhysicalShortcuts.Button("test-pad", "Test pad", KeyEvent.KEYCODE_BUTTON_MODE, 0),
            PhysicalShortcuts.Action.STEAM)
        assertEquals(25, ControllerPrefs.read(context).deadZone)
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
        assertEquals(0.2f, pad().leftX)
        assertEquals(0.4f, pad().rightX)
    }

    @Test fun changingPreferenceReprojectsAllSticksWithoutAnotherMotionEvent() {
        motion(device(), MotionEvent.AXIS_X to 0.2f, MotionEvent.AXIS_Y to 0.02f,
            MotionEvent.AXIS_Z to 0.03f, MotionEvent.AXIS_RZ to -0.04f)
        bridge.setStickDeadZone(25)
        assertEquals(0f, pad().leftX)
        assertEquals(0f, pad().rightY)
        bridge.setStickDeadZone(0)
        assertEquals(0.2f, pad().leftX)
        assertEquals(0.02f, pad().leftY)
        assertEquals(0.03f, pad().rightX)
        assertEquals(-0.04f, pad().rightY)
    }

    @Test fun preferenceChangesReprojectEveryPlayer() {
        motion(device(id = 61), MotionEvent.AXIS_X to 0.2f)
        motion(device(id = 62), MotionEvent.AXIS_Z to -0.2f)
        bridge.setStickDeadZone(25)
        assertEquals(0f, pad().leftX)
        assertEquals(0f, requireNotNull(JoyConPadTest.RecordingWriter.pads[1]).rightX)
        bridge.setStickDeadZone(0)
        assertEquals(0.2f, pad().leftX)
        assertEquals(-0.2f, requireNotNull(JoyConPadTest.RecordingWriter.pads[1]).rightX)
    }

    @Test fun preferenceChangesReprojectBothHalvesOfAJoyConPair() {
        val left = device(joyCon = true, id = 61)
        val right = device(joyCon = true, id = 62, rightJoyCon = true)
        motion(left)
        motion(right)
        motion(left, MotionEvent.AXIS_X to 0.2f)
        motion(right, MotionEvent.AXIS_RX to -0.2f)
        bridge.setStickDeadZone(25)
        assertEquals(0f, pad().leftX)
        assertEquals(0f, pad().rightX)
        bridge.setStickDeadZone(0)
        assertEquals(0.2f, pad().leftX)
        assertEquals(-0.2f, pad().rightX)
    }

    @Test fun preferenceChangesDoNotReplaceTouchStickInput() {
        motion(device(), MotionEvent.AXIS_X to 0.2f, MotionEvent.AXIS_Z to -0.2f)
        bridge.applyTouch { it.leftX = 0.8f }
        bridge.setStickDeadZone(25)
        assertEquals(0.8f, pad().leftX)
        assertEquals(0f, pad().rightX)
        bridge.setStickDeadZone(0)
        assertEquals(0.8f, pad().leftX)
        assertEquals(-0.2f, pad().rightX)
    }

    @Test fun explicitZeroTouchWriteDiscardsFilteredPhysicalLeftStick() {
        motion(device(), MotionEvent.AXIS_X to 0.05f, MotionEvent.AXIS_Z to -0.05f)
        bridge.applyTouch(PadBridge.LEFT_STICK) { it.leftX = 0f; it.leftY = 0f }
        bridge.setStickDeadZone(0)
        assertEquals(0f, pad().leftX)
        assertEquals(-0.05f, pad().rightX)
    }

    @Test fun explicitZeroTouchWriteDiscardsFilteredPhysicalRightStick() {
        motion(device(), MotionEvent.AXIS_X to 0.05f, MotionEvent.AXIS_Z to -0.05f)
        bridge.applyTouch(PadBridge.RIGHT_STICK) { it.rightX = 0f; it.rightY = 0f }
        bridge.setStickDeadZone(0)
        assertEquals(0.05f, pad().leftX)
        assertEquals(0f, pad().rightX)
    }

    @Test fun routedTouchStickWritesKeepSeparatePhysicalInput() {
        motion(device(), MotionEvent.AXIS_X to 0.05f)
        bridge.setTouchRoute(object : PadBridge.TouchRoute {
            override fun active() = true
            override fun accept(state: PadState) { assertEquals(0f, state.leftX) }
        })
        bridge.applyTouch(PadBridge.LEFT_STICK) { it.leftX = 0f; it.leftY = 0f }
        bridge.setStickDeadZone(0)
        assertEquals(0.05f, pad().leftX)
    }

    @Test fun touchButtonOnlyWritesKeepFilteredPhysicalSticks() {
        motion(device(), MotionEvent.AXIS_X to 0.05f)
        bridge.applyTouch { it.press(PadState.A, true) }
        bridge.setStickDeadZone(0)
        assertEquals(0.05f, pad().leftX)
        assertTrue(pad().isDown(PadState.A))
    }

    @Test fun preferenceChangesDoNotReplaceExternalStickInput() {
        motion(device(), MotionEvent.AXIS_X to 0.2f, MotionEvent.AXIS_Z to -0.2f)
        bridge.applyExternal({ it.rightX = 0.8f }, true)
        bridge.setStickDeadZone(25)
        assertEquals(0f, pad().leftX)
        assertEquals(0.8f, pad().rightX)
    }

    @Test fun buttonAndTriggerMutationsKeepPhysicalSticksReprojectable() {
        motion(device(), MotionEvent.AXIS_X to 0.2f)
        bridge.applyExternal({ it.leftTrigger = 0.7f; it.press(PadState.X, true) }, true)
        bridge.setStickDeadZone(25)
        assertEquals(0f, pad().leftX)
        assertEquals(0.7f, pad().leftTrigger)
        assertTrue(pad().isDown(PadState.X))
        bridge.setStickDeadZone(0)
        assertEquals(0.2f, pad().leftX)
    }

    @Test fun releaseAllDiscardsEvenPreviouslyFilteredRawMotion() {
        motion(device(), MotionEvent.AXIS_X to 0.05f)
        bridge.releaseAll()
        bridge.setStickDeadZone(0)
        assertEquals(0f, pad().leftX)
    }

    @Test fun aDisconnectedControllerDoesNotReturnOnPreferenceChange() {
        val device = device()
        motion(device, MotionEvent.AXIS_X to 0.2f)
        bridge.onDeviceRemoved(device.id)
        bridge.setStickDeadZone(0)
        assertEquals(0f, pad().leftX)
    }

    @Test fun pairingDoesNotRestoreAnOldJoyConPosition() {
        val left = device(joyCon = true, id = 61)
        val right = device(joyCon = true, id = 62, rightJoyCon = true)
        motion(left, MotionEvent.AXIS_X to 0.2f)
        motion(right)
        bridge.setStickDeadZone(0)
        assertEquals(0f, pad().leftX)
    }

    @Test fun aControllerRemovedWhileInactiveDoesNotReturnOnPreferenceChange() {
        val device = device()
        motion(device, MotionEvent.AXIS_X to 0.05f)
        val manager = ReflectionHelpers.callStaticMethod<Any>(Class.forName("android.hardware.input.InputManagerGlobal"), "getInstance")
        ReflectionHelpers.getField<SparseArray<InputDevice>>(manager, "mInputDevices").remove(device.id)
        assertNull(InputDevice.getDevice(device.id))
        bridge.setStickDeadZone(0)
        assertEquals(0f, pad().leftX)
    }

    @Test fun excludedControllerInputIsNotRestoredOnPreferenceChange() {
        val device = device()
        motion(device, MotionEvent.AXIS_X to 0.2f)
        bridge.setExcluded { it == device.id }
        bridge.setStickDeadZone(0)
        assertEquals(0f, pad().leftX)
    }

    @Test fun preferenceChangesDoNotGenerateChordInput() {
        motion(device(), MotionEvent.AXIS_X to 0.2f)
        var notifications = 0
        bridge.setOnChordInput { notifications++ }
        bridge.setStickDeadZone(25)
        bridge.setStickDeadZone(0)
        assertEquals(0, notifications)
    }

    @Test fun aStoppedBridgeDoesNotRepublishOldInput() {
        motion(device(), MotionEvent.AXIS_X to 0.2f)
        bridge.stop()
        JoyConPadTest.RecordingWriter.pads.clear()
        bridge.setStickDeadZone(0)
        assertTrue(JoyConPadTest.RecordingWriter.pads.isEmpty())
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

    @Test fun zeroDeadZoneDriftDoesNotConsumeBackTap() {
        val (taps, presses) = backTapWithMotion(device(), MotionEvent.AXIS_X to 0.01f)
        assertEquals(0.01f, pad().leftX)
        assertEquals(listOf(false), taps)
        assertTrue(presses.isEmpty())
    }

    @Test fun zeroDeadZoneJoyConDriftDoesNotConsumeBackTap() {
        val (taps, presses) = backTapWithMotion(device(joyCon = true), MotionEvent.AXIS_X to 0.01f)
        assertEquals(0.01f, pad().leftX)
        assertEquals(listOf(false), taps)
        assertTrue(presses.isEmpty())
    }

    @Test fun diagonalInsideTheLegacyChordGuardDoesNotConsumeBackTap() {
        val (taps, presses) = backTapWithMotion(device(), MotionEvent.AXIS_X to 0.09f, MotionEvent.AXIS_Y to 0.09f)
        assertEquals(0.09f, pad().leftX)
        assertEquals(0.09f, pad().leftY)
        assertEquals(listOf(false), taps)
        assertTrue(presses.isEmpty())
    }

    @Test fun intentionalMotionOnEitherStickStillMakesABackChord() {
        val device = device()
        for (axis in intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Z)) {
            val (taps, presses) = backTapWithMotion(device, axis to 0.5f)
            assertTrue(taps.isEmpty())
            assertEquals(listOf(true, false), presses)
        }
    }

    @Test fun higherGameDeadZoneDoesNotDisableExistingBackChords() {
        val (taps, presses) = backTapWithMotion(device(), MotionEvent.AXIS_X to 0.2f, deadZone = 25)
        assertEquals(0f, pad().leftX)
        assertTrue(taps.isEmpty())
        assertEquals(listOf(true, false), presses)
    }

    @Test fun higherGameDeadZoneDoesNotDisableExistingJoyConBackChords() {
        val (taps, presses) = backTapWithMotion(device(joyCon = true), MotionEvent.AXIS_X to 0.2f, deadZone = 25)
        assertEquals(0f, pad().leftX)
        assertTrue(taps.isEmpty())
        assertEquals(listOf(true, false), presses)
    }

    private fun backTapWithMotion(device: InputDevice, vararg axes: Pair<Int, Float>, deadZone: Int = 0): Pair<List<Boolean>, List<Boolean>> {
        val taps = mutableListOf<Boolean>()
        val presses = mutableListOf<Boolean>()
        val input = BackQamInput(presses::add, taps::add)
        bridge.setStickDeadZone(deadZone)
        bridge.setOnChordInput(input::onChordInput)
        assertTrue(input.onKeyEvent(KeyEvent(0, 100, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK,
            0, 0, device.id, 0), true))
        motion(device, *axes)
        assertTrue(input.onKeyEvent(KeyEvent(0, 100, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK,
            0, 0, device.id, 0), true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        return taps to presses
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

    private fun device(wired: Boolean = false, joyCon: Boolean = false, id: Int = 61, rightJoyCon: Boolean = false): InputDevice {
        val builder = InputDeviceBuilder.newBuilder().setId(id)
            .setName(if (joyCon) if (rightJoyCon) "Joy-Con (R)" else "Joy-Con (L)" else "dead-zone pad")
            .setDescriptor("dead-zone-pad-$id").setExternal(true)
            .setSources(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)
            .setKeyboardType(InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC)
            .setVendorId(if (joyCon) 0x057e else if (wired) 0x20d6 else 0)
            .setProductId(if (joyCon) if (rightJoyCon) 0x2007 else 0x2006 else if (wired) 0x2063 else 0)
        for (axis in intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)) {
            builder.addMotionRange(axis, InputDevice.SOURCE_JOYSTICK, -1f, 1f, 0f, 0f, 0f)
        }
        return builder.build().also {
            shadowOf(context.getSystemService(InputManager::class.java)).addInputDevice(it)
        }
    }
}
