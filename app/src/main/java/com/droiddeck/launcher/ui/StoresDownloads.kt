package com.droiddeck.launcher.ui

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
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(
        verticalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.fillMaxWidth().clip(Shape12).background(colors.surface).border(1.dp, pal.line, Shape12).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            // The game's art, small and fixed, so the row stays one line tall on a narrow page.
            CardArt(CatalogItem(d.store, d.id, d.name, d.cover), Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(6.dp)))
            Column(modifier = Modifier.weight(1f)) {
                Text(d.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
                    SourceChip(d.store.id, small = true)
                    if (d.location.isNotBlank()) Text(d.location, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!LocalNarrowPane.current) Stages(d)
        }
        if (LocalNarrowPane.current) Stages(d)
        ProgressBarThin(
            if (d.state == DownloadState.INSTALLED) 1f else d.fraction, paused = d.state == DownloadState.PAUSED,
            verify = d.stage == DownloadStage.VERIFY, height = 8.dp, done = d.state == DownloadState.INSTALLED,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            val meta = when (d.state) {
                DownloadState.INSTALLED -> stringResource(R.string.stores_installed_chip)
                DownloadState.CANCELLED -> stringResource(R.string.stores_dl_cancelled)
                DownloadState.FAILED -> d.error?.let { stringResource(R.string.stores_dl_failed_reason, it) } ?: stringResource(R.string.stores_dl_failed)
                DownloadState.PAUSED -> stringResource(R.string.stores_dl_paused)
                DownloadState.QUEUED -> if (d.queuePosition > 0) stringResource(R.string.stores_dl_queued_at, d.queuePosition) else stringResource(R.string.stores_dl_queued)
                DownloadState.RUNNING -> when {
                    d.stage == DownloadStage.DOWNLOAD && d.bytesTotal > 0 -> stringResource(R.string.stores_dl_bytes, formatBytes(d.bytesDone), formatBytes(d.bytesTotal), d.percent)
                    else -> stageLabel(d.stage)
                }
            }
            Text(meta, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (d.state == DownloadState.RUNNING && d.speedBps > 0) {
                Text(formatSpeed(d.speedBps), fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1)
                if (d.etaSeconds >= 0) Text(eta(d.etaSeconds), fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1)
            }
        }
        if (d.detail.isNotBlank() && d.state == DownloadState.RUNNING) Text(d.detail, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Actions {
            when (d.state) {
                DownloadState.RUNNING, DownloadState.QUEUED -> {
                    SecondaryButton(stringResource(R.string.stores_dl_pause), compact = true) { DownloadQueue.pause(d.key) }
                    SecondaryButton(stringResource(R.string.stores_dl_cancel), compact = true) { DownloadQueue.cancel(ctx, d.key) }
                }
                DownloadState.PAUSED -> {
                    PrimaryButton(stringResource(R.string.stores_dl_resume), compact = true) { DownloadQueue.resume(ctx, d.key) }
                    SecondaryButton(stringResource(R.string.stores_dl_cancel), compact = true) { DownloadQueue.cancel(ctx, d.key) }
                }
                DownloadState.FAILED -> {
                    PrimaryButton(stringResource(R.string.stores_dl_retry), compact = true) { DownloadQueue.retry(ctx, d.key) }
                    SecondaryButton(stringResource(R.string.stores_dl_clear), compact = true) { DownloadQueue.dismiss(d.key) }
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

/** Manifest → Download → Verify → Install → Done, the one under way lit, the ones behind it green. */
@Composable
private fun Stages(d: DownloadEntry) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (stage in DownloadStage.entries) {
            val passed = d.state == DownloadState.INSTALLED || stage.ordinal < d.stage.ordinal
            val on = d.state == DownloadState.RUNNING && stage == d.stage
            Text(
                stageLabel(stage), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                color = when { on -> pal.signal; passed -> pal.good; else -> colors.onSurfaceVariant },
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) pal.signal.copy(alpha = 0.18f) else colors.surfaceVariant).padding(horizontal = 6.dp, vertical = 2.dp),
            )
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
    SectionTitle(stringResource(R.string.stores_engine_log), null)
    Box(Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 220.dp).clip(Shape12).background(Color.Black).border(1.dp, pal.line, Shape12).padding(12.dp)) {
        val lines = StoresState.log
        Text(
            if (lines.isEmpty()) stringResource(R.string.stores_log_idle) else lines.asReversed().joinToString("\n"),
            fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF9BE27A),
        )
    }
    Text(stringResource(R.string.stores_downloads_footnote), fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
}

/**
 * The queue's two knobs on one line - the speed tier on the left, downloads at a time on the
 * right - with one caption under them; the same control sits in the cog's popup. A narrow page
 * puts the two controls on two tight lines.
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
    Text(stringResource(R.string.stores_download_controls_note), fontSize = 12.sp, color = colors.onSurfaceVariant)
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
    AppDialog(shown, close, "storesSettings", wide = false) {
        DialogHeader(stringResource(R.string.stores_settings_eyebrow), stringResource(R.string.stores_title))
        Rise(1) {
            Column {
                SettingsRow(stringResource(R.string.setup_stores_open_on), stringResource(R.string.setup_stores_open_on_hint)) {
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
