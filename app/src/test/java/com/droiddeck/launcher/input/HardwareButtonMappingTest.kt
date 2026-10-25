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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [
    HardwareButtonMappingTest.RegisteredKeyEvent::class,
    HardwareButtonMappingTest.RegisteredMotionEvent::class,
])
class HardwareButtonMappingTest {
    @Test fun learnedVendorKeyRemapsBothEdgesOnlyForItsDevice() {
        val context = RuntimeEnvironment.getApplication()
        ControllerPrefs.resetAll(context)
        val controller = device(51, "pad-51")
        val other = device(52, "pad-52")
        val down = key(controller, KeyEvent.ACTION_DOWN)
        val up = key(controller, KeyEvent.ACTION_UP)

        HardwareButtonMapping.capture(context, down)
        val binding = HardwareButtonMapping.read(context).single()
        assertEquals(controller.descriptor, binding.descriptor)
        assertEquals("a", binding.target)
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, HardwareButtonMapping.remap(context, down).keyCode)
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, HardwareButtonMapping.remap(context, up).keyCode)
        val otherDown = key(other, KeyEvent.ACTION_DOWN)
        assertSame(otherDown, HardwareButtonMapping.remap(context, otherDown))

        HardwareButtonMapping.setTarget(context, binding, "x")
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, HardwareButtonMapping.remap(context, down).keyCode)
        assertSame(otherDown, HardwareButtonMapping.remap(context, otherDown))
        HardwareButtonMapping.setTarget(context, HardwareButtonMapping.read(context).single(), "qam")
        assertEquals("qam", HardwareButtonMapping.targetFor(context, down))
        assertEquals("qam", HardwareButtonMapping.targetFor(context, up))
        assertSame(down, HardwareButtonMapping.remap(context, down))
        HardwareButtonMapping.remove(context, HardwareButtonMapping.read(context).single())
        assertEquals(KeyEvent.KEYCODE_F1, HardwareButtonMapping.remap(context, down).keyCode)
    }

    @Test fun learnedKeyOnSeparateKeyboardInterfaceUsesConnectedController() {
        val context = RuntimeEnvironment.getApplication()
        ControllerPrefs.resetAll(context)
        val controller = device(61, "pad-61")
        val keyboard = device(62, "keyboard-62", InputDevice.SOURCE_KEYBOARD)
        val event = key(keyboard, KeyEvent.ACTION_DOWN)

        assertTrue(HardwareButtonMapping.canCapture(event))
        HardwareButtonMapping.capture(context, event)
        val mapped = HardwareButtonMapping.remap(context, event)
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, mapped.keyCode)
        assertEquals(controller.id, mapped.deviceId)
        assertEquals(InputDevice.SOURCE_GAMEPAD, mapped.source)
    }

    @Test fun learnedMouseClickOnControllerTouchpadRemapsToGamepadKey() {
        val context = RuntimeEnvironment.getApplication()
        ControllerPrefs.resetAll(context)
        val controller = device(71, "pad-71")
        val touchpad = device(72, "pad-touch-72", InputDevice.SOURCE_MOUSE)
        RegisteredMotionEvent.registeredDevice = touchpad
        val down = mouse(touchpad, MotionEvent.ACTION_DOWN)
        val up = mouse(touchpad, MotionEvent.ACTION_UP)

        assertTrue(HardwareButtonMapping.canCapture(context, down))
        HardwareButtonMapping.capture(context, down)
        val binding = HardwareButtonMapping.read(context).single()
        assertEquals(1, binding.motionButton)
        assertTrue(binding.label.contains("mouse button 1"))
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, HardwareButtonMapping.remap(context, down)?.keyCode)
        val mappedUp = HardwareButtonMapping.remap(context, up)
        assertEquals(KeyEvent.ACTION_UP, mappedUp?.action)
        assertEquals(controller.id, mappedUp?.deviceId)

        HardwareButtonMapping.setTarget(context, binding, "x")
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, HardwareButtonMapping.remap(context, down)?.keyCode)
        HardwareButtonMapping.remove(context, HardwareButtonMapping.read(context).single())
        assertNull(HardwareButtonMapping.remap(context, down))
        down.recycle()
        up.recycle()
    }

    @Test fun touchpadContactCanBeLearnedAndMappedWithoutAControllerModel() {
        val context = RuntimeEnvironment.getApplication()
        ControllerPrefs.resetAll(context)
        val first = device(73, "touchpad-73", InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_TOUCHPAD,
            "First controller")
        val second = device(74, "touchpad-74", InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_TOUCHPAD,
            "Second controller")
        val firstDown = mouse(first, MotionEvent.ACTION_DOWN, InputDevice.SOURCE_TOUCHPAD)

        assertTrue(HardwareButtonMapping.canCapture(context, firstDown))
        HardwareButtonMapping.capture(context, firstDown)
        assertTrue(HardwareButtonMapping.hasConnectedTouchpadBinding(context))
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, HardwareButtonMapping.remap(context, firstDown)?.keyCode)

        val secondDown = mouse(second, MotionEvent.ACTION_DOWN, InputDevice.SOURCE_TOUCHPAD)
        assertNull(HardwareButtonMapping.targetFor(context, secondDown))
        HardwareButtonMapping.capture(context, secondDown)
        val secondBinding = HardwareButtonMapping.read(context).first { it.descriptor == second.descriptor }
        HardwareButtonMapping.setTarget(context, secondBinding, "x")
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, HardwareButtonMapping.remap(context, secondDown)?.keyCode)
        firstDown.recycle()
        secondDown.recycle()
    }

    private fun key(device: InputDevice, action: Int) = KeyEvent(
        10L, 20L, action, KeyEvent.KEYCODE_F1, 0, 0, device.id, 318, 0, InputDevice.SOURCE_KEYBOARD,
    )

    private fun mouse(device: InputDevice, action: Int, source: Int = InputDevice.SOURCE_MOUSE): MotionEvent =
        MotionEvent.obtain(10L, 20L, action, 12f, 4f, 0).apply {
            setSource(source)
            RegisteredMotionEvent.registeredDevice = device
        }

    private fun device(id: Int, descriptor: String, sources: Int = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_KEYBOARD,
        name: String = "Vendor pad", vendorId: Int = 0, productId: Int = 0): InputDevice {
        val device = InputDeviceBuilder.newBuilder().setId(id).setName(name)
            .setDescriptor(descriptor).setExternal(true)
            .setVendorId(vendorId).setProductId(productId)
            .setSources(sources)
            .setKeyboardType(InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC)
            .setKeyCharacterMap(KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)).build()
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(InputManager::class.java)).addInputDevice(device)
        return device
    }

    @Implements(KeyEvent::class)
    class RegisteredKeyEvent : ShadowKeyEvent() {
        @RealObject private lateinit var event: KeyEvent
        @Implementation override fun getDevice(): InputDevice? =
            Shadow.directlyOn(event as InputEvent, InputEvent::class.java, "getDevice")
    }

    @Implements(MotionEvent::class)
    class RegisteredMotionEvent : org.robolectric.shadows.ShadowMotionEvent() {
        @RealObject private lateinit var event: MotionEvent
        @Implementation override fun getDevice(): InputDevice? = registeredDevice

        companion object {
            var registeredDevice: InputDevice? = null
        }
    }
}
