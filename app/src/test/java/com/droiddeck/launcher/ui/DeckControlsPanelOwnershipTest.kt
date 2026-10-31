package com.droiddeck.launcher.ui

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.DeckControls
import com.droiddeck.launcher.input.JoyConPadTest
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.input.PadState
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [JoyConPadTest.RecordingWriter::class])
class DeckControlsPanelOwnershipTest {
    private val controller = Robolectric.buildActivity(Activity::class.java).setup()
    private val activity = controller.get()
    private val root = FrameLayout(activity)
    private val bridge = PadBridge(File(activity.cacheDir, "panel-ownership"))
    private lateinit var panel: DeckControlsPanel
    private val previousDeckPad = SessionState.deckPad

    @Before fun setUp() {
        SessionState.deckPad = true
        DeckControls.releaseAll()
        SessionPrefs.setGamepadSurface(activity, false, SessionPrefs.SURFACE_STICK)
        SessionPrefs.setGamepadSurface(activity, true, SessionPrefs.SURFACE_TRACKPAD)
        activity.setContentView(root)
        panel = DeckControlsPanel(activity, {}, {}, {}, bridge)
        root.addView(panel)
        descendants(panel).filterIsInstance<TextView>().first { it.text == activity.getString(R.string.deck_tab_gamepad) }.performClick()
        val trackpad = child("TrackpadView")
        trackpad.layout(0, 0, 300, 300)
        touch(trackpad, MotionEvent.ACTION_DOWN, 200f, 100f)
        touch(trackpad, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200f, 100f, fingers = 2)
        assertEquals(32 or 128, JoyConPadTest.RecordingWriter.grips)
    }

    @After fun close() {
        root.removeAllViews()
        bridge.stop()
        DeckControls.releaseAll()
        controller.pause().stop().destroy()
        SessionState.deckPad = previousDeckPad
    }

    @Test fun switchingTheOtherStickKeepsTrackpadContactAndClickHeld() {
        val stick = child("StickView")
        stick.layout(0, 0, 300, 300)
        touch(stick, MotionEvent.ACTION_DOWN, 150f, 150f)
        touch(stick, MotionEvent.ACTION_MOVE, 240f, 150f)
        assertTrue(state().leftX > 0f)
        val position = JoyConPadTest.RecordingWriter.deckPads.copyOf()
        descendants(stick.parent as View).first { it.contentDescription == activity.getString(R.string.deck_surface_switch) }.performClick()
        assertEquals(0f, state().leftX)
        assertTrackpadStillHeld(position)
        root.removeView(panel)
        assertEquals(0, JoyConPadTest.RecordingWriter.grips)
    }

    @Test fun removingDirectionPadReleasesOnlyItsOwnInput() {
        val dpad = child("DirectionPad")
        dpad.layout(0, 0, 300, 300)
        touch(dpad, MotionEvent.ACTION_DOWN, 150f, 30f)
        assertTrue(state().up)
        val position = JoyConPadTest.RecordingWriter.deckPads.copyOf()
        (dpad.parent as ViewGroup).removeView(dpad)
        assertFalse(state().up)
        assertTrackpadStillHeld(position)
    }

    @Test fun removingFaceButtonsReleasesOnlyTheirOwnInput() {
        val buttons = child("FaceButtons")
        buttons.layout(0, 0, 300, 300)
        touch(buttons, MotionEvent.ACTION_DOWN, 150f, 240f)
        assertTrue(state().isDown(PadState.A))
        val position = JoyConPadTest.RecordingWriter.deckPads.copyOf()
        (buttons.parent as ViewGroup).removeView(buttons)
        assertFalse(state().isDown(PadState.A))
        assertTrackpadStillHeld(position)
    }

    @Test fun switchingTabsRemovesTheOldTouchSourceAfterChildrenRelease() {
        val routed = recordTouchRoute()
        holdFaceButton()
        assertTrue(routed.last().isDown(PadState.A))
        descendants(panel).filterIsInstance<TextView>().first { it.text == activity.getString(R.string.deck_tab_grips) }.performClick()
        assertFalse(routed.last().isDown(PadState.A))
        assertEquals(0, JoyConPadTest.RecordingWriter.grips)
        routed.clear()
        bridge.applyExternal({ it.press(PadState.B, true) }, true)
        assertTrue("removed tab still forwards a neutral touch source", routed.isEmpty())
        assertTrue(state().isDown(PadState.B))

        descendants(panel).filterIsInstance<TextView>().first { it.text == activity.getString(R.string.deck_tab_gamepad) }.performClick()
        holdFaceButton()
        assertTrue(routed.last().isDown(PadState.A))
    }

    @Test fun removingThePanelLeavesNoTouchSourceForLaterPhysicalInput() {
        val routed = recordTouchRoute()
        holdFaceButton()
        root.removeView(panel)
        assertFalse(routed.last().isDown(PadState.A))
        routed.clear()
        bridge.applyExternal({ it.press(PadState.B, true) }, true)
        assertTrue("removed panel still forwards a neutral touch source", routed.isEmpty())
        assertTrue(state().isDown(PadState.B))
    }

    private fun holdFaceButton() {
        val buttons = child("FaceButtons")
        buttons.layout(0, 0, 300, 300)
        touch(buttons, MotionEvent.ACTION_DOWN, 150f, 240f)
    }

    private fun recordTouchRoute(): MutableList<PadState> {
        val routed = mutableListOf<PadState>()
        bridge.setTouchRoute(object : PadBridge.TouchRoute {
            override fun active() = true
            override fun accept(state: PadState) { routed += PadState().apply { copyFrom(state) } }
        })
        return routed
    }

    private fun assertTrackpadStillHeld(position: ShortArray) {
        assertEquals(32 or 128, JoyConPadTest.RecordingWriter.grips)
        assertArrayEquals(position, JoyConPadTest.RecordingWriter.deckPads)
    }

    private fun state() = JoyConPadTest.RecordingWriter.pads[0]!!
    private fun child(name: String) = descendants(panel).first { it.javaClass.simpleName == name }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    private fun touch(view: View, action: Int, x: Float, y: Float, fingers: Int = 1) {
        val properties = Array(fingers) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(fingers) { MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f } }
        val event = MotionEvent.obtain(1000, 1010, action, fingers, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try { assertTrue(view.onTouchEvent(event)) } finally { event.recycle() }
    }
}
