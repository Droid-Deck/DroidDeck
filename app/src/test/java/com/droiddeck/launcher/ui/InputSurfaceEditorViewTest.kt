package com.droiddeck.launcher.ui

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.droiddeck.launcher.input.InputControl
import com.droiddeck.launcher.input.InputLibraries
import com.droiddeck.launcher.input.InputSurfaceElement
import com.droiddeck.launcher.input.OnScreenLayout
import com.droiddeck.launcher.input.PadKit
import com.droiddeck.launcher.input.SavedInputLayout
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class InputSurfaceEditorViewTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val layout = SavedInputLayout("custom", "Custom", listOf(InputSurfaceElement(InputControl.A, .1f, .2f, .2f, .2f)), aspect = 2f)

    @Before fun reset() {
        for (name in listOf("input-profiles", "input-surfaces", "controller", "session")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun restoreSession() {
        SessionState.deckPad = false
        SessionState.mode = SessionService.MODE_STEAM
    }

    private fun view() = InputSurfaceEditorView(context).apply {
        place(1000, 1000)
        sourceLayout = layout
        aspect = layout.aspect
        elements = layout.elements
        snap = false
    }

    private fun center() = OnScreenLayout.editorElements(context, layout, 1000, 1000).first().let { (it.x + it.width / 2) * 1000 to (it.y + it.height / 2) * 1000 }
    private fun overlay() = InputLibraries.read(context).layouts.first { it.id == InputLibraries.OVERLAY }

    private fun editor(layout: SavedInputLayout, w: Int, h: Int) = InputSurfaceEditorView(context).apply {
        sourceLayout = layout
        aspect = layout.aspect
        elements = layout.elements
        snap = false
        place(w, h)
    }

    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(target)
    private fun InputSurfaceEditorView.controls() = (field(field(this, "preview")!!, "customCanvas") as ViewGroup).let { canvas -> (0 until canvas.childCount).map(canvas::getChildAt) }
    private fun InputSurfaceEditorView.rects() = controls().map { listOf(it.left, it.top, it.width, it.height) }

    private fun InputSurfaceEditorView.assertControlsDrawnWhereDisplayed() {
        val shown = rendered()
        val views = controls()
        assertEquals(shown.size, views.size)
        for ((e, v) in shown.zip(views)) {
            assertEquals(e.id, e.x * width, v.left.toFloat(), 1f)
            assertEquals(e.id, e.y * height, v.top.toFloat(), 1f)
            assertEquals(e.id, e.width * width, v.width.toFloat(), 1f)
            assertEquals(e.id, e.height * height, v.height.toFloat(), 1f)
        }
    }

    private fun InputSurfaceEditorView.touch(action: Int, x: Float, y: Float) {
        MotionEvent.obtain(0, 10, action, x, y, 0).also { onTouchEvent(it); it.recycle() }
    }

    private fun InputSurfaceEditorView.mouse(action: Int, x: Float, y: Float) {
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f }
        val buttons = if (action == MotionEvent.ACTION_UP) 0 else MotionEvent.BUTTON_PRIMARY
        MotionEvent.obtain(0, 10, action, 1, arrayOf(properties), arrayOf(coords), 0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            .also { dispatchTouchEvent(it); it.recycle() }
    }

    private fun InputSurfaceEditorView.centre(id: String) = rendered().first { it.id == id }.let { (it.x + it.width / 2) * width to (it.y + it.height / 2) * height }

    @Test fun editingStartsInTheDisplayedFrame() {
        val view = view()
        var started: Pair<List<InputSurfaceElement>, Float>? = null
        view.onBeginEdit = { elements, w, h -> started = elements to w.toFloat() / h }
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
        val renamed = layout.elements.map { it.copy(name = "Renamed") }
        view.elements = renamed
        view.aspect = 2f
        assertEquals(OnScreenLayout.editorElements(context, layout.copy(elements = renamed, aspect = 2f), 1000, 1000), view.rendered())
        view.place(800, 1000)
        assertEquals(OnScreenLayout.editorElements(context, layout.copy(elements = renamed, aspect = 2f), 800, 1000), view.rendered())
    }

    @Test fun theOverlayEditorDrawsTheKitArtworkWhereTheSessionPlacesIt() {
        val layout = overlay()
        assertNotNull(PadKit.forOverlay(context, layout))
        val view = editor(layout, 2400, 1504)
        assertEquals(OnScreenLayout.editorElements(context, layout, 2400, 1504), view.rendered())
        view.assertControlsDrawnWhereDisplayed()
        assertEquals(4, view.artworkDpad().size)
    }

    @Test fun copiesOfTheOverlayDrawTheSameKitArtworkInTheSamePlaces() {
        val source = overlay()
        val copy = source.derived("copy", "Copy")
        val again = copy.derived("again", "Again")
        for (layout in listOf(copy, again)) {
            assertEquals(PadKit.forOverlay(context, source), PadKit.forOverlay(context, layout))
            for ((w, h) in listOf(2400 to 1504, 1504 to 2400)) {
                assertEquals(OnScreenLayout.editorElements(context, source, w, h),
                    OnScreenLayout.editorElements(context, layout, w, h))
            }
            assertEquals(4, editor(layout, 2400, 1504).artworkDpad().size)
        }
        assertNotNull(PadKit.forOverlay(context, source.copy(id = "migrated.layout.2400x1504")))
        assertEquals(null, PadKit.forOverlay(context, this.layout))
        assertEquals(null, PadKit.forOverlay(context, this.layout.derived("custom-copy", "Copy")))
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_NONE)
        assertEquals(null, PadKit.forOverlay(context, copy))
    }

    @Config(qualifiers = "xhdpi")
    @Test fun controlsAddedForTheScreenAreDrawnOnTheFirstFrameInBothOrientations() {
        SessionState.mode = SessionService.MODE_STEAM
        SessionState.deckPad = true
        SessionPrefs.setOscRearButtons(context, true)
        SessionPrefs.setOscTrackpads(context, true)
        val layout = overlay()
        for ((w, h) in listOf(2400 to 1504, 1504 to 2400)) {
            val view = editor(layout, w, h)
            assertTrue(view.rendered().size > layout.elements.size)
            view.assertControlsDrawnWhereDisplayed()
            assertEquals(4, view.artworkDpad().size)
        }
    }

    @Test fun displayedControlsStayPutAcrossRepeatedRefreshesAndResizes() {
        val view = editor(overlay(), 2400, 1504)
        val first = view.rendered()
        val drawn = view.rects()
        repeat(3) {
            view.elements = view.elements
            view.aspect = view.aspect
            view.sourceLayout = view.sourceLayout
            view.place(1504, 2400)
            view.assertControlsDrawnWhereDisplayed()
            view.place(2400, 1504)
            assertEquals(first, view.rendered())
            assertEquals(drawn, view.rects())
            assertEquals(4, view.artworkDpad().size)
        }
    }

    @Test fun dpadPiecesRedrawWhenTheyLeaveOrRejoinTheKitCross() {
        val view = editor(overlay(), 2400, 1504)
        val stock = view.rendered()
        view.aspect = 2400f / 1504
        val pieces = view.controls().filter { (it.tag as InputSurfaceElement).id in setOf("legacy:up", "legacy:right", "legacy:down", "legacy:left") }
        assertEquals(4, pieces.size)
        for ((moved, expected) in listOf(stock.map { if (it.id == "legacy:up") it.copy(y = it.y - .2f) else it } to 0, stock to 4)) {
            pieces.forEach { shadowOf(it).clearWasInvalidated() }
            view.elements = moved
            view.place(2400, 1504)
            assertEquals(expected, view.artworkDpad().size)
            pieces.forEach { assertTrue((it.tag as InputSurfaceElement).id, shadowOf(it).wasInvalidated()) }
        }
    }

    @Test fun draggingMovesTheTouchedControlByTheDragDistance() {
        val view = editor(overlay(), 2400, 1504)
        val a = view.rendered().first { it.id == "legacy:a" }
        val changes = mutableListOf<InputSurfaceElement>()
        view.onChange = { changes += it }
        val (x, y) = view.centre(a.id)
        view.touch(MotionEvent.ACTION_DOWN, x, y)
        view.touch(MotionEvent.ACTION_MOVE, x - 240f, y - 150f)
        view.touch(MotionEvent.ACTION_UP, x - 240f, y - 150f)
        val moved = changes.last()
        assertEquals(a.id, moved.id)
        assertEquals(a.x - 240f / 2400, moved.x, .0001f)
        assertEquals(a.y - 150f / 1504, moved.y, .0001f)
        assertEquals(a.width, moved.width, .0001f)
    }

    @Test fun theDpadMovesAndResizesAsOneControlFromAnyArrowOrItsCentre() {
        val view = editor(overlay(), 2400, 1504)
        val arrows = listOf("legacy:up", "legacy:right", "legacy:down", "legacy:left")
        fun pad() = view.rendered().filter { it.id in arrows }.associateBy { it.id }
        val before = pad()
        assertEquals(4, view.artworkDpad().size)
        val changes = mutableListOf<InputSurfaceElement>()
        view.onBeginEdit = { shown, w, h -> view.aspect = w.toFloat() / h; view.elements = shown }
        view.onChange = { changes += it; view.elements = view.elements.map { e -> it.firstOrNull { c -> c.id == e.id } ?: e } }
        val left = before.getValue("legacy:left"); val right = before.getValue("legacy:right")
        val x = (left.x + left.width + right.x) / 2 * 2400; val y = (left.y + left.height / 2) * 1504
        view.touch(MotionEvent.ACTION_DOWN, x, y)
        view.touch(MotionEvent.ACTION_MOVE, x + 240f, y - 150f)
        view.touch(MotionEvent.ACTION_UP, x + 240f, y - 150f)
        val moved = changes.takeLast(4).associateBy { it.id }
        assertEquals(arrows.toSet(), moved.keys)
        for (id in arrows) {
            assertEquals(id, before.getValue(id).x + 240f / 2400, moved.getValue(id).x, .0001f)
            assertEquals(id, before.getValue(id).y - 150f / 1504, moved.getValue(id).y, .0001f)
        }
        assertEquals(4, view.artworkDpad().size)
        val down = moved.getValue("legacy:down"); val r = moved.getValue("legacy:right")
        val cx = (r.x + r.width) * 2400; val cy = (down.y + down.height) * 1504
        view.touch(MotionEvent.ACTION_DOWN, cx, cy)
        view.touch(MotionEvent.ACTION_MOVE, cx + 80f, cy + 80f)
        view.touch(MotionEvent.ACTION_UP, cx + 80f, cy + 80f)
        val resized = changes.takeLast(4).associateBy { it.id }
        assertEquals(arrows.toSet(), resized.keys)
        assertTrue(resized.getValue("legacy:up").width > moved.getValue("legacy:up").width)
        assertEquals(4, view.artworkDpad().size)
    }

    @Test fun shrinkingTheDpadStopsBeforeAnyArrowIsSmallerThanASingleControlCanBe() {
        val view = editor(overlay(), 2400, 1504)
        val arrows = listOf("legacy:up", "legacy:right", "legacy:down", "legacy:left")
        val pad = view.rendered().filter { it.id in arrows }
        val changes = mutableListOf<InputSurfaceElement>()
        view.onBeginEdit = { shown, w, h -> view.aspect = w.toFloat() / h; view.elements = shown }
        view.onChange = { changes += it; view.elements = view.elements.map { e -> it.firstOrNull { c -> c.id == e.id } ?: e } }
        val right = pad.maxOf { it.x + it.width } * 2400; val bottom = pad.maxOf { it.y + it.height } * 1504
        val (ux, uy) = view.centre("legacy:up")
        view.touch(MotionEvent.ACTION_DOWN, ux, uy)
        view.touch(MotionEvent.ACTION_UP, ux, uy)
        view.touch(MotionEvent.ACTION_DOWN, right, bottom)
        view.touch(MotionEvent.ACTION_MOVE, 0f, 0f)
        view.touch(MotionEvent.ACTION_UP, 0f, 0f)
        val shrunk = changes.takeLast(4)
        assertEquals(arrows.toSet(), shrunk.map { it.id }.toSet())
        shrunk.forEach { assertTrue(it.id, it.width >= .0299f && it.height >= .0299f) }
    }

    @Test fun aMouseSelectsDragsAndResizesLikeATouch() {
        val view = editor(overlay(), 2400, 1504)
        val a = view.rendered().first { it.id == "legacy:a" }
        val changes = mutableListOf<InputSurfaceElement>()
        var selection: String? = null
        view.onSelect = { selection = it }
        view.onChange = { changes += it }
        val (x, y) = view.centre(a.id)
        view.mouse(MotionEvent.ACTION_DOWN, x, y)
        view.mouse(MotionEvent.ACTION_UP, x, y)
        assertEquals(a.id, selection)
        view.mouse(MotionEvent.ACTION_DOWN, x, y)
        view.mouse(MotionEvent.ACTION_MOVE, x - 240f, y)
        view.mouse(MotionEvent.ACTION_UP, x - 240f, y)
        assertEquals(a.x - 240f / 2400, changes.last().x, .0001f)
        val right = (a.x + a.width) * 2400; val bottom = (a.y + a.height) * 1504
        view.mouse(MotionEvent.ACTION_DOWN, right, bottom)
        view.mouse(MotionEvent.ACTION_MOVE, right + 60f, bottom + 60f)
        view.mouse(MotionEvent.ACTION_UP, right + 60f, bottom + 60f)
        val resized = changes.last()
        assertEquals(a.id, resized.id)
        assertEquals(a.width + 60f / 2400, resized.width, .0001f)
        assertEquals(a.height + 60f / 1504, resized.height, .0001f)
    }
}

internal fun InputSurfaceEditorView.place(w: Int, h: Int) {
    measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
    layout(0, 0, w, h)
}

@Suppress("UNCHECKED_CAST")
internal fun InputSurfaceEditorView.rendered() = javaClass.getDeclaredField("visibleElements").also { it.isAccessible = true }.get(this) as List<InputSurfaceElement>

internal fun InputSurfaceEditorView.artworkDpad() = javaClass.getDeclaredField("preview").also { it.isAccessible = true }.get(this)!!.let { preview ->
    preview.javaClass.getDeclaredField("artworkDpad").also { it.isAccessible = true }.get(preview) as List<*> }
