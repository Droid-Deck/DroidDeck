package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.*

@Composable
fun InputSettingsDialog(onBack: () -> Unit, onChanged: () -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.95f)) {
            InputSettingsPage(onBack, onChanged)
        }
    }
}

@Composable
fun InputSettingsPage(onBack: () -> Unit, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val host = rememberMenuHost()
    var measuring by remember { mutableStateOf(InputTiming.enabled) }
    var timing by remember { mutableStateOf(InputTiming.report()) }
    var id by remember { mutableStateOf(InputProfile.GAME) }
    var profile by remember { mutableStateOf(InputProfiles.read(context, id)) }
    var selected by remember { mutableStateOf(InputProfiles.selection(context)) }
    var role by remember { mutableStateOf(InputRole.PHYSICAL) }
    var edit by remember { mutableStateOf<InputControl?>(null) }
    var capture by remember { mutableStateOf(false) }
    var editKey by remember { mutableStateOf<PhysicalInputKey?>(null) }
    var surface by remember { mutableStateOf(false) }
    val names = listOf(InputProfile.GAME to stringResource(R.string.input_profile_game), InputProfile.DESKTOP to stringResource(R.string.input_profile_desktop))
    SettingsPage(host, title = stringResource(R.string.input_settings), eyebrow = stringResource(R.string.setup_controller),
        lede = stringResource(R.string.input_settings_hint), onBack = onBack) {
        SettingsGroup(stringResource(R.string.shortcuts_title)) {
            PhysicalShortcutRows()
        }
        SettingsGroup(stringResource(R.string.input_profiles)) {
            ChoiceRow(host, "input-active", stringResource(R.string.input_active_profile), stringResource(R.string.input_auto_hint),
                listOf(InputProfiles.AUTO to stringResource(R.string.common_auto)) + names, selected) {
                InputProfiles.select(context, it); selected = it; onChanged()
            }
            ChoiceRow(host, "input-edit-profile", stringResource(R.string.input_edit_profile), null, names, id) {
                id = it; profile = InputProfiles.read(context, it)
            }
            ChoiceRow(host, "input-source", stringResource(R.string.input_source), null,
                listOf(InputRole.PHYSICAL.name to stringResource(R.string.input_physical), InputRole.PRIMARY.name to stringResource(R.string.input_primary),
                    InputRole.SECONDARY.name to stringResource(R.string.input_secondary)), role.name) { role = InputRole.valueOf(it) }
        }
        SettingsGroup(stringResource(R.string.input_bindings)) {
            for (control in InputControl.entries) {
                ActionRow(control.label, actionLabel(profile.action(role, control)), stringResource(R.string.ctrl_edit), { edit = control }, modifier = Modifier.testTag("input-binding-${control.id}"))
            }
        }
        if (role == InputRole.PHYSICAL) SettingsGroup(stringResource(R.string.input_extra_keys)) {
            for (binding in profile.keys) {
                ActionRow(binding.key.label, if (binding.action is InputAction.Native) stringResource(R.string.input_normal_binding) else actionLabel(binding.action), stringResource(R.string.ctrl_edit), { editKey = binding.key })
                TextButton(onClick = {
                    profile = profile.copy(keys = profile.keys - binding); InputProfiles.save(context, profile); onChanged()
                }) { Text(stringResource(R.string.input_remove_binding)) }
            }
            ActionRow(stringResource(R.string.input_capture), stringResource(R.string.input_capture_hint), stringResource(R.string.ctrl_edit), { capture = true })
        }
        SettingsGroup(stringResource(R.string.input_latency)) {
            Text(stringResource(R.string.input_latency_hint))
            Row {
                Switch(measuring, onCheckedChange = { measuring = it; InputTiming.enabled = it; if (it) InputTiming.reset() })
                Text(stringResource(R.string.input_measure))
            }
            Text(timing)
            TextButton(onClick = { timing = InputTiming.report(); android.util.Log.i("InputTiming", timing) }) { Text(stringResource(R.string.input_read_timing)) }
        }
        SettingsGroup(stringResource(R.string.input_surface)) {
            ActionRow(stringResource(R.string.input_surface_edit), stringResource(R.string.input_surface_hint), stringResource(R.string.ctrl_edit), { surface = true }, modifier = Modifier.testTag("input-surface-edit"))
            ActionRow(stringResource(R.string.ctrl_reset_mapping), null, stringResource(R.string.ctrl_reset), {
                InputProfiles.reset(context, id); profile = InputProfiles.read(context, id); onChanged()
            })
        }
    }
    edit?.let { control ->
        InputActionDialog(control, profile.action(role, control), onDismiss = { edit = null }) { action ->
            profile = profile.withBinding(role, control, action)
            InputProfiles.save(context, profile)
            edit = null
            onChanged()
        }
    }
    if (capture) InputCaptureDialog(onDismiss = { capture = false }) { capture = false; editKey = it }
    editKey?.let { key -> InputActionDialog(InputControl.A, profile.keys.firstOrNull { it.key == key }?.action ?: InputAction.Disabled,
        onDismiss = { editKey = null }, nativeTargets = false, title = key.label, onPick = { action ->
            profile = profile.copy(keys = profile.keys.filterNot { it.key == key } + PhysicalInputBinding(key, action))
            InputProfiles.save(context, profile); editKey = null; onChanged()
        }) }
    if (surface) InputSurfaceDialog(id, onDismiss = { surface = false }, onChanged)
}

@Composable
private fun actionLabel(action: InputAction): String {
    val context = LocalContext.current
    return when (action) {
        is InputAction.Native -> action.target?.let { stringResource(R.string.input_native_target, it.label) } ?: stringResource(R.string.input_native)
        InputAction.Disabled -> stringResource(R.string.input_disabled)
        is InputAction.Keys -> action.codes.joinToString(" + ") { pcKeyLabel(context, it) }
        is InputAction.MouseButton -> stringResource(when (action.code) { 272 -> R.string.input_mouse_left; 273 -> R.string.input_mouse_right; else -> R.string.input_mouse_middle })
        is InputAction.Command -> commandLabel(action.command)
        is InputAction.Directions -> listOf(action.up, action.right, action.down, action.left).joinToString(" / ") { pcKeyLabel(context, it) }
        is InputAction.Pointer -> stringResource(R.string.input_pointer)
        is InputAction.Scroll -> stringResource(R.string.input_scroll)
    }
}

@Composable
private fun commandLabel(command: InputCommand) = stringResource(when (command) {
    InputCommand.MENU -> R.string.input_command_menu
    InputCommand.KEYBOARD -> R.string.input_command_keyboard
    InputCommand.STEAM -> R.string.input_command_steam
    InputCommand.QUICK_ACCESS -> R.string.input_command_qam
})

@Composable
private fun InputActionDialog(control: InputControl, current: InputAction, onDismiss: () -> Unit, nativeTargets: Boolean = true, title: String = control.label, onPick: (InputAction) -> Unit) {
    var keys by remember { mutableStateOf(false) }
    var directions by remember { mutableStateOf(false) }
    var native by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(when (current) { is InputAction.Pointer -> current.speed; else -> 900f }) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.gesture_cancel)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton(onClick = { onPick(InputAction.Native()) }) { Text(stringResource(if (nativeTargets) R.string.input_native else R.string.input_normal_binding)) }
                if (nativeTargets) TextButton(onClick = { native = true }) { Text(stringResource(R.string.input_remap_native)) }
                TextButton(onClick = { onPick(InputAction.Disabled) }) { Text(stringResource(R.string.input_disabled)) }
                HorizontalDivider()
                TextButton(onClick = { keys = true }) { Text(stringResource(R.string.input_keys)) }
                if (control.dimensions == 2) {
                    Text(stringResource(R.string.input_pointer_speed, speed.toInt()))
                    Slider(value = speed, onValueChange = { speed = it }, valueRange = 100f..3000f)
                    TextButton(onClick = { onPick(InputAction.Pointer(speed)) }) { Text(stringResource(R.string.input_pointer)) }
                    TextButton(onClick = { onPick(InputAction.Scroll()) }) { Text(stringResource(R.string.input_scroll)) }
                    TextButton(onClick = { directions = true }) { Text(stringResource(R.string.input_direction_keys)) }
                }
                for (code in 272..274) TextButton(onClick = { onPick(InputAction.MouseButton(code)) }) { Text(actionLabel(InputAction.MouseButton(code))) }
                for (command in InputCommand.entries) TextButton(onClick = { onPick(InputAction.Command(command)) }) { Text(commandLabel(command)) }
            }
        })
    if (keys) InputKeysDialog((current as? InputAction.Keys)?.codes.orEmpty(), onDismiss = { keys = false }, onPick = { onPick(InputAction.Keys(it)) })
    if (native) AlertDialog(onDismissRequest = { native = false }, title = { Text(stringResource(R.string.input_remap_native)) }, confirmButton = {},
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            for (target in InputControl.entries) if (InputAction.Native(target).validFor(control)) {
                TextButton(onClick = { onPick(InputAction.Native(target)) }) { Text(target.label) }
            }
        } })
    if (directions) InputDirectionsDialog((current as? InputAction.Directions) ?: InputAction.Directions(103, 106, 108, 105),
        onDismiss = { directions = false }, onPick = onPick)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InputKeysDialog(initial: List<Int>, onDismiss: () -> Unit, onPick: (List<Int>) -> Unit, single: Boolean = false) {
    val context = LocalContext.current
    var codes by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.input_keys)) },
        confirmButton = { TextButton(enabled = codes.isNotEmpty(), onClick = {
            onPick(codes.sortedBy { if (it in PC_MODIFIERS) 0 else 1 })
        }) { Text(stringResource(R.string.gesture_save_changes)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.gesture_cancel)) } },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(codes.joinToString(" + ") { pcKeyLabel(context, it) })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (key in PC_KEYS.values) FilterChip(selected = key.code in codes, onClick = {
                    codes = if (single) listOf(key.code) else if (key.code in codes) codes - key.code else (codes + key.code).take(8)
                }, label = { Text(pcKeyLabel(context, key.code)) })
            }
        } })
}

@Composable
private fun InputDirectionsDialog(initial: InputAction.Directions, onDismiss: () -> Unit, onPick: (InputAction) -> Unit) {
    val context = LocalContext.current
    var codes by remember { mutableStateOf(listOf(initial.up, initial.right, initial.down, initial.left)) }
    var editing by remember { mutableStateOf<Int?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.input_direction_keys)) },
        confirmButton = { TextButton(onClick = { onPick(InputAction.Directions(codes[0], codes[1], codes[2], codes[3])) }) { Text(stringResource(R.string.gesture_save_changes)) } },
        text = { Column {
            listOf("↑", "→", "↓", "←").forEachIndexed { i, label ->
                TextButton(onClick = { editing = i }) { Text("$label  ${pcKeyLabel(context, codes[i])}") }
            }
            TextButton(onClick = { codes = listOf(17, 32, 31, 30) }) { Text("W / D / S / A") }
        } })
    editing?.let { i -> InputKeysDialog(listOf(codes[i]), onDismiss = { editing = null }, single = true, onPick = { value ->
        codes = codes.toMutableList().also { it[i] = value.first() }; editing = null
    }) }
}

@Composable
private fun InputCaptureDialog(onDismiss: () -> Unit, onPick: (PhysicalInputKey) -> Unit) {
    val focus = remember { FocusRequester() }
    var captured by remember { mutableStateOf<PhysicalInputKey?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.input_capture)) },
        modifier = Modifier.onPreviewKeyEvent {
            val event = it.nativeKeyEvent
            val key = PhysicalInputKey.capture(event)
            if (key != null) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) captured = key
                true
            } else false
        }.focusRequester(focus).focusable(),
        text = { Text(captured?.label ?: stringResource(R.string.input_capture_hint)) },
        confirmButton = { TextButton(enabled = captured != null, onClick = { captured?.let(onPick) }) { Text(stringResource(R.string.ctrl_edit)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.gesture_cancel)) } })
    LaunchedEffect(Unit) { focus.requestFocus() }
}
