package com.droiddeck.launcher.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import com.droiddeck.launcher.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val DROP_PIECES = 12

internal class DropSheetState(private val scope: CoroutineScope) {
    var open by mutableStateOf(false)
    var shown by mutableStateOf(false)
    var lipBack by mutableStateOf(false)
    var tab by mutableStateOf(0)
    var travel by mutableStateOf(1)
    var settledByDrag = false
    var engaged = false
        private set
    val pull = Animatable(0f)
    val lift = Animatable(0f)
    val ride = Animatable(0f)
    val sink = Animatable(0f)
    val hot = Animatable(1f)
    val lipShow = Animatable(0f)
    val lipGone = Animatable(0f)
    val pieces = List(DROP_PIECES) { Animatable(0f) }
    var travelPx = 1f
    private var from = 0f
    var progress = 0f
        private set
    private var dragJob: Job? = null

    fun cancelDrag() {
        dragJob?.cancel()
        dragJob = null
    }

    fun reset() {
        cancelDrag()
        engaged = false
        settledByDrag = false
        open = false
        shown = false
        lipBack = false
        scope.launch {
            listOf(pull, lift, ride, sink, lipGone).forEach { it.snapTo(0f) }
            pieces.forEach { it.snapTo(0f) }
            hot.snapTo(1f)
        }
    }

    fun beginDrag() {
        cancelDrag()
        settledByDrag = false
        engaged = true
        from = if (open) 1f else 0f
        progress = from
        if (!open) shown = true
    }

    fun drag(dy: Float) = follow((progress + dy / travelPx).coerceIn(0f, 1.04f))

    fun follow(p: Float) {
        progress = p
        scope.launch {
            lift.snapTo(p); ride.snapTo(p); sink.snapTo(p.coerceIn(0f, 1f)); hot.snapTo(0f)
            lipGone.snapTo(1f)
            pieces.forEach { it.snapTo(p.coerceIn(0f, 1f)) }
        }
    }

    fun settle(v: Float, flick: Float) {
        engaged = false
        val opens = when {
            v > flick -> true
            v < -flick -> false
            from > 0.5f -> progress > 0.7f
            else -> progress > 0.3f
        }
        settledByDrag = true
        cancelDrag()
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
        if (open != opens) open = opens else settledByDrag = false
    }
}

@Composable
internal fun rememberDropSheetState(): DropSheetState {
    val scope = rememberCoroutineScope()
    return remember { DropSheetState(scope) }
}

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
internal fun BoxWithConstraintsScope.DropSheet(
    state: DropSheetState,
    tabs: List<Pair<String, String>>,
    covered: () -> Float = { 0f },
    button: @Composable (enabled: Boolean) -> Unit,
    rows: @Composable (tab: String) -> Unit,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val inputMode = LocalInputModeManager.current
    val narrow = LocalNarrowPane.current
    val chipFocus = remember(tabs.size) { List(tabs.size) { FocusRequester() } }
    val buttonFocus = remember { FocusRequester() }
    val leftForButton = remember { booleanArrayOf(false) }
    var rest by remember { mutableStateOf<Rect?>(null) }
    val openState = rememberUpdatedState(state.open)
    if (state.tab > tabs.lastIndex) state.tab = 0
    fun focusButton() { if (inputMode.inputMode == InputMode.Keyboard) runCatching { buttonFocus.requestFocus() } }
    fun close() {
        if (!state.open) return
        state.open = false
        focusButton()
    }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP && (state.open || state.shown || state.engaged)) state.reset()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(state.open) {
        if (state.settledByDrag) {
            state.settledByDrag = false
            if (state.open) {
                if (inputMode.inputMode == InputMode.Keyboard) { withFrameNanos { }; runCatching { chipFocus[state.tab].requestFocus() } }
            } else focusButton()
            return@LaunchedEffect
        }
        if (state.engaged) return@LaunchedEffect
        state.cancelDrag()
        if (state.open) {
            state.lipBack = false
            state.shown = true
            coroutineScope {
                launch { state.pull.animateTo(24f, Motion.sp(0.7f, 380f)) }
                launch { state.sink.animateTo(1f, Motion.tw(260)) }
                launch {
                    delay(Motion.ms(120).toLong())
                    launch { state.lipGone.animateTo(1f, Motion.tw(140)) }
                    launch { state.pull.animateTo(0f, Motion.sp(1f, 500f)) }
                    launch { state.lift.animateTo(1f, Motion.sp(0.7f, 380f)) }
                    launch { state.ride.animateTo(1f, Motion.sp(0.7f, 380f)) }
                    launch { state.hot.snapTo(1f); delay(Motion.ms(100).toLong()); state.hot.animateTo(0f, Motion.tw(200)) }
                }
                state.pieces.forEachIndexed { i, p -> launch { delay(Motion.ms(120 + 260 + i * 55).toLong()); p.animateTo(1f, Motion.tw(220)) } }
                if (inputMode.inputMode == InputMode.Keyboard) launch {
                    delay(Motion.ms(300).toLong())
                    withFrameNanos { }
                    runCatching { chipFocus[state.tab].requestFocus() }
                }
            }
        } else if (state.shown) {
            coroutineScope {
                state.pieces.forEach { launch { it.animateTo(0f, Motion.tw(90)) } }
                launch { delay(Motion.ms(60).toLong()); state.lift.animateTo(0f, Motion.sp(1f, 380f)) }
                launch { delay(Motion.ms(100).toLong()); state.ride.animateTo(0f, Motion.sp(0.55f, 300f)) }
                launch { state.sink.animateTo(0f, Motion.tw(300)) }
                launch { delay(Motion.ms(420).toLong()); state.lipGone.snapTo(0f); state.lipBack = true }
            }
            state.shown = false
            state.lipBack = false
            state.lipGone.snapTo(0f)
            state.hot.snapTo(1f)
        } else {
            listOf(state.pull, state.sink, state.lift, state.ride, state.lipGone).forEach { it.snapTo(0f) }
            state.pieces.forEach { it.snapTo(0f) }
            state.hot.snapTo(1f)
        }
    }
    BackHandler(enabled = state.open) { close() }
    if (state.ride.isRunning || (state.shown && !state.open)) VeilRing()

    val lipWanted = !state.open && (!state.shown || state.lipBack) && covered() < 0.5f
    LaunchedEffect(lipWanted) {
        if (lipWanted) {
            if (!state.lipBack) delay(Motion.ms(160).toLong())
            state.lipShow.animateTo(1f, Motion.tw(220))
        } else {
            delay(Motion.ms(140).toLong())
            state.lipShow.animateTo(0f, Motion.tw(220))
        }
    }

    val flick = with(density) { 600.dp.toPx() }
    val rowsPush = remember(state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!state.engaged || available.y <= 0f) return Offset.Zero
                val before = state.progress
                state.follow((before + available.y / state.travelPx).coerceAtMost(1f))
                return Offset(0f, (state.progress - before) * state.travelPx)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f || source != NestedScrollSource.Drag || !openState.value) return Offset.Zero
                if (!state.engaged) state.beginDrag()
                state.follow((state.progress + available.y / state.travelPx).coerceIn(0f, 1f))
                return available
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!state.engaged) return Velocity.Zero
                state.settle(available.y, flick)
                return available
            }
        }
    }

    val w = constraints.maxWidth.toFloat()
    val h = constraints.maxHeight.toFloat()
    val inset = with(density) { 12.dp.toPx() }
    val edge = with(density) { 3.dp.toPx() }
    val buttonH = rest?.height ?: with(density) { 56.dp.toPx() }
    val openBottom = h - sheetTop(h, buttonH, with(density) { 16.dp.toPx() }, edge)
    val lower = rest?.let { (openBottom - edge - buttonH / 2f) - it.top } ?: 0f
    state.travelPx = openBottom.coerceAtLeast(1f)

    if (state.shown || state.open) Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = state.sink.value }.background(Color.Black.copy(alpha = 0.42f))
            .focusProperties { canFocus = false }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = state.open) { close() },
    )

    if (state.shown) {
        val bottom = lerp(0f, openBottom, state.lift.value)
        val sheetLeft = inset
        val sheetRight = w - inset
        val corner = with(density) { 18.dp.toPx() }
        val rim = with(density) { 1.4.dp.toPx() }
        val path = remember { Path() }
        val rimPath = remember { Path() }
        Canvas(Modifier.fillMaxSize()) {
            val t = -corner
            rimPath.reset()
            rimPath.moveTo(sheetLeft, t)
            rimPath.lineTo(sheetLeft, bottom - corner)
            rimPath.arcTo(Rect(sheetLeft, bottom - 2 * corner, sheetLeft + 2 * corner, bottom), 180f, -90f, false)
            rimPath.lineTo(sheetRight - corner, bottom)
            rimPath.arcTo(Rect(sheetRight - 2 * corner, bottom - 2 * corner, sheetRight, bottom), 90f, -90f, false)
            rimPath.lineTo(sheetRight, t)
            path.reset()
            path.addPath(rimPath)
            path.close()
            drawPath(path, lerp(colors.surface, pal.signal, 0.22f * state.hot.value))
            drawPath(rimPath, pal.signal.copy(alpha = lerp(0.55f, 0.9f, state.hot.value)), style = Stroke(rim))
        }
        val pill = rest?.translate(0f, lower)
        val sheetW = sheetRight - sheetLeft
        val chipsH = with(density) { 44.dp.toPx() }
        val closeW = with(density) { 64.dp.toPx() }
        val chipsBottom = with(density) {
            if (narrow && pill != null) pill.top - 10.dp.toPx() else openBottom - 14.dp.toPx()
        }
        val chipsTop = chipsBottom - chipsH
        val chipsLeft = with(density) {
            if (narrow || pill == null) 16.dp.toPx() else pill.right - sheetLeft + 20.dp.toPx()
        }
        val chipsRight = sheetW - closeW
        val rowsBottom = chipsTop - with(density) { 8.dp.toPx() }
        Box(
            Modifier
                .offset { IntOffset(sheetLeft.roundToInt(), (bottom - openBottom).roundToInt()) }
                .size(with(density) { sheetW.toDp() }, with(density) { openBottom.toDp() })
                .pointerInput(Unit) { detectTapGestures { } }
                .controllerBack { close() }
                .bumpers(
                    onPrevious = { if (state.tab > 0) { state.travel = -1; state.tab--; runCatching { chipFocus[state.tab].requestFocus() } } },
                    onNext = { if (state.tab < tabs.lastIndex) { state.travel = 1; state.tab++; runCatching { chipFocus[state.tab].requestFocus() } } },
                )
                .focusProperties {
                    exit = { dir ->
                        if (!openState.value) FocusRequester.Default
                        else if (dir == FocusDirection.Left) { leftForButton[0] = true; buttonFocus }
                        else FocusRequester.Cancel
                    }
                }
                .focusGroup(),
        ) {
            Box(
                Modifier
                    .nestedScroll(rowsPush)
                    .fillMaxWidth()
                    .height(with(density) { rowsBottom.coerceAtLeast(0f).toDp() }),
            ) {
                AnimatedContent(
                    targetState = state.tab.coerceIn(0, tabs.lastIndex.coerceAtLeast(0)),
                    transitionSpec = {
                        val d = state.travel
                        val shift = with(density) { 24.dp.roundToPx() }
                        (slideInHorizontally(Motion.tw(240)) { shift * d } + fadeIn(Motion.tw(240)))
                            .togetherWith(slideOutHorizontally(Motion.tw(240)) { -shift * d } + fadeOut(Motion.tw(240)))
                    },
                    label = "dropTab",
                ) { t ->
                    val leaving = transition.targetState == androidx.compose.animation.EnterExitState.PostExit
                    val leavingNow = rememberUpdatedState(leaving)
                    CompositionLocalProvider(LocalSheetBeat provides remember { SheetBeat(state.pieces) }) {
                        Column(
                            Modifier.fillMaxSize()
                                .focusProperties { enter = { if (leavingNow.value) FocusRequester.Cancel else FocusRequester.Default } }
                                .focusGroup()
                                .verticalScroll(rememberScrollState())
                                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                        ) { tabs.getOrNull(t)?.let { rows(it.first) } }
                    }
                }
            }
            Box(
                Modifier
                    .offset { IntOffset(chipsLeft.roundToInt(), chipsTop.roundToInt()) }
                    .zIndex(1f)
                    .width(with(density) { (chipsRight - chipsLeft).coerceAtLeast(0f).toDp() })
                    .graphicsLayer { val v = state.pieces[0].value; alpha = v; translationY = (1f - v) * -6.dp.toPx() }
                    .onPreviewKeyEvent { e ->
                        val k = e.nativeKeyEvent
                        if (k.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { if (k.action == KeyEvent.ACTION_DOWN) close(); true } else false
                    },
            ) {
                SheetChips(tabs.map { it.second }, state.tab, chipFocus, width = LocalSheetChipWidth.current) { i -> state.travel = if (i > state.tab) 1 else -1; state.tab = i }
            }
            val closeSource = remember { MutableInteractionSource() }
            val closeHot = rememberHot(closeSource)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset { IntOffset((sheetW - 52.dp.toPx()).roundToInt(), chipsTop.roundToInt()) }
                    .zIndex(1f).size(44.dp).testTag("drop-close")
                    .clip(CircleShape).glideBorder(closeHot, CircleShape, pal.signal)
                    .hoverable(closeSource)
                    .clickable(interactionSource = closeSource, indication = LocalIndication.current, enabled = state.open, role = Role.Button) { close() }
                    .controllerConfirm(enabled = state.open) { close() },
            ) { Icon(Icons.Filled.Close, stringResource(R.string.common_close), tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
        }
    }

    val enabled = covered() < 0.5f
    val sideInset = if (narrow) 20.dp else 44.dp
    val play = LocalPlayRect.current
    Box(
        Modifier
            .offset { IntOffset(play?.left?.roundToInt() ?: sideInset.roundToPx(), sideInset.roundToPx()) }
            .then(if (play != null) Modifier.width(with(density) { play.width.toDp() }) else Modifier)
            .onGloballyPositioned { rest = it.boundsInParent() }
            .graphicsLayer { translationY = lower * state.ride.value; alpha = 1f - covered() }
            .zIndex(2f)
            .focusRequester(buttonFocus)
            .focusProperties { canFocus = enabled }
            .onFocusChanged {
                if (it.hasFocus && leftForButton[0]) { leftForButton[0] = false; if (openState.value) close() }
            }
            .onPreviewKeyEvent { e ->
                val k = e.nativeKeyEvent
                if (k.keyCode != KeyEvent.KEYCODE_DPAD_UP && !(state.open && k.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) false
                else {
                    if (k.action == KeyEvent.ACTION_DOWN && k.repeatCount == 0) {
                        if (state.open) runCatching { chipFocus[state.tab].requestFocus() } else if (enabled) state.open = true
                    }
                    true
                }
            },
    ) { button(enabled) }

    val r0 = rest
    if (r0 != null && (state.lipShow.value > 0.01f || state.pull.value > 0.5f)) {
        var handleSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        val lipH = 32.dp + state.pull.value.dp
        val drop = { if (!state.open && enabled) state.open = true }
        val settingsLabel = stringResource(R.string.drawer_page_settings)
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier
                .offset { IntOffset((r0.center.x - handleSize.width / 2f).roundToInt(), (r0.top + lower * state.ride.value - handleSize.height + (1f - state.lipShow.value) * 8.dp.toPx()).roundToInt()) }
                .onSizeChanged { handleSize = it }
                .graphicsLayer { alpha = state.lipShow.value * (1f - state.lipGone.value) * (1f - covered()) }
                .zIndex(2f)
                .heightIn(min = 44.dp).testTag("drop-open")
                .semantics { contentDescription = settingsLabel }
                .focusProperties { canFocus = false }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = drop),
        ) {
            Handle(lipH, above = true)
        }
    }
}
