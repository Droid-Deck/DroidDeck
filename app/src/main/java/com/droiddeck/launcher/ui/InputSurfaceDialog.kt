package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.*

@Composable
fun InputSurfaceDialog(profile: String, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    var layout by remember { mutableStateOf(InputSurfaceLayout.read(context, profile)) }
    var selected by remember { mutableStateOf(InputControl.A) }
    var adding by remember { mutableStateOf(false) }
    val element = layout.firstOrNull { it.control == selected }
    fun change(next: InputSurfaceElement) { layout = layout.map { if (it.control == next.control) next.bounded() else it } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.input_surface_edit)) },
        confirmButton = { TextButton(onClick = { InputSurfaceLayout.save(context, profile, layout); onChanged(); onDismiss() }) { Text(stringResource(R.string.gesture_save_changes)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.gesture_cancel)) } },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.input_surface_hint))
            BoxWithConstraints(Modifier.fillMaxWidth().height(180.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                for (e in layout) Surface(Modifier.offset(maxWidth * e.x, maxHeight * e.y).size(maxWidth * e.width, maxHeight * e.height)
                    .padding(2.dp).clickable { selected = e.control },
                    color = if (e.control == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer) {
                    Text(e.control.label, Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (element != null) {
                Text(element.control.label)
                Text(stringResource(R.string.input_position_x)); Slider(element.x, { change(element.copy(x = it)) }, valueRange = 0f..0.9f)
                Text(stringResource(R.string.input_position_y)); Slider(element.y, { change(element.copy(y = it)) }, valueRange = 0f..0.9f)
                Text(stringResource(R.string.input_width)); Slider(element.width, { change(element.copy(width = it)) }, valueRange = 0.1f..1f)
                Text(stringResource(R.string.input_height)); Slider(element.height, { change(element.copy(height = it)) }, valueRange = 0.1f..1f)
                TextButton(onClick = { layout = layout.filter { it.control != selected } }) { Text(stringResource(R.string.input_remove_element)) }
            }
            TextButton(onClick = { adding = true }) { Text(stringResource(R.string.input_add_element)) }
            TextButton(onClick = { layout = InputSurfaceLayout.defaults() }) { Text(stringResource(R.string.ctrl_reset)) }
        } })
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text(stringResource(R.string.input_add_element)) }, confirmButton = {},
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            for (control in InputControl.entries) if (layout.none { it.control == control }) {
                TextButton(onClick = {
                    layout = layout + InputSurfaceElement(control, 0.1f, 0.1f, if (control.dimensions == 2) 0.4f else 0.2f, 0.3f)
                    selected = control; adding = false
                }) { Text(control.label) }
            }
        } })
}
