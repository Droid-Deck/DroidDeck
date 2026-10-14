package com.droiddeck.launcher.ui

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.launch
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
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
    val stacked = narrow || LocalConfiguration.current.screenWidthDp < 700
    val id = key.substringAfter(':')
    val item = (StoresState.library[store].orEmpty() + StoresState.shelves[store]?.all.orEmpty()).firstOrNull { it.id == id }
    val installed = StoresState.installedGame(store, id)
    val rawDownload = StoresState.download("${store.id}:$id")
    val download = rawDownload?.takeIf { it.isActive }
    val failedDownload = rawDownload?.takeIf { it.state == DownloadState.FAILED }
    val title = item?.title ?: installed?.sidecar?.title ?: id
    Rise(0) {
        Box(Modifier.padding(bottom = 12.dp)) { BackLink(stringResource(R.string.common_back), compact = true, onClick = onBack) }
    }
    val scores = if (store == Store.GOG || store == Store.EPIC) rememberGameScores(title.takeIf { it != id }) else null
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
            if (scores != null && !stacked) {
                Spacer(Modifier.matchParentSize().background(Brush.horizontalGradient(0.45f to Color.Transparent, 1f to colors.background.copy(alpha = 0.75f))))
                HeroScores(scores, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 18.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 22.dp, vertical = 18.dp)) {
                val state = when {
                    installed != null -> stringResource(R.string.stores_eyebrow_installed) + " · " + com.droiddeck.launcher.stores.StoreInstallRoot.labelFor(ctx, installed.folder)
                    download != null && download.location.isNotBlank() -> stringResource(R.string.stores_eyebrow_library) + " · " + download.location
                    failedDownload != null && failedDownload.location.isNotBlank() -> stringResource(R.string.stores_eyebrow_library) + " · " + failedDownload.location
                    item?.owned == true -> stringResource(R.string.stores_eyebrow_library)
                    else -> stringResource(R.string.stores_eyebrow_store)
                }
                val size = (item?.sizeBytes ?: 0L).takeIf { it > 0 }?.let { " · " + formatBytes(it) } ?: ""
                Text((state + size).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = pal.signal)
                Text(title, fontSize = if (narrow) 24.sp else 30.sp, lineHeight = if (narrow) 27.sp else 33.sp, fontWeight = FontWeight.Black, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!failedDownload?.error.isNullOrBlank()) {
                    Text(
                        failedDownload!!.error!!,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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
                        failedDownload != null -> {
                            ActionChip(stringResource(R.string.stores_dl_failed), ok = false)
                            PrimaryButton(stringResource(R.string.stores_dl_resume), main = true) {
                                if (item != null) StoresState.requestInstall(ctx, item)
                                else com.droiddeck.launcher.stores.download.DownloadQueue.retry(ctx, failedDownload.key)
                            }
                            ConfirmButton(stringResource(R.string.stores_dl_clear), stringResource(R.string.stores_dl_cancel_confirm), compact = true) {
                                com.droiddeck.launcher.stores.download.DownloadQueue.clear(ctx, failedDownload.key)
                            }
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
    if (scores != null) Rise(2) {
        val listed = StoresState.shelves[store]?.all.orEmpty().firstOrNull { it.id == id }
        val developer = item?.developer?.ifBlank { null } ?: listed?.developer.orEmpty()
        val publisher = item?.publisher?.ifBlank { null } ?: listed?.publisher.orEmpty()
        val releaseDate = item?.releaseDate?.ifBlank { null } ?: listed?.releaseDate.orEmpty()
        StoreGameFacts(developer, publisher, releaseDate, scores, scoresInHero = !stacked)
    }
    val description = item?.let { com.droiddeck.launcher.stores.cleanStoreText(it.description) }.orEmpty()
        .takeUnless { it.trim().equals(title.trim(), ignoreCase = true) }.orEmpty()
    val achievements = remember(store, id, item?.owned, installed) {
        when {
            StoreAchievements.forSource(store.id) == null -> null
            item?.owned == true -> StoreAchievements.Game.of(item)
            installed != null -> StoreAchievements.Game.of(installed.sidecar)
            else -> null
        }
    }
    if (description.isNotBlank() || achievements != null) Rise(3) {
        val about: @Composable (Modifier, Dp) -> Unit = { m, h -> if (description.isNotBlank()) StoreAbout(description, h, m) }
        val card: @Composable (Modifier) -> Unit = { m -> if (achievements != null) StoreAchievementsCard(achievements, title, m) }
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
            about(Modifier.fillMaxWidth(), AboutHeight)
            card(Modifier.fillMaxWidth())
        } else Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
            val density = LocalDensity.current
            var cardHeight by remember { mutableStateOf<Dp?>(null) }
            about(Modifier.weight(1f), cardHeight ?: AboutHeight)
            card(Modifier.width(340.dp).onSizeChanged { cardHeight = with(density) { it.height.toDp() } })
        }
    }
}

private val AboutHeight = 150.dp

@Composable
private fun StoreAbout(text: String, height: Dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val src = remember { MutableInteractionSource() }
    val more = scroll.maxValue > 0
    val hot = more && rememberHot(src)
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.heightIn(max = height).clip(shape)
            .glideBorder(hot, shape, pal.signal, restColor = Color.Transparent)
            .then(if (more) Modifier.paneItem("about") else Modifier)
            .focusable(enabled = more, interactionSource = src)
            .onPreviewKeyEvent { e ->
                val step = when (e.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> if (scroll.value < scroll.maxValue) 120f else return@onPreviewKeyEvent false
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> if (scroll.value > 0) -120f else return@onPreviewKeyEvent false
                    else -> return@onPreviewKeyEvent false
                }
                if (e.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) scope.launch { scroll.animateScrollBy(step) }
                true
            },
    ) {
        Text(text, fontSize = 14.sp, lineHeight = 21.sp, color = colors.onSurfaceVariant, modifier = Modifier.verticalScroll(scroll).padding(horizontal = 10.dp, vertical = 6.dp))
        if (scroll.value < scroll.maxValue) Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, colors.background))),
        )
    }
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
