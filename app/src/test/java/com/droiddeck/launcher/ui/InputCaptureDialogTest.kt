package com.droiddeck.launcher.ui

import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.PadBridgeRoutingTest
import com.droiddeck.launcher.input.PhysicalInputKey
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.InputDeviceBuilder
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h640dp-land", shadows = [PadBridgeRoutingTest.RegisteredKeyEvent::class])
class InputCaptureDialogTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val open = mutableStateOf(true)
    private var selected: PhysicalInputKey? = null
    private lateinit var device: InputDevice

    @Before fun setUp() {
        device = InputDeviceBuilder.newBuilder().setId(10).setName("Thor buttons")
            .setDescriptor("thor-extra").setSources(InputDevice.SOURCE_KEYBOARD)
            .setKeyboardType(InputDevice.KEYBOARD_TYPE_ALPHABETIC)
            .setKeyCharacterMap(KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)).build()
        shadowOf(context.getSystemService(InputManager::class.java)).addInputDevice(device)
        compose.setContent { DroidDeckTheme {
            if (open.value) InputCaptureDialog({ open.value = false }) { selected = it; open.value = false }
        } }
    }

    @Test fun physicalBackCanBeCapturedAndConfirmedWithControllerA() {
        send(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN)
        compose.onNodeWithText(context.getString(R.string.ctrl_edit)).assertIsNotEnabled()
        assertTrue(open.value)
        send(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP)
        compose.onNodeWithText(context.getString(R.string.ctrl_edit)).assertIsEnabled()
        assertNull(selected)
        send(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN)
        assertEquals(KeyEvent.KEYCODE_BACK, selected?.keyCode)
        assertFalse(open.value)
    }

    @Test fun controllerBCanBeCapturedAndAnotherPressCancelsWithoutSaving() {
        send(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.ACTION_DOWN)
        send(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.ACTION_UP)
        assertTrue(open.value)
        send(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.ACTION_DOWN)
        assertFalse(open.value)
        assertNull(selected)
    }

    @Test fun retryAllowsAnotherButtonAndConsumesItsReleaseBeforeConfirmation() {
        send(KeyEvent.KEYCODE_F1, KeyEvent.ACTION_DOWN)
        send(KeyEvent.KEYCODE_F1, KeyEvent.ACTION_UP)
        compose.onNodeWithText(context.getString(R.string.input_capture_retry)).performClick()
        send(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN)
        send(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_UP)
        assertNull(selected)
        assertTrue(open.value)
        send(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN)
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, selected?.keyCode)
    }

    private fun send(code: Int, action: Int) {
        compose.runOnIdle {
            assertTrue(ShadowDialog.getLatestDialog().window!!.callback.dispatchKeyEvent(
                KeyEvent(100L, 100L, action, code, 0, 0, device.id, 700, 0, InputDevice.SOURCE_KEYBOARD)))
        }
        compose.waitForIdle()
    }
}
