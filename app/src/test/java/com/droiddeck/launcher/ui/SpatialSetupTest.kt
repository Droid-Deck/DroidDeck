package com.droiddeck.launcher.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.platform.LocalView
import com.droiddeck.launcher.glasses.SpatialCalibrationProgress
import com.droiddeck.launcher.glasses.SpatialTrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpatialSetupTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private lateinit var view: android.view.View

    @Test fun pendingRecenterExplainsTheWaitAndBlocksCaptures() {
        compose.setContent {
            DroidDeckTheme(Themes.ELECTRIC) {
                view = LocalView.current
                SpatialSetupScreen(SpatialTrackingStatus(message = "Hold still while tracking stabilizes", recenterPending = true),
                    SpatialCalibrationProgress(collecting = true), null, "", SpatialSetupActions())
            }
        }
        compose.onNodeWithText("Hold still while tracking stabilizes").assertExists()
        compose.onNodeWithText("Waiting for tracking…").assertIsNotEnabled()
        savePreview("tracking-recovery")
        compose.onNodeWithText("Capture board view").performScrollTo().assertIsNotEnabled()
    }

    @Test fun completedFitValidatesTheSelectedImperialOrMetricDistance() {
        var distance = 0f
        compose.setContent {
            DroidDeckTheme {
                view = LocalView.current
                SpatialSetupScreen(SpatialTrackingStatus(trackingValid = true, synchronized = true),
                    SpatialCalibrationProgress(collecting = true, views = 24, fitted = true), null, "Ready to validate",
                    SpatialSetupActions(validate = { distance = it }))
            }
        }
        compose.onNodeWithText("Validate distance").performScrollTo().performClick()
        assertEquals(0.508f, distance, 0.000001f)
        savePreview("distance-check")
        compose.onNodeWithTag("setting-calibration-distance").performScrollTo().performClick()
        compose.onNodeWithText("50 cm (19.69 in)").performClick()
        compose.onNodeWithText("Validate distance").performScrollTo().performClick()
        assertEquals(0.5f, distance, 0.000001f)
    }

    private fun savePreview(name: String) {
        val file = File("build/reports/spatial-setup/$name.png")
        file.parentFile?.mkdirs()
        compose.runOnIdle {
            val root = view.rootView
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
