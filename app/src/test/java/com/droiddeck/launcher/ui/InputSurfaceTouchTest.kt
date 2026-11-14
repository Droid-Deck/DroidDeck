package com.droiddeck.launcher.ui

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import com.droiddeck.launcher.input.*
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [JoyConPadTest.RecordingWriter::class])
class InputSurfaceTouchTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val bridge = PadBridge(File(context.cacheDir, "surface-touch"))
    private val events = mutableListOf<String>()
    private val router = InputRouter(object : InputOutput {
        override fun key(code: Int, down: Boolean) { events += "key $code $down" }
        override fun button(code: Int, down: Boolean) {}
        override fun pointer(dx: Float, dy: Float) {}
        override fun scroll(steps: Int) {}
        override fun command(command: InputCommand) { events += command.name }
        override fun command(command: InputCommand, role: InputRole) { events += "${command.name}:$role" }
        override fun requestMotion() {}
    })
    private lateinit var view: OnScreenControls
    private val a = InputSurfaceElement(InputControl.A, .7f, .3f, .2f, .2f)
    private val b = InputSurfaceElement(InputControl.B, .7f, .6f, .2f, .2f)
    private val stick = InputSurfaceElement(InputControl.LS, .1f, .3f, .2f, .4f, floating = false)
    private val keyboard = InputSurfaceElement(InputControl.A, .4f, .8f, .1f, .1f, "builtin:keyboard", name = "Keyboard")
    private var time = 1000L

    @Before fun setup() {
        bridge.setInputRouter(router)
        bridge.setInputProfile(InputProfile("touch", elements = mapOf(InputRole.PRIMARY to mapOf(
            "builtin:keyboard" to InputAction.Command(InputCommand.KEYBOARD), "latch" to InputAction.Keys(listOf(42))))))
        view = OnScreenControls(context, bridge)
        show(a, b, stick, keyboard)
    }

    @After fun close() { view.releaseAll(); bridge.stop() }

    private fun show(vararg elements: InputSurfaceElement) {
        view.updateLayout(SavedInputLayout("touch", "Touch", elements.toList(), aspect = 1000f / 600f))
        measure(view)
    }

    private fun measure(target: View) {
        target.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        target.layout(0, 0, 1000, 600)
    }

    private fun state() = JoyConPadTest.RecordingWriter.pads[0] ?: PadState()

    @Test fun aFingerSlidingBetweenButtonsMovesThePress() {
        touch(MotionEvent.ACTION_DOWN, 1 to (800f to 240f))
        assertTrue(state().isDown(PadState.A))
        touch(MotionEvent.ACTION_MOVE, 1 to (800f to 420f))
        assertFalse(state().isDown(PadState.A))
        assertTrue(state().isDown(PadState.B))
        touch(MotionEvent.ACTION_MOVE, 1 to (600f to 100f))
        assertFalse(state().isDown(PadState.B))
        touch(MotionEvent.ACTION_MOVE, 1 to (800f to 240f))
        assertTrue(state().isDown(PadState.A))
        touch(MotionEvent.ACTION_UP, 1 to (800f to 240f))
        assertFalse(state().isDown(PadState.A))
    }

    @Test fun aStickKeepsItsFingerWhenItCrossesAButton() {
        touch(MotionEvent.ACTION_DOWN, 1 to (200f to 300f))
        touch(MotionEvent.ACTION_MOVE, 1 to (800f to 240f))
        assertFalse(state().isDown(PadState.A))
        assertEquals(1f, state().leftX, .01f)
        touch(MotionEvent.ACTION_UP, 1 to (800f to 240f))
        assertEquals(0f, state().leftX, 0f)
    }

    @Test fun mouseClicksPassThroughToTheGame() {
        val missed = mutableListOf<Int>()
        view.onTouchMiss = { missed += it.actionMasked }
        touch(MotionEvent.ACTION_DOWN, 1 to (800f to 240f), tool = MotionEvent.TOOL_TYPE_MOUSE)
        touch(MotionEvent.ACTION_UP, 1 to (800f to 240f), tool = MotionEvent.TOOL_TYPE_MOUSE)
        assertFalse(state().isDown(PadState.A))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), missed)
        view.onTouchMiss = null
        assertFalse(touch(MotionEvent.ACTION_DOWN, 1 to (800f to 240f), tool = MotionEvent.TOOL_TYPE_MOUSE))
        assertFalse(state().isDown(PadState.A))
    }

    @Test fun keyboardShortcutOpensOnRelease() {
        touch(MotionEvent.ACTION_DOWN, 1 to (450f to 510f))
        assertTrue(events.isEmpty())
        touch(MotionEvent.ACTION_UP, 1 to (450f to 510f))
        assertEquals(listOf("KEYBOARD:PRIMARY"), events)
    }

    @Test fun cancelledDraggedOrDisabledKeyboardShortcutDoesNotOpen() {
        touch(MotionEvent.ACTION_DOWN, 1 to (450f to 510f))
        touch(MotionEvent.ACTION_CANCEL, 1 to (450f to 510f))
        touch(MotionEvent.ACTION_UP, 1 to (450f to 510f))
        touch(MotionEvent.ACTION_DOWN, 1 to (450f to 510f))
        touch(MotionEvent.ACTION_UP, 1 to (450f to 510f), flags = MotionEvent.FLAG_CANCELED)
        touch(MotionEvent.ACTION_DOWN, 1 to (450f to 510f))
        touch(MotionEvent.ACTION_MOVE, 1 to (600f to 510f))
        touch(MotionEvent.ACTION_UP, 1 to (600f to 510f))
        assertTrue(events.isEmpty())
        show(a, b, stick)
        view.setQuickHidden(true)
        measure(view)
        assertFalse(view.takesTouchAt(450f, 510f))
        assertFalse(touch(MotionEvent.ACTION_DOWN, 1 to (450f to 510f)))
        assertTrue(events.isEmpty())
    }

    @Test fun keyboardShortcutWorksWithHiddenPadAndButtonsOnly() {
        view.setQuickHidden(true)
        measure(view)
        assertFalse(view.takesTouchAt(800f, 240f))
        touch(MotionEvent.ACTION_DOWN, 1 to (450f to 510f))
        touch(MotionEvent.ACTION_UP, 1 to (450f to 510f))
        view.setButtonsOnly(true)
        measure(view)
        val (x, y) = centre(element(view, "builtin:keyboard"))
        assertFalse(view.takesTouchAt(800f, 240f))
        touch(MotionEvent.ACTION_DOWN, 1 to (x to y))
        touch(MotionEvent.ACTION_UP, 1 to (x to y))
        assertEquals(listOf("KEYBOARD:PRIMARY", "KEYBOARD:PRIMARY"), events)
    }

    @Test fun hidingTheViewReleasesLatchedKeys() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContentView(view)
            show(InputSurfaceElement(InputControl.A, .7f, .3f, .2f, .2f, "latch", toggle = true))
            touch(MotionEvent.ACTION_DOWN, 1 to (800f to 240f))
            touch(MotionEvent.ACTION_UP, 1 to (800f to 240f))
            assertEquals(listOf("key 42 true"), events)
            view.visibility = View.GONE
            assertEquals(listOf("key 42 true", "key 42 false"), events)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun onlyPanelsVibrateOnPress() {
        touch(MotionEvent.ACTION_DOWN, 1 to (800f to 240f))
        assertTrue(state().isDown(PadState.A))
        assertNotEquals(HapticFeedbackConstants.VIRTUAL_KEY, shadowOf(button(view)).lastHapticFeedbackPerformed())
        touch(MotionEvent.ACTION_UP, 1 to (800f to 240f))
        val panel = InputSurfaceView(context, bridge, listOf(a))
        measure(panel)
        touch(MotionEvent.ACTION_DOWN, 1 to (800f to 240f), target = panel)
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, shadowOf(button(panel)).lastHapticFeedbackPerformed())
        touch(MotionEvent.ACTION_CANCEL, 1 to (800f to 240f), target = panel)
    }

    @Test fun classicSticksTravelTheirFullRadius() {
        show(stick.copy(buttonShape = InputButtonShape.CIRCLE))
        touch(MotionEvent.ACTION_DOWN, 1 to (200f to 300f))
        touch(MotionEvent.ACTION_MOVE, 1 to (250f to 300f))
        assertEquals(.5f, state().leftX, .01f)
        touch(MotionEvent.ACTION_MOVE, 1 to (300f to 300f))
        assertEquals(1f, state().leftX, .01f)
        touch(MotionEvent.ACTION_UP, 1 to (300f to 300f))
    }

    @Test fun buttonsAcceptTouchesJustOutsideTheirEdge() {
        show(InputSurfaceElement(InputControl.A, .7f, .3f, .1f, 1f / 6f, buttonShape = InputButtonShape.CIRCLE),
            InputSurfaceElement(InputControl.B, .3f, .3f, .2f, .1f, buttonShape = InputButtonShape.PILL),
            InputSurfaceElement(InputControl.X, .3f, .6f, .2f, .1f))
        assertTrue(view.takesTouchAt(750f, 285f))
        assertTrue(view.takesTouchAt(805f, 230f))
        assertFalse(view.takesTouchAt(750f, 300f))
        assertFalse(view.takesTouchAt(805f, 285f))
        assertTrue(view.takesTouchAt(400f, 245f))
        assertFalse(view.takesTouchAt(400f, 255f))
        assertFalse(view.takesTouchAt(400f, 359f))
        assertTrue(view.takesTouchAt(400f, 361f))
        touch(MotionEvent.ACTION_DOWN, 1 to (750f to 285f))
        assertTrue(state().isDown(PadState.A))
        touch(MotionEvent.ACTION_UP, 1 to (750f to 285f))
    }

    @Test fun adaptiveSticksCaptureAroundThemAndTheLeftOneToTheEdge() {
        val left = InputSurfaceElement(InputControl.LS, .3f, .3f, .2f, .4f, hideWhenIdle = true)
        val right = InputSurfaceElement(InputControl.RS, .6f, .3f, .2f, .4f, hideWhenIdle = true)
        show(left, right)
        assertTrue(view.takesTouchAt(10f, 300f))
        assertTrue(view.takesTouchAt(10f, 390f))
        assertFalse(view.takesTouchAt(10f, 420f))
        assertTrue(view.takesTouchAt(795f, 300f))
        assertFalse(view.takesTouchAt(815f, 300f))
        val missed = mutableListOf<Int>()
        view.onTouchMiss = { missed += it.getPointerId(0) }
        touch(MotionEvent.ACTION_DOWN, 1 to (50f to 300f))
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 1 to (50f to 300f), 2 to (60f to 310f))
        assertEquals(listOf(2), missed)
        touch(MotionEvent.ACTION_MOVE, 1 to (150f to 300f), 2 to (60f to 310f))
        assertTrue(state().leftX > .9f)
        show(left.copy(hideWhenIdle = false), right.copy(hideWhenIdle = false))
        assertFalse(view.takesTouchAt(10f, 300f))
        assertFalse(view.takesTouchAt(815f, 300f))
    }

    private fun element(root: View, id: String): View = descendants(root).first { (it.tag as? InputSurfaceElement)?.id == id }
    private fun centre(target: View): Pair<Float, Float> {
        var x = target.width / 2f
        var y = target.height / 2f
        var at: View = target
        while (at !== view) { x += at.left; y += at.top; at = at.parent as View }
        return x to y
    }
    private fun button(root: View): View = descendants(root).first { it.javaClass.simpleName == "HoldButton" }
    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(descendants(root.getChildAt(i)))
    }

    private fun touch(action: Int, vararg pointers: Pair<Int, Pair<Float, Float>>, flags: Int = 0,
        tool: Int = MotionEvent.TOOL_TYPE_FINGER, target: View = view): Boolean {
        val properties = pointers.map { (id, _) -> MotionEvent.PointerProperties().apply { this.id = id; toolType = tool } }.toTypedArray()
        val coords = pointers.map { (_, position) -> MotionEvent.PointerCoords().apply { x = position.first; y = position.second; pressure = 1f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(1000L, time++, action, pointers.size, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, flags)
        return try { target.dispatchTouchEvent(event) } finally { event.recycle() }
    }
}
