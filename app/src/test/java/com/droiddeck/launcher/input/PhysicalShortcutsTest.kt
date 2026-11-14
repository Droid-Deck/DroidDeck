package com.droiddeck.launcher.input

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.droiddeck.launcher.input.PhysicalShortcuts.Action
import org.json.JSONArray
import org.json.JSONObject
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
@Config(sdk = [34], manifest = Config.NONE, shadows = [PadBridgeRoutingTest.RegisteredKeyEvent::class])
class PhysicalShortcutsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val router = PhysicalShortcutRouter()
    private val actions = mutableListOf<Action>()
    private lateinit var pad: InputDevice

    @Before fun setUp() {
        ControllerPrefs.prefs(context).edit().clear().commit()
        pad = device(10, "retroid")
    }

    @Test fun defaultsDoNotInterceptAnyButton() {
        assertTrue(PhysicalShortcuts.read(context).isEmpty())
        for (code in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_MODE,
                KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_1)) {
            assertFalse(route(key(code)))
            assertFalse(route(key(code, KeyEvent.ACTION_UP)))
        }
        assertTrue(actions.isEmpty())
    }

    @Test fun assignmentPersistsAndConsumesTheWholePressWithoutRepeating() {
        assign(key(), Action.STEAM)
        assertEquals(listOf(PhysicalShortcuts.Binding(button(key()), Action.STEAM)), PhysicalShortcuts.read(context))
        assertTrue(route(key()))
        assertTrue(route(key(repeat = 1)))
        assertTrue(route(key(repeat = 2)))
        assertTrue(route(key(action = KeyEvent.ACTION_UP)))
        assertEquals(listOf(Action.STEAM), actions)
        assertFalse(route(key(action = KeyEvent.ACTION_UP)))
    }

    @Test fun releaseIsConsumedEvenIfAnActionChangedTheRoutingContextOrSettings() {
        assign(key(), Action.DROIDDECK)
        assertTrue(route(key()))
        router.bindings = emptyList()
        assertTrue(route(key(action = KeyEvent.ACTION_UP), enabled = false, steam = false))
        assertEquals(listOf(Action.DROIDDECK), actions)
    }

    @Test fun aRepeatedDownWithoutAnInitialPressDoesNotTriggerAnAction() {
        assign(key(), Action.QAM)
        assertTrue(route(key(repeat = 1)))
        assertTrue(route(key(action = KeyEvent.ACTION_UP)))
        assertTrue(actions.isEmpty())
    }

    @Test fun disabledMappedConfirmLeavesTheWholePressForDrawerNavigation() {
        assign(key(KeyEvent.KEYCODE_BUTTON_A), Action.DROIDDECK)
        assertFalse(route(key(KeyEvent.KEYCODE_BUTTON_A), enabled = false))
        assertFalse(route(key(KeyEvent.KEYCODE_BUTTON_A, repeat = 1), enabled = false))
        assertFalse(route(key(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_UP), enabled = false))
        assertTrue(actions.isEmpty())
    }

    @Test fun mappedConfirmOpeningTheDrawerConsumesItsReleaseWithoutStayingHeld() {
        assign(key(KeyEvent.KEYCODE_BUTTON_A), Action.DROIDDECK)
        assertTrue(route(key(KeyEvent.KEYCODE_BUTTON_A)))
        assertTrue(route(key(KeyEvent.KEYCODE_BUTTON_A, repeat = 1), enabled = false))
        assertTrue(route(key(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_UP), enabled = false))
        assertFalse(route(key(KeyEvent.KEYCODE_BUTTON_A), enabled = false))
        assertFalse(route(key(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_UP), enabled = false))
        assertTrue(route(key(KeyEvent.KEYCODE_BUTTON_A)))
        assertTrue(route(key(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_UP)))
        assertEquals(listOf(Action.DROIDDECK, Action.DROIDDECK), actions)
    }

    @Test fun aResumePressKeepsItsRepeatsAndReleaseInTheResumePath() {
        assign(key(), Action.STEAM)
        assertFalse(route(key(), enabled = false))
        assertFalse(route(key(repeat = 1), enabled = false))
        assertFalse(route(key(action = KeyEvent.ACTION_UP), enabled = false))
        assertTrue(actions.isEmpty())
        assertTrue(route(key()))
        assertEquals(listOf(Action.STEAM), actions)
    }

    @Test fun steamActionsAreInactiveOutsideSteamAndAllActionsRespectDisabledContexts() {
        for (action in Action.entries) {
            assign(key(), action)
            assertFalse(route(key(), enabled = false))
            assertFalse(route(key(action = KeyEvent.ACTION_UP), enabled = false))
            assertEquals(action == Action.DROIDDECK, route(key(), steam = false))
            route(key(action = KeyEvent.ACTION_UP), steam = false)
        }
        assertEquals(listOf(Action.DROIDDECK), actions)
    }

    @Test fun reassigningAButtonReplacesItsPreviousAction() {
        assign(key(), Action.STEAM)
        assign(key(), Action.QAM)
        assertEquals(listOf(Action.QAM), PhysicalShortcuts.read(context).map { it.action })
        assertTrue(route(key()))
        assertEquals(listOf(Action.QAM), actions)
    }

    @Test fun reassigningAnActionOnlyReplacesThatControllersBinding() {
        val second = device(11, "bluetooth")
        assign(key(), Action.STEAM)
        assign(key(device = second), Action.STEAM)
        assign(key(KeyEvent.KEYCODE_BUTTON_2), Action.STEAM)
        val saved = PhysicalShortcuts.read(context)
        assertEquals(2, saved.size)
        assertTrue(saved.any { it.button.sameInput(button(key(device = second))) })
        assertTrue(saved.any { it.button.sameInput(button(key(KeyEvent.KEYCODE_BUTTON_2))) })
        assertFalse(route(key()))
    }

    @Test fun deviceIdentitySurvivesReconnectionButDoesNotMatchAnUnrelatedController() {
        assign(key(), Action.QAM)
        assertFalse(route(key(device = device(11, "other"))))
        val reconnected = device(12, "retroid")
        assertTrue(route(key(device = reconnected)))
        assertEquals(listOf(Action.QAM), actions)
    }

    @Test fun scanCodesDistinguishButtonsSharingAnAndroidKeyCode() {
        assign(key(scan = 700), Action.STEAM)
        assign(key(scan = 701), Action.QAM)
        assertEquals(2, PhysicalShortcuts.read(context).size)
        assertTrue(route(key(scan = 700)))
        assertTrue(route(key(scan = 701)))
        assertFalse(route(key(scan = 702)))
        assertEquals(listOf(Action.STEAM, Action.QAM), actions)
    }

    @Test fun keyboardLikeExtraButtonsCanBeExplicitlyAssignedWithoutChangingKeyboardDefaults() {
        val keyboard = device(11, "m1-keyboard", InputDevice.SOURCE_KEYBOARD, InputDevice.KEYBOARD_TYPE_ALPHABETIC)
        val m1 = key(KeyEvent.KEYCODE_F1, device = keyboard)
        assertFalse(PadBridge.isControllerKeyEvent(m1))
        assign(m1, Action.STEAM)
        assertTrue(route(m1))
        assertFalse(route(key(KeyEvent.KEYCODE_F2, device = keyboard)))
        assertEquals(listOf(Action.STEAM), actions)
    }

    @Test fun virtualAndSystemKeysCannotBeAssigned() {
        val virtual = device(-1, "virtual")
        assertNull(PhysicalShortcuts.button(key(device = virtual)))
        for (code in listOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_APP_SWITCH,
                KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)) {
            assertNull(PhysicalShortcuts.button(key(code)))
        }
        assertNull(PhysicalShortcuts.button(key(KeyEvent.KEYCODE_UNKNOWN, scan = 0)))
        assertNotNull(PhysicalShortcuts.button(key(KeyEvent.KEYCODE_UNKNOWN, scan = 700)))
    }

    @Test fun drawerDirectionalKeysRemainAvailableForNavigation() {
        for (code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            assertTrue(PhysicalShortcuts.isDrawerNavigationKey(key(code)))
        }
        assertFalse(PhysicalShortcuts.isDrawerNavigationKey(key(KeyEvent.KEYCODE_BUTTON_A)))
    }

    @Test fun resetAndRemovalPreserveOtherControllerSettings() {
        ControllerPrefs.setSelectSteam(context, true)
        ControllerPrefs.setTarget(context, "a", "b")
        assign(key(), Action.STEAM)
        assign(key(KeyEvent.KEYCODE_BUTTON_2), Action.QAM)
        PhysicalShortcuts.remove(context, PhysicalShortcuts.read(context).first())
        assertEquals(listOf(Action.QAM), PhysicalShortcuts.read(context).map { it.action })
        PhysicalShortcuts.remove(context, PhysicalShortcuts.read(context).single())
        assertTrue(PhysicalShortcuts.read(context).isEmpty())
        assertTrue(ControllerPrefs.read(context).selectSteam)
        assertEquals("b", ControllerPrefs.target(context, "a"))
        assign(key(), Action.STEAM)
        ControllerPrefs.resetAll(context)
        assertTrue(PhysicalShortcuts.read(context).isEmpty())
    }

    @Test fun disconnectAndPauseClearHeldButtons() {
        assign(key(), Action.QAM)
        assertTrue(route(key()))
        router.onDeviceRemoved(pad.id)
        assertFalse(route(key(action = KeyEvent.ACTION_UP)))
        assertTrue(route(key()))
        router.clear()
        assertFalse(route(key(action = KeyEvent.ACTION_UP)))
        assertTrue(route(key()))
        assertEquals(listOf(Action.QAM, Action.QAM, Action.QAM), actions)
    }

    @Test fun malformedPreferencesDoNotCrashInputDispatch() {
        ControllerPrefs.prefs(context).edit().putString("physicalShortcuts", "invalid").commit()
        assertTrue(PhysicalShortcuts.read(context).isEmpty())
    }

    @Test fun malformedEntriesDoNotDiscardValidAssignmentsWhenSaving() {
        assign(key(), Action.STEAM)
        assign(key(KeyEvent.KEYCODE_BUTTON_2), Action.QAM)
        val entries = JSONArray(ControllerPrefs.prefs(context).getString("physicalShortcuts", null))
        val damaged = JSONArray().put(entries.getJSONObject(0)).put(JSONObject().put("action", "STEAM"))
            .put("not an object").put(entries.getJSONObject(1))
        ControllerPrefs.prefs(context).edit().putString("physicalShortcuts", damaged.toString()).commit()
        assertEquals(listOf(Action.STEAM, Action.QAM), PhysicalShortcuts.read(context).map { it.action })
        assign(key(KeyEvent.KEYCODE_BUTTON_3), Action.DROIDDECK)
        assertEquals(listOf(Action.STEAM, Action.QAM, Action.DROIDDECK),
            PhysicalShortcuts.read(context).map { it.action })
    }

    @Test fun buttonSummaryKeepsTechnicalDetailsSeparate() {
        val select = button(key(KeyEvent.KEYCODE_BUTTON_SELECT, scan = 314))
        val androidName = KeyEvent.keyCodeToString(KeyEvent.KEYCODE_BUTTON_SELECT)
        assertEquals(androidName.removePrefix("KEYCODE_").removePrefix("BUTTON_"), select.label)
        assertFalse(select.label.contains("(314)"))
    }

    private fun assign(event: KeyEvent, action: Action) {
        PhysicalShortcuts.assign(context, button(event), action)
        router.bindings = PhysicalShortcuts.read(context)
    }

    private fun button(event: KeyEvent) = requireNotNull(PhysicalShortcuts.button(event))

    private fun route(event: KeyEvent, enabled: Boolean = true, steam: Boolean = true) =
        router.onKey(event, enabled, steam) { actions.add(it) }

    private fun key(code: Int = KeyEvent.KEYCODE_BUTTON_1, action: Int = KeyEvent.ACTION_DOWN,
        repeat: Int = 0, scan: Int = 700, device: InputDevice = pad): KeyEvent =
        KeyEvent(100L, 100L, action, code, repeat, 0, device.id, scan, 0, device.sources)

    private fun device(id: Int, descriptor: String, sources: Int = InputDevice.SOURCE_GAMEPAD,
        type: Int = InputDevice.KEYBOARD_TYPE_NONE): InputDevice {
        val device = InputDeviceBuilder.newBuilder().setId(id).setName("Test $descriptor")
            .setDescriptor(descriptor).setSources(sources).setKeyboardType(type)
            .setKeyCharacterMap(KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)).build()
        shadowOf(context.getSystemService(InputManager::class.java)).addInputDevice(device)
        return device
    }
}
