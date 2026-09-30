package com.droiddeck.launcher.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One driver list as a full page - the Runtime driver or the Display driver - opened from the
 * session's settings page. Installed drivers are picked by tapping the row and each carries a trash
 * can (the "Auto"/"Runtime default" row is a setting, not a driver, so it has none); what the last
 * check of the release repos found is offered below; the refresh button beside the title is the
 * only thing that goes online.
 */
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
    // Back (the controller's B too) closes this page only, back to the session's settings.
    BackHandler(onBack = onBack)
    // D-pad: up from the first driver lands on refresh; the page opens with the driver in use focused.
    val refreshFocus = remember { FocusRequester() }
    val selectedFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { selectedFocus.requestFocus() } }
    val refreshSrc = remember { MutableInteractionSource() }
    val refreshHot = refreshSrc.collectIsFocusedAsState().value || refreshSrc.collectIsHoveredAsState().value
    SettingsPage(
        host, title = title, onBack = onBack, lede = "$hint\n$status",
        action = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (refreshHot) pal.signal.copy(alpha = 0.16f) else Color.Transparent)
                    .glideBorder(refreshHot, RoundedCornerShape(10.dp), pal.signal, pal.line)
                    .focusRequester(refreshFocus)
                    .hoverable(refreshSrc)
                    .clickable(interactionSource = refreshSrc, indication = null, enabled = !checking, onClick = onRefresh),
            ) {
                if (checking) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else Icon(Icons.Outlined.Refresh, contentDescription = "Check for new drivers", tint = colors.onBackground, modifier = Modifier.size(20.dp))
            }
        },
    ) {
        SettingsGroup("Installed") {
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
        SettingsGroup("Available to download") {
            if (downloads.isEmpty()) Text(
                "Nothing new from the last check. Tap the refresh button to look for newer releases.",
                fontSize = 12.5.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            ) else for (d in downloads) {
                SettingsRow(d.label, d.detail) {
                    val p = d.progress
                    if (p == null) SecondaryButton("Download") { onDownload(d.key) }
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
            if (canRestore) FocusText("Restore built-in drivers", pal.signal, onClick = onRestore)
        }
    }
    confirm?.let { row ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete ${row.name}?") },
            text = {
                Text(
                    if (row.tag == DriverRow.BUNDLED) "It is built into the app, so this removes its unpacked files and hides it. \"Restore built-in drivers\" brings it back, and Auto still uses it on a GPU that needs it."
                    else if (row.tag == DriverRow.BUNDLE) "It is one half of an Android + Linux bundle, so its runtime and display drivers are both removed. A driver in use goes back to the default."
                    else "Its files are removed from the app." + if (row.id == selected) " It is the driver in use, so the default takes its place." else "",
                    fontSize = 13.sp,
                )
            },
            // Opens on Cancel, so a stray A press on a controller never deletes anything.
            confirmButton = { FocusText("Delete", colors.error) { confirm = null; onDelete(row.id) } },
            dismissButton = {
                val cancelFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
                FocusText("Cancel", colors.onBackground, modifier = Modifier.focusRequester(cancelFocus)) { confirm = null }
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
    // Two focus targets side by side - the driver itself, and its trash can - so a controller's
    // d-pad right reaches the trash can and left comes back. (Inside one clickable row the trash
    // can was a child of the focused area and "right" had nowhere to go.) The row's outline still
    // follows the driver part, so it looks the same.
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
                .hoverable(src).clickable(interactionSource = src, indication = null, onClick = onSelect)
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
                        row.tag, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = colors.onSurfaceVariant,
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
                    .hoverable(delSrc).clickable(interactionSource = delSrc, indication = null, onClick = onDelete),
            ) { Icon(Icons.Outlined.Delete, contentDescription = "Delete ${row.name}", tint = if (delHot) colors.error else colors.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

/** A text button that shows where controller focus is: an outline in its own colour. */
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
            .hoverable(src).clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
