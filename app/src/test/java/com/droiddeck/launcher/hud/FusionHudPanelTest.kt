package com.droiddeck.launcher.hud

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.Base64
import kotlin.math.roundToInt
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FusionHudPanelTest {
    @Test fun minimalIsTheSizeTheHudStartsWith() {
        assertEquals(FusionSize.MINIMAL, FusionSize.from(FusionHudConfig.DEFAULTS["hudSize"]))
    }

    @Test fun theHudStartsLocked() {
        assertEquals("1", FusionHudConfig.DEFAULTS["hudLocked"])
    }

    @Test fun everySizeDrawsTheDPanelWithEverythingInsideIt() {
        val stats = FrameStats()
        var now = 1_000_000_000L
        repeat(2_000) { i ->
            val frametime = if (i == 1_700) 25_600_000L else 2_850_000L
            now += frametime
            stats.add(frametime, now)
        }
        val snapshot = SNAPSHOT
        for (size in FusionSize.values()) {
            val view = FusionHudView(RuntimeEnvironment.getApplication())
            view.applyConfig(FusionHudConfig.encode(FusionHudConfig.DEFAULTS + ("hudSize" to size.token)))
            view.setFrameStats(stats)
            view.showForTest(snapshot, "DXVK", now)
            val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            view.measure(unspecified, unspecified)
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))

            val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("FUSIONHUD-${size.token.uppercase()}-PNG ${Base64.getEncoder().encodeToString(png.toByteArray())}")

            val panel = view.panelForTest()!!
            assertTrue("$size panel inside the view",
                panel.left > 0f && panel.top > 0f && panel.right < view.width && panel.bottom < view.height)
            view.graphForTest()?.let { graph ->
                val r = view.panelRadiusForTest()
                val d = maxOf(panel.top + r - graph.top, graph.bottom - (panel.bottom - r), 0f)
                val edge = panel.right - r + sqrt(r * r - d * d)
                assertTrue("$size graph clears the round side", graph.right <= edge)
            }
            val orb = bitmap.getPixel(panel.left.roundToInt() + 1, panel.centerY().roundToInt())
            assertTrue("$size orb on the left side", Color.blue(orb) > 200 && Color.alpha(orb) > 200)
        }
    }

    @Test fun pillShowsEachBatteryPartOnItsOwnSwitch() {
        fun pill(vararg settings: Pair<String, String>): List<String> {
            val view = FusionHudView(RuntimeEnvironment.getApplication())
            view.applyConfig(FusionHudConfig.encode(FusionHudConfig.DEFAULTS + ("hudSize" to FusionSize.PILL.token) + settings))
            view.showForTest(SNAPSHOT, "DXVK", 0L)
            return view.linesForTest()
        }
        val all = pill("showBattery" to "1")
        assertTrue(all.toString(), all.any { "BAT 79% · 34°C · 16.0W" in it })
        assertEquals(all.toString(), 1, all.count { "BAT" in it })
        assertFalse(all.toString(), all.single { "RAM" in it }.contains("BAT"))

        val noMemory = pill("showBattery" to "1", "showRAM" to "0")
        assertTrue(noMemory.toString(), noMemory.none { "RAM" in it })
        assertTrue(noMemory.toString(), noMemory.any { "BAT 79% · 34°C · 16.0W" in it })

        val defaults = pill()
        assertTrue(defaults.toString(), defaults.any { "BAT 34°C · 16.0W" in it })
        val wattsOnly = pill("showBatteryTemp" to "0")
        assertTrue(wattsOnly.toString(), wattsOnly.any { "BAT 16.0W" in it })
        val nothing = pill("showBatteryTemp" to "0", "showPower" to "0")
        assertTrue(nothing.toString(), nothing.none { "BAT" in it })
    }

    @Test fun aLockedHudLetsTouchesThroughOnlyWhereAControlTakesThem() {
        val app = RuntimeEnvironment.getApplication()
        var reached = 0
        val under = View(app).apply {
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) reached++; true }
        }
        val hud = FusionHudView(app)
        val parent = FrameLayout(app)
        parent.addView(under, FrameLayout.LayoutParams(2000, 2000))
        parent.addView(hud, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        var control = false
        hud.passesTouchAt = { _, _ -> control }
        val locks = ArrayList<Boolean>()
        hud.setOnLockChangedListener { locks += it }
        hud.applyConfig(FusionHudConfig.encode(FusionHudConfig.DEFAULTS + ("hudLocked" to "0")))
        hud.showForTest(SNAPSHOT, "DXVK", 0L)
        val exactly = View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)
        parent.measure(exactly, exactly)
        parent.layout(0, 0, 2000, 2000)
        assertTrue(hud.width > 0 && hud.height > 0)
        fun touch(holdMs: Long = 50L) {
            val x = hud.width / 2f; val y = hud.height / 2f
            val down = SystemClock.uptimeMillis()
            MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0).also { parent.dispatchTouchEvent(it); it.recycle() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(holdMs))
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0).also { parent.dispatchTouchEvent(it); it.recycle() }
            shadowOf(Looper.getMainLooper()).idle()
        }
        control = true
        touch()
        assertEquals("an unlocked HUD keeps the touch (tap, drag, long press)", 0, reached)
        hud.applyConfig(FusionHudConfig.encode(FusionHudConfig.DEFAULTS + ("hudLocked" to "1")))
        touch()
        assertEquals("a locked HUD passes it to the control under it", 1, reached)
        control = false
        touch()
        assertEquals("a locked HUD keeps a touch no control takes", 1, reached)
        touch(holdMs = ViewConfiguration.getLongPressTimeout() + 300L)
        assertEquals("a long press there unlocks it", listOf(false), locks)
    }

    private companion object {
        val SNAPSHOT = HudMetrics.Snapshot(64, 52, 3302, 1050, 89, 79, 10_800_000_000L, 16_000_000_000L, 67f,
            1_800_000_000L, HudMetrics.Battery(16f, false, 79, 34, null),
            intArrayOf(91, 91, 48, 50, 48, 19, 17, 73), intArrayOf(2265, 2265, 3148, 3148, 3148, 2956, 2956, 3302),
            2_700_000_000L, 6_400_000_000L, 31_000L, 9_000L)
    }
}
