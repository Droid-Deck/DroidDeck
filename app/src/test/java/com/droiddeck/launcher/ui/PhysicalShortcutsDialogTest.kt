package com.droiddeck.launcher.ui

import android.app.Application
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.input.PadBridgeRoutingTest
import com.droiddeck.launcher.input.PhysicalShortcuts
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
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h640dp-land",
    shadows = [PadBridgeRoutingTest.RegisteredKeyEvent::class])
class PhysicalShortcutsDialogTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var device: InputDevice

    @Before fun setUp() {
        context.getSharedPreferences("controller", 0).edit().clear().commit()
        device = InputDeviceBuilder.newBuilder().setId(10).setName("Retroid extra buttons")
            .setDescriptor("retroid-extra").setSources(InputDevice.SOURCE_KEYBOARD)
            .setKeyboardType(InputDevice.KEYBOARD_TYPE_ALPHABETIC)
            .setKeyCharacterMap(KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)).build()
        shadowOf(context.getSystemService(InputManager::class.java)).addInputDevice(device)
    }

    @Test fun assignmentRequiresReleaseAndExplicitSave() {
        var changes = 0
        compose.setContent { DroidDeckTheme { PhysicalShortcutsDialog({}, { changes++ }) } }
        assign(PhysicalShortcuts.Action.STEAM)
        send(KeyEvent.KEYCODE_F1, KeyEvent.ACTION_DOWN)
        compose.onNodeWithText(context.getString(R.string.shortcuts_release)).assertExists()
        compose.onNodeWithTag("shortcuts-save").assertDoesNotExist()
        assertTrue(PhysicalShortcuts.read(context).isEmpty())
        send(KeyEvent.KEYCODE_F1, KeyEvent.ACTION_UP)
        compose.onNodeWithTag("shortcuts-save").performScrollTo().performClick()
        compose.runOnIdle {
            val binding = PhysicalShortcuts.read(context).single()
            assertEquals(KeyEvent.KEYCODE_F1, binding.button.keyCode)
            assertEquals(PhysicalShortcuts.Action.STEAM, binding.action)
            assertEquals(1, changes)
        }
    }

    @Test fun backCanBeCapturedWithoutDismissingAndCancelDoesNotSave() {
        var dismissed = false
        compose.setContent { DroidDeckTheme { PhysicalShortcutsDialog({ dismissed = true }) } }
        assign(PhysicalShortcuts.Action.QAM)
        send(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN)
        send(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP)
        compose.onNodeWithTag("shortcuts-save").assertExists()
        compose.onNodeWithTag("shortcuts-cancel").performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(dismissed)
            assertTrue(PhysicalShortcuts.read(context).isEmpty())
        }
        compose.onNodeWithTag("shortcuts-assign-QAM").assertExists()
    }

    @Test fun conflictingAssignmentIsNotReplacedUntilSave() {
        PhysicalShortcuts.assign(context, requireNotNull(PhysicalShortcuts.button(event(KeyEvent.KEYCODE_F1, KeyEvent.ACTION_DOWN))),
            PhysicalShortcuts.Action.STEAM)
        compose.setContent { DroidDeckTheme { PhysicalShortcutsDialog({}) } }
        assign(PhysicalShortcuts.Action.QAM)
        send(KeyEvent.KEYCODE_F1, KeyEvent.ACTION_DOWN)
        send(KeyEvent.KEYCODE_F1, KeyEvent.ACTION_UP)
        compose.onNodeWithText(context.getString(R.string.shortcuts_conflict,
            context.getString(R.string.drawer_steam_menu))).assertExists()
        assertEquals(PhysicalShortcuts.Action.STEAM, PhysicalShortcuts.read(context).single().action)
        compose.onNodeWithTag("shortcuts-save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(PhysicalShortcuts.Action.QAM, PhysicalShortcuts.read(context).single().action) }
    }

    @Test fun resetRequiresConfirmationAndPreservesOtherControllerSettings() {
        ControllerPrefs.setSelectSteam(context, true)
        PhysicalShortcuts.assign(context, requireNotNull(PhysicalShortcuts.button(event(KeyEvent.KEYCODE_F1, KeyEvent.ACTION_DOWN))),
            PhysicalShortcuts.Action.STEAM)
        compose.setContent { DroidDeckTheme { PhysicalShortcutsDialog({}) } }
        compose.onNodeWithTag("shortcuts-reset").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.shortcuts_reset_hint)).assertExists()
        assertFalse(PhysicalShortcuts.read(context).isEmpty())
        compose.onAllNodesWithText(context.getString(R.string.ctrl_reset)).onLast().performClick()
        compose.runOnIdle {
            assertTrue(PhysicalShortcuts.read(context).isEmpty())
            assertTrue(ControllerPrefs.read(context).selectSteam)
        }
    }

    @Test
    @Config(qualifiers = "de-rDE-w400dp-h850dp-port")
    fun narrowLargeTextKeepsTranslatedCaptureAndSaveReachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                DroidDeckTheme { PhysicalShortcutsDialog({}) }
            }
        }
        compose.onNodeWithText("Physische Tastenkürzel").assertExists()
        assign(PhysicalShortcuts.Action.DROIDDECK)
        send(KeyEvent.KEYCODE_F1, KeyEvent.ACTION_DOWN)
        send(KeyEvent.KEYCODE_F1, KeyEvent.ACTION_UP)
        compose.onNodeWithTag("shortcuts-save").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(PhysicalShortcuts.Action.DROIDDECK, PhysicalShortcuts.read(context).single().action) }
    }

    @Test
    @Config(qualifiers = "ar-rEG-w400dp-h850dp-port")
    fun rightToLeftDialogKeepsCancelReachable() {
        compose.setContent { DroidDeckTheme { PhysicalShortcutsDialog({}) } }
        compose.onNodeWithText("اختصارات الأزرار الفعلية").assertExists()
        assign(PhysicalShortcuts.Action.QAM)
        compose.onNodeWithTag("shortcuts-cancel").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("shortcuts-assign-QAM").assertExists()
    }

    @Test fun everySupportedLanguageHasALocalizedShortcutTitleAndValidFormatArguments() {
        val languages = mutableListOf<String>()
        context.resources.getXml(R.xml.locales_config).use { xml ->
            while (xml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "locale") {
                    languages.add(xml.getAttributeValue("http://schemas.android.com/apk/res/android", "name"))
                }
            }
        }
        val english = context.getString(R.string.shortcuts_title)
        for (language in languages) {
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val localized = context.createConfigurationContext(config)
            if (language != "en") assertNotEquals(language, english, localized.getString(R.string.shortcuts_title))
            assertTrue(localized.getString(R.string.shortcuts_capture, "ACTION").contains("ACTION"))
            assertTrue(localized.getString(R.string.shortcuts_conflict, "ACTION").contains("ACTION"))
            val detected = localized.getString(R.string.shortcuts_detected, "DEVICE", "BUTTON")
            assertTrue(detected.contains("DEVICE"))
            assertTrue(detected.contains("BUTTON"))
        }
    }

    private fun assign(action: PhysicalShortcuts.Action) {
        compose.onNodeWithTag("shortcuts-assign-${action.name}").performScrollTo().performClick()
    }

    private fun send(code: Int, action: Int) {
        compose.runOnIdle {
            assertTrue(ShadowDialog.getLatestDialog().window!!.callback.dispatchKeyEvent(event(code, action)))
        }
        compose.waitForIdle()
    }

    private fun event(code: Int, action: Int) =
        KeyEvent(100L, 100L, action, code, 0, 0, device.id, 700, 0, InputDevice.SOURCE_KEYBOARD)
}
