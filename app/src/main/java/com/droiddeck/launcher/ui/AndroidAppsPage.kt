package com.droiddeck.launcher.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.PointerEventPass
import com.droiddeck.launcher.HomeApp
import com.droiddeck.launcher.HomeFolders
import com.droiddeck.launcher.HomeHidden
import com.droiddeck.launcher.HomeIcons
import com.droiddeck.launcher.HomePins
import com.droiddeck.launcher.IconPack
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import com.droiddeck.launcher.HomeWidgets
import com.droiddeck.launcher.R
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private sealed interface AppEntry : AppEntryKey {
    val key: String

    class App(val app: HomeApp.LaunchableApp) : AppEntry {
        override val label get() = app.label
        override val key get() = "app:" + app.key
        override val installedAt get() = app.installedAt
        override val updatedAt get() = app.updatedAt
    }

    class Folder(val folder: HomeFolders.Folder, val apps: List<HomeApp.LaunchableApp>) : AppEntry {
        override val label get() = folder.name
        override val key get() = "folder:" + folder.id
        override val installedAt get() = apps.maxOf { it.installedAt }
        override val updatedAt get() = apps.maxOf { it.updatedAt }
    }

    class Shortcut(val pinned: HomePins.Pinned) : AppEntry {
        override val label get() = pinned.label
        override val key get() = pinned.key
        override val installedAt get() = 0L
        override val updatedAt get() = 0L
    }
}

/** Rearranging the grid: the entry being moved, and what tiles call to start a move or to drop it on their spot. */
private class AppMove(
    /** Move mode is on: tiles can be dragged, and choosing one picks it up or puts it down. */
    val arranging: Boolean,
    val moving: String?, val allowed: Boolean, val start: (String) -> Unit, val tap: (String) -> Unit,
    /** A tile says a finger came down on it, so that a drag from there carries it. */
    val arm: (String) -> Unit,
)

private val LocalAppMove = staticCompositionLocalOf<AppMove?> { null }

private fun indexLetter(label: String): String {
    val c = label.trim().firstOrNull()?.uppercaseChar() ?: return "#"
    return if (c in 'A'..'Z') c.toString() else if (c.isLetter()) c.toString() else "#"
}

private fun searchRank(label: String, q: String): Int? {
    if (label.startsWith(q, ignoreCase = true)) return 0
    if (label.split(' ', '-', '_', '.').any { it.startsWith(q, ignoreCase = true) }) return 1
    if (label.contains(q, ignoreCase = true)) return 2
    return null
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun Modifier.menuKey(onMenu: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    val k = event.nativeKeyEvent
    if (k.keyCode != KeyEvent.KEYCODE_BUTTON_Y && k.keyCode != KeyEvent.KEYCODE_MENU) false
    else {
        if (k.action == KeyEvent.ACTION_DOWN && k.repeatCount == 0) onMenu()
        true
    }
}

internal object AppsView {
    const val ALL = "all"
    const val GAMES = "games"
    const val DOWNLOADED = "downloaded"
    const val SYSTEM = "system"
    const val HIDDEN = "hidden"
    val FILTERS = listOf(ALL, GAMES, DOWNLOADED, SYSTEM, HIDDEN)
    const val NAME_ASC = "nameAsc"
    const val NAME_DESC = "nameDesc"
    const val INSTALLED = "installed"
    const val UPDATED = "updated"
    const val CUSTOM = "custom"
    val SORTS = listOf(NAME_ASC, NAME_DESC, INSTALLED, UPDATED, CUSTOM)

    var filter by mutableStateOf(ALL)
    var sort by mutableStateOf(NAME_ASC)
    /** Entry keys in the order the user arranged them; kept while another sort is in use. */
    var order by mutableStateOf<List<String>>(emptyList())
    private var loaded = false

    private fun prefs(context: Context) = context.getSharedPreferences("home_launcher", Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        filter = p.getString("filter", ALL)?.takeIf { it in FILTERS } ?: ALL
        sort = p.getString("sort", NAME_ASC)?.takeIf { it in SORTS } ?: NAME_ASC
        order = p.getString("customOrder", null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
    }

    /** Stores [shown] as the arranged order; arranged entries that are not on screen now keep their place after it. */
    fun setOrder(context: Context, shown: List<String>) {
        val seen = shown.toSet()
        order = shown + order.filter { it !in seen }
        prefs(context).edit().putString("customOrder", order.joinToString("\n")).apply()
    }

    fun setFilter(context: Context, value: String) {
        filter = value
        prefs(context).edit().putString("filter", value).apply()
    }

    fun setSort(context: Context, value: String) {
        sort = value
        prefs(context).edit().putString("sort", value).apply()
    }

    fun keeps(app: HomeApp.LaunchableApp): Boolean {
        val hidden = app.key in HomeHidden.keys
        return when (filter) {
            HIDDEN -> hidden
            GAMES -> !hidden && app.game
            DOWNLOADED -> !hidden && !app.system
            SYSTEM -> !hidden && app.system
            else -> !hidden
        }
    }

    fun comparator(): Comparator<AppEntryKey> = when (sort) {
        NAME_DESC -> compareBy<AppEntryKey, String>(String.CASE_INSENSITIVE_ORDER) { it.label }.reversed()
        INSTALLED -> compareByDescending<AppEntryKey> { it.installedAt }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
        UPDATED -> compareByDescending<AppEntryKey> { it.updatedAt }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
        else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }
    }
}

internal object AppsGrid {
    const val SMALL = 44
    const val MEDIUM = 52
    const val LARGE = 64
    private val ICON_SIZES = listOf(SMALL, MEDIUM, LARGE)
    const val TIGHT = -12
    const val NORMAL = 0
    const val ROOMY = 16
    private val SPACINGS = listOf(TIGHT, NORMAL, ROOMY)

    var labels by mutableStateOf(true)
        private set
    var iconDp by mutableStateOf(MEDIUM)
        private set
    var spacing by mutableStateOf(NORMAL)
        private set

    private fun prefs(context: Context) = context.getSharedPreferences("home_launcher", Context.MODE_PRIVATE)

    fun load(context: Context) {
        val p = prefs(context)
        labels = p.getBoolean("grid_labels", true)
        iconDp = p.getInt("grid_icon", MEDIUM).takeIf { it in ICON_SIZES } ?: MEDIUM
        spacing = p.getInt("grid_spacing", NORMAL).takeIf { it in SPACINGS } ?: NORMAL
    }

    fun setLabels(context: Context, value: Boolean) {
        labels = value
        prefs(context).edit().putBoolean("grid_labels", value).apply()
    }

    fun setIconDp(context: Context, value: Int) {
        iconDp = value
        prefs(context).edit().putInt("grid_icon", value).apply()
    }

    fun setSpacing(context: Context, value: Int) {
        spacing = value
        prefs(context).edit().putInt("grid_spacing", value).apply()
    }
}

internal interface AppEntryKey {
    val label: String
    val installedAt: Long
    val updatedAt: Long
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun AndroidAppsPage(s: FrontEndState, a: FrontEndActions, modifier: Modifier, onLaunch: (HomeApp.LaunchableApp) -> Unit) {
    val ctx = LocalContext.current
    remember { HomeFolders.load(ctx); HomeWidgets.load(ctx); HomeHidden.load(ctx); AppsView.load(ctx); AppsGrid.load(ctx); true }
    val colors = MaterialTheme.colorScheme
    val narrow = LocalNarrowPane.current
    val padH = if (narrow) 16.dp else 22.dp
    var query by rememberSaveable { mutableStateOf("") }
    var openFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var renameOnOpen by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var iconFor by remember { mutableStateOf<HomeApp.LaunchableApp?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { HomeIcons.overrides }.drop(1).collect { a.onAppIconsChanged() }
    }
    val folders = HomeFolders.folders
    val byKey = remember(s.androidApps) { s.androidApps.associateBy { it.key } }
    val byPkg = remember(s.androidApps) { s.androidApps.filterNot { it.work }.groupBy { it.packageName }.mapNotNull { (pkg, apps) -> apps.singleOrNull()?.let { pkg to it } }.toMap() }
    LaunchedEffect(byPkg) { if (byPkg.isNotEmpty()) HomeFolders.migrateMembers(ctx) { byPkg[it]?.key } }
    val member: (String) -> HomeApp.LaunchableApp? = { byKey[it] ?: byPkg[it] }
    val pins = HomePins.pinned
    val hidden = HomeHidden.keys
    val q = query.trim()
    val filter = AppsView.filter
    val sort = AppsView.sort
    val order = AppsView.order
    val entries: List<AppEntry> = remember(s.androidApps, folders, q, filter, sort, pins, hidden, order) {
        val place = if (sort == AppsView.CUSTOM) order.withIndex().associate { it.value to it.index } else emptyMap()
        // Arranged entries first in their order; anything not arranged yet follows by name.
        val arranged = compareBy<AppEntry> { place[it.key] ?: Int.MAX_VALUE }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
        val by: Comparator<AppEntry> = if (sort == AppsView.CUSTOM) arranged else Comparator { a, b -> AppsView.comparator().compare(a, b) }
        val kept = s.androidApps.filter { AppsView.keeps(it) }
        val shortcuts = if (filter == AppsView.ALL) pins.map { AppEntry.Shortcut(it) } else emptyList()
        if (q.isNotEmpty()) {
            (kept.map { AppEntry.App(it) } + shortcuts).mapNotNull { e -> searchRank(e.label, q)?.let { it to e } }
                .sortedWith(compareBy<Pair<Int, AppEntry>> { it.first }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.second.label })
                .map { it.second }
        } else if (filter != AppsView.ALL) {
            kept.map<HomeApp.LaunchableApp, AppEntry> { AppEntry.App(it) }.sortedWith(by)
        } else {
            val folderEntries = folders.mapNotNull { f ->
                val apps = f.apps.mapNotNull(member).filter { it.key !in hidden }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                if (apps.isEmpty()) null else AppEntry.Folder(f, apps)
            }
            val grouped = folderEntries.flatMap { f -> f.apps.map { it.key } }.toSet()
            (folderEntries + kept.filter { it.key !in grouped }.map { AppEntry.App(it) } + shortcuts).sortedWith(by)
        }
    }
    val grid = rememberLazyGridState()
    val widgetsShown = q.isEmpty() && filter == AppsView.ALL && HomeWidgets.widgets.isNotEmpty()
    val leadItems = if (widgetsShown) 1 else 0
    var arranging by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(q, filter, sort) { if (!arranging) grid.scrollToItem(0) }
    val entriesNow = androidx.compose.runtime.rememberUpdatedState(entries)
    val scope = rememberCoroutineScope()
    val moveTo: (String?, Int) -> Unit = moveTo@{ key, to ->
        val keys = entriesNow.value.map { it.key }
        val from = keys.indexOf(key)
        val at = to.coerceIn(0, keys.lastIndex)
        if (from < 0 || at == from) return@moveTo
        // The grid follows its first visible item by key; when the move changes which item that is, hold the scroll where it was.
        val first = grid.firstVisibleItemIndex - leadItems
        val firstOffset = grid.firstVisibleItemScrollOffset
        AppsView.setOrder(ctx, keys.toMutableList().apply { add(at, removeAt(from)) })
        if (first in minOf(from, at)..maxOf(from, at)) scope.launch { grid.scrollToItem(first + leadItems, firstOffset) }
    }
    // Dragging by touch: the tile the finger came down on, how far the finger has gone, and where the tile sat when it was picked up.
    val armed = remember { arrayOfNulls<String>(1) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragBy by remember { mutableStateOf(Offset.Zero) }
    var dragFrom by remember { mutableStateOf(IntOffset.Zero) }
    val dragCenter: () -> Offset? = {
        grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == dragKey }?.let { Offset(dragFrom.x + dragBy.x + it.size.width / 2f, dragFrom.y + dragBy.y + it.size.height / 2f) }
    }
    val dragOver = {
        val c = dragCenter()
        val over = if (c == null) null else grid.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != dragKey && c.x >= it.offset.x && c.x < it.offset.x + it.size.width && c.y >= it.offset.y && c.y < it.offset.y + it.size.height
        }
        val to = entriesNow.value.indexOfFirst { it.key == over?.key }
        if (to >= 0) moveTo(dragKey, to)
    }
    val appMove = AppMove(
        arranging, moving, allowed = q.isEmpty() && filter == AppsView.ALL,
        start = { key ->
            // Arranging starts from the order on screen, whatever sort made it.
            if (AppsView.sort != AppsView.CUSTOM) {
                AppsView.setOrder(ctx, entriesNow.value.map { it.key })
                AppsView.setSort(ctx, AppsView.CUSTOM)
            }
            arranging = true
            moving = key
        },
        tap = { key ->
            if (moving == null) moving = key
            else {
                if (key != moving) moveTo(moving, entriesNow.value.indexOfFirst { it.key == key })
                moving = null
            }
        },
        arm = { armed[0] = it },
    )
    // Moving only makes sense in the full Custom list: a search, a filter or another sort ends it.
    if (arranging && (!appMove.allowed || sort != AppsView.CUSTOM)) { arranging = false; moving = null }
    if (moving != null && entries.none { it.key == moving }) { moving = null; dragKey = null }
    if (!arranging && dragKey != null) dragKey = null
    BackHandler(arranging) { moving = null; arranging = false }
    LaunchedEffect(dragKey) {
        if (dragKey == null) return@LaunchedEffect
        // Near the top or bottom edge the list scrolls under the held tile.
        val step = 9 * ctx.resources.displayMetrics.density
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            val c = dragCenter() ?: continue
            val info = grid.layoutInfo
            val h = (info.viewportEndOffset - info.afterContentPadding).toFloat()
            val moved = if (c.y < h * 0.12f) grid.scrollBy(-step) else if (c.y > h * 0.88f) grid.scrollBy(step) else 0f
            if (moved != 0f) dragOver()
        }
    }
    LaunchedEffect(moving, order) {
        if (dragKey != null) return@LaunchedEffect
        val at = entries.indexOfFirst { it.key == (moving ?: return@LaunchedEffect) }
        if (at < 0) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        val info = grid.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == at + leadItems }
        if (item == null) grid.scrollToItem(at + leadItems)
        else {
            val top = item.offset.y - info.viewportStartOffset
            val over = item.offset.y + item.size.height - (info.viewportEndOffset - info.afterContentPadding)
            if (top < 0) grid.animateScrollBy(top.toFloat() - 24f) else if (over > 0) grid.animateScrollBy(over.toFloat() + 24f)
        }
    }
    val byName = sort == AppsView.NAME_ASC || sort == AppsView.NAME_DESC
    val newFolder: (HomeApp.LaunchableApp) -> Unit = { app ->
        val f = HomeFolders.create(ctx, ctx.getString(R.string.apps_folder_default), app.key)
        renameOnOpen = true
        openFolder = f.id
    }

    Box(modifier) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.fillMaxWidth().padding(start = padH, end = padH, top = if (narrow) 8.dp else 12.dp)) {
                ModeHeader(
                    stringResource(R.string.content_android_apps), query, { query = it },
                    placeholder = stringResource(R.string.apps_search_hint),
                    clearSearchFocus = openFolder != null || picking || iconFor != null,
                    lead = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 2.dp, end = 4.dp)) {
                            Text(stringResource(R.string.content_android_apps), fontSize = if (narrow) 20.sp else 24.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1)
                            val shownCount = s.androidApps.count { AppsView.keeps(it) } + if (filter == AppsView.ALL) pins.size else 0
                            Text(androidx.compose.ui.res.pluralStringResource(R.plurals.apps_count, shownCount, shownCount), fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1)
                        }
                    },
                ) {
                    AppsFilterButton()
                    AppsSortButton()
                    AppsAppearanceButton(a)
                    HeaderIconButton(stringResource(R.string.apps_add_widget), Icons.Filled.Widgets, "add-widget") {
                        HomeWidgets.loadProviders(ctx)
                        picking = true
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val scroller = q.isEmpty() && byName && entries.size > 16
                LazyVerticalGrid(
                    columns = GridCells.Adaptive((if (narrow) 84 else 96).dp + AppsGrid.spacing.dp),
                    state = grid,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(start = padH, end = if (scroller) padH + 22.dp else padH, top = 12.dp, bottom = 16.dp),
                    modifier = Modifier.fillMaxSize().then(if (!arranging) Modifier else Modifier.onPreviewKeyEvent { event ->
                        val k = event.nativeKeyEvent
                        if (k.keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                            if (k.action == KeyEvent.ACTION_DOWN && k.repeatCount == 0) { moving = null; arranging = false }
                            return@onPreviewKeyEvent true
                        }
                        // With a tile picked up the D-pad carries it; otherwise it moves the cursor as usual.
                        val from = entriesNow.value.indexOfFirst { it.key == moving }
                        if (from < 0) return@onPreviewKeyEvent false
                        val columns = (grid.layoutInfo.visibleItemsInfo.maxOfOrNull { it.column } ?: 0) + 1
                        val to = when (k.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> from - 1
                            KeyEvent.KEYCODE_DPAD_RIGHT -> from + 1
                            KeyEvent.KEYCODE_DPAD_UP -> if (from - columns >= 0) from - columns else from
                            KeyEvent.KEYCODE_DPAD_DOWN -> if (from + columns <= entriesNow.value.lastIndex) from + columns else from
                            else -> return@onPreviewKeyEvent false
                        }
                        if (k.action == KeyEvent.ACTION_DOWN) moveTo(moving, to)
                        true
                    }.pointerInput(leadItems) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            armed[0] = null
                            var total = Offset.Zero
                            var origin = Offset.Zero
                            var decided = false
                            while (true) {
                                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                total += change.positionChange()
                                if (!decided && total.getDistance() > viewConfiguration.touchSlop) {
                                    decided = true
                                    // A finger that rests on a tile before moving carries it; a quick swipe scrolls the list.
                                    val key = armed[0]
                                    val item = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
                                    if (key != null && item != null && change.uptimeMillis - down.uptimeMillis >= 120) {
                                        origin = total
                                        dragFrom = item.offset
                                        dragBy = Offset.Zero
                                        moving = key
                                        dragKey = key
                                    }
                                }
                                if (dragKey != null) {
                                    change.consume()
                                    dragBy = total - origin
                                    dragOver()
                                }
                            }
                            if (dragKey != null) {
                                dragKey = null
                                moving = null
                            }
                        }
                    }),
                ) {
                    if (widgetsShown) item(key = "widgets", span = { GridItemSpan(maxLineSpan) }) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val maxW = maxWidth
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            ) {
                                for (w in HomeWidgets.widgets) key(w.id) { WidgetCard(w, maxW.value.toInt()) }
                            }
                        }
                    }
                    if (entries.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        Note(
                            when {
                                q.isNotEmpty() -> stringResource(R.string.apps_no_match, q)
                                filter == AppsView.HIDDEN -> stringResource(R.string.apps_hidden_none)
                                else -> stringResource(R.string.content_no_android_apps)
                            },
                        )
                    }
                    items(entries, key = { it.key }) { entry ->
                        CompositionLocalProvider(LocalAppMove provides appMove) { Box(
                            if (entry.key == dragKey) Modifier.zIndex(1f).graphicsLayer {
                                val at = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == entry.key }?.offset ?: return@graphicsLayer
                                translationX = dragFrom.x + dragBy.x - at.x
                                translationY = dragFrom.y + dragBy.y - at.y
                            } else Modifier.animateItemPlacement(),
                        ) {
                        when (entry) {
                            is AppEntry.App -> AppTile(
                                entry.app, folderId = null, onLaunch = onLaunch, onNewFolder = newFolder, onChangeIcon = { iconFor = it },
                                first = entry === entries.first(),
                            )
                            is AppEntry.Shortcut -> PinnedTile(entry.pinned, first = entry === entries.first())
                            is AppEntry.Folder -> FolderTile(entry, first = entry === entries.first(), onOpen = { renameOnOpen = false; openFolder = entry.folder.id }) {
                                renameOnOpen = true
                                openFolder = entry.folder.id
                            }
                        }
                        } }
                    }
                }
                if (arranging) Row(
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).clip(Shape12).background(colors.surfaceVariant).padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Text(stringResource(R.string.apps_move_hint), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                    Text(
                        stringResource(R.string.apps_move_done), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LocalPalette.current.signal,
                        modifier = Modifier.clip(Shape12).clickable(role = Role.Button) { arranging = false; moving = null }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                if (scroller) AlphabetScroller(entries, grid, leadItems, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 4.dp, top = 8.dp, bottom = 8.dp))
            }
        }

        val folder = folders.firstOrNull { it.id == openFolder }
        LaunchedEffect(folder == null, openFolder) { if (folder == null && openFolder != null) openFolder = null }
        AnimatedVisibility(
            folder != null, enter = fadeIn(Motion.tw(180)), exit = fadeOut(Motion.tw(160)),
            modifier = Modifier.fillMaxSize().zIndex(2f),
        ) {
            var last by remember { mutableStateOf(folder) }
            if (folder != null) last = folder
            last?.let { f ->
                FolderSheet(f, f.apps.mapNotNull(member).filter { it.key !in hidden }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }), renameOnOpen, onLaunch, newFolder, { iconFor = it }) {
                    openFolder = null
                    renameOnOpen = false
                }
            }
        }
        AnimatedVisibility(
            picking, enter = fadeIn(Motion.tw(180)), exit = fadeOut(Motion.tw(160)),
            modifier = Modifier.fillMaxSize().zIndex(3f),
        ) { WidgetPicker(padH) { picking = false } }
        AnimatedVisibility(
            iconFor != null, enter = fadeIn(Motion.tw(180)), exit = fadeOut(Motion.tw(160)),
            modifier = Modifier.fillMaxSize().zIndex(4f),
        ) {
            var last by remember { mutableStateOf(iconFor) }
            iconFor?.let { last = it }
            last?.let { app -> AppIconPicker(app, padH) { iconFor = null } }
        }
    }
}

@Composable
internal fun SwipeLock() {
    DisposableEffect(Unit) {
        PageSwipe.locks++
        onDispose { PageSwipe.locks-- }
    }
}

@Composable
private fun appsFilterLabel(filter: String): String = stringResource(
    when (filter) {
        AppsView.GAMES -> R.string.apps_filter_games
        AppsView.DOWNLOADED -> R.string.apps_filter_downloaded
        AppsView.SYSTEM -> R.string.apps_filter_system
        AppsView.HIDDEN -> R.string.apps_filter_hidden
        else -> R.string.apps_filter_all
    },
)

@Composable
private fun appsSortLabel(sort: String): String = stringResource(
    when (sort) {
        AppsView.NAME_DESC -> R.string.apps_sort_name_desc
        AppsView.INSTALLED -> R.string.apps_sort_installed
        AppsView.UPDATED -> R.string.apps_sort_updated
        AppsView.CUSTOM -> R.string.apps_sort_custom
        else -> R.string.apps_sort_name_asc
    },
)

@Composable
private fun AppsFilterButton() = HeaderChoiceButton(
    stringResource(R.string.apps_filter), Icons.Filled.FilterAlt, "apps-filter",
    AppsView.FILTERS, AppsView.filter, { appsFilterLabel(it) }, AppsView::setFilter,
)

@Composable
private fun AppsSortButton() = HeaderChoiceButton(
    stringResource(R.string.stores_sort), Icons.Filled.Sort, "apps-sort",
    AppsView.SORTS, AppsView.sort, { appsSortLabel(it) }, AppsView::setSort,
)

@Composable
private fun HeaderChoiceButton(
    title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, key: String,
    options: List<String>, current: String, label: @Composable (String) -> String, onPick: (Context, String) -> Unit,
) {
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    var at by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
        HeaderIconButton("$title · ${label(current)}", icon, key) {
            val from = at ?: return@HeaderIconButton
            Steps.ask(StepAsk(
                anchor = from, side = StepSide.Left, accent = pal.signal, pillCorner = 8.dp, items = options.size,
                handle = { Icon(icon, null, tint = pal.signal, modifier = Modifier.size(18.dp)) },
            ) {
                StepTitle(title)
                StepList {
                    options.forEachIndexed { i, option ->
                        StepChoice(
                            label(option), selected = option == current, enabled = open,
                            modifier = Modifier.fillMaxWidth().stepItem(i).then(if (option == current) Modifier.focusRequester(first) else Modifier),
                        ) {
                            onPick(ctx, option)
                            Steps.fold()
                        }
                    }
                }
            })
        }
    }
}

@Composable
private fun AlphabetScroller(entries: List<AppEntry>, grid: LazyGridState, leadItems: Int, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()
    val firstAt = remember(entries) {
        val map = LinkedHashMap<String, Int>()
        entries.forEachIndexed { i, e -> map.putIfAbsent(indexLetter(e.label), i) }
        map
    }
    val letters = remember(firstAt) { firstAt.keys.toList() }
    var dragging by remember { mutableStateOf<String?>(null) }
    val current by remember(entries, leadItems) {
        derivedStateOf {
            val i = (grid.firstVisibleItemIndex - leadItems).coerceIn(0, (entries.size - 1).coerceAtLeast(0))
            entries.getOrNull(i)?.let { indexLetter(it.label) }
        }
    }
    var height by remember { mutableIntStateOf(1) }
    val jump: (Float) -> Unit = { y ->
        val i = ((y / height) * letters.size).toInt().coerceIn(0, letters.size - 1)
        val letter = letters[i]
        if (letter != dragging) {
            dragging = letter
            val at = firstAt[letter] ?: 0
            scope.launch { grid.scrollToItem(if (at == 0) 0 else leadItems + at) }
        }
    }
    Box(modifier.width(22.dp)) {
        Column(
            verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
                .clip(RoundedCornerShape(11.dp))
                .background(if (dragging != null) colors.surface.copy(alpha = 0.85f) else Color.Transparent)
                .onSizeChanged { height = it.height.coerceAtLeast(1) }
                .pointerInput(letters) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        jump(down.position.y)
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                            jump(c.position.y)
                        }
                        dragging = null
                    }
                },
        ) {
            for (l in letters) {
                val on = l == (dragging ?: current)
                Text(
                    l, fontSize = 10.sp, lineHeight = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) pal.signal else colors.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
        }
        dragging?.let { l ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.align(Alignment.Center).offset { IntOffset(-64.dp.roundToPx(), 0) }.size(56.dp)
                    .clip(CircleShape).background(pal.signal),
            ) { Text(l, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = pal.onPrimary) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileFrame(
    id: String, first: Boolean, onClick: () -> Unit, onMenu: () -> Unit,
    moveKey: String? = null,
    content: @Composable (hot: Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val move = LocalAppMove.current?.takeIf { moveKey != null && it.arranging }
    val lifted = move != null && move.moving == moveKey
    val hot = rememberHot(src) || lifted
    val pressed by src.collectIsPressedAsState()
    // In move mode, choosing a tile picks it up or puts the held one down there.
    val onClick = if (move != null) ({ move.tap(moveKey!!) }) else onClick
    val onMenu = if (move != null) ({}) else onMenu
    val wiggle = if (move != null && !lifted) {
        val turn by rememberInfiniteTransition(label = "appWiggle").animateFloat(
            -1.2f, 1.2f, infiniteRepeatable(tween(140 + (id.hashCode() and 31), easing = LinearEasing), RepeatMode.Reverse), label = "appWiggle",
        )
        turn
    } else 0f
    val scale by animateFloatAsState(if (pressed) 0.95f else if (lifted) 1.1f else if (hot) 1.04f else 1f, Motion.sp(0.55f, Spring.StiffnessMedium), label = "appScale")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
            .paneItem("tile:$id").then(if (first) Modifier.firstTile().paneEntry() else Modifier)
            .then(if (move != null) Modifier.pointerInput(moveKey) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); move.arm(moveKey!!) } } else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = wiggle }
            .clip(Shape12)
            .background(if (hot) colors.surface else colors.surface.copy(alpha = 0.55f))
            .glideBorder(hot, Shape12, pal.signal, hotWidth = 2.5.dp)
            .hoverable(src)
            .combinedClickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onLongClick = onMenu, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .menuKey(onMenu)
            .padding(horizontal = 6.dp, vertical = 10.dp),
    ) { content(hot) }
}

@Composable
private fun TileLabel(text: String) = Text(
    text, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground,
    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
)

@Composable
private fun AppIcon(app: HomeApp.LaunchableApp, size: androidx.compose.ui.unit.Dp) {
    val icon = app.icon
    if (icon != null) Image(icon.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
    else Box(Modifier.size(size).clip(CircleShape).background(artBrush(hueOf(app.label))))
}

@Composable
private fun AppTile(
    app: HomeApp.LaunchableApp, folderId: String?, first: Boolean,
    onLaunch: (HomeApp.LaunchableApp) -> Unit, onNewFolder: (HomeApp.LaunchableApp) -> Unit,
    onChangeIcon: (HomeApp.LaunchableApp) -> Unit, modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    if (menu && LocalAppMove.current?.arranging == true) menu = false
    Box(modifier) {
        TileFrame(app.key, first, onClick = { onLaunch(app) }, onMenu = { menu = true }, moveKey = if (folderId == null) "app:" + app.key else null) {
            AppIcon(app, AppsGrid.iconDp.dp)
            if (AppsGrid.labels) TileLabel(app.label)
        }
        AppMenu(app, folderId, menu, onDismiss = { menu = false }, onNewFolder = onNewFolder, onChangeIcon = onChangeIcon)
    }
}

@Composable
private fun AppMenu(
    app: HomeApp.LaunchableApp, folderId: String?, open: Boolean,
    onDismiss: () -> Unit, onNewFolder: (HomeApp.LaunchableApp) -> Unit, onChangeIcon: (HomeApp.LaunchableApp) -> Unit,
) {
    val ctx = LocalContext.current
    var choosing by remember { mutableStateOf(false) }
    var shortcuts by remember { mutableStateOf<List<Pair<ShortcutInfo, Bitmap?>>>(emptyList()) }
    LaunchedEffect(open) {
        if (!open) {
            choosing = false
            return@LaunchedEffect
        }
        shortcuts = withContext(Dispatchers.IO) {
            HomeApp.shortcuts(ctx, app).map { it to HomeApp.shortcutIcon(ctx, it, 64) }
        }
    }
    val isHidden = app.key in HomeHidden.keys
    val move = LocalAppMove.current
    AnchoredMenu(open, onDismiss = onDismiss, title = app.label, compact = true, minWidth = 200.dp) { first ->
        if (choosing) {
            val others = HomeFolders.folders.filter { it.id != folderId }
            var firstUsed = false
            for (f in others) key(f.id) {
                MenuItem(f.name, checked = false, focusRequester = if (!firstUsed) first.also { firstUsed = true } else null) {
                    HomeFolders.add(ctx, f.id, app.key)
                    onDismiss()
                }
            }
            MenuItem(stringResource(R.string.apps_new_folder), checked = false, focusRequester = if (others.isEmpty()) first else null) {
                onDismiss()
                onNewFolder(app)
            }
        } else {
            shortcuts.forEachIndexed { i, (info, icon) ->
                key(info.id) {
                    MenuItem(
                        info.shortLabel?.toString().orEmpty(), checked = false,
                        focusRequester = if (i == 0) first else null,
                        leading = { MenuIcon(icon) },
                    ) {
                        onDismiss()
                        runCatching { HomeApp.startShortcut(ctx, info) }
                    }
                }
            }
            if (shortcuts.isNotEmpty()) Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(LocalPalette.current.line))
            if (!isHidden) MenuItem(stringResource(R.string.apps_add_to_folder), checked = false, focusRequester = if (shortcuts.isEmpty()) first else null) { choosing = true }
            if (folderId == null && move?.allowed == true) MenuItem(stringResource(R.string.apps_move), checked = false) {
                onDismiss()
                move.start("app:" + app.key)
            }
            if (folderId != null) MenuItem(stringResource(R.string.apps_remove_from_folder), checked = false) {
                HomeFolders.remove(ctx, app.key)
                onDismiss()
            }
            MenuItem(stringResource(if (isHidden) R.string.apps_unhide else R.string.apps_hide), checked = false,
                focusRequester = if (isHidden && shortcuts.isEmpty()) first else null) {
                onDismiss()
                HomeHidden.set(ctx, app.key, !isHidden)
            }
            if (IconPack.active(ctx) != null) MenuItem(stringResource(R.string.apps_change_icon), checked = false) {
                onDismiss()
                onChangeIcon(app)
            }
            if (app.key in HomeIcons.overrides) MenuItem(stringResource(R.string.apps_reset_icon), checked = false) {
                onDismiss()
                HomeIcons.clear(ctx, app.key)
            }
            MenuItem(stringResource(R.string.apps_app_info), checked = false) {
                onDismiss()
                runCatching { HomeApp.openAppInfo(ctx, app) }
            }
            if (HomeApp.canUninstall(app)) MenuItem(stringResource(R.string.apps_uninstall), checked = false) {
                onDismiss()
                runCatching { HomeApp.uninstall(ctx, app) }
            }
        }
    }
}

@Composable
private fun MenuIcon(icon: Bitmap?) {
    if (icon != null) Image(icon.asImageBitmap(), null, modifier = Modifier.size(20.dp))
    else Spacer(Modifier.size(20.dp))
}

@Composable
private fun PinnedTile(pinned: HomePins.Pinned, first: Boolean) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Box {
        val move = LocalAppMove.current
        if (menu && move?.arranging == true) menu = false
        TileFrame(pinned.key, first, onClick = { runCatching { HomeApp.startShortcut(ctx, pinned.info) } }, onMenu = { menu = true }, moveKey = pinned.key) {
            Box {
                val icon = pinned.icon
                if (icon != null) Image(icon.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.size(AppsGrid.iconDp.dp))
                else Box(Modifier.size(AppsGrid.iconDp.dp).clip(CircleShape).background(artBrush(hueOf(pinned.label))))
            }
            if (AppsGrid.labels) TileLabel(pinned.label)
        }
        AnchoredMenu(menu, onDismiss = { menu = false }, title = pinned.label, compact = true, minWidth = 180.dp) { firstItem ->
            MenuItem(stringResource(R.string.apps_shortcut_remove), checked = false, detail = pinned.appLabel, focusRequester = firstItem) {
                menu = false
                HomePins.unpin(ctx, pinned)
            }
            if (move?.allowed == true) MenuItem(stringResource(R.string.apps_move), checked = false) {
                menu = false
                move.start(pinned.key)
            }
        }
    }
}

@Composable
private fun FolderIcon(apps: List<HomeApp.LaunchableApp>, size: androidx.compose.ui.unit.Dp) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val cell = (size - 14.dp) / 2
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 3.5f)).background(colors.surfaceVariant)
            .glideBorder(false, RoundedCornerShape(size / 3.5f), pal.signal, pal.line2)
            .padding(5.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (row in apps.take(4).chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (a in row) AppIcon(a, cell)
            }
        }
    }
}

@Composable
private fun FolderTile(entry: AppEntry.Folder, first: Boolean, onOpen: () -> Unit, onRename: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Box {
        val move = LocalAppMove.current
        if (menu && move?.arranging == true) menu = false
        TileFrame(entry.key, first, onClick = onOpen, onMenu = { menu = true }, moveKey = entry.key) {
            FolderIcon(entry.apps, AppsGrid.iconDp.dp)
            if (AppsGrid.labels) TileLabel(entry.folder.name)
        }
        AnchoredMenu(menu, onDismiss = { menu = false }, title = entry.folder.name, compact = true, minWidth = 160.dp) { firstItem ->
            MenuItem(stringResource(R.string.apps_folder_rename), checked = false, focusRequester = firstItem) {
                menu = false
                onRename()
            }
            if (move?.allowed == true) MenuItem(stringResource(R.string.apps_move), checked = false) {
                menu = false
                move.start(entry.key)
            }
            MenuItem(stringResource(R.string.apps_folder_ungroup), checked = false) {
                menu = false
                HomeFolders.delete(ctx, entry.folder.id)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FolderSheet(
    folder: HomeFolders.Folder, apps: List<HomeApp.LaunchableApp>, renameFirst: Boolean,
    onLaunch: (HomeApp.LaunchableApp) -> Unit, onNewFolder: (HomeApp.LaunchableApp) -> Unit,
    onChangeIcon: (HomeApp.LaunchableApp) -> Unit, onClose: () -> Unit,
) {
    SwipeLock()
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var renaming by remember(folder.id) { mutableStateOf(renameFirst) }
    var name by remember(folder.id) { mutableStateOf(if (renameFirst) "" else folder.name) }
    val commit = {
        val n = name.trim()
        if (n.isNotEmpty() && n != folder.name) HomeFolders.rename(ctx, folder.id, n)
        renaming = false
    }
    val firstApp = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current.inputMode
    val ff = LocalFrontFocus.current
    val grid = rememberLazyGridState()
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(renaming, inputMode) {
        if (!renaming && !closing && inputMode == InputMode.Keyboard && apps.isNotEmpty()) {
            grid.scrollToItem(0)
            repeat(2) { androidx.compose.runtime.withFrameNanos { } }
            if (!closing) runCatching { firstApp.requestFocus() }
        }
    }
    val close = {
        closing = true
        if (renaming) commit()
        onClose()
        if (inputMode == InputMode.Keyboard && ff != null) {
            runCatching { ff.items.getValue("tile:folder:${folder.id}").requestFocus() }
                .onFailure { runCatching { ff.firstTile.requestFocus() } }
        }
    }
    BackHandler { close() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { awaitEachGesture { val d = awaitFirstDown(); d.consume(); close() } },
    ) {
        val shown = remember { androidx.compose.animation.core.MutableTransitionState(false) }.apply { targetState = true }
        AnimatedVisibility(shown, enter = scaleIn(Motion.sp(0.75f), initialScale = 0.92f) + fadeIn(Motion.tw(160)), exit = scaleOut(Motion.tw(140)) + fadeOut(Motion.tw(140))) {
            Column(
                modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(0.9f).heightIn(max = 520.dp)
                    .clip(Shape16).background(pal.surfaceVariant.copy(alpha = 0.97f))
                    .glideBorder(false, Shape16, pal.signal, pal.signal.copy(alpha = 0.22f))
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } }
                    .controllerBack(close)
                    .focusProperties {
                        exit = { dir ->
                            if (dir == FocusDirection.Up || dir == FocusDirection.Down || dir == FocusDirection.Left || dir == FocusDirection.Right) FocusRequester.Cancel
                            else FocusRequester.Default
                        }
                    }
                    .focusGroup()
                    .padding(16.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    if (renaming) {
                        AdbTextField(
                            value = name, onValueChange = { name = it.take(40) }, label = stringResource(R.string.apps_folder_name),
                            keyboardType = KeyboardType.Text, imeAction = ImeAction.Done, compact = true, showLabel = false,
                            placeholder = folder.name, focusOnShow = true, onDone = { commit() },
                        )
                    } else {
                        val src = remember { MutableInteractionSource() }
                        val hot = rememberHot(src)
                        val rename = { renaming = true }
                        Text(
                            folder.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.onBackground,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            // A faint chip at rest shows the name can be selected, now that the pencil is gone.
                            modifier = Modifier.clip(Shape12)
                                .background(if (hot) pal.signal.copy(alpha = 0.12f) else colors.onBackground.copy(alpha = 0.07f))
                                .glideBorder(hot, Shape12, pal.signal)
                                .hoverable(src)
                                .clickable(interactionSource = src, indication = LocalIndication.current, onClickLabel = stringResource(R.string.apps_folder_rename), role = Role.Button, onClick = rename)
                                .controllerConfirm(onClick = rename)
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(92.dp + AppsGrid.spacing.dp),
                    state = grid,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(apps, key = { it.key }) { app ->
                        AppTile(
                            app, folderId = folder.id, first = false, onLaunch = { onClose(); onLaunch(it) }, onNewFolder = onNewFolder, onChangeIcon = onChangeIcon,
                            modifier = if (app === apps.first()) Modifier.focusRequester(firstApp) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetCard(w: HomeWidgets.Placed, maxWidthDp: Int) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val width = w.widthDp.coerceAtMost(maxWidthDp)
    val activity = remember(ctx) { ctx.findActivity() }
    Box {
        Box(
            Modifier.size(width.dp, w.heightDp.dp)
                .clip(Shape16)
                .background(colors.surface.copy(alpha = 0.35f))
                .glideBorder(hot || menu, Shape16, pal.signal)
                .hoverable(src).focusable(interactionSource = src)
                .controllerConfirm { menu = true }
                .menuKey { menu = true },
        ) {
            AndroidView(
                factory = { c ->
                    (HomeWidgets.createView(c, w.id) ?: android.view.View(c)).apply {
                        val radius = 16 * c.resources.displayMetrics.density
                        outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) =
                                outline.setRoundRect(0, 0, view.width, view.height, radius)
                        }
                        clipToOutline = true
                    }
                },
                update = { v ->
                    if (v is HomeWidgets.HomeWidgetView) {
                        v.onLongPress = { menu = true }
                        HomeWidgets.applySize(v, width, w.heightDp)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        val (minW, minH) = remember(w.id) { HomeWidgets.minSize(ctx, w.id) }
        val index = HomeWidgets.widgets.indexOfFirst { it.id == w.id }
        val label = remember(w.id) { HomeWidgets.info(ctx, w.id)?.loadLabel(ctx.packageManager) ?: "" }
        AnchoredMenu(menu, onDismiss = { menu = false }, title = label, compact = true, minWidth = 180.dp) { first ->
            val step = 48
            MenuItem(stringResource(R.string.apps_widget_wider), checked = false, enabled = width < maxWidthDp, focusRequester = first) {
                HomeWidgets.resize(ctx, w.id, (width + step).coerceAtMost(maxWidthDp), w.heightDp)
            }
            MenuItem(stringResource(R.string.apps_widget_narrower), checked = false, enabled = width > minW) {
                HomeWidgets.resize(ctx, w.id, (width - step).coerceAtLeast(minW), w.heightDp)
            }
            MenuItem(stringResource(R.string.apps_widget_taller), checked = false, enabled = w.heightDp < 480) {
                HomeWidgets.resize(ctx, w.id, width, (w.heightDp + step).coerceAtMost(480))
            }
            MenuItem(stringResource(R.string.apps_widget_shorter), checked = false, enabled = w.heightDp > minH) {
                HomeWidgets.resize(ctx, w.id, width, (w.heightDp - step).coerceAtLeast(minH))
            }
            if (index > 0) MenuItem(stringResource(R.string.apps_widget_move_earlier), checked = false) { HomeWidgets.move(ctx, w.id, -1) }
            if (index in 0 until HomeWidgets.widgets.size - 1) MenuItem(stringResource(R.string.apps_widget_move_later), checked = false) { HomeWidgets.move(ctx, w.id, 1) }
            if (activity != null && HomeWidgets.canReconfigure(ctx, w.id)) MenuItem(stringResource(R.string.apps_widget_configure), checked = false) {
                menu = false
                HomeWidgets.reconfigure(activity, w.id)
            }
            MenuItem(stringResource(R.string.apps_widget_remove), checked = false) {
                menu = false
                HomeWidgets.remove(ctx, w.id)
            }
        }
    }
}

@Composable
private fun WidgetPicker(padH: androidx.compose.ui.unit.Dp, onClose: () -> Unit) {
    SwipeLock()
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val activity = remember(ctx) { ctx.findActivity() }
    BackHandler { onClose() }
    val providers = HomeWidgets.providers
    Column(Modifier.fillMaxSize().background(colors.background).pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = padH, vertical = 14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "back", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Box(Modifier.padding(bottom = 10.dp)) { BackLink(stringResource(R.string.rail_apps), compact = true, onClick = onClose) }
                    PageHeader(stringResource(R.string.apps_add_widget)) {
                        if (providers != null) Chip(providers.size.toString(), ok = false)
                    }
                }
            }
            when {
                providers == null -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { Note(stringResource(R.string.apps_widgets_loading)) }
                providers.isEmpty() -> item(key = "none", span = { GridItemSpan(maxLineSpan) }) { Note(stringResource(R.string.apps_widgets_none)) }
                else -> items(providers, key = { it.info.provider.flattenToString() }) { p ->
                    ProviderCard(p, first = p === providers.first()) {
                        onClose()
                        if (activity != null) HomeWidgets.add(activity, p)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(p: HomeWidgets.Provider, first: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Column(
        modifier = Modifier.fillMaxWidth().paneItem("widget:" + p.info.provider.flattenToString()).then(if (first) Modifier.firstTile() else Modifier)
            .clip(Shape12).background(colors.surface)
            .glideBorder(hot, Shape12, pal.signal, hotWidth = 2.5.dp)
            .hoverable(src)
            .clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().aspectRatio(1.6f).background(colors.surfaceVariant).padding(10.dp),
        ) {
            val preview = p.preview
            if (preview != null) Image(preview.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(p.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val cells = "${(p.widthDp / 80f).roundToInt().coerceAtLeast(1)} × ${(p.heightDp / 80f).roundToInt().coerceAtLeast(1)}"
            Text(if (p.appLabel == p.label) cells else "${p.appLabel} · $cells", fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PinRequestPrompt(label: String, appLabel: String, icon: Bitmap?, wide: Boolean, onAdd: () -> Unit, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    BackHandler { onCancel() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume(); onCancel() } },
    ) {
        val shown = remember { androidx.compose.animation.core.MutableTransitionState(false) }.apply { targetState = true }
        AnimatedVisibility(shown, enter = scaleIn(Motion.sp(0.75f), initialScale = 0.92f) + fadeIn(Motion.tw(160))) {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.widthIn(min = 280.dp, max = 420.dp)
                    .clip(Shape16).background(pal.surfaceVariant.copy(alpha = 0.97f))
                    .glideBorder(false, Shape16, pal.signal, pal.signal.copy(alpha = 0.22f))
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } }
                    .controllerBack(onCancel)
                    .padding(18.dp),
            ) {
                Text(
                    stringResource(R.string.apps_pin_title).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp, color = colors.onSurfaceVariant,
                )
                if (wide && icon != null) Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(Shape12).background(MaterialTheme.colorScheme.surface).padding(10.dp),
                ) { Image(icon.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth()) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (!wide) {
                        if (icon != null) Image(icon.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.size(52.dp))
                        else Box(Modifier.size(52.dp).clip(CircleShape).background(artBrush(hueOf(label))))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(appLabel, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                    SecondaryButton(stringResource(R.string.common_cancel), onClick = onCancel)
                    PrimaryButton(stringResource(R.string.apps_pin_add), onClick = onAdd)
                }
            }
        }
    }
}
