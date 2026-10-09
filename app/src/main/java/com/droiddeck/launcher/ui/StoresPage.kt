package com.droiddeck.launcher.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadStage
import com.droiddeck.launcher.stores.download.DownloadState
import com.droiddeck.launcher.stores.formatBytes

// The Stores section: GOG, Epic Games and Amazon Games libraries and storefronts, and one
// download queue for the three, in a single full-width pane.

/** The chip row's fourth entry, beside the three stores. */
private const val DOWNLOADS = "downloads"

private val TABS = listOf("store", "installed", "library", "all")

/**
 * The tabs a store has. Amazon has no public catalog: its Store and All would only repeat the
 * library, so it has Installed and Library alone.
 */
internal fun tabsFor(store: Store?): List<String> = if (store == Store.AMAZON) listOf("installed", "library") else TABS

/** [tab] if [store] has it, else Library (the open-on Store choice falls back there for Amazon). */
internal fun tabFor(store: Store?, tab: String): String = if (tab in tabsFor(store)) tab else "library"

@Composable
internal fun StoresPage(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    val ctx = LocalContext.current
    val narrow = LocalNarrowPane.current
    val padH = if (narrow) 14.dp else 22.dp
    var chip by rememberSaveable { mutableStateOf(Store.GOG.id) }
    var tab by rememberSaveable { mutableStateOf(if (SessionPrefs.storesOpenTab(ctx) == SessionPrefs.STORES_OPEN_STORE) "store" else "library") }
    var query by rememberSaveable { mutableStateOf("") }
    var openGame by rememberSaveable { mutableStateOf<String?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val store = Store.byId(chip)
    // A tab this store does not have (Store or All on Amazon) reads as Library.
    val shownTab = tabFor(store, tab)
    LaunchedEffect(Unit) { StoresState.refresh(ctx) }
    // A store the account is signed into fills itself when its chip is on screen.
    LaunchedEffect(chip, StoresState.accounts[store]) { if (store != null && StoresState.isSignedIn(store)) StoresState.open(ctx, store) }
    // A pad's focus sits on something the page is about to replace - the card that opens a game,
    // a tab's contents - and would be lost with it, leaving the next press to land on the rail.
    // Each move says where focus goes next: into the game page's main action, back to the card
    // it was opened from, or onto the first card of a tab or store just picked.
    val ff = LocalFrontFocus.current
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    var focusMove by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var lastOpened by remember { mutableStateOf<String?>(null) }
    fun move(kind: String) { focusMove = kind to (focusMove?.second ?: 0) + 1 }
    fun open(key: String) { lastOpened = key; openGame = key; move("detail") }
    fun closeGame() { openGame = null; move("card") }
    fun switchTab(to: String) { tab = to; move("first") }
    // Picking a chip opens the tab Setup says (Library or Store); a tab the user switches to
    // afterwards stays until another chip is picked.
    val pickChip: (String) -> Unit = { id ->
        chip = id; openGame = null; query = ""
        if (id != DOWNLOADS) tab = tabFor(Store.byId(id), if (s.storesOpenTab == SessionPrefs.STORES_OPEN_STORE) "store" else "library")
        move("first")
    }
    BackHandler(enabled = openGame != null) { closeGame() }
    val scroll = rememberScrollState()
    LaunchedEffect(chip, tab, openGame) { scroll.scrollTo(0) }
    LaunchedEffect(focusMove, openGame) {
        if (inputMode.inputMode != androidx.compose.ui.input.InputMode.Keyboard || ff == null) return@LaunchedEffect
        val target = when {
            openGame != null -> if (ff.primaryAttached > 0) ff.primary else ff.items["back:${store?.label}"]
            focusMove?.first == "card" -> lastOpened?.let { ff.items["card:$it"] } ?: ff.firstTile.takeIf { ff.firstTileAttached > 0 }
            focusMove?.first == "first" -> ff.firstTile.takeIf { ff.firstTileAttached > 0 }
            else -> null
        } ?: return@LaunchedEffect
        var landed = false
        focusWithinFrames({ landed }) { target.also { landed = runCatching { it.requestFocus() }.isSuccess } }
    }
    Column(
        modifier = modifier.padding(horizontal = padH, vertical = if (narrow) 10.dp else 14.dp)
            .bumpers(
                onPrevious = { if (openGame == null && chip != DOWNLOADS) { val t = tabsFor(store); switchTab(t[(t.indexOf(shownTab) + t.size - 1) % t.size]) } },
                onNext = { if (openGame == null && chip != DOWNLOADS) { val t = tabsFor(store); switchTab(t[(t.indexOf(shownTab) + 1) % t.size]) } },
            ),
    ) {
        // The chip row stays put on the page's own ground; what scrolls passes under it, clipped,
        // with a short fade where it meets the row.
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(bottom = 8.dp)) {
            Rise(0) { StoreChips(chip, s.storeDownloadsActive, onPick = pickChip, onSettings = { settings = true }) }
        }
        Box(Modifier.fillMaxWidth().weight(1f, fill = false).clipToBounds()) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(top = 4.dp, bottom = 16.dp)) {
            when {
                chip == DOWNLOADS -> StoresDownloadsPane(s, a)
                store == null -> {}
                !StoresState.isSignedIn(store) -> Rise(1) { SignInCard(store) }
                // One focus group, so the pad walks the page's own controls - back, the hero's
                // actions, the cards - and reaches the rail only with Left from them.
                openGame != null -> Column(Modifier.fillMaxWidth().focusGroup()) { StoreGameDetail(store, openGame!!, s, a, onBack = { closeGame() }) }
                else -> Storefront(store, shownTab, query, s, a, onTab = { switchTab(it) }, onQuery = { query = it }, onOpen = { open(it) })
            }
        }
        if (scroll.value > 0) Spacer(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(14.dp)
                .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.background, 1f to Color.Transparent)),
        )
        }
    }
    if (settings) StoresSettingsDialog(s, a) { settings = false }
    StoresState.pendingInstall?.let { item -> InstallWhereDialog(item) { StoresState.pendingInstall = null } }
}

// ---- the chip row -------------------------------------------------------------------------------

/** Four equal chips - the stores with a signed-in dot, Downloads with its count - and the cog. */
@Composable
private fun StoreChips(selected: String, active: Int, onPick: (String) -> Unit, onSettings: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        for (store in Store.entries) {
            StoreChip(
                label = store.shortLabel, on = selected == store.id, key = store.id, modifier = Modifier.weight(1f),
                lead = {
                    val c = sourceColours(store.id)
                    Box(Modifier.size(12.dp).clip(CircleShape).background(c.dot).alpha(if (StoresState.isSignedIn(store)) 1f else 0.35f))
                },
                description = store.label + if (StoresState.isSignedIn(store)) "" else " " + stringResource(R.string.stores_chip_signed_out),
            ) { onPick(store.id) }
        }
        StoreChip(
            label = stringResource(R.string.stores_tab_downloads), on = selected == DOWNLOADS, key = DOWNLOADS, modifier = Modifier.weight(1f),
            lead = { Icon(Icons.Outlined.Download, null, modifier = Modifier.size(18.dp)) },
            trail = if (active > 0) { { CountPill(active) } } else null,
        ) { onPick(DOWNLOADS) }
        IconChip(Icons.Filled.Settings, stringResource(R.string.stores_settings), onSettings)
    }
}

@Composable
private fun StoreChip(
    label: String, on: Boolean, key: String, modifier: Modifier, lead: @Composable () -> Unit,
    trail: (@Composable () -> Unit)? = null, description: String? = null, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(99.dp)
    val ink = if (on) colors.onBackground else if (hot) colors.onBackground else colors.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        modifier = modifier.paneItem("storechip:$key").heightIn(min = 44.dp).clip(shape)
            .background(if (on) pal.signal.copy(alpha = 0.16f) else colors.surface)
            .glideBorder(hot, shape, pal.signal, if (on) pal.signal.copy(alpha = 0.7f) else pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Tab, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides ink) { lead() }
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trail?.invoke()
    }
}

/** The chip row's cog: icon only, the chips' height; opens the section's settings. */
@Composable
private fun IconChip(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.paneItem("storechip:settings").size(44.dp).clip(CircleShape).background(colors.surface)
            .glideBorder(hot, CircleShape, pal.signal, pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick),
    ) { Icon(icon, description, tint = if (hot) pal.signal else colors.onBackground, modifier = Modifier.size(20.dp)) }
}

@Composable
internal fun CountPill(count: Int) {
    val pal = LocalPalette.current
    Text(
        count.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = pal.onSignal, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(pal.signal).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

// ---- sign-in -----------------------------------------------------------------------------------

/** No account for this store yet: who it signs in as, and the button that opens its login page. */
@Composable
private fun SignInCard(store: Store) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val narrow = LocalNarrowPane.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxWidth().clip(Shape16).background(colors.surface).border(1.dp, pal.line, Shape16).padding(18.dp),
        ) {
            StoreLogo(store, 56.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(store.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
            }
            if (!narrow) PrimaryButton(stringResource(R.string.stores_signin), main = true) { StoresState.signIn(ctx, store) }
        }
        if (narrow) Actions { PrimaryButton(stringResource(R.string.stores_signin), main = true) { StoresState.signIn(ctx, store) } }
    }
}

/** A square in the store's colour with its name on it: the stores ship no logo this app may bundle. */
@Composable
internal fun StoreLogo(store: Store, size: Dp) {
    val c = sourceColours(store.id)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size).clip(Shape14).background(c.fill).border(1.dp, c.dot.copy(alpha = 0.5f), Shape14)) {
        Text(store.shortLabel, fontSize = (size.value / 4).sp, fontWeight = FontWeight.Black, color = c.ink, maxLines = 1)
    }
}

// ---- storefront --------------------------------------------------------------------------------

@Composable
private fun Storefront(
    store: Store, tab: String, query: String, s: FrontEndState, a: FrontEndActions,
    onTab: (String) -> Unit, onQuery: (String) -> Unit, onOpen: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val library = StoresState.library[store] ?: emptyList()
    val shelves = StoresState.shelves[store]
    // The Installed tab, its count and every card's installed mark come from the installs on disk.
    val installedItems = remember(StoresState.installed, library) { com.droiddeck.launcher.stores.installedCards(store, StoresState.installed, library) }
    val installedKeys = remember(installedItems) { installedItems.map { it.id }.toSet() + StoresState.installed.filter { it.sidecar.store == store }.map { it.sidecar.id } }
    val q = query.trim().lowercase()
    fun matches(i: CatalogItem) = q.isEmpty() || i.title.lowercase().contains(q)
    // The storefront leaves out what the store rates or tags as adult unless the user shows it; owned games always show.
    fun shown(i: CatalogItem) = s.storesShowMature || !i.mature || i.owned
    fun shelf(l: List<CatalogItem>?) = l?.filter(::shown)
    // Everything this store knows about: the library plus whatever the shelves brought, each title once.
    val everything = remember(library, shelves) { (library + (shelves?.all ?: emptyList())).distinctBy { it.id } }
    Rise(1) {
        SubTabs(
            listOf(
                "store" to stringResource(R.string.stores_tab_store),
                "installed" to stringResource(R.string.stores_tab_count, stringResource(R.string.stores_tab_installed), installedItems.size),
                "library" to stringResource(R.string.stores_tab_count, stringResource(R.string.stores_tab_library), library.size),
                "all" to stringResource(R.string.stores_tab_count, stringResource(R.string.stores_tab_all), everything.size),
            ).filter { it.first in tabsFor(store) },
            tab, onTab,
            trailing = {
                val name = StoresState.accounts[store] ?: ""
                SmallTextButton(stringResource(R.string.stores_signout_named, name)) { StoresState.signOut(ctx, store) }
            },
        )
    }
    Rise(2) {
        SearchField(
            query, onQuery,
            placeholder = when (tab) {
                "store" -> stringResource(R.string.stores_search_catalog, store.label)
                "installed" -> stringResource(R.string.stores_search_installed)
                "library" -> stringResource(R.string.stores_search_library)
                else -> stringResource(R.string.stores_search_all, store.label)
            },
        )
    }
    StoresState.status[store]?.let { line -> Rise(3) { SyncBar(line) } }
    StoresState.problems[store]?.let { Rise(3) { Box(Modifier.padding(bottom = 8.dp)) { Note(it) } } }
    // The first card drawn on the tab is where a pad lands after a tab or chip change.
    var firstPlaced = false
    val card: @Composable (CatalogItem) -> Unit = { item ->
        val first = !firstPlaced
        firstPlaced = true
        GameCard(item, store, s, a, installedKeys, onOpen, first)
    }
    when (tab) {
        "installed" -> Grid(installedItems.filter(::matches), stringResource(R.string.stores_installed_empty), card)
        "library" -> Grid(library.filter(::matches), if (library.isEmpty() && StoresState.status[store] != null) stringResource(R.string.stores_library_loading) else stringResource(R.string.stores_nothing_matches), card)
        "all" -> Grid(everything.filter(::matches), stringResource(R.string.stores_nothing_matches), card)
        else -> {
            if (q.isNotEmpty()) Grid(everything.filter { matches(it) && shown(it) }, stringResource(R.string.stores_nothing_matches), card)
            else {
                Shelf(stringResource(R.string.stores_shelf_new), shelf(shelves?.whatsNew), card)
                Shelf(stringResource(R.string.stores_shelf_deals), shelf(shelves?.deals), card)
                Shelf(stringResource(R.string.stores_shelf_free), shelf(shelves?.free), card)
                Shelf(stringResource(R.string.stores_shelf_trending), shelf(shelves?.trending), card)
                Shelf(stringResource(R.string.stores_shelf_library), library, card)
                if (shelves == null && library.isEmpty()) Rise(4) { Note(if (StoresState.status[store] != null) stringResource(R.string.stores_library_loading) else stringResource(R.string.stores_shelves_loading)) }
                else if (shelves != null && shelves.isEmpty && store == Store.AMAZON) Rise(4) { Note(stringResource(R.string.stores_amazon_no_catalog)) }
            }
        }
    }
}

/** Underlined tabs as the preview draws them, with [trailing] (the account) at the right. */
@Composable
private fun SubTabs(tabs: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit, trailing: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                for ((key, label) in tabs) {
                    val on = key == selected
                    val src = remember { MutableInteractionSource() }
                    val hot = rememberHot(src)
                    val pick = { onPick(key) }
                    Column(
                        modifier = Modifier.paneItem("storetab:$key").clip(RoundedCornerShape(8.dp))
                            .background(if (hot) pal.signal.copy(alpha = 0.12f) else Color.Transparent)
                            .glideBorder(hot, RoundedCornerShape(8.dp), pal.signal)
                            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Tab, onClick = pick)
                            .controllerConfirm(onClick = pick)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) colors.onBackground else colors.onSurfaceVariant, maxLines = 1)
                        Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(2.dp).background(if (on) pal.signal else Color.Transparent))
                    }
                }
            }
            trailing()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
    }
}

@Composable
private fun SmallTextButton(text: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (hot) colors.onBackground else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.paneItem("btn:$text").clip(RoundedCornerShape(8.dp)).glideBorder(hot, RoundedCornerShape(8.dp), pal.signal)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, placeholder: String) {
    AdbTextField(
        value = query, onValueChange = onQuery, label = "", keyboardType = KeyboardType.Text, imeAction = ImeAction.Search,
        placeholder = placeholder, compact = true, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/** A horizontal row of cards under a title; nothing at all when the list is empty or not here yet. */
@Composable
private fun Shelf(title: String, items: List<CatalogItem>?, card: @Composable (CatalogItem) -> Unit) {
    if (items.isNullOrEmpty()) return
    val colors = MaterialTheme.colorScheme
    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp)) {
        for (item in items.take(24)) key(item.key) { Box(Modifier.width(CardWidth)) { card(item) } }
    }
}

/** Cards in rows that fill the width; [empty] when there are none. */
@Composable
private fun Grid(items: List<CatalogItem>, empty: String, card: @Composable (CatalogItem) -> Unit) {
    if (items.isEmpty()) { Box(Modifier.padding(vertical = 32.dp).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(empty, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; return }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        val gap = 10.dp
        val cols = ((maxWidth + gap) / (CardWidth + gap)).toInt().coerceAtLeast(1)
        val w = (maxWidth - gap * (cols - 1)) / cols
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            for (row in items.chunked(cols)) Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxWidth()) {
                for (item in row) key(item.key) { Box(Modifier.width(w)) { card(item) } }
            }
        }
    }
}

private val CardWidth = 150.dp

/**
 * One title: its wide art, the title, a price or status line and one button that says the one
 * thing to do next - Install with its size, Play, the download's progress, Get for free, or View
 * on the store. Tapping the card itself opens the game's page.
 */
@Composable
private fun GameCard(item: CatalogItem, store: Store, s: FrontEndState, a: FrontEndActions, installedKeys: Set<String>, onOpen: (String) -> Unit, first: Boolean = false) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "cardScale")
    val installed = item.id in installedKeys
    val download = StoresState.download("${store.id}:${item.id}")?.takeIf { it.isActive }
    val open = { onOpen(item.key) }
    Column(
        modifier = Modifier.fillMaxWidth().paneItem("card:${item.key}").then(if (first) Modifier.firstTile() else Modifier).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp)).glideBorder(hot, RoundedCornerShape(10.dp), pal.signal, pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = open).controllerConfirm(onClick = open),
    ) {
        CardFace(item, download) { CardMarker(item, installed, download) }
    }
}

/** A store's sync as a thin bar and its count ("25/55"); a moving bar when it has no count yet. */
@Composable
private fun SyncBar(line: String) {
    val count = Regex("(\\d+)\\s*/\\s*(\\d+)").find(line)
    val done = count?.groupValues?.get(1)?.toIntOrNull()
    val total = count?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it > 0 }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Box(Modifier.weight(1f)) {
            if (done != null && total != null) androidx.compose.material3.LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp))
            else androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
        }
        if (done != null && total != null) Text("$done/$total", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** How tall the card's one line is, over the blurred foot of its art. */
private val CardStrip = 28.dp

/**
 * The card is its art: sharp above, and under the one line a blurred, darkened copy of the same
 * picture, placed so the two meet without a seam. Blur needs API 31; below it the copy is only
 * darkened, more strongly.
 */
@Composable
private fun CardFace(item: CatalogItem, download: com.droiddeck.launcher.stores.download.DownloadEntry?, marker: @Composable () -> Unit) {
    val url = item.imageUrl ?: item.tallImageUrl
    val blurs = android.os.Build.VERSION.SDK_INT >= 31
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val total = maxWidth * 9f / 16f + CardStrip
        Box(Modifier.fillMaxWidth().height(total).background(Color(0xFF101318)).then(if (url == null) Modifier.background(artBrush(hueOf(item.title))) else Modifier)) {
            if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(CardStrip).clipToBounds()) {
                if (url != null) AsyncImage(
                    model = url, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Bottom, unbounded = true).height(total).blur(24.dp),
                )
                Spacer(Modifier.matchParentSize().background(Color.Black.copy(alpha = if (blurs) 0.45f else 0.7f)))
                Row(
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.matchParentSize().padding(horizontal = 8.dp),
                ) {
                    Text(item.title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    marker()
                }
            }
            // A soft edge where the sharp art meets the strip.
            Spacer(Modifier.align(Alignment.BottomCenter).padding(bottom = CardStrip).fillMaxWidth().height(14.dp).background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f))))
            if (download != null) Box(Modifier.align(Alignment.BottomCenter).padding(start = 6.dp, end = 6.dp, bottom = CardStrip + 5.dp)) {
                ProgressBarThin(download.fraction, paused = download.state == DownloadState.PAUSED, verify = download.stage == DownloadStage.VERIFY)
            }
        }
    }
}

/** The card's right-hand value: done, resume, progress, size, or the price. */
@Composable
private fun CardMarker(item: CatalogItem, installed: Boolean, download: com.droiddeck.launcher.stores.download.DownloadEntry?) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val small = 10.sp
    when {
        installed -> Icon(Icons.Filled.Check, stringResource(R.string.stores_installed_chip), tint = pal.good, modifier = Modifier.size(14.dp))
        download != null -> Text("${download.percent}%", fontSize = small, fontWeight = FontWeight.Bold, color = pal.signal, maxLines = 1)
        item.owned && StoresState.isUnfinished(item) -> Text(stringResource(R.string.stores_resume), fontSize = small, fontWeight = FontWeight.Bold, color = pal.signal, maxLines = 1)
        item.owned -> if (item.sizeBytes > 0) Text(formatBytes(item.sizeBytes), fontSize = small, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
        item.isFree -> Text(stringResource(R.string.stores_free), fontSize = small, fontWeight = FontWeight.Bold, color = pal.good, maxLines = 1)
        item.isDiscounted -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StateChip("-${item.discountPercent}%", ChipTone.DEAL, small = true)
            Text(item.finalPrice, fontSize = small, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
        }
        item.hasPrice -> Text(item.finalPrice, fontSize = small, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
    }
}

@Composable
internal fun CardArt(item: CatalogItem, modifier: Modifier) {
    val url = item.imageUrl ?: item.tallImageUrl
    val colors = MaterialTheme.colorScheme
    // A dark ground under the image, so art with transparency never ends in white, and a quiet
    // fade into the card's surface at the foot.
    Box(modifier.background(colors.surfaceVariant).then(if (url == null) Modifier.background(artBrush(hueOf(item.title))) else Modifier)) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        else Text(item.title, fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
        Spacer(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.6f to Color.Transparent, 1f to colors.surface.copy(alpha = 0.85f))))
    }
}

@Composable
internal fun ProgressBarThin(fraction: Float, paused: Boolean = false, verify: Boolean = false, height: Dp = 4.dp, done: Boolean = false) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val fill = when { done -> pal.good; paused -> Color(0xFFFFC24D); verify -> Color(0xFF9B6DFF); else -> pal.signal }
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(colors.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).background(fill))
    }
}

@Composable
internal fun downloadLabel(d: com.droiddeck.launcher.stores.download.DownloadEntry): String = when (d.state) {
    DownloadState.PAUSED -> stringResource(R.string.stores_dl_paused)
    DownloadState.QUEUED -> stringResource(R.string.stores_dl_queued)
    else -> if (d.stageFraction >= 0f) stringResource(R.string.stores_dl_active_percent, activeStageLabel(d), d.percent) else activeStageLabel(d)
}

/** The active stage as the row and the page name it: Checking (files already there), Downloading, Verifying, Installing. */
@Composable
internal fun activeStageLabel(d: com.droiddeck.launcher.stores.download.DownloadEntry): String = when (d.stage) {
    DownloadStage.MANIFEST -> if (d.stageItemsTotal > 0) stringResource(R.string.stores_stage_checking) else stringResource(R.string.stores_stage_manifest)
    DownloadStage.DOWNLOAD -> stringResource(R.string.stores_stage_downloading)
    DownloadStage.VERIFY -> stringResource(R.string.stores_stage_verifying)
    DownloadStage.INSTALL -> stringResource(R.string.stores_stage_installing)
    DownloadStage.DONE -> stringResource(R.string.stores_stage_done)
}

/** Two taps: the first arms it ([confirm] shows), the second acts; it disarms itself after a few seconds. */
@Composable
internal fun ConfirmButton(label: String, confirm: String, compact: Boolean = false, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { kotlinx.coroutines.delay(4000); armed = false } }
    SecondaryButton(if (armed) confirm else label, compact = compact) { if (!armed) armed = true else { armed = false; onConfirm() } }
}

@Composable
internal fun stageLabel(stage: DownloadStage): String = when (stage) {
    DownloadStage.MANIFEST -> stringResource(R.string.stores_stage_manifest)
    DownloadStage.DOWNLOAD -> stringResource(R.string.stores_stage_download)
    DownloadStage.VERIFY -> stringResource(R.string.stores_stage_verify)
    DownloadStage.INSTALL -> stringResource(R.string.stores_stage_install)
    DownloadStage.DONE -> stringResource(R.string.stores_stage_done)
}

/** The game's web page, in the device's browser: this app buys nothing and claims nothing itself. */
internal fun openStoreUrl(ctx: android.content.Context, item: CatalogItem) {
    val url = item.storeUrl.ifBlank { return }
    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Launches an installed store game the way the Games tab does: through its Steam shortcut. The
 * shortcut exists once the client has (re)started since the install; until then the user is told.
 */
internal fun launchStoreGame(ctx: android.content.Context, store: Store, id: String, s: FrontEndState, a: FrontEndActions) {
    val game = s.steamGames.firstOrNull { it.source == store.id && it.storeId == id }
    if (game == null) {
        android.widget.Toast.makeText(ctx, R.string.stores_launch_not_registered, android.widget.Toast.LENGTH_LONG).show()
        return
    }
    com.droiddeck.launcher.stores.StoreLaunch.prepare(ctx, store, id) { a.onSteamGame(game) }
}
