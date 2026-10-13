package com.droiddeck.launcher.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.store.FlathubApi
import com.droiddeck.launcher.store.StoreState
import kotlinx.coroutines.delay


private const val MODE_DISCOVER = "discover"
private const val MODE_INSTALLED = "installed"
private const val MODE_UPDATES = "updates"

private class CardApp(val id: String, val name: String, val icon: Any?, val summary: String? = null, val verified: Boolean = false)

private object FlathubMotion {
    var installedChip: Rect? = null
    var landed by mutableIntStateOf(0)
}

@Composable
internal fun StorePage(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    val ctx = LocalContext.current
    LaunchedEffect(s.ready) { StoreState.refresh(ctx) }
    LaunchedEffect(Unit) { StoreState.loadSections() }
    FlathubTheme {
        var front by remember { mutableStateOf(StoreState.ready && s.ready) }
        var ring by remember { mutableIntStateOf(0) }
        LaunchedEffect(StoreState.ready, s.ready) {
            val ready = StoreState.ready && s.ready
            if (ready && !front) {
                ring++
                delay(Motion.ms(450).toLong())
                front = true
            } else if (!ready) front = false
        }
        AnimatedContent(
            front,
            modifier = modifier,
            transitionSpec = {
                EnterTransition.None.togetherWith(fadeOut(Motion.tw(320)) + scaleOut(Motion.tw(320), targetScale = 0.95f))
            },
            label = "flathub",
        ) { ready ->
            if (ready) Storefront(s, a) else FlathubHero(s, ring)
        }
    }
}

@Composable
private fun FlathubHero(s: FrontEndState, ring: Int) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val narrow = LocalNarrowPane.current
    val icons = remember(StoreState.sections.values.toList()) {
        StoreState.sections.values.flatten().mapNotNull { it.icon }.distinct().take(48).takeIf { it.size >= 8 }.orEmpty()
    }
    val busy = StoreState.busy == "setup"
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = if (narrow) 14.dp else 22.dp, vertical = if (narrow) 10.dp else 14.dp)) {
        val shape = RoundedCornerShape(20.dp)
        Box(Modifier.fillMaxWidth().height(maxHeight.coerceAtLeast(320.dp)).clip(shape).background(colors.background).border(1.dp, pal.line, shape)) {
            androidx.compose.animation.Crossfade(icons, animationSpec = Motion.tw(600), label = "flathubWall") { tiles ->
                val ground = Brush.linearGradient(listOf(Color(0xFF0B2E2B), Color(0xFF0B2E2B)))
                val edge = FlathubTeal.copy(alpha = 0.18f)
                TiltedWall(tiles.size, if (tiles.isEmpty() || busy) 75_000 else 50_000, capW = 68.dp, capH = 68.dp, gap = 14.dp, corner = 17.dp) { i ->
                    WallCapsule(ground, edge = edge, icon = tiles.getOrNull(i))
                }
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to colors.background.copy(alpha = 0.97f), 0.45f to colors.background.copy(alpha = 0.9f),
                        0.78f to colors.background.copy(alpha = 0.35f), 1f to colors.background.copy(alpha = 0.15f),
                    ),
                ),
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to colors.background.copy(alpha = 0.92f))))
            Box(
                Modifier.fillMaxSize().drawBehind {
                    drawRect(Brush.radialGradient(listOf(FlathubTeal.copy(alpha = 0.22f), Color.Transparent), center = Offset(size.width * 0.16f, size.height * 0.9f), radius = size.height * 0.75f))
                },
            )
            BoxWithConstraints(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = if (narrow) 20.dp else 40.dp, bottom = if (narrow) 20.dp else 36.dp, end = 20.dp)) {
                val full = maxWidth
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Rise(1) {
                        Text(stringResource(R.string.flathub_eyebrow), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = FlathubTeal)
                    }
                    Rise(2) {
                        Text(
                            stringResource(R.string.flathub_name), fontSize = if (narrow) 30.sp else 42.sp, lineHeight = if (narrow) 34.sp else 46.sp,
                            fontWeight = FontWeight.Black, color = colors.onBackground,
                        )
                    }
                    Rise(3) { Text(stringResource(R.string.flathub_line), fontSize = 15.sp, color = colors.onSurfaceVariant) }
                    Spacer(Modifier.height(8.dp))
                    Rise(4) { SetupButton(s, busy, full, ring) { StoreState.setup(ctx) } }
                }
            }
        }
    }
}

@Composable
private fun SetupButton(s: FrontEndState, busy: Boolean, full: Dp, ring: Int, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val ff = LocalFrontFocus.current
    val inputMode = LocalInputModeManager.current
    var restW by remember { mutableStateOf(0.dp) }
    val grow by animateFloatAsState(if (busy) 1f else 0f, Motion.sp(0.7f, 380f), label = "setupGrow")
    val fill by animateFloatAsState((StoreState.percent.coerceAtLeast(0) / 100f), Motion.tw(300), label = "setupFill")
    if (!busy && grow < 0.01f) {
        Box(Modifier.onSizeChanged { restW = with(density) { it.width.toDp() } }) {
            PrimaryButton(
                if (s.ready) stringResource(R.string.flathub_setup) else stringResource(R.string.store_needs_runtime),
                enabled = s.ready && StoreState.busy == null, main = true, large = true, icon = Icons.Filled.Download, onClick = onClick,
            )
        }
        return
    }
    if (ff != null) LaunchedEffect(Unit) { if (inputMode.inputMode == InputMode.Keyboard) focusSoon { ff.primary } }
    val width = androidx.compose.ui.unit.lerp(restW.coerceAtLeast(120.dp), full, grow)
    val shape = RoundedCornerShape(12.dp)
    val shimmer = rememberInfiniteTransition(label = "setupShimmer")
    val sweep by shimmer.animateFloat(-0.3f, 1.3f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    val stage = StoreState.stage ?: stringResource(R.string.store_starting)
    val label = if (StoreState.percent >= 0) "$stage · ${StoreState.percent}%" else stage
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier.width(width).heightIn(min = 56.dp)
            .then(if (ff != null) Modifier.focusRequester(ff.primary) else Modifier)
            .pulseRing(ring, FlathubTeal)
            .clip(shape).background(colors.surfaceVariant).border(1.dp, FlathubTeal.copy(alpha = 0.5f), shape)
            .drawBehind {
                if (StoreState.percent >= 0) drawRect(FlathubTeal, size = size.copy(width = size.width * fill))
                else if (Motion.scale != 0f) {
                    val w = size.width * 0.3f
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, FlathubTeal.copy(alpha = 0.55f), Color.Transparent), startX = size.width * sweep - w, endX = size.width * sweep + w))
                }
            }
            .focusable()
            .semantics { contentDescription = label }
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (StoreState.percent >= 50) FlathubInk else colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun Storefront(s: FrontEndState, a: FrontEndActions) {
    val ctx = LocalContext.current
    val narrow = LocalNarrowPane.current
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val padH = if (narrow) 14.dp else 22.dp
    var mode by rememberSaveable { mutableStateOf(MODE_DISCOVER) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var openApp by rememberSaveable { mutableStateOf<String?>(null) }
    val updates = StoreState.updates
    if (mode == MODE_UPDATES && updates.isEmpty()) mode = MODE_INSTALLED
    LaunchedEffect(StoreState.ready) { if (StoreState.ready && !StoreState.updatesChecked && !StoreState.checkingUpdates) StoreState.checkUpdates(ctx) }
    LaunchedEffect(query, category) {
        if (query.isBlank() && category == null) { StoreState.clearSearch(); return@LaunchedEffect }
        delay(350)
        StoreState.search(query.trim(), category)
    }
    val ff = LocalFrontFocus.current
    val inputMode = LocalInputModeManager.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val cards = remember { HashMap<String, Rect>() }
    var viewportAt by remember { mutableStateOf(Offset.Zero) }
    var detail by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var detailFrom by remember { mutableStateOf<Origin?>(null) }
    var lastApp by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(openApp) {
        val id = openApp
        if (id != null) {
            focusManager.clearFocus(force = true)
            view.findFocus()?.clearFocus()
            keyboard?.hide()
            detailFrom = cards[id]?.let { Origin(it.translate(-viewportAt), 16f * density.density) }
            leaving = false
            detail = id
            if (inputMode.inputMode == InputMode.Keyboard && ff != null) { delay(Motion.ms(300).toLong()); focusSoon { ff.primary } }
        } else if (detail != null) {
            lastApp = detail
            if (detailFrom != null) { leaving = true; delay(Motion.ms(PAGE_RETURN_MS).toLong()) }
            detail = null; leaving = false; detailFrom = null
        }
    }
    LaunchedEffect(detail) {
        if (detail != null || inputMode.inputMode != InputMode.Keyboard || ff == null) return@LaunchedEffect
        val id = lastApp ?: return@LaunchedEffect
        ff.items["tile:store:$id"]?.let { req -> focusSoon { req } }
    }
    BackHandler(enabled = openApp != null) { openApp = null }
    LaunchedEffect(StoreState.openRequest) { StoreState.openRequest?.let { openApp = it; StoreState.openRequest = null } }
    val covering = detail != null && !leaving
    val sink by animateFloatAsState(if (covering) 0.95f else 1f, if (covering) Motion.tw(480) else Motion.tw(460, 40), label = "flathubSink")
    val fade by animateFloatAsState(if (covering) 0f else 1f, if (covering) Motion.tw(320, 80) else Motion.tw(300, 100), label = "flathubFade")
    val modes = buildList { add(MODE_DISCOVER); add(MODE_INSTALLED); if (updates.isNotEmpty()) add(MODE_UPDATES) }
    val seenMode = remember { arrayOf(mode) }
    LaunchedEffect(mode) {
        if (seenMode[0] == mode) return@LaunchedEffect
        seenMode[0] = mode
        if (inputMode.inputMode != InputMode.Keyboard || ff == null) return@LaunchedEffect
        ff.items["flathub:$mode"]?.let { req -> focusSoon { req } }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = padH, vertical = if (narrow) 10.dp else 14.dp)
            .bumpers(
                onPrevious = { if (openApp == null) mode = modes[(modes.indexOf(mode) + modes.size - 1) % modes.size] },
                onNext = { if (openApp == null) mode = modes[(modes.indexOf(mode) + 1) % modes.size] },
            ),
    ) {
        Rise(0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val show = LocalShowSection.current
                BackLink(stringResource(R.string.rail_desktop), compact = true) { show("desktop") }
                Box(Modifier.weight(1f)) { FlathubChips(mode, modes, StoreState.installed.size, updates.size) { mode = it } }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onGloballyPositioned { viewportAt = it.positionInRoot() }) {
            Column(
                Modifier.fillMaxSize().focusProperties {
                    enter = { if (detail == null) androidx.compose.ui.focus.FocusRequester.Default else androidx.compose.ui.focus.FocusRequester.Cancel }
                }.focusGroup()
                    .graphicsLayer { scaleX = sink; scaleY = sink; alpha = fade }.verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            ) {
                val open: (String) -> Unit = { openApp = it }
                when (mode) {
                    MODE_DISCOVER -> Discover(query, category, { query = it }, { category = if (category == it) null else it }, cards, open)
                    MODE_INSTALLED -> InstalledMode(cards, open)
                    else -> UpdatesMode(cards, open)
                }
            }
            detail?.let { id ->
                val from = detailFrom
                val icon = StoreState.details[id]?.icon ?: StoreState.sections.values.flatten().firstOrNull { it.id == id }?.icon ?: FlathubApi.iconUrl(id)
                val body: @Composable () -> Unit = {
                    Column(
                        Modifier.fillMaxSize().background(colors.background).pointerInput(Unit) { detectTapGestures { } }
                            .verticalScroll(rememberScrollState()).padding(bottom = 16.dp).focusGroup(),
                    ) { AppDetail(s, a, id) { openApp = null } }
                }
                if (from == null) body()
                else {
                    val art = coil.compose.rememberAsyncImagePainter(
                        remember(icon) { coil.request.ImageRequest.Builder(ctx).data(icon).size(256, 256).build() },
                    )
                    PageFlood(from, leaving, art) { body() }
                }
            }
        }
    }
}

@Composable
private fun FlathubChips(mode: String, modes: List<String>, installed: Int, updates: Int, onPick: (String) -> Unit) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    val tint = rememberUpdatedState(pal.signal)
    Box(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            for (m in modes) {
                key(m) {
                    val src = remember { MutableInteractionSource() }
                    val hot = rememberHot(src)
                    val shape = RoundedCornerShape(99.dp)
                    val on = m == mode
                    val under = on && androidx.compose.ui.platform.LocalInputModeManager.current.inputMode != androidx.compose.ui.input.InputMode.Keyboard
                    Row(
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        modifier = Modifier.weight(1f).paneItem("flathub:$m")
                            .onGloballyPositioned {
                                bounds[m] = it.boundsInParent()
                                if (m == MODE_INSTALLED) FlathubMotion.installedChip = it.boundsInRoot()
                            }
                            .heightIn(min = 44.dp).clip(shape)
                            .background(if (under) Color.Transparent else colors.surface)
                            .shownBar(on, pal.signal)
                            .glideBorder(hot, shape, pal.signal, if (under) Color.Transparent else pal.line)
                            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Tab) { onPick(m) }
                            .controllerConfirm { onPick(m) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            stringResource(when (m) { MODE_DISCOVER -> R.string.store_tab_discover; MODE_INSTALLED -> R.string.store_tab_installed; else -> R.string.store_updates }),
                            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (on || hot) colors.onBackground else colors.onSurfaceVariant, maxLines = 1,
                        )
                        val count = when (m) { MODE_INSTALLED -> installed; MODE_UPDATES -> updates; else -> -1 }
                        if (count >= 0) Text(
                            count.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = pal.onSignal, maxLines = 1,
                            modifier = Modifier.then(if (m == MODE_INSTALLED) Modifier.landingPop(FlathubMotion.landed) else Modifier)
                                .clip(RoundedCornerShape(8.dp)).background(pal.signal).padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
        ChipGlide(modes.indexOf(mode), bounds[mode], tint)
    }
}

private fun cardSize(width: Dp): Dp = when {
    width >= 700.dp -> 164.dp
    width >= 480.dp -> 148.dp
    else -> 132.dp
}

@Composable
private fun Discover(
    query: String, category: String?, onQuery: (String) -> Unit, onCategory: (String) -> Unit,
    cards: HashMap<String, Rect>, onOpen: (String) -> Unit,
) {
    Rise(1) {
        ModeHeader(
            stringResource(R.string.store_tab_discover), query, onQuery, stringResource(R.string.store_search_placeholder),
        ) {
            val pills = rememberScrollState()
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.widthIn(max = 420.dp).edgeFade(pills).horizontalScroll(pills),
            ) {
                FlathubApi.categories.forEach { c -> PillButton(stringResource(c.label), selected = category == c.id) { onCategory(c.id) } }
            }
        }
    }
    val results = StoreState.searchResults
    if (query.isNotBlank() || category != null) {
        when {
            StoreState.searching && results == null -> Rise(2) { Box(Modifier.padding(top = 12.dp)) { Note(stringResource(R.string.store_searching)) } }
            results.isNullOrEmpty() -> Rise(2) { Box(Modifier.padding(top = 12.dp)) { Note(stringResource(R.string.store_no_results)) } }
            else -> Rise(2) { CardGrid(results.map { CardApp(it.id, it.name, it.icon, it.summary, it.verified) }, cards, onOpen) }
        }
        return
    }
    if (StoreState.sectionsFailed && StoreState.sections.values.all { it.isEmpty() }) {
        Rise(2) { Box(Modifier.padding(top = 12.dp)) { Note(stringResource(R.string.store_unreachable)) } }
        Actions { SecondaryButton(stringResource(R.string.store_try_again)) { StoreState.loadSections(force = true) } }
        return
    }
    StoreState.SECTIONS.forEachIndexed { i, (key, title) ->
        val apps = StoreState.sections[key]
        Rise(2 + i) { Shelf(stringResource(title), apps?.map { CardApp(it.id, it.name, it.icon, it.summary, it.verified) }, first = i == 0, cards, onOpen) }
    }
}

@Composable
private fun InstalledMode(cards: HashMap<String, Rect>, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val apps = StoreState.installed
    Rise(1) {
        ModeHeader(stringResource(R.string.store_tab_count, stringResource(R.string.store_tab_installed), apps.size), "", {}, "") {
            SmallLink(if (StoreState.checkingUpdates) stringResource(R.string.common_checking) else stringResource(R.string.store_check_updates), enabled = !StoreState.checkingUpdates && StoreState.busy == null) {
                StoreState.checkUpdates(ctx)
            }
        }
    }
    if (apps.isEmpty()) { Rise(2) { Box(Modifier.padding(top = 12.dp)) { Note(stringResource(R.string.store_no_apps)) } }; return }
    Rise(2) { CardGrid(apps.map { CardApp(it.id, it.name, it.icon ?: StoreState.details[it.id]?.icon ?: FlathubApi.iconUrl(it.id), it.summary) }, cards, onOpen) }
}

@Composable
private fun UpdatesMode(cards: HashMap<String, Rect>, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val apps = StoreState.installed.filter { it.id in StoreState.updates }
    Rise(1) {
        ModeHeader(stringResource(R.string.store_tab_count, stringResource(R.string.store_updates), apps.size), "", {}, "") {
            PrimaryButton(stringResource(R.string.store_update_all, apps.size), compact = true, enabled = StoreState.busy == null) {
                StoreState.update(ctx, null, ctx.getString(R.string.store_updates))
            }
        }
    }
    Rise(2) { CardGrid(apps.map { CardApp(it.id, it.name, it.icon ?: StoreState.details[it.id]?.icon ?: FlathubApi.iconUrl(it.id), it.summary) }, cards, onOpen) }
}

@Composable
private fun Shelf(title: String, apps: List<CardApp>?, first: Boolean, cards: HashMap<String, Rect>, onOpen: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        if (apps == null) Text(stringResource(R.string.store_section_loading), fontSize = 13.sp, color = colors.onSurfaceVariant)
        else if (apps.isNotEmpty()) BoxWithConstraints(Modifier.fillMaxWidth()) {
            val size = cardSize(maxWidth)
            val shelf = rememberScrollState()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().edgeFade(shelf).horizontalScroll(shelf).padding(vertical = 2.dp)) {
                apps.take(18).forEachIndexed { i, app -> key(app.id) { AppCard(app, size, first && i == 0, cards) { onOpen(app.id) } } }
            }
        }
    }
}

@Composable
private fun CardGrid(apps: List<CardApp>, cards: HashMap<String, Rect>, onOpen: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        val size = cardSize(maxWidth)
        val columns = ((maxWidth + 10.dp) / (size + 10.dp)).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            apps.chunked(columns).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEachIndexed { i, app -> key(app.id) { AppCard(app, size, r == 0 && i == 0, cards) { onOpen(app.id) } } }
                }
            }
        }
    }
}

@Composable
private fun AppCard(app: CardApp, size: Dp, first: Boolean, cards: HashMap<String, Rect>, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else if (hot) 1.03f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "cardScale")
    val shape = RoundedCornerShape(16.dp)
    val installed = StoreState.installed.any { it.id == app.id }
    val update = app.id in StoreState.updates
    val busy = StoreState.busy == app.id
    val artH = size * 0.62f
    Column(
        Modifier.width(size).paneItem("tile:store:${app.id}").then(if (first) Modifier.firstTile() else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape).background(if (hot) pal.signal.copy(alpha = 0.10f) else colors.surface)
            .glideBorder(hot, shape, pal.signal, pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .semantics { contentDescription = app.name },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().height(artH).onGloballyPositioned { cards[app.id] = it.boundsInRoot() }
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(Color(0xFF0B2E2B)),
        ) {
            if (app.icon != null) {
                AsyncImage(
                    model = app.icon, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.8f; scaleY = 1.8f; alpha = 0.55f }
                        .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(18.dp) else Modifier),
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                AsyncImage(model = app.icon, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(artH * 0.62f))
            } else Icon(Icons.Outlined.Apps, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(artH * 0.5f))
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                when {
                    busy -> Box(Modifier.clip(RoundedCornerShape(99.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(4.dp)) {
                        if (StoreState.percent >= 0) CircularProgressIndicator(progress = { StoreState.percent / 100f }, strokeWidth = 2.dp, color = pal.signal, trackColor = pal.signal.copy(alpha = 0.25f), modifier = Modifier.size(16.dp))
                        else CircularProgressIndicator(strokeWidth = 2.dp, color = pal.signal, modifier = Modifier.size(16.dp))
                    }
                    else -> Text(
                        when {
                            update -> stringResource(R.string.common_update)
                            installed -> "✓"
                            else -> stringResource(R.string.comp_get)
                        },
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        color = if (installed && !update) FlathubInk else pal.onSignal,
                        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(if (installed && !update) pal.good else pal.signal)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    app.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp, color = colors.onBackground,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (app.verified) Icon(Icons.Outlined.Verified, stringResource(R.string.store_verified), tint = pal.signal, modifier = Modifier.padding(top = 2.dp).size(13.dp))
            }
            Text(
                app.summary.orEmpty(), fontSize = 12.sp, lineHeight = 15.sp, color = colors.onSurfaceVariant,
                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun AppDetail(s: FrontEndState, a: FrontEndActions, id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val narrow = LocalNarrowPane.current
    LaunchedEffect(id) { StoreState.loadDetails(id) }
    val d = StoreState.details[id]
    val local = StoreState.installed.firstOrNull { it.id == id }
    val name = d?.name ?: local?.name ?: id
    val busyHere = StoreState.busy == id
    val installed = local != null
    Rise(0) { BackLink(stringResource(R.string.flathub_name), onClick = onBack) }
    Rise(1) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)) {
            AppIcon(d?.icon ?: local?.icon ?: FlathubApi.iconUrl(id), name, 72)
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontSize = if (narrow) 22.sp else 26.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(
                    d?.developer, d?.version?.let { "v$it" },
                    d?.downloadSize?.takeIf { it > 0 }?.let { stringResource(R.string.store_download_size, FileUtils.sizeToString(it)) },
                ).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, fontSize = 14.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                (d?.summary ?: local?.summary)?.let { Text(it, fontSize = 14.sp, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }
    Rise(2) {
        Actions {
            if (installed) {
                PrimaryButton(if (busyHere) stringResource(R.string.common_working) else stringResource(R.string.store_open), enabled = !busyHere, main = true) { a.onFlatpakApp(id, name) }
                if (id in StoreState.updates) SecondaryButton(stringResource(R.string.common_update), enabled = StoreState.busy == null) { StoreState.update(ctx, id, name) }
            } else {
                var at by remember { mutableStateOf<Rect?>(null) }
                Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
                    PrimaryButton(
                        if (busyHere) (if (StoreState.percent >= 0) "${StoreState.stage ?: stringResource(R.string.store_installing)} · ${StoreState.percent}%" else stringResource(R.string.store_installing))
                        else stringResource(R.string.store_install),
                        main = true,
                        enabled = StoreState.busy == null && s.ready && (d == null || d.arches.isEmpty() || "aarch64" in d.arches),
                    ) {
                        StoreState.install(ctx, id, name)
                        val from = at
                        if (from != null) Flights.fly(Flight(from, to = { FlathubMotion.installedChip?.center }, onLand = { FlathubMotion.landed++ }))
                        else FlathubMotion.landed++
                    }
                }
            }
            d?.homepage?.let { url ->
                SecondaryButton(stringResource(R.string.store_website)) {
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                }
            }
            if (installed) RemoveButton(id, name, enabled = StoreState.busy == null)
        }
    }
    if (d == null) {
        Rise(3) {
            Box(Modifier.padding(top = 14.dp)) {
                Note(if (StoreState.detailsFailed[id] == true) stringResource(R.string.store_details_failed) else stringResource(R.string.store_loading))
            }
        }
        return
    }
    if (d.arches.isNotEmpty() && "aarch64" !in d.arches) Rise(3) { Box(Modifier.padding(top = 12.dp)) { Note(stringResource(R.string.store_no_arm)) } }
    if (d.screenshots.isNotEmpty()) {
        Rise(3) { SectionTitle(stringResource(R.string.store_screenshots), null) }
        Rise(3) {
            val shots = rememberScrollState()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().edgeFade(shots).horizontalScroll(shots)) {
                d.screenshots.take(6).forEach { shot ->
                    AsyncImage(
                        model = shot.thumb, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.height(if (narrow) 150.dp else 190.dp).aspectRatio(16f / 9f).clip(Shape12).background(colors.surface),
                    )
                }
            }
        }
    }
    if (d.description.isNotEmpty()) {
        Rise(4) { SectionTitle(stringResource(R.string.store_about), null) }
        Rise(4) { Text(d.description, fontSize = 14.sp, lineHeight = 20.sp, color = colors.onBackground, modifier = Modifier.fillMaxWidth()) }
    }
    Rise(5) { SectionTitle(stringResource(R.string.store_details), null) }
    Rise(5) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            listOfNotNull(
                stringResource(R.string.store_app_id) to d.id,
                d.license?.let { stringResource(R.string.store_license) to it },
                d.runtime?.let { stringResource(R.string.store_runtime) to it.replace("/x86_64/", "/aarch64/") },
                if (d.installedSize > 0) stringResource(R.string.store_installed_size) to stringResource(R.string.store_installed_size_value, FileUtils.sizeToString(d.installedSize)) else null,
                d.homepage?.let { stringResource(R.string.store_website) to it },
            ).forEach { (k, v) ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(k, fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.width(110.dp))
                    Text(v, fontSize = 13.sp, color = colors.onBackground, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RemoveButton(id: String, name: String, enabled: Boolean) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.store_remove)
    var at by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
        SecondaryButton(label, enabled = enabled) {
            val from = at ?: return@SecondaryButton
            Steps.ask(StepAsk(
                anchor = from, side = StepSide.Right, accent = colors.error, pillCorner = 12.dp, items = 3,
                handle = { Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.error, maxLines = 1) },
            ) {
                StepTitle(ctx.getString(R.string.flathub_remove_title, name))
                Text(ctx.getString(R.string.flathub_remove_note), fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.stepItem(1).padding(start = 4.dp, bottom = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.stepItem(2)) {
                    StepChoice(ctx.getString(R.string.flathub_keep), enabled = open, modifier = Modifier.focusRequester(first)) { Steps.fold() }
                    StepChoice(label, danger = true, enabled = open) { Steps.fold(); StoreState.uninstall(ctx, id, name) }
                }
            })
        }
    }
}

@Composable
private fun SmallLink(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src) && enabled
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        color = if (!enabled) colors.onSurfaceVariant.copy(alpha = 0.5f) else if (hot) colors.onBackground else colors.onSurfaceVariant,
        modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).glideBorder(hot, RoundedCornerShape(8.dp), pal.signal)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
    )
}

@Composable
internal fun AppIcon(model: Any?, name: String, size: Int) {
    val colors = MaterialTheme.colorScheme
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size / 5).dp)).background(if (model == null) colors.surfaceVariant else Color.Transparent)) {
        if (model == null) Icon(Icons.Outlined.Apps, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size((size / 2).dp))
        else AsyncImage(model = model, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.size(size.dp))
    }
}

@Composable
private fun PillButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(99.dp)
    Text(
        label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        color = if (selected) pal.onSignal else if (hot) colors.onBackground else colors.onSurfaceVariant,
        modifier = Modifier.paneItem("pill:$label").heightIn(min = 44.dp).padding(vertical = 6.dp).clip(shape)
            .background(if (selected) pal.signal else if (hot) pal.signal.copy(alpha = 0.16f) else colors.surfaceVariant)
            .glideBorder(hot, shape, pal.signal, pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
