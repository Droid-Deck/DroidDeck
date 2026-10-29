package com.droiddeck.launcher.hud

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
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

    @Test fun everySizeDrawsTheDPanelWithEverythingInsideIt() {
        val stats = FrameStats()
        var now = 1_000_000_000L
        repeat(2_000) { i ->
            val frametime = if (i == 1_700) 25_600_000L else 2_850_000L
            now += frametime
            stats.add(frametime, now)
        }
        val snapshot = HudMetrics.Snapshot(64, 52, 3302, 1050, 89, 79, 10_800_000_000L, 16_000_000_000L, 67f,
            1_800_000_000L, HudMetrics.Battery(16f, false, 79, 34, null),
            intArrayOf(91, 91, 48, 50, 48, 19, 17, 73), intArrayOf(2265, 2265, 3148, 3148, 3148, 2956, 2956, 3302),
            2_700_000_000L, 6_400_000_000L, 31_000L, 9_000L)

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
}
