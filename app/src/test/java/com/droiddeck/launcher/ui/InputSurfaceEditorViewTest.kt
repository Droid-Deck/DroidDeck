package com.droiddeck.launcher.ui

import android.view.MotionEvent
import com.droiddeck.launcher.input.InputControl
import com.droiddeck.launcher.input.InputSurfaceElement
import com.droiddeck.launcher.input.OnScreenLayout
import com.droiddeck.launcher.input.SavedInputLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class InputSurfaceEditorViewTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val layout = SavedInputLayout("custom", "Custom", listOf(InputSurfaceElement(InputControl.A, .1f, .2f, .2f, .2f)), aspect = 2f)

    private fun view() = InputSurfaceEditorView(context).apply {
        layout(0, 0, 1000, 1000)
        sourceLayout = layout
        aspect = layout.aspect
        elements = layout.elements
        snap = false
    }

    private fun center(target: SavedInputLayout = layout, width: Int = 1000, height: Int = 1000) =
        OnScreenLayout.editorElements(context, target, width, height).first().let { (it.x + it.width / 2) * width to (it.y + it.height / 2) * height }

    private fun InputSurfaceEditorView.touch(action: Int, x: Float, y: Float) {
        MotionEvent.obtain(0, 10, action, x, y, 0).also { onTouchEvent(it); it.recycle() }
    }

    @Test fun editingStartsInTheDisplayedFrame() {
        val view = view()
        var started: Pair<List<InputSurfaceElement>, Float>? = null
        view.onBeginEdit = { elements, aspect -> started = elements to aspect }
        val (x, y) = center()
        view.touch(MotionEvent.ACTION_DOWN, x, y)
        view.touch(MotionEvent.ACTION_MOVE, x + 100f, y + 50f)
        val (elements, aspect) = requireNotNull(started)
        assertEquals(1f, aspect, .0001f)
        assertEquals(OnScreenLayout.editorElements(context, layout, 1000, 1000), elements)
    }

    @Test fun undoRestoresTheSavedFrameForHitTesting() {
        val view = view()
        var selection: String? = null
        view.onSelect = { selection = it }
        view.elements = layout.elements.map { it.copy(x = .5f) }
        view.elements = layout.elements
        val (x, y) = center()
        view.touch(MotionEvent.ACTION_DOWN, x, y)
        assertEquals("legacy:a", selection)
    }

    @Test fun editorFramePreservesAddedAndRemovedDraftElements() {
        val added = layout.elements + InputSurfaceElement(InputControl.B, .6f, .2f, .2f, .2f, id = "added")
        val removed = layout.copy(elements = emptyList())
        assertEquals(added, OnScreenLayout.editorFrame(layout.copy(elements = added), added))
        assertEquals(emptyList<InputSurfaceElement>(), OnScreenLayout.editorFrame(removed, emptyList()))
    }

    @Test fun savedAspectRemainsStableForRenamesAndResizes() {
        val view = view()
        var selection: String? = null
        view.onSelect = { selection = it }
        val renamed = layout.elements.map { it.copy(name = "Renamed") }
        view.elements = renamed
        view.aspect = 2f
        val (rx, ry) = center(layout.copy(elements = renamed, aspect = 2f), 1000, 1000)
        view.touch(MotionEvent.ACTION_DOWN, rx, ry)
        assertEquals("legacy:a", selection)
        selection = null
        view.layout(0, 0, 800, 1000)
        val (lx, ly) = center(layout.copy(elements = renamed, aspect = 2f), 800, 1000)
        view.touch(MotionEvent.ACTION_DOWN, lx, ly)
        assertEquals("legacy:a", selection)
    }

    @Test fun resetPositionsRestoresDefaultCoordinatesAndReferenceAspect() {
        val library = com.droiddeck.launcher.input.InputLibraries.read(context)
        val deck = library.layouts.first { it.id == com.droiddeck.launcher.input.InputLibraries.DECK }
        val preset = library.bindings.first { it.layoutId == deck.id }
        val draft = SurfaceDraft(deck, preset, com.droiddeck.launcher.input.InputLibraries.defaultPositions(context, library, deck),
            defaultAspect = com.droiddeck.launcher.input.InputLibraries.defaultAspect(context, library, deck))
        val displayed = OnScreenLayout.editorElements(context, deck, 1000, 1000)
        draft.beginEdit(displayed.map { if (it.control == InputControl.L4) it.copy(y = .60f, height = .15f) else it }, 1f)
        assertEquals(1f, draft.aspect, .0001f)
        draft.resetPositions()
        assertEquals(deck.aspect, draft.aspect, .0001f)
        val l4 = draft.elements.first { it.control == InputControl.L4 }
        assertEquals(.78f, l4.y, .0001f)
        assertEquals(.20f, l4.height, .0001f)
    }
}
