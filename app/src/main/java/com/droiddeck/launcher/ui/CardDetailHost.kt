package com.droiddeck.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/** A grid of cards with a game page that floods out of the opened card and drains back into it. */
internal val LocalDetailFloor = compositionLocalOf<() -> Float> { { Float.MAX_VALUE } }

/** How far the host reaches past the page's padding, so a game page can paint to the screen edge and still inset its content. */
internal class DetailInset(val horizontal: Dp, val top: Dp)
internal val LocalDetailInset = compositionLocalOf { DetailInset(0.dp, 0.dp) }

private fun Modifier.bleed(horizontal: Dp, top: Dp): Modifier =
    if (horizontal == 0.dp && top == 0.dp) this else layout { measurable, constraints ->
        val h = horizontal.roundToPx()
        val t = top.roundToPx()
        val placeable = measurable.measure(
            Constraints.fixed(constraints.maxWidth + h * 2, if (constraints.hasBoundedHeight) constraints.maxHeight + t else constraints.minHeight),
        )
        layout(constraints.maxWidth, placeable.height - t) { placeable.place(-h, -t) }
    }

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun CardDetailHost(
    open: String?,
    detailScroll: ScrollState,
    gridScrolled: () -> Boolean,
    onDetailFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    bleedHorizontal: Dp = 0.dp,
    bleedTop: Dp = 0.dp,
    wrap: @Composable (key: String, content: @Composable () -> Unit) -> Unit = { _, content -> content() },
    grid: @Composable () -> Unit,
    detail: @Composable (key: String) -> Unit,
) {
    val ctx = LocalContext.current
    val ff = LocalFrontFocus.current
    val density = LocalDensity.current
    var shown by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf<Origin?>(null) }
    var art by remember { mutableStateOf<String?>(null) }
    var viewportAt by remember { mutableStateOf(Offset.Zero) }
    var viewportH by remember { mutableStateOf(0) }
    LaunchedEffect(open) {
        if (open != null) {
            val mark = StoresMotion.takeCard(open)
            from = mark?.let { Origin(it.bounds.translate(-viewportAt), it.corner) }
            art = mark?.art
            StoresMotion.heroLands = mark != null
            leaving = false
            shown = open
        } else if (shown != null) {
            if (from != null) {
                leaving = true
                delay(Motion.ms(PAGE_RETURN_MS).toLong())
            }
            shown = null
            leaving = false
            from = null
        }
    }
    LaunchedEffect(shown) { detailScroll.scrollTo(0) }
    val covering = shown != null && !leaving
    val coveringNow = rememberUpdatedState(covering)
    val gridScale by animateFloatAsState(if (covering) 0.95f else 1f, if (covering) Motion.tw(480) else Motion.tw(460, 40), label = "gridSink")
    val gridAlpha by animateFloatAsState(if (covering) 0f else 1f, if (covering) Motion.tw(320, 80) else Motion.tw(300, 100), label = "gridFade")
    val fade = with(density) { 14.dp.toPx() }
    Box(
        modifier.bleed(bleedHorizontal, bleedTop).clipToBounds()
            .onGloballyPositioned { viewportAt = it.positionInRoot(); viewportH = it.size.height }
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val scrolled = if (shown != null) detailScroll.value > 0 else gridScrolled()
                if (scrolled && size.height > fade) drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, fade / size.height to Color.Black),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            },
    ) {
        CompositionLocalProvider(LocalFrontFocus provides if (covering) null else ff) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = bleedHorizontal).padding(top = bleedTop)
                    .graphicsLayer { scaleX = gridScale; scaleY = gridScale; alpha = gridAlpha }
                    .focusProperties { enter = { if (coveringNow.value) FocusRequester.Cancel else FocusRequester.Default } }
                    .focusGroup(),
            ) { grid() }
        }
        shown?.let { key ->
            wrap(key) {
                val body: @Composable () -> Unit = {
                    Column(
                        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }
                            .verticalScroll(detailScroll).padding(bottom = 16.dp),
                    ) {
                        Column(
                            Modifier.fillMaxWidth()
                                .onFocusChanged { onDetailFocus(it.hasFocus) }
                                .focusProperties { exit = { if (coveringNow.value && Steps.step == null && StoresMotion.step == null) FocusRequester.Cancel else FocusRequester.Default } }
                                .focusGroup(),
                        ) {
                            val floor = { viewportAt.y + viewportH - with(density) { 16.dp.toPx() } - detailScroll.value }
                            CompositionLocalProvider(LocalDetailFloor provides floor, LocalDetailInset provides DetailInset(bleedHorizontal, bleedTop + 4.dp)) { detail(key) }
                        }
                    }
                }
                val origin = from
                if (origin == null) body()
                else {
                    val painter = art?.let { url ->
                        coil.compose.rememberAsyncImagePainter(
                            remember(url) {
                                coil.request.ImageRequest.Builder(ctx).data(url).size(1280, 720)
                                    .placeholderMemoryCacheKey(url).build()
                            },
                        )
                    }
                    PageFlood(origin, leaving, painter, onto = { StoresMotion.heroAt }, ontoCorner = 16.dp) { body() }
                }
            }
        }
    }
}
