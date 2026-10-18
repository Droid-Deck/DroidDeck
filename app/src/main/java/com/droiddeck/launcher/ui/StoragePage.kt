package com.droiddeck.launcher.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.storage.Bucket
import com.droiddeck.launcher.storage.CleanItem
import com.droiddeck.launcher.storage.ComponentUsage
import com.droiddeck.launcher.storage.GameUsage
import com.droiddeck.launcher.storage.StorageReport
import com.droiddeck.launcher.storage.StorageState
import com.droiddeck.launcher.storage.ToolReason
import com.droiddeck.launcher.storage.ToolState
import com.droiddeck.launcher.storage.ToolUsage
import java.text.DateFormat
import java.util.Date

private val BucketColors = mapOf(
    Bucket.GAMES to Color(0xFF4B9CF5),
    Bucket.GAME_DATA to Color(0xFF8F7CF6),
    Bucket.TOOLS to Color(0xFF3FC1C9),
    Bucket.COMPONENTS to Color(0xFF9BD65B),
    Bucket.STORES to Color(0xFFF0B44C),
    Bucket.DESKTOP to Color(0xFFE57FB1),
    Bucket.SYSTEM to Color(0xFF7D8796),
    Bucket.DROIDDECK to Color(0xFF6FD08C),
)
private val OtherApps = Color(0xFF3A3F48)
private val Shape22 = RoundedCornerShape(18.dp)

private fun gb(bytes: Long) = StorageState.format(bytes)
private const val ONE_MB = 1024L * 1024

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun StoragePanel(s: FrontEndState, a: FrontEndActions) {
    val ctx = LocalContext.current
    var view by remember { mutableStateOf("overview") }
    var cleanup by remember { mutableStateOf(false) }
    val report = StorageState.report
    LaunchedEffect(Unit) { StorageState.refresh(ctx) }
    LaunchedEffect(s.sessionRunning) { if (!s.sessionRunning) StorageState.refresh(ctx, force = report != null) }
    if (report == null) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(Shape14).background(MaterialTheme.colorScheme.surface)
                .border(1.dp, LocalPalette.current.line, Shape14).padding(18.dp),
        ) {
            CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(28.dp), color = LocalPalette.current.signal)
            Column {
                Text(stringResource(R.string.storage_measuring), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                Text(stringResource(R.string.storage_measuring_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    if (cleanup) CleanUpDialog(report, s.sessionRunning) { cleanup = false }
    val top = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(view) {
        StorageState.lastFreed = null
        StorageState.lastError = null
        runCatching { top.bringIntoView() }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).bringIntoViewRequester(top))
    when (view) {
        "games" -> GamesView(report, s.sessionRunning, a) { view = "overview" }
        "data" -> GameDataView(report, s.sessionRunning) { view = "overview" }
        "components" -> ComponentsView(report, s.sessionRunning) { view = "overview" }
        "desktop" -> DesktopView(report, s.sessionRunning) { view = "overview" }
        "tools" -> ToolsView(report, s.sessionRunning) { view = "overview" }
        else -> Overview(report, s.sessionRunning, onCleanUp = { cleanup = true }, onOpen = { view = it })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Overview(report: StorageReport, sessionRunning: Boolean, onCleanUp: () -> Unit, onOpen: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val wide = !LocalNarrowPane.current
    val used = report.total - report.free
    val safe = StorageState.safeBytes(report)
    val low = report.free < StorageState.LOW_SPACE_BYTES
    ActionStatus()
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.storage_internal), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
                Text("· " + stringResource(R.string.storage_used_of, gb(used), gb(report.total)), fontSize = 15.sp, color = colors.onSurfaceVariant)
                Pill(stringResource(R.string.storage_free, gb(report.free)), if (low) AttentionAmber else pal.good)
            }
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(report.at))
            Text(
                stringResource(R.string.storage_droiddeck_uses, gb(report.droiddeck), time) +
                    if (StorageState.scanning) " · " + stringResource(R.string.storage_rescanning) else "",
                fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
            )
            UsageBar(report, Modifier.padding(top = 12.dp, bottom = 10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Bucket.entries.forEach { Legend(BucketColors.getValue(it), bucketLabel(it)) }
                Legend(OtherApps, stringResource(R.string.storage_other_apps))
            }
        }
        Box(Modifier.width(1.dp).height(120.dp).background(pal.line))
        Column(Modifier.widthIn(min = 170.dp, max = 210.dp)) {
            Text(stringResource(R.string.storage_safe_now), fontSize = 13.sp, color = colors.onSurfaceVariant)
            Text(gb(safe), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (safe > 0) pal.good else colors.onBackground)
            Text(stringResource(R.string.storage_safe_hint), fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
            PrimaryButton(stringResource(R.string.storage_clean_up), enabled = !sessionRunning && !StorageState.cleaning, compact = true, onClick = onCleanUp)
        }
    }
    if (sessionRunning) {
        Spacer(Modifier.height(8.dp))
        Note(stringResource(R.string.storage_session_running))
    }
    SectionTitle(stringResource(R.string.storage_where), null)
    val unused = report.unusedTools.sumOf { it.bytes }
    val tiles = listOf<@Composable (Modifier) -> Unit>(
        { m ->
            BucketCard(Bucket.GAMES, Icons.Outlined.VideogameAsset, stringResource(R.string.storage_games),
                pluralStringResource(R.plurals.storage_games_installed, report.games.size, report.games.size) +
                    if (report.gamesOnSd > 0) " · " + pluralStringResource(R.plurals.storage_games_on_sd, report.gamesOnSd, report.gamesOnSd) else "",
                report.buckets.getValue(Bucket.GAMES), m) { onOpen("games") }
        },
        { m ->
            BucketCard(Bucket.GAME_DATA, Icons.Outlined.SaveAlt, stringResource(R.string.storage_game_data),
                stringResource(R.string.storage_game_data_hint, gb(report.prefixes), gb(report.workshop), gb(report.shaders)),
                report.buckets.getValue(Bucket.GAME_DATA), m) { onOpen("data") }
        },
        { m ->
            BucketCard(Bucket.TOOLS, Icons.Outlined.Layers, stringResource(R.string.storage_tools),
                if (unused > 0) stringResource(R.string.storage_tools_unused, gb(unused)) else pluralStringResource(R.plurals.storage_tools_count, report.tools.size, report.tools.size),
                report.buckets.getValue(Bucket.TOOLS), m, attention = unused > 0) { onOpen("tools") }
        },
        { m ->
            val unusedComponents = report.unusedComponents.sumOf { it.bytes }
            BucketCard(Bucket.COMPONENTS, Icons.Outlined.Extension, stringResource(R.string.storage_components),
                if (unusedComponents > 0) stringResource(R.string.storage_tools_unused, gb(unusedComponents))
                else pluralStringResource(R.plurals.storage_components_count, report.components.size, report.components.size),
                report.buckets.getValue(Bucket.COMPONENTS), m, attention = unusedComponents > 0) { onOpen("components") }
        },
        { m ->
            BucketCard(Bucket.STORES, Icons.Outlined.ShoppingBag, stringResource(R.string.storage_stores),
                stringResource(R.string.storage_stores_hint), report.buckets.getValue(Bucket.STORES), m)
        },
        { m ->
            BucketCard(Bucket.DESKTOP, Icons.Outlined.Monitor, stringResource(R.string.storage_desktop),
                pluralStringResource(R.plurals.storage_desktop_apps, report.flatpakApps.size, report.flatpakApps.size) +
                    " · " + stringResource(R.string.storage_desktop_shared, gb(report.flatpakRuntimes + report.flatpakCache)),
                report.buckets.getValue(Bucket.DESKTOP), m) { onOpen("desktop") }
        },
        { m ->
            BucketCard(Bucket.SYSTEM, Icons.Outlined.Storage, stringResource(R.string.storage_system),
                stringResource(R.string.storage_system_hint), report.buckets.getValue(Bucket.SYSTEM), m, locked = true)
        },
        { m ->
            BucketCard(Bucket.DROIDDECK, Icons.Outlined.Folder, stringResource(R.string.storage_app_data),
                stringResource(R.string.storage_app_data_hint, gb(report.logBytes)), report.buckets.getValue(Bucket.DROIDDECK), m)
        },
    )
    val columns = if (wide) 2 else 1
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEach { it(Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun bucketLabel(bucket: Bucket) = stringResource(when (bucket) {
    Bucket.GAMES -> R.string.storage_legend_games
    Bucket.GAME_DATA -> R.string.storage_legend_data
    Bucket.TOOLS -> R.string.storage_legend_tools
    Bucket.COMPONENTS -> R.string.storage_legend_components
    Bucket.STORES -> R.string.storage_legend_stores
    Bucket.DESKTOP -> R.string.storage_legend_desktop
    Bucket.SYSTEM -> R.string.storage_legend_system
    Bucket.DROIDDECK -> R.string.storage_legend_app
})

@Composable
private fun UsageBar(report: StorageReport, modifier: Modifier) {
    val total = report.total.coerceAtLeast(1).toFloat()
    val other = (report.total - report.free - report.droiddeck).coerceAtLeast(0)
    Row(modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF0A0C0F))) {
        Bucket.entries.forEach { b ->
            val v = report.buckets.getValue(b)
            if (v > 0) Box(Modifier.weight(v / total).fillMaxHeight().background(BucketColors.getValue(b)))
        }
        if (other > 0) Box(Modifier.weight(other / total).fillMaxHeight().background(OtherApps))
        if (report.free > 0) Spacer(Modifier.weight(report.free / total))
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun BucketCard(
    bucket: Bucket, icon: ImageVector, title: String, detail: String, bytes: Long, modifier: Modifier,
    attention: Boolean = false, locked: Boolean = false, onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src) && onClick != null
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "bucketScale")
    val tint = BucketColors.getValue(bucket)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.paneItem("storage:${bucket.name}")
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(Shape14)
            .background(if (hot) pal.signal.copy(alpha = 0.10f) else colors.surface)
            .glideBorder(hot, Shape14, pal.signal, pal.line)
            .then(if (onClick != null) Modifier.hoverable(src)
                .clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
                .controllerConfirm(onClick = onClick) else Modifier.hoverable(src))
            .heightIn(min = 64.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tint)) {
            Icon(icon, contentDescription = null, tint = Color(0xFF0B0D10), modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, fontSize = 12.5.sp, color = if (attention) AttentionAmber else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(gb(bytes), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
        when {
            locked -> Icon(Icons.Outlined.Lock, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
            onClick != null -> Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = if (hot) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
            else -> Spacer(Modifier.width(16.dp))
        }
    }
}

@Composable
private fun CleanUpDialog(report: StorageReport, sessionRunning: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val available = CleanItem.entries.filter { StorageState.bytes(report, it) > 0 }
    var picked by remember { mutableStateOf(available.filter { it in StorageState.SAFE }.toSet()) }
    val total = picked.sumOf { StorageState.bytes(report, it) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    AlertDialog(
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.62f).widthIn(min = 420.dp, max = 760.dp),
        onDismissRequest = { if (!StorageState.cleaning) onDismiss() },
        title = { Text(stringResource(R.string.storage_clean_up_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.storage_clean_up_lede), fontSize = 13.sp, color = colors.onSurfaceVariant)
                if (available.isEmpty()) Text(stringResource(R.string.storage_nothing_to_clean), fontSize = 14.sp, color = colors.onBackground)
                available.forEachIndexed { i, item ->
                    val on = item in picked
                    CleanRow(
                        item, report, on, if (i == 0) Modifier.focusRequester(first) else Modifier,
                    ) { picked = if (on) picked - item else picked + item }
                }
                Text(
                    stringResource(R.string.storage_selected, gb(total), gb(report.free), gb(report.free + total)),
                    fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                )
                if (StorageState.cleaning) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = pal.signal)
                    Text(stringResource(R.string.storage_cleaning), fontSize = 13.sp, color = colors.onBackground)
                }
            }
        },
        confirmButton = {
            if (picked.isNotEmpty() && !sessionRunning && !StorageState.cleaning) {
                FocusText(stringResource(R.string.storage_free_button, gb(total)), pal.signal) {
                    StorageState.clean(ctx, picked) { onDismiss() }
                }
            }
        },
        dismissButton = {
            FocusText(stringResource(R.string.common_cancel), colors.onBackground) { if (!StorageState.cleaning) onDismiss() }
        },
    )
}

@Composable
private fun CleanRow(item: CleanItem, report: StorageReport, on: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    val unsureCount = report.leftovers.count { !it.safe }
    val safeCount = report.leftovers.count { it.safe }
    val oldTools = report.unusedTools.filter { it.reason != ToolReason.CUSTOM }
    val custom = report.unusedTools.filter { it.reason == ToolReason.CUSTOM }
    val unusedLabels = report.unusedComponents.map { componentLabel(it) }
    val (title, detail, tag) = when (item) {
        CleanItem.OLD_TOOLS -> Triple(stringResource(R.string.storage_item_runtimes), oldTools.joinToString(", ") { it.name }, 0)
        CleanItem.LEFTOVERS -> Triple(stringResource(R.string.storage_item_leftovers), pluralStringResource(R.plurals.storage_item_leftovers_detail, safeCount, safeCount), 0)
        CleanItem.LEFTOVERS_UNSURE -> Triple(stringResource(R.string.storage_item_leftovers_unsure), pluralStringResource(R.plurals.storage_item_leftovers_unsure_detail, unsureCount, unsureCount), 2)
        CleanItem.LOGS -> Triple(stringResource(R.string.storage_item_logs), stringResource(R.string.storage_item_logs_detail), 0)
        CleanItem.UNUSED_CUSTOM -> Triple(custom.joinToString(", ") { it.name }, stringResource(R.string.storage_item_custom_detail), 1)
        CleanItem.UNUSED_COMPONENTS -> Triple(stringResource(R.string.storage_item_components), unusedLabels.joinToString(", "), 1)
        CleanItem.SHADERS -> Triple(stringResource(R.string.storage_item_shaders), stringResource(R.string.storage_item_shaders_detail), 2)
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().clip(shape)
            .background(if (hot) pal.signal.copy(alpha = 0.10f) else colors.surfaceVariant.copy(alpha = 0.5f))
            .glideBorder(hot, shape, pal.signal, pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Checkbox, onClick = onToggle)
            .controllerConfirm(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                .background(if (on) pal.signal else Color.Transparent)
                .border(2.dp, if (on) pal.signal else colors.onSurfaceVariant, RoundedCornerShape(6.dp)),
        ) { if (on) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                when (tag) {
                    0 -> Pill(stringResource(R.string.storage_tag_safe), pal.good)
                    1 -> Pill(stringResource(R.string.storage_tag_unused), colors.onSurfaceVariant)
                    else -> Pill(stringResource(if (item == CleanItem.SHADERS) R.string.storage_tag_stutter else R.string.storage_tag_saves), AttentionAmber)
                }
            }
            Text(detail, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(gb(StorageState.bytes(report, item)), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
    }
}

@Composable
private fun SubHeader(title: String, detail: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp).controllerBack(onBack),
    ) {
        BackLink(stringResource(R.string.storage_back), compact = true, onClick = onBack)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
private fun GamesView(report: StorageReport, sessionRunning: Boolean, a: FrontEndActions, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var sort by rememberSaveable { mutableStateOf(0) }
    var confirm by remember { mutableStateOf<GameUsage?>(null) }
    val games = when (sort) {
        1 -> report.games.sortedBy { if (it.lastPlayed == 0L) Long.MIN_VALUE else it.lastPlayed }
        2 -> report.games.filter { !it.internal }
        else -> report.games
    }
    val max = report.games.maxOfOrNull { it.total }?.coerceAtLeast(1) ?: 1
    SubHeader(stringResource(R.string.storage_games), stringResource(R.string.storage_games_with_data, gb(report.games.sumOf { it.total })), onBack)
    Box(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        SheetChips(listOf(stringResource(R.string.storage_sort_largest), stringResource(R.string.storage_sort_least_played), stringResource(R.string.storage_sort_sd)), sort, null) { sort = it }
    }
    if (sessionRunning) { Note(stringResource(R.string.storage_session_running)); Spacer(Modifier.height(8.dp)) }
    Column(Modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14)) {
        if (games.isEmpty()) Text(stringResource(R.string.storage_no_games), fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        games.forEachIndexed { i, g ->
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).background(artBrush(hueOf(g.name)))) {
                    if (g.art != null) AsyncImage(model = g.art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(42.dp))
                }
                Column(Modifier.weight(1.3f)) {
                    Text(gameName(g), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            g.library.takeIf { !g.internal },
                            if (g.lastPlayed > 0) stringResource(R.string.storage_played, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(g.lastPlayed * 1000))) else stringResource(R.string.storage_never_played),
                        ).joinToString(" · "),
                        fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF0A0C0F))) {
                        listOf(g.game to BucketColors.getValue(Bucket.GAMES), g.prefix to BucketColors.getValue(Bucket.GAME_DATA), (g.shaders + g.workshop) to Color(0xFFC5B8FF))
                            .forEach { (v, c) -> if (v > 0) Box(Modifier.weight(v.toFloat() / max).fillMaxHeight().background(c)) }
                        val rest = max - g.total
                        if (rest > 0) Spacer(Modifier.weight(rest.toFloat() / max))
                    }
                    Text(
                        stringResource(R.string.storage_game_parts, gb(g.game), gb(g.data)),
                        fontSize = 11.5.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp), maxLines = 1,
                    )
                }
                Text(gb(g.total), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, modifier = Modifier.widthIn(min = 72.dp))
                SecondaryButton(stringResource(R.string.storage_uninstall), compact = true, enabled = !sessionRunning) { confirm = g }
            }
            if (i < games.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
        }
    }
    Spacer(Modifier.height(8.dp))
    Note(stringResource(R.string.storage_games_note))
    confirm?.let { g ->
        // steam://uninstall reaches only the session's client, the selected one.
        val otherClient = g.x64 != (SessionPrefs.steamClientArch(LocalContext.current) == "x86_64")
        if (otherClient) AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.storage_uninstall_title, gameName(g))) },
            text = { Text(stringResource(if (g.x64) R.string.storage_uninstall_in_x64 else R.string.storage_uninstall_in_arm64), fontSize = 14.sp) },
            confirmButton = {
                val ok = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { ok.requestFocus() } }
                FocusText(stringResource(R.string.common_ok), pal.signal, modifier = Modifier.focusRequester(ok)) { confirm = null }
            },
        ) else AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.storage_uninstall_title, gameName(g))) },
            text = { Text(stringResource(R.string.storage_uninstall_body, gb(g.game)), fontSize = 14.sp) },
            confirmButton = { FocusText(stringResource(R.string.storage_open_steam), pal.signal) { confirm = null; a.onSteamUrl("steam://uninstall/${g.appId}") } },
            dismissButton = {
                val cancel = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
                FocusText(stringResource(R.string.common_cancel), colors.onBackground, modifier = Modifier.focusRequester(cancel)) { confirm = null }
            },
        )
    }
}

@Composable
private fun gameName(g: GameUsage): String = if (g.x64) stringResource(R.string.storage_game_x64, g.name) else g.name

@Composable
private fun ToolsView(report: StorageReport, sessionRunning: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var confirm by remember { mutableStateOf<ToolUsage?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    val unused = report.unusedTools
    val inUse = report.tools.filter { it.state != ToolState.UNUSED }
    SubHeader(
        stringResource(R.string.storage_tools),
        stringResource(R.string.storage_tools_detail, gb(report.tools.sumOf { it.bytes }), gb(unused.sumOf { it.bytes })), onBack,
    ) {
        if (unused.isNotEmpty()) PrimaryButton(stringResource(R.string.storage_remove_unused, gb(unused.sumOf { it.bytes })), compact = true,
            enabled = !sessionRunning && !StorageState.cleaning) { confirmAll = true }
    }
    if (sessionRunning) { Note(stringResource(R.string.storage_session_running)); Spacer(Modifier.height(4.dp)) }
    SectionTitle(stringResource(R.string.storage_in_use), null)
    ToolList(inUse, sessionRunning) { }
    if (unused.isNotEmpty()) {
        SectionTitle(stringResource(R.string.storage_not_used), null)
        ToolList(unused, sessionRunning) { confirm = it }
    }
    confirm?.let { t ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.storage_remove_title, t.name)) },
            text = { Text(toolDetail(t) + "\n\n" + stringResource(R.string.storage_remove_body, gb(t.bytes)), fontSize = 14.sp) },
            confirmButton = { FocusText(stringResource(R.string.storage_remove), colors.error) { confirm = null; StorageState.removeTool(ctx, t) { } } },
            dismissButton = {
                val cancel = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
                FocusText(stringResource(R.string.common_cancel), colors.onBackground, modifier = Modifier.focusRequester(cancel)) { confirm = null }
            },
        )
    }
    if (confirmAll) AlertDialog(
        onDismissRequest = { confirmAll = false },
        title = { Text(stringResource(R.string.storage_remove_unused, gb(unused.sumOf { it.bytes }))) },
        text = { Text(unused.joinToString("\n") { "• " + it.name + "  " + gb(it.bytes) }, fontSize = 14.sp) },
        confirmButton = {
            FocusText(stringResource(R.string.storage_remove), colors.error) {
                confirmAll = false
                StorageState.clean(ctx, setOf(CleanItem.OLD_TOOLS, CleanItem.UNUSED_CUSTOM)) { }
            }
        },
        dismissButton = {
            val cancel = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
            FocusText(stringResource(R.string.common_cancel), colors.onBackground, modifier = Modifier.focusRequester(cancel)) { confirmAll = false }
        },
    )
    if (StorageState.cleaning) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = pal.signal)
        Text(stringResource(R.string.storage_cleaning), fontSize = 13.sp, color = colors.onBackground)
    }
}

@Composable
private fun toolDetail(t: ToolUsage): String = when (t.reason) {
    ToolReason.DEFAULT_PROTON -> stringResource(R.string.storage_tool_default_proton)
    ToolReason.PROTON -> stringResource(R.string.storage_tool_proton)
    ToolReason.FEX_RUNTIME -> stringResource(R.string.storage_tool_fex_runtime)
    ToolReason.ARM_RUNTIME -> stringResource(R.string.storage_tool_arm_runtime)
    ToolReason.REDISTRIBUTABLES -> stringResource(R.string.storage_tool_redist)
    ToolReason.FEX -> stringResource(R.string.storage_tool_fex)
    ToolReason.STEAM_TOOL -> stringResource(R.string.storage_tool_steam)
    ToolReason.OLD_RUNTIME -> stringResource(R.string.storage_tool_old_runtime)
    ToolReason.X86_PROTON -> stringResource(R.string.storage_tool_x86_proton)
    ToolReason.CUSTOM_UNKNOWN -> stringResource(R.string.storage_tool_custom_unknown)
    ToolReason.X64_CLIENT -> stringResource(R.string.storage_tool_x64_client)
    ToolReason.CUSTOM -> when {
        t.isDefault -> stringResource(R.string.storage_tool_custom_default)
        t.games > 0 -> pluralStringResource(R.plurals.storage_tool_custom_games, t.games, t.games)
        else -> stringResource(R.string.storage_tool_custom_unused)
    }
}

@Composable
private fun ToolList(tools: List<ToolUsage>, sessionRunning: Boolean, onRemove: (ToolUsage) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(Modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14)) {
        tools.forEachIndexed { i, t ->
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(toolDetail(t), fontSize = 12.5.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                when (t.state) {
                    ToolState.IN_USE -> Pill(if (t.isDefault) stringResource(R.string.storage_tag_default) else pluralStringResource(R.plurals.storage_tag_games, t.games, t.games), pal.signal)
                    ToolState.REQUIRED -> Pill(stringResource(R.string.storage_tag_required), colors.onSurfaceVariant)
                    ToolState.UNUSED -> Pill(stringResource(R.string.storage_tag_not_used), AttentionAmber)
                }
                Text(gb(t.bytes), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, modifier = Modifier.widthIn(min = 70.dp))
                if (t.state == ToolState.UNUSED) SecondaryButton(stringResource(R.string.storage_remove), compact = true, enabled = !sessionRunning && !StorageState.cleaning) { onRemove(t) }
                else Icon(Icons.Outlined.Lock, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
            if (i < tools.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
        }
    }
}

@Composable
internal fun LowSpaceDialog(free: Long, onFreeUp: () -> Unit, onPlay: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val report = StorageState.report
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.storage_low_title, gb(free))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.storage_low_body), fontSize = 14.sp)
                if (report != null && StorageState.safeBytes(report) > 0) Text(
                    stringResource(R.string.storage_low_safe, gb(StorageState.safeBytes(report))), fontSize = 13.sp, color = pal.good,
                )
            }
        },
        confirmButton = { FocusText(stringResource(R.string.storage_free_up), pal.signal, modifier = Modifier.focusRequester(focus), onClick = onFreeUp) },
        dismissButton = { FocusText(stringResource(R.string.storage_play_anyway), colors.onBackground, onClick = onPlay) },
    )
}

@Composable
private fun ActionStatus() {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    when {
        StorageState.cleaning -> Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14).padding(12.dp),
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = pal.signal)
            Text(StorageState.progress ?: stringResource(R.string.storage_cleaning), fontSize = 13.sp, color = colors.onBackground)
        }
        StorageState.lastError != null -> { Note(stringResource(R.string.storage_failed, StorageState.lastError ?: "")); Spacer(Modifier.height(8.dp)) }
        StorageState.lastFreed != null -> { Note(stringResource(R.string.storage_freed, gb(StorageState.lastFreed ?: 0))); Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun componentLabel(c: ComponentUsage): String {
    val name = com.droiddeck.launcher.session.ComponentsManager.LABEL[c.comp] ?: c.comp
    return if (c.packageFile != null) "$name ${c.version}"
    else stringResource(R.string.storage_component_original, name, c.protonName ?: c.protonId.orEmpty())
}

@Composable
private fun ListCard(content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(Modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14)) { content() }
}

@Composable
private fun ItemRow(title: String, detail: String?, bytes: Long, last: Boolean, pill: (@Composable () -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, fontSize = 12.5.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        pill?.invoke()
        Text(gb(bytes), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, modifier = Modifier.widthIn(min = 70.dp))
        action?.invoke()
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, danger: Boolean = true, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, fontSize = 14.sp) },
        confirmButton = { FocusText(confirm, if (danger) colors.error else pal.signal) { onDismiss(); onConfirm() } },
        dismissButton = {
            val cancel = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
            FocusText(stringResource(R.string.common_cancel), colors.onBackground, modifier = Modifier.focusRequester(cancel), onClick = onDismiss)
        },
    )
}

@Composable
private fun GameDataView(report: StorageReport, sessionRunning: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val busy = sessionRunning || StorageState.cleaning
    var confirm by remember { mutableStateOf<com.droiddeck.launcher.storage.Leftover?>(null) }
    SubHeader(stringResource(R.string.storage_game_data), stringResource(R.string.storage_game_data_hint, gb(report.prefixes), gb(report.workshop), gb(report.shaders)), onBack)
    ActionStatus()
    if (sessionRunning) { Note(stringResource(R.string.storage_session_running)); Spacer(Modifier.height(4.dp)) }
    val games = report.games.filter { it.data >= ONE_MB }.sortedByDescending { it.data }
    SectionTitle(stringResource(R.string.storage_data_installed), gb(games.sumOf { it.data }))
    ListCard {
        if (games.isEmpty()) Text(stringResource(R.string.storage_no_games), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        games.forEachIndexed { i, g ->
            ItemRow(
                gameName(g), stringResource(R.string.storage_data_parts, gb(g.prefix), gb(g.shaders), gb(g.workshop)), g.data, i == games.lastIndex,
                action = { SecondaryButton(stringResource(R.string.storage_clear_shaders), compact = true, enabled = !busy && g.shaders >= ONE_MB) { StorageState.clearShaders(ctx, g.appId, g.x64) } },
            )
        }
    }
    val leftovers = report.leftovers.sortedByDescending { it.bytes }
    if (!report.leftoversChecked) {
        SectionTitle(stringResource(R.string.storage_data_leftovers), null)
        Note(stringResource(R.string.storage_leftovers_unchecked))
    }
    if (leftovers.isNotEmpty()) {
        SectionTitle(stringResource(R.string.storage_data_leftovers), gb(leftovers.sumOf { it.bytes }))
        val safe = leftovers.filter { it.safe }
        ListCard {
            if (safe.isNotEmpty()) ItemRow(
                pluralStringResource(R.plurals.storage_item_leftovers_detail, safe.size, safe.size), null, safe.sumOf { it.bytes }, leftovers.none { !it.safe },
                pill = { Pill(stringResource(R.string.storage_tag_safe), LocalPalette.current.good) },
                action = { SecondaryButton(stringResource(R.string.storage_remove), compact = true, enabled = !busy) { StorageState.clean(ctx, setOf(CleanItem.LEFTOVERS)) { } } },
            )
            val unsure = leftovers.filter { !it.safe }
            unsure.forEachIndexed { i, l ->
                ItemRow(
                    stringResource(R.string.storage_steam_app, l.appId), stringResource(R.string.storage_leftover_unsure_detail), l.bytes, i == unsure.lastIndex,
                    pill = { Pill(stringResource(R.string.storage_tag_saves), AttentionAmber) },
                    action = { SecondaryButton(stringResource(R.string.storage_remove), compact = true, enabled = !busy) { confirm = l } },
                )
            }
        }
    }
    if (report.otherPrefixes > 0) {
        SectionTitle(stringResource(R.string.storage_data_other), null)
        ListCard { ItemRow(stringResource(R.string.storage_data_other_title), stringResource(R.string.storage_data_other_detail), report.otherPrefixes, true) }
    }
    confirm?.let { l ->
        ConfirmDialog(
            stringResource(R.string.storage_remove_title, stringResource(R.string.storage_steam_app, l.appId)),
            stringResource(R.string.storage_leftover_confirm, gb(l.bytes)), stringResource(R.string.storage_remove),
            onConfirm = { StorageState.removeLeftover(ctx, l) }, onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun ComponentsView(report: StorageReport, sessionRunning: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val busy = sessionRunning || StorageState.cleaning
    var confirm by remember { mutableStateOf<ComponentUsage?>(null) }
    val unused = report.unusedComponents
    SubHeader(stringResource(R.string.storage_components),
        stringResource(R.string.storage_tools_detail, gb(report.components.sumOf { it.bytes }), gb(unused.sumOf { it.bytes })), onBack) {
        if (unused.isNotEmpty()) PrimaryButton(stringResource(R.string.storage_remove_unused, gb(unused.sumOf { it.bytes })), compact = true, enabled = !busy) {
            StorageState.clean(ctx, setOf(CleanItem.UNUSED_COMPONENTS)) { }
        }
    }
    ActionStatus()
    if (sessionRunning) { Note(stringResource(R.string.storage_session_running)); Spacer(Modifier.height(4.dp)) }
    val packages = report.components.filter { it.packageFile != null }
    val originals = report.components.filter { it.packageFile == null }
    SectionTitle(stringResource(R.string.storage_components_packages), null)
    ListCard {
        if (packages.isEmpty()) Text(stringResource(R.string.storage_components_none), fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        packages.forEachIndexed { i, c ->
            ItemRow(componentLabel(c), stringResource(if (c.inUse) R.string.storage_component_in_use else R.string.storage_component_unused), c.bytes, i == packages.lastIndex,
                pill = { Pill(stringResource(if (c.inUse) R.string.storage_tag_in_use else R.string.storage_tag_not_used), if (c.inUse) pal.signal else AttentionAmber) },
                action = { if (!c.inUse) SecondaryButton(stringResource(R.string.storage_remove), compact = true, enabled = !busy) { confirm = c } })
        }
    }
    if (originals.isNotEmpty()) {
        SectionTitle(stringResource(R.string.storage_components_originals), null)
        ListCard {
            originals.forEachIndexed { i, c ->
                ItemRow(componentLabel(c), c.version + " · " + stringResource(if (c.inUse) R.string.storage_original_kept else R.string.storage_original_old), c.bytes, i == originals.lastIndex,
                    pill = { Pill(stringResource(if (c.inUse) R.string.storage_tag_required else R.string.storage_tag_not_used), if (c.inUse) colors.onSurfaceVariant else AttentionAmber) },
                    action = { if (!c.inUse) SecondaryButton(stringResource(R.string.storage_remove), compact = true, enabled = !busy) { confirm = c } })
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Note(stringResource(R.string.storage_components_note))
    confirm?.let { c ->
        ConfirmDialog(stringResource(R.string.storage_remove_title, componentLabel(c)),
            stringResource(if (c.packageFile != null) R.string.storage_component_confirm else R.string.storage_original_confirm, gb(c.bytes)),
            stringResource(R.string.storage_remove), onConfirm = { StorageState.removeComponent(ctx, c) }, onDismiss = { confirm = null })
    }
}

@Composable
private fun DesktopView(report: StorageReport, sessionRunning: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val busy = sessionRunning || StorageState.cleaning
    var confirm by remember { mutableStateOf<com.droiddeck.launcher.storage.FlatpakUsage?>(null) }
    var confirmPrune by remember { mutableStateOf(false) }
    SubHeader(stringResource(R.string.storage_desktop), stringResource(R.string.storage_desktop_detail, gb(report.flatpak)), onBack) {
        PrimaryButton(stringResource(R.string.storage_desktop_prune), compact = true, enabled = !busy && report.flatpakRuntimes + report.flatpakCache >= ONE_MB) { confirmPrune = true }
    }
    ActionStatus()
    if (sessionRunning) { Note(stringResource(R.string.storage_session_running)); Spacer(Modifier.height(4.dp)) }
    SectionTitle(stringResource(R.string.storage_desktop_apps_title), null)
    ListCard {
        if (report.flatpakApps.isEmpty()) Text(stringResource(R.string.storage_desktop_none), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        report.flatpakApps.forEachIndexed { i, app ->
            ItemRow(app.name, app.id, app.bytes, i == report.flatpakApps.lastIndex,
                action = { SecondaryButton(stringResource(R.string.storage_uninstall), compact = true, enabled = !busy) { confirm = app } })
        }
    }
    SectionTitle(stringResource(R.string.storage_desktop_shared_title), null)
    ListCard {
        ItemRow(stringResource(R.string.storage_desktop_runtimes), stringResource(R.string.storage_desktop_runtimes_detail), report.flatpakRuntimes, false)
        ItemRow(stringResource(R.string.storage_desktop_cache), stringResource(R.string.storage_desktop_cache_detail), report.flatpakCache, true)
    }
    confirm?.let { app ->
        ConfirmDialog(stringResource(R.string.storage_uninstall_title, app.name), stringResource(R.string.storage_flatpak_uninstall_body, gb(app.bytes)),
            stringResource(R.string.storage_uninstall), onConfirm = { StorageState.uninstallFlatpak(ctx, app.id) }, onDismiss = { confirm = null })
    }
    if (confirmPrune) ConfirmDialog(stringResource(R.string.storage_desktop_prune), stringResource(R.string.storage_desktop_prune_body),
        stringResource(R.string.storage_desktop_prune), danger = false, onConfirm = { StorageState.pruneFlatpak(ctx) }, onDismiss = { confirmPrune = false })
}
