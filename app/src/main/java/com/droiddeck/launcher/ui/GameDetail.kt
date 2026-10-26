package com.droiddeck.launcher.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.GameRemoval
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.GameSaves
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.CloudSaves
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Lookup
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAchievements
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreLanguage
import com.droiddeck.launcher.stores.StoreSizes
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadState
import com.droiddeck.launcher.stores.formatBytes
import com.droiddeck.launcher.stores.xbox.XboxBackend
import com.droiddeck.launcher.stores.xbox.XboxCloudGames
import com.droiddeck.launcher.stores.xbox.XboxContent
import com.droiddeck.launcher.stores.xbox.xboxCloud
import com.droiddeck.launcher.stores.xbox.xboxPc
import com.droiddeck.launcher.stores.xbox.XboxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/** What a game page shows: `app:<appId>` for a game in the library, `[app:]<store>:<id>` for a store product. */
internal class GameRef(val appId: Int?, val store: Store?, val storeId: String?) {
    fun game(games: List<Library.SteamGame>): Library.SteamGame? = when {
        appId != null -> games.firstOrNull { it.appId == appId }
        store != null && storeId != null -> games.firstOrNull { it.source == store.id && it.storeId == storeId }
        else -> null
    }

    companion object {
        fun parse(key: String): GameRef {
            val rest = key.removePrefix("app:")
            rest.toIntOrNull()?.let { return GameRef(it, null, null) }
            val store = Store.byId(rest.substringBefore(':'))
            return GameRef(null, store, rest.substringAfter(':', "").takeIf { store != null && it.isNotEmpty() })
        }

        fun key(game: Library.SteamGame) = "app:${game.appId}"
        fun key(item: CatalogItem) = "app:${item.key}"
    }
}

internal enum class GameTab { ABOUT, ACHIEVEMENTS, LAUNCH, SAVES, INSTALL }

@Composable
internal fun GameDetail(
    key: String, s: FrontEndState, a: FrontEndActions, lead: FocusRequester, backLabel: String, hopRail: String?, onBack: () -> Unit,
) {
    val narrow = LocalNarrowPane.current
    val stacked = narrow || LocalConfiguration.current.screenWidthDp < 700
    val ref = remember(key) { GameRef.parse(key) }
    val games = s.steamGames + rememberXboxCloudRows(s)
    val found = ref.game(games)
    val known = remember(key) { arrayOfNulls<Pair<Store, String>>(1) }
    val store = ref.store ?: Store.byId(found?.source) ?: known[0]?.first
    val id = ref.storeId ?: found?.storeId ?: known[0]?.second
    SideEffect { if (store != null && id != null) known[0] = store to id }
    val game = found ?: if (store != null && id != null) s.steamGames.firstOrNull { it.source == store.id && it.storeId == id } else null
    val item = if (store != null && id != null) StoresState.detailItem(store, id) else null
    val installed = if (store != null && id != null) StoresState.installedGame(store, id) else null
    val rawDownload = if (store != null && id != null) StoresState.download("${store.id}:$id") else null
    val download = rawDownload?.takeIf { it.isActive }
    val failed = rawDownload?.takeIf { it.state == DownloadState.FAILED }
    val nothing = game == null && store == null
    LaunchedEffect(nothing) { if (nothing) onBack() }
    if (nothing) return
    val source = game?.source ?: store?.id ?: Library.SOURCE_STEAM
    val title = game?.name ?: item?.title ?: installed?.sidecar?.title ?: id.orEmpty()
    val scores = if (source != Library.ADDED) rememberGameScores(title.takeIf { it.isNotBlank() && it != id }) else null
    val achievements = remember(store, id, item?.accessible, installed) {
        when {
            store == null || StoreAchievements.forSource(store.id) == null -> null
            item?.accessible == true -> StoreAchievements.Game.of(item)
            installed != null -> StoreAchievements.Game.of(installed.sidecar)
            else -> null
        }
    }
    val achievementState = rememberAchievements(achievements)
    val listed = if (store == null) null else (StoresState.found[store].orEmpty() + StoresState.shelves[store]?.all.orEmpty()).firstOrNull { it.id == id }
    val developer = item?.developer?.ifBlank { null } ?: listed?.developer.orEmpty()
    val publisher = item?.publisher?.ifBlank { null } ?: listed?.publisher.orEmpty()
    val releaseDate = item?.releaseDate?.ifBlank { null } ?: listed?.releaseDate.orEmpty()
    val description = item?.let { com.droiddeck.launcher.stores.cleanStoreText(it.description) }.orEmpty()
        .takeUnless { it.trim().equals(title.trim(), ignoreCase = true) }.orEmpty()
    val scored = scores != null && (scores.metacritic != Lookup.None || scores.hltb != Lookup.None)
    val prefix = game?.protonPrefix?.takeIf { it.isDirectory }
    val tabs = buildList {
        if (description.isNotBlank() || developer.isNotBlank() || publisher.isNotBlank() || releaseDate.isNotBlank() || (scored && stacked)) add(GameTab.ABOUT)
        if (achievementState != null) add(GameTab.ACHIEVEMENTS)
        val cloud = game?.library == Library.XBOX_CLOUD
        if (game != null && !cloud) add(GameTab.LAUNCH)
        if (game != null && game.library == Library.ADDED && prefix != null) add(GameTab.SAVES)
        if ((game != null && !cloud) || installed != null) add(GameTab.INSTALL)
    }
    var picked by remember(key) { mutableStateOf<GameTab?>(null) }
    val tab = picked?.takeIf { it in tabs } ?: tabs.firstOrNull()
    val tabFocus = remember(key) { GameTab.entries.associateWith { FocusRequester() } }
    var inPanel by remember(key) { mutableStateOf(false) }
    var inPage by remember(key) { mutableStateOf(false) }
    var onStrip by remember(key) { mutableStateOf(false) }
    var dropped by remember(key) { mutableStateOf(0) }
    // A tab's content is swapped or refreshed under the focused row; focus goes back to the tabs, not to the rail.
    LaunchedEffect(dropped) {
        if (dropped == 0) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        val t = tab ?: return@LaunchedEffect
        if (!inPage) focusWithinFrames({ onStrip }) { tabFocus.getValue(t) }
    }
    fun cycle(step: Int) {
        if (tabs.size < 2) return
        val at = tabs.indexOf(tab).coerceAtLeast(0)
        picked = tabs[(at + step + tabs.size) % tabs.size]
    }
    Column(Modifier.fillMaxWidth().onFocusChanged { inPage = it.hasFocus }.bumpers(onPrevious = { cycle(-1) }, onNext = { cycle(1) })) {
        Rise(0) {
            Box(Modifier.padding(bottom = 12.dp)) { BackLink(backLabel, compact = true, onClick = onBack) }
        }
        val hero: @Composable () -> Unit = {
            DisposableEffect(Unit) { onDispose { StoresMotion.heroAt = null } }
            GameHero(
                title, item, installed, game,
                Modifier.fillMaxWidth().heightIn(min = if (narrow) 170.dp else 210.dp).onGloballyPositioned { StoresMotion.heroAt = it.boundsInRoot() },
                corner = { if (!s.shortcutPicker) RemoveButton(title, game, installed, a) },
                scores = scores.takeIf { !stacked },
            ) {
                HeroEyebrow(source, game, item, installed, download?.location ?: failed?.location)
                GameTitle(title, game?.logo ?: item?.logoUrl, if (narrow) 26.sp else 34.sp)
                if (!failed?.error.isNullOrBlank()) Text(
                    failed!!.error!!, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                GameActions(store, id, game, item, installed, s, a, Modifier.focusRequester(lead))
            }
        }
        if (StoresMotion.heroLands) hero() else Rise(1) { hero() }
        if (tab != null) Rise(2) {
            Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val labels = tabs.map { t ->
                    when (t) {
                        GameTab.ABOUT -> stringResource(R.string.game_tab_about)
                        GameTab.ACHIEVEMENTS -> achievementState?.list?.takeIf { it.isNotEmpty() }?.let { list ->
                            stringResource(R.string.game_tab_achievements_count, stringResource(R.string.achievements_title), achievementState.done, list.size)
                        } ?: stringResource(R.string.achievements_title)
                        GameTab.LAUNCH -> stringResource(R.string.game_tab_launch)
                        GameTab.SAVES -> stringResource(if (Store.byId(source).let { it == Store.GOG || it == Store.EPIC }) R.string.game_tab_saves_cloud else R.string.games_saves)
                        GameTab.INSTALL -> stringResource(R.string.game_tab_install)
                    }
                }
                TabStrip(
                    labels, tabs.indexOf(tab), { picked = tabs[it] }, Modifier.onFocusChanged { onStrip = it.hasFocus },
                    focusRequesters = tabs.map { tabFocus.getValue(it) }, bumpers = tabs.size > 1, compact = narrow,
                )
                Box(Modifier.fillMaxWidth().onFocusChanged { if (inPanel && !it.hasFocus) dropped++; inPanel = it.hasFocus }.testTag("game-tab-${tab.name.lowercase()}")) {
                    when (tab) {
                        GameTab.ABOUT -> AboutTab(developer, publisher, releaseDate, description, scores, stacked)
                        GameTab.ACHIEVEMENTS -> achievementState?.let { st -> AchievementsTab(StoreAchievements.forSource(store?.id)?.label.orEmpty(), st) }
                        GameTab.LAUNCH -> if (game != null) LaunchTab(game, s, a, hopRail)
                        GameTab.SAVES -> if (game != null && prefix != null) SavesTab(game, prefix, a)
                        GameTab.INSTALL -> InstallTab(game, installed, item, store)
                    }
                }
            }
        }
    }
}

@Composable
private fun GameHero(
    title: String, item: CatalogItem?, installed: InstalledStoreGame?, game: Library.SteamGame?, modifier: Modifier,
    corner: @Composable BoxScope.() -> Unit, scores: GameScores?, content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.clip(Shape16).background(artBrush(hueOf(title)))) {
        val wide: Any? = game?.hero ?: item?.imageUrl ?: installed?.sidecar?.hero ?: installed?.sidecar?.cover
        if (wide != null) {
            // The card it came from keeps its own bitmap under the hero until the wide art is decoded.
            val request = if (wide is String) cardArtRequest(wide, item?.tallImageUrl) else wide
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else game?.art?.let { art ->
            AsyncImage(
                model = art, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().blur(24.dp).graphicsLayer { scaleX = 1.3f; scaleY = 1.3f },
            )
        }
        Spacer(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to colors.background.copy(alpha = 0.94f), 0.55f to colors.background.copy(alpha = 0.58f), 1f to Color.Transparent)))
        if (scores != null) {
            Spacer(Modifier.matchParentSize().background(Brush.horizontalGradient(0.45f to Color.Transparent, 1f to colors.background.copy(alpha = 0.75f))))
            HeroScores(scores, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 18.dp))
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 22.dp, vertical = 18.dp),
            content = content,
        )
        Box(Modifier.align(Alignment.TopEnd).padding(12.dp), content = corner)
    }
}

@Composable
private fun HeroEyebrow(source: String, game: Library.SteamGame?, item: CatalogItem?, installed: InstalledStoreGame?, downloadAt: String?) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val folder = installed?.folder ?: game?.gameFiles
    val passName = item?.includedWith.orEmpty()
    val state = when {
        installed != null -> listOf(stringResource(R.string.stores_eyebrow_installed), StoreInstallRoot.labelFor(ctx, installed.folder))
        game?.library == Library.XBOX_CLOUD -> listOf(libraryLabel(game))
        game != null -> listOf(stringResource(R.string.stores_eyebrow_installed), libraryLabel(game))
        !downloadAt.isNullOrBlank() -> listOf(stringResource(R.string.stores_eyebrow_library), downloadAt)
        item?.owned == true -> listOf(stringResource(R.string.stores_eyebrow_library))
        item?.subscription == true -> listOf(passName.ifBlank { stringResource(R.string.stores_game_pass) })
        passName.isNotBlank() -> listOf(stringResource(R.string.stores_eyebrow_pass, passName))
        item?.accessible == true -> listOf(stringResource(R.string.stores_eyebrow_library))
        else -> listOf(stringResource(R.string.stores_eyebrow_store))
    }
    val size = if (folder != null && (installed != null || game != null)) diskSize(installed, folder) else (item?.sizeBytes ?: 0L).takeIf { it > 0 }
    val line = (state + listOfNotNull(size?.let { formatBytes(it) })).filter { it.isNotBlank() }.joinToString(" · ")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SourceChip(source)
        Text(line.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = pal.signal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GameTitle(title: String, logo: Any?, titleSize: TextUnit) {
    var failed by remember(logo) { mutableStateOf(false) }
    if (logo != null && !failed) AsyncImage(
        model = logo, contentDescription = title, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
        onError = { failed = true },
        modifier = Modifier.padding(vertical = 4.dp).height((titleSize.value * 2.2f).dp).widthIn(max = (titleSize.value * 9f).dp).testTag("game-logo"),
    ) else Text(
        title, fontSize = titleSize, lineHeight = titleSize * 1.12f, fontWeight = FontWeight.Black,
        color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun GameActions(
    store: Store?, id: String?, game: Library.SteamGame?, item: CatalogItem?, installed: InstalledStoreGame?,
    s: FrontEndState, a: FrontEndActions, leadOn: Modifier,
) {
    val ctx = LocalContext.current
    val storeKey = if (store != null && id != null) "${store.id}:$id" else null
    val rawDownload = storeKey?.let { StoresState.download(it) }
    val download = rawDownload?.takeIf { it.isActive }
    val failed = rawDownload?.takeIf { it.state == DownloadState.FAILED }
    val cloud = game?.library == Library.XBOX_CLOUD && installed == null
    Actions {
        when {
            s.shortcutPicker && game != null -> {
                PrimaryButton(stringResource(R.string.game_shortcut_choose), enabled = !s.busy, main = true, icon = Icons.Filled.PlayArrow, modifier = leadOn) { a.onSteamGame(game) }
                FileButtons(game, a)
            }
            !cloud && (game != null || installed != null) -> {
                val gone = storeKey?.let { StoresState.uninstalling[it] }
                val xboxBusy = store == Store.XBOX && id != null && XboxContent.busy(id)
                PrimaryButton(
                    stringResource(R.string.stores_play), enabled = !s.busy && gone == null && download == null && !xboxBusy,
                    main = true, icon = Icons.Filled.PlayArrow, modifier = leadOn,
                ) {
                    if (game != null) a.onSteamGame(game) else if (store != null && id != null) launchStoreGame(ctx, store, id, s, a)
                }
                if ((game?.source ?: store?.id) != Library.SOURCE_STEAM) ActionChip(stringResource(R.string.stores_in_steam), ok = true)
                if (game != null) {
                    FileButtons(game, a)
                    GameShortcutMenu(game, a)
                    EditArtButton(game)
                }
                if (store == Store.XBOX && id != null) XboxInstalledStream(id, game?.name ?: item?.title.orEmpty(), item)
                if (store == Store.XBOX && installed != null && id != null) {
                    SecondaryButton(stringResource(R.string.stores_xbox_update), enabled = download == null && !XboxRuntime.isActive(id)) {
                        runCatching { XboxBackend.update(ctx, installed) }
                            .onFailure { StoresState.problems[Store.XBOX] = it.message ?: ctx.getString(R.string.stores_xbox_close_first) }
                    }
                    SecondaryButton(stringResource(R.string.stores_xbox_manage), enabled = download == null) { XboxContent.manage(ctx, installed) }
                }
                if (store == Store.BATTLENET) SecondaryButton(stringResource(R.string.bnet_open)) { a.onBattleNet() }
            }
            download != null -> {
                ActionChip(downloadLabel(download), ok = false)
                if (download.state != DownloadState.PAUSED) SecondaryButton(stringResource(R.string.stores_dl_pause), modifier = leadOn) { DownloadQueue.pause(download.key) }
                else PrimaryButton(stringResource(R.string.stores_dl_resume), main = true, modifier = leadOn) { DownloadQueue.resume(ctx, download.key) }
                ConfirmButton(stringResource(R.string.stores_dl_cancel), stringResource(R.string.stores_dl_cancel_confirm)) { DownloadQueue.cancel(ctx, download.key) }
            }
            failed != null -> {
                ActionChip(stringResource(R.string.stores_dl_failed), ok = false)
                PrimaryButton(stringResource(R.string.stores_dl_resume), main = true, modifier = leadOn) {
                    if (item != null) StoresState.requestInstall(ctx, item) else DownloadQueue.retry(ctx, failed.key)
                }
                ConfirmButton(stringResource(R.string.stores_dl_clear), stringResource(R.string.stores_dl_cancel_confirm), compact = true) { DownloadQueue.clear(ctx, failed.key) }
            }
            cloud -> {
                val cloudId = game.storeId.orEmpty()
                XboxStreamButton(cloudId, game.name, main = true, modifier = leadOn)
                if (item?.installable == true && item.xboxPc) {
                    SecondaryButton(
                        if (StoresState.isUnfinished(item)) stringResource(R.string.stores_resume_install)
                        else if (item.sizeBytes > 0) stringResource(R.string.stores_install_size, formatBytes(item.sizeBytes))
                        else stringResource(R.string.stores_install),
                    ) { StoresState.requestInstall(ctx, item) }
                }
                if (item != null && StoresState.isUnfinished(item)) ConfirmButton(stringResource(R.string.stores_dl_clear), stringResource(R.string.stores_dl_cancel_confirm), compact = true) { StoresState.clearUnfinished(ctx, item) }
                XboxLibraryButton(cloudId, item)
            }
            store == Store.BATTLENET && item != null -> InstallButton(stringResource(R.string.stores_install), leadOn) { StoresState.requestInstall(ctx, item) }
            store == Store.XBOX && item != null && !item.xboxPc && item.xboxCloud -> {
                XboxStreamButton(item.id, item.title, main = true, modifier = leadOn)
                XboxLibraryButton(item.id, item)
            }
            item?.installable == true && StoresState.isUnfinished(item) -> {
                InstallButton(stringResource(R.string.stores_resume_install), leadOn) { StoresState.requestInstall(ctx, item) }
                ConfirmButton(stringResource(R.string.stores_dl_clear), stringResource(R.string.stores_dl_cancel_confirm), compact = true) { StoresState.clearUnfinished(ctx, item) }
            }
            item?.installable == true -> {
                InstallButton(
                    if (item.sizeBytes > 0) stringResource(R.string.stores_install_size, formatBytes(item.sizeBytes)) else stringResource(R.string.stores_install),
                    leadOn,
                ) { StoresState.requestInstall(ctx, item) }
                if (store == Store.XBOX && item.xboxCloud) {
                    XboxStreamButton(item.id, item.title)
                    XboxLibraryButton(item.id, item)
                }
                if (!item.accessible) SecondaryButton(buyLabel(item)) { openStoreUrl(ctx, item) }
            }
            item != null && item.store == Store.ITCH && item.isFree -> {
                InstallButton(stringResource(R.string.stores_install), leadOn) { StoresState.requestInstall(ctx, item) }
                SecondaryButton(stringResource(R.string.stores_view_on, item.store.shortLabel)) { openStoreUrl(ctx, item) }
            }
            item != null && item.isFree -> PrimaryButton(stringResource(R.string.stores_get_free), main = true, modifier = leadOn) { openStoreUrl(ctx, item) }
            item != null -> PrimaryButton(buyLabel(item), main = true, modifier = leadOn) { openStoreUrl(ctx, item) }
        }
        if (cloud && (download != null || failed != null)) {
            XboxStreamButton(game.storeId.orEmpty(), game.name)
            XboxLibraryButton(game.storeId.orEmpty(), item)
        }
        BusyChip(s)
    }
}

/** Xbox Cloud Gaming rows the user added to the Library, for games not installed here. */
@Composable
internal fun rememberXboxCloudRows(s: FrontEndState): List<Library.SteamGame> {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { XboxCloudGames.load(ctx) }
    if (s.shortcutPicker) return emptyList()
    val added = XboxCloudGames.entries.toList()
    return remember(added, s.steamGames) {
        val installedXbox = s.steamGames.filter { it.source == Store.XBOX.id }.mapNotNull { it.storeId }.toSet()
        XboxCloudGames.rows(ctx, installedXbox)
    }
}

@Composable
private fun XboxStreamButton(id: String, title: String, main: Boolean = false, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { XboxCloudGames.prefetch(ctx) }
    val label = stringResource(R.string.game_stream)
    if (main) PrimaryButton(label, main = true, icon = Icons.Filled.PlayArrow, modifier = modifier) { XboxCloudGames.stream(ctx, id, title) }
    else SecondaryButton(label, modifier = modifier) { XboxCloudGames.stream(ctx, id, title) }
}

@Composable
private fun XboxInstalledStream(id: String, title: String, item: CatalogItem?) {
    val ctx = LocalContext.current
    val streams by produceState(item?.xboxCloud == true, id) {
        if (!value) value = withContext(Dispatchers.IO) { XboxCloudGames.streams(ctx, id) }
    }
    if (streams) XboxStreamButton(id, title)
}

@Composable
private fun XboxLibraryButton(id: String, item: CatalogItem?) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { XboxCloudGames.load(ctx) }
    // Add and Remove replace each other; the controller focus moves to the new button instead of getting lost.
    val slot = remember { FocusRequester() }
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    val scope = rememberCoroutineScope()
    fun refocus() { if (inputMode.inputMode == androidx.compose.ui.input.InputMode.Keyboard) scope.launch { focusSoon { slot } } }
    if (XboxCloudGames.isAdded(id)) {
        ActionChip(stringResource(R.string.library_in), ok = true)
        SecondaryButton(stringResource(R.string.library_remove), compact = true, modifier = Modifier.focusRequester(slot)) { XboxCloudGames.remove(ctx, id); refocus() }
    } else if (item != null) SecondaryButton(stringResource(R.string.library_add), modifier = Modifier.focusRequester(slot)) { XboxCloudGames.add(ctx, item); refocus() }
}

@Composable
private fun buyLabel(item: CatalogItem): String = when {
    item.isFree -> stringResource(R.string.stores_get_free)
    item.hasPrice && item.finalPrice.isNotBlank() -> stringResource(R.string.stores_buy_price, item.finalPrice)
    else -> stringResource(R.string.stores_view_on, item.store.shortLabel)
}

@Composable
private fun FileButtons(game: Library.SteamGame, a: FrontEndActions) {
    game.gameFiles?.takeIf { it.isDirectory }?.let { dir -> SecondaryButton(stringResource(R.string.games_files), compact = true) { a.onBrowseFiles(dir) } }
    game.protonPrefix?.takeIf { it.isDirectory }?.let { dir -> SecondaryButton(stringResource(R.string.games_prefix), compact = true) { a.onBrowseFiles(dir) } }
}

@Composable
private fun GameShortcutMenu(g: Library.SteamGame, a: FrontEndActions) {
    var open by remember(g.gameId) { mutableStateOf(false) }
    Box {
        SecondaryButton(stringResource(R.string.game_shortcut), compact = true) { open = !open }
        AnchoredMenu(open, onDismiss = { open = false }, title = stringResource(R.string.game_shortcut)) { first ->
            MenuItem(stringResource(R.string.game_shortcut_add), checked = false, focusRequester = first) { open = false; a.onGameShortcut(g) }
            MenuItem(stringResource(R.string.game_file_export), checked = false) { open = false; a.onExportGameFile(g) }
            MenuItem(stringResource(R.string.game_link_copy), checked = false) { open = false; a.onCopyGameLink(g) }
        }
    }
}

/** The pencil opens the editor each kind of game always had: the shortcut editor for added games, art for the rest. */
@Composable
private fun EditArtButton(g: Library.SteamGame) {
    val ctx = LocalContext.current
    val folder = g.gameFiles
    val open: (() -> Unit)? = when {
        g.library == Library.ADDED && g.source == Library.ADDED && folder != null -> {
            { GamesDialogState.editing = EditRequest(folder.path, openTarget = false, fromSummary = false, preview = EditPreview.of(ctx, g, folder.path)) }
        }
        g.library != Library.ADDED && g.source == Library.SOURCE_STEAM -> { { GamesDialogState.steamArt = g } }
        g.library == Library.ADDED && g.source != Library.ADDED && folder != null -> { { GamesDialogState.storeArt = g } }
        else -> null
    }
    if (open != null) IconAction(Icons.Outlined.Edit, stringResource(R.string.common_edit), "game:edit", onClick = open)
}

@Composable
private fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, id: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src) && enabled
    val accent = if (danger) colors.error else pal.signal
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.paneItem(id).downToFirstTile().size(44.dp).clip(Shape12)
            .background(if (hot) accent.copy(alpha = if (danger) 0.22f else 0.14f) else if (danger) Color.Black.copy(alpha = 0.34f) else Color.White.copy(alpha = 0.03f))
            .glideBorder(hot, Shape12, accent, pal.line2)
            .alpha(if (enabled) 1f else 0.45f)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = androidx.compose.ui.semantics.Role.Button, enabled = enabled, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Icon(icon, contentDescription = null, tint = if (danger && hot) colors.error else if (danger) Color.White else colors.onBackground, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RemoveButton(title: String, game: Library.SteamGame?, installed: InstalledStoreGame?, a: FrontEndActions) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    if ((installed?.sidecar?.store?.id ?: game?.source) == Store.BATTLENET.id) return
    if (installed != null) {
        val key = installed.key
        val gone = StoresState.uninstalling[key]
        if (gone != null) {
            val uninstalling = stringResource(R.string.stores_uninstalling)
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp).clip(Shape12).background(Color.Black.copy(alpha = 0.34f)).semantics { contentDescription = uninstalling }) {
                if (gone > 0f) CircularProgressIndicator(progress = { gone }, strokeWidth = 2.dp, color = colors.error, trackColor = colors.error.copy(alpha = 0.22f), modifier = Modifier.size(20.dp))
                else CircularProgressIndicator(strokeWidth = 2.dp, color = colors.error, modifier = Modifier.size(20.dp))
            }
            return
        }
        val heading = stringResource(R.string.stores_uninstall_title, title)
        val size = StoreSizes.onDisk[key]
        val body = if (size != null && size > 0) stringResource(R.string.stores_uninstall_body_size, formatBytes(size)) else stringResource(R.string.stores_uninstall_body)
        val keep = stringResource(R.string.stores_notification_keep)
        val remove = stringResource(R.string.stores_uninstall)
        val busy = StoresState.download(key)?.isActive == true || (installed.sidecar.store == Store.XBOX && XboxContent.busy(installed.sidecar.id))
        val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
        Box(Modifier.onGloballyPositioned { placed[0] = it }) {
            IconAction(Icons.Outlined.Delete, remove, "game:remove", danger = true, enabled = !busy) {
                val at = placed[0]?.takeIf { it.isAttached }?.boundsInRoot() ?: return@IconAction
                var confirmed = false
                Steps.ask(StepAsk(anchor = at, side = StepSide.Left, accent = colors.error, items = 3, pillCorner = 12.dp, maxWidth = 340.dp,
                    onDismiss = { if (confirmed) StoresState.uninstall(ctx, installed) { StoresState.refresh(ctx); a.onLibraryChanged() } },
                    handle = { Icon(Icons.Outlined.Delete, null, tint = colors.error, modifier = Modifier.size(18.dp)) }) {
                    StepTitle(heading)
                    Text(body, fontSize = 13.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant, modifier = Modifier.stepItem(1).padding(start = 4.dp, bottom = 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth().stepItem(2)) {
                        StepChoice(keep, enabled = open, modifier = Modifier.focusRequester(first)) { Steps.fold() }
                        StepChoice(remove, danger = true, enabled = open) { confirmed = true; Steps.fold() }
                    }
                })
            }
        }
        return
    }
    val g = game ?: return
    val kind = GameRemoval.kind(g)
    if (kind == GameRemoval.Kind.LAUNCHER || g.gameFiles?.isDirectory != true) return
    var asking by remember(g.gameId) { mutableStateOf(false) }
    val shortcut = kind == GameRemoval.Kind.SHORTCUT
    val label = stringResource(if (shortcut) R.string.edit_remove else R.string.common_uninstall)
    IconAction(Icons.Outlined.Delete, label, "game:remove", danger = true) { asking = true }
    if (asking) ConfirmDialog(
        title = if (shortcut) stringResource(R.string.games_remove_title, g.name) else stringResource(R.string.stores_uninstall_title, g.name),
        text = when {
            shortcut -> stringResource(R.string.games_remove_body)
            g.library == Library.ADDED -> stringResource(R.string.stores_uninstall_body)
            else -> stringResource(R.string.games_uninstall_body)
        },
        confirm = label,
        onConfirm = { a.onGameRemoved(g) },
        onDismiss = { asking = false },
    )
}

@Composable
private fun InstallButton(label: String, modifier: Modifier = Modifier, onInstall: () -> Unit) {
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    Box(Modifier.onGloballyPositioned { placed[0] = it }) {
        PrimaryButton(label, main = true, modifier = modifier) {
            StoresMotion.markInstall(placed[0]?.takeIf { it.isAttached }?.boundsInRoot(), label)
            onInstall()
        }
    }
}

@Composable
private fun AboutTab(developer: String, publisher: String, releaseDate: String, description: String, scores: GameScores?, stacked: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().tabPanel()) {
        if (scores != null) Box(Modifier.readable()) { StoreGameFacts(developer, publisher, releaseDate, scores, scoresInHero = !stacked) }
        else if (developer.isNotBlank() || publisher.isNotBlank() || releaseDate.isNotBlank()) {
            val parts = listOf(developer, publisher, com.droiddeck.launcher.stores.GameFacts.releaseLabel(releaseDate).orEmpty()).filter { it.isNotBlank() }.distinct()
            Text(parts.joinToString(" · "), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.readable())
        }
        if (description.isNotBlank()) {
            val floor = LocalDetailFloor.current
            val density = LocalDensity.current
            var room by remember { mutableStateOf(AboutHeight) }
            val fill = Modifier.onGloballyPositioned { c -> room = maxOf(AboutHeight, with(density) { (floor() - c.positionInRoot().y).toDp() - 14.dp }) }
            StoreAbout(description, room, Modifier.fillMaxWidth().then(fill))
        }
    }
}

private val AboutHeight = 260.dp

@Composable
internal fun Modifier.tabPanel(): Modifier {
    val shape = RoundedCornerShape(16.dp)
    return clip(shape).background(MaterialTheme.colorScheme.surface).border(1.dp, LocalPalette.current.line, shape).padding(horizontal = 16.dp, vertical = 14.dp)
}

@Composable
private fun StoreAbout(text: String, height: Dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.heightIn(max = height).clip(shape)
            .glideBorder(hot, shape, pal.signal, restColor = Color.Transparent)
            .paneItem("about")
            .focusable(interactionSource = src)
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
        Text(text, fontSize = 14.sp, lineHeight = 21.sp, color = colors.onSurfaceVariant, modifier = Modifier.verticalScroll(scroll).padding(horizontal = 4.dp, vertical = 6.dp))
        if (scroll.value < scroll.maxValue) Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, colors.surface))),
        )
    }
}

@Composable
private fun LaunchTab(game: Library.SteamGame, s: FrontEndState, a: FrontEndActions, hopRail: String?) {
    val host = rememberMenuHost()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        LaunchSettings(s, a, host, game, hopRail ?: GameRef.key(game))
        val folder = game.gameFiles
        if (game.library == Library.ADDED && game.source == Library.ADDED && !s.shortcutPicker && folder != null) ShortcutFields(game, folder)
    }
}

@Composable
private fun ShortcutFields(game: Library.SteamGame, folder: File) {
    val ctx = LocalContext.current
    val preview = remember(game.gameId, game.name, GamesDialogState.editing) { EditPreview.of(ctx, game, folder.path) }
    fun edit(target: Boolean) { GamesDialogState.editing = EditRequest(folder.path, openTarget = target, fromSummary = false, preview = EditPreview.of(ctx, game, folder.path)) }
    Column(Modifier.fillMaxWidth().tabPanel(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionLabel(stringResource(R.string.game_launch_shortcut))
        MenuItem(stringResource(R.string.edit_name), checked = false, detail = preview.name) { edit(false) }
        MenuItem(stringResource(R.string.edit_target), checked = false, detail = preview.exeName ?: "—") { edit(true) }
        MenuItem(stringResource(R.string.edit_start_in), checked = false, detail = preview.startIn ?: "—") { edit(false) }
        MenuItem(stringResource(R.string.edit_launch_options), checked = false, detail = preview.launchOptions.ifEmpty { "—" }) { edit(false) }
    }
}

@Composable
private fun SavesTab(g: Library.SteamGame, prefix: File, a: FrontEndActions) {
    val saves by produceState<List<GameSaves.SaveDir>?>(null, g.gameId) {
        value = withContext(Dispatchers.IO) { runCatching { GameSaves.locate(prefix, g) }.getOrDefault(emptyList()) }
    }
    val found = saves
    val summary = when {
        found == null -> stringResource(R.string.games_saves_looking)
        found.isEmpty() -> stringResource(R.string.games_saves_none)
        else -> {
            val mb = found.sumOf { it.bytes } / 1048576.0
            stringResource(
                R.string.games_saves_summary,
                if (found.size == 1) found[0].relPath else stringResource(R.string.games_saves_folders, found.size),
                found.sumOf { it.files },
                if (mb < 1) stringResource(R.string.games_kb, (mb * 1024).toInt()) else stringResource(R.string.common_size_mb, mb),
            )
        }
    }
    Column(Modifier.fillMaxWidth().tabPanel(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp).readable().padding(4.dp))
        if (Store.byId(g.source).let { it == Store.GOG || it == Store.EPIC }) {
            SectionLabel(stringResource(R.string.store_cloud_saves))
            CloudRows(g)
            SectionLabel(stringResource(R.string.games_saves))
        }
        MenuItem(stringResource(R.string.games_import_saves), checked = false) { a.onSaveImport(g) }
        MenuItem(stringResource(R.string.games_export_gamehub), checked = false) { a.onSaveExport(g, GameSaves.Layout.GAMEHUB) }
        MenuItem(stringResource(R.string.games_export_winlator), checked = false) { a.onSaveExport(g, GameSaves.Layout.WINLATOR) }
        found?.firstOrNull()?.let { d ->
            MenuItem(stringResource(R.string.games_open_save_folder), checked = false, detail = d.relPath) {
                a.onBrowseFiles(File(prefix, "drive_c/users/steamuser/" + d.relPath))
            }
        }
    }
}

@Composable
private fun CloudRows(g: Library.SteamGame) {
    val store = Store.byId(g.source)?.takeIf { it != Store.AMAZON } ?: return
    val id = g.storeId ?: return
    val context = LocalContext.current.applicationContext
    var busy by remember(g.gameId) { mutableStateOf<String?>(null) }
    var tick by remember(g.gameId) { mutableStateOf(0) }
    val status by produceState<CloudSaves.Status?>(null, g.gameId, tick) {
        value = withContext(Dispatchers.IO) { runCatching { CloudSaves.status(context, store, id) }.getOrNull() }
    }
    val folder = g.gameFiles
    var enabled by remember(g.gameId) { mutableStateOf(folder?.let { StoreGameSidecar.read(it)?.cloud } ?: true) }
    val st = status
    val supported = st?.supported ?: true
    val conflict = st != null && st.conflicts.isNotEmpty()
    fun sync(up: Boolean, force: Boolean = false) {
        if (busy != null) return
        busy = if (up) "up" else "down"
        Thread({
            val r = if (up) CloudSaves.upload(context, store, id, force) else CloudSaves.download(context, store, id, force)
            StoresState.post { busy = null; tick++; android.widget.Toast.makeText(context, cloudMessage(context, r), android.widget.Toast.LENGTH_SHORT).show() }
        }, "cloud-manual").start()
    }
    MenuItem(stringResource(R.string.store_cloud_saves), checked = enabled && supported, enabled = supported,
        detail = if (!supported) stringResource(R.string.cloud_none) else null) {
        val f = folder ?: return@MenuItem
        val next = !enabled
        Thread({
            val written = runCatching { StoreGameSidecar.updateCloud(f, next) }.getOrNull()
            StoresState.post { enabled = written?.cloud ?: enabled }
        }, "cloud-switch").start()
    }
    val ready = st != null && supported && enabled && busy == null
    val pending = stringResource(R.string.cloud_pending)
    val summary = when {
        st == null || busy != null -> pending
        !supported -> stringResource(R.string.cloud_none)
        conflict -> stringResource(R.string.cloud_conflict_files, st.conflicts.size)
        st.lastSync > 0 -> stringResource(R.string.cloud_last_sync, android.text.format.DateUtils.getRelativeTimeSpanString(st.lastSync).toString())
        else -> stringResource(R.string.cloud_never)
    }
    MenuItem(stringResource(if (conflict) R.string.cloud_keep_local else R.string.cloud_upload), checked = false, enabled = ready, detail = summary) { sync(up = true, force = conflict) }
    MenuItem(stringResource(if (conflict) R.string.cloud_keep_cloud else R.string.cloud_download), checked = false, enabled = ready, detail = if (st == null || busy != null) pending else null) { sync(up = false, force = conflict) }
}

@Composable
private fun InstallTab(game: Library.SteamGame?, installed: InstalledStoreGame?, item: CatalogItem?, store: Store?) {
    val ctx = LocalContext.current
    val folder = installed?.folder ?: game?.gameFiles
    val sidecar = installed?.sidecar
    val size = folder?.let { diskSize(installed, it) }
    val rows = buildList {
        add(stringResource(R.string.game_install_location) to when {
            installed != null -> StoreInstallRoot.labelFor(ctx, installed.folder)
            game != null -> libraryLabel(game).ifBlank { stringResource(R.string.games_internal) }
            else -> ""
        })
        if (folder != null) {
            add(stringResource(R.string.game_install_folder) to folder.path)
            add(stringResource(R.string.game_install_size) to (size?.let { formatBytes(it) } ?: stringResource(R.string.cloud_pending)))
        }
        sidecar?.extra?.get(StoreLanguage.EXTRA)?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.stores_install_language) to StoreLanguage.displayName(it)) }
        sidecar?.installVersion?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.game_install_version) to it) }
        sidecar?.installedAt?.takeIf { it > 0 }?.let {
            add(stringResource(R.string.game_install_date) to java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it)))
        }
        if (game != null) add(stringResource(R.string.game_install_played) to (playedSpan(game.lastPlayed)?.replaceFirstChar { it.uppercase() } ?: stringResource(R.string.games_never_played)))
        if (store == Store.XBOX) {
            val access = sidecar?.extra?.get("access") ?: if (item?.subscription == true) "gamepass" else if (item?.owned == true) "owned" else null
            if (access != null) add(stringResource(R.string.game_install_access) to stringResource(if (access == "gamepass") R.string.stores_game_pass else R.string.game_install_access_owned))
        }
    }.filter { it.second.isNotBlank() }
    Column(Modifier.fillMaxWidth().tabPanel(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((label, value) in rows) FactRow(label, value)
    }
}

/** Text a controller has to land on to bring it into view: focusable, with the focus ring. */
@Composable
internal fun Modifier.readable(): Modifier {
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(8.dp)
    return clip(shape).glideBorder(hot, shape, pal.signal, restColor = Color.Transparent).focusable(interactionSource = src)
}

@Composable
private fun FactRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().readable().padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(150.dp))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Store installs are measured by [StoreSizes]; Steam and added games by walking their folder once. */
@Composable
internal fun diskSize(installed: InstalledStoreGame?, folder: File): Long? {
    val ctx = LocalContext.current
    if (installed != null) {
        LaunchedEffect(installed.key) { StoreSizes.requestDisk(ctx, installed.key, installed) }
        return StoreSizes.onDisk[installed.key]
    }
    LaunchedEffect(folder.path) { FolderSizes.request(folder) }
    return FolderSizes.known[folder.path]
}

internal object FolderSizes {
    val known = androidx.compose.runtime.mutableStateMapOf<String, Long>()
    private val asked = java.util.Collections.synchronizedSet(HashSet<String>())

    fun request(folder: File) {
        if (!asked.add(folder.path)) return
        Thread({
            var total = 0L
            val walked = runCatching {
                java.nio.file.Files.walkFileTree(folder.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                        if (attrs.isRegularFile) total += attrs.size()
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(file: java.nio.file.Path, exc: java.io.IOException): java.nio.file.FileVisitResult = java.nio.file.FileVisitResult.CONTINUE
                })
            }.isSuccess
            if (walked) StoresState.post { known[folder.path] = total } else asked.remove(folder.path)
        }, "folder-size").start()
    }
}
