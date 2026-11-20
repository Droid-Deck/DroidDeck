package com.droiddeck.launcher.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws every preview case with Robolectric's native graphics and saves it as a PNG under app/build/preview-renders/,
 * so UI changes can be looked at without a device. Fails on a preview that throws or draws a single flat colour.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class PreviewRenderTest(private val case: PreviewCase) {
    @get:Rule val compose = createComposeRule()

    @Test fun handheld() = render("handheld")

    @Test @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun portrait() = render("portrait")

    private fun render(size: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent { case.content() }
        // The clock is paused, so step it while waiting: navigation and the loads that make a case ready run in frames.
        compose.waitUntil(timeoutMillis = 15_000) { compose.mainClock.advanceTimeByFrame(); case.ready() }
        // Entrance animations settle well within this.
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.runOnIdle { save(size) }
    }

    private fun save(size: String) {
        // A dialog has its own window; the last one is on top.
        val view = WindowInspector.getGlobalWindowViews().last()
        assertTrue("${case.name}: empty bounds", view.width > 0 && view.height > 0)
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        assertTrue("${case.name}: drew one flat colour", pixels.any { it != pixels.first() })
        val out = File("build/preview-renders/$size/${case.name}.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    companion object {
        @JvmStatic @Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = previewCases.map { arrayOf(it) }
    }
}
