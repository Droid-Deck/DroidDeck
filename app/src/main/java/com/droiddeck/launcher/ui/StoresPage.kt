package com.droiddeck.launcher.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.outlined.Folder
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreLanguage
import com.droiddeck.launcher.stores.epic.EpicClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import android.view.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.itch.ItchBrowseState
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreTab
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.arrangeStoreTabs
import com.droiddeck.launcher.stores.download.DownloadStage
import com.droiddeck.launcher.stores.download.DownloadState
import com.droiddeck.launcher.stores.formatBytes
import androidx.compose.runtime.setValue


private const val DOWNLOADS = "downloads"

private data class PaneKey(val chip: String, val signedIn: Boolean, val accountsLoaded: Boolean)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
internal fun StoresPage(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    val ctx = LocalContext.current
    val focusManager = LocalFocusManager.current
    val narrow = LocalNarrowPane.current
    val padH = if (narrow) 14.dp else 22.dp
    val tabs = remember {
        androidx.compose.runtime.mutableStateListOf<StoreTab>().apply {
            addAll(arrangeStoreTabs(SessionPrefs.storesTabOrder(ctx), SessionPrefs.storesTabHidden(ctx)))
        }
    }
    var chip by rememberSaveable { mutableStateOf(tabs.firstOrNull { it.shown }?.store?.id ?: Store.GOG.id) }
    var arranging by remember { mutableStateOf(false) }
    val shownStores = tabs.filter { it.shown }.map { it.store }
    fun commitTabs() {
        SessionPrefs.setStoresTabOrder(ctx, tabs.map { it.store.id })
        SessionPrefs.setStoresTabHidden(ctx, tabs.filterNot { it.shown }.map { it.store.id }.toSet())
    }
    fun shiftTab(store: Store, delta: Int) {
        val from = tabs.indexOfFirst { it.store == store }
        val to = from + delta
        if (from < 0 || to !in tabs.indices) return
        tabs.add(to, tabs.removeAt(from))
        commitTabs()
    }
    fun moveTab(store: Store, to: Int) {
        val from = tabs.indexOfFirst { it.store == store }
        if (from < 0) return
        val target = to.coerceIn(0, tabs.lastIndex)
        if (target == from) return
        tabs.add(target, tabs.removeAt(from))
        commitTabs()
    }
    fun toggleTab(store: Store) {
        val i = tabs.indexOfFirst { it.store == store }
        if (i < 0) return
        val show = !tabs[i].shown
        if (!show && tabs.count { it.shown } <= 1) return
        tabs[i] = tabs[i].copy(shown = show)
        commitTabs()
    }
    LaunchedEffect(tabs.map { it.store to it.shown }, chip) {
        val picked = Store.byId(chip) ?: return@LaunchedEffect
        if (tabs.none { it.store == picked && it.shown }) chip = tabs.firstOrNull { it.shown }?.store?.id ?: chip
    }
    var query by rememberSaveable { mutableStateOf("") }
    var openGame by rememberSaveable { mutableStateOf<String?>(null) }
    val liveState = androidx.compose.runtime.rememberUpdatedState(s)
    LaunchedEffect(StoresState.openDownloads) { if (StoresState.openDownloads) { chip = DOWNLOADS; StoresState.openDownloads = false } }
    val store = Store.byId(chip)
    LaunchedEffect(tabs.toList()) {
        if (chip != DOWNLOADS && Store.byId(chip) !in shownStores) chip = shownStores.firstOrNull()?.id ?: DOWNLOADS
    }
    val pane = PaneKey(
        chip,
        store == null || (StoresState.isSignedIn(store) && StoresState.expired[store] != true),
        store == null || StoresState.accountsLoaded,
    )
    val grids = remember { HashMap<PaneKey, LazyGridState>() }
    val grid = grids.getOrPut(pane) { LazyGridState() }
    LaunchedEffect(Unit) { StoresState.refresh(ctx) }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(chip) {
        if (chip == Store.BATTLENET.id) {
            while (true) {
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) com.droiddeck.launcher.stores.battlenet.BattleNetClient.refresh(ctx)
                delay(5_000)
            }
        }
        if (chip == Store.EPIC.id) {
            EpicClient.ensure(ctx)
            while (true) {
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) EpicClient.refresh(ctx)
                delay(5_000)
            }
        }
    }
    LaunchedEffect(chip, StoresState.accounts[store]) { if (store != null && StoresState.isSignedIn(store)) StoresState.open(ctx, store) }
    val ff = LocalFrontFocus.current
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    var focusMove by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var lastOpened by remember { mutableStateOf<String?>(null) }
    val heroFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var detailFocused by remember { mutableStateOf(false) }
    fun move(kind: String) { focusMove = kind to (focusMove?.second ?: 0) + 1 }
    fun open(key: String) { focusManager.clearFocus(force = true); lastOpened = key; openGame = key; move("detail") }
    fun closeGame() { openGame = null; move("card") }
    val pickChip: (String) -> Unit = { id ->
        chip = id; openGame = null; query = ""
        move("first")
    }
    LaunchedEffect(StoresState.openGameRequest) {
        val key = StoresState.openGameRequest ?: return@LaunchedEffect
        StoresState.openGameRequest = null
        Store.byId(key.substringBefore(':'))?.let { store -> chip = store.id; query = ""; open(key) }
    }
    var focusedChip by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedChip) {
        val id = focusedChip ?: return@LaunchedEffect
        delay(140)
        if (id != chip) { chip = id; openGame = null; query = "" }
    }
    val pal = LocalPalette.current
    fun cycleStore(step: Int) {
        val ids = tabs.filter { it.shown }.map { it.store.id }
        if (ids.isEmpty()) return
        val at = ids.indexOf(chip)
        val id = ids[if (at < 0) (if (step > 0) 0 else ids.size - 1) else (at + step + ids.size) % ids.size]
        chip = id; openGame = null; query = ""
        move("item:storechip:$id")
    }
    fun openModes(st: Store) {
        if (chip != st.id) pickChip(st.id) else move("first")
    }
    BackHandler(enabled = openGame != null) { closeGame() }
    BackHandler(enabled = arranging) { arranging = false }
    var contentFocused by remember { mutableStateOf(false) }
    BackHandler(enabled = contentFocused && openGame == null && !arranging) { move("item:storechip:$chip") }
    LaunchedEffect(arranging) {
        val focus = ff ?: return@LaunchedEffect
        if (!arranging || inputMode.inputMode != androidx.compose.ui.input.InputMode.Keyboard) return@LaunchedEffect
        repeat(45) {
            androidx.compose.runtime.withFrameNanos { }
            val req = tabs.firstOrNull()?.let { focus.items["storechip:${it.store.id}"] } ?: return@repeat
            if (runCatching { req.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    val scroll = rememberScrollState()
    val detailScroll = rememberScrollState()
    LaunchedEffect(chip) { scroll.scrollTo(0); grid.scrollToItem(0) }
    LaunchedEffect(focusMove, openGame) {
        if (inputMode.inputMode != androidx.compose.ui.input.InputMode.Keyboard || ff == null) return@LaunchedEffect
        var frames = 0
        fun attached(id: String) = (ff.attached[id] ?: 0) > 0
        fun target(): Pair<androidx.compose.ui.focus.FocusRequester, String?>? {
            val kind = focusMove?.first
            return when {
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
        if (openGame != null) {
            val back = "back:${ctx.getString(R.string.common_back)}"
            repeat(45) { n ->
                androidx.compose.runtime.withFrameNanos { }
                if (detailFocused) return@LaunchedEffect
                val req = if (n < 20) heroFocus else ff.items[back]?.takeIf { attached(back) } ?: heroFocus
                runCatching { req.requestFocus() }
            }
            return@LaunchedEffect
        }
        repeat(45) {
            androidx.compose.runtime.withFrameNanos { }
            val (req, id) = target() ?: return@repeat
            if (id != null && ff.last == id) return@LaunchedEffect
            if (runCatching { req.requestFocus() }.isSuccess && id == null) return@LaunchedEffect
        }
    }

    var hostAt by remember { mutableStateOf(Offset.Zero) }
    val backLabel = stringResource(R.string.common_back)
    Box(modifier.onGloballyPositioned { hostAt = it.positionInRoot() }) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = padH).padding(top = if (narrow) 10.dp else 14.dp)
            .then(if (openGame != null && !arranging) Modifier else Modifier.bumpers(
                onPrevious = {
                    val picked = Store.byId(focusedChip)
                    if (arranging) { if (picked != null) shiftTab(picked, -1) } else cycleStore(-1)
                },
                onNext = {
                    val picked = Store.byId(focusedChip)
                    if (arranging) { if (picked != null) shiftTab(picked, 1) } else cycleStore(1)
                },
            )),
    ) {
        Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            StoreTheme(Store.byId(focusedChip ?: chip)) {
                Rise(0) {
                    StoreChips(
                        chip, focusedChip, s.storeDownloadsActive, tabs, arranging,
                        onStore = { st -> openModes(st) },
                        onDownloads = { if (chip != DOWNLOADS) pickChip(DOWNLOADS) else move("first") },
                        onFocusChip = { focusedChip = it },
                        onArrangeDone = { arranging = false },
                        onMoveTab = { st, to -> moveTab(st, to) },
                        onToggleTab = { toggleTab(it) },
                        onSettings = { at -> if (at != null) StoresMotion.ask(storesSettingsStep(at, liveState, a, pal.signal) { arranging = true }) },
                    )
                }
            }
        }
        var viewportH by remember { mutableStateOf(0.dp) }
        val density = androidx.compose.ui.platform.LocalDensity.current
        CardDetailHost(
            openGame, detailScroll,
            gridScrolled = {
                if (store != null && pane.signedIn) grid.firstVisibleItemIndex > 0 || grid.firstVisibleItemScrollOffset > 0
                else scroll.value > 0
            },
            onDetailFocus = { detailFocused = it },
            modifier = Modifier.fillMaxWidth().weight(1f).onFocusChanged { contentFocused = it.hasFocus }.onSizeChanged { viewportH = with(density) { it.height.toDp() } },
            bleedHorizontal = padH,
            wrap = { key, content -> StoreTheme(Store.byId(key.substringBefore(':')) ?: store) { content() } },
            grid = {
                AnimatedContent(
                    targetState = pane,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        ((fadeIn(Motion.tw(300, 80)) + slideInVertically(Motion.tw(420, 80)) { it / 24 })
                            .togetherWith(fadeOut(Motion.tw(170)) + scaleOut(Motion.tw(170), targetScale = 0.97f))).using(SizeTransform(clip = false))
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
                                !k.accountsLoaded -> Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                                }
                                !k.signedIn -> Box(Modifier.fillMaxWidth().verticalScroll(scroll).padding(top = 4.dp, bottom = 16.dp)) { SignInCard(st, viewportH - 20.dp) }
                                else -> Storefront(st, query, s, a, grids.getOrPut(k) { LazyGridState() }, clearSearchFocus = openGame != null, onQuery = { query = it }, onOpen = { open(it) })
                            }
                          }
                      }
                    }
                }
            },
        ) { key -> GameDetail(key, s, a, heroFocus, backLabel, hopRail = null, onBack = { closeGame() }) }
    }
    FlightLayer(hostAt)
    StoresMotion.step?.let { ask ->
        key(ask) {
            StepOut(
                open = StoresMotion.stepOpen, pill = ask.anchor.translate(-hostAt), side = ask.side, accent = ask.accent,
                onDismiss = { StoresMotion.fold() }, onClosed = { StoresMotion.closed(ask) },
                pillCorner = ask.pillCorner, maxWidth = ask.maxWidth, items = ask.items, handle = ask.handle, content = ask.content,
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
}

@Composable
internal fun InstallWhere(item: CatalogItem) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val from = remember(item) { StoresMotion.recentInstall() }
    if (from == null || StoreLanguage.selectable(item.store)) {
        InstallWhereDialog(item) { StoresState.pendingInstall = null }
        return
    }
    val title = stringResource(R.string.stores_install_title, item.title)
    val internalLabel = stringResource(R.string.stores_target_internal)
    val label = StoresMotion.installLabel
    LaunchedEffect(item) {
        val targets = StoreInstallRoot.targets(ctx)
        val remembered = SessionPrefs.storesInstallTarget(ctx)
        val defaultIndex = targets.indexOfFirst { it.root.absolutePath == remembered }.coerceAtLeast(0)
        Steps.ask(StepAsk(
            anchor = from, side = StepSide.Right, accent = pal.signal, pillCorner = 12.dp, items = 1 + targets.size,
            maxWidth = (28 + 192 * targets.size - 8).dp,
            onDismiss = { if (StoresState.pendingInstall === item) StoresState.pendingInstall = null },
            handle = { Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.signal, maxLines = 1) },
        ) {
            StepTitle(title)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    StepTile(
                        label = if (t.removable) t.label else internalLabel,
                        icon = if (t.folder) androidx.compose.material.icons.Icons.Outlined.Folder else if (t.removable) androidx.compose.material.icons.Icons.Outlined.SdCard else androidx.compose.material.icons.Icons.Outlined.Smartphone,
                        detail = trailingText,
                        warning = isLow,
                        enabled = open,
                        modifier = Modifier.stepItem(1 + i).then(if (i == defaultIndex) Modifier.focusRequester(first) else Modifier),
                    ) {
                        SessionPrefs.setStoresInstallTarget(ctx, t.root.absolutePath)
                        StoresMotion.markInstall(from, label)
                        StoresState.install(ctx, item, t.root)
                        Steps.fold()
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


private class ChipDrag {
    var store by mutableStateOf<Store?>(null)
    var from by mutableIntStateOf(-1)
    var target by mutableIntStateOf(-1)
    var x by mutableFloatStateOf(0f)
}

@Composable
private fun StoreDot(store: Store, signedIn: Boolean) {
    val c = sourceColours(store.id)
    Box(
        Modifier.size(12.dp)
            .onGloballyPositioned { StoresMotion.dots[store] = it.boundsInRoot() }
            .pulseRing(StoresMotion.pulses[store] ?: 0, c.dot)
            .background(c.dot.copy(alpha = if (signedIn) 1f else 0.35f), CircleShape),
    )
}

@Composable
private fun RowScope.ArrangeStoreChip(
    tab: StoreTab, index: Int, last: Int, gapPx: Float, drag: ChipDrag,
    onFocusChip: (String?) -> Unit, onMoveTab: (Store, Int) -> Unit, onToggleTab: (Store) -> Unit,
) {
    val store = tab.store
    val box = remember(store) { mutableFloatStateOf(0f) }
    val pitch = box.floatValue + gapPx
    val held = drag.store == store
    val lift by animateFloatAsState(if (held) 1f else 0f, Motion.sp(0.7f, Spring.StiffnessMediumLow), label = "chipLift")
    val shift by animateFloatAsState(
        when {
            drag.store == null || held -> 0f
            drag.from < index && drag.target >= index -> -pitch
            drag.from > index && drag.target <= index -> pitch
            else -> 0f
        },
        Motion.sp(0.6f, Spring.StiffnessMediumLow), label = "chipShift",
    )
    val pill = remember { RoundedCornerShape(99.dp) }
    val signedIn = StoresState.isSignedIn(store) && StoresState.expired[store] != true
    fun reset() {
        drag.store = null
        drag.from = -1
        drag.target = -1
        drag.x = 0f
    }
    fun commit() {
        val from = drag.from
        val to = drag.target
        reset()
        if (from >= 0 && to >= 0 && to != from) onMoveTab(store, to)
    }
    StoreChip(
        label = store.shortLabel, on = false, under = false, key = store.id, mark = sourceColours(store.id).dot, lift = lift,
        modifier = Modifier.weight(1f)
            .zIndex(if (held) 2f else 0f)
            .onSizeChanged { box.floatValue = it.width.toFloat() }
            .graphicsLayer {
                translationX = if (held) drag.x else shift
                shadowElevation = 14.dp.toPx() * lift
                shape = pill
                clip = false
            }
            .pointerInput(store, index) {
                detectDragGestures(
                    onDragStart = {
                        drag.store = store
                        drag.from = index
                        drag.target = index
                        drag.x = 0f
                    },
                    onDragEnd = { commit() },
                    onDragCancel = { reset() },
                ) { change, amount ->
                    change.consume()
                    val step = box.floatValue + gapPx
                    if (step <= 0f) return@detectDragGestures
                    drag.x = (drag.x + amount.x).coerceIn(-drag.from * step, (last - drag.from) * step)
                    drag.target = (drag.from + (drag.x / step).roundToInt()).coerceIn(0, last)
                }
            }
            .alpha(if (tab.shown) 1f else 0.32f),
        lead = { StoreDot(store, signedIn) },
        onFocused = onFocusChip,
    ) { onToggleTab(store) }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StoreChips(
    selected: String, focused: String?, active: Int, tabs: List<StoreTab>, arranging: Boolean,
    onStore: (Store) -> Unit, onDownloads: () -> Unit, onSettings: (Rect?) -> Unit, onFocusChip: (String?) -> Unit,
    onArrangeDone: () -> Unit, onMoveTab: (Store, Int) -> Unit, onToggleTab: (Store) -> Unit,
) {
    val pal = LocalPalette.current
    val keys = tabs.filter { it.shown }.map { it.store.id } + DOWNLOADS
    val bounds = remember { androidx.compose.runtime.mutableStateMapOf<String, Rect>() }
    val highlighted = focused ?: selected
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current.inputMode
    val fillShown = inputMode != androidx.compose.ui.input.InputMode.Keyboard && !arranging
    val tint = androidx.compose.animation.animateColorAsState(
        Store.byId(highlighted)?.let { sourceColours(it.id).dot } ?: pal.signal, Motion.tw(160), label = "chipTint",
    )
    val gap = 8.dp
    val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { gap.toPx() }
    val drag = remember { ChipDrag() }
    val tabScroll = rememberScrollState()
    Box(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f).then(if (arranging) Modifier else Modifier.edgeFade(tabScroll).horizontalScroll(tabScroll))) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically,
                    modifier = (if (arranging) Modifier.fillMaxWidth() else Modifier).focusGroup(),
                ) {
                    for (tab in tabs) {
                        val store = tab.store
                        if (arranging) {
                            val index = tabs.indexOfFirst { it.store == store }
                            key(store) { ArrangeStoreChip(tab, index, tabs.lastIndex, gapPx, drag, onFocusChip, onMoveTab, onToggleTab) }
                        } else if (tab.shown) {
                            val signedIn = StoresState.isSignedIn(store) && StoresState.expired[store] != true
                            key(store) {
                                val reveal = remember { BringIntoViewRequester() }
                                LaunchedEffect(selected) { if (selected == store.id) reveal.bringIntoView() }
                                StoreChip(
                                    label = store.shortLabel, on = selected == store.id, under = fillShown && highlighted == store.id, key = store.id, mark = sourceColours(store.id).dot,
                                    modifier = Modifier.bringIntoViewRequester(reveal).onGloballyPositioned { bounds[store.id] = it.boundsInParent() },
                                    lead = { StoreDot(store, signedIn) },
                                    description = store.label + if (signedIn || !StoresState.accountsLoaded) "" else " " + stringResource(R.string.stores_chip_signed_out),
                                    onFocused = onFocusChip,
                                ) { onStore(store) }
                            }
                        }
                    }
                }
                ChipGlide(keys.indexOf(highlighted), bounds[highlighted], tint, visible = fillShown && highlighted != DOWNLOADS)
            }
            if (arranging) {
                IconChip(Icons.Filled.Check, stringResource(R.string.stores_tabs_arrange_done)) { onArrangeDone() }
            } else {
                Box {
                    StoreChip(
                        label = null, on = selected == DOWNLOADS, under = fillShown && highlighted == DOWNLOADS, key = DOWNLOADS, mark = pal.signal,
                        modifier = Modifier.onGloballyPositioned {
                            bounds[DOWNLOADS] = it.boundsInParent()
                            StoresMotion.downloadsChip = it.boundsInRoot()
                        },
                        lead = { Icon(Icons.Outlined.Download, null, modifier = Modifier.size(18.dp)) },
                        trail = if (active > 0) { { CountPill(active) } } else null,
                        description = stringResource(R.string.stores_tab_downloads),
                        onFocused = onFocusChip,
                    ) { onDownloads() }
                    ChipGlide(keys.indexOf(DOWNLOADS), bounds[DOWNLOADS], tint, visible = fillShown && highlighted == DOWNLOADS)
                }
                var cogAt by remember { mutableStateOf<Rect?>(null) }
                Box(Modifier.onGloballyPositioned { cogAt = it.boundsInRoot() }) {
                    IconChip(Icons.Filled.Settings, stringResource(R.string.stores_settings)) { onSettings(cogAt) }
                }
            }
        }
    }
}

@Composable
private fun StoreChip(
    label: String?, on: Boolean, under: Boolean, key: String, mark: Color, modifier: Modifier, lead: @Composable () -> Unit,
    trail: (@Composable () -> Unit)? = null, description: String? = null, lift: Float = 0f,
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
            .heightIn(min = 44.dp).graphicsLayer { scaleX = scale * (1f + 0.08f * lift); scaleY = scale * (1f + 0.08f * lift) }.clip(shape)
            .background(if (under) Color.Transparent else lerp(colors.surface, pal.signal, 0.18f * lift))
            .shownBar(on, mark)
            .glideBorder(hot, shape, pal.signal, if (under) Color.Transparent else lerp(pal.line, pal.signal, lift))
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Tab, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides ink) {
            Box(Modifier.pulseRing(picked, mark), contentAlignment = Alignment.Center) { lead() }
        }
        if (label != null) Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        val shelves = StoresState.shelves[store]
        val urls = (shelves?.art?.takeIf { it.isNotEmpty() }
            ?: shelves?.all.orEmpty().filter { !it.mature && !it.owned }.mapNotNull { it.tallImageUrl ?: it.imageUrl }).distinct().take(48)
        when {
            urls.size >= 8 -> urls
            urls.size >= 4 -> urls + urls.drop(urls.size / 2) + urls.take(urls.size / 2)
            else -> emptyList()
        }
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
    var askKey by remember { mutableStateOf(false) }
    if (askKey) ItchKeyDialog { askKey = false }
    Box(Modifier.onGloballyPositioned { placed[0] = it }) {
        PrimaryButton(stringResource(R.string.stores_signin), main = true, large = large, icon = if (large) Icons.AutoMirrored.Filled.Login else null) {
            if (store == Store.ITCH) askKey = true
            else {
                val at = placed[0]?.takeIf { it.isAttached }?.boundsInRoot()
                if (at == null || Motion.scale == 0f || StoresMotion.signIn != null) StoresState.signIn(ctx, store)
                else StoresMotion.signIn = SignInFlood(store, at, colors.primary, sourceColours(store.id).dot)
            }
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
    store: Store, query: String, s: FrontEndState, a: FrontEndActions,
    grid: LazyGridState,
    clearSearchFocus: Boolean,
    onQuery: (String) -> Unit, onOpen: (String) -> Unit,
) {
    BackHandler(enabled = query.isNotEmpty() && !clearSearchFocus) { onQuery("") }
    val ctx = LocalContext.current
    val library = StoresState.library[store] ?: emptyList()
    val shelves = StoresState.shelves[store]
    val found = StoresState.found[store] ?: emptyList()
    val installedKeys = remember(StoresState.installed) { StoresState.installed.filter { it.sidecar.store == store }.map { it.sidecar.id }.toSet() }
    val q = query.trim().lowercase()
    val foundKeys = remember(found) { found.map { it.key }.toSet() }
    fun shown(i: CatalogItem) = s.storesShowMature || !i.mature || i.accessible
    fun shelf(l: List<CatalogItem>?) = l?.filter(::shown)
    LaunchedEffect(store, q) { StoresState.search(ctx, store, q) }
    val browsing = store == Store.ITCH && q.isEmpty() && ItchBrowseState.active
    val browseItems = if (browsing) ItchBrowseState.items else emptyList()
    val catalog = store == Store.BATTLENET && q.isEmpty()
    val visible = remember(found, library, shelves, q, s.storesShowMature, browsing, browseItems, catalog) {
        if (catalog) shelf(shelves?.trending) ?: emptyList()
        else if (browsing) {
            val owned = library.associateBy { it.id }
            browseItems.map { owned[it.id] ?: it }.filter(::shown)
        } else if (q.isEmpty()) emptyList()
        else (found + library + (shelves?.all ?: emptyList())).distinctBy { it.id }
            .filter { (it.title.lowercase().contains(q) || it.key in foundKeys) && shown(it) }
    }
    val nothing = stringResource(when {
        browsing && ItchBrowseState.loading -> R.string.stores_shelves_loading
        browsing && ItchBrowseState.failed -> R.string.stores_itch_browse_failed
        catalog && shelves == null -> R.string.stores_shelves_loading
        else -> R.string.stores_nothing_matches
    })
    var firstPlaced = false
    var columns by remember { mutableIntStateOf(1) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val displayContext = LocalContext.current
    remember { LibraryDisplay.load(displayContext) }
    val tileWidth = libraryTileWidth()
    val focus = LocalFrontFocus.current
    val scope = rememberCoroutineScope()
    val searching = q.isNotEmpty() || browsing || catalog
    // A bar only while there is nothing else to look at: no shelves yet and no search results.
    val bareTab = if (searching) visible.isEmpty() else shelves == null || shelves.isEmpty
    val barLine = StoresState.status[store]?.takeIf { bareTab && syncCount(it) != null }
    val itchBar = store == Store.ITCH && q.isEmpty()
    val headerItems = 2 + (if (barLine != null) 1 else 0) + (if (StoresState.problems[store] != null) 1 else 0) + (if (itchBar) 1 else 0) + (if (catalog) 1 else 0)
    val card: @Composable (CatalogItem) -> Unit = { item ->
        val first = if (searching) item.key == visible.firstOrNull()?.key else !firstPlaced
        firstPlaced = true
        val navigation = if (!searching || focus == null) Modifier else Modifier.onPreviewKeyEvent { event ->
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
                            launch { grid.revealRow(headerItems + index, targetIndex, direction, with(density) { 10.dp.toPx() }) }
                            focusWithinFrames({ focus.last == target }) { focus.items[target] ?: androidx.compose.ui.focus.FocusRequester.Default }
                        }
                    }
                    true
                }
            }
        }
        GameCard(item, store, installedKeys, onOpen, first, navigation)
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(tileWidth), state = grid, modifier = Modifier.fillMaxSize().focusGroup().onSizeChanged {
            val gap = with(density) { 10.dp.roundToPx() }
            val minimum = with(density) { tileWidth.roundToPx() }
            columns = ((it.width + gap) / (minimum + gap)).coerceAtLeast(1)
        },
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
    ) {
        item(key = "profile", span = { GridItemSpan(maxLineSpan) }) { Rise(1) { ProfileStrip(store, library.size, StoresState.library.containsKey(store), s, a) } }
        item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
            Rise(2) { ModeHeader(store.label, query, onQuery, placeholder = stringResource(R.string.stores_search_catalog, store.label), clearSearchFocus = clearSearchFocus, inlineWhenStacked = true) { DisplayButton() } }
        }
        barLine?.let { line -> item(key = "sync", span = { GridItemSpan(maxLineSpan) }) { Rise(3) { SyncBar(line) } } }
        StoresState.problems[store]?.let { line -> item(key = "problem", span = { GridItemSpan(maxLineSpan) }) { Rise(3) { Box(Modifier.padding(bottom = 8.dp)) { Note(line) } } } }
        if (itchBar) item(key = "itch-browse-bar", span = { GridItemSpan(maxLineSpan) }) { ItchBrowseBar() }
        if (catalog) item(key = "catalog-label", span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.padding(top = 10.dp)) { SectionLabel(stringResource(R.string.bnet_store)) }
        }
        if (searching) {
            storeGrid(visible, nothing, card)
            if (browsing) item(key = "itch-more", span = { GridItemSpan(maxLineSpan) }) { ItchLoadMore() }
        } else item(key = "shelves", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Shelf(stringResource(R.string.stores_shelf_new), shelf(shelves?.whatsNew), card)
                Shelf(stringResource(R.string.stores_shelf_deals), shelf(shelves?.deals), card)
                Shelf(stringResource(R.string.stores_shelf_free), shelf(shelves?.free), card)
                Shelf(stringResource(R.string.stores_shelf_trending), shelf(shelves?.trending), card)
                if (shelves == null) Rise(4) { Box(Modifier.padding(top = 8.dp)) { Note(stringResource(R.string.stores_shelves_loading)) } }
                else if (shelves.isEmpty && store == Store.AMAZON) Rise(4) { Box(Modifier.padding(top = 8.dp)) { Note(stringResource(R.string.stores_amazon_no_catalog)) } }
            }
        }
    }
}

@Composable
private fun ProfileStrip(store: Store, owned: Int, loaded: Boolean, s: FrontEndState, a: FrontEndActions) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val show = LocalShowSection.current
    val name = StoresState.accounts[store] ?: ""
    val c = sourceColours(store.id)
    val shape = RoundedCornerShape(16.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(shape).background(colors.surface).border(1.dp, pal.line, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp).testTag("stores-profile"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).clip(CircleShape).background(c.dot)) {
                Text(name.take(1).uppercase().ifEmpty { store.shortLabel.take(1) }, fontSize = 16.sp, fontWeight = FontWeight.Black, color = if (c.dot.luminance() > 0.5f) Color.Black else Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(name.ifEmpty { store.label }, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (loaded) store.shortLabel + " · " + androidx.compose.ui.res.pluralStringResource(R.plurals.mode_added_count, owned, owned) else store.label,
                    fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Actions {
                SmallTextButton(stringResource(R.string.stores_owned_in_library), key = "owned-in-library") {
                    LibraryView.showOwned(ctx, store)
                    show("games")
                }
                SmallTextButton(stringResource(R.string.stores_refresh)) { StoresState.open(ctx, store, force = true) }
                if (store == Store.XBOX) SmallTextButton(stringResource(R.string.stores_xbox_cloud_settings), key = "xbox-cloud-settings") {
                    ctx.startActivity(android.content.Intent(ctx, com.droiddeck.launcher.stores.xbox.XboxCloudSettingsActivity::class.java))
                }
                SignOutButton(store, name)
            }
        }
        if (store == Store.EPIC) EpicLauncherRow(s, a)
        if (store == Store.BATTLENET) BattleNetLauncherRow(s, a)
    }
}

@Composable
private fun BattleNetLauncherRow(s: FrontEndState, a: FrontEndActions) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val title = stringResource(R.string.bnet_name)
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().testTag("stores-battlenet-launcher")) {
        val icon by androidx.compose.runtime.produceState<java.io.File?>(null, s.battleNetInstalled) {
            value = if (!s.battleNetInstalled) null
            else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.droiddeck.launcher.runtime.BattleNet.icon(ctx) }
        }
        if (icon != null) AsyncImage(model = icon, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Actions {
            SecondaryButton(stringResource(R.string.bnet_open), enabled = !s.busy, compact = true) { a.onBattleNet() }
            val entry = remember(s.battleNetInstalled) { com.droiddeck.launcher.runtime.BattleNet.launcherEntry(s.battleNetInstalled) }
            val scope = entry?.let { com.droiddeck.launcher.session.NoSteamLaunch.scope(it) }
            if (entry != null && scope != null) {
                val host = rememberMenuHost()
                val settings = rememberGameSettings(scope)
                Box {
                    SecondaryButton(stringResource(R.string.game_settings_card), compact = true) {
                        host.open = if (host.open == GAME_SETTINGS_MENU) null else GAME_SETTINGS_MENU
                    }
                    GameSettingsMenus(host, entry, settings)
                }
            }
        }
    }
}

@Composable
private fun EpicLauncherRow(s: FrontEndState, a: FrontEndActions) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val epic = EpicClient.status
    val title = stringResource(R.string.epic_client_title)
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().testTag("stores-epic-launcher")) {
        val icon by androidx.compose.runtime.produceState<java.io.File?>(null, epic.version) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { EpicClient.icon(ctx, epic.version) }
        }
        if (icon != null) AsyncImage(model = icon, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val version = epic.version?.substringBefore('+')
            Text(
                if (epic.installed && version != null) stringResource(R.string.epic_client_version, version) else stringResource(R.string.epic_client_missing),
                fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Actions {
            if (epic.installed) {
                SecondaryButton(stringResource(R.string.epic_client_open), enabled = !s.busy, compact = true) { a.onEpicLauncher() }
                val entry = remember(epic, title) { EpicClient.launcherEntry(ctx, title, epic, null) }
                val scope = entry?.let { com.droiddeck.launcher.session.NoSteamLaunch.scope(it) }
                if (entry != null && scope != null) {
                    val host = rememberMenuHost()
                    val settings = rememberGameSettings(scope)
                    Box {
                        SecondaryButton(stringResource(R.string.game_settings_card), compact = true) {
                            host.open = if (host.open == GAME_SETTINGS_MENU) null else GAME_SETTINGS_MENU
                        }
                        GameSettingsMenus(host, entry, settings)
                    }
                }
                UninstallEpicLauncher(s, a)
            } else SecondaryButton(stringResource(R.string.epic_client_install), compact = true) {
                EpicClient.install(ctx)
                StoresState.openDownloads = true
            }
        }
    }
}

@Composable
internal fun SectionLabel(title: String) {
    val pal = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 2.dp)) {
        Text(headingUppercase(title), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Box(Modifier.weight(1f).height(1.dp).background(pal.line))
    }
}

@Composable
internal fun ModeHeader(
    title: String, query: String, onQuery: (String) -> Unit, placeholder: String,
    clearSearchFocus: Boolean = false,
    lead: (@Composable () -> Unit)? = null, inlineWhenStacked: Boolean = false, trailing: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var searching by remember { mutableStateOf(query.isNotEmpty()) }
    var focusOnOpen by remember { mutableStateOf(false) }
    val open by animateFloatAsState(if (searching) 1f else 0f, Motion.sp(0.75f, 420f), label = "searchOpen")
    val close = {
        searching = false
        focusOnOpen = false
        if (query.isNotEmpty()) onQuery("")
    }
    BackHandler(enabled = searching && !clearSearchFocus) { close() }
    val heading: @Composable () -> Unit = {
        if (lead != null) lead()
        else Text(
            title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp),
        )
    }
    val searchField: @Composable (Modifier) -> Unit = { modifier ->
        BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
            val closable = maxWidth >= 80.dp
            val w = androidx.compose.ui.unit.lerp(44.dp, (maxWidth - if (closable) 36.dp else 0.dp).coerceIn(44.dp, 520.dp), open)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.width(w).graphicsLayer { alpha = (open * 1.6f).coerceAtMost(1f) }) {
                    AdbTextField(
                        value = query, onValueChange = onQuery, label = "", keyboardType = KeyboardType.Text, imeAction = ImeAction.Search,
                        placeholder = placeholder, compact = true, focusOnShow = focusOnOpen,
                        clearFocus = clearSearchFocus || !searching,
                        onFocusChange = { focused -> if (!focused && query.isEmpty()) { searching = false; focusOnOpen = false } },
                    )
                }
                if (searching && closable) SmallIconButton(Icons.Filled.Close, stringResource(R.string.common_close), tint = colors.onSurfaceVariant, onClick = close)
            }
        }
    }
    val searchButton: @Composable () -> Unit = {
        SearchIcon(stringResource(R.string.stores_search_open)) { searching = true; focusOnOpen = true }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < NarrowPaneWidth
        val inline = stacked && inlineWhenStacked && maxWidth >= 440.dp
        Column(Modifier.fillMaxWidth()) {
            if (inline && !(searching || open > 0.01f)) Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 4.dp),
            ) {
                heading()
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        searchButton()
                        trailing()
                    }
                }
            } else if (stacked) {
                Box(Modifier.padding(vertical = 4.dp)) { heading() }
                Row(
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).heightIn(min = 52.dp).padding(vertical = 4.dp),
                ) {
                    searchButton()
                    trailing()
                }
                if (searching || open > 0.01f) searchField(Modifier.fillMaxWidth().padding(bottom = 6.dp))
            } else Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 4.dp),
            ) {
                heading()
                if (searching || open > 0.01f) searchField(Modifier.weight(1f))
                else Box(Modifier.weight(1f)) { searchButton() }
                trailing()
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
        }
    }
}


internal fun storePalette(base: Palette, store: Store?): Palette {
    val (accent, deep) = when (store) {
        Store.GOG -> Color(0xFFC25BC8) to Color(0xFF9A3FA1)
        Store.EPIC -> Color(0xFFE6E6E6) to Color(0xFFBDBDBD)
        Store.AMAZON -> Color(0xFFFF9900) to Color(0xFFE07800)
        Store.XBOX -> Color(0xFF70C850) to Color(0xFF107C10)
        Store.ITCH -> Color(0xFFFF5C5C) to Color(0xFFD93B3B)
        Store.BATTLENET -> Color(0xFF148EFF) to Color(0xFF0B6BD3)
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
private fun SmallTextButton(text: String, key: String = text, danger: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(10.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.paneItem("btn:$key").heightIn(min = 40.dp).clip(shape)
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.03f))
            .glideBorder(hot, shape, pal.signal, pal.line2)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (danger) colors.error else if (hot) colors.onBackground else colors.onSurfaceVariant,
        )
    }
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
        SmallTextButton(label, key = "signout", danger = true) {
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
    Box(Modifier.padding(top = 10.dp, bottom = 8.dp)) { SectionLabel(title) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp)) {
        for (item in items.take(24)) key(item.key) { Box(Modifier.width(libraryTileWidth())) { card(item) } }
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

internal val CardWidth = 112.dp
internal val CapsuleWidth = 92.dp

@Composable
private fun GameCard(
    item: CatalogItem, store: Store, installedKeys: Set<String>, onOpen: (String) -> Unit,
    first: Boolean = false, navigation: Modifier = Modifier,
) {
    val ctx = LocalContext.current
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
                if (item.accessible && item.sizeBytes <= 0) {
                    val b = c.boundsInWindow()
                    if (b.bottom > -ahead && b.top < screen + ahead && b.width > 0f) com.droiddeck.launcher.stores.StoreSizes.request(ctx, item)
                }
            }
            .then(navigation).paneItem("card:${item.key}").then(if (first) Modifier.firstTile() else Modifier).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp)).glideBorder(hot, RoundedCornerShape(10.dp), pal.signal, pal.line)
            .hoverable(src).clickable(role = androidx.compose.ui.semantics.Role.Button, interactionSource = src, indication = LocalIndication.current, onClick = open).controllerConfirm(onClick = open),
    ) {
        val chip = when {
            download != null -> "${download.percent}%"
            item.accessible && StoresState.isUnfinished(item) -> stringResource(R.string.stores_resume)
            item.store == Store.GOG && installed && com.droiddeck.launcher.stores.gog.GogMaintenance.checks[item.id]?.state == com.droiddeck.launcher.stores.gog.GogMaintenance.State.AVAILABLE -> stringResource(R.string.common_update)
            else -> null
        }
        val priced = installed || item.isFree || item.isDiscounted || item.hasPrice
        val pill: (@Composable BoxScope.() -> Unit)? = if (priced && !LibraryDisplay.size && !hot) ({
            Box(Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.82f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                CardMarker(item, installed, download, compact = true, status = false)
            }
        }) else null
        ArtCard(item.title, item.imageUrl, item.tallImageUrl, download, hot, capsule = false, chip = chip, badge = pill) { CardMarker(item, installed, download, status = false) }
    }
}

internal fun syncCount(line: String): Pair<Int, Int>? {
    val match = Regex("(\\d+)\\s*/\\s*(\\d+)").find(line) ?: return null
    val done = match.groupValues[1].toIntOrNull() ?: return null
    val total = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
    return done to total
}

@Composable
internal fun SyncBar(line: String) {
    val count = syncCount(line) ?: return
    val done = count.first
    val total = count.second
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Box(Modifier.weight(1f)) { androidx.compose.material3.LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp)) }
        Text("$done/$total", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
internal fun cardArtRequest(url: Any?, cover: Any?): coil.request.ImageRequest? {
    val ctx = LocalContext.current
    val under = (cover as? String)?.takeIf { it != url }
    return remember(url, under) {
        url?.let { coil.request.ImageRequest.Builder(ctx).data(it).apply { if (under != null) placeholderMemoryCacheKey(under) }.build() }
    }
}

@Composable
private fun CardMarker(item: CatalogItem, installed: Boolean, download: com.droiddeck.launcher.stores.download.DownloadEntry?, compact: Boolean = false, status: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val small = 10.sp
    when {
        status && item.store == Store.GOG && download != null -> Text("${download.percent}%", fontSize = small, fontWeight = FontWeight.Bold, color = pal.signal, maxLines = 1)
        status && item.store == Store.GOG && installed && com.droiddeck.launcher.stores.gog.GogMaintenance.checks[item.id]?.state == com.droiddeck.launcher.stores.gog.GogMaintenance.State.AVAILABLE ->
            Text(stringResource(R.string.common_update), fontSize = small, fontWeight = FontWeight.Bold, color = pal.signal, maxLines = 1)
        installed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            val ctx = LocalContext.current
            val game = StoresState.installedGame(item.store, item.id)
            if (game != null && !compact) androidx.compose.runtime.LaunchedEffect(item.key) { com.droiddeck.launcher.stores.StoreSizes.requestDisk(ctx, item.key, game) }
            if (!compact) com.droiddeck.launcher.stores.StoreSizes.onDisk[item.key]?.let { Text(formatBytes(it), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1) }
            Icon(Icons.Filled.Check, stringResource(R.string.stores_installed_chip), tint = pal.good, modifier = Modifier.size(11.dp))
        }
        status && download != null -> Text("${download.percent}%", fontSize = small, fontWeight = FontWeight.Bold, color = pal.signal, maxLines = 1)
        status && item.accessible && StoresState.isUnfinished(item) -> Text(stringResource(R.string.stores_resume), fontSize = small, fontWeight = FontWeight.Bold, color = pal.signal, maxLines = 1)
        item.accessible && compact -> Unit
        item.accessible -> com.droiddeck.launcher.stores.StoreSizes.size(item).takeIf { it > 0 }?.let { Text(formatBytes(it), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1) }
        item.isFree -> Text(stringResource(R.string.stores_free), fontSize = small, fontWeight = FontWeight.Bold, color = pal.good, maxLines = 1)
        item.isDiscounted -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StateChip("-${item.discountPercent}%", ChipTone.DEAL, small = true)
            Text(item.finalPrice, fontSize = small, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
        }
        item.hasPrice -> Text(item.finalPrice, fontSize = small, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
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
    DownloadStage.MANIFEST -> when {
        d.store == Store.BATTLENET && d.detail.isNotBlank() -> d.detail
        d.stageItemsTotal > 0 -> stringResource(R.string.stores_stage_checking)
        else -> stringResource(R.string.stores_stage_manifest)
    }
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
    if (game != null) {
        a.onSteamGame(game)
        return
    }
    val app = ctx.applicationContext
    Thread({
        val installed = runCatching { com.droiddeck.launcher.stores.StoreLaunch.installedGame(app, store, id) }.getOrNull()
        StoresState.post {
            if (installed == null) android.widget.Toast.makeText(ctx, R.string.stores_launch_not_registered, android.widget.Toast.LENGTH_LONG).show()
            else a.onSteamGame(installed)
        }
    }, "store-play").start()
}
