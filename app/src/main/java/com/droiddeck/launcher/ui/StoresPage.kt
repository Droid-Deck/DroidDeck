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
import androidx.compose.ui.focus.onFocusChanged
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
import android.view.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.basicMarquee
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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


private const val DOWNLOADS = "downloads"

private val TABS = listOf("store", "library", "installed")

internal fun tabsFor(store: Store?): List<String> = if (store == Store.AMAZON) TABS.drop(1) else TABS

internal fun tabFor(store: Store?, tab: String): String = if (tab in tabsFor(store)) tab else "library"

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
    val liveState = androidx.compose.runtime.rememberUpdatedState(s)
    LaunchedEffect(StoresState.openDownloads) { if (StoresState.openDownloads) { chip = DOWNLOADS; StoresState.openDownloads = false } }
    val store = Store.byId(chip)
    val shownTab = tabFor(store, tab)
    val pane = PaneKey(
        chip, if (store == null) "" else shownTab,
        store == null || (StoresState.isSignedIn(store) && StoresState.expired[store] != true),
    )
    val grids = remember { HashMap<PaneKey, LazyGridState>() }
    val grid = grids.getOrPut(pane) { LazyGridState() }
    LaunchedEffect(Unit) { StoresState.refresh(ctx) }
    LaunchedEffect(chip, StoresState.accounts[store]) { if (store != null && StoresState.isSignedIn(store)) StoresState.open(ctx, store) }
    val ff = LocalFrontFocus.current
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    var focusMove by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var lastOpened by remember { mutableStateOf<String?>(null) }
    fun move(kind: String) { focusMove = kind to (focusMove?.second ?: 0) + 1 }
    fun open(key: String) { lastOpened = key; openGame = key; move("detail") }
    fun closeGame() { openGame = null; move("card") }
    fun switchTab(to: String) { tab = to; move("first") }
    val pickChip: (String) -> Unit = { id ->
        chip = id; openGame = null; query = ""
        if (id != DOWNLOADS) tab = tabFor(Store.byId(id), if (s.storesOpenTab == SessionPrefs.STORES_OPEN_STORE) "store" else "library")
        move("first")
    }
    var focusedChip by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedChip) {
        val id = focusedChip ?: return@LaunchedEffect
        delay(140)
        if (id != chip) {
            chip = id; openGame = null; query = ""
            if (id != DOWNLOADS) tab = tabFor(Store.byId(id), if (s.storesOpenTab == SessionPrefs.STORES_OPEN_STORE) "store" else "library")
        }
    }
    val pal = LocalPalette.current
    fun openModes(st: Store, at: Rect?) {
        if (chip != st.id) { pickChip(st.id); return }
        if (at == null || !StoresState.isSignedIn(st) || StoresState.expired[st] == true) { move("first"); return }
        val modes = tabsFor(st)
        val current = if (chip == st.id) shownTab else null
        var picked = false
        StoresMotion.ask(StepAsk(
            anchor = at, side = StepSide.Right, accent = storePalette(pal, st).signal, items = modes.size,
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
    LaunchedEffect(chip, tab) { scroll.scrollTo(0); grid.scrollToItem(0) }
    LaunchedEffect(openGame) { detailScroll.scrollTo(0) }
    LaunchedEffect(focusMove, openGame) {
        if (inputMode.inputMode != androidx.compose.ui.input.InputMode.Keyboard || ff == null) return@LaunchedEffect
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
                    ?: ff.primary.takeIf { ff.primaryAttached > 0 }?.let { it to FrontFocus.PRIMARY }
                    ?: "storechip:$chip".let { id -> ff.items[id]?.takeIf { ++frames > 12 && attached(id) }?.let { it to id } }
                kind?.startsWith("item:") == true -> kind.removePrefix("item:").let { id -> ff.items[id]?.takeIf { attached(id) }?.let { it to id } }
                else -> null
            }
        }
        repeat(60) { if (!HeldKeys.confirm) return@repeat; androidx.compose.runtime.withFrameNanos { } }
        repeat(45) {
            androidx.compose.runtime.withFrameNanos { }
            val (req, id) = target() ?: return@repeat
            if (id != null && ff.last == id) return@LaunchedEffect
            if (runCatching { req.requestFocus() }.isSuccess && id == null) return@LaunchedEffect
        }
    }

    var detail by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var detailFrom by remember { mutableStateOf<Origin?>(null) }
    var detailArt by remember { mutableStateOf<String?>(null) }
    var viewportAt by remember { mutableStateOf(Offset.Zero) }
    var viewportH by remember { mutableStateOf(0.dp) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    var hostAt by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(openGame) {
        val g = openGame
        if (g != null) {
            val mark = StoresMotion.takeCard(g)
            detailFrom = mark?.let { Origin(it.bounds.translate(-viewportAt), it.corner) }
            detailArt = mark?.art
            StoresMotion.heroLands = mark != null
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

    Box(modifier.onGloballyPositioned { hostAt = it.positionInRoot() }) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = padH, vertical = if (narrow) 10.dp else 14.dp)
            .bumpers(
                onPrevious = { if (openGame == null && chip != DOWNLOADS) { val t = tabsFor(store); switchTab(t[(t.indexOf(shownTab) + t.size - 1) % t.size]) } },
                onNext = { if (openGame == null && chip != DOWNLOADS) { val t = tabsFor(store); switchTab(t[(t.indexOf(shownTab) + 1) % t.size]) } },
            ),
    ) {
        Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            StoreTheme(Store.byId(focusedChip ?: chip)) {
                Rise(0) { StoreChips(chip, focusedChip, s.storeDownloadsActive, onStore = { st, at -> openModes(st, at) }, onDownloads = { if (chip != DOWNLOADS) pickChip(DOWNLOADS) else move("first") },
                    onFocusChip = { focusedChip = it }, onSettings = { at -> if (at != null) StoresMotion.ask(storesSettingsStep(at, liveState, a, pal.signal)) }) }
            }
        }
        val fade = with(androidx.compose.ui.platform.LocalDensity.current) { 14.dp.toPx() }
        Box(
            Modifier.fillMaxWidth().weight(1f).clipToBounds()
                .onGloballyPositioned { viewportAt = it.positionInRoot(); viewportH = with(density) { it.size.height.toDp() } }
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val scrolled = if (detail != null) detailScroll.value > 0
                        else if (store != null && pane.signedIn) grid.firstVisibleItemIndex > 0 || grid.firstVisibleItemScrollOffset > 0
                        else scroll.value > 0
                    if (scrolled && size.height > fade) drawRect(
                        Brush.verticalGradient(0f to Color.Transparent, fade / size.height to Color.Black),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                },
        ) {
            val gridFocus = if (covering) null else ff
            CompositionLocalProvider(LocalFrontFocus provides gridFocus) {
                Column(
                    modifier = Modifier.fillMaxSize()
                        .graphicsLayer { scaleX = gridScale; scaleY = gridScale; alpha = gridAlpha }
                        .focusProperties { enter = { if (coveringNow.value) androidx.compose.ui.focus.FocusRequester.Cancel else androidx.compose.ui.focus.FocusRequester.Default } }
                        .focusGroup(),
                ) {
                    AnimatedContent(
                        targetState = pane,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            val sameStore = initialState.chip == targetState.chip && initialState.signedIn == targetState.signedIn
                            (if (sameStore && initialState.tab != targetState.tab) {
                                val dir = if (TABS.indexOf(targetState.tab) > TABS.indexOf(initialState.tab)) 1 else -1
                                (slideInHorizontally(Motion.sp(0.7f, Spring.StiffnessMediumLow)) { it / 6 * dir } + fadeIn(Motion.tw(220, 60)))
                                    .togetherWith(slideOutHorizontally(Motion.tw(170)) { -it / 6 * dir } + fadeOut(Motion.tw(170)) + scaleOut(Motion.tw(170), targetScale = 0.97f))
                            } else {
                                (fadeIn(Motion.tw(300, 80)) + slideInVertically(Motion.tw(420, 80)) { it / 24 })
                                    .togetherWith(fadeOut(Motion.tw(170)) + scaleOut(Motion.tw(170), targetScale = 0.97f))
                            }).using(SizeTransform(clip = false))
                        },
                        label = "storesPane",
                    ) { k ->
                        val outgoing = transition.targetState == androidx.compose.animation.EnterExitState.PostExit
                        CompositionLocalProvider(LocalFrontFocus provides if (outgoing) null else LocalFrontFocus.current) {
                          val leavingNow = androidx.compose.runtime.rememberUpdatedState(outgoing)
                          val st = Store.byId(k.chip)
                          StoreTheme(st) {
                              Column(
                                  Modifier.fillMaxSize()
                                      .focusProperties { enter = { if (leavingNow.value) androidx.compose.ui.focus.FocusRequester.Cancel else androidx.compose.ui.focus.FocusRequester.Default } }
                                      .focusGroup(),
                              ) {
                                when {
                                    k.chip == DOWNLOADS -> Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(top = 4.dp, bottom = 16.dp)) { StoresDownloadsPane(s, a) }
                                    st == null -> {}
                                    !k.signedIn -> Box(Modifier.fillMaxWidth().verticalScroll(scroll).padding(top = 4.dp, bottom = 16.dp)) { SignInCard(st, viewportH - 20.dp) }
                                    else -> Storefront(st, k.tab, query, s, a, grids.getOrPut(k) { LazyGridState() }, onQuery = { query = it }, onOpen = { open(it) })
                                }
                              }
                          }
                        }
                    }
                }
            }
            detail?.let { key ->
                val detailStore = Store.byId(key.substringBefore(':')) ?: store
                StoreTheme(detailStore) {
                    val body: @Composable () -> Unit = {
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
                        val art = detailArt?.let { url ->
                            coil.compose.rememberAsyncImagePainter(
                                remember(url) {
                                    coil.request.ImageRequest.Builder(ctx).data(url).size(1280, 720)
                                        .placeholderMemoryCacheKey(url).build()
                                },
                            )
                        }
                        PageFlood(from, leaving, art, onto = { StoresMotion.heroAt }, ontoCorner = 16.dp) { body() }
                    }
                }
            }
        }
    }
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
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { StoresMotion.drop() } }
    val activeKeys = StoresState.downloads.filter { it.isActive }.map { it.key }.toSet()
    val seenActive = remember { mutableSetOf<String>().also { it.addAll(activeKeys) } }
    LaunchedEffect(activeKeys) {
        val fresh = activeKeys - seenActive
        seenActive.clear(); seenActive.addAll(activeKeys)
        if (fresh.isNotEmpty()) StoresMotion.takeInstall()?.let { StoresMotion.fly(it) }
    }
    StoresState.pendingInstall?.let { item -> InstallWhere(item) }
}

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
                    val needed = com.droiddeck.launcher.stores.StoreInstallRoot.estimatedBytesNeeded(item)
                    val freeBytes = t.freeBytes
                    val isLow = com.droiddeck.launcher.stores.StoreInstallRoot.insufficientSpace(needed, freeBytes)
                    val trailingText = if (freeBytes < 0L) {
                        stringResource(R.string.stores_target_free_unknown)
                    } else if (isLow) {
                        stringResource(R.string.stores_storage_low, formatBytes(freeBytes), formatBytes(needed))
                    } else {
                        stringResource(R.string.stores_target_free, formatBytes(freeBytes))
                    }
                    StepChoice(
                        label = if (t.removable) t.label else internalLabel,
                        icon = if (t.removable) androidx.compose.material.icons.Icons.Outlined.SdCard else androidx.compose.material.icons.Icons.Outlined.Smartphone,
                        trailing = trailingText,
                        enabled = open,
                        modifier = Modifier.fillMaxWidth().stepItem(1 + i).then(if (i == defaultIndex) Modifier.focusRequester(first) else Modifier),
                    ) {
                        SessionPrefs.setStoresInstallTarget(ctx, t.root.absolutePath)
                        StoresMotion.markInstall(from, label)
                        StoresState.install(ctx, item, t.root)
                        StoresMotion.fold()
                    }
                }
            }
        })
    }
}

@Composable
private fun SignInFloodLayer(hostAt: Offset) {
    val f = StoresMotion.signIn ?: return
    val ctx = LocalContext.current
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    val density = androidx.compose.ui.platform.LocalDensity.current
    key(f) {
        var paused by remember { mutableStateOf(false) }
        fun back() { if (f.drainTo == null) f.drainTo = if (StoresState.isSignedIn(f.store)) StoresMotion.dots[f.store] ?: f.from else f.from }
        androidx.compose.runtime.DisposableEffect(lifecycle) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE && f.away) paused = true
                if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && f.away && paused) back()
            }
            lifecycle.addObserver(obs)
            onDispose { lifecycle.removeObserver(obs) }
        }
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


@Composable
private fun StoreChips(
    selected: String, focused: String?, active: Int, onStore: (Store, Rect?) -> Unit, onDownloads: () -> Unit, onSettings: (Rect?) -> Unit,
    onFocusChip: (String?) -> Unit,
) {
    val pal = LocalPalette.current
    val keys = Store.entries.map { it.id } + DOWNLOADS
    val bounds = remember { androidx.compose.runtime.mutableStateMapOf<String, Rect>() }
    val rootBounds = remember { HashMap<String, Rect>() }
    val highlighted = focused ?: selected
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current.inputMode
    val fillShown = inputMode != androidx.compose.ui.input.InputMode.Keyboard
    val tint = androidx.compose.animation.animateColorAsState(
        Store.byId(highlighted)?.let { sourceColours(it.id).dot } ?: pal.signal, Motion.tw(160), label = "chipTint",
    )
    Box(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            for (store in Store.entries) {
                val signedIn = StoresState.isSignedIn(store) && StoresState.expired[store] != true
                StoreChip(
                    label = store.shortLabel, on = selected == store.id, under = fillShown && highlighted == store.id, key = store.id, mark = sourceColours(store.id).dot,
                    modifier = Modifier.weight(1f).onGloballyPositioned { bounds[store.id] = it.boundsInParent(); rootBounds[store.id] = it.boundsInRoot() },
                    lead = {
                        val c = sourceColours(store.id)
                        Box(
                            Modifier.size(12.dp)
                                .onGloballyPositioned { StoresMotion.dots[store] = it.boundsInRoot() }
                                .pulseRing(StoresMotion.pulses[store] ?: 0, c.dot),
                        ) { Box(Modifier.matchParentSize().clip(CircleShape).background(c.dot).alpha(if (signedIn) 1f else 0.35f)) }
                    },
                    description = store.label + if (signedIn) "" else " " + stringResource(R.string.stores_chip_signed_out),
                    onFocused = onFocusChip,
                ) { onStore(store, rootBounds[store.id]) }
            }
            StoreChip(
                label = stringResource(R.string.stores_tab_downloads), on = selected == DOWNLOADS, under = fillShown && highlighted == DOWNLOADS, key = DOWNLOADS, mark = pal.signal,
                modifier = Modifier.weight(1f).onGloballyPositioned {
                    bounds[DOWNLOADS] = it.boundsInParent()
                    StoresMotion.downloadsChip = it.boundsInRoot()
                },
                lead = { Icon(Icons.Outlined.Download, null, modifier = Modifier.size(18.dp)) },
                trail = if (active > 0) { { CountPill(active) } } else null,
                onFocused = onFocusChip,
            ) { onDownloads() }
            var cogAt by remember { mutableStateOf<Rect?>(null) }
            Box(Modifier.onGloballyPositioned { cogAt = it.boundsInRoot() }) {
                IconChip(Icons.Filled.Settings, stringResource(R.string.stores_settings)) { onSettings(cogAt) }
            }
        }
        ChipGlide(keys.indexOf(highlighted), bounds[highlighted], tint, visible = fillShown)
    }
}

@Composable
private fun StoreChip(
    label: String, on: Boolean, under: Boolean, key: String, mark: Color, modifier: Modifier, lead: @Composable () -> Unit,
    trail: (@Composable () -> Unit)? = null, description: String? = null,
    onFocused: (String?) -> Unit = {}, onClick: () -> Unit,
) {
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "chipScale")
    val shape = RoundedCornerShape(99.dp)
    val ink = if (on) colors.onBackground else if (hot) colors.onBackground else colors.onSurfaceVariant
    var picked by remember { mutableStateOf(0) }
    val wasOn = remember { booleanArrayOf(on) }
    LaunchedEffect(on) {
        if (on && !wasOn[0]) picked++
        wasOn[0] = on
    }
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        modifier = modifier.paneItem("storechip:$key")
            .onFocusChanged { f ->
                if (f.isFocused && inputMode.inputMode == androidx.compose.ui.input.InputMode.Keyboard) onFocused(key)
                else if (!f.isFocused) onFocused(null)
            }
            .heightIn(min = 44.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape)
            .background(if (under) Color.Transparent else colors.surface)
            .shownBar(on, mark)
            .glideBorder(hot, shape, pal.signal, if (under) Color.Transparent else pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Tab, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides ink) {
            Box(Modifier.pulseRing(picked, mark), contentAlignment = Alignment.Center) { lead() }
        }
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trail?.invoke()
    }
}

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
        modifier = Modifier.landingPop().clip(RoundedCornerShape(8.dp)).background(pal.signal).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}


@Composable
private fun SignInCard(store: Store, height: Dp) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val narrow = LocalNarrowPane.current
    val c = sourceColours(store.id)
    LaunchedEffect(store) { StoresState.preview(ctx, store) }
    val art = remember(StoresState.shelves[store]) {
        StoresState.shelves[store]?.all.orEmpty().filter { !it.mature }.mapNotNull { it.tallImageUrl ?: it.imageUrl }.distinct().take(48)
            .takeIf { it.size >= 8 }.orEmpty()
    }
    val shape = RoundedCornerShape(20.dp)
    val blank = WallCapsule(Brush.linearGradient(listOf(c.fill, colors.background)), edge = c.dot.copy(alpha = 0.22f))
    val ground = SolidColor(c.fill)
    Box(Modifier.fillMaxWidth().heightIn(min = height.coerceAtLeast(0.dp)).clip(shape).background(colors.background).border(1.dp, pal.line, shape)) {
        androidx.compose.animation.Crossfade(art, animationSpec = Motion.tw(600), label = "signInWall", modifier = Modifier.matchParentSize()) { tiles ->
            TiltedWall(tiles.size, if (tiles.isEmpty()) 75_000 else 50_000) { i -> if (tiles.isEmpty()) blank else WallCapsule(ground, tiles[i]) }
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(
                    0f to colors.background.copy(alpha = 0.97f), 0.45f to colors.background.copy(alpha = 0.9f),
                    0.78f to colors.background.copy(alpha = 0.35f), 1f to colors.background.copy(alpha = 0.15f),
                ),
            ),
        )
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to colors.background.copy(alpha = 0.92f))))
        Box(
            Modifier.matchParentSize().drawBehind {
                drawRect(Brush.radialGradient(listOf(c.dot.copy(alpha = 0.22f), Color.Transparent), center = Offset(size.width * 0.16f, size.height * 0.9f), radius = size.height * 0.75f))
            },
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = if (narrow) 20.dp else 40.dp, bottom = if (narrow) 20.dp else 36.dp, end = 20.dp),
        ) {
            Rise(2) {
                Text(
                    store.label, fontSize = if (narrow) 30.sp else 42.sp, lineHeight = if (narrow) 34.sp else 46.sp,
                    fontWeight = FontWeight.Black, color = colors.onBackground,
                )
            }
            Spacer(Modifier.height(6.dp))
            Rise(3) { SignInButton(store, large = true) }
        }
    }
}

@Composable
private fun SignInButton(store: Store, large: Boolean = false) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    Box(Modifier.onGloballyPositioned { placed[0] = it }) {
        PrimaryButton(stringResource(R.string.stores_signin), main = true, large = large, icon = if (large) Icons.AutoMirrored.Filled.Login else null) {
            val at = placed[0]?.takeIf { it.isAttached }?.boundsInRoot()
            if (at == null || Motion.scale == 0f || StoresMotion.signIn != null) StoresState.signIn(ctx, store)
            else StoresMotion.signIn = SignInFlood(store, at, colors.primary, sourceColours(store.id).dot)
        }
    }
}

@Composable
internal fun StoreLogo(store: Store, size: Dp) {
    val c = sourceColours(store.id)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size).clip(Shape14).background(c.fill).border(1.dp, c.dot.copy(alpha = 0.5f), Shape14)) {
        Text(store.shortLabel, fontSize = (size.value / 4).sp, fontWeight = FontWeight.Black, color = c.ink, maxLines = 1)
    }
}


@Composable
private fun Storefront(
    store: Store, tab: String, query: String, s: FrontEndState, a: FrontEndActions,
    grid: LazyGridState,
    onQuery: (String) -> Unit, onOpen: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val library = StoresState.library[store] ?: emptyList()
    val shelves = StoresState.shelves[store]
    val installedItems = remember(StoresState.installed, library) { com.droiddeck.launcher.stores.installedCards(store, StoresState.installed, library) }
    val installedKeys = remember(installedItems) { installedItems.map { it.id }.toSet() + StoresState.installed.filter { it.sidecar.store == store }.map { it.sidecar.id } }
    val q = query.trim().lowercase()
    fun matches(i: CatalogItem) = q.isEmpty() || i.title.lowercase().contains(q)
    fun shown(i: CatalogItem) = s.storesShowMature || !i.mature || i.owned
    fun shelf(l: List<CatalogItem>?) = l?.filter(::shown)
    val everything = remember(library, shelves) { (library + (shelves?.all ?: emptyList())).distinctBy { it.id } }
    val installedOnly = tab == "installed"
    val loaded = StoresState.library.containsKey(store)
    val showGrid = tab != "store" || q.isNotEmpty()
    val visible = remember(library, installedItems, everything, tab, q, s.storesShowMature) {
        when (tab) {
            "library" -> library.filter(::matches)
            "installed" -> installedItems.filter(::matches)
            else -> everything.filter { matches(it) && shown(it) }
        }
    }
    val empty = stringResource(when {
        installedOnly -> R.string.stores_installed_empty
        tab == "library" && library.isEmpty() && StoresState.status[store] != null -> R.string.stores_library_loading
        else -> R.string.stores_nothing_matches
    })
    var firstPlaced = false
    var columns by remember { mutableIntStateOf(1) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val focus = LocalFrontFocus.current
    val scope = rememberCoroutineScope()
    val headerItems = 1 + (if (StoresState.status[store] != null) 1 else 0) + (if (StoresState.problems[store] != null) 1 else 0)
    val card: @Composable (CatalogItem) -> Unit = { item ->
        val first = if (showGrid) item.key == visible.firstOrNull()?.key else !firstPlaced
        firstPlaced = true
        val navigation = if (!showGrid || focus == null) Modifier else Modifier.onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            val direction = when (key.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> 1
                KeyEvent.KEYCODE_DPAD_UP -> -1
                else -> 0
            }
            val index = visible.indexOfFirst { it.key == item.key }
            val next = storeGridNeighbor(index, visible.size, columns, direction)
            when {
                direction == 0 || index < 0 -> false
                next == null -> direction > 0
                else -> {
                    if (key.action == KeyEvent.ACTION_DOWN) {
                        val target = "card:${visible[next].key}"
                        val targetIndex = headerItems + next
                        val visibleTarget = grid.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
                        if (!visibleTarget || (focus.attached[target] ?: 0) == 0 ||
                            runCatching { focus.items.getValue(target).requestFocus() }.isFailure) scope.launch {
                            grid.scrollToItem(targetIndex)
                            focusWithinFrames({ focus.last == target }) { focus.items[target] ?: androidx.compose.ui.focus.FocusRequester.Default }
                        }
                    }
                    true
                }
            }
        }
        GameCard(item, store, s, a, installedKeys, onOpen, first, navigation)
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(CardWidth), state = grid, modifier = Modifier.fillMaxSize().focusGroup().onSizeChanged {
            val gap = with(density) { 10.dp.roundToPx() }
            val minimum = with(density) { CardWidth.roundToPx() }
            columns = ((it.width + gap) / (minimum + gap)).toInt().coerceAtLeast(1)
        },
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
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
                    SmallTextButton(stringResource(R.string.stores_refresh)) { StoresState.open(ctx, store, force = true) }
                    val name = StoresState.accounts[store] ?: ""
                    SignOutButton(store, name)
                }
            }
        }
        StoresState.status[store]?.let { line -> item(key = "sync", span = { GridItemSpan(maxLineSpan) }) { Rise(3) { SyncBar(line) } } }
        StoresState.problems[store]?.let { line -> item(key = "problem", span = { GridItemSpan(maxLineSpan) }) { Rise(3) { Box(Modifier.padding(bottom = 8.dp)) { Note(line) } } } }
        when (tab) {
            "library", "installed" -> if (!loaded) Unit
                else storeGrid(visible, empty, card)
            else -> {
                if (q.isNotEmpty()) storeGrid(visible, empty, card)
                else {
                    item(key = "shelves", span = { GridItemSpan(maxLineSpan) }) {
                        Column {
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
        }
    }
}

@Composable
internal fun ModeHeader(title: String, query: String, onQuery: (String) -> Unit, placeholder: String, trailing: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
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

@Composable
private fun ChipLabel(store: Store, ink: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(sourceColours(store.id).dot))
        Text(store.shortLabel, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 1)
    }
}

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

internal fun flathubPalette(base: Palette): Palette = Palette(
    base.id, base.label, base.detail, base.background, base.surface, base.surfaceVariant, base.line, base.line2,
    base.onBackground, base.onSurfaceVariant, primary = FlathubTeal, primary2 = Color(0xFF2E958D), onPrimary = FlathubInk, signal = FlathubTeal,
    good = base.good, error = base.error,
)

@Composable
internal fun FlathubTheme(content: @Composable () -> Unit) {
    val base = LocalPalette.current
    val p = remember(base) { flathubPalette(base) }
    val scheme = MaterialTheme.colorScheme.copy(primary = p.primary, onPrimary = p.onPrimary)
    CompositionLocalProvider(LocalPalette provides p) { MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content) }
}

@Composable
internal fun StoreTheme(store: Store?, content: @Composable () -> Unit) {
    val base = LocalPalette.current
    val p = remember(base, store) { storePalette(base, store) }
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

@Composable
private fun SignOutButton(store: Store, name: String) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.stores_signout_named, name)
    val title = stringResource(R.string.stores_signout_title, store.label)
    val note = stringResource(R.string.stores_signout_note)
    val stay = stringResource(R.string.stores_signout_stay)
    val go = stringResource(R.string.stores_signout_go)
    var at by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
        SmallTextButton(label, key = "signout") {
            val from = at
            if (from == null) { StoresState.signOut(ctx, store); return@SmallTextButton }
            StoresMotion.ask(StepAsk(
                anchor = from, side = StepSide.Left, accent = colors.error, pillCorner = 8.dp, items = 3,
                handle = { Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.error, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            ) {
                StepTitle(title)
                Text(note, fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.stepItem(1).padding(start = 4.dp, bottom = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.stepItem(2)) {
                    StepChoice(stay, enabled = open, modifier = Modifier.focusRequester(first)) { StoresMotion.fold() }
                    StepChoice(go, danger = true, enabled = open) { StoresMotion.fold(); StoresState.signOut(ctx, store) }
                }
            })
        }
    }
}

@Composable
internal fun SearchIcon(description: String, onClick: () -> Unit) {
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

@Composable
private fun Shelf(title: String, items: List<CatalogItem>?, card: @Composable (CatalogItem) -> Unit) {
    if (items.isNullOrEmpty()) return
    val colors = MaterialTheme.colorScheme
    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp)) {
        for (item in items.take(24)) key(item.key) { Box(Modifier.width(CardWidth)) { card(item) } }
    }
}

private fun LazyGridScope.storeGrid(games: List<CatalogItem>, empty: String, card: @Composable (CatalogItem) -> Unit) {
    if (games.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
        Box(Modifier.padding(vertical = 32.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(empty, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    else items(games, key = { it.key }, contentType = { "game" }) { card(it) }
}

private val CardWidth = 112.dp

@Composable
private fun GameCard(item: CatalogItem, store: Store, s: FrontEndState, a: FrontEndActions, installedKeys: Set<String>, onOpen: (String) -> Unit, first: Boolean = false, navigation: Modifier = Modifier) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "cardScale")
    val installed = item.id in installedKeys
    val download = StoresState.download("${store.id}:${item.id}")?.takeIf { it.isActive }
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val corner = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
    val open = {
        placed[0]?.takeIf { it.isAttached }?.let { StoresMotion.markCard(CardMark(item.key, it.boundsInRoot(), corner, item.imageUrl ?: item.tallImageUrl)) }
        onOpen(item.key)
    }
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
            .then(navigation).paneItem("card:${item.key}").then(if (first) Modifier.firstTile() else Modifier).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp)).glideBorder(hot, RoundedCornerShape(10.dp), pal.signal, pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = open).controllerConfirm(onClick = open),
    ) {
        CardFace(item, download, hot) { CardMarker(item, installed, download) }
    }
}

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

private val CardStrip = 26.dp

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
                    Text(
                        item.title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1,
                        overflow = if (hot) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).then(if (hot) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 700, velocity = 40.dp) else Modifier),
                    )
                    marker()
                }
            }
            Spacer(Modifier.align(Alignment.BottomCenter).padding(bottom = CardStrip).fillMaxWidth().height(14.dp).background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f))))
            if (download != null) Box(Modifier.align(Alignment.BottomCenter).padding(start = 6.dp, end = 6.dp, bottom = CardStrip + 5.dp)) {
                ProgressBarThin(download.fraction, paused = download.state == DownloadState.PAUSED, verify = download.stage == DownloadStage.VERIFY)
            }
        }
    }
}

@Composable
private fun CardMarker(item: CatalogItem, installed: Boolean, download: com.droiddeck.launcher.stores.download.DownloadEntry?) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val small = 10.sp
    when {
        installed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            val ctx = LocalContext.current
            val game = StoresState.installedGame(item.store, item.id)
            if (game != null) androidx.compose.runtime.LaunchedEffect(item.key) { com.droiddeck.launcher.stores.StoreSizes.requestDisk(ctx, item.key, game) }
            com.droiddeck.launcher.stores.StoreSizes.onDisk[item.key]?.let { Text(formatBytes(it), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1) }
            Icon(Icons.Filled.Check, stringResource(R.string.stores_installed_chip), tint = pal.good, modifier = Modifier.size(11.dp))
        }
        download != null -> Text("${download.percent}%", fontSize = small, fontWeight = FontWeight.Bold, color = pal.signal, maxLines = 1)
        item.owned && StoresState.isUnfinished(item) -> Text(stringResource(R.string.stores_resume), fontSize = small, fontWeight = FontWeight.Bold, color = pal.signal, maxLines = 1)
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

@Composable
internal fun activeStageLabel(d: com.droiddeck.launcher.stores.download.DownloadEntry): String = when (d.stage) {
    DownloadStage.MANIFEST -> if (d.stageItemsTotal > 0) stringResource(R.string.stores_stage_checking) else stringResource(R.string.stores_stage_manifest)
    DownloadStage.DOWNLOAD -> stringResource(R.string.stores_stage_downloading)
    DownloadStage.VERIFY -> stringResource(R.string.stores_stage_verifying)
    DownloadStage.INSTALL -> stringResource(R.string.stores_stage_installing)
    DownloadStage.DONE -> stringResource(R.string.stores_stage_done)
}

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

internal fun openStoreUrl(ctx: android.content.Context, item: CatalogItem) = openUrl(ctx, item.storeUrl)

internal fun openUrl(ctx: android.content.Context, url: String) {
    if (url.isBlank()) return
    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

internal fun launchStoreGame(ctx: android.content.Context, store: Store, id: String, s: FrontEndState, a: FrontEndActions) {
    val game = s.steamGames.firstOrNull { it.source == store.id && it.storeId == id }
    if (game == null) {
        android.widget.Toast.makeText(ctx, R.string.stores_launch_not_registered, android.widget.Toast.LENGTH_LONG).show()
        return
    }
    com.droiddeck.launcher.stores.StoreLaunch.prepare(ctx, store, id) { a.onSteamGame(game) }
}
