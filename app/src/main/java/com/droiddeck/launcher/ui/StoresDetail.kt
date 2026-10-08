package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreInstalls
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadState
import com.droiddeck.launcher.stores.formatBytes

// A store game's own page: hero, the one main action, how it is launched and where it lives.

@Composable
internal fun StoreGameDetail(store: Store, key: String, s: FrontEndState, a: FrontEndActions, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val narrow = LocalNarrowPane.current
    val id = key.substringAfter(':')
    val item = (StoresState.library[store].orEmpty() + StoresState.shelves[store]?.all.orEmpty()).firstOrNull { it.id == id }
    val installed = StoresState.installedGame(store, id)
    val download = StoresState.downloads.firstOrNull { it.store == store && it.id == id && it.isActive }
    // Removal asks twice: one stray press of A should not cost a download.
    var confirmRemove by remember(key) { mutableStateOf(false) }
    LaunchedEffect(confirmRemove) { if (confirmRemove) { kotlinx.coroutines.delay(4000); confirmRemove = false } }
    val title = item?.title ?: installed?.sidecar?.title ?: id
    Rise(0) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            BackLink(store.label, compact = true, onClick = onBack)
            SourceChip(store.id)
        }
    }
    Rise(1) {
        Box(Modifier.fillMaxWidth().heightIn(min = if (narrow) 150.dp else 180.dp).clip(Shape16).background(artBrush(hueOf(title)))) {
            val art = item?.imageUrl ?: installed?.sidecar?.hero ?: installed?.sidecar?.cover
            if (art != null) AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            Spacer(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to colors.background.copy(alpha = 0.94f), 0.5f to colors.background.copy(alpha = 0.55f), 1f to Color.Transparent)))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 22.dp, vertical = 18.dp)) {
                val state = when {
                    installed != null -> stringResource(R.string.stores_eyebrow_installed)
                    item?.owned == true -> stringResource(R.string.stores_eyebrow_library)
                    else -> stringResource(R.string.stores_eyebrow_store)
                }
                val size = (item?.sizeBytes ?: 0L).takeIf { it > 0 }?.let { " · " + formatBytes(it) } ?: ""
                Text((state + size).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = pal.signal)
                Text(title, fontSize = if (narrow) 24.sp else 30.sp, lineHeight = if (narrow) 27.sp else 33.sp, fontWeight = FontWeight.Black, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Actions {
                    when {
                        installed != null -> {
                            PrimaryButton(stringResource(R.string.games_launch), main = true, icon = Icons.Filled.PlayArrow, enabled = !s.busy) { launchStoreGame(ctx, store, id, s, a) }
                            val inSteam = installed.sidecar.addToSteam
                            SecondaryButton(if (inSteam) stringResource(R.string.stores_in_steam) else stringResource(R.string.stores_add_to_steam)) {
                                StoreInstalls.setAddToSteam(ctx, installed, !inSteam) { StoresState.refresh(ctx); a.onLibraryChanged() }
                            }
                            s.steamGames.firstOrNull { it.source == store.id && it.storeId == id }?.protonPrefix?.takeIf { it.isDirectory }?.let { dir ->
                                SecondaryButton(stringResource(R.string.games_prefix)) { a.onBrowseFiles(dir) }
                            }
                            SecondaryButton(stringResource(R.string.games_files)) { a.onBrowseFiles(installed.folder) }
                            SecondaryButton(if (confirmRemove) stringResource(R.string.stores_uninstall_confirm) else stringResource(R.string.stores_uninstall), enabled = download == null) {
                                if (!confirmRemove) confirmRemove = true
                                else { confirmRemove = false; StoresState.uninstall(ctx, installed) { StoresState.refresh(ctx); a.onLibraryChanged() }; onBack() }
                            }
                        }
                        download != null -> {
                            ActionChip(downloadLabel(download.state, download.stage, download.percent), ok = false)
                            if (download.state != DownloadState.PAUSED) SecondaryButton(stringResource(R.string.stores_dl_pause)) { com.droiddeck.launcher.stores.download.DownloadQueue.pause(download.key) }
                            else PrimaryButton(stringResource(R.string.stores_dl_resume), main = true) { com.droiddeck.launcher.stores.download.DownloadQueue.resume(ctx, download.key) }
                            SecondaryButton(stringResource(R.string.stores_dl_cancel)) { com.droiddeck.launcher.stores.download.DownloadQueue.cancel(ctx, download.key) }
                        }
                        item?.owned == true -> PrimaryButton(
                            if (item.sizeBytes > 0) stringResource(R.string.stores_install_size, formatBytes(item.sizeBytes)) else stringResource(R.string.stores_install), main = true,
                        ) { StoresState.install(ctx, item) }
                        item != null && item.isFree -> PrimaryButton(stringResource(R.string.stores_get_free), main = true) { openStoreUrl(ctx, item) }
                        item != null -> PrimaryButton(if (item.hasPrice && item.finalPrice.isNotBlank()) stringResource(R.string.stores_buy_price, item.finalPrice) else stringResource(R.string.stores_view_on, store.shortLabel), main = true) { openStoreUrl(ctx, item) }
                    }
                    BusyChip(s)
                }
            }
        }
    }
    // Plain text only, and nothing at all when the store sent a template key instead of words.
    val description = item?.let { com.droiddeck.launcher.stores.cleanStoreText(it.description) }.orEmpty()
    if (description.isNotBlank()) Rise(2) {
        Text(description, fontSize = 13.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
    }
    Rise(3) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            val wide = maxWidth >= 640.dp
            val launchSettings: @Composable () -> Unit = {
                Column {
                    SectionTitle(stringResource(R.string.games_launch_settings), null)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        InfoCard(stringResource(R.string.stores_card_proton), stringResource(R.string.stores_card_proton_value), Modifier.weight(1f))
                        InfoCard(stringResource(R.string.stores_card_audio), stringResource(R.string.stores_card_audio_value), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        InfoCard(stringResource(R.string.stores_card_components), stringResource(R.string.stores_card_components_value), Modifier.weight(1f))
                        InfoCard(stringResource(R.string.stores_card_engine), if (StoresState.engine.isNullOrEmpty()) stringResource(R.string.stores_card_engine_java, store.shortLabel) else stringResource(R.string.stores_card_engine_value, store.shortLabel), Modifier.weight(1f))
                    }
                }
            }
            val howItRuns: @Composable () -> Unit = {
                Column {
                    SectionTitle(stringResource(R.string.stores_how_it_runs), null)
                    KeyValue(stringResource(R.string.stores_kv_fetched), stringResource(R.string.stores_kv_fetched_value, store.label))
                    KeyValue(stringResource(R.string.stores_kv_installed_to), installed?.folder?.path ?: stringResource(R.string.stores_kv_installed_to_value, store.shortLabel, com.droiddeck.launcher.stores.StoreInstallRoot.folderName(title, id)))
                    KeyValue(stringResource(R.string.stores_kv_launched_by), when (store) {
                        Store.EPIC -> stringResource(R.string.stores_kv_launched_by_epic)
                        Store.AMAZON -> stringResource(R.string.stores_kv_launched_by_amazon)
                        Store.GOG -> stringResource(R.string.stores_kv_launched_by_value)
                    })
                    KeyValue(stringResource(R.string.stores_kv_steam), when {
                        installed != null && installed.sidecar.addToSteam -> stringResource(R.string.stores_kv_steam_on)
                        installed != null -> stringResource(R.string.stores_kv_steam_off)
                        s.gameStoresAddToSteam -> stringResource(R.string.stores_kv_steam_on)
                        else -> stringResource(R.string.stores_kv_steam_off)
                    })
                }
            }
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.weight(2f)) { launchSettings() }
                Box(Modifier.weight(1.2f)) { howItRuns() }
            } else Column { launchSettings(); howItRuns() }
        }
    }
}

@Composable
private fun InfoCard(label: String, value: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.clip(Shape12).background(colors.surface).border(1.dp, LocalPalette.current.line, Shape12).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun KeyValue(k: String, v: String) {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(v, fontSize = 13.sp, color = colors.onBackground, modifier = Modifier.weight(1f))
    }
}
