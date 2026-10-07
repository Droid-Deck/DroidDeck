package com.droiddeck.launcher.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createComposeRule
import com.droiddeck.launcher.files.filePreviewCases
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** Render the actual preview entry points, including dialogs and their inspection-only paths. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class PreviewRenderTest(private val case: PreviewCase) {
    @get:Rule val compose = createComposeRule()

    @Test fun rendersContentWithoutDeviceOrRuntime() = render("handheld")

    @Test @Config(qualifiers = "w480dp-h360dp-land-mdpi")
    fun rendersCompactLayout() = render("compact")

    @Test @Config(qualifiers = "w400dp-h700dp-port-mdpi")
    fun rendersPortraitLayout() = render("portrait")

    private fun render(size: String) {
        if (case.name == "CustomResolution") {
            // Compose 1.6's AlertDialog + weighted text fields never report idle to Espresso here.
            // Render this dialog with bounded native frames instead of changing production UI.
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                activity.get().setContent { case.content() }
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
                saveWindow(size)
            } finally {
                activity.pause().stop().destroy()
            }
            return
        }
        compose.mainClock.autoAdvance = false
        compose.setContent { case.content() }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.runOnIdle { saveWindow(size) }
    }

    private fun saveWindow(size: String) {
        // Dialogs have their own root; capture their content rather than the empty host.
        val view = WindowInspector.getGlobalWindowViews().last()
        assertTrue("${case.name}: empty bounds", view.width > 0 && view.height > 0)
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        assertTrue("${case.name}: blank preview", pixels.any { it != pixels.first() })
        val output = File("build/preview-renders/$size/${case.name}.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    companion object {
        @JvmStatic @Parameters(name = "{0}")
        fun previews(): List<Array<Any>> = (screenPreviewCases + filePreviewCases).map { arrayOf(it) }
    }
}
