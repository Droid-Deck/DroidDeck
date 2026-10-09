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
import com.droiddeck.launcher.session.SessionPrefs

class ControllerActions(
    val onOsc: (String) -> Unit,
    val onTint: (Int) -> Unit,
    val onOpacity: (Int) -> Unit,
    val onSize: (Int) -> Unit,
    val onStickClick: (Boolean) -> Unit,
    val onAdaptiveSticks: (Boolean) -> Unit,
    val onRumble: (Boolean) -> Unit,
    val onRumblePhoneFallback: (Boolean) -> Unit,
    val onSteamButton: (Boolean) -> Unit,
    val onQamButton: (Boolean) -> Unit,
    val onKeyboardButton: (Boolean) -> Unit,
    val onEditLayout: () -> Unit,
    val onResetLayout: () -> Unit,
    val onMapping: () -> Unit,
    val onResetAll: () -> Unit,
    val onTestRumble: () -> Unit = {},
)

@Composable
private fun Swatch(color: Int) {
    Box(Modifier.size(14.dp).background(Color(color), CircleShape).border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape))
}

@Composable
fun ColumnScope.ControllerRows(host: MenuHost, oscMode: String, c: ControllerPrefs.Settings, a: ControllerActions) {
    ChoiceRow(
        host, "controller-osc", stringResource(R.string.mode_osc), stringResource(R.string.ctrl_osc_hint),
        listOf(SessionPrefs.OSC_AUTO to stringResource(R.string.common_auto), SessionPrefs.OSC_ALWAYS to stringResource(R.string.common_always), SessionPrefs.OSC_STEAM_QAM to stringResource(R.string.mode_osc_qam), SessionPrefs.OSC_NEVER to stringResource(R.string.common_never)),
        oscMode, note = stringResource(R.string.mode_osc_note), onPick = a.onOsc,
    )
    val tintOpen = host.open == "controller-tint"
    SettingsRow(stringResource(R.string.ctrl_color), stringResource(R.string.ctrl_color_hint), highlighted = tintOpen) {
        Box {
            ValueChip(ControllerPrefs.tints.firstOrNull { it.first == c.tint }?.second?.let { stringResource(it) } ?: stringResource(R.string.ctrl_custom), tintOpen) {
                host.open = if (tintOpen) null else "controller-tint"
            }
            AnchoredMenu(tintOpen, onDismiss = { if (host.open == "controller-tint") host.open = null }, title = stringResource(R.string.ctrl_color)) { firstItemFocus ->
                ControllerPrefs.tints.forEachIndexed { index, (color, name) ->
                    MenuItem(stringResource(name), checked = c.tint == color, leading = { Swatch(color) }, focusRequester = if (index == 0) firstItemFocus else null) {
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
    ToggleRow(host, "controller-rumble-phone", stringResource(R.string.ctrl_rumble_phone), stringResource(R.string.ctrl_rumble_phone_hint), c.rumblePhoneFallback, onChange = a.onRumblePhoneFallback)
    ActionRow(stringResource(R.string.ctrl_rumble_test), stringResource(R.string.ctrl_rumble_test_hint), stringResource(R.string.ctrl_rumble_test_button), a.onTestRumble)
    ToggleRow(host, "controller-steam", stringResource(R.string.ctrl_steam_button), null, c.steamButton, onChange = a.onSteamButton)
    ToggleRow(host, "controller-qam", stringResource(R.string.ctrl_qam_button), null, c.qamButton, onChange = a.onQamButton)
    ToggleRow(host, "controller-keyboard", stringResource(R.string.ctrl_keyboard_button), null, c.keyboardButton, onChange = a.onKeyboardButton)
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
