package com.droiddeck.launcher.hud

import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import java.time.Duration
import java.util.function.BiConsumer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DroidDeckHudHostCollapseTest {
    private fun animate(scale: Float) {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        Motion.refresh(app)
    }

    @Before fun noAnimation() = animate(0f)

    @After fun restoreAnimation() = animate(1f)

    private fun collapse(snap: String, hudX: Float, hudY: Float, pressX: Float, pressY: Float, layoutDuringCollapse: Boolean = false): Triple<DroidDeckHudView, String, Pair<Float, Float>> {
        val app = RuntimeEnvironment.getApplication()
        SessionPrefs.setDroidDeckHudConfig(app, DroidDeckHudConfig.encode(DroidDeckHudConfig.DEFAULTS + ("hudSnap" to snap) + ("hudSize" to "full")))
        SessionPrefs.setDroidDeckHudPosition(app, hudX, hudY)
        val root = FrameLayout(app)
        val host = DroidDeckHudHost(app)
        host.attach(root, 0)
        val hud = root.getChildAt(0) as DroidDeckHudView
        hud.visibility = View.VISIBLE
        hud.showForTest(DroidDeckHudPanelTest.SNAPSHOT, "DXVK", 0L)
        val exactly = { size: Int -> View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY) }
        root.measure(exactly(2000), exactly(1000))
        root.layout(0, 0, 2000, 1000)
        hud.x = hudX; hud.y = hudY
        if (layoutDuringCollapse) {
            @Suppress("UNCHECKED_CAST")
            val collapse = DroidDeckHudView::class.java.getDeclaredField("onHoldListener").also { it.isAccessible = true }.get(hud) as BiConsumer<Float, Float>
            hud.setOnHoldListener { x, y ->
                collapse.accept(x, y)
                hud.requestLayout()
                root.measure(exactly(2000), exactly(1000))
                root.layout(0, 0, 2000, 1000)
            }
        }
        val down = SystemClock.uptimeMillis()
        MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, pressX, pressY, 0).also { root.dispatchTouchEvent(it); it.recycle() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 300L))
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, pressX, pressY, 0).also { root.dispatchTouchEvent(it); it.recycle() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        root.measure(exactly(2000), exactly(1000))
        root.layout(0, 0, 2000, 1000)
        shadowOf(Looper.getMainLooper()).idle()
        val stored = DroidDeckHudConfig.load(app)["hudDock"].orEmpty()
        return Triple(hud, stored, hud.x to hud.y)
    }

    @Test fun withSnappingTheHudCollapsesIntoTheEdgeNearestWhereThePressBegan() {
        assertEquals("top", collapse("1", 400f, 40f, 500f, 60f).second)
        assertEquals("left", collapse("1", 20f, 300f, 40f, 500f).second)
        assertEquals("right", collapse("1", 1500f, 300f, 1900f, 500f).second)
        assertEquals("bottom", collapse("1", 400f, 700f, 500f, 980f).second)
    }

    @Test fun withoutSnappingTheHudCollapsesWhereItWasTouched() {
        val (hud, stored, at) = collapse("0", 600f, 300f, 700f, 350f)
        assertEquals("float", stored)
        assertEquals(DroidDeckHudDock.FLOAT, hud.dock)
        val (tabW, tabH) = hud.dockTabSize()
        assertEquals(700f - tabW / 2f, at.first, 2f)
        assertEquals(350f - tabH / 2f, at.second, 2f)
    }

    @Test fun aLayoutPassDuringTheCollapseKeepsTheTabWhereThePressPutIt() {
        animate(1f)
        val (hud, stored, at) = collapse("1", 600f, 300f, 700f, 350f, layoutDuringCollapse = true)
        val (tabW, tabH) = hud.dockTabSize()
        assertEquals("top", stored)
        assertEquals(700f - tabW / 2f, SessionPrefs.droidDeckHudPosition(RuntimeEnvironment.getApplication())!!.first, 2f)
        assertEquals(700f - tabW / 2f, at.first, 2f)
    }
}
