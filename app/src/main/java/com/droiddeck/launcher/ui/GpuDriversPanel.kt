package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import com.droiddeck.launcher.gpu.DeviceInfo
import com.droiddeck.launcher.session.SessionPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
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
import com.droiddeck.launcher.ui.GlideDropdownMenu as DropdownMenu
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

class PairRow(
    val key: String,
    val name: String,
    val version: String,
    val detail: String,
    val recommended: Boolean,
    val suits: Boolean,
    val complete: Boolean,
    val installed: Boolean,
    val active: Boolean,
)

const val GPU_MODE_AUTO = "auto"
const val GPU_MODE_PAIR = "pair"
const val GPU_MODE_MIX = "mix"

class DriverSlot(
    val name: String = "",
    val source: String = "",
    val pending: Boolean = false,
    val status: String = "",
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
    val busy: String? = null,
    val percent: Int = -1,
    val autoStatus: String = "",
    val releaseStatus: String = "",
    val checking: Boolean = false,
    val linuxRows: List<DriverRow> = emptyList(),
    val linuxSelected: String = "",
    val androidRows: List<DriverRow> = emptyList(),
    val androidSelected: String = "",
    val linuxDownloads: List<DownloadRow> = emptyList(),
    val androidDownloads: List<DownloadRow> = emptyList(),
    val canRestoreBundled: Boolean = false,
    val activeBundle: String? = null,
    val mode: String = GPU_MODE_AUTO,
    val display: DriverSlot = DriverSlot(),
    val runtime: DriverSlot = DriverSlot(),
    val canRestart: Boolean = true,
    val checkedAt: Long = 0L,
    val tpUbwcMode: String? = null,
    val tpUbwcRestartRequired: Boolean = false,
) {
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
    val onImportZip: () -> Unit = {},
    val onRestoreBundled: () -> Unit = {},
    val onMode: (String) -> Unit = {},
    val onRestart: () -> Unit = {},
    val onTpUbwcMode: (String) -> Unit = {},
)

@Composable
internal fun DeviceDetails(first: FocusRequester) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var details by remember { mutableStateOf<List<DeviceInfo.Section>?>(null) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { details = withContext(Dispatchers.IO) { DeviceInfo.collect(context) } }
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
    ) {
        val sections = details
        if (sections == null) {
            Text(stringResource(R.string.device_info_loading), fontSize = 13.sp, color = colors.onSurfaceVariant)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (copied) R.string.device_info_copied else R.string.device_info_copy_hint),
                    fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                SecondaryButton(stringResource(R.string.device_info_copy), compact = true, modifier = Modifier.focusRequester(first)) {
                    clipboard.setText(AnnotatedString(DeviceInfo.text(sections)))
                    copied = true
                }
            }
            for (section in sections) DeviceInfoSection(section)
        }
    }
}

@Composable
private fun DeviceInfoSection(section: DeviceInfo.Section) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.fillMaxWidth().clip(Shape12)
            .glideBorder(hot, Shape12, pal.signal)
            .focusable(interactionSource = src).padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(section.title.uppercase(), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = colors.onSurfaceVariant)
        for ((label, value) in section.rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.width(150.dp))
                Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.onBackground, modifier = Modifier.weight(1f))
            }
        }
    }
}

private const val STALE_MS = 6 * 3_600_000L

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun GpuDriversBody(
    gpu: GpuDriversState,
    actions: GpuDriversActions,
    single: Boolean,
    first: FocusRequester,
    slotFocus: Map<String, FocusRequester>,
    onOpenSlot: (String, Rect) -> Unit,
) {
    LaunchedEffect(Unit) {
        if (!gpu.checking && gpu.busy == null && System.currentTimeMillis() - gpu.checkedAt > STALE_MS) actions.onRefresh()
    }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val placed = remember { arrayOfNulls<LayoutCoordinates>(2) }
    // Focusing the top mode shows as much of the in-use card above it as fits, without hiding the mode itself.
    val revealCard = {
        val (viewport, top) = placed
        if (viewport != null && top != null && viewport.isAttached && top.isAttached) scope.launch {
            val bottom = viewport.localPositionOf(top, Offset(0f, top.size.height.toFloat())).y + scroll.value
            scroll.animateScrollTo((bottom - viewport.size.height).toInt().coerceAtLeast(0))
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize().onGloballyPositioned { placed[0] = it }.verticalScroll(scroll).padding(bottom = 16.dp)
            .focusProperties { enter = { first } }.focusGroup(),
    ) {
        InUseCard(gpu, actions, first)
        PanelHeading(stringResource(R.string.gpu_mode_title))
        val modes = listOf(
            Triple(GPU_MODE_AUTO, R.string.common_auto, R.string.gpu_mode_auto_detail),
            Triple(GPU_MODE_PAIR, R.string.gpu_mode_pair, R.string.gpu_mode_pair_detail),
            Triple(GPU_MODE_MIX, R.string.gpu_mode_mix, R.string.gpu_mode_mix_detail),
        )
        val choice: @Composable (Int, Modifier) -> Unit = { i, mod ->
            val (id, title, detail) = modes[i]
            PanelRow(
                stringResource(title), stringResource(detail), gpu.mode == id,
                modifier = mod.then(if (gpu.mode == id) Modifier.focusRequester(first) else Modifier)
                    .then(if (i == 0) Modifier.onGloballyPositioned { placed[1] = it }.onFocusChanged { if (it.isFocused) revealCard() } else Modifier),
                onClick = { if (gpu.mode != id) actions.onMode(id) },
            )
        }
        if (single) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { modes.indices.forEach { choice(it, Modifier) } }
        else Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            modes.indices.forEach { choice(it, Modifier.weight(1f).fillMaxHeight()) }
        }
        when (gpu.mode) {
            GPU_MODE_AUTO -> AutoBody(gpu)
            GPU_MODE_PAIR -> PairBody(gpu, actions)
            else -> MixBody(single, actions, slotFocus, onOpenSlot)
        }
        gpu.tpUbwcMode?.let { selected ->
            var advanced by rememberSaveable { mutableStateOf(false) }
            SecondaryButton(stringResource(R.string.gpu_advanced) + if (advanced) " ▴" else " ▾", modifier = Modifier.fillMaxWidth()) { advanced = !advanced }
            AnimatedVisibility(advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChoiceRow(
                        rememberMenuHost(), "tp-ubwc", stringResource(R.string.gpu_ubwc_title),
                        stringResource(R.string.gpu_ubwc_hint),
                        listOf(
                            SessionPrefs.TP_UBWC_AUTO to stringResource(R.string.common_auto),
                            SessionPrefs.TP_UBWC_ON to stringResource(R.string.widgets_on),
                            SessionPrefs.TP_UBWC_OFF to stringResource(R.string.widgets_off),
                        ), selected, onPick = actions.onTpUbwcMode,
                    )
                    if (gpu.tpUbwcRestartRequired) {
                        Text(stringResource(R.string.gpu_ubwc_restart), fontSize = 13.sp, color = AttentionAmber)
                        if (gpu.canRestart) SecondaryButton(stringResource(R.string.gpu_restart_now), onClick = actions.onRestart)
                    }
                }
            }
        }
    }
}

@Composable
private fun InUseCard(gpu: GpuDriversState, actions: GpuDriversActions, first: FocusRequester) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().clip(shape).background(colors.surface).border(1.dp, pal.line, shape).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        SlotLine(Icons.Outlined.Monitor, stringResource(R.string.comp_display_driver), gpu.display)
        Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
        SlotLine(Icons.Outlined.SportsEsports, stringResource(R.string.comp_runtime_driver), gpu.runtime)
        if (gpu.busy != null) LinearProgressIndicator(
            progress = { (gpu.percent.coerceAtLeast(0)) / 100f }, modifier = Modifier.fillMaxWidth(),
        )
        if (gpu.display.pending) RestartBanner(gpu.display.name, gpu.canRestart, first, actions.onRestart)
    }
}

@Composable
private fun SlotLine(icon: ImageVector, label: String, slot: DriverSlot) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(colors.surfaceVariant)) {
            Icon(icon, contentDescription = null, tint = colors.onBackground, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, fontSize = 12.5.sp, color = colors.onSurfaceVariant)
                Pill(slot.source, pal.line2.copy(alpha = 0.6f), colors.onBackground)
            }
            Text(slot.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (slot.status.isNotEmpty()) Text(
                slot.status, fontSize = 12.5.sp, color = if (slot.pending) AttentionAmber else colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun RestartBanner(target: String, canRestart: Boolean, afterLater: FocusRequester, onRestart: () -> Unit) {
    var later by rememberSaveable(target) { mutableStateOf(false) }
    if (later) return
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().clip(shape).background(AttentionAmber.copy(alpha = 0.12f))
            .border(1.dp, AttentionAmber.copy(alpha = 0.45f), shape).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(Icons.Outlined.Refresh, contentDescription = null, tint = AttentionAmber, modifier = Modifier.size(20.dp))
        Text(
            stringResource(if (canRestart) R.string.gpu_restart_banner else R.string.gpu_restart_blocked),
            fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f),
        )
        FocusText(stringResource(R.string.gpu_restart_later), MaterialTheme.colorScheme.onBackground) {
            runCatching { afterLater.requestFocus() }
            later = true
        }
        if (canRestart) FocusText(stringResource(R.string.gpu_restart_now), AttentionAmber, onClick = onRestart)
    }
}

@Composable
private fun AutoBody(gpu: GpuDriversState) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelTitle(
            stringResource(if (gpu.activePair != null) R.string.gpu_auto_title else R.string.gpu_auto_builtin_title, gpu.gpuName),
            stringResource(R.string.gpu_auto_reason, gpu.gpuFamily),
        )
        gpu.activePair?.let { p -> PanelRow(p.name, "${p.version} · ${p.detail}", selected = true, onClick = null) }
        if (gpu.autoStatus.isNotEmpty()) Text(gpu.autoStatus, fontSize = 13.sp, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun PairBody(gpu: GpuDriversState, actions: GpuDriversActions) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var showAll by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelTitle(stringResource(R.string.gpu_pair_title), stringResource(R.string.gpu_pair_hint))
        val rows = gpu.pairs.filter { (it.installed || it.complete) && (it.suits || showAll) }
        if (rows.isEmpty()) Text(
            stringResource(if (gpu.checking) R.string.gpu_checking else R.string.gpu_none_listed),
            fontSize = 13.sp, color = colors.onSurfaceVariant,
        )
        for (p in rows) PanelRow(
            p.name, "${p.version} · ${p.detail}", selected = p.active,
            badge = if (p.recommended) stringResource(R.string.gpu_autos_pick) else null,
            trailing = when {
                gpu.busy == p.key -> "${gpu.percent.coerceAtLeast(0)}%"
                p.installed -> stringResource(R.string.gpu_installed)
                else -> stringResource(R.string.common_download)
            },
            enabled = gpu.busy == null,
            onClick = { if (!p.active) actions.onPair(p.key) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (gpu.pairs.any { !it.suits }) FocusText(stringResource(if (showAll) R.string.gpu_hide_other else R.string.gpu_show_other), pal.signal) { showAll = !showAll }
            FocusText(stringResource(R.string.comp_import_zip), pal.signal, onClick = actions.onImportZip)
        }
    }
}

@Composable
private fun MixBody(single: Boolean, actions: GpuDriversActions, slotFocus: Map<String, FocusRequester>, onOpenSlot: (String, Rect) -> Unit) {
    val pal = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelTitle(stringResource(R.string.gpu_mix_title), stringResource(R.string.gpu_mix_hint))
        val at = remember { mutableMapOf<String, Rect>() }
        val slotButton: @Composable (String, Int, Modifier) -> Unit = { slot, label, mod ->
            SecondaryButton(
                stringResource(label),
                modifier = mod.focusRequester(slotFocus.getValue(slot)).onGloballyPositioned { at[slot] = it.boundsInRoot() },
            ) { onOpenSlot(slot, at[slot] ?: Rect.Zero) }
        }
        val buttons: @Composable (Modifier) -> Unit = { mod ->
            slotButton(SLOT_DISPLAY, R.string.gpu_change_display, mod)
            slotButton(SLOT_RUNTIME, R.string.gpu_change_runtime, mod)
        }
        if (single) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons(Modifier.fillMaxWidth()) }
        else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { buttons(Modifier.weight(1f)) }
        FocusText(stringResource(R.string.comp_import_zip), pal.signal, onClick = actions.onImportZip)
    }
}

internal const val SLOT_DISPLAY = "panel"
internal const val SLOT_RUNTIME = "rt"

@Composable
private fun PanelHeading(text: String) {
    Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PanelTitle(title: String, hint: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 4.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        Text(hint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Pill(text: String, fill: Color, ink: Color) {
    if (text.isEmpty()) return
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = ink, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(fill).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun PanelRow(
    title: String,
    detail: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    badge: String? = null,
    trailing: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape)
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else if (selected) pal.signal.copy(alpha = 0.08f) else colors.surface)
            .glideBorder(hot, shape, pal.signal, if (selected) pal.signal.copy(alpha = 0.7f) else pal.line)
            .then(
                if (onClick != null) Modifier.hoverable(src)
                    .clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                else Modifier,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(20.dp).border(2.dp, if (selected) pal.signal else colors.onSurfaceVariant, CircleShape),
        ) { if (selected) Box(Modifier.size(10.dp).background(pal.signal, CircleShape)) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (selected) pal.signal else colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, fontSize = 12.5.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (badge != null) Pill(badge, pal.signal.copy(alpha = 0.16f), pal.signal)
        if (trailing != null) Text(trailing, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1)
    }
}
