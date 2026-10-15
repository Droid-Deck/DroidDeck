package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DriverPage(
    title: String,
    hint: String,
    rows: List<DriverRow>,
    selected: String,
    downloads: List<DownloadRow>,
    status: String,
    checking: Boolean,
    importLabel: String,
    checkedAt: Long = Long.MAX_VALUE,
    canRestore: Boolean,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
    onDownload: (String) -> Unit,
    onImport: () -> Unit,
    onRestore: () -> Unit,
    onBack: () -> Unit,
) {
    val host = rememberMenuHost()
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var confirm by remember { mutableStateOf<DriverRow?>(null) }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        if (!checking && System.currentTimeMillis() - checkedAt > 6 * 3_600_000L) onRefresh()
    }
    val refreshFocus = remember { FocusRequester() }
    val selectedFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    var pageFocused by remember { mutableStateOf(false) }
    LaunchedEffect(inputMode.inputMode) {
        if (inputMode.inputMode != InputMode.Keyboard) return@LaunchedEffect
        snapshotFlow { Steps.step }.first { it == null }
        focusWithinFrames({ pageFocused }) { selectedFocus }
    }
    val refreshSrc = remember { MutableInteractionSource() }
    val refreshHot = refreshSrc.collectIsFocusedAsState().value || refreshSrc.collectIsHoveredAsState().value
    SettingsPage(
        host, title = title, onBack = onBack, lede = "$hint\n$status",
        modifier = Modifier.onFocusChanged { pageFocused = it.hasFocus }.focusGroup(),
        action = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (refreshHot) pal.signal.copy(alpha = 0.16f) else Color.Transparent)
                    .glideBorder(refreshHot, RoundedCornerShape(10.dp), pal.signal, pal.line)
                    .focusRequester(refreshFocus)
                    .hoverable(refreshSrc)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, interactionSource = refreshSrc, indication = null, enabled = !checking, onClick = onRefresh),
            ) {
                if (checking) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.comp_check_drivers), tint = colors.onBackground, modifier = Modifier.size(20.dp))
            }
        },
    ) {
        SettingsGroup(stringResource(R.string.driver_installed)) {
            val focusRow = rows.firstOrNull { it.id == selected } ?: rows.firstOrNull()
            rows.forEachIndexed { i, row ->
                InstalledRow(
                    row, row.id == selected, onSelect = { onSelect(row.id) }, onDelete = { confirm = row },
                    modifier = Modifier
                        .then(if (row === focusRow) Modifier.focusRequester(selectedFocus) else Modifier)
                        .then(if (i == 0) Modifier.focusProperties { up = refreshFocus } else Modifier),
                )
            }
        }
        SettingsGroup(stringResource(R.string.driver_available)) {
            if (downloads.isEmpty()) Text(
                stringResource(R.string.driver_nothing_new),
                fontSize = 12.5.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            ) else for (d in downloads) {
                SettingsRow(d.label, d.detail) {
                    val p = d.progress
                    if (p == null) SecondaryButton(stringResource(R.string.common_download)) { onDownload(d.key) }
                    else Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(120.dp)) {
                        Text("$p%", fontSize = 12.sp, color = colors.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp)) {
            SecondaryButton(importLabel, onClick = onImport)
            if (canRestore) FocusText(stringResource(R.string.driver_restore_builtin), pal.signal, onClick = onRestore)
        }
    }
    confirm?.let { row ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.common_delete_named, row.name) + "?") },
            text = {
                Text(
                    if (row.tag == DriverRow.BUNDLED) stringResource(R.string.driver_delete_builtin)
                    else if (row.tag == DriverRow.BUNDLE) stringResource(R.string.driver_delete_bundle)
                    else if (row.id == selected) stringResource(R.string.driver_delete_in_use) else stringResource(R.string.driver_delete_plain),
                    fontSize = 13.sp,
                )
            },
            confirmButton = { FocusText(stringResource(R.string.common_delete), colors.error) { confirm = null; onDelete(row.id) } },
            dismissButton = {
                val cancelFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
                FocusText(stringResource(R.string.common_cancel), colors.onBackground, modifier = Modifier.focusRequester(cancelFocus)) { confirm = null }
            },
        )
    }
}

@Composable
private fun InstalledRow(row: DriverRow, selected: Boolean, onSelect: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    val shape = RoundedCornerShape(10.dp)
    val mainFocus = remember { FocusRequester() }
    val delFocus = remember { FocusRequester() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(3.dp).clip(shape)
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else if (selected) pal.signal.copy(alpha = 0.08f) else Color.Transparent)
            .glideBorder(hot, shape, pal.signal),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f).then(modifier).focusRequester(mainFocus)
                .then(if (row.removable) Modifier.focusProperties { right = delFocus } else Modifier)
                .hoverable(src).selectable(selected = selected, role = androidx.compose.ui.semantics.Role.RadioButton, interactionSource = src, indication = null, onClick = onSelect)
                .padding(horizontal = 11.dp, vertical = 8.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(20.dp).border(2.dp, if (selected) pal.signal else colors.onSurfaceVariant, CircleShape),
            ) { if (selected) Box(Modifier.size(10.dp).background(pal.signal, CircleShape)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (selected) pal.signal else colors.onBackground, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.tag.isNotEmpty()) Text(
                        driverTagLabel(row.tag), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.07f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (row.detail.isNotEmpty()) Text(row.detail, fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (row.removable) {
            val delSrc = remember { MutableInteractionSource() }
            val delHot = delSrc.collectIsFocusedAsState().value || delSrc.collectIsHoveredAsState().value
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(end = 8.dp).size(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (delHot) colors.error.copy(alpha = 0.16f) else Color.Transparent)
                    .glideBorder(delHot, RoundedCornerShape(10.dp), colors.error)
                    .focusRequester(delFocus)
                    .focusProperties { left = mainFocus }
                    .hoverable(delSrc).clickable(role = androidx.compose.ui.semantics.Role.Button, interactionSource = delSrc, indication = null, onClick = onDelete),
            ) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.common_delete_named, row.name), tint = if (delHot) colors.error else colors.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
internal fun FocusText(text: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    val shape = RoundedCornerShape(8.dp)
    Text(
        text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color,
        modifier = modifier.clip(shape)
            .background(if (hot) color.copy(alpha = 0.14f) else Color.Transparent)
            .glideBorder(hot, shape, color)
            .hoverable(src).clickable(role = androidx.compose.ui.semantics.Role.Button, interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
