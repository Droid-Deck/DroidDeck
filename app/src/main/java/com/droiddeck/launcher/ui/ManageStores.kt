package com.droiddeck.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreTab
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.arrangeStoreTabs

internal object StoreTabsState {
    var tabs by mutableStateOf<List<StoreTab>>(emptyList())
        private set
    var libraryPlaces by mutableStateOf(DEFAULT_LIBRARY_PLACES)
        private set
    var libraryHidden by mutableStateOf<Set<String>>(emptySet())
        private set

    fun load(context: android.content.Context) {
        SessionPrefs.settleStoresTabOrder(context, Store.tabbed.map { it.id })
        tabs = arrangeStoreTabs(SessionPrefs.storesTabOrder(context), SessionPrefs.storesTabHidden(context))
        libraryPlaces = SessionPrefs.libraryTabPlaces(context) ?: DEFAULT_LIBRARY_PLACES
        libraryHidden = SessionPrefs.libraryTabsHidden(context) + listOfNotNull(LIBRARY_HIDDEN.takeUnless { SessionPrefs.libraryShowHidden(context) })
    }

    /** [ids] may hold the Library's own tabs among the stores; each one's place is kept as the tab it follows. */
    fun reorder(context: android.content.Context, ids: List<String>) {
        if (ids.any { it in LIBRARY_OWN_TABS }) {
            val places = libraryPlacesOf(ids)
            libraryPlaces = places
            SessionPrefs.setLibraryTabPlaces(context, places)
        }
        val byId = tabs.associateBy { it.store.id }
        val order = ids.mapNotNull { byId[it] }
        if (order.map { it.store } != tabs.map { it.store }) {
            tabs = order
            save(context, arranged = true)
        }
    }

    // The Stores tab needs at least one shown store to open on.
    fun setShown(context: android.content.Context, store: Store, show: Boolean): Boolean {
        if (!show && tabs.count { it.shown } <= 1) return false
        tabs = tabs.map { if (it.store == store) it.copy(shown = show) else it }
        save(context, arranged = false)
        return true
    }

    fun setLibraryShown(context: android.content.Context, id: String, show: Boolean) {
        libraryHidden = if (show) libraryHidden - id else libraryHidden + id
        if (id == LIBRARY_HIDDEN) SessionPrefs.setLibraryShowHidden(context, show)
        else SessionPrefs.setLibraryTabsHidden(context, libraryHidden - LIBRARY_HIDDEN)
    }

    private fun save(context: android.content.Context, arranged: Boolean) =
        SessionPrefs.saveStoresTabs(context, tabs.map { it.store.id }, tabs.filterNot { it.shown }.map { it.store.id }.toSet(), arranged)
}

/** The Library tabs that are not stores but sort among them in Manage stores. Only Favorites, Custom games and Hidden can be turned off; Hidden starts off. */
internal val LIBRARY_OWN_TABS = listOf(LIBRARY_FAVORITES, Library.SOURCE_STEAM, Library.ADDED, LIBRARY_HIDDEN)
internal val LIBRARY_HIDEABLE_TABS = setOf(LIBRARY_FAVORITES, Library.ADDED, LIBRARY_HIDDEN)

/** Favorites, then Steam, ahead of the stores; Custom games and Hidden after them (null: the end). */
internal val DEFAULT_LIBRARY_PLACES: List<Pair<String, String?>> =
    listOf(LIBRARY_FAVORITES to "", Library.SOURCE_STEAM to LIBRARY_FAVORITES, Library.ADDED to null, LIBRARY_HIDDEN to null)

/**
 * Puts the Library's own tabs into a store tab order. Each goes after the tab it follows ("" for first; null or a tab that is gone
 * for last), in [places] order, so a place may name a tab placed before it. A tab [places] misses keeps its default place.
 */
internal fun placeLibraryTabs(stores: List<String>, places: List<Pair<String, String?>>): List<String> {
    var out = stores.filterNot { it in LIBRARY_OWN_TABS }
    for ((id, after) in places.filter { it.first in LIBRARY_OWN_TABS } + DEFAULT_LIBRARY_PLACES) {
        if (id in out) continue
        val at = when {
            after == "" -> 0
            after != null && after in out -> out.indexOf(after) + 1
            else -> out.size
        }
        out = out.take(at) + id + out.drop(at)
    }
    return out
}

/** The places that rebuild [order] with [placeLibraryTabs]. */
internal fun libraryPlacesOf(order: List<String>): List<Pair<String, String>> =
    order.mapIndexedNotNull { i, id -> if (id in LIBRARY_OWN_TABS) id to (order.getOrNull(i - 1) ?: "") else null }

internal object ManageStores {
    var open by mutableStateOf(false)
    /** Opened from the Library, which also lists the Custom games tab. */
    var custom by mutableStateOf(false)
    var returnTo: FocusRequester? = null
}

internal enum class StoreAccount { SIGNED_IN, SIGNED_OUT, EXPIRED, NEEDS_KEY }

internal fun storeAccount(store: Store): StoreAccount = when {
    StoresState.isSignedIn(store) && StoresState.expired[store] == true -> StoreAccount.EXPIRED
    StoresState.isSignedIn(store) -> StoreAccount.SIGNED_IN
    store == Store.ITCH -> StoreAccount.NEEDS_KEY
    else -> StoreAccount.SIGNED_OUT
}

internal fun startStoreSignIn(context: android.content.Context, store: Store, at: Rect?, start: Color, onItchKey: () -> Unit) {
    when {
        store == Store.ITCH -> onItchKey()
        at == null || Motion.scale == 0f || StoresMotion.signIn != null -> StoresState.signIn(context, store)
        else -> StoresMotion.signIn = SignInFlood(store, at, start, sourceColours(store.id).dot)
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun SideSheet(open: Boolean, onClose: () -> Unit, returnFocus: FocusRequester?, tag: String, content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPalette.current
    BackHandler(enabled = open) { onClose() }
    var wasOpen by remember { mutableStateOf(false) }
    val closeInput = androidx.compose.ui.platform.LocalInputModeManager.current
    LaunchedEffect(open) {
        if (open) wasOpen = true
        else if (wasOpen && returnFocus != null && closeInput.inputMode == androidx.compose.ui.input.InputMode.Keyboard) focusSoon { returnFocus }
    }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                    .focusProperties { canFocus = false }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            val width = minOf(560.dp, maxWidth * 0.92f)
            AnimatedVisibility(open, enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut()) {
                val inside = remember { FocusRequester() }
                var holds by remember { mutableStateOf(false) }
                val pad = androidx.compose.ui.platform.LocalInputModeManager.current.inputMode == androidx.compose.ui.input.InputMode.Keyboard
                // Focus (and its ring) only matters to a controller: opened by touch the sheet waits until one is picked up.
                LaunchedEffect(pad) {
                    if (!pad) return@LaunchedEffect
                    repeat(60) {
                        androidx.compose.runtime.withFrameNanos { }
                        if (holds || !open || Steps.open) return@LaunchedEffect
                        runCatching { inside.requestFocus() }
                    }
                }
                Column(
                    Modifier.width(width).fillMaxHeight()
                        .onFocusChanged { holds = it.hasFocus }
                        .focusRequester(inside)
                        .focusProperties { exit = { if (open && !Steps.open) FocusRequester.Cancel else FocusRequester.Default } }
                        .focusGroup()
                        .background(MaterialTheme.colorScheme.background)
                        .border(1.dp, pal.line).padding(horizontal = 18.dp, vertical = 16.dp).testTag(tag),
                    content = content,
                )
            }
        }
    }
}

internal val STORE_ROW = 60.dp
internal val STORE_HANDLE = 44.dp

@Composable
internal fun ManageStoresSheet() {
    val ctx = LocalContext.current
    LaunchedEffect(ManageStores.open) { if (ManageStores.open) StoreTabsState.load(ctx) }
    SideSheet(ManageStores.open, onClose = { ManageStores.open = false }, returnFocus = ManageStores.returnTo, tag = "manage-stores-sheet") {
        PageHeader(stringResource(R.string.library_manage_stores)) {
            Spacer(Modifier.weight(1f))
            SmallIconButton(Icons.Filled.Close, stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.onSurfaceVariant) {
                ManageStores.open = false
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SortableRows(
                rows = {
                    val ids = StoreTabsState.tabs.map { it.store.id }
                    if (ManageStores.custom) placeLibraryTabs(ids, StoreTabsState.libraryPlaces) else ids
                },
                commit = { StoreTabsState.reorder(ctx, it) },
                movable = { true },
                rowHeight = STORE_ROW, handleWidth = STORE_HANDLE,
                onActivate = { id ->
                    if (id in LIBRARY_HIDEABLE_TABS) {
                        StoreTabsState.setLibraryShown(ctx, id, id in StoreTabsState.libraryHidden)
                        return@SortableRows
                    }
                    val tab = StoreTabsState.tabs.firstOrNull { it.store.id == id } ?: return@SortableRows
                    StoreTabsState.setShown(ctx, tab.store, !tab.shown)
                },
            ) { id, held, offset, hint, modifier, onFocus ->
                val tab = StoreTabsState.tabs.firstOrNull { it.store.id == id }
                if (id in LIBRARY_OWN_TABS) LibraryTabSortRow(id, held, offset, hint, modifier, onFocus)
                else if (tab != null) StoreSortRow(tab, held, offset, hint, modifier, onFocus)
            }
        }
    }
}

@Composable
private fun StoreSortRow(
    tab: StoreTab, held: Boolean, offset: Float, hint: String?, modifier: Modifier, onFocus: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val store = tab.store
    val account = storeAccount(store)
    val name = StoresState.accounts[store]
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val note = when (account) {
        StoreAccount.SIGNED_IN -> name ?: ""
        StoreAccount.SIGNED_OUT -> stringResource(R.string.store_account_signed_out)
        StoreAccount.EXPIRED -> stringResource(R.string.store_account_expired)
        StoreAccount.NEEDS_KEY -> stringResource(R.string.store_account_api_key)
    }
    SortRowFrame(held, offset, modifier.testTag("store-row-${store.id}"), hot, src, onFocus) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(width = STORE_HANDLE, height = STORE_ROW).testTag("store-handle-${store.id}")) {
            Icon(Icons.Filled.DragHandle, null, tint = if (held) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f).alpha(if (tab.shown) 1f else 0.5f)) {
            Box(Modifier.size(10.dp).background(sourceColours(store.id).dot.copy(alpha = if (account == StoreAccount.SIGNED_IN) 1f else 0.4f), CircleShape))
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(store.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (note.isNotEmpty()) Text(
                    note, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (account == StoreAccount.EXPIRED) colors.error else colors.onSurfaceVariant,
                )
            }
        }
        if (hint != null) KeyHint("Y", hint)
        ToggleSwitch(
            tab.shown, true, store.label,
            Modifier.testTag("store-shown-${store.id}").focusProperties { canFocus = false },
        ) { StoreTabsState.setShown(ctx, store, it) }
    }
}

@Composable
internal fun SortRowFrame(
    held: Boolean, offset: Float, modifier: Modifier, hot: Boolean, src: MutableInteractionSource, onFocus: (Boolean) -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(
        modifier.fillMaxWidth().height(STORE_ROW)
            .zIndex(if (held) 1f else 0f)
            .graphicsLayer { translationY = offset }
            .background(if (held) colors.surfaceVariant else colors.surface)
            .glideBorder(hot, Shape12, pal.signal)
            .onFocusChanged { onFocus(it.isFocused) }
            .hoverable(src)
            .focusable(interactionSource = src),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().weight(1f).padding(end = 10.dp),
            content = content,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (held) Color.Transparent else pal.line))
    }
}

@Composable
private fun LibraryTabSortRow(id: String, held: Boolean, offset: Float, hint: String?, modifier: Modifier, onFocus: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val hideable = id in LIBRARY_HIDEABLE_TABS
    val shown = id !in StoreTabsState.libraryHidden
    val label = when (id) {
        LIBRARY_FAVORITES -> stringResource(R.string.soundtracks_favorites)
        Library.ADDED -> stringResource(R.string.library_custom_games)
        LIBRARY_HIDDEN -> stringResource(R.string.apps_filter_hidden)
        else -> stringResource(R.string.source_steam)
    }
    val dot = when (id) {
        LIBRARY_FAVORITES -> FavoritesColours.dot
        LIBRARY_HIDDEN -> HIDDEN_DOT
        else -> sourceColours(id).dot
    }
    SortRowFrame(held, offset, modifier.testTag("store-row-$id"), hot, src, onFocus) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(width = STORE_HANDLE, height = STORE_ROW).testTag("store-handle-$id")) {
            Icon(Icons.Filled.DragHandle, null, tint = if (held) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f).alpha(if (shown) 1f else 0.5f)) {
            Box(Modifier.size(10.dp).background(dot, CircleShape))
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (hint != null) KeyHint("Y", hint)
        if (hideable) ToggleSwitch(
            shown, true, label,
            Modifier.testTag("store-shown-$id").focusProperties { canFocus = false },
        ) { StoreTabsState.setLibraryShown(ctx, id, it) }
    }
}
