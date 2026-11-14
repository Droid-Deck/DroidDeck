package com.droiddeck.launcher.ui

import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import com.droiddeck.launcher.input.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [JoyConPadTest.RecordingWriter::class])
class InputSurfaceGesturesTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity = controller.get()
    private val events = mutableListOf<String>()
    private val moves = mutableListOf<Pair<Float, Float>>()
    private val bridge = PadBridge(activity.cacheDir)
    private val router = InputRouter(object : InputOutput {
        override fun key(code: Int, down: Boolean) { events += "key:$code:$down" }
        override fun button(code: Int, down: Boolean) { events += "button:$code:$down" }
        override fun pointer(dx: Float, dy: Float) { moves += dx to dy }
        override fun scroll(steps: Int) { events += "scroll:$steps" }
        override fun command(command: InputCommand) {}
        override fun requestMotion() { error("Touchpad must not schedule stick frames") }
    })
    private lateinit var panel: InputSurfaceView
    private lateinit var pad: View
    private var time = 1000L
    private val element = InputSurfaceElement(InputControl.RIGHT_PAD, 0f, 0f, 1f, 1f, mouseGestures = true)
    private fun profile(action: InputAction = InputAction.Pointer()) = InputProfile("test",
        elements = mapOf(InputRole.SECONDARY to mapOf(element.id to action)),
        clicks = mapOf(InputRole.SECONDARY to mapOf(element.id to InputAction.MouseButton(272))))

    @Before fun setup() {
        bridge.setInputRouter(router)
        bridge.setInputProfile(profile())
        panel = InputSurfaceView(activity, bridge, listOf(element))
        activity.setContentView(panel)
        panel.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        panel.layout(0, 0, 1000, 600)
        pad = descendants(panel).first { it.javaClass.simpleName == "TrackpadView" }
    }
    @After fun close() { panel.releaseInputs(); bridge.stop(); controller.pause().stop().destroy() }

    @Test fun tapClicksWithoutRepositioningCursorFromAnotherSource() {
        touch(MotionEvent.ACTION_DOWN); touch(MotionEvent.ACTION_UP)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(listOf("button:272:true", "button:272:false"), events)
        assertTrue(moves.isEmpty())
        val other = router.channel(InputRole.PHYSICAL, InputControl.LS, fixedAction = InputAction.Pointer())
        other.update(0f, 0f, true); other.pointerDelta(.1f, .2f); other.update(0f)
        val positionChanges = moves.toList()
        time += 1000
        touch(MotionEvent.ACTION_DOWN); touch(MotionEvent.ACTION_UP)
        assertEquals(positionChanges, moves)
        other.close()
    }

    @Test fun tapUsesTheConfiguredElementClick() {
        bridge.setInputProfile(profile().copy(clicks = mapOf(InputRole.SECONDARY to mapOf(element.id to InputAction.Keys(listOf(57))))))
        touch(MotionEvent.ACTION_DOWN); touch(MotionEvent.ACTION_UP)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(listOf("key:57:true", "key:57:false"), events)
    }

    @Test fun motionDeliversImmediatelyAndTwoFingerScrollDoesNotMoveThePointer() {
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_MOVE, y = 230f)
        assertTrue(moves.isNotEmpty())
        touch(MotionEvent.ACTION_UP)
        time += 1000
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), fingers = 2)
        val before = moves.size
        touch(MotionEvent.ACTION_MOVE, y = 270f, fingers = 2)
        assertEquals(before, moves.size)
        assertTrue(events.any { it.startsWith("scroll:") })
    }

    @Test fun profileChangeAndMenuSuspensionSuppressPendingGesturePresses() {
        touch(MotionEvent.ACTION_DOWN)
        bridge.setInputEnabled(false)
        bridge.setInputEnabled(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        assertTrue(events.isEmpty())
        touch(MotionEvent.ACTION_CANCEL)
        time += 1000
        touch(MotionEvent.ACTION_DOWN)
        bridge.setInputProfile(profile(InputAction.Disabled))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        assertTrue(events.isEmpty())
        panel.releaseInputs()
        bridge.setInputProfile(profile())
        time += 1000
        touch(MotionEvent.ACTION_DOWN); touch(MotionEvent.ACTION_UP)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(listOf("button:272:true", "button:272:false"), events)
    }

    private fun touch(action: Int, y: Float = 200f, fingers: Int = 1) {
        val properties = Array(fingers) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(fingers) { i -> MotionEvent.PointerCoords().apply { x = 200f + 100 * i; this.y = y; pressure = 1f; size = 1f } }
        val event = MotionEvent.obtain(time - 20, time, action, fingers, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try { assertTrue(pad.onTouchEvent(event)) } finally { event.recycle(); time += 10 }
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
