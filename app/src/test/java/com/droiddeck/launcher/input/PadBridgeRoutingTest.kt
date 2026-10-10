package com.droiddeck.launcher.input

import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.InputDeviceBuilder
import org.robolectric.shadows.ShadowKeyEvent
import org.robolectric.shadows.ShadowMotionEvent

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [PadBridgeRoutingTest.RegisteredKeyEvent::class,
    PadBridgeRoutingTest.RegisteredMotionEvent::class])
class PadBridgeRoutingTest {
    @Test fun aCompositeKeyboardDoesNotCountAsAConnectedPad() {
        val keyboard = device(COMPOSITE, InputDevice.KEYBOARD_TYPE_ALPHABETIC)
        assertFalse(PadBridge.isFromController(keyboard))
        assertFalse(PadBridge.anyControllerConnected())
        assertFalse(PadBridge.isFromController(device(InputDevice.SOURCE_KEYBOARD, InputDevice.KEYBOARD_TYPE_ALPHABETIC)))
    }

    @Test fun mouseAxesDoNotMakeACompositeKeyboardAJoystick() {
        val keyboard = device(COMPOSITE, InputDevice.KEYBOARD_TYPE_ALPHABETIC, rangeSource = InputDevice.SOURCE_MOUSE)
        assertFalse(PadBridge.isFromController(keyboard))
        assertFalse(PadBridge.anyControllerConnected())
    }

    @Test fun gamepadsKeepTheirKeyboardCapabilities() {
        for (type in listOf(InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC, InputDevice.KEYBOARD_TYPE_ALPHABETIC)) {
            assertTrue(PadBridge.isFromController(device(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_KEYBOARD, type)))
        }
        assertTrue(PadBridge.anyControllerConnected())
    }

    @Test fun aJoystickWithoutKeyboardCapabilitiesRemainsAController() {
        assertTrue(PadBridge.isFromController(device(InputDevice.SOURCE_JOYSTICK, InputDevice.KEYBOARD_TYPE_NONE)))
        assertTrue(PadBridge.isFromController(device(COMPOSITE, InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC)))
    }

    @Test fun joystickRangesDistinguishAHybridFromIncidentalSourceBits() {
        for (axis in listOf(MotionEvent.AXIS_X, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)) {
            val hybrid = device(COMPOSITE, InputDevice.KEYBOARD_TYPE_ALPHABETIC,
                rangeSource = InputDevice.SOURCE_JOYSTICK, axis = axis)
            assertTrue(PadBridge.isFromController(hybrid))
        }
    }

    @Test fun physicalControllerButtonsDistinguishAHybridWithoutAxes() {
        val hybrid = device(COMPOSITE, InputDevice.KEYBOARD_TYPE_ALPHABETIC,
            keys = intArrayOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_BUTTON_A))
        assertTrue(hybrid.hasKeys(KeyEvent.KEYCODE_BUTTON_A)[0])
        assertTrue(PadBridge.isFromController(hybrid))
    }

    @Test fun absentAndVirtualDevicesDoNotCountAsControllers() {
        assertFalse(PadBridge.isFromController(null))
        val virtual = device(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK,
            InputDevice.KEYBOARD_TYPE_NONE, id = -1)
        assertTrue(virtual.isVirtual)
        assertFalse(PadBridge.isFromController(virtual))
    }

    @Test fun keyAndMotionOwnershipDoesNotFollowEverySourceOnTheDevice() {
        val keyboard = device(InputDevice.SOURCE_KEYBOARD, InputDevice.KEYBOARD_TYPE_ALPHABETIC)
        val composite = device(COMPOSITE, InputDevice.KEYBOARD_TYPE_ALPHABETIC)
        val hybrid = device(COMPOSITE, InputDevice.KEYBOARD_TYPE_ALPHABETIC, rangeSource = InputDevice.SOURCE_JOYSTICK)
        val gamepad = device(COMPOSITE or InputDevice.SOURCE_GAMEPAD, InputDevice.KEYBOARD_TYPE_ALPHABETIC)
        val joystick = device(InputDevice.SOURCE_JOYSTICK, InputDevice.KEYBOARD_TYPE_NONE)
        val virtual = device(InputDevice.SOURCE_GAMEPAD, InputDevice.KEYBOARD_TYPE_NONE, id = -1)
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            for (device in listOf(keyboard, composite, hybrid, gamepad, joystick)) {
                assertFalse(PadBridge.isControllerKeyEvent(key(device, KeyEvent.KEYCODE_A, action)))
                assertFalse(PadBridge.isControllerKeyEvent(key(device, KeyEvent.KEYCODE_A, action, InputDevice.SOURCE_GAMEPAD)))
                assertFalse(PadBridge.isControllerKeyEvent(key(device, KeyEvent.KEYCODE_VOLUME_UP, action)))
            }
            for (code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_HOME)) {
                for (device in listOf(keyboard, composite, hybrid)) {
                    assertFalse(PadBridge.isControllerKeyEvent(key(device, code, action)))
                    assertFalse(PadBridge.isControllerKeyEvent(key(device, code, action, InputDevice.SOURCE_DPAD)))
                }
                assertTrue(PadBridge.isControllerKeyEvent(key(gamepad, code, action)))
                assertTrue(PadBridge.isControllerKeyEvent(key(joystick, code, action)))
                assertTrue(PadBridge.isControllerKeyEvent(key(hybrid, code, action, InputDevice.SOURCE_JOYSTICK)))
            }
            for (code in listOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_MODE)) {
                assertFalse(PadBridge.isControllerKeyEvent(key(keyboard, code, action)))
                assertFalse(PadBridge.isControllerKeyEvent(key(virtual, code, action)))
                for (device in listOf(composite, hybrid, gamepad, joystick)) {
                    assertTrue(PadBridge.isControllerKeyEvent(key(device, code, action)))
                }
            }
        }
        assertNull(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 0, 0, 999, 0).device)
        assertFalse(PadBridge.isControllerKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 0, 0, 999, 0)))
        for (device in listOf(keyboard, composite, hybrid, gamepad, joystick, virtual)) {
            val motion = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 1,
                arrayOf(MotionEvent.PointerProperties().apply { id = 0 }),
                arrayOf(MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_X, 0.75f) }),
                0, 0, 1f, 1f, device.id, 0, InputDevice.SOURCE_JOYSTICK, 0)
            try {
                assertSame(device, motion.device)
                assertEquals(device in listOf(composite, hybrid, gamepad, joystick), PadBridge.isControllerMotionEvent(motion))
                motion.source = InputDevice.SOURCE_MOUSE
                assertFalse(PadBridge.isControllerMotionEvent(motion))
            } finally { motion.recycle() }
        }
    }

    private fun key(device: InputDevice, code: Int, action: Int, source: Int = InputDevice.SOURCE_KEYBOARD): KeyEvent {
        val event = KeyEvent(0L, 0L, action, code, 0, 0, device.id, 0, 0, source)
        assertSame(device, event.device)
        return event
    }

    private var nextId = 10

    private fun device(sources: Int, type: Int, keys: IntArray = intArrayOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_DPAD_UP),
        rangeSource: Int? = null, axis: Int = MotionEvent.AXIS_X, id: Int = nextId++): InputDevice {
        val builder = InputDeviceBuilder.newBuilder().setId(id).setName("routing fixture $id")
            .setDescriptor("routing-$id").setExternal(true).setSources(sources).setKeyboardType(type)
            .setKeyCharacterMap(KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD))
        if (rangeSource != null) builder.addMotionRange(axis, rangeSource, -1f, 1f, 0f, 0f, 0f)
        val device = builder.build()
        val manager = RuntimeEnvironment.getApplication().getSystemService(InputManager::class.java)
        shadowOf(manager).addInputDevice(device)
        shadowOf(manager).addInputDeviceKeys(id, keys)
        assertSame(device, InputDevice.getDevice(id))
        assertEquals(sources, device.sources)
        assertEquals(type, device.keyboardType)
        return device
    }

    private companion object { const val COMPOSITE = 0x1002313 }

    // Robolectric's default InputEvent shadow has its own nullable device field. Use Android's
    // original lookup so key and motion events resolve the registered device by id.
    @Implements(KeyEvent::class)
    class RegisteredKeyEvent : ShadowKeyEvent() {
        @RealObject private lateinit var event: KeyEvent
        @Implementation override fun getDevice(): InputDevice? = Shadow.directlyOn(event as InputEvent, InputEvent::class.java, "getDevice")
    }

    @Implements(MotionEvent::class)
    class RegisteredMotionEvent : ShadowMotionEvent() {
        @RealObject private lateinit var event: MotionEvent
        @Implementation override fun getDevice(): InputDevice? = Shadow.directlyOn(event as InputEvent, InputEvent::class.java, "getDevice")
    }
}
