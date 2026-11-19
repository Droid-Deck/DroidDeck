package com.droiddeck.launcher.ui

import android.content.Context
import android.app.Presentation
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.*
import com.droiddeck.launcher.input.*
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.SessionService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1000dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputSettingsTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("input-surfaces", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("controller", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun commandSymbolsAreSelectableForAnyCustomButton() {
        val element = mutableStateOf(InputSurfaceElement(InputControl.A, .1f, .1f, .2f, .2f, name = "Custom button"))
        compose.setContent { DroidDeckTheme {
            androidx.compose.foundation.layout.Column { InputElementProperties(element.value) { element.value = it } }
        } }
        var current = context.getString(R.string.input_button_text)
        for (code in listOf(InputButtonIcons.MENU, InputButtonIcons.KEYBOARD, InputButtonIcons.STEAM, InputButtonIcons.QUICK_ACCESS)) {
            compose.onNodeWithText(current).performClick()
            current = InputButtonIcons.label(context, code)
            compose.onNodeWithText(current).performScrollTo().performClick()
            compose.runOnIdle { assertEquals(code, element.value.icon) }
        }
    }

    @Test fun steamAndDesktopQuickSettingsOpenTheSameInputLibrary() {
        val mode = mutableStateOf(SessionService.MODE_STEAM)
        var opened = 0
        compose.setContent { DroidDeckTheme {
            ModeSheetRows(ModeSettings(mode = mode.value, resolution = "auto", hdr = false, hdrReason = null,
                touchMode = "auto", suspendPolicy = "auto", oscMode = "auto", directAudio = null, mic = null),
                ModeSettingsActions(onResolution = {}, onHdr = {}, onTouch = {}, onSuspendPolicy = {}, onOsc = {},
                    onInputSettings = { opened++ }, onDirectAudio = {}, onMic = {}, onDismiss = {}), SheetTab.CONTROLS)
        } }
        compose.onNodeWithText(context.getString(R.string.ctrl_edit)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(1, opened); mode.value = SessionService.MODE_DESKTOP }
        compose.onNodeWithText(context.getString(R.string.ctrl_edit)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(2, opened) }
    }

    @Test fun actionDraftSurvivesRouteAndTypeChanges() {
        var saved: InputAction? = null
        compose.setContent { DroidDeckTheme { InputActionEditor(InputControl.RS, InputAction.Scroll(27f, true), "Right stick", {}, { saved = it }) } }
        compose.onNodeWithText(context.getString(R.string.input_route_controller), substring = false).performClick()
        compose.onNodeWithText(context.getString(R.string.input_disabled), substring = false).performClick()
        compose.onNodeWithText(context.getString(R.string.input_route_local), substring = false).performClick()
        compose.onNodeWithText(context.getString(R.string.input_scroll_speed, 27)).assertExists()
        compose.onNodeWithTag("setting-action-type").performClick()
        compose.onNodeWithText(context.getString(R.string.input_mouse_click)).performClick()
        compose.onNodeWithTag("setting-action-type").performClick()
        compose.onNodeWithText(context.getString(R.string.input_scroll)).performClick()
        compose.onNodeWithTag("input-action-save").performClick()
        compose.runOnIdle { assertEquals(InputAction.Scroll(27f, true), saved) }
    }

    @Test fun bindingEditorPersistsDisabledWithoutChangingTouchControls() {
        var changed = false
        compose.setContent { DroidDeckTheme { InputSettingsPage({}, { changed = true }) } }
        compose.onNodeWithTag("input-profile-game").performScrollTo().performClick()
        compose.onNodeWithTag("input-part-physical").performScrollTo().performClick()
        compose.onNodeWithTag("input-binding-a").performScrollTo().performClick()
        compose.onNodeWithText("Disabled", substring = false).performScrollTo().performClick()
        compose.runOnIdle { assertFalse(changed) }
        compose.onNodeWithTag("input-action-save").performClick()
        compose.runOnIdle {
            assertTrue(changed)
            val profile = InputProfiles.read(context, InputProfile.GAME)
            assertEquals(InputAction.Disabled, profile.action(InputRole.PHYSICAL, InputControl.A))
            assertEquals(InputAction.Native(), profile.action(InputRole.SECONDARY, InputControl.A))
        }
    }

    @Test fun addingCreatesAnUnassignedElementAtTheCanvasCenterWithoutAnotherTouch() {
        var changed = false
        val library = InputLibraries.read(context)
        val layout = library.layout(InputProfile.GAME, InputRole.SECONDARY)!!
        compose.setContent { DroidDeckTheme { InputSurfaceDialog(InputProfile.GAME, {}, { changed = true },
            role = InputRole.PRIMARY, layoutId = layout.id, bindingsId = library.profile(InputProfile.GAME).binding(InputRole.SECONDARY)) } }
        addButton(layout)
        compose.onNodeWithText(context.getString(R.string.input_options)).assertExists()
        compose.onNodeWithText(context.getString(R.string.input_editor_done)).performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(changed)
            val updated = InputLibraries.read(context)
            val added = updated.layout(InputProfile.GAME, InputRole.SECONDARY)!!.elements.single { e -> layout.elements.none { it.id == e.id } }
            assertEquals(InputAction.Disabled, updated.compile(InputProfile.GAME).elementAction(InputRole.SECONDARY, added.id, added.control))
            assertEquals(.5f, added.x + added.width / 2, .01f)
        }
    }

    @Test fun aNewProfileIsIndependentAndDoesNotBecomeActive() {
        compose.setContent { DroidDeckTheme { InputSettingsPage({}) } }
        compose.onNodeWithText(context.getString(R.string.input_new_profile)).performScrollTo().performClick()
        compose.onNodeWithTag("input-name").performTextInput("Minecraft")
        compose.onNodeWithText(context.getString(R.string.gesture_save_changes)).performClick()
        compose.runOnIdle {
            val library = InputLibraries.read(context)
            val created = library.profiles.single { it.name == "Minecraft" }
            for (role in InputRole.entries) assertNotEquals(library.profile(InputProfile.GAME).binding(role), created.binding(role))
            assertNotEquals(library.layout(InputProfile.GAME, InputRole.SECONDARY)!!.id, library.layout(created.id, InputRole.SECONDARY)!!.id)
            assertEquals(InputProfiles.AUTO, library.selected)
        }
    }

    @Test fun openingAProfileDoesNotActivateItAndBackKeepsItsContext() {
        compose.setContent { DroidDeckTheme { InputSettingsPage({}) } }
        compose.onNodeWithTag("input-profile-desktop").performScrollTo().performClick()
        compose.onNodeWithTag("input-part-physical").performScrollTo().performClick()
        compose.onNodeWithTag("input-binding-a").performScrollTo().performClick()
        compose.onNodeWithTag("input-action-save").performClick()
        compose.onNodeWithTag("input-binding-a").assertExists()
        compose.onNodeWithText(context.getString(R.string.mode_back)).performClick()
        compose.onNodeWithTag("input-part-secondary").assertExists()
        compose.runOnIdle { assertEquals(InputProfiles.AUTO, InputLibraries.read(context).selected) }
    }

    @Test fun profileCreationCanExplicitlyShareOnlyPhysicalControls() {
        compose.setContent { DroidDeckTheme { InputSettingsPage({}) } }
        compose.onNodeWithText(context.getString(R.string.input_new_profile)).performScrollTo().performClick()
        compose.onNodeWithTag("input-name").performTextInput("Shared controls")
        compose.onNodeWithTag("input-share-physical").performClick()
        compose.onNodeWithText(context.getString(R.string.gesture_save_changes)).performClick()
        compose.runOnIdle {
            val library = InputLibraries.read(context)
            val created = library.profiles.single { it.name == "Shared controls" }
            assertEquals(library.profile("game").binding(InputRole.PHYSICAL), created.binding(InputRole.PHYSICAL))
            assertNotEquals(library.profile("game").binding(InputRole.SECONDARY), created.binding(InputRole.SECONDARY))
        }
    }

    private val add get() = context.getString(R.string.input_editor_add)

    private fun addButton(layout: SavedInputLayout) {
        compose.onNodeWithText(add).performScrollTo().performClick()
        val clicks = listOf("legacy:l3", "legacy:r3").count { id -> layout.elements.none { it.id == id } }
        compose.onAllNodesWithText(add)[1 + clicks + InputElementKind.entries.indexOf(InputElementKind.BUTTON)].performScrollTo().performClick()
    }

    @Test fun theEditorToolbarStaysAboveTheCanvasAndAddOpensAndClosesItsList() {
        val library = InputLibraries.read(context)
        compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = library.layout("game", InputRole.SECONDARY)!!.id, bindingsId = library.profile("game").binding(InputRole.SECONDARY)) } }
        compose.runOnIdle {
            val window = ShadowDialog.getLatestDialog().window!!
            assertEquals(android.view.WindowManager.LayoutParams.MATCH_PARENT, window.attributes.width)
            assertEquals(android.view.WindowManager.LayoutParams.MATCH_PARENT, window.attributes.height)
        }
        val canvas = compose.onNodeWithTag("input-layout-canvas").fetchSemanticsNode().boundsInRoot
        val tools = compose.onNodeWithTag("input-editor-tools").fetchSemanticsNode().boundsInRoot
        assertTrue(tools.top >= canvas.top && tools.bottom < canvas.center.y)
        val button = context.getString(R.string.input_element_button)
        compose.onNodeWithText(button).assertDoesNotExist()
        compose.onNodeWithText(add).performScrollTo().performClick()
        compose.onNodeWithText(button).assertIsDisplayed()
        compose.onAllNodesWithText(add).onFirst().performScrollTo().performClick()
        compose.onNodeWithText(button).assertDoesNotExist()
        assertEquals(canvas, compose.onNodeWithTag("input-layout-canvas").fetchSemanticsNode().boundsInRoot)
    }

    @Test @Config(qualifiers = "w833dp-h468dp-land-369dpi")
    fun removedKitControlsWaitUnderHiddenAndRestoringThemKeepsTheGamepadGenerated() {
        val library = InputLibraries.read(context)
        val layout = library.layout("game", InputRole.PRIMARY)!!
        val a = InputLibraries.defaultPositions(context, library, layout)!!.first.single { it.id == "legacy:a" }
        compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = layout.id, bindingsId = library.profile("game").binding(InputRole.PRIMARY)) } }
        compose.onNodeWithText(context.getString(R.string.input_hidden_count, 0)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.input_nothing_hidden)).assertExists()
        compose.onNodeWithText(context.getString(R.string.input_hidden_count, 0)).performScrollTo().performClick()
        compose.onNodeWithTag("input-layout-canvas").performTouchInput { click(percentOffset(a.x + a.width / 2, a.y + a.height / 2)) }
        compose.onNodeWithText(context.getString(R.string.input_remove_element)).performClick()
        compose.onNodeWithText(context.getString(R.string.input_hidden_count, 1)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.input_restore)).performClick()
        compose.onNodeWithText(context.getString(R.string.input_hidden_count, 0)).assertExists()
        compose.onNodeWithText(context.getString(R.string.input_editor_done)).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(OnScreenLayout.generated(InputLibraries.read(context).layouts.first { it.id == layout.id })) }
    }

    @Test fun resetAfterResizingMakesTheGamepadFollowTheKitAgain() {
        val library = InputLibraries.read(context)
        val layout = library.layout("game", InputRole.PRIMARY)!!
        compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = layout.id, bindingsId = library.profile("game").binding(InputRole.PRIMARY)) } }
        val reset = compose.onNodeWithText(context.getString(R.string.hud_reset_button))
        reset.performScrollTo().assertIsNotEnabled()
        pressPlus(2)
        reset.performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("input-editor-scale").assertTextEquals("100%")
        reset.performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.input_editor_done)).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(OnScreenLayout.generated(InputLibraries.read(context).layouts.first { it.id == layout.id })) }
    }

    @Test fun resetAndSaveKeepTheDpadDirectionOverrides() {
        val library = InputLibraries.read(context)
        val layout = library.layout("game", InputRole.PRIMARY)!!
        val bindingsId = library.profile("game").binding(InputRole.PRIMARY)
        InputLibraries.update(context) { lib -> lib.preset(bindingsId).let { b -> lib.withPreset(b.copy(elements = b.elements + ("legacy:up" to InputAction.Keys(listOf(103))))) } }
        compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY, layoutId = layout.id, bindingsId = bindingsId) } }
        pressPlus(2)
        compose.onNodeWithText(context.getString(R.string.hud_reset_button)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_done)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(InputAction.Keys(listOf(103)), InputLibraries.read(context).preset(bindingsId).elements["legacy:up"]) }
    }

    @Test fun deckPresetOffersItsRealElementsAndTheCommonEditor() {
        InputLibraries.update(context) { it.useLayout("game", InputRole.SECONDARY, InputLibraries.DECK, shared = true) }
        compose.setContent { DroidDeckTheme { InputSettingsPage({}) } }
        compose.onNodeWithTag("input-profile-game").performScrollTo().performClick()
        compose.onNodeWithTag("input-part-secondary").performScrollTo().performClick()
        compose.onNodeWithTag("input-element-legacy:l4").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("input-binding-a").assertDoesNotExist()
    }

    @Test fun mouseTrackpadPresetIsEditableLikeAnyOtherLayout() {
        InputLibraries.update(context) { it.useLayout("game", InputRole.SECONDARY, InputLibraries.TRACKPAD, shared = true) }
        compose.setContent { DroidDeckTheme { InputSettingsPage({}) } }
        compose.onNodeWithTag("input-profile-game").performScrollTo().performClick()
        compose.onNodeWithTag("input-part-secondary").performScrollTo().performClick()
        compose.onNodeWithTag("input-element-legacy:right-pad").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("input-surface-secondary").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("input-binding-a").assertDoesNotExist()
    }

    @Test fun primaryScreenOffersItsActualElementsAndTheCommonEditor() {
        compose.setContent { DroidDeckTheme { InputSettingsPage({}) } }
        compose.onNodeWithTag("input-profile-game").performScrollTo().performClick()
        compose.onNodeWithTag("input-part-primary").performScrollTo().performClick()
        compose.onNodeWithTag("input-element-legacy:a").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("input-binding-a").assertDoesNotExist()
        compose.onNodeWithTag("input-surface-primary").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).assertIsDisplayed()
        compose.onNodeWithTag("input-layout-canvas").assertExists()
    }

    @Test fun deviceSettingsAreInlineBelowProfiles() {
        compose.setContent { DroidDeckTheme { InputSettingsPage({}) } }
        compose.onNodeWithTag("input-more").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.ctrl_dead_zone)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("input-profile-game").performScrollTo().assertIsDisplayed()
    }

    @Test fun migratedPadClicksRemainEditableWithoutChangingMovement() {
        compose.setContent { DroidDeckTheme { InputSettingsPage({}) } }
        compose.onNodeWithTag("input-profile-desktop").performScrollTo().performClick()
        compose.onNodeWithTag("input-part-secondary").performScrollTo().performClick()
        compose.onNodeWithTag("input-click-legacy:right-pad").performScrollTo().performClick()
        compose.onNodeWithText("Disabled", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("input-action-save").performClick()
        compose.runOnIdle {
            val library = InputLibraries.read(context)
            val pad = library.layout("desktop", InputRole.SECONDARY)!!.elements.first { it.control == InputControl.RIGHT_PAD }
            val profile = library.compile("desktop")
            assertEquals(InputAction.Disabled, profile.elementClickAction(InputRole.SECONDARY, pad))
            assertEquals(InputAction.Pointer(), profile.elementAction(InputRole.SECONDARY, pad.id, pad.control))
        }
    }

    @Test @Config(sdk = [34]) fun secondarySettingsCanOpenAnActivityDialogAndCloseCleanly() {
        val displayId = ShadowDisplayManager.addDisplay("w640dp-h480dp", ReflectionHelpers.getStaticField<Int>(Display::class.java, "TYPE_EXTERNAL"))
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
        val open = mutableStateOf(true)
        try {
            compose.setContent { DroidDeckTheme {
                val host = androidx.compose.ui.platform.LocalContext.current.findActivity() as androidx.activity.ComponentActivity
                if (open.value) InputDisplayContent(host, display, { open.value = false }) { InputSettingsPage({ open.value = false }) }
            } }
            compose.onNodeWithText(context.getString(R.string.input_new_profile)).performScrollTo().performClick()
            compose.onNodeWithTag("input-name").assertExists()
            compose.runOnIdle {
                assertTrue(ShadowDialog.getShownDialogs().filterIsInstance<Presentation>().any { it.isShowing && it.display.displayId == displayId })
                val dialog = ShadowDialog.getShownDialogs().last { it !is Presentation && it.isShowing }
                assertEquals(Display.DEFAULT_DISPLAY, dialog.window?.decorView?.display?.displayId)
                open.value = false
            }
            compose.runOnIdle { assertTrue(ShadowDialog.getShownDialogs().none { it.isShowing }) }
        } finally { ShadowDisplayManager.removeDisplay(displayId) }
    }

    @Test @Config(sdk = [34]) fun unavailableDisplayDismissesSettingsWithoutCrashing() {
        val displayId = ShadowDisplayManager.addDisplay("w640dp-h480dp", ReflectionHelpers.getStaticField<Int>(Display::class.java, "TYPE_EXTERNAL"))
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
        ShadowDisplayManager.removeDisplay(displayId)
        val open = mutableStateOf(true)
        compose.setContent { DroidDeckTheme {
            val host = androidx.compose.ui.platform.LocalContext.current.findActivity() as androidx.activity.ComponentActivity
            if (open.value) InputDisplayContent(host, display, { open.value = false }) { InputSettingsPage({}) }
        } }
        compose.runOnIdle {
            assertFalse(open.value)
            assertTrue(ShadowDialog.getShownDialogs().none { it.isShowing })
        }
    }

    @Test @Config(sdk = [34]) fun mainEditorOpenedFromSecondarySettingsUsesAnActivityWindow() {
        val displayId = ShadowDisplayManager.addDisplay("w640dp-h480dp", ReflectionHelpers.getStaticField<Int>(Display::class.java, "TYPE_EXTERNAL"))
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
        val open = mutableStateOf(true)
        try {
            compose.setContent { DroidDeckTheme {
                val host = androidx.compose.ui.platform.LocalContext.current.findActivity() as androidx.activity.ComponentActivity
                if (open.value) InputDisplayContent(host, display, { open.value = false }) {
                    InputSurfaceDialog("game", { open.value = false }, {}, role = InputRole.PRIMARY)
                }
            } }
            compose.onNodeWithTag("input-layout-canvas").assertExists()
            compose.runOnIdle {
                assertTrue(ShadowDialog.getShownDialogs().none { it is Presentation && it.display.displayId == Display.DEFAULT_DISPLAY })
                val editor = ShadowDialog.getShownDialogs().last { it !is Presentation && it.isShowing }
                assertEquals(Display.DEFAULT_DISPLAY, editor.window?.decorView?.display?.displayId)
                open.value = false
            }
            compose.runOnIdle { assertTrue(ShadowDialog.getShownDialogs().none { it.isShowing }) }
        } finally { ShadowDisplayManager.removeDisplay(displayId) }
    }

    @Test @Config(sdk = [34]) fun auxiliaryEditorUsesThatDisplayAndSurvivesItsRemoval() {
        val displayId = ShadowDisplayManager.addDisplay("w640dp-h480dp", ReflectionHelpers.getStaticField<Int>(Display::class.java, "TYPE_EXTERNAL"))
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
        assertTrue(SecondScreenDisplays.available(context.getSystemService(DisplayManager::class.java)).any { it.id == displayId })
        val previous = SessionState.secondScreenDisplay
        SessionState.secondScreenDisplay = displayId
        val open = mutableStateOf(true)
        try {
            compose.setContent { DroidDeckTheme { if (open.value) InputSurfaceDialog("game", { open.value = false }, {}) } }
            compose.onNodeWithText(context.getString(R.string.input_editor_remote)).assertExists()
            compose.onAllNodesWithText(context.getString(R.string.input_editor_add)).assertCountEquals(1)
            compose.onNodeWithText(context.getString(R.string.input_hide_tools)).assertDoesNotExist()
            assertEquals(0f, compose.onNodeWithTag("input-layout-canvas").fetchSemanticsNode().boundsInRoot.top, 1f)
            compose.runOnIdle {
                assertTrue(ShadowDialog.getShownDialogs().filterIsInstance<Presentation>().any { it.isShowing && it.display.displayId == displayId })
            }
            compose.onNodeWithTag("input-layout-canvas").performTouchInput { click(percentOffset(.25f, .25f)) }
            compose.onAllNodesWithText(context.getString(R.string.input_properties)).onFirst().performClick()
            compose.onNodeWithTag("input-action-save").assertIsDisplayed()
            compose.onNodeWithText("Disabled", substring = false).performScrollTo().performClick()
            compose.runOnIdle {
                assertTrue(ShadowDialog.getShownDialogs().any { it !is Presentation && it.isShowing && it.window?.decorView?.display?.displayId == Display.DEFAULT_DISPLAY })
                ShadowDisplayManager.removeDisplay(displayId)
            }
            compose.onNodeWithTag("input-action-save").assertIsDisplayed()
            compose.onNodeWithTag("input-action-save").performClick()
            compose.onNodeWithText(context.getString(R.string.input_display_unavailable)).assertExists()
            compose.onNodeWithText(context.getString(R.string.input_editor_done)).performScrollTo().performClick()
            compose.runOnIdle {
                assertFalse(open.value)
                assertEquals(InputAction.Disabled, InputProfiles.read(context, "game").elementAction(InputRole.SECONDARY, "legacy:left-pad", InputControl.LEFT_PAD))
            }
        } finally {
            SessionState.secondScreenDisplay = previous
            if (display.isValid) ShadowDisplayManager.removeDisplay(displayId)
        }
    }

    @Test fun elementPropertiesEditOpacityLabelsAndOfferImages() {
        val element = mutableStateOf(InputSurfaceElement(InputControl.A, .1f, .1f, .2f, .2f, "custom", name = "Custom button"))
        val action = mutableStateOf<InputAction?>(null)
        compose.setContent { DroidDeckTheme {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                InputElementProperties(element.value, action.value) { element.value = it }
            }
        } }
        compose.onNodeWithTag("setting-element-opacity").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(60f) }
        compose.runOnIdle { assertEquals(.6f, element.value.opacity, .001f) }
        compose.onNodeWithTag("setting-element-label").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(element.value.showLabel) }
        compose.onNodeWithText(context.getString(R.string.input_button_text)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.screen_key_image)).assertExists()
        compose.runOnIdle { element.value = element.value.copy(icon = InputButtonIcons.MENU) }
        compose.onNodeWithTag("setting-element-label").assertDoesNotExist()
        compose.runOnIdle { element.value = InputSurfaceElement(InputControl.LEFT_PAD, .1f, .1f, .4f, .4f, "pad", InputElementKind.TRACKPAD, "Pad") }
        compose.onNodeWithTag("setting-element-label").assertDoesNotExist()
        compose.onNodeWithTag("setting-element-opacity").assertExists()
        for (kind in listOf(InputElementKind.DPAD, InputElementKind.STICK)) {
            compose.runOnIdle { element.value = InputSurfaceElement(kind.control, .1f, .1f, .4f, .4f, "imported:0", kind, "WASD"); action.value = InputAction.Directions(17, 32, 31, 30) }
            compose.onNodeWithTag("setting-element-label").performScrollTo().performClick()
            compose.runOnIdle { assertFalse(element.value.showLabel); action.value = InputAction.Native() }
            compose.onNodeWithTag("setting-element-label").assertDoesNotExist()
            compose.runOnIdle { action.value = InputAction.Directions(0, 0, 0, 0, listOf(16, 18, 44, 46)) }
            if (kind == InputElementKind.STICK) compose.onNodeWithTag("setting-element-label").assertExists()
            else compose.onNodeWithTag("setting-element-label").assertDoesNotExist()
        }
        compose.runOnIdle { element.value = InputSurfaceElement(InputControl.LS, .1f, .1f, .4f, .4f, "legacy:left-stick", InputElementKind.STICK, "Move") }
        compose.onNodeWithTag("setting-element-label").assertExists()
    }

    @Test fun editorScalesEveryControlAndRestoresStockPositions() {
        val library = InputLibraries.read(context)
        val layout = library.layout("game", InputRole.SECONDARY)!!
        assertEquals(InputLibraries.DECK, layout.id)
        val round = mutableStateOf(0)
        compose.setContent { DroidDeckTheme { androidx.compose.runtime.key(round.value) { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = layout.id, bindingsId = library.profile("game").binding(InputRole.SECONDARY)) } } }
        compose.onNodeWithText(context.getString(R.string.hud_reset_button)).performScrollTo().assertIsNotEnabled()
        repeat(4) { compose.onNodeWithText("\u2212").performScrollTo().performClick() }
        compose.onNodeWithTag("input-editor-scale").assertTextEquals("80%")
        compose.onNodeWithText(context.getString(R.string.input_editor_done)).performScrollTo().performClick()
        val scaled = InputLibraries.read(context).layouts.first { it.id == layout.id }
        assertEquals(layout.elements.scaled(.8f), scaled.elements)
        compose.runOnIdle { round.value = 1 }
        compose.onNodeWithText(context.getString(R.string.hud_reset_button)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_done)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(layout.elements, InputLibraries.read(context).layouts.first { it.id == layout.id }.elements) }
    }

    @Test fun editorSnapChoiceIsRemembered() {
        com.droiddeck.launcher.input.ControllerPrefs.setSnap(context, true)
        val library = InputLibraries.read(context)
        val layout = library.layout("game", InputRole.SECONDARY)!!
        val round = mutableStateOf(0)
        compose.setContent { DroidDeckTheme { androidx.compose.runtime.key(round.value) { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = layout.id, bindingsId = library.profile("game").binding(InputRole.SECONDARY)) } } }
        val snap = isToggleable() and hasAnySibling(hasText(context.getString(R.string.ctrl_editor_snap)))
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNode(snap).performScrollTo().assertIsOn().performClick()
        compose.runOnIdle { assertFalse(com.droiddeck.launcher.input.ControllerPrefs.snap(context)) }
        compose.runOnIdle { round.value = 1 }
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNode(snap).performScrollTo().assertIsOff()
        com.droiddeck.launcher.input.ControllerPrefs.setSnap(context, true)
    }

    @Test fun layoutsCanBeImportedAndExported() {
        val source = java.io.File(context.cacheDir, "jump.icp").apply {
            writeText("""{"name":"Jump keys","elements":[{"type":"BUTTON","shape":"CIRCLE","bindings":["KEY_SPACE"],"scale":1.0,"x":0.8,"y":0.7,"text":"Jump"}]}""")
        }
        val target = java.io.File(context.cacheDir, "out.icp").apply { delete() }
        val registry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I,
                options: androidx.core.app.ActivityOptionsCompat?) {
                dispatchResult(requestCode, android.net.Uri.fromFile(if (input is String) target else source))
            }
        }
        val owner = object : androidx.activity.result.ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent { androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) {
            DroidDeckTheme { InputSettingsPage({}) }
        } }
        compose.onNodeWithTag("input-profile-game").performScrollTo().performClick()
        compose.onNodeWithTag("input-part-primary").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.input_saved_layouts)).performScrollTo().performClick()
        compose.onNodeWithTag("layout-manage-${InputLibraries.OVERLAY}").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.screen_key_layout_export)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.common_close)).performClick()
        compose.onNodeWithTag("input-layout-import").performScrollTo().performClick()
        compose.waitUntil(5_000) { InputLibraries.read(context).layout("game", InputRole.PRIMARY)!!.name == "Jump keys" }
        val imported = InputLibraries.read(context).layout("game", InputRole.PRIMARY)!!
        assertEquals(InputLayoutScreen.MAIN, imported.screen)
        assertEquals(InputAction.Keys(listOf(57)), InputLibraries.read(context).compile("game").elementAction(InputRole.PRIMARY, imported.elements.single().id, InputControl.A))
        compose.onNodeWithText(context.getString(R.string.input_saved_layouts)).performScrollTo().performClick()
        compose.onNodeWithTag("layout-manage-${imported.id}").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.screen_key_layout_export)).performClick()
        compose.waitUntil(5_000) { target.isFile && target.length() > 0 }
        assertEquals(source.readText().let { org.json.JSONObject(it).getString("name") }, org.json.JSONObject(target.readText()).getString("name"))
        assertEquals("KEY_SPACE", org.json.JSONObject(target.readText()).getJSONArray("elements").getJSONObject(0).getJSONArray("bindings").getString(0))
    }

    private fun pressPlus(times: Int) = repeat(times) { compose.onNodeWithText("+").performScrollTo().performClick() }

    @Test fun sizeButtonsScaleTheWholeGamepadAndUndoAsOneStep() {
        val library = InputLibraries.read(context)
        val layout = library.layout("game", InputRole.PRIMARY)!!
        assertTrue(OnScreenLayout.generated(layout))
        val defaults = InputLibraries.defaultPositions(context, library, layout)!!.first
        compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = layout.id, bindingsId = library.profile("game").binding(InputRole.PRIMARY)) } }
        val scale = compose.onNodeWithTag("input-editor-scale")
        scale.assertTextEquals("100%")
        pressPlus(2)
        scale.assertTextEquals("110%")
        compose.onNodeWithText(context.getString(R.string.input_editor_undo)).performScrollTo().performClick()
        scale.assertTextEquals("100%")
        pressPlus(5)
        scale.assertTextEquals("125%")
        compose.onNodeWithText(context.getString(R.string.input_editor_done)).performScrollTo().performClick()
        compose.runOnIdle {
            val saved = InputLibraries.read(context).layouts.first { it.id == layout.id }
            assertFalse(OnScreenLayout.generated(saved))
            assertEquals(defaults.map { it.id }, saved.elements.map { it.id })
            assertTrue(saved.elements.first { it.id == "legacy:a" }.width > defaults.first { it.id == "legacy:a" }.width * 1.2f)
        }
    }

    @Test @Config(qualifiers = "w833dp-h468dp-land-369dpi")
    fun sizeButtonsKeepAMovedFaceClusterInShape() {
        val face = setOf("legacy:a", "legacy:b", "legacy:x", "legacy:y")
        val original = InputLibraries.read(context)
        val gamepad = original.layout("game", InputRole.PRIMARY)!!
        val (defaults, aspect) = InputLibraries.defaultPositions(context, original, gamepad)!!
        val moved = gamepad.copy(elements = defaults.map { if (it.id in face) it.copy(x = it.x + .012f, y = it.y + .017f) else it }, aspect = aspect)
        InputLibraries.update(context) { it.withLayout(moved) }
        val library = InputLibraries.read(context)
        compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = moved.id, bindingsId = library.profile("game").binding(InputRole.PRIMARY)) } }
        pressPlus(5)
        compose.onNodeWithText(context.getString(R.string.input_editor_done)).performScrollTo().performClick()
        compose.runOnIdle {
            val saved = InputLibraries.read(context).layouts.first { it.id == moved.id }
            val w = saved.aspect * 1000; val h = 1000f
            fun centre(id: String) = saved.elements.first { it.id == id }.let { (it.x + it.width / 2) * w to (it.y + it.height / 2) * h }
            val cross = face.associateWith(::centre)
            assertEquals(centre("legacy:y").first, centre("legacy:a").first, 1f)
            assertEquals(centre("legacy:x").second, centre("legacy:b").second, 1f)
            assertTrue("$cross", centre("legacy:y").second < centre("legacy:x").second && centre("legacy:x").second < centre("legacy:a").second)
            assertTrue("$cross", centre("legacy:x").first < centre("legacy:a").first && centre("legacy:a").first < centre("legacy:b").first)
            val before = moved.elements.first { it.id == "legacy:a" }
            assertTrue(saved.elements.first { it.id == "legacy:a" }.width > before.width * 1.2f)
        }
    }
}
