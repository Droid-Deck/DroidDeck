package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
            SourceChip(store.id, withName = true)
        }
    }
    Rise(1) {
        Box(Modifier.fillMaxWidth().heightIn(min = if (narrow) 150.dp else 180.dp).clip(Shape16).background(artBrush(hueOf(title)))) {
            val art = item?.imageUrl ?: installed?.sidecar?.hero ?: installed?.sidecar?.cover
            if (art != null) AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            Spacer(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to colors.background.copy(alpha = 0.94f), 0.5f to colors.background.copy(alpha = 0.55f), 1f to Color.Transparent)))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 22.dp, vertical = 18.dp)) {
                // An install names where it went (internal storage, the card), a download where it is going.
                val state = when {
                    installed != null -> stringResource(R.string.stores_eyebrow_installed) + " · " + com.droiddeck.launcher.stores.StoreInstallRoot.labelFor(ctx, installed.folder)
                    download != null && download.location.isNotBlank() -> stringResource(R.string.stores_eyebrow_library) + " · " + download.location
                    item?.owned == true -> stringResource(R.string.stores_eyebrow_library)
                    else -> stringResource(R.string.stores_eyebrow_store)
                }
                val size = (item?.sizeBytes ?: 0L).takeIf { it > 0 }?.let { " · " + formatBytes(it) } ?: ""
                Text((state + size).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = pal.signal)
                Text(title, fontSize = if (narrow) 24.sp else 30.sp, lineHeight = if (narrow) 27.sp else 33.sp, fontWeight = FontWeight.Black, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Actions {
                    when {
                        installed != null -> {
                            // Launching, the prefix and the files belong to the Games tab, where every
                            // installed game has them; here an install only says it is in Steam and
                            // offers the one thing Games does not: removing it.
                            ActionChip(stringResource(R.string.stores_in_steam), ok = true)
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
                        item?.owned == true && StoresState.isUnfinished(item) -> PrimaryButton(stringResource(R.string.stores_resume_install), main = true) { StoresState.requestInstall(ctx, item) }
                        item?.owned == true -> PrimaryButton(
                            if (item.sizeBytes > 0) stringResource(R.string.stores_install_size, formatBytes(item.sizeBytes)) else stringResource(R.string.stores_install), main = true,
                        ) { StoresState.requestInstall(ctx, item) }
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
}
