package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.*

@Composable
internal fun actionLabel(action: InputAction): String {
    val context = LocalContext.current
    return when (action) {
        is InputAction.Native -> action.target?.let { stringResource(R.string.input_native_target, it.label) } ?: stringResource(R.string.input_native)
        InputAction.Disabled -> stringResource(R.string.input_disabled)
        is InputAction.Keys -> action.codes.joinToString(" + ") { pcKeyLabel(context, it) }
        is InputAction.MouseButton -> stringResource(when (action.code) { 272 -> R.string.input_mouse_left; 273 -> R.string.input_mouse_right; else -> R.string.input_mouse_middle })
        is InputAction.Command -> commandLabel(action.command)
        is InputAction.Directions -> listOf(action.up, action.right, action.down, action.left).joinToString(" / ") { pcKeyLabel(context, it) }
        is InputAction.Pointer -> stringResource(R.string.input_pointer)
        is InputAction.Scroll -> stringResource(if (action.horizontal) R.string.input_scroll_horizontal else R.string.input_scroll_vertical)
    }
}

@Composable
internal fun commandLabel(command: InputCommand) = stringResource(when (command) {
    InputCommand.MENU -> R.string.input_command_menu
    InputCommand.KEYBOARD -> R.string.input_command_keyboard
    InputCommand.STEAM -> R.string.input_command_steam
    InputCommand.QUICK_ACCESS -> R.string.input_command_qam
})

@Composable
internal fun InputCaptureDialog(onDismiss: () -> Unit, onPick: (PhysicalInputKey) -> Unit) {
    val confirmFocus = remember { FocusRequester() }
    var captured by remember { mutableStateOf<PhysicalInputKey?>(null) }
    var capturedDevice by remember { mutableIntStateOf(-1) }
    var ready by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.input_capture)) },
        properties = DialogProperties(dismissOnBackPress = ready),
        text = {
            CaptureShortcutKeys { event ->
                if (ready) {
                    if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK || event.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_B) {
                        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) onDismiss()
                        true
                    } else false
                } else {
                    val key = PhysicalInputKey.capture(event)
                    if (key == null) false else {
                        if (captured == null && event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            captured = key
                            capturedDevice = event.deviceId
                        } else if (captured == key && capturedDevice == event.deviceId && event.action == android.view.KeyEvent.ACTION_UP) ready = true
                        true
                    }
                }
            }
            Column {
                Text(captured?.label ?: stringResource(R.string.input_capture_hint))
                if (captured != null) Text(stringResource(R.string.input_capture_confirm))
            }
        },
        confirmButton = {
            TextButton(enabled = ready, modifier = Modifier.focusRequester(confirmFocus)
                .controllerConfirm(ready) { captured?.let(onPick) }, onClick = { captured?.let(onPick) }) { Text(stringResource(R.string.ctrl_edit)) }
            LaunchedEffect(ready) { if (ready) confirmFocus.requestFocus() }
        },
        dismissButton = {
            TextButton(modifier = Modifier.controllerConfirm { onDismiss() }, onClick = onDismiss) { Text(stringResource(R.string.gesture_cancel)) }
            if (ready) TextButton(modifier = Modifier.controllerConfirm { captured = null; ready = false },
                onClick = { captured = null; ready = false }) { Text(stringResource(R.string.input_capture_retry)) }
        })
}
