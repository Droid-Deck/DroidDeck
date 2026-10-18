package com.droiddeck.launcher.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Job
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.droiddeck.launcher.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt


internal object SheetTab {
    const val DISPLAY = "display"
    const val CONTROLS = "controls"
    const val AUDIO = "audio"
    const val SESSION = "session"
    const val LIBRARY = "library"
    const val STEAM = "steam"
    const val FILES = "files"
}

internal fun sheetTop(paneH: Float, buttonH: Float, minAbove: Float, ride: Float): Float =
    maxOf(paneH * 60f / 360f, minAbove + buttonH / 2f - ride)

private class SheetDrag {
    var from = 0f
    var progress = 0f
    var engaged = false
}

internal class SheetBeat(private val pieces: List<Animatable<Float, AnimationVector1D>>) {
    private var next = 1
    fun take(): Int = next++
    fun value(i: Int): Float = pieces[i.coerceIn(0, pieces.lastIndex)].value
}

internal val LocalSheetBeat = staticCompositionLocalOf<SheetBeat?> { null }

internal val LocalSheetChipWidth = compositionLocalOf { Dp.Unspecified }

internal val LocalPlayRect = compositionLocalOf<Rect?> { null }

private val ChipGap = 6.dp

internal fun sheetChipWidth(row: Dp, count: Int): Dp =
    if (count > 0 && row / count >= 96.dp) (row - ChipGap * (count - 1)) / count else 0.dp

@Composable
internal fun Modifier.sheetPiece(): Modifier {
    val beat = LocalSheetBeat.current ?: return this
    val i = remember { beat.take() }
    return graphicsLayer {
        val v = beat.value(i)
        alpha = v
        translationY = (1f - v) * 6.dp.toPx()
    }
}

private val SheetInset = 12.dp
private val SheetCorner = 18.dp
private val ChipsTop = 14.dp
private val RowsTop = 54.dp
private const val PIECES = 12

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
internal fun LiftHero(
    key: String,
    tabs: List<Pair<String, String>>,
    onOpen: () -> Unit,
    onPrimary: () -> Unit,
    background: @Composable () -> Unit,
    scrim: @Composable (lifted: () -> Float) -> Unit = {},
    words: @Composable () -> Unit,
    beside: @Composable () -> Unit = {},
    button: @Composable (onClick: () -> Unit) -> Unit,
    rows: @Composable (tab: String) -> Unit,
    modifier: Modifier = Modifier,
    downOpens: Boolean = true,
    onOpenChange: (Boolean) -> Unit = {},
    start: Dp = 44.dp,
    bottom: Dp = 44.dp,
    drop: DropSheetState? = null,
    overlay: @Composable BoxWithConstraintsScope.(lifted: () -> Float) -> Unit = {},
) {
    val density = LocalDensity.current
    val inputMode = LocalInputModeManager.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val ff = LocalFrontFocus.current
    val narrow = LocalNarrowPane.current
    val openState = remember { mutableStateOf(false) }
    var open by openState
    var shown by remember { mutableStateOf(false) }
    val launchingState = remember { mutableStateOf(false) }
    var launching by launchingState
    var tab by remember { mutableStateOf(0) }
    var travel by remember { mutableStateOf(1) }
    var focusOnLift by remember { mutableStateOf<String?>(null) }
    var lipBack by remember { mutableStateOf(false) }
    var dragSettled by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var dragJob by remember { mutableStateOf<Job?>(null) }
    val onOpenNow = rememberUpdatedState(onOpen)
    val pull = remember { Animatable(0f) }
    val sink = remember { Animatable(0f) }
    val lift = remember { Animatable(0f) }
    val ride = remember { Animatable(0f) }
    val hot = remember { Animatable(1f) }
    val lipShow = remember { Animatable(0f) }
    val lipGone = remember { Animatable(0f) }
    val fold = remember { Animatable(0f) }
    val foldTop = remember { Animatable(0f) }
    val pieces = remember { List(PIECES) { Animatable(0f) } }
    val chipFocus = remember(tabs.size) { List(tabs.size) { FocusRequester() } }
    val playSrc = remember { MutableInteractionSource() }
    val playHovered by playSrc.collectIsHoveredAsState()
    var playFocused by remember { mutableStateOf(false) }
    val leftForPlay = remember { booleanArrayOf(false) }
    var heroAt by remember { mutableStateOf(Offset.Zero) }
    var rest by remember { mutableStateOf<Rect?>(null) }
    fun focusPlay() { if (ff != null && inputMode.inputMode == InputMode.Keyboard) runCatching { ff.primary.requestFocus() } }
    fun close() {
        if (!open) return
        open = false
        focusPlay()
    }
    LaunchedEffect(Unit) {
        val r = Hops.restore ?: return@LaunchedEffect
        if (r.surface != HOP_SURFACE_SHEET || r.rail != key) return@LaunchedEffect
        Hops.restore = null
        tab = tabs.indexOfFirst { it.first == r.tab }.coerceAtLeast(0)
        focusOnLift = r.control
        open = true
    }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP && (open || shown)) {
                open = false; shown = false; launching = false
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(open, launching) {
        if (!dragSettled) { dragJob?.cancel(); dragJob = null }
        if (dragSettled) {
            dragSettled = false
            if (open) {
                if (inputMode.inputMode == InputMode.Keyboard) { withFrameNanos { }; runCatching { chipFocus[tab].requestFocus() } }
            } else focusPlay()
            return@LaunchedEffect
        }
        if (open && !launching) {
            lipBack = false
            shown = true
            onOpen()
            fold.snapTo(0f); foldTop.snapTo(0f)
            coroutineScope {
                launch { pull.animateTo(24f, Motion.sp(0.7f, 380f)) }
                launch { sink.animateTo(1f, Motion.tw(260)) }
                launch {
                    delay(Motion.ms(120).toLong())
                    launch { lipGone.animateTo(1f, Motion.tw(140)) }
                    launch { pull.animateTo(0f, Motion.sp(1f, 500f)) }
                    launch { lift.animateTo(1f, Motion.sp(0.7f, 380f)) }
                    launch { ride.animateTo(1f, Motion.sp(0.7f, 380f)) }
                    launch { hot.snapTo(1f); delay(Motion.ms(100).toLong()); hot.animateTo(0f, Motion.tw(200)) }
                }
                pieces.forEachIndexed { i, p -> launch { delay(Motion.ms(120 + 260 + i * 55).toLong()); p.animateTo(1f, Motion.tw(220)) } }
                if (inputMode.inputMode == InputMode.Keyboard) launch {
                    val control = focusOnLift
                    focusOnLift = null
                    delay(Motion.ms(if (control != null) 420 else 300).toLong())
                    withFrameNanos { }
                    val target = control?.let { ff?.items?.get(it) } ?: chipFocus[tab]
                    runCatching { target.requestFocus() }
                }
            }
        } else if (launching) {
            coroutineScope {
                pieces.forEach { launch { it.animateTo(0f, Motion.tw(90)) } }
                launch { delay(Motion.ms(60).toLong()); fold.animateTo(1f, Motion.sp(1f, 420f)) }
                launch { delay(Motion.ms(160).toLong()); foldTop.animateTo(1f, Motion.sp(1f, 380f)) }
            }
            shown = false
            onPrimary()
            delay(3_000)
            launching = false
        } else if (shown) {
            coroutineScope {
                pieces.forEach { launch { it.animateTo(0f, Motion.tw(90)) } }
                launch { delay(Motion.ms(60).toLong()); lift.animateTo(0f, Motion.sp(1f, 380f)) }
                launch { delay(Motion.ms(100).toLong()); ride.animateTo(0f, Motion.sp(0.55f, 300f)) }
                launch { sink.animateTo(0f, Motion.tw(300)) }
                launch { delay(Motion.ms(420).toLong()); lipGone.snapTo(0f); lipBack = true }
            }
            shown = false
            lipBack = false
            lipGone.snapTo(0f)
            hot.snapTo(1f)
        } else {
            listOf(pull, sink, lift, ride, lipGone, fold, foldTop).forEach { it.snapTo(0f) }
            pieces.forEach { it.snapTo(0f) }
            hot.snapTo(1f)
        }
    }
    BackHandler(enabled = open && !launching) { close() }
    if (ride.isRunning || (shown && !open)) VeilRing()
    val openNow = open || shown
    LaunchedEffect(openNow) { onOpenChange(openNow) }

    val touchOnly = inputMode.inputMode == InputMode.Touch
    val lipFocus = remember { FocusRequester() }
    val settingsLabel = stringResource(R.string.drawer_page_settings)
    var lipFocused by remember { mutableStateOf(false) }
    val lipWanted = !open && (!shown || lipBack) && (playFocused || playHovered || touchOnly || lipFocused) && drop?.let { it.open || it.shown } != true
    LaunchedEffect(lipWanted) {
        if (lipWanted) {
            if (!lipBack) delay(Motion.ms(160).toLong())
            lipShow.animateTo(1f, Motion.tw(220))
        } else {
            delay(Motion.ms(140).toLong())
            lipShow.animateTo(0f, Motion.tw(220))
        }
    }

    val travelPx = remember { floatArrayOf(1f) }
    val drag = remember { SheetDrag() }
    fun follow(p: Float) {
        drag.progress = p
        scope.launch {
            lift.snapTo(p); ride.snapTo(p); sink.snapTo(p.coerceIn(0f, 1f)); hot.snapTo(0f)
            lipGone.snapTo(1f)
            pieces.forEach { it.snapTo(p.coerceIn(0f, 1f)) }
        }
    }
    fun beginDrag() {
        dragJob?.cancel(); dragJob = null
        dragSettled = false
        drag.engaged = true
        drag.from = if (open) 1f else 0f
        drag.progress = drag.from
        if (!open) {
            shown = true
            scope.launch { fold.snapTo(0f); foldTop.snapTo(0f) }
            onOpenNow.value()
        }
    }
    fun settleDrag(v: Float) {
        drag.engaged = false
        val flick = with(density) { 600.dp.toPx() }
        val opens = when {
            v < -flick -> true
            v > flick -> false
            drag.from > 0.5f -> drag.progress > 0.7f
            else -> drag.progress > 0.3f
        }
        dragSettled = true
        dragJob?.cancel()
        dragJob = scope.launch {
            val to = if (opens) 1f else 0f
            coroutineScope {
                launch { lift.animateTo(to, Motion.sp(if (opens) 0.7f else 1f, 380f)) }
                launch { ride.animateTo(to, Motion.sp(if (opens) 0.7f else 0.55f, if (opens) 380f else 300f)) }
                launch { sink.animateTo(to, Motion.tw(220)) }
                pieces.forEachIndexed { i, piece -> launch { if (opens) delay(Motion.ms(i * 35).toLong()); piece.animateTo(to, Motion.tw(if (opens) 200 else 90)) } }
            }
            if (!opens) { shown = false; lipGone.snapTo(0f); hot.snapTo(1f); lipBack = false }
        }
        if (open != opens) open = opens else dragSettled = false
    }
    val rowsPull = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                if (!drag.engaged || available.y >= 0f) return Offset.Zero
                val before = drag.progress
                follow((before - available.y / travelPx[0]).coerceAtMost(1f))
                return Offset(0f, (drag.progress - before) * -travelPx[0])
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                if (available.y <= 0f || source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.Drag || !openState.value || launchingState.value) return Offset.Zero
                if (!drag.engaged) beginDrag()
                follow((drag.progress - available.y / travelPx[0]).coerceIn(0f, 1f))
                return available
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (!drag.engaged) return androidx.compose.ui.unit.Velocity.Zero
                settleDrag(available.y)
                return available
            }
        }
    }
    BoxWithConstraints(
        modifier.fillMaxSize().onGloballyPositioned { heroAt = it.boundsInRoot().topLeft }
            .pointerInput(drop) {
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                var decided = false
                val dropFlick = 600.dp.toPx()
                detectVerticalDragGestures(
                    onDragStart = { tracker.resetTracking(); decided = false; drag.engaged = false },
                    onDragEnd = {
                        if (drag.engaged) settleDrag(tracker.calculateVelocity().y)
                        else if (drop?.engaged == true) drop.settle(tracker.calculateVelocity().y, dropFlick)
                    },
                    onDragCancel = {
                        if (drag.engaged) settleDrag(0f)
                        else if (drop?.engaged == true) drop.settle(0f, dropFlick)
                    },
                ) { change, amount ->
                    if (launching) return@detectVerticalDragGestures
                    if (!decided) {
                        decided = true
                        when {
                            drop != null && (drop.open || drop.shown) -> if (drop.open && amount < 0f) drop.beginDrag()
                            open || shown || amount < 0f -> if (if (open) amount > 0f else amount < 0f) beginDrag()
                            drop != null && amount > 0f -> drop.beginDrag()
                        }
                    }
                    if (drop?.engaged == true) {
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        drop.drag(amount)
                        return@detectVerticalDragGestures
                    }
                    if (!drag.engaged) return@detectVerticalDragGestures
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    follow((drag.progress - amount / travelPx[0]).coerceIn(0f, 1.04f))
                }
            },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val inset = with(density) { SheetInset.toPx() }
        val restRect = rest?.translate(-heroAt)
        val buttonH = restRect?.height ?: with(density) { 56.dp.toPx() }
        val openTop = sheetTop(h, buttonH, with(density) { 16.dp.toPx() }, with(density) { 3.dp.toPx() })
        val raise = restRect?.let { (openTop + with(density) { 3.dp.toPx() } - buttonH / 2f) - it.top } ?: 0f
        travelPx[0] = (h - openTop).coerceAtLeast(1f)
        val pill = restRect?.translate(0f, raise)
        val chipsX = with(density) {
            if (narrow || pill == null) 16.dp.toPx() else pill.right + 20.dp.toPx() - inset
        }
        val chipWidth = with(density) { sheetChipWidth((w - 2 * inset - chipsX - 64.dp.toPx()).coerceAtLeast(0f).toDp(), tabs.size) }

        val foot = h
        val wallSink = { maxOf(sink.value, drop?.sink?.value ?: 0f) }
        Box(Modifier.fillMaxSize().clipToBounds().graphicsLayer { val k = 1f - 0.05f * wallSink(); scaleX = k; scaleY = k }) { background() }
        scrim { sink.value }
        if (shown || open) Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = sink.value }.background(Color.Black.copy(alpha = 0.42f))
                .focusProperties { canFocus = false }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = open && !launching) { close() },
        )

        if (shown) {
            val top = lerp(foot, openTop, lift.value)
            val sheetLeft = inset
            val sheetRight = w - inset
            val path = remember { Path() }
            val rimPath = remember { Path() }
            val corner = with(density) { SheetCorner.toPx() }
            val rim = with(density) { 1.4.dp.toPx() }
            Canvas(Modifier.fillMaxSize()) {
                val f = fold.value
                val ft = foldTop.value
                val l = if (pill != null) lerp(sheetLeft, pill.left, f) else sheetLeft
                val r = if (pill != null) lerp(sheetRight, pill.right, f) else sheetRight
                val t = if (pill != null) lerp(top, pill.top, ft) else top
                val b = if (pill != null) lerp(foot + corner, pill.bottom, ft) else foot + corner
                val c = if (pill != null) lerp(corner, with(density) { 12.dp.toPx() }, ft) else corner
                val bc = if (ft > 0f) c else 0f
                path.reset()
                path.addRoundRect(RoundRect(l, t, r, b, CornerRadius(c), CornerRadius(c), CornerRadius(bc), CornerRadius(bc)))
                val fill = lerp(colors.surface, pal.signal, 0.22f * hot.value)
                drawPath(path, fill)
                val rimColor = pal.signal.copy(alpha = lerp(0.55f, 0.9f, hot.value))
                if (ft > 0f) drawPath(path, rimColor, style = Stroke(rim))
                else {
                    rimPath.reset()
                    rimPath.moveTo(l, b)
                    rimPath.lineTo(l, t + c)
                    rimPath.arcTo(Rect(l, t, l + 2 * c, t + 2 * c), 180f, 90f, false)
                    rimPath.lineTo(r - c, t)
                    rimPath.arcTo(Rect(r - 2 * c, t, r, t + 2 * c), -90f, 90f, false)
                    rimPath.lineTo(r, b)
                    drawPath(rimPath, rimColor, style = Stroke(rim))
                }
            }
            val chipsY = with(density) { if (narrow && pill != null) (pill.bottom - openTop) + 10.dp.toPx() else ChipsTop.toPx() }
            val rowsY = with(density) { maxOf(RowsTop.toPx(), chipsY + 52.dp.toPx()) }
            val sheetW = sheetRight - sheetLeft
            Box(
                Modifier
                    .offset { IntOffset(sheetLeft.roundToInt(), top.roundToInt()) }
                    .size(with(density) { sheetW.toDp() }, with(density) { (foot - openTop).coerceAtLeast(0f).toDp() })
                    .pointerInput(Unit) { detectTapGestures { } }
                    .controllerBack { close() }
                    .bumpers(
                        onPrevious = { if (tab > 0) { travel = -1; tab--; runCatching { chipFocus[tab].requestFocus() } } },
                        onNext = { if (tab < tabs.lastIndex) { travel = 1; tab++; runCatching { chipFocus[tab].requestFocus() } } },
                    )
                    .focusProperties { exit = { dir -> if (!openState.value || launchingState.value) FocusRequester.Default else if (dir == FocusDirection.Left && ff != null) { leftForPlay[0] = true; ff.primary } else FocusRequester.Cancel } }
                    .focusGroup(),
            ) {
                Box(
                    Modifier
                        .offset { IntOffset(chipsX.roundToInt(), chipsY.roundToInt()) }
                        .zIndex(1f)
                        .width(with(density) { (sheetW - chipsX - 64.dp.toPx()).coerceAtLeast(0f).toDp() })
                        .graphicsLayer { val v = pieces[0].value; alpha = v; translationY = (1f - v) * 6.dp.toPx() }
                        .onPreviewKeyEvent { e ->
                            val k = e.nativeKeyEvent
                            if (k.keyCode == KeyEvent.KEYCODE_DPAD_UP) { if (k.action == KeyEvent.ACTION_DOWN) close(); true } else false
                        },
                ) {
                    SheetChips(tabs.map { it.second }, tab, chipFocus) { i -> travel = if (i > tab) 1 else -1; tab = i }
                }
                Box(
                    Modifier
                        .offset { IntOffset(0, rowsY.roundToInt()) }
                        .nestedScroll(rowsPull)
                        .fillMaxWidth()
                        .height(with(density) { (foot - openTop - rowsY).coerceAtLeast(0f).toDp() }),
                ) {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            val d = travel
                            val shift = with(density) { 24.dp.roundToPx() }
                            (slideInHorizontally(Motion.tw(240)) { shift * d } + fadeIn(Motion.tw(240)))
                                .togetherWith(slideOutHorizontally(Motion.tw(240)) { -shift * d } + fadeOut(Motion.tw(240)))
                        },
                        label = "sheetTab",
                    ) { t ->
                        val leaving = transition.targetState == androidx.compose.animation.EnterExitState.PostExit
                        val leavingNow = rememberUpdatedState(leaving)
                        CompositionLocalProvider(LocalSheetBeat provides remember { SheetBeat(pieces) }) {
                            Column(
                                Modifier.fillMaxSize()
                                    .focusProperties { enter = { if (leavingNow.value) FocusRequester.Cancel else FocusRequester.Default } }
                                    .focusGroup()
                                    .verticalScroll(rememberScrollState())
                                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                            ) { rows(tabs[t].first) }
                        }
                    }
                }
                val closeSource = remember { MutableInteractionSource() }
                val closeHot = rememberHot(closeSource)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .offset { IntOffset((sheetW - 52.dp.toPx()).roundToInt(), chipsY.roundToInt()) }
                        .zIndex(1f).size(44.dp).testTag("sheet-close")
                        .clip(CircleShape).glideBorder(closeHot, CircleShape, pal.signal)
                        .hoverable(closeSource)
                        .clickable(interactionSource = closeSource, indication = LocalIndication.current, enabled = open && !launching, role = Role.Button) { close() }
                        .controllerConfirm(enabled = open && !launching) { close() },
                ) { Icon(Icons.Filled.Close, stringResource(R.string.common_close), tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
            }
        }

        Column(Modifier.align(Alignment.BottomStart).padding(start = start, bottom = bottom, end = 20.dp)) {
            Box(Modifier.graphicsLayer { alpha = 1f - wallSink() }) { words() }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .onGloballyPositioned { rest = it.boundsInRoot() }
                    .graphicsLayer { translationY = raise * ride.value; alpha = 1f - (drop?.sink?.value ?: 0f) }
                    .onFocusChanged {
                        if (it.hasFocus && leftForPlay[0]) { leftForPlay[0] = false; if (openState.value && !launchingState.value) close() }
                        playFocused = it.hasFocus
                    }
                    .hoverable(playSrc)
                    .onPreviewKeyEvent { e ->
                        val k = e.nativeKeyEvent
                        if (k.keyCode != KeyEvent.KEYCODE_DPAD_DOWN) false
                        else {
                            if (k.action == KeyEvent.ACTION_DOWN && k.repeatCount == 0) {
                                if (open) runCatching { chipFocus[tab].requestFocus() }
                                else if (downOpens) open = true
                                else runCatching { lipFocus.requestFocus() }
                            }
                            true
                        }
                    },
            ) {
                button {
                    if (launching) return@button
                    if (open || shown) { launching = true; open = false } else onPrimary()
                }
            }
            val besideShown = !(open || shown)
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.graphicsLayer { alpha = 1f - wallSink() }
                    .focusProperties { canFocus = besideShown },
            ) { if (besideShown || sink.value < 0.99f) beside() }
            }
        }

        val r0 = restRect
        if (r0 != null && (lipShow.value > 0.01f || pull.value > 0.5f || (!downOpens && !open && !shown))) {
            var handleW by remember { mutableStateOf(0f) }
            val lipH = 32.dp + pull.value.dp
            val lift = { if (!open) open = true }
            Box(
                Modifier
                    .offset { IntOffset((r0.center.x - handleW / 2f).roundToInt(), (r0.bottom + raise * ride.value + (lipShow.value - 1f) * 8.dp.toPx()).roundToInt()) }
                    .onSizeChanged { handleW = it.width.toFloat() }
                    .graphicsLayer { alpha = lipShow.value * (1f - lipGone.value) * (1f - (drop?.sink?.value ?: 0f)) }
                    .heightIn(min = 44.dp).testTag("sheet-open")
                    .semantics { contentDescription = settingsLabel }
                    .then(
                        if (downOpens) Modifier.focusProperties { canFocus = false }
                        else Modifier.focusRequester(lipFocus).onFocusChanged { lipFocused = it.isFocused }.controllerConfirm(onClick = lift),
                    )
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = lift),
            ) {
                Box(Modifier.glideBorder(lipFocused, RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp), LocalPalette.current.signal)) {
                    Handle(lipH)
                }
            }
        }
        CompositionLocalProvider(LocalSheetChipWidth provides chipWidth, LocalPlayRect provides restRect) { overlay { sink.value } }
    }
}

@Composable
internal fun Handle(height: Dp, above: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val shape = if (above) RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp) else RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
    val still = Motion.scale == 0f
    val bob = androidx.compose.animation.core.rememberInfiniteTransition(label = "handle")
    val lift by bob.animateFloat(
        0f, 0f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.keyframes {
                durationMillis = 2400
                0f at 0
                1f at 180 using androidx.compose.animation.core.FastOutSlowInEasing
                -0.25f at 420
                0f at 620
            },
        ),
        label = "handleBob",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.height(height).clip(shape).background(colors.surface)
            .drawBorderSidesAndFoot(pal.line, 12.dp, flip = above)
            .padding(start = 14.dp, end = 10.dp),
    ) {
        Text(stringResource(R.string.drawer_page_settings), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant, maxLines = 1)
        Icon(
            if (above) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp, null, tint = colors.onSurfaceVariant,
            modifier = Modifier.size(16.dp).graphicsLayer { translationY = if (still) 0f else (if (above) 3.dp else -3.dp).toPx() * lift },
        )
    }
}

private fun Modifier.drawBorderSidesAndFoot(color: Color, corner: Dp, flip: Boolean = false): Modifier =
    this.drawWithContent {
        drawContent()
        val c = corner.toPx()
        val sw = 1.dp.toPx()
        val p = Path().apply {
            moveTo(sw / 2, 0f)
            lineTo(sw / 2, size.height - c)
            arcTo(Rect(sw / 2, size.height - 2 * c + sw / 2, 2 * c, size.height - sw / 2), 180f, -90f, false)
            lineTo(size.width - c, size.height - sw / 2)
            arcTo(Rect(size.width - 2 * c, size.height - 2 * c + sw / 2, size.width - sw / 2, size.height - sw / 2), 90f, -90f, false)
            lineTo(size.width - sw / 2, 0f)
        }
        if (flip) scale(1f, -1f) { drawPath(p, color, style = Stroke(sw)) } else drawPath(p, color, style = Stroke(sw))
    }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SheetChips(
    labels: List<String>,
    selected: Int,
    focus: List<FocusRequester>? = null,
    width: Dp = Dp.Unspecified,
    onPick: (Int) -> Unit,
) {
    val pal = LocalPalette.current
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    val tint = rememberUpdatedState(pal.signal)
    val chips = focus ?: remember(labels.size) { List(labels.size) { FocusRequester() } }
    var within by remember { mutableStateOf(false) }
    LaunchedEffect(selected) { if (within) runCatching { chips[selected].requestFocus() } }
    BoxWithConstraints(
        Modifier.fillMaxWidth().onFocusChanged { within = it.hasFocus },
        contentAlignment = Alignment.CenterStart,
    ) {
        val equal = !width.isSpecified && maxWidth / labels.size.coerceAtLeast(1) >= 96.dp
        val least = if (width.isSpecified) maxOf(width, 64.dp) else 64.dp
        val scroll = rememberScrollState()
        Box(if (equal) Modifier.fillMaxWidth() else Modifier.edgeFade(scroll).horizontalScroll(scroll).padding(3.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ChipGap),
                modifier = (if (equal) Modifier.fillMaxWidth() else Modifier)
                    .focusProperties { enter = { chips.getOrNull(selected) ?: FocusRequester.Default } }
                    .focusGroup(),
            ) {
                labels.forEachIndexed { i, label ->
                    val m = (if (equal) Modifier.weight(1f) else Modifier.widthIn(min = least))
                        .onGloballyPositioned { bounds[i] = it.boundsInParent() }
                        .focusRequester(chips[i])
                    SheetChip(label, i == selected, m.onFocusChanged { if (it.isFocused && i != selected) onPick(i) }) { onPick(i) }
                }
            }
            ChipGlide(selected, bounds[selected], tint)
        }
    }
}

@Composable
private fun SheetChip(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(99.dp)
    val under = on && LocalInputModeManager.current.inputMode != InputMode.Keyboard
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.heightIn(min = 44.dp).clip(shape)
            .background(if (under) Color.Transparent else colors.surfaceVariant.copy(alpha = 0.5f))
            .shownBar(on, pal.signal)
            .glideBorder(hot, shape, pal.signal, if (under) Color.Transparent else pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Tab, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (on || hot) colors.onBackground else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun LinkRow(title: String, value: String, link: String, id: String, arrow: String = "↗", onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().paneItem(id).hopSource(id).heightIn(min = 56.dp).clip(shape)
            .background(if (hot) pal.signal.copy(alpha = 0.10f) else Color.Transparent)
            .glideBorder(hot, shape, pal.signal)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
            Text(value, fontSize = 12.5.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("$link $arrow", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.signal, maxLines = 1)
    }
}
