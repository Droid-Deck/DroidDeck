package com.droiddeck.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.*

@Composable
internal fun InputActionEditor(
    control: InputControl, current: InputAction, title: String, onBack: () -> Unit, onSave: (InputAction) -> Unit,
    element: InputSurfaceElement? = null, nativeTargets: Boolean = true, detail: String? = null,
    properties: (@Composable ColumnScope.() -> Unit)? = null,
    click: Boolean = false,
) {
    val context = LocalContext.current
    val host = rememberMenuHost()
    var draft by remember(current, title) { mutableStateOf(current) }
    val drafts = remember(current, title) { mutableMapOf<String, InputAction>() }
    fun typeOf(action: InputAction): String = when (action) {
        is InputAction.Native -> "native"; InputAction.Disabled -> "disabled"
        is InputAction.MouseButton -> "mouse"; is InputAction.Command -> "command"
        is InputAction.Pointer -> "pointer"; is InputAction.Scroll -> "scroll"
        is InputAction.Directions -> "directions"; is InputAction.Keys -> "keys"
    }
    var localType by remember(current, title) { mutableStateOf(typeOf(current).takeUnless { it == "native" || it == "disabled" } ?: "keys") }
    fun switch(type: String, initial: () -> InputAction) {
        drafts[typeOf(draft)] = draft
        draft = drafts[type] ?: initial()
        if (type != "native" && type != "disabled") localType = type
    }
    var keys by remember { mutableStateOf(false) }
    var direction by remember { mutableStateOf<Int?>(null) }
    fun valid(action: InputAction) = if (click) action.validForClick() else if (element != null) action.validFor(element) else action.validFor(control)
    val nativeOptions = remember(control, element, nativeTargets, click) {
        (listOf(InputAction.Native()) + if (nativeTargets) InputControl.entries.map { InputAction.Native(it) } else emptyList()).filter(::valid)
    }
    val route = when (draft) { is InputAction.Native -> 0; InputAction.Disabled -> 2; else -> 1 }
    BackHandler(onBack = onBack)
    SettingsPage(host, title, onBack, eyebrow = stringResource(R.string.input_properties), lede = detail,
        action = { PrimaryButton(stringResource(R.string.gesture_save_changes), enabled = valid(draft),
            modifier = Modifier.testTag("input-action-save")) { onSave(draft) } }) {
        properties?.invoke(this)
        Spacer(Modifier.height(12.dp))
        SheetChips(listOf(stringResource(R.string.input_route_controller), stringResource(R.string.input_route_local), stringResource(R.string.input_disabled)), route) {
            when (it) {
                0 -> switch("native") { nativeOptions.firstOrNull() ?: InputAction.Disabled }
                2 -> switch("disabled") { InputAction.Disabled }
                else -> switch(localType) { InputAction.Keys(listOf(28)) }
            }
        }
        Spacer(Modifier.height(16.dp))
        when (route) {
            0 -> SettingsGroup(stringResource(R.string.input_route_controller)) {
                ChoiceRow(host, "native-target", stringResource(R.string.input_remap_native), stringResource(R.string.input_controller_route_hint),
                    nativeOptions.map { it to (it.target?.label ?: stringResource(if (nativeTargets) R.string.input_native else R.string.input_normal_binding)) },
                    draft as InputAction.Native) { draft = it }
            }
            2 -> Text(stringResource(R.string.input_disabled_hint), Modifier.padding(16.dp))
            else -> {
                val types = listOf("keys" to stringResource(R.string.input_keys), "mouse" to stringResource(R.string.input_mouse_click),
                    "command" to stringResource(R.string.input_command)) + if (control.dimensions == 2) listOf(
                    "pointer" to stringResource(R.string.input_pointer), "scroll" to stringResource(R.string.input_scroll),
                    "directions" to stringResource(R.string.input_direction_keys)) else emptyList()
                val type = when (draft) { is InputAction.MouseButton -> "mouse"; is InputAction.Command -> "command";
                    is InputAction.Pointer -> "pointer"; is InputAction.Scroll -> "scroll"; is InputAction.Directions -> "directions"; else -> "keys" }
                SettingsGroup(stringResource(R.string.input_action)) {
                    ChoiceRow(host, "action-type", stringResource(R.string.input_action), null, types, type) {
                        switch(it) { when (it) { "mouse" -> InputAction.MouseButton(272); "command" -> InputAction.Command(InputCommand.MENU);
                            "pointer" -> InputAction.Pointer(); "scroll" -> InputAction.Scroll();
                            "directions" -> InputAction.Directions(103, 106, 108, 105); else -> InputAction.Keys(listOf(28)) } }
                    }
                    if (!click && draft !is InputAction.Pointer && draft !is InputAction.Scroll && draft !is InputAction.Directions) {
                        val hint = when {
                            control.isTrackpad -> R.string.input_contact_action_hint
                            control.dimensions == 2 -> R.string.input_axis_action_hint
                            control == InputControl.LT || control == InputControl.RT -> R.string.input_trigger_action_hint
                            else -> null
                        }
                        hint?.let { Text(stringResource(it), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    when (val action = draft) {
                        is InputAction.Keys -> ActionRow(stringResource(R.string.input_keys), actionLabel(action), stringResource(R.string.ctrl_edit), { keys = true })
                        is InputAction.MouseButton -> ChoiceRow(host, "mouse-button", stringResource(R.string.input_mouse_click), null,
                            (272..274).map { it to actionLabel(InputAction.MouseButton(it)) }, action.code) { draft = InputAction.MouseButton(it) }
                        is InputAction.Command -> ChoiceRow(host, "command", stringResource(R.string.input_command), null,
                            InputCommand.entries.map { it to commandLabel(it) }, action.command) { draft = InputAction.Command(it) }
                        is InputAction.Pointer -> {
                            Text(stringResource(R.string.input_pointer_speed, action.speed.toInt()), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurface)
                            Slider(action.speed, { draft = action.copy(speed = it) }, valueRange = 100f..3000f, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                        is InputAction.Scroll -> {
                            ChoiceRow(host, "scroll-axis", stringResource(R.string.input_scroll_axis), null,
                                listOf(false to stringResource(R.string.input_vertical), true to stringResource(R.string.input_horizontal)), action.horizontal) {
                                draft = action.copy(horizontal = it)
                            }
                            Text(stringResource(R.string.input_scroll_speed, action.speed.toInt()), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurface)
                            Slider(action.speed, { draft = action.copy(speed = it) }, valueRange = 1f..60f, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                        is InputAction.Directions -> {
                            ToggleRow(host, "direction-diagonals", stringResource(R.string.input_diagonal_keys), stringResource(R.string.input_diagonal_keys_hint), action.diagonals != null) {
                                draft = action.copy(diagonals = if (it) action.diagonals ?: listOf(0, 0, 0, 0) else null)
                            }
                            val codes = listOf(action.up, action.right, action.down, action.left) + action.diagonals.orEmpty()
                            val arrows = listOf("↑", "→", "↓", "←", "↗", "↘", "↙", "↖")
                            codes.forEachIndexed { i, code ->
                                ActionRow(arrows[i], if (code == 0) stringResource(if (i < 4) R.string.input_disabled else R.string.input_diagonal_neighbors) else pcKeyLabel(context, code), stringResource(R.string.ctrl_edit), { direction = i })
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
    if (keys) KeyCombinationPicker(onDismiss = { keys = false }, initial = (draft as? InputAction.Keys)?.codes.orEmpty(),
        sequence = true, sequenceLimit = 8, title = stringResource(R.string.input_keys), confirm = stringResource(R.string.gesture_save_changes), onAdd = {
            draft = InputAction.Keys(it.sortedBy { code -> if (code in PC_MODIFIERS) 0 else 1 }); keys = false
        })
    direction?.let { at ->
        val action = draft as? InputAction.Directions ?: return@let
        val codes = listOf(action.up, action.right, action.down, action.left) + action.diagonals.orEmpty()
        KeyCombinationPicker(onDismiss = { direction = null }, initial = listOf(codes[at]).filter { it != 0 }, sequence = true, sequenceLimit = 1, allowEmpty = true,
            title = stringResource(R.string.input_direction_keys), confirm = stringResource(R.string.gesture_save_changes), onAdd = {
                val next = codes.toMutableList().also { values -> values[at] = it.firstOrNull() ?: 0 }
                draft = InputAction.Directions(next[0], next[1], next[2], next[3], if (action.diagonals != null) next.drop(4) else null); direction = null
            })
    }
}
