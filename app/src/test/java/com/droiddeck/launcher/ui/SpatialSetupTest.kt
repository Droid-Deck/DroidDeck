package com.droiddeck.launcher.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.mutableStateOf
import com.droiddeck.launcher.glasses.SpatialCalibrationProgress
import com.droiddeck.launcher.glasses.SpatialTrackingStatus
import com.droiddeck.launcher.glasses.CalibrationDistance
import com.droiddeck.launcher.glasses.CalibrationDistanceCheck
import com.droiddeck.launcher.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpatialSetupTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private lateinit var view: android.view.View

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun frenchCalibrationControlsKeepTheSelectedDistanceAndActions() {
        val context = RuntimeEnvironment.getApplication()
        var selected = 0f
        compose.setContent {
            DroidDeckTheme {
                view = LocalView.current
                SpatialSetupScreen(SpatialTrackingStatus(trackingValid = true, synchronized = true),
                    SpatialCalibrationProgress(views = 24, fitted = true), null, "",
                    SpatialSetupActions(validate = { selected = it }))
            }
        }
        compose.onNodeWithText("Configuration spatiale").assertIsDisplayed()
        compose.onNodeWithText("Spatial setup").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.spatial_validate)).performScrollTo().performClick()
        assertEquals(.3048f, selected, .000001f)
        savePreview("calibration-fr")
    }

    @Test
    @Config(qualifiers = "ar-w411dp-h891dp")
    fun arabicCalibrationDiagnosticsUseTranslatedLabelsAndFormatNumbers() {
        val context = RuntimeEnvironment.getApplication()
        compose.setContent {
            DroidDeckTheme {
                view = LocalView.current
                SpatialSetupScreen(SpatialTrackingStatus(cameraHz = 25f, cameraCopyPermille = 500, frames = 12),
                    SpatialCalibrationProgress(), null, "", SpatialSetupActions())
            }
        }
        compose.onNodeWithText(context.getString(R.string.spatial_title)).assertIsDisplayed()
        compose.onNodeWithText("Spatial setup").assertDoesNotExist()
        compose.onNodeWithTag("setting-spatial-diagnostics").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.spatial_input_values, 12.5f, 50f)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Show diagnostics").assertDoesNotExist()
        savePreview("calibration-ar")
    }

    @Test fun directLaunchWithoutConnectedUltraClosesBeforeShowingCalibration() {
        val field = com.droiddeck.launcher.glasses.Glasses::class.java.getDeclaredField("_state")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(null) as kotlinx.coroutines.flow.MutableStateFlow<com.droiddeck.launcher.glasses.GlassesState>
        val previous = state.value
        try {
            for (candidate in listOf(previous.copy(connected = false, supportsSpatial = true),
                previous.copy(connected = true, controllable = true, supportsSpatial = false))) {
                state.value = candidate
                val activity = org.robolectric.Robolectric.buildActivity(SpatialTrackingActivity::class.java).create()
                assertTrue(activity.get().isFinishing)
                activity.destroy()
            }
        } finally { state.value = previous }
    }

    @Test fun sharedCalibrationIsUsableWithoutClaimingPerDeviceVerification() {
        compose.setContent {
            DroidDeckTheme {
                SpatialSetupScreen(SpatialTrackingStatus(calibration = true,
                    calibrationSource = "luma-ultra-base", calibrationVerified = false),
                    SpatialCalibrationProgress(fitted = true), null, "", SpatialSetupActions())
            }
        }
        compose.onNodeWithText("Shared Luma Ultra calibration").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Measured calibration verified").assertDoesNotExist()
        compose.onNodeWithText("Validation required").assertDoesNotExist()
        compose.onNodeWithText("Hand tracking — Experimental").assertExists()
    }

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
        assertEquals(0.3048f, distance, 0.000001f)
        savePreview("distance-check")
        for (choice in CalibrationDistance.entries.drop(1)) {
            compose.onNodeWithTag("setting-calibration-distance").performScrollTo().performClick()
            compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(choice.labelRes)).performClick()
            compose.onNodeWithText("Validate distance").performScrollTo().performClick()
            assertEquals(choice.meters, distance, 0.000001f)
        }
    }

    @Test fun captureRejectionAppearsBesideTheCounter() {
        val reason = "Capture rejected: 4 / 12 required corners visible in both cameras."
        compose.setContent {
            DroidDeckTheme {
                SpatialSetupScreen(SpatialTrackingStatus(trackingValid = true, synchronized = true),
                    SpatialCalibrationProgress(collecting = true, views = 2, captureMessage = reason), null, "",
                    SpatialSetupActions())
            }
        }
        compose.onNodeWithText("Capture board view").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("2 / 24 views captured").assertIsDisplayed()
        compose.onNodeWithText(reason).assertIsDisplayed()
    }

    @Test fun pendingCaptureCannotQueueAnotherPress() {
        compose.setContent {
            DroidDeckTheme {
                SpatialSetupScreen(SpatialTrackingStatus(trackingValid = true, synchronized = true),
                    SpatialCalibrationProgress(collecting = true, capturing = true), null, "", SpatialSetupActions())
            }
        }
        compose.onNodeWithText("Capturing…").performScrollTo().assertIsNotEnabled()
    }

    @Test fun distanceResultAppearsBesideValidationButton() {
        val reason=CalibrationDistanceCheck.evaluate(RuntimeEnvironment.getApplication(),.3048f,3,null).message
        compose.setContent {
            DroidDeckTheme {
                view=LocalView.current
                SpatialSetupScreen(SpatialTrackingStatus(trackingValid=true,synchronized=true),
                    SpatialCalibrationProgress(views=25,fitted=true,validationMessage=reason),null,
                    "Fit 1.12 px. Measure the distance, then validate.",SpatialSetupActions())
            }
        }
        compose.onNodeWithText("Validate distance").performScrollTo().assertIsEnabled()
        compose.onNodeWithText(reason).assertIsDisplayed()
        savePreview("distance-result")
    }

    @Test fun pendingDistanceCheckShowsFeedbackAndPreventsDuplicateRequests() {
        val message="Checking the next camera image. Keep the full board visible and hold still."
        compose.setContent {
            DroidDeckTheme {
                SpatialSetupScreen(SpatialTrackingStatus(trackingValid=true,synchronized=true),
                    SpatialCalibrationProgress(views=25,fitted=true,validating=true,validationMessage=message),null,
                    "Fit complete",SpatialSetupActions())
            }
        }
        compose.onNodeWithText("Checking distance…").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(message).assertIsDisplayed()
    }

    @Test fun restoredCapturesCanBeRecalculatedWithoutMixingNewTrackingFrames() {
        var solves=0
        compose.setContent {
            DroidDeckTheme {
                SpatialSetupScreen(SpatialTrackingStatus(trackingValid = true, synchronized = true),
                    SpatialCalibrationProgress(views = 35, captureMessage = "35 saved views restored."), null,
                    "Head-to-camera alignment varies by 25 cm (9.84 in).",
                    SpatialSetupActions(solve = { solves++ }))
            }
        }
        compose.onNodeWithText("Capture board view").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("35 saved views restored.").assertIsDisplayed()
        compose.onNodeWithText("Calculate calibration").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1,solves)
    }

    @Test
    @Config(qualifiers = "w840dp-h360dp-land")
    fun landscapeSetupLeavesMostOfTheHeightForScrolling() {
        compose.setContent {
            DroidDeckTheme {
                view = LocalView.current
                SpatialSetupScreen(SpatialTrackingStatus(trackingValid = true, synchronized = true),
                    SpatialCalibrationProgress(views = 35), null, "Ready to calculate", SpatialSetupActions())
            }
        }
        compose.waitForIdle()
        val page=compose.onNodeWithTag("spatial-setup-page").fetchSemanticsNode().boundsInRoot
        val viewport=compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
        assertTrue("Calibration controls need at least 75% of the page height", viewport.height >= page.height*.75f)
        savePreview("landscape-compact")
    }

    @Test fun diagnosticsSeparateCameraRateFromDeliberatelySkippedFrames() {
        compose.setContent {
            DroidDeckTheme {
                SpatialSetupScreen(SpatialTrackingStatus(cameraHz = 25f, cameraFrameDivisor = 2, cameraCopyPermille = 500,
                    frames = 12, dropped = 3, rateLimitedFrames = 15),
                    SpatialCalibrationProgress(), null, "", SpatialSetupActions())
            }
        }
        compose.onNodeWithTag("setting-spatial-diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("Input limit 12.5 Hz • 50% of camera frames. Head tracking stays at full rate.").assertExists()
        compose.onNodeWithText("12 processed • 3 dropped • 15 skipped by input limit").assertExists()
        compose.onNodeWithText("25.0 Hz", substring = true).assertExists()
    }

    @Test fun diagnosticsShowTheAdaptiveFractionInsteadOfTheLegacyDivisor() {
        compose.setContent {
            DroidDeckTheme {
                SpatialSetupScreen(SpatialTrackingStatus(cameraHz = 25f, cameraFrameDivisor = 1, cameraCopyPermille = 600),
                    SpatialCalibrationProgress(), null, "", SpatialSetupActions())
            }
        }
        compose.onNodeWithTag("setting-spatial-diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("Input limit 15.0 Hz • 60% of camera frames. Head tracking stays at full rate.").assertExists()
    }

    @Test fun manualRateControlBecomesAvailableAfterAutomaticRateIsDisabled() {
        val automatic=mutableStateOf(true)
        val halfRate=mutableStateOf(true)
        compose.setContent {
            DroidDeckTheme {
                SpatialSetupScreen(SpatialTrackingStatus(), SpatialCalibrationProgress(), null, "",
                    SpatialSetupActions(adaptive = { automatic.value=it }, halfRate = { halfRate.value=it }),
                    halfRate = halfRate.value, adaptive = automatic.value)
            }
        }
        compose.onNodeWithTag("setting-spatial-diagnostics").performScrollTo().performClick()
        compose.onNodeWithTag("setting-spatial-half-rate").assertDoesNotExist()
        compose.onNodeWithTag("setting-spatial-adaptive-rate").performScrollTo().performClick()
        compose.onNodeWithTag("setting-spatial-half-rate").performScrollTo().performClick()
        assertEquals(false,automatic.value)
        assertEquals(false,halfRate.value)
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
