package com.droiddeck.launcher.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.droiddeck.launcher.input.*
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [JoyConPadTest.RecordingWriter::class])
class InputSurfaceComboTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val bridge = PadBridge(File(context.cacheDir, "surface-combo"))
    private val router = InputRouter(object : InputOutput {
        override fun key(code: Int, down: Boolean) {}
        override fun button(code: Int, down: Boolean) {}
        override fun pointer(dx: Float, dy: Float) {}
        override fun scroll(steps: Int) {}
        override fun command(command: InputCommand) {}
        override fun requestMotion() {}
    })
    private lateinit var view: OnScreenControls
    private var time = 1000L

    private fun round(control: InputControl, x: Float, y: Float) =
        InputSurfaceElement(control, x, y, .1f, 1f / 6f, buttonShape = InputButtonShape.CIRCLE)

    private val up = round(InputControl.UP, .1f, .2f)
    private val right = round(InputControl.RIGHT, .2f, .35f)
    private val down = round(InputControl.DOWN, .1f, .5f)
    private val left = round(InputControl.LEFT, 0f, .35f)
    private val a = round(InputControl.A, .8f, .5f)
    private val b = round(InputControl.B, .9f, .35f)
    private val x = round(InputControl.X, .7f, .35f)
    private val y = round(InputControl.Y, .8f, .2f)
    private val dpad = InputSurfaceElement(InputControl.LS, .3f, .55f, .2f, .4f, "dpad", InputElementKind.DPAD)

    private val upAt = 150f to 150f
    private val rightAt = 250f to 240f
    private val downAt = 150f to 350f
    private val leftAt = 50f to 240f
    private val aAt = 850f to 350f
    private val bAt = 950f to 240f
    private val xAt = 750f to 240f
    private val yAt = 850f to 150f
    private val padUp = 400f to 385f
    private val padRight = 465f to 450f
    private val padLeft = 335f to 450f

    @Before fun setup() {
        bridge.setInputRouter(router)
        bridge.setInputProfile(InputProfile("touch", elements = mapOf(InputRole.PRIMARY to mapOf("dpad" to InputAction.Native()))))
        view = OnScreenControls(context, bridge)
        view.updateLayout(SavedInputLayout("touch", "Touch", listOf(up, right, down, left, a, b, x, y, dpad), aspect = 1000f / 600f))
        view.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1000, 600)
    }

    @After fun close() { view.releaseAll(); bridge.stop() }

    private fun state() = JoyConPadTest.RecordingWriter.pads[0] ?: PadState()

    @Test fun holdingAFaceButtonKeepsItThroughTapsOnTheOthers() {
        press(1, aAt)
        for ((id, at) in listOf(2 to bAt, 3 to xAt, 4 to yAt)) {
            press(id, at)
            assertTrue(state().isDown(PadState.A))
            lift(id)
            assertTrue(state().isDown(PadState.A))
        }
        lift(1)
        assertFalse(state().isDown(PadState.A))
    }

    @Test fun allFourFaceButtonsHoldTogether() {
        press(1, aAt); press(2, bAt); press(3, xAt); press(4, yAt)
        assertTrue(listOf(PadState.A, PadState.B, PadState.X, PadState.Y).all { state().isDown(it) })
        lift(3)
        assertFalse(state().isDown(PadState.X))
        assertTrue(listOf(PadState.A, PadState.B, PadState.Y).all { state().isDown(it) })
    }

    @Test fun directionButtonsHoldTogetherAndWithFaceButtons() {
        press(1, upAt); press(2, rightAt); press(3, aAt)
        assertTrue(state().up && state().right && state().isDown(PadState.A))
        lift(2)
        assertTrue(state().up && !state().right && state().isDown(PadState.A))
        press(4, leftAt); press(5, downAt)
        assertTrue(state().up && state().left && state().down)
    }

    @Test fun theDirectionPadKeepsEachFingersDirection() {
        press(1, padUp)
        assertTrue(state().up)
        press(2, padRight)
        assertTrue(state().up && state().right)
        lift(2)
        assertTrue(state().up && !state().right)
        press(3, padLeft)
        assertTrue(state().up && state().left)
        lift(1)
        assertTrue(!state().up && state().left)
        lift(3)
        assertFalse(state().up || state().left || state().right)
    }

    @Test fun theDirectionPadStaysHeldWhileFaceButtonsAreTapped() {
        press(1, padLeft)
        press(2, aAt)
        assertTrue(state().left && state().isDown(PadState.A))
        lift(2)
        assertTrue(state().left)
        press(3, bAt)
        assertTrue(state().left && state().isDown(PadState.B))
        lift(1)
        assertFalse(state().left)
        assertTrue(state().isDown(PadState.B))
        lift(3)
    }

    @Test fun oneThumbBetweenNeighbouringArrowsPressesBoth() {
        for ((at, expect) in listOf(
            (200f to 215f) to "up right", (200f to 305f) to "right down",
            (100f to 305f) to "down left", (100f to 215f) to "up left",
        )) {
            press(1, at)
            assertEquals(expect, held())
            lift(1)
            assertEquals("", held())
        }
    }

    @Test fun oneThumbBetweenNeighbouringFaceButtonsPressesBoth() {
        for ((at, expect) in listOf(
            (900f to 215f) to "B Y", (900f to 305f) to "A B", (800f to 305f) to "A X", (800f to 215f) to "X Y",
        )) {
            press(1, at)
            assertEquals(expect, held())
            lift(1)
            assertEquals("", held())
        }
    }

    @Test fun rollingAThumbAcrossTheGapAddsThenDropsTheNeighbour() {
        press(1, 850f to 170f)
        assertEquals("Y", held())
        move(1, 900f to 215f)
        assertEquals("B Y", held())
        move(1, 950f to 260f)
        assertEquals("B", held())
        move(1, 900f to 215f)
        assertEquals("B Y", held())
        lift(1)
        assertEquals("", held())
    }

    @Test fun aThumbOnOneButtonOrBetweenOppositeOnesPressesOnlyOne() {
        press(1, 170f to 190f)
        assertEquals("up", held())
        lift(1)
        press(1, 850f to 260f)
        assertTrue(held().split(" ").size <= 1)
        lift(1)
        press(1, 150f to 260f)
        assertTrue(held().split(" ").size <= 1)
        lift(1)
    }

    @Test fun aComboThumbAndAnotherFingerShareAButton() {
        press(1, 900f to 215f)
        press(2, 950f to 260f)
        assertEquals("B Y", held())
        lift(1)
        assertEquals("B", held())
        lift(2)
        assertEquals("", held())
    }

    private fun held(): String {
        val s = state()
        return (listOf("A" to s.isDown(PadState.A), "B" to s.isDown(PadState.B), "X" to s.isDown(PadState.X), "Y" to s.isDown(PadState.Y),
            "up" to s.up, "right" to s.right, "down" to s.down, "left" to s.left).filter { it.second }.map { it.first }).joinToString(" ")
    }

    private fun move(id: Int, at: Pair<Float, Float>) {
        fingers[id] = at
        dispatch(MotionEvent.ACTION_MOVE)
    }

    private val fingers = LinkedHashMap<Int, Pair<Float, Float>>()

    private fun press(id: Int, at: Pair<Float, Float>) {
        fingers[id] = at
        val index = fingers.keys.indexOf(id)
        dispatch(if (fingers.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        dispatch(MotionEvent.ACTION_MOVE)
    }

    private fun lift(id: Int) {
        val index = fingers.keys.indexOf(id)
        dispatch(if (fingers.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        fingers.remove(id)
        if (fingers.isNotEmpty()) dispatch(MotionEvent.ACTION_MOVE)
    }

    private fun dispatch(action: Int) {
        val properties = fingers.keys.map { id -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coords = fingers.values.map { (px, py) -> MotionEvent.PointerCoords().apply { this.x = px; this.y = py; pressure = 1f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(1000L, time++, action, fingers.size, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
}
