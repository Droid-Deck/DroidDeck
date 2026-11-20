package com.droiddeck.launcher.input

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.runtime.MutableState
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
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
    private val previousDeckPad = SessionState.deckPad

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
        SessionState.deckPad = previousDeckPad
    }

    @Test fun firstOpeningKeepsTheLibraryAndUsesProfileControlsInBothSessions() {
        for (desktop in listOf(false, true)) for (deckPad in listOf(false, true)) {
            val original = customLibrary(desktop)
            open(desktop, deckPad)
            assertEquals(original, InputLibraries.read(activity))
            assertNull(SessionPrefs.secondScreenPanel(activity, desktop))
            val expected = SecondScreenMode.CUSTOM_CONTROLS
            assertEquals(expected, SessionState.secondScreenMode)
        }
    }

    @Test fun desktopAndGamesEachReopenTheirOwnLastPanel() {
        customLibrary(desktop = false)
        SessionPrefs.setSecondScreenPanel(activity, true, SecondScreenMode.TERMINAL.id)
        SessionPrefs.setSecondScreenPanel(activity, false, SecondScreenMode.CUSTOM_CONTROLS.id)
        open(desktop = true, deckPad = true)
        assertEquals(SecondScreenMode.TERMINAL, SessionState.secondScreenMode)
        open(desktop = false, deckPad = true)
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SessionState.secondScreenMode)
    }

    @Test fun disablingAndEnablingRestoresTheRememberedContent() {
        customLibrary(desktop = false)
        ReflectionHelpers.getField<MutableState<List<SecondScreenDisplay>>>(activity, "secondScreenDisplays\$delegate").value =
            listOf(SecondScreenDisplay(Display.DEFAULT_DISPLAY, "Bottom screen"))
        SessionPrefs.setSecondScreenPanel(activity, false, SecondScreenMode.TERMINAL.id)
        show(SecondScreenMode.TERMINAL)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "setSecondScreenEnabled",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        assertEquals(SecondScreenMode.NONE, SessionState.secondScreenMode)
        assertFalse(SessionPrefs.secondScreenEnabled(activity, false))
        assertTrue(ReflectionHelpers.getField<MutableState<Boolean>>(activity, "drawerOpenState\$delegate").value)
        assertEquals(SecondScreenMode.TERMINAL.id, SessionPrefs.secondScreenPanel(activity, false))
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "setSecondScreenEnabled",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        assertEquals(SecondScreenMode.TERMINAL, SessionState.secondScreenMode)
        assertTrue(SessionPrefs.secondScreenEnabled(activity, false))
        assertTrue(ReflectionHelpers.getField<MutableState<Boolean>>(activity, "drawerOpenState\$delegate").value)
    }

    @Test fun theSharedPanelChoiceCarriesOverToGamesExceptTheForcedProfileControls() {
        val prefs = activity.getSharedPreferences("session", Context.MODE_PRIVATE)
        prefs.edit().putString("secondScreenPanel", SecondScreenMode.TERMINAL.id).commit()
        assertEquals(SecondScreenMode.TERMINAL.id, SessionPrefs.secondScreenPanel(activity, false))
        assertNull(SessionPrefs.secondScreenPanel(activity, true))
        customLibrary(desktop = true)
        open(desktop = true, deckPad = true)
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SessionState.secondScreenMode)
        prefs.edit().putString("secondScreenPanel", SecondScreenMode.CUSTOM_CONTROLS.id).commit()
        for (desktop in listOf(false, true)) assertNull(SessionPrefs.secondScreenPanel(activity, desktop))
    }

    @Test fun historicalModeIdsDoNotOverrideTheActiveProfileAfterMigration() {
        for (desktop in listOf(false, true)) for (mode in listOf(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.DECK_CONTROLS)) {
            val original = customLibrary(desktop)
            SessionState.deckPad = true
            show(mode)
            assertEquals(original, InputLibraries.read(activity))
            assertEquals("custom.layout", routedLayout()?.id)
            assertEquals(InputAction.Keys(listOf(57)), routedProfile().elementAction(InputRole.SECONDARY, "custom.button", InputControl.A))
            show(SecondScreenMode.CUSTOM_CONTROLS)
            assertEquals("custom.layout", routedLayout()?.id)
            assertEquals(InputAction.Keys(listOf(57)), routedProfile().elementAction(InputRole.SECONDARY, "custom.button", InputControl.A))
        }
    }

    @Test fun aDeckPanelLeftOverWithoutTheDeckPadOpensTheFallbackInstead() {
        for (desktop in listOf(false, true)) {
            customLibrary(desktop)
            SessionState.deckPad = false
            show(SecondScreenMode.DECK_CONTROLS)
            val fallback = ReflectionHelpers.callInstanceMethod<SecondScreenMode>(activity, "openingPanel")
            assertNotEquals(SecondScreenMode.DECK_CONTROLS, fallback)
            if (desktop) assertEquals(SecondScreenMode.CUSTOM_CONTROLS, fallback)
            assertEquals(fallback, SessionState.secondScreenMode)
            assertNotEquals(InputLibraries.DECK, routedLayout()?.id)
        }
    }

    private fun customLibrary(desktop: Boolean): InputLibrary {
        session(desktop)
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
        }.also { ReflectionHelpers.callInstanceMethod<Unit>(activity, "refreshInputProfile") }
    }

    private fun routedProfile(): InputProfile = ReflectionHelpers.getField(activity, "inputProfile")

    private fun routedLayout(): SavedInputLayout? = ReflectionHelpers.getField<MutableState<SavedInputLayout?>>(activity, "secondaryInputLayout\$delegate").value

    private fun session(desktop: Boolean) {
        SessionState.mode = if (desktop) SessionService.MODE_DESKTOP else SessionService.MODE_STEAM
        activity.intent = Intent().putExtra(SessionService.EXTRA_MODE, SessionState.mode)
    }

    private fun open(desktop: Boolean, deckPad: Boolean) {
        session(desktop)
        SessionState.deckPad = deckPad
        show(ReflectionHelpers.callInstanceMethod<SecondScreenMode>(activity, "openingPanel"))
    }

    private fun show(mode: SecondScreenMode) =
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "showSecondScreen", ReflectionHelpers.ClassParameter.from(SecondScreenMode::class.java, mode))
}
