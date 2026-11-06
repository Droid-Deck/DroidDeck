package com.droiddeck.launcher.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.droiddeck.launcher.stores.Store
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sin


internal object StoresMotion {
    var installFrom: Rect? = null
        private set
    var installLabel: String = ""
        private set
    private var installAt = 0L

    fun markInstall(bounds: Rect?, label: String) {
        installFrom = bounds
        installLabel = label
        installAt = SystemClock.uptimeMillis()
    }

    fun recentInstall(): Rect? = installFrom.takeIf { SystemClock.uptimeMillis() - installAt < 5_000 }

    fun takeInstall(): Rect? = installFrom.takeIf { SystemClock.uptimeMillis() - installAt < 20_000 }.also { installAt = 0L }

    var downloadsChip by mutableStateOf<Rect?>(null)

    var landed by mutableIntStateOf(0)

    val flights = mutableStateListOf<Flight>()

    fun fly(from: Rect) {
        if (Motion.scale == 0f || downloadsChip == null) { landed++; return }
        flights += Flight(from, to = { downloadsChip?.let { Offset(it.right - 22.dp.toPx(), it.center.y) } }, onLand = { landed++ })
    }

    var step by mutableStateOf<StepAsk?>(null)
        private set
    var stepOpen by mutableStateOf(false)
        private set

    fun ask(a: StepAsk) {
        step?.onDismiss()
        step = a
        stepOpen = true
    }

    fun drop() {
        step?.let { step = null; it.onDismiss() }
        stepOpen = false
        signIn = null
    }

    fun fold() { stepOpen = false }

    internal fun closed(a: StepAsk) {
        if (step === a) step = null
        a.onDismiss()
    }

    var signIn by mutableStateOf<SignInFlood?>(null)

    val pulses = androidx.compose.runtime.mutableStateMapOf<Store, Int>()

    fun pulse(store: Store) { pulses[store] = (pulses[store] ?: 0) + 1 }

    val dots = HashMap<Store, Rect>()

    var heroAt by mutableStateOf<Rect?>(null)

    var heroLands by mutableStateOf(false)

    private var card: CardMark? = null
    private var cardAt = 0L

    fun markCard(c: CardMark) {
        card = c
        cardAt = SystemClock.uptimeMillis()
    }

    fun takeCard(key: String): CardMark? = card?.takeIf { it.key == key && SystemClock.uptimeMillis() - cardAt < 1_000 }.also { card = null }
}

internal class CardMark(val key: String, val bounds: Rect, val corner: Float, val art: String?)

internal class StepAsk(
    val anchor: Rect,
    val side: StepSide,
    val accent: Color,
    val pillCorner: androidx.compose.ui.unit.Dp? = null,
    val items: Int = 4,
    val onDismiss: () -> Unit = {},
    val canDismiss: () -> Boolean = { true },
    val maxWidth: androidx.compose.ui.unit.Dp = 300.dp,
    val handle: @Composable () -> Unit,
    val content: @Composable StepOutScope.() -> Unit,
)

internal class SignInFlood(val store: Store, val from: Rect, val start: Color, val color: Color) {
    var away by mutableStateOf(false)
    var drainTo by mutableStateOf<Rect?>(null)
}

@Composable
internal fun FlightLayer(origin: Offset) {
    for (f in StoresMotion.flights.toList()) key(f.id) { FlightDot(f, origin) { StoresMotion.flights.remove(f) } }
}

internal fun Modifier.landingPop(trigger: Int = StoresMotion.landed): Modifier = composed {
    val pal = LocalPalette.current
    val scale = remember { Animatable(1f) }
    val ring = remember { Animatable(1f) }
    val seen = remember { intArrayOf(trigger) }
    LaunchedEffect(trigger) {
        if (trigger == seen[0]) return@LaunchedEffect
        seen[0] = trigger
        coroutineScope {
            launch { scale.snapTo(1.3f); scale.animateTo(1f, Motion.sp(0.45f, 500f)) }
            launch { ring.snapTo(0f); ring.animateTo(1f, Motion.tw(400)) }
        }
    }
    this.drawBehind {
        if (ring.value < 1f) {
            val rr = max(size.width, size.height) / 2f * lerp(0.4f, 1.6f, ring.value)
            drawCircle(pal.signal.copy(alpha = 0.5f * (1f - ring.value)), rr, center, style = Stroke(1.5.dp.toPx()))
        }
    }.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

@Composable
internal fun ColorFlood(from: Rect, start: Color, color: Color, onCovered: () -> Unit) {
    val edges = remember { List(4) { Animatable(0f) } }
    val reached = remember { BooleanArray(4) }
    var size by remember { mutableStateOf(Size.Zero) }
    LaunchedEffect(size != Size.Zero) {
        if (size == Size.Zero) return@LaunchedEffect
        val travel = listOf(from.left, from.top, size.width - from.right, size.height - from.bottom).map { it.coerceAtLeast(0f) }
        val far = travel.max().coerceAtLeast(1f)
        coroutineScope {
            edges.forEachIndexed { i, edge ->
                val lead = travel[i] / far
                launch {
                    delay(Motion.ms((120 * (1f - lead)).toInt()).toLong())
                    edge.animateTo(1f, if (Motion.scale == 0f) Motion.sp() else spring(dampingRatio = 0.48f, stiffness = lerp(110f, 190f, lead))) {
                        if (value >= 1f) reached[i] = true
                    }
                    reached[i] = true
                }
            }
            snapshotFlow { edges.indices.all { reached[it] || edges[it].value >= 1f } }.first { it }
            onCovered()
        }
    }
    Canvas(Modifier.fillMaxSize().onSizeChangedCompat { size = it }) {
        val v = edges.mapIndexed { i, e -> if (reached[i]) 1f else e.value }
        drawFloodShape(from, from.minDimension / 2f, v, lerp(start, color, (v.average().toFloat() * 2f).coerceIn(0f, 1f)))
    }
}

@Composable
internal fun ColorDrain(color: Color, to: Rect, toCorner: Float, onDone: () -> Unit) {
    val edges = remember { List(4) { Animatable(1f) } }
    val alpha = remember { Animatable(1f) }
    var size by remember { mutableStateOf(Size.Zero) }
    LaunchedEffect(size != Size.Zero) {
        if (size == Size.Zero) return@LaunchedEffect
        val travel = listOf(to.left, to.top, size.width - to.right, size.height - to.bottom).map { it.coerceAtLeast(0f) }
        val far = travel.max().coerceAtLeast(1f)
        coroutineScope {
            edges.mapIndexed { i, edge ->
                val lead = travel[i] / far
                launch {
                    delay(Motion.ms((120 * (1f - lead)).toInt()).toLong())
                    edge.animateTo(0f, if (Motion.scale == 0f) Motion.sp() else spring(dampingRatio = 1f, stiffness = lerp(170f, 260f, lead)))
                }
            }.forEach { it.join() }
        }
        alpha.animateTo(0f, Motion.tw(160))
        onDone()
    }
    Canvas(Modifier.fillMaxSize().onSizeChangedCompat { size = it }) {
        drawFloodShape(to, toCorner, edges.map { it.value }, color.copy(alpha = color.alpha * alpha.value))
    }
}

private fun Modifier.onSizeChangedCompat(onSize: (Size) -> Unit) =
    onSizeChanged { onSize(Size(it.width.toFloat(), it.height.toFloat())) }

private fun DrawScope.drawFloodShape(r: Rect, restCorner: Float, v: List<Float>, color: Color) {
    val left = lerp(r.left, 0f, v[0])
    val top = lerp(r.top, 0f, v[1])
    val right = lerp(r.right, size.width, v[2])
    val bottom = lerp(r.bottom, size.height, v[3])
    val w = (right - left).coerceAtLeast(0f)
    val h = (bottom - top).coerceAtLeast(0f)
    val mean = v.sumOf { it.coerceIn(0f, 1f).toDouble() }.toFloat() / 4f
    val blob = sin(PI * mean).toFloat().coerceAtLeast(0f)
    val corner = lerp(restCorner * (1f - mean), minOf(w, h) * 0.42f, blob)
    drawRoundRect(color, Offset(left, top), Size(w, h), CornerRadius(corner))
}

internal fun Modifier.pulseRing(trigger: Int, color: Color): Modifier = composed {
    val ring = remember { Animatable(1f) }
    val seen = remember { intArrayOf(trigger) }
    LaunchedEffect(trigger) {
        if (trigger == seen[0]) return@LaunchedEffect
        seen[0] = trigger
        if (Motion.scale == 0f) return@LaunchedEffect
        ring.snapTo(0f)
        ring.animateTo(1f, Motion.tw(600))
    }
    drawBehind {
        if (ring.value < 1f) drawCircle(
            color.copy(alpha = 0.6f * (1f - ring.value)), size.minDimension / 2f * lerp(0.4f, 1.6f, ring.value) * 1.4f, center,
            style = Stroke(1.5.dp.toPx()),
        )
    }
}

@Composable
internal fun <T> AnimatedRows(
    items: List<T>,
    key: (T) -> Any,
    enter: (index: Int, later: Boolean) -> EnterTransition,
    exit: (item: T, index: Int, count: Int) -> ExitTransition,
    content: @Composable (item: T, later: Boolean) -> Unit,
) {
    val order = remember { mutableListOf<Any>() }
    val last = remember { mutableMapOf<Any, T>() }
    val states = remember { mutableMapOf<Any, MutableTransitionState<Boolean>>() }
    val later = remember { mutableMapOf<Any, Boolean>() }
    val mounted = remember { booleanArrayOf(false) }
    val now = items.associateBy(key)
    val merged = ArrayList<Any>()
    var j = 0
    for (k in now.keys) {
        val pos = order.indexOf(k)
        if (pos >= 0) {
            while (j < pos) { val o = order[j]; if (o !in now && o !in merged) merged += o; j++ }
            j = maxOf(j, pos + 1)
        }
        merged += k
    }
    while (j < order.size) { val o = order[j]; if (o !in now && o !in merged) merged += o; j++ }
    now.forEach { (k, v) -> last[k] = v }
    val shown = merged.filter { k ->
        val st = states.getOrPut(k) { MutableTransitionState(false).also { later[k] = mounted[0] } }
        st.targetState = k in now
        val gone = k !in now && st.isIdle && !st.currentState
        if (gone) { states.remove(k); last.remove(k); later.remove(k) }
        !gone
    }
    order.clear(); order.addAll(shown)
    SideEffect { mounted[0] = true }
    shown.forEachIndexed { i, k ->
        val item = last[k] ?: return@forEachIndexed
        val st = states[k] ?: return@forEachIndexed
        key(k) {
            AnimatedVisibility(st, enter = enter(i, later[k] == true), exit = exit(item, i, shown.size)) { content(item, later[k] == true) }
        }
    }
}
