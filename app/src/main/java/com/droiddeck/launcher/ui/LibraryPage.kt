package com.droiddeck.launcher.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.frontend.Soundtrack
import com.droiddeck.launcher.frontend.SoundtrackCollections
import com.droiddeck.launcher.frontend.SoundtrackIndex
import com.droiddeck.launcher.frontend.SoundtrackTracks
import com.droiddeck.launcher.playback.SoundtrackController
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreSizes
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.arrangeStoreTabs
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadStage
import com.droiddeck.launcher.stores.download.DownloadState
import com.droiddeck.launcher.stores.formatBytes
import com.droiddeck.launcher.stores.gamejolt.GameJoltCollections
import com.droiddeck.launcher.stores.gamejolt.GameJoltUpdates
import com.droiddeck.launcher.stores.itch.ItchCollections
import com.droiddeck.launcher.stores.itch.ItchUpdates
import com.droiddeck.launcher.stores.gog.GogMaintenance
import com.droiddeck.launcher.stores.retainOrder
import com.droiddeck.launcher.stores.xbox.XboxLibraryFilter
import com.droiddeck.launcher.stores.entryTime
import com.droiddeck.launcher.stores.sortGames
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue

internal class LibraryEntry(
    val key: String, val source: String, val title: String,
    val game: Library.SteamGame?, val item: CatalogItem?, val installed: Boolean,
) {
    val storeKey: String? get() = item?.key ?: game?.storeId?.let { "${game.source}:$it" }
    val favoriteKey: String get() = storeKey ?: key
}

internal fun libraryEntries(
    games: List<Library.SteamGame>, owned: Map<Store, List<CatalogItem>>, installedStore: Set<String>,
): List<LibraryEntry> {
    val claimed = HashSet<String>()
    val out = ArrayList<LibraryEntry>()
    for (g in games.sortedBy { it.library == Library.XBOX_CLOUD }) {
        if (g.library == Library.EPIC_LAUNCHER) continue
        val storeKey = g.storeId?.let { "${g.source}:$it" }
        val item = storeKey?.let { k -> Store.byId(g.source)?.let { st -> owned[st]?.firstOrNull { it.key == k } } }
        if (storeKey != null) {
            if (g.library == Library.XBOX_CLOUD && storeKey in claimed) continue
            claimed.add(storeKey)
        }
        out += LibraryEntry(GameRef.key(g), g.source, g.name, g, item,
            installed = g.library != Library.XBOX_CLOUD || storeKey in installedStore)
    }
    for (store in Store.entries) for (item in owned[store].orEmpty()) {
        if (!claimed.add(item.key)) continue
        out += LibraryEntry(GameRef.key(item), store.id, item.title, null, item, installed = item.key in installedStore)
    }
    return out.distinctBy { it.key }
}

internal fun collectionEntries(all: List<LibraryEntry>, items: List<CatalogItem>, store: Store = Store.ITCH): List<LibraryEntry> {
    val keys = items.map { it.key }.toSet()
    val own = all.filter { it.source == store.id }
    val have = own.mapNotNull { it.storeKey }.toSet()
    return own.filter { it.storeKey in keys } +
        items.filter { it.key !in have }.map { LibraryEntry(GameRef.key(it), store.id, it.title, null, it, false) }
}

internal fun installedFirst(entries: List<LibraryEntry>): List<LibraryEntry> = entries.partition { it.installed }.let { (on, off) -> on + off }

internal const val LIBRARY_ALL = "all"
internal fun librarySources(
    entries: List<LibraryEntry>, order: List<String>, always: Set<String> = emptySet(), favorites: Set<String> = emptySet(),
    hidden: (LibraryEntry) -> Boolean = { false }, offStores: Set<String> = emptySet(),
    places: List<Pair<String, String?>> = DEFAULT_LIBRARY_PLACES, offTabs: Set<String> = setOf(LIBRARY_HIDDEN),
): List<String> {
    val tabs = arrangeStoreTabs(order, offStores)
    val shown = tabs.filter { it.shown }.map { it.store.id }.toSet() + LIBRARY_OWN_TABS - offTabs
    return (placeLibraryTabs(tabs.map { it.store.id }, places).filter { it in shown } + Library.SOURCE_COLLECTION)
        .filter { source ->
            when (source) {
                LIBRARY_HIDDEN -> true
                LIBRARY_FAVORITES -> entries.any { it.favoriteKey in favorites && !hidden(it) }
                else -> source in always || entries.any { it.source == source && !hidden(it) }
            }
        }
}

internal object LibraryDisplay {
    const val DEFAULT_LEVEL = 2
    val scales = listOf(0.75f, 0.875f, 1f, 1.2f, 1.45f)
    var level by mutableIntStateOf(DEFAULT_LEVEL)
        private set
    var title by mutableStateOf(true)
        private set
    var size by mutableStateOf(true)
        private set

    fun load(context: android.content.Context) {
        level = SessionPrefs.libraryCardLevel(context).coerceIn(0, scales.lastIndex)
        title = SessionPrefs.libraryShowTitle(context)
        size = SessionPrefs.libraryShowSize(context)
    }

    fun step(context: android.content.Context, by: Int) {
        level = (level + by).coerceIn(0, scales.lastIndex)
        SessionPrefs.setLibraryCardLevel(context, level)
    }

    fun toggleTitle(context: android.content.Context) {
        title = !title
        SessionPrefs.setLibraryShowTitle(context, title)
    }

    fun toggleSize(context: android.content.Context) {
        size = !size
        SessionPrefs.setLibraryShowSize(context, size)
    }
}

private enum class ToggleAt { NONE, TOP, SPLIT, BOTTOM }

private const val NOT_INSTALLED_ROW = "library-not-installed"

@Composable
private fun NotInstalledRow(count: Int, expanded: Boolean, keys: Modifier, onFocus: (Boolean) -> Unit, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp).then(keys).paneItem(NOT_INSTALLED_ROW).onFocusChanged { onFocus(it.isFocused) }.testTag(NOT_INSTALLED_ROW)
            .heightIn(min = 44.dp).clip(shape)
            .background(if (hot) pal.signal.copy(alpha = 0.12f) else Color.Transparent)
            .glideBorder(hot, shape, pal.signal, Color.Transparent)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onToggle)
            .controllerConfirm(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Text(
            stringResource(R.string.stores_section_not_installed).uppercase() + "  ·  " + count,
            fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = if (hot) colors.onBackground else colors.onSurfaceVariant, maxLines = 1,
        )
        Box(Modifier.weight(1f).height(1.dp).background(pal.line))
        val hop = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(expanded, hot, Motion.scale) {
            hop.snapTo(0f)
            if (Motion.scale == 0f) return@LaunchedEffect
            val toward = if (expanded) -3.5f else 3.5f
            kotlinx.coroutines.delay(if (hot) 0L else 1_200L)
            while (true) {
                repeat(2) {
                    hop.animateTo(toward, androidx.compose.animation.core.tween(150, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                    hop.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = androidx.compose.animation.core.Spring.StiffnessMedium))
                }
                kotlinx.coroutines.delay(3_500L)
            }
        }
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, stringResource(if (expanded) R.string.library_filter_installed else R.string.library_browse_owned),
            tint = if (hot) colors.onBackground else colors.onSurfaceVariant,
            modifier = Modifier.size(22.dp).graphicsLayer { translationY = hop.value.dp.toPx() },
        )
    }
}

internal fun libraryColumns(width: Int, base: Int, gap: Int, level: Int): Int =
    (((width + gap) / (base + gap)).coerceAtLeast(1) + LibraryDisplay.DEFAULT_LEVEL - level).coerceAtLeast(1)

private class LevelCells(private val base: androidx.compose.ui.unit.Dp, private val level: Int) : GridCells {
    override fun androidx.compose.ui.unit.Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val count = libraryColumns(availableSize, base.roundToPx(), spacing, level)
        val usable = (availableSize - spacing * (count - 1)).coerceAtLeast(0)
        return List(count) { usable / count + if (it < usable % count) 1 else 0 }
    }

    override fun equals(other: Any?): Boolean = other is LevelCells && other.base == base && other.level == level
    override fun hashCode(): Int = 31 * base.hashCode() + level
}

internal object LibraryView {
    var source by mutableStateOf(LIBRARY_ALL)
    var query by mutableStateOf("")
    var focusFirst = false
    var ownedRequest by mutableIntStateOf(0)
        private set

    fun showOwned(context: android.content.Context, store: Store) {
        SessionPrefs.setLibraryShowUninstalled(context, true)
        ownedRequest++
        source = store.id
        query = ""
        focusFirst = true
    }
}

@Composable
internal fun LibraryPage(s: FrontEndState, a: FrontEndActions, selected: String, onSelect: (String) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val narrow = LocalNarrowPane.current
    val ff = LocalFrontFocus.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    LaunchedEffect(Unit) { StoresState.refresh(ctx) }
    remember { LibraryDisplay.load(ctx) }
    remember { FolderSizes.load(ctx) }
    remember { LibraryFavorites.load(ctx) }
    rememberHiddenSync()
    val soundMode = selected == "soundtracks" || selected.startsWith("sound:")
    val favorites = if (soundMode) emptySet() else LibraryFavorites.keys
    val concealed: (LibraryEntry) -> Boolean = { !soundMode && LibraryHidden.isHidden(it) }
    val stores = !s.shortcutPicker && !soundMode
    val preview = if (stores) DevPreview.library else LibraryPreview.OFF
    val signedIn = when (preview) {
        LibraryPreview.SAMPLE -> Store.entries.filter { StoresState.backend(it) != null }
        LibraryPreview.SIGNED_OUT -> emptyList()
        else -> Store.entries.filter { StoresState.isSignedIn(it) }
    }
    LaunchedEffect(stores, signedIn, preview) {
        if (stores) signedIn.forEach { if (preview == LibraryPreview.SAMPLE) StoresState.preview(ctx, it) else StoresState.loadLibrary(ctx, it) }
    }
    val owned = when {
        !stores -> emptyMap()
        preview == LibraryPreview.SAMPLE -> signedIn.associateWith { sampleOwned(it) }
        else -> signedIn.associateWith { StoresState.library[it].orEmpty() }
    }
    val reallyInstalled = StoresState.installed.map { it.key }.toSet()
    val installedStore = reallyInstalled + if (preview == LibraryPreview.SAMPLE) sampleInstalled(owned) else emptySet()
    val syncing = if (stores && preview == LibraryPreview.OFF) signedIn.mapNotNull { st -> StoresState.status[st]?.let { st to it } } else emptyList()
    val cloudRows = rememberXboxCloudRows(s)
    remember { SoundtrackCollections.load(ctx) }
    val savedSnapshot = SoundtrackCollections.snapshot
    val favoritesLabel = stringResource(R.string.soundtracks_favorites)
    val all = remember(soundMode, s.steamGames, s.soundtracks, s.soundtracksEnabled, cloudRows, owned, installedStore, savedSnapshot, favoritesLabel, preview) {
        if (soundMode) s.soundtracks.map { LibraryEntry("sound:${it.appId}", it.source, it.name, it, null, true) } +
            (if (s.soundtracks.isEmpty() && savedSnapshot.favorites.isEmpty() && savedSnapshot.collections.isEmpty()) emptyList() else SoundtrackCollections.games(ctx, favoritesLabel, skipEmptyFavorites = true))
                .map { LibraryEntry("sound:${it.appId}", it.source, it.name, it, null, true) }
        else libraryEntries(s.steamGames + cloudRows + if (s.soundtracksEnabled) emptyList() else s.soundtracks.filter { it.library != Library.ADDED }, owned, installedStore)
            .let { rows -> if (preview == LibraryPreview.NOTHING_INSTALLED) rows.map { LibraryEntry(it.key, it.source, it.title, it.game, it.item, false) } else rows }
    }
    var addingSoundtrack by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { XboxLibraryFilter.load(ctx) }
    var showUninstalled by remember(LibraryView.ownedRequest) { mutableStateOf(SessionPrefs.libraryShowUninstalled(ctx)) }
    var collectionShows by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val setShowUninstalled = { show: Boolean ->
        val picked = when (LibraryView.source) {
            Store.ITCH.id -> ItchCollections.selected
            Store.GAMEJOLT.id -> GameJoltCollections.selected
            else -> null
        }
        if (picked != null) collectionShows = picked to show
        else {
            showUninstalled = show
            SessionPrefs.setLibraryShowUninstalled(ctx, show)
        }
    }
    val itchSignedIn = owned.containsKey(Store.ITCH)
    val gameJoltSignedIn = owned.containsKey(Store.GAMEJOLT)
    remember { StoreTabsState.load(ctx) }
    val storeTabs = StoreTabsState.tabs
    val signInChips = if (!stores || !StoresState.accountsLoaded || preview == LibraryPreview.SAMPLE) emptySet() else storeTabs
        .filter { it.shown && StoresState.backend(it.store) != null && (preview == LibraryPreview.SIGNED_OUT || storeAccount(it.store) != StoreAccount.SIGNED_IN) }
        .map { it.store.id }.toSet()
    val sources = librarySources(
        all, storeTabs.map { it.store.id },
        listOfNotNull(Store.ITCH.id.takeIf { itchSignedIn && !soundMode }, Store.GAMEJOLT.id.takeIf { gameJoltSignedIn && !soundMode }, Library.ADDED.takeIf { stores }, Store.BATTLENET.id.takeIf { stores }).toSet() + signInChips,
        favorites, concealed,
        offStores = if (soundMode) emptySet() else storeTabs.filterNot { it.shown }.map { it.store.id }.toSet(),
        places = if (soundMode) DEFAULT_LIBRARY_PLACES else StoreTabsState.libraryPlaces,
        offTabs = if (soundMode) setOf(LIBRARY_HIDDEN) else StoreTabsState.libraryHidden,
    )
    val source = LibraryView.source.takeIf { it == LIBRARY_ALL || it in sources } ?: LIBRARY_ALL
    val q = LibraryView.query.trim().lowercase()
    val xboxFilter = if (source == Store.XBOX.id) XboxLibraryFilter.current else XboxLibraryFilter.ALL
    LaunchedEffect(source, itchSignedIn, gameJoltSignedIn) {
        if (source == Store.ITCH.id && itchSignedIn) {
            ItchCollections.load(ctx)
            ItchUpdates.check(ctx)
        }
        if (source == Store.GAMEJOLT.id && gameJoltSignedIn) {
            GameJoltCollections.load(ctx)
            GameJoltUpdates.check(ctx)
        }
    }
    val collectionId = when (source) {
        Store.ITCH.id -> ItchCollections.chosen()?.id
        Store.GAMEJOLT.id -> GameJoltCollections.chosen()?.id
        else -> null
    }
    val collectionItems = collectionId?.let { if (source == Store.GAMEJOLT.id) GameJoltCollections.gamesOf(it) else ItchCollections.gamesOf(it) }
    val pool = if (collectionId == null) all else collectionEntries(all, collectionItems.orEmpty(), Store.byId(source) ?: Store.ITCH)
    val matching = pool.filter {
        inLibrarySource(source, it, favorites, concealed) && (q.isEmpty() || it.title.lowercase().contains(q)) && keepsXbox(xboxFilter, it)
    }
    val listUninstalled = if (collectionId == null) showUninstalled else collectionShows?.takeIf { it.first == collectionId }?.second ?: true
    val trackHits by produceState<List<SoundtrackIndex.Hit>>(emptyList(), soundMode, q, source, s.soundtracks) {
        if (!soundMode || q.isEmpty()) {
            value = emptyList()
            return@produceState
        }
        kotlinx.coroutines.delay(150)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            SoundtrackIndex.build(ctx, s.soundtracks)
                .filter { (source == LIBRARY_ALL || it.game.source == source) && SoundtrackTracks.matches(q, it.ref.title, it.ref.artist, it.ref.album) }
                .distinctBy { it.ref.path }
                .sortedWith(compareBy({ SoundtrackTracks.rank(q, it.ref.title) }, { it.ref.title.lowercase() }))
                .take(100)
        }
    }
    val shownHits = trackHits.filter { source == LIBRARY_ALL || it.game.source == source }
    val hitScope = rememberCoroutineScope()
    val playHit: (SoundtrackIndex.Hit) -> Unit = { hit ->
        val dir = hit.game.gameFiles
        if (dir != null) hitScope.launch {
            val album = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Soundtrack.load(ctx, dir, hit.game.art) }
            SoundtrackController.play(ctx, album, hit.game.name, hit.ref.path, root = dir)
        }
    }
    var savedDialog by remember { mutableStateOf(false) }
    val candidates = if (listUninstalled || source == LIBRARY_HIDDEN) matching else matching.filter { it.installed }
    val hiddenUninstalled = if (listUninstalled || source == LIBRARY_HIDDEN) 0 else matching.count { !it.installed }
    val sort = s.storesSort
    val sizeSort = sort == "sizeAsc" || sort == "sizeDesc"
    val installedAt = StoresState.installed.associate { it.key to it.sidecar.installedAt }
    val lastPlayed = s.steamGames.filter { it.storeId != null }.associate { "${it.source}:${it.storeId}" to it.lastPlayed }
    fun sizeOf(e: LibraryEntry): Long? {
        val key = e.storeKey
        return when {
            key != null && e.installed && key in installedStore -> StoreSizes.onDisk[key]
            e.game?.gameFiles != null -> FolderSizes.known[e.game.gameFiles.path]
            e.item != null -> StoreSizes.size(e.item).takeIf { it > 0 }
            else -> 0L
        }
    }
    LaunchedEffect(sizeSort, candidates.map { it.key }) {
        if (!sizeSort) return@LaunchedEffect
        for (e in candidates) {
            val key = e.storeKey
            val inst = key?.let { k -> StoresState.installed.firstOrNull { it.key == k } }
            when {
                inst != null -> StoreSizes.requestDisk(ctx, inst.key, inst)
                e.game?.gameFiles != null -> FolderSizes.request(e.game.gameFiles)
                e.item != null && e.item.accessible -> StoreSizes.request(ctx, e.item)
            }
        }
    }
    var sizesWaited by remember(sizeSort) { mutableStateOf(false) }
    LaunchedEffect(sizeSort) { if (sizeSort) { kotlinx.coroutines.delay(8_000); sizesWaited = true } }
    fun sizeComing(e: LibraryEntry): Boolean = when {
        e.storeKey != null && e.storeKey in installedStore -> e.storeKey in reallyInstalled // a sample install has no size to wait for
        e.game?.gameFiles != null -> true
        e.item != null -> StoreSizes.coming(e.item)
        else -> false
    }
    val sizesPending = sizeSort && !sizesWaited && candidates.any { sizeOf(it) == null && sizeComing(it) }
    val sorted = sortGames(
        candidates, sort, title = { it.title }, releaseDate = { it.item?.releaseDate.orEmpty() },
        lastPlayed = { e -> entryTime(e.game?.lastPlayed, e.storeKey?.let { lastPlayed[it] }) },
        installedAt = { e -> entryTime(e.storeKey?.let { installedAt[it] }, e.game?.installedAt) },
        size = { e -> sizeOf(e) ?: 0L },
    )
    var stable by remember { mutableStateOf(emptyList<LibraryEntry>()) }
    val ordered = if (sizesPending) retainOrder(candidates, stable) { it.key } else sorted
    SideEffect { if (!sizesPending) stable = sorted }
    val visible = installedFirst(ordered).let { list ->
        if (soundMode && source == Library.SOURCE_COLLECTION) list.sortedBy { if (it.game?.appId == SoundtrackCollections.FAVORITES_ID) 0 else 1 } else list
    }
    val split = visible.count { it.installed }.takeIf { it in 1 until visible.size } ?: 0

    val open = selected.takeIf { it.startsWith(if (soundMode) "sound:" else "app:") }
    val back = { onSelect(if (soundMode) "soundtracks" else "games") }
    BackHandler(enabled = open == null && LibraryView.query.isNotEmpty()) { LibraryView.query = "" }
    BackHandler(enabled = open != null) { back() }
    val lead = remember { FocusRequester() }
    var detailFocused by remember { mutableStateOf(false) }
    var lastOpened by remember { mutableStateOf<String?>(null) }
    val wasOpen = remember { booleanArrayOf(false) }
    val backLabel = stringResource(if (soundMode) R.string.rail_soundtracks else R.string.rail_games)
    LaunchedEffect(open) {
        val closing = open == null && wasOpen[0]
        wasOpen[0] = open != null
        if (open != null) lastOpened = open
        else if (!closing) return@LaunchedEffect
        if (inputMode.inputMode != androidx.compose.ui.input.InputMode.Keyboard || ff == null) return@LaunchedEffect
        repeat(60) { if (!HeldKeys.confirm) return@repeat; androidx.compose.runtime.withFrameNanos { } }
        if (open != null) {
            val backId = "back:$backLabel"
            repeat(45) { n ->
                androidx.compose.runtime.withFrameNanos { }
                if (detailFocused) return@LaunchedEffect
                val req = if (n < 20) lead else ff.items[backId]?.takeIf { (ff.attached[backId] ?: 0) > 0 } ?: lead
                runCatching { req.requestFocus() }
            }
            return@LaunchedEffect
        }
        val id = lastOpened?.let { "card:$it" }
        repeat(45) {
            androidx.compose.runtime.withFrameNanos { }
            if (id != null && ff.last == id) return@LaunchedEffect
            val card = id?.let { ff.items[it] }?.takeIf { (ff.attached[id] ?: 0) > 0 }
            if (card != null) runCatching { card.requestFocus() }
            else if (ff.firstTileAttached > 0 && runCatching { ff.firstTile.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    LaunchedEffect(Unit) {
        if (!LibraryView.focusFirst || ff == null) return@LaunchedEffect
        LibraryView.focusFirst = false
        if (inputMode.inputMode != androidx.compose.ui.input.InputMode.Keyboard) return@LaunchedEffect
        kotlinx.coroutines.delay(Motion.ms(240).toLong())
        focusWithinFrames({ ff.last?.startsWith("card:") == true }) { if (ff.firstTileAttached > 0) ff.firstTile else FocusRequester.Default }
    }
    if (!soundMode && s.shortcutPicker && all.isEmpty() && open == null && !itchSignedIn && !gameJoltSignedIn) {
        LibraryEmpty(s, a, modifier)
        return
    }
    val grid = remember { LazyGridState() }
    val detailScroll = rememberScrollState()
    var cycled by remember { mutableIntStateOf(0) }
    var gridFocused by remember { mutableStateOf(false) }
    LaunchedEffect(source, sort) { grid.scrollToItem(0) }
    LaunchedEffect(cycled) {
        if (cycled == 0 || ff == null || inputMode.inputMode != androidx.compose.ui.input.InputMode.Keyboard) return@LaunchedEffect
        focusWithinFrames({ gridFocused }) {
            if (ff.firstTileAttached > 0) ff.firstTile else ff.items["libchip:$source"] ?: FocusRequester.Default
        }
    }
    CardDetailHost(
        open, detailScroll,
        gridScrolled = { grid.firstVisibleItemIndex > 0 || grid.firstVisibleItemScrollOffset > 0 },
        onDetailFocus = { detailFocused = it },
        modifier = modifier.padding(horizontal = if (narrow) 14.dp else 22.dp).padding(top = if (narrow) 10.dp else 14.dp),
        bleedHorizontal = if (narrow) 14.dp else 22.dp, bleedTop = if (narrow) 10.dp else 14.dp,
        wrap = { key, content -> StoreTheme(GameRef.parse(key).let { r -> r.store ?: r.game(s.steamGames)?.source?.let { Store.byId(it) } }) { content() } },
        grid = {
            StoreTheme(Store.byId(source)) { LibraryGrid(
                s, a, grid, visible, split, visible.size, sources, source, sizesPending, syncing,
                showUninstalled = listUninstalled.takeIf { !s.shortcutPicker },onShowUninstalled = setShowUninstalled,
                hiddenUninstalled = hiddenUninstalled,
                onSource = { LibraryView.source = it },
                onCycle = { step ->
                    val ids = listOf(LIBRARY_ALL) + sources
                    LibraryView.source = ids[(ids.indexOf(source).coerceAtLeast(0) + step + ids.size) % ids.size]
                    cycled++
                },
                onOpen = {
                    // A preview is read-only: a game's page would act on the real install behind the pretend one.
                    if (preview != LibraryPreview.OFF) android.widget.Toast.makeText(ctx, R.string.dev_preview_read_only, android.widget.Toast.LENGTH_SHORT).show()
                    else { focusManager.clearFocus(force = true); onSelect(it) }
                },
                searchCleared = open != null,
                onFocus = { gridFocused = it },
                soundMode = soundMode,
                wallArt = { wallTiles(all.filterNot(concealed).mapNotNull { entryArt(it, tall = true) }) },
                emptyText = when {
                    soundMode && all.isEmpty() -> R.string.soundtracks_empty
                    !soundMode && all.isEmpty() && source == LIBRARY_ALL -> R.string.games_empty
                    soundMode && source == Library.SOURCE_COLLECTION && q.isEmpty() -> R.string.soundtracks_collections_empty
                    else -> R.string.stores_nothing_matches
                },
                onAddSoundtrack = { addingSoundtrack = true },
                onCollections = { savedDialog = true },
                trackHits = shownHits, onPlayHit = playHit,
                // An expired account keeps showing its cached games; with none cached it asks to sign in again, like a signed-out store.
                signInStore = Store.byId(source)?.takeIf { st -> st.id in signInChips && (preview == LibraryPreview.SIGNED_OUT || storeAccount(st) != StoreAccount.EXPIRED || all.none { it.source == st.id }) },
            ) }
        },
    ) { key ->
        if (soundMode || (!s.soundtracksEnabled && s.soundtracks.any { GameRef.key(it) == key })) SoundtrackDetail(key, s, a, lead, backLabel, onBack = back)
        else GameDetail(key, s, a, lead, backLabel, hopRail = "games", onBack = back)
    }
    if (savedDialog) SavedDialog(emptyList(), onOpen = { id -> onSelect("sound:$id") }) { savedDialog = false }
    if (addingSoundtrack) SyncFolderPicker(stringResource(R.string.soundtracks_add_title), null, onDismiss = { addingSoundtrack = false }) { dir ->
        addingSoundtrack = false
        a.onSoundtrackAdded(dir)
    }
}

@Composable
private fun LibraryEmpty(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    val narrow = LocalNarrowPane.current
    Column(modifier = modifier.padding(horizontal = if (narrow) 16.dp else 22.dp, vertical = if (narrow) 12.dp else 18.dp)) {
        Rise(0) {
            PageHeader(stringResource(R.string.rail_games)) {
                if (s.steamGamesX64) ClientTag()
                if (!s.shortcutPicker) {
                    AddGameButton { GamesDialogState.adding = true }
                    ManageStoresChip()
                }
            }
        }
        Rise(1) { Note(stringResource(if (s.shortcutPicker && s.shortcutLibraryScanning) R.string.game_shortcut_scanning else R.string.games_empty)) }
        if (!s.shortcutPicker) Rise(2) {
            Actions {
                PrimaryButton(stringResource(R.string.games_play_steam), enabled = !s.busy, main = true, icon = Icons.Filled.PlayArrow, modifier = Modifier.padding(top = 12.dp).testTag("play-steam"), onClick = a.onPlay)
            }
        }
        Box(Modifier.padding(top = 12.dp)) { GameFileFolderActions(s, a, labeled = true) }
    }
}

@Composable
private fun LibraryGrid(
    s: FrontEndState, a: FrontEndActions, grid: LazyGridState, visible: List<LibraryEntry>, split: Int, total: Int,
    sources: List<String>, source: String, sizesPending: Boolean, syncing: List<Pair<Store, String>>,
    showUninstalled: Boolean?, onShowUninstalled: (Boolean) -> Unit, hiddenUninstalled: Int,
    onSource: (String) -> Unit, onCycle: (Int) -> Unit, onOpen: (String) -> Unit, onFocus: (Boolean) -> Unit, searchCleared: Boolean,
    soundMode: Boolean = false, emptyText: Int = R.string.stores_nothing_matches, onAddSoundtrack: () -> Unit = {},
    onCollections: () -> Unit = {}, trackHits: List<SoundtrackIndex.Hit> = emptyList(), onPlayHit: (SoundtrackIndex.Hit) -> Unit = {},
    signInStore: Store? = null, wallArt: () -> List<Any> = { emptyList() },
) {
    val listMode = s.storesView == "list"
    val capsules = s.storesView == "capsules"
    val baseWidth = if (capsules) CapsuleWidth else LibraryCardWidth
    val level = LibraryDisplay.level
    val focus = LocalFrontFocus.current
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    var columns by remember { mutableIntStateOf(1) }
    var gridH by remember { mutableIntStateOf(0) }
    var chipsH by remember { mutableIntStateOf(0) }
    // A search that matches nothing keeps the toolbar, so the search can still be cleared.
    val customEmpty = !soundMode && source == Library.ADDED && visible.isEmpty() && hiddenUninstalled == 0 && LibraryView.query.isBlank()
    val splash = !soundMode && (signInStore != null || customEmpty)
    val showHeader = !splash || source == Store.BATTLENET.id
    val headerItems = (if (showHeader) 2 else 1) + (if (sizesPending) 1 else 0) + syncing.size
    val expanded = showUninstalled == true
    val notInstalled = if (expanded) visible.count { !it.installed } else hiddenUninstalled
    val toggleAt = when {
        showUninstalled == null || soundMode || notInstalled == 0 || (expanded && visible.isEmpty()) -> ToggleAt.NONE
        !expanded -> ToggleAt.BOTTOM
        split > 0 -> ToggleAt.SPLIT
        else -> ToggleAt.TOP
    }
    val itemIndex: (Int) -> Int = { i ->
        headerItems + i + when {
            split > 0 -> if (i < split) 0 else 1
            toggleAt == ToggleAt.TOP -> 1
            else -> 0
        }
    }
    val toggleIndex = when (toggleAt) {
        ToggleAt.TOP -> headerItems
        ToggleAt.SPLIT -> headerItems + split
        else -> headerItems + visible.size
    }
    val gapPx = with(density) { 10.dp.toPx() }
    val moveFocus: (String, Int, Int, Int) -> Unit = { target, targetIndex, currentIndex, direction ->
        val shown = grid.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
        if (focus != null && (!shown || (focus.attached[target] ?: 0) == 0 ||
                runCatching { focus.items.getValue(target).requestFocus() }.isFailure)) scope.launch {
            launch { grid.revealRow(currentIndex, targetIndex, direction, gapPx) }
            focusWithinFrames({ focus.last == target }) { focus.items[target] ?: FocusRequester.Default }
        }
    }
    val rowStart: (Int) -> Int? = { i -> if (i < 0) null else if (listMode) i else i - i % columns.coerceAtLeast(1) }
    val switchedAt = remember(source) { android.os.SystemClock.uptimeMillis() }
    val tile: @Composable (LibraryEntry) -> Unit = { e ->
        val first = e.key == visible.firstOrNull()?.key
        val navigation = if (focus == null) Modifier else Modifier.onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            val direction = when (key.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> 1
                KeyEvent.KEYCODE_DPAD_UP -> -1
                else -> 0
            }
            val index = visible.indexOfFirst { it.key == e.key }
            val next = storeGridNeighbor(index, visible.size, if (listMode) 1 else columns, direction, split)
            val toToggle = index >= 0 && when (toggleAt) {
                ToggleAt.BOTTOM -> next == null && direction > 0
                ToggleAt.TOP -> next == null && direction < 0
                ToggleAt.SPLIT -> next != null && (index < split) != (next < split)
                ToggleAt.NONE -> false
            }
            when {
                direction == 0 || index < 0 -> false
                toToggle -> {
                    if (key.action == KeyEvent.ACTION_DOWN) moveFocus(NOT_INSTALLED_ROW, toggleIndex, itemIndex(index), direction)
                    true
                }
                next == null -> direction > 0 && trackHits.isEmpty()
                else -> {
                    if (key.action == KeyEvent.ACTION_DOWN) moveFocus("card:${visible[next].key}", itemIndex(next), itemIndex(index), direction)
                    true
                }
            }
        }
        val picker = s.shortcutPicker && e.game != null
        val choose = if (picker) ({ a.onSteamGame(e.game!!) }) else null
        val enter = remember(source) {
            androidx.compose.animation.core.Animatable(if (Motion.scale == 0f || android.os.SystemClock.uptimeMillis() - switchedAt > 600) 1f else 0f)
        }
        LaunchedEffect(source) {
            if (enter.value >= 1f) return@LaunchedEffect
            kotlinx.coroutines.delay(Motion.ms(minOf(visible.indexOfFirst { it.key == e.key }.coerceAtLeast(0), 11) * 28).toLong())
            enter.animateTo(1f, Motion.tw(320))
        }
        Box(Modifier.graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 18.dp.toPx() }) {
            if (listMode) LibraryRow(e, first, navigation, choose, onOpen) else LibraryTile(e, capsules, first, navigation, choose, onOpen, showSource = source == LIBRARY_ALL || source == LIBRARY_FAVORITES || source == LIBRARY_HIDDEN)
        }
    }
    var rowFocused by remember { mutableStateOf(false) }
    val toggleRow: @Composable () -> Unit = {
        val keys = if (focus == null) Modifier else Modifier.onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            val direction = when (key.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> 1
                KeyEvent.KEYCODE_DPAD_UP -> -1
                else -> 0
            }
            val target = when {
                direction > 0 && toggleAt == ToggleAt.TOP -> 0
                direction > 0 && toggleAt == ToggleAt.SPLIT -> split
                direction < 0 && toggleAt == ToggleAt.SPLIT -> rowStart(split - 1)
                direction < 0 && toggleAt == ToggleAt.BOTTOM -> rowStart(visible.lastIndex)
                else -> null
            }
            when {
                direction == 0 -> false
                target == null -> direction > 0 && toggleAt == ToggleAt.BOTTOM && trackHits.isEmpty()
                else -> {
                    if (key.action == KeyEvent.ACTION_DOWN) moveFocus("card:${visible[target].key}", itemIndex(target), toggleIndex, direction)
                    true
                }
            }
        }
        NotInstalledRow(notInstalled, expanded, keys, onFocus = { rowFocused = it }) {
            rowFocused = false
            val landing = if (expanded) headerItems + split else headerItems + visible.size
            onShowUninstalled(!expanded)
            if (focus != null && focus.last == NOT_INSTALLED_ROW) scope.launch {
                androidx.compose.runtime.withFrameNanos { }
                if (grid.layoutInfo.visibleItemsInfo.none { it.key == NOT_INSTALLED_ROW }) grid.scrollToItem((landing - 1).coerceAtLeast(0))
                repeat(30) {
                    androidx.compose.runtime.withFrameNanos { }
                    if (rowFocused) return@launch
                    runCatching { focus.items[NOT_INSTALLED_ROW]?.requestFocus() }
                }
            }
        }
    }
    LazyVerticalGrid(
        columns = if (listMode) GridCells.Fixed(1) else remember(baseWidth, level) { LevelCells(baseWidth, level) }, state = grid,
        modifier = Modifier.fillMaxSize().onFocusChanged { onFocus(it.hasFocus) }.focusGroup().bumpers(onPrevious = { onCycle(-1) }, onNext = { onCycle(1) }).onSizeChanged {
            val gap = with(density) { 10.dp.roundToPx() }
            columns = if (listMode) 1 else libraryColumns(it.width, with(density) { baseWidth.roundToPx() }, gap, level)
            gridH = it.height
        },
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
    ) {
        val header: @Composable () -> Unit = {
            LibraryHeader(s, a, if (soundMode) total else visible.size + hiddenUninstalled, searchCleared, source == Store.XBOX.id, showUninstalled, onShowUninstalled, collections = Store.byId(source)?.takeIf { it == Store.ITCH || it == Store.GAMEJOLT }, soundMode = soundMode, onAddSoundtrack = onAddSoundtrack, onCollections = onCollections, source = source)
        }
        val filters: @Composable () -> Unit = { LibraryFilters(sources, source, onSource, manage = !soundMode && !s.shortcutPicker) }
        item(key = if (soundMode) "header" else "filters", span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.onSizeChanged { chipsH = it.height }) { Rise(0) { if (soundMode) header() else filters() } }
        }
        val splashHeight = with(density) { (gridH - chipsH).toDp() - 28.dp }.coerceAtLeast(300.dp)
        if (showHeader) item(key = if (soundMode) "filters" else "header", span = { GridItemSpan(maxLineSpan) }) { Rise(1) { if (soundMode) filters() else header() } }
        for ((store, line) in syncing) item(key = "sync-${store.id}", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                Text(store.shortLabel + " · " + line, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (syncCount(line) != null) SyncBar(line)
                else androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp).height(3.dp))
            }
        }
        if (sizesPending) item(key = "size-sort-progress", span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                androidx.compose.material3.LinearProgressIndicator(Modifier.weight(1f).height(3.dp))
                Text(stringResource(R.string.store_section_loading), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val empty = emptyText
        if (customEmpty) item(key = "custom-empty", span = { GridItemSpan(maxLineSpan) }) {
            val art = remember { wallArt() }
            Box(Modifier.padding(top = 4.dp, bottom = 16.dp)) { CustomGamesCard(splashHeight, art) }
        }
        else if (signInStore != null) item(key = "sign-in:${signInStore.id}", span = { GridItemSpan(maxLineSpan) }) {
            StoreTheme(signInStore) { Box(Modifier.padding(top = 4.dp, bottom = 16.dp)) { SignInCard(signInStore, splashHeight, inert = DevPreview.library != LibraryPreview.OFF) } }
        }
        else if (split == 0) {
            if (toggleAt == ToggleAt.TOP) item(key = NOT_INSTALLED_ROW, span = { GridItemSpan(maxLineSpan) }) { toggleRow() }
            if (visible.isNotEmpty() || toggleAt == ToggleAt.NONE) libraryGrid(visible, empty, tile)
            if (toggleAt == ToggleAt.BOTTOM) item(key = NOT_INSTALLED_ROW, span = { GridItemSpan(maxLineSpan) }) { toggleRow() }
        }
        else {
            libraryGrid(visible.take(split), empty, tile)
            item(key = NOT_INSTALLED_ROW, span = { GridItemSpan(maxLineSpan) }) {
                if (toggleAt == ToggleAt.SPLIT) toggleRow() else SectionLabel(stringResource(R.string.stores_section_not_installed))
            }
            libraryGrid(visible.drop(split), empty, tile)
        }
        if (trackHits.isNotEmpty()) {
            item(key = "tracks-label", span = { GridItemSpan(maxLineSpan) }) { SectionLabel(stringResource(R.string.soundtracks_tracks_heading)) }
            items(trackHits, key = { "track:${it.ref.path}" }, span = { GridItemSpan(maxLineSpan) }, contentType = { "track" }) { hit -> TrackHitRow(hit, onPlayHit) }
        }
    }
}

@Composable
private fun TrackHitRow(hit: SoundtrackIndex.Hit, onPlay: (SoundtrackIndex.Hit) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(10.dp)
    val cover = hit.ref.cover.takeIf { it.isNotEmpty() }?.let { java.io.File(it) }?.takeIf { it.isFile }
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).paneItem("trackhit:${hit.ref.path}").testTag("track-hit")
            .clip(shape).glideBorder(hot, shape, pal.signal, pal.line)
            .hoverable(src).clickable(role = Role.Button, interactionSource = src, indication = LocalIndication.current) { onPlay(hit) }
            .controllerConfirm { onPlay(hit) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(artBrush(hueOf(hit.ref.album)))) {
            if (cover != null) AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(hit.ref.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(hit.ref.album, hit.ref.artist).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (hit.ref.durationMs > 0) Text(Soundtrack.formatLength(hit.ref.durationMs), fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1)
    }
}

internal suspend fun LazyGridState.revealRow(currentIndex: Int, targetIndex: Int, direction: Int, gapPx: Float) {
    val current = layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentIndex }
    if (current != null) {
        val row = current.size.height + gapPx
        val delta = if (direction > 0) {
            current.offset.y + 2 * row - gapPx - (layoutInfo.viewportSize.height - layoutInfo.afterContentPadding)
        } else {
            current.offset.y - row - layoutInfo.beforeContentPadding
        }
        if (direction > 0 && delta > 0f || direction < 0 && delta < 0f) animateScrollBy(delta, tween(durationMillis = 160, easing = FastOutSlowInEasing))
    }
    if (layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) scrollToItem(targetIndex)
}

private fun LazyGridScope.libraryGrid(entries: List<LibraryEntry>, empty: Int, tile: @Composable (LibraryEntry) -> Unit) {
    if (entries.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
        Box(Modifier.padding(vertical = 32.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(stringResource(empty), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    else items(entries, key = { it.key }, contentType = { "game" }) { tile(it) }
}

@Composable
private fun LibraryHeader(
    s: FrontEndState, a: FrontEndActions, total: Int, searchCleared: Boolean, xbox: Boolean,
    showUninstalled: Boolean?, onShowUninstalled: (Boolean) -> Unit, collections: Store?,
    soundMode: Boolean = false, onAddSoundtrack: () -> Unit = {}, onCollections: () -> Unit = {}, source: String = LIBRARY_ALL,
) {
    val colors = MaterialTheme.colorScheme
    val narrow = LocalNarrowPane.current
    val heading = if (soundMode) stringResource(R.string.rail_soundtracks) else sourceTitle(source)
    val soundLead: (@Composable () -> Unit)? = if (!soundMode) { -> if (s.steamGamesX64 && !narrow) ClientTag() } else { ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 2.dp, end = 4.dp)) {
            Text(heading, fontSize = if (narrow) 20.sp else 24.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1)
            Text(total.toString(), fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1)
            if (s.steamGamesX64 && !narrow) ClientTag()
        }
    }
    ModeHeader(
        heading, LibraryView.query, { LibraryView.query = it },
        placeholder = stringResource(if (soundMode) R.string.soundtracks_search else R.string.stores_search_library),
        clearSearchFocus = searchCleared, inlineWhenStacked = true,
        lead = soundLead,
    ) {
        if (!soundMode && !s.shortcutPicker && source == Store.BATTLENET.id) {
            SecondaryButton(
                stringResource(R.string.bnet_open), enabled = !s.busy && DevPreview.library == LibraryPreview.OFF,
                compact = true, modifier = Modifier.testTag("library-battlenet-open"), onClick = a.onBattleNet,
            )
        }
        if (soundMode) HeaderIconButton(stringResource(R.string.soundtracks_saved), Icons.Filled.BookmarkBorder, "collections", onClick = onCollections)
        if (!soundMode && DeveloperTools.on && DevPreview.shortcuts) LibraryPreviewButton()
        DisplayButton()
        if (!soundMode && xbox) LibraryFilterButton(null, onShowUninstalled, xbox)
        if (collections != null) CollectionsButton(collections)
        SortButton(s, a)
        ViewButton(s, a)
        if (!soundMode) GameFileFolderActions(s, a)
        // No sign-out in a preview: it would sign the real account out.
        Store.byId(source)?.takeIf { !soundMode && DevPreview.library == LibraryPreview.OFF && StoresState.isSignedIn(it) }?.let { StoreAccountButton(it) }
        if (!s.shortcutPicker && (soundMode || (source == Library.ADDED && total > 0))) AddGameButton { if (soundMode) onAddSoundtrack() else GamesDialogState.adding = true }
    }
    if (s.steamGamesX64 && narrow) Box(Modifier.padding(top = 4.dp)) { ClientTag() }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LibraryFilters(sources: List<String>, source: String, onSource: (String) -> Unit, manage: Boolean) {
    val chips = (manage && sources.isNotEmpty()) || sources.size >= 2 || sources.singleOrNull() == LIBRARY_HIDDEN
    if (!chips && !manage) return
    val pal = LocalPalette.current
    val keys = if (chips) listOf(LIBRARY_ALL) + sources else emptyList()
    var focused by remember { mutableStateOf<String?>(null) }
    val highlighted = focused?.takeIf { it in keys } ?: source
    val bounds = remember { androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
    val inputMode = LocalInputModeManager.current.inputMode
    val fillShown = inputMode != InputMode.Keyboard
    val scroll = rememberScrollState()
    StoreTheme(Store.byId(highlighted)) {
        val tint = androidx.compose.animation.animateColorAsState(sourceDot(highlighted) ?: LocalPalette.current.signal, Motion.tw(160), label = "libChipTint")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        ) {
            Box(Modifier.weight(1f).edgeFade(scroll).horizontalScroll(scroll)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.focusGroup()) {
                    for (id in keys) {
                        val store = Store.byId(id)
                        val dot = sourceDot(id)
                        val label = when (id) {
                            LIBRARY_ALL -> stringResource(R.string.stores_tab_all)
                            Library.ADDED -> stringResource(R.string.library_custom_games)
                            LIBRARY_FAVORITES -> stringResource(R.string.soundtracks_favorites)
                            LIBRARY_HIDDEN -> stringResource(R.string.apps_filter_hidden)
                            else -> store?.shortLabel ?: sourceLabel(id)
                        }
                        androidx.compose.runtime.key(id) {
                            val reveal = remember { BringIntoViewRequester() }
                            LaunchedEffect(source) { if (source == id) reveal.bringIntoView() }
                            StoreChip(
                                label = label, on = source == id, under = fillShown && highlighted == id, key = id, mark = dot ?: pal.signal,
                                modifier = Modifier.bringIntoViewRequester(reveal).testTag("library-source-$id")
                                    .onGloballyPositioned { bounds[id] = it.boundsInParent() },
                                lead = {
                                    if (dot != null) Box(Modifier.size(9.dp).background(dot, CircleShape))
                                    else Icon(Icons.Filled.GridView, null, modifier = Modifier.size(14.dp))
                                },
                                onFocused = { focused = it },
                                paneKey = "libchip:$id",
                            ) { onSource(id) }
                        }
                    }
                }
                if (highlighted in keys) ChipGlide(keys.indexOf(highlighted), bounds[highlighted], tint, visible = fillShown)
            }
            if (manage) ManageStoresChip()
        }
    }
}

@Composable
private fun sourceTitle(src: String): String = when (src) {
    LIBRARY_ALL -> stringResource(R.string.library_all_games)
    Library.ADDED -> stringResource(R.string.library_custom_games)
    LIBRARY_FAVORITES -> stringResource(R.string.soundtracks_favorites)
    LIBRARY_HIDDEN -> stringResource(R.string.apps_filter_hidden)
    else -> Store.byId(src)?.label ?: sourceLabel(src)
}

@Composable
private fun StoreAccountButton(store: Store) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val name = StoresState.accounts[store] ?: store.label
    val label = stringResource(R.string.stores_signout_named, name)
    val title = stringResource(R.string.stores_signout_title, store.label)
    val note = stringResource(R.string.stores_signout_note)
    val stay = stringResource(R.string.stores_signout_stay)
    val go = stringResource(R.string.stores_signout_go)
    var at by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
        HeaderIconButton(label, Icons.Filled.ManageAccounts, "library-account") {
            val from = at ?: run { StoresState.signOut(ctx, store); return@HeaderIconButton }
            Steps.ask(StepAsk(
                anchor = from, side = StepSide.Left, accent = colors.error, pillCorner = 8.dp, items = 3,
                handle = { Icon(Icons.Filled.ManageAccounts, null, tint = colors.error, modifier = Modifier.size(18.dp)) },
            ) {
                StepTitle(title)
                Text(note, fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.stepItem(1).padding(start = 4.dp, bottom = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth().stepItem(2)) {
                    StepChoice(stay, enabled = open, modifier = Modifier.focusRequester(first)) { Steps.fold() }
                    StepChoice(go, danger = true, enabled = open) { Steps.fold(); StoresState.signOut(ctx, store) }
                }
            })
        }
    }
}

@Composable
private fun CustomGamesCard(height: androidx.compose.ui.unit.Dp, art: List<Any>) {
    HeroWallCard(stringResource(R.string.library_custom_games), art, sourceColours(Library.ADDED), height, Modifier.testTag("library-custom-empty")) {
        PrimaryButton(stringResource(R.string.games_add), main = true, large = true, icon = Icons.Filled.Add, modifier = Modifier.testTag("library-custom-add")) {
            GamesDialogState.adding = true
        }
    }
}

private fun sourceDot(src: String): Color? {
    val store = Store.byId(src)
    return when {
        store != null -> sourceColours(src).dot.copy(
            alpha = if (DevPreview.library == LibraryPreview.SAMPLE || (DevPreview.library != LibraryPreview.SIGNED_OUT && StoresState.isSignedIn(store) && StoresState.expired[store] != true)) 1f else 0.35f,
        )
        src == LIBRARY_FAVORITES -> FavoritesColours.dot
        src == LIBRARY_HIDDEN -> HIDDEN_DOT
        src == Library.SOURCE_STEAM || src == Library.ADDED || src == Library.SOURCE_COLLECTION -> sourceColours(src).dot
        else -> null
    }
}

@Composable
internal fun ManageStoresChip() {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val back = remember { FocusRequester() }
    val label = stringResource(R.string.library_manage_stores)
    val open = { ManageStores.returnTo = back; ManageStores.custom = true; ManageStores.open = true }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.paneItem("libchip:manage").focusRequester(back).testTag("library-manage-stores").size(44.dp).clip(CircleShape)
            .background(colors.surface)
            .glideBorder(hot, CircleShape, pal.signal, pal.line)
            .hoverable(src)
            .clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClickLabel = label, onClick = open)
            .controllerConfirm(onClick = open),
    ) { Icon(Icons.Filled.Tune, label, tint = if (hot) colors.onBackground else colors.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
}

private fun entryArt(e: LibraryEntry, tall: Boolean): Any? {
    val inst = e.storeKey?.let { k -> StoresState.installed.firstOrNull { it.key == k } }
    val wide: Any? = e.game?.hero ?: e.item?.imageUrl ?: inst?.sidecar?.hero ?: inst?.sidecar?.cover
    val portrait: Any? = e.game?.art ?: e.item?.tallImageUrl ?: inst?.sidecar?.cover
    return if (tall) portrait ?: wide else wide ?: portrait
}

@Composable
private fun LibraryTile(e: LibraryEntry, capsule: Boolean, first: Boolean, navigation: Modifier, choose: (() -> Unit)?, onOpen: (String) -> Unit, showSource: Boolean) {
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "tileScale")
    val download = e.storeKey?.let { StoresState.download(it) }?.takeIf { it.isActive }
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val corner = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
    val wide = entryArt(e, tall = false)
    val tall = entryArt(e, tall = true)
    RequestSize(e)
    val open = {
        val art = if (capsule) tall else wide
        placed[0]?.takeIf { it.isAttached }?.let {
            StoresMotion.markCard(CardMark(e.key, it.boundsInRoot(), corner, (art as? java.io.File)?.path ?: art as? String, (wide as? java.io.File)?.path ?: wide as? String))
        }
        onOpen(e.key)
    }
    Column(
        modifier = Modifier.fillMaxWidth().onGloballyPositioned { placed[0] = it }
            .then(navigation).paneItem("card:${e.key}").then(if (first) Modifier.firstTile().paneEntry() else Modifier).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp)).glideBorder(hot, RoundedCornerShape(10.dp), pal.signal, pal.line)
            .hoverable(src).clickable(role = Role.Button, interactionSource = src, indication = LocalIndication.current, onClick = open)
            .controllerConfirm(onClick = choose ?: open),
    ) {
        LibraryArt(e, wide, tall, download, hot, capsule, showSource)
    }
}

internal val LibraryCardWidth = 150.dp

internal fun libraryTileWidth() = LibraryCardWidth * LibraryDisplay.scales[LibraryDisplay.level]

@Composable
private fun LibraryArt(e: LibraryEntry, wide: Any?, tall: Any?, download: DownloadEntry?, hot: Boolean, capsule: Boolean, showSource: Boolean) {
    val chip = when {
        download != null -> "${(steadyProgress(download) * 100).toInt()}%"
        e.item != null && StoresState.isUnfinished(e.item) -> stringResource(R.string.stores_resume)
        e.installed && libraryUpdateAvailable(e) -> stringResource(R.string.common_update)
        else -> null
    }
    ArtCard(
        e.title, wide, tall, download, hot, capsule, chip,
        badge = if (showSource) ({ SourceChip(e.source, small = true, modifier = Modifier.align(Alignment.TopStart).padding(6.dp), label = favoritesBadge(e), colours = favoritesColours(e)) }) else null,
    ) { LibrarySize(e) }
}

@Composable
internal fun ArtCard(
    title: String, wide: Any?, tall: Any?, download: DownloadEntry?, hot: Boolean, capsule: Boolean, chip: String?,
    badge: (@Composable BoxScope.() -> Unit)? = null, detail: @Composable () -> Unit,
) {
    val url = if (capsule) tall ?: wide else wide ?: tall
    val art = cardArtRequest(url, tall)
    val ratio = if (capsule) 5f / 7f else 16f / 9f
    var offRatio by remember(url) { mutableStateOf(false) }
    var trim by remember(url) { mutableStateOf(false) }
    val blurs = android.os.Build.VERSION.SDK_INT >= 31
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio).background(Color(0xFF101318))
            .then(if (url == null) Modifier.background(artBrush(hueOf(title))) else Modifier),
    ) {
        if (art != null && offRatio) {
            AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().blur(32.dp))
            Spacer(Modifier.matchParentSize().background(Color.Black.copy(alpha = if (blurs) 0.18f else 0.6f)))
        }
        if (art != null) AsyncImage(
            model = art, contentDescription = null, contentScale = if (trim) ContentScale.Crop else ContentScale.Fit, modifier = Modifier.matchParentSize(),
            onSuccess = { r ->
                val d = r.result.drawable
                if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                    val shape = d.intrinsicWidth.toFloat() / d.intrinsicHeight
                    trim = !capsule && shape > ratio * 1.25f
                    offRatio = !trim && kotlin.math.abs(shape / ratio - 1f) > 0.03f
                }
            },
        )
        badge?.invoke(this)
        if (chip != null) Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
            StateChip(chip, ChipTone.BUSY, small = true, fraction = download?.let { steadyProgress(it) }, key = download?.key)
        }
        val showName = LibraryDisplay.title || hot
        val showDetail = LibraryDisplay.size || hot
        if (showName || showDetail) {
            val top = with(androidx.compose.ui.platform.LocalDensity.current) { 20.dp.roundToPx() }
            val bottom = with(androidx.compose.ui.platform.LocalDensity.current) { (if (download != null) 14.dp else 6.dp).roundToPx() }
            Column(
                verticalArrangement = Arrangement.spacedBy(1.dp),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.6f to Color.Black.copy(alpha = 0.78f), 1f to Color.Black.copy(alpha = 0.92f)))
                    .layout { measurable, constraints ->
                        val inner = measurable.measure(constraints)
                        if (inner.height == 0) layout(inner.width, 0) {} else layout(inner.width, inner.height + top + bottom) { inner.place(0, top) }
                    }
                    .padding(horizontal = 8.dp),
            ) {
                if (showName) Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = if (hot) 2 else 1, overflow = TextOverflow.Ellipsis, lineHeight = 13.sp)
                if (showDetail) detail()
            }
        }
        if (download != null) Box(Modifier.align(Alignment.BottomCenter).padding(start = 6.dp, end = 6.dp, bottom = 5.dp)) {
            ProgressBarThin(download.fraction, paused = download.state == DownloadState.PAUSED, verify = download.stage == DownloadStage.VERIFY)
        }
    }
}

@Composable
private fun RequestSize(e: LibraryEntry) {
    val ctx = LocalContext.current
    if (e.installed) {
        val inst = e.storeKey?.let { k -> StoresState.installed.firstOrNull { it.key == k } }
        LaunchedEffect(e.key, inst) {
            if (inst != null) StoreSizes.requestDisk(ctx, inst.key, inst)
            else e.game?.gameFiles?.let { FolderSizes.request(it) }
        }
        return
    }
    val item = e.item?.takeIf { !e.installed && it.accessible && it.sizeBytes <= 0 } ?: return
    LaunchedEffect(item.key) { StoreSizes.request(ctx, item) }
}

private fun librarySize(e: LibraryEntry): Long? = if (e.installed) e.storeKey?.let { StoreSizes.onDisk[it] }
    ?: e.game?.gameFiles?.let { FolderSizes.known[it.path] }
    else e.item?.let { StoreSizes.size(it) }?.takeIf { it > 0 }

@Composable
private fun LibrarySize(e: LibraryEntry) {
    val size = librarySize(e)?.takeIf { it > 0 } ?: return
    Text(formatBytes(size), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
}

@Composable
internal fun LibraryMarker(e: LibraryEntry, download: DownloadEntry?, showDownload: Boolean = true) {
    val pal = LocalPalette.current
    val size = librarySize(e)
    when {
        download != null -> Text("${download.percent}%", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pal.signal, maxLines = 1)
        e.item != null && StoresState.isUnfinished(e.item) -> Text(stringResource(R.string.stores_resume), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pal.signal, maxLines = 1)
        else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (e.installed && libraryUpdateAvailable(e)) Text(stringResource(R.string.common_update), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pal.signal, maxLines = 1)
            if (size != null && size > 0) Text(formatBytes(size), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            if (!e.installed && showDownload) Icon(Icons.Outlined.Download, stringResource(R.string.stores_section_not_installed), tint = Color.White, modifier = Modifier.size(12.dp))
        }
    }
}

private fun libraryUpdateAvailable(e: LibraryEntry): Boolean = e.storeKey?.let { it in ItchUpdates.available || it in GameJoltUpdates.available || it in com.droiddeck.launcher.stores.ea.EaUpdates.available } == true ||
    e.source == Store.GOG.id && (e.item?.id ?: e.game?.storeId)?.let { GogMaintenance.checks[it]?.state } == GogMaintenance.State.AVAILABLE

@Composable
private fun LibraryRow(e: LibraryEntry, first: Boolean, navigation: Modifier, choose: (() -> Unit)?, onOpen: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val download = e.storeKey?.let { StoresState.download(it) }?.takeIf { it.isActive }
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val corner = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
    val wide = entryArt(e, tall = false)
    val open = {
        placed[0]?.takeIf { it.isAttached }?.let { StoresMotion.markCard(CardMark(e.key, it.boundsInRoot(), corner, (wide as? java.io.File)?.path ?: wide as? String)) }
        onOpen(e.key)
    }
    RequestSize(e)
    val inst = e.storeKey?.let { k -> StoresState.installed.firstOrNull { it.key == k } }
    val folder = inst?.folder ?: e.game?.gameFiles
    val size = if (e.installed && folder != null) diskSize(inst, folder) else e.item?.let { StoreSizes.size(it) }?.takeIf { it > 0 }
    val status = when {
        download != null -> downloadLabel(download)
        e.installed && libraryUpdateAvailable(e) -> stringResource(R.string.common_update)
        !e.installed -> stringResource(R.string.stores_section_not_installed)
        e.game != null -> playedSpan(e.game.lastPlayed)?.replaceFirstChar { it.uppercase() } ?: stringResource(R.string.games_never_played)
        else -> stringResource(R.string.stores_installed_chip)
    }
    val meta = listOfNotNull(status, size?.let { formatBytes(it) }?.takeIf { it.isNotEmpty() })
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).onGloballyPositioned { placed[0] = it }.then(navigation).paneItem("card:${e.key}")
            .then(if (first) Modifier.firstTile().paneEntry() else Modifier)
            .clip(RoundedCornerShape(10.dp)).glideBorder(hot, RoundedCornerShape(10.dp), pal.signal, pal.line)
            .hoverable(src).clickable(role = Role.Button, interactionSource = src, indication = LocalIndication.current, onClick = open)
            .controllerConfirm(onClick = choose ?: open)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Box(Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(8.dp)).background(artBrush(hueOf(e.title))).graphicsLayer { alpha = if (e.installed || download != null) 1f else 0.55f }) {
            if (wide != null) AsyncImage(model = wide, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(e.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SourceChip(e.source, small = true, label = favoritesBadge(e), colours = favoritesColours(e))
                Text(meta.joinToString("  ·  "), fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (download != null) Text("${download.percent}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = pal.signal, maxLines = 1)
        else if (!e.installed) Icon(Icons.Outlined.Download, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

@Composable
internal fun HeaderIconButton(description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, key: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.paneItem("btn:$key").size(32.dp).clip(CircleShape).background(if (hot) pal.signal.copy(alpha = 0.14f) else Color.Transparent)
            .glideBorder(hot, CircleShape, pal.signal)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .semantics { contentDescription = description },
    ) { Icon(icon, null, tint = if (hot) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
}

@Composable
internal fun Modifier.paneEntry(): Modifier {
    val ff = LocalFrontFocus.current ?: return this
    androidx.compose.runtime.DisposableEffect(ff) {
        ff.primaryAttached++
        onDispose { ff.primaryAttached-- }
    }
    return focusRequester(ff.primary)
}

internal fun keepsXbox(filter: String, e: LibraryEntry): Boolean = when {
    filter == XboxLibraryFilter.ALL || e.source != Store.XBOX.id -> true
    e.game?.library == Library.XBOX_CLOUD && e.item == null -> filter == XboxLibraryFilter.CLOUD
    else -> e.item?.let { XboxLibraryFilter.keeps(filter, it) } == true
}

@Composable
private fun xboxFilterLabel(filter: String): String = when (filter) {
    XboxLibraryFilter.OWNED -> stringResource(R.string.game_install_access_owned)
    XboxLibraryFilter.GAME_PASS -> stringResource(R.string.xbox_filter_game_pass)
    XboxLibraryFilter.CLOUD -> stringResource(R.string.xbox_filter_cloud)
    else -> stringResource(R.string.stores_tab_all)
}

@Composable
private fun LibraryFilterButton(showUninstalled: Boolean?, onShowUninstalled: (Boolean) -> Unit, xbox: Boolean) {
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val title = stringResource(R.string.library_filter)
    val visibility = stringResource(if (showUninstalled == true) R.string.library_filter_all_owned else R.string.library_filter_installed)
    val description = title + " · " + if (showUninstalled == null) xboxFilterLabel(XboxLibraryFilter.current) else visibility
    val offset = if (showUninstalled == null) 0 else 2
    var at by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
        HeaderIconButton(description, Icons.Filled.FilterAlt, "library-filter") {
            val from = at ?: return@HeaderIconButton
            Steps.ask(StepAsk(
                anchor = from, side = StepSide.Left, accent = pal.signal, pillCorner = 8.dp, items = offset + if (xbox) XboxLibraryFilter.ALL_FILTERS.size else 0,
                handle = { Icon(Icons.Filled.FilterAlt, null, tint = pal.signal, modifier = Modifier.size(18.dp)) },
            ) {
                StepTitle(title)
                StepList {
                    if (showUninstalled != null) listOf(false, true).forEachIndexed { i, show ->
                        StepChoice(
                            stringResource(if (show) R.string.library_filter_all_owned else R.string.library_filter_installed),
                            selected = show == showUninstalled, enabled = open,
                            modifier = Modifier.fillMaxWidth().stepItem(i).then(if (show == showUninstalled) Modifier.focusRequester(first) else Modifier),
                        ) {
                            onShowUninstalled(show)
                            Steps.fold()
                        }
                    }
                    if (xbox && showUninstalled != null) Text(Store.XBOX.shortLabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.stepItem(offset).padding(start = 4.dp, top = 10.dp, bottom = 4.dp))
                    if (xbox) XboxLibraryFilter.ALL_FILTERS.forEachIndexed { i, filter ->
                        StepChoice(
                            xboxFilterLabel(filter), selected = filter == XboxLibraryFilter.current, enabled = open,
                            modifier = Modifier.fillMaxWidth().stepItem(offset + i).then(if (showUninstalled == null && filter == XboxLibraryFilter.current) Modifier.focusRequester(first) else Modifier),
                        ) {
                            XboxLibraryFilter.set(ctx, filter)
                            Steps.fold()
                        }
                    }
                }
            })
        }
    }
}

@Composable
private fun CollectionsButton(store: Store) {
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val title = stringResource(R.string.itch_collections)
    val all = stringResource(R.string.stores_tab_all)
    var at by remember { mutableStateOf<Rect?>(null) }
    val gameJolt = store == Store.GAMEJOLT
    val chosenTitle = if (gameJolt) GameJoltCollections.chosen()?.title else ItchCollections.chosen()?.title
    val selectedId = if (gameJolt) GameJoltCollections.selected else ItchCollections.selected
    Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
        HeaderIconButton(title + " · " + (chosenTitle ?: all), CollectionsIcon, "${store.id}-collections") {
            val from = at ?: return@HeaderIconButton
            val choices = listOf<Pair<String, String>?>(null) + if (gameJolt) GameJoltCollections.list.map { c ->
                c.id to (c.title + (GameJoltCollections.gamesOf(c.id)?.let { " (" + it.size + ")" }.orEmpty()))
            } else ItchCollections.list.map { c -> c.id to (c.title + " (" + c.count + ")") }
            Steps.ask(StepAsk(
                anchor = from, side = StepSide.Left, accent = pal.signal, pillCorner = 8.dp, items = choices.size,
                handle = { Icon(CollectionsIcon, null, tint = pal.signal, modifier = Modifier.size(18.dp)) },
            ) {
                StepTitle(title)
                StepList {
                    choices.forEachIndexed { i, c ->
                        val picked = c?.first == selectedId
                        StepChoice(
                            c?.second ?: all, selected = picked, enabled = open,
                            modifier = Modifier.fillMaxWidth().stepItem(i).then(if (picked) Modifier.focusRequester(first) else Modifier),
                        ) {
                            if (gameJolt) GameJoltCollections.select(ctx, c?.first) else ItchCollections.select(ctx, c?.first)
                            Steps.fold()
                        }
                    }
                }
            })
        }
    }
}

@Composable
internal fun DisplayButton() {
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val title = stringResource(R.string.library_display)
    var at by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
        HeaderIconButton(title, Icons.Filled.Visibility, "display") {
            val from = at ?: return@HeaderIconButton
            Steps.ask(StepAsk(
                anchor = from, side = StepSide.Left, accent = pal.signal, pillCorner = 8.dp, items = 4,
                handle = { Icon(Icons.Filled.Visibility, null, tint = pal.signal, modifier = Modifier.size(18.dp)) },
            ) {
                StepTitle(title)
                StepList {
                    val last = LibraryDisplay.scales.lastIndex
                    StepChoice(
                        stringResource(R.string.library_display_larger), icon = Icons.Filled.Add, enabled = open && LibraryDisplay.level < last,
                        trailing = "${LibraryDisplay.level + 1}/${last + 1}",
                        modifier = Modifier.fillMaxWidth().stepItem(0).focusRequester(first),
                    ) { LibraryDisplay.step(ctx, 1) }
                    StepChoice(
                        stringResource(R.string.library_display_smaller), icon = Icons.Filled.Remove, enabled = open && LibraryDisplay.level > 0,
                        modifier = Modifier.fillMaxWidth().stepItem(1),
                    ) { LibraryDisplay.step(ctx, -1) }
                    StepChoice(
                        stringResource(R.string.library_display_title), selected = LibraryDisplay.title, enabled = open,
                        modifier = Modifier.fillMaxWidth().stepItem(2),
                    ) { LibraryDisplay.toggleTitle(ctx) }
                    StepChoice(
                        stringResource(R.string.library_display_size), selected = LibraryDisplay.size, enabled = open,
                        modifier = Modifier.fillMaxWidth().stepItem(3),
                    ) { LibraryDisplay.toggleSize(ctx) }
                }
            })
        }
    }
}

@Composable
private fun SortButton(s: FrontEndState, a: FrontEndActions) {
    val pal = LocalPalette.current
    val title = stringResource(R.string.stores_sort)
    var at by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
        HeaderIconButton(title, Icons.Filled.Sort, "sort") {
            val from = at ?: return@HeaderIconButton
            Steps.ask(StepAsk(
                anchor = from, side = StepSide.Left, accent = pal.signal, pillCorner = 8.dp, items = SessionPrefs.STORES_SORTS.size,
                handle = { Icon(Icons.Filled.Sort, null, tint = pal.signal, modifier = Modifier.size(18.dp)) },
            ) {
                StepTitle(title)
                StepList {
                    SessionPrefs.STORES_SORTS.forEachIndexed { i, sort ->
                        val label = when (sort) {
                            "lastPlayed" -> stringResource(R.string.stores_sort_last_played)
                            "recentlyInstalled" -> stringResource(R.string.stores_sort_recently_installed)
                            "titleAsc" -> stringResource(R.string.stores_sort_title_asc)
                            "titleDesc" -> stringResource(R.string.stores_sort_title_desc)
                            "sizeDesc" -> stringResource(R.string.stores_sort_size_desc)
                            "sizeAsc" -> stringResource(R.string.stores_sort_size_asc)
                            else -> stringResource(R.string.stores_sort_release_date)
                        }
                        StepChoice(
                            label, selected = sort == s.storesSort, enabled = open,
                            modifier = Modifier.fillMaxWidth().stepItem(i).then(if (sort == s.storesSort) Modifier.focusRequester(first) else Modifier),
                        ) {
                            a.onStoresSort(sort)
                            Steps.fold()
                        }
                    }
                }
            })
        }
    }
}

@Composable
private fun ViewButton(s: FrontEndState, a: FrontEndActions) {
    val views = SessionPrefs.STORES_VIEWS
    val next = views[(views.indexOf(s.storesView) + 1) % views.size]
    HeaderIconButton(
        stringResource(when (next) { "capsules" -> R.string.stores_view_capsules; "list" -> R.string.stores_view_list; else -> R.string.stores_view_cards }),
        when (next) { "capsules" -> Icons.Filled.ViewModule; "list" -> Icons.Filled.ViewList; else -> Icons.Filled.GridView },
        "view",
    ) { a.onStoresView(next) }
}

@Composable
private fun favoritesBadge(e: LibraryEntry): String? =
    if (e.game?.appId == SoundtrackCollections.FAVORITES_ID && e.source == Library.SOURCE_COLLECTION) stringResource(R.string.soundtracks_favorites) else null

private fun favoritesColours(e: LibraryEntry): SourceColours? =
    if (e.game?.appId == SoundtrackCollections.FAVORITES_ID && e.source == Library.SOURCE_COLLECTION) FavoritesColours else null
