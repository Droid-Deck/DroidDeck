package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.droiddeck.launcher.R
import com.droiddeck.launcher.glasses.GlassesState
import com.droiddeck.launcher.glasses.VitureDeviceState
import com.droiddeck.launcher.glasses.VitureSdk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VitureDeviceControlsTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Before fun controlAnimationFrames() {
        compose.mainClock.autoAdvance = false
    }

    @Test fun advancedTabHasADistinctLabelFromExistingDeviceTab() {
        var labels = emptyList<String>()
        compose.setContent {
            labels = glassesTabs(GlassesState(connected = true, controllable = true, viture = VitureDeviceState())).map { it.second }
        }
        compose.renderPendingChanges()
        compose.runOnIdle {
            assertEquals(labels.size, labels.distinct().size)
            assertEquals(true, labels.contains(RuntimeEnvironment.getApplication().getString(R.string.viture_device_tab)))
        }
    }

    @Test fun unsupportedControlsRemainHiddenAndDisconnectRemovesControls() {
        val state = mutableStateOf(GlassesState(connected = true, controllable = true,
            viture = VitureDeviceState(imuRate = 3, imuRateMask = (1 shl 3) or (1 shl 5))))
        compose.setContent { Column { VitureDeviceControls(rememberMenuHost(), state.value) } }
        compose.renderPendingChanges()
        compose.onNodeWithTag("setting-viture-imu-rate").assertExists()
        compose.onNodeWithTag("setting-viture-native-mode").assertDoesNotExist()
        compose.onNodeWithTag("setting-viture-auto-exposure").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(connected = false) }
        compose.renderPendingChanges()
        compose.onNodeWithTag("setting-viture-imu-rate").assertDoesNotExist()
    }

    @Test fun bypassHidesNativeOnlyControlsAndNativeSwitchIsAnExplicitAction() {
        var selected = true
        val state = mutableStateOf(GlassesState(connected = true, controllable = true,
            viture = VitureDeviceState(capabilities = VitureSdk.CAP_NATIVE, nativeMode = 1, nativeDof = 0, displayMode = 0x31)))
        compose.setContent {
            Column { VitureDeviceControls(rememberMenuHost(), state.value,
                actions = VitureDeviceActions(nativeMode = { selected = it })) }
        }
        compose.renderPendingChanges()
        compose.onNodeWithTag("setting-viture-native-mode").performClick()
        assertFalse(selected)
        compose.runOnIdle { state.value = state.value.copy(viture = state.value.viture!!.copy(nativeMode = 0)) }
        compose.renderPendingChanges()
        compose.onNodeWithTag("setting-viture-native-tracking").assertDoesNotExist()
        compose.onNodeWithTag("setting-viture-device-display").assertExists()
    }

    @Test fun trackingResetUsesTheRecoveryActionAndBusyStatePreventsRepeats() {
        val state = mutableStateOf(GlassesState(connected = true, controllable = true,
            viture = VitureDeviceState(capabilities = VitureSdk.CAP_CARINA_RESET)))
        val commands = mutableListOf<Int>()
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                VitureDeviceControls(rememberMenuHost(), state.value,
                    actions = VitureDeviceActions(control = { command, _, _ -> commands += command }))
            }
        }
        compose.renderPendingChanges()
        compose.onNodeWithTag("viture-tracking-reset").performScrollTo()
        compose.renderPendingChanges()
        compose.onNodeWithTag("viture-tracking-reset").performClick()
        assertEquals(listOf(VitureSdk.CONTROL_CARINA_RESET), commands)
        compose.runOnIdle { state.value = state.value.copy(viture = state.value.viture!!.copy(busy = true)) }
        compose.renderPendingChanges()
        compose.onNodeWithTag("viture-tracking-reset").assertIsNotEnabled()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.viture_tracking_reset_hint)).assertExists()
    }
}
