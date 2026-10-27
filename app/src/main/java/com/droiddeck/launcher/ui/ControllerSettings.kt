package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.input.DualSenseManager
import com.droiddeck.launcher.session.SessionPrefs

class ControllerActions(
    val onOsc: (String) -> Unit,
    val onTint: (Int) -> Unit,
    val onOpacity: (Int) -> Unit,
    val onSize: (Int) -> Unit,
    val onStickClick: (Boolean) -> Unit,
    val onAdaptiveSticks: (Boolean) -> Unit,
    val onRumble: (Boolean) -> Unit,
    val onSteamButton: (Boolean) -> Unit,
    val onQamButton: (Boolean) -> Unit,
    val onKeyboardButton: (Boolean) -> Unit,
    val onSelectSteam: (Boolean) -> Unit,
    val onJoyConVr: (Boolean) -> Unit,
    val onDualSenseAudio: (Boolean) -> Unit,
    val onDualSenseLightbarMode: (String) -> Unit = {},
    val onDualSensePlayerLeds: (String) -> Unit = {},
    val onDualSensePlayerLedBrightness: (Int) -> Unit = {},
    val onDualSenseTriggerMode: (String) -> Unit = {},
    val onDualSenseTouchpad: (Boolean) -> Unit = {},
    val onDualSenseGyroAutoCal: (Boolean) -> Unit = {},
    val onEditLayout: () -> Unit,
    val onResetLayout: () -> Unit,
    val onMapping: () -> Unit,
    val onResetAll: () -> Unit,
)

@Composable
private fun Swatch(color: Int) {
    Box(Modifier.size(14.dp).background(Color(color), CircleShape).border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape))
}

@Composable
fun ColumnScope.ControllerRows(host: MenuHost, oscMode: String, c: ControllerPrefs.Settings, a: ControllerActions) {
    ChoiceRow(
        host, "controller-osc", stringResource(R.string.mode_osc), stringResource(R.string.ctrl_osc_hint),
        listOf(SessionPrefs.OSC_AUTO to stringResource(R.string.common_auto), SessionPrefs.OSC_ALWAYS to stringResource(R.string.common_always), SessionPrefs.OSC_STEAM_TOUCH to stringResource(R.string.osc_steam_touch), SessionPrefs.OSC_STEAM_QAM to stringResource(R.string.mode_osc_qam), SessionPrefs.OSC_NEVER to stringResource(R.string.common_never)),
        oscMode, note = stringResource(R.string.mode_osc_note), onPick = a.onOsc,
    )
    val tintOpen = host.open == "controller-tint"
    SettingsRow(stringResource(R.string.ctrl_color), stringResource(R.string.ctrl_color_hint), highlighted = tintOpen) {
        Box {
            ValueChip(
                ControllerPrefs.tints.firstOrNull { it.first == c.tint }?.second?.let { stringResource(it) } ?: stringResource(R.string.ctrl_custom),
                tintOpen,
                modifier = Modifier.paneItem("setting:controller-tint"),
            ) {
                host.open = if (tintOpen) null else "controller-tint"
            }
            AnchoredMenu(tintOpen, onDismiss = { if (host.open == "controller-tint") host.open = null }, title = stringResource(R.string.ctrl_color)) { firstItemFocus ->
                val focusIndex = ControllerPrefs.tints.indexOfFirst { it.first == c.tint }.takeIf { it >= 0 } ?: 0
                ControllerPrefs.tints.forEachIndexed { index, (color, name) ->
                    MenuItem(stringResource(name), checked = c.tint == color, leading = { Swatch(color) }, focusRequester = if (index == focusIndex) firstItemFocus else null) {
                        a.onTint(color)
                        host.open = null
                    }
                }
            }
        }
    }
    ChoiceRow(host, "controller-opacity", stringResource(R.string.ctrl_opacity), null, ControllerPrefs.opacities.map { it to stringResource(R.string.ctrl_percent, it) }, c.opacity, onPick = a.onOpacity)
    ChoiceRow(host, "controller-size", stringResource(R.string.ctrl_size), stringResource(R.string.ctrl_size_hint), ControllerPrefs.sizes.map { it to stringResource(R.string.ctrl_percent, it) }, c.size, onPick = a.onSize)
    ToggleRow(host, "controller-stick-click", stringResource(R.string.ctrl_stick_click), stringResource(R.string.ctrl_stick_click_hint), c.stickClick, onChange = a.onStickClick)
    ToggleRow(host, "controller-adaptive", stringResource(R.string.ctrl_adaptive), stringResource(R.string.ctrl_adaptive_hint), c.adaptiveSticks, onChange = a.onAdaptiveSticks)
    ToggleRow(host, "controller-rumble", stringResource(R.string.ctrl_rumble), null, c.rumble, onChange = a.onRumble)
    ToggleRow(host, "controller-steam", stringResource(R.string.ctrl_steam_button), null, c.steamButton, onChange = a.onSteamButton)
    ToggleRow(host, "controller-qam", stringResource(R.string.ctrl_qam_button), null, c.qamButton, onChange = a.onQamButton)
    ToggleRow(host, "controller-keyboard", stringResource(R.string.ctrl_keyboard_button), null, c.keyboardButton, onChange = a.onKeyboardButton)
    ToggleRow(host, "controller-select-steam", stringResource(R.string.ctrl_select_steam), stringResource(R.string.ctrl_select_steam_hint), c.selectSteam, onChange = a.onSelectSteam)
    ToggleRow(host, "controller-joycon-vr", stringResource(R.string.ctrl_joycon_vr), stringResource(R.string.ctrl_joycon_vr_hint), c.joyConVr, onChange = a.onJoyConVr)
    SettingsRow(stringResource(R.string.ctrl_layout), if (c.customLayout) stringResource(R.string.ctrl_layout_custom) else stringResource(R.string.ctrl_layout_default)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SecondaryButton(stringResource(R.string.ctrl_edit)) { a.onEditLayout() }
            SecondaryButton(stringResource(R.string.ctrl_reset), enabled = c.customLayout) { a.onResetLayout() }
        }
    }
    val remapped = c.mapping.count { (id, target) -> id != target }
    ActionRow(stringResource(R.string.ctrl_mapping), if (remapped == 0) stringResource(R.string.ctrl_mapping_default) else stringResource(R.string.ctrl_mapping_count, remapped, c.mapping.size), stringResource(R.string.ctrl_configure), a.onMapping)
    ActionRow(stringResource(R.string.ctrl_reset_all), stringResource(R.string.ctrl_reset_all_hint), stringResource(R.string.ctrl_reset), a.onResetAll)
}

@Composable
fun DualSenseSettingsGroup(host: MenuHost, c: ControllerPrefs.Settings, a: ControllerActions) {
    val status = DualSenseManager.liveStatus
    val statusSubtitle = if (status.connected) {
        stringResource(
            R.string.ctrl_dualsense_status_connected,
            if (status.isEdge) "DualSense Edge" else "DualSense",
            status.transport,
            status.batteryPercent,
            if (status.isCharging) stringResource(R.string.ctrl_dualsense_charging) else "",
            if (status.headphonesPlugged) stringResource(R.string.ctrl_dualsense_headphones) else "",
            if (status.gyroCalibrated) stringResource(R.string.ctrl_dualsense_gyro_cal) else "",
        )
    } else {
        stringResource(R.string.ctrl_dualsense_status_disconnected)
    }
    SettingsGroup(stringResource(R.string.ctrl_dualsense_group)) {
        SettingsRow(stringResource(R.string.ctrl_dualsense_status), statusSubtitle) {}
        ChoiceRow(
            host, "dualsense-lightbar", stringResource(R.string.ctrl_dualsense_lightbar), stringResource(R.string.ctrl_dualsense_lightbar_hint),
            listOf(
                ControllerPrefs.LIGHTBAR_TINT to stringResource(R.string.ctrl_dualsense_lightbar_tint),
                ControllerPrefs.LIGHTBAR_BATTERY to stringResource(R.string.ctrl_dualsense_lightbar_battery),
                ControllerPrefs.LIGHTBAR_PS_BLUE to stringResource(R.string.ctrl_dualsense_lightbar_ps_blue),
                ControllerPrefs.LIGHTBAR_OFF to stringResource(R.string.perf_cap_off),
            ),
            c.dualSenseLightbarMode,
            onPick = a.onDualSenseLightbarMode,
        )
        ChoiceRow(
            host, "dualsense-player-leds", stringResource(R.string.ctrl_dualsense_player_leds), null,
            listOf(
                ControllerPrefs.PLAYER_LEDS_P1 to stringResource(R.string.ctrl_dualsense_player_leds_p1),
                ControllerPrefs.PLAYER_LEDS_BATTERY to stringResource(R.string.ctrl_dualsense_player_leds_battery),
                ControllerPrefs.PLAYER_LEDS_OFF to stringResource(R.string.perf_cap_off),
            ),
            c.dualSensePlayerLeds,
            onPick = a.onDualSensePlayerLeds,
        )
        ChoiceRow(
            host, "dualsense-led-brightness", stringResource(R.string.ctrl_dualsense_led_brightness), null,
            listOf(
                0 to stringResource(R.string.ctrl_dualsense_led_high),
                1 to stringResource(R.string.ctrl_dualsense_led_medium),
                2 to stringResource(R.string.ctrl_dualsense_led_low),
            ),
            c.dualSensePlayerLedBrightness,
            onPick = a.onDualSensePlayerLedBrightness,
        )
        ChoiceRow(
            host, "dualsense-triggers", stringResource(R.string.ctrl_dualsense_triggers), stringResource(R.string.ctrl_dualsense_triggers_hint),
            listOf(
                ControllerPrefs.TRIGGER_AUTO to stringResource(R.string.ctrl_dualsense_trigger_auto),
                ControllerPrefs.TRIGGER_FEEDBACK to stringResource(R.string.ctrl_dualsense_trigger_feedback),
                ControllerPrefs.TRIGGER_WEAPON to stringResource(R.string.ctrl_dualsense_trigger_weapon),
                ControllerPrefs.TRIGGER_BOW to stringResource(R.string.ctrl_dualsense_trigger_bow),
                ControllerPrefs.TRIGGER_VIBRATION to stringResource(R.string.ctrl_dualsense_trigger_vibration),
                ControllerPrefs.TRIGGER_OFF to stringResource(R.string.perf_cap_off),
            ),
            c.dualSenseTriggerMode,
            onPick = a.onDualSenseTriggerMode,
        )
        ToggleRow(host, "dualsense-touchpad", stringResource(R.string.ctrl_dualsense_touchpad), stringResource(R.string.ctrl_dualsense_touchpad_hint), c.dualSenseTouchpad, onChange = a.onDualSenseTouchpad)
        ToggleRow(host, "dualsense-gyro-autocal", stringResource(R.string.ctrl_dualsense_gyro_autocal), stringResource(R.string.ctrl_dualsense_gyro_autocal_hint), c.dualSenseGyroAutoCal, onChange = a.onDualSenseGyroAutoCal)
        ToggleRow(host, "controller-dualsense-audio", stringResource(R.string.ctrl_dualsense_audio), stringResource(R.string.ctrl_dualsense_audio_hint), c.dualSenseAudio, onChange = a.onDualSenseAudio)
    }
}

@Composable
fun ControllerMappingPage(mapping: Map<String, String>, onPick: (String, String) -> Unit, onReset: () -> Unit, onBack: () -> Unit) {
    val host = rememberMenuHost()
    SettingsPage(
        host, title = stringResource(R.string.ctrl_mapping), eyebrow = stringResource(R.string.setup_controller),
        lede = stringResource(R.string.ctrl_mapping_lede),
        onBack = onBack,
    ) {
        SettingsGroup(stringResource(R.string.ctrl_onscreen_buttons)) {
            val targets = ControllerPrefs.targets.map { (id, label) -> id to stringResource(label) }
            for ((id, name) in ControllerPrefs.mappable) {
                ChoiceRow(host, "map-$id", stringResource(name), null, targets, mapping[id] ?: id) { onPick(id, it) }
            }
        }
        SettingsGroup(stringResource(R.string.ctrl_defaults)) {
            ActionRow(stringResource(R.string.ctrl_reset_mapping), stringResource(R.string.ctrl_mapping_default), stringResource(R.string.ctrl_reset), onReset)
        }
    }
}
