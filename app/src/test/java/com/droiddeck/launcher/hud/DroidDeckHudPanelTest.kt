package com.droiddeck.launcher.hud

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.ui.Themes
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
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DroidDeckHudPanelTest {
    @Test fun minimalIsTheSizeTheHudStartsWith() {
        assertEquals(DroidDeckHudSize.MINIMAL, DroidDeckHudSize.from(DroidDeckHudConfig.DEFAULTS["hudSize"]))
    }

    @Test fun theHudStartsLocked() {
        assertEquals("1", DroidDeckHudConfig.DEFAULTS["hudLocked"])
    }

    @Test fun lowMetricsCanBeHiddenIndependentlyInEveryLayoutThatShowsThem() {
        val metrics = listOf("showLow1" to "1%", "showLow01" to "0.1%", "showLow001" to "0.01%")
        for (size in DroidDeckHudSize.values().filter { it != DroidDeckHudSize.PILL }) {
            for (mask in 0..7) {
                val app = RuntimeEnvironment.getApplication()
                val parent = FrameLayout(app)
                val view = DroidDeckHudView(app)
                parent.addView(view)
                parent.layout(0, 0, 4000, 1080)
                val settings = metrics.mapIndexed { index, (key, _) ->
                    key to if (mask and (1 shl index) != 0) "1" else "0"
                }.toMap()
                view.applyConfig(DroidDeckHudConfig.encode(DroidDeckHudConfig.DEFAULTS +
                    DroidDeckHudConfig.ORDER.associateWith { "0" } + settings + mapOf(
                    "hudSize" to size.token, "showFPSGraph" to "0", "showClock" to "0")))
                view.showForTest(SNAPSHOT, "DXVK", 0L)
                val lines = view.linesForTest().joinToString(" ")
                for ((index, metric) in metrics.withIndex()) {
                    val supported = metrics[index].first in size.rows
                    val expected = supported && mask and (1 shl index) != 0
                    val label = Regex("(?<![0-9.])${Regex.escape(metric.second)}")
                    assertEquals("$size, mask=$mask, ${metric.second}: $lines", expected, label.containsMatchIn(lines))
                }
            }
        }
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
        for (size in DroidDeckHudSize.values()) {
            if (size == DroidDeckHudSize.STRIP) continue
            val view = DroidDeckHudView(RuntimeEnvironment.getApplication())
            view.applyConfig(DroidDeckHudConfig.encode(DroidDeckHudConfig.DEFAULTS + ("hudSize" to size.token)))
            view.setFrameStats(stats)
            view.showForTest(snapshot, "DXVK", now)
            val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            view.measure(unspecified, unspecified)
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))

            val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("DROIDDECKHUD-${size.token.uppercase()}-PNG ${Base64.getEncoder().encodeToString(png.toByteArray())}")

            val panel = view.panelForTest()!!
            assertTrue("$size panel inside the view",
                panel.left > 0f && panel.top > 0f && panel.right < view.width && panel.bottom < view.height)
            view.graphForTest()?.let { graph ->
                val r = view.panelRadiusForTest()
                val d = maxOf(panel.top + r - graph.top, graph.bottom - (panel.bottom - r), 0f)
                val edge = panel.right - r + sqrt(r * r - d * d)
                assertTrue("$size graph clears the round side", graph.right <= edge)
            }
        }
    }

    @Test fun aSwitchTurnsItsRowOff() {
        fun full(vararg settings: Pair<String, String>): List<String> {
            val values = LinkedHashMap(DroidDeckHudConfig.DEFAULTS)
            values["hudSize"] = DroidDeckHudSize.FULL.token
            for ((key, value) in settings) values[key] = value
            val view = DroidDeckHudView(RuntimeEnvironment.getApplication())
            view.applyConfig(DroidDeckHudConfig.encode(values))
            view.showForTest(SNAPSHOT, "DXVK", 0L)
            return view.linesForTest()
        }
        assertTrue(full().toString(), full().any { "RAM67%" in it })
        val noRam = full("showRAM" to "0")
        assertFalse(noRam.toString(), noRam.any { "RAM67%" in it })
        assertTrue(noRam.toString(), noRam.any { "FPS0.0" in it })
    }

    @Test fun theSavedOrderDecidesTheRowOrder() {
        fun only(order: String): List<String> {
            val off = DroidDeckHudConfig.ORDER.associateWith { "0" }
            val view = DroidDeckHudView(RuntimeEnvironment.getApplication())
            view.applyConfig(DroidDeckHudConfig.encode(
                DroidDeckHudConfig.DEFAULTS + off + mapOf(
                    "hudSize" to DroidDeckHudSize.FULL.token,
                    "showPower" to "1", "showFPS" to "1", "showRAM" to "1",
                    "showFPSGraph" to "0", "showClock" to "0", "hudOrder" to order,
                )))
            view.showForTest(SNAPSHOT, "DXVK", 0L)
            return view.linesForTest().filter { !it.startsWith("DroidDeck") }
        }
        val powerFirst = only("full:showPower|showFPS|showRAM")
        assertEquals(powerFirst.toString(), listOf("PWR", "FPS", "RAM"), powerFirst.map { it.take(3) })
        val ramFirst = only("full:showRAM|showFPS|showPower")
        assertEquals(ramFirst.toString(), listOf("RAM", "FPS", "PWR"), ramFirst.map { it.take(3) })
    }

    @Test fun thePillHeroIsWhicheverRowTheUserPutsFirst() {
        fun pill(order: String): List<String> {
            val off = DroidDeckHudConfig.ORDER.associateWith { "0" }
            val view = DroidDeckHudView(RuntimeEnvironment.getApplication())
            view.applyConfig(DroidDeckHudConfig.encode(
                DroidDeckHudConfig.DEFAULTS + off + mapOf(
                    "hudSize" to DroidDeckHudSize.PILL.token,
                    "showFPS" to "1", "showRAM" to "1", "showPower" to "1",
                    "showFPSGraph" to "0", "showClock" to "0",
                    "hudOrder" to order,
                )))
            view.showForTest(SNAPSHOT, "DXVK", 0L)
            return view.linesForTest()
        }
        val fpsFirst = pill("pill:showFPS|showRAM|showPower")
        assertTrue(fpsFirst.toString(), fpsFirst.any { it.trim() == "0.0fps" })
        val fpsLast = pill("pill:showRAM|showPower|showFPS")
        assertTrue(fpsLast.toString(), fpsLast.any { it.trim().startsWith("FPS") })
        assertFalse(fpsLast.toString(), fpsLast.any { it.trim() == "0.0fps" })
    }

    @Test fun stripIsOneLineAcrossTheParentWithTheClockOnTheRight() {
        val app = RuntimeEnvironment.getApplication()
        val parent = FrameLayout(app)
        val view = DroidDeckHudView(app)
        parent.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        parent.measure(View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, 2400, 1080)
        view.applyConfig(DroidDeckHudConfig.encode(DroidDeckHudConfig.DEFAULTS + ("hudSize" to DroidDeckHudSize.STRIP.token) +
            ("showBattery" to "1")))
        view.showForTest(SNAPSHOT, "DXVK", 0L)
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(unspecified, unspecified)

        assertTrue(view.isStrip)
        assertEquals(2400, view.measuredWidth)
        val lines = view.linesForTest()
        assertEquals(lines.toString(), 1, lines.size)
        val line = lines.single()
        listOf("DXVK", "fps", "GPU", "CPU", "RAM", "BAT 79%").forEach { assertTrue(line, it in line) }
        assertTrue(line, line.indexOf("DXVK") < line.indexOf("GPU") && line.indexOf("GPU") < line.indexOf("BAT"))

        val bitmap = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        view.draw(Canvas(bitmap))
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("DROIDDECKHUD-STRIP-PNG ${Base64.getEncoder().encodeToString(png.toByteArray())}")
    }

    @Test fun stripLeavesOffWhatNoLongerFitsBeforeTheClock() {
        val app = RuntimeEnvironment.getApplication()
        val parent = FrameLayout(app)
        val view = DroidDeckHudView(app)
        parent.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        parent.measure(View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, 700, 400)
        view.applyConfig(DroidDeckHudConfig.encode(DroidDeckHudConfig.DEFAULTS + ("hudSize" to DroidDeckHudSize.STRIP.token)))
        view.showForTest(SNAPSHOT, "DXVK", 0L)
        val line = view.linesForTest().single()
        assertTrue(line, "fps" in line)
        assertFalse(line, "BAT" in line)
    }

    @Test fun stripGraphClearsThePreviousTraceWhenFrameHistoryChanges() {
        val app = RuntimeEnvironment.getApplication()
        val parent = FrameLayout(app)
        val view = DroidDeckHudView(app)
        parent.addView(view)
        parent.layout(0, 0, 2400, 1080)
        val stats = FrameStats()
        var now = 1_000_000_000L
        repeat(300) { now += 25_000_000L; stats.add(25_000_000L, now) }
        view.setFrameStats(stats)
        view.applyConfig(DroidDeckHudConfig.encode(DroidDeckHudConfig.DEFAULTS +
            ("hudSize" to "strip") + ("showFPSGraph" to "1") + ("hudOpacity" to "100")))
        view.showForTest(SNAPSHOT, "DXVK", now)
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(unspecified, unspecified)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        fun render() = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
            view.draw(Canvas(it))
        }
        val graph = requireNotNull(view.graphForTest())
        val x = (graph.left + graph.width() * 0.75f).toInt()
        val y = graph.centerY().toInt()
        val before = render()
        stats.reset()
        val after = render()
        assertTrue("the graph initially crosses its midpoint", before.getPixel(x, y) != after.getPixel(x, y))
        assertEquals("the previous trace is cleared to the strip background",
            after.getPixel(x, graph.top.toInt() + 2), after.getPixel(x, y))
    }

    @Test fun stripMarkUpdatesAfterThemeChangesWithoutChangingSize() {
        val app = RuntimeEnvironment.getApplication()
        val oldTheme = SessionPrefs.theme(app)
        val parent = FrameLayout(app)
        val view = DroidDeckHudView(app)
        parent.addView(view)
        parent.layout(0, 0, 2400, 1080)
        fun render(theme: String): Bitmap {
            SessionPrefs.setTheme(app, theme)
            view.applyConfig(DroidDeckHudConfig.encode(DroidDeckHudConfig.DEFAULTS + ("hudSize" to "strip")))
            view.showForTest(SNAPSHOT, "DXVK", 0L)
            val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            view.measure(unspecified, unspecified)
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        try {
            val first = render(Themes.GRAPHITE)
            val mark = DroidDeckHudView::class.java.getDeclaredField("headerMark").let {
                it.isAccessible = true
                RectF(it.get(view) as RectF)
            }
            val second = render(Themes.ELECTRIC)
            val x = (mark.left + mark.width() / 2f).toInt()
            val y = mark.centerY().toInt()
            assertEquals(first.width, second.width)
            assertEquals(first.height, second.height)
            assertTrue("the mark orb follows the current theme", first.getPixel(x, y) != second.getPixel(x, y))
            assertEquals(View.LAYER_TYPE_NONE, view.layerType)
        } finally {
            SessionPrefs.setTheme(app, oldTheme)
        }
    }

    @Test fun stripFollowsTheHudScaleInHeightAndKeepsTheFullWidth() {
        val app = RuntimeEnvironment.getApplication()
        val parent = FrameLayout(app)
        val view = DroidDeckHudView(app)
        parent.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        parent.measure(View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, 2400, 1080)
        fun at(scale: Int): Pair<Float, Float> {
            view.applyConfig(DroidDeckHudConfig.encode(DroidDeckHudConfig.DEFAULTS + ("hudSize" to DroidDeckHudSize.STRIP.token) +
                ("hudScale" to scale.toString())))
            view.showForTest(SNAPSHOT, "DXVK", 0L)
            val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            view.measure(unspecified, unspecified)
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val bitmap = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("DROIDDECKHUD-STRIP$scale-PNG ${Base64.getEncoder().encodeToString(png.toByteArray())}")
            return Pair(view.contentWidth, view.contentHeight)
        }
        val small = at(70); val big = at(150)
        assertEquals(2400f, small.first); assertEquals(2400f, big.first)
        assertEquals(big.second / small.second, 150f / 70f, 0.01f)
    }

    @Test fun pillShowsEachBatteryPartOnItsOwnSwitch() {
        fun pill(vararg settings: Pair<String, String>): String {
            val view = DroidDeckHudView(RuntimeEnvironment.getApplication())
            view.applyConfig(DroidDeckHudConfig.encode(DroidDeckHudConfig.DEFAULTS + ("hudSize" to DroidDeckHudSize.PILL.token) + settings))
            view.showForTest(SNAPSHOT, "DXVK", 0L)
            return view.linesForTest().toString()
        }
        val defaults = pill()
        assertTrue(defaults, defaults.contains("BAT.T"))
        assertTrue(defaults, defaults.contains("PWR"))
        assertFalse(defaults, defaults.contains("BAT79%"))
        val levelOn = pill("showBattery" to "1")
        assertTrue(levelOn, levelOn.contains("BAT79%"))
        val tempOff = pill("showBatteryTemp" to "0")
        assertFalse(tempOff, tempOff.contains("BAT.T"))
        assertTrue(tempOff, tempOff.contains("PWR"))
        val powerOff = pill("showPower" to "0")
        assertFalse(powerOff, powerOff.contains("PWR"))
        assertTrue(powerOff, powerOff.contains("BAT.T"))
    }

    @Test fun aLockedHudLetsTouchesThroughOnlyWhereAControlTakesThem() {
        val app = RuntimeEnvironment.getApplication()
        var reached = 0
        val under = View(app).apply {
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) reached++; true }
        }
        val hud = DroidDeckHudView(app)
        val parent = FrameLayout(app)
        parent.addView(under, FrameLayout.LayoutParams(2000, 2000))
        parent.addView(hud, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        var control = false
        hud.passesTouchAt = { _, _ -> control }
        val locks = ArrayList<Boolean>()
        hud.setOnLockChangedListener { locks += it }
        hud.applyConfig(DroidDeckHudConfig.encode(DroidDeckHudConfig.DEFAULTS + ("hudLocked" to "0")))
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
        hud.applyConfig(DroidDeckHudConfig.encode(DroidDeckHudConfig.DEFAULTS + ("hudLocked" to "1")))
        touch()
        assertEquals("a locked HUD passes it to the control under it", 1, reached)
        control = false
        touch()
        assertEquals("a locked HUD keeps a touch no control takes", 1, reached)
        touch(holdMs = ViewConfiguration.getLongPressTimeout() + 300L)
        assertEquals("a long press there unlocks it", listOf(false), locks)
    }

    @Test fun aDockedHudIsJustTheFpsTabAndATapBringsItBack() {
        val app = RuntimeEnvironment.getApplication()
        val hud = DroidDeckHudView(app)
        hud.applyConfig(DroidDeckHudConfig.encode(DroidDeckHudConfig.DEFAULTS + ("hudSize" to "full") + ("hudDock" to "left")))
        hud.showForTest(SNAPSHOT, "DXVK", 0L)
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        hud.measure(unspecified, unspecified)
        hud.layout(0, 0, hud.measuredWidth, hud.measuredHeight)
        assertEquals(DroidDeckHudDock.LEFT, hud.dock)
        assertTrue("a tab, not the panel: ${hud.width}x${hud.height}", hud.width < 400 && hud.height < 200)
        val docks = ArrayList<String>()
        hud.setOnDockChangedListener { docks += it }
        val down = SystemClock.uptimeMillis()
        MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 5f, 5f, 0).also { hud.dispatchTouchEvent(it); it.recycle() }
        MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, 5f, 5f, 0).also { hud.dispatchTouchEvent(it); it.recycle() }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("a locked, docked HUD still answers a tap", listOf("none"), docks)
    }

    private companion object {
        val SNAPSHOT = HudMetrics.Snapshot(64, 52, 3302, 1050, 89, 79, 10_800_000_000L, 16_000_000_000L, 67f,
            1_800_000_000L, HudMetrics.Battery(16f, false, 79, 34, null),
            intArrayOf(91, 91, 48, 50, 48, 19, 17, 73), intArrayOf(2265, 2265, 3148, 3148, 3148, 2956, 2956, 3302),
            2_700_000_000L, 6_400_000_000L, 31_000L, 9_000L)
    }
}
