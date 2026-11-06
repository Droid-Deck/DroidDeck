package com.droiddeck.launcher.ui

import android.content.ComponentName
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import android.view.View
import android.widget.FrameLayout
import com.droiddeck.launcher.input.*
import com.droiddeck.launcher.input.JoyConPadTest
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.droiddeck.launcher.input.FakeInputWriter"],
    shadows = [JoyConPadTest.RecordingWriter::class, SessionInputPointerTest.CompositorShadow::class])
class InputSurfaceViewTest {
    @Test fun customControlsReleaseOnDetachAndWorkWhenTheSameViewReturns() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val events = mutableListOf<Boolean>()
        val bridge = PadBridge(activity.cacheDir)
        val router = InputRouter(object : InputOutput {
            override fun key(code: Int, down: Boolean) { events += down }
            override fun button(code: Int, down: Boolean) {}
            override fun pointer(dx: Float, dy: Float) {}
            override fun scroll(steps: Int) {}
            override fun command(command: InputCommand) {}
            override fun requestMotion() {}
        })
        bridge.setInputRouter(router)
        bridge.setInputProfile(InputProfile("test", elements = mapOf(InputRole.SECONDARY to mapOf("one" to InputAction.Keys(listOf(57))))))
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val panel = InputSurfaceView(activity, bridge,
            listOf(InputSurfaceElement(InputControl.A, .2f, .2f, .2f, .2f, "one")))
        try {
            repeat(2) {
                root.addView(panel)
                panel.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
                panel.layout(0, 0, 600, 600)
                val down = MotionEvent.obtain(0, 1, MotionEvent.ACTION_DOWN, 150f, 150f, 0)
                try { assertTrue(panel.dispatchTouchEvent(down)) } finally { down.recycle() }
                assertEquals(true, events.last())
                root.removeView(panel)
                assertEquals(false, events.last())
            }
            assertEquals(listOf(true, false, true, false), events)
        } finally { root.removeAllViews(); bridge.stop(); controller.pause().stop().destroy() }
    }

    @Test fun takesTouchAtMatchesWhatATouchDoes() {
        val directory = Files.createTempDirectory("deck-panel").toFile()
        try {
            SessionState.deckPad = false
            val app = RuntimeEnvironment.getApplication()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            for (pad in listOf(null, PadBridge(directory))) for (portraitDock in listOf(false, true)) for (layout in InputStockLayouts.layouts()) {
                val name = "pad=${pad != null} portraitDock=$portraitDock"
                val panel = InputSurfaceView(activity, pad, layout.elements)
                activity.setContentView(panel)
                val (w, h) = if (portraitDock) 720 to 1000 else 1200 to 480
                panel.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                panel.layout(0, 0, w, h)
                var taken = 0
                var missed = 0
                for (x in 0 until w step w / 40) for (y in 0 until h step h / 24) {
                    val expected = panel.takesTouchAt(x.toFloat(), y.toFloat())
                    val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
                    assertEquals("$name ($x, $y)", expected, panel.dispatchTouchEvent(down))
                    down.recycle()
                    MotionEvent.obtain(0L, 1L, MotionEvent.ACTION_CANCEL, x.toFloat(), y.toFloat(), 0).also { panel.dispatchTouchEvent(it); it.recycle() }
                    if (expected) taken++ else missed++
                }
                assertTrue("$name: some points land on controls", taken > 0)
                assertTrue("$name: some points land between them", missed > 0)
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
