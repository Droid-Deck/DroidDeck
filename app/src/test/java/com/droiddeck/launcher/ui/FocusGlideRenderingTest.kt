package com.droiddeck.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FocusGlideRenderingTest {
    @get:Rule val compose = createRobolectricComposeRule()

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
