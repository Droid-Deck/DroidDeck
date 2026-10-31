package com.droiddeck.launcher.input

import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import java.io.File
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
@Config(sdk = [34], manifest = Config.NONE, shadows = [JoyConPadTest.RecordingWriter::class,
    PadBridgeRoutingTest.RegisteredKeyEvent::class])
class SelectProfileRoutingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val bridge = PadBridge(File(context.cacheDir, "select-profile"))
    private val events = mutableListOf<String>()
    private val output = object : InputOutput {
        override fun key(code: Int, down: Boolean) { events += "key $code $down" }
        override fun button(code: Int, down: Boolean) = Unit
        override fun pointer(dx: Float, dy: Float) = Unit
        override fun scroll(steps: Int) = Unit
        override fun command(command: InputCommand) { events += command.name }
        override fun requestMotion() = Unit
    }
    private val device = InputDeviceBuilder.newBuilder().setId(42).setName("Select test pad")
        .setDescriptor("select-test").setSources(InputDevice.SOURCE_GAMEPAD).build()

    @Before fun setUp() {
        context.getSharedPreferences("input-profiles", 0).edit().clear().commit()
        context.getSharedPreferences("controller", 0).edit().clear().commit()
        ControllerPrefs.setSelectSteam(context, true)
        shadowOf(context.getSystemService(InputManager::class.java)).addInputDevice(device)
        bridge.setInputRouter(InputRouter(output))
        bridge.setInputProfile(InputProfiles.active(context, desktop = false))
    }

    @After fun close() { bridge.stop() }

    @Test fun migratedSteamSelectReachesGuideAndReleasesWithoutReleasingPhysicalGuide() {
        key(KeyEvent.KEYCODE_BUTTON_SELECT, true)
        assertTrue(state().isDown(PadState.GUIDE))
        assertFalse(state().isDown(PadState.SELECT))
        key(KeyEvent.KEYCODE_BUTTON_MODE, true)
        key(KeyEvent.KEYCODE_BUTTON_SELECT, false)
        assertTrue(state().isDown(PadState.GUIDE))
        key(KeyEvent.KEYCODE_BUTTON_MODE, false)
        assertFalse(state().isDown(PadState.GUIDE))
    }

    @Test fun profileResetAndExplicitNativeBindingOverrideTheMigratedSetting() {
        val migrated = InputProfiles.active(context, desktop = false)
        InputProfiles.save(context, migrated.withBinding(InputRole.PHYSICAL, InputControl.SELECT, InputAction.Native()))
        bridge.setInputProfile(InputProfiles.active(context, desktop = false))
        assertNativeSelect()
        InputProfiles.reset(context, InputProfile.GAME)
        bridge.setInputProfile(InputProfiles.active(context, desktop = false))
        assertNativeSelect()
        assertTrue(ControllerPrefs.read(context).selectSteam)
    }

    @Test fun disabledAndLocalSelectBindingsDoNotLeakGuide() {
        for (action in listOf(InputAction.Disabled, InputAction.Keys(listOf(28)), InputAction.Command(InputCommand.KEYBOARD))) {
            bridge.setInputProfile(InputProfile.defaults(InputProfile.GAME).withBinding(InputRole.PHYSICAL, InputControl.SELECT, action))
            key(KeyEvent.KEYCODE_BUTTON_SELECT, true)
            assertFalse(state().isDown(PadState.GUIDE))
            assertFalse(state().isDown(PadState.SELECT))
            key(KeyEvent.KEYCODE_BUTTON_SELECT, false)
        }
        assertEquals(listOf("key 28 true", "key 28 false", "KEYBOARD"), events)
    }

    @Test fun replacingOrRemovingRouterWhileSelectIsHeldNeverLeavesGuideStuck() {
        key(KeyEvent.KEYCODE_BUTTON_SELECT, true)
        bridge.setInputRouter(null)
        assertFalse(state().isDown(PadState.GUIDE))
        key(KeyEvent.KEYCODE_BUTTON_SELECT, false)
        assertFalse(state().isDown(PadState.SELECT))
        key(KeyEvent.KEYCODE_BUTTON_SELECT, true)
        bridge.setInputRouter(InputRouter(output).also { it.setProfile(InputProfiles.active(context, desktop = false)) })
        assertFalse(state().isDown(PadState.GUIDE))
        key(KeyEvent.KEYCODE_BUTTON_SELECT, false)
        assertFalse(state().isDown(PadState.GUIDE))
        key(KeyEvent.KEYCODE_BUTTON_SELECT, true)
        assertTrue(state().isDown(PadState.GUIDE))
        key(KeyEvent.KEYCODE_BUTTON_SELECT, false)
        assertFalse(state().isDown(PadState.GUIDE))
    }

    private fun assertNativeSelect() {
        key(KeyEvent.KEYCODE_BUTTON_SELECT, true)
        assertTrue(state().isDown(PadState.SELECT))
        assertFalse(state().isDown(PadState.GUIDE))
        key(KeyEvent.KEYCODE_BUTTON_SELECT, false)
    }

    private fun key(code: Int, down: Boolean) {
        assertTrue(bridge.onKeyEvent(KeyEvent(0, 0, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            code, 0, 0, device.id, 0, 0, InputDevice.SOURCE_GAMEPAD)))
    }

    private fun state() = JoyConPadTest.RecordingWriter.pads[0]!!
}
