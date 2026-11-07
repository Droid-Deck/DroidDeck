package com.droiddeck.launcher.input

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.runtime.MutableState
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [SessionInputPointerTest.WriterShadow::class])
class SecondScreenProfileMigrationTest {
    private lateinit var activity: SessionActivity
    private val previousMode = SessionState.mode
    private val previousPanel = SessionState.secondScreenMode
    private val previousDisplay = SessionState.secondScreenDisplay

    @Before fun setUp() {
        SessionState.secondScreenMode = SecondScreenMode.NONE
        activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val displays = activity.getSystemService(DisplayManager::class.java)
        shadowOf(displays.getDisplay(Display.DEFAULT_DISPLAY)).setFlags(Display.FLAG_PRESENTATION)
        ReflectionHelpers.setField(activity, "displayManager", displays)
        ReflectionHelpers.setField(activity, "started", true)
        val display: MutableState<Int> = ReflectionHelpers.getField(activity, "selectedSecondScreenDisplay\$delegate")
        display.value = Display.DEFAULT_DISPLAY
    }

    @After fun tearDown() {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "closeSecondScreen", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        SessionState.mode = previousMode
        SessionState.secondScreenMode = previousPanel
        SessionState.secondScreenDisplay = previousDisplay
    }

    @Test fun automaticOpeningKeepsCustomLayoutsAndBindingsInBothSessionModes() {
        for (desktop in listOf(false, true)) for (deckPad in listOf(false, true)) {
            val original = customLibrary(desktop)
            open(null, deckPad)
            assertEquals(original, InputLibraries.read(activity))
            assertNull(SessionPrefs.secondScreenPanel(activity))
            assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SessionState.secondScreenMode)
        }
    }

    @Test fun unavailableOrUnknownPanelsDoNotReplaceCustomControls() {
        for (remembered in listOf("none", "gone", "dashboard", "desktop")) for (deckPad in listOf(false, true)) {
            val original = customLibrary(desktop = false)
            SessionPrefs.setSecondScreenPanel(activity, remembered)
            open(remembered, deckPad)
            assertEquals(original, InputLibraries.read(activity))
            assertEquals(remembered, SessionPrefs.secondScreenPanel(activity))
            assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SessionState.secondScreenMode)
        }
    }

    @Test fun explicitlySavedLegacyPanelsStillMigrateOnlyTheCurrentMode() {
        for (desktop in listOf(false, true)) for (legacy in listOf(SecondScreenMode.DECK_CONTROLS, SecondScreenMode.KEYBOARD_TRACKPAD)) {
            val original = customLibrary(desktop)
            SessionPrefs.setSecondScreenPanel(activity, legacy.id)
            open(legacy.id, deckPad = true)
            val preset = if (legacy == SecondScreenMode.DECK_CONTROLS) InputStockLayouts.DECK else InputStockLayouts.TRACKPAD
            val expected = original.selectPart(desktop, InputRole.SECONDARY, preset)
            assertEquals(expected, InputLibraries.read(activity))
            assertEquals(SecondScreenMode.CUSTOM_CONTROLS.id, SessionPrefs.secondScreenPanel(activity))
            open(SessionPrefs.secondScreenPanel(activity), deckPad = true)
            assertEquals(expected, InputLibraries.read(activity))
        }
    }

    @Test fun legacyPanelsDoNotReplaceAnExistingQuickSelection() {
        customLibrary(desktop = true)
        val original = InputLibraries.update(activity) { it.selectPart(true, InputRole.SECONDARY, "custom.bindings") }
        SessionPrefs.setSecondScreenPanel(activity, SecondScreenMode.DECK_CONTROLS.id)
        open(SecondScreenMode.DECK_CONTROLS.id, deckPad = true)
        assertEquals(original, InputLibraries.read(activity))
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS.id, SessionPrefs.secondScreenPanel(activity))
    }

    @Test fun unrelatedPartSelectionsDoNotSkipTheSavedPanelMigration() {
        for (desktop in listOf(false, true)) for (role in listOf(InputRole.PHYSICAL, InputRole.PRIMARY)) {
            customLibrary(desktop)
            val original = InputLibraries.update(activity) { it.selectPart(desktop, role, it.profile("game").binding(role)) }
            SessionPrefs.setSecondScreenPanel(activity, SecondScreenMode.KEYBOARD_TRACKPAD.id)
            open(SecondScreenMode.KEYBOARD_TRACKPAD.id, deckPad = true)
            assertEquals(original.selectPart(desktop, InputRole.SECONDARY, InputStockLayouts.TRACKPAD), InputLibraries.read(activity))
            assertEquals(SecondScreenMode.CUSTOM_CONTROLS.id, SessionPrefs.secondScreenPanel(activity))
        }
    }

    @Test fun explicitProfilesIncludingAutoKeepTheirSecondaryLayoutAfterOtherPartChanges() {
        for (desktop in listOf(false, true)) for (profile in listOf(InputProfiles.AUTO, InputProfile.GAME, InputProfile.DESKTOP)) {
            for (role in listOf(null, InputRole.PHYSICAL, InputRole.PRIMARY)) {
                customLibrary(desktop)
                val original = InputLibraries.update(activity) { library ->
                    val selected = library.selectProfile(desktop, profile)
                    if (role == null) selected else selected.selectPart(desktop, role, selected.profile("game").binding(role))
                }
                SessionPrefs.setSecondScreenPanel(activity, SecondScreenMode.DECK_CONTROLS.id)
                open(SecondScreenMode.DECK_CONTROLS.id, deckPad = true)
                assertEquals(original, InputLibraries.read(activity))
                assertEquals(SecondScreenMode.CUSTOM_CONTROLS.id, SessionPrefs.secondScreenPanel(activity))
            }
        }
    }

    @Test fun unavailableDeckPanelKeepsItsSavedChoiceUntilItCanMigrate() {
        for (desktop in listOf(false, true)) {
            val original = customLibrary(desktop)
            SessionPrefs.setSecondScreenPanel(activity, SecondScreenMode.DECK_CONTROLS.id)
            open(SecondScreenMode.DECK_CONTROLS.id, deckPad = false)
            assertEquals(original, InputLibraries.read(activity))
            assertEquals(SecondScreenMode.DECK_CONTROLS.id, SessionPrefs.secondScreenPanel(activity))
            open(SessionPrefs.secondScreenPanel(activity), deckPad = true)
            assertEquals(original.selectPart(desktop, InputRole.SECONDARY, InputStockLayouts.DECK), InputLibraries.read(activity))
            assertEquals(SecondScreenMode.CUSTOM_CONTROLS.id, SessionPrefs.secondScreenPanel(activity))
        }
    }

    @Test fun failedMigrationKeepsTheSavedPanelAndCanBeRetried() {
        val original = customLibrary(desktop = false)
        val prefs = activity.getSharedPreferences("input-profiles", Context.MODE_PRIVATE)
        val valid = prefs.getString("library", null)!!
        val unsupported = JSONObject(valid).put("version", 999).toString()
        prefs.edit().putString("library", unsupported).commit()
        SessionPrefs.setSecondScreenPanel(activity, SecondScreenMode.KEYBOARD_TRACKPAD.id)
        open(SecondScreenMode.KEYBOARD_TRACKPAD.id, deckPad = true)
        assertEquals(unsupported, prefs.getString("library", null))
        assertEquals(SecondScreenMode.KEYBOARD_TRACKPAD.id, SessionPrefs.secondScreenPanel(activity))
        prefs.edit().putString("library", valid).commit()
        open(SessionPrefs.secondScreenPanel(activity), deckPad = true)
        assertEquals(original.selectPart(false, InputRole.SECONDARY, InputStockLayouts.TRACKPAD), InputLibraries.read(activity))
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS.id, SessionPrefs.secondScreenPanel(activity))
    }

    private fun customLibrary(desktop: Boolean): InputLibrary {
        SessionState.mode = if (desktop) SessionService.MODE_DESKTOP else SessionService.MODE_STEAM
        return InputLibraries.update(activity) { library ->
            val id = if (desktop) InputProfile.DESKTOP else InputProfile.GAME
            val element = InputSurfaceElement(InputControl.A, .3f, .2f, .2f, .2f, "custom.button")
            val layout = SavedInputLayout("custom.layout", "Custom controls", listOf(element), screen = InputLayoutScreen.SECONDARY)
            val bindings = SavedInputBindings("custom.bindings", "Custom actions", false, layout.id,
                elements = mapOf(element.id to InputAction.Keys(listOf(57))))
            val profile = library.profile(id)
            library.withLayout(layout).withPreset(bindings)
                .withProfile(profile.copy(parts = profile.parts + (InputRole.SECONDARY to InputPart(bindings.id, layout.id))))
                .copy(selections = emptyMap())
        }
    }

    private fun open(remembered: String?, deckPad: Boolean) {
        val mode = SecondScreenMode.opening(remembered, deckPad)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "showSecondScreen", ReflectionHelpers.ClassParameter.from(SecondScreenMode::class.java, mode))
    }
}
