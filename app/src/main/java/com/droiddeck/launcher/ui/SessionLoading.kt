package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.LoadingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow


@Composable
fun HudText(text: String, graph: FloatArray = FloatArray(0), color: String = "mint", opacity: Int = 70) {
    if (text.isEmpty() && graph.size < 2) return
    val accent = hudInk(color)
    val wash = (opacity.coerceIn(30, 100) / 100f)
    val ink = accent.copy(alpha = (0.5f + wash * 0.5f).coerceIn(0.62f, 1f))
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .padding(12.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = wash * 0.28f), Color.Black.copy(alpha = wash * 0.72f)),
                    ),
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            if (text.isNotEmpty()) Text(
                text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = ink,
                textAlign = TextAlign.End,
            )
            if (graph.size >= 2) {
                if (text.isNotEmpty()) Spacer(Modifier.height(4.dp))
                HudGraph(graph, accent, wash)
            }
        }
    }
}

@Composable
private fun HudGraph(samples: FloatArray, accent: Color, wash: Float) {
    Canvas(Modifier.width(148.dp).height(44.dp)) {
        val maxMs = (samples.maxOrNull() ?: 16.7f).coerceAtLeast(16.7f)
        val step = size.width / (samples.size - 1)
        fun yOf(ms: Float) = size.height * (1f - (ms / maxMs).coerceIn(0f, 1f))
        val fillAlpha = wash * 0.5f
        val fill = Path().apply {
            moveTo(0f, size.height)
            samples.forEachIndexed { i, ms -> lineTo(i * step, yOf(ms)) }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(
            fill,
            Brush.verticalGradient(listOf(HUD_SLOW.copy(alpha = fillAlpha), accent.copy(alpha = fillAlpha * 0.45f))),
        )
        val guide = yOf(16.7f)
        drawLine(Color.White.copy(alpha = 0.32f), Offset(0f, guide), Offset(size.width, guide), strokeWidth = 1f)
        for (i in 0 until samples.lastIndex) {
            val tone = ((samples[i] - 8f) / 25f).coerceIn(0f, 1f)
            drawLine(
                mix(accent, HUD_SLOW, tone).copy(alpha = 0.55f + wash * 0.4f),
                Offset(i * step, yOf(samples[i])),
                Offset((i + 1) * step, yOf(samples[i + 1])),
                strokeWidth = 2.5f,
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun hudInk(color: String): Color = Color(
    when (color) {
        "white" -> 0xFFF2F5F8
        "sky" -> 0xFF66C0F4
        "amber" -> 0xFFFFB74D
        "violet" -> 0xFFD0A2FF
        else -> 0xFF3DDC84
    }.toInt(),
)

private fun mix(from: Color, to: Color, t: Float): Color {
    val u = t.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * u,
        green = from.green + (to.green - from.green) * u,
        blue = from.blue + (to.blue - from.blue) * u,
        alpha = 1f,
    )
}

private val HUD_SLOW = Color(0xFFFF5C6C.toInt())

private class LoadStage(@androidx.annotation.StringRes val label: Int, @androidx.annotation.StringRes val detail: Int)

private fun loadStages(steam: Boolean): List<LoadStage> = if (steam) listOf(
    LoadStage(R.string.load_runtime, R.string.load_runtime_detail),
    LoadStage(R.string.load_session, R.string.load_session_detail),
    LoadStage(R.string.load_steam, R.string.load_steam_detail),
    LoadStage(R.string.load_opening_steam, R.string.load_opening_steam_detail),
) else listOf(
    LoadStage(R.string.load_runtime, R.string.load_runtime_detail),
    LoadStage(R.string.load_desktop, R.string.load_desktop_detail),
    LoadStage(R.string.load_opening, R.string.load_opening_detail),
)

private fun stageOf(topic: LoadingState.Topic, steam: Boolean): Int = when (topic) {
    LoadingState.Topic.RUNTIME -> 0
    LoadingState.Topic.SESSION -> 1
    LoadingState.Topic.DESKTOP -> if (steam) -1 else 1
    LoadingState.Topic.STEAM_STARTING -> if (steam) 3 else -1
    LoadingState.Topic.STEAM -> if (steam) 2 else -1
    LoadingState.Topic.OTHER -> -1
}

@Composable
fun LoadingOverlay(
    step: String, percent: Int, elapsed: String, hint: String, ended: Boolean,
    topic: LoadingState.Topic, readable: Boolean,
    title: String = stringResource(R.string.load_starting_steam), steam: Boolean = true, onCancel: (() -> Unit)? = null,
    endedDetail: String? = null,
    onRetry: (() -> Unit)? = null,
    onRepair: (() -> Unit)? = null,
    onShareLogs: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    flood: Color? = null,
    onFlooded: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    var ball by remember { mutableStateOf<Pair<Offset, Float>?>(null) }
    val stages = remember(steam) { loadStages(steam) }
    var reached by remember { mutableStateOf(0) }
    LaunchedEffect(step, topic, steam) {
        val at = stageOf(topic, steam)
        if (at > reached) reached = at
    }
    val active = reached.coerceAtMost(stages.lastIndex)
    val detail = if (percent >= 0 || readable) step.replaceFirstChar { it.uppercase() } else stringResource(stages[active].detail)
    val context = LocalContext.current
    val games by produceState(emptyList<Library.SteamGame>()) {
        value = withContext(Dispatchers.IO) { runCatching { Library.steamGames(context).sortedByDescending { it.lastPlayed } }.getOrDefault(emptyList()) }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .blockTouches(),
    ) {
        CapsuleWall(games, driftMs = 65_000, modifier = Modifier.alpha(0.3f), pauseWhenIdle = false)
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.radialGradient(
                        0f to colors.background.copy(alpha = 0.94f),
                        0.6f to colors.background.copy(alpha = 0.72f),
                        1f to colors.background.copy(alpha = 0.55f),
                        center = Offset(size.width / 2f, size.height * 0.46f), radius = maxOf(size.width, size.height) * 0.55f,
                    ),
                )
            },
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.7f to Color.Transparent, 1f to colors.background.copy(alpha = 0.95f))))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center).verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            key(flood == null) {
                LogoThrobber(
                    Modifier.width(if (ended) 88.dp else 110.dp).onGloballyPositioned { ball = throbberBall(it.boundsInRoot()).also { b -> ThrobberSpot.ball = b } },
                    running = !ended && flood == null,
                )
            }
            Spacer(Modifier.height(30.dp))
            Text(
                if (ended) stringResource(R.string.load_ended) else title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                color = colors.onBackground, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (ended) step else detail, fontSize = 14.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
                maxLines = if (ended) 12 else 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp).widthIn(max = 520.dp),
            )
            if (!ended) LoadSegments(stages.size, active, percent, Modifier.padding(top = 18.dp))
            if (ended && endedDetail != null) Text(
                endedDetail, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp).widthIn(max = 520.dp),
            )
            if (ended) EndedActions(onRetry, onRepair, onShareLogs, onClose)
        }
        if (!ended && hint.isNotEmpty()) Text(
            hint, fontSize = 13.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant.copy(alpha = 0.8f), textAlign = TextAlign.Center,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 120.dp, end = 120.dp, bottom = 28.dp),
        )
        if (!ended && onCancel != null) CancelHint(onCancel, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp))
        if (flood != null) FloodGather(flood, ball, onFlooded)
    }
}

@Composable
private fun EndedActions(onRetry: (() -> Unit)?, onRepair: (() -> Unit)?, onShareLogs: (() -> Unit)?, onClose: (() -> Unit)?) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 22.dp)) {
        var firstUsed = false
        fun claim(): Modifier = if (firstUsed) Modifier else { firstUsed = true; Modifier.focusRequester(first) }
        if (onRepair != null) PrimaryButton(stringResource(R.string.runtime_repair), modifier = claim(), onClick = onRepair)
        if (onRetry != null) {
            if (onRepair != null) SecondaryButton(stringResource(R.string.store_try_again), modifier = claim(), onClick = onRetry)
            else PrimaryButton(stringResource(R.string.store_try_again), modifier = claim(), onClick = onRetry)
        }
        if (onShareLogs != null) SecondaryButton(stringResource(R.string.drawer_share_logs), modifier = claim(),
            progress = com.droiddeck.launcher.session.SessionLogShare.progress, onClick = onShareLogs)
        if (onClose != null) SecondaryButton(stringResource(R.string.mode_back), modifier = claim(), onClick = onClose)
    }
}

@Composable
private fun LoadSegments(count: Int, active: Int, percent: Int, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    val track = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f)
    val run = rememberInfiniteTransition(label = "segment")
        .animateFloat(-0.4f, 1f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing)), label = "run")
    val still = Motion.scale == 0f
    val stepDescription = stringResource(R.string.load_step, active + 1, count)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.semantics { contentDescription = stepDescription },
    ) {
        repeat(count) { i ->
            Box(
                Modifier.width(44.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (i < active) pal.signal else track)
                    .drawBehind {
                        if (i != active) return@drawBehind
                        if (percent >= 0) drawRect(pal.signal, size = Size(size.width * percent.coerceIn(0, 100) / 100f, size.height))
                        else if (still) drawRect(pal.signal.copy(alpha = 0.5f))
                        else drawRect(pal.signal, topLeft = Offset(size.width * run.value, 0f), size = Size(size.width * 0.4f, size.height))
                    },
            )
        }
    }
}

@Composable
private fun CancelHint(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsHoveredAsState().value || src.collectIsFocusedAsState().value
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp))
            .background(if (hot) colors.onBackground.copy(alpha = 0.08f) else Color.Transparent)
            .glideBorder(hot, RoundedCornerShape(22.dp), LocalPalette.current.signal)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onCancel)
            .padding(horizontal = 12.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp).clip(CircleShape).background(colors.onBackground)) {
            Text("B", color = colors.background, style = TextStyle(
                fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ))
        }
        Text(stringResource(R.string.common_cancel), fontSize = 13.sp, color = colors.onSurfaceVariant)
    }
}
