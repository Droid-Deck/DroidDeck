package com.droiddeck.launcher.ui

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

// The session's loading screen, its paused screen and the HUD text style.

@Composable
fun HudText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = Color.White,
            modifier = Modifier
                .padding(12.dp)
                .background(Color(0x8C000000))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** One line of the loading screen's checklist; [detail] is what it says while it is the one running. */
private class LoadStage(val label: String, val detail: String)

private fun loadStages(steam: Boolean): List<LoadStage> = if (steam) listOf(
    LoadStage("Linux runtime", "Getting the Linux runtime ready"),
    LoadStage("Session", "Starting the display and audio"),
    LoadStage("Steam client", "Checking the Steam client and its compatibility tools"),
    LoadStage("Opening Steam", "Waiting for Steam's first frame"),
) else listOf(
    LoadStage("Linux runtime", "Getting the Linux runtime ready"),
    LoadStage("Desktop", "Getting the desktop ready"),
    LoadStage("Opening", "Waiting for the first frame"),
)

/**
 * The checklist stage a loading line belongs to, -1 when it says nothing about the stage. The lines
 * are the session script's "== STEP" milestones and the installer's own progress; they arrive in
 * order, and the screen never steps back.
 */
private fun stageOf(step: String, steam: Boolean): Int {
    val t = step.lowercase()
    return when {
        "linux runtime" in t -> 0
        "starting the session" in t -> 1
        !steam && "desktop" in t -> 1
        steam && "starting the steam client" in t -> 3
        steam && ("steam" in t || "client" in t || "proton" in t || "library" in t || "compatibility" in t) -> 2
        else -> -1
    }
}

/** Download and install lines are worth reading as they are; the script's own notes are not. */
private fun readableStep(step: String): Boolean {
    val t = step.lowercase()
    return t.startsWith("download") || t.startsWith("checking the linux") || t.startsWith("unpacking") || t.startsWith("installing")
}

@Composable
fun LoadingOverlay(
    step: String, percent: Int, elapsed: String, hint: String, ended: Boolean,
    title: String = "Starting Steam", steam: Boolean = true, onCancel: (() -> Unit)? = null,
    /** An ended session's exit status and log path, shown small under the advice. */
    endedDetail: String? = null,
    onRetry: (() -> Unit)? = null,
    onShareLogs: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    /** The blue the page opens on when a button flooded to it, gathered into the throbber's ball. */
    flood: Color? = null,
    onFlooded: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    var ball by remember { mutableStateOf<Pair<Offset, Float>?>(null) }
    val stages = remember(steam) { loadStages(steam) }
    var reached by remember { mutableStateOf(0) }
    LaunchedEffect(step, steam) {
        val at = stageOf(step, steam)
        if (at > reached) reached = at
    }
    val active = reached.coerceAtMost(stages.lastIndex)
    val detail = if (percent >= 0 || readableStep(step)) step.replaceFirstChar { it.uppercase() } else stages[active].detail
    val context = LocalContext.current
    // The Steam tab's wall behind it, dimmed and slowed: starting Steam reads as the same place settling in.
    val games by produceState(emptyList<Library.SteamGame>()) {
        value = withContext(Dispatchers.IO) { runCatching { Library.steamGames(context).sortedByDescending { it.lastPlayed } }.getOrDefault(emptyList()) }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        CapsuleWall(games, driftMs = 65_000, modifier = Modifier.alpha(0.3f))
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
            // Held on the plain D while the flood gathers, and started fresh when it lands.
            key(flood == null) {
                LogoThrobber(
                    Modifier.width(if (ended) 88.dp else 110.dp).onGloballyPositioned { ball = throbberBall(it.boundsInRoot()) },
                    running = !ended && flood == null,
                )
            }
            Spacer(Modifier.height(30.dp))
            Text(
                if (ended) "The session ended" else title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
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
            if (ended) EndedActions(onRetry, onShareLogs, onClose)
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

/** What to do about an ended session: nothing leaves on a timer, and the pad starts on Try again. */
@Composable
private fun EndedActions(onRetry: (() -> Unit)?, onShareLogs: (() -> Unit)?, onClose: (() -> Unit)?) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 22.dp)) {
        var firstUsed = false
        fun claim(): Modifier = if (firstUsed) Modifier else { firstUsed = true; Modifier.focusRequester(first) }
        if (onRetry != null) PrimaryButton("Try again", modifier = claim(), onClick = onRetry)
        if (onShareLogs != null) SecondaryButton("Share logs", modifier = claim(), onClick = onShareLogs)
        if (onClose != null) SecondaryButton("Back", modifier = claim(), onClick = onClose)
    }
}

/** One segment per stage: done ones filled, the current one filling (or running, with no percent). */
@Composable
private fun LoadSegments(count: Int, active: Int, percent: Int, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    val track = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f)
    val run = rememberInfiniteTransition(label = "segment")
        .animateFloat(-0.4f, 1f, infiniteRepeatable(tween(Motion.ms(1300).coerceAtLeast(1), easing = FastOutSlowInEasing)), label = "run")
    val still = Motion.scale == 0f
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.semantics { contentDescription = "Step ${active + 1} of $count" },
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

/** Cancel as the pad's B, in the corner; a tap works too. */
@Composable
private fun CancelHint(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsHoveredAsState().value || src.collectIsFocusedAsState().value
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp))
            .background(if (hot) colors.onBackground.copy(alpha = 0.08f) else Color.Transparent)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onCancel)
            .padding(horizontal = 12.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp).clip(CircleShape).background(colors.onBackground)) {
            Text("B", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.background)
        }
        Text("Cancel", fontSize = 13.sp, color = colors.onSurfaceVariant)
    }
}

/** A session paused in the background, ready to pick up where it left off. */
@Composable
fun SessionPausedOverlay(title: String = "Steam is paused", onResume: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val resumeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { resumeFocus.requestFocus() }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pal.background.copy(alpha = 0.94f))
            .clickable(interactionSource = interactionSource, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.widthIn(max = 440.dp).padding(32.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(bottom = 6.dp).size(64.dp).border(2.dp, pal.line2, CircleShape)) {
                Icon(Icons.Filled.Pause, contentDescription = null, tint = colors.onBackground, modifier = Modifier.size(30.dp))
            }
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, textAlign = TextAlign.Center)
            PrimaryButton(
                "Resume",
                modifier = Modifier.padding(top = 14.dp).focusRequester(resumeFocus).controllerConfirm(onClick = onResume),
                onClick = onResume,
            )
        }
    }
}
