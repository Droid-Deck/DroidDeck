package com.droiddeck.launcher.ui

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import com.droiddeck.launcher.input.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w833dp-h468dp-land-369dpi")
class InputEditorFitTest {
    @get:Rule val looper = MainLooperDrain()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val size get() = InputLibraries.primaryDisplay(context).second.let { it.widthPixels to it.heightPixels }
    private val left get() = Rect((size.first * .05f).toInt(), 0, 0, 0)

    @Before fun reset() {
        for (name in listOf("input-profiles", "input-surfaces", "controller", "session")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun custom() = SavedInputLayout("custom", "Custom", listOf(
        InputSurfaceElement(InputControl.A, .1f, .2f, .1f, .2f, "custom:a"),
        InputSurfaceElement(InputControl.B, .7f, .6f, .1f, .2f, "custom:b"),
        InputSurfaceElement(InputControl.X, .45f, .1f, .1f, .2f, "custom:x")), aspect = 4f / 3f)
    private fun deck() = InputLibraries.read(context).layouts.first { it.id == InputLibraries.DECK }
    private fun library(layout: SavedInputLayout) = InputLibraries.read(context).withLayout(layout)
    private fun editor(layout: SavedInputLayout, safe: Rect) = library(layout).let { library ->
        surfaceDraft(context, library, layout, library.preset(library.profile(InputProfile.GAME).binding(InputRole.PRIMARY)), InputRole.PRIMARY, safe)
    }
    private fun inGame(layout: SavedInputLayout, safe: Rect) = OnScreenLayout.elements(context, layout, size.first, size.second, safe)
    private fun saved(layout: SavedInputLayout, draft: SurfaceDraft) = draft.saveTo(library(layout), null).layouts.first { it.id == layout.id }

    private fun assertSamePlaces(expected: List<InputSurfaceElement>, actual: List<InputSurfaceElement>) {
        assertEquals(expected.map { it.id }, actual.map { it.id })
        val (w, h) = size
        for ((e, a) in expected.zip(actual)) {
            assertEquals("${e.id} x", e.x * w, a.x * w, .5f)
            assertEquals("${e.id} y", e.y * h, a.y * h, .5f)
            assertEquals("${e.id} width", e.width * w, a.width * w, .5f)
            assertEquals("${e.id} height", e.height * h, a.height * h, .5f)
        }
    }

    @Test fun layoutsThatAreNotTheGamepadOpenWhereTheGameDrawsThem() {
        for (layout in listOf(custom(), deck())) for (safe in listOf(Rect(), left)) {
            val draft = editor(layout, safe)
            assertSamePlaces(inGame(layout, safe), draft.elements)
            assertFalse(draft.dirty)
        }
        assertNotEquals(custom().elements.first().width, editor(custom(), Rect()).elements.first().width)
    }

    @Test fun savingKeepsEveryControlWhereTheEditorShowsIt() {
        for (safe in listOf(Rect(), left)) {
            val layout = custom()
            val draft = editor(layout, safe)
            assertSamePlaces(inGame(layout, safe), inGame(saved(layout, draft), safe))
            draft.checkpoint()
            draft.elements = draft.elements.map { if (it.id == "custom:x") it.copy(x = it.x + .05f) else it }
            val again = saved(layout, draft)
            assertSamePlaces(draft.elements, inGame(again, safe))
            assertSamePlaces(draft.elements, editor(again, safe).elements)
            assertSamePlaces(editor(again, safe).elements, editor(saved(again, editor(again, safe)), safe).elements)
        }
    }

    private val rear = InputSurfaceElement(InputControl.L4, .02f, .4f, .08f, .08f)
    private fun portraitSaved(layout: SavedInputLayout, actions: Map<String, InputAction>): Pair<SavedInputLayout, SavedInputBindings> {
        val preset = SavedInputBindings("custom.bindings", "Custom", false, layout.id, elements = actions)
        val library = InputLibraries.update(context) { it.withLayout(layout).withPreset(preset) }
        return library.layouts.first { it.id == layout.id } to library.preset(preset.id)
    }
    private fun editAndSave(layout: SavedInputLayout, preset: SavedInputBindings, edit: SurfaceDraft.() -> Unit): InputLibrary {
        val draft = surfaceDraft(context, InputLibraries.read(context), layout, preset, InputRole.PRIMARY, Rect())
        draft.checkpoint()
        draft.edit()
        InputLibraries.update(context) { draft.saveTo(it, null) }
        return InputLibraries.read(context)
    }
    private fun withRear(removed: Set<String> = emptySet()) = custom().let { base ->
        base.copy(rotated = base.elements + rear, rotatedAspect = 3f / 4f, style = base.style.copy(removed = removed))
    }

    @Test fun savingKeepsAControlDeletedOnlyFromThePortraitFrame() {
        val (layout, preset) = portraitSaved(withRear(setOf(rear.id)), emptyMap())
        assertTrue(OnScreenLayout.oriented(layout, 900, 1600).elements.any { it.id == rear.id })
        val saved = editAndSave(layout, preset) { elements = elements.map { if (it.id == "custom:a") it.copy(opacity = .5f) else it } }
        assertEquals(setOf(rear.id), saved.layouts.first { it.id == layout.id }.style.removed)
    }

    @Test fun savingKeepsTheActionOfAControlOnlyInThePortraitFrame() {
        val jump = InputAction.Keys(listOf(57))
        val (layout, preset) = portraitSaved(withRear(), mapOf(rear.id to jump))
        assertTrue(OnScreenLayout.oriented(layout, 900, 1600).elements.any { it.id == rear.id })
        val saved = editAndSave(layout, preset) { elements = elements.map { if (it.id == "custom:a") it.copy(opacity = .5f) else it } }
        assertEquals(jump, saved.preset(preset.id).elements[rear.id])
    }

    @Test fun deletingAControlInOneFrameDropsItAndItsActionFromBoth() {
        val jump = InputAction.Native(InputControl.B)
        val base = custom()
        val (layout, preset) = portraitSaved(base.copy(rotated = base.elements, rotatedAspect = 3f / 4f), mapOf("custom:x" to jump))
        val saved = editAndSave(layout, preset) { elements = elements.filterNot { it.id == "custom:x" }; actions = actions - "custom:x" }
        val result = saved.layouts.first { it.id == layout.id }
        assertTrue(result.anyFrame.none { it.id == "custom:x" })
        assertNull(saved.preset(preset.id).elements["custom:x"])
    }

    @Test fun sizeChangesOnlyTheSelectedControlOrTheWholeDpad() {
        val lib = InputLibraries.read(context)
        val layout = lib.layout("game", InputRole.PRIMARY)!!
        val draft = editor(layout, Rect())
        val stock = draft.elements.associateBy { it.id }
        draft.select(listOf("legacy:a"))
        draft.resizeSelection(150)
        val one = draft.elements.associateBy { it.id }
        assertEquals(stock.getValue("legacy:a").width * 1.5f, one.getValue("legacy:a").width, .002f)
        for (id in listOf("legacy:b", "legacy:x", "legacy:ls")) assertEquals(id, stock.getValue(id), one.getValue(id))
        val dpad = listOf("legacy:up", "legacy:down", "legacy:left", "legacy:right")
        draft.select(draft.visible.groupOf("legacy:up"))
        assertEquals(dpad.toSet(), draft.selection.toSet())
        val opened = draft.selectionSize!!
        draft.resizeSelection(opened * 3 / 2)
        for (id in dpad) assertEquals(id, stock.getValue(id).height * (opened * 3 / 2) / opened, draft.elements.first { it.id == id }.height, .002f)
    }

    @Test fun resetReturnsToWhereTheGameDrawsTheStockLayout() {
        val layout = deck()
        val draft = editor(layout, Rect())
        val moved = draft.elements.first().id
        draft.checkpoint()
        draft.elements = draft.elements.map { if (it.id == moved) it.copy(x = (it.x + .05f).coerceAtMost(1f - it.width)) else it }
        assertTrue(draft.resettable)
        draft.resetLayout()
        assertSamePlaces(inGame(layout, Rect()), draft.elements)
    }

    @Test fun aGameOnAnotherDisplayOpensWhereThatDisplayDrawsIt() {
        val id = ShadowDisplayManager.addDisplay("w800dp-h600dp-mdpi")
        try {
            val target = context.getSystemService(DisplayManager::class.java).getDisplay(id)
            val layout = custom()
            val draft = library(layout).let { library ->
                surfaceDraft(context, library, layout, library.preset(library.profile(InputProfile.GAME).binding(InputRole.PRIMARY)), InputRole.PRIMARY, target = target)
            }
            val expected = OnScreenLayout.elements(context, layout, 800, 600, Rect())
            assertEquals(expected.map { it.id }, draft.elements.map { it.id })
            for ((e, a) in expected.zip(draft.elements)) {
                assertEquals("${e.id} x", e.x * 800, a.x * 800, .5f)
                assertEquals("${e.id} y", e.y * 600, a.y * 600, .5f)
                assertEquals("${e.id} width", e.width * 800, a.width * 800, .5f)
                assertEquals("${e.id} height", e.height * 600, a.height * 600, .5f)
            }
        } finally { ShadowDisplayManager.removeDisplay(id) }
    }
}
