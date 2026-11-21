package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.glasses.Glasses
import com.droiddeck.launcher.glasses.GlassesState
import com.droiddeck.launcher.glasses.VitureDeviceState
import com.droiddeck.launcher.glasses.VitureDisplayMode
import com.droiddeck.launcher.glasses.VitureSdk
import java.util.Locale

internal data class VitureDeviceActions(
    val control: (Int, Float, Int) -> Unit = Glasses::setVitureControl,
    val nativeMode: (Boolean) -> Unit = Glasses::setVitureNativeMode,
    val displayMode: (Int) -> Unit = Glasses::setVitureDisplayMode,
    val saveDisplay: () -> Unit = Glasses::saveVitureDefaultDisplay,
)

@Composable
internal fun VitureDeviceControls(host: MenuHost, g: GlassesState, track: (String) -> Modifier = { Modifier },
    actions: VitureDeviceActions = VitureDeviceActions()) {
    val d = g.viture?.takeIf { g.canControl } ?: return
    val enabled = !d.busy
    d.error?.let { SettingsRow(stringResource(R.string.glasses_control), it, hintLines = 4) {} }
    if (d.supportsNative && d.nativeMode in 0..1) {
        SettingsGroup(stringResource(R.string.viture_device_display)) {
            ToggleRow(host, "viture-native-mode", stringResource(R.string.viture_native_mode),
                stringResource(R.string.viture_native_mode_hint), d.nativeMode == 1, enabled,
                track("viture-native-mode"), actions.nativeMode)
            ChoiceRow(host, "viture-device-display", stringResource(R.string.glasses_display_mode), null,
                d.displayModes.map { it.code to displayLabel(it) }, d.displayMode ?: -1,
                enabled = enabled, chipModifier = track("viture-device-display"), onPick = actions.displayMode)
            if (d.nativeMode == 1) {
                d.nativeDof?.let { dof ->
                    ChoiceRow(host, "viture-native-tracking", stringResource(R.string.viture_native_tracking),
                        stringResource(if (g.vrActive) R.string.viture_native_vr_hint else R.string.viture_native_tracking_hint),
                        listOf(0 to stringResource(R.string.viture_native_off), 1 to stringResource(R.string.viture_native_fixed),
                            2 to stringResource(R.string.viture_native_follow)), dof,
                        enabled = enabled && !g.vrActive, chipModifier = track("viture-native-tracking")) {
                        actions.control(VitureSdk.CONTROL_NATIVE_DOF, it.toFloat(), 0)
                    }
                    if (d.nativeTracking) DeviceAction(stringResource(R.string.glasses_recenter), null,
                        stringResource(R.string.cardboard_recenter_button), "viture-native-recenter", enabled) {
                        actions.control(VitureSdk.CONTROL_NATIVE_RECENTER, 0f, 0)
                    }
                }
                d.size?.let { size ->
                    val labels = listOf(R.string.viture_size_small, R.string.viture_size_medium, R.string.viture_size_large,
                        R.string.viture_size_extra, R.string.viture_size_ultra)
                    ChoiceRow(host, "viture-native-size", stringResource(R.string.glasses_screen_size), null,
                        labels.mapIndexed { i, label -> i to stringResource(label) }, size,
                        enabled = enabled, chipModifier = track("viture-native-size")) {
                        actions.control(VitureSdk.CONTROL_NATIVE_SIZE, it.toFloat(), 0)
                    }
                }
                d.distance?.let { distance ->
                    SliderRow(stringResource(R.string.glasses_screen_distance), stringResource(R.string.viture_distance_hint),
                        distance, 1..10, enabled = enabled, modifier = track("viture-native-distance").testTag("viture-native-distance")) {
                        actions.control(VitureSdk.CONTROL_NATIVE_DISTANCE, it.toFloat(), 0)
                    }
                }
                d.side?.let { side ->
                    ToggleRow(host, "viture-native-side", stringResource(R.string.viture_side_mode),
                        stringResource(R.string.viture_side_mode_hint), side, enabled, track("viture-native-side")) {
                        actions.control(VitureSdk.CONTROL_NATIVE_SIDE, if (it) 1f else 0f, 0)
                    }
                }
            }
        }
    }
    if (d.supportsDefaultDisplay || d.supportsDisplayButton || d.dutyCycle != null || d.worn != null) {
        SettingsGroup(stringResource(R.string.viture_device_preferences)) {
            if (d.supportsDefaultDisplay) DeviceAction(stringResource(R.string.viture_power_on_display),
                stringResource(if (d.defaultDisplay != null) R.string.viture_power_on_saved else R.string.viture_power_on_hint),
                stringResource(R.string.viture_save_current), "viture-default-display", enabled, actions.saveDisplay)
            if (d.supportsDisplayButton) ChoiceRow(host, "viture-display-button", stringResource(R.string.viture_display_button),
                stringResource(R.string.viture_display_button_hint),
                listOf(-1 to stringResource(R.string.viture_unchanged), 1 to stringResource(R.string.viture_button_enabled),
                    0 to stringResource(R.string.viture_button_locked)), d.displayButtonEnabled?.let { if (it) 1 else 0 } ?: -1,
                enabled = enabled, chipModifier = track("viture-display-button")) {
                if (it >= 0) actions.control(VitureSdk.CONTROL_DISPLAY_BUTTON, it.toFloat(), 0)
            }
            d.dutyCycle?.let { duty ->
                SliderRow(stringResource(R.string.viture_duty_cycle), stringResource(R.string.viture_duty_cycle_hint), duty,
                    0..100, enabled = enabled, format = { "$it%" }, modifier = track("viture-duty-cycle").testTag("viture-duty-cycle")) {
                    actions.control(VitureSdk.CONTROL_DUTY_CYCLE, it.toFloat(), 0)
                }
            }
            d.worn?.let { worn ->
                SettingsRow(stringResource(R.string.viture_wear_status),
                    stringResource(if (worn) R.string.viture_worn else R.string.viture_removed)) {}
            }
        }
    }
    if (d.rates.size > 1 || d.supportsExposure || d.supportsReset) {
        SettingsGroup(stringResource(R.string.viture_device_tracking)) {
            if (d.rates.size > 1 && d.imuRate != null) ChoiceRow(host, "viture-imu-rate", stringResource(R.string.viture_tracking_rate),
                stringResource(R.string.viture_tracking_rate_hint), d.rates.map { it to "${VitureDeviceState.RATE_HZ[it]} Hz" },
                d.imuRate, enabled = enabled && !d.nativeTracking, chipModifier = track("viture-imu-rate")) {
                actions.control(VitureSdk.CONTROL_IMU_RATE, it.toFloat(), 0)
            }
            if (d.supportsExposure) {
                ToggleRow(host, "viture-auto-exposure", stringResource(R.string.viture_auto_exposure),
                    stringResource(R.string.viture_auto_exposure_hint), d.automaticExposure, enabled, track("viture-auto-exposure")) {
                    if (it) actions.control(VitureSdk.CONTROL_CARINA_AUTO_EXPOSURE, 0f, 0)
                    else actions.control(VitureSdk.CONTROL_CARINA_MANUAL_EXPOSURE, d.exposureMs, d.exposureGain)
                }
                if (!d.automaticExposure) {
                    var exposure by remember(d.exposureMs) { mutableFloatStateOf(d.exposureMs) }
                    var gain by remember(d.exposureGain) { mutableIntStateOf(d.exposureGain) }
                    SliderRow(stringResource(R.string.viture_exposure_time), null, (exposure * 100).toInt(), 1..800,
                        enabled = enabled, format = { String.format(Locale.getDefault(), "%.2f ms", it / 100f) },
                        modifier = track("viture-exposure-time")) { exposure = it / 100f }
                    SliderRow(stringResource(R.string.viture_exposure_gain), null, gain, 0..15,
                        enabled = enabled, modifier = track("viture-exposure-gain")) { gain = it }
                    DeviceAction(stringResource(R.string.viture_manual_exposure), null, stringResource(R.string.viture_apply),
                        "viture-exposure-apply", enabled) {
                        actions.control(VitureSdk.CONTROL_CARINA_MANUAL_EXPOSURE, exposure, gain)
                    }
                }
            }
            if (d.supportsReset) {
                DeviceAction(stringResource(R.string.viture_tracking_origin), stringResource(R.string.viture_tracking_origin_hint),
                    stringResource(R.string.cardboard_recenter_button), "viture-origin-reset", enabled) {
                    actions.control(VitureSdk.CONTROL_CARINA_RECENTER, 0f, 0)
                }
                DeviceAction(stringResource(R.string.viture_tracking_reset), stringResource(R.string.viture_tracking_reset_hint),
                    stringResource(R.string.viture_restart), "viture-tracking-reset", enabled) {
                    actions.control(VitureSdk.CONTROL_CARINA_RESET, 0f, 0)
                }
            }
        }
    }
}

@Composable
private fun displayLabel(mode: VitureDisplayMode): String {
    val kind = stringResource(when { mode.ultrawide -> R.string.viture_ultrawide
        mode.stereo -> R.string.glasses_mode_3d; else -> R.string.glasses_mode_2d })
    return "$kind · ${mode.width} × ${mode.height} · ${mode.hz} Hz"
}

@Composable
private fun DeviceAction(label: String, hint: String?, button: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    SettingsRow(label, hint, hintLines = 4) {
        SecondaryButton(button, enabled = enabled, modifier = Modifier.testTag(tag), onClick = onClick)
    }
}
