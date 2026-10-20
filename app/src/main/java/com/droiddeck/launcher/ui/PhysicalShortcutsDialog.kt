package com.droiddeck.launcher.ui

import android.util.Log
import android.view.KeyEvent
import android.view.Window
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.PhysicalShortcuts

@Composable
internal fun PhysicalShortcutsDialog(onDismiss: () -> Unit, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    var bindings by remember { mutableStateOf(PhysicalShortcuts.read(context)) }
    var editing by remember { mutableStateOf<PhysicalShortcuts.Action?>(null) }
    var candidate by remember { mutableStateOf<PhysicalShortcuts.Button?>(null) }
    var listening by remember { mutableStateOf(false) }
    var capturedDevice by remember { mutableStateOf(-1) }
    var confirmReset by remember { mutableStateOf(false) }
    fun refresh() {
        bindings = PhysicalShortcuts.read(context)
        onChanged()
    }
    fun cancelCapture() {
        editing = null
        candidate = null
        listening = false
    }
    val back = { if (editing != null) cancelCapture() else onDismiss() }
    Dialog(
        onDismissRequest = back,
        properties = DialogProperties(dismissOnBackPress = !listening, usePlatformDefaultWidth = false),
    ) {
        CaptureShortcutKeys { event ->
            if (!listening || PhysicalShortcuts.isVolumeKey(event)) {
                false
            } else {
                if (candidate == null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    PhysicalShortcuts.button(event)?.let { button ->
                        candidate = button
                        capturedDevice = event.deviceId
                        Log.i("PhysicalShortcuts", "capture device=${button.name} key=${event.keyCode} " +
                            "scan=${event.scanCode} source=0x${event.source.toString(16)}")
                    }
                } else if (event.action == KeyEvent.ACTION_UP && event.deviceId == capturedDevice &&
                    candidate?.matches(event) == true) {
                    listening = false
                }
                true
            }
        }
        CompositionLocalProvider(LocalFrontFocus provides null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(12.dp).widthIn(max = 560.dp).fillMaxWidth()
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp - 24).dp),
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(18.dp).controllerBack(back),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.shortcuts_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.shortcuts_hint), style = MaterialTheme.typography.bodyMedium)
                    val action = editing
                    if (action == null) {
                        for (item in PhysicalShortcuts.Action.entries) {
                            SettingsGroup(stringResource(item.label)) {
                                val assigned = bindings.filter { it.action == item }
                                if (assigned.isEmpty()) {
                                    Text(stringResource(R.string.shortcuts_default), Modifier.padding(12.dp))
                                }
                                assigned.forEach { binding ->
                                    ActionRow(
                                        binding.button.name, binding.button.label,
                                        stringResource(R.string.shortcuts_remove),
                                        { PhysicalShortcuts.remove(context, binding); refresh() },
                                    )
                                }
                                ActionRow(
                                    stringResource(R.string.shortcuts_assign), null, stringResource(R.string.ctrl_configure),
                                    { editing = item; candidate = null; listening = true },
                                    modifier = Modifier.testTag("shortcuts-assign-${item.name}"),
                                )
                            }
                        }
                        ActionRow(stringResource(R.string.shortcuts_reset), null, stringResource(R.string.ctrl_reset),
                            { confirmReset = true }, modifier = Modifier.testTag("shortcuts-reset"))
                        SecondaryButton(stringResource(R.string.common_close), onClick = onDismiss)
                    } else {
                        Text(stringResource(R.string.shortcuts_capture, stringResource(action.label)),
                            style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.shortcuts_limitations), style = MaterialTheme.typography.bodySmall)
                        val button = candidate
                        if (button == null) {
                            Text(stringResource(R.string.shortcuts_press))
                        } else {
                            Text(stringResource(R.string.shortcuts_detected, button.name, button.label))
                            Text(button.diagnostics, style = MaterialTheme.typography.bodySmall)
                            if (listening) {
                                Text(stringResource(R.string.shortcuts_release))
                            } else {
                                val conflicts = bindings.filter {
                                    (it.button.sameInput(button) && it.action != action) ||
                                        (it.button.device == button.device && it.action == action &&
                                            !it.button.sameInput(button))
                                }
                                for (conflict in conflicts) {
                                    Text(stringResource(R.string.shortcuts_conflict, stringResource(conflict.action.label)))
                                    Text(stringResource(R.string.shortcuts_detected, conflict.button.name, conflict.button.label))
                                }
                                Text(stringResource(R.string.shortcuts_replace))
                                SecondaryButton(stringResource(R.string.shortcuts_save), modifier = Modifier.testTag("shortcuts-save")) {
                                    PhysicalShortcuts.assign(context, button, action)
                                    refresh()
                                    cancelCapture()
                                }
                            }
                            SecondaryButton(stringResource(R.string.shortcuts_retry), modifier = Modifier.testTag("shortcuts-retry")) {
                                candidate = null
                                capturedDevice = -1
                                listening = true
                            }
                        }
                        SecondaryButton(stringResource(R.string.common_cancel), modifier = Modifier.testTag("shortcuts-cancel"),
                            onClick = ::cancelCapture)
                    }
                }
            }
        }
    }
    if (confirmReset) ConfirmDialog(
        title = stringResource(R.string.shortcuts_reset),
        text = stringResource(R.string.shortcuts_reset_hint),
        confirm = stringResource(R.string.ctrl_reset),
        onConfirm = { PhysicalShortcuts.reset(context); refresh(); confirmReset = false },
        onDismiss = { confirmReset = false },
    )
}

@Composable
private fun CaptureShortcutKeys(onKey: (KeyEvent) -> Boolean) {
    val currentOnKey by rememberUpdatedState(onKey)
    val window = (LocalView.current.parent as DialogWindowProvider).window
    DisposableEffect(window) {
        val previous = window.callback
        // Capture before dialog navigation so Back and gamepad buttons can be assigned.
        window.callback = object : Window.Callback by previous {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                currentOnKey(event) || previous.dispatchKeyEvent(event)
        }
        onDispose { window.callback = previous }
    }
}
