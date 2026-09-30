package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One matched driver pair in the list (DriverPairs), and what this device makes of it. */
class PairRow(
    val key: String,
    val name: String,
    val version: String,
    val detail: String,
    val recommended: Boolean,
    /** Built for this GPU's family; the others only show when asked. */
    val suits: Boolean,
    val complete: Boolean,
    val installed: Boolean,
    val active: Boolean,
)

class GpuDriversState(
    val gpuName: String = "",
    val gpuFamily: String = "",
    val soc: String = "",
    val supportText: String = "",
    val supported: Boolean = true,
    val unsupported: Boolean = false,
    val auto: Boolean = true,
    val pairs: List<PairRow> = emptyList(),
    /** The pair key being installed, and how far along (-1 = unknown). */
    val busy: String? = null,
    val percent: Int = -1,
    val autoStatus: String = "",
    val releaseStatus: String = "",
    val checking: Boolean = false,
    // Advanced: each driver on its own.
    val linuxRows: List<DriverRow> = emptyList(),
    val linuxSelected: String = "",
    val androidRows: List<DriverRow> = emptyList(),
    val androidSelected: String = "",
    val linuxDownloads: List<DownloadRow> = emptyList(),
    val androidDownloads: List<DownloadRow> = emptyList(),
    val canRestoreBundled: Boolean = false,
    /** The Android + Linux bundle both drivers are set to ("DD-Turnip 0.1.0"), or null. */
    val activeBundle: String? = null,
) {
    /** The pair both drivers are set to, if they make one. */
    val activePair: PairRow? get() = pairs.firstOrNull { it.active }
}

class GpuDriversActions(
    val onAuto: (Boolean) -> Unit = {},
    val onPair: (String) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSelectLinux: (String) -> Unit = {},
    val onSelectAndroid: (String) -> Unit = {},
    val onRemoveLinux: (String) -> Unit = {},
    val onRemoveAndroid: (String) -> Unit = {},
    val onDownloadDriver: (String) -> Unit = {},
    val onImportLinux: () -> Unit = {},
    val onImportAndroid: () -> Unit = {},
    /** The tab's own import: a bundle, or a single driver of either kind. */
    val onImportZip: () -> Unit = {},
    val onRestoreBundled: () -> Unit = {},
)

/**
 * The GPU drivers tab: this GPU, and the drivers it draws with - a runtime (Linux) driver for the
 * games and a display (Android) driver for the screen, set together as one matched pair. Auto keeps
 * the pair recommended for the GPU installed and current; Manual picks a pair from the list; the
 * two lists each on their own, and imports, are under Advanced.
 */
@Composable
internal fun GpuDriversPanel(s: GpuDriversState, a: GpuDriversActions, onAdvanced: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var showAll by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        DeviceCard(s)
        if (!s.unsupported) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeCard(Icons.Outlined.AutoAwesome, "Auto", "The best pair for this GPU, kept up to date", s.auto, Modifier.weight(1f)) { a.onAuto(true) }
                ModeCard(Icons.Outlined.Tune, "Manual", "Pick a pair yourself", !s.auto, Modifier.weight(1f)) { a.onAuto(false) }
            }
            if (s.auto) AutoCard(s)
            else {
                val shown = s.pairs.filter { it.suits || showAll }
                if (shown.isEmpty()) Text(
                    if (s.checking) "Checking for drivers…" else "No drivers listed yet. Tap refresh to look for them.",
                    fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp),
                )
                for (p in shown) PairLine(p, s.busy == p.key, s.percent, enabled = s.busy == null) { a.onPair(p.key) }
                if (s.pairs.any { !it.suits }) FocusText(
                    if (showAll) "Hide drivers for other GPUs" else "Show drivers for other GPUs", pal.signal,
                ) { showAll = !showAll }
            }
        }
        SettingsGroup("Advanced") {
            val together = s.activeBundle?.let { "Set together with the %s driver: $it carries both." }
            SettingsRow("Runtime driver", together?.format("display") ?: "Games and the desktop draw with it.") {
                DriverDropdown(s.linuxRows, s.linuxSelected, "Runtime default", a.onSelectLinux) { onAdvanced("rt") }
            }
            SettingsRow("Display driver", together?.format("runtime") ?: "Puts frames on the screen. Changes apply after DroidDeck restarts.") {
                DriverDropdown(s.androidRows, s.androidSelected, "Auto - picked by GPU", a.onSelectAndroid) { onAdvanced("panel") }
            }
        }
    }
}

/**
 * One driver list as a drop-down: the installed drivers to pick from, and the full page for
 * downloading and deleting them. An Android + Linux bundle's row is tagged, and picking it sets both.
 */
@Composable
private fun DriverDropdown(rows: List<DriverRow>, selected: String, fallback: String, onSelect: (String) -> Unit, onManage: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        SecondaryButton((rows.firstOrNull { it.id == selected }?.name ?: fallback) + "  ▾", compact = true) { open = true }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            for (row in rows) DropdownMenuItem(
                leadingIcon = { Radio(row.id == selected) },
                text = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(row.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (row.id == selected) pal.signal else colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (row.tag == DriverRow.BUNDLE) Text(row.tag, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = colors.onSurfaceVariant)
                        }
                        if (row.detail.isNotEmpty()) Text(row.detail, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                onClick = { open = false; if (row.id != selected) onSelect(row.id) },
            )
            HorizontalDivider(color = pal.line)
            DropdownMenuItem(text = { Text("Download, import or delete…", fontSize = 14.sp, color = pal.signal) }, onClick = { open = false; onManage() })
        }
    }
}

@Composable
private fun DeviceCard(s: GpuDriversState) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val tint = when {
        s.unsupported -> pal.error
        s.supported -> pal.good
        else -> AttentionAmber
    }
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14).padding(14.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp).clip(Shape12).background(tint.copy(alpha = 0.14f))) {
            Icon(Icons.Outlined.Memory, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(s.gpuName, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
            Text(listOf(s.soc, s.gpuFamily).filter { it.isNotEmpty() }.joinToString(" · "), fontSize = 13.sp, color = colors.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(tint))
            Text(s.supportText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 2)
        }
    }
}

/** What Auto has set and what it is doing. */
@Composable
private fun AutoCard(s: GpuDriversState) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val active = s.activePair
    val recommended = s.pairs.firstOrNull { it.recommended }
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14).padding(14.dp),
    ) {
        Text("IN USE", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = colors.onSurfaceVariant)
        Text(
            active?.let { "${it.name} ${it.version}" } ?: s.activeBundle ?: "The drivers built into DroidDeck",
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
        )
        Text(
            if (active != null || s.activeBundle != null) "Runtime and display drivers set together as a matched pair."
            else recommended?.let { "Recommended for this GPU: ${it.name}." } ?: "Tap refresh to look for drivers for this GPU.",
            fontSize = 13.sp, color = colors.onSurfaceVariant,
        )
        if (s.busy != null) {
            LinearProgressIndicator(
                progress = { (s.percent.coerceAtLeast(0)) / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(CircleShape),
            )
        }
        if (s.autoStatus.isNotEmpty()) Text(s.autoStatus, fontSize = 13.sp, color = colors.onBackground, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ModeCard(icon: ImageVector, title: String, hint: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val fill = if (hot) pal.signal.copy(alpha = 0.14f) else if (selected) pal.signal.copy(alpha = 0.08f) else Color.Transparent
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.paneItem("gpu-mode:$title").heightIn(min = 56.dp)
            .clip(Shape14).background(colors.surface).background(fill)
            .border(if (hot) 2.dp else 1.dp, if (hot || selected) pal.signal else pal.line, Shape14)
            .hoverable(src).clickable(interactionSource = src, indication = null, role = Role.RadioButton, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
            Text(hint, fontSize = 12.5.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Radio(selected)
    }
}

@Composable
private fun PairLine(p: PairRow, busy: Boolean, percent: Int, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val usable = enabled && p.complete && !p.active
    val fill = if (hot) pal.signal.copy(alpha = 0.14f) else if (p.active) pal.signal.copy(alpha = 0.08f) else Color.Transparent
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().paneItem("pair:${p.key}").heightIn(min = 56.dp)
            .clip(Shape12).background(colors.surface).background(fill)
            .border(if (hot) 2.dp else 1.dp, if (hot || p.active) pal.signal else pal.line, Shape12)
            .hoverable(src).clickable(interactionSource = src, indication = null, enabled = usable, role = Role.RadioButton, onClick = onClick)
            .controllerConfirm(enabled = usable, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Radio(p.active)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.name, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (p.recommended) Text(
                    "RECOMMENDED", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = pal.good,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(pal.good.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text("${p.version} · ${p.detail}", fontSize = 12.5.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            busy -> Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(96.dp)) {
                Text(if (percent >= 0) "$percent%" else "…", fontSize = 12.sp, color = colors.onSurfaceVariant)
                LinearProgressIndicator(progress = { percent.coerceAtLeast(0) / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            p.active -> Text("In use", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.signal)
            !p.complete -> Text("Unavailable", fontSize = 13.sp, color = colors.onSurfaceVariant)
            p.installed -> Text("Use", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
            else -> Text("Download", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
        }
    }
}

@Composable
private fun Radio(selected: Boolean) {
    val pal = LocalPalette.current
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (selected) pal.signal else pal.line2, CircleShape)) {
        if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(pal.signal))
    }
}
