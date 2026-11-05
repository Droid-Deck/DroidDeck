package com.droiddeck.launcher.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import com.droiddeck.launcher.ui.GlideAlertDialog as AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.glasses.CalibrationPhase
import com.droiddeck.launcher.glasses.GlassesCalibrationState

@Composable
internal fun GlassesCalibrationControls(
    state: GlassesCalibrationState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    val cancel by rememberUpdatedState(onCancel)
    DisposableEffect(Unit) { onDispose { cancel() } }
    ActionRow(stringResource(R.string.glasses_calibration),
        stringResource(if (state.saved) R.string.glasses_calibration_saved_hint else R.string.glasses_calibration_hint),
        stringResource(R.string.glasses_calibration_open), { onCancel(); shown = true })
    if (state.saved && !state.running) ActionRow(stringResource(R.string.glasses_calibration_reset), null,
        stringResource(R.string.glasses_calibration_reset_button), { reset = true })
    if (reset) AlertDialog(
        onDismissRequest = { reset = false },
        title = { Text(stringResource(R.string.glasses_calibration_reset)) },
        text = { Text(stringResource(R.string.glasses_calibration_reset_hint)) },
        confirmButton = { TextButton(onClick = { onReset(); reset = false }, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.glasses_calibration_reset_button)) } },
        dismissButton = { TextButton(onClick = { reset = false }, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.common_cancel)) } },
    )
    if (shown) AlertDialog(
        onDismissRequest = { onCancel(); shown = false },
        title = { Text(stringResource(R.string.glasses_calibration)) },
        text = { Column {
            Text(stringResource(when (state.phase) {
                CalibrationPhase.IDLE -> R.string.glasses_calibration_instructions
                CalibrationPhase.SETTLING -> R.string.glasses_calibration_settling
                CalibrationPhase.COLLECTING -> R.string.glasses_calibration_collecting
                CalibrationPhase.MOVING -> R.string.glasses_calibration_moving
                CalibrationPhase.TURN_OVER -> R.string.glasses_calibration_turn_over
                CalibrationPhase.SAVED -> R.string.glasses_calibration_complete
                CalibrationPhase.FAILED -> R.string.glasses_calibration_failed
            }))
            if (state.running) {
                LinearProgressIndicator(progress = { state.progress / 100f })
                Text("${state.progress}%")
            }
        } },
        confirmButton = {
            if (!state.running) TextButton(onClick = {
                if (state.phase == CalibrationPhase.SAVED) { onDone(); onCancel(); shown = false }
                else onStart()
            }, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(if (state.phase == CalibrationPhase.SAVED) R.string.glasses_calibration_done else R.string.glasses_calibration_start)) }
        },
        dismissButton = { TextButton(onClick = { onCancel(); shown = false }, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.common_cancel)) } },
    )
}
