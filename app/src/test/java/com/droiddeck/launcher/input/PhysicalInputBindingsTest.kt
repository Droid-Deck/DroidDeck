package com.droiddeck.launcher.input

import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.InputDeviceBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [PadBridgeRoutingTest.RegisteredKeyEvent::class])
class PhysicalInputBindingsTest {
    private val events = ArrayList<String>()
    private val router = InputRouter(object : InputOutput {
        override fun key(code: Int, down: Boolean) { events += "$code $down" }
        override fun button(code: Int, down: Boolean) {}
        override fun pointer(dx: Float, dy: Float) {}
        override fun scroll(steps: Int) {}
        override fun command(command: InputCommand) { events += command.name }
        override fun requestMotion() {}
    })
    private val bindings = PhysicalInputBindings(router)

    private fun device(id: Int): InputDevice {
        val device = InputDeviceBuilder.newBuilder().setId(id).setName("Thor extra keys").setDescriptor("keys-$id")
            .setSources(InputDevice.SOURCE_KEYBOARD).setKeyboardType(InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC).build()
        val manager = RuntimeEnvironment.getApplication().getSystemService(InputManager::class.java)
        shadowOf(manager).addInputDevice(device)
        return device
    }
    private fun event(id: Int, down: Boolean, code: Int = KeyEvent.KEYCODE_F1, repeat: Int = 0) =
        KeyEvent(0L, 0L, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, repeat, 0, id, 59, 0, InputDevice.SOURCE_KEYBOARD)

    @Test fun captureReservesSystemKeysButAcceptsKeyboardBackButtons() {
        val device = device(40)
        assertNotNull(PhysicalInputKey.capture(event(device.id, true)))
        assertNull(PhysicalInputKey.capture(event(device.id, true, KeyEvent.KEYCODE_VOLUME_UP)))
        assertNull(PhysicalInputKey.capture(event(device.id, true, KeyEvent.KEYCODE_HOME)))
    }

    @Test fun bindingMatchesOnlyItsDeviceAndReleasesAfterEditingProfile() {
        val device = device(40); val other = device(41)
        val key = PhysicalInputKey.capture(event(device.id, true))!!
        bindings.configure(listOf(PhysicalInputBinding(key, InputAction.Keys(listOf(29, 30)))))
        assertFalse(bindings.onKey(event(other.id, true), true))
        assertTrue(bindings.onKey(event(device.id, true), true))
        bindings.configure(listOf(PhysicalInputBinding(key, InputAction.Keys(listOf(28)))))
        assertTrue(bindings.onKey(event(device.id, true, repeat = 1), true))
        assertTrue(bindings.onKey(event(device.id, false), true))
        assertEquals(listOf("29 true", "30 true", "30 false", "29 false"), events)
        bindings.onKey(event(device.id, true), true)
        assertEquals("28 true", events.last())
    }

    @Test fun releasesRemainConsumedAfterOpeningMenuAndVirtualKeysAreUntouched() {
        val device = device(40)
        val key = PhysicalInputKey.capture(event(device.id, true))!!
        bindings.configure(listOf(PhysicalInputBinding(key, InputAction.Keys(listOf(57)))))
        assertFalse(bindings.onKey(event(-1, true), true))
        bindings.onKey(event(device.id, true), true)
        bindings.release()
        assertTrue(bindings.onKey(event(device.id, false), false))
        assertEquals(listOf("57 true", "57 false"), events)
    }
}
