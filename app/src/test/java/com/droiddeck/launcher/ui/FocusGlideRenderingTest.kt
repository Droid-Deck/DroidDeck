package com.droiddeck.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.session.SessionPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FocusGlideRenderingTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun largeJumpCollapsesIntoABallAndExpandsAtTheDestination() {
        val context = RuntimeEnvironment.getApplication()
        SessionPrefs.setAnimationsEnabled(context, true)
        Motion.refresh(context)
        compose.mainClock.autoAdvance = false
        lateinit var view: View
        val from = FocusRequester()
        val to = FocusRequester()
        compose.setContent {
            view = LocalView.current
            FocusGlideHost(Modifier.fillMaxSize().background(Color.Black)) {
                Box(Modifier.offset(10.dp, 10.dp).size(80.dp, 40.dp).focusRequester(from)
                    .focusGlide(RectangleShape, Color.White).focusable())
                Box(Modifier.offset(650.dp, 400.dp).size(80.dp, 40.dp).focusRequester(to)
                    .focusGlide(RectangleShape, Color.White).focusable())
            }
        }
        fun ringBounds(): Rect = compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            var left = view.width
            var top = view.height
            var right = 0
            var bottom = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (android.graphics.Color.red(bitmap.getPixel(x, y)) > 200) {
                    left = minOf(left, x); top = minOf(top, y)
                    right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
                }
            }
            bitmap.recycle()
            Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        }
        compose.runOnIdle { from.requestFocus() }
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue(ringBounds().width > 60f)
        compose.runOnIdle { to.requestFocus() }
        compose.mainClock.advanceTimeBy(160)
        val ball = ringBounds()
        assertTrue("Ball bounds: $ball", ball.width in 8f..16f && ball.height in 8f..16f)
        compose.mainClock.advanceTimeBy(1_500)
        val landed = ringBounds()
        assertTrue("Landed bounds: $landed", landed.width > 60f && landed.left > 600f)
    }

    @Test fun hollowGlideKeepsItsOutlineWithoutTintingTheContent() {
        val context = RuntimeEnvironment.getApplication()
        SessionPrefs.setAnimationsEnabled(context, false)
        Motion.refresh(context)
        try {
            lateinit var view: View
            var bounds = Rect.Zero
            var hollow by mutableStateOf(true)
            compose.setContent {
                view = LocalView.current
                FocusGlideHost {
                    Box(Modifier.size(120.dp).background(Color.Black)
                        .glideBorder(true, RectangleShape, Color.White, hotFillAlpha = if (hollow) 0f else 0.14f)
                        .onGloballyPositioned { bounds = it.boundsInRoot() })
                }
            }
            fun sample(): Pair<Int, Int> = compose.runOnIdle {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val center = bitmap.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt())
                val edge = bitmap.getPixel(bounds.left.toInt(), bounds.center.y.toInt())
                bitmap.recycle()
                center to edge
            }
            val (center, edge) = sample()
            assertEquals(android.graphics.Color.BLACK, center)
            assertEquals(android.graphics.Color.WHITE, edge)
            compose.runOnIdle { hollow = false }
            assertTrue(android.graphics.Color.red(sample().first) > 0)
        } finally {
            SessionPrefs.setAnimationsEnabled(context, true)
            Motion.refresh(context)
        }
    }
}
