package com.droiddeck.launcher.ui

import android.content.Context
import android.app.Presentation
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
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
import com.droiddeck.launcher.session.SessionPrefs

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
        // The Steam Deck controller redraws the stock overlay in its own places; these tests check the editor on the plain one.
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_XBOX360)
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
        compose.onNodeWithTag("input-profile-expand").performClick()
        compose.onNodeWithText(context.getString(R.string.ctrl_edit)).performClick()
        compose.runOnIdle { assertEquals(1, opened); mode.value = SessionService.MODE_DESKTOP }
        compose.onNodeWithText(context.getString(R.string.ctrl_edit)).performClick()
        compose.runOnIdle { assertEquals(2, opened) }
    }

    @Test fun quickMenuCombinesPartsWithoutChangingTheSavedProfile() {
        val original = InputLibraries.read(context)
        compose.setContent { DroidDeckTheme {
            androidx.compose.foundation.layout.Column { InputQuickSettings(rememberMenuHost(), desktop = true) }
        } }
        compose.onNodeWithTag("setting-input-profile").assertExists()
        compose.onNodeWithTag("setting-input-part-PHYSICAL").assertDoesNotExist()
        compose.onNodeWithTag("input-profile-expand").performClick()
        compose.onNodeWithTag("setting-input-part-PHYSICAL").assertExists()
        compose.onNodeWithTag("setting-input-part-PRIMARY").assertExists()
        compose.onNodeWithTag("setting-input-part-SECONDARY").performClick()
        compose.onNodeWithText("Deck grips + trackpads").performScrollTo().performClick()
        compose.runOnIdle {
            val changed = InputLibraries.read(context)
            assertEquals(InputStockLayouts.DECK, changed.activePart(true, InputRole.SECONDARY).bindings)
            assertEquals(original.profiles, changed.profiles)
            assertEquals(original.compileActive(false), changed.compileActive(false))
        }
        compose.onNodeWithText(context.getString(R.string.input_save_combination)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Reading")
        compose.onNodeWithText(context.getString(R.string.gesture_save_changes)).performClick()
        compose.runOnIdle {
            val saved = InputLibraries.read(context)
            assertEquals("Reading", saved.profile(saved.activeId(true)).name)
            assertTrue(saved.selection(true).parts.isEmpty())
            assertEquals(original.profile("desktop"), saved.profile("desktop"))
        }
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

    @Test fun addingAControllerButtonFromTheEditorSavesIt() {
        val library = InputLibraries.read(context)
        val layout = library.layout(InputProfile.GAME, InputRole.SECONDARY)!!
        compose.setContent { DroidDeckTheme { InputSurfaceDialog(InputProfile.GAME, {}, {}, role = InputRole.PRIMARY, layoutId = layout.id,
            bindingsId = library.profile(InputProfile.GAME).binding(InputRole.SECONDARY)) } }
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).performClick()
        compose.onNodeWithTag("input-add-control-right-pad").assertExists()
        compose.onNodeWithTag("input-add-control-b").performClick()
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.input_properties)).assertDoesNotExist()
        compose.onNodeWithTag("input-editor-save").performClick()
        compose.runOnIdle {
            val updated = InputLibraries.read(context)
                val added = updated.layout(InputProfile.GAME, InputRole.SECONDARY)!!.elements.single { e -> layout.elements.none { it.id == e.id } }
            assertEquals(InputControl.B.label, added.name)
            assertEquals(InputAction.Native(InputControl.B), updated.compile(InputProfile.GAME).elementAction(InputRole.SECONDARY, added.id, added.control))
        }
    }

    @Test fun aControlAddedInOneProfileWorksInEveryProfileSharingTheLayout() {
        val library = InputLibraries.read(context)
        val layout = library.layouts.first { it.id == InputLibraries.OVERLAY }
        assertEquals(setOf(InputProfile.GAME, InputProfile.DESKTOP), library.usersOfLayout(layout.id).map { it.id }.toSet())
        compose.setContent { DroidDeckTheme { InputSurfaceDialog(InputProfile.GAME, {}, {}, role = InputRole.PRIMARY, layoutId = layout.id,
            bindingsId = library.profile(InputProfile.GAME).binding(InputRole.PRIMARY)) } }
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).performClick()
        compose.onNodeWithTag("input-add-control-b").performClick()
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithTag("input-editor-save").performClick()
        compose.runOnIdle {
            val updated = InputLibraries.read(context)
            val added = updated.layouts.first { it.id == layout.id }.elements.single { e -> layout.elements.none { it.id == e.id } }
            val stock = layout.elements.first { it.id == "legacy:b" }
            for (profile in listOf(InputProfile.GAME, InputProfile.DESKTOP)) {
                val compiled = updated.compile(profile)
                val action = compiled.elementAction(InputRole.PRIMARY, added.id, added.control)
                assertNotEquals(profile, InputAction.Disabled, action)
                assertEquals(profile, compiled.elementAction(InputRole.PRIMARY, stock.id, stock.control).let { if (it == InputAction.Native()) InputAction.Native(InputControl.B) else it }, action)
                assertEquals(profile, action, updated.preset(updated.profile(profile).binding(InputRole.PRIMARY)).action(added))
            }
            assertEquals(InputAction.MouseButton(273), updated.compile(InputProfile.DESKTOP).elementAction(InputRole.PRIMARY, added.id, added.control))
        }
    }

    @Test fun theSelectedControlCarriesItsDeleteBadgeAndSliders() {
        val library = InputLibraries.read(context)
        val layout = library.layouts.first { it.id == InputLibraries.OVERLAY }
        compose.setContent { DroidDeckTheme { InputSurfaceDialog(InputProfile.GAME, {}, {}, role = InputRole.PRIMARY, layoutId = layout.id,
            bindingsId = library.profile(InputProfile.GAME).binding(InputRole.PRIMARY)) } }
        val canvas = compose.onNodeWithTag("input-layout-canvas").fetchSemanticsNode().boundsInRoot
        val start = layout.elements.first { it.id == "legacy:start" }
        val target = androidx.compose.ui.geometry.Rect(start.x * canvas.width, start.y * canvas.height,
            (start.x + start.width) * canvas.width, (start.y + start.height) * canvas.height)
        compose.onNodeWithTag("input-selection-opacity-panel").assertDoesNotExist()
        compose.onNodeWithTag("input-layout-canvas").performTouchInput { click(target.center) }
        compose.onNodeWithTag("input-selection-size").assertExists()
        compose.onNode(hasTestTag("input-selection-size") and hasAnyAncestor(hasTestTag("input-editor-panel"))).assertDoesNotExist()
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithTag("input-editor-redo").assertExists()
        compose.onNodeWithTag("input-remove-selection").assertDoesNotExist()
        compose.onNodeWithTag("input-editor-tools").performClick()
        val badge = editorBadge(context, layout, listOf(start.id), canvas)
        compose.onNodeWithTag("input-layout-canvas").performTouchInput { click(androidx.compose.ui.geometry.Offset(badge.first, badge.second)) }
        compose.onNodeWithTag("input-selection-opacity-panel").assertDoesNotExist()
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithTag("input-editor-save").performClick()
        compose.runOnIdle { assertEquals(setOf("legacy:start"), InputLibraries.read(context).layouts.first { it.id == layout.id }.style.removed) }
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

    @Test fun hidingEditorToolsPreservesPlacedElements() {
        val library = InputLibraries.read(context)
        val layout = library.layout("game", InputRole.SECONDARY)!!
        compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = layout.id, bindingsId = library.profile("game").binding(InputRole.SECONDARY)) } }
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).performClick()
        compose.onNodeWithTag("input-add-control-a").performScrollTo().performClick()
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.input_editor_hide_menu)).performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).assertDoesNotExist()
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithTag("input-editor-save").performClick()
        compose.runOnIdle { assertEquals(layout.elements.size + 1, InputLibraries.read(context).layout("game", InputRole.SECONDARY)!!.elements.size) }
    }

    @Test fun localToolsStayPutAndExpandWithoutMovingTheCanvas() {
        val library = InputLibraries.read(context)
        compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = library.layout("game", InputRole.SECONDARY)!!.id, bindingsId = library.profile("game").binding(InputRole.SECONDARY)) } }
        compose.runOnIdle {
            val window = ShadowDialog.getLatestDialog().window!!
            assertEquals(android.view.WindowManager.LayoutParams.MATCH_PARENT, window.attributes.width)
            assertEquals(android.view.WindowManager.LayoutParams.MATCH_PARENT, window.attributes.height)
        }
        val canvas = compose.onNodeWithTag("input-layout-canvas").fetchSemanticsNode().boundsInRoot
        val before = compose.onNodeWithTag("input-editor-tools").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("input-editor-tools").performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(250f, 150f), 500) }
        val after = compose.onNodeWithTag("input-editor-tools").fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).assertIsDisplayed()
        assertEquals(canvas, compose.onNodeWithTag("input-layout-canvas").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).assertDoesNotExist()
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
        compose.onNodeWithTag("input-editor-tools").performClick()
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
            compose.onNodeWithTag("input-remove-selection").performScrollTo().performClick()
            compose.runOnIdle {
                assertTrue(ShadowDialog.getShownDialogs().any { it !is Presentation && it.isShowing && it.window?.decorView?.display?.displayId == Display.DEFAULT_DISPLAY })
                ShadowDisplayManager.removeDisplay(displayId)
            }
            compose.onNodeWithText(context.getString(R.string.input_display_unavailable)).assertExists()
            compose.onNodeWithText(context.getString(R.string.gesture_save_changes)).performScrollTo().performClick()
            compose.runOnIdle {
                assertFalse(open.value)
                assertEquals(setOf("legacy:left-pad"), InputLibraries.read(context).layout("game", InputRole.SECONDARY)!!.style.removed)
            }
        } finally {
            SessionState.secondScreenDisplay = previous
            if (display.isValid) ShadowDisplayManager.removeDisplay(displayId)
        }
    }

    @Test @Config(sdk = [34]) fun auxiliaryEditorOffersDeleteAndSlidersForAnAddedSet() {
        val displayId = ShadowDisplayManager.addDisplay("w640dp-h480dp", ReflectionHelpers.getStaticField<Int>(Display::class.java, "TYPE_EXTERNAL"))
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
        val previous = SessionState.secondScreenDisplay
        SessionState.secondScreenDisplay = displayId
        try {
            compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", {}, {}) } }
            compose.onNodeWithText(context.getString(R.string.input_editor_add)).performClick()
            compose.onNodeWithTag("input-add-tab-groups").performClick()
            compose.onNodeWithTag("input-add-group-dpad").performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.input_group_dpad)).assertExists()
            compose.onNodeWithTag("input-remove-selection").assertExists()
            compose.onNodeWithTag("input-selection-size").assertExists()
            compose.onNodeWithTag("input-selection-opacity").assertExists()
        } finally {
            SessionState.secondScreenDisplay = previous
            ShadowDisplayManager.removeDisplay(displayId)
        }
    }

    @Test @Config(sdk = [34]) fun auxiliaryEditorUndoesTwoDragsOfOneSliderSeparately() {
        val displayId = ShadowDisplayManager.addDisplay("w640dp-h480dp", ReflectionHelpers.getStaticField<Int>(Display::class.java, "TYPE_EXTERNAL"))
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
        val previous = SessionState.secondScreenDisplay
        SessionState.secondScreenDisplay = displayId
        try {
            compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", {}, {}) } }
            compose.onNodeWithTag("input-layout-canvas").performTouchInput { click(percentOffset(.25f, .25f)) }
            val opacity = { compose.onNodeWithTag("input-selection-opacity").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription] }
            val drag = { to: Float -> compose.onNodeWithTag("input-selection-opacity").performScrollTo().performTouchInput {
                val y = bottom - 20.dp.toPx()
                swipe(androidx.compose.ui.geometry.Offset(width * .95f, y), androidx.compose.ui.geometry.Offset(width * to, y), 300)
            } }
            assertEquals("100%", opacity())
            drag(.6f)
            val first = opacity()
            assertNotEquals("100%", first)
            drag(.2f)
            assertNotEquals(first, opacity())
            compose.onNodeWithTag("input-editor-undo").performScrollTo().performClick()
            assertEquals(first, opacity())
        } finally {
            SessionState.secondScreenDisplay = previous
            ShadowDisplayManager.removeDisplay(displayId)
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
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithTag("input-editor-reset").assertIsNotEnabled()
        scaleSlider().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(80f) }
        compose.onNodeWithTag("input-editor-save").performClick()
        val scaled = InputLibraries.read(context).layouts.first { it.id == layout.id }
        assertEquals(layout.elements.scaled(.8f), scaled.elements)
        compose.runOnIdle { round.value = 1 }
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithTag("input-editor-reset").performClick()
        compose.onNodeWithTag("input-editor-save").performClick()
        compose.runOnIdle { assertEquals(layout.elements, InputLibraries.read(context).layouts.first { it.id == layout.id }.elements) }
    }

    private fun stockDraft(id: String, role: InputRole): SurfaceDraft {
        val library = InputLibraries.read(context)
        val layout = library.layouts.first { it.id == id }
        val (display, metrics) = InputLibraries.primaryDisplay(context)
        val gamepad = layout.elements.takeIf { e -> e.any { it.id.startsWith("legacy:") } }?.let {
            val (w, h) = OnScreenLayout.generatedSize(context, layout, metrics.widthPixels, metrics.heightPixels) ?: (metrics.widthPixels to metrics.heightPixels)
            GamepadScaler(display, w, h)
        }
        return SurfaceDraft(layout, library.preset(library.profile("game").binding(role)), InputLibraries.defaultPositions(context, library, layout)?.first, gamepad)
    }

    @Test fun resetReturnsToTheStockLayoutExactlyAsOneUndoStep() {
        for ((id, role) in listOf(InputLibraries.OVERLAY to InputRole.PRIMARY, InputLibraries.DECK to InputRole.SECONDARY)) {
            val draft = stockDraft(id, role)
            assertFalse(id, draft.resettable)
            val stock = draft.elements
            draft.select(listOf(stock.first().id)); draft.setSelectionOpacity(50)
            assertTrue(id, draft.resettable)
            draft.resetLayout()
            assertEquals(id, stock, draft.elements)
            draft.addOne(choice("control-a"))
            draft.add(group("group-face"), 16f / 9f)
            val stockId = stock.first { it.id.startsWith("legacy:") }.id
            draft.select(listOf(stockId))
            draft.checkpoint(); draft.elements = draft.elements.moveSelection(listOf(stockId), .05f, .05f)
            draft.resizeSelection(130)
            draft.setSelectionOpacity(40)
            draft.scaleAll(120)
            draft.select(listOf(stock.last().id)); draft.deleteSelection()
            assertTrue(id, draft.resettable)
            val edited = draft.state()
            val steps = draft.history.size
            draft.resetLayout()
            assertEquals(id, stock, draft.elements)
            assertEquals(id, emptySet<String>(), draft.removed)
            assertEquals(id, 100, draft.scale)
            assertEquals(id, draft.preset.elements, draft.actions)
            assertEquals(id, draft.preset.clicks, draft.clicks)
            assertFalse(id, draft.dirty)
            assertFalse(id, draft.resettable)
            assertEquals(id, steps + 1, draft.history.size)
            draft.undo()
            assertEquals(id, edited, draft.state())
        }
    }

    @Test fun discardingTheEditorRestoresSnapAndKeepsTheSavedLayout() {
        com.droiddeck.launcher.input.ControllerPrefs.setSnap(context, true)
        val library = InputLibraries.read(context)
        val layout = library.layout("game", InputRole.SECONDARY)!!
        var dismissed = false
        compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", { dismissed = true }, {}, role = InputRole.PRIMARY,
            layoutId = layout.id, bindingsId = library.profile("game").binding(InputRole.SECONDARY)) } }
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNode(hasTestTag("input-editor-snap") and isToggleable()).performClick()
        compose.runOnIdle { assertFalse(com.droiddeck.launcher.input.ControllerPrefs.snap(context)) }
        scaleSlider().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(80f) }
        compose.onNodeWithTag("input-editor-close").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_discard_confirm)).performClick()
        compose.runOnIdle {
            assertTrue(dismissed)
            assertTrue(com.droiddeck.launcher.input.ControllerPrefs.snap(context))
            val saved = InputLibraries.read(context).layouts.first { it.id == layout.id }
            assertEquals(layout.elements, saved.elements)
            assertEquals(layout.scale, saved.scale)
        }
    }

    @Test fun editorSnapChoiceIsRemembered() {
        com.droiddeck.launcher.input.ControllerPrefs.setSnap(context, true)
        val library = InputLibraries.read(context)
        val layout = library.layout("game", InputRole.SECONDARY)!!
        val round = mutableStateOf(0)
        compose.setContent { DroidDeckTheme { androidx.compose.runtime.key(round.value) { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = layout.id, bindingsId = library.profile("game").binding(InputRole.SECONDARY)) } } }
        val snap = hasTestTag("input-editor-snap") and isToggleable()
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNode(snap).assertIsOn().performClick()
        compose.runOnIdle { assertFalse(com.droiddeck.launcher.input.ControllerPrefs.snap(context)) }
        compose.runOnIdle { round.value = 1 }
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNode(snap).assertIsOff()
        com.droiddeck.launcher.input.ControllerPrefs.setSnap(context, true)
    }

    @Test fun editorAddsRemovedButtonsBackWhereTheyWere() {
        val library = InputLibraries.read(context)
        val layout = library.layout("game", InputRole.PRIMARY)!!
        val removed = setOf("builtin:keyboard", "legacy:guide")
        InputLibraries.update(context) { it.withLayout(layout.copy(style = layout.style.copy(removed = removed))) }
        compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = layout.id, bindingsId = library.profile("game").binding(InputRole.PRIMARY)) } }
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).performClick()
        compose.onNodeWithTag("input-add-control-guide").performScrollTo().performClick()
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).performClick()
        compose.onNodeWithTag("input-add-control-keyboard").performScrollTo().performClick()
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithTag("input-editor-save").performClick()
        compose.runOnIdle {
            val after = InputLibraries.read(context).layout("game", InputRole.PRIMARY)!!
            assertEquals(layout.elements, after.elements)
            assertEquals(emptySet<String>(), after.style.removed)
        }
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

    @Test fun undoAfterAddingBackOnlyReversesTheNextScaleChange() {
        val library = InputLibraries.read(context)
        val layout = library.layout("game", InputRole.PRIMARY)!!
        InputLibraries.update(context) { it.withLayout(layout.copy(style = layout.style.copy(removed = setOf("builtin:keyboard", "legacy:guide")))) }
        compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = layout.id, bindingsId = library.profile("game").binding(InputRole.PRIMARY)) } }
        compose.onNodeWithTag("input-editor-tools").performClick()
        val slider = scaleSlider()
        slider.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(110f) }
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).performClick()
        compose.onNodeWithTag("input-add-control-guide").performScrollTo().performClick()
        compose.onNodeWithTag("input-editor-tools").performClick()
        scaleSlider()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(120f) }
        compose.onNodeWithTag("input-editor-undo").performClick()
        compose.onNodeWithTag("input-editor-save").performClick()
        compose.runOnIdle {
            val saved = InputLibraries.read(context).layouts.first { it.id == layout.id }
            assertEquals(1.1f, saved.scale)
            assertEquals(setOf("builtin:keyboard"), saved.style.removed)
        }
    }

    @Test fun scaleAfterAddingBackKeepsItsMeaningAndUndoesOneStep() {
        val library = InputLibraries.read(context)
        val layout = library.layouts.first { it.id == InputLibraries.DECK }
        val hidden = layout.elements.first().id
        InputLibraries.update(context) { it.withLayout(layout.copy(style = layout.style.copy(removed = setOf(hidden)))) }
        compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = layout.id, bindingsId = library.profile("game").binding(InputRole.SECONDARY)) } }
        compose.onNodeWithTag("input-editor-tools").performClick()
        val percent = androidx.compose.ui.semantics.SemanticsProperties.StateDescription
        scaleSlider()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(110f) }
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).performClick()
        compose.onNodeWithTag("input-add-control-left-pad").performScrollTo().performClick()
        compose.onNodeWithTag("input-editor-tools").performClick()
        scaleSlider().assert(SemanticsMatcher.expectValue(percent, "110%"))
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(120f) }
        compose.onNodeWithTag("input-editor-save").performClick()
        compose.runOnIdle {
            val saved = InputLibraries.read(context).layouts.first { it.id == layout.id }
            assertEquals(layout.elements.scaled(1.2f), saved.elements)
            assertEquals(emptySet<String>(), saved.style.removed)
        }
        compose.onNodeWithTag("input-editor-undo").performClick()
        scaleSlider().assert(SemanticsMatcher.expectValue(percent, "110%"))
        compose.onNodeWithTag("input-editor-save").performClick()
        compose.runOnIdle {
            val saved = InputLibraries.read(context).layouts.first { it.id == layout.id }
            assertEquals(layout.elements.scaled(1.1f), saved.elements)
            assertEquals(emptySet<String>(), saved.style.removed)
        }
    }

    private fun scaleSlider(): SemanticsNodeInteraction {
        if (compose.onAllNodesWithTag("input-editor-scale").fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("input-editor-size").performClick()
        return compose.onNodeWithTag("input-editor-scale")
    }

    private fun deckDraft(): Pair<SurfaceDraft, String> {
        val library = InputLibraries.read(context)
        val layout = library.layouts.first { it.id == InputLibraries.DECK }
        val draft = SurfaceDraft(layout, library.preset(library.profile("game").binding(InputRole.SECONDARY)))
        val id = "legacy:l4"
        draft.select(listOf(id))
        return draft to id
    }

    private fun overlayDraft(): SurfaceDraft {
        val library = InputLibraries.read(context)
        return SurfaceDraft(library.layouts.first { it.id == InputLibraries.OVERLAY }, library.preset(library.profile("game").binding(InputRole.PRIMARY)))
    }

    private fun choice(key: String) = inputAddChoices(context, trackpads = true).first { it.key == key }

    private fun group(key: String) = inputAddGroups(context).first { it.key == key }

    private fun SurfaceDraft.addOne(choice: InputAddChoice): InputSurfaceElement {
        val before = elements.map { it.id }.toSet()
        add(choice, 16f / 9f)
        return elements.single { it.id !in before }.also { assertEquals(listOf(it.id), selection) }
    }

    private fun bounds(e: InputSurfaceElement) = SurfaceBounds(e.x, e.y, e.x + e.width, e.y + e.height)

    private fun SurfaceBounds.overlaps(o: SurfaceBounds) = left < o.right && o.left < right && top < o.bottom && o.top < bottom

    @Test fun anUntouchedSecondScreenControlReadsAsFullSize() {
        val library = InputLibraries.read(context)
        val stock = InputStockLayouts.layouts().first { it.id == DeckPanel.GAMEPAD.layout }
        val layout = library.layouts.firstOrNull { it.id == stock.id } ?: stock
        val defaults = InputLibraries.defaultPositions(context, library, layout)!!.first
        val gamepad = GamepadScaler(context, 1920, 1080)
        val draft = SurfaceDraft(layout, library.preset(library.profile("game").binding(InputRole.SECONDARY)), defaults, gamepad)
        val shared = layout.elements.map { it.id }.filter { it in gamepad.geometry && defaults.any { d -> d.id == it } }
        assertTrue(shared.isNotEmpty())
        for (id in shared) {
            draft.select(listOf(id))
            assertEquals(id, 100, draft.selectionSize)
        }
    }

    @Test fun addingAControllerInputCreatesOneBoundAndLabelledControl() {
        val draft = overlayDraft()
        val cases = listOf(
            Triple(choice("control-a"), InputAction.Native(InputControl.A), "A"),
            Triple(choice("control-lt"), InputAction.Native(InputControl.LT), "LT"),
            Triple(choice("control-ls"), InputAction.Native(InputControl.LS), InputControl.LS.label),
            Triple(choice("control-up"), InputAction.Native(InputControl.UP), InputControl.UP.label),
            Triple(choice("control-guide"), InputAction.Native(InputControl.GUIDE), InputControl.GUIDE.label),
            Triple(choice("control-right-pad"), InputAction.Native(InputControl.RIGHT_PAD), InputControl.RIGHT_PAD.label),
        )
        for ((choice, action, name) in cases) {
            val added = draft.addOne(choice)
            assertEquals(choice.key, name, added.name)
            assertEquals(choice.key, choice.kind, added.kind)
            assertEquals(choice.key, choice.control, added.control)
            assertNull(choice.key, draft.actions[added.id])
            assertTrue(choice.key, action.validFor(added))
            assertEquals(choice.key, action, draft.preset.copy(elements = draft.actions).action(added))
        }
        assertEquals(0, draft.addOne(choice("control-up")).icon)
        assertEquals(listOf("a", "b", "x", "y", "up", "down", "left", "right", "ls", "rs", "lb", "rb", "lt", "rt", "l3", "r3", "l4", "r4", "l5", "r5", "select", "start", "guide", "qam", "keyboard",
            "left-pad", "right-pad"), inputAddChoices(context, trackpads = true).map { it.key.removePrefix("control-") })
        assertTrue(inputAddChoices(context, trackpads = false).none { it.kind == InputElementKind.TRACKPAD })
    }

    @Test fun anAddedCopyFollowsTheProfilesRemapOfTheStockControl() {
        val library = InputLibraries.read(context)
        val layout = library.layouts.first { it.id == InputLibraries.OVERLAY }
        val preset = library.preset(library.profile(InputProfile.GAME).binding(InputRole.PRIMARY))
        val remapped = preset.copy(elements = preset.elements + ("legacy:a" to InputAction.Keys(listOf(57))))
        val draft = SurfaceDraft(layout, remapped)
        val added = draft.addOne(choice("control-a"))
        assertNull(draft.actions[added.id])
        assertEquals(InputAction.Keys(listOf(57)), remapped.copy(elements = draft.actions).action(added))
        val saved = library.withLayout(layout.copy(elements = draft.elements)).withPreset(remapped.copy(elements = draft.actions)).validate()
        assertEquals(InputAction.Keys(listOf(57)), saved.compile(InputProfile.GAME).elementAction(InputRole.PRIMARY, added.id, added.control))
        val own = saved.withPreset(saved.preset(remapped.id).let { it.copy(elements = it.elements + (added.id to InputAction.Native(InputControl.B))) }).validate()
        assertEquals(InputAction.Native(InputControl.B), own.compile(InputProfile.GAME).elementAction(InputRole.PRIMARY, added.id, added.control))
    }

    private fun render(element: InputSurfaceElement): android.graphics.Bitmap {
        val view = InputSurfaceView(context, customLayout = listOf(element), haptics = false)
        val spec = android.view.View.MeasureSpec.makeMeasureSpec(160, android.view.View.MeasureSpec.EXACTLY)
        view.measure(spec, spec)
        view.layout(0, 0, 160, 160)
        return android.graphics.Bitmap.createBitmap(160, 160, android.graphics.Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
    }

    @Test fun addedControlsAndTheirAddTilesLookLikeTheStockControls() {
        val draft = overlayDraft()
        val stock = draft.elements.associateBy { it.id }
        fun InputSurfaceElement.at(b: SurfaceBounds) = copy(x = b.left, y = b.top, width = b.width, height = b.height)
        val box = SurfaceBounds(.1f, .1f, .9f, .9f)
        var compared = 0
        for (choice in inputAddChoices(context, trackpads = true)) {
            val added = draft.addOne(choice)
            val tile = choice.preview()
            assertTrue(choice.key, render(tile).sameAs(render(added.at(SurfaceBounds(tile.x, tile.y, tile.x + tile.width, tile.y + tile.height)))))
            val original = stock[choice.stock.first()] ?: continue
            assertTrue(choice.key, render(original.at(box)).sameAs(render(added.at(box))))
            compared++
        }
        assertEquals(19, compared)
    }

    @Test fun anAddedStickFollowsTheLayoutsAdaptiveSticksAndLook() {
        for (adaptive in listOf(true, false)) {
            val library = InputLibraries.read(context)
            val overlay = library.layouts.first { it.id == InputLibraries.OVERLAY }
            val layout = overlay.copy(elements = overlay.elements.map { if (it.kind == InputElementKind.STICK) it.copy(floating = adaptive, hideWhenIdle = adaptive) else it })
            val draft = SurfaceDraft(layout, library.preset(library.profile(InputProfile.GAME).binding(InputRole.PRIMARY)))
            val stick = draft.addOne(choice("control-rs"))
            assertEquals(adaptive, stick.floating)
            assertEquals(adaptive, stick.hideWhenIdle)
            assertEquals(adaptive, layout.copy(elements = draft.elements).adaptiveSticks)
        }
        for (adaptive in listOf(true, false)) {
            val empty = SurfaceDraft(SavedInputLayout("empty", "Empty"), SavedInputBindings("empty", "Empty", false, "empty"), adaptiveSticks = adaptive)
            val stick = empty.addOne(choice("control-ls"))
            assertEquals(adaptive, stick.floating && stick.hideWhenIdle)
            assertEquals(adaptive, SavedInputLayout("empty", "Empty", empty.elements).adaptiveSticks)
        }
        val library = InputLibraries.read(context)
        val deck = library.layouts.first { it.id == DeckPanel.GAMEPAD.layout }.let { it.copy(template = InputLayoutTemplate.CUSTOM) }
        val draft = SurfaceDraft(deck, library.preset(DeckPanel.GAMEPAD.bindings))
        assertEquals(deck.elements.first { it.id == "legacy:ls" }.buttonShape, draft.addOne(choice("control-rs")).buttonShape)
    }

    @Test fun anAddedControlLandsInFreeSpaceAndDuplicatesAreAllowed() {
        val draft = overlayDraft()
        val first = draft.addOne(choice("control-a"))
        val second = draft.addOne(choice("control-a"))
        assertTrue(draft.visible.any { it.id == "legacy:a" })
        assertEquals(draft.actions[first.id], draft.actions[second.id])
        assertFalse(bounds(first).overlaps(bounds(second)))
        for (other in draft.visible.filter { it.id != first.id && it.id != second.id }) assertFalse(other.id, bounds(first).overlaps(bounds(other)))
    }

    @Test fun addingADeletedStockInputRestoresItInsteadOfAddingAnother() {
        val draft = overlayDraft()
        val original = draft.elements
        val guide = original.first { it.id == "legacy:guide" }
        draft.select(listOf(guide.id)); draft.deleteSelection()
        assertTrue(draft.visible.none { it.id == guide.id })
        draft.add(choice("control-guide"), 16f / 9f)
        assertEquals(original, draft.elements)
        assertEquals(listOf(guide.id), draft.selection)
        assertTrue(guide.id !in draft.removed)
        val dpad = original.filter { it.id in listOf("legacy:up", "legacy:right", "legacy:down", "legacy:left") }.map { it.id }
        draft.select(dpad); draft.deleteSelection()
        draft.add(choice("control-up"), 16f / 9f)
        assertEquals(original, draft.elements)
        assertEquals(listOf("legacy:up"), draft.selection)
        assertEquals(dpad - "legacy:up", dpad.filter { it in draft.removed })
        draft.add(group("group-dpad"), 16f / 9f)
        assertEquals(original, draft.elements)
        assertTrue(dpad.none { it in draft.removed })
        assertEquals(dpad.toSet(), draft.selection.toSet())
        val (deck, grip) = deckDraft()
        val stock = deck.elements
        deck.deleteSelection()
        assertTrue(deck.visible.none { it.id == grip })
        deck.add(choice("control-l4"), 16f / 9f)
        assertEquals(stock, deck.elements)
        assertEquals(listOf(grip), deck.selection)
        assertTrue(grip !in deck.removed)
        val added = deck.addOne(choice("control-r5"))
        assertEquals(InputAction.Native(InputControl.R5), deck.preset.copy(elements = deck.actions).action(added))
        assertTrue("builtin:keyboard" !in deck.preset.elements)
        val keyboard = deck.addOne(choice("control-keyboard"))
        assertEquals(InputAction.Command(InputCommand.KEYBOARD), deck.preset.copy(elements = deck.actions).action(keyboard))
    }

    @Test fun theKeyboardButtonCanBeRestoredOrAddedAgain() {
        val draft = overlayDraft()
        val original = draft.elements
        assertTrue(original.any { it.id == "builtin:keyboard" })
        val keyboard = choice("control-keyboard")
        draft.select(listOf("builtin:keyboard")); draft.deleteSelection()
        assertTrue(draft.visible.none { it.id == "builtin:keyboard" })
        draft.add(keyboard, 16f / 9f)
        assertEquals(original, draft.elements)
        assertEquals(listOf("builtin:keyboard"), draft.selection)
        assertTrue(draft.visible.any { it.id == "builtin:keyboard" })
        val another = draft.addOne(keyboard)
        assertEquals(InputAction.Command(InputCommand.KEYBOARD), draft.preset.copy(elements = draft.actions).action(another))
        assertEquals(InputButtonIcons.KEYBOARD, another.icon)
        assertEquals(context.getString(R.string.input_add_keyboard), another.name)
        assertTrue(InputAction.Command(InputCommand.KEYBOARD).validFor(another))
    }

    @Test fun addingAGroupAddsANewSetLaidOutLikeTheStockOneAsOneUndoStep() {
        val draft = overlayDraft()
        assertEquals(listOf("group-dpad", "group-face", "group-left-shoulder", "group-right-shoulder"), inputAddGroups(context).map { it.key })
        val before = draft.elements
        draft.add(group("group-face"), 16f / 9f)
        val added = draft.elements.filter { e -> before.none { it.id == e.id } }
        assertEquals(4, added.size)
        assertEquals(added.map { it.id }, draft.selection)
        assertEquals(listOf(InputControl.Y, InputControl.B, InputControl.A, InputControl.X).map { InputAction.Native(it) }, added.map { draft.preset.copy(elements = draft.actions).action(it) })
        val (y, b, a, x) = added
        val stockA = before.first { it.id == "legacy:a" }
        assertEquals(stockA.width, a.width, 1e-5f)
        assertEquals(stockA.height, a.height, 1e-5f)
        assertEquals(y.x, a.x, 1e-5f)
        assertTrue(y.y < a.y && x.x < b.x)
        assertEquals(x.y, b.y, 1e-5f)
        assertTrue(added.none { e -> before.any { bounds(it).overlaps(bounds(e)) } })
        draft.undo()
        assertEquals(before, draft.elements)
        draft.add(group("group-left-shoulder"), 16f / 9f)
        val (lt, lb) = draft.elements.drop(before.size)
        assertEquals(listOf("LT", "LB"), listOf(lt.name, lb.name))
        assertTrue(lt.x < lb.x)
        assertEquals(InputButtonShape.PILL, lt.buttonShape)
    }

    @Test fun undoAndRedoKeepAnAddedSetSelected() {
        val draft = overlayDraft()
        draft.add(group("group-dpad"), 16f / 9f)
        val set = draft.selection
        assertEquals(4, set.size)
        draft.setSelectionOpacity(50)
        draft.undo()
        assertEquals(set, draft.selection)
        draft.redo()
        assertEquals(set, draft.selection)
        draft.select(listOf("legacy:up"))
        draft.select(draft.visible.groupOf("legacy:up"))
        draft.setSelectionOpacity(50)
        draft.undo()
        assertEquals(draft.visible.groupOf("legacy:up"), draft.selection)
    }

    @Test fun addedControlsAreAlwaysStoredInBounds() {
        for (aspect in listOf(16f / 9f, 2.37f, 20f, .45f)) {
            val draft = overlayDraft()
            for (key in listOf("control-a", "control-ls", "control-lt", "control-keyboard")) repeat(4) {
                val before = draft.visible.size
                val room = draft.canAdd
                draft.add(choice(key), aspect)
                if (room) assertEquals("$aspect $key", before + 1, draft.visible.size)
            }
            for (key in listOf("group-dpad", "group-face", "group-left-shoulder")) {
                val before = draft.visible.size
                val room = draft.available(group(key))
                draft.add(group(key), aspect)
                if (room) assertEquals("$aspect $key", before + group(key).members.size, draft.visible.size)
            }
            assertTrue("$aspect", draft.elements.size > overlayDraft().elements.size)
            for (e in draft.elements) assertEquals("$aspect ${e.name}", e.bounded(), e)
        }
    }

    @Test fun aSetWithoutStockGeometryShrinksToFitANarrowScreen() {
        val library = InputLibraries.read(context)
        val layout = library.layouts.first { it.id == InputLibraries.TRACKPAD }
        for (aspect in listOf(.3f, 16f / 9f)) {
            val draft = SurfaceDraft(layout, library.preset(library.profile("game").binding(InputRole.SECONDARY)))
            draft.add(group("group-face"), aspect)
            val set = draft.elements.filter { it.id in draft.selection }
            assertEquals(4, set.size)
            for (e in set) {
                assertEquals(e.bounded(), e)
                assertTrue("$aspect $e", e.x >= 0f && e.y >= 0f && e.x + e.width <= 1f + 1e-5f && e.y + e.height <= 1f + 1e-5f)
            }
            val (y, b, a, x) = set
            assertTrue(y.y < a.y && x.x < b.x)
        }
    }

    @Test fun aSelectionIsNamedAfterItsGroupOrItsSize() {
        val draft = overlayDraft()
        draft.select(draft.visible.groupOf("legacy:a"))
        assertEquals(context.getString(R.string.input_group_face), draft.selectionName(context))
        draft.add(group("group-dpad"), 16f / 9f)
        assertEquals(InputControlGroup.DPAD, draft.selectionGroup)
        assertEquals(context.getString(R.string.input_group_dpad), draft.selectionName(context))
        draft.add(group("group-left-shoulder"), 16f / 9f)
        assertEquals(context.getString(R.string.input_group_left_shoulder), draft.selectionName(context))
        val shoulders = draft.selection
        draft.select(listOf(shoulders.first()))
        assertEquals("LT", draft.selectionName(context))
        val one = draft.addOne(choice("control-a")).id
        val two = draft.addOne(choice("control-a")).id
        draft.select(listOf(one, two))
        assertNull(draft.selectionGroup)
        assertEquals("2 controls", draft.selectionName(context))
    }

    @Test fun anAddedGroupStaysAGroupLikeAStockOne() {
        val draft = overlayDraft()
        draft.add(group("group-dpad"), 16f / 9f)
        val set = draft.selection
        assertEquals(4, set.size)
        assertEquals(1, draft.elements.filter { it.id in set }.map { it.group }.distinct().size)
        assertTrue(draft.elements.first { it.id == set[0] }.group.startsWith("DPAD:"))
        draft.select(emptyList())
        val tapped = draft.visible.tapSelection(emptyList(), set[2])
        assertEquals(set, tapped)
        assertEquals(InputControlGroup.DPAD, draft.visible.selectedGroup(tapped))
        assertEquals(listOf(set[2]), draft.visible.tapSelection(tapped, set[2]))
        draft.select(tapped)
        val before = draft.elements.filter { it.id in set }
        draft.resizeSelection(120)
        draft.setSelectionOpacity(50)
        val after = draft.elements.filter { it.id in set }
        assertTrue(before.zip(after).all { (b, a) -> a.width > b.width && a.opacity == .5f })
        draft.undo()
        assertEquals(set, draft.selection)
        draft.undo()
        assertEquals(set, draft.selection)
        assertEquals(before, draft.elements.filter { it.id in set })
        draft.redo()
        assertEquals(set, draft.selection)
        draft.select(listOf(set[0])); draft.deleteSelection()
        assertEquals(set.drop(1), draft.visible.groupOf(set[1]))
        draft.select(draft.visible.tapSelection(emptyList(), set[3]))
        assertEquals(set.drop(1), draft.selection)
        draft.deleteSelection()
        assertTrue(draft.elements.none { it.id in set })
        assertTrue(set.none { it in draft.actions })
    }

    @Test fun twoAddedSetsOfTheSameGroupAreSeparateGroups() {
        val draft = overlayDraft()
        draft.add(group("group-face"), 16f / 9f)
        val first = draft.selection
        draft.add(group("group-face"), 16f / 9f)
        val second = draft.selection
        assertTrue(first.none { it in second })
        assertEquals(first, draft.visible.groupOf(first[0]))
        assertEquals(second, draft.visible.groupOf(second[0]))
        val single = draft.addOne(choice("control-a"))
        assertEquals("", single.group)
        assertEquals(listOf(single.id), draft.visible.groupOf(single.id))
    }

    @Test fun aGroupThatDoesNotFitTheLimitCannotBeAdded() {
        val draft = overlayDraft()
        while (draft.elements.size < 61) draft.addOne(choice("control-a"))
        assertFalse(draft.available(group("group-dpad")))
        assertTrue(draft.available(group("group-left-shoulder")))
        val full = draft.elements
        draft.add(group("group-dpad"), 16f / 9f)
        assertEquals(full, draft.elements)
        draft.add(group("group-left-shoulder"), 16f / 9f)
        assertEquals(63, draft.elements.size)
        assertFalse(draft.available(group("group-right-shoulder")))
        draft.select(listOf("legacy:rb")); draft.deleteSelection()
        assertTrue(draft.available(group("group-right-shoulder")))
        draft.add(group("group-right-shoulder"), 16f / 9f)
        assertTrue(draft.visible.any { it.id == "legacy:rb" })
        assertEquals(63, draft.elements.size)
    }

    @Test fun aDeletedStockControlComesBackOnlyWhenThereIsRoomForIt() {
        val draft = overlayDraft()
        while (draft.visible.size < 64) draft.addOne(choice("control-a"))
        assertFalse(draft.canAdd)
        draft.select(listOf("legacy:guide")); draft.deleteSelection()
        assertTrue(draft.canAdd)
        assertTrue(draft.available(choice("control-guide")))
        draft.add(choice("control-guide"), 16f / 9f)
        assertTrue(draft.visible.any { it.id == "legacy:guide" })
        assertEquals(64, draft.visible.size)
        draft.select(listOf("legacy:guide")); draft.deleteSelection()
        draft.select(listOf("legacy:rb")); draft.deleteSelection()
        repeat(2) { draft.addOne(choice("control-a")) }
        assertEquals(64, draft.visible.size)
        assertFalse(draft.canAdd)
        assertFalse(draft.available(choice("control-guide")))
        assertFalse(draft.available(group("group-right-shoulder")))
        val full = draft.elements
        draft.add(choice("control-guide"), 16f / 9f)
        draft.add(group("group-right-shoulder"), 16f / 9f)
        assertEquals(full, draft.elements)
        assertEquals(64, draft.visible.size)
    }

    @Test fun deletingAndUndoingBringsBackStockAndAddedControls() {
        val draft = overlayDraft()
        val added = draft.addOne(choice("control-x"))
        draft.deleteSelection()
        assertTrue(draft.elements.none { it.id == added.id })
        assertNull(draft.actions[added.id])
        draft.undo()
        assertEquals(added, draft.elements.single { it.id == added.id })
        assertEquals(InputAction.Native(InputControl.X), draft.preset.copy(elements = draft.actions).action(added))
        draft.select(listOf("legacy:b")); draft.deleteSelection()
        assertTrue("legacy:b" in draft.removed)
        assertTrue(draft.elements.any { it.id == "legacy:b" })
        draft.undo()
        assertFalse("legacy:b" in draft.removed)
    }

    private fun SurfaceDraft.state() = listOf(elements, actions, clicks, removed, layoutScale, scale, selectionSize, selectionOpacity)

    @Test fun redoRestoresEveryUndoneStepExactly() {
        val (draft, id) = deckDraft()
        val states = mutableListOf(draft.state())
        draft.select(listOf("legacy:r4")); draft.deleteSelection(); draft.select(listOf(id)); states += draft.state()
        draft.resizeSelection(120); states += draft.state()
        draft.checkpoint(); draft.actions = draft.actions + (id to InputAction.Keys(listOf(57))); states += draft.state()
        draft.setSelectionOpacity(60); states += draft.state()
        draft.scaleAll(110); draft.scaleAll(115); states += draft.state()
        for (i in states.indices.reversed().drop(1)) { draft.undo(); assertEquals(states[i], draft.state()) }
        assertTrue(draft.history.isEmpty())
        for (i in 1 until states.size) { draft.redo(); assertEquals(states[i], draft.state()) }
        assertTrue(draft.redoable.isEmpty())
    }

    @Test fun aNewEditAfterUndoClearsRedo() {
        val (draft, id) = deckDraft()
        draft.resizeSelection(120)
        draft.undo()
        assertEquals(1, draft.redoable.size)
        draft.setSelectionOpacity(50)
        assertTrue(draft.redoable.isEmpty())
        draft.redo()
        assertEquals(.5f, draft.selectionOpacity, .001f)
        assertEquals(100, draft.selectionSize)
        draft.undo(); draft.undo()
        assertEquals(id, draft.selected)
    }

    @Test fun redoAcrossAScaleSliderStepStartsAFreshSliderSession() {
        val (draft, _) = deckDraft()
        val start = draft.elements
        draft.scaleAll(110); draft.scaleAll(120)
        val scaled = draft.elements
        draft.undo()
        assertEquals(100, draft.scale)
        assertEquals(start, draft.elements)
        draft.redo()
        assertEquals(120, draft.scale)
        assertEquals(scaled, draft.elements)
        draft.scaleAll(115)
        assertTrue(draft.redoable.isEmpty())
        draft.undo()
        assertEquals(120, draft.scale)
        assertEquals(scaled, draft.elements)
        draft.undo()
        assertEquals(100, draft.scale)
        assertEquals(start, draft.elements)
    }

    @Test fun resizeAfterAnActionChangeIsItsOwnUndoStep() {
        val (draft, id) = deckDraft()
        val jump = InputAction.Keys(listOf(57))
        draft.resizeSelection(120)
        draft.checkpoint(); draft.actions = draft.actions + (id to jump)
        draft.resizeSelection(140)
        assertEquals(140, draft.selectionSize)
        draft.undo()
        assertEquals(120, draft.selectionSize)
        assertEquals(jump, draft.actions[id])
        draft.undo()
        assertEquals(120, draft.selectionSize)
        assertNotEquals(jump, draft.actions[id])
        draft.undo()
        assertEquals(100, draft.selectionSize)
    }

    @Test fun opacityAfterAnActionChangeIsItsOwnUndoStep() {
        val (draft, id) = deckDraft()
        val jump = InputAction.Keys(listOf(57))
        val opacity = draft.selectionOpacity
        assertNotEquals(.7f, opacity, .001f)
        draft.setSelectionOpacity(80)
        draft.setSelectionOpacity(70)
        draft.checkpoint(); draft.actions = draft.actions + (id to jump)
        draft.setSelectionOpacity(50)
        draft.undo()
        assertEquals(.7f, draft.selectionOpacity, .001f)
        assertEquals(jump, draft.actions[id])
        draft.undo()
        assertEquals(.7f, draft.selectionOpacity, .001f)
        assertNotEquals(jump, draft.actions[id])
        draft.undo()
        assertEquals(opacity, draft.selectionOpacity, .001f)
    }

    @Test fun buttonSizeRegeneratesTheGamepadAndUndoesAsOneStep() {
        val library = InputLibraries.read(context)
        val layout = library.layout("game", InputRole.PRIMARY)!!
        val (_, metrics) = InputLibraries.primaryDisplay(context)
        assertNotNull(OnScreenLayout.generatedSize(context, layout, metrics.widthPixels, metrics.heightPixels))
        compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = layout.id, bindingsId = library.profile("game").binding(InputRole.PRIMARY)) } }
        compose.onNodeWithTag("input-editor-tools").performClick()
        val slider = scaleSlider()
        slider.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "100%"))
        for (value in listOf(110f, 125f)) slider.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(value) }
        compose.onNodeWithTag("input-editor-undo").performClick()
        slider.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "100%"))
        slider.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(125f) }
        compose.onNodeWithTag("input-editor-save").performClick()
        compose.runOnIdle {
            val saved = InputLibraries.read(context).layouts.first { it.id == layout.id }
            assertEquals(1.25f, saved.scale)
            assertNotNull(OnScreenLayout.generatedSize(context, saved, metrics.widthPixels, metrics.heightPixels))
            assertNotEquals(layout.elements.first { it.id == "legacy:a" }.y, saved.elements.first { it.id == "legacy:a" }.y)
        }
    }

    @Test @Config(qualifiers = "w833dp-h468dp-land-369dpi")
    fun buttonSizeKeepsMovedGamepadGroupsInShape() {
        val face = setOf("legacy:a", "legacy:b", "legacy:x", "legacy:y")
        val original = InputLibraries.read(context)
        val gamepad = original.layout("game", InputRole.PRIMARY)!!
        val moved = gamepad.copy(elements = gamepad.elements.map { if (it.id in face) it.copy(x = it.x + .012f, y = it.y + .017f) else it })
        InputLibraries.update(context) { it.withLayout(moved) }
        val (_, metrics) = InputLibraries.primaryDisplay(context)
        assertNull(OnScreenLayout.generatedSize(context, moved, metrics.widthPixels, metrics.heightPixels))
        val library = InputLibraries.read(context)
        compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = moved.id, bindingsId = library.profile("game").binding(InputRole.PRIMARY)) } }
        compose.onNodeWithTag("input-editor-tools").performClick()
        scaleSlider().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(125f) }
        compose.onNodeWithTag("input-editor-save").performClick()
        compose.runOnIdle {
            val saved = InputLibraries.read(context).layouts.first { it.id == moved.id }
            assertEquals(1.25f, saved.scale)
            val w = metrics.widthPixels; val h = metrics.heightPixels
            fun centre(id: String) = saved.elements.first { it.id == id }.let { (it.x + it.width / 2) * w to (it.y + it.height / 2) * h }
            assertEquals(centre("legacy:y").first, centre("legacy:a").first, 1f)
            assertEquals(centre("legacy:x").second, centre("legacy:b").second, 1f)
            assertTrue(centre("legacy:y").second < centre("legacy:x").second && centre("legacy:x").second < centre("legacy:a").second)
            assertEquals(centre("legacy:a").second - centre("legacy:y").second, centre("legacy:b").first - centre("legacy:x").first, 1f)
            assertEquals(centre("legacy:up").first, centre("legacy:down").first, 1f)
            assertEquals(centre("legacy:left").second, centre("legacy:right").second, 1f)
            val before = moved.elements.first { it.id == "legacy:a" }
            assertTrue(saved.elements.first { it.id == "legacy:a" }.width > before.width * 1.2f)
        }
    }
}
