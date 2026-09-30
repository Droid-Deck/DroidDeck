package com.droiddeck.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.session.ComponentsManager.CatalogItem
import com.droiddeck.launcher.session.ComponentsManager.Snapshot
import java.text.DateFormat
import java.util.Date

/** One installed row: a Proton's original bundle (one per Proton build) or a stored package. */
private class InstalledItem(
    val name: String, val detail: String, val tag: String,
    val selected: Boolean, val removable: Boolean,
    /** Stored package file, or null for an original. */
    val file: String?,
    /** The Proton build an original belongs to. */
    val protonVersion: String?,
)

private val GOLD = Color(0xFFF2C66D)
private val ROW_SHAPE = RoundedCornerShape(10.dp)

/**
 * FEX, DXVK and VKD3D-Proton per Proton, as a full page opened from the rail's "Components" entry.
 * A title row with the Nightlies refresh, then the Proton and the component (LB / RB turn it), what
 * is in use, and the installed packages beside the ones on the Nightlies - stacked when the page is
 * narrow. Tapping an installed row swaps it in; the refresh button is the only thing that goes online.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ComponentsPage(
    snapshot: Snapshot?,
    catalog: List<CatalogItem>,
    catalogAt: Long,
    protonId: String?,
    comp: String,
    checking: Boolean,
    busy: String?,
    downloads: Map<String, Int>,
    onProton: (String) -> Unit,
    onComp: (String) -> Unit,
    onSwap: (String) -> Unit,
    onRestore: (String) -> Unit,
    onCancelQueued: () -> Unit,
    onDeletePackage: (String) -> Unit,
    onDeleteOriginal: (String) -> Unit,
    onDownload: (CatalogItem) -> Unit,
    onRefresh: () -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
    requestInitialFocus: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val narrow = LocalNarrowPane.current
    BackHandler(onBack = onBack)
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var confirmTitle by remember { mutableStateOf("") }
    var about by remember { mutableStateOf(false) }
    var protonMenu by remember { mutableStateOf(false) }
    fun ask(title: String, body: String, action: () -> Unit) { confirmTitle = title; confirm = body to action }

    val views = snapshot?.protons ?: emptyList()
    val view = views.firstOrNull { it.proton.id == protonId } ?: views.firstOrNull()
    val label = ComponentsManager.LABEL[comp] ?: comp
    val checked = if (catalogAt > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(catalogAt * 1000)) else "never"
    val nightlies = if (busy != null) "$busy…" else "Nightlies checked $checked"

    Column(Modifier.fillMaxSize().padding(horizontal = if (narrow) 16.dp else 22.dp, vertical = if (narrow) 12.dp else 18.dp)) {
        // ---- title, and the one thing that goes online -----------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                "Components", fontSize = if (narrow) 22.sp else 26.sp, fontWeight = FontWeight.Bold, color = colors.onBackground,
                maxLines = 1, modifier = Modifier.weight(1f),
            )
            if (!narrow) Text(nightlies, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ToolIcon(Icons.Outlined.Info, "About Components") { about = true }
            ToolIcon(Icons.Outlined.Refresh, "Check the Nightlies for new packages", busy = checking, enabled = !checking, onClick = onRefresh)
        }
        if (narrow) Text(nightlies, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)

        // ---- which Proton, which component ------------------------------------------------------
        val comps = ComponentsManager.COMPONENTS
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            if (view != null) Box {
                ValueChip(view.proton.name, protonMenu, modifier = Modifier.widthIn(max = 300.dp).heightIn(min = 44.dp)) { protonMenu = !protonMenu }
                AnchoredMenu(protonMenu, onDismiss = { protonMenu = false }, title = "Proton",
                    note = "Valve's own Proton is replaced when Steam updates it; its originals are kept per build.") { first ->
                    views.forEachIndexed { i, v ->
                        val swaps = v.components.values.count { it.activeFile != null }
                        MenuItem(
                            v.proton.name, checked = v.proton.id == view.proton.id,
                            detail = v.proton.version + (if (swaps > 0) " · $swaps swapped" else " · all original") + (if (v.inUseByGame) " · game running" else ""),
                            focusRequester = if (i == 0) first else null,
                        ) { onProton(v.proton.id); protonMenu = false }
                    }
                }
            }
            TabStrip(comps.map { ComponentsManager.LABEL.getValue(it) }, comps.indexOf(comp).coerceAtLeast(0), { onComp(comps[it]) })
        }

        // ---- what is in use -----------------------------------------------------------------------
        if (view != null) {
            val st = view.components.getValue(comp)
            val fixedAt = if (view.reappliedAt > 0) " · re-applied at launch " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(view.reappliedAt * 1000)) else ""
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(colors.surface)
                    .border(1.dp, pal.line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp).heightIn(min = 28.dp),
            ) {
                Box(Modifier.size(8.dp).background(if (st.queued != null) Color(0xFFFFB86B) else pal.good, CircleShape))
                Text(
                    (if (st.queued != null) "Next: ${st.queued} · after the game closes" else "$label in use: ${st.inUse}") + fixedAt,
                    fontSize = 14.sp, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (st.queued != null) FocusText("Cancel", pal.signal, onClick = onCancelQueued)
                else if (!narrow) Text(
                    if (view.inUseByGame) "Waits until the game closes" else "Changes apply the next time a game starts",
                    fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1,
                )
            }
        }

        // ---- the lists ------------------------------------------------------------------------------
        when {
            snapshot == null -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Text("Reading the Protons…", fontSize = 14.sp, color = colors.onSurfaceVariant)
            }
            view == null -> Text(
                "No Proton is installed in the Linux runtime yet. Start the Steam client once so it downloads its ARM64 Proton, or add GE-Proton / CachyOS from Setup.",
                fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp),
            )
            else -> {
                val p = view.proton
                val st = view.components.getValue(comp)
                val build = ComponentsManager.safeName(p.version)
                val originals = view.originals.filter { it.comp == comp }
                val stored = snapshot.packages.filter { it.comp == comp }
                val activeIsOriginal = st.activeFile == null
                val rows = originals.map { o ->
                    val isCurrent = o.protonVersion == build
                    InstalledItem(
                        "Original · ${o.protonVersion}",
                        o.label.substringAfterLast(" · ", "").ifEmpty { o.label } + if (isCurrent) " · this build" else " · earlier build",
                        "ORIGINAL", activeIsOriginal && isCurrent, !isCurrent, null, o.protonVersion,
                    )
                } + stored.map { s ->
                    InstalledItem(s.version, String.format("%.1f MB", s.size / 1048576.0), "STORED", s.file == st.activeFile, true, s.file, null)
                }
                val storedNames = snapshot.packages.map { it.file }.toSet()
                val available = catalog.filter { it.comp == comp && ComponentsManager.safeName(it.file) !in storedNames }
                val first = remember { FocusRequester() }
                LaunchedEffect(p.id, comp, requestInitialFocus) {
                    if (requestInitialFocus) runCatching { first.requestFocus() }
                }

                val installed: @Composable () -> Unit = {
                    SettingsGroup("Installed · ${rows.size}") {
                        if (rows.isEmpty()) Text(
                            "Nothing stored for $label yet. This Proton's own files are saved as its original when the page opens.",
                            fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                        rows.forEachIndexed { i, item ->
                            InstalledLine(
                                item, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                                onSelect = {
                                    if (item.selected) return@InstalledLine
                                    val waits = if (view.inUseByGame) " A game is running on this Proton, so it waits until the game closes." else " It applies the next time a game starts."
                                    if (item.file != null) ask("Swap $label?", "Put ${item.name} into ${p.name}? Its shipped files stay saved as an original bundle.$waits") { onSwap(item.file) }
                                    else ask("Restore $label?", "Put the ${item.protonVersion} original back into ${p.name}?$waits") { onRestore(item.protonVersion!!) }
                                },
                                onDelete = {
                                    if (item.file != null) ask("Delete ${item.name}?", "Its package file is removed from the app. You can download it again any time.") { onDeletePackage(item.file) }
                                    else ask("Delete this original?", "The ${item.protonVersion} original of $label belongs to an earlier build of ${p.name}. The installed build's original is always kept.") { onDeleteOriginal(item.protonVersion!!) }
                                },
                            )
                        }
                    }
                }
                val nightly: @Composable () -> Unit = {
                    SettingsGroup("On the Nightlies · ${available.size}") {
                        if (available.isEmpty()) Text(
                            if (catalogAt == 0L) "Refresh to list the packages on the Nightlies." else "Nothing new since the last check.",
                            fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                        available.forEach { d -> AvailableLine(d, downloads[d.file], enabled = busy == null) { onDownload(d) } }
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                    if (narrow) { installed(); nightly() }
                    else Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) { installed() }
                        Column(Modifier.weight(1f)) { nightly() }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp)) {
                        SmallButton("Import .wcp", onClick = onImport)
                        if (!activeIsOriginal && originals.any { it.protonVersion == build }) FocusText("Restore this Proton's original", pal.signal) {
                            ask("Restore $label?", "Put ${p.name}'s original $label back?") { onRestore(build) }
                        }
                    }
                }
            }
        }
    }

    if (about) AlertDialog(
        onDismissRequest = { about = false },
        title = { Text("Components") },
        text = {
            Text(
                "FEX, DXVK and VKD3D-Proton for each Proton the Steam client runs games with.\n\n" +
                    "Tap an installed row to swap it in; it applies the next time a game starts. If a game is running on that Proton, the swap waits until it closes.\n\n" +
                    "Your choices are also checked right before every game launch: if anything changed a Proton's files (Steam's start-up tests, for one), they are put back first, and the page says \"re-applied at launch\".\n\n" +
                    "Each Proton build's own files are kept as its original, so a Steam update never loses them. Packages come from the Nightlies \"-Linux\" releases (refresh) or Import .wcp.",
                fontSize = 14.sp,
            )
        },
        confirmButton = { FocusText("OK", pal.signal) { about = false } },
    )
    confirm?.let { (body, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(confirmTitle) },
            text = { Text(body, fontSize = 14.sp) },
            // Opens on Cancel, so a stray A press on a controller never swaps or deletes anything.
            confirmButton = {
                val del = confirmTitle.startsWith("Delete")
                FocusText(if (del) "Delete" else if (confirmTitle.startsWith("Restore")) "Restore" else "Swap", if (del) colors.error else pal.signal) { confirm = null; action() }
            },
            dismissButton = {
                val cancelFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
                FocusText("Cancel", colors.onBackground, modifier = Modifier.focusRequester(cancelFocus)) { confirm = null }
            },
        )
    }
}

@Composable
internal fun ToolIcon(icon: ImageVector, description: String, busy: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
            .background(if (hot) pal.signal.copy(alpha = 0.16f) else Color.Transparent)
            .glideBorder(hot, RoundedCornerShape(12.dp), pal.signal, pal.line2)
            .hoverable(src).clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick),
    ) {
        if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        else Icon(icon, contentDescription = description, tint = colors.onBackground, modifier = Modifier.size(20.dp))
    }
}

/**
 * A small button. Resting: grey outline (blue text when [accent]). Focused or hovered: a solid blue
 * fill with an outline in the text colour - unmistakable in a column of identical buttons.
 */
@Composable
private fun SmallButton(text: String, enabled: Boolean = true, accent: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = (src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value) && enabled
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
            .background(if (hot) pal.signal else colors.surfaceVariant)
            .glideBorder(hot, RoundedCornerShape(10.dp), colors.onBackground, pal.line2, restWidth = 2.dp)
            .hoverable(src).clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            color = if (!enabled) colors.onSurfaceVariant else if (hot) pal.onSignal else if (accent) pal.signal else colors.onBackground,
        )
    }
}

@Composable
private fun Tag(text: String) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val (fg, bg) = when (text) {
        "ORIGINAL" -> GOLD to Color(0x22F2C66D)
        "IN USE" -> pal.onSignal to pal.signal
        "NEW" -> pal.good to Color(0x224CD37F)
        else -> colors.onSurfaceVariant to Color.White.copy(alpha = 0.07f)
    }
    Text(
        text.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

@Composable
private fun InstalledLine(item: InstalledItem, modifier: Modifier = Modifier, onSelect: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else if (item.selected) pal.signal.copy(alpha = 0.07f) else Color.Transparent),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).then(modifier)
                .glideBorder(hot, ROW_SHAPE, pal.signal)
                .hoverable(src).clickable(interactionSource = src, indication = null, onClick = onSelect)
                .controllerConfirm(onClick = onSelect)
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp).border(2.dp, if (item.selected) pal.signal else colors.onSurfaceVariant, CircleShape)) {
                if (item.selected) Box(Modifier.size(9.dp).background(pal.signal, CircleShape))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        item.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (item.selected) pal.signal else colors.onBackground, modifier = Modifier.weight(1f, fill = false),
                    )
                    Tag(item.tag)
                }
                Text(item.detail, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            if (item.selected) Tag("IN USE")
        }
        // The trash column is always there (empty when the row can't be deleted) so rows line up.
        if (!item.removable) Spacer(Modifier.padding(end = 6.dp).size(40.dp))
        else {
            val delSrc = remember { MutableInteractionSource() }
            val delHot = delSrc.collectIsFocusedAsState().value || delSrc.collectIsHoveredAsState().value
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(end = 6.dp).size(40.dp).clip(ROW_SHAPE)
                    .background(if (delHot) colors.error.copy(alpha = 0.14f) else Color.Transparent)
                    .glideBorder(delHot, ROW_SHAPE, colors.error)
                    .hoverable(delSrc).clickable(interactionSource = delSrc, indication = null, onClick = onDelete)
                    .controllerConfirm(onClick = onDelete),
            ) { Icon(Icons.Outlined.Delete, contentDescription = "Delete ${item.name}", tint = if (delHot) colors.error else colors.onSurfaceVariant, modifier = Modifier.size(19.dp)) }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
private fun AvailableLine(d: CatalogItem, progress: Int?, enabled: Boolean, onDownload: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(d.file.removeSuffix(".wcp"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${d.release} · " + String.format("%.1f MB", d.size / 1048576.0), fontSize = 13.sp, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (progress == null) SmallButton("Download", enabled = enabled, accent = true, onClick = onDownload)
        else Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(96.dp)) {
            Text(if (progress < 0) "…" else "$progress%", fontSize = 13.sp, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            if (progress < 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}
