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
        elements = layout.elements
        snap = false
    }

    private fun center() = OnScreenLayout.editorElements(context, layout, 1000, 1000).first().let { (it.x + it.width / 2) * 1000 to (it.y + it.height / 2) * 1000 }

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
}
