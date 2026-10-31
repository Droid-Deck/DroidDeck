package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.SteamDownload
import com.droiddeck.launcher.session.SteamDownloads
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadState
import com.droiddeck.launcher.stores.formatBytes
import com.droiddeck.launcher.stores.formatSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Bytes per second between two reads of the same download; 0 when it did not move or restarted. */
internal fun downloadRate(previousDone: Long, previousAt: Long, done: Long, at: Long): Long =
    if (previousDone < 0L || done <= previousDone || at <= previousAt) 0L else (done - previousDone) * 1000L / (at - previousAt)

private const val STEAM_POLL_MS = 2_000L

private class SteamRow(val download: SteamDownload, val rate: Long)

@Composable
fun BottomDownloadsPage() {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var steam by remember { mutableStateOf<List<SteamRow>>(emptyList()) }
    LaunchedEffect(Unit) {
        var last = emptyMap<String, Pair<Long, Long>>()
        while (true) {
            val now = System.currentTimeMillis()
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    SteamDownloads.read(LinuxRuntime.rootDir(context), SessionState.steamLibraryMounts, SessionState.steamClientArch)
                }.getOrDefault(emptyList())
            }
            steam = read.map { d ->
                val before = last[d.appId]
                SteamRow(d, if (before == null) 0L else downloadRate(before.first, before.second, d.done, now))
            }
            last = read.associate { it.appId to (it.done to now) }
            delay(STEAM_POLL_MS)
        }
    }
    val store = StoresState.downloads.filter { it.isActive }
    if (steam.isEmpty() && store.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.bottom_downloads_none), fontSize = 15.sp, color = colors.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(steam, key = { "steam:${it.download.appId}:${it.download.shader}" }) { row ->
            val d = row.download
            val detail = when {
                d.shader -> stringResource(R.string.bottom_downloads_shader)
                d.paused -> stringResource(R.string.bottom_downloads_paused)
                d.total <= 0L -> stringResource(R.string.bottom_downloads_queued)
                else -> listOf("${formatBytes(d.done)} / ${formatBytes(d.total)}", formatSpeed(row.rate)).filter { it.isNotEmpty() }.joinToString(" · ")
            }
            DownloadRow(d.name, stringResource(R.string.bottom_downloads_steam), detail,
                if (d.total > 0L) (d.done.toFloat() / d.total).coerceIn(0f, 1f) else null)
        }
        items(store, key = { "store:${it.key}" }) { entry ->
            val e = StoresState.download(entry.key) ?: entry
            val detail = when (e.state) {
                DownloadState.PAUSED -> stringResource(R.string.bottom_downloads_paused)
                DownloadState.QUEUED -> stringResource(R.string.bottom_downloads_queued)
                else -> listOf(
                    if (e.bytesTotal > 0L) "${formatBytes(e.bytesDone)} / ${formatBytes(e.bytesTotal)}" else "",
                    formatSpeed(e.speedBps),
                ).filter { it.isNotEmpty() }.joinToString(" · ")
            }
            DownloadRow(e.name, e.store.label, detail, e.stageFraction.takeIf { it >= 0f })
        }
    }
}

@Composable
private fun DownloadRow(name: String, source: String, detail: String, fraction: Float?) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(pal.surface).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(source, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        }
        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = pal.signal)
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = pal.signal)
        if (detail.isNotEmpty()) Text(detail, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
