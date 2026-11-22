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

    @Test fun savingKeepsAControlDeletedOnlyFromThePortraitFrame() {
        val portraitOnly = InputSurfaceElement(InputControl.Y, .4f, .4f, .1f, .1f, "custom:y")
        val base = custom()
        val layout = base.copy(rotated = base.elements + portraitOnly, rotatedAspect = 3f / 4f,
            style = base.style.copy(removed = setOf("custom:y")))
        val draft = editor(layout, Rect())
        draft.checkpoint()
        draft.elements = draft.elements.map { if (it.id == "custom:a") it.copy(opacity = .5f) else it }
        assertEquals(setOf("custom:y"), saved(layout, draft).style.removed)
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
