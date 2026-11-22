package com.droiddeck.launcher.hud

import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.ui.Motion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DroidDeckHudHostInsetTest {
    private fun animate(scale: Float) {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        Motion.refresh(app)
    }

    @Before fun noAnimation() = animate(0f)

    @After fun restoreAnimation() = animate(1f)

    private fun show(size: String, x: Float, y: Float, padLeft: Int, padRight: Int): DroidDeckHudView {
        val app = RuntimeEnvironment.getApplication()
        SessionPrefs.setDroidDeckHudConfig(app, DroidDeckHudConfig.encode(DroidDeckHudConfig.DEFAULTS + ("hudSnap" to "1") + ("hudSize" to size)))
        SessionPrefs.setDroidDeckHudPosition(app, x, y)
        val root = FrameLayout(app)
        root.setPadding(padLeft, 0, padRight, 0)
        DroidDeckHudHost(app).attach(root, 0)
        val hud = root.getChildAt(0) as DroidDeckHudView
        hud.visibility = View.VISIBLE
        val exactly = { s: Int -> View.MeasureSpec.makeMeasureSpec(s, View.MeasureSpec.EXACTLY) }
        root.measure(exactly(2000), exactly(1000))
        root.layout(0, 0, 2000, 1000)
        hud.showForTest(DroidDeckHudPanelTest.SNAPSHOT, "DXVK", 0L)
        hud.requestLayout()
        root.measure(exactly(2000), exactly(1000))
        root.layout(0, 0, 2000, 1000)
        shadowOf(Looper.getMainLooper()).idle()
        return hud
    }

    @Test fun theStripSpansOnlyTheAreaInsideTheNotchPadding() {
        val hud = show("strip", 0f, 0f, padLeft = 120, padRight = 40)
        assertEquals(120f, hud.x, 0.5f)
        assertEquals(2000 - 120 - 40, hud.width)
    }

    @Test fun aHudSnappedLeftSitsBesideTheNotchInsteadOfUnderIt() {
        val hud = show("full", 0f, 300f, padLeft = 120, padRight = 0)
        assertEquals(120f, hud.x, 0.5f)
    }

    @Test fun aHudSnappedRightStopsAtTheRightPadding() {
        val hud = show("full", 1990f, 300f, padLeft = 0, padRight = 90)
        assertEquals(2000f - 90f, hud.x + hud.width, 0.5f)
    }

    @Test fun withoutPaddingTheHudStillReachesTheScreenEdges() {
        assertEquals(0f, show("full", 0f, 300f, 0, 0).x, 0.5f)
        val strip = show("strip", 0f, 0f, 0, 0)
        assertEquals(0f, strip.x, 0.5f)
        assertEquals(2000, strip.width)
    }

    @Test fun boundsAnchorBetweenThePaddedEdges() {
        val b = HudBounds(120f, 0f, 2000f, 1000f, 400f, 200f)
        assertEquals(1600f, b.maxX, 0f)
        assertEquals(800f, b.maxY, 0f)
        assertEquals(120f, b.anchoredX(0f), 0f)
        assertEquals(1600f, b.anchoredX(1f), 0f)
        assertEquals(0f, b.anchorFor(0f), 0f)
        assertEquals(0f, b.anchorFor(850f), 0f)
        assertEquals(1f, b.anchorFor(870f), 0f)
    }

    @Test fun boundsNeverInvertWhenTheHudIsWiderThanTheArea() {
        val b = HudBounds(120f, 50f, 600f, 300f, 900f, 900f)
        assertEquals(120f, b.maxX, 0f)
        assertEquals(50f, b.maxY, 0f)
    }
}
