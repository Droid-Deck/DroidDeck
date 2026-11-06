package com.droiddeck.launcher.input

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
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
class OnScreenControlsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val bridge = PadBridge(File(context.cacheDir, "surface-overlay"))
    private lateinit var view: OnScreenControls
    private val left = InputSurfaceElement(InputControl.LS, .1f, .3f, .2f, .4f)
    private val button = InputSurfaceElement(InputControl.A, .7f, .3f, .2f, .3f)
    private var time = 1000L
    @Before fun setup() {
        view = OnScreenControls(context, bridge)
        view.updateLayout(SavedInputLayout("test", "Test", listOf(left, button)))
        measure(view)
    }
    @After fun close() { view.releaseAll(); bridge.stop() }
    private fun state() = JoyConPadTest.RecordingWriter.pads[0] ?: PadState()
    private fun measure(target: View) {
        target.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        target.layout(0, 0, 1000, 600)
    }

    @Test fun independentFingersHoldStickAndButtonAndCancelReleasesBoth() {
        touch(MotionEvent.ACTION_DOWN, 3 to (200f to 300f))
        touch(MotionEvent.ACTION_MOVE, 3 to (245f to 300f))
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 3 to (245f to 300f), 8 to (800f to 250f))
        assertTrue(state().leftX > .5f)
        assertTrue(state().isDown(PadState.A))
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl 8), 3 to (245f to 300f), 8 to (800f to 250f))
        assertFalse(state().isDown(PadState.A))
        assertTrue(state().leftX > .5f)
        touch(MotionEvent.ACTION_CANCEL, 3 to (245f to 300f))
        assertEquals(0f, state().leftX, 0f)
    }

    @Test fun reloadCancelsHeldViewsSoTheNextPressWorks() {
        touch(MotionEvent.ACTION_DOWN, 0 to (800f to 250f))
        assertTrue(state().isDown(PadState.A))
        view.reload()
        assertFalse(state().isDown(PadState.A))
        touch(MotionEvent.ACTION_DOWN, 0 to (800f to 250f))
        assertTrue(state().isDown(PadState.A))
    }

    @Test fun replacingTheLayoutReleasesOldSourcesAndHitRegions() {
        touch(MotionEvent.ACTION_DOWN, 0 to (800f to 250f))
        view.updateLayout(SavedInputLayout("empty", "Empty"))
        measure(view)
        assertFalse(state().isDown(PadState.A))
        assertFalse(view.takesTouchAt(800f, 250f))
    }

    @Test fun emptySpaceDoesNotConsumeTheUnderlyingTrackpadStream() {
        val received = mutableListOf<Int>()
        val root = FrameLayout(context).apply {
            addView(View(context).apply { setOnTouchListener { _, e -> received += e.actionMasked; true } }, FrameLayout.LayoutParams(-1, -1))
            addView(view, FrameLayout.LayoutParams(-1, -1))
        }
        measure(root)
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(1000, time++, action, 500f, 100f, 0)
            try { assertTrue(root.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), received)
    }

    @Test fun backgroundFingerIsForwardedWhileAStickIsHeld() {
        val received = mutableListOf<Pair<Int, Int>>()
        view.onTouchMiss = { received += it.actionMasked to it.getPointerId(0) }
        touch(MotionEvent.ACTION_DOWN, 3 to (200f to 300f))
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 3 to (200f to 300f), 8 to (500f to 100f))
        touch(MotionEvent.ACTION_MOVE, 3 to (245f to 300f), 8 to (520f to 100f))
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl 8), 3 to (245f to 300f), 8 to (520f to 100f))
        assertEquals(listOf(MotionEvent.ACTION_DOWN to 8, MotionEvent.ACTION_MOVE to 8, MotionEvent.ACTION_UP to 8), received)
        assertTrue(state().leftX > .5f)
    }

    @Test fun aBackgroundStreamDoesNotTurnIntoAButtonWhenItCrossesOne() {
        val received = mutableListOf<Int>()
        view.onTouchMiss = { received += it.actionMasked }
        touch(MotionEvent.ACTION_DOWN, 3 to (500f to 100f))
        touch(MotionEvent.ACTION_MOVE, 3 to (800f to 250f))
        touch(MotionEvent.ACTION_UP, 3 to (800f to 250f))
        assertFalse(state().isDown(PadState.A))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), received)
    }

    @Test fun floatingAndFixedSticksUseTheirConfiguredOrigin() {
        for (floating in listOf(true, false)) {
            view.updateLayout(SavedInputLayout("test", "Test", listOf(left.copy(floating = floating))))
            measure(view)
            touch(MotionEvent.ACTION_DOWN, 3 to (230f to 300f))
            assertEquals(if (floating) 0f else .5f, state().leftX, .001f)
            touch(MotionEvent.ACTION_UP, 3 to (230f to 300f))
        }
    }

    @Test fun systemOnlyVisibilityUsesTheSameLayoutAndReleasesOtherButtons() {
        val guide = InputSurfaceElement(InputControl.GUIDE, .4f, .7f, .1f, .2f)
        view.updateLayout(SavedInputLayout("test", "Test", listOf(button, guide)))
        measure(view)
        touch(MotionEvent.ACTION_DOWN, 0 to (800f to 250f))
        view.setButtonsOnly(true)
        measure(view)
        assertFalse(state().isDown(PadState.A))
        assertFalse(view.takesTouchAt(800f, 250f))
        assertTrue(view.takesTouchAt(450f, 480f))
    }

    private fun touch(action: Int, vararg pointers: Pair<Int, Pair<Float, Float>>): Boolean {
        val properties = pointers.map { (id, _) -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coords = pointers.map { (_, position) -> MotionEvent.PointerCoords().apply { x = position.first; y = position.second; pressure = 1f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(1000L, time++, action, pointers.size, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        return try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
}
