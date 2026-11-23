package com.droiddeck.launcher.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import com.droiddeck.launcher.input.InputButtonShape
import com.droiddeck.launcher.input.InputControl
import com.droiddeck.launcher.input.InputElementKind
import com.droiddeck.launcher.input.InputSurfaceElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "xhdpi")
class InputSelectionOverlayTest {
    @get:Rule val looper = MainLooperDrain()
    private val w = 1000f
    private val h = 500f
    private val a = InputSurfaceElement(InputControl.A, .4f, .3f, .1f, .2f, "one", buttonShape = InputButtonShape.CIRCLE)
    private val b = InputSurfaceElement(InputControl.B, .7f, .3f, .1f, .2f, "two", buttonShape = InputButtonShape.CIRCLE)

    private fun editor(vararg elements: InputSurfaceElement): InputSurfaceEditorView {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        return InputSurfaceEditorView(activity).apply {
            this.elements = elements.toList()
            snap = false
            measure(View.MeasureSpec.makeMeasureSpec(w.toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h.toInt(), View.MeasureSpec.EXACTLY))
            layout(0, 0, w.toInt(), h.toInt())
        }
    }

    private val InputSurfaceEditorView.density get() = resources.displayMetrics.density

    private fun InputSurfaceEditorView.touch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        try { onTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun handlesGrabWithin44dpAndTheBadgeKeepsItsOwnCorner() {
        val view = editor(a)
        assertEquals(-1, view.handleAt(450f, 200f))
        view.selection = listOf("one")
        val d = view.density
        val diagonal = (50f + 3 * d) * .70710677f
        val tlX = 450f - diagonal; val tlY = 200f - diagonal
        assertEquals(0, view.handleAt(tlX - 15.5f * d, tlY - 15.5f * d))
        assertEquals(-1, view.handleAt(tlX - 16f * d, tlY - 16f * d))
        assertEquals(1, view.handleAt(450f - diagonal - 20 * d, 200f + diagonal))
        assertEquals(2, view.handleAt(450f + diagonal, 200f + diagonal + 21 * d))
        assertEquals(-1, view.handleAt(450f + diagonal, 200f - diagonal))
        assertEquals(-1, view.handleAt(450f, 200f))

        val (bx, by) = view.badgeCenter()!!
        assertEquals(450f + diagonal, bx, .01f)
        assertEquals(200f - diagonal, by, .01f)
        assertTrue(view.hitsBadge(bx + 21 * d, by))
        assertTrue(view.hitsBadge(bx - 8 * d, by + 8 * d))
        assertFalse(view.hitsBadge(bx - 15 * d, by + 15 * d))
        assertFalse(view.hitsBadge(bx, by - 23 * d))
        assertEquals(-1, view.handleAt(bx, by))
    }

    @Test fun theBadgeSitsOnTheOutlineCornerOfARoundedRectangle() {
        val pill = InputSurfaceElement(InputControl.LB, .2f, .4f, .2f, .2f, "pill", buttonShape = InputButtonShape.ROUNDED)
        val view = editor(pill)
        view.selection = listOf("pill")
        val d = view.density
        val corner = 23 * d
        val (bx, by) = view.badgeCenter()!!
        assertEquals(400f + 3 * d - corner * (1 - .70710677f), bx, .01f)
        assertEquals(200f - 3 * d + corner * (1 - .70710677f), by, .01f)
    }

    @Test fun theBadgeTakesTheNextCornerWhenAControlOrTheEdgeIsInTheWay() {
        val d = 2f
        val diagonal = (50f + 3 * d) * .70710677f
        val neighbour = InputSurfaceElement(InputControl.B, .48f, .2f, .06f, .12f, "two", buttonShape = InputButtonShape.CIRCLE)
        val view = editor(a, neighbour)
        assertEquals(d, view.density, 0f)
        view.selection = listOf("one")
        assertNear(450f - diagonal to 200f - diagonal, view.badgeCenter()!!)
        assertEquals(-1, view.handleAt(450f - diagonal, 200f - diagonal))
        assertEquals(2, view.handleAt(450f + diagonal, 200f - diagonal))

        val edge = InputSurfaceElement(InputControl.A, .9f, 0f, .1f, .2f, "edge", buttonShape = InputButtonShape.CIRCLE)
        val cornered = editor(edge)
        cornered.selection = listOf("edge")
        assertNear(950f - diagonal to 50f + diagonal, cornered.badgeCenter()!!)
    }

    @Test fun pressingTheMiddleOfATinyControlMovesItInsteadOfDeletingIt() {
        val tiny = InputSurfaceElement(InputControl.A, .5f, .5f, .03f, .03f, "tiny", buttonShape = InputButtonShape.CIRCLE)
        val view = editor(tiny)
        view.selection = listOf("tiny")
        var deleted = 0
        view.onDelete = { deleted++ }
        val cx = 515f; val cy = 257.5f
        assertFalse(view.hitsBadge(cx, cy))
        assertEquals(-1, view.handleAt(cx, cy))
        view.touch(MotionEvent.ACTION_DOWN, cx, cy)
        view.touch(MotionEvent.ACTION_UP, cx, cy)
        assertEquals(0, deleted)
        val (bx, by) = view.badgeCenter()!!
        view.touch(MotionEvent.ACTION_DOWN, bx, by)
        view.touch(MotionEvent.ACTION_UP, bx, by)
        assertEquals(1, deleted)
    }

    @Test fun aTinyControlsHandlesAndBadgeDoNotOverlap() {
        val tiny = InputSurfaceElement(InputControl.A, .5f, .5f, .03f, .03f, "tiny", buttonShape = InputButtonShape.CIRCLE)
        val view = editor(tiny)
        view.selection = listOf("tiny")
        val d = view.density
        val points = listOf(view.badgeCenter()!!) + (0 until 3).map(view::handleCenter)
        // Handle discs are 12dp and the badge 18dp with its ring, so centres closer than 30dp overlap.
        for (i in points.indices) for (j in i + 1 until points.size) {
            val (ax, ay) = points[i]; val (cx, cy) = points[j]
            assertTrue("$i-$j", kotlin.math.hypot(ax - cx, ay - cy) >= 30 * d)
        }
    }

    @Test fun theBadgeStaysOnScreenWhenTheControlFillsIt() {
        val full = InputSurfaceElement(InputControl.A, 0f, 0f, 1f, 1f, "full", buttonShape = InputButtonShape.ROUNDED)
        val view = editor(full)
        view.selection = listOf("full")
        val r = 18 * view.density
        val (bx, by) = view.badgeCenter()!!
        assertTrue("$bx", bx in r..(w - r))
        assertTrue("$by", by in r..(h - r))
    }

    @Test fun aControlHiddenUnderAnotherCanBeSelectedAndDragged() {
        val top = a.copy(id = "top")
        val view = editor(a, top)
        view.onSelect = { view.selection = it }
        view.onChange = { changed -> val byId = changed.associateBy { it.id }; view.elements = view.elements.map { byId[it.id] ?: it } }
        view.touch(MotionEvent.ACTION_DOWN, 450f, 200f); view.touch(MotionEvent.ACTION_UP, 450f, 200f)
        assertEquals(listOf("top"), view.selection)
        view.touch(MotionEvent.ACTION_DOWN, 450f, 200f); view.touch(MotionEvent.ACTION_UP, 450f, 200f)
        assertEquals(listOf("one"), view.selection)
        view.touch(MotionEvent.ACTION_DOWN, 450f, 200f)
        view.touch(MotionEvent.ACTION_MOVE, 450f, 300f)
        view.touch(MotionEvent.ACTION_UP, 450f, 300f)
        assertEquals(listOf("one"), view.selection)
        assertEquals(.5f, view.elements.first { it.id == "one" }.y, .001f)
        assertEquals(a.y, view.elements.first { it.id == "top" }.y, .001f)
    }

    @Test fun undoDuringADragEndsIt() {
        val view = editor(a)
        view.onSelect = { view.selection = it }
        view.onChange = { changed -> val byId = changed.associateBy { it.id }; view.elements = view.elements.map { byId[it.id] ?: it } }
        view.touch(MotionEvent.ACTION_DOWN, 450f, 200f); view.touch(MotionEvent.ACTION_UP, 450f, 200f)
        view.touch(MotionEvent.ACTION_DOWN, 450f, 200f)
        view.touch(MotionEvent.ACTION_MOVE, 450f, 300f)
        view.elements = listOf(a)
        view.restored++
        view.touch(MotionEvent.ACTION_MOVE, 450f, 350f)
        view.touch(MotionEvent.ACTION_UP, 450f, 350f)
        assertEquals(a.y, view.elements.single().y, .001f)
    }

    private fun assertNear(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, .01f)
        assertEquals(expected.second, actual.second, .01f)
    }

    @Test fun draggingAHandleResizesFromTheOppositeCorner() {
        val view = editor(a)
        view.selection = listOf("one")
        val changes = mutableListOf<List<InputSurfaceElement>>()
        view.onChange = { changes += it }
        val d = view.density
        val diagonal = (50f + 3 * d) * .70710677f
        val x = 450f + diagonal + 18 * d; val y = 200f + diagonal
        view.touch(MotionEvent.ACTION_DOWN, x, y)
        view.touch(MotionEvent.ACTION_MOVE, x + 50f, y + 50f)
        view.touch(MotionEvent.ACTION_UP, x + 50f, y + 50f)
        val resized = changes.last().single()
        assertEquals(a.x, resized.x, .0001f)
        assertEquals(a.y, resized.y, .0001f)
        assertTrue(resized.width > a.width)
        assertTrue(resized.height > a.height)
    }

    private fun InputSurfaceEditorView.touch(action: Int, vararg pointers: Triple<Int, Float, Float>) {
        val now = SystemClock.uptimeMillis()
        val props = pointers.map { MotionEvent.PointerProperties().apply { id = it.first; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coords = pointers.map { MotionEvent.PointerCoords().apply { x = it.second; y = it.third; pressure = 1f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(now, now, action, pointers.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try { onTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun aSecondFingerNeitherMovesNorSelectsAfterTheFirstLifts() {
        val view = editor(a, b)
        view.selection = listOf("one")
        val selected = mutableListOf<List<String>>()
        view.onSelect = { selected += it }
        view.onChange = { changed -> val byId = changed.associateBy { it.id }; view.elements = view.elements.map { byId[it.id] ?: it } }
        val pointerUp = { index: Int -> MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) }
        val pointerDown = { index: Int -> MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) }
        view.touch(MotionEvent.ACTION_DOWN, Triple(0, 450f, 200f))
        view.touch(MotionEvent.ACTION_MOVE, Triple(0, 500f, 200f))
        view.touch(pointerDown(1), Triple(0, 500f, 200f), Triple(1, 750f, 200f))
        view.touch(pointerUp(0), Triple(0, 500f, 200f), Triple(1, 750f, 200f))
        val moved = view.elements.first { it.id == "one" }
        assertEquals(.45f, moved.x, .001f)
        view.touch(MotionEvent.ACTION_MOVE, Triple(1, 760f, 300f))
        view.touch(MotionEvent.ACTION_UP, Triple(1, 760f, 300f))
        assertEquals(moved, view.elements.first { it.id == "one" })
        assertTrue(selected.isEmpty())
    }

    @Test fun overlapFollowsTheDrawnShapes() {
        val circle = { cx: Float, cy: Float, r: Float -> OutlineShape().set(cx - r, cy - r, cx + r, cy + r, r) }
        assertTrue(circle(0f, 0f, 50f).overlaps(circle(90f, 0f, 50f)))
        assertFalse(circle(0f, 0f, 50f).overlaps(circle(100f, 0f, 50f)))
        // Boxes overlap at the corners but the circles do not.
        assertFalse(circle(0f, 0f, 50f).overlaps(circle(75f, 75f, 50f)))
        assertTrue(circle(0f, 0f, 50f).overlaps(circle(65f, 65f, 50f)))
        val square = { l: Float, t: Float -> OutlineShape().set(l, t, l + 100f, t + 100f, 0f) }
        assertFalse(square(0f, 0f).overlaps(square(100f, 0f)))
        assertTrue(square(0f, 0f).overlaps(square(99f, 99f)))
        val pill = OutlineShape().set(0f, 0f, 200f, 60f, 30f)
        assertFalse(pill.overlaps(circle(220f, 80f, 20f)))
        assertTrue(pill.overlaps(circle(150f, 80f, 25f)))
    }

    @Test fun movingOntoAnotherControlFlagsItUntilTheDragEnds() {
        val view = editor(a, b)
        view.selection = listOf("one")
        view.onChange = { changed -> val byId = changed.associateBy { it.id }; view.elements = view.elements.map { byId[it.id] ?: it } }
        view.touch(MotionEvent.ACTION_DOWN, 450f, 200f)
        view.touch(MotionEvent.ACTION_MOVE, 550f, 200f)
        assertEquals(0, view.overlapping)
        view.touch(MotionEvent.ACTION_MOVE, 700f, 200f)
        assertEquals(1, view.overlapping)
        view.touch(MotionEvent.ACTION_UP, 700f, 200f)
        assertEquals(0, view.overlapping)
    }

    @Test fun outlinesFollowHowEachControlIsDrawn() {
        val d = 2f
        val stick = InputSurfaceElement(InputControl.LS, .1f, .2f, .3f, .2f, buttonShape = InputButtonShape.CIRCLE)
        val classic = OutlineShape().ofControl(stick, width = w, height = h, density = d)
        assertEquals(400f - 50f, classic.centerX, .01f)
        assertEquals(50f, classic.corner, .01f)
        assertTrue(classic.circle)
        val added = OutlineShape().ofControl(stick.copy(id = "copy:legacy:ls#1"), width = w, height = h, density = d)
        assertEquals(classic.centerX, added.centerX, .01f); assertEquals(classic.corner, added.corner, .01f)
        val panel = OutlineShape().ofControl(stick.copy(buttonShape = InputButtonShape.ROUNDED), width = w, height = h, density = d)
        assertEquals(100f, panel.left, .01f); assertEquals(400f, panel.right, .01f); assertEquals(40f, panel.corner, .01f)
        val pad = InputSurfaceElement(InputControl.UP, .1f, .1f, .2f, .2f, kind = InputElementKind.DPAD)
        assertTrue(OutlineShape().ofControl(pad, w, h, d).circle)
        val bumper = InputSurfaceElement(InputControl.LB, .1f, .1f, .2f, .1f, buttonShape = InputButtonShape.PILL)
        assertEquals(25f, OutlineShape().ofControl(bumper, w, h, d).corner, .01f)
        assertEquals(25f, OutlineShape().ofControl(bumper.copy(id = "added"), w, h, d).corner, .01f)
    }

    @Test fun onlyTheSelectedControlGetsEditingUi() {
        val view = editor(a, b)
        val drawn = RecordingCanvas()
        view.drawOverlay(drawn)
        assertEquals(0, drawn.calls)

        view.selection = listOf("one")
        val selected = RecordingCanvas()
        view.drawOverlay(selected)
        assertTrue(selected.calls > 0)
        assertTrue(selected.outlines.isNotEmpty())
        for ((cx, cy) in selected.outlines) {
            assertEquals(450f, cx, 1f)
            assertEquals(200f, cy, 1f)
        }

        view.selection = emptyList()
        val cleared = RecordingCanvas()
        view.drawOverlay(cleared)
        assertEquals(0, cleared.calls)
    }

    @Test fun aPopInEndsAtTheOpacitySetWhileItPlays() {
        val view = editor(a)
        fun control() = generateSequence(listOf<View>(view)) { level -> level.flatMap { v -> (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).map(g::getChildAt) }.orEmpty() }.takeIf { it.isNotEmpty() } }
            .flatten().first { (it.tag as? InputSurfaceElement)?.id == "one" }
        val popped = control()
        view.popIn(listOf("one"))
        assertEquals(View.LAYER_TYPE_HARDWARE, popped.layerType)
        view.elements = listOf(a.copy(opacity = .5f))
        assertTrue(popped === control())
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertEquals(.5f, popped.alpha, .001f)
        assertEquals(1f, popped.scaleX, .001f)
        assertEquals(View.LAYER_TYPE_NONE, popped.layerType)
    }

    @Test fun handlesAndTheBadgeTakeTouchesAcross24dp() {
        val mid = InputSurfaceElement(InputControl.A, .45f, .3f, .08f, .16f, "mid", buttonShape = InputButtonShape.CIRCLE)
        val view = editor(mid)
        view.selection = listOf("mid")
        val r = 11.5f * view.density
        for (i in 0 until 3) {
            val (hx, hy) = view.handleCenter(i)
            for (step in 0 until 8) {
                val angle = step * Math.PI / 4
                val x = hx + r * Math.cos(angle).toFloat(); val y = hy + r * Math.sin(angle).toFloat()
                assertEquals("handle $i at ${step * 45}", i, view.handleAt(x, y))
                assertFalse(view.hitsBadge(x, y))
            }
        }
        val (bx, by) = view.badgeCenter()!!
        for (step in 0 until 8) {
            val angle = step * Math.PI / 4
            assertTrue(view.hitsBadge(bx + r * Math.cos(angle).toFloat(), by + r * Math.sin(angle).toFloat()))
        }
        assertEquals("the middle still moves a small control", -1, view.handleAt(490f, 190f))
    }

    private fun InputSurfaceEditorView.editable(onDeleted: () -> Unit = {}): () -> Int {
        var begins = 0
        onBeginEdit = { begins++ }
        onChange = { changed -> elements = elements.map { e -> changed.firstOrNull { it.id == e.id } ?: e } }
        onDelete = onDeleted
        return { begins }
    }

    private fun InputSurfaceEditorView.key(code: Int, meta: Int = 0) =
        onKeyDown(code, android.view.KeyEvent(0L, 0L, android.view.KeyEvent.ACTION_DOWN, code, 0, meta))

    @Test fun aControllerOrKeyboardFocusesSelectsMovesResizesAndDeletes() {
        val view = editor(a, b)
        var deleted = false
        val begins = view.editable { deleted = true }
        val step = 8 * view.density
        assertTrue(view.key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("one", view.focusedControl)
        assertTrue(view.key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("two", view.focusedControl)
        assertFalse("focus leaves the canvas past the last control", view.key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(view.key(android.view.KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("one", view.focusedControl)

        assertTrue(view.key(android.view.KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(listOf("one"), view.selection)
        view.key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        view.key(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        val moved = view.elements.first { it.id == "one" }
        assertEquals(.4f + step / w, moved.x, .0001f)
        assertEquals(.3f + step / h, moved.y, .0001f)
        assertEquals(1, begins())

        view.key(android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.META_SHIFT_ON)
        assertEquals(.11f, view.elements.first { it.id == "one" }.width, .0001f)
        view.key(android.view.KeyEvent.KEYCODE_BUTTON_L1)
        assertEquals(.1f, view.elements.first { it.id == "one" }.width, .0001f)
        assertEquals("a run of nudges is one undo step", 1, begins())

        assertTrue(view.key(android.view.KeyEvent.KEYCODE_BUTTON_B))
        assertTrue(view.selection.isEmpty())
        assertFalse("B with nothing selected falls through to Back", view.key(android.view.KeyEvent.KEYCODE_BUTTON_B))
        view.key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(view.key(android.view.KeyEvent.KEYCODE_FORWARD_DEL))
        assertTrue(deleted)
    }

    @Test fun focusMovesToTheNearestControlThatWayAndEntersFromTheEdge() {
        val left = InputSurfaceElement(InputControl.A, .1f, .4f, .1f, .2f, "left")
        val up = InputSurfaceElement(InputControl.B, .45f, .1f, .1f, .2f, "up")
        val right = InputSurfaceElement(InputControl.X, .8f, .45f, .1f, .2f, "right")
        val far = InputSurfaceElement(InputControl.Y, .6f, .8f, .1f, .2f, "far")
        val all = listOf(left, up, right, far)
        assertEquals("left", nextControl(all, null, View.FOCUS_RIGHT, w, h))
        assertEquals("right", nextControl(all, null, View.FOCUS_LEFT, w, h))
        assertEquals("up", nextControl(all, null, View.FOCUS_DOWN, w, h))
        assertEquals("right", nextControl(all, "left", View.FOCUS_RIGHT, w, h))
        assertEquals("up", nextControl(all, "left", View.FOCUS_UP, w, h))
        assertEquals("far", nextControl(all, "left", View.FOCUS_DOWN, w, h))
        assertEquals(null, nextControl(all, "left", View.FOCUS_LEFT, w, h))
    }

    @Test fun talkBackFindsEachControlAndSelectsMovesAndDeletesIt() {
        val view = editor(a, b)
        var deleted = false
        view.editable { deleted = true }
        val provider = view.accessibilityNodeProvider!!
        val host = provider.createAccessibilityNodeInfo(View.NO_ID)!!
        assertEquals(2, host.childCount)
        val node = provider.createAccessibilityNodeInfo(0)!!
        assertEquals(a.name, node.contentDescription)
        val bounds = android.graphics.Rect().also(node::getBoundsInParent)
        assertEquals(android.graphics.Rect(400, 150, 500, 250), bounds)
        val actions = node.actionList.map { it.id }
        for (id in listOf(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, com.droiddeck.launcher.R.id.input_editor_move_left,
            com.droiddeck.launcher.R.id.input_editor_move_down, com.droiddeck.launcher.R.id.input_editor_delete)) assertTrue("$id", id in actions)
        assertFalse(node.isSelected)

        assertTrue(provider.performAction(0, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(listOf("one"), view.selection)
        assertTrue(provider.createAccessibilityNodeInfo(0)!!.isSelected)

        assertTrue(provider.performAction(1, com.droiddeck.launcher.R.id.input_editor_move_right, null))
        assertEquals(listOf("two"), view.selection)
        assertEquals(.7f + 32 * view.density / w, view.elements.first { it.id == "two" }.x, .0001f)
        assertTrue(provider.performAction(1, com.droiddeck.launcher.R.id.input_editor_delete, null))
        assertTrue(deleted)
    }

    @Test fun groupOutlineDashesWithoutAPathEffect() {
        val shape = OutlineShape().set(0f, 0f, 300f, 200f, 40f)
        val perimeter = 2 * (220f + 120f) + 2 * Math.PI.toFloat() * 40f
        val dashes = DashCanvas()
        drawDashedOutline(dashes, shape, 6f, 4f, Paint(), android.graphics.RectF())
        assertEquals(perimeter * .6f, dashes.length, 6f)
        assertTrue(dashes.arcs > 0)
        assertFalse(dashes.pathEffect)

        val view = editor(a, b)
        view.selection = listOf("one", "two")
        val group = DashCanvas()
        view.drawOverlay(group)
        assertTrue(group.length > 0f)
        assertFalse(group.pathEffect)
    }

    private class DashCanvas : Canvas() {
        var length = 0f
        var arcs = 0
        var pathEffect = false
        override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {
            length += Math.hypot((stopX - startX).toDouble(), (stopY - startY).toDouble()).toFloat(); pathEffect = pathEffect || paint.pathEffect != null
        }
        override fun drawArc(oval: android.graphics.RectF, startAngle: Float, sweepAngle: Float, useCenter: Boolean, paint: Paint) {
            arcs++; length += Math.toRadians(sweepAngle.toDouble()).toFloat() * oval.width() / 2f; pathEffect = pathEffect || paint.pathEffect != null
        }
        override fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint) {
            pathEffect = pathEffect || paint.pathEffect != null
        }
        override fun drawPath(path: Path, paint: Paint) { pathEffect = pathEffect || paint.pathEffect != null }
        override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {}
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {}
    }

    private class RecordingCanvas : Canvas() {
        var calls = 0
        val outlines = mutableListOf<Pair<Float, Float>>()
        override fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint) {
            calls++; outlines += (left + right) / 2f to (top + bottom) / 2f
        }
        override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) { calls++ }
        override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) { calls++ }
        override fun drawPath(path: Path, paint: Paint) { calls++ }
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) { calls++ }
        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) { calls++ }
        override fun drawBitmap(bitmap: android.graphics.Bitmap, left: Float, top: Float, paint: Paint?) { calls++ }
    }
}
