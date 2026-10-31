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
import androidx.compose.foundation.layout.width
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
internal fun PhysicalShortcutRows(
    onChanged: () -> Unit = {},
    modifierFor: (PhysicalShortcuts.Action) -> Modifier = { Modifier },
) {
    val context = LocalContext.current
    var bindings by remember { mutableStateOf(PhysicalShortcuts.read(context)) }
    var editing by remember { mutableStateOf<PhysicalShortcuts.Action?>(null) }
    Column {
        for (action in PhysicalShortcuts.Action.entries) {
            val assigned = bindings.filter { it.action == action }
            SettingsRow(
                stringResource(action.label),
                assigned.joinToString { "${it.button.label} · ${it.button.name}" }.ifEmpty { null },
            ) {
                SecondaryButton(
                    stringResource(if (assigned.isEmpty()) R.string.game_settings_default else R.string.ctrl_custom),
                    compact = true,
                    modifier = modifierFor(action).width(LocalChipMinWidth.current)
                        .testTag("shortcuts-assign-${action.name}"),
                ) { editing = action }
            }
        }
    }
    editing?.let { action ->
        PhysicalShortcutsDialog(action, onDismiss = { editing = null }, onChanged = {
            bindings = PhysicalShortcuts.read(context)
            onChanged()
        })
    }
}

@Composable
internal fun PhysicalShortcutsDialog(
    action: PhysicalShortcuts.Action,
    onDismiss: () -> Unit,
    onChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val bindings = remember { PhysicalShortcuts.read(context) }
    var candidate by remember { mutableStateOf<PhysicalShortcuts.Button?>(null) }
    var listening by remember { mutableStateOf(true) }
    var capturedDevice by remember { mutableStateOf(-1) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = !listening, usePlatformDefaultWidth = false),
    ) {
        CaptureShortcutKeys { event ->
            if (listening && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
                (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_BUTTON_B)) {
                listening = false
                onDismiss()
                true
            } else if (!listening || PhysicalShortcuts.isVolumeKey(event)) {
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
                    candidate?.let { button ->
                        if (bindings.none { it.button.sameInput(button) && it.action != action }) {
                            PhysicalShortcuts.assign(context, button, action)
                            onChanged()
                            onDismiss()
                        }
                    }
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
                    Modifier.verticalScroll(rememberScrollState()).padding(18.dp).controllerBack(onDismiss),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.shortcuts_capture, stringResource(action.label)),
                        style = MaterialTheme.typography.titleLarge)
                    val button = candidate
                    if (button == null) {
                        Text(stringResource(R.string.shortcuts_press))
                    } else {
                        Text(stringResource(R.string.shortcuts_detected, button.name, button.label))
                        if (listening) {
                            Text(stringResource(R.string.shortcuts_release))
                        } else {
                            val replacements = bindings.filter {
                                it.button.sameInput(button) || (it.button.device == button.device && it.action == action)
                            }
                            for (conflict in replacements) {
                                Text("${conflict.button.label} · ${conflict.button.name}")
                                Text(stringResource(R.string.shortcuts_conflict, stringResource(conflict.action.label)))
                            }
                            SecondaryButton(stringResource(R.string.shortcuts_save), modifier = Modifier.testTag("shortcuts-save")) {
                                PhysicalShortcuts.assign(context, button, action)
                                onChanged()
                                onDismiss()
                            }
                        }
                        SecondaryButton(stringResource(R.string.shortcuts_retry), modifier = Modifier.testTag("shortcuts-retry")) {
                            candidate = null
                            capturedDevice = -1
                            listening = true
                        }
                    }
                    Text(stringResource(R.string.shortcuts_limitations), style = MaterialTheme.typography.bodySmall)
                    for (binding in bindings.filter { it.action == action }) {
                        ActionRow(
                            stringResource(R.string.shortcuts_detected, binding.button.name, binding.button.label),
                            null, stringResource(R.string.ctrl_reset), {
                                PhysicalShortcuts.remove(context, binding)
                                onChanged()
                                onDismiss()
                            },
                            modifier = Modifier.testTag("shortcuts-remove"),
                        )
                    }
                    SecondaryButton(stringResource(R.string.common_close), modifier = Modifier.testTag("shortcuts-cancel"),
                        onClick = onDismiss)
                }
            }
        }
    }
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
