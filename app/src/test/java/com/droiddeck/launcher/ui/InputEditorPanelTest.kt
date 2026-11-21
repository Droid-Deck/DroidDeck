package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w914dp-h411dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputEditorPanelTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private var dismissed = 0

    @Before fun reset() {
        context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("input-surfaces", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("controller", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun open(): SavedInputLayout {
        val library = InputLibraries.read(context)
        val layout = library.layouts.first { it.id == InputLibraries.OVERLAY }
        compose.setContent { DroidDeckTheme { InputSurfaceDialog(InputProfile.GAME, { dismissed++ }, {}, role = InputRole.PRIMARY, layoutId = layout.id,
            bindingsId = library.profile(InputProfile.GAME).binding(InputRole.PRIMARY)) } }
        return layout
    }

    private fun canvas() = compose.onNodeWithTag("input-layout-canvas").fetchSemanticsNode().boundsInRoot
    private fun panel() = compose.onNodeWithTag("input-editor-panel").fetchSemanticsNode().boundsInRoot
    private fun rect(e: InputSurfaceElement) = canvas().let { c -> Rect(e.x * c.width, e.y * c.height, (e.x + e.width) * c.width, (e.y + e.height) * c.height) }
    private fun tap(e: InputSurfaceElement) = compose.onNodeWithTag("input-layout-canvas").performTouchInput { click(rect(e).center) }
    private fun menu() = compose.onNodeWithTag("input-editor-tools").performClick()
    private fun value(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    private fun slide(tag: String, to: Float) = compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetProgress) { it(to) }
    private fun SavedInputLayout.element(id: String) = elements.first { it.id == id }
    private fun deleteBadge(layout: SavedInputLayout, e: InputSurfaceElement) {
        val badge = editorBadge(context, layout, listOf(e.id), canvas())
        compose.onNodeWithTag("input-layout-canvas").performTouchInput { click(Offset(badge.first, badge.second)) }
    }

    @Test fun theMenuStaysAtTheTopCentreWhateverIsSelected() {
        val layout = open()
        val visible = layout.elements.filterNot { it.id in layout.style.removed }
        val dp = context.resources.displayMetrics.density
        val centre = canvas().center.x
        val home = panel()
        assertEquals(48f * dp, home.width, 1f)
        assertEquals(centre, home.center.x, 1f)
        assertEquals(8f * dp, home.top, 1f)
        for (e in listOf(visible.minBy { it.y }, visible.maxBy { it.y + it.height })) {
            tap(e)
            assertEquals(home, panel())
            menu()
            assertEquals(centre, panel().center.x, 1f)
            assertEquals(home.top + 4f * dp, panel().top, 1f)
            menu()
        }
        menu()
        compose.onNodeWithTag("input-editor-size").performClick()
        compose.onNodeWithTag("input-editor-scale").assertIsDisplayed()
        compose.onNode(hasTestTag("input-selection-size") and hasAnyAncestor(hasTestTag("input-editor-panel"))).assertDoesNotExist()
    }

    @Test fun theSlidersOfASelectionUnderTheMenuKeepClearOfIt() {
        val layout = open()
        val handle = panel()
        val under = layout.elements.filterNot { it.id in layout.style.removed }.minBy { kotlin.math.hypot(it.x + it.width / 2 - .5f, it.y) }
        tap(under)
        fun sliders() = listOf("input-selection-size-panel", "input-selection-opacity-panel")
            .flatMap { compose.onAllNodesWithTag(it).fetchSemanticsNodes() }.map { it.boundsInRoot }
        assertTrue(sliders().isNotEmpty())
        for (s in sliders()) assertFalse("$s $handle", s.overlaps(handle))
        menu()
        val bar = panel()
        for (s in sliders()) assertFalse("$s $bar", s.overlaps(bar))
    }

    @Test fun theOpenMenuIsOneRowInOrder() {
        open()
        menu()
        val order = listOf("input-editor-tools", "input-editor-close", "input-editor-undo", "input-editor-redo", "input-editor-reset",
            "input-editor-snap", "input-editor-size", "input-editor-save").map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        val add = compose.onNodeWithText(context.getString(R.string.input_editor_add)).fetchSemanticsNode().boundsInRoot
        val row = order.take(6) + add + order.drop(6)
        for ((a, b) in row.zipWithNext()) {
            assertTrue("$a $b", a.right <= b.left)
            assertEquals(a.center.y, b.center.y, 1f)
        }
    }

    @Config(qualifiers = "w411dp-h914dp-port")
    @Test fun aNarrowScreenWrapsTheMenuIntoCentredRows() {
        open()
        menu()
        val bar = panel()
        val close = compose.onNodeWithTag("input-editor-close").fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithTag("input-editor-save").fetchSemanticsNode().boundsInRoot
        assertEquals(canvas().center.x, bar.center.x, 1f)
        assertTrue("$close $save", save.top >= close.bottom)
        assertTrue(bar.left >= 0f && bar.right <= canvas().right)
    }

    @Test fun saveSpansTheSnapCheckboxAndItsLabel() {
        open()
        menu()
        compose.waitForIdle()
        val dp = context.resources.displayMetrics.density
        val snap = compose.onNodeWithTag("input-editor-snap").fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithTag("input-editor-save").fetchSemanticsNode().boundsInRoot
        assertEquals(snap.width - 16 * dp, save.width, 1f)
    }

    @Test fun editorButtonsHaveAFullSizeTouchTarget() {
        open()
        compose.onNodeWithTag("input-editor-tools").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        menu()
        for (tag in listOf("input-editor-tools", "input-editor-close", "input-editor-undo", "input-editor-redo", "input-editor-reset"))
            compose.onNodeWithTag(tag).assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("input-editor-snap").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).performClick()
        compose.onNodeWithTag("input-add-close").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
    }

    @Test fun theLayoutSizeChipSaysWhetherItIsOpen() {
        open()
        menu()
        assertEquals(context.getString(R.string.widgets_collapsed), value("input-editor-size"))
        compose.onNodeWithTag("input-editor-size").performClick()
        assertEquals(context.getString(R.string.widgets_expanded), value("input-editor-size"))
    }

    @Test fun selectionSlidersAreThinRowsThatTakeTouchesAcrossAFullTarget() {
        val layout = open()
        tap(layout.element("legacy:start"))
        val opacity = compose.onNodeWithTag("input-selection-opacity")
        opacity.assertHeightIsEqualTo(48.dp).assertWidthIsEqualTo(200.dp)
        assertEquals(15, opacity.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].steps)
        assertEquals(context.getString(R.string.ctrl_opacity), opacity.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single())
        opacity.performTouchInput { swipe(center, Offset(0f, centerY)) }
        assertEquals("20%", value("input-selection-opacity"))
        opacity.performTouchInput { swipe(Offset(0f, centerY), Offset(width - 1f, centerY)) }
        assertEquals("100%", value("input-selection-opacity"))
    }

    @Test fun sizeSitsAboveTheSelectionAndOpacityBelowIt() {
        val layout = open()
        val stick = rect(layout.element("legacy:ls"))
        tap(layout.element("legacy:ls"))
        val size = compose.onNodeWithTag("input-selection-size-panel").fetchSemanticsNode().boundsInRoot
        val opacity = compose.onNodeWithTag("input-selection-opacity-panel").fetchSemanticsNode().boundsInRoot
        assertTrue("$size $stick", size.bottom <= stick.top)
        assertTrue("$opacity $stick", opacity.top >= stick.bottom)
        assertEquals(stick.center.x, size.center.x, 1f)
        assertEquals(stick.center.x, opacity.center.x, 1f)
    }

    @Test fun selectionSlidersStayVisibleStepByFiveAndUndoOneDragAtATime() {
        val layout = open()
        tap(layout.element("legacy:start"))
        compose.onNodeWithTag("input-selection-size").assertIsDisplayed()
        compose.onNodeWithTag("input-selection-opacity").assertIsDisplayed()
        slide("input-selection-opacity", 63f)
        assertEquals("65%", value("input-selection-opacity"))
        slide("input-selection-size", 118f)
        assertEquals("120%", value("input-selection-size"))
        compose.onNodeWithTag("input-selection-size").assertIsDisplayed()
        compose.onNodeWithTag("input-selection-opacity").assertIsDisplayed()
        for (tag in listOf("input-selection-size-panel", "input-selection-opacity-panel"))
            assertFalse(tag, compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.overlaps(rect(layout.element("legacy:start"))))
        compose.onNodeWithTag("input-selection-opacity").performTouchInput {
            swipe(Offset(width * 0.42f, centerY), Offset(width * 0.62f, centerY), 300)
        }
        val dragged = value("input-selection-opacity")
        assertNotEquals("65%", dragged)
        assertEquals(0, dragged.removeSuffix("%").toInt() % 5)
        menu()
        compose.onNodeWithTag("input-editor-undo").performClick()
        assertEquals("65%", value("input-selection-opacity"))
        assertEquals("120%", value("input-selection-size"))
    }

    @Test fun twoDragsOfOneSliderUndoSeparately() {
        val layout = open()
        tap(layout.element("legacy:start"))
        val drag = { to: Float -> compose.onNodeWithTag("input-selection-opacity").performTouchInput {
            swipe(Offset(width * 0.95f, centerY + height * .2f), Offset(width * to, centerY + height * .2f), 300)
        } }
        drag(.6f)
        val first = value("input-selection-opacity")
        assertNotEquals("100%", first)
        drag(.2f)
        assertNotEquals(first, value("input-selection-opacity"))
        menu()
        compose.onNodeWithTag("input-editor-undo").performClick()
        assertEquals(first, value("input-selection-opacity"))
        compose.onNodeWithTag("input-editor-undo").performClick()
        assertEquals("100%", value("input-selection-opacity"))
    }

    @Test fun saveWaitsForAChangeAndUndoRedoFollowHistory() {
        val layout = open()
        menu()
        compose.onNodeWithTag("input-editor-save").assertIsNotEnabled()
        compose.onNodeWithTag("input-editor-undo").assertIsNotEnabled()
        compose.onNodeWithTag("input-editor-redo").assertIsNotEnabled()
        menu()
        tap(layout.element("legacy:start"))
        slide("input-selection-opacity", 50f)
        menu()
        compose.onNodeWithTag("input-editor-save").assertIsEnabled()
        compose.onNodeWithTag("input-editor-undo").assertIsEnabled()
        compose.onNodeWithTag("input-editor-redo").assertIsNotEnabled()
        compose.onNodeWithTag("input-editor-undo").performClick()
        compose.onNodeWithTag("input-editor-redo").assertIsEnabled()
        compose.onNodeWithTag("input-editor-save").assertIsNotEnabled()
        compose.onNodeWithTag("input-editor-redo").performClick()
        compose.onNodeWithTag("input-editor-save").assertIsEnabled()
        compose.onNodeWithTag("input-editor-save").performClick()
        assertEquals(context.getString(R.string.input_editor_saved), ShadowToast.getTextOfLatestToast())
        assertEquals(1, dismissed)
    }

    @Test fun closingWithChangesAsksBeforeDiscarding() {
        val layout = open()
        tap(layout.element("legacy:start"))
        slide("input-selection-opacity", 50f)
        menu()
        compose.onNodeWithTag("input-editor-close").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_discard_title)).assertIsDisplayed()
        compose.onNodeWithText("You have 1 unsaved change to this layout.").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.common_cancel)).performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_discard_title)).assertDoesNotExist()
        assertEquals(0, dismissed)
        compose.onNodeWithTag("input-editor-close").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_discard_confirm)).performClick()
        assertEquals(1, dismissed)
    }

    @Test fun theAddPanelShowsATileForEveryControlAndClosesWithCloseOrBack() {
        open()
        menu()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).performClick()
        compose.onNodeWithTag("input-add-panel").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.input_add_element)).assertIsDisplayed()
        for (choice in inputAddChoices(context, false)) compose.onNodeWithTag("input-add-${choice.key}").assertExists().assertHasClickAction()
        for (name in listOf("LB", "RB", "LT", "RT")) compose.onNodeWithText(name, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("input-add-close").performClick()
        compose.onNodeWithTag("input-add-panel").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).performClick()
        compose.runOnIdle {
            val window = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!
            for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP))
                window.callback.dispatchKeyEvent(android.view.KeyEvent(action, android.view.KeyEvent.KEYCODE_BACK))
        }
        compose.onNodeWithTag("input-add-panel").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).performClick()
        compose.onNodeWithTag("input-add-control-a").performClick()
        compose.onNodeWithTag("input-add-panel").assertDoesNotExist()
        compose.onNodeWithTag("input-selection-opacity-panel").assertExists()
    }

    @Test fun tilesThatWouldOverfillTheLayoutStayVisibleAsDisabledAndSaySo() {
        val full = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { DroidDeckTheme { AddControlPanel(inputAddChoices(context, false), inputAddGroups(context), { !full.value || it.key != "control-a" }, { !full.value },
            InputSurfaceStyle(), {}, {}, {}) } }
        compose.onNodeWithTag("input-add-control-a").assertIsNotEnabled().assertHasClickAction()
        compose.onNodeWithTag("input-add-control-b").assertIsEnabled()
        compose.onNodeWithTag("input-add-full").assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.input_add_full, MaxSurfaceControls))
        full.value = false
        compose.onNodeWithTag("input-add-control-a").assertIsEnabled()
        compose.onNodeWithTag("input-add-full").assertDoesNotExist()
    }

    private fun back() = compose.runOnIdle {
        val window = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!
        for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP))
            window.callback.dispatchKeyEvent(android.view.KeyEvent(action, android.view.KeyEvent.KEYCODE_BACK))
    }

    @Test fun backClearsTheSelectionBeforeLeaving() {
        val layout = open()
        tap(layout.element("legacy:start"))
        compose.onNodeWithTag("input-selection-opacity-panel").assertExists()
        back()
        compose.onNodeWithTag("input-selection-opacity-panel").assertDoesNotExist()
        assertEquals(0, dismissed)
        back()
        assertEquals(1, dismissed)
    }

    @Test fun pickingATileClosesTheAddPanelThroughItsExitAnimation() {
        open()
        menu()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("input-add-control-a").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("input-add-panel").assertExists()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("input-add-panel").assertDoesNotExist()
        compose.onNodeWithTag("input-selection-opacity-panel").assertExists()
    }

    @Test fun deletedStockControlsDoNotCountTowardTheLimit() {
        var fresh = ""
        InputLibraries.update(context) { latest ->
            val l = latest.layouts.first { it.id == InputLibraries.OVERLAY }
            fresh = inputAddChoices(context, false).first { c -> c.stock.none { id -> l.elements.any { it.id == id } } }.key
            val added = (0 until MaxSurfaceControls - l.elements.size).map { i ->
                InputSurfaceElement(InputControl.A, (i % 9) * .1f, .25f + (i / 9) * .1f, .05f, .05f, "added-$i", InputElementKind.BUTTON, "A")
            }
            latest.withLayout(l.copy(elements = l.elements + added, style = l.style.copy(removed = l.elements.map { it.id }.toSet())))
        }
        val layout = open()
        assertEquals(MaxSurfaceControls, layout.elements.size)
        menu()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).performClick()
        compose.onNodeWithTag("input-add-$fresh").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("input-add-panel").assertDoesNotExist()
        menu()
        compose.onNodeWithTag("input-editor-save").performClick()
        assertEquals(1, dismissed)
        val saved = InputLibraries.read(context).layouts.first { it.id == layout.id }
        assertEquals(MaxSurfaceControls + 1, saved.elements.size)
        assertEquals(MaxSurfaceControls - layout.style.removed.size + 1, saved.elements.count { it.id !in saved.style.removed })
    }

    @Test fun theGroupsTabAddsAWholeSet() {
        open()
        menu()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).performClick()
        compose.onNodeWithTag("input-add-tab-buttons").assertIsSelected()
        compose.onNodeWithTag("input-add-control-up").assertExists()
        compose.onNodeWithTag("input-add-tab-groups").performClick()
        compose.onNodeWithTag("input-add-control-up").assertDoesNotExist()
        for (group in inputAddGroups(context)) compose.onNodeWithTag("input-add-${group.key}").assertExists().assertHasClickAction()
        compose.onNodeWithTag("input-add-group-dpad").performClick()
        compose.onNodeWithTag("input-add-panel").assertDoesNotExist()
        compose.onNodeWithTag("input-selection-opacity-panel").assertExists()
    }

    @Test fun deletingShowsAToastAndUndoBringsTheControlBack() {
        val layout = open()
        val start = layout.element("legacy:start")
        tap(start)
        deleteBadge(layout, start)
        compose.onNodeWithTag("input-selection-opacity-panel").assertDoesNotExist()
        assertEquals("${start.name} deleted. Undo is in the menu.", ShadowToast.getTextOfLatestToast())
        menu()
        compose.onNodeWithTag("input-editor-save").assertIsEnabled()
        compose.onNodeWithTag("input-editor-undo").performClick()
        compose.onNodeWithTag("input-editor-save").assertIsNotEnabled()
    }

    @Test fun openingTheEditorShowsTheHintAsAToast() {
        open()
        compose.waitForIdle()
        assertEquals("Tap a control to select it. Drag to move it.", ShadowToast.getTextOfLatestToast())
    }

    @Test fun theEditorAnnouncesItselfAsEditLayout() {
        open()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, context.getString(R.string.input_edit_layout))).assertExists()
    }
}

/** Where the editor draws the delete badge for [selection] on a canvas the size of [canvas]. */
internal fun editorBadge(context: Context, layout: SavedInputLayout, selection: List<String>, canvas: Rect): Pair<Float, Float> =
    InputSurfaceEditorView(context).run {
        style = layout.style.copy(removed = emptySet())
        elements = layout.elements.filterNot { it.id in layout.style.removed }
        measure(android.view.View.MeasureSpec.makeMeasureSpec(canvas.width.toInt(), android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(canvas.height.toInt(), android.view.View.MeasureSpec.EXACTLY))
        layout(0, 0, canvas.width.toInt(), canvas.height.toInt())
        this.selection = selection
        badgeCenter()!!
    }
