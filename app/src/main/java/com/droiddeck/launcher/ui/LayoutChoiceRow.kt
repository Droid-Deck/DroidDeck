package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.ControlsChoice

/** The on-screen controls layout menu: each option with what it is, and a delete button on saved layouts nothing uses. */
@Composable
fun LayoutChoiceRow(
    host: MenuHost, key: String, options: List<ControlsChoice.Option>, selected: String, hint: String? = null,
    chipModifier: Modifier = Modifier, onPick: (String) -> Unit, onDelete: (String) -> Unit,
) {
    val label = stringResource(R.string.osc_layout)
    val open = host.open == key
    var deleting by remember { mutableStateOf<ControlsChoice.Option?>(null) }
    SettingsRow(label, hint, highlighted = open) {
        Box {
            ValueChip(options.firstOrNull { it.id == selected }?.name ?: "-", open,
                modifier = chipModifier.testTag("setting-$key")) { host.open = if (open) null else key }
            AnchoredMenu(open, onDismiss = { if (host.open == key) host.open = null }, title = label) { firstItemFocus ->
                options.forEachIndexed { index, option ->
                    MenuItem(option.name, checked = option.id == selected, detail = option.detail,
                        focusRequester = if (index == 0) firstItemFocus else null,
                        trailing = if (option.deletable) ({ DeleteButton(option.id, option.name) { host.open = null; deleting = option } }) else null) {
                        onPick(option.id)
                        host.open = null
                    }
                }
            }
        }
    }
    deleting?.let { option ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.osc_delete_layout)) },
            text = { Text(stringResource(R.string.input_delete_layout_detail, option.name)) },
            confirmButton = { PadTextButton(onClick = { deleting = null; onDelete(option.id) }, modifier = Modifier.testTag("osc-delete-confirm")) {
                Text(stringResource(R.string.common_delete))
            } },
            dismissButton = { PadTextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
}

@Composable
private fun DeleteButton(id: String, name: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val hot = source.collectIsFocusedAsState().value || source.collectIsHoveredAsState().value
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
            .background(if (hot) colors.error.copy(alpha = 0.16f) else Color.Transparent)
            .glideBorder(hot, RoundedCornerShape(8.dp), colors.error)
            .hoverable(source).clickable(role = Role.Button, interactionSource = source, indication = null, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .testTag("osc-delete-$id"),
    ) {
        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.common_delete_named, name),
            tint = if (hot) colors.error else colors.onSurfaceVariant, modifier = Modifier.size(19.dp))
    }
}
