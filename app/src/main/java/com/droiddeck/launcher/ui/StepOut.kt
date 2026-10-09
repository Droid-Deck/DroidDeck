package com.droiddeck.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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

// End session's confirm, for any control: a pull drops out of the control and a wider box steps
// out under it for the choices, one edge flush with the control. Stop draws it from its pill on
// the drawer; the stores draw it from Install, the Library tab and a download's cancel.

/**
 * The stair-step outline: the pill [p] on top, a box under it from [left] to the pill's right edge
 * and down to [bottom], the pull between them [pullTop] (the box's top). Right edges are flush and
 * the inside corner where the pull meets the box is rounded the other way. While the box is still
 * no wider than the pill it is just the pill, stretched down. [pillCorner] is the control's own
 * corner: a pill's is half its height.
 */
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
    val f = minOf(fillet, sw / 2f)
    val yl = minOf(pt + rpl, pb - rbl)
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
    corner(Rect(l, pt, l + 2 * rpl, pt + 2 * rpl), 180f, 90f, l + rpl, pt)
    path.lineTo(tl - f, pt)
    corner(Rect(tl - 2 * f, pt - 2 * f, tl, pt), 90f, -90f, tl, pt - f)
    path.lineTo(tl, tt + rt)
    corner(Rect(tl, tt, tl + 2 * rt, tt + 2 * rt), 180f, 90f, tl + rt, tt)
    path.close()
}

/** [stepPath] for a box that steps out to the right of the pill, out to [right], left edges flush. */
internal fun stepPathRight(
    path: Path, p: Rect, right: Float, bottom: Float, pullTop: Float, box: Float, fillet: Float,
    pillCorner: Float = minOf(p.height / 2f, p.width / 2f),
) {
    // Drawn as the left-hand outline about the pill's centre, which maps the pill onto itself,
    // then mirrored back: x -> (p.left + p.right) - x.
    val axis = p.left + p.right
    stepPath(path, p, axis - right, bottom, pullTop, box, fillet, pillCorner)
    path.transform(Matrix().apply { this[0, 0] = -1f; this[3, 0] = axis })
}

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Which way the box steps out of its control. */
internal enum class StepSide {
    /** Out to the left, right edges flush (a control at the right: Stop, a row's cancel). */
    Left,
    /** Out to the right, left edges flush (a control at the left: Install, a tab). */
    Right,
}

/** The choices' side of a [StepOut]: each piece fades in on its own beat, and [first] takes focus. */
internal class StepOutScope(private val items: List<Animatable<Float, AnimationVector1D>>, val first: FocusRequester, val open: Boolean) {
    /** Piece [i] (0 first) of the box: it fades and rises in after the box has stepped out. */
    fun Modifier.stepItem(i: Int): Modifier = graphicsLayer {
        val v = items[i.coerceIn(0, items.lastIndex)].value
        alpha = v
        translationY = (1f - v) * 6.dp.toPx()
    }
}

/** Room between the control and the box that steps out under it: the pull. */
private val OutPull = 6.dp
private val OutBoxCorner = 16.dp
private val OutFillet = 10.dp

/**
 * A confirm or a short list grown out of the control at [pill] (px, in this element's space, which
 * should fill the screen area it may cover): a pull the control's width drops out of it, then a
 * box steps out to [side] for [content]. [onDismiss] (B, a tap outside) asks to fold it; it folds
 * back width first once [open] is false, then [onClosed]. Exactly StopConfirm's springs.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun StepOut(
    open: Boolean,
    pill: Rect,
    side: StepSide,
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
    val pull = with(density) { OutPull.toPx() }
    val corner = pillCorner?.let { with(density) { it.toPx() } } ?: minOf(pill.height, pill.width) / 2f

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hostWidth = constraints.maxWidth.toFloat()
        val room = if (side == StepSide.Left) pill.right else hostWidth - pill.left
        val maxW = with(density) { maxWidth.toPx() }.coerceAtMost(room).coerceAtLeast(pill.width)
        LaunchedEffect(open, measured) {
            // Asked to close before it ever measured: nothing to fold.
            if (!open && !grown) { onClosed(); return@LaunchedEffect }
            val c = measured ?: return@LaunchedEffect
            val rest = if (side == StepSide.Left) pill.left else pill.right
            val out = if (side == StepSide.Left) (pill.right - c.width).coerceAtLeast(0f) else (pill.left + c.width).coerceAtMost(hostWidth)
            val boxBottom = pill.bottom + pull + c.height
            if (open) {
                if (!grown) {
                    edge.snapTo(rest); bottom.snapTo(pill.bottom); tint.snapTo(0f)
                    pieces.forEach { it.snapTo(0f) }
                    grown = true
                }
                coroutineScope {
                    launch { dim.animateTo(1f, Motion.tw(260)) }
                    launch { tint.animateTo(1f, Motion.tw(200)) }
                    launch { bottom.animateTo(boxBottom, Motion.sp(0.7f, 380f)) }
                    launch { delay(Motion.ms(110).toLong()); edge.animateTo(out, Motion.sp(0.55f, 300f)) }
                    pieces.forEachIndexed { i, a ->
                        launch { delay(Motion.ms(260 + i * 55).toLong()); a.animateTo(1f, Motion.tw(220)) }
                    }
                    if (inputMode.inputMode == InputMode.Keyboard) launch {
                        delay(Motion.ms(300).toLong())
                        withFrameNanos { }
                        runCatching { first.requestFocus() }
                    }
                }
            } else {
                coroutineScope {
                    pieces.forEach { launch { it.animateTo(0f, Motion.tw(90)) } }
                    launch { delay(Motion.ms(60).toLong()); dim.animateTo(0f, Motion.tw(300)) }
                    launch { delay(Motion.ms(60).toLong()); edge.animateTo(rest, Motion.sp(1f, 420f)) }
                    launch { delay(Motion.ms(160).toLong()); bottom.animateTo(pill.bottom, Motion.sp(1f, 380f)) }
                    launch { delay(Motion.ms(200).toLong()); tint.animateTo(0f, Motion.tw(200)) }
                }
                grown = false
                onClosed()
            }
        }

        // Opened by touch and then answered with a pad: the pad starts in the box, not behind it.
        LaunchedEffect(open, grown, inputMode.inputMode) {
            if (open && grown && inputMode.inputMode == InputMode.Keyboard) { withFrameNanos { }; runCatching { first.requestFocus() } }
        }
        // The rest of the screen dims behind it, and a tap there folds it. Never a pad's stop.
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
            drawPath(path, lerp(hotFill, restFill, tint.value))
            drawPath(path, accent.copy(alpha = lerpF(0.9f, 0.55f, tint.value)), style = Stroke(rim))
        }
        // The control's label rides on top, as the pull's handle.
        if (grown) Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.offset { IntOffset(pill.left.roundToInt(), pill.top.roundToInt()) }
                .size(with(density) { pill.width.toDp() }, with(density) { pill.height.toDp() }),
        ) { handle() }
        val x = if (side == StepSide.Left) pill.right - (measured?.width?.toFloat() ?: maxW) else pill.left
        Column(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), (pill.bottom + pull).roundToInt()) }
                .widthIn(min = with(density) { pill.width.toDp() }, max = with(density) { maxW.toDp() })
                .onSizeChanged { if (measured != it) measured = it }
                .controllerBack(onDismiss)
                // A pad stays in the box until it is answered or folded, as it did in Stop's dialog.
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        ) { StepOutScope(pieces, first, open).content() }
    }
}

/**
 * One choice in a [StepOut]: a capsule that fills when focused, like Stop's Cancel and Stop. [danger]
 * draws it in red; [icon] and [trailing] make it a row (a place to install to and its free space).
 */
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

/** A [StepOut]'s heading line, the first piece to fade in. */
@Composable
internal fun StepOutScope.StepTitle(text: String) {
    Text(
        text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.stepItem(0).padding(start = 4.dp, bottom = 10.dp),
    )
}

/** The choices of a [StepOut] stacked, each the box's width. */
@Composable
internal fun StepList(content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}
