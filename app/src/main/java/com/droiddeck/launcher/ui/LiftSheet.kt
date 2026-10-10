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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.droiddeck.launcher.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Play's settings, lifted out from under Play instead of opened on a page of their own. At rest a
// lip tucks out under Play and says what it will do. Down (or a pull up, or a tap on the lip)
// stretches the lip, the wall sinks back, and a sheet rises from the pane's foot with Play riding
// its top edge as the handle - StepOut's move, with the pane for a box. B, Up from the chips or a
// tap above folds it away; A on Play launches straight from it, the sheet folding into the pill
// before the launch flood. Open desktop does the same with the desktop's settings.

/** The sheets' tabs (their chips), by id. */
internal object SheetTab {
    const val DISPLAY = "display"
    const val CONTROLS = "controls"
    const val AUDIO = "audio"
    const val SESSION = "session"
    const val LIBRARY = "library"
    const val STEAM = "steam"
    const val FILES = "files"
}

/**
 * What the lip says, in order, by part: the session's size, then its frame-rate cap, its upscaler
 * and its touch mode, each left out at its default (null or 0) to keep the lip short.
 */
internal fun lipParts(size: Pair<Int, Int>, fps: Int, fpsLabel: (Int) -> String, upscaler: String?, touch: String?): List<Pair<String, String>> = buildList {
    add("size" to "${size.first}×${size.second}")
    if (fps > 0) add("fps" to fpsLabel(fps))
    if (upscaler != null) add("upscaler" to upscaler)
    if (touch != null) add("touch" to touch)
}

/** The parts of [now] whose value differs from what the lip showed last ([seen]); none the first time. */
internal fun lipChanged(seen: Map<String, String>?, now: List<Pair<String, String>>): Set<String> =
    if (seen == null) emptySet() else now.filter { (k, v) -> seen[k] != v }.map { it.first }.toSet()

/**
 * Where the open sheet's top edge sits in a pane [paneH] tall (px), for a button [buttonH] tall
 * riding it: 82dp down a 360dp pane, the same share of any other, and never so high that the
 * button's top comes closer than [minAbove] to the pane's. [ride] is how far below the edge the
 * button's centre sits.
 */
internal fun sheetTop(paneH: Float, buttonH: Float, minAbove: Float, ride: Float): Float =
    maxOf(paneH * 82f / 360f, minAbove + buttonH / 2f - ride)

/** What the lip showed last, per sheet: a value that changed since is lit the next time it shows. */
private object LipMemory {
    val seen = HashMap<String, Map<String, String>>()
}

/** One piece of the sheet's rows fades and rises in on its own beat (a settings group asks for its index). */
internal class SheetBeat(private val pieces: List<Animatable<Float, AnimationVector1D>>) {
    private var next = 1
    fun take(): Int = next++
    fun value(i: Int): Float = pieces[i.coerceIn(0, pieces.lastIndex)].value
}

/** The sheet whose rows are being laid out, so each settings group can take its beat. */
internal val LocalSheetBeat = staticCompositionLocalOf<SheetBeat?> { null }

/** A settings group's piece of the sheet's entrance; nothing outside a sheet. */
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

/**
 * A hero with one big button and the sheet that lifts out from under it. [key] names the sheet
 * (a hop comes back to it, the lip remembers what it said); [tabs] are its chips (id to label) and
 * [rows] what each holds. [background] sinks and [words] fade as it opens. [button] draws the
 * button itself with the click it should make; [onPrimary] is what that click does once the
 * sheet is out of the way. [onOpen] reads the settings the rows show.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
internal fun LiftHero(
    key: String,
    tabs: List<Pair<String, String>>,
    lip: () -> List<Pair<String, String>>,
    onOpen: () -> Unit,
    onPrimary: () -> Unit,
    background: @Composable () -> Unit,
    words: @Composable () -> Unit,
    button: @Composable (onClick: () -> Unit) -> Unit,
    rows: @Composable (tab: String) -> Unit,
    modifier: Modifier = Modifier,
    start: Dp = 44.dp,
    bottom: Dp = 44.dp,
) {
    val density = LocalDensity.current
    val inputMode = LocalInputModeManager.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val ff = LocalFrontFocus.current
    val narrow = LocalNarrowPane.current
    var open by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    var launching by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    var travel by remember { mutableStateOf(1) }
    // The control to focus once a sheet put back by a hop's B has lifted, instead of the first chip.
    var focusOnLift by remember { mutableStateOf<String?>(null) }
    val pull = remember { Animatable(0f) }
    val sink = remember { Animatable(0f) }
    val lift = remember { Animatable(0f) }
    val ride = remember { Animatable(0f) }
    val hot = remember { Animatable(1f) }
    val lipShow = remember { Animatable(0f) }
    val lipGone = remember { Animatable(0f) }
    val lipLit = remember { Animatable(0f) }
    val fold = remember { Animatable(0f) }
    val foldTop = remember { Animatable(0f) }
    val pieces = remember { List(PIECES) { Animatable(0f) } }
    val firstChip = remember { FocusRequester() }
    val playSrc = remember { MutableInteractionSource() }
    val playHovered by playSrc.collectIsHoveredAsState()
    var playFocused by remember { mutableStateOf(false) }
    var heroAt by remember { mutableStateOf(Offset.Zero) }
    var rest by remember { mutableStateOf<Rect?>(null) }
    var lit by remember { mutableStateOf(emptySet<String>()) }
    fun focusPlay() { if (ff != null && inputMode.inputMode == InputMode.Keyboard) runCatching { ff.primary.requestFocus() } }
    fun close() {
        if (!open) return
        // Focus leaves the rows before they go.
        focusPlay()
        open = false
    }
    // Back from a hop the sheet started (or a page it opened): put it back as it was.
    LaunchedEffect(Unit) {
        val r = Hops.restore ?: return@LaunchedEffect
        if (r.surface != HOP_SURFACE_SHEET || r.rail != key) return@LaunchedEffect
        Hops.restore = null
        tab = tabs.indexOfFirst { it.first == r.tab }.coerceAtLeast(0)
        focusOnLift = r.control
        open = true
    }
    // Launched: the session covers the screen, and the sheet is put away under it.
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
        if (open && !launching) {
            shown = true
            onOpen()
            fold.snapTo(0f); foldTop.snapTo(0f)
            coroutineScope {
                // Pull: the lip stretches down as the wall sinks back and dims.
                launch { pull.animateTo(24f, Motion.sp(0.7f, 380f)) }
                launch { sink.animateTo(1f, Motion.tw(260)) }
                // Lift: the sheet rises with Play riding its top edge; the lip lets go.
                launch {
                    delay(Motion.ms(120).toLong())
                    launch { lipGone.animateTo(1f, Motion.tw(140)) }
                    launch { pull.animateTo(0f, Motion.sp(1f, 500f)) }
                    launch { lift.animateTo(1f, Motion.sp(0.7f, 380f)) }
                    launch { ride.animateTo(1f, Motion.sp(0.7f, 380f)) }
                    launch { hot.snapTo(1f); delay(Motion.ms(100).toLong()); hot.animateTo(0f, Motion.tw(200)) }
                }
                pieces.forEachIndexed { i, p -> launch { delay(Motion.ms(260 + i * 55).toLong()); p.animateTo(1f, Motion.tw(220)) } }
                if (inputMode.inputMode == InputMode.Keyboard) launch {
                    val control = focusOnLift
                    focusOnLift = null
                    delay(Motion.ms(if (control != null) 420 else 300).toLong())
                    withFrameNanos { }
                    val target = control?.let { ff?.items?.get(it) } ?: firstChip
                    runCatching { target.requestFocus() }
                }
            }
        } else if (launching) {
            // Play from the sheet: the rows go, the sheet folds into the pill, then the launch.
            coroutineScope {
                pieces.forEach { launch { it.animateTo(0f, Motion.tw(90)) } }
                launch { delay(Motion.ms(60).toLong()); fold.animateTo(1f, Motion.sp(1f, 420f)) }
                launch { delay(Motion.ms(160).toLong()); foldTop.animateTo(1f, Motion.sp(1f, 380f)) }
            }
            shown = false
            onPrimary()
            // The session covers the screen and the sheet is put away under it (ON_STOP). If
            // nothing started, put it away anyway.
            delay(3_000)
            launching = false
        } else if (shown) {
            coroutineScope {
                pieces.forEach { launch { it.animateTo(0f, Motion.tw(90)) } }
                launch { delay(Motion.ms(60).toLong()); lift.animateTo(0f, Motion.sp(1f, 380f)) }
                launch { delay(Motion.ms(100).toLong()); ride.animateTo(0f, Motion.sp(0.55f, 300f)) }
                launch { sink.animateTo(0f, Motion.tw(300)) }
            }
            shown = false
            lipGone.snapTo(0f)
            hot.snapTo(1f)
        } else {
            // Snapped shut under a session: everything back at rest, no animation.
            listOf(pull, sink, lift, ride, lipGone, fold, foldTop).forEach { it.snapTo(0f) }
            pieces.forEach { it.snapTo(0f) }
            hot.snapTo(1f)
        }
    }
    BackHandler(enabled = open && !launching) { close() }

    // The lip: there while Play has a pad or a pointer on it, or always with touch alone.
    val touchOnly = inputMode.inputMode == InputMode.Touch
    val lipWanted = !open && !shown && (playFocused || playHovered || touchOnly)
    val lipNow = rememberUpdatedState(lip)
    LaunchedEffect(lipWanted) {
        if (lipWanted) {
            delay(Motion.ms(160).toLong())
            val now = lipNow.value()
            lit = lipChanged(LipMemory.seen[key], now)
            LipMemory.seen[key] = now.toMap()
            coroutineScope {
                launch { lipShow.animateTo(1f, Motion.tw(220)) }
                if (lit.isNotEmpty()) launch { lipLit.snapTo(1f); lipLit.animateTo(0f, Motion.tw(600)) }
            }
        } else {
            delay(Motion.ms(140).toLong())
            lipShow.animateTo(0f, Motion.tw(220))
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { heroAt = it.boundsInRoot().topLeft }) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val inset = with(density) { SheetInset.toPx() }
        val restRect = rest?.translate(-heroAt)
        val buttonH = restRect?.height ?: with(density) { 56.dp.toPx() }
        val openTop = sheetTop(h, buttonH, with(density) { 16.dp.toPx() }, with(density) { 3.dp.toPx() })
        // Play's raised place: its centre 3dp under the sheet's edge, same left inset.
        val raise = restRect?.let { (openTop + with(density) { 3.dp.toPx() } - buttonH / 2f) - it.top } ?: 0f

        // The wall behind sinks back about the pane's centre.
        Box(Modifier.fillMaxSize().graphicsLayer { val k = 1f - 0.05f * sink.value; scaleX = k; scaleY = k }) { background() }
        // The dim; a tap on it (above the sheet) folds the sheet away.
        if (shown || open) Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = sink.value }.background(Color.Black.copy(alpha = 0.42f))
                .focusProperties { canFocus = false }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = open && !launching) { close() },
        )

        // The sheet: inset at the sides, rounded at the top, no bottom edge - the pane's foot is its foot.
        if (shown) {
            val top = lerp(h, openTop, lift.value)
            val sheetLeft = inset
            val sheetRight = w - inset
            val path = remember { Path() }
            val rimPath = remember { Path() }
            val corner = with(density) { SheetCorner.toPx() }
            val rim = with(density) { 1.4.dp.toPx() }
            val pill = restRect?.translate(0f, raise)
            Canvas(Modifier.fillMaxSize()) {
                val f = fold.value
                val ft = foldTop.value
                val l = if (pill != null) lerp(sheetLeft, pill.left, f) else sheetLeft
                val r = if (pill != null) lerp(sheetRight, pill.right, f) else sheetRight
                val t = if (pill != null) lerp(top, pill.top, ft) else top
                val b = if (pill != null) lerp(h + corner, pill.bottom, ft) else h + corner
                val c = if (pill != null) lerp(corner, with(density) { 12.dp.toPx() }, ft) else corner
                val bc = if (ft > 0f) c else 0f
                path.reset()
                path.addRoundRect(RoundRect(l, t, r, b, CornerRadius(c), CornerRadius(c), CornerRadius(bc), CornerRadius(bc)))
                val fill = lerp(colors.surface, pal.signal, 0.22f * hot.value)
                drawPath(path, fill)
                val rimColor = pal.signal.copy(alpha = lerp(0.55f, 0.9f, hot.value))
                if (ft > 0f) drawPath(path, rimColor, style = Stroke(rim))
                else {
                    // Sides and top only: the sheet runs on under the pane's foot.
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
            // What the sheet holds: the chips to the right of Play, the rows under them.
            val chipsX = with(density) {
                if (narrow || pill == null) 16.dp.toPx() else pill.right + 20.dp.toPx() - sheetLeft
            }
            val chipsY = with(density) { if (narrow && pill != null) (pill.bottom - openTop) + 10.dp.toPx() else ChipsTop.toPx() }
            val rowsY = with(density) { if (narrow && pill != null) chipsY + 54.dp.toPx() else RowsTop.toPx() }
            val sheetW = sheetRight - sheetLeft
            Box(
                Modifier
                    .offset { IntOffset(sheetLeft.roundToInt(), top.roundToInt()) }
                    .size(with(density) { sheetW.toDp() }, with(density) { (h - openTop).coerceAtLeast(0f).toDp() })
                    // Touches on the sheet stay on it; none reach the dim behind.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .controllerBack { close() }
                    .bumpers(
                        onPrevious = { if (tab > 0) { travel = -1; tab-- } },
                        onNext = { if (tab < tabs.lastIndex) { travel = 1; tab++ } },
                    )
                    // A pad stays in the sheet until it folds; Left from it reaches Play, the handle.
                    .focusProperties { exit = { dir -> if (dir == FocusDirection.Left && ff != null) ff.primary else FocusRequester.Cancel } }
                    .focusGroup(),
            ) {
                Box(
                    Modifier
                        .offset { IntOffset(chipsX.roundToInt(), chipsY.roundToInt()) }
                        .width(with(density) { (sheetW - chipsX - 16.dp.toPx()).coerceAtLeast(0f).toDp() })
                        .graphicsLayer { val v = pieces[0].value; alpha = v; translationY = (1f - v) * 6.dp.toPx() }
                        // A pull down on the chips folds the sheet; so does Up from them.
                        .pointerInput(Unit) {
                            var dy = 0f
                            detectVerticalDragGestures(onDragStart = { dy = 0f }) { change, amount ->
                                dy += amount
                                if (dy > 24.dp.toPx()) { change.consume(); close(); dy = Float.NEGATIVE_INFINITY }
                            }
                        }
                        .onPreviewKeyEvent { e ->
                            val k = e.nativeKeyEvent
                            if (k.keyCode == KeyEvent.KEYCODE_DPAD_UP) { if (k.action == KeyEvent.ACTION_DOWN) close(); true } else false
                        },
                ) {
                    SheetChips(tabs.map { it.second }, tab, firstChip) { i -> travel = if (i > tab) 1 else -1; tab = i }
                }
                Box(
                    Modifier
                        .offset { IntOffset(0, rowsY.roundToInt()) }
                        .fillMaxWidth()
                        .height(with(density) { (h - openTop - rowsY).coerceAtLeast(0f).toDp() }),
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
                                    // Focus never lands in rows on their way out.
                                    .focusProperties { canFocus = !leavingNow.value }
                                    .verticalScroll(rememberScrollState())
                                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                            ) { rows(tabs[t].first) }
                        }
                    }
                }
            }
        }

        // The words and the button, where they always were; the button rides the sheet's edge.
        Column(Modifier.align(Alignment.BottomStart).padding(start = start, bottom = bottom, end = 20.dp)) {
            Box(Modifier.graphicsLayer { alpha = 1f - sink.value }) { words() }
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .onGloballyPositioned { rest = it.boundsInRoot() }
                    .graphicsLayer { translationY = raise * ride.value }
                    .onFocusChanged { playFocused = it.hasFocus }
                    .hoverable(playSrc)
                    // A pull up from Play opens the sheet.
                    .pointerInput(Unit) {
                        var dy = 0f
                        detectVerticalDragGestures(onDragStart = { dy = 0f }) { change, amount ->
                            dy += amount
                            if (dy < -24.dp.toPx() && !open) { change.consume(); open = true; dy = 0f }
                        }
                    }
                    .onPreviewKeyEvent { e ->
                        val k = e.nativeKeyEvent
                        if (k.keyCode != KeyEvent.KEYCODE_DPAD_DOWN) false
                        else {
                            if (k.action == KeyEvent.ACTION_DOWN && k.repeatCount == 0) {
                                if (!open) open = true else runCatching { firstChip.requestFocus() }
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
        }

        // The lip, under Play: stretched by the pull, gone as the sheet lifts.
        val r0 = restRect
        if (r0 != null && (lipShow.value > 0.01f || pull.value > 0.5f)) {
            val lipH = 26.dp + pull.value.dp
            Box(
                Modifier
                    .offset { IntOffset((r0.left + 16.dp.toPx()).roundToInt(), (r0.bottom + raise * ride.value + (lipShow.value - 1f) * 8.dp.toPx()).roundToInt()) }
                    .graphicsLayer { alpha = lipShow.value * (1f - lipGone.value) }
                    // 44dp to touch; the tab itself is the top 26.
                    .heightIn(min = 44.dp)
                    .pointerInput(Unit) {
                        var dy = 0f
                        detectVerticalDragGestures(onDragStart = { dy = 0f }) { change, amount ->
                            dy += amount
                            if (dy < -24.dp.toPx() && !open) { change.consume(); open = true; dy = 0f }
                        }
                    }
                    .focusProperties { canFocus = false }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { if (!open) open = true },
            ) {
                Lip(lip(), lit, lipLit.value, lipH)
            }
        }
    }
}

/** The lip's tab: what Play will do, a part lit while it says something new. */
@Composable
private fun Lip(parts: List<Pair<String, String>>, lit: Set<String>, glow: Float, height: Dp) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(height).clip(shape).background(colors.surface)
            .drawBorderSidesAndFoot(pal.line, 14.dp)
            .padding(start = 12.dp, end = 8.dp),
    ) {
        parts.forEachIndexed { i, (k, v) ->
            if (i > 0) Text(" · ", fontSize = 12.sp, color = colors.onSurfaceVariant)
            Text(
                v, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .background(if (k in lit) pal.signal.copy(alpha = 0.25f * glow) else Color.Transparent)
                    .padding(horizontal = 2.dp),
            )
        }
        Icon(Icons.Filled.KeyboardArrowDown, null, tint = colors.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp).size(16.dp))
    }
}

/** A 1dp outline down the sides and along the foot, none along the top (the lip tucks under Play). */
private fun Modifier.drawBorderSidesAndFoot(color: Color, corner: Dp): Modifier =
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
        drawPath(p, color, style = Stroke(sw))
    }

/**
 * The sheet's chips: equal capsules with one selection gliding under them (the stores' chip row),
 * LB and RB a chip at a time, a tap or A on a far one a hop as a drop. [first] takes focus as the
 * sheet opens. Too narrow for equal chips, the row scrolls.
 */
@Composable
internal fun SheetChips(labels: List<String>, selected: Int, first: FocusRequester? = null, onPick: (Int) -> Unit) {
    val pal = LocalPalette.current
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    val tint = rememberUpdatedState(pal.signal)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Equal chips while every label fits one; otherwise each its own width, and the row scrolls.
        val equal = maxWidth / labels.size.coerceAtLeast(1) >= 96.dp
        val scroll = rememberScrollState()
        Box(if (equal) Modifier.fillMaxWidth() else Modifier.horizontalScroll(scroll)) {
            ChipGlide(selected, bounds[selected], tint)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = if (equal) Modifier.fillMaxWidth() else Modifier) {
                labels.forEachIndexed { i, label ->
                    val m = (if (equal) Modifier.weight(1f) else Modifier.widthIn(min = 64.dp))
                        .onGloballyPositioned { bounds[i] = it.boundsInParent() }
                        .then(if (i == selected && first != null) Modifier.focusRequester(first) else Modifier)
                    SheetChip(label, i == selected, m) { onPick(i) }
                }
            }
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
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.heightIn(min = 44.dp).clip(shape)
            .background(if (on) Color.Transparent else colors.surfaceVariant.copy(alpha = 0.5f))
            .glideBorder(hot, shape, pal.signal, if (on) Color.Transparent else pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Tab, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (on || hot) colors.onBackground else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A read-only line that is a link: [title], [value] under it, and "[link] ↗" at the right in the
 * signal blue - not a picker, so no value box. A (or a tap) follows it. [id] is its focus key.
 */
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
