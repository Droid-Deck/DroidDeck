package com.droiddeck.launcher.ui

import android.content.ComponentName
import android.graphics.Color
import android.os.Looper
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import android.view.View
import android.widget.FrameLayout
import com.droiddeck.launcher.input.*
import com.droiddeck.launcher.input.JoyConPadTest
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.droiddeck.launcher.input.FakeInputWriter"],
    shadows = [JoyConPadTest.RecordingWriter::class, SessionInputPointerTest.CompositorShadow::class])
class InputSurfaceViewTest {
    @get:Rule val looper = MainLooperDrain()

    @Test fun labelsFollowTheControlSizeAndShrinkOnlyToFitItsWidth() {
        val threeLetters = { size: Float -> size * 3f }
        assertEquals(16f, fittedTextSize(16f, 100f, measure = threeLetters), 0f)
        assertEquals(32f, fittedTextSize(32f, 200f, measure = threeLetters), 0f)
        assertEquals(10f, fittedTextSize(16f, 30f, measure = threeLetters), .0001f)
        assertEquals(20f, fittedTextSize(32f, 60f, measure = threeLetters), .0001f)
    }

    @Test fun labelsNeverShrinkBelowTheFloor() {
        val threeLetters = { size: Float -> size * 3f }
        assertEquals(12f, fittedTextSize(16f, 30f, 12f, threeLetters), 0f)
        assertEquals(12f, fittedTextSize(8f, 100f, 12f, threeLetters), 0f)
        assertEquals(20f, fittedTextSize(32f, 60f, 12f, threeLetters), .0001f)
    }

    @Test fun controlColoursComeFromTheTint() {
        val look = ControlLook()
        look.tint(ControllerPrefs.STEAM_BLUE)
        assertEquals(ControlLook.SIGNAL, look.accent)
        assertEquals(Color.argb(140, 0x1A, 0x9F, 0xFF), look.stroke)
        assertEquals(Color.rgb(0xCA, 0xE8, 0xFF), look.ink)
        assertEquals(ControlLook.GLASS, look.fill(0f))
        assertEquals(Color.WHITE, look.ink(1f))
        assertEquals(look.accent, look.stroke(1f))
        val pressed = look.fill(1f)
        assertEquals(ControlLook.over(ControlLook.alpha(look.accent, .45f), ControlLook.GLASS), pressed)
        assertTrue(Color.blue(pressed) > Color.blue(ControlLook.GLASS) && Color.alpha(pressed) > Color.alpha(ControlLook.GLASS))

        val green = 0xFF3DDC84.toInt()
        look.tint(green)
        assertEquals(green, look.accent)
        assertEquals(green and 0xFFFFFF, look.stroke and 0xFFFFFF)
        assertTrue(Color.green(look.ink) > Color.green(green) && Color.red(look.ink) > Color.red(green))

        look.tint(null)
        assertEquals(ControlLook.NEUTRAL_INK, look.ink)
        assertEquals(ControlLook.SIGNAL, look.stroke(1f))
    }

    @Test fun colourMixingStaysWithinItsEnds() {
        assertEquals(Color.BLACK, ControlLook.mix(Color.BLACK, Color.WHITE, -.2f))
        assertEquals(Color.WHITE, ControlLook.mix(Color.BLACK, Color.WHITE, 1.2f))
        assertEquals(Color.rgb(128, 128, 128), ControlLook.mix(Color.BLACK, Color.WHITE, .5f))
        assertEquals(Color.RED, ControlLook.over(Color.RED, Color.BLUE))
        assertEquals(Color.BLUE, ControlLook.over(Color.TRANSPARENT, Color.BLUE))
    }

    @Test fun pressSquashesAndSpringsBackOrSnapsWithAnimationsOff() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = View(activity)
        activity.setContentView(view)
        val motion = ControlMotion(view, squash = true)
        motion.press(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals(1f, motion.value, 0f)
        assertEquals(1f - ControlMotion.SQUASH, view.scaleX, .0001f)
        motion.press(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(0f, motion.value, .0001f)
        assertEquals(1f, view.scaleX, .0001f)

        try {
            SessionPrefs.setAnimationsEnabled(activity, false)
            Motion.refresh(activity)
            motion.press(true)
            assertEquals(1f, motion.value, 0f)
            assertEquals(1f - ControlMotion.SQUASH, view.scaleY, .0001f)
        } finally {
            SessionPrefs.setAnimationsEnabled(activity, true)
            Motion.refresh(activity)
        }
    }

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

    @Test fun movingOrFadingAControlKeepsItsViewAndRenamingRebuildsIt() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val one = InputSurfaceElement(InputControl.A, .2f, .2f, .2f, .2f, "one")
        val panel = InputSurfaceView(activity, customLayout = listOf(one))
        fun control() = generateSequence(listOf<View>(panel)) { level -> level.flatMap { v -> (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).map(g::getChildAt) }.orEmpty() }.takeIf { it.isNotEmpty() } }
            .flatten().first { (it.tag as? InputSurfaceElement)?.id == "one" }
        val view = control()
        panel.updateCustomLayout(listOf(one.copy(x = .4f, width = .3f, opacity = .5f)))
        assertTrue(view === control())
        assertEquals(.5f, view.alpha, .001f)
        assertEquals(.4f, (view.tag as InputSurfaceElement).x, 0f)
        panel.updateCustomLayout(listOf(one.copy(name = "Jump")))
        assertTrue(view !== control())
    }

    @Test fun keyedDirectionsAreLabelledWithTheirKeysAndOtherActionsAreNot() {
        val context = RuntimeEnvironment.getApplication()
        val wasd = InputAction.Directions(17, 32, 31, 30, listOf(18, 0, 0, 16))
        assertEquals(listOf((0f to -1f) to "W", (1f to 0f) to "D", (0f to 1f) to "S", (-1f to 0f) to "A"),
            InputSurfaceView.directionLabels(context, wasd, cardinalOnly = true))
        assertEquals(listOf("W", "D", "S", "A", "E", "Q"), InputSurfaceView.directionLabels(context, wasd, cardinalOnly = false).map { it.second })
        assertEquals(listOf("W", "A"), InputSurfaceView.directionLabels(context, InputAction.Directions(17, 0, 0, 30), cardinalOnly = false).map { it.second })
        assertTrue(InputSurfaceView.directionLabels(context, null, cardinalOnly = false).isEmpty())
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
