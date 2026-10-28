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
class FusionHudMinimalTest {
    @Test fun minimalIsTheSizeTheHudStartsWith() {
        assertEquals(FusionSize.MINIMAL, FusionSize.from(FusionHudConfig.DEFAULTS["hudSize"]))
    }

    @Test fun minimalDrawsTheDPanelWithEveryRowInsideIt() {
        val stats = FrameStats()
        var now = 1_000_000_000L
        repeat(2_000) { i ->
            val frametime = if (i == 1_700) 25_600_000L else 2_850_000L
            now += frametime
            stats.add(frametime, now)
        }
        val view = FusionHudView(RuntimeEnvironment.getApplication())
        view.applyConfig(FusionHudConfig.encode(FusionHudConfig.DEFAULTS))
        view.setFrameStats(stats)
        val snapshot = HudMetrics.Snapshot(64, 52, 3302, 1050, 89, 79, 10_800_000_000L, 16_000_000_000L, 67f,
            1_800_000_000L, HudMetrics.Battery(16f, false, 79, 34, null), IntArray(0), IntArray(0), null, null, null, null)
        view.showForTest(snapshot, "DXVK", now)
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(unspecified, unspecified)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))

        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("FUSIONHUD-MINIMAL-PNG ${Base64.getEncoder().encodeToString(png.toByteArray())}")

        val panel = view.minimalPanel()!!
        assertTrue(panel.left > 0f && panel.top > 0f && panel.right < view.width && panel.bottom < view.height)
        val graph = view.graphArea()!!
        val r = panel.height() / 2f
        val dy = maxOf(panel.centerY() - graph.top, graph.bottom - panel.centerY())
        assertTrue("graph clears the round side", graph.right <= panel.right - r + sqrt(r * r - dy * dy))
        val orb = bitmap.getPixel(panel.left.roundToInt() + 1, panel.centerY().roundToInt())
        assertTrue("orb drawn on the left side", Color.blue(orb) > 200 && Color.alpha(orb) > 200)
    }
}
