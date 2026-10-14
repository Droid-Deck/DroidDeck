package com.droiddeck.launcher.ui

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.droiddeck.launcher.stores.StoreAchievements
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadState
import com.droiddeck.launcher.stores.formatBytes


@Composable
internal fun StoreGameDetail(store: Store, key: String, s: FrontEndState, a: FrontEndActions, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val narrow = LocalNarrowPane.current
    val id = key.substringAfter(':')
    val item = (StoresState.library[store].orEmpty() + StoresState.shelves[store]?.all.orEmpty()).firstOrNull { it.id == id }
    val installed = StoresState.installedGame(store, id)
    val download = StoresState.download("${store.id}:$id")?.takeIf { it.isActive }
    val title = item?.title ?: installed?.sidecar?.title ?: id
    Rise(0) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            BackLink(store.label, compact = true, onClick = onBack)
            SourceChip(store.id)
        }
    }
    val hero: @Composable () -> Unit = {
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { StoresMotion.heroAt = null } }
        Box(
            Modifier.fillMaxWidth().heightIn(min = if (narrow) 150.dp else 180.dp)
                .onGloballyPositioned { StoresMotion.heroAt = it.boundsInRoot() }
                .clip(Shape16).background(artBrush(hueOf(title))),
        ) {
            val art = item?.imageUrl ?: installed?.sidecar?.hero ?: installed?.sidecar?.cover
            if (art != null) AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            Spacer(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to colors.background.copy(alpha = 0.94f), 0.5f to colors.background.copy(alpha = 0.55f), 1f to Color.Transparent)))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 22.dp, vertical = 18.dp)) {
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
                            ActionChip(stringResource(R.string.stores_in_steam), ok = true)
                            val gone = StoresState.uninstalling["${store.id}:$id"]
                            if (gone != null) SecondaryButton(stringResource(R.string.stores_uninstalling), enabled = false, progress = gone) {}
                            else UninstallButton(title, installed, enabled = download == null) {
                                StoresState.uninstall(ctx, installed) { StoresState.refresh(ctx); a.onLibraryChanged() }
                            }
                        }
                        download != null -> {
                            ActionChip(downloadLabel(download), ok = false)
                            if (download.state != DownloadState.PAUSED) SecondaryButton(stringResource(R.string.stores_dl_pause)) { com.droiddeck.launcher.stores.download.DownloadQueue.pause(download.key) }
                            else PrimaryButton(stringResource(R.string.stores_dl_resume), main = true) { com.droiddeck.launcher.stores.download.DownloadQueue.resume(ctx, download.key) }
                            ConfirmButton(stringResource(R.string.stores_dl_cancel), stringResource(R.string.stores_dl_cancel_confirm)) { com.droiddeck.launcher.stores.download.DownloadQueue.cancel(ctx, download.key) }
                        }
                        item?.owned == true && StoresState.isUnfinished(item) -> {
                            InstallButton(stringResource(R.string.stores_resume_install)) { StoresState.requestInstall(ctx, item) }
                            ConfirmButton(stringResource(R.string.stores_dl_clear), stringResource(R.string.stores_dl_cancel_confirm), compact = true) { StoresState.clearUnfinished(ctx, item) }
                        }
                        item?.owned == true -> InstallButton(
                            if (item.sizeBytes > 0) stringResource(R.string.stores_install_size, formatBytes(item.sizeBytes)) else stringResource(R.string.stores_install),
                        ) { StoresState.requestInstall(ctx, item) }
                        item != null && item.isFree -> PrimaryButton(stringResource(R.string.stores_get_free), main = true) { openStoreUrl(ctx, item) }
                        item != null -> PrimaryButton(if (item.hasPrice && item.finalPrice.isNotBlank()) stringResource(R.string.stores_buy_price, item.finalPrice) else stringResource(R.string.stores_view_on, store.shortLabel), main = true) { openStoreUrl(ctx, item) }
                    }
                    BusyChip(s)
                }
            }
        }
    }
    if (StoresMotion.heroLands) hero() else Rise(1) { hero() }
    if (store == Store.GOG || store == Store.EPIC) {
        val listed = StoresState.shelves[store]?.all.orEmpty().firstOrNull { it.id == id }
        val developer = item?.developer?.ifBlank { null } ?: listed?.developer.orEmpty()
        val publisher = item?.publisher?.ifBlank { null } ?: listed?.publisher.orEmpty()
        val releaseDate = item?.releaseDate?.ifBlank { null } ?: listed?.releaseDate.orEmpty()
        val scores = rememberGameScores(title.takeIf { it != id })
        Rise(2) { StoreGameFacts(developer, publisher, releaseDate, scores) }
    }
    val description = item?.let { com.droiddeck.launcher.stores.cleanStoreText(it.description) }.orEmpty()
        .takeUnless { it.trim().equals(title.trim(), ignoreCase = true) }.orEmpty()
    if (description.isNotBlank()) Rise(3) {
        Text(description, fontSize = 13.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
    }
    val achievements = remember(store, id, item?.owned, installed) {
        when {
            StoreAchievements.forSource(store.id) == null -> null
            item?.owned == true -> StoreAchievements.Game.of(item)
            installed != null -> StoreAchievements.Game.of(installed.sidecar)
            else -> null
        }
    }
    if (achievements != null) Rise(4) { StoreAchievementsSection(achievements) }
}

@Composable
private fun UninstallButton(title: String, game: com.droiddeck.launcher.stores.InstalledStoreGame, enabled: Boolean, onUninstall: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val heading = stringResource(R.string.stores_uninstall_title, title)
    val size = com.droiddeck.launcher.stores.StoreSizes.onDisk["${game.sidecar.store.id}:${game.sidecar.id}"]
    val body = if (size != null && size > 0) stringResource(R.string.stores_uninstall_body_size, formatBytes(size)) else stringResource(R.string.stores_uninstall_body)
    val keep = stringResource(R.string.stores_notification_keep)
    val remove = stringResource(R.string.stores_uninstall)
    Box(Modifier.onGloballyPositioned { placed[0] = it }) {
        SecondaryButton(remove, enabled = enabled) {
            val at = placed[0]?.takeIf { it.isAttached }?.boundsInRoot() ?: return@SecondaryButton
            var confirmed = false
            StoresMotion.ask(StepAsk(anchor = at, side = StepSide.Right, accent = colors.error, items = 3, pillCorner = 12.dp, maxWidth = 340.dp,
                onDismiss = { if (confirmed) onUninstall() }, handle = {
                Text(remove, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.error, maxLines = 1)
            }) {
                StepTitle(heading)
                Text(body, fontSize = 13.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant, modifier = Modifier.stepItem(1).padding(start = 4.dp, bottom = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth().stepItem(2)) {
                    StepChoice(keep, enabled = open, modifier = Modifier.focusRequester(first)) { StoresMotion.fold() }
                    StepChoice(remove, danger = true, enabled = open) { confirmed = true; StoresMotion.fold() }
                }
            })
        }
    }
}

@Composable
private fun InstallButton(label: String, onInstall: () -> Unit) {
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    Box(Modifier.onGloballyPositioned { placed[0] = it }) {
        PrimaryButton(label, main = true) {
            StoresMotion.markInstall(placed[0]?.takeIf { it.isAttached }?.boundsInRoot(), label)
            onInstall()
        }
    }
}
