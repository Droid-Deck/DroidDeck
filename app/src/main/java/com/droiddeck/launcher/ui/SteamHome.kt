package com.droiddeck.launcher.ui

import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxWidth
import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.State
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.glasses.GlassesMenuMirror
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Scale
import coil.transform.RoundedCornersTransformation
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val WALL_MIN_GAMES = 4
private const val WALL_DISTINCT = 40

private const val DRIFT_STEP_NS = 15_000_000L

@Composable
internal fun SteamHome(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { com.droiddeck.launcher.frontend.SteamFeatured.load(ctx) }
    val featured = com.droiddeck.launcher.frontend.SteamFeatured.games
    val signedIn = s.offlineAccount != null
    val owned by produceState(emptyList<Pair<Int, java.io.File>>(), signedIn) {
        value = if (!signedIn) emptyList() else withContext(Dispatchers.IO) { runCatching { Library.steamLibraryArt(ctx).shuffled() }.getOrDefault(emptyList()) }
    }
    val games = remember(s.steamGames) { s.steamGames.sortedWith(compareByDescending<Library.SteamGame> { it.lastPlayed }.thenBy { it.gameId }) }
    val narrow = LocalNarrowPane.current
    var words by remember { mutableStateOf<Rect?>(null) }
    val mode = com.droiddeck.launcher.session.SessionService.MODE_STEAM
    val tabs = modeSheetTabs(steam = true).map { it to sheetTabLabel(it) }
    val back = stringResource(R.string.hop_from_play)
    val extras = SheetExtras(
        frameGen = { FrameGenGroup(s, a) },
        performance = { PerformanceLink(a, "steam", SheetTab.SESSION) },
        drivers = { summary -> DriversLink(a, summary, "steam", back) },
        account = { host ->
            SettingsGroup(stringResource(R.string.setup_account)) {
                ToggleRow(
                    host, "offline", stringResource(R.string.setup_offline),
                    s.offlineAccount?.let { if (it.isEmpty()) stringResource(R.string.setup_signed_in_unnamed) else stringResource(R.string.setup_signed_in, it) } ?: stringResource(R.string.setup_sign_in_first),
                    s.offline, enabled = s.offlineAccount != null,
                ) { a.onOffline() }
            }
        },
    )
    val (glasses, glassesSettings) = rememberGlasses()
    val headset = glasses.present && glasses.canControl
    val drop = rememberDropSheetState()
    LaunchedEffect(headset) { if (!headset && (drop.open || drop.shown)) drop.reset() }
    LiftHero(
        key = "steam", tabs = tabs,
        drop = drop.takeIf { headset },
        overlay = { lifted ->
            if (headset) DropSheet(
                drop, glassesTabs(glasses) + (GlassesTab.CONTROLLERS to stringResource(R.string.vr_controllers_tab)), covered = lifted,
                button = { enabled ->
                    PrimaryButton(stringResource(R.string.games_play_steamvr), enabled = !s.busy && glasses.usable, large = true, icon = Icons.Filled.PlayArrow, fill = true,
                        modifier = Modifier.fillMaxWidth().testTag("play-steamvr")) { if (enabled) { drop.open = false; a.onPlaySteamVr() } }
                },
            ) { tab ->
                if (tab == GlassesTab.CONTROLLERS) VrControllerGroups(rememberMenuHost())
                else GlassesGroups(rememberMenuHost(), glasses, glassesSettings,
                    onRecenter = GlassesMenuMirror::recenter, inSession = false, tab = tab)
            }
        },
        onOpen = a.onSteamSettings, onPrimary = a.onPlay,
        modifier = modifier.clipToBounds(),
        start = if (narrow) 20.dp else 44.dp, bottom = 44.dp,
        background = {
            when {
                games.isEmpty() -> EmptyLibrary(
                    avoid = words,
                    wall = if (owned.isEmpty() && featured.isEmpty()) null else { { CapsuleWall(emptyList(), owned = owned, featured = featured) } },
                ) { Box(Modifier.fillMaxSize().graphicsLayer { scaleX = 1.06f; scaleY = 1.06f }) { WallFade() } }
                games.size < WALL_MIN_GAMES -> CapsuleFan(games)
                else -> CapsuleWall(games, owned = owned, featured = featured)
            }
        },
        scrim = { if (games.size >= WALL_MIN_GAMES) WallFade() },
        words = {
            val runtimeStatus: @Composable () -> Unit = {
                if (s.busy || !s.ready || (s.available != null && s.available != s.installed)) RuntimeChip(s)
            }
            if (narrow) Column(
                verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.onPlaced { words = it.boundsInParent() },
            ) {
                Wordmark(30.dp)
                runtimeStatus()
            } else Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.onPlaced { words = it.boundsInParent() },
            ) {
                Wordmark(if (narrow) 30.dp else 40.dp)
                runtimeStatus()
            }
        },
        button = { click ->
            PrimaryButton(stringResource(R.string.games_play_steam), enabled = !s.busy, main = true, large = true, icon = Icons.Filled.PlayArrow, modifier = Modifier.testTag("play-steam"), onClick = click)
        },
        rows = { tab -> a.modeSheet(mode, tab, extras) },
    )
}

@Composable
internal fun WallFade() {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(
                0f to colors.background.copy(alpha = 0.97f),
                0.34f to colors.background.copy(alpha = 0.86f),
                0.68f to colors.background.copy(alpha = 0.25f),
                1f to colors.background.copy(alpha = 0.1f),
            ),
        ),
    )
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to colors.background.copy(alpha = 0.9f))))
}

@Composable
internal fun CapsuleWall(
    games: List<Library.SteamGame>,
    driftMs: Int = if (games.isEmpty()) 75_000 else 40_000,
    modifier: Modifier = Modifier,
    owned: List<Pair<Int, java.io.File>> = emptyList(),
    featured: List<com.droiddeck.launcher.frontend.SteamFeatured.Game> = emptyList(),
) {
    val pal = LocalPalette.current
    val blank = remember(pal) { WallCapsule(Brush.linearGradient(listOf(pal.surface, pal.background)), edge = pal.line) }
    val padding = remember(games, owned, featured) {
        val shown = games.map { it.appId }.toMutableSet()
        val library = owned.filter { shown.add(it.first) }.map { (id, art) -> id.toString() to art }
        val store = if (owned.isEmpty()) featured.filter { shown.add(it.appId) }.map { it.name to it.art } else emptyList()
        (library + store).take((WALL_DISTINCT - games.size).coerceAtLeast(0))
    }
    TiltedWall(games.size + padding.size, driftMs, modifier) { i ->
        when {
            i < games.size -> WallCapsule(artBrush(hueOf(games[i].name)), games[i].art)
            i - games.size < padding.size -> padding[i - games.size].let { (seed, art) -> WallCapsule(artBrush(hueOf(seed)), art) }
            else -> blank
        }
    }
}

internal class WallCapsule(val ground: Brush, val art: Any? = null, val edge: Color? = null, val icon: Any? = null, val bar: Color? = null)

@Composable
internal fun TiltedWall(
    count: Int,
    driftMs: Int,
    modifier: Modifier = Modifier,
    capW: androidx.compose.ui.unit.Dp = 112.dp,
    capH: androidx.compose.ui.unit.Dp = 168.dp,
    gap: androidx.compose.ui.unit.Dp = 14.dp,
    corner: androidx.compose.ui.unit.Dp = 10.dp,
    capsule: (index: Int) -> WallCapsule,
) {
    val still = Motion.scale == 0f
    val drift = remember { mutableFloatStateOf(0f) }
    val period = androidx.compose.runtime.rememberUpdatedState(driftMs)
    if (!still) LaunchedEffect(Unit) {
        var shown = 0L
        while (true) withFrameNanos { now ->
            if (shown == 0L) shown = now
            else if (now - shown >= DRIFT_STEP_NS) {
                drift.floatValue = ((drift.floatValue + (now - shown) / (period.value * 1_000_000.0 * Motion.scale)) % 1.0).toFloat()
                shown = now
            }
        }
    }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val tilt = Math.toRadians(13.0)
        val cos = kotlin.math.cos(tilt).toFloat()
        val sin = kotlin.math.sin(tilt).toFloat()
        val wallW = (maxWidth * cos + maxHeight * sin) * 1.1f
        val wallH = maxOf(maxHeight * 2.4f, (maxWidth * sin + maxHeight * cos) * 1.1f)
        val columns = ((wallW + gap) / (capW + gap)).toInt() + 1
        val perRun = ((wallH + gap) / (capH + gap)).toInt() + 1
        val run = (capH + gap) * perRun
        val cap = with(density) { Size(capW.toPx(), capH.toPx()) }
        val step = with(density) { (capH + gap).toPx() }
        val radius = with(density) { corner.toPx() }
        val line = with(density) { 1.dp.toPx() }
        val barH = with(density) { 12.dp.toPx() }
        val indexOf = { c: Int, i: Int -> if (count == 0) 0 else (c * 5 + (i % perRun) * 3) % count }
        val shown = remember(count, columns, perRun) { (0 until columns).flatMap { c -> (0 until perRun).map { indexOf(c, it) } }.distinct() }
        val looks = HashMap<Int, Pair<WallCapsule, Painter?>>(shown.size * 2)
        for (index in shown) key(index) {
            val look = capsule(index)
            looks[index] = look to (look.art?.let { rememberCapsuleArt(it, cap, radius) } ?: look.icon?.let { rememberIcon(it) })
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .offset(x = -(wallW - maxWidth) / 2, y = -(wallH - maxHeight) / 2)
                .requiredSize(wallW, wallH)
                .graphicsLayer { rotationZ = -13f },
        ) {
            repeat(columns) { c ->
                Spacer(
                    Modifier.width(capW).wrapContentHeight(Alignment.Top, unbounded = true).height(run * 2 - gap)
                        .graphicsLayer {
                            val shift = if (still) 0f else run.toPx() * drift.floatValue
                            translationY = if (c % 2 == 0) -shift else shift - run.toPx()
                        }
                        .drawBehind {
                            for (i in 0 until perRun * 2) {
                                val (look, art) = looks[indexOf(c, i)] ?: continue
                                val top = i * step
                                inset(0f, top, 0f, size.height - top - cap.height) { drawCapsule(look, art, radius, line, barH) }
                            }
                        },
                )
            }
        }
    }
}

@Composable
private fun rememberCapsuleArt(model: Any, size: Size, radius: Float): Painter {
    val context = LocalContext.current
    val w = size.width.roundToInt()
    val h = size.height.roundToInt()
    return rememberAsyncImagePainter(
        remember(model, w, h, radius) {
            ImageRequest.Builder(context).data(model).size(w, h).scale(Scale.FILL)
                .transformations(RoundedCornersTransformation(radius)).build()
        },
    )
}

@Composable
private fun rememberIcon(model: Any): Painter =
    if (model is Int) androidx.compose.ui.res.painterResource(model) else rememberAsyncImagePainter(model)

private fun DrawScope.drawCapsule(look: WallCapsule, art: Painter?, radius: Float, line: Float, barH: Float = 0f) {
    drawRoundRect(look.ground, size = size, cornerRadius = CornerRadius(radius))
    look.bar?.let { drawRect(it, Offset(0f, size.height - barH), Size(size.width, barH)) }
    if (art != null && look.icon != null) {
        val side = minOf(size.width, size.height - (if (look.bar != null) barH else 0f)) * 0.5f
        val cy = (size.height - (if (look.bar != null) barH else 0f)) / 2f
        translate((size.width - side) / 2f, cy - side / 2f) { with(art) { draw(Size(side, side)) } }
    } else if (art != null) with(art) { draw(size) }
    look.edge?.let {
        drawRoundRect(it, Offset(line / 2, line / 2), Size(size.width - line, size.height - line), CornerRadius(radius - line / 2), style = Stroke(line))
    }
}

@Composable
private fun CapsuleFan(games: List<Library.SteamGame>) {
    val pal = LocalPalette.current
    val density = LocalDensity.current
    val transition = rememberInfiniteTransition(label = "fan")
    val floats = listOf(9_000, 11_000, 13_000).map { ms ->
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "float")
    }
    val still = Motion.scale == 0f
    val slots = listOf(
        FanSlot(0.54f, 0.14f, 0.62f, -9f),
        FanSlot(0.70f, 0.24f, 0.56f, 5f),
        FanSlot(0.40f, 0.30f, 0.50f, -17f),
    )
    BoxWithConstraints(
        Modifier.fillMaxSize().drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(pal.signal.copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(size.width * 0.68f, size.height * 0.45f), radius = size.height * 0.6f,
                ),
            )
        },
    ) {
        val radius = with(density) { 14.dp.toPx() }
        for (i in games.indices.reversed()) {
            val slot = slots[i]
            val h = maxHeight * slot.height
            val v: State<Float> = floats[i]
            val look = WallCapsule(artBrush(hueOf(games[i].name)), games[i].art)
            val art = look.art?.let { rememberCapsuleArt(it, with(density) { Size((h * 2f / 3f).toPx(), h.toPx()) }, radius) }
            Spacer(
                Modifier
                    .offset(x = maxWidth * slot.x, y = maxHeight * slot.y)
                    .size(h * 2f / 3f, h)
                    .graphicsLayer {
                        val t = if (still) 0f else v.value
                        rotationZ = slot.lean + 2f * t
                        translationY = -12.dp.toPx() * t
                        shadowElevation = 24.dp.toPx()
                        shape = RoundedCornerShape(14.dp)
                        clip = false
                    }
                    .drawBehind { drawCapsule(look, art, radius, 0f) },
            )
        }
    }
}

private class FanSlot(val x: Float, val y: Float, val height: Float, val lean: Float)
