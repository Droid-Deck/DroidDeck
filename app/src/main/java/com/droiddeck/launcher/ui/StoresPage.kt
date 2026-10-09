package com.droiddeck.launcher.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import com.droiddeck.launcher.stores.StoreInstallRoot
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.basicMarquee
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

private val TABS = listOf("store", "library", "installed")

/**
 * The modes a store has, picked from its chip (A steps them out of it) or cycled with LB / RB:
 * Store, Library and Installed. Amazon has no public catalog, so no Store.
 */
internal fun tabsFor(store: Store?): List<String> = if (store == Store.AMAZON) TABS.drop(1) else TABS

/** [tab] if [store] has it, else Library (the open-on Store choice on Amazon falls back there). */
internal fun tabFor(store: Store?, tab: String): String = if (tab in tabsFor(store)) tab else "library"

/** What the pane under the chips shows: a store's tab (signed in or not) or the downloads. */
private data class PaneKey(val chip: String, val tab: String, val signedIn: Boolean)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
internal fun StoresPage(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val narrow = LocalNarrowPane.current
    val padH = if (narrow) 14.dp else 22.dp
    var chip by rememberSaveable { mutableStateOf(Store.GOG.id) }
    var tab by rememberSaveable { mutableStateOf(if (SessionPrefs.storesOpenTab(ctx) == SessionPrefs.STORES_OPEN_STORE) "store" else "library") }
    var query by rememberSaveable { mutableStateOf("") }
    var openGame by rememberSaveable { mutableStateOf<String?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(StoresState.openDownloads) { if (StoresState.openDownloads) { chip = DOWNLOADS; StoresState.openDownloads = false } }
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
    // A store's chip steps its modes out of itself (End session's shape, in the store's colour);
    // picking one shows that store in it. A store not signed in has none: its chip just opens it.
    val pal = LocalPalette.current
    fun openModes(st: Store, at: Rect?) {
        if (at == null || !StoresState.isSignedIn(st) || StoresState.expired[st] == true) { if (chip != st.id) pickChip(st.id); return }
        val modes = tabsFor(st)
        val current = if (chip == st.id) shownTab else null
        var picked = false
        StoresMotion.ask(StepAsk(
            anchor = at, side = StepSide.Right, accent = storePalette(pal, st).signal, items = modes.size,
            // Folded: focus goes to the first card of what was picked, or back to the chip.
            onDismiss = { move(if (picked) "first" else "item:storechip:${st.id}") },
            handle = { ChipLabel(st, MaterialTheme.colorScheme.onBackground) },
        ) {
            StoreTheme(st) {
                val library = StoresState.library[st].orEmpty()
                val installedCount = remember(StoresState.installed, library) { com.droiddeck.launcher.stores.installedCards(st, StoresState.installed, library).size }
                StepList {
                    modes.forEachIndexed { i, m ->
                        val label = when (m) {
                            "store" -> stringResource(R.string.stores_tab_store)
                            "installed" -> stringResource(R.string.stores_tab_count, stringResource(R.string.stores_tab_installed), installedCount)
                            else -> stringResource(R.string.stores_tab_count, stringResource(R.string.stores_tab_library), library.size)
                        }
                        val focusHere = if (current != null) m == current else i == 0
                        StepChoice(
                            label, selected = m == current, enabled = open,
                            modifier = Modifier.fillMaxWidth().stepItem(i).then(if (focusHere) Modifier.focusRequester(first) else Modifier),
                        ) {
                            picked = true
                            if (chip != st.id) { chip = st.id; openGame = null; query = "" }
                            tab = m
                            StoresMotion.fold()
                        }
                    }
                }
            }
        })
    }
    BackHandler(enabled = openGame != null) { closeGame() }
    val scroll = rememberScrollState()
    val detailScroll = rememberScrollState()
    // A store or tab just picked starts at the top. Closing a game page leaves the grid where it
    // was, so the page draws back into the card it came from and focus lands on it.
    LaunchedEffect(chip, tab) { scroll.scrollTo(0) }
    LaunchedEffect(openGame) { detailScroll.scrollTo(0) }
    LaunchedEffect(focusMove, openGame) {
        if (inputMode.inputMode != androidx.compose.ui.input.InputMode.Keyboard || ff == null) return@LaunchedEffect
        // Looked up each frame, and asked again until focus is really there: what it goes to (the
        // game page's action, the card under it) is composed and placed a frame or two after the
        // move that asks for it, and a request before then is dropped without a word.
        // A mode with nothing in it (Installed, none installed) has no first card: after a few
        // frames focus goes to the store's chip rather than falling back to the rail.
        var frames = 0
        fun attached(id: String) = (ff.attached[id] ?: 0) > 0
        fun target(): Pair<androidx.compose.ui.focus.FocusRequester, String?>? {
            val kind = focusMove?.first
            return when {
                openGame != null -> if (ff.primaryAttached > 0) ff.primary to FrontFocus.PRIMARY
                    else "back:${store?.label}".let { id -> ff.items[id]?.takeIf { attached(id) }?.let { it to id } }
                kind == "card" -> lastOpened?.let { "card:$it" }?.let { id -> ff.items[id]?.takeIf { attached(id) }?.let { it to id } }
                    ?: ff.firstTile.takeIf { ff.firstTileAttached > 0 }?.let { it to null }
                kind == "first" -> ff.firstTile.takeIf { ff.firstTileAttached > 0 }?.let { it to null }
                    // A store not signed in: its Sign in.
                    ?: ff.primary.takeIf { ff.primaryAttached > 0 }?.let { it to FrontFocus.PRIMARY }
                    ?: "storechip:$chip".let { id -> ff.items[id]?.takeIf { ++frames > 12 && attached(id) }?.let { it to id } }
                kind?.startsWith("item:") == true -> kind.removePrefix("item:").let { id -> ff.items[id]?.takeIf { attached(id) }?.let { it to id } }
                else -> null
            }
        }
        repeat(45) {
            androidx.compose.runtime.withFrameNanos { }
            val (req, id) = target() ?: return@repeat
            if (id != null && ff.last == id) return@LaunchedEffect
            if (runCatching { req.requestFocus() }.isSuccess && id == null) return@LaunchedEffect
        }
    }

    // The game page is a layer over the grid: it floods out of the card it was opened from (the
    // cog's PageFlood, carrying the card's art), and Back draws it into that card again. [detail]
    // outlives [openGame] while it draws back.
    var detail by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var detailFrom by remember { mutableStateOf<Origin?>(null) }
    var detailArt by remember { mutableStateOf<String?>(null) }
    var viewportAt by remember { mutableStateOf(Offset.Zero) }
    var hostAt by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(openGame) {
        val g = openGame
        if (g != null) {
            val mark = StoresMotion.takeCard(g)
            detailFrom = mark?.let { Origin(it.bounds.translate(-viewportAt), it.corner) }
            detailArt = mark?.art
            leaving = false
            detail = g
        } else if (detail != null) {
            if (detailFrom != null) {
                leaving = true
                delay(Motion.ms(PAGE_RETURN_MS).toLong())
            }
            detail = null
            leaving = false
            detailFrom = null
        }
    }
    val covering = detail != null && !leaving
    val coveringNow = androidx.compose.runtime.rememberUpdatedState(covering)
    val gridScale by animateFloatAsState(if (covering) 0.95f else 1f, if (covering) Motion.tw(480) else Motion.tw(460, 40), label = "gridSink")
    val gridAlpha by animateFloatAsState(if (covering) 0f else 1f, if (covering) Motion.tw(320, 80) else Motion.tw(300, 100), label = "gridFade")

    // Each store in its own colour: the focus ring, the buttons and what steps out of them.
    StoreTheme(store) {
    Box(modifier.onGloballyPositioned { hostAt = it.positionInRoot() }) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = padH, vertical = if (narrow) 10.dp else 14.dp)
            .bumpers(
                onPrevious = { if (openGame == null && chip != DOWNLOADS) { val t = tabsFor(store); switchTab(t[(t.indexOf(shownTab) + t.size - 1) % t.size]) } },
                onNext = { if (openGame == null && chip != DOWNLOADS) { val t = tabsFor(store); switchTab(t[(t.indexOf(shownTab) + 1) % t.size]) } },
            ),
    ) {
        // The chip row stays put and paints nothing - the page's background shows through it. What
        // scrolls is clipped at the row's lower edge and, once scrolled, faded out there: the
        // content's own alpha, not a colour laid over it.
        Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Rise(0) { StoreChips(chip, s.storeDownloadsActive, onStore = { st, at -> openModes(st, at) }, onDownloads = { pickChip(DOWNLOADS) }, onSettings = { settings = true }) }
        }
        val fade = with(androidx.compose.ui.platform.LocalDensity.current) { 14.dp.toPx() }
        Box(
            Modifier.fillMaxWidth().weight(1f).clipToBounds()
                .onGloballyPositioned { viewportAt = it.positionInRoot() }
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val scrolled = if (detail != null) detailScroll.value > 0 else scroll.value > 0
                    if (scrolled && size.height > fade) drawRect(
                        Brush.verticalGradient(0f to Color.Transparent, fade / size.height to Color.Black),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                },
        ) {
            // Under a game page the grid stays composed - its scroll and the card to come back to
            // stay put - but sinks away, and a pad can neither reach it nor be sent to it. Once the
            // page starts drawing back the grid takes focus again, so it lands on the card at once.
            val gridFocus = if (covering) null else ff
            CompositionLocalProvider(LocalFrontFocus provides gridFocus) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .graphicsLayer { scaleX = gridScale; scaleY = gridScale; alpha = gridAlpha }
                        // Always the same group, only its rule changes: a focus group added around
                        // the card that has focus leaves the focus tree refusing every request after.
                        .focusProperties { enter = { if (coveringNow.value) androidx.compose.ui.focus.FocusRequester.Cancel else androidx.compose.ui.focus.FocusRequester.Default } }
                        .focusGroup()
                        .verticalScroll(scroll).padding(top = 4.dp, bottom = 16.dp),
                ) {
                    val pane = PaneKey(
                        chip, if (store == null) "" else shownTab,
                        store == null || (StoresState.isSignedIn(store) && StoresState.expired[store] != true),
                    )
                    AnimatedContent(
                        targetState = pane,
                        transitionSpec = {
                            val sameStore = initialState.chip == targetState.chip && initialState.signedIn == targetState.signedIn
                            (if (sameStore && initialState.tab != targetState.tab) {
                                // LB / RB: the tab's contents leave the way the bumper points and the
                                // next tab's come in from the other side, the column nearest leading.
                                val dir = if (TABS.indexOf(targetState.tab) > TABS.indexOf(initialState.tab)) 1 else -1
                                (slideInHorizontally(Motion.sp(0.7f, Spring.StiffnessMediumLow)) { it / 6 * dir } + fadeIn(Motion.tw(220, 60)))
                                    .togetherWith(slideOutHorizontally(Motion.tw(170)) { -it / 6 * dir } + fadeOut(Motion.tw(170)) + scaleOut(Motion.tw(170), targetScale = 0.97f))
                            } else {
                                // Another store: the shelf sinks away, the next rises in (the pane's own move).
                                (fadeIn(Motion.tw(300, 80)) + slideInVertically(Motion.tw(420, 80)) { it / 24 })
                                    .togetherWith(fadeOut(Motion.tw(170)) + scaleOut(Motion.tw(170), targetScale = 0.97f))
                            }).using(SizeTransform(clip = false))
                        },
                        label = "storesPane",
                    ) { k ->
                        // What is leaving is not where a pad's next move should land.
                        val outgoing = transition.targetState == androidx.compose.animation.EnterExitState.PostExit
                        CompositionLocalProvider(LocalFrontFocus provides if (outgoing) null else LocalFrontFocus.current) {
                          // AnimatedContent stacks its child in a Box: the pane's rows need their own column.
                          Column(Modifier.fillMaxWidth()) {
                            val st = Store.byId(k.chip)
                            when {
                                k.chip == DOWNLOADS -> StoresDownloadsPane(s, a)
                                st == null -> {}
                                // Signed out, or a sign-in that ran out (a launch could not get its code): sign in again.
                                !k.signedIn -> Rise(1) { SignInCard(st) }
                                else -> Storefront(st, k.tab, query, s, a, onQuery = { query = it }, onOpen = { open(it) })
                            }
                          }
                        }
                    }
                }
            }
            detail?.let { key ->
                val detailStore = Store.byId(key.substringBefore(':')) ?: store
                val body: @Composable () -> Unit = {
                    // One focus group, so the pad walks the page's own controls - back, the hero's
                    // actions - and reaches the rail only with Left from them. It takes every touch,
                    // so nothing reaches the grid under it.
                    Column(
                        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }
                            .verticalScroll(detailScroll).padding(top = 4.dp, bottom = 16.dp),
                    ) {
                        if (detailStore != null) Column(Modifier.fillMaxWidth().focusGroup()) { StoreGameDetail(detailStore, key, s, a, onBack = { closeGame() }) }
                    }
                }
                val from = detailFrom
                if (from == null) body()
                else {
                    // Sized up front: the flood draws the art only once it knows its size, and a painter
                    // left to take its size from drawing would never load. The card's own bitmap stands
                    // in from the memory cache until the full one is decoded.
                    val art = detailArt?.let { url ->
                        coil.compose.rememberAsyncImagePainter(
                            remember(url) {
                                coil.request.ImageRequest.Builder(ctx).data(url).size(1280, 720)
                                    .placeholderMemoryCacheKey(url).build()
                            },
                        )
                    }
                    PageFlood(from, leaving, art) { body() }
                }
            }
        }
    }
    // Over the whole page: an install's dot on its way to Downloads, a confirm stepping out of the
    // control that asked for it, and a sign-in's flood.
    FlightLayer(hostAt)
    StoresMotion.step?.let { ask ->
        key(ask) {
            StepOut(
                open = StoresMotion.stepOpen, pill = ask.anchor.translate(-hostAt), side = ask.side, accent = ask.accent,
                onDismiss = { StoresMotion.fold() }, onClosed = { StoresMotion.closed(ask) },
                pillCorner = ask.pillCorner, items = ask.items, handle = ask.handle, content = ask.content,
            )
        }
    }
    SignInFloodLayer(hostAt)
    }
    }
    // Leaving the section: nothing stays stepped out of a control that is no longer on screen.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { StoresMotion.drop() } }
    // An install that started from a button here flies from it to the Downloads chip.
    val activeKeys = StoresState.downloads.filter { it.isActive }.map { it.key }.toSet()
    val seenActive = remember { mutableSetOf<String>().also { it.addAll(activeKeys) } }
    LaunchedEffect(activeKeys) {
        val fresh = activeKeys - seenActive
        seenActive.clear(); seenActive.addAll(activeKeys)
        if (fresh.isNotEmpty()) StoresMotion.takeInstall()?.let { StoresMotion.fly(it) }
    }
    if (settings) StoresSettingsDialog(s, a) { settings = false }
    StoresState.pendingInstall?.let { item -> InstallWhere(item) }
}

/**
 * "Install <game>:" with a row per place, grown out of the Install button that asked (End
 * session's step-out, in blue); the plain dialog when that button is not on screen.
 */
@Composable
private fun InstallWhere(item: CatalogItem) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val from = remember(item) { StoresMotion.recentInstall() }
    if (from == null) { InstallWhereDialog(item) { StoresState.pendingInstall = null }; return }
    val title = stringResource(R.string.stores_install_title, item.title)
    val internalLabel = stringResource(R.string.stores_target_internal)
    val label = StoresMotion.installLabel
    LaunchedEffect(item) {
        val targets = StoreInstallRoot.targets(ctx)
        val remembered = SessionPrefs.storesInstallTarget(ctx)
        val defaultIndex = targets.indexOfFirst { it.root.absolutePath == remembered }.coerceAtLeast(0)
        StoresMotion.ask(StepAsk(
            anchor = from, side = StepSide.Right, accent = pal.signal, pillCorner = 12.dp, items = 1 + targets.size,
            onDismiss = { if (StoresState.pendingInstall === item) StoresState.pendingInstall = null },
            handle = { Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.signal, maxLines = 1) },
        ) {
            StepTitle(title)
            StepList {
                targets.forEachIndexed { i, t ->
                    StepChoice(
                        label = if (t.removable) t.label else internalLabel,
                        icon = if (t.removable) androidx.compose.material.icons.Icons.Outlined.SdCard else androidx.compose.material.icons.Icons.Outlined.Smartphone,
                        trailing = stringResource(R.string.stores_target_free, formatBytes(t.freeBytes)),
                        enabled = open,
                        modifier = Modifier.fillMaxWidth().stepItem(1 + i).then(if (i == defaultIndex) Modifier.focusRequester(first) else Modifier),
                    ) {
                        SessionPrefs.setStoresInstallTarget(ctx, t.root.absolutePath)
                        // The fold and the dot leave together: the download it starts flies from Install.
                        StoresMotion.markInstall(from, label)
                        StoresState.install(ctx, item, t.root)
                        StoresMotion.fold()
                    }
                }
            }
        })
    }
}

/** A sign-in's flood: out of the button in the store's colour, and home again once the login page closes. */
@Composable
private fun SignInFloodLayer(hostAt: Offset) {
    val f = StoresMotion.signIn ?: return
    val ctx = LocalContext.current
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    val density = androidx.compose.ui.platform.LocalDensity.current
    key(f) {
        var paused by remember { mutableStateOf(false) }
        // Signed in: into the store's dot on its chip. Not (closed, or it failed): back into the button.
        fun back() { if (f.drainTo == null) f.drainTo = if (StoresState.isSignedIn(f.store)) StoresMotion.dots[f.store] ?: f.from else f.from }
        androidx.compose.runtime.DisposableEffect(lifecycle) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE && f.away) paused = true
                if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && f.away && paused) back()
            }
            lifecycle.addObserver(obs)
            onDispose { lifecycle.removeObserver(obs) }
        }
        // The store had no login page to open: nothing will come back, so drain now.
        LaunchedEffect(f.away) { if (f.away) { delay(1_500); if (!paused) back() } }
        val to = f.drainTo
        if (to == null) ColorFlood(f.from.translate(-hostAt), f.start, f.color) {
            if (!f.away) {
                f.away = true
                com.droiddeck.launcher.stores.StoreLoginActivity.floodColor = f.color.toArgb()
                StoresState.signIn(ctx, f.store)
            }
        } else {
            val corner = if (to === f.from) with(density) { 12.dp.toPx() } else to.minDimension / 2f
            ColorDrain(f.color, to.translate(-hostAt), corner) {
                if (StoresMotion.signIn === f) StoresMotion.signIn = null
                if (StoresState.isSignedIn(f.store)) StoresMotion.pulse(f.store)
            }
        }
    }
}

// ---- the chip row -------------------------------------------------------------------------------

/**
 * Four equal chips - the stores with a signed-in dot, Downloads with its count - and the cog. The
 * selection is one shape that belongs to the row, not each chip's background: it glides from chip
 * to chip like the focus ring, its leading edge first, tinted in the store's colour, and a hop
 * past a chip goes as a drop.
 */
@Composable
private fun StoreChips(selected: String, active: Int, onStore: (Store, Rect?) -> Unit, onDownloads: () -> Unit, onSettings: () -> Unit) {
    val pal = LocalPalette.current
    val keys = Store.entries.map { it.id } + DOWNLOADS
    val bounds = remember { androidx.compose.runtime.mutableStateMapOf<String, Rect>() }
    val rootBounds = remember { HashMap<String, Rect>() }
    val tint by androidx.compose.animation.animateColorAsState(
        Store.byId(selected)?.let { sourceColours(it.id).dot } ?: pal.signal, Motion.tw(160), label = "chipTint",
    )
    Box(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            for (store in Store.entries) {
                val signedIn = StoresState.isSignedIn(store) && StoresState.expired[store] != true
                StoreChip(
                    label = store.shortLabel, on = selected == store.id, key = store.id,
                    modifier = Modifier.weight(1f).onGloballyPositioned { bounds[store.id] = it.boundsInParent(); rootBounds[store.id] = it.boundsInRoot() },
                    lead = {
                        val c = sourceColours(store.id)
                        // A store's dot pulses once when it is picked signed in, and when a sign-in lands.
                        var picked by remember { mutableStateOf(0) }
                        val on = selected == store.id
                        val wasOn = remember { booleanArrayOf(on) }
                        LaunchedEffect(on) {
                            if (on && !wasOn[0] && signedIn) picked++
                            wasOn[0] = on
                        }
                        Box(
                            Modifier.size(12.dp)
                                .onGloballyPositioned { StoresMotion.dots[store] = it.boundsInRoot() }
                                .pulseRing(picked + (StoresMotion.pulses[store] ?: 0), c.dot),
                        ) { Box(Modifier.matchParentSize().clip(CircleShape).background(c.dot).alpha(if (signedIn) 1f else 0.35f)) }
                    },
                    description = store.label + if (signedIn) "" else " " + stringResource(R.string.stores_chip_signed_out),
                ) { onStore(store, rootBounds[store.id]) }
            }
            StoreChip(
                label = stringResource(R.string.stores_tab_downloads), on = selected == DOWNLOADS, key = DOWNLOADS,
                modifier = Modifier.weight(1f).onGloballyPositioned {
                    bounds[DOWNLOADS] = it.boundsInParent()
                    StoresMotion.downloadsChip = it.boundsInRoot()
                },
                lead = { Icon(Icons.Outlined.Download, null, modifier = Modifier.size(18.dp)) },
                trail = if (active > 0) { { CountPill(active) } } else null,
            ) { onDownloads() }
            IconChip(Icons.Filled.Settings, stringResource(R.string.stores_settings), onSettings)
        }
        // Over the chips: the fill is translucent, and the other chips' rest fill would hide it in flight.
        ChipGlide(keys.indexOf(selected), bounds[selected], tint)
    }
}

/**
 * The selection under the chips: [at] (in the row) of chip [index], in [tint]. A move to the next
 * chip stretches it - the leading edge on the focus ring's lead spring, the trailing one a beat
 * later on its trail spring - and a hop past a chip pinches it to a drop, carries it and opens it.
 */
@Composable
private fun BoxScope.ChipGlide(index: Int, at: Rect?, tint: Color) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val left = remember { Animatable(Float.NaN) }
    val right = remember { Animatable(0f) }
    val pinch = remember { Animatable(0f) }
    val top = remember { mutableStateOf(0f) }
    val bottom = remember { mutableStateOf(0f) }
    val was = remember { intArrayOf(-1) }
    LaunchedEffect(index, at) {
        val b = at ?: return@LaunchedEffect
        top.value = b.top; bottom.value = b.bottom
        val from = was[0]
        was[0] = index
        if (left.value.isNaN() || from == index || from < 0 || Motion.scale == 0f) {
            left.snapTo(b.left); right.snapTo(b.right); pinch.snapTo(0f)
            return@LaunchedEffect
        }
        coroutineScope {
            if (kotlin.math.abs(index - from) > 1) {
                // Too far to stretch: pinch into a drop where it is, carry it over, open it there.
                pinch.animateTo(1f, Motion.tw(90))
                val l = launch { left.animateTo(b.left, Motion.sp(0.7f, 380f)) }
                val r = launch { right.animateTo(b.right, Motion.sp(0.7f, 380f)) }
                l.join(); r.join()
                pinch.animateTo(0f, Motion.sp(0.6f, 500f))
            } else {
                val forward = index > from
                val (lead, trail) = if (forward) right to left else left to right
                val leadTo = if (forward) b.right else b.left
                val trailTo = if (forward) b.left else b.right
                launch { lead.animateTo(leadTo, Motion.sp(0.62f, 700f)) }
                launch { delay(Motion.ms(40).toLong()); trail.animateTo(trailTo, Motion.sp(0.78f, 360f)) }
            }
        }
    }
    androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
        if (left.value.isNaN() || bottom.value <= top.value) return@Canvas
        val l = minOf(left.value, right.value)
        val r = maxOf(left.value, right.value)
        val cx = (l + r) / 2f
        val cy = (top.value + bottom.value) / 2f
        val drop = with(density) { 6.dp.toPx() }
        val p = pinch.value
        val x0 = androidx.compose.ui.util.lerp(l, cx - drop, p)
        val x1 = androidx.compose.ui.util.lerp(r, cx + drop, p)
        val y0 = androidx.compose.ui.util.lerp(top.value, cy - drop, p)
        val y1 = androidx.compose.ui.util.lerp(bottom.value, cy + drop, p)
        val size = androidx.compose.ui.geometry.Size(x1 - x0, y1 - y0)
        val corner = androidx.compose.ui.geometry.CornerRadius(minOf(size.width, size.height) / 2f)
        val at0 = Offset(x0, y0)
        // A drop in flight is solid; on a chip it is the chip's tinted fill and outline.
        drawRoundRect(tint.copy(alpha = androidx.compose.ui.util.lerp(0.16f, 0.9f, p)), at0, size, corner)
        drawRoundRect(tint.copy(alpha = 0.7f * (1f - p)), at0, size, corner, style = androidx.compose.ui.graphics.drawscope.Stroke(with(density) { 1.dp.toPx() }))
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
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "chipScale")
    val shape = RoundedCornerShape(99.dp)
    val ink = if (on) colors.onBackground else if (hot) colors.onBackground else colors.onSurfaceVariant
    // The selected look is the row's glide under the chip, so a chip here draws only its rest state.
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        modifier = modifier.paneItem("storechip:$key").heightIn(min = 44.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape)
            .background(if (on) Color.Transparent else colors.surface)
            .glideBorder(hot, shape, pal.signal, if (on) Color.Transparent else pal.line)
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
        // Pops as an install's dot lands in it.
        modifier = Modifier.landingPop().clip(RoundedCornerShape(8.dp)).background(pal.signal).padding(horizontal = 6.dp, vertical = 1.dp),
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
            if (!narrow) SignInButton(store)
        }
        if (narrow) Actions { SignInButton(store) }
    }
}

/**
 * Sign in: the button floods the page in the store's colour (LaunchFlood's move), the login page
 * opens on that colour, and the flood comes home into the store's dot once it closes.
 */
@Composable
private fun SignInButton(store: Store) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    Box(Modifier.onGloballyPositioned { placed[0] = it }) {
        PrimaryButton(stringResource(R.string.stores_signin), main = true) {
            val at = placed[0]?.takeIf { it.isAttached }?.boundsInRoot()
            if (at == null || Motion.scale == 0f || StoresMotion.signIn != null) StoresState.signIn(ctx, store)
            else StoresMotion.signIn = SignInFlood(store, at, colors.primary, sourceColours(store.id).dot)
        }
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
    onQuery: (String) -> Unit, onOpen: (String) -> Unit,
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
    // The Store tab's search: the catalog the shelves brought and the library, each title once.
    val everything = remember(library, shelves) { (library + (shelves?.all ?: emptyList())).distinctBy { it.id } }
    // Installed is a mode of its own now, picked from the store's chip like Store and Library.
    val installedOnly = tab == "installed"
    // Until the cache is read (a moment, off the main thread) there is nothing to count or to say is missing.
    val loaded = StoresState.library.containsKey(store)
    Rise(1) {
        ModeHeader(
            when {
                tab == "store" -> stringResource(R.string.stores_tab_store)
                !loaded -> stringResource(if (installedOnly) R.string.stores_tab_installed else R.string.stores_tab_library)
                installedOnly -> stringResource(R.string.stores_tab_count, stringResource(R.string.stores_tab_installed), installedItems.size)
                else -> stringResource(R.string.stores_tab_count, stringResource(R.string.stores_tab_library), library.size)
            },
            query, onQuery,
            placeholder = when {
                tab == "store" -> stringResource(R.string.stores_search_catalog, store.label)
                installedOnly -> stringResource(R.string.stores_search_installed)
                else -> stringResource(R.string.stores_search_library)
            },
        ) {
            // A purchase made elsewhere shows on a refresh; otherwise the library refreshes itself every six hours.
            SmallTextButton(stringResource(R.string.stores_refresh)) { StoresState.open(ctx, store, force = true) }
            val name = StoresState.accounts[store] ?: ""
            // Asks twice: a stray A here costs the sign-in and the library cache.
            var arm by remember(store) { mutableStateOf(false) }
            LaunchedEffect(arm) { if (arm) { delay(4000); arm = false } }
            SmallTextButton(if (arm) stringResource(R.string.stores_signout_confirm) else stringResource(R.string.stores_signout_named, name), key = "signout") {
                if (!arm) arm = true else { arm = false; StoresState.signOut(ctx, store) }
            }
        }
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
        "library", "installed" -> if (!loaded) Unit
            else if (installedOnly) Grid(installedItems.filter(::matches), stringResource(R.string.stores_installed_empty), card)
            else Grid(library.filter(::matches), if (library.isEmpty() && StoresState.status[store] != null) stringResource(R.string.stores_library_loading) else stringResource(R.string.stores_nothing_matches), card)
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

/**
 * What the store is showing (Store, Library, Installed), a magnifier beside it that opens into the
 * search field across the row, and [trailing] (the account) at the right, over a rule.
 */
@Composable
private fun ModeHeader(title: String, query: String, onQuery: (String) -> Unit, placeholder: String, trailing: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    // Open while it holds a query; an empty field closes again once focus leaves it.
    var searching by remember { mutableStateOf(query.isNotEmpty()) }
    val open by animateFloatAsState(if (searching) 1f else 0f, Motion.sp(0.75f, 420f), label = "searchOpen")
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 4.dp)) {
            Text(
                title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp),
            )
            BoxWithConstraints(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (searching || open > 0.01f) {
                    // The field grows out of the magnifier, the row's width at most.
                    val w = androidx.compose.ui.unit.lerp(44.dp, maxWidth.coerceAtMost(520.dp), open)
                    Box(Modifier.width(w).graphicsLayer { alpha = (open * 1.6f).coerceAtMost(1f) }) {
                        AdbTextField(
                            value = query, onValueChange = onQuery, label = "", keyboardType = KeyboardType.Text, imeAction = ImeAction.Search,
                            placeholder = placeholder, compact = true, focusOnShow = true,
                            onFocusChange = { focused -> if (!focused && query.isEmpty()) searching = false },
                        )
                    }
                } else SearchIcon(stringResource(R.string.stores_search_open)) { searching = true }
            }
            trailing()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
    }
}

/** A store chip's face: its dot and short name, for what steps out of it to carry. */
@Composable
private fun ChipLabel(store: Store, ink: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(sourceColours(store.id).dot))
        Text(store.shortLabel, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 1)
    }
}

/** The page's accent in [store]'s own colour (GOG purple, Epic white, Amazon orange); [base] for none. */
internal fun storePalette(base: Palette, store: Store?): Palette {
    val (accent, deep) = when (store) {
        Store.GOG -> Color(0xFFC25BC8) to Color(0xFF9A3FA1)
        Store.EPIC -> Color(0xFFE6E6E6) to Color(0xFFBDBDBD)
        Store.AMAZON -> Color(0xFFFF9900) to Color(0xFFE07800)
        null -> return base
    }
    val on = if (accent.luminance() > 0.18f) Color(0xFF0B0B0D) else Color.White
    return Palette(
        base.id, base.label, base.detail, base.background, base.surface, base.surfaceVariant, base.line, base.line2,
        base.onBackground, base.onSurfaceVariant, primary = accent, primary2 = deep, onPrimary = on, signal = accent,
        good = base.good, error = base.error,
    )
}

/** [content] with the focus ring, buttons and step-outs in [store]'s colour. */
@Composable
internal fun StoreTheme(store: Store?, content: @Composable () -> Unit) {
    val base = LocalPalette.current
    val p = remember(base, store) { storePalette(base, store) }
    if (p === base) { content(); return }
    val scheme = MaterialTheme.colorScheme.copy(primary = p.primary, onPrimary = p.onPrimary)
    CompositionLocalProvider(LocalPalette provides p) { MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content) }
}

@Composable
private fun SmallTextButton(text: String, key: String = text, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (hot) colors.onBackground else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.paneItem("btn:$key").clip(RoundedCornerShape(8.dp)).glideBorder(hot, RoundedCornerShape(8.dp), pal.signal)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** The header's magnifier: a round button the search field opens out of. */
@Composable
private fun SearchIcon(description: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.paneItem("storesearch").size(40.dp).clip(CircleShape).background(if (hot) pal.signal.copy(alpha = 0.14f) else Color.Transparent)
            .glideBorder(hot, CircleShape, pal.signal)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick),
    ) { Icon(Icons.Filled.Search, description, tint = if (hot) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
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

/**
 * A card's least width: shelf cards are this wide and a grid fits as many columns as the width
 * allows at it (the columns then share the leftover), so a display-density setting changes the
 * column count rather than the cards' size. 25% under the first 150 dp: five columns where there
 * were four on the Pocket FIT.
 */
private val CardWidth = 112.dp

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
    // Where it sits, so the game page can flood out of it (and draw back into it).
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val corner = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
    val open = {
        placed[0]?.takeIf { it.isAttached }?.let { StoresMotion.markCard(CardMark(item.key, it.boundsInRoot(), corner, item.imageUrl ?: item.tallImageUrl)) }
        onOpen(item.key)
    }
    // A size the library did not give is looked up once the card is on screen or close to it.
    val screen = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val ahead = screen / 2
    Column(
        modifier = Modifier.fillMaxWidth()
            .onGloballyPositioned { c ->
                placed[0] = c
                if (item.owned && item.sizeBytes <= 0) {
                    val b = c.boundsInWindow()
                    if (b.bottom > -ahead && b.top < screen + ahead && b.width > 0f) com.droiddeck.launcher.stores.StoreSizes.request(ctx, item)
                }
            }
            .paneItem("card:${item.key}").then(if (first) Modifier.firstTile() else Modifier).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp)).glideBorder(hot, RoundedCornerShape(10.dp), pal.signal, pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = open).controllerConfirm(onClick = open),
    ) {
        CardFace(item, download, hot) { CardMarker(item, installed, download) }
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
private val CardStrip = 26.dp

/**
 * The card is its art: sharp above, and under the one line a blurred, darkened copy of the same
 * picture, placed so the two meet without a seam. Blur needs API 31; below it the copy is only
 * darkened, more strongly.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CardFace(item: CatalogItem, download: com.droiddeck.launcher.stores.download.DownloadEntry?, hot: Boolean, marker: @Composable () -> Unit) {
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
                    // A title too long for the strip scrolls through while the card has focus.
                    Text(
                        item.title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1,
                        overflow = if (hot) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).then(if (hot) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 700, velocity = 40.dp) else Modifier),
                    )
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
        // Installed: its size on disk and a small check after it.
        installed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            val ctx = LocalContext.current
            val game = StoresState.installedGame(item.store, item.id)
            if (game != null) androidx.compose.runtime.LaunchedEffect(item.key) { com.droiddeck.launcher.stores.StoreSizes.requestDisk(ctx, item.key, game) }
            com.droiddeck.launcher.stores.StoreSizes.onDisk[item.key]?.let { Text(formatBytes(it), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1) }
            Icon(Icons.Filled.Check, stringResource(R.string.stores_installed_chip), tint = pal.good, modifier = Modifier.size(11.dp))
        }
        download != null -> Text("${download.percent}%", fontSize = small, fontWeight = FontWeight.Bold, color = pal.signal, maxLines = 1)
        item.owned && StoresState.isUnfinished(item) -> Text(stringResource(R.string.stores_resume), fontSize = small, fontWeight = FontWeight.Bold, color = pal.signal, maxLines = 1)
        // The size in the title's own type; nothing until it is known.
        item.owned -> com.droiddeck.launcher.stores.StoreSizes.size(item).takeIf { it > 0 }?.let { Text(formatBytes(it), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1) }
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
    // The fill glides to each new value over a quarter second - progress arrives a few times a second
    // and in steps - and is read only while drawing, so the bar animates without relayout.
    val shown = androidx.compose.animation.core.animateFloatAsState(
        fraction.coerceIn(0f, 1f), androidx.compose.animation.core.tween(250, easing = androidx.compose.animation.core.LinearEasing), label = "bar",
    )
    Box(
        Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(colors.surfaceVariant)
            .drawBehind { drawRect(fill, size = androidx.compose.ui.geometry.Size(size.width * shown.value, size.height)) },
    )
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
