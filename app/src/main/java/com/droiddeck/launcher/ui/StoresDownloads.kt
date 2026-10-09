package com.droiddeck.launcher.ui

import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadStage
import com.droiddeck.launcher.stores.download.DownloadState
import com.droiddeck.launcher.stores.download.StoreDownloadTier
import com.droiddeck.launcher.stores.formatBytes
import com.droiddeck.launcher.stores.formatSpeed

// The Downloads chip: one queue for the three stores, its settings and the engine log.

@Composable
internal fun StoresDownloadsPane(s: FrontEndState, a: FrontEndActions) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 700.dp
        val list: @Composable () -> Unit = { DownloadList(s, a) }
        val side: @Composable () -> Unit = { DownloadSettings(s, a) }
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.weight(3f)) { list() }
            Box(Modifier.weight(2f)) { side() }
        } else Column { list(); side() }
    }
}

@Composable
private fun DownloadList(s: FrontEndState, a: FrontEndActions) {
    val colors = MaterialTheme.colorScheme
    val entries = StoresState.downloads
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.stores_downloads_empty), fontSize = 13.sp, color = colors.onSurfaceVariant)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEachIndexed { i, d -> key(d.key) { Rise(1 + i.coerceAtMost(5)) { DownloadCard(d, s, a) } } }
    }
}

@Composable
private fun DownloadCard(d: DownloadEntry, s: FrontEndState, a: FrontEndActions) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val narrow = LocalNarrowPane.current
    val ink = Color.White
    val dim = Color.White.copy(alpha = 0.72f)
    // The game's wide art is the row: cropped to it, a dark wash from the left so the copy reads
    // in white there, the art clear on the right.
    Box(Modifier.fillMaxWidth().heightIn(min = 88.dp).clip(Shape12).background(Color(0xFF101318)).border(1.dp, pal.line, Shape12)) {
        if (!d.cover.isNullOrEmpty()) AsyncImage(model = d.cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        else Spacer(Modifier.matchParentSize().background(artBrush(hueOf(d.name))))
        Spacer(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.5f to Color.Black.copy(alpha = 0.7f), 1f to Color.Black.copy(alpha = 0.15f))))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(d.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    SourceChip(d.store.id, small = true)
                    val where = listOfNotNull(d.location.takeIf { it.isNotBlank() }, d.diskBytes.takeIf { it > 0 }?.let { formatBytes(it) }).joinToString(" · ")
                    if (where.isNotEmpty()) Text(where, fontSize = 11.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StageBar(d)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val meta = when (d.state) {
                        DownloadState.INSTALLED -> stringResource(R.string.stores_installed_chip)
                        DownloadState.CANCELLED -> stringResource(R.string.stores_dl_cancelled)
                        // The reason is in the engine log; the row only says what is kept for Resume.
                        DownloadState.FAILED -> if (d.bytesDone > 0) stringResource(R.string.stores_dl_failed_kept, formatBytes(d.bytesDone)) else stringResource(R.string.stores_dl_failed)
                        DownloadState.PAUSED -> stringResource(R.string.stores_dl_paused)
                        DownloadState.QUEUED -> if (d.queuePosition > 0) stringResource(R.string.stores_dl_queued_at, d.queuePosition) else stringResource(R.string.stores_dl_queued)
                        DownloadState.RUNNING -> {
                            val head = downloadLabel(d)
                            when {
                                d.stage == DownloadStage.DOWNLOAD && d.bytesTotal > 0 -> stringResource(R.string.stores_dl_with, head, stringResource(R.string.stores_dl_amount, formatBytes(d.bytesDone), formatBytes(d.bytesTotal)))
                                d.stageItemsTotal > 0 -> stringResource(R.string.stores_dl_with, head, stringResource(R.string.stores_dl_items, d.stageItems, d.stageItemsTotal))
                                else -> head
                            }
                        }
                    }
                    Text(meta, fontSize = 12.sp, color = dim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (d.state == DownloadState.RUNNING && (d.stage == DownloadStage.DOWNLOAD || d.stage == DownloadStage.INSTALL) && d.speedBps > 0) {
                        Text(formatSpeed(d.speedBps), fontSize = 12.sp, color = dim, maxLines = 1)
                        if (d.etaSeconds >= 0) Text(eta(d.etaSeconds), fontSize = 12.sp, color = dim, maxLines = 1)
                    }
                }
            }
            // The row's actions on the right, over the art.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
                when (d.state) {
                    DownloadState.RUNNING, DownloadState.QUEUED -> {
                        SecondaryButton(stringResource(R.string.stores_dl_pause), compact = true) { DownloadQueue.pause(d.key) }
                        ConfirmButton(stringResource(R.string.stores_dl_cancel), stringResource(R.string.stores_dl_cancel_confirm), compact = true) { DownloadQueue.cancel(ctx, d.key) }
                    }
                    DownloadState.PAUSED -> {
                        PrimaryButton(stringResource(R.string.stores_dl_resume), compact = true) { DownloadQueue.resume(ctx, d.key) }
                        ConfirmButton(stringResource(R.string.stores_dl_cancel), stringResource(R.string.stores_dl_cancel_confirm), compact = true) { DownloadQueue.cancel(ctx, d.key) }
                    }
                    DownloadState.FAILED -> {
                        PrimaryButton(stringResource(R.string.stores_dl_resume), compact = true) { DownloadQueue.retry(ctx, d.key) }
                        ConfirmButton(stringResource(R.string.stores_dl_clear), stringResource(R.string.stores_dl_cancel_confirm), compact = true) { DownloadQueue.clear(ctx, d.key) }
                    }
                    DownloadState.INSTALLED -> {
                        PrimaryButton(stringResource(R.string.stores_play), compact = true, icon = Icons.Filled.PlayArrow) { launchStoreGame(ctx, d.store, d.id, s, a) }
                        SecondaryButton(stringResource(R.string.stores_dl_clear), compact = true) { DownloadQueue.dismiss(d.key) }
                    }
                    DownloadState.CANCELLED -> SecondaryButton(stringResource(R.string.stores_dl_clear), compact = true) { DownloadQueue.dismiss(d.key) }
                }
            }
        }
    }
}

/**
 * One segment per stage - Manifest, Download, Verify, Install - equal widths: a passed stage full, the
 * active one filling with its own progress (a sliver while it has nothing to count), the rest empty.
 */
@Composable
private fun StageBar(d: DownloadEntry) {
    val stages = listOf(DownloadStage.MANIFEST, DownloadStage.DOWNLOAD, DownloadStage.VERIFY, DownloadStage.INSTALL)
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
        for (st in stages) {
            val active = d.state != DownloadState.INSTALLED && d.stage == st
            val fill = when {
                active -> d.stageFraction.let { if (it < 0f) 0.04f else it }
                d.passed(st) || d.stage == DownloadStage.DONE -> 1f
                else -> 0f
            }
            Box(Modifier.weight(1f)) {
                ProgressBarThin(
                    fill, paused = active && d.state == DownloadState.PAUSED, verify = active && st == DownloadStage.VERIFY,
                    height = 6.dp, done = !active && fill >= 1f,
                )
            }
        }
    }
}

private fun eta(seconds: Long): String = when {
    seconds < 60 -> "<1 min"
    seconds < 3600 -> "~${seconds / 60} min"
    else -> "~${seconds / 3600} h ${(seconds % 3600) / 60} m"
}

/** The queue's settings beside it: speed tier, downloads at a time, and the engine log. */
@Composable
private fun DownloadSettings(s: FrontEndState, a: FrontEndActions) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    SectionTitle(stringResource(R.string.stores_download_manager), null)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().clip(Shape12).background(colors.surface).border(1.dp, pal.line, Shape12).padding(horizontal = 14.dp, vertical = 10.dp)) {
        DownloadControls(s, a)
    }
}

/**
 * The queue's two knobs on one line - the speed tier on the left, downloads at a time on the
 * right; the same control sits in the cog's popup. A narrow page puts them on two tight lines.
 */
@Composable
internal fun DownloadControls(s: FrontEndState, a: FrontEndActions) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var parallel by remember { mutableStateOf(DownloadQueue.parallel(ctx)) }
    val tier: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.setup_stores_speed), fontSize = 12.sp, color = colors.onSurfaceVariant)
            SegmentedTabs(StoreDownloadTier.ALL.map { it.id to stringResource(it.label) }, s.gameStoresSpeedTier) { a.onGameStoresSpeedTier(it) }
        }
    }
    val count: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.stores_parallel), fontSize = 12.sp, color = colors.onSurfaceVariant)
            SegmentedTabs(listOf(1 to "1", 2 to "2", 3 to "3"), parallel) { parallel = it; DownloadQueue.setParallel(ctx, it) }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 460.dp) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) { tier(); count() }
        else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { tier(); count() }
    }
}

/**
 * The chip row's cog: the section's own settings in a small card - which tab a store opens on
 * and the speed tier. Setup › Stores repeats them under its gate. Every install is added to Steam;
 * that is not a choice.
 */
@Composable
internal fun StoresSettingsDialog(s: FrontEndState, a: FrontEndActions, onDismiss: () -> Unit) {
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    AppDialog(shown, close, "storesSettings", wide = false, maxWidth = 440.dp) {
        DialogHeader(stringResource(R.string.stores_settings_eyebrow), stringResource(R.string.stores_title))
        Rise(1) {
            Column {
                SettingsRow(stringResource(R.string.setup_stores_open_on), null) {
                    SegmentedTabs(
                        listOf(SessionPrefs.STORES_OPEN_LIBRARY to stringResource(R.string.stores_tab_library), SessionPrefs.STORES_OPEN_STORE to stringResource(R.string.stores_tab_store)),
                        s.storesOpenTab,
                    ) { a.onStoresOpenTab(it) }
                }
                // The queue's two knobs as one compact row, the same control the Downloads page has.
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { DownloadControls(s, a) }
            }
        }
        Rise(2) { Actions { SecondaryButton(stringResource(R.string.common_ok), onClick = close) } }
    }
}
