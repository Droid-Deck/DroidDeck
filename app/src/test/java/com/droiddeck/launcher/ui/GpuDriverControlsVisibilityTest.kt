package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h400dp-land")
class GpuDriverControlsVisibilityTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private var openedSlot: String? = null

    @Test fun openingMixedModeRevealsBothDriverButtonsOnAShortScreen() {
        show(GPU_MODE_MIX)
        assertControlsVisible()
        compose.onNodeWithText(text(R.string.gpu_change_display)).performClick()
        assertEquals(SLOT_DISPLAY, openedSlot)
    }

    @Test fun selectingMixedModeRevealsItsControlsBelowTheModeChoices() {
        show(GPU_MODE_AUTO)
        compose.onNodeWithText(text(R.string.gpu_mode_mix)).performScrollTo().performClick()
        assertControlsVisible()
        compose.onNodeWithText(text(R.string.gpu_change_runtime)).performClick()
        assertEquals(SLOT_RUNTIME, openedSlot)
    }

    private fun assertControlsVisible() {
        compose.onNodeWithText(text(R.string.gpu_change_display)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.gpu_change_runtime)).assertIsDisplayed()
    }

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun show(initialMode: String) {
        val mode = mutableStateOf(initialMode)
        val first = FocusRequester()
        val slots = mapOf(SLOT_DISPLAY to FocusRequester(), SLOT_RUNTIME to FocusRequester())
        compose.setContent {
            DroidDeckTheme {
                Box(Modifier.requiredSize(480.dp, 280.dp)) {
                    GpuDriversBody(
                        gpu = GpuDriversState(
                            gpuName = "Adreno 830", gpuFamily = "Adreno 8xx", mode = mode.value,
                            display = DriverSlot(name = "Automatic", status = "Applies after restarting DroidDeck"),
                            runtime = DriverSlot(name = "System Qualcomm driver", status = "Applies at the next session start"),
                        ),
                        actions = GpuDriversActions(onMode = { mode.value = it }),
                        single = true, first = first, slotFocus = slots,
                        onOpenSlot = { slot, _ -> openedSlot = slot },
                    )
                }
            }
        }
    }
}
