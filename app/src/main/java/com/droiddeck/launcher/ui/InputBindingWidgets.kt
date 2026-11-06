package com.droiddeck.launcher.ui

import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
