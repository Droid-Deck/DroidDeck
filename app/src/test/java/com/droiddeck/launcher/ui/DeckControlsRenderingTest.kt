package com.droiddeck.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeckControlsRenderingTest {
    @Test fun movingTouchesReuseTheGlowWithoutLeavingItAtThePreviousPosition() {
        val view = trackpad()
        val bitmap = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = ReflectionHelpers.getField<Paint>(view, "glow")
        draw(view, canvas, 100f, 120f)
        val shader = paint.shader
        repeat(120) { frame ->
            val x = 100f + frame
            draw(view, canvas, x, 120f)
            assertSame("frame $frame allocated a new glow", shader, paint.shader)
            val matrix = Matrix()
            shader.getLocalMatrix(matrix)
            val center = floatArrayOf(0f, 0f)
            matrix.mapPoints(center)
            assertEquals(x, center[0], 0.001f)
            assertEquals(120f, center[1], 0.001f)
        }
        bitmap.recycle()
    }

    @Test fun releasingAndResizingTheTrackpadPreservesItsGlowAndNewTouchPosition() {
        val view = trackpad()
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        draw(view, canvas, 80f, 90f)
        val paint = ReflectionHelpers.getField<Paint>(view, "glow")
        val shader: Shader = paint.shader
        ReflectionHelpers.setField(view, "touching", false)
        view.draw(canvas)
        view.layout(0, 0, 640, 480)
        draw(view, canvas, 420f, 280f)
        assertSame(shader, paint.shader)
        val matrix = Matrix()
        assertTrue(shader.getLocalMatrix(matrix))
        val center = floatArrayOf(0f, 0f)
        matrix.mapPoints(center)
        assertEquals(420f, center[0], 0.001f)
        assertEquals(280f, center[1], 0.001f)
        bitmap.recycle()
    }

    private fun trackpad(): View {
        val type = Class.forName("com.droiddeck.launcher.ui.DeckControlsPanel\$TrackpadView")
        val constructor = type.getDeclaredConstructor(Context::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        constructor.isAccessible = true
        return (constructor.newInstance(RuntimeEnvironment.getApplication(), false, false) as View).apply {
            layout(0, 0, 480, 320)
        }
    }

    private fun draw(view: View, canvas: Canvas, x: Float, y: Float) {
        ReflectionHelpers.setField(view, "touching", true)
        ReflectionHelpers.setField(view, "fingerX", x)
        ReflectionHelpers.setField(view, "fingerY", y)
        view.draw(canvas)
    }
}
