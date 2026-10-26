package com.droiddeck.launcher.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
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
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreSizes
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.formatBytes
import com.droiddeck.launcher.stores.retainOrder
import com.droiddeck.launcher.stores.xbox.XboxLibraryFilter
import com.droiddeck.launcher.stores.sortGames
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue

/** One owned game: installed ones carry their [game], store products not installed yet only their [item]. */
internal class LibraryEntry(
    val key: String, val source: String, val title: String,
    val game: Library.SteamGame?, val item: CatalogItem?, val installed: Boolean,
) {
    val storeKey: String? get() = item?.key ?: game?.storeId?.let { "${game.source}:$it" }
}

/** Installed games first claim their store product, so a game owned and installed shows once. */
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

internal fun installedFirst(entries: List<LibraryEntry>): List<LibraryEntry> = entries.partition { it.installed }.let { (on, off) -> on + off }

internal const val LIBRARY_ALL = "all"
internal val LIBRARY_SOURCES = listOf(Library.SOURCE_STEAM) + Store.entries.map { it.id } + Library.ADDED

/** Survives leaving the page, so Stores can open the Library on one store's owned games. */
internal object LibraryView {
    var source by mutableStateOf(LIBRARY_ALL)
    var query by mutableStateOf("")
    var focusFirst = false

    fun showOwned(store: Store) {
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
    val stores = s.gameStoresEnabled && !s.shortcutPicker
    val signedIn = Store.entries.filter { StoresState.isSignedIn(it) }
    LaunchedEffect(stores, signedIn) { if (stores) signedIn.forEach { StoresState.loadLibrary(ctx, it) } }
    val owned = if (stores) signedIn.associateWith { StoresState.library[it].orEmpty() } else emptyMap()
    val installedStore = StoresState.installed.map { it.key }.toSet()
    val syncing = if (stores) signedIn.mapNotNull { st -> StoresState.status[st]?.let { st to it } } else emptyList()
    val cloudRows = rememberXboxCloudRows(s)
    val all = remember(s.steamGames, cloudRows, owned, installedStore) { libraryEntries(s.steamGames + cloudRows, owned, installedStore) }
    LaunchedEffect(Unit) { XboxLibraryFilter.load(ctx) }
    val sources = LIBRARY_SOURCES.filter { src -> all.any { it.source == src } }
    val source = LibraryView.source.takeIf { it == LIBRARY_ALL || it in sources } ?: LIBRARY_ALL
    val q = LibraryView.query.trim().lowercase()
    val xboxFilter = if (source == Store.XBOX.id) XboxLibraryFilter.current else XboxLibraryFilter.ALL
    val candidates = all.filter {
        (source == LIBRARY_ALL || it.source == source) && (q.isEmpty() || it.title.lowercase().contains(q)) && keepsXbox(xboxFilter, it)
    }
    val sort = s.storesSort
    val sizeSort = sort == "sizeAsc" || sort == "sizeDesc"
    val installedAt = StoresState.installed.associate { it.key to it.sidecar.installedAt }
    val lastPlayed = s.steamGames.filter { it.storeId != null }.associate { "${it.source}:${it.storeId}" to it.lastPlayed }
    fun sizeOf(e: LibraryEntry): Long? {
        val key = e.storeKey
        return when {
            key != null && key in installedStore -> StoreSizes.onDisk[key]
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
    val sizesPending = sizeSort && !sizesWaited && candidates.any { sizeOf(it) == null }
    val sorted = sortGames(
        candidates, sort, title = { it.title }, releaseDate = { it.item?.releaseDate.orEmpty() },
        lastPlayed = { e -> e.game?.lastPlayed ?: e.storeKey?.let { lastPlayed[it] } ?: 0L },
        installedAt = { e -> e.storeKey?.let { installedAt[it] } ?: 0L },
        size = { e -> sizeOf(e) ?: 0L },
    )
    var stable by remember { mutableStateOf(emptyList<LibraryEntry>()) }
    val ordered = if (sizesPending) retainOrder(candidates, stable) { it.key } else sorted
    SideEffect { if (!sizesPending) stable = sorted }
    val visible = installedFirst(ordered)
    val split = visible.count { it.installed }.takeIf { it in 1 until visible.size } ?: 0

    val open = selected.takeIf { it.startsWith("app:") }
    val back = { onSelect("games") }
    BackHandler(enabled = open == null && LibraryView.query.isNotEmpty()) { LibraryView.query = "" }
    BackHandler(enabled = open != null) { back() }
    val lead = remember { FocusRequester() }
    var detailFocused by remember { mutableStateOf(false) }
    var lastOpened by remember { mutableStateOf<String?>(null) }
    val wasOpen = remember { booleanArrayOf(false) }
    val backLabel = stringResource(R.string.rail_games)
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
    if (all.isEmpty() && open == null) {
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
        modifier = modifier.padding(horizontal = if (narrow) 14.dp else 22.dp, vertical = if (narrow) 10.dp else 14.dp),
        wrap = { key, content -> StoreTheme(GameRef.parse(key).let { r -> r.store ?: r.game(s.steamGames)?.source?.let { Store.byId(it) } }) { content() } },
        grid = {
            LibraryGrid(
                s, a, grid, visible, split, all.size, sources, source, sizesPending, syncing,
                onSource = { LibraryView.source = it },
                onCycle = { step ->
                    val ids = listOf(LIBRARY_ALL) + sources
                    LibraryView.source = ids[(ids.indexOf(source).coerceAtLeast(0) + step + ids.size) % ids.size]
                    cycled++
                },
                onOpen = { focusManager.clearFocus(force = true); onSelect(it) },
                searchCleared = open != null,
                onFocus = { gridFocused = it },
            )
        },
    ) { key -> GameDetail(key, s, a, lead, backLabel, hopRail = "games", onBack = back) }
}

@Composable
private fun LibraryEmpty(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    val narrow = LocalNarrowPane.current
    Column(modifier = modifier.padding(horizontal = if (narrow) 16.dp else 22.dp, vertical = if (narrow) 12.dp else 18.dp)) {
        Rise(0) {
            PageHeader(stringResource(R.string.rail_games)) {
                if (s.steamGamesX64) ClientTag()
                if (!s.shortcutPicker) AddGameButton { GamesDialogState.adding = true }
            }
        }
        Rise(1) { Note(stringResource(if (s.shortcutPicker && s.shortcutLibraryScanning) R.string.game_shortcut_scanning else R.string.games_empty)) }
        if (!s.shortcutPicker) Rise(2) {
            Actions {
                PrimaryButton(stringResource(R.string.games_play_steam), enabled = !s.busy, main = true, icon = Icons.Filled.PlayArrow, modifier = Modifier.padding(top = 12.dp).testTag("play-steam"), onClick = a.onPlay)
            }
        }
        Box(Modifier.padding(top = 12.dp)) { GameFileFolderActions(s, a) }
    }
}

@Composable
private fun LibraryGrid(
    s: FrontEndState, a: FrontEndActions, grid: LazyGridState, visible: List<LibraryEntry>, split: Int, total: Int,
    sources: List<String>, source: String, sizesPending: Boolean, syncing: List<Pair<Store, String>>,
    onSource: (String) -> Unit, onCycle: (Int) -> Unit, onOpen: (String) -> Unit, onFocus: (Boolean) -> Unit, searchCleared: Boolean,
) {
    val listMode = s.storesView == "list"
    val capsules = s.storesView == "capsules"
    val tileWidth = if (capsules) CapsuleWidth else CardWidth
    val focus = LocalFrontFocus.current
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    var columns by remember { mutableIntStateOf(1) }
    val headerItems = 2 + (if (sizesPending) 1 else 0) + syncing.size
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
            when {
                direction == 0 || index < 0 -> false
                next == null -> direction > 0
                else -> {
                    if (key.action == KeyEvent.ACTION_DOWN) {
                        val target = "card:${visible[next].key}"
                        val targetIndex = headerItems + next + if (split == 0) 0 else if (next < split) 1 else 2
                        val shown = grid.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
                        if (!shown || (focus.attached[target] ?: 0) == 0 ||
                            runCatching { focus.items.getValue(target).requestFocus() }.isFailure) scope.launch {
                            grid.scrollToItem(targetIndex)
                            focusWithinFrames({ focus.last == target }) { focus.items[target] ?: FocusRequester.Default }
                        }
                    }
                    true
                }
            }
        }
        val picker = s.shortcutPicker && e.game != null
        val choose = if (picker) ({ a.onSteamGame(e.game!!) }) else null
        if (listMode) LibraryRow(e, first, navigation, choose, onOpen) else LibraryTile(e, capsules, first, navigation, choose, onOpen)
    }
    LazyVerticalGrid(
        columns = if (listMode) GridCells.Fixed(1) else GridCells.Adaptive(tileWidth), state = grid,
        modifier = Modifier.fillMaxSize().onFocusChanged { onFocus(it.hasFocus) }.focusGroup().bumpers(onPrevious = { onCycle(-1) }, onNext = { onCycle(1) }).onSizeChanged {
            val gap = with(density) { 10.dp.roundToPx() }
            val minimum = with(density) { (if (listMode) CardWidth * 2 else tileWidth).roundToPx() }
            columns = ((it.width + gap) / (minimum + gap)).coerceAtLeast(1)
        },
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) { Rise(0) { LibraryHeader(s, a, total, searchCleared, xbox = source == Store.XBOX.id) } }
        item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
            Rise(1) { LibraryFilters(sources, source, onSource) }
        }
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
        val empty = R.string.stores_nothing_matches
        if (split == 0) libraryGrid(visible, empty, tile)
        else {
            item(key = "installed-label", span = { GridItemSpan(maxLineSpan) }) { SectionLabel(stringResource(R.string.stores_section_installed)) }
            libraryGrid(visible.take(split), empty, tile)
            item(key = "not-installed-label", span = { GridItemSpan(maxLineSpan) }) { SectionLabel(stringResource(R.string.stores_section_not_installed)) }
            libraryGrid(visible.drop(split), empty, tile)
        }
    }
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
private fun LibraryHeader(s: FrontEndState, a: FrontEndActions, total: Int, searchCleared: Boolean, xbox: Boolean) {
    val colors = MaterialTheme.colorScheme
    val narrow = LocalNarrowPane.current
    ModeHeader(
        stringResource(R.string.rail_games), LibraryView.query, { LibraryView.query = it },
        placeholder = stringResource(R.string.stores_search_library),
        clearSearchFocus = searchCleared,
        lead = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 2.dp, end = 4.dp)) {
                Text(stringResource(R.string.rail_games), fontSize = if (narrow) 20.sp else 24.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1)
                Text(pluralStringResource(R.plurals.mode_added_count, total, total), fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1)
                if (s.steamGamesX64 && !narrow) ClientTag()
            }
        },
    ) {
        if (xbox) XboxFilterButton()
        SortButton(s, a)
        ViewButton(s, a)
        GameFileFolderActions(s, a)
        if (!s.shortcutPicker) AddGameButton { GamesDialogState.adding = true }
    }
    if (s.steamGamesX64 && narrow) Box(Modifier.padding(top = 4.dp)) { ClientTag() }
}

@Composable
private fun LibraryFilters(sources: List<String>, source: String, onSource: (String) -> Unit) {
    if (sources.size < 2) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 2.dp),
    ) {
        SourceFilterChip(LIBRARY_ALL, stringResource(R.string.stores_tab_all), source == LIBRARY_ALL, dot = null) { onSource(LIBRARY_ALL) }
        for (src in sources) {
            val store = Store.byId(src)
            val dot = when {
                store != null -> sourceColours(src).dot.copy(alpha = if (StoresState.isSignedIn(store) && StoresState.expired[store] != true) 1f else 0.35f)
                src == Library.SOURCE_STEAM -> sourceColours(src).dot
                else -> null
            }
            SourceFilterChip(src, if (src == Library.ADDED) stringResource(R.string.library_filter_added) else store?.shortLabel ?: sourceLabel(src), source == src, dot) { onSource(src) }
        }
    }
}

@Composable
private fun SourceFilterChip(key: String, label: String, on: Boolean, dot: Color?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(99.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.paneItem("libchip:$key").testTag("library-source-$key").heightIn(min = 40.dp).clip(shape)
            .background(if (on) pal.signal.copy(alpha = 0.16f) else colors.surface)
            .glideBorder(hot, shape, pal.signal, if (on) pal.signal else pal.line)
            .hoverable(src).selectable(selected = on, interactionSource = src, indication = LocalIndication.current, role = Role.Tab, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        if (dot != null) Box(Modifier.size(9.dp).background(dot, CircleShape))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (on || hot) colors.onBackground else colors.onSurfaceVariant, maxLines = 1)
    }
}

private fun entryArt(e: LibraryEntry, tall: Boolean): Any? {
    val inst = e.storeKey?.let { k -> StoresState.installed.firstOrNull { it.key == k } }
    val wide: Any? = e.game?.hero ?: e.item?.imageUrl ?: inst?.sidecar?.hero ?: inst?.sidecar?.cover
    val portrait: Any? = e.game?.art ?: e.item?.tallImageUrl ?: inst?.sidecar?.cover
    return if (tall) portrait ?: wide else wide ?: portrait
}

@Composable
private fun LibraryTile(e: LibraryEntry, capsule: Boolean, first: Boolean, navigation: Modifier, choose: (() -> Unit)?, onOpen: (String) -> Unit) {
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
        placed[0]?.takeIf { it.isAttached }?.let { StoresMotion.markCard(CardMark(e.key, it.boundsInRoot(), corner, (art as? java.io.File)?.path ?: art as? String)) }
        onOpen(e.key)
    }
    Column(
        modifier = Modifier.fillMaxWidth().onGloballyPositioned { placed[0] = it }
            .then(navigation).paneItem("card:${e.key}").then(if (first) Modifier.firstTile().paneEntry() else Modifier).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp)).glideBorder(hot, RoundedCornerShape(10.dp), pal.signal, pal.line)
            .hoverable(src).clickable(role = Role.Button, interactionSource = src, indication = LocalIndication.current, onClick = open)
            .controllerConfirm(onClick = choose ?: open),
    ) {
        CardFace(e.title, wide, tall, download, hot, capsule, dim = !e.installed && download == null,
            badge = { SourceChip(e.source, small = true, modifier = Modifier.align(Alignment.TopStart).padding(6.dp)) }) {
            LibraryMarker(e, download)
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

@Composable
internal fun LibraryMarker(e: LibraryEntry, download: DownloadEntry?) {
    val pal = LocalPalette.current
    val size = if (e.installed) e.storeKey?.let { StoreSizes.onDisk[it] }
        ?: e.game?.gameFiles?.let { FolderSizes.known[it.path] }
        else e.item?.let { StoreSizes.size(it) }?.takeIf { it > 0 }
    when {
        download != null -> Text("${download.percent}%", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pal.signal, maxLines = 1)
        e.item != null && StoresState.isUnfinished(e.item) -> Text(stringResource(R.string.stores_resume), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pal.signal, maxLines = 1)
        else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (size != null && size > 0) Text(formatBytes(size), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            if (!e.installed) Icon(Icons.Outlined.Download, stringResource(R.string.stores_section_not_installed), tint = Color.White, modifier = Modifier.size(12.dp))
        }
    }
}

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
                SourceChip(e.source, small = true)
                Text(meta.joinToString("  ·  "), fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (download != null) Text("${download.percent}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = pal.signal, maxLines = 1)
        else if (!e.installed) Icon(Icons.Outlined.Download, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun HeaderIconButton(description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, key: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.paneItem("btn:$key").size(32.dp).clip(CircleShape).background(if (hot) pal.signal.copy(alpha = 0.14f) else Color.Transparent)
            .glideBorder(hot, CircleShape, pal.signal)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .semantics { contentDescription = description },
    ) { Icon(icon, null, tint = if (hot) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
}

/** Choosing Library in the rail lands on the first game, as Play Steam does on the Steam page. */
@Composable
private fun Modifier.paneEntry(): Modifier {
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
private fun XboxFilterButton() {
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val title = stringResource(R.string.xbox_filter_show)
    var at by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
        HeaderIconButton(title + " · " + xboxFilterLabel(XboxLibraryFilter.current), Icons.Filled.FilterList, "xbox-filter") {
            val from = at ?: return@HeaderIconButton
            Steps.ask(StepAsk(
                anchor = from, side = StepSide.Left, accent = pal.signal, pillCorner = 8.dp, items = XboxLibraryFilter.ALL_FILTERS.size,
                handle = { Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.signal, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            ) {
                StepTitle(title)
                StepList {
                    XboxLibraryFilter.ALL_FILTERS.forEachIndexed { i, filter ->
                        StepChoice(
                            xboxFilterLabel(filter), selected = filter == XboxLibraryFilter.current, enabled = open,
                            modifier = Modifier.fillMaxWidth().stepItem(i).then(if (filter == XboxLibraryFilter.current) Modifier.focusRequester(first) else Modifier),
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
private fun SortButton(s: FrontEndState, a: FrontEndActions) {
    val pal = LocalPalette.current
    val title = stringResource(R.string.stores_sort)
    var at by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
        HeaderIconButton(title, Icons.Filled.Sort, "sort") {
            val from = at ?: return@HeaderIconButton
            Steps.ask(StepAsk(
                anchor = from, side = StepSide.Left, accent = pal.signal, pillCorner = 8.dp, items = SessionPrefs.STORES_SORTS.size,
                handle = { Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.signal, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
