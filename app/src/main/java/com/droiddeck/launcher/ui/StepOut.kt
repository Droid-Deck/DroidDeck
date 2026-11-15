package com.droiddeck.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt


internal fun stepPath(
    path: Path, p: Rect, left: Float, bottom: Float, pullTop: Float, box: Float, fillet: Float,
    pillCorner: Float = minOf(p.height / 2f, p.width / 2f),
) {
    path.reset()
    val r = p.right; val tl = p.left; val tt = p.top
    val pb = maxOf(bottom, p.bottom)
    val l = minOf(left, tl)
    val pt = minOf(pullTop, pb)
    val sw = tl - l
    val h = (pb - pt).coerceAtLeast(0f)
    val rt = minOf(pillCorner, p.height / 2f, p.width / 2f)
    val rbr = minOf(box, (pb - tt) / 2f)
    val rbl = minOf(lerpF(minOf(box, (pb - tt) / 2f), minOf(box, h / 2f), (sw / box).coerceIn(0f, 1f)), (r - l) / 2f)
    val rpl = minOf(box, sw / 2f, h / 2f)
    val yl = minOf(pt + rpl, pb - rbl)
    val sy = yl - rpl
    val f = minOf(fillet, sw / 2f, (sy - tt - rt).coerceAtLeast(0f))
    fun corner(rect: Rect, start: Float, sweep: Float, endX: Float, endY: Float) {
        if (rect.width < 0.5f) path.lineTo(endX, endY) else path.arcTo(rect, start, sweep, false)
    }
    path.moveTo(tl + rt, tt)
    path.lineTo(r - rt, tt)
    corner(Rect(r - 2 * rt, tt, r, tt + 2 * rt), -90f, 90f, r, tt + rt)
    path.lineTo(r, pb - rbr)
    corner(Rect(r - 2 * rbr, pb - 2 * rbr, r, pb), 0f, 90f, r - rbr, pb)
    path.lineTo(l + rbl, pb)
    corner(Rect(l, pb - 2 * rbl, l + 2 * rbl, pb), 90f, 90f, l, pb - rbl)
    path.lineTo(l, yl)
    corner(Rect(l, sy, l + 2 * rpl, sy + 2 * rpl), 180f, 90f, l + rpl, sy)
    path.lineTo(tl - f, sy)
    corner(Rect(tl - 2 * f, sy - 2 * f, tl, sy), 90f, -90f, tl, sy - f)
    path.lineTo(tl, tt + rt)
    corner(Rect(tl, tt, tl + 2 * rt, tt + 2 * rt), 180f, 90f, tl + rt, tt)
    path.close()
}

internal fun stepPathRight(
    path: Path, p: Rect, right: Float, bottom: Float, pullTop: Float, box: Float, fillet: Float,
    pillCorner: Float = minOf(p.height / 2f, p.width / 2f),
) {
    val axis = p.left + p.right
    stepPath(path, p, axis - right, bottom, pullTop, box, fillet, pillCorner)
    path.transform(Matrix().apply { this[0, 0] = -1f; this[3, 0] = axis })
}

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

internal enum class StepSide {
    Left,
    Right,
}

internal fun fitStepSide(preferred: StepSide, pill: Rect, hostWidth: Float, wanted: Float): StepSide {
    val leftRoom = pill.right
    val rightRoom = hostWidth - pill.left
    val room = if (preferred == StepSide.Left) leftRoom else rightRoom
    val other = if (preferred == StepSide.Left) rightRoom else leftRoom
    return if (room >= wanted || other <= room) preferred else if (preferred == StepSide.Left) StepSide.Right else StepSide.Left
}

internal fun stepReach(side: StepSide, pill: Rect, hostWidth: Float, width: Int): Pair<Float, Float> =
    if (side == StepSide.Left) pill.left to (pill.right - width).coerceAtLeast(0f)
    else pill.right to (pill.left + width).coerceAtMost(hostWidth)

internal class StepOutScope(private val items: List<Animatable<Float, AnimationVector1D>>, val first: FocusRequester, val open: Boolean, val maxHeight: Dp) {
    fun Modifier.stepItem(i: Int): Modifier = graphicsLayer {
        val v = items[i.coerceIn(0, items.lastIndex)].value
        alpha = v
        translationY = (1f - v) * 6.dp.toPx()
    }
}

private val OutPull = 6.dp
private val OutBoxCorner = 16.dp
private val OutFillet = 10.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun StepOut(
    open: Boolean,
    pill: Rect,
    preferredSide: StepSide,
    accent: Color,
    onDismiss: () -> Unit,
    onClosed: () -> Unit,
    pillCorner: Dp? = null,
    maxWidth: Dp = 300.dp,
    items: Int = 4,
    handle: @Composable () -> Unit,
    content: @Composable StepOutScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val inputMode = LocalInputModeManager.current
    val edge = remember { Animatable(0f) }
    val bottom = remember { Animatable(0f) }
    val tint = remember { Animatable(0f) }
    val dim = remember { Animatable(0f) }
    val pieces = remember { List(items) { Animatable(0f) } }
    var grown by remember { mutableStateOf(false) }
    var measured by remember { mutableStateOf<IntSize?>(null) }
    val first = remember { FocusRequester() }
    val glide = LocalFocusGlide.current
    val opener = remember { arrayOfNulls<GlideSource>(1) }
    val openNow = androidx.compose.runtime.rememberUpdatedState(open)
    var moving by remember { mutableStateOf(false) }
    val placed = remember { arrayOfNulls<Any>(3) }
    if (moving) VeilRing()
    val pull = with(density) { OutPull.toPx() }
    val corner = pillCorner?.let { with(density) { it.toPx() } } ?: minOf(pill.height, pill.width) / 2f

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hostWidth = constraints.maxWidth.toFloat()
        val hostHeight = constraints.maxHeight.toFloat()
        val availableHeight = with(density) {
            maxOf(hostHeight - pill.bottom - pull, pill.top - pull).coerceAtLeast(44.dp.toPx()).toDp()
        }
        val up = measured?.let { pill.bottom + pull + it.height > hostHeight && pill.top - pull - it.height >= 0f } ?: false
        val wantedW = with(density) { maxWidth.toPx() }
        val side = fitStepSide(preferredSide, pill, hostWidth, wantedW)
        val room = if (side == StepSide.Left) pill.right else hostWidth - pill.left
        val maxW = wantedW.coerceAtMost(room).coerceAtLeast(pill.width)
        LaunchedEffect(open, measured, side, pill, hostWidth) {
            val moved = placed[0] == open && placed[1] == measured
            val flipped = placed[2] != null && placed[2] != side
            placed[0] = open; placed[1] = measured; placed[2] = side
            if (!open && !grown) { if (!moved) onClosed(); return@LaunchedEffect }
            val c = measured ?: return@LaunchedEffect
            val (rest, out) = stepReach(side, pill, hostWidth, c.width)
            if (grown && flipped) edge.snapTo(if (open) out else rest)
            val boxBottom = pill.bottom + pull + c.height
            if (open) {
                if (!grown) {
                    opener[0] = glide?.current
                    edge.snapTo(rest); bottom.snapTo(pill.bottom); tint.snapTo(0f)
                    pieces.forEach { it.snapTo(0f) }
                    grown = true
                }
                coroutineScope {
                    launch { dim.animateTo(1f, Motion.tw(260)) }
                    launch { tint.animateTo(1f, Motion.tw(200)) }
                    moving = true
                    val down = launch { bottom.animateTo(boxBottom, Motion.sp(0.8f, 380f)) }
                    val across = launch { edge.animateTo(out, Motion.sp(0.8f, 380f)) }
                    launch { down.join(); across.join(); moving = false }
                    pieces.forEachIndexed { i, a ->
                        launch { delay(Motion.ms(260 + i * 55).toLong()); a.animateTo(1f, Motion.tw(220)) }
                    }
                }
            } else {
                opener[0]?.let { o -> if (inputMode.inputMode == InputMode.Keyboard) runCatching { o.requester.requestFocus() } }
                opener[0] = null
                coroutineScope {
                    pieces.forEach { launch { it.animateTo(0f, Motion.tw(90)) } }
                    launch { delay(Motion.ms(60).toLong()); dim.animateTo(0f, Motion.tw(300)) }
                    moving = true
                    launch { delay(Motion.ms(60).toLong()); edge.animateTo(rest, Motion.tw(240)) }
                    launch { delay(Motion.ms(60).toLong()); bottom.animateTo(pill.bottom, Motion.tw(240)) }
                    launch { delay(Motion.ms(200).toLong()); tint.animateTo(0f, Motion.tw(200)) }
                }
                grown = false
                moving = false
                onClosed()
            }
        }

        LaunchedEffect(open, measured) {
            if (!open || measured == null || inputMode.inputMode != InputMode.Keyboard) return@LaunchedEffect
            delay(Motion.ms(300).toLong())
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }

        LaunchedEffect(open, grown, inputMode.inputMode) {
            if (open && grown && inputMode.inputMode == InputMode.Keyboard) { withFrameNanos { }; runCatching { first.requestFocus() } }
        }
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = dim.value }.background(Color(0x59000000))
                .focusProperties { canFocus = false }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = open, onClick = onDismiss),
        )
        val path = remember { Path() }
        val boxCorner = with(density) { OutBoxCorner.toPx() }
        val fillet = with(density) { OutFillet.toPx() }
        val rim = with(density) { 1.4.dp.toPx() }
        val restFill = colors.surface
        val hotFill = lerp(colors.surface, accent, 0.22f)
        Canvas(Modifier.fillMaxSize()) {
            if (!grown) return@Canvas
            if (side == StepSide.Left) stepPath(path, pill, edge.value, bottom.value, pill.bottom + pull, boxCorner, fillet, corner)
            else stepPathRight(path, pill, edge.value, bottom.value, pill.bottom + pull, boxCorner, fillet, corner)
            if (up) path.transform(Matrix().apply { this[1, 1] = -1f; this[3, 1] = pill.top + pill.bottom })
            val a = measured?.let { c ->
                val (rest, out) = stepReach(side, pill, hostWidth, c.width)
                val down = pill.bottom + pull + c.height - pill.bottom
                val across = kotlin.math.abs(out - rest)
                val fd = if (down < 1f) 1f else (bottom.value - pill.bottom) / down
                val fa = if (across < 1f) 1f else kotlin.math.abs(edge.value - rest) / across
                ((minOf(fd, fa) - 0.1f) / 0.3f).coerceIn(0f, 1f)
            } ?: 0f
            if (a <= 0.01f) return@Canvas
            drawPath(path, lerp(hotFill, restFill, tint.value), alpha = a)
            drawPath(path, accent.copy(alpha = lerpF(0.9f, 0.55f, tint.value) * a), style = Stroke(rim))
        }
        if (grown) Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.offset { IntOffset(pill.left.roundToInt(), pill.top.roundToInt()) }
                .size(with(density) { pill.width.toDp() }, with(density) { pill.height.toDp() }),
        ) { handle() }
        val x = if (side == StepSide.Left) pill.right - (measured?.width?.toFloat() ?: maxW) else pill.left
        Column(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), (if (up) pill.top - pull - (measured?.height ?: 0) else pill.bottom + pull).roundToInt()) }
                .widthIn(min = with(density) { pill.width.toDp() }, max = with(density) { maxW.toDp() })
                .heightIn(max = availableHeight)
                .onSizeChanged { if (measured != it) measured = it }
                .controllerBack(onDismiss)
                .focusProperties { exit = { if (openNow.value) FocusRequester.Cancel else FocusRequester.Default } }
                .focusGroup()
                .verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        ) { StepOutScope(pieces, first, open, availableHeight).content() }
    }
}

@Composable
internal fun StepChoice(
    label: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    icon: ImageVector? = null,
    trailing: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(20.dp)
    val accent = if (danger) colors.error else pal.signal
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.heightIn(min = 40.dp).clip(shape)
            .background(if (hot || selected) accent.copy(alpha = if (danger) 0.18f else 0.14f) else Color.Transparent)
            .glideBorder(hot, shape, accent, if (selected) accent.copy(alpha = 0.5f) else Color.Transparent)
            .hoverable(src)
            .clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (hot) accent else colors.onBackground, modifier = Modifier.size(20.dp))
        Text(
            label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (danger) colors.error else colors.onBackground,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = if (trailing != null) Modifier.weight(1f) else Modifier,
        )
        if (trailing != null) Text(trailing, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
internal fun StepTile(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    detail: String? = null,
    warning: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(18.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.width(184.dp).height(IntrinsicSize.Min).clip(shape)
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else colors.surface.copy(alpha = 0.5f))
            .glideBorder(hot, shape, pal.signal, pal.line)
            .hoverable(src)
            .clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(icon, null, tint = if (hot) pal.signal else colors.onBackground, modifier = Modifier.fillMaxHeight().aspectRatio(1f))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, fontSize = 12.sp, color = if (warning) colors.error else colors.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
internal fun StepOutScope.StepTitle(text: String) {
    Text(
        text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.stepItem(0).padding(start = 4.dp, bottom = 10.dp),
    )
}

@Composable
internal fun StepList(content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}
