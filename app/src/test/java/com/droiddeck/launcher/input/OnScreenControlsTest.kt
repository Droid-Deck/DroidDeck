package com.droiddeck.launcher.input

import kotlin.math.abs
import kotlin.math.sqrt
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnScreenControlsTest {
    private lateinit var context: Context
    private lateinit var view: OnScreenControls
    private var time = 1000L

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("controller", Context.MODE_PRIVATE).edit().clear().commit()
        ControllerPrefs.setLayout(context, 1200, 800, mapOf("ls" to (0.45f to 0.5f), "rs" to (0.55f to 0.5f)))
        view = OnScreenControls(context, null)
        view.layout(0, 0, 1200, 800)
    }

    @Test fun editorSnapsToOtherGroupCentreLines() {
        editor()
        val button = control("lb")
        val target = control("rb")
        val x = value(button, "cx")
        val y = value(button, "cy")
        touch(MotionEvent.ACTION_DOWN, 1 to (x to y))
        touch(MotionEvent.ACTION_MOVE, 1 to (x to y + 5f))
        assertEquals(value(target, "cy"), value(button, "cy"), 0f)
        assertEquals(value(target, "cy"), nullableFloat(view, "snapY")!!, 0f)
        touch(MotionEvent.ACTION_UP, 1 to (x to y + 5f))
        assertNull(nullableFloat(view, "snapY"))
    }

    @Test fun editorSnapsToClusterCentresNotTheirOuterButtons() {
        editor()
        val button = control("lb")
        val x = value(button, "cx")
        val row = value(control("y"), "cy") + 3f
        touch(MotionEvent.ACTION_DOWN, 1 to (x to value(button, "cy")))
        touch(MotionEvent.ACTION_MOVE, 1 to (x to row))
        assertEquals(row, value(button, "cy"), 0f)
        assertNull(nullableFloat(view, "snapY"))
        touch(MotionEvent.ACTION_MOVE, 1 to (x to value(control("x"), "cy") + 3f))
        assertEquals(value(control("x"), "cy"), value(button, "cy"), 0f)
        touch(MotionEvent.ACTION_UP, 1 to (x to row))
    }

    @Test fun editorDoesNotSnapBeyondDistanceAndCanDragFreeFromGuide() {
        editor()
        val button = control("lb")
        val x = value(button, "cx")
        val y = value(button, "cy")
        val otherYs = (field(view, "controls") as List<*>)
            .filter { field(it!!, "group") != "lb" }
            .map { value(it!!, "cy") }
        val freeY = (100..700 step 31).map(Int::toFloat).first {
            otherYs.minOf { targetY -> abs(it - targetY) } > 12f
        }
        touch(MotionEvent.ACTION_DOWN, 1 to (x to y))
        touch(MotionEvent.ACTION_MOVE, 1 to (x to freeY))
        assertEquals(freeY, value(button, "cy"), 0f)
        assertNull(nullableFloat(view, "snapY"))
        val target = value(control("rb"), "cy")
        touch(MotionEvent.ACTION_MOVE, 1 to (x to target + 5f))
        assertEquals(target, value(button, "cy"), 0f)
        touch(MotionEvent.ACTION_MOVE, 1 to (x to freeY))
        assertEquals(freeY, value(button, "cy"), 0f)
        assertNull(nullableFloat(view, "snapY"))
        touch(MotionEvent.ACTION_UP, 1 to (x to freeY))
    }

    @Test fun editorSnapsToScreenCentreAndCanDisableSnapping() {
        editor()
        val button = control("lb")
        val y = value(button, "cy")
        touch(MotionEvent.ACTION_DOWN, 1 to (value(button, "cx") to y))
        touch(MotionEvent.ACTION_MOVE, 1 to (605f to y))
        assertEquals(600f, value(button, "cx"), 0f)
        assertEquals(600f, nullableFloat(view, "snapX")!!, 0f)
        touch(MotionEvent.ACTION_UP, 1 to (605f to y))

        ControllerPrefs.setSnap(context, false)
        editor()
        val unsnapped = control("lb")
        val x = value(unsnapped, "cx")
        val startY = value(unsnapped, "cy")
        touch(MotionEvent.ACTION_DOWN, 1 to (x to startY))
        touch(MotionEvent.ACTION_MOVE, 1 to (605f to startY))
        assertEquals(605f, value(unsnapped, "cx"), 0f)
        assertNull(nullableFloat(view, "snapX"))
        touch(MotionEvent.ACTION_UP, 1 to (605f to startY))
    }

    @Test fun editorSnapPreferenceDefaultsOnAndCanBeChanged() {
        assertTrue(ControllerPrefs.read(context).snap)
        ControllerPrefs.setSnap(context, false)
        assertFalse(ControllerPrefs.read(context).snap)
        ControllerPrefs.resetAll(context)
        assertTrue(ControllerPrefs.read(context).snap)
    }

    @Test fun adaptiveStickHidesUntilTouchedAndReturnsToNeutralOnRelease() {
        val left = control("ls")
        assertEquals(0, pixel(value(left, "cx"), value(left, "cy")))
        assertTrue(touch(MotionEvent.ACTION_DOWN, 7 to (540f to 400f)))
        assertEquals(7, field(left, "pressedBy"))
        assertEquals(540f, value(left, "ax"), 0f)
        assertEquals(400f, value(left, "ay"), 0f)
        assertNotEquals(0, pixel(540f, 400f))
        touch(MotionEvent.ACTION_MOVE, 7 to (580f to 420f))
        assertTrue(value(left, "kx") > 0f)
        assertTrue(value(left, "ky") > 0f)
        touch(MotionEvent.ACTION_UP, 7 to (580f to 420f))
        assertEquals(-1, field(left, "pressedBy"))
        assertEquals(0f, value(left, "kx"), 0f)
        assertEquals(0f, value(left, "ky"), 0f)
        assertEquals(0, pixel(540f, 400f))
    }

    @Test fun simultaneousSticksKeepTheirPointersAndReleaseIndependently() {
        val left = control("ls")
        val right = control("rs")
        touch(MotionEvent.ACTION_DOWN, 7 to (540f to 400f))
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 7 to (540f to 400f), 9 to (660f to 400f))
        assertEquals(7, field(left, "pressedBy"))
        assertEquals(9, field(right, "pressedBy"))
        touch(MotionEvent.ACTION_POINTER_DOWN or (2 shl 8), 7 to (540f to 400f), 9 to (660f to 400f), 11 to (500f to 400f))
        assertEquals(7, field(left, "pressedBy"))
        touch(MotionEvent.ACTION_POINTER_UP or (2 shl 8), 7 to (540f to 400f), 9 to (660f to 400f), 11 to (500f to 400f))
        touch(MotionEvent.ACTION_MOVE, 7 to (700f to 400f), 9 to (500f to 400f))
        assertTrue(value(left, "kx") > 0f)
        assertTrue(value(right, "kx") < 0f)
        touch(MotionEvent.ACTION_POINTER_UP, 7 to (700f to 400f), 9 to (500f to 400f))
        assertEquals(-1, field(left, "pressedBy"))
        assertEquals(9, field(right, "pressedBy"))
        touch(MotionEvent.ACTION_CANCEL, 9 to (500f to 400f))
        for (stick in listOf(left, right)) {
            assertEquals(-1, field(stick, "pressedBy"))
            assertEquals(0f, value(stick, "kx"), 0f)
        }
    }

    @Test fun cancelledTouchDoesNotBecomeAStickClick() {
        touch(MotionEvent.ACTION_DOWN, 1 to (540f to 400f))
        touch(MotionEvent.ACTION_CANCEL, 1 to (540f to 400f))
        touch(MotionEvent.ACTION_DOWN, 1 to (540f to 400f))
        assertEquals(false, field(control("ls"), "clicked"))
        touch(MotionEvent.ACTION_UP, 1 to (540f to 400f))
        touch(MotionEvent.ACTION_DOWN, 1 to (540f to 400f))
        assertEquals(true, field(control("ls"), "clicked"))
    }

    @Test fun buttonsTakePriorityOverAdaptiveRegions() {
        val button = control("a")
        touch(MotionEvent.ACTION_DOWN, 3 to (value(button, "cx") to value(button, "cy")))
        assertEquals(3, field(button, "pressedBy"))
        assertEquals(-1, field(control("rs"), "pressedBy"))
    }

    @Test fun fixedSticksAndEditorRemainVisibleAtSavedPositions() {
        ControllerPrefs.setAdaptiveSticks(context, false)
        view.reload()
        val left = control("ls")
        val x = value(left, "cx")
        val y = value(left, "cy")
        assertNotEquals(0, pixel(x, y))
        assertFalse(touch(MotionEvent.ACTION_DOWN, 1 to (600f to 200f)))
        touch(MotionEvent.ACTION_DOWN, 1 to (x + 10f to y))
        assertEquals(x, value(left, "ax"), 0f)
        assertEquals(10f, value(left, "kx"), 0f)
        ControllerPrefs.setAdaptiveSticks(context, true)
        view = OnScreenControls(context, null, editing = true)
        view.layout(0, 0, 1200, 800)
        assertNotEquals(0, pixel(value(control("ls"), "cx"), value(control("ls"), "cy")))
    }

    @Test fun buttonsOnlyAndReloadReleaseAdaptiveSticks() {
        touch(MotionEvent.ACTION_DOWN, 1 to (540f to 400f))
        touch(MotionEvent.ACTION_MOVE, 1 to (570f to 400f))
        view.reload()
        assertEquals(-1, field(control("ls"), "pressedBy"))
        assertEquals(0f, value(control("ls"), "kx"), 0f)
        view.setButtonsOnly(true)
        assertFalse(touch(MotionEvent.ACTION_DOWN, 1 to (600f to 200f)))
    }

    @Test fun adaptiveActivationIsLimitedToExpandedSavedCircle() {
        val left = control("ls")
        val x = value(left, "cx")
        val y = value(left, "cy")
        val reach = value(left, "radius") * 1.4f * sqrt(1.5f)
        assertFalse(touch(MotionEvent.ACTION_DOWN, 1 to (x to y - reach - 1f)))
        assertTrue(touch(MotionEvent.ACTION_DOWN, 1 to (x to y - reach + 1f)))
        assertEquals(y - reach + 1f, value(left, "ay"), 0.001f)
        touch(MotionEvent.ACTION_UP, 1 to (x to y - reach + 1f))
        assertFalse(touch(MotionEvent.ACTION_DOWN, 1 to (x - reach to y - reach)))
        assertFalse(touch(MotionEvent.ACTION_DOWN, 1 to (600f to 200f)))
    }

    @Test fun touchesOutsideActivationAreasStayWithTrackpad() {
        val received = mutableListOf<Int>()
        val trackpad = View(context).apply {
            setOnTouchListener { _, event -> received.add(event.actionMasked); true }
        }
        val root = FrameLayout(context).apply {
            addView(trackpad, FrameLayout.LayoutParams(1200, 800))
            addView(view, FrameLayout.LayoutParams(1200, 800))
            measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            layout(0, 0, 1200, 800)
        }
        for ((action, position) in listOf(
            MotionEvent.ACTION_DOWN to (600f to 200f),
            MotionEvent.ACTION_MOVE to (540f to 400f),
            MotionEvent.ACTION_UP to (540f to 400f),
        )) {
            val event = MotionEvent.obtain(1000L, time++, action, position.first, position.second, 0)
            try { assertTrue(root.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), received)
        assertEquals(-1, field(control("ls"), "pressedBy"))
    }

    @Test fun adaptiveActivationLeavesFivePixelsAroundButtonHitTargets() {
        for (id in listOf("a", "rb")) {
            val button = control(id)
            val radius = value(button, "radius")
            val edge = value(button, "cy") - radius * if (id == "rb") 1.05f else 1.25f
            val x = value(button, "cx")
            val left = control("ls")
            left.javaClass.getDeclaredField("cx").apply { isAccessible = true }.setFloat(left, x)
            left.javaClass.getDeclaredField("cy").apply { isAccessible = true }.setFloat(left, edge - 10f)
            assertFalse(touch(MotionEvent.ACTION_DOWN, 1 to (x to edge - 4f)))
            assertEquals(-1, field(left, "pressedBy"))
            assertTrue(touch(MotionEvent.ACTION_DOWN, 1 to (x to edge - 6f)))
            assertEquals(1, field(left, "pressedBy"))
            touch(MotionEvent.ACTION_UP, 1 to (x to edge - 6f))
        }
    }

    private fun control(id: String): Any = (field(view, "controls") as List<*>).first { field(it!!, "id") == id }!!
    private fun editor() {
        view = OnScreenControls(context, null, editing = true)
        view.layout(0, 0, 1200, 800)
    }

    @Test fun keyboardShortcutWorksWithHiddenPadAndButtonsOnly() {
        var opened = 0
        view = OnScreenControls(context, null, onKeyboard = { opened++ })
        view.layout(0, 0, 1200, 800)
        view.setQuickHidden(true)
        assertNotEquals(0, pixel(90f, 766f))
        touch(MotionEvent.ACTION_DOWN, 1 to (90f to 766f))
        assertEquals(0, opened)
        touch(MotionEvent.ACTION_UP, 1 to (90f to 766f))
        assertEquals(1, opened)
        assertFalse(touch(MotionEvent.ACTION_DOWN, 1 to (600f to 400f)))
        view.setButtonsOnly(true)
        touch(MotionEvent.ACTION_DOWN, 1 to (600f to 756f))
        touch(MotionEvent.ACTION_UP, 1 to (600f to 756f))
        assertEquals(2, opened)
        assertEquals(-1, field(control("guide"), "pressedBy"))
    }

    @Test fun cancelledDraggedOrDisabledKeyboardShortcutDoesNotOpen() {
        var opened = 0
        view = OnScreenControls(context, null, onKeyboard = { opened++ })
        view.layout(0, 0, 1200, 800)
        touch(MotionEvent.ACTION_DOWN, 1 to (90f to 766f))
        touch(MotionEvent.ACTION_CANCEL, 1 to (90f to 766f))
        touch(MotionEvent.ACTION_UP, 1 to (90f to 766f))
        assertEquals(0, opened)
        touch(MotionEvent.ACTION_DOWN, 1 to (90f to 766f))
        touch(MotionEvent.ACTION_MOVE, 1 to (180f to 766f))
        touch(MotionEvent.ACTION_UP, 1 to (180f to 766f))
        assertEquals(0, opened)
        ControllerPrefs.setKeyboardButton(context, false)
        view.reload()
        view.setQuickHidden(true)
        assertEquals(0, pixel(90f, 766f))
        assertFalse(touch(MotionEvent.ACTION_DOWN, 1 to (90f to 766f)))
    }

    @Test fun hidingSystemButtonsRemovesHitTargetsInBothOverlayModes() {
        for (buttonsOnly in listOf(false, true)) {
            ControllerPrefs.setSteamButton(context, true)
            ControllerPrefs.setQamButton(context, true)
            view.reload()
            view.setButtonsOnly(buttonsOnly)
            val guide = control("guide")
            val qam = control("qam")
            val gx = value(guide, "cx"); val gy = value(guide, "cy")
            val qx = value(qam, "cx"); val qy = value(qam, "cy")
            assertNotEquals(0, pixel(gx, gy))
            assertNotEquals(0, pixel(qx, qy))
            touch(MotionEvent.ACTION_DOWN, 1 to (gx to gy))
            ControllerPrefs.setSteamButton(context, false)
            view.reload()
            assertEquals(-1, field(guide, "pressedBy"))
            assertEquals(0, pixel(gx, gy))
            assertFalse(touch(MotionEvent.ACTION_DOWN, 1 to (gx to gy)))
            assertTrue(touch(MotionEvent.ACTION_DOWN, 1 to (qx to qy)))
            touch(MotionEvent.ACTION_UP, 1 to (qx to qy))
            ControllerPrefs.setQamButton(context, false)
            view.reload()
            assertEquals(0, pixel(qx, qy))
            assertFalse(touch(MotionEvent.ACTION_DOWN, 1 to (qx to qy)))
        }
    }
    private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)!!
    private fun nullableFloat(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as Float?
    private fun value(owner: Any, name: String) = field(owner, name) as Float

    private fun pixel(x: Float, y: Float): Int {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val color = bitmap.getPixel(x.toInt(), y.toInt())
        bitmap.recycle()
        return color
    }

    private fun touch(action: Int, vararg pointers: Pair<Int, Pair<Float, Float>>): Boolean {
        val properties = pointers.map { (id, _) -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coords = pointers.map { (_, position) -> MotionEvent.PointerCoords().apply { x = position.first; y = position.second; pressure = 1f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(1000L, time++, action, pointers.size, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        return try { view.onTouchEvent(event) } finally { event.recycle() }
    }
}
