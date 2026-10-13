package com.droiddeck.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import com.droiddeck.launcher.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val DROP_PIECES = 10

internal class DropSheetState(private val scope: CoroutineScope) {
    var open by mutableStateOf(false)
    var shown by mutableStateOf(false)
    var settledByDrag = false
    var engaged = false
        private set
    val lift = Animatable(0f)
    val ride = Animatable(0f)
    val sink = Animatable(0f)
    val hot = Animatable(1f)
    val pieces = List(DROP_PIECES) { Animatable(0f) }
    var travelPx = 1f
    private var from = 0f
    private var progress = 0f
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
        scope.launch {
            listOf(lift, ride, sink).forEach { it.snapTo(0f) }
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

    fun drag(dy: Float) {
        progress = (progress + dy / travelPx).coerceIn(0f, 1.04f)
        val p = progress
        scope.launch {
            lift.snapTo(p); ride.snapTo(p); sink.snapTo(p.coerceIn(0f, 1f)); hot.snapTo(0f)
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
            if (!opens) { shown = false; hot.snapTo(1f) }
        }
        if (open != opens) open = opens else settledByDrag = false
    }
}

@Composable
internal fun rememberDropSheetState(): DropSheetState {
    val scope = rememberCoroutineScope()
    return remember { DropSheetState(scope) }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun BoxWithConstraintsScope.DropSheet(
    state: DropSheetState,
    title: String,
    icon: ImageVector,
    label: String,
    covered: () -> Float = { 0f },
    rows: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val inputMode = LocalInputModeManager.current
    val firstRow = remember { FocusRequester() }
    val buttonFocus = remember { FocusRequester() }
    var rest by remember { mutableStateOf<Rect?>(null) }
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
                if (inputMode.inputMode == InputMode.Keyboard) { withFrameNanos { }; runCatching { firstRow.requestFocus() } }
            } else focusButton()
            return@LaunchedEffect
        }
        if (state.engaged) return@LaunchedEffect
        state.cancelDrag()
        if (state.open) {
            state.shown = true
            coroutineScope {
                launch { state.sink.animateTo(1f, Motion.tw(260)) }
                launch {
                    delay(Motion.ms(120).toLong())
                    launch { state.lift.animateTo(1f, Motion.sp(0.7f, 380f)) }
                    launch { state.ride.animateTo(1f, Motion.sp(0.7f, 380f)) }
                    launch { state.hot.snapTo(1f); delay(Motion.ms(100).toLong()); state.hot.animateTo(0f, Motion.tw(200)) }
                }
                state.pieces.forEachIndexed { i, p -> launch { delay(Motion.ms(120 + 260 + i * 55).toLong()); p.animateTo(1f, Motion.tw(220)) } }
                if (inputMode.inputMode == InputMode.Keyboard) launch {
                    delay(Motion.ms(300).toLong())
                    withFrameNanos { }
                    runCatching { firstRow.requestFocus() }
                }
            }
        } else if (state.shown) {
            coroutineScope {
                state.pieces.forEach { launch { it.animateTo(0f, Motion.tw(90)) } }
                launch { delay(Motion.ms(60).toLong()); state.lift.animateTo(0f, Motion.sp(1f, 380f)) }
                launch { delay(Motion.ms(100).toLong()); state.ride.animateTo(0f, Motion.sp(0.55f, 300f)) }
                launch { state.sink.animateTo(0f, Motion.tw(300)) }
            }
            state.shown = false
            state.hot.snapTo(1f)
        } else {
            listOf(state.sink, state.lift, state.ride).forEach { it.snapTo(0f) }
            state.pieces.forEach { it.snapTo(0f) }
            state.hot.snapTo(1f)
        }
    }
    BackHandler(enabled = state.open) { close() }

    val w = constraints.maxWidth.toFloat()
    val h = constraints.maxHeight.toFloat()
    val inset = with(density) { 12.dp.toPx() }
    val edge = with(density) { 3.dp.toPx() }
    val buttonH = rest?.height ?: with(density) { 48.dp.toPx() }
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
        val corner = with(density) { 18.dp.toPx() }
        val rim = with(density) { 1.4.dp.toPx() }
        val path = remember { Path() }
        val rimPath = remember { Path() }
        Canvas(Modifier.fillMaxSize()) {
            val l = inset
            val r = w - inset
            val t = -corner
            rimPath.reset()
            rimPath.moveTo(l, t)
            rimPath.lineTo(l, bottom - corner)
            rimPath.arcTo(Rect(l, bottom - 2 * corner, l + 2 * corner, bottom), 180f, -90f, false)
            rimPath.lineTo(r - corner, bottom)
            rimPath.arcTo(Rect(r - 2 * corner, bottom - 2 * corner, r, bottom), 90f, -90f, false)
            rimPath.lineTo(r, t)
            path.reset()
            path.addPath(rimPath)
            path.close()
            drawPath(path, lerp(colors.surface, pal.signal, 0.22f * state.hot.value))
            drawPath(rimPath, pal.signal.copy(alpha = lerp(0.55f, 0.9f, state.hot.value)), style = Stroke(rim))
        }
        val sheetW = w - 2 * inset
        val rowsTop = with(density) { 62.dp.toPx() }
        val rowsBottom = openBottom - edge - buttonH / 2f - with(density) { 8.dp.toPx() }
        Box(
            Modifier
                .offset { IntOffset(inset.roundToInt(), (bottom - openBottom).roundToInt()) }
                .size(with(density) { sheetW.toDp() }, with(density) { openBottom.toDp() })
                .pointerInput(Unit) { detectTapGestures { } }
                .controllerBack { close() }
                .focusProperties { exit = { if (state.open) FocusRequester.Cancel else FocusRequester.Default } }
                .focusGroup(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .offset { IntOffset(0, 18.dp.roundToPx()) }
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 64.dp)
                    .graphicsLayer { val v = state.pieces[0].value; alpha = v; translationY = (1f - v) * -6.dp.toPx() },
            ) {
                Icon(icon, null, tint = pal.signal, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(
                Modifier
                    .offset { IntOffset(0, rowsTop.roundToInt()) }
                    .fillMaxWidth()
                    .height(with(density) { (rowsBottom - rowsTop).coerceAtLeast(0f).toDp() })
                    .focusRequester(firstRow)
                    .focusGroup()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            ) {
                CompositionLocalProvider(LocalSheetBeat provides remember { SheetBeat(state.pieces) }) { rows() }
            }
            val closeSource = remember { MutableInteractionSource() }
            val closeHot = rememberHot(closeSource)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset { IntOffset((sheetW - 52.dp.toPx()).roundToInt(), 8.dp.roundToPx()) }
                    .zIndex(1f).size(44.dp).testTag("drop-close")
                    .clip(CircleShape).glideBorder(closeHot, CircleShape, pal.signal)
                    .hoverable(closeSource)
                    .clickable(interactionSource = closeSource, indication = LocalIndication.current, enabled = state.open, role = Role.Button) { close() }
                    .controllerConfirm(enabled = state.open) { close() },
            ) { Icon(Icons.Filled.Close, stringResource(R.string.common_close), tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
        }
    }

    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val toggle = { if (state.open) close() else state.open = true }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 16.dp, end = 20.dp)
            .onGloballyPositioned { rest = it.boundsInParent() }
            .graphicsLayer { translationY = lower * state.ride.value; alpha = 1f - covered() }
            .zIndex(2f)
            .size(48.dp)
            .testTag("glasses-open")
            .focusRequester(buttonFocus)
            .clip(CircleShape)
            .background(lerp(colors.surface.copy(alpha = 0.82f), pal.signal, 0.22f * state.ride.value))
            .glideBorder(hot, CircleShape, pal.signal, pal.line)
            .hoverable(src)
            .focusProperties { canFocus = covered() < 0.5f }
            .clickable(interactionSource = src, indication = LocalIndication.current, enabled = covered() < 0.5f, role = Role.Button, onClick = toggle)
            .controllerConfirm(enabled = covered() < 0.5f, onClick = toggle),
    ) {
        Icon(icon, label, tint = if (hot || state.open) colors.onBackground else colors.onSurfaceVariant, modifier = Modifier.size(26.dp))
    }
}
