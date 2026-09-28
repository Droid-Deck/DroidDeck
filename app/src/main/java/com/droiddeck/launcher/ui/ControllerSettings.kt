package com.droiddeck.launcher.ui

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
        host, "controller-osc", "On-screen controls", "When the touch pad appears in a session",
        listOf(SessionPrefs.OSC_AUTO to "Auto", SessionPrefs.OSC_ALWAYS to "Always", SessionPrefs.OSC_STEAM_QAM to "Steam + QAM", SessionPrefs.OSC_NEVER to "Never"),
        oscMode, note = "Auto shows all controls without a controller. Steam + QAM shows only those buttons.", onPick = a.onOsc,
    )
    val tintOpen = host.open == "controller-tint"
    SettingsRow("Color", "Tint of the on-screen buttons", highlighted = tintOpen) {
        Box {
            ValueChip(ControllerPrefs.tints.firstOrNull { it.first == c.tint }?.second ?: "Custom", tintOpen) {
                host.open = if (tintOpen) null else "controller-tint"
            }
            AnchoredMenu(tintOpen, onDismiss = { if (host.open == "controller-tint") host.open = null }, title = "Color") { firstItemFocus ->
                ControllerPrefs.tints.forEachIndexed { index, (color, name) ->
                    MenuItem(name, checked = c.tint == color, leading = { Swatch(color) }, focusRequester = if (index == 0) firstItemFocus else null) {
                        a.onTint(color)
                        host.open = null
                    }
                }
            }
        }
    }
    ChoiceRow(host, "controller-opacity", "Opacity", null, ControllerPrefs.opacities.map { it to "$it%" }, c.opacity, onPick = a.onOpacity)
    ChoiceRow(host, "controller-size", "Button size", "100% keeps the standard size", ControllerPrefs.sizes.map { it to "$it%" }, c.size, onPick = a.onSize)
    ToggleRow(host, "controller-stick-click", "Stick click", "Double-tap a stick and hold for L3 or R3", c.stickClick, onChange = a.onStickClick)
    ToggleRow(host, "controller-adaptive", "Adaptive sticks", "Sticks appear when touched near their saved positions and hide when released", c.adaptiveSticks, onChange = a.onAdaptiveSticks)
    SettingsRow("Layout", if (c.customLayout) "Custom positions saved" else "Placed for this screen's size and your grip") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SecondaryButton("Edit") { a.onEditLayout() }
            SecondaryButton("Reset", enabled = c.customLayout) { a.onResetLayout() }
        }
    }
    val remapped = c.mapping.count { (id, target) -> id != target }
    ActionRow("Button mapping", if (remapped == 0) "Every button sends its own input" else "$remapped of ${c.mapping.size} buttons remapped", "Configure", a.onMapping)
    ActionRow("Reset controller", "Restore the default color, opacity, size, stick behavior, mapping and layout", "Reset", a.onResetAll)
}

@Composable
fun ControllerMappingPage(mapping: Map<String, String>, onPick: (String, String) -> Unit, onReset: () -> Unit, onBack: () -> Unit) {
    val host = rememberMenuHost()
    SettingsPage(
        host, title = "Button mapping", eyebrow = "Controller",
        lede = "Choose what each on-screen button sends to the game. Hidden removes the button.",
        onBack = onBack,
    ) {
        SettingsGroup("On-screen buttons") {
            for ((id, name) in ControllerPrefs.mappable) {
                ChoiceRow(host, "map-$id", name, null, ControllerPrefs.targets, mapping[id] ?: id) { onPick(id, it) }
            }
        }
        SettingsGroup("Defaults") {
            ActionRow("Reset mapping", "Every button sends its own input again", "Reset", onReset)
        }
    }
}
