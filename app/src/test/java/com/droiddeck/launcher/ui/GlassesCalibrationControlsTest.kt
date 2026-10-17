package com.droiddeck.launcher.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.droiddeck.launcher.R
import com.droiddeck.launcher.glasses.CalibrationPhase
import com.droiddeck.launcher.glasses.GlassesCalibrationState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GlassesCalibrationControlsTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    @Test fun startRequiresInstructionsAndDoneRecentersAfterSuccessfulCapture() {
        val state = mutableStateOf(GlassesCalibrationState(supported = true))
        var starts = 0; var cancels = 0; var recentered = 0
        compose.setContent {
            Column {
            GlassesCalibrationControls(state.value,
                onStart = { starts++; state.value = state.value.copy(phase = CalibrationPhase.SETTLING) },
                onCancel = { cancels++ }, onReset = {}, onDone = { recentered++ })
            }
        }
        compose.onNodeWithText(text(R.string.glasses_calibration_open)).performClick()
        compose.onNodeWithText(text(R.string.glasses_calibration_instructions)).assertExists()
        assertEquals(0, starts)
        compose.onNodeWithText(text(R.string.glasses_calibration_start)).performClick()
        assertEquals(1, starts)
        compose.onNodeWithText(text(R.string.glasses_calibration_settling)).assertExists()
        compose.onNodeWithText(text(R.string.glasses_calibration_start)).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(saved = true, phase = CalibrationPhase.SAVED, progress = 100) }
        compose.onNodeWithText(text(R.string.glasses_calibration_complete)).assertExists()
        compose.onNodeWithText(text(R.string.glasses_calibration_done)).performClick()
        assertEquals(1, recentered)
    }

    @Test fun cancelAndResetDoNotSilentlyStartOrOverwriteCalibration() {
        var starts = 0; var cancels = 0; var resets = 0
        compose.setContent {
            Column {
            GlassesCalibrationControls(GlassesCalibrationState(supported = true, saved = true),
                onStart = { starts++ }, onCancel = { cancels++ }, onReset = { resets++ }, onDone = {})
            }
        }
        compose.onNodeWithText(text(R.string.glasses_calibration_open)).performClick()
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        assertEquals(0, starts)
        assertTrue(cancels > 0)
        compose.onNodeWithText(text(R.string.glasses_calibration_reset_button)).performClick()
        compose.onNodeWithText(text(R.string.glasses_calibration_reset_hint)).assertExists()
        assertEquals(0, resets)
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        assertEquals(0, resets)
    }
}
